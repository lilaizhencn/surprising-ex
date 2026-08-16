package com.surprising.aeron.service.state;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.product.api.ProductLine;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ActiveOrderIndexTest {

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
}
