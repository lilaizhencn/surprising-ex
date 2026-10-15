package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.service.state.index.*;
import com.surprising.product.api.ProductLine;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

@Timeout(15)
class LedgerPositionPublicationTest {
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void positionOnlyControlValidationRollsBackWithAndWithoutTreasuryScope(boolean treasuryScope) throws Exception {
        var product = ProductLine.LINEAR_PERPETUAL;
        var instrument = com.surprising.aeron.service.state.instrument.CoreInstrument.from(product,
                new com.surprising.aeron.protocol.RegisterInstrumentCommand("1",
                        com.surprising.instrument.api.model.ContractType.LINEAR_PERPETUAL.ordinal(), "BTC", "USDT", "USDT",
                        1, 1, 1, 100_000, 50_000, 0, 0, 0, -1, 0));
        var position = new com.surprising.aeron.service.state.model.CorePositionState("1", "USDT",
                com.surprising.aeron.protocol.CoreMarginMode.CROSS, CorePositionSide.NET, 10, 100, 1000, 0, 100);
        var user = new CoreUserState(product, 7, 1,
                java.util.Map.of("USDT", new com.surprising.aeron.service.state.model.AssetBalance("USDT", 900, 100)),
                java.util.Map.of(), java.util.Map.of("1", position));
        var initial = new TradingCoreState(product, 1, java.util.Map.of(7L, user), java.util.Map.of(),
                java.util.Map.of("1", instrument), com.surprising.aeron.service.state.model.CoreRiskState.empty(),
                CoreTreasuryState.empty());
        var ids = new RuntimeIdentityRegistry();
        var users = new PositionUserIndex(initial, ids);
        var oi = new OpenInterestIndex(initial, ids);
        var adl = new AdlPositionIndex(initial, ids);
        try (var runtime = RuntimeStateProjector.project(initial, ids)) {
            runtime.bindFactIndexes(indexes(initial, ids, users, oi, adl), ids);
            long key = ids.findPositionKeyValue(7, instrument, CorePositionSide.NET);
            int symbol = ids.symbolId("1");
            assertThat(runtime.publishedPositionCount()).isOne();
            runtime.startAccountLanes(); runtime.startSystemLedgerLane(); runtime.enterAsynchronousCommandScope();
            try {
                for (int round = 0; round < 2; round++) {
                    if (treasuryScope) {
                        var command = new SystemLedgerCommand() {
                            { initializeLedgerCommand(runtime); }
                            @Override protected void applyLedger(TreasuryRuntime ledger) { ledger.setFundingSettlement(symbol, 9); }
                        };
                        assertThat(runtime.dispatchLedgerCommand(command)).isTrue();
                        LedgerControlTestSupport.await(runtime, command::complete);
                    }
                    runtime.dispatchControlLanes(runtime.topology().accountLaneMask(7), lane -> {
                        runtime.removePosition(key, 7); return null;
                    });
                    LedgerControlTestSupport.await(runtime, runtime::pollControlLanes);
                    assertThat(runtime.publishedPositionCount()).isZero();
                    // No money patch exists: position-only controls must still reach Ledger validation.
                    LedgerControlTestSupport.awaitFunds(runtime, false);
                    assertThat(adl.count()).isZero();
                    LedgerControlTestSupport.await(runtime, runtime.beginCommandRollback(1, 2 + round));
                    assertThat(runtime.publishedPositionCount()).isOne();
                    onLedger(runtime, () -> {
                        assertThat(oi.openInterestSteps("1")).isEqualTo(10);
                        assertThat(users.users("1")).containsExactly(7L);
                        assertThat(adl.value(key).signedQuantitySteps()).isEqualTo(10);
                    });
                    assertThat(runtime.treasury().fundingSettlement(symbol)).isZero();
                }
            } finally { runtime.exitAsynchronousCommandScope(); }
        }
    }

