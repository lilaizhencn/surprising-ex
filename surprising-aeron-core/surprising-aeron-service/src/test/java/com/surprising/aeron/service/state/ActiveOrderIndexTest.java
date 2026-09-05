package com.surprising.aeron.service.state;

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
    }
}
