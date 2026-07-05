package com.surprising.trading.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.trading.api.model.AlgoOrderStatus;
import com.surprising.trading.api.model.AlgoOrderType;
import com.surprising.trading.api.model.CancelAlgoOrderRequest;
import com.surprising.trading.api.model.CancelOrderRequest;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.OrderResponse;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.OrderStatus;
import com.surprising.trading.api.model.OrderType;
import com.surprising.trading.api.model.PlaceAlgoOrderRequest;
import com.surprising.trading.api.model.PlaceOrderRequest;
import com.surprising.trading.api.model.PositionSide;
import com.surprising.trading.api.model.TimeInForce;
import com.surprising.trading.order.config.TradingOrderProperties;
import com.surprising.trading.order.model.AlgoOrderProgress;
import com.surprising.trading.order.model.AlgoOrderRecord;
import com.surprising.trading.order.model.OrderRecord;
import com.surprising.trading.order.repository.AlgoOrderRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AlgoOrderServiceTest {

    @Test
    void twapRejectsChildQuantityThatCannotFinishInsideDuration() {
        AlgoOrderService service = service(mock(AlgoOrderRepository.class), mock(OrderService.class));

        assertThatThrownBy(() -> service.place(new PlaceAlgoOrderRequest(
                1001L,
                "twap-small-child",
                "BTC-USDT",
                AlgoOrderType.TWAP,
                OrderSide.BUY,
                0L,
                100L,
                10L,
                10L,
                20L,
                MarginMode.CROSS,
                PositionSide.NET,
                false,
                false,
                TimeInForce.IOC,
                null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("childQuantitySteps is too small");
    }

    @Test
    void dueTwapPlacesIocChildOrderThroughOrderService() {
        AlgoOrderRepository repository = mock(AlgoOrderRepository.class);
        OrderService orderService = mock(OrderService.class);
        AlgoOrderService service = service(repository, orderService);
        AlgoOrderRecord record = twapRecord();
        Instant now = Instant.parse("2026-07-05T00:00:00Z");
        when(repository.progress(record.algoOrderId()))
                .thenReturn(new AlgoOrderProgress(0L, 0L, 0, 0, 1));
        when(orderService.place(any())).thenReturn(orderResponse(9001L, "algo-77-1", TimeInForce.IOC));

        service.executeDue(record, now);

        ArgumentCaptor<PlaceOrderRequest> request = ArgumentCaptor.forClass(PlaceOrderRequest.class);
        verify(orderService).place(request.capture());
        assertThat(request.getValue().clientOrderId()).isEqualTo("algo-77-1");
        assertThat(request.getValue().orderType()).isEqualTo(OrderType.MARKET);
        assertThat(request.getValue().timeInForce()).isEqualTo(TimeInForce.IOC);
        assertThat(request.getValue().quantitySteps()).isEqualTo(50L);
        verify(repository).markChildPlaced(eq(record), eq(1), any(OrderResponse.class), eq(now),
                eq(now.plusSeconds(10)));
    }

    @Test
    void icebergWaitsForActiveVisibleChildBeforePlacingNextSlice() {
        AlgoOrderRepository repository = mock(AlgoOrderRepository.class);
        OrderService orderService = mock(OrderService.class);
        AlgoOrderService service = service(repository, orderService);
        AlgoOrderRecord record = icebergRecord();
        Instant now = Instant.parse("2026-07-05T00:00:00Z");
        when(repository.progress(record.algoOrderId()))
                .thenReturn(new AlgoOrderProgress(10L, 40L, 1, 1, 2));

        service.executeDue(record, now);

        verify(orderService, never()).place(any());
        verify(repository).scheduleNext(eq(record.algoOrderId()), eq(AlgoOrderStatus.RUNNING), any(), eq(now));
    }

    @Test
    void cancelAlgoCancelsActiveChildrenAndStopsFutureSlices() {
        AlgoOrderRepository repository = mock(AlgoOrderRepository.class);
        OrderService orderService = mock(OrderService.class);
        AlgoOrderService service = service(repository, orderService);
        AlgoOrderRecord record = icebergRecord();
        AlgoOrderRecord canceled = new AlgoOrderRecord(record.algoOrderId(), record.userId(),
                record.clientAlgoOrderId(), record.symbol(), record.algoType(), record.side(), record.priceTicks(),
                record.quantitySteps(), record.childQuantitySteps(), record.intervalSeconds(), record.durationSeconds(),
                record.marginMode(), record.positionSide(), record.reduceOnly(), record.postOnly(),
                record.timeInForce(), AlgoOrderStatus.CANCELED, record.currentOrderId(), record.rejectReason(),
                record.traceId(), record.startAt(), null, Instant.parse("2026-07-05T00:00:01Z"),
                record.createdAt(), Instant.parse("2026-07-05T00:00:01Z"));
        when(repository.findByAlgoOrderId(record.algoOrderId()))
                .thenReturn(Optional.of(record))
                .thenReturn(Optional.of(canceled));
        when(repository.activeChildOrders(record.algoOrderId())).thenReturn(List.of(childRecord()));
        when(repository.progress(record.algoOrderId()))
                .thenReturn(new AlgoOrderProgress(0L, 50L, 1, 1, 2));

        var response = service.cancel(new CancelAlgoOrderRequest(record.userId(), record.algoOrderId()));

        assertThat(response.status()).isEqualTo(AlgoOrderStatus.CANCELED);
        verify(repository).markCancelRequested(eq(record.algoOrderId()), any());
        verify(orderService).cancel(any(CancelOrderRequest.class));
        verify(repository).markCanceled(eq(record.algoOrderId()), any());
    }

    private AlgoOrderService service(AlgoOrderRepository repository, OrderService orderService) {
        TradingOrderProperties properties = new TradingOrderProperties();
        properties.getAlgo().setMinDurationSeconds(1L);
        properties.getAlgo().setMinIntervalSeconds(1L);
        return new AlgoOrderService(properties, repository, orderService);
    }

    private AlgoOrderRecord twapRecord() {
        Instant now = Instant.parse("2026-07-05T00:00:00Z");
        return new AlgoOrderRecord(77L, 1001L, "twap-1", "BTC-USDT", AlgoOrderType.TWAP,
                OrderSide.BUY, 0L, 100L, 50L, 10L, 20L, MarginMode.CROSS,
                PositionSide.NET, false, false, TimeInForce.IOC, AlgoOrderStatus.PENDING,
                null, null, "trace-1", now, now, null, now, now);
    }

    private AlgoOrderRecord icebergRecord() {
        Instant now = Instant.parse("2026-07-05T00:00:00Z");
        return new AlgoOrderRecord(78L, 1001L, "ice-1", "BTC-USDT", AlgoOrderType.ICEBERG,
                OrderSide.SELL, 600_000L, 100L, 50L, 10L, 20L, MarginMode.CROSS,
                PositionSide.NET, false, true, TimeInForce.GTX, AlgoOrderStatus.RUNNING,
                9001L, null, "trace-1", now, now, null, now, now);
    }

    private OrderResponse orderResponse(long orderId, String clientOrderId, TimeInForce timeInForce) {
        Instant now = Instant.parse("2026-07-05T00:00:00Z");
        return new OrderResponse(orderId, 1001L, clientOrderId, "BTC-USDT", 1L,
                OrderSide.BUY, OrderType.MARKET, timeInForce, 0L, 50L, 0L, 50L,
                MarginMode.CROSS, PositionSide.NET, 0L, 0L, false, false,
                OrderStatus.ACCEPTED, null, now, now);
    }

    private OrderRecord childRecord() {
        Instant now = Instant.parse("2026-07-05T00:00:00Z");
        return new OrderRecord(9001L, 1001L, "algo-78-1", "BTC-USDT", 1L,
                OrderSide.SELL, OrderType.LIMIT, TimeInForce.GTX, 600_000L,
                50L, 0L, 50L, MarginMode.CROSS, PositionSide.NET, 0L, 0L,
                false, true, OrderStatus.ACCEPTED, null, now, now);
    }
}