    @Test void committedPrefixAndMinimalControlUndoStayOnLedger() throws Exception {
        var initial = TradingCoreState.empty(ProductLine.LINEAR_PERPETUAL);
        var ids = new RuntimeIdentityRegistry();
        var users = new PositionUserIndex(initial, ids);
        var oi = new OpenInterestIndex(initial, ids);
        var adl = new AdlPositionIndex(initial, ids);
        var indexes = indexes(initial, ids, users, oi, adl);
        try (var runtime = RuntimeStateProjector.project(initial, ids)) {
            runtime.bindFactIndexes(indexes, ids);
            runtime.startSystemLedgerLane();
            assertThatThrownBy(() -> oi.openInterestSteps("1")).hasMessageContaining("SystemLedgerLane");
            assertThatThrownBy(() -> users.higherUserId("1", 0)).hasMessageContaining("SystemLedgerLane");
            assertThatThrownBy(() -> adl.positions("USDT")).hasMessageContaining("SystemLedgerLane");
            var changes = new RuntimeIndexedChangeBuffer<PositionRuntime, RuntimePositionIndexValue>();
            onLedger(runtime, () -> {
                changes.putIndexed(101, null, true, position(1, CorePositionSide.LONG, 10));
                changes.putIndexed(102, null, true, position(2, CorePositionSide.SHORT, -10));
                indexes.applyPositions(changes, ids, null);
                assertThat(oi.openInterestSteps("1")).isEqualTo(10);
                changes.clear();
                changes.putIndexed(101, null, true, position(1, CorePositionSide.LONG, 15));
                changes.putIndexed(103, null, true, position(1, CorePositionSide.SHORT, -5));
                changes.putIndexed(102, null, true, position(2, CorePositionSide.SHORT, -20));
                indexes.applyPositions(changes, ids, null);
                assertThat(oi.openInterestSteps("1")).isEqualTo(25);
                assertThat(users.users("1")).containsExactly(1L, 2L);
                changes.clear();
            });
            assertThat(adl.count()).isEqualTo(3); // Published technical count needs no map read.
            var undo = new RuntimeFactIndexes.PositionUndo();
            onLedger(runtime, () -> {
                changes.putIndexed(101, null, true, position(1, CorePositionSide.LONG, -30));
                changes.putIndexed(102, null, true, null);
                indexes.applyPositions(changes, ids, undo);
                assertThat(oi.openInterestSteps("1")).isEqualTo(35);
                assertThat(users.users("1")).containsExactly(1L);
                // The old ADL cell was mutated in place; undo must hold primitive old values.
                undo.rollback(indexes);
                assertThat(oi.longQuantity("1")).isEqualTo(15);
                assertThat(oi.shortQuantity("1")).isEqualTo(25);
                assertThat(adl.value(101).signedQuantitySteps()).isEqualTo(15);
                assertThat(adl.value(102).signedQuantitySteps()).isEqualTo(-20);
                assertThat(users.users("1")).containsExactly(1L, 2L);
            });
        }
        assertThat(oi.openInterestSteps("1")).isEqualTo(25);
    }

    @Test void ordinaryQuantityOverflowDoesNotPartiallyWriteATotalsCell() {
        var initial = TradingCoreState.empty(ProductLine.LINEAR_PERPETUAL);
        var oi = new OpenInterestIndex(initial);
        oi.apply(null, position(1, CorePositionSide.NET, Long.MAX_VALUE));
        assertThatThrownBy(() -> oi.apply(null, position(2, CorePositionSide.NET, 1)))
                .isInstanceOf(ArithmeticException.class);
        assertThat(oi.openInterestSteps("1")).isEqualTo(Long.MAX_VALUE);
    }

    private static RuntimeFactIndexes indexes(TradingCoreState initial, RuntimeIdentityRegistry ids,
            PositionUserIndex users, OpenInterestIndex oi, AdlPositionIndex adl) {
        return new RuntimeFactIndexes(users, oi, new TriggerOrderIndex(initial), new AlgoOrderIndex(initial),
                new LiquidationIndex(initial), new CancelAllAfterIndex(initial), new ActiveOrderIndex(initial, ids), adl);
    }

    private static RuntimePositionIndexValue position(long user, CorePositionSide side, long quantity) {
        return new RuntimePositionIndexValue(user, "1", "USDT", side, quantity);
    }

    private static void onLedger(TradingRuntimeState runtime, Runnable operation) throws Exception {
        var done = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();
        assertThat(runtime.dispatchLedgerQuery(ignored -> {
            try { operation.run(); } catch (Throwable problem) { failure.set(problem); }
            finally { done.countDown(); }
        })).isTrue();
        assertThat(done.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(failure.get()).isNull();
    }
}
