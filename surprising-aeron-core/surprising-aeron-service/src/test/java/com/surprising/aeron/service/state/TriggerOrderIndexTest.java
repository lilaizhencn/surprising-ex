package com.surprising.aeron.service.state;

import com.surprising.aeron.service.state.index.TriggerOrderIndex;


import com.surprising.aeron.service.state.model.CoreRiskState;
import com.surprising.aeron.service.state.model.CoreTriggerOrderState;

import static org.assertj.core.api.Assertions.assertThat;

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
    void ownerReadsPublishedTriggersAndRemovalWithoutSubmittingLaneTasks() throws Exception {
        try (var runtime = new TradingRuntimeState(LaneTopology.productionDefault())) {
            long user = 1;
            while (runtime.topology().accountLaneId(user) != 3) user++;
            var trigger = trigger(901, user, 100);
            runtime.putTriggerOrder(trigger);
            runtime.clearChangedKeys();
            runtime.startAccountLanes();
            var tasksField = TradingRuntimeState.class.getDeclaredField("laneMutationTasks");
            tasksField.setAccessible(true);
            var tasks = (Object[]) tasksField.get(runtime);
            assertThat(tasks).containsOnlyNulls();
            assertThat(runtime.triggerOrder(901)).isSameAs(trigger);
            assertThat(runtime.triggerOrder(999)).isNull();
            assertThat(tasks).as("owner lookup must not enqueue any Lane read").containsOnlyNulls();

            var changed = trigger(901, user, 110);
            runtime.putTriggerOrder(changed);
            assertThat(runtime.triggerOrder(901)).isSameAs(changed);
            var observed = new java.util.HashMap<Long, CoreTriggerOrderState>();
            var consumer = new RuntimeFactFrame.ChangeConsumer() {
                public void triggerOrder(long id, CoreTriggerOrderState before, CoreTriggerOrderState after) {
                    observed.put(id, after);
                }
            };
            runtime.visitChangedIndexes(consumer);
            assertThat(observed).containsEntry(901L, changed);
            assertThat(tasks[0]).isNull();
            assertThat(tasks[1]).isNull();
            assertThat(tasks[2]).isNull();
            runtime.removeTriggerOrder(901);
            runtime.visitChangedIndexes(consumer);
            assertThat(runtime.triggerOrder(901)).isNull();
            assertThat(observed).containsEntry(901L, null);

            runtime.rollbackActiveCommand(runtime.revision(), 1);
            assertThat(runtime.triggerOrder(901)).isSameAs(trigger);
            assertThat(runtime.hasTriggerClient(user, trigger.clientTriggerOrderId())).isTrue();
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
            assertThat(runtime.triggerOrder(901)).isEqualTo(initial);
            runtime.replaceAuxiliaryState(TradingCoreState.empty(ProductLine.SPOT));
            assertThat(runtime.triggerOrder(901)).isNull();
            runtime.replaceAuxiliaryState(state);
            assertThat(runtime.triggerOrder(901)).isEqualTo(initial);
        }
    }

    private static CoreTriggerOrderState trigger(long id, long user, long price) {
        return new CoreTriggerOrderState(id, ProductLine.SPOT, user, "published-" + id, "",
                "BTC-USDT", CoreOrderSide.BUY, CoreTriggerOrderType.STOP_LOSS,
                CoreTriggerCondition.GREATER_OR_EQUAL, price, 0, 0, 0, 0, 0,
                CoreOrderType.LIMIT, CoreTimeInForce.GTC, 90, 1, CoreMarginMode.CROSS,
                CorePositionSide.NET, CoreTriggerOrderStatus.PENDING, 0, 0, 0, "", "",
                0, 0, 1, 1, 1);
    }

    @Test
    void candidatesPageBoundsAndResumesWithoutOmission() {
        Map<Long, CoreTriggerOrderState> triggers = new java.util.TreeMap<>();
        for (long id = 1; id <= 300; id++) {
            triggers.put(id, new CoreTriggerOrderState(id, ProductLine.SPOT, 7, "client-" + id, "",
                    "BTC-USDT", CoreOrderSide.SELL, CoreTriggerOrderType.TAKE_PROFIT,
                    CoreTriggerCondition.GREATER_OR_EQUAL, 70_000, 0, 0, 0, 0, 0,
                    CoreOrderType.MARKET, CoreTimeInForce.IOC, 0, 1, CoreMarginMode.CROSS,
                    CorePositionSide.NET, CoreTriggerOrderStatus.PENDING, 0, 0, 0, "",
                    "trace-" + id, 0, 0, 1, 1, 1));
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

        long upperId = index.maxPendingId("BTC-USDT");
        int phase = TriggerOrderIndex.PHASE_GREATER_OR_EQUAL;
        long priceCursor = Long.MAX_VALUE;
        long orderCursor = Long.MAX_VALUE;
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        int pages = 0;
        while (phase < TriggerOrderIndex.PHASE_COMPLETE) {
            TriggerOrderIndex.TriggerCandidatePage page = index.candidatesPage("BTC-USDT", 70_000, phase,
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
            TriggerOrderIndex.TriggerCandidatePage page = index.candidatesPage("BTC-USDT", markPrice,
                    phase, priceCursor, orderCursor, index.maxPendingId("BTC-USDT"), 64);
            ids.addAll(page.ids());
            if (page.complete()) break;
            phase = page.nextPhase();
            priceCursor = page.nextPriceCursor();
            orderCursor = page.nextOrderCursor();
        }
        return ids;
    }

    private static CoreTriggerOrderState trailing(long id, CoreOrderSide side, long activationPrice,
                                                  long callbackRate, long highest, long lowest, long activatedAt) {
        return new CoreTriggerOrderState(id, ProductLine.SPOT, 7, "client-" + id, "", "BTC-USDT", side,
                CoreTriggerOrderType.TRAILING_STOP, CoreTriggerCondition.GREATER_OR_EQUAL, 0,
                activationPrice, callbackRate, highest, lowest, activatedAt, CoreOrderType.MARKET,
                CoreTimeInForce.IOC, 0, 1, CoreMarginMode.CROSS, CorePositionSide.NET,
                CoreTriggerOrderStatus.PENDING, 0, 0, 0, "", "trace-" + id, 0, 0, 1, 1, 1);
    }
}
