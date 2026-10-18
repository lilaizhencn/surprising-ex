package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.*;
import com.surprising.aeron.service.state.instrument.CoreInstrument;
import com.surprising.aeron.service.state.model.*;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import exchange.core2.core.common.MatcherResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static com.surprising.aeron.service.matching.MatcherEventFixtures.trade;
import static org.assertj.core.api.Assertions.*;

class MatcherTreasurySettlementTest {
    @Test
    void shrinkingLaneRoutesRetainTreasurySlotsAndExcludeInactiveContributions() throws Exception {
        var event = new MatcherSettlementEvent();
        var prepare = MatcherSettlementEvent.class.getDeclaredMethod("prepareTreasuryDeltas", long.class, boolean.class);
        prepare.setAccessible(true);
        var storage = MatcherSettlementEvent.class.getDeclaredField("touchedLaneTreasuryDeltas");
        storage.setAccessible(true);
        var mask = MatcherSettlementEvent.class.getDeclaredField("requiredLaneMask");
        mask.setAccessible(true);
        var trades = MatcherSettlementEvent.class.getDeclaredField("treasuryTrades");
        trades.setAccessible(true);
        var merge = MatcherSettlementEvent.class.getDeclaredMethod("mergeTreasuryDeltas");
        merge.setAccessible(true);

        prepare.invoke(event, 1L, false);
        assertThat(storage.get(event)).isNull();
        prepare.invoke(event, 1L, true);
        RuntimeTreasuryDelta first = ((RuntimeTreasuryDelta[]) storage.get(event))[0];
        first.addFee(2, 17);
        prepare.invoke(event, 5L, true);
        RuntimeTreasuryDelta[] slots = (RuntimeTreasuryDelta[]) storage.get(event);
        assertThat(slots).hasSize(2);
        assertThat(slots[0]).isSameAs(first);
        assertThat(first.size()).isZero();
        RuntimeTreasuryDelta second = slots[1];

        for (int iteration = 0; iteration < 64; iteration++) {
            prepare.invoke(event, 1L << 63, true);
            assertThat(storage.get(event)).isSameAs(slots);
            assertThat(slots[0]).isSameAs(first);
            assertThat(slots[1]).isSameAs(second);
            assertThat(first.size()).isZero();
            assertThat(second.size()).isZero();
            mask.setLong(event, 1L << 63);
            trades.setBoolean(event, true);
            first.addFee(2, 3);
            // Capacity is not the current business range, even if an inactive cell has data.
            second.addFee(2, 100);
            assertThat(((RuntimeTreasuryDelta) merge.invoke(event)).feeUnits(0)).isEqualTo(3);

            prepare.invoke(event, (1L << 63) | 1, true);
            mask.setLong(event, (1L << 63) | 1);
            first.addFee(2, 5);
            second.addFee(2, 7);
            assertThat(((RuntimeTreasuryDelta) merge.invoke(event)).feeUnits(0)).isEqualTo(12);
            prepare.invoke(event, 1L, false);
            assertThat(storage.get(event)).isSameAs(slots);
            assertThat(first.size()).isZero();
            assertThat(second.size()).isZero();
        }
    }

