package com.surprising.aeron.service.state.index;

import com.surprising.aeron.service.state.CoreTreasuryState;
import com.surprising.aeron.service.state.CoreUserState;
import com.surprising.aeron.service.state.RuntimeOrderAdmission;
import com.surprising.aeron.service.state.StateMapSupport;
import com.surprising.aeron.service.state.TradingCoreState;
import com.surprising.aeron.service.state.TradingDependencyMask;

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
    void crossingParticipantMasksMatchOrdersThroughPriceChangesRemovalsAndRebuild() {
        var random = new java.util.Random(8191);
        var orders = new HashMap<Long, CoreOrderState>();
        var index = new ActiveOrderIndex(TradingCoreState.empty(ProductLine.SPOT));
        for (int step = 0; step < 2_000; step++) {
            long id = 1 + random.nextInt(150);
            if (random.nextInt(4) == 0) {
                orders.remove(id); index.applySnapshot(id, null);
            } else {
                var order = new CoreOrderState(id, ProductLine.SPOT, 1 + random.nextInt(200), "BTC-USDT", 1,
                        random.nextBoolean() ? CoreOrderSide.BUY : CoreOrderSide.SELL,
                        1 + random.nextInt(100), 2, 0, 2, false, CoreOrderStatus.OPEN, 1);
                orders.put(id, order); index.applySnapshot(id, order);
            }
            if (step % 71 == 0) {
                var users = new HashMap<Long, CoreUserState>();
                orders.values().forEach(order -> users.put(order.userId(), CoreUserState.empty(ProductLine.SPOT, order.userId())));
                index.rebuild(new TradingCoreState(ProductLine.SPOT, 1, users,
                        orders, Map.of(), CoreRiskState.empty(), CoreTreasuryState.empty()));
            }
            for (var side : CoreOrderSide.values()) {
                for (long price : new long[]{0, 1, 50, 100, Long.MAX_VALUE}) {
                    long expected = 0;
                    for (var order : orders.values()) {
                        if (order.side() != side && (price == 0 || (side == CoreOrderSide.BUY
                                ? order.matchingPriceTicks() <= price : order.matchingPriceTicks() >= price)))
                            expected |= TradingDependencyMask.account(order.userId());
                    }
                    assertThat(index.counterpartyMask("BTC-USDT", side, price)).isEqualTo(expected);
                    for (long user : new long[]{1, 11, 23, 77, 150, 200}) {
                        boolean present = orders.values().stream().anyMatch(order -> order.userId() == user
                                && order.side() != side && (price == 0 || (side == CoreOrderSide.BUY
                                ? order.matchingPriceTicks() <= price : order.matchingPriceTicks() >= price)));
                        assertThat(index.hasCounterparty("BTC-USDT", side, price, user)).isEqualTo(present);
                    }
                }
            }
        }
        for (long id : orders.keySet()) index.applySnapshot(id, null);
        assertThat(index.counterpartyMask("BTC-USDT", CoreOrderSide.BUY, 0)).isZero();
        assertThat(index.counterpartyMask("BTC-USDT", CoreOrderSide.SELL, 0)).isZero();
    }

    @Test
    void participantMaskRetainsCollidingAccountsUntilTheirLastOrderIsRemoved() {
        long first = 11, colliding = first + 1;
        while (TradingDependencyMask.account(colliding) != TradingDependencyMask.account(first)) colliding++;
        var index = new ActiveOrderIndex(TradingCoreState.empty(ProductLine.SPOT));
        var a = new CoreOrderState(1, ProductLine.SPOT, first, "BTC-USDT", 1,
                CoreOrderSide.BUY, 100, 2, 0, 2, false, CoreOrderStatus.OPEN, 1);
        var b = new CoreOrderState(2, ProductLine.SPOT, colliding, "BTC-USDT", 1,
                CoreOrderSide.SELL, 100, 2, 0, 2, false, CoreOrderStatus.OPEN, 1);
        index.applySnapshot(1, a); index.applySnapshot(2, b);
        assertThat(index.hasCounterparty("BTC-USDT", CoreOrderSide.BUY, 100, first)).isFalse();
        assertThat(index.hasCounterparty("BTC-USDT", CoreOrderSide.BUY, 100, colliding)).isTrue();
        long mask = TradingDependencyMask.account(first);
        assertThat(participantMask(index)).isEqualTo(mask);
        index.applySnapshot(1, a.fill(1));
        assertThat(participantMask(index)).isEqualTo(mask);
        index.applySnapshot(1, null);
        assertThat(participantMask(index)).isEqualTo(mask);
        index.applySnapshot(2, null);
        assertThat(participantMask(index)).isZero();
        index.rebuild(new TradingCoreState(ProductLine.SPOT, 1,
                Map.of(first, CoreUserState.empty(ProductLine.SPOT, first)), Map.of(1L, a),
                Map.of(), CoreRiskState.empty(), CoreTreasuryState.empty()));
        assertThat(participantMask(index)).isEqualTo(mask);
    }

    private static long participantMask(ActiveOrderIndex index) {
        return index.counterpartyMask("BTC-USDT", CoreOrderSide.BUY, 0)
                | index.counterpartyMask("BTC-USDT", CoreOrderSide.SELL, 0);
    }

    @Test
    void exactCounterpartyOverlapTracksCollisionsPricesAndReferenceCounts() {
        long first = 1, collision = 2;
        while (TradingDependencyMask.account(first) != TradingDependencyMask.account(collision)) collision++;
        var index = new ActiveOrderIndex(TradingCoreState.empty(ProductLine.SPOT));
        index.applySnapshot(1, new CoreOrderState(1, ProductLine.SPOT, first, "BTC-USDT", 1,
                CoreOrderSide.SELL, 100, 2, 0, 2, false, CoreOrderStatus.OPEN, 1));
        index.applySnapshot(2, new CoreOrderState(2, ProductLine.SPOT, collision, "ETH-USDT", 1,
                CoreOrderSide.SELL, 100, 2, 0, 2, false, CoreOrderStatus.OPEN, 1));
        assertThat(index.counterpartiesOverlap("BTC-USDT", CoreOrderSide.BUY, 0, "ETH-USDT", CoreOrderSide.BUY, 0)).isFalse();
        for (long id : new long[]{3, 4}) index.applySnapshot(id, new CoreOrderState(id, ProductLine.SPOT, first,
                "ETH-USDT", 1, CoreOrderSide.SELL, 101, 2, 0, 2, false, CoreOrderStatus.OPEN, 1));
        assertThat(index.counterpartiesOverlap("BTC-USDT", CoreOrderSide.BUY, 100, "ETH-USDT", CoreOrderSide.BUY, 100)).isFalse();
        assertThat(index.counterpartiesOverlap("BTC-USDT", CoreOrderSide.BUY, 100, "ETH-USDT", CoreOrderSide.BUY, 101)).isTrue();
        index.applySnapshot(3, null);
        assertThat(index.counterpartiesOverlap("BTC-USDT", CoreOrderSide.BUY, 0, "ETH-USDT", CoreOrderSide.BUY, 0)).isTrue();
        index.applySnapshot(4, null);
        assertThat(index.counterpartiesOverlap("BTC-USDT", CoreOrderSide.BUY, 0, "ETH-USDT", CoreOrderSide.BUY, 0)).isFalse();
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
        RuntimeOrderAdmission.AdmissionSummary summary = index.inspect(
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
