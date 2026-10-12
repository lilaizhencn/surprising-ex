package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.RegisterInstrumentCommand;
import com.surprising.aeron.service.state.instrument.CoreInstrument;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TreasuryProgressOwnershipTest {
    private static final CoreInstrument INSTRUMENT = CoreInstrument.from(ProductLine.LINEAR_PERPETUAL,
            new RegisterInstrumentCommand("1", ContractType.LINEAR_PERPETUAL.ordinal(),
                    "BTC", "USDT", "USDT", 1, 1, 1, 100_000, 50_000, 0, 0, 0, -1, 0));
    private static final UUID COMMAND_ID = UUID.fromString("00000000-0000-0000-0000-000000000701");

    @Test
    void admissionReadsOriginalProgressAfterLedgerHandoffButCannotAccessMoneyOrWriteProgress() throws Exception {
        TreasuryRuntime ledger = new TreasuryRuntime();
        var funding = funding(1);
        var lifecycle = lifecycle(2);
        ledger.setFundingProgress(0, funding);
        ledger.setLifecycleProgress(0, lifecycle);
        ledger.clearChangedKeys();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try (var lane = new SystemLedgerLane(ledger, 256, failure::set)) {
            assertThat(ledger.fundingProgress(0)).isSameAs(funding);
            assertThat(ledger.lifecycleProgress(0)).isSameAs(lifecycle);
            assertThat(ledger.incompleteFundingCount()).isEqualTo(1);
            assertThatThrownBy(() -> ledger.fee(0)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> ledger.insurance(0)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> ledger.setFundingSettlement(0, 3)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> ledger.setFundingProgress(0, funding(3))).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> ledger.setLifecycleSettlement(0, 4)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> ledger.setLifecycleProgress(0, lifecycle(4))).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(ledger::fundingProgresses).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(ledger::rollbackChangedValues).isInstanceOf(IllegalStateException.class);
            CountDownLatch completed = new CountDownLatch(1);
            assertThat(lane.offer(actual -> {
                actual.setFundingSettlement(0, 3);
                actual.setLifecycleSettlement(0, 4);
                actual.clearChangedKeys();
                completed.countDown();
            })).isTrue();
            assertThat(completed.await(3, TimeUnit.SECONDS)).isTrue();
            lane.assertHealthy();
            assertThat(ledger.fundingSettlement(0)).isEqualTo(3);
            assertThat(ledger.lifecycleSettlement(0)).isEqualTo(4);
            assertThat(ledger.fundingProgress(0)).isNull();
            assertThat(ledger.lifecycleProgress(0)).isNull();
            assertThat(ledger.incompleteFundingCount()).isZero();
        }
        assertThat(failure.get()).isNull();
        assertThat(funding.settlementId()).isEqualTo(1);
        assertThat(lifecycle.settlementId()).isEqualTo(2);
    }

    @Test
    void concurrentAdmissionReadsRemainSafeAsLedgerAddsSymbolsAndReplacesProgress() throws Exception {
        TreasuryRuntime ledger = new TreasuryRuntime();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<Throwable> readerFailure = new AtomicReference<>();
        AtomicBoolean finished = new AtomicBoolean();
        CountDownLatch readerStarted = new CountDownLatch(1), firstPublished = new CountDownLatch(1);
        CountDownLatch firstRead = new CountDownLatch(1), completed = new CountDownLatch(1);
        Thread reader = new Thread(() -> {
            readerStarted.countDown();
            try {
                if (!firstPublished.await(3, TimeUnit.SECONDS)) throw new AssertionError("progress publication timeout");
                assertThat(ledger.fundingProgress(0)).isNotNull();
                firstRead.countDown();
                long lastSettlement = 0;
                do {
                    long current = ledger.fundingSettlement(0);
                    assertThat(current).isGreaterThanOrEqualTo(lastSettlement);
                    lastSettlement = current;
                    for (int symbol = 0; symbol < 64; symbol++) {
                        var value = ledger.fundingProgress(symbol);
                        if (value != null) {
                            assertThat(value.instrument()).isSameAs(INSTRUMENT);
                            assertThat(value.commandId()).isSameAs(COMMAND_ID);
                            assertThat(value.settlementId()).isPositive();
                        }
                        ledger.lifecycleProgress(symbol);
                        ledger.lifecycleSettlement(symbol);
                    }
                } while (!finished.get());
            } catch (Throwable problem) {
                readerFailure.set(problem);
                firstRead.countDown();
            }
        }, "admission-progress-reader");
        try (var lane = new SystemLedgerLane(ledger, 256, failure::set)) {
            reader.start();
            assertThat(readerStarted.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(lane.offer(actual -> {
                actual.setFundingProgress(0, funding(1));
                firstPublished.countDown();
                try {
                    if (!firstRead.await(3, TimeUnit.SECONDS)) throw new AssertionError("admission reader timeout");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
                for (int symbol = 1; symbol < 64; symbol++) {
                    actual.setFundingProgress(symbol, funding(symbol + 1));
                    actual.setLifecycleProgress(symbol, lifecycle(symbol + 1));
                    actual.setFundingSettlement(0, symbol);
                    actual.setFundingProgress(0, funding(symbol + 1));
                }
                assertThat(actual.fundingProgresses().size()).isEqualTo(64);
                assertThat(actual.lifecycleProgresses().size()).isEqualTo(63);
                actual.clearChangedKeys();
                completed.countDown();
            })).isTrue();
            assertThat(completed.await(3, TimeUnit.SECONDS)).isTrue();
            lane.assertHealthy();
            assertThat(ledger.fundingSettlement(0)).isEqualTo(63);
            assertThat(ledger.fundingProgress(63).settlementId()).isEqualTo(64);
            assertThat(ledger.incompleteFundingCount()).isEqualTo(64);
        } finally {
            finished.set(true);
            firstPublished.countDown();
            reader.join(3_000L);
        }
        assertThat(reader.isAlive()).isFalse();
        assertThat(readerFailure.get()).isNull();
        assertThat(failure.get()).isNull();
    }

    @Test
    void rollbackRestoresFirstProgressAndCompletedIdsForBothDomainsAndRemovesNewSymbols() {
        TreasuryRuntime ledger = new TreasuryRuntime();
        var funding = funding(3);
        var lifecycle = lifecycle(4);
        ledger.setFundingSettlement(0, 1);
        ledger.setLifecycleSettlement(0, 2);
        ledger.setFundingProgress(0, funding);
        ledger.setLifecycleProgress(0, lifecycle);
        ledger.clearChangedKeys();

        ledger.setFundingSettlement(0, 5);
        assertThat(ledger.lifecycleProgress(0)).isSameAs(lifecycle);
        ledger.setFundingProgress(0, funding(6));
        ledger.setLifecycleSettlement(0, 7);
        assertThat(ledger.fundingProgress(0).settlementId()).isEqualTo(6);
        ledger.setLifecycleProgress(0, lifecycle(8));
        ledger.setFundingProgress(32, funding(9));
        ledger.setLifecycleSettlement(32, 10);
        assertThat(ledger.incompleteFundingCount()).isEqualTo(2);
        ledger.rollbackChangedValues();

        assertThat(ledger.fundingSettlement(0)).isEqualTo(1);
        assertThat(ledger.lifecycleSettlement(0)).isEqualTo(2);
        assertThat(ledger.fundingProgress(0)).isSameAs(funding);
        assertThat(ledger.lifecycleProgress(0)).isSameAs(lifecycle);
        assertThat(ledger.fundingProgress(32)).isNull();
        assertThat(ledger.lifecycleSettlement(32)).isZero();
        assertThat(ledger.incompleteFundingCount()).isEqualTo(1);
        assertThat(ledger.fundingSettlements().size()).isEqualTo(1);
        assertThat(ledger.lifecycleSettlements().size()).isEqualTo(1);
        assertThat(ledger.hasChangedValues()).isFalse();
    }

    @Test
    void orderBatchCannotChangeProgressAndClearResetsAdmissionStatus() {
        TreasuryRuntime ledger = new TreasuryRuntime();
        ledger.setFundingProgress(0, funding(1));
        ledger.setLifecycleProgress(0, lifecycle(2));
        ledger.clearChangedKeys();
        ledger.beginOrderBatchMutationScope();
        assertThatThrownBy(() -> ledger.setFundingSettlement(0, 3)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ledger.setLifecycleSettlement(0, 4)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ledger.setFundingProgress(0, funding(3))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ledger.setLifecycleProgress(0, lifecycle(4))).isInstanceOf(IllegalStateException.class);
        ledger.endOrderBatchMutationScope();
        assertThat(ledger.hasChangedValues()).isFalse();
        assertThat(ledger.fundingProgress(0).settlementId()).isEqualTo(1);
        ledger.clear();
        assertThat(ledger.fundingProgress(0)).isNull();
        assertThat(ledger.lifecycleProgress(0)).isNull();
        assertThat(ledger.incompleteFundingCount()).isZero();
        assertThat(ledger.fundingProgresses().isEmpty()).isTrue();
        assertThat(ledger.lifecycleProgresses().isEmpty()).isTrue();
    }

    private static TreasuryRuntime.FundingProgressRuntime funding(long settlementId) {
        return new TreasuryRuntime.FundingProgressRuntime(settlementId, INSTRUMENT, 100,
                0, 7, COMMAND_ID, 100, 1);
    }

    private static TreasuryRuntime.LifecycleProgressRuntime lifecycle(long settlementId) {
        return new TreasuryRuntime.LifecycleProgressRuntime(settlementId, INSTRUMENT, 100,
                0, true, 0, 0, 7, COMMAND_ID);
    }
}