    @Test
    void independentAccountWritersThenOriginalLedgerSettleFeesBeforeOwnerCollects() throws Exception {
        var topology = LaneTopology.productionDefault();
        long makerUser = 8, takerUser = 7;
        while (topology.accountLaneId(makerUser) == topology.accountLaneId(takerUser)) takerUser++;
        var instrument = CoreInstrument.from(ProductLine.LINEAR_PERPETUAL,
                new RegisterInstrumentCommand("1", ContractType.LINEAR_PERPETUAL.ordinal(),
                        "BTC", "USDT", "USDT", 1, 1, 1, 100_000, 50_000,
                        0, 0, 0, -1, 0, 10_000_000, 10_000, 0, 1,
                        List.of(new CoreRiskLimitBracket(1, 0, 10_000, 10_000_000, 100_000, 50_000))));
        var taker = order(11, takerUser, CoreOrderSide.BUY);
        var maker = order(12, makerUser, CoreOrderSide.SELL);
        var before = new TradingCoreState(ProductLine.LINEAR_PERPETUAL, 1,
                Map.of(takerUser, user(takerUser, taker), makerUser, user(makerUser, maker)),
                Map.of(11L, taker, 12L, maker), Map.of("1", instrument),
                CoreRiskState.empty(), CoreTreasuryState.empty());
        var identities = new RuntimeIdentityRegistry();
        try (var runtime = RuntimeStateProjector.project(before, identities)) {
            OrderRuntime originalTaker = runtime.order(11);
            UUID commandId = new UUID(1, 2);
            long laneMask = topology.accountLaneMask(takerUser) | topology.accountLaneMask(makerUser);
            var event = runtime.prepareDirectMatcherSettlement(1, laneMask, originalTaker, null, 1,
                    commandId, 0, identities, 10, 20, List.of(), null);
            TreasuryRuntime original = runtime.treasury();
            var failure = new AtomicReference<Throwable>();
            runtime.startAccountLanes();
            int makerLane = topology.accountLaneId(makerUser), takerLane = topology.accountLaneId(takerUser);
            var makerParked = new CountDownLatch(1);
            var releaseMaker = new CountDownLatch(1);
            var takerFinished = new CountDownLatch(1);
            var financialSignalling = new CountDownLatch(1);
            var releaseFinancialSignal = new CountDownLatch(1);
            var holdFirstFinancialSignal = new java.util.concurrent.atomic.AtomicBoolean(true);
            runtime.ownerCompletionSignal(() -> {
                if (Thread.currentThread() == runtime.laneWorkers[takerLane].thread()) takerFinished.countDown();
                if (Thread.currentThread().getName().equals("core-system-ledger-lane")
                        && holdFirstFinancialSignal.compareAndSet(true, false)) {
                    financialSignalling.countDown();
                    await(releaseFinancialSignal);
                }
            });
            runtime.laneWorkers[makerLane].submit(lane -> {
                makerParked.countDown();
                await(releaseMaker);
            });
            assertThat(makerParked.await(3, TimeUnit.SECONDS)).isTrue();
            var releaseLedgerHead = new java.util.concurrent.atomic.AtomicBoolean();
            try (var ledger = new SystemLedgerLane(original, 1, failure::set)) {
                assertThat(ledger.offer(new SystemLedgerLane.Entry() {
                    @Override public boolean ready() { return releaseLedgerHead.get(); }
                    @Override public void apply(TreasuryRuntime treasury) { }
                })).isTrue();
                event.reserveMatcherPublication();
                event.beginMatcherPublication();
                event.publishDirectNativeResult(new MatcherResult(1,
                        exchange.core2.core.common.cmd.OrderCommandType.PLACE_ORDER, 11, identities.symbolId("1"),
                        100, 1, 100, exchange.core2.core.common.OrderAction.BID,
                        exchange.core2.core.common.OrderType.GTC, takerUser, 1_000, 0,
                        exchange.core2.core.common.cmd.CommandResultCode.SUCCESS,
                        List.of(trade(12, makerUser, 100, 1, true, true)),
                        new MatcherResult.MarketData(List.of(), List.of(), 0, 0)),
                        1, 1, 2, 11, 1, 0);
                event.completeMatcherPublication();
                try {
                    assertThat(event.submitTreasurySettlement(ledger)).isFalse();
                    assertThat(takerFinished.await(3, TimeUnit.SECONDS)).isTrue();
                    assertThat(event.complete()).isFalse();
                    assertThatThrownBy(() -> runtime.releaseMatcherSettlement(event))
                            .isInstanceOf(IllegalStateException.class).hasMessageContaining("incomplete");
                    assertThatThrownBy(() -> original.fee(identities.assetId("USDT")))
                            .isInstanceOf(IllegalStateException.class);
                } finally { releaseMaker.countDown(); }
                long accountDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while (!event.accountLanesCompleteForLedger()) {
                    ledger.assertHealthy(); runtime.assertAccountLanesHealthy();
                    if (System.nanoTime() >= accountDeadline) throw new AssertionError("accounts timed out");
                    Thread.onSpinWait();
                }
                assertThat(event.complete()).isFalse();
                assertThat(runtime.collectMatcherSettlement(event, null, null)).isNull();
                releaseLedgerHead.set(true);
                long admissionDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while (!event.submitTreasurySettlement(ledger)) {
                    if (System.nanoTime() >= admissionDeadline) throw new AssertionError("Ledger queue remained full");
                    Thread.onSpinWait();
                }
                assertThatThrownBy(() -> event.submitTreasurySettlement(ledger))
                        .isInstanceOf(IllegalStateException.class).hasMessageContaining("already queued");
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while (!event.complete()) {
                    ledger.assertHealthy(); runtime.assertAccountLanesHealthy();
                    if (System.nanoTime() >= deadline) throw new AssertionError("financial settlement timed out");
                    Thread.onSpinWait();
                }
                assertThatThrownBy(() -> runtime.collectMatcherSettlement(event, new RuntimeFundsAccumulator(), null))
                        .isInstanceOf(IllegalStateException.class).hasMessageContaining("funds remain on SystemLedgerLane");
                assertThat(runtime.collectMatcherSettlement(event, null, null).size()).isZero();
                assertThatThrownBy(() -> runtime.collectMatcherSettlement(event, null, null))
                        .isInstanceOf(IllegalStateException.class).hasMessageContaining("already collected");
                runtime.releaseMatcherSettlement(event);
                assertThat(financialSignalling.await(3, TimeUnit.SECONDS)).isTrue();
                // The previous Ledger callback still runs after its completion release. Its
                // financial slot can be reused, provided it never reads the pooled fact again.
                UUID secondId = new UUID(3, 4);
                var reused = runtime.prepareDirectCancellation(2, originalTaker.userId(), originalTaker.orderId(),
                        secondId, 0, identities, 30, 40);
                assertThat(reused).isSameAs(event);
                assertThat(reused.submitTreasurySettlement(ledger)).isTrue();
                reused.reserveMatcherPublication();
                reused.beginMatcherPublication();
                reused.publishDirectNativeResult(new MatcherResult(2,
                        exchange.core2.core.common.cmd.OrderCommandType.CANCEL_ORDER, 2, identities.symbolId("1"),
                        100, 1, 100, exchange.core2.core.common.OrderAction.BID,
                        exchange.core2.core.common.OrderType.GTC, takerUser, 1_000, 0,
                        exchange.core2.core.common.cmd.CommandResultCode.MATCHING_UNKNOWN_ORDER_ID,
                        List.of(), new MatcherResult.MarketData(List.of(), List.of(), 0, 0)),
                        2, 3, 4, 11, 2, 0);
                reused.completeMatcherPublication();
                releaseFinancialSignal.countDown();
                deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while (!reused.complete()) {
                    ledger.assertHealthy(); runtime.assertAccountLanesHealthy();
                    if (System.nanoTime() >= deadline) throw new AssertionError("reused financial slot timed out");
                    Thread.onSpinWait();
                }
                assertThat(runtime.collectMatcherSettlement(reused, null, null).size()).isZero();
                runtime.releaseMatcherSettlement(reused);
                assertThat(failure.get()).isNull();
            } finally {
                releaseLedgerHead.set(true); releaseMaker.countDown(); releaseFinancialSignal.countDown();
            }
            assertThat(original.fee(identities.assetId("USDT"))).isEqualTo(2);
            runtime.ownerCompletionSignal(null);
        }
    }

    private static CoreUserState user(long userId, CoreOrderState order) {
        return new CoreUserState(ProductLine.LINEAR_PERPETUAL, userId, 1,
                Map.of("USDT", new AssetBalance("USDT", 800, 100)),
                Map.of(order.orderId(), OrderReservation.create(order.orderId(), "1",
                        ReservationKind.DERIVATIVE_MARGIN, "USDT", 100, 1)), Map.of());
    }

    private static CoreOrderState order(long orderId, long userId, CoreOrderSide side) {
        return new CoreOrderState(orderId, ProductLine.LINEAR_PERPETUAL, userId, "1", side,
                100, 1, 0, 1, false, CoreMarginMode.CROSS, CorePositionSide.NET,
                CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "", new UUID(0, orderId),
                10_000, 10_000, CoreOrderStatus.OPEN, 1);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(3, TimeUnit.SECONDS)) throw new AssertionError("account gate timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw new AssertionError(interrupted);
        }
    }
}
