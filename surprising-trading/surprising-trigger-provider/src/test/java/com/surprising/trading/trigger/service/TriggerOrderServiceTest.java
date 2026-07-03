package com.surprising.trading.trigger.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.trading.api.TraceContext;
import com.surprising.trading.api.client.OrderRpcApi;
import com.surprising.trading.api.model.AdminCursorPage;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.OrderResponse;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.OrderStatus;
import com.surprising.trading.api.model.OrderType;
import com.surprising.trading.api.model.PlaceOrderRequest;
import com.surprising.trading.api.model.PlaceTriggerOrderRequest;
import com.surprising.trading.api.model.PositionMode;
import com.surprising.trading.api.model.PositionSide;
import com.surprising.trading.api.model.TimeInForce;
import com.surprising.trading.api.model.TriggerCondition;
import com.surprising.trading.api.model.TriggerOrderResponse;
import com.surprising.trading.api.model.TriggerOrderStatus;
import com.surprising.trading.api.model.TriggerOrderType;
import com.surprising.trading.api.model.TriggerPriceType;
import com.surprising.trading.trigger.config.TriggerProperties;
import com.surprising.trading.trigger.model.MarkTrigger;
import com.surprising.trading.trigger.model.TriggerOrderRecord;
import com.surprising.trading.trigger.model.TriggerPosition;
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
        PlaceTriggerOrderRequest request = new PlaceTriggerOrderRequest(1001L, "tp-1", "oco-1", "btc-usdt",
                OrderSide.SELL, TriggerOrderType.TAKE_PROFIT, TriggerPriceType.MARK_PRICE, 70_000L,
                OrderType.LIMIT, TimeInForce.GTC, 69_950L, 10L, MarginMode.CROSS, null);
        when(repository.nextSequence("trigger-order")).thenReturn(501L);
        stubCloseCapacity(repository, 20L, 0L, 0L, 0L);
        when(repository.insert(any())).thenReturn(true);
        ArgumentCaptor<TriggerOrderRecord> orderCaptor = ArgumentCaptor.forClass(TriggerOrderRecord.class);

        TriggerOrderResponse response = service.place(request);

        assertThat(response.triggerOrderId()).isEqualTo(501L);
        assertThat(response.positionSide()).isEqualTo(PositionSide.NET);
        verify(repository).insert(orderCaptor.capture());
        TriggerOrderRecord saved = orderCaptor.getValue();
        assertThat(saved.symbol()).isEqualTo("BTC-USDT");
        assertThat(saved.ocoGroupId()).isEqualTo("oco-1");
        assertThat(saved.triggerCondition()).isEqualTo(TriggerCondition.GREATER_OR_EQUAL);
        assertThat(saved.status()).isEqualTo(TriggerOrderStatus.PENDING);
        assertThat(saved.traceId()).isNotBlank();
    }

    @Test
    void placeDerivesStopLossCloseLongCondition() {
        TriggerOrderRepository repository = mock(TriggerOrderRepository.class);
        TriggerOrderService service = new TriggerOrderService(repository, mock(OrderRpcApi.class),
                new TriggerProperties());
        PlaceTriggerOrderRequest request = new PlaceTriggerOrderRequest(1001L, "sl-long", null, "btc-usdt",
                OrderSide.SELL, TriggerOrderType.STOP_LOSS, TriggerPriceType.MARK_PRICE, 60_000L,
                OrderType.MARKET, TimeInForce.IOC, 0L, 4L, MarginMode.CROSS, null);
        when(repository.nextSequence("trigger-order")).thenReturn(502L);
        stubCloseCapacity(repository, 20L, 0L, 0L, 0L);
        when(repository.insert(any())).thenReturn(true);
        ArgumentCaptor<TriggerOrderRecord> orderCaptor = ArgumentCaptor.forClass(TriggerOrderRecord.class);

        TriggerOrderResponse response = service.place(request);

        assertThat(response.triggerOrderId()).isEqualTo(502L);
        verify(repository).insert(orderCaptor.capture());
        TriggerOrderRecord saved = orderCaptor.getValue();
        assertThat(saved.triggerCondition()).isEqualTo(TriggerCondition.LESS_OR_EQUAL);
        assertThat(saved.quantitySteps()).isEqualTo(4L);
    }

    @Test
    void placeDerivesStopLossCloseShortConditionInHedgeMode() {
        TriggerOrderRepository repository = mock(TriggerOrderRepository.class);
        TriggerOrderService service = new TriggerOrderService(repository, mock(OrderRpcApi.class),
                new TriggerProperties());
        PlaceTriggerOrderRequest request = new PlaceTriggerOrderRequest(1001L, "sl-short", null, "btc-usdt",
                OrderSide.BUY, TriggerOrderType.STOP_LOSS, TriggerPriceType.MARK_PRICE, 80_000L,
                OrderType.MARKET, TimeInForce.IOC, 0L, 5L, MarginMode.CROSS, PositionSide.SHORT, null);
        when(repository.positionMode(1001L)).thenReturn(PositionMode.HEDGE);
        when(repository.nextSequence("trigger-order")).thenReturn(503L);
        stubCloseCapacity(repository, -20L, 0L, 0L, 0L);
        when(repository.insert(any())).thenReturn(true);
        ArgumentCaptor<TriggerOrderRecord> orderCaptor = ArgumentCaptor.forClass(TriggerOrderRecord.class);

        TriggerOrderResponse response = service.place(request);

        assertThat(response.positionSide()).isEqualTo(PositionSide.SHORT);
        verify(repository).insert(orderCaptor.capture());
        TriggerOrderRecord saved = orderCaptor.getValue();
        assertThat(saved.triggerCondition()).isEqualTo(TriggerCondition.GREATER_OR_EQUAL);
        assertThat(saved.positionSide()).isEqualTo(PositionSide.SHORT);
    }

    @Test
    void placeReturnsExistingClientTriggerOrder() {
        TriggerOrderRepository repository = mock(TriggerOrderRepository.class);
        TriggerOrderService service = new TriggerOrderService(repository, mock(OrderRpcApi.class),
                new TriggerProperties());
        TriggerOrderRecord existing = record(501L, TriggerOrderStatus.PENDING);
        when(repository.findByClientTriggerOrderId(1001L, "sl-1")).thenReturn(Optional.of(existing));

        TriggerOrderResponse response = service.place(new PlaceTriggerOrderRequest(1001L, "sl-1", null, "BTC-USDT",
                OrderSide.SELL, TriggerOrderType.STOP_LOSS, TriggerPriceType.MARK_PRICE, 60_000L,
                OrderType.MARKET, TimeInForce.IOC, 0L, 10L, MarginMode.CROSS, null));

        assertThat(response.triggerOrderId()).isEqualTo(501L);
        assertThat(response.status()).isEqualTo(TriggerOrderStatus.PENDING);
    }

    @Test
    void placeRejectsMarginModeSwitchWhileOtherModeIsActive() {
        TriggerOrderRepository repository = mock(TriggerOrderRepository.class);
        TriggerOrderService service = new TriggerOrderService(repository, mock(OrderRpcApi.class),
                new TriggerProperties());
        PlaceTriggerOrderRequest request = new PlaceTriggerOrderRequest(1001L, "tp-iso", null, "BTC-USDT",
                OrderSide.SELL, TriggerOrderType.TAKE_PROFIT, TriggerPriceType.MARK_PRICE, 70_000L,
                OrderType.MARKET, TimeInForce.IOC, 0L, 10L, MarginMode.ISOLATED, null);
        when(repository.hasActiveMarginModeConflict(1001L, "BTC-USDT", MarginMode.ISOLATED))
                .thenReturn(true);

        assertThatThrownBy(() -> service.place(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("margin mode switch requires closing positions and open orders first");

        verify(repository).lockUserSymbolMarginScope(1001L, "BTC-USDT");
        verify(repository, never()).nextSequence("trigger-order");
        verify(repository, never()).insert(any());
    }

    @Test
    void placeRejectsHedgePositionSideInOneWayModeBeforePersistence() {
        TriggerOrderRepository repository = mock(TriggerOrderRepository.class);
        TriggerOrderService service = new TriggerOrderService(repository, mock(OrderRpcApi.class),
                new TriggerProperties());
        PlaceTriggerOrderRequest request = new PlaceTriggerOrderRequest(1001L, "tp-hedge", null, "BTC-USDT",
                OrderSide.SELL, TriggerOrderType.TAKE_PROFIT, TriggerPriceType.MARK_PRICE, 70_000L,
                OrderType.MARKET, TimeInForce.IOC, 0L, 10L, MarginMode.CROSS, PositionSide.SHORT, null);

        assertThatThrownBy(() -> service.place(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("positionSide LONG/SHORT requires HEDGE position mode");

        verify(repository, never()).lockUserSymbolMarginScope(anyLong(), anyString());
        verify(repository, never()).nextSequence("trigger-order");
        verify(repository, never()).insert(any());
    }

    @Test
    void placeRejectsTriggerOrderWithoutOpenPosition() {
        TriggerOrderRepository repository = mock(TriggerOrderRepository.class);
        TriggerOrderService service = new TriggerOrderService(repository, mock(OrderRpcApi.class),
                new TriggerProperties());
        PlaceTriggerOrderRequest request = new PlaceTriggerOrderRequest(1001L, "tp-empty", null, "BTC-USDT",
                OrderSide.SELL, TriggerOrderType.TAKE_PROFIT, TriggerPriceType.MARK_PRICE, 70_000L,
                OrderType.MARKET, TimeInForce.IOC, 0L, 10L, MarginMode.CROSS, null);
        when(repository.lockedPosition(1001L, "BTC-USDT", MarginMode.CROSS, PositionSide.NET))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.place(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("trigger order requires an open position");

        verify(repository, never()).nextSequence("trigger-order");
        verify(repository, never()).insert(any());
    }

    @Test
    void placeRejectsTriggerSideThatWouldIncreasePosition() {
        TriggerOrderRepository repository = mock(TriggerOrderRepository.class);
        TriggerOrderService service = new TriggerOrderService(repository, mock(OrderRpcApi.class),
                new TriggerProperties());
        PlaceTriggerOrderRequest request = new PlaceTriggerOrderRequest(1001L, "tp-wrong-side", null, "BTC-USDT",
                OrderSide.BUY, TriggerOrderType.TAKE_PROFIT, TriggerPriceType.MARK_PRICE, 70_000L,
                OrderType.MARKET, TimeInForce.IOC, 0L, 10L, MarginMode.CROSS, null);
        stubCloseCapacity(repository, 20L, 0L, 0L, 0L);

        assertThatThrownBy(() -> service.place(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("trigger order side does not reduce current position");

        verify(repository, never()).nextSequence("trigger-order");
        verify(repository, never()).insert(any());
    }

    @Test
    void placeRejectsWhenMultiLevelTriggersWouldExceedAvailablePosition() {
        TriggerOrderRepository repository = mock(TriggerOrderRepository.class);
        TriggerOrderService service = new TriggerOrderService(repository, mock(OrderRpcApi.class),
                new TriggerProperties());
        PlaceTriggerOrderRequest request = new PlaceTriggerOrderRequest(1001L, "tp-too-much", null, "BTC-USDT",
                OrderSide.SELL, TriggerOrderType.TAKE_PROFIT, TriggerPriceType.MARK_PRICE, 72_000L,
                OrderType.MARKET, TimeInForce.IOC, 0L, 5L, MarginMode.CROSS, null);
        stubCloseCapacity(repository, 10L, 2L, 4L, 0L);

        assertThatThrownBy(() -> service.place(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("trigger order quantity exceeds available position");

        verify(repository, never()).nextSequence("trigger-order");
        verify(repository, never()).insert(any());
    }

    @Test
    void placeCountsSameOcoGroupByMaximumCloseQuantity() {
        TriggerOrderRepository repository = mock(TriggerOrderRepository.class);
        TriggerOrderService service = new TriggerOrderService(repository, mock(OrderRpcApi.class),
                new TriggerProperties());
        PlaceTriggerOrderRequest request = new PlaceTriggerOrderRequest(1001L, "sl-oco", "oco-1", "BTC-USDT",
                OrderSide.SELL, TriggerOrderType.STOP_LOSS, TriggerPriceType.MARK_PRICE, 60_000L,
                OrderType.MARKET, TimeInForce.IOC, 0L, 6L, MarginMode.CROSS, null);
        when(repository.nextSequence("trigger-order")).thenReturn(504L);
        stubCloseCapacity(repository, 10L, 0L, 6L, 6L);
        when(repository.insert(any())).thenReturn(true);

        TriggerOrderResponse response = service.place(request);

        assertThat(response.triggerOrderId()).isEqualTo(504L);
        verify(repository).insert(any());
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
    void onMarkPriceWithoutPersistedMarkPriceDoesNotClaimOrPlaceOrder() {
        TriggerOrderRepository repository = mock(TriggerOrderRepository.class);
        OrderRpcApi orderRpcApi = mock(OrderRpcApi.class);
        TriggerOrderService service = new TriggerOrderService(repository, orderRpcApi, new TriggerProperties());
        when(repository.markPriceTicks("BTC-USDT", 98L)).thenReturn(OptionalLong.empty());

        assertThatThrownBy(() -> service.onMarkPrice(new MarkTrigger("BTC-USDT", 98L,
                Instant.parse("2026-07-01T00:00:00Z"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mark price ticks unavailable: BTC-USDT sequence=98");

        verify(repository, never()).claimTriggered(anyString(), anyLong(), anyLong(), any(), anyInt(), any());
        verify(orderRpcApi, never()).place(any());
    }

    @Test
    void onMarkPricePassesHedgePositionSideToReduceOnlyOrder() {
        TriggerOrderRepository repository = mock(TriggerOrderRepository.class);
        OrderRpcApi orderRpcApi = mock(OrderRpcApi.class);
        TriggerOrderService service = new TriggerOrderService(repository, orderRpcApi, new TriggerProperties());
        TriggerOrderRecord claimed = record(601L, OrderSide.SELL, TriggerOrderType.TAKE_PROFIT,
                TriggerCondition.GREATER_OR_EQUAL, 71_000L, 3L, PositionSide.LONG,
                TriggerOrderStatus.TRIGGERING);
        when(repository.markPriceTicks("BTC-USDT", 43L)).thenReturn(OptionalLong.of(71_001L));
        when(repository.claimTriggered(eq("BTC-USDT"), eq(71_001L), eq(43L), any(), anyInt(), any()))
                .thenReturn(List.of(claimed));
        when(orderRpcApi.place(any())).thenReturn(orderResponse(9101L, OrderStatus.ACCEPTED, null));
        ArgumentCaptor<PlaceOrderRequest> orderCaptor = ArgumentCaptor.forClass(PlaceOrderRequest.class);

        service.onMarkPrice(new MarkTrigger("BTC-USDT", 43L, Instant.parse("2026-07-01T00:00:00Z")));

        verify(orderRpcApi).place(orderCaptor.capture());
        assertThat(orderCaptor.getValue().positionSide()).isEqualTo(PositionSide.LONG);
        assertThat(orderCaptor.getValue().side()).isEqualTo(OrderSide.SELL);
        assertThat(orderCaptor.getValue().quantitySteps()).isEqualTo(3L);
        verify(repository).markTriggered(eq(601L), eq(9101L), any());
    }

    @Test
    void onMarkPriceExecutesMultipleClaimedLevelsIndependently() {
        TriggerOrderRepository repository = mock(TriggerOrderRepository.class);
        OrderRpcApi orderRpcApi = mock(OrderRpcApi.class);
        TriggerOrderService service = new TriggerOrderService(repository, orderRpcApi, new TriggerProperties());
        TriggerOrderRecord firstLevel = record(701L, OrderSide.SELL, TriggerOrderType.TAKE_PROFIT,
                TriggerCondition.GREATER_OR_EQUAL, 70_000L, 2L, PositionSide.NET,
                TriggerOrderStatus.TRIGGERING);
        TriggerOrderRecord secondLevel = record(702L, OrderSide.SELL, TriggerOrderType.TAKE_PROFIT,
                TriggerCondition.GREATER_OR_EQUAL, 72_000L, 5L, PositionSide.NET,
                TriggerOrderStatus.TRIGGERING);
        when(repository.markPriceTicks("BTC-USDT", 44L)).thenReturn(OptionalLong.of(72_001L));
        when(repository.claimTriggered(eq("BTC-USDT"), eq(72_001L), eq(44L), any(), anyInt(), any()))
                .thenReturn(List.of(firstLevel, secondLevel));
        when(orderRpcApi.place(any()))
                .thenReturn(orderResponse(9201L, OrderStatus.ACCEPTED, null))
                .thenReturn(orderResponse(9202L, OrderStatus.ACCEPTED, null));
        ArgumentCaptor<PlaceOrderRequest> orderCaptor = ArgumentCaptor.forClass(PlaceOrderRequest.class);

        service.onMarkPrice(new MarkTrigger("BTC-USDT", 44L, Instant.parse("2026-07-01T00:00:00Z")));

        verify(orderRpcApi, org.mockito.Mockito.times(2)).place(orderCaptor.capture());
        assertThat(orderCaptor.getAllValues())
                .extracting(PlaceOrderRequest::clientOrderId, PlaceOrderRequest::quantitySteps)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("trigger-701", 2L),
                        org.assertj.core.groups.Tuple.tuple("trigger-702", 5L));
        verify(repository).markTriggered(eq(701L), eq(9201L), any());
        verify(repository).markTriggered(eq(702L), eq(9202L), any());
    }

    @Test
    void liquidationClosedPositionBeforeTriggerMarksFailedWithoutOpeningReversePosition() {
        TriggerOrderRepository repository = mock(TriggerOrderRepository.class);
        OrderRpcApi orderRpcApi = mock(OrderRpcApi.class);
        TriggerOrderService service = new TriggerOrderService(repository, orderRpcApi, new TriggerProperties());
        TriggerOrderRecord claimed = record(801L, OrderSide.SELL, TriggerOrderType.STOP_LOSS,
                TriggerCondition.LESS_OR_EQUAL, 60_000L, 4L, PositionSide.LONG,
                TriggerOrderStatus.TRIGGERING);
        when(repository.markPriceTicks("BTC-USDT", 45L)).thenReturn(OptionalLong.of(59_999L));
        when(repository.claimTriggered(eq("BTC-USDT"), eq(59_999L), eq(45L), any(), anyInt(), any()))
                .thenReturn(List.of(claimed));
        when(orderRpcApi.place(any())).thenReturn(orderResponse(9301L, OrderStatus.REJECTED,
                "reduce-only requires an open position"));
        ArgumentCaptor<PlaceOrderRequest> orderCaptor = ArgumentCaptor.forClass(PlaceOrderRequest.class);

        service.onMarkPrice(new MarkTrigger("BTC-USDT", 45L, Instant.parse("2026-07-01T00:00:00Z")));

        verify(orderRpcApi).place(orderCaptor.capture());
        assertThat(orderCaptor.getValue().clientOrderId()).isEqualTo("trigger-801");
        assertThat(orderCaptor.getValue().side()).isEqualTo(OrderSide.SELL);
        assertThat(orderCaptor.getValue().positionSide()).isEqualTo(PositionSide.LONG);
        assertThat(orderCaptor.getValue().reduceOnly()).isTrue();
        verify(repository).markTriggerFailed(eq(801L), eq(9301L),
                eq("reduce-only requires an open position"), any());
        verify(repository, never()).markTriggered(anyLong(), anyLong(), any());
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

    @Test
    void adminOrdersNormalizesFiltersAndMapsRows() {
        TriggerOrderRepository repository = mock(TriggerOrderRepository.class);
        TriggerOrderService service = new TriggerOrderService(repository, mock(OrderRpcApi.class),
                new TriggerProperties());
        TriggerOrderRecord row = record(501L, TriggerOrderStatus.PENDING);
        when(repository.adminOrderPage(1001L, "BTC-USDT", TriggerOrderStatus.PENDING, 501L, 50,
                "cursor-1", "createdAt.asc"))
                .thenReturn(new AdminCursorPage.CursorPage<>(List.of(row),
                        "cursor-2", true, "createdAt.asc", 50));

        var response = service.adminOrders(1001L, "btc-usdt", "pending", 501L, 50,
                "cursor-1", "createdAt.asc");

        assertThat(response.count()).isEqualTo(1);
        assertThat(response.orders().getFirst().triggerOrderId()).isEqualTo(501L);
        assertThat(response.nextCursor()).isEqualTo("cursor-2");
        assertThat(response.hasMore()).isTrue();
        assertThat(response.sort()).isEqualTo("createdAt.asc");
        assertThat(response.limit()).isEqualTo(50);
        verify(repository).adminOrderPage(1001L, "BTC-USDT", TriggerOrderStatus.PENDING, 501L, 50,
                "cursor-1", "createdAt.asc");
    }

    @Test
    void adminTimelineBuildsExecutionEvents() {
        TriggerOrderRepository repository = mock(TriggerOrderRepository.class);
        TriggerOrderService service = new TriggerOrderService(repository, mock(OrderRpcApi.class),
                new TriggerProperties());
        Instant created = Instant.parse("2026-07-01T00:00:00Z");
        Instant triggered = Instant.parse("2026-07-01T00:01:00Z");
        Instant updated = Instant.parse("2026-07-01T00:01:02Z");
        TriggerOrderRecord row = new TriggerOrderRecord(501L, 1001L, "tp-1", "oco-1", "BTC-USDT",
                OrderSide.SELL, TriggerOrderType.TAKE_PROFIT, TriggerPriceType.MARK_PRICE,
                TriggerCondition.GREATER_OR_EQUAL, 70_000L, OrderType.MARKET, TimeInForce.IOC, 0L, 10L,
                MarginMode.CROSS, TriggerOrderStatus.TRIGGERED, 9001L, 42L, 70_001L, null,
                "trace-501", null, triggered, created, updated);
        when(repository.findById(501L)).thenReturn(Optional.of(row));

        var response = service.adminTimeline(501L);

        assertThat(response.order().triggerOrderId()).isEqualTo(501L);
        assertThat(response.events()).extracting("eventType")
                .containsExactly("CREATED", "TRIGGERED_MARK", "EXECUTION_PLACED");
    }

    private TriggerOrderRecord record(long triggerOrderId, TriggerOrderStatus status) {
        return record(triggerOrderId, OrderSide.SELL, TriggerOrderType.TAKE_PROFIT,
                TriggerCondition.GREATER_OR_EQUAL, 70_000L, 10L, PositionSide.NET, status);
    }

    private TriggerOrderRecord record(long triggerOrderId,
                                      OrderSide side,
                                      TriggerOrderType triggerType,
                                      TriggerCondition triggerCondition,
                                      long triggerPriceTicks,
                                      long quantitySteps,
                                      PositionSide positionSide,
                                      TriggerOrderStatus status) {
        Instant now = Instant.parse("2026-07-01T00:00:00Z");
        return new TriggerOrderRecord(triggerOrderId, 1001L, "tp-" + triggerOrderId, "oco-1", "BTC-USDT", side,
                triggerType, TriggerPriceType.MARK_PRICE, triggerCondition, triggerPriceTicks,
                OrderType.MARKET, TimeInForce.IOC, 0L, quantitySteps, MarginMode.CROSS, positionSide, status, null,
                null, null, null, "trace-" + triggerOrderId, null, null, now, now);
    }

    private OrderResponse orderResponse(long orderId, OrderStatus status, String rejectReason) {
        Instant now = Instant.parse("2026-07-01T00:00:00Z");
        return new OrderResponse(orderId, 1001L, "trigger-501", "BTC-USDT", 1L, OrderSide.SELL,
                OrderType.MARKET, TimeInForce.IOC, 0L, 10L, 0L, 10L, MarginMode.CROSS, 0L, 0L,
                true, false, status, rejectReason, now, now);
    }

    private void stubCloseCapacity(TriggerOrderRepository repository,
                                   long signedQuantitySteps,
                                   long openReduceOnlySteps,
                                   long pendingTriggerCloseSteps,
                                   long sameOcoGroupMaxSteps) {
        when(repository.lockedPosition(anyLong(), anyString(), any(), any()))
                .thenReturn(Optional.of(new TriggerPosition(signedQuantitySteps, 1L)));
        when(repository.openReduceOnlySteps(anyLong(), anyString(), any(), any(), anyLong(), any()))
                .thenReturn(openReduceOnlySteps);
        when(repository.pendingTriggerCloseSteps(anyLong(), anyString(), any(), any(), any()))
                .thenReturn(pendingTriggerCloseSteps);
        when(repository.pendingTriggerOcoGroupMaxSteps(anyLong(), anyString(), any(), any(), any(), any()))
                .thenReturn(sameOcoGroupMaxSteps);
    }
}
