package com.surprising.trading.trigger.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.trading.api.TraceContext;
import com.surprising.trading.api.client.OrderRpcApi;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.OrderResponse;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.OrderStatus;
import com.surprising.trading.api.model.OrderType;
import com.surprising.trading.api.model.PlaceOrderRequest;
import com.surprising.trading.api.model.PlaceTriggerOrderRequest;
import com.surprising.trading.api.model.TimeInForce;
import com.surprising.trading.api.model.TriggerCondition;
import com.surprising.trading.api.model.TriggerOrderResponse;
import com.surprising.trading.api.model.TriggerOrderStatus;
import com.surprising.trading.api.model.TriggerOrderType;
import com.surprising.trading.api.model.TriggerPriceType;
import com.surprising.trading.trigger.config.TriggerProperties;
import com.surprising.trading.trigger.model.MarkTrigger;
import com.surprising.trading.trigger.model.TriggerOrderRecord;
import com.surprising.trading.trigger.repository.TriggerOrderRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TriggerOrderServiceTest {

    @AfterEach
    void clearTrace() {
        TraceContext.clear();
    }

    @Test
    void placeDerivesTakeProfitCloseLongCondition() {
        TriggerOrderRepository repository = mock(TriggerOrderRepository.class);
        TriggerOrderService service = new TriggerOrderService(repository, mock(OrderRpcApi.class),
                new TriggerProperties());
        PlaceTriggerOrderRequest request = new PlaceTriggerOrderRequest(1001L, "tp-1", "btc-usdt",
                OrderSide.SELL, TriggerOrderType.TAKE_PROFIT, TriggerPriceType.MARK_PRICE, 70_000L,
                OrderType.LIMIT, TimeInForce.GTC, 69_950L, 10L, MarginMode.CROSS, null);
        when(repository.nextSequence("trigger-order")).thenReturn(501L);
        when(repository.insert(any())).thenReturn(true);
        ArgumentCaptor<TriggerOrderRecord> orderCaptor = ArgumentCaptor.forClass(TriggerOrderRecord.class);

        TriggerOrderResponse response = service.place(request);

        assertThat(response.triggerOrderId()).isEqualTo(501L);
        verify(repository).insert(orderCaptor.capture());
        TriggerOrderRecord saved = orderCaptor.getValue();
        assertThat(saved.symbol()).isEqualTo("BTC-USDT");
        assertThat(saved.triggerCondition()).isEqualTo(TriggerCondition.GREATER_OR_EQUAL);
        assertThat(saved.status()).isEqualTo(TriggerOrderStatus.PENDING);
        assertThat(saved.traceId()).isNotBlank();
    }

    @Test
    void placeReturnsExistingClientTriggerOrder() {
        TriggerOrderRepository repository = mock(TriggerOrderRepository.class);
        TriggerOrderService service = new TriggerOrderService(repository, mock(OrderRpcApi.class),
                new TriggerProperties());
        TriggerOrderRecord existing = record(501L, TriggerOrderStatus.PENDING);
        when(repository.findByClientTriggerOrderId(1001L, "sl-1")).thenReturn(Optional.of(existing));

        TriggerOrderResponse response = service.place(new PlaceTriggerOrderRequest(1001L, "sl-1", "BTC-USDT",
                OrderSide.SELL, TriggerOrderType.STOP_LOSS, TriggerPriceType.MARK_PRICE, 60_000L,
                OrderType.MARKET, TimeInForce.IOC, 0L, 10L, MarginMode.CROSS, null));

        assertThat(response.triggerOrderId()).isEqualTo(501L);
        assertThat(response.status()).isEqualTo(TriggerOrderStatus.PENDING);
    }

    @Test
    void onMarkPricePlacesReduceOnlyOrderAndMarksTriggered() {
        TriggerOrderRepository repository = mock(TriggerOrderRepository.class);
        OrderRpcApi orderRpcApi = mock(OrderRpcApi.class);
        TriggerOrderService service = new TriggerOrderService(repository, orderRpcApi, new TriggerProperties());
        TriggerOrderRecord claimed = record(501L, TriggerOrderStatus.TRIGGERING);
        when(repository.markPriceTicks("BTC-USDT", 42L)).thenReturn(OptionalLong.of(70_001L));
        when(repository.claimTriggered(eq("BTC-USDT"), eq(70_001L), eq(42L), any(), anyInt(), any()))
                .thenReturn(List.of(claimed));
        when(orderRpcApi.place(any())).thenReturn(orderResponse(9001L, OrderStatus.ACCEPTED, null));
        ArgumentCaptor<PlaceOrderRequest> orderCaptor = ArgumentCaptor.forClass(PlaceOrderRequest.class);

        service.onMarkPrice(new MarkTrigger("BTC-USDT", 42L, Instant.parse("2026-07-01T00:00:00Z")));

        verify(orderRpcApi).place(orderCaptor.capture());
        assertThat(orderCaptor.getValue().clientOrderId()).isEqualTo("trigger-501");
        assertThat(orderCaptor.getValue().reduceOnly()).isTrue();
        assertThat(orderCaptor.getValue().postOnly()).isFalse();
        verify(repository).markTriggered(eq(501L), eq(9001L), any());
    }

    @Test
    void rejectedExecutionMarksTriggerFailed() {
        TriggerOrderRepository repository = mock(TriggerOrderRepository.class);
        OrderRpcApi orderRpcApi = mock(OrderRpcApi.class);
        TriggerOrderService service = new TriggerOrderService(repository, orderRpcApi, new TriggerProperties());
        TriggerOrderRecord claimed = record(501L, TriggerOrderStatus.TRIGGERING);
        when(repository.markPriceTicks("BTC-USDT", 42L)).thenReturn(OptionalLong.of(50_000L));
        when(repository.claimTriggered(eq("BTC-USDT"), eq(50_000L), eq(42L), any(), anyInt(), any()))
                .thenReturn(List.of(claimed));
        when(orderRpcApi.place(any())).thenReturn(orderResponse(9002L, OrderStatus.REJECTED,
                "reduce-only requires an open position"));

        service.onMarkPrice(new MarkTrigger("BTC-USDT", 42L, Instant.parse("2026-07-01T00:00:00Z")));

        verify(repository).markTriggerFailed(eq(501L), eq(9002L),
                eq("reduce-only requires an open position"), any());
    }

    private TriggerOrderRecord record(long triggerOrderId, TriggerOrderStatus status) {
        Instant now = Instant.parse("2026-07-01T00:00:00Z");
        return new TriggerOrderRecord(triggerOrderId, 1001L, "tp-1", "BTC-USDT", OrderSide.SELL,
                TriggerOrderType.TAKE_PROFIT, TriggerPriceType.MARK_PRICE, TriggerCondition.GREATER_OR_EQUAL,
                70_000L, OrderType.MARKET, TimeInForce.IOC, 0L, 10L, MarginMode.CROSS, status, null,
                null, null, null, "trace-501", null, null, now, now);
    }

    private OrderResponse orderResponse(long orderId, OrderStatus status, String rejectReason) {
        Instant now = Instant.parse("2026-07-01T00:00:00Z");
        return new OrderResponse(orderId, 1001L, "trigger-501", "BTC-USDT", 1L, OrderSide.SELL,
                OrderType.MARKET, TimeInForce.IOC, 0L, 10L, 0L, 10L, MarginMode.CROSS, 0L, 0L,
                true, false, status, rejectReason, now, now);
    }
}
