package com.surprising.aeron.service.state;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.product.api.ProductLine;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ActiveOrderIndexTest {

    @Test
    void rebuildUsesOpenOrderLifecycleWhenCompatibilityBookIsEmpty() {
        CoreOrderState order = new CoreOrderState(7, ProductLine.SPOT, 11, "BTC-USDT", 1,
                CoreOrderSide.BUY, 100, 5, 0, 5, false, CoreOrderStatus.OPEN, 1);
        TradingCoreState state = new TradingCoreState(ProductLine.SPOT, 1,
                Map.of(11L, CoreUserState.empty(ProductLine.SPOT, 11)), Map.of(7L, order),
                CoreBookState.empty(), Map.of(), CoreRiskState.empty(), CoreTreasuryState.empty());

        assertThat(new ActiveOrderIndex(state).ids()).containsExactly(7L);
    }
}
