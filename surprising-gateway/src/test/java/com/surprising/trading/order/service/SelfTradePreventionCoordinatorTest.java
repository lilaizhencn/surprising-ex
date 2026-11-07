package com.surprising.trading.order.service;

import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.OrderType;
import com.surprising.trading.api.model.PlaceOrderRequest;
import com.surprising.trading.api.model.PositionSide;
import com.surprising.trading.api.model.SelfTradePreventionMode;
import com.surprising.trading.api.model.TimeInForce;
import com.surprising.trading.api.model.TriggerPriceSource;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SelfTradePreventionCoordinatorTest {

    private final SelfTradePreventionCoordinator coordinator = new SelfTradePreventionCoordinator();

    private PlaceOrderRequest createRequest(SelfTradePreventionMode mode) {
        return new PlaceOrderRequest(
                8888L, "client-stp-01", "BTC-USDT",
                OrderSide.BUY, OrderType.LIMIT, TimeInForce.GTC,
                600000L, 10L, MarginMode.CROSS, PositionSide.NET,
                false, false, null,
                0L, 0L, TriggerPriceSource.MARK, mode
        );
    }

    @Test
    @DisplayName("STP NONE 模式下允许正常通过")
    void shouldAllowWhenNone() {
        AtomicBoolean canceled = new AtomicBoolean(false);
        boolean allowed = coordinator.applyStpProtection(createRequest(SelfTradePreventionMode.NONE), true, () -> canceled.set(true));
        assertTrue(allowed);
        assertFalse(canceled.get());
    }

    @Test
    @DisplayName("STP EXPIRE_TAKER 模式下抛出异常阻止 Taker 下单")
    void shouldRejectTaker() {
        AtomicBoolean canceled = new AtomicBoolean(false);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                coordinator.applyStpProtection(createRequest(SelfTradePreventionMode.EXPIRE_TAKER), true, () -> canceled.set(true))
        );
        assertTrue(ex.getMessage().contains("EXPIRE_TAKER"));
        assertFalse(canceled.get());
    }

    @Test
    @DisplayName("STP EXPIRE_MAKER 模式下撤销 Maker 挂单并放行 Taker")
    void shouldCancelMakerAndAllowTaker() {
        AtomicBoolean canceled = new AtomicBoolean(false);
        boolean allowed = coordinator.applyStpProtection(createRequest(SelfTradePreventionMode.EXPIRE_MAKER), true, () -> canceled.set(true));
        assertTrue(allowed);
        assertTrue(canceled.get());
    }

    @Test
    @DisplayName("STP EXPIRE_BOTH 模式下撤销 Maker 并终止 Taker")
    void shouldCancelMakerAndRejectTaker() {
        AtomicBoolean canceled = new AtomicBoolean(false);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                coordinator.applyStpProtection(createRequest(SelfTradePreventionMode.EXPIRE_BOTH), true, () -> canceled.set(true))
        );
        assertTrue(ex.getMessage().contains("EXPIRE_BOTH"));
        assertTrue(canceled.get());
    }
}
