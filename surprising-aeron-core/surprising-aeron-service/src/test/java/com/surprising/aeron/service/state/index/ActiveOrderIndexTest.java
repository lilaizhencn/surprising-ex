package com.surprising.aeron.service.state.index;

import com.surprising.aeron.service.state.CoreTreasuryState;
import com.surprising.aeron.service.state.CoreUserState;
import com.surprising.aeron.service.state.admission.AdmissionSummary;
import com.surprising.aeron.service.state.StateMapSupport;
import com.surprising.aeron.service.state.TradingCoreState;

import com.surprising.aeron.service.state.model.CoreOrderState;
import com.surprising.aeron.service.state.model.CoreOrderStatus;
import com.surprising.aeron.service.state.model.CoreRiskState;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.product.api.ProductLine;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ActiveOrderIndexTest {

    @Test
    void terminalRemovalIsIdempotentAndPreservesOtherAccountAndSymbolOrders() {
        for (var status : CoreOrderStatus.values()) {
            if (!status.terminal()) continue;
            var identities = new com.surprising.aeron.service.state.RuntimeIdentityRegistry();
            var index = new ActiveOrderIndex(TradingCoreState.empty(ProductLine.SPOT), identities);
            var orders = new com.surprising.aeron.service.state.OrderRuntime[3];
            for (int i = 0; i < orders.length; i++) {
                var order = new CoreOrderState(i + 1, ProductLine.SPOT, i < 2 ? 7 : 8, "BTC-USDT", 1,
                        CoreOrderSide.BUY, 90, 10, 0, 10, false, CoreOrderStatus.OPEN, 1);
                orders[i] = com.surprising.aeron.service.state.RuntimeStateProjector.toRuntimeOrder(order, identities);
                index.apply(i + 1, orders[i], identities);
            }
            var terminal = status == CoreOrderStatus.FILLED
                    ? orders[0].withFill(10, 0, 0, status, 2) : orders[0].withStatus(status, 2);
            index.apply(1, terminal, identities);
            index.apply(1, terminal, identities);
            index.apply(1, null, identities);
            index.apply(999, null, identities);
            assertThat(index.activeOrderRuntime(1)).isNull();
            assertThat(index.ids(7)).containsExactly(2L);
            assertThat(index.ids(8)).containsExactly(3L);
            assertThat(index.ids("BTC-USDT")).containsExactly(3L, 2L);
            index.apply(2, null, identities);
            index.apply(3, null, identities);
            assertThat(index.count()).isZero();
            assertThat(index.ids(7)).isEmpty();
            assertThat(index.ids(8)).isEmpty();
            assertThat(index.ids("BTC-USDT")).isEmpty();
        }
    }

    @Test
    void boundedTurnoverKeepsStorageAndIndependentQueryCursors() throws Exception {
        var identities = new com.surprising.aeron.service.state.RuntimeIdentityRegistry();
        var index = new ActiveOrderIndex(TradingCoreState.empty(ProductLine.SPOT), identities);
        var mapField = ActiveOrderIndex.class.getDeclaredField("ordersById");
        mapField.setAccessible(true);
        var entries = (org.agrona.collections.Long2ObjectHashMap<?>) mapField.get(index);
        var valuesField = org.agrona.collections.Long2ObjectHashMap.class.getDeclaredField("values");
        valuesField.setAccessible(true);
        for (long id = 1; id <= 32; id++) {
            var order = new CoreOrderState(id, ProductLine.SPOT, 7, "BTC-USDT", 1,
                    CoreOrderSide.BUY, 90, 10, 0, 10, false, CoreOrderStatus.OPEN, 1);
            index.apply(id, com.surprising.aeron.service.state.RuntimeStateProjector.toRuntimeOrder(order, identities), identities);
        }
        Object storage = valuesField.get(entries);
        for (long id = 33; id <= 4096; id++) {
            index.apply(id - 32, null, identities);
            var order = new CoreOrderState(id, ProductLine.SPOT, 7, "BTC-USDT", 1,
                    CoreOrderSide.BUY, 90, 10, 0, 10, false, CoreOrderStatus.OPEN, 1);
            index.apply(id, com.surprising.aeron.service.state.RuntimeStateProjector.toRuntimeOrder(order, identities), identities);
        }
        assertThat(valuesField.get(entries)).isSameAs(storage);
        var cursor = index.matchingIds(7, "BTC-USDT");
        var actual = new java.util.HashSet<Long>();
        while (cursor.hasNext()) {
            actual.add(cursor.next());
            assertThat(index.sortedIds(7, "BTC-USDT")).hasSize(32);
            assertThat(index.page(0, null, 0, 16).orderIds()).hasSize(16);
        }
        assertThat(actual).hasSize(32).allMatch(id -> id > 4064 && id <= 4096);
        assertThat(index.pendingQuantity(7, "BTC-USDT", CorePositionSide.NET, CoreOrderSide.BUY)).isEqualTo(320);
        for (long id = 4065; id <= 4096; id++) index.apply(id, null, identities);
        assertThat(index.count()).isZero();
        assertThat(index.ids(7)).isEmpty();
        assertThat(index.ids("BTC-USDT")).isEmpty();
    }

    @Test
    void runtimeUpdatesShareThePublishedOrderAndKeepQuerySnapshotsImmutable() throws Exception {
        var identities = new com.surprising.aeron.service.state.RuntimeIdentityRegistry();
        var index = new ActiveOrderIndex(TradingCoreState.empty(ProductLine.SPOT), identities);
        var initial = new CoreOrderState(1, ProductLine.SPOT, 7, "BTC-USDT", 1,
                CoreOrderSide.BUY, 90, 10, 0, 10, false, CoreOrderStatus.OPEN, 1);
        var order = com.surprising.aeron.service.state.RuntimeStateProjector.toRuntimeOrder(initial, identities);
        index.apply(1, order, identities);
        assertThat(index.activeOrderRuntime(1)).isSameAs(order);
        var oldQuery = index.activeOrder(1);
        assertThat(oldQuery).isEqualTo(initial);
        var field = ActiveOrderIndex.class.getDeclaredField("ordersById");
        field.setAccessible(true);
        var entries = (org.agrona.collections.Long2ObjectHashMap<?>) field.get(index);
        Object entry = entries.get(1);
        var partiallyFilled = order.withFill(4, 6, 3, CoreOrderStatus.OPEN, 2);
        index.apply(1, partiallyFilled, identities);
        assertThat(entries.get(1)).isSameAs(entry);
        assertThat(index.activeOrderRuntime(1)).isSameAs(partiallyFilled);
        assertThat(index.pendingQuantity(7, "BTC-USDT", CorePositionSide.NET, CoreOrderSide.BUY)).isEqualTo(6);
        assertThat(index.activeOrder(1).cumulativeFeeUnits()).isEqualTo(3);
        assertThat(oldQuery.remainingQuantitySteps()).isEqualTo(10);
        assertThat(oldQuery.cumulativeFeeUnits()).isZero();
        assertThat(index.orders()).containsExactly(index.activeOrder(1));
        index.apply(1, partiallyFilled.withStatus(CoreOrderStatus.CANCELED, 3), identities);
        assertThat(index.activeOrderRuntime(1)).isNull();
        assertThat(index.activeOrderSymbol(1)).isNull();
        assertThat(index.ids(7)).isEmpty();
    }

    @Test
    void runtimeScopeChangesReplaceMembershipWithoutRetainingThePreviousParticipant() {
        var identities = new com.surprising.aeron.service.state.RuntimeIdentityRegistry();
        var index = new ActiveOrderIndex(TradingCoreState.empty(ProductLine.SPOT), identities);
        var first = new CoreOrderState(1, ProductLine.SPOT, 7, "BTC-USDT", 1,
                CoreOrderSide.BUY, 90, 10, 0, 10, false, CoreOrderStatus.OPEN, 1);
        var second = new CoreOrderState(1, ProductLine.SPOT, 8, "ETH-USDT", 1,
                CoreOrderSide.SELL, 110, 10, 0, 10, false, CoreOrderStatus.OPEN, 2);
        index.apply(1, com.surprising.aeron.service.state.RuntimeStateProjector.toRuntimeOrder(first, identities), identities);
        var replacement = com.surprising.aeron.service.state.RuntimeStateProjector.toRuntimeOrder(second, identities);
        index.apply(1, replacement, identities);
        assertThat(index.activeOrderRuntime(1)).isSameAs(replacement);
        assertThat(index.activeOrderSymbol(1)).isEqualTo("ETH-USDT");
        assertThat(index.ids(7)).isEmpty();
        assertThat(index.ids("BTC-USDT")).isEmpty();
        assertThat(index.ids(8)).containsExactly(1L);
        assertThat(index.activeOrder(1)).isEqualTo(second);
    }

    @Test
    void primitiveIntersectionIsIndependentAndMatchesBothIndexDirections() {
        Map<Long, CoreOrderState> orders = new HashMap<>();
        for (long id = 1; id <= 12; id++) {
            long user = id <= 9 ? 11 : 12;
            String symbol = id % 3 == 0 ? "ETH-USDT" : "BTC-USDT";
            orders.put(id, new CoreOrderState(id, ProductLine.SPOT, user, symbol, 1,
                    CoreOrderSide.BUY, 100, 1, 0, 1, false, CoreOrderStatus.OPEN, 1));
        }
        var state = new TradingCoreState(ProductLine.SPOT, 1,
                Map.of(11L, CoreUserState.empty(ProductLine.SPOT, 11),
                        12L, CoreUserState.empty(ProductLine.SPOT, 12)), orders,
                Map.of(), CoreRiskState.empty(), CoreTreasuryState.empty());
        var index = new ActiveOrderIndex(state);
        for (long user : new long[]{11, 12, 99}) {
            for (String symbol : new String[]{"BTC-USDT", "ETH-USDT", "NONE-USDT"}) {
                var first = index.matchingIds(user, symbol);
                var nested = index.matchingIds(user, symbol);
                var actual = new java.util.TreeSet<Long>();
                while (first.hasNext()) {
                    assertThat(first.hasNext()).isTrue();
                    long id = first.next();
                    actual.add(id);
                    assertThat(nested.next()).isEqualTo(id);
                }
                assertThat(nested.hasNext()).isFalse();
                assertThat(actual).containsExactlyElementsOf(index.ids(user, symbol).descendingSet());
                org.assertj.core.api.Assertions.assertThatThrownBy(first::next)
                        .isInstanceOf(java.util.NoSuchElementException.class);
            }
        }
    }

    @Test
    void maintainsRiskAggregatesAcrossOrderUpdates() {
        CoreOrderState opening = new CoreOrderState(7, ProductLine.LINEAR_PERPETUAL, 11, "BTC-USDT", 1,
                CoreOrderSide.BUY, 100, 5, 0, 5, false, CoreMarginMode.CROSS, CorePositionSide.NET,
                CoreOrderStatus.OPEN, 1);
        CoreOrderState reducing = new CoreOrderState(8, ProductLine.LINEAR_PERPETUAL, 11, "BTC-USDT", 1,
                CoreOrderSide.SELL, 100, 3, 0, 3, true, CoreMarginMode.ISOLATED, CorePositionSide.NET,
                CoreOrderStatus.OPEN, 1);
        TradingCoreState before = new TradingCoreState(ProductLine.LINEAR_PERPETUAL, 1,
                Map.of(11L, CoreUserState.empty(ProductLine.LINEAR_PERPETUAL, 11)),
                Map.of(7L, opening, 8L, reducing), Map.of(), CoreRiskState.empty(), CoreTreasuryState.empty());
        ActiveOrderIndex index = new ActiveOrderIndex(before);

        assertThat(index.pendingQuantity(11, "BTC-USDT", CorePositionSide.NET, CoreOrderSide.BUY)).isEqualTo(5);
        assertThat(index.reduceOnlyQuantity(11, "BTC-USDT", CoreOrderSide.SELL)).isEqualTo(3);
        assertThat(index.hasDifferentMarginMode(11, "BTC-USDT", CorePositionSide.NET,
                CoreMarginMode.CROSS)).isTrue();
        AdmissionSummary summary = index.inspect(
                11, "BTC-USDT", CorePositionSide.NET, CoreOrderSide.BUY, CoreMarginMode.ISOLATED);
        assertThat(summary.pendingQuantity()).isEqualTo(5);
        assertThat(summary.reduceOnlyQuantity()).isZero();
        assertThat(summary.marginModeCount()).isEqualTo(1);

        Map<Long, CoreOrderState> orders = StateMapSupport.delta(before.orders());
        orders.put(7L, opening.fill(3));
        orders.put(8L, reducing.cancel());
        TradingCoreState after = new TradingCoreState(ProductLine.LINEAR_PERPETUAL, 2, before.users(), orders,
                before.instruments(), before.riskState(), before.treasuryState(), before.leverages(),
                before.algoOrders(), before.cancelAllAfterTimers(), before.clientOrderIndex(), before.triggerOrders());
        index.rebuild(after);

        assertThat(index.pendingQuantity(11, "BTC-USDT", CorePositionSide.NET, CoreOrderSide.BUY)).isEqualTo(2);
        assertThat(index.reduceOnlyQuantity(11, "BTC-USDT", CoreOrderSide.SELL)).isZero();
        assertThat(index.hasDifferentMarginMode(11, "BTC-USDT", CorePositionSide.NET,
                CoreMarginMode.CROSS)).isFalse();
    }

    @Test
    void rebuildUsesOpenOrderLifecycle() {
        CoreOrderState order = new CoreOrderState(7, ProductLine.SPOT, 11, "BTC-USDT", 1,
                CoreOrderSide.BUY, 100, 5, 0, 5, false, CoreOrderStatus.OPEN, 1);
        TradingCoreState state = new TradingCoreState(ProductLine.SPOT, 1,
                Map.of(11L, CoreUserState.empty(ProductLine.SPOT, 11)), Map.of(7L, order),
                Map.of(), CoreRiskState.empty(), CoreTreasuryState.empty());

        ActiveOrderIndex index = new ActiveOrderIndex(state);
        assertThat(index.ids()).containsExactly(7L);
        assertThat(index.orders()).containsExactly(order);
    }

    @Test
    void pageUsesExclusiveCursorAndTheBoundedLifecycleLimit() {
        Map<Long, CoreOrderState> orders = new HashMap<>();
        for (long orderId = 1; orderId <= 2_049; orderId++) {
            orders.put(orderId, new CoreOrderState(orderId, ProductLine.SPOT, 11, "BTC-USDT", 1,
                    CoreOrderSide.BUY, 100, 1, 0, 1, false, CoreOrderStatus.OPEN, 1));
        }
        TradingCoreState state = new TradingCoreState(ProductLine.SPOT, 1,
                Map.of(11L, CoreUserState.empty(ProductLine.SPOT, 11)), orders,
                Map.of(), CoreRiskState.empty(), CoreTreasuryState.empty());
        ActiveOrderIndex index = new ActiveOrderIndex(state);

        ActiveOrderIndex.Page first = index.page(11, "BTC-USDT", 0, ActiveOrderIndex.MAX_PAGE_SIZE);
        ActiveOrderIndex.Page second = index.page(11, "BTC-USDT", first.nextCursorOrderId(),
                ActiveOrderIndex.MAX_PAGE_SIZE);
        ActiveOrderIndex.Page third = index.page(11, "BTC-USDT", second.nextCursorOrderId(),
                ActiveOrderIndex.MAX_PAGE_SIZE);

        assertThat(first.orderIds()).hasSize(1_024).startsWith(2_049L).endsWith(1_026L);
        assertThat(second.orderIds()).hasSize(1_024).startsWith(1_025L).endsWith(2L);
        assertThat(third.orderIds()).containsExactly(1L);
        assertThat(third.nextCursorOrderId()).isZero();
    }

    @Test
    void primitiveSortedCursorAvoidsBoxedCompatibilityView() {
        CoreOrderState first = new CoreOrderState(7, ProductLine.SPOT, 11, "BTC-USDT", 1,
                CoreOrderSide.BUY, 100, 1, 0, 1, false, CoreOrderStatus.OPEN, 1);
        CoreOrderState second = new CoreOrderState(9, ProductLine.SPOT, 11, "BTC-USDT", 1,
                CoreOrderSide.BUY, 101, 1, 0, 1, false, CoreOrderStatus.OPEN, 1);
        CoreOrderState third = new CoreOrderState(8, ProductLine.SPOT, 12, "BTC-USDT", 1,
                CoreOrderSide.BUY, 102, 1, 0, 1, false, CoreOrderStatus.OPEN, 1);
        TradingCoreState state = new TradingCoreState(ProductLine.SPOT, 1,
                Map.of(11L, CoreUserState.empty(ProductLine.SPOT, 11),
                        12L, CoreUserState.empty(ProductLine.SPOT, 12)),
                Map.of(7L, first, 8L, third, 9L, second), Map.of(), CoreRiskState.empty(),
                CoreTreasuryState.empty());
        ActiveOrderIndex index = new ActiveOrderIndex(state);

        assertThat(index.sortedIdsDescending("BTC-USDT")).containsExactly(9L, 8L, 7L);
        assertThat(index.sortedIdsDescending(11L)).containsExactly(9L, 7L);
        assertThat(index.sortedIdsDescending(999L)).isEmpty();
        assertThat(index.page(0, null, 0, 1).orderIds()).containsExactly(9L);
        assertThat(index.page(0, null, 9, 1).orderIds()).containsExactly(8L);
        assertThat(index.page(11, "BTC-USDT", 0, 1).orderIds()).containsExactly(9L);
        assertThat(index.page(11, "BTC-USDT", 9, 1).orderIds()).containsExactly(7L);
        assertThat(index.page(11, "BTC-USDT", 7, 1).orderIds()).isEmpty();
    }
}
