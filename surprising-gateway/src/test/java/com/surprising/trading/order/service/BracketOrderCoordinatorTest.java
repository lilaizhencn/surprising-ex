package com.surprising.trading.order.service;

import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.OrderResponse;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.OrderStatus;
import com.surprising.trading.api.model.OrderType;
import com.surprising.trading.api.model.PlaceOrderRequest;
import com.surprising.trading.api.model.PlaceTriggerOrderRequest;
import com.surprising.trading.api.model.PositionSide;
import com.surprising.trading.api.model.SelfTradePreventionMode;
import com.surprising.trading.api.model.TimeInForce;
import com.surprising.trading.api.model.TriggerOrderType;
import com.surprising.trading.api.model.TriggerPriceSource;
import com.surprising.trading.trigger.service.TriggerOrderService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BracketOrderCoordinatorTest {

    @Test
    @DisplayName("主订单即时成交时自动部署止盈和止损触发单")
    void shouldDeployBracketTriggersOnImmediateFill() {
        TriggerOrderService triggerService = mock(TriggerOrderService.class);
        BracketOrderCoordinator coordinator = new BracketOrderCoordinator(triggerService);

        // 买单开多，入场价 50000，止盈 55000，止损 48000
        PlaceOrderRequest request = new PlaceOrderRequest(
                1001L, "client-001", "BTC-USDT",
                OrderSide.BUY, OrderType.LIMIT, TimeInForce.GTC,
                500000L, 10L, MarginMode.CROSS, PositionSide.NET,
                false, false, null,
                550000L, 480000L, TriggerPriceSource.MARK, SelfTradePreventionMode.NONE
        );

        OrderResponse response = new OrderResponse(
                12345L, 1001L, "client-001", "BTC-USDT",
                OrderSide.BUY, OrderType.LIMIT, TimeInForce.GTC,
                500000L, 10L, 10L, 0L,
                MarginMode.CROSS, PositionSide.NET,
                100L, 200L, false, false,
                OrderStatus.FILLED, null,
                Instant.now(), Instant.now()
        );

        coordinator.registerBracket(request, response);

        ArgumentCaptor<PlaceTriggerOrderRequest> captor = ArgumentCaptor.forClass(PlaceTriggerOrderRequest.class);
        verify(triggerService, times(2)).place(captor.capture());

        List<PlaceTriggerOrderRequest> placed = captor.getAllValues();
        assertEquals(2, placed.size());

        // 校验止盈单 (平仓方向应为 SELL)
        PlaceTriggerOrderRequest tp = placed.stream()
                .filter(r -> r.triggerType() == TriggerOrderType.TAKE_PROFIT)
                .findFirst().orElseThrow();
        assertEquals(OrderSide.SELL, tp.side());
        assertEquals(550000L, tp.triggerPriceTicks());
        assertEquals(10L, tp.quantitySteps());
        assertEquals(1001L, tp.userId());

        // 校验止损单 (平仓方向应为 SELL)
        PlaceTriggerOrderRequest sl = placed.stream()
                .filter(r -> r.triggerType() == TriggerOrderType.STOP_LOSS)
                .findFirst().orElseThrow();
        assertEquals(OrderSide.SELL, sl.side());
        assertEquals(480000L, sl.triggerPriceTicks());
        assertEquals(10L, sl.quantitySteps());
        assertEquals(1001L, sl.userId());

        // 校验两者归属同一个 OCO 组
        assertEquals(tp.ocoGroupId(), sl.ocoGroupId());
    }

    @Test
    @DisplayName("卖单开空即时成交时平仓方向为 BUY")
    void shouldDeployBracketTriggersForSellOrder() {
        TriggerOrderService triggerService = mock(TriggerOrderService.class);
        BracketOrderCoordinator coordinator = new BracketOrderCoordinator(triggerService);

        // 卖单开空，入场价 50000，止盈 45000，止损 52000
        PlaceOrderRequest request = new PlaceOrderRequest(
                1002L, "client-002", "BTC-USDT",
                OrderSide.SELL, OrderType.MARKET, TimeInForce.IOC,
                500000L, 5L, MarginMode.CROSS, PositionSide.NET,
                false, false, null,
                450000L, 520000L, TriggerPriceSource.MARK, SelfTradePreventionMode.NONE
        );

        OrderResponse response = new OrderResponse(
                12346L, 1002L, "client-002", "BTC-USDT",
                OrderSide.SELL, OrderType.MARKET, TimeInForce.IOC,
                500000L, 5L, 5L, 0L,
                MarginMode.CROSS, PositionSide.NET,
                100L, 200L, false, false,
                OrderStatus.FILLED, null,
                Instant.now(), Instant.now()
        );

        coordinator.registerBracket(request, response);

        ArgumentCaptor<PlaceTriggerOrderRequest> captor = ArgumentCaptor.forClass(PlaceTriggerOrderRequest.class);
        verify(triggerService, times(2)).place(captor.capture());

        for (PlaceTriggerOrderRequest trigger : captor.getAllValues()) {
            assertEquals(OrderSide.BUY, trigger.side());
            assertEquals(5L, trigger.quantitySteps());
        }
    }
}
