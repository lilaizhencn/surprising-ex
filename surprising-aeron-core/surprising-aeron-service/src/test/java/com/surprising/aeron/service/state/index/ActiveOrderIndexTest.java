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
import com.surprising.aeron.protocol.RegisterInstrumentCommand;
import com.surprising.aeron.service.state.instrument.CoreInstrument;
import com.surprising.instrument.api.model.ContractType;
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
                var order = new CoreOrderState(i + 1, ProductLine.SPOT, i < 2 ? 7 : 8, "1", CoreOrderSide.BUY, 90, 10, 0, 10, false, CoreOrderStatus.OPEN, 1);
                orders[i] = com.surprising.aeron.service.state.RuntimeStateProjector.toRuntimeOrder(
                        order, identities, instrument(order.instrumentId()));
                index.apply(i + 1, orders[i], identities);
            }
            var terminal = status == CoreOrderStatus.FILLED
                    ? orders[0].withFill(10, 0, 0, status, 2) : orders[0].withStatus(status, 2);
            index.apply(1, terminal, identities);
            index.apply(1, terminal, identities);
            index.apply(1, null, identities);
            index.apply(999, null, identities);
            assertThat(index.activeOrderRoute(1)).isNull();
            assertThat(index.ids(7)).containsExactly(2L);
            assertThat(index.ids(8)).containsExactly(3L);
            assertThat(index.ids("1")).containsExactly(3L, 2L);
            index.apply(2, null, identities);
            index.apply(3, null, identities);
            assertThat(index.count()).isZero();
            assertThat(index.ids(7)).isEmpty();
            assertThat(index.ids(8)).isEmpty();
            assertThat(index.ids("1")).isEmpty();
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
            var order = new CoreOrderState(id, ProductLine.SPOT, 7, "1", CoreOrderSide.BUY, 90, 10, 0, 10, false, CoreOrderStatus.OPEN, 1);
            index.apply(id, com.surprising.aeron.service.state.RuntimeStateProjector.toRuntimeOrder(
                    order, identities, instrument(order.instrumentId())), identities);
        }
        Object storage = valuesField.get(entries);
        for (long id = 33; id <= 4096; id++) {
            index.apply(id - 32, null, identities);
            var order = new CoreOrderState(id, ProductLine.SPOT, 7, "1", CoreOrderSide.BUY, 90, 10, 0, 10, false, CoreOrderStatus.OPEN, 1);
            index.apply(id, com.surprising.aeron.service.state.RuntimeStateProjector.toRuntimeOrder(
                    order, identities, instrument(order.instrumentId())), identities);
        }
        assertThat(valuesField.get(entries)).isSameAs(storage);
        var cursor = index.matchingIds(7, "1");
        var actual = new java.util.HashSet<Long>();
        while (cursor.hasNext()) {
            actual.add(cursor.next());
            assertThat(index.sortedIds(7, "1")).hasSize(32);
            assertThat(index.page(0, null, 0, 16).orderIds()).hasSize(16);
        }
        assertThat(actual).hasSize(32).allMatch(id -> id > 4064 && id <= 4096);
        assertThat(index.count()).isEqualTo(32);
        for (long id = 4065; id <= 4096; id++) index.apply(id, null, identities);
        assertThat(index.count()).isZero();
        assertThat(index.ids(7)).isEmpty();
        assertThat(index.ids("1")).isEmpty();
    }

    @Test
    void runtimeUpdatesRetainRoutingOnlyAndRetireTerminalMembership() {
        var identities = new com.surprising.aeron.service.state.RuntimeIdentityRegistry();
        var index = new ActiveOrderIndex(TradingCoreState.empty(ProductLine.SPOT), identities);
        var initial = new CoreOrderState(1, ProductLine.SPOT, 7, "1", CoreOrderSide.BUY, 90, 10, 0, 10, false, CoreOrderStatus.OPEN, 1);
        var order = com.surprising.aeron.service.state.RuntimeStateProjector.toRuntimeOrder(initial, identities, instrument("1"));
        index.apply(1, order, identities);
        var route = index.activeOrderRoute(1);
        assertThat(route.userId()).isEqualTo(7);
        assertThat(route.instrumentId()).isEqualTo("1");
        assertThat(java.util.Arrays.stream(route.getClass().getDeclaredFields()).map(field -> field.getType().getName()))
                .doesNotContain(com.surprising.aeron.service.state.OrderRuntime.class.getName());
        index.apply(1, order.withFill(4, 6, 3, CoreOrderStatus.OPEN, 2), identities);
        assertThat(index.activeOrderRoute(1)).isSameAs(route);
        assertThat(index.page(7, "1", 0, 10).orderIds()).containsExactly(1L);
        index.apply(1, order.withStatus(CoreOrderStatus.CANCELED, 3), identities);
        assertThat(index.activeOrderRoute(1)).isNull();
        assertThat(index.ids(7)).isEmpty();
    }

    @Test
    void runtimeScopeChangesReplaceMembershipWithoutRetainingThePreviousParticipant() {
        var identities = new com.surprising.aeron.service.state.RuntimeIdentityRegistry();
        var index = new ActiveOrderIndex(TradingCoreState.empty(ProductLine.SPOT), identities);
        var first = new CoreOrderState(1, ProductLine.SPOT, 7, "1", CoreOrderSide.BUY, 90, 10, 0, 10, false, CoreOrderStatus.OPEN, 1);
        var second = new CoreOrderState(1, ProductLine.SPOT, 8, "2", CoreOrderSide.SELL, 110, 10, 0, 10, false, CoreOrderStatus.OPEN, 2);
        index.apply(1, com.surprising.aeron.service.state.RuntimeStateProjector.toRuntimeOrder(
                first, identities, instrument(first.instrumentId())), identities);
        var replacement = com.surprising.aeron.service.state.RuntimeStateProjector.toRuntimeOrder(
                second, identities, instrument(second.instrumentId()));
        index.apply(1, replacement, identities);
        assertThat(index.activeOrderRoute(1).userId()).isEqualTo(8);
        assertThat(index.activeOrderSymbol(1)).isEqualTo("2");
        assertThat(index.ids(7)).isEmpty();
        assertThat(index.ids("1")).isEmpty();
        assertThat(index.ids(8)).containsExactly(1L);
        assertThat(index.activeOrderRoute(1).instrumentId()).isEqualTo("2");
    }

    @Test
    void primitiveIntersectionIsIndependentAndMatchesBothIndexDirections() {
        Map<Long, CoreOrderState> orders = new HashMap<>();
        for (long id = 1; id <= 12; id++) {
            long user = id <= 9 ? 11 : 12;
            String instrumentId = id % 3 == 0 ? "2" : "1";
            orders.put(id, new CoreOrderState(id, ProductLine.SPOT, user, instrumentId, CoreOrderSide.BUY, 100, 1, 0, 1, false, CoreOrderStatus.OPEN, 1));
        }
        var state = new TradingCoreState(ProductLine.SPOT, 1,
                Map.of(11L, CoreUserState.empty(ProductLine.SPOT, 11),
                        12L, CoreUserState.empty(ProductLine.SPOT, 12)), orders,
                instruments(ProductLine.SPOT, "1", "2"), CoreRiskState.empty(), CoreTreasuryState.empty());
        var index = new ActiveOrderIndex(state);
        for (long user : new long[]{11, 12, 99}) {
            for (String instrumentId : new String[]{"1", "2", "24"}) {
                var first = index.matchingIds(user, instrumentId);
                var nested = index.matchingIds(user, instrumentId);
                var actual = new java.util.TreeSet<Long>();
                while (first.hasNext()) {
                    assertThat(first.hasNext()).isTrue();
                    long id = first.next();
                    actual.add(id);
                    assertThat(nested.next()).isEqualTo(id);
                }
                assertThat(nested.hasNext()).isFalse();
                assertThat(actual).containsExactlyElementsOf(index.ids(user, instrumentId).descendingSet());
                org.assertj.core.api.Assertions.assertThatThrownBy(first::next)
                        .isInstanceOf(java.util.NoSuchElementException.class);
            }
        }
    }

    @Test
    void maintainsRiskAggregatesAcrossOrderUpdates() {
        CoreOrderState opening = new CoreOrderState(7, ProductLine.LINEAR_PERPETUAL, 11, "1", CoreOrderSide.BUY, 100, 5, 0, 5, false, CoreMarginMode.CROSS, CorePositionSide.NET,
                CoreOrderStatus.OPEN, 1);
        CoreOrderState reducing = new CoreOrderState(8, ProductLine.LINEAR_PERPETUAL, 11, "1", CoreOrderSide.SELL, 100, 3, 0, 3, true, CoreMarginMode.ISOLATED, CorePositionSide.NET,
                CoreOrderStatus.OPEN, 1);
        TradingCoreState before = new TradingCoreState(ProductLine.LINEAR_PERPETUAL, 1,
                Map.of(11L, CoreUserState.empty(ProductLine.LINEAR_PERPETUAL, 11)),
                Map.of(7L, opening, 8L, reducing), instruments(ProductLine.LINEAR_PERPETUAL, "1"),
                CoreRiskState.empty(), CoreTreasuryState.empty());
        var identities = new com.surprising.aeron.service.state.RuntimeIdentityRegistry();
        try (var runtime = com.surprising.aeron.service.state.RuntimeStateProjector.project(before, identities)) {
            var laneOrders = runtime.admissionOrders(11, identities.symbolId("1"));
            var summary = laneOrders.inspect(11, "1", CorePositionSide.NET, CoreOrderSide.BUY, CoreMarginMode.ISOLATED);
            assertThat(summary.pendingQuantity()).isEqualTo(5);
            assertThat(summary.reduceOnlyQuantity()).isZero();
            assertThat(summary.marginModeCount()).isEqualTo(1);
            runtime.executeUserSettlement(11, () -> {
                runtime.replaceOrder(com.surprising.aeron.service.state.RuntimeStateProjector.toRuntimeOrder(
                        opening.fill(3), identities, runtime.instrument("1")));
                runtime.replaceOrder(com.surprising.aeron.service.state.RuntimeStateProjector.toRuntimeOrder(
                        reducing.cancel(), identities, runtime.instrument("1")));
                return null;
            });
            summary = laneOrders.inspect(11, "1", CorePositionSide.NET, CoreOrderSide.BUY, CoreMarginMode.ISOLATED);
            assertThat(summary.pendingQuantity()).isEqualTo(2);
            assertThat(summary.reduceOnlyQuantity()).isZero();
            assertThat(summary.marginModeCount()).isZero();
        }
    }

    @Test
    void rebuildUsesOpenOrderLifecycle() {
        CoreOrderState order = new CoreOrderState(7, ProductLine.SPOT, 11, "1", CoreOrderSide.BUY, 100, 5, 0, 5, false, CoreOrderStatus.OPEN, 1);
        TradingCoreState state = new TradingCoreState(ProductLine.SPOT, 1,
                Map.of(11L, CoreUserState.empty(ProductLine.SPOT, 11)), Map.of(7L, order),
                instruments(ProductLine.SPOT, "1"), CoreRiskState.empty(), CoreTreasuryState.empty());

        ActiveOrderIndex index = new ActiveOrderIndex(state);
        assertThat(index.ids()).containsExactly(7L);
        assertThat(index.activeOrderRoute(7).userId()).isEqualTo(11);
    }

    @Test
    void pageUsesExclusiveCursorAndTheBoundedLifecycleLimit() {
        Map<Long, CoreOrderState> orders = new HashMap<>();
        for (long orderId = 1; orderId <= 2_049; orderId++) {
            orders.put(orderId, new CoreOrderState(orderId, ProductLine.SPOT, 11, "1", CoreOrderSide.BUY, 100, 1, 0, 1, false, CoreOrderStatus.OPEN, 1));
        }
        TradingCoreState state = new TradingCoreState(ProductLine.SPOT, 1,
                Map.of(11L, CoreUserState.empty(ProductLine.SPOT, 11)), orders,
                instruments(ProductLine.SPOT, "1"), CoreRiskState.empty(), CoreTreasuryState.empty());
        ActiveOrderIndex index = new ActiveOrderIndex(state);

        ActiveOrderIndex.Page first = index.page(11, "1", 0, ActiveOrderIndex.MAX_PAGE_SIZE);
        ActiveOrderIndex.Page second = index.page(11, "1", first.nextCursorOrderId(),
                ActiveOrderIndex.MAX_PAGE_SIZE);
        ActiveOrderIndex.Page third = index.page(11, "1", second.nextCursorOrderId(),
                ActiveOrderIndex.MAX_PAGE_SIZE);

        assertThat(first.orderIds()).hasSize(1_024).startsWith(2_049L).endsWith(1_026L);
        assertThat(second.orderIds()).hasSize(1_024).startsWith(1_025L).endsWith(2L);
        assertThat(third.orderIds()).containsExactly(1L);
        assertThat(third.nextCursorOrderId()).isZero();
    }

    @Test
    void primitiveSortedCursorAvoidsBoxedCompatibilityView() {
        CoreOrderState first = new CoreOrderState(7, ProductLine.SPOT, 11, "1", CoreOrderSide.BUY, 100, 1, 0, 1, false, CoreOrderStatus.OPEN, 1);
        CoreOrderState second = new CoreOrderState(9, ProductLine.SPOT, 11, "1", CoreOrderSide.BUY, 101, 1, 0, 1, false, CoreOrderStatus.OPEN, 1);
        CoreOrderState third = new CoreOrderState(8, ProductLine.SPOT, 12, "1", CoreOrderSide.BUY, 102, 1, 0, 1, false, CoreOrderStatus.OPEN, 1);
        TradingCoreState state = new TradingCoreState(ProductLine.SPOT, 1,
                Map.of(11L, CoreUserState.empty(ProductLine.SPOT, 11),
                        12L, CoreUserState.empty(ProductLine.SPOT, 12)),
                Map.of(7L, first, 8L, third, 9L, second), instruments(ProductLine.SPOT, "1"), CoreRiskState.empty(),
                CoreTreasuryState.empty());
        ActiveOrderIndex index = new ActiveOrderIndex(state);

        assertThat(index.sortedIdsDescending("1")).containsExactly(9L, 8L, 7L);
        assertThat(index.sortedIdsDescending(11L)).containsExactly(9L, 7L);
        assertThat(index.sortedIdsDescending(999L)).isEmpty();
        assertThat(index.page(0, null, 0, 1).orderIds()).containsExactly(9L);
        assertThat(index.page(0, null, 9, 1).orderIds()).containsExactly(8L);
        assertThat(index.page(11, "1", 0, 1).orderIds()).containsExactly(9L);
        assertThat(index.page(11, "1", 9, 1).orderIds()).containsExactly(7L);
        assertThat(index.page(11, "1", 7, 1).orderIds()).isEmpty();
    }

    private static CoreInstrument instrument(String instrumentId) {
        String baseAsset = (instrumentId.equals("1") ? "BTC" : "ETH");
        return CoreInstrument.from(ProductLine.SPOT,
                new RegisterInstrumentCommand(instrumentId, ContractType.SPOT.ordinal(), baseAsset,
                        "USDT", "USDT", 1, 1, 1, 100_000, 50_000, 0, 0, 0, -1, 0));
    }

    private static Map<String, CoreInstrument> instruments(ProductLine productLine, String... symbols) {
        Map<String, CoreInstrument> instruments = new HashMap<>();
        for (String instrumentId : symbols) {
            String baseAsset = (instrumentId.equals("1") ? "BTC" : "ETH");
            ContractType type = ContractType.valueOf(productLine.contractTypeCode());
            instruments.put(instrumentId, CoreInstrument.from(productLine,
                    new RegisterInstrumentCommand(instrumentId, type.ordinal(), baseAsset, "USDT",
                            type.isInverse() ? baseAsset : "USDT", 1, 1,
                            type.isInverse() ? 1_000 : 1, 100_000, 50_000, 0, 0,
                            type.isDelivery() || type.isOption() ? 2_000_000_000_000L : 0,
                            type.isOption() ? 0 : -1, type.isOption() ? 100 : 0)));
        }
        return Map.copyOf(instruments);
    }
}
