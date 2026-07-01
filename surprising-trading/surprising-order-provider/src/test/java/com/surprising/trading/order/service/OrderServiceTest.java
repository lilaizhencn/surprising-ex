package com.surprising.trading.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.trading.api.TraceContext;
import com.surprising.trading.api.model.OrderCommandEvent;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.OrderEvent;
import com.surprising.trading.api.model.OrderStatus;
import com.surprising.trading.api.model.OrderType;
import com.surprising.trading.api.model.PlaceOrderRequest;
import com.surprising.trading.api.model.TimeInForce;
import com.surprising.trading.order.config.TradingOrderProperties;
import com.surprising.trading.order.model.MarginRequirement;
import com.surprising.trading.order.model.OrderRecord;
import com.surprising.trading.order.model.ValidationResult;
import com.surprising.trading.order.repository.OrderMarginRepository;
import com.surprising.trading.order.repository.OrderRepository;
import com.surprising.trading.order.repository.OutboxRepository;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderServiceTest {

    @Mock
    private OrderValidator orderValidator;

    @Mock
    private ReduceOnlyValidator reduceOnlyValidator;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderMarginRepository orderMarginRepository;

    @Mock
    private OutboxRepository outboxRepository;

    @AfterEach
    void clearTraceContext() {
        TraceContext.clear();
    }

    @Test
    void acceptedOrderEventAndCommandPayloadCarryTraceId() throws Exception {
        TraceContext.set("trace-order-1");
        OrderService service = service();
        when(orderValidator.validate(any())).thenReturn(ValidationResult.ok(7L));
        when(reduceOnlyValidator.validate(any())).thenReturn(ValidationResult.ok(7L));
        when(orderRepository.nextSequence("order")).thenReturn(9002L);
        when(orderRepository.nextSequence("event")).thenReturn(9100L);
        when(orderRepository.nextSequence("command")).thenReturn(9200L);
        when(orderRepository.insert(any(OrderRecord.class))).thenReturn(true);
        PlaceOrderRequest request = new PlaceOrderRequest(1001L, null, "BTC-USDT", OrderSide.BUY,
                OrderType.LIMIT, TimeInForce.GTC, 65_000L, 10L, true, false);

        var response = service.place(request);

        assertThat(response.status()).isEqualTo(OrderStatus.ACCEPTED);
        ArgumentCaptor<OrderEvent> eventCaptor = ArgumentCaptor.forClass(OrderEvent.class);
        verify(orderRepository).insertEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().traceId()).isEqualTo("trace-order-1");

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(outboxRepository, times(2)).enqueue(eq("ORDER"), eq(9002L), anyString(), eq("BTC-USDT"),
                anyString(), payloadCaptor.capture(), any());
        ObjectMapper objectMapper = new ObjectMapper();
        assertThat(objectMapper.readValue(payloadCaptor.getAllValues().get(0), OrderEvent.class).traceId())
                .isEqualTo("trace-order-1");
        assertThat(objectMapper.readValue(payloadCaptor.getAllValues().get(1), OrderCommandEvent.class).traceId())
                .isEqualTo("trace-order-1");
    }

    @Test
    void duplicateClientOrderInsertConflictDoesNotReserveMargin() {
        OrderService service = service();
        OrderRecord existing = order(9001L, "dup-1", OrderStatus.ACCEPTED, null);
        when(orderRepository.findByClientOrderId(1001L, "dup-1"))
                .thenReturn(Optional.empty(), Optional.of(existing));
        when(orderValidator.validate(any())).thenReturn(ValidationResult.ok(7L));
        when(orderRepository.nextSequence("order")).thenReturn(9002L);
        when(orderRepository.insert(any(OrderRecord.class))).thenReturn(false);

        var response = service.place(request("dup-1"));

        assertThat(response.orderId()).isEqualTo(9001L);
        verify(orderMarginRepository, never()).requirement(anyString(), anyLong(), any(), any(),
                anyLong(), anyLong(), anyLong(), anyLong());
        verify(orderMarginRepository, never()).reserve(anyLong(), anyString(), anyLong(), anyString(), anyLong(), any());
        verify(outboxRepository, never()).enqueue(anyString(), anyLong(), anyString(), anyString(), anyString(),
                anyString(), any());
    }

    @Test
    void reservationFailureRejectsInsertedOrderWithoutPublishingCommand() {
        OrderService service = service();
        when(orderRepository.findByClientOrderId(1001L, "no-margin")).thenReturn(Optional.empty());
        when(orderValidator.validate(any())).thenReturn(ValidationResult.ok(7L));
        when(orderRepository.nextSequence("order")).thenReturn(9002L);
        when(orderRepository.nextSequence("event")).thenReturn(9100L);
        when(orderRepository.insert(any(OrderRecord.class))).thenReturn(true);
        when(orderMarginRepository.requirement(eq("BTC-USDT"), eq(7L), eq(OrderSide.BUY), eq(OrderType.LIMIT),
                eq(65_000L), eq(10L), anyLong(), anyLong()))
                .thenReturn(Optional.of(new MarginRequirement("USDT", 100L)));
        when(orderMarginRepository.reserve(eq(1001L), eq("USDT"), eq(9002L), eq("BTC-USDT"),
                eq(100L), any())).thenReturn(false);

        var response = service.place(request("no-margin"));

        assertThat(response.status()).isEqualTo(OrderStatus.REJECTED);
        assertThat(response.remainingQuantitySteps()).isZero();
        assertThat(response.rejectReason()).isEqualTo("insufficient available margin");
        verify(orderRepository).reject(eq(9002L), eq("insufficient available margin"), any());
        verify(outboxRepository).enqueue(eq("ORDER"), eq(9002L), anyString(), eq("BTC-USDT"),
                eq("REJECTED"), anyString(), any());
        verify(orderRepository, never()).nextSequence("command");
    }

    private OrderService service() {
        return new OrderService(new ObjectMapper(), new TradingOrderProperties(), orderValidator,
                reduceOnlyValidator, orderRepository, orderMarginRepository, outboxRepository);
    }

    private PlaceOrderRequest request(String clientOrderId) {
        return new PlaceOrderRequest(1001L, clientOrderId, "BTC-USDT", OrderSide.BUY,
                OrderType.LIMIT, TimeInForce.GTC, 65_000L, 10L, false, false);
    }

    private OrderRecord order(long orderId, String clientOrderId, OrderStatus status, String rejectReason) {
        Instant now = Instant.parse("2026-07-01T00:00:00Z");
        long remaining = status == OrderStatus.REJECTED ? 0L : 10L;
        return new OrderRecord(orderId, 1001L, clientOrderId, "BTC-USDT", 7L, OrderSide.BUY,
                OrderType.LIMIT, TimeInForce.GTC, 65_000L, 10L, 0L, remaining,
                false, false, status, rejectReason, now, now);
    }
}
