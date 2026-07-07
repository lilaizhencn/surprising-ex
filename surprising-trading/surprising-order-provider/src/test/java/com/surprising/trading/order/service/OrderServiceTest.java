package com.surprising.trading.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.instrument.api.model.ContractType;
import com.surprising.instrument.api.model.InstrumentType;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.TraceContext;
import com.surprising.trading.api.model.AmendOrderRequest;
import com.surprising.trading.api.model.AdminBatchCancelOrdersRequest;
import com.surprising.trading.api.model.AdminCancelBySymbolRequest;
import com.surprising.trading.api.model.AdminCursorPage;
import com.surprising.trading.api.model.BatchAmendOrdersRequest;
import com.surprising.trading.api.model.BatchPlaceOrderRequest;
import com.surprising.trading.api.model.CancelOpenOrdersRequest;
import com.surprising.trading.api.model.CancelOrderRequest;
import com.surprising.trading.api.model.ClosePositionRequest;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.OrderCommandEvent;
import com.surprising.trading.api.model.OrderEvent;
import com.surprising.trading.api.model.OrderEventType;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.OrderStatus;
import com.surprising.trading.api.model.OrderType;
import com.surprising.trading.api.model.PlaceOrderRequest;
import com.surprising.trading.api.model.PositionMode;
import com.surprising.trading.api.model.PositionSide;
import com.surprising.trading.api.model.TimeInForce;
import com.surprising.trading.order.config.TradingOrderProperties;
import com.surprising.trading.order.model.MarginRequirement;
import com.surprising.trading.order.model.OrderFeeSnapshot;
import com.surprising.trading.order.model.OrderRecord;
import com.surprising.trading.order.model.ReduceOnlyPosition;
import com.surprising.trading.order.model.SpotReservationRequirement;
import com.surprising.trading.order.model.ValidationResult;
import com.surprising.trading.order.repository.OrderFeeRepository;
import com.surprising.trading.order.repository.OrderMarginRepository;
import com.surprising.trading.order.repository.OrderRepository;
import com.surprising.trading.order.repository.OutboxRepository;
import com.surprising.trading.order.repository.SpotOrderReservationRepository;
import java.time.Instant;
import java.util.List;
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
    private OrderFeeRepository orderFeeRepository;

    @Mock
    private OrderMarginRepository orderMarginRepository;

    @Mock
    private SpotOrderReservationRepository spotOrderReservationRepository;

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
        when(orderFeeRepository.snapshot(eq(1001L), eq("BTC-USDT"), eq(7L), any()))
                .thenReturn(Optional.of(new OrderFeeSnapshot(200L, 500L, "INSTRUMENT")));
        PlaceOrderRequest request = new PlaceOrderRequest(1001L, null, "BTC-USDT", OrderSide.BUY,
                OrderType.LIMIT, TimeInForce.GTC, 65_000L, 10L, true, false);

        var response = service.place(request);

        assertThat(response.status()).isEqualTo(OrderStatus.ACCEPTED);
        assertThat(response.positionSide()).isEqualTo(PositionSide.NET);
        assertThat(response.makerFeeRatePpm()).isEqualTo(200L);
        assertThat(response.takerFeeRatePpm()).isEqualTo(500L);
        ArgumentCaptor<OrderEvent> eventCaptor = ArgumentCaptor.forClass(OrderEvent.class);
        verify(orderRepository).insertEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().traceId()).isEqualTo("trace-order-1");

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(outboxRepository, times(2)).enqueue(eq("ORDER"), eq(9002L), anyString(), eq("BTC-USDT"),
                anyString(), payloadCaptor.capture(), any());
        ObjectMapper objectMapper = new ObjectMapper();
        assertThat(objectMapper.readValue(payloadCaptor.getAllValues().get(0), OrderEvent.class).traceId())
                .isEqualTo("trace-order-1");
        OrderCommandEvent command = objectMapper.readValue(payloadCaptor.getAllValues().get(1),
                OrderCommandEvent.class);
        assertThat(command.traceId()).isEqualTo("trace-order-1");
        assertThat(command.marginMode()).isEqualTo(MarginMode.CROSS);
        ArgumentCaptor<OrderRecord> orderCaptor = ArgumentCaptor.forClass(OrderRecord.class);
        verify(orderRepository).insert(orderCaptor.capture());
        assertThat(orderCaptor.getValue().makerFeeRatePpm()).isEqualTo(200L);
        assertThat(orderCaptor.getValue().takerFeeRatePpm()).isEqualTo(500L);
    }

    @Test
    void duplicateClientOrderInsertConflictDoesNotReserveMargin() {
        OrderService service = service();
        OrderRecord existing = order(9001L, "dup-1", OrderStatus.ACCEPTED, null);
        when(orderRepository.findByClientOrderId(ProductLine.LINEAR_PERPETUAL, 1001L, "dup-1"))
                .thenReturn(Optional.empty(), Optional.of(existing));
        when(orderValidator.validate(any())).thenReturn(ValidationResult.ok(7L));
        when(orderFeeRepository.snapshot(eq(1001L), eq("BTC-USDT"), eq(7L), any()))
                .thenReturn(Optional.of(new OrderFeeSnapshot(200L, 500L, "INSTRUMENT")));
        when(orderRepository.nextSequence("order")).thenReturn(9002L);
        when(orderRepository.insert(any(OrderRecord.class))).thenReturn(false);

        var response = service.place(request("dup-1"));

        assertThat(response.orderId()).isEqualTo(9001L);
        verify(orderMarginRepository, never()).requirement(anyString(), anyLong(), anyLong(), any(), any(), any(),
                anyLong(), anyLong(), anyLong(), anyLong());
        verify(orderMarginRepository, never()).reserve(anyLong(), anyString(), anyString(), anyLong(), anyString(),
                any(), anyLong(), any());
        verify(outboxRepository, never()).enqueue(anyString(), anyLong(), anyString(), anyString(), anyString(),
                anyString(), any());
    }

    @Test
    void reservationFailureRejectsInsertedOrderWithoutPublishingCommand() {
        OrderService service = service();
        when(orderRepository.findByClientOrderId(ProductLine.LINEAR_PERPETUAL, 1001L, "no-margin")).thenReturn(Optional.empty());
        when(orderValidator.validate(any())).thenReturn(ValidationResult.ok(7L));
        when(orderFeeRepository.snapshot(eq(1001L), eq("BTC-USDT"), eq(7L), any()))
                .thenReturn(Optional.of(new OrderFeeSnapshot(200L, 500L, "INSTRUMENT")));
        when(orderRepository.nextSequence("order")).thenReturn(9002L);
        when(orderRepository.nextSequence("event")).thenReturn(9100L);
        when(orderRepository.insert(any(OrderRecord.class))).thenReturn(true);
        when(orderMarginRepository.requirement(eq("BTC-USDT"), eq(7L), eq(1001L), eq(MarginMode.CROSS),
                eq(PositionSide.NET), eq(OrderSide.BUY), eq(OrderType.LIMIT), eq(65_000L), eq(10L), anyLong(),
                anyLong()))
                .thenReturn(Optional.of(new MarginRequirement("USDT", 100L)));
        when(orderMarginRepository.reserve(eq(1001L), eq("USDT_PERPETUAL"), eq("USDT"), eq(9002L), eq("BTC-USDT"),
                eq(MarginMode.CROSS), eq(PositionSide.NET), eq(100L), any())).thenReturn(false);

        var response = service.place(request("no-margin"));

        assertThat(response.status()).isEqualTo(OrderStatus.REJECTED);
        assertThat(response.remainingQuantitySteps()).isZero();
        assertThat(response.rejectReason()).isEqualTo("insufficient available margin");
        verify(orderRepository).reject(eq(9002L), eq("insufficient available margin"), any());
        verify(outboxRepository).enqueue(eq("ORDER"), eq(9002L), anyString(), eq("BTC-USDT"),
                eq("REJECTED"), anyString(), any());
        verify(orderRepository, never()).nextSequence("command");
    }

    @Test
    void missingFeeScheduleRejectsOrderBeforeMarginReservation() {
        OrderService service = service();
        when(orderValidator.validate(any())).thenReturn(ValidationResult.ok(7L));
        when(orderFeeRepository.snapshot(eq(1001L), eq("BTC-USDT"), eq(7L), any()))
                .thenReturn(Optional.empty());
        when(orderRepository.nextSequence("order")).thenReturn(9002L);
        when(orderRepository.nextSequence("event")).thenReturn(9100L);
        when(orderRepository.insert(any(OrderRecord.class))).thenReturn(true);

        var response = service.place(request("fee-missing"));

        assertThat(response.status()).isEqualTo(OrderStatus.REJECTED);
        assertThat(response.rejectReason()).isEqualTo("fee schedule unavailable");
        verify(orderMarginRepository, never()).requirement(anyString(), anyLong(), anyLong(), any(), any(), any(),
                anyLong(), anyLong(), anyLong(), anyLong());
        verify(orderRepository, never()).nextSequence("command");
    }

    @Test
    void leverageRiskLimitRejectsInsertedOrderWithoutPublishingCommand() {
        OrderService service = service();
        when(orderValidator.validate(any())).thenReturn(ValidationResult.ok(7L));
        when(orderFeeRepository.snapshot(eq(1001L), eq("BTC-USDT"), eq(7L), any()))
                .thenReturn(Optional.of(new OrderFeeSnapshot(200L, 500L, "INSTRUMENT")));
        when(orderRepository.nextSequence("order")).thenReturn(9002L);
        when(orderRepository.nextSequence("event")).thenReturn(9100L);
        when(orderRepository.insert(any(OrderRecord.class))).thenReturn(true);
        when(orderMarginRepository.requirement(eq("BTC-USDT"), eq(7L), eq(1001L), eq(MarginMode.CROSS),
                eq(PositionSide.NET), eq(OrderSide.BUY), eq(OrderType.LIMIT), eq(65_000L), eq(10L), anyLong(),
                anyLong()))
                .thenReturn(Optional.of(new MarginRequirement("USDT", 0L, "leverage exceeds risk limit",
                        100_000_000L, 50_000_000L, 20_000L)));

        var response = service.place(request("bad-leverage"));

        assertThat(response.status()).isEqualTo(OrderStatus.REJECTED);
        assertThat(response.rejectReason()).isEqualTo("leverage exceeds risk limit");
        verify(orderRepository).reject(eq(9002L), eq("leverage exceeds risk limit"), any());
        verify(orderMarginRepository, never()).reserve(anyLong(), anyString(), anyString(), anyLong(), anyString(),
                any(), anyLong(), any());
        verify(orderRepository, never()).nextSequence("command");
    }

    @Test
    void isolatedMarginOrdersReserveIsolatedMarginAndPublishCommand() {
        OrderService service = service();
        when(orderValidator.validate(any())).thenReturn(ValidationResult.ok(7L));
        when(orderFeeRepository.snapshot(eq(1001L), eq("BTC-USDT"), eq(7L), any()))
                .thenReturn(Optional.of(new OrderFeeSnapshot(200L, 500L, "INSTRUMENT")));
        when(orderRepository.nextSequence("order")).thenReturn(9002L);
        when(orderRepository.nextSequence("event")).thenReturn(9100L);
        when(orderRepository.nextSequence("command")).thenReturn(9200L);
        when(orderRepository.insert(any(OrderRecord.class))).thenReturn(true);
        when(orderMarginRepository.requirement(eq("BTC-USDT"), eq(7L), eq(1001L), eq(MarginMode.ISOLATED),
                eq(PositionSide.NET), eq(OrderSide.BUY), eq(OrderType.LIMIT), eq(65_000L), eq(10L), anyLong(),
                anyLong()))
                .thenReturn(Optional.of(new MarginRequirement("USDT", 100L)));
        when(orderMarginRepository.reserve(eq(1001L), eq("USDT_PERPETUAL"), eq("USDT"), eq(9002L), eq("BTC-USDT"),
                eq(MarginMode.ISOLATED), eq(PositionSide.NET), eq(100L), any())).thenReturn(true);

        PlaceOrderRequest request = new PlaceOrderRequest(1001L, "iso-1", "BTC-USDT", OrderSide.BUY,
                OrderType.LIMIT, TimeInForce.GTC, 65_000L, 10L, MarginMode.ISOLATED, false, false);

        var response = service.place(request);

        assertThat(response.status()).isEqualTo(OrderStatus.ACCEPTED);
        assertThat(response.marginMode()).isEqualTo(MarginMode.ISOLATED);
        assertThat(response.positionSide()).isEqualTo(PositionSide.NET);
        assertThat(response.rejectReason()).isNull();
        ArgumentCaptor<OrderRecord> orderCaptor = ArgumentCaptor.forClass(OrderRecord.class);
        verify(orderRepository).insert(orderCaptor.capture());
        assertThat(orderCaptor.getValue().marginMode()).isEqualTo(MarginMode.ISOLATED);
        verify(orderMarginRepository).reserve(eq(1001L), eq("USDT_PERPETUAL"), eq("USDT"), eq(9002L), eq("BTC-USDT"),
                eq(MarginMode.ISOLATED), eq(PositionSide.NET), eq(100L), any());
        verify(orderRepository).nextSequence("command");
        verify(outboxRepository, times(2)).enqueue(eq("ORDER"), eq(9002L), anyString(), eq("BTC-USDT"),
                anyString(), anyString(), any());
    }

    @Test
    void coinPerpetualOrdersReserveCoinPerpetualProductAccount() {
        OrderService service = service();
        when(orderValidator.validate(any())).thenReturn(ValidationResult.ok(7L));
        when(orderFeeRepository.snapshot(eq(1001L), eq("BTC-USD"), eq(7L), any()))
                .thenReturn(Optional.of(new OrderFeeSnapshot(200L, 500L, "INSTRUMENT")));
        when(orderRepository.nextSequence("order")).thenReturn(9002L);
        when(orderRepository.nextSequence("event")).thenReturn(9100L);
        when(orderRepository.nextSequence("command")).thenReturn(9200L);
        when(orderRepository.insert(any(OrderRecord.class))).thenReturn(true);
        when(orderMarginRepository.requirement(eq("BTC-USD"), eq(7L), eq(1001L), eq(MarginMode.CROSS),
                eq(PositionSide.NET), eq(OrderSide.BUY), eq(OrderType.LIMIT), eq(65_000L), eq(10L), anyLong(),
                anyLong()))
                .thenReturn(Optional.of(new MarginRequirement("COIN_PERPETUAL", "BTC", 100L, null,
                        0L, 0L, 0L)));
        when(orderMarginRepository.reserve(eq(1001L), eq("COIN_PERPETUAL"), eq("BTC"), eq(9002L), eq("BTC-USD"),
                eq(MarginMode.CROSS), eq(PositionSide.NET), eq(100L), any())).thenReturn(true);

        PlaceOrderRequest request = new PlaceOrderRequest(1001L, "coin-1", "BTC-USD", OrderSide.BUY,
                OrderType.LIMIT, TimeInForce.GTC, 65_000L, 10L, false, false);

        var response = service.place(request);

        assertThat(response.status()).isEqualTo(OrderStatus.ACCEPTED);
        assertThat(response.rejectReason()).isNull();
        verify(orderMarginRepository).reserve(eq(1001L), eq("COIN_PERPETUAL"), eq("BTC"), eq(9002L), eq("BTC-USD"),
                eq(MarginMode.CROSS), eq(PositionSide.NET), eq(100L), any());
        verify(orderRepository).nextSequence("command");
    }

    @Test
    void spotOrderReservesSpotBalanceWithoutUsingPerpetualMargin() {
        OrderService service = service();
        when(orderValidator.validate(any())).thenReturn(ValidationResult.ok(11L, InstrumentType.SPOT));
        when(orderFeeRepository.snapshot(eq(1001L), eq("BTC-USDT"), eq(11L), any()))
                .thenReturn(Optional.of(new OrderFeeSnapshot(200L, 500L, "INSTRUMENT")));
        when(orderRepository.nextSequence("order")).thenReturn(9002L);
        when(orderRepository.nextSequence("event")).thenReturn(9100L);
        when(orderRepository.nextSequence("command")).thenReturn(9200L);
        when(orderRepository.insert(any(OrderRecord.class))).thenReturn(true);
        when(spotOrderReservationRepository.requirement(eq("BTC-USDT"), eq(11L), eq(OrderSide.BUY),
                eq(OrderType.LIMIT), eq(65_000L), eq(10L), anyLong(), anyLong(), any()))
                .thenReturn(Optional.of(new SpotReservationRequirement("USDT", 651L)));
        when(spotOrderReservationRepository.reserve(eq(1001L), eq("USDT"), eq(9002L), eq("BTC-USDT"),
                eq(OrderSide.BUY), eq(651L), any())).thenReturn(true);

        var response = service.place(request("spot-1"));

        assertThat(response.status()).isEqualTo(OrderStatus.ACCEPTED);
        assertThat(response.rejectReason()).isNull();
        verify(orderMarginRepository, never()).requirement(anyString(), anyLong(), anyLong(), any(), any(), any(),
                anyLong(), anyLong(), anyLong(), anyLong());
        verify(orderMarginRepository, never()).reserve(anyLong(), anyString(), anyString(), anyLong(), anyString(),
                any(), anyLong(), any());
        verify(spotOrderReservationRepository).reserve(eq(1001L), eq("USDT"), eq(9002L), eq("BTC-USDT"),
                eq(OrderSide.BUY), eq(651L), any());
        verify(orderRepository).nextSequence("command");
    }

    @Test
    void openingOrderRejectsMarginModeSwitchWhileOtherModeIsActive() {
        OrderService service = service();
        when(orderRepository.hasActiveMarginModeConflict(1001L, "BTC-USDT", MarginMode.ISOLATED))
                .thenReturn(true);
        when(orderRepository.nextSequence("order")).thenReturn(9002L);
        when(orderRepository.nextSequence("event")).thenReturn(9100L);
        when(orderRepository.insert(any(OrderRecord.class))).thenReturn(true);

        PlaceOrderRequest request = new PlaceOrderRequest(1001L, "iso-switch", "BTC-USDT", OrderSide.BUY,
                OrderType.LIMIT, TimeInForce.GTC, 65_000L, 10L, MarginMode.ISOLATED, false, false);

        var response = service.place(request);

        assertThat(response.status()).isEqualTo(OrderStatus.REJECTED);
        assertThat(response.rejectReason())
                .isEqualTo("margin mode switch requires closing positions and open orders first");
        assertThat(response.marginMode()).isEqualTo(MarginMode.ISOLATED);
        verify(orderRepository).lockUserSymbolMarginScope(1001L, "BTC-USDT");
        verify(orderValidator, never()).validate(any());
        verify(orderFeeRepository, never()).snapshot(anyLong(), anyString(), anyLong(), any());
        verify(orderMarginRepository, never()).requirement(anyString(), anyLong(), anyLong(), any(), any(), any(),
                anyLong(), anyLong(), anyLong(), anyLong());
        verify(orderRepository, never()).nextSequence("command");
    }

    @Test
    void hedgePositionSideRequiresHedgeModeBeforePersistence() {
        OrderService service = service();
        PlaceOrderRequest request = new PlaceOrderRequest(1001L, "hedge-long", "BTC-USDT", OrderSide.BUY,
                OrderType.LIMIT, TimeInForce.GTC, 65_000L, 10L, MarginMode.CROSS, PositionSide.LONG,
                false, false);

        assertThatThrownBy(() -> service.place(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("positionSide LONG/SHORT requires HEDGE position mode");

        verify(orderRepository, never()).lockUserSymbolMarginScope(anyLong(), anyString());
        verify(orderRepository, never()).insert(any());
        verify(outboxRepository, never()).enqueue(anyString(), anyLong(), anyString(), anyString(), anyString(),
                anyString(), any());
    }

    @Test
    void hedgeOpeningOrderCarriesPositionSideAndReservesHedgeMargin() throws Exception {
        OrderService service = service();
        when(orderRepository.positionMode(ProductLine.LINEAR_PERPETUAL, 1001L)).thenReturn(PositionMode.HEDGE);
        when(orderValidator.validate(any())).thenReturn(ValidationResult.ok(7L));
        when(orderFeeRepository.snapshot(eq(1001L), eq("BTC-USDT"), eq(7L), any()))
                .thenReturn(Optional.of(new OrderFeeSnapshot(200L, 500L, "INSTRUMENT")));
        when(orderRepository.nextSequence("order")).thenReturn(9002L);
        when(orderRepository.nextSequence("event")).thenReturn(9100L);
        when(orderRepository.nextSequence("command")).thenReturn(9200L);
        when(orderRepository.insert(any(OrderRecord.class))).thenReturn(true);
        when(orderMarginRepository.requirement(eq("BTC-USDT"), eq(7L), eq(1001L), eq(MarginMode.CROSS),
                eq(PositionSide.LONG), eq(OrderSide.BUY), eq(OrderType.LIMIT), eq(65_000L), eq(10L), anyLong(),
                anyLong()))
                .thenReturn(Optional.of(new MarginRequirement("USDT", 100L)));
        when(orderMarginRepository.reserve(eq(1001L), eq("USDT_PERPETUAL"), eq("USDT"), eq(9002L), eq("BTC-USDT"),
                eq(MarginMode.CROSS), eq(PositionSide.LONG), eq(100L), any())).thenReturn(true);

        PlaceOrderRequest request = new PlaceOrderRequest(1001L, "hedge-open-long", "BTC-USDT", OrderSide.BUY,
                OrderType.LIMIT, TimeInForce.GTC, 65_000L, 10L, MarginMode.CROSS, PositionSide.LONG,
                false, false);

        var response = service.place(request);

        assertThat(response.status()).isEqualTo(OrderStatus.ACCEPTED);
        assertThat(response.positionSide()).isEqualTo(PositionSide.LONG);
        assertThat(response.reduceOnly()).isFalse();
        ArgumentCaptor<OrderRecord> orderCaptor = ArgumentCaptor.forClass(OrderRecord.class);
        verify(orderRepository).insert(orderCaptor.capture());
        assertThat(orderCaptor.getValue().positionSide()).isEqualTo(PositionSide.LONG);
        assertThat(orderCaptor.getValue().reduceOnly()).isFalse();
        verify(orderMarginRepository).reserve(eq(1001L), eq("USDT_PERPETUAL"), eq("USDT"), eq(9002L),
                eq("BTC-USDT"), eq(MarginMode.CROSS), eq(PositionSide.LONG), eq(100L), any());
        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(outboxRepository, times(2)).enqueue(eq("ORDER"), eq(9002L), anyString(), eq("BTC-USDT"),
                anyString(), payloadCaptor.capture(), any());
        OrderCommandEvent command = new ObjectMapper().readValue(payloadCaptor.getAllValues().get(1),
                OrderCommandEvent.class);
        assertThat(command.positionSide()).isEqualTo(PositionSide.LONG);
        assertThat(command.reduceOnly()).isFalse();
    }

    @Test
    void hedgeClosingOrderIsNormalizedToReduceOnlyWithoutOpeningMarginReservation() {
        OrderService service = service();
        when(orderRepository.positionMode(ProductLine.LINEAR_PERPETUAL, 1001L)).thenReturn(PositionMode.HEDGE);
        when(orderValidator.validate(any())).thenReturn(ValidationResult.ok(7L));
        when(reduceOnlyValidator.validate(any())).thenReturn(ValidationResult.ok(7L));
        when(orderFeeRepository.snapshot(eq(1001L), eq("BTC-USDT"), eq(7L), any()))
                .thenReturn(Optional.of(new OrderFeeSnapshot(200L, 500L, "INSTRUMENT")));
        when(orderRepository.nextSequence("order")).thenReturn(9002L);
        when(orderRepository.nextSequence("event")).thenReturn(9100L);
        when(orderRepository.nextSequence("command")).thenReturn(9200L);
        when(orderRepository.insert(any(OrderRecord.class))).thenReturn(true);

        PlaceOrderRequest request = new PlaceOrderRequest(1001L, "hedge-close-long", "BTC-USDT", OrderSide.SELL,
                OrderType.LIMIT, TimeInForce.IOC, 65_000L, 10L, MarginMode.CROSS, PositionSide.LONG,
                false, false);

        var response = service.place(request);

        assertThat(response.status()).isEqualTo(OrderStatus.ACCEPTED);
        assertThat(response.positionSide()).isEqualTo(PositionSide.LONG);
        assertThat(response.reduceOnly()).isTrue();
        ArgumentCaptor<OrderRecord> orderCaptor = ArgumentCaptor.forClass(OrderRecord.class);
        verify(orderRepository).insert(orderCaptor.capture());
        assertThat(orderCaptor.getValue().reduceOnly()).isTrue();
        assertThat(orderCaptor.getValue().positionSide()).isEqualTo(PositionSide.LONG);
        verify(reduceOnlyValidator).validate(any());
        verify(orderMarginRepository, never()).requirement(anyString(), anyLong(), anyLong(), any(), any(), any(),
                anyLong(), anyLong(), anyLong(), anyLong());
        verify(orderMarginRepository, never()).reserve(anyLong(), anyString(), anyString(), anyLong(), anyString(),
                any(), any(), anyLong(), any());
    }

    @Test
    void reduceOnlyOptionBuyReservesPremiumForShortClose() {
        OrderService service = service();
        when(orderValidator.validate(any())).thenReturn(ValidationResult.ok(7L, InstrumentType.PERPETUAL,
                ContractType.VANILLA_OPTION));
        when(reduceOnlyValidator.validate(any())).thenReturn(ValidationResult.ok(7L));
        when(orderFeeRepository.snapshot(eq(1001L), eq("BTC-USDT-260925-70000-C"), eq(7L), any()))
                .thenReturn(Optional.of(new OrderFeeSnapshot(200L, 500L, "INSTRUMENT")));
        when(orderRepository.nextSequence("order")).thenReturn(9002L);
        when(orderRepository.nextSequence("event")).thenReturn(9100L);
        when(orderRepository.nextSequence("command")).thenReturn(9200L);
        when(orderRepository.insert(any(OrderRecord.class))).thenReturn(true);
        when(orderMarginRepository.requirement(eq("BTC-USDT-260925-70000-C"), eq(7L), eq(1001L),
                eq(MarginMode.CROSS), eq(PositionSide.NET), eq(OrderSide.BUY), eq(OrderType.LIMIT),
                eq(120L), eq(2L), anyLong(), anyLong()))
                .thenReturn(Optional.of(new MarginRequirement("OPTION", "USDT", 240L)));
        when(orderMarginRepository.reserve(eq(1001L), eq("OPTION"), eq("USDT"), eq(9002L),
                eq("BTC-USDT-260925-70000-C"), eq(MarginMode.CROSS), eq(PositionSide.NET), eq(240L), any()))
                .thenReturn(true);

        PlaceOrderRequest request = new PlaceOrderRequest(1001L, "option-close-short",
                "BTC-USDT-260925-70000-C", OrderSide.BUY, OrderType.LIMIT, TimeInForce.IOC, 120L, 2L,
                MarginMode.CROSS, PositionSide.NET, true, false);

        var response = service.place(request);

        assertThat(response.status()).isEqualTo(OrderStatus.ACCEPTED);
        assertThat(response.reduceOnly()).isTrue();
        verify(orderMarginRepository).reserve(eq(1001L), eq("OPTION"), eq("USDT"), eq(9002L),
                eq("BTC-USDT-260925-70000-C"), eq(MarginMode.CROSS), eq(PositionSide.NET), eq(240L), any());
        verify(orderRepository).nextSequence("command");
    }

    @Test
    void reduceOnlyOrderBypassesMarginModeSwitchConflictForRiskReduction() {
        OrderService service = service();
        when(orderValidator.validate(any())).thenReturn(ValidationResult.ok(7L));
        when(reduceOnlyValidator.validate(any())).thenReturn(ValidationResult.ok(7L));
        when(orderFeeRepository.snapshot(eq(1001L), eq("BTC-USDT"), eq(7L), any()))
                .thenReturn(Optional.of(new OrderFeeSnapshot(200L, 500L, "INSTRUMENT")));
        when(orderRepository.nextSequence("order")).thenReturn(9002L);
        when(orderRepository.nextSequence("event")).thenReturn(9100L);
        when(orderRepository.nextSequence("command")).thenReturn(9200L);
        when(orderRepository.insert(any(OrderRecord.class))).thenReturn(true);

        PlaceOrderRequest request = new PlaceOrderRequest(1001L, "reduce-conflict", "BTC-USDT", OrderSide.SELL,
                OrderType.LIMIT, TimeInForce.IOC, 65_000L, 10L, MarginMode.CROSS, true, false);

        var response = service.place(request);

        assertThat(response.status()).isEqualTo(OrderStatus.ACCEPTED);
        assertThat(response.reduceOnly()).isTrue();
        verify(orderRepository).lockUserSymbolMarginScope(1001L, "BTC-USDT");
        verify(orderRepository, never()).hasActiveMarginModeConflict(anyLong(), anyString(), any());
        verify(orderRepository).nextSequence("command");
    }

    @Test
    void testOrderDryRunDoesNotPersistReserveOrPublish() {
        OrderService service = service();
        when(orderValidator.validate(any())).thenReturn(ValidationResult.ok(7L));
        when(orderFeeRepository.snapshot(eq(1001L), eq("BTC-USDT"), eq(7L), any()))
                .thenReturn(Optional.of(new OrderFeeSnapshot(200L, 500L, "INSTRUMENT")));
        when(orderMarginRepository.requirement(eq("BTC-USDT"), eq(7L), eq(1001L), eq(MarginMode.CROSS),
                eq(PositionSide.NET), eq(OrderSide.BUY), eq(OrderType.LIMIT), eq(65_000L), eq(10L), anyLong(),
                anyLong()))
                .thenReturn(Optional.of(new MarginRequirement("USDT", 100L)));

        var response = service.test(request("dry-run-1"));

        assertThat(response.accepted()).isTrue();
        assertThat(response.validationStage()).isEqualTo("ACCEPTED");
        assertThat(response.accountType()).isEqualTo("USDT_PERPETUAL");
        assertThat(response.asset()).isEqualTo("USDT");
        assertThat(response.estimatedReserveUnits()).isEqualTo(100L);
        verify(orderRepository, never()).nextSequence("order");
        verify(orderRepository, never()).insert(any());
        verify(orderMarginRepository, never()).reserve(anyLong(), anyString(), anyString(), anyLong(), anyString(),
                any(), any(), anyLong(), any());
        verify(outboxRepository, never()).enqueue(anyString(), anyLong(), anyString(), anyString(), anyString(),
                anyString(), any());
    }

    @Test
    void placeBatchKeepsItemFailuresIsolatedFromValidOrders() {
        OrderService service = service();
        when(orderValidator.validate(any())).thenReturn(ValidationResult.ok(7L));
        when(reduceOnlyValidator.validate(any())).thenReturn(ValidationResult.ok(7L));
        when(orderFeeRepository.snapshot(eq(1001L), eq("BTC-USDT"), eq(7L), any()))
                .thenReturn(Optional.of(new OrderFeeSnapshot(200L, 500L, "INSTRUMENT")));
        when(orderRepository.nextSequence("order")).thenReturn(9002L);
        when(orderRepository.nextSequence("event")).thenReturn(9100L);
        when(orderRepository.nextSequence("command")).thenReturn(9200L);
        when(orderRepository.insert(any(OrderRecord.class))).thenReturn(true);
        PlaceOrderRequest invalid = new PlaceOrderRequest(0L, "bad", "BTC-USDT", OrderSide.BUY,
                OrderType.LIMIT, TimeInForce.GTC, 65_000L, 10L, false, false);
        PlaceOrderRequest validReduceOnly = new PlaceOrderRequest(1001L, "batch-ok", "BTC-USDT", OrderSide.SELL,
                OrderType.LIMIT, TimeInForce.IOC, 65_000L, 10L, true, false);

        var response = service.placeBatch(new BatchPlaceOrderRequest(List.of(invalid, validReduceOnly)));

        assertThat(response.requested()).isEqualTo(2);
        assertThat(response.completed()).isEqualTo(1);
        assertThat(response.failed()).isEqualTo(1);
        assertThat(response.results().get(0).success()).isFalse();
        assertThat(response.results().get(0).message()).isEqualTo("userId must be positive");
        assertThat(response.results().get(1).success()).isTrue();
        assertThat(response.results().get(1).order().status()).isEqualTo(OrderStatus.ACCEPTED);
        verify(orderRepository).insert(any(OrderRecord.class));
        verify(orderMarginRepository, never()).reserve(anyLong(), anyString(), anyString(), anyLong(), anyString(),
                any(), any(), anyLong(), any());
    }

    @Test
    void amendOrderRequestsCancelAndPlacesReplacementOrder() {
        TraceContext.set("trace-amend-1");
        OrderService service = service();
        OrderRecord original = order(9001L, "orig-1", OrderStatus.ACCEPTED, null);
        OrderRecord cancelRequested = order(9001L, "orig-1", OrderStatus.CANCEL_REQUESTED, null);
        when(orderRepository.findByClientOrderId(ProductLine.LINEAR_PERPETUAL, 1001L, "amend-1"))
                .thenReturn(Optional.empty(), Optional.empty());
        when(orderRepository.findByOrderId(9001L))
                .thenReturn(Optional.of(original), Optional.of(cancelRequested));
        when(orderRepository.requestCancel(eq(9001L), any())).thenReturn(true);
        when(orderRepository.nextSequence("event")).thenReturn(9100L, 9101L);
        when(orderRepository.nextSequence("command")).thenReturn(9200L, 9201L);
        when(orderRepository.nextSequence("order")).thenReturn(9002L);
        when(orderValidator.validate(any())).thenReturn(ValidationResult.ok(7L));
        when(orderFeeRepository.snapshot(eq(1001L), eq("BTC-USDT"), eq(7L), any()))
                .thenReturn(Optional.of(new OrderFeeSnapshot(200L, 500L, "INSTRUMENT")));
        when(orderRepository.insert(any(OrderRecord.class))).thenReturn(true);
        when(orderMarginRepository.requirement(eq("BTC-USDT"), eq(7L), eq(1001L), eq(MarginMode.CROSS),
                eq(PositionSide.NET), eq(OrderSide.BUY), eq(OrderType.LIMIT), eq(66_000L), eq(5L), anyLong(),
                anyLong()))
                .thenReturn(Optional.of(new MarginRequirement("USDT_PERPETUAL", "USDT", 100L)));
        when(orderMarginRepository.reserve(eq(1001L), eq("USDT_PERPETUAL"), eq("USDT"), eq(9002L),
                eq("BTC-USDT"), eq(MarginMode.CROSS), eq(PositionSide.NET), eq(100L), any())).thenReturn(true);
        ArgumentCaptor<OrderRecord> replacementCaptor = ArgumentCaptor.forClass(OrderRecord.class);

        var response = service.amend(new AmendOrderRequest(1001L, 9001L, "amend-1",
                66_000L, 5L, TimeInForce.GTC, true));

        assertThat(response.cancelRequested()).isTrue();
        assertThat(response.originalOrder().status()).isEqualTo(OrderStatus.CANCEL_REQUESTED);
        assertThat(response.replacementOrder().clientOrderId()).isEqualTo("amend-1");
        assertThat(response.replacementOrder().priceTicks()).isEqualTo(66_000L);
        assertThat(response.replacementOrder().quantitySteps()).isEqualTo(5L);
        assertThat(response.replacementOrder().postOnly()).isTrue();
        verify(orderRepository).requestCancel(eq(9001L), any());
        verify(orderRepository).insert(replacementCaptor.capture());
        assertThat(replacementCaptor.getValue().clientOrderId()).isEqualTo("amend-1");
        verify(outboxRepository, times(4)).enqueue(eq("ORDER"), anyLong(), anyString(), eq("BTC-USDT"),
                anyString(), anyString(), any());
    }

    @Test
    void batchAmendKeepsItemFailuresIsolatedFromValidOrders() {
        OrderService service = service();
        OrderRecord original = order(9001L, "batch-orig-1", OrderStatus.ACCEPTED, null);
        OrderRecord cancelRequested = order(9001L, "batch-orig-1", OrderStatus.CANCEL_REQUESTED, null);
        when(orderRepository.findByClientOrderId(ProductLine.LINEAR_PERPETUAL, 1001L, "batch-amend-1"))
                .thenReturn(Optional.empty(), Optional.empty());
        when(orderRepository.findByOrderId(9001L))
                .thenReturn(Optional.of(original), Optional.of(cancelRequested));
        when(orderRepository.requestCancel(eq(9001L), any())).thenReturn(true);
        when(orderRepository.nextSequence("event")).thenReturn(9100L, 9101L);
        when(orderRepository.nextSequence("command")).thenReturn(9200L, 9201L);
        when(orderRepository.nextSequence("order")).thenReturn(9002L);
        when(orderValidator.validate(any())).thenReturn(ValidationResult.ok(7L));
        when(orderFeeRepository.snapshot(eq(1001L), eq("BTC-USDT"), eq(7L), any()))
                .thenReturn(Optional.of(new OrderFeeSnapshot(200L, 500L, "INSTRUMENT")));
        when(orderRepository.insert(any(OrderRecord.class))).thenReturn(true);
        when(orderMarginRepository.requirement(eq("BTC-USDT"), eq(7L), eq(1001L), eq(MarginMode.CROSS),
                eq(PositionSide.NET), eq(OrderSide.BUY), eq(OrderType.LIMIT), eq(66_000L), eq(4L), anyLong(),
                anyLong()))
                .thenReturn(Optional.of(new MarginRequirement("USDT_PERPETUAL", "USDT", 100L)));
        when(orderMarginRepository.reserve(eq(1001L), eq("USDT_PERPETUAL"), eq("USDT"), eq(9002L),
                eq("BTC-USDT"), eq(MarginMode.CROSS), eq(PositionSide.NET), eq(100L), any())).thenReturn(true);
        AmendOrderRequest valid = new AmendOrderRequest(1001L, 9001L, "batch-amend-1",
                66_000L, 4L, null, null);
        AmendOrderRequest invalid = new AmendOrderRequest(1001L, 0L, "batch-amend-bad",
                67_000L, null, null, null);

        var response = service.amendBatch(new BatchAmendOrdersRequest(List.of(valid, invalid)));

        assertThat(response.requested()).isEqualTo(2);
        assertThat(response.completed()).isEqualTo(1);
        assertThat(response.failed()).isEqualTo(1);
        assertThat(response.results().get(0).success()).isTrue();
        assertThat(response.results().get(0).amend().replacementOrder().clientOrderId()).isEqualTo("batch-amend-1");
        assertThat(response.results().get(1).success()).isFalse();
        assertThat(response.results().get(1).message()).isEqualTo("userId and orderId must be positive");
        verify(orderRepository).requestCancel(eq(9001L), any());
    }

    @Test
    void closePositionPlacesReduceOnlyMarketIocForLockedLongPosition() {
        OrderService service = service();
        when(orderRepository.lockedPosition(1001L, "BTC-USDT", MarginMode.CROSS, PositionSide.NET))
                .thenReturn(Optional.of(new ReduceOnlyPosition(12L, 7L)));
        when(orderValidator.validate(any())).thenReturn(ValidationResult.ok(7L));
        when(reduceOnlyValidator.validate(any())).thenReturn(ValidationResult.ok(7L));
        when(orderFeeRepository.snapshot(eq(1001L), eq("BTC-USDT"), eq(7L), any()))
                .thenReturn(Optional.of(new OrderFeeSnapshot(200L, 500L, "INSTRUMENT")));
        when(orderRepository.nextSequence("order")).thenReturn(9002L);
        when(orderRepository.nextSequence("event")).thenReturn(9100L);
        when(orderRepository.nextSequence("command")).thenReturn(9200L);
        when(orderRepository.insert(any(OrderRecord.class))).thenReturn(true);

        var response = service.closePosition(new ClosePositionRequest(1001L, "close-long", "btc-usdt",
                MarginMode.CROSS, PositionSide.NET));

        assertThat(response.status()).isEqualTo(OrderStatus.ACCEPTED);
        assertThat(response.side()).isEqualTo(OrderSide.SELL);
        assertThat(response.orderType()).isEqualTo(OrderType.MARKET);
        assertThat(response.timeInForce()).isEqualTo(TimeInForce.IOC);
        assertThat(response.quantitySteps()).isEqualTo(12L);
        assertThat(response.reduceOnly()).isTrue();
        ArgumentCaptor<OrderRecord> orderCaptor = ArgumentCaptor.forClass(OrderRecord.class);
        verify(orderRepository).insert(orderCaptor.capture());
        assertThat(orderCaptor.getValue().clientOrderId()).isEqualTo("close-long");
        assertThat(orderCaptor.getValue().priceTicks()).isZero();
        verify(orderMarginRepository, never()).reserve(anyLong(), anyString(), anyString(), anyLong(), anyString(),
                any(), any(), anyLong(), any());
    }

    @Test
    void cancelOpenOrdersRequestsCancelOnlyForRepositorySelectedUserOrders() {
        OrderService service = service();
        OrderRecord first = order(9001L, "open-1", OrderStatus.ACCEPTED, null);
        OrderRecord second = order(9002L, "open-2", OrderStatus.PARTIALLY_FILLED, null);
        OrderRecord firstCancelRequested = order(9001L, "open-1", OrderStatus.CANCEL_REQUESTED, null);
        OrderRecord secondCancelRequested = order(9002L, "open-2", OrderStatus.CANCEL_REQUESTED, null);
        when(orderRepository.adminCancelableOrders(1001L, "BTC-USDT", 2)).thenReturn(List.of(first, second));
        when(orderRepository.requestCancel(eq(9001L), any())).thenReturn(true);
        when(orderRepository.requestCancel(eq(9002L), any())).thenReturn(true);
        when(orderRepository.findByOrderId(9001L)).thenReturn(Optional.of(firstCancelRequested));
        when(orderRepository.findByOrderId(9002L)).thenReturn(Optional.of(secondCancelRequested));
        when(orderRepository.nextSequence("event")).thenReturn(9100L, 9101L);
        when(orderRepository.nextSequence("command")).thenReturn(9200L, 9201L);

        var response = service.cancelOpenOrders(new CancelOpenOrdersRequest(1001L, "btc-usdt", 2));

        assertThat(response.requested()).isEqualTo(2);
        assertThat(response.completed()).isEqualTo(2);
        assertThat(response.failed()).isZero();
        assertThat(response.results()).extracting("order.orderId").containsExactly(9001L, 9002L);
        verify(orderRepository).adminCancelableOrders(1001L, "BTC-USDT", 2);
        verify(orderRepository).requestCancel(eq(9001L), any());
        verify(orderRepository).requestCancel(eq(9002L), any());
        verify(outboxRepository, times(4)).enqueue(eq("ORDER"), anyLong(), anyString(), eq("BTC-USDT"),
                anyString(), anyString(), any());
    }

    @Test
    void getRejectsOrderOutsideCurrentProductLine() {
        OrderService service = service(ProductLine.LINEAR_DELIVERY);
        OrderRecord accepted = order(9001L, "wrong-product", OrderStatus.ACCEPTED, null);
        when(orderRepository.findByOrderId(9001L)).thenReturn(Optional.of(accepted));
        when(orderRepository.orderMatchesContractType(9001L, "LINEAR_DELIVERY")).thenReturn(false);

        assertThatThrownBy(() -> service.get(9001L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("order not found: 9001");
    }

    @Test
    void cancelRejectsOrderOutsideCurrentProductLineBeforeMutating() {
        OrderService service = service(ProductLine.LINEAR_DELIVERY);
        OrderRecord accepted = order(9001L, "cancel-wrong-product", OrderStatus.ACCEPTED, null);
        when(orderRepository.findByOrderId(9001L)).thenReturn(Optional.of(accepted));
        when(orderRepository.orderMatchesContractType(9001L, "LINEAR_DELIVERY")).thenReturn(false);

        assertThatThrownBy(() -> service.cancel(new CancelOrderRequest(1001L, 9001L)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("order not found: 9001");

        verify(orderRepository, never()).requestCancel(eq(9001L), any());
    }

    @Test
    void adminCancelOrderPublishesCancelEventAndCommand() throws Exception {
        TraceContext.set("trace-admin-cancel");
        OrderService service = service();
        OrderRecord accepted = order(9001L, "cancel-1", OrderStatus.ACCEPTED, null);
        OrderRecord cancelRequested = order(9001L, "cancel-1", OrderStatus.CANCEL_REQUESTED, null);
        when(orderRepository.findByOrderId(9001L))
                .thenReturn(Optional.of(accepted), Optional.of(cancelRequested));
        when(orderRepository.requestCancel(eq(9001L), any())).thenReturn(true);
        when(orderRepository.nextSequence("event")).thenReturn(9100L);
        when(orderRepository.nextSequence("command")).thenReturn(9200L);

        var response = service.adminCancelOrder(9001L, "risk operation");

        assertThat(response.cancelRequested()).isTrue();
        assertThat(response.order().status()).isEqualTo(OrderStatus.CANCEL_REQUESTED);
        ArgumentCaptor<OrderEvent> eventCaptor = ArgumentCaptor.forClass(OrderEvent.class);
        verify(orderRepository).insertEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().eventType()).isEqualTo(OrderEventType.CANCEL_REQUESTED);
        assertThat(eventCaptor.getValue().reason()).isEqualTo("admin cancel: risk operation");
        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(outboxRepository, times(2)).enqueue(eq("ORDER"), eq(9001L), anyString(), eq("BTC-USDT"),
                anyString(), payloadCaptor.capture(), any());
        OrderCommandEvent command = new ObjectMapper().readValue(payloadCaptor.getAllValues().get(1),
                OrderCommandEvent.class);
        assertThat(command.commandType()).isEqualTo(com.surprising.trading.api.model.OrderCommandType.CANCEL);
        assertThat(command.traceId()).isEqualTo("trace-admin-cancel");
    }

    @Test
    void adminBatchCancelOrdersCancelsOnlyRepositorySelectedOrders() {
        OrderService service = service();
        OrderRecord first = order(9001L, "batch-1", OrderStatus.ACCEPTED, null);
        OrderRecord second = order(9002L, "batch-2", OrderStatus.PARTIALLY_FILLED, null);
        OrderRecord firstCancelRequested = order(9001L, "batch-1", OrderStatus.CANCEL_REQUESTED, null);
        OrderRecord secondCancelRequested = order(9002L, "batch-2", OrderStatus.CANCEL_REQUESTED, null);
        when(orderRepository.adminCancelableOrders(null, "BTC-USDT", 2)).thenReturn(java.util.List.of(first, second));
        when(orderRepository.requestCancel(eq(9001L), any())).thenReturn(true);
        when(orderRepository.requestCancel(eq(9002L), any())).thenReturn(true);
        when(orderRepository.findByOrderId(9001L)).thenReturn(Optional.of(firstCancelRequested));
        when(orderRepository.findByOrderId(9002L)).thenReturn(Optional.of(secondCancelRequested));
        when(orderRepository.nextSequence("event")).thenReturn(9100L, 9101L);
        when(orderRepository.nextSequence("command")).thenReturn(9200L, 9201L);

        var response = service.adminCancelOrders(new AdminBatchCancelOrdersRequest(null, "BTC-USDT", 2,
                "symbol halt"));

        assertThat(response.requested()).isEqualTo(2);
        assertThat(response.canceled()).isEqualTo(2);
        assertThat(response.skipped()).isZero();
        verify(orderRepository).adminCancelableOrders(null, "BTC-USDT", 2);
        verify(orderRepository).requestCancel(eq(9001L), any());
        verify(orderRepository).requestCancel(eq(9002L), any());
        verify(orderRepository, times(2)).insertEvent(any());
        verify(outboxRepository, times(4)).enqueue(eq("ORDER"), anyLong(), anyString(), eq("BTC-USDT"),
                anyString(), anyString(), any());
    }

    @Test
    void adminCancelPreviewReturnsImpactAndSampleOrders() {
        OrderService service = service();
        OrderRecord first = order(9001L, "preview-1", OrderStatus.ACCEPTED, null);
        when(orderRepository.adminCancelableImpact(1001L, "BTC-USDT"))
                .thenReturn(new OrderRepository.CancelableOrderImpact(3, 25L, 2, 1));
        when(orderRepository.adminCancelableOrders(1001L, "BTC-USDT", 2)).thenReturn(java.util.List.of(first));

        var response = service.adminCancelPreview(1001L, "btc-usdt", 2);

        assertThat(response.userId()).isEqualTo(1001L);
        assertThat(response.symbol()).isEqualTo("BTC-USDT");
        assertThat(response.matched()).isEqualTo(3);
        assertThat(response.sampleSize()).isEqualTo(1);
        assertThat(response.totalRemainingQuantitySteps()).isEqualTo(25L);
        assertThat(response.buyOrders()).isEqualTo(2);
        assertThat(response.sellOrders()).isEqualTo(1);
        assertThat(response.orders()).extracting("orderId").containsExactly(9001L);
    }

    @Test
    void adminOrdersDelegatesCursorAndSort() {
        OrderService service = service();
        OrderRecord row = order(9001L, "admin-page", OrderStatus.ACCEPTED, null);
        when(orderRepository.adminOrderPage(1001L, "BTC-USDT", OrderStatus.ACCEPTED, 9001L, 25,
                "cursor-1", "createdAt.asc"))
                .thenReturn(new AdminCursorPage.CursorPage<>(java.util.List.of(row),
                        "cursor-2", true, "createdAt.asc", 25));

        var response = service.adminOrders(1001L, "btc-usdt", "accepted", 9001L, 25,
                "cursor-1", "createdAt.asc");

        assertThat(response.orders()).extracting("orderId").containsExactly(9001L);
        assertThat(response.nextCursor()).isEqualTo("cursor-2");
        assertThat(response.hasMore()).isTrue();
        assertThat(response.sort()).isEqualTo("createdAt.asc");
        assertThat(response.limit()).isEqualTo(25);
        verify(orderRepository).adminOrderPage(1001L, "BTC-USDT", OrderStatus.ACCEPTED, 9001L, 25,
                "cursor-1", "createdAt.asc");
    }

    @Test
    void adminOrdersDelegatesProductLineAsContractType() {
        OrderService service = service();
        OrderRecord row = order(9001L, "admin-product-line", OrderStatus.ACCEPTED, null);
        when(orderRepository.adminOrderPage(1001L, "BTC-USDT", OrderStatus.ACCEPTED, 9001L, 25,
                "LINEAR_DELIVERY", "cursor-1", "createdAt.asc"))
                .thenReturn(new AdminCursorPage.CursorPage<>(java.util.List.of(row),
                        "cursor-2", true, "createdAt.asc", 25));

        var response = service.adminOrders(1001L, "btc-usdt", "accepted", 9001L, 25,
                "cursor-1", "createdAt.asc", ProductLine.LINEAR_DELIVERY);

        assertThat(response.orders()).extracting("orderId").containsExactly(9001L);
        verify(orderRepository).adminOrderPage(1001L, "BTC-USDT", OrderStatus.ACCEPTED, 9001L, 25,
                "LINEAR_DELIVERY", "cursor-1", "createdAt.asc");
    }

    @Test
    void adminMatchTradesDelegatesCursorAndSort() {
        OrderService service = service();
        var trade = new com.surprising.trading.api.model.AdminMatchTradeResponse(
                7001L, 8001L, "BTC-USDT", 9001L, 1001L, OrderSide.BUY, MarginMode.CROSS,
                9002L, 1002L, MarginMode.CROSS, 65_000L, 3L, true, false,
                "trace-trade", Instant.parse("2026-07-01T00:00:00Z"),
                Instant.parse("2026-07-01T00:00:01Z"));
        when(orderRepository.matchTradePage(1001L, 9001L, "BTC-USDT", 25,
                "cursor-1", "eventTime.asc"))
                .thenReturn(new AdminCursorPage.CursorPage<>(java.util.List.of(trade),
                        "cursor-2", true, "eventTime.asc", 25));

        var response = service.adminMatchTrades(1001L, 9001L, "btc-usdt", 25,
                "cursor-1", "eventTime.asc");

        assertThat(response.trades()).extracting("tradeId").containsExactly(7001L);
        assertThat(response.nextCursor()).isEqualTo("cursor-2");
        assertThat(response.hasMore()).isTrue();
        assertThat(response.sort()).isEqualTo("eventTime.asc");
        assertThat(response.limit()).isEqualTo(25);
        verify(orderRepository).matchTradePage(1001L, 9001L, "BTC-USDT", 25,
                "cursor-1", "eventTime.asc");
    }

    @Test
    void adminCancelOrderRejectsMismatchedProductLine() {
        OrderService service = service();
        OrderRecord accepted = order(9001L, "cancel-wrong-product", OrderStatus.ACCEPTED, null);
        when(orderRepository.findByOrderId(9001L)).thenReturn(Optional.of(accepted));
        when(orderRepository.orderMatchesContractType(9001L, "LINEAR_DELIVERY")).thenReturn(false);

        assertThatThrownBy(() -> service.adminCancelOrder(9001L, "risk operation", ProductLine.LINEAR_DELIVERY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("order not found: 9001");

        verify(orderRepository, never()).requestCancel(eq(9001L), any());
    }

    @Test
    void adminCancelBySymbolRequiresSymbolAndCancelsSelectedSymbolOrders() {
        OrderService service = service();
        OrderRecord first = order(9001L, "symbol-1", OrderStatus.ACCEPTED, null);
        OrderRecord firstCancelRequested = order(9001L, "symbol-1", OrderStatus.CANCEL_REQUESTED, null);
        when(orderRepository.adminCancelableOrders(null, "BTC-USDT", 1)).thenReturn(java.util.List.of(first));
        when(orderRepository.requestCancel(eq(9001L), any())).thenReturn(true);
        when(orderRepository.findByOrderId(9001L)).thenReturn(Optional.of(firstCancelRequested));
        when(orderRepository.nextSequence("event")).thenReturn(9100L);
        when(orderRepository.nextSequence("command")).thenReturn(9200L);

        var response = service.adminCancelBySymbol(new AdminCancelBySymbolRequest("btc-usdt", 1,
                "symbol halt"));

        assertThat(response.requested()).isEqualTo(1);
        assertThat(response.canceled()).isEqualTo(1);
        verify(orderRepository).adminCancelableOrders(null, "BTC-USDT", 1);
    }

    private OrderService service() {
        return service(new TradingOrderProperties());
    }

    private OrderService service(ProductLine productLine) {
        TradingOrderProperties properties = new TradingOrderProperties();
        properties.getKafka().setProductTopicsEnabled(true);
        properties.getKafka().setProductLine(productLine);
        return service(properties);
    }

    private OrderService service(TradingOrderProperties properties) {
        return new OrderService(new ObjectMapper(), properties, orderValidator,
                reduceOnlyValidator, orderRepository, orderFeeRepository, orderMarginRepository,
                spotOrderReservationRepository, outboxRepository);
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
                200L, 500L, false, false, status, rejectReason, now, now);
    }
}
