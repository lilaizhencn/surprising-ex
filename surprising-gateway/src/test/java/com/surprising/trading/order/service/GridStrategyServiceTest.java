package com.surprising.trading.order.service;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.CancelOrderRequest;
import com.surprising.trading.api.model.OrderResponse;
import com.surprising.trading.api.model.OrderStatus;
import com.surprising.trading.api.model.PlaceOrderRequest;
import com.surprising.trading.order.model.GridStrategyModels.CreateGridStrategyRequest;
import com.surprising.trading.order.model.GridStrategyModels.GridStrategyRecord;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class GridStrategyServiceTest {

    private final OrderService orderService = mock(OrderService.class);
    private final GridStrategyService service = new GridStrategyService(orderService);

    @Test
    @DisplayName("等差网格生成价格阶梯准确")
    void shouldGenerateArithmeticGridPrices() {
        // 下限 10000，上限 20000，网格数 5 -> step 2000
        List<Long> grid = service.generatePriceGrid(10000L, 20000L, 5, true);
        assertEquals(5, grid.size());
        assertEquals(10000L, grid.get(0));
        assertEquals(12000L, grid.get(1));
        assertEquals(14000L, grid.get(2));
        assertEquals(16000L, grid.get(3));
        assertEquals(18000L, grid.get(4));
    }

    @Test
    @DisplayName("等比网格生成价格阶梯准确")
    void shouldGenerateGeometricGridPrices() {
        // 下限 10000，上限 40000，网格数 2 -> ratio = 2.0 -> 10000, 20000
        List<Long> grid = service.generatePriceGrid(10000L, 40000L, 2, false);
        assertEquals(2, grid.size());
        assertEquals(10000L, grid.get(0));
        assertEquals(20000L, grid.get(1));
    }

    @Test
    @DisplayName("成功启动网格并根据市价自动铺设买卖单")
    void shouldCreateAndStartGridStrategy() {
        when(orderService.place(any())).thenAnswer(invocation -> {
            PlaceOrderRequest req = invocation.getArgument(0);
            return new OrderResponse(
                    99901L, req.userId(), req.clientOrderId(), req.instrumentId(),
                    req.side(), req.orderType(), req.timeInForce(), req.priceTicks(),
                    req.quantitySteps(), 0L, req.quantitySteps(), req.marginMode(),
                    req.positionSide(), 0L, 0L, false, true, OrderStatus.ACCEPTED,
                    null, Instant.now(), Instant.now()
            );
        });

        CreateGridStrategyRequest request = new CreateGridStrategyRequest(
                "BTC-USDT", ProductLine.LINEAR_PERPETUAL,
                40000L, 60000L, 4, 10000L, true
        );

        GridStrategyRecord record = service.createAndStartGrid(1001L, request, 50000L);
        assertNotNull(record);
        assertEquals("ACTIVE", record.status());
        assertEquals(1001L, record.userId());

        // 验证自动挂单调用了 4 次（4 个网格阶梯）
        ArgumentCaptor<PlaceOrderRequest> captor = ArgumentCaptor.forClass(PlaceOrderRequest.class);
        verify(orderService, times(4)).place(captor.capture());

        List<PlaceOrderRequest> placed = captor.getAllValues();
        assertEquals(4, placed.size());

        // 验证价格低于 50000 的是 BUY，高于 50000 的是 SELL
        assertTrue(placed.stream().anyMatch(o -> o.priceTicks() < 50000L && o.side() == com.surprising.trading.api.model.OrderSide.BUY));
        assertTrue(placed.stream().anyMatch(o -> o.priceTicks() > 50000L && o.side() == com.surprising.trading.api.model.OrderSide.SELL));
    }

    @Test
    @DisplayName("停止网格策略时自动撤销所有网格活动挂单")
    void shouldStopGridAndCancelOrders() {
        when(orderService.place(any())).thenAnswer(invocation -> {
            PlaceOrderRequest req = invocation.getArgument(0);
            return new OrderResponse(
                    99902L, req.userId(), req.clientOrderId(), req.instrumentId(),
                    req.side(), req.orderType(), req.timeInForce(), req.priceTicks(),
                    req.quantitySteps(), 0L, req.quantitySteps(), req.marginMode(),
                    req.positionSide(), 0L, 0L, false, true, OrderStatus.ACCEPTED,
                    null, Instant.now(), Instant.now()
            );
        });

        CreateGridStrategyRequest request = new CreateGridStrategyRequest(
                "BTC-USDT", ProductLine.LINEAR_PERPETUAL,
                40000L, 60000L, 2, 1000L, true
        );

        GridStrategyRecord record = service.createAndStartGrid(1001L, request, 50000L);
        GridStrategyRecord stopped = service.stopGrid(1001L, record.strategyId());

        assertEquals("STOPPED", stopped.status());
        verify(orderService, atLeastOnce()).cancel(any(CancelOrderRequest.class));
    }
}
