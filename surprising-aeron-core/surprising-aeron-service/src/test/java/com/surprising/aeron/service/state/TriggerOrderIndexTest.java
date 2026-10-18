package com.surprising.aeron.service.state;
import com.surprising.aeron.service.state.account.UserRuntime;

import com.surprising.aeron.service.state.index.TriggerOrderIndex;


import com.surprising.aeron.service.state.model.CoreRiskState;
import com.surprising.aeron.service.state.model.CoreTriggerOrderState;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreOrderType;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreTimeInForce;
import com.surprising.aeron.protocol.CoreTriggerCondition;
import com.surprising.aeron.protocol.CoreTriggerOrderStatus;
import com.surprising.aeron.protocol.CoreTriggerOrderType;
import com.surprising.product.api.ProductLine;
import java.util.LinkedHashSet;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TriggerOrderIndexTest {

    @Test
    void closeTriggerCommitPublishesOnlyAfterLanesAndDoesNotRepeatOnReuse() {
        try (var runtime = new TradingRuntimeState(LaneTopology.productionDefault())) {
            long second = 8;
            while (runtime.topology().accountLaneId(second) == runtime.topology().accountLaneId(7)) second++;
            final long secondUser = second;
            runtime.putUser(new UserRuntime(7));
            runtime.putUser(new UserRuntime(second));
            runtime.putTriggerOrder(trigger(901, 7, 100));
            runtime.putTriggerOrder(trigger(902, second, 100));
            var initial = TradingCoreState.empty(ProductLine.SPOT);
            var identities = new RuntimeIdentityRegistry();
            var triggers = new TriggerOrderIndex(initial);
            triggers.apply(901, trigger(901, 7, 100));
            triggers.apply(902, trigger(902, second, 100));
            runtime.bindFactIndexes(new RuntimeFactIndexes(new PositionUserIndex(initial, identities),
                    new OpenInterestIndex(initial, identities), triggers,
                    new com.surprising.aeron.service.state.index.AlgoOrderIndex(initial),
                    new com.surprising.aeron.service.state.index.LiquidationIndex(initial),
                    new com.surprising.aeron.service.state.index.CancelAllAfterIndex(initial),
                    new com.surprising.aeron.service.state.index.ActiveOrderIndex(initial, identities),
                    new AdlPositionIndex(initial, identities)), identities);
            runtime.setMetadata(ProductLine.SPOT, 10);
            runtime.clearChangedKeys();
            runtime.startAccountLanes();
            runtime.enterAsynchronousCommandScope();
            try {
                var event = runtime.dispatchLaneMutation(1, java.util.List.of(7L, second),
                        java.util.List.of(), java.util.List.of(901L, 902L), 100, 200);
                awaitCommit(runtime, event);
                assertThat(runtime.revision()).isEqualTo(10);
                assertThat(AccountLaneQueryTestSupport.read(runtime, 7, () -> runtime.triggerOrder(901).status())).isEqualTo(CoreTriggerOrderStatus.CANCELED);
                assertThat(AccountLaneQueryTestSupport.read(runtime, secondUser, () -> runtime.triggerOrder(902).status())).isEqualTo(CoreTriggerOrderStatus.CANCELED);
                assertThat(runtime.ownerLaneAccess).isFalse();
                runtime.releaseLaneCommit(event);
                assertThat(runtime.revision()).isEqualTo(12);
                assertThat(AccountLaneQueryTestSupport.read(runtime, 7, () -> runtime.triggerOrder(901).status())).isEqualTo(CoreTriggerOrderStatus.CANCELED);
                assertThat(AccountLaneQueryTestSupport.read(runtime, secondUser, () -> runtime.triggerOrder(902).revision())).isEqualTo(2);
                var plain = runtime.dispatchLaneMutation(2, java.util.List.of(7L, second));
                assertThat(plain).isSameAs(event);
                awaitCommit(runtime, plain);
                runtime.releaseLaneCommit(plain);
                assertThat(runtime.revision()).isEqualTo(12);
                assertThat(AccountLaneQueryTestSupport.read(runtime, 7, () -> runtime.triggerOrder(901).revision())).isEqualTo(2);
            } finally { runtime.exitAsynchronousCommandScope(); }
        }
    }

    private static void awaitCommit(TradingRuntimeState runtime, LaneCommitEvent event) {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (!runtime.laneCommitComplete(event)) {
            if (System.nanoTime() > deadline) throw new AssertionError("trigger commit timed out");
            Thread.onSpinWait();
        }
    }

    @Test
    void accountThreadMutatesTriggerWhileOwnerUsesOnlyCommitReceipts() {
        try (var runtime = new TradingRuntimeState(LaneTopology.productionDefault())) {
            long user = 1;
            int targetLane = runtime.topology().accountLaneCount() - 1;
            while (runtime.topology().accountLaneId(user) != targetLane) user++;
            final long userId = user;
            var initial = trigger(901, userId, 100);
            runtime.putTriggerOrder(initial); runtime.clearChangedKeys(); runtime.startAccountLanes();
            assertThatThrownBy(() -> runtime.triggerOrder(901)).isInstanceOf(IllegalStateException.class);
            assertThat(AccountLaneQueryTestSupport.read(runtime, userId, () -> runtime.triggerOrder(901))).isSameAs(initial);
            var changed = trigger(901, userId, 110);
            runtime.dispatchControlLanes(runtime.topology().accountLaneMask(userId), ignored -> { runtime.putTriggerOrder(changed); return null; });
            LedgerControlTestSupport.await(runtime, runtime::pollControlLanes);
            assertThat(AccountLaneQueryTestSupport.read(runtime, userId, () -> runtime.triggerOrder(901))).isSameAs(changed);
            var observed = new java.util.TreeMap<Long, com.surprising.aeron.service.state.model.CoreTriggerOrderState>();
            var consumer = new TradingRuntimeState.ChangedIndexConsumer() {
                @Override public void triggerOrder(long id, com.surprising.aeron.service.state.model.CoreTriggerOrderState before,
                        com.surprising.aeron.service.state.model.CoreTriggerOrderState after) { observed.put(id, after); }
            };
            runtime.visitChangedIndexes(consumer);
            assertThat(observed).containsEntry(901L, changed);
            runtime.dispatchControlLanes(runtime.topology().accountLaneMask(userId), ignored -> { runtime.removeTriggerOrder(901); return null; });
            LedgerControlTestSupport.await(runtime, runtime::pollControlLanes);
            runtime.visitChangedIndexes(consumer);
            assertThat(AccountLaneQueryTestSupport.read(runtime, userId, () -> runtime.triggerOrder(901))).isNull();
            assertThat(observed).containsEntry(901L, null);
            LedgerControlTestSupport.await(runtime, runtime.beginCommandRollback(runtime.revision(), 1));
            assertThat(AccountLaneQueryTestSupport.read(runtime, userId, () -> runtime.triggerOrder(901))).isSameAs(initial);
            assertThat(AccountLaneQueryTestSupport.read(runtime, userId, () -> runtime.hasTriggerClient(userId, initial.clientTriggerOrderId()))).isTrue();
        }
    }

    @Test
    void replacingAuxiliarySnapshotClearsPublishedTriggerIdentities() {
        var initial = trigger(901, 7, 100);
        var state = new TradingCoreState(ProductLine.SPOT, 0,
                Map.of(7L, CoreUserState.empty(ProductLine.SPOT, 7)), Map.of(), Map.of(),
                CoreRiskState.empty(), CoreTreasuryState.empty(), Map.of(), Map.of(), Map.of(), Map.of(901L, initial));
        try (var runtime = RuntimeStateProjector.project(state, new RuntimeIdentityRegistry())) {
            runtime.startAccountLanes();
            assertThat(AccountLaneQueryTestSupport.read(runtime, 7, () -> runtime.triggerOrder(901))).isEqualTo(initial);
            while (!runtime.tryAcquireOwnerLaneAccess()) Thread.onSpinWait();
            runtime.replaceAuxiliaryState(TradingCoreState.empty(ProductLine.SPOT));
            assertThat(runtime.triggerOrder(901)).isNull();
            runtime.replaceAuxiliaryState(state);
            runtime.releaseOwnerLaneAccess();
            assertThat(AccountLaneQueryTestSupport.read(runtime, 7, () -> runtime.triggerOrder(901))).isEqualTo(initial);
        }
    }

    private static CoreTriggerOrderState trigger(long id, long user, long price) {
        return new CoreTriggerOrderState(id, ProductLine.SPOT, user, "published-" + id, "",
                "1", CoreStateTestFixtures.runtimeInstrument(), CoreOrderSide.BUY,
                CoreTriggerOrderType.STOP_LOSS,
                CoreTriggerCondition.GREATER_OR_EQUAL, price, 0, 0, 0, 0, 0,
                CoreOrderType.LIMIT, CoreTimeInForce.GTC, 90, 1, CoreMarginMode.CROSS,
                CorePositionSide.NET, CoreTriggerOrderStatus.PENDING, 0, 0, 0, "",
                0, 0, 1, 1, 1);
    }

    @Test
    void candidatesPageBoundsAndResumesWithoutOmission() {
        Map<Long, CoreTriggerOrderState> triggers = new java.util.TreeMap<>();
        for (long id = 1; id <= 300; id++) {
            triggers.put(id, new CoreTriggerOrderState(id, ProductLine.SPOT, 7, "client-" + id, "",
                    "1", CoreStateTestFixtures.runtimeInstrument(), CoreOrderSide.SELL,
                    CoreTriggerOrderType.TAKE_PROFIT,
                    CoreTriggerCondition.GREATER_OR_EQUAL, 70_000, 0, 0, 0, 0, 0,
                    CoreOrderType.MARKET, CoreTimeInForce.IOC, 0, 1, CoreMarginMode.CROSS,
                    CorePositionSide.NET, CoreTriggerOrderStatus.PENDING, 0, 0, 0, "",
                    0, 0, 1, 1, 1));
        }
        TradingCoreState state = new TradingCoreState(ProductLine.SPOT, 0,
                Map.of(7L, CoreUserState.empty(ProductLine.SPOT, 7)), Map.of(), Map.of(),
                CoreRiskState.empty(), CoreTreasuryState.empty(), Map.of(), Map.of(), Map.of(), triggers);
        TriggerOrderIndex index = new TriggerOrderIndex(state);

        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        try (TradingRuntimeState runtime = RuntimeStateProjector.project(state, identities)) {
            assertThat(runtime.hasTriggerClient(7, "client-1")).isTrue();
            assertThat(runtime.hasTriggerClient(8, "client-1")).isFalse();
            runtime.removeTriggerOrder(1);
            assertThat(runtime.hasTriggerClient(7, "client-1")).isFalse();
            runtime.putTriggerOrder(triggers.get(1L));
            assertThat(runtime.hasTriggerClient(7, "client-1")).isTrue();
        }

        long upperId = index.maxPendingId("1");
        int phase = TriggerOrderIndex.PHASE_GREATER_OR_EQUAL;
        long priceCursor = Long.MAX_VALUE;
        long orderCursor = Long.MAX_VALUE;
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        int pages = 0;
        while (phase < TriggerOrderIndex.PHASE_COMPLETE) {
            TriggerOrderIndex.TriggerCandidatePage page = index.candidatesPage("1", 70_000, phase,
                    priceCursor, orderCursor, upperId, 64);
            assertThat(page.ids()).hasSizeLessThanOrEqualTo(64);
            ids.addAll(page.ids());
            pages++;
            if (page.complete()) break;
            phase = page.nextPhase();
            priceCursor = page.nextPriceCursor();
            orderCursor = page.nextOrderCursor();
            assertThat(pages).isLessThan(20);
        }

        assertThat(ids).hasSize(300);
        assertThat(ids).containsExactlyInAnyOrderElementsOf(triggers.keySet());
    }

    @Test
    void trailingCandidatesUseActivationAndCallbackThresholds() {
        Map<Long, CoreTriggerOrderState> triggers = new java.util.TreeMap<>();
        triggers.put(301L, trailing(301, CoreOrderSide.SELL, 100, 100_000, 100, 0, 1_000));
        triggers.put(302L, trailing(302, CoreOrderSide.SELL, 100, 100_000, 0, 0, 0));
        triggers.put(303L, trailing(303, CoreOrderSide.SELL, 0, 100_000, 0, 0, 0));
        triggers.put(304L, trailing(304, CoreOrderSide.BUY, 0, 100_000, 0, 100, 1_000));
        TradingCoreState state = new TradingCoreState(ProductLine.SPOT, 0,
                Map.of(7L, CoreUserState.empty(ProductLine.SPOT, 7)), Map.of(), Map.of(),
                CoreRiskState.empty(), CoreTreasuryState.empty(), Map.of(), Map.of(), Map.of(), triggers);
        TriggerOrderIndex index = new TriggerOrderIndex(state);

        assertThat(candidateIds(index, 95)).containsExactly(303L);
        assertThat(candidateIds(index, 105)).containsExactly(302L, 303L);
        assertThat(candidateIds(index, 115)).containsExactly(304L, 302L, 303L);
    }

    private static java.util.List<Long> candidateIds(TriggerOrderIndex index, long markPrice) {
        java.util.ArrayList<Long> ids = new java.util.ArrayList<>();
        int phase = TriggerOrderIndex.PHASE_GREATER_OR_EQUAL;
        long priceCursor = Long.MAX_VALUE;
        long orderCursor = Long.MAX_VALUE;
        while (phase < TriggerOrderIndex.PHASE_COMPLETE) {
            TriggerOrderIndex.TriggerCandidatePage page = index.candidatesPage("1", markPrice,
                    phase, priceCursor, orderCursor, index.maxPendingId("1"), 64);
            ids.addAll(page.ids());
            if (page.complete()) break;
            phase = page.nextPhase();
            priceCursor = page.nextPriceCursor();
            orderCursor = page.nextOrderCursor();
        }
        return ids;
    }

    @Test
    @org.junit.jupiter.api.Timeout(5)
    void nativePricePagesRetainRangeOrderingAtLongBoundaryAndAcrossDeletedBuckets() {
        var index = new TriggerOrderIndex(TradingCoreState.empty(ProductLine.SPOT));
        index.apply(11, indexed(11, "1", CoreTriggerCondition.GREATER_OR_EQUAL, Long.MAX_VALUE, 0, ""));
        index.apply(12, indexed(12, "1", CoreTriggerCondition.GREATER_OR_EQUAL, Long.MAX_VALUE, 0, ""));
        index.apply(10, indexed(10, "1", CoreTriggerCondition.GREATER_OR_EQUAL, 100, 0, ""));
        index.apply(9, indexed(9, "1", CoreTriggerCondition.LESS_OR_EQUAL, Long.MAX_VALUE, 0, ""));
        assertThat(pagedIds(index, Long.MAX_VALUE, 1)).containsExactly(12L, 11L, 10L, 9L);

        var first = index.candidatesPage("1", Long.MAX_VALUE, 0, Long.MAX_VALUE, Long.MAX_VALUE, 12, 1);
        assertThat(first.ids()).containsExactly(12L);
        index.apply(11, null);
        index.apply(12, null);
        index.apply(99, indexed(99, "1", CoreTriggerCondition.GREATER_OR_EQUAL, 100, 0, ""));
        var resumed = index.candidatesPage("1", Long.MAX_VALUE, first.nextPhase(), first.nextPriceCursor(),
                first.nextOrderCursor(), 12, 4);
        assertThat(resumed.ids()).containsExactly(10L, 9L);
        assertThat(resumed.complete()).isTrue();
    }

    @Test
    void nativeMembershipExpiryAndOcoStayConsistentWithUpdatesAndPermanentIntIds() {
        var index = new TriggerOrderIndex(TradingCoreState.empty(ProductLine.SPOT));
        long large = 4_000_000_000L;
        var first = indexed(large, "2147483647", CoreTriggerCondition.GREATER_OR_EQUAL, 100, 20, "group");
        var second = indexed(large + 1, "2147483647", CoreTriggerCondition.GREATER_OR_EQUAL, 100, 10, "group");
        var other = indexed(large + 2, "1", CoreTriggerCondition.GREATER_OR_EQUAL, 100, Long.MAX_VALUE, "group");
        index.apply(large, first); index.apply(large + 1, second); index.apply(large + 2, other);
        assertThat(index.route(large).instrumentId()).isEqualTo(Integer.MAX_VALUE);
        assertThat(index.ids("2147483647")).containsExactly(large + 1, large);
        assertThat(index.ids(7)).containsExactly(large + 2, large + 1, large);
        assertThat(index.ocoSiblingsDescending(first)).containsExactly(large + 1, large);
        assertThat(index.ids(7, "2147483647", CoreMarginMode.CROSS, CorePositionSide.NET))
                .containsExactly(large, large + 1);
        assertThat(index.expired(10, 1)).containsExactly(large + 1);
        assertThat(index.expired(Long.MAX_VALUE, 10)).containsExactly(large, large + 1, large + 2);
        index.apply(large, indexed(large, "1", CoreTriggerCondition.LESS_OR_EQUAL, 110, 0, ""));
        assertThat(index.ids("2147483647")).containsExactly(large + 1);
        assertThat(index.ocoSiblingsDescending(first)).containsExactly(large + 1);
        assertThat(index.expired(20, 10)).containsExactly(large + 1);
        index.apply(large + 1, null);
        assertThat(index.maxPendingId("2147483647")).isZero();
        assertThat(index.containsClient(7, "indexed-" + (large + 1))).isFalse();
        index.rebuild(TradingCoreState.empty(ProductLine.SPOT));
        assertThat(index.ids()).isEmpty();
        assertThat(index.expired(Long.MAX_VALUE, 10)).isEmpty();
    }

    private static java.util.List<Long> pagedIds(TriggerOrderIndex index, long price, int limit) {
        var ids = new java.util.ArrayList<Long>();
        int phase = 0;
        long priceCursor = Long.MAX_VALUE, orderCursor = Long.MAX_VALUE;
        for (int pageCount = 0; pageCount < 100; pageCount++) {
            var page = index.candidatesPage("1", price, phase, priceCursor, orderCursor, index.maxPendingId("1"), limit);
            ids.addAll(page.ids());
            if (page.complete()) return ids;
            phase = page.nextPhase(); priceCursor = page.nextPriceCursor(); orderCursor = page.nextOrderCursor();
        }
        throw new AssertionError("candidate pages did not finish");
    }

    private static CoreTriggerOrderState indexed(long id, String instrumentId, CoreTriggerCondition condition,
            long price, long expiry, String oco) {
        var instrument = com.surprising.aeron.service.state.instrument.CoreInstrument.from(ProductLine.SPOT,
                new com.surprising.aeron.protocol.RegisterInstrumentCommand(instrumentId,
                        com.surprising.instrument.api.model.ContractType.SPOT.ordinal(), "BTC", "USDT", "USDT",
                        1, 1, 1, 100_000, 50_000, 0, 0, 0, -1, 0));
        return new CoreTriggerOrderState(id, ProductLine.SPOT, 7, "indexed-" + id, oco, instrumentId,
                instrument, CoreOrderSide.BUY, CoreTriggerOrderType.STOP_LOSS, condition, price, 0, 0, 0, 0, 0,
                CoreOrderType.LIMIT, CoreTimeInForce.GTC, 90, 1, CoreMarginMode.CROSS, CorePositionSide.NET,
                CoreTriggerOrderStatus.PENDING, 0, 0, 0, "", expiry, 0, 1, 1, 1);
    }

    private static CoreTriggerOrderState trailing(long id, CoreOrderSide side, long activationPrice,
                                                  long callbackRate, long highest, long lowest, long activatedAt) {
        return new CoreTriggerOrderState(id, ProductLine.SPOT, 7, "client-" + id, "", "1",
                CoreStateTestFixtures.runtimeInstrument(), side,
                CoreTriggerOrderType.TRAILING_STOP, CoreTriggerCondition.GREATER_OR_EQUAL, 0,
                activationPrice, callbackRate, highest, lowest, activatedAt, CoreOrderType.MARKET,
                CoreTimeInForce.IOC, 0, 1, CoreMarginMode.CROSS, CorePositionSide.NET,
                CoreTriggerOrderStatus.PENDING, 0, 0, 0, "", 0, 0, 1, 1, 1);
    }
}
