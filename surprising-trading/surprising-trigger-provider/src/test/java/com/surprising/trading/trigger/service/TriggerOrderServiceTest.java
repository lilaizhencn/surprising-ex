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

import com.surprising.account.api.model.PositionUpdatedEvent;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.TraceContext;
import com.surprising.trading.api.client.OrderRpcApi;
import com.surprising.trading.api.model.AdminCursorPage;
import com.surprising.trading.api.model.BatchCancelTriggerOrdersRequest;
import com.surprising.trading.api.model.BatchPlaceTriggerOrderRequest;
import com.surprising.trading.api.model.CancelOpenTriggerOrdersRequest;
import com.surprising.trading.api.model.CancelTriggerOrderRequest;
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
import com.surprising.trading.api.model.TriggerOrderBatchResponse;
import com.surprising.trading.api.model.TriggerOrderResponse;
import com.surprising.trading.api.model.TriggerOrderStatus;
import com.surprising.trading.api.model.TriggerOrderType;
import com.surprising.trading.trigger.config.TriggerProperties;
import com.surprising.trading.trigger.model.MarkTrigger;
import com.surprising.trading.trigger.model.TriggerOrderRecord;
import com.surprising.trading.trigger.model.TriggerPosition;
import com.surprising.trading.trigger.repository.TriggerOrderRepository;
import com.surprising.trading.trigger.repository.TriggerOrderOutboxRepository;
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
                OrderSide.SELL, TriggerOrderType.TAKE_PROFIT, 70_000L,
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
    void placeEnqueuesPendingSnapshotWithTheTriggerRow() {
        TriggerOrderRepository repository = mock(TriggerOrderRepository.class);
        TriggerOrderOutboxRepository outboxRepository = mock(TriggerOrderOutboxRepository.class);
        TriggerOrderService service = new TriggerOrderService(repository, mock(OrderRpcApi.class),
                new TriggerProperties(), TriggerOrderIndex.disabled(), outboxRepository, null);
        PlaceTriggerOrderRequest request = new PlaceTriggerOrderRequest(1001L, "tp-push", null, "btc-usdt",
                OrderSide.SELL, TriggerOrderType.TAKE_PROFIT, 70_000L,
                OrderType.MARKET, TimeInForce.IOC, 0L, 2L, MarginMode.CROSS, null);
        when(repository.nextSequence("trigger-order")).thenReturn(598L);
        stubCloseCapacity(repository, 10L, 0L, 0L, 0L);
        when(repository.insert(any())).thenReturn(true);
        ArgumentCaptor<TriggerOrderRecord> row = ArgumentCaptor.forClass(TriggerOrderRecord.class);
        ArgumentCaptor<TriggerOrderResponse> snapshot = ArgumentCaptor.forClass(TriggerOrderResponse.class);

        service.place(request);

        verify(outboxRepository).enqueue(row.capture(), snapshot.capture());
        assertThat(row.getValue().triggerOrderId()).isEqualTo(598L);
        assertThat(row.getValue().status()).isEqualTo(TriggerOrderStatus.PENDING);
        assertThat(snapshot.getValue().triggerOrderId()).isEqualTo(598L);
        assertThat(snapshot.getValue().status()).isEqualTo(TriggerOrderStatus.PENDING);
    }

    @Test
    void placeIndexesCommittedStaticTriggerCandidate() {
        TriggerOrderRepository repository = mock(TriggerOrderRepository.class);
        TriggerOrderIndex index = mock(TriggerOrderIndex.class);
        TriggerOrderService service = new TriggerOrderService(repository, mock(OrderRpcApi.class),
                new TriggerProperties(), index);
        PlaceTriggerOrderRequest request = new PlaceTriggerOrderRequest(1001L, "tp-redis", null, "BTC-USDT",
                OrderSide.SELL, TriggerOrderType.TAKE_PROFIT, 70_000L,
                OrderType.MARKET, TimeInForce.IOC, 0L, 2L, MarginMode.CROSS, null);
        when(repository.nextSequence("trigger-order")).thenReturn(599L);
        stubCloseCapacity(repository, 10L, 0L, 0L, 0L);
        when(repository.insert(any())).thenReturn(true);
        ArgumentCaptor<TriggerOrderRecord> orderCaptor = ArgumentCaptor.forClass(TriggerOrderRecord.class);

        service.place(request);

        verify(index).indexPlaced(orderCaptor.capture());
        assertThat(orderCaptor.getValue().triggerOrderId()).isEqualTo(599L);
        assertThat(orderCaptor.getValue().status()).isEqualTo(TriggerOrderStatus.PENDING);
    }

    @Test
    void placeDerivesStopLossCloseLongCondition() {
        TriggerOrderRepository repository = mock(TriggerOrderRepository.class);
        TriggerOrderService service = new TriggerOrderService(repository, mock(OrderRpcApi.class),
                new TriggerProperties());
        PlaceTriggerOrderRequest request = new PlaceTriggerOrderRequest(1001L, "sl-long", null, "btc-usdt",
                OrderSide.SELL, TriggerOrderType.STOP_LOSS, 60_000L,
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
    void placeAcceptsTrailingStopCloseLongFields() {
        TriggerOrderRepository repository = mock(TriggerOrderRepository.class);
        TriggerOrderService service = new TriggerOrderService(repository, mock(OrderRpcApi.class),
                new TriggerProperties());
        PlaceTriggerOrderRequest request = new PlaceTriggerOrderRequest(1001L, "trail-long", null, "btc-usdt",
                OrderSide.SELL, TriggerOrderType.TRAILING_STOP, 0L,
                60_500L, 1_000L, OrderType.MARKET, TimeInForce.IOC, 0L, 4L, MarginMode.CROSS,
                PositionSide.NET, null);
        when(repository.nextSequence("trigger-order")).thenReturn(507L);
        stubCloseCapacity(repository, 20L, 0L, 0L, 0L);
        when(repository.insert(any())).thenReturn(true);
        ArgumentCaptor<TriggerOrderRecord> orderCaptor = ArgumentCaptor.forClass(TriggerOrderRecord.class);

        TriggerOrderResponse response = service.place(request);

        assertThat(response.triggerOrderId()).isEqualTo(507L);
        assertThat(response.triggerType()).isEqualTo(TriggerOrderType.TRAILING_STOP);
        assertThat(response.activationPriceTicks()).isEqualTo(60_500L);
        assertThat(response.callbackRatePpm()).isEqualTo(1_000L);
        verify(repository).insert(orderCaptor.capture());
        TriggerOrderRecord saved = orderCaptor.getValue();
        assertThat(saved.triggerCondition()).isEqualTo(TriggerCondition.LESS_OR_EQUAL);
        assertThat(saved.triggerPriceTicks()).isZero();
        assertThat(saved.activationPriceTicks()).isEqualTo(60_500L);
        assertThat(saved.callbackRatePpm()).isEqualTo(1_000L);
    }

    @Test
    void placeRejectsTrailingStopWithoutCallbackRate() {
        TriggerOrderRepository repository = mock(TriggerOrderRepository.class);
        TriggerOrderService service = new TriggerOrderService(repository, mock(OrderRpcApi.class),
                new TriggerProperties());
        PlaceTriggerOrderRequest request = new PlaceTriggerOrderRequest(1001L, "trail-no-callback", null,
                "BTC-USDT", OrderSide.SELL, TriggerOrderType.TRAILING_STOP, 0L,
                null, null, OrderType.MARKET, TimeInForce.IOC, 0L, 10L, MarginMode.CROSS,
                PositionSide.NET, null);

        assertThatThrownBy(() -> service.place(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("callbackRatePpm is required for trailing stop");

        verify(repository, never()).nextSequence("trigger-order");
        verify(repository, never()).insert(any());
    }

    @Test
    void placeRejectsLimitTrailingStopExecution() {
        TriggerOrderRepository repository = mock(TriggerOrderRepository.class);
        TriggerOrderService service = new TriggerOrderService(repository, mock(OrderRpcApi.class),
                new TriggerProperties());
        PlaceTriggerOrderRequest request = new PlaceTriggerOrderRequest(1001L, "trail-limit", null,
                "BTC-USDT", OrderSide.SELL, TriggerOrderType.TRAILING_STOP, 0L,
                null, 1_000L, OrderType.LIMIT, TimeInForce.GTC, 60_000L, 10L, MarginMode.CROSS,
                PositionSide.NET, null);

        assertThatThrownBy(() -> service.place(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("trailing stop execution requires MARKET");

        verify(repository, never()).nextSequence("trigger-order");
        verify(repository, never()).insert(any());
    }

    @Test
    void placeDerivesStopLossCloseShortConditionInHedgeMode() {
        TriggerOrderRepository repository = mock(TriggerOrderRepository.class);
        TriggerOrderService service = new TriggerOrderService(repository, mock(OrderRpcApi.class),
                new TriggerProperties());
        PlaceTriggerOrderRequest request = new PlaceTriggerOrderRequest(1001L, "sl-short", null, "btc-usdt",
                OrderSide.BUY, TriggerOrderType.STOP_LOSS, 80_000L,
                OrderType.MARKET, TimeInForce.IOC, 0L, 5L, MarginMode.CROSS, PositionSide.SHORT, null);
        when(repository.positionMode(ProductLine.LINEAR_PERPETUAL, 1001L)).thenReturn(PositionMode.HEDGE);
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
        when(repository.findByClientTriggerOrderId(ProductLine.LINEAR_PERPETUAL, 1001L, "sl-1"))
                .thenReturn(Optional.of(existing));

        TriggerOrderResponse response = service.place(new PlaceTriggerOrderRequest(1001L, "sl-1", null, "BTC-USDT",
                OrderSide.SELL, TriggerOrderType.STOP_LOSS, 60_000L,
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
                OrderSide.SELL, TriggerOrderType.TAKE_PROFIT, 70_000L,
                OrderType.MARKET, TimeInForce.IOC, 0L, 10L, MarginMode.ISOLATED, null);
        when(repository.hasActiveMarginModeConflict(ProductLine.LINEAR_PERPETUAL, 1001L, "BTC-USDT",
                MarginMode.ISOLATED))
                .thenReturn(true);

        assertThatThrownBy(() -> service.place(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("margin mode switch requires closing positions and open orders first");

        verify(repository).lockUserSymbolMarginScope(ProductLine.LINEAR_PERPETUAL, 1001L, "BTC-USDT");
        verify(repository, never()).nextSequence("trigger-order");
        verify(repository, never()).insert(any());
    }

    @Test
    void placeRejectsHedgePositionSideInOneWayModeBeforePersistence() {
        TriggerOrderRepository repository = mock(TriggerOrderRepository.class);
        TriggerOrderService service = new TriggerOrderService(repository, mock(OrderRpcApi.class),
                new TriggerProperties());
        PlaceTriggerOrderRequest request = new PlaceTriggerOrderRequest(1001L, "tp-hedge", null, "BTC-USDT",
                OrderSide.SELL, TriggerOrderType.TAKE_PROFIT, 70_000L,
                OrderType.MARKET, TimeInForce.IOC, 0L, 10L, MarginMode.CROSS, PositionSide.SHORT, null);

        assertThatThrownBy(() -> service.place(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("positionSide LONG/SHORT requires HEDGE position mode");

        verify(repository, never()).lockUserSymbolMarginScope(any(), anyLong(), anyString());
        verify(repository, never()).nextSequence("trigger-order");
        verify(repository, never()).insert(any());
    }

    @Test
    void placeRejectsTriggerOrderWithoutOpenPosition() {
        TriggerOrderRepository repository = mock(TriggerOrderRepository.class);
        TriggerOrderService service = new TriggerOrderService(repository, mock(OrderRpcApi.class),
                new TriggerProperties());
        PlaceTriggerOrderRequest request = new PlaceTriggerOrderRequest(1001L, "tp-empty", null, "BTC-USDT",
                OrderSide.SELL, TriggerOrderType.TAKE_PROFIT, 70_000L,
                OrderType.MARKET, TimeInForce.IOC, 0L, 10L, MarginMode.CROSS, null);
        when(repository.lockedPosition(ProductLine.LINEAR_PERPETUAL, 1001L, "BTC-USDT", MarginMode.CROSS,
                PositionSide.NET))
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
                OrderSide.BUY, TriggerOrderType.TAKE_PROFIT, 70_000L,
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
                OrderSide.SELL, TriggerOrderType.TAKE_PROFIT, 72_000L,
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
                OrderSide.SELL, TriggerOrderType.STOP_LOSS, 60_000L,
                OrderType.MARKET, TimeInForce.IOC, 0L, 6L, MarginMode.CROSS, null);
        when(repository.nextSequence("trigger-order")).thenReturn(504L);
        stubCloseCapacity(repository, 10L, 0L, 6L, 6L);
        when(repository.insert(any())).thenReturn(true);

        TriggerOrderResponse response = service.place(request);

        assertThat(response.triggerOrderId()).isEqualTo(504L);
        verify(repository).insert(any());
    }

    @Test
    void placeBatchKeepsItemFailuresIsolatedFromValidTriggerOrders() {
        TriggerOrderRepository repository = mock(TriggerOrderRepository.class);
        TriggerOrderService service = new TriggerOrderService(repository, mock(OrderRpcApi.class),
                new TriggerProperties());
        PlaceTriggerOrderRequest invalid = new PlaceTriggerOrderRequest(0L, "bad", null, "BTC-USDT",
                OrderSide.SELL, TriggerOrderType.TAKE_PROFIT, 70_000L,
                OrderType.MARKET, TimeInForce.IOC, 0L, 10L, MarginMode.CROSS, null);
        PlaceTriggerOrderRequest valid = new PlaceTriggerOrderRequest(1001L, "tp-batch", null, "BTC-USDT",
                OrderSide.SELL, TriggerOrderType.TAKE_PROFIT, 70_000L,
                OrderType.MARKET, TimeInForce.IOC, 0L, 10L, MarginMode.CROSS, null);
        when(repository.nextSequence("trigger-order")).thenReturn(505L);
        stubCloseCapacity(repository, 20L, 0L, 0L, 0L);
        when(repository.insert(any())).thenReturn(true);

        var response = service.placeBatch(new BatchPlaceTriggerOrderRequest(List.of(invalid, valid)));

        assertThat(response.requested()).isEqualTo(2);
        assertThat(response.completed()).isEqualTo(1);
        assertThat(response.failed()).isEqualTo(1);
        assertThat(response.results().get(0).success()).isFalse();
        assertThat(response.results().get(0).message()).isEqualTo("userId must be positive");
        assertThat(response.results().get(1).success()).isTrue();
        assertThat(response.results().get(1).order().triggerOrderId()).isEqualTo(505L);
        verify(repository).insert(any());
    }

    @Test
    void placeAtomicBatchRejectsWholeGroupWhenAnyItemFails() {
        TriggerOrderRepository repository = mock(TriggerOrderRepository.class);
        TriggerOrderService service = new TriggerOrderService(repository, mock(OrderRpcApi.class),
                new TriggerProperties());
        PlaceTriggerOrderRequest valid = new PlaceTriggerOrderRequest(1001L, "tp-atomic", null, "BTC-USDT",
                OrderSide.SELL, TriggerOrderType.TAKE_PROFIT, 70_000L,
                OrderType.MARKET, TimeInForce.IOC, 0L, 4L, MarginMode.CROSS, null);
        PlaceTriggerOrderRequest invalid = new PlaceTriggerOrderRequest(1001L, "sl-atomic", null, "BTC-USDT",
                OrderSide.SELL, TriggerOrderType.STOP_LOSS, 60_000L,
                OrderType.MARKET, TimeInForce.IOC, 0L, 7L, MarginMode.CROSS, null);
        when(repository.nextSequence("trigger-order")).thenReturn(506L);
        stubCloseCapacity(repository, 10L, 0L, 0L, 0L);
        when(repository.pendingTriggerCloseSteps(any(), anyLong(), anyString(), any(), any(), any()))
                .thenReturn(0L, 4L);
        when(repository.insert(any())).thenReturn(true);

        assertThatThrownBy(() -> service.placeBatch(new BatchPlaceTriggerOrderRequest(List.of(valid, invalid), true)))
                .isInstanceOf(AtomicTriggerBatchRejectedException.class)
                .satisfies(ex -> {
                    TriggerOrderBatchResponse response = ((AtomicTriggerBatchRejectedException) ex).response();
                    assertThat(response.requested()).isEqualTo(2);
                    assertThat(response.completed()).isZero();
                    assertThat(response.failed()).isEqualTo(2);
                    assertThat(response.results()).allSatisfy(item -> {
                        assertThat(item.success()).isFalse();
                        assertThat(item.order()).isNull();
                        assertThat(item.message()).isEqualTo(
                                "atomic batch rejected: trigger order quantity exceeds available position");
                    });
                });
    }

    @Test
    void cancelBatchCancelsEachUserTriggerOrder() {
        TriggerOrderRepository repository = mock(TriggerOrderRepository.class);
        TriggerOrderService service = new TriggerOrderService(repository, mock(OrderRpcApi.class),
                new TriggerProperties());
        TriggerOrderRecord first = record(501L, TriggerOrderStatus.PENDING);
        TriggerOrderRecord second = record(502L, TriggerOrderStatus.PENDING);
        TriggerOrderRecord firstCanceled = record(501L, TriggerOrderStatus.CANCELED);
        TriggerOrderRecord secondCanceled = record(502L, TriggerOrderStatus.CANCELED);
        when(repository.findById(501L)).thenReturn(Optional.of(first));
        when(repository.findById(502L)).thenReturn(Optional.of(second));
        when(repository.cancel(eq(1001L), eq(501L), any())).thenReturn(Optional.of(firstCanceled));
        when(repository.cancel(eq(1001L), eq(502L), any())).thenReturn(Optional.of(secondCanceled));

        var response = service.cancelBatch(new BatchCancelTriggerOrdersRequest(List.of(
                new CancelTriggerOrderRequest(1001L, 501L),
                new CancelTriggerOrderRequest(1001L, 502L))));

        assertThat(response.requested()).isEqualTo(2);
        assertThat(response.completed()).isEqualTo(2);
        assertThat(response.failed()).isZero();
        assertThat(response.results()).extracting("order.status")
                .containsExactly(TriggerOrderStatus.CANCELED, TriggerOrderStatus.CANCELED);
        verify(repository).cancel(eq(1001L), eq(501L), any());
        verify(repository).cancel(eq(1001L), eq(502L), any());
    }

    @Test
    void cancelOpenOrdersCancelsOnlyPendingRepositorySelection() {
        TriggerOrderRepository repository = mock(TriggerOrderRepository.class);
        TriggerOrderService service = new TriggerOrderService(repository, mock(OrderRpcApi.class),
                new TriggerProperties());
        TriggerOrderRecord first = record(501L, TriggerOrderStatus.PENDING);
        TriggerOrderRecord second = record(502L, TriggerOrderStatus.PENDING);
        TriggerOrderRecord firstCanceled = record(501L, TriggerOrderStatus.CANCELED);
        TriggerOrderRecord secondCanceled = record(502L, TriggerOrderStatus.CANCELED);
        when(repository.pendingCancelableOrders(1001L, "BTC-USDT", 2)).thenReturn(List.of(first, second));
        when(repository.findById(501L)).thenReturn(Optional.of(first));
        when(repository.findById(502L)).thenReturn(Optional.of(second));
        when(repository.cancel(eq(1001L), eq(501L), any())).thenReturn(Optional.of(firstCanceled));
        when(repository.cancel(eq(1001L), eq(502L), any())).thenReturn(Optional.of(secondCanceled));

        var response = service.cancelOpenOrders(new CancelOpenTriggerOrdersRequest(1001L, "btc-usdt", 2));

        assertThat(response.requested()).isEqualTo(2);
        assertThat(response.completed()).isEqualTo(2);
        assertThat(response.failed()).isZero();
        assertThat(response.results()).extracting("order.triggerOrderId").containsExactly(501L, 502L);
        verify(repository).pendingCancelableOrders(1001L, "BTC-USDT", 2);
        verify(repository).cancel(eq(1001L), eq(501L), any());
        verify(repository).cancel(eq(1001L), eq(502L), any());
    }

    @Test
    void cancelRejectsTriggerOrderOutsideCurrentProductLineBeforeMutating() {
        TriggerOrderRepository repository = mock(TriggerOrderRepository.class);
        TriggerProperties properties = new TriggerProperties();
        properties.getKafka().setProductTopicsEnabled(true);
        properties.getKafka().setProductLine(ProductLine.LINEAR_DELIVERY);
        TriggerOrderService service = new TriggerOrderService(repository, mock(OrderRpcApi.class), properties);
        TriggerOrderRecord row = record(501L, TriggerOrderStatus.PENDING);
        when(repository.findById(501L)).thenReturn(Optional.of(row));
        when(repository.triggerOrderMatchesContractType(501L, "LINEAR_DELIVERY")).thenReturn(false);

        assertThatThrownBy(() -> service.cancel(new CancelTriggerOrderRequest(1001L, 501L)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("trigger order not found: 501");

        verify(repository, never()).cancel(eq(1001L), eq(501L), any());
    }

    @Test
    void cancelRemovesRedisCandidateAfterAuthoritativeStateChange() {
        TriggerOrderRepository repository = mock(TriggerOrderRepository.class);
        TriggerOrderIndex index = mock(TriggerOrderIndex.class);
        TriggerOrderService service = new TriggerOrderService(repository, mock(OrderRpcApi.class),
                new TriggerProperties(), index);
        TriggerOrderRecord pending = record(502L, TriggerOrderStatus.PENDING);
        TriggerOrderRecord canceled = record(502L, TriggerOrderStatus.CANCELED);
        when(repository.findById(502L)).thenReturn(Optional.of(pending));
        when(repository.cancel(eq(1001L), eq(502L), any())).thenReturn(Optional.of(canceled));

        TriggerOrderResponse response = service.cancel(new CancelTriggerOrderRequest(1001L, 502L));

        assertThat(response.status()).isEqualTo(TriggerOrderStatus.CANCELED);
        verify(index).remove(canceled);
    }

    @Test
    void cancelLosingRaceToTriggerKeepsCandidateForStaleRetry() {
        TriggerOrderRepository repository = mock(TriggerOrderRepository.class);
        TriggerOrderIndex index = mock(TriggerOrderIndex.class);
        TriggerOrderService service = new TriggerOrderService(repository, mock(OrderRpcApi.class),
                new TriggerProperties(), index);
        TriggerOrderRecord pending = record(503L, TriggerOrderStatus.PENDING);
        TriggerOrderRecord triggering = record(503L, TriggerOrderStatus.TRIGGERING);
        when(repository.findById(503L)).thenReturn(Optional.of(pending));
        when(repository.cancel(eq(1001L), eq(503L), any())).thenReturn(Optional.of(triggering));

        TriggerOrderResponse response = service.cancel(new CancelTriggerOrderRequest(1001L, 503L));

        assertThat(response.status()).isEqualTo(TriggerOrderStatus.TRIGGERING);
        verify(index, never()).remove(any(TriggerOrderRecord.class));
    }

    @Test
    void closedPositionReconcilesAuthoritativelyCanceledTriggersOutOfRedis() {
        TriggerOrderRepository repository = mock(TriggerOrderRepository.class);
        TriggerOrderIndex index = mock(TriggerOrderIndex.class);
        TriggerProperties properties = new TriggerProperties();
        properties.getKafka().setProductLine(ProductLine.INVERSE_PERPETUAL);
        properties.getKafka().setProductTopicsEnabled(true);
        TriggerOrderService service = new TriggerOrderService(
                repository, mock(OrderRpcApi.class), properties, index);
        Instant closedAt = Instant.parse("2026-07-01T00:00:00Z");
        TriggerOrderRecord canceled = record(504L, TriggerOrderStatus.CANCELED);
        when(repository.positionClosedCancellations(ProductLine.INVERSE_PERPETUAL, 1001L, "BTC-USDT",
                MarginMode.CROSS, PositionSide.NET, closedAt)).thenReturn(List.of(canceled));

        service.onPositionClosed(new PositionUpdatedEvent(701L, 801L, 1001L, "btc-usdt", 1L,
                MarginMode.CROSS, PositionSide.NET, 0L, 0L, 0L, closedAt, "trace-close"));

        verify(index).synchronize(canceled);
    }

    @Test
    void onMarkPricePlacesReduceOnlyOrderAndMarksTriggered() {
        TriggerOrderRepository repository = mock(TriggerOrderRepository.class);
        OrderRpcApi orderRpcApi = mock(OrderRpcApi.class);
        TriggerOrderService service = new TriggerOrderService(repository, orderRpcApi, new TriggerProperties());
        TriggerOrderRecord claimed = record(501L, TriggerOrderStatus.TRIGGERING);
        when(repository.markPriceTicks("BTC-USDT", 42L)).thenReturn(OptionalLong.of(70_001L));
        when(repository.claimTriggered(eq("BTC-USDT"),
                eq(70_001L), eq(42L), any(), anyInt(), any()))
                .thenReturn(List.of(claimed));
        stubNoTrailingClaim(repository);
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
    void productLineModeClaimsTriggeredOrdersByContractType() {
        TriggerOrderRepository repository = mock(TriggerOrderRepository.class);
        OrderRpcApi orderRpcApi = mock(OrderRpcApi.class);
        TriggerProperties properties = new TriggerProperties();
        properties.getKafka().setProductTopicsEnabled(true);
        properties.getKafka().setProductLine(ProductLine.LINEAR_DELIVERY);
        TriggerOrderService service = new TriggerOrderService(repository, orderRpcApi, properties);
        TriggerOrderRecord claimed = record(501L, TriggerOrderStatus.TRIGGERING);
        when(repository.markPriceTicks("BTC-USDT", 42L, "LINEAR_DELIVERY")).thenReturn(OptionalLong.of(70_001L));
        when(repository.claimTriggered(eq("BTC-USDT"),
                eq(70_001L), eq(42L), any(), anyInt(), any(), eq("LINEAR_DELIVERY")))
                .thenReturn(List.of(claimed));
        when(repository.claimTrailingTriggered(eq("BTC-USDT"),
                eq(70_001L), eq(42L), any(), anyInt(), any(), eq("LINEAR_DELIVERY")))
                .thenReturn(List.of());
        when(orderRpcApi.place(any())).thenReturn(orderResponse(9001L, OrderStatus.ACCEPTED, null));

        service.onMarkPrice(new MarkTrigger("BTC-USDT", 42L, Instant.parse("2026-07-01T00:00:00Z")));

        verify(repository).markPriceTicks("BTC-USDT", 42L, "LINEAR_DELIVERY");
        verify(repository).claimTriggered(eq("BTC-USDT"),
                eq(70_001L), eq(42L), any(), anyInt(), any(), eq("LINEAR_DELIVERY"));
        verify(repository).claimTrailingTriggered(eq("BTC-USDT"),
                eq(70_001L), eq(42L), any(), anyInt(), any(), eq("LINEAR_DELIVERY"));
        verify(repository).markTriggered(eq(501L), eq(9001L), any());
    }

    @Test
    void onMarkPriceExecutesTrailingClaimThroughReduceOnlyOrder() {
        TriggerOrderRepository repository = mock(TriggerOrderRepository.class);
        OrderRpcApi orderRpcApi = mock(OrderRpcApi.class);
        TriggerOrderService service = new TriggerOrderService(repository, orderRpcApi, new TriggerProperties());
        TriggerOrderRecord trailing = record(531L, OrderSide.SELL, TriggerOrderType.TRAILING_STOP,
                TriggerCondition.LESS_OR_EQUAL, 0L, 4L, PositionSide.NET, TriggerOrderStatus.TRIGGERING);
        when(repository.markPriceTicks("BTC-USDT", 52L)).thenReturn(OptionalLong.of(60_100L));
        when(repository.claimTriggered(eq("BTC-USDT"),
                eq(60_100L), eq(52L), any(), anyInt(), any()))
                .thenReturn(List.of());
        when(repository.claimTrailingTriggered(eq("BTC-USDT"),
                eq(60_100L), eq(52L), any(), anyInt(), any()))
                .thenReturn(List.of(trailing));
        when(orderRpcApi.place(any())).thenReturn(orderResponse(9031L, OrderStatus.ACCEPTED, null));
        ArgumentCaptor<PlaceOrderRequest> orderCaptor = ArgumentCaptor.forClass(PlaceOrderRequest.class);

        service.onMarkPrice(new MarkTrigger("BTC-USDT", 52L, Instant.parse("2026-07-01T00:00:00Z")));

        verify(orderRpcApi).place(orderCaptor.capture());
        assertThat(orderCaptor.getValue().clientOrderId()).isEqualTo("trigger-531");
        assertThat(orderCaptor.getValue().reduceOnly()).isTrue();
        assertThat(orderCaptor.getValue().side()).isEqualTo(OrderSide.SELL);
        assertThat(orderCaptor.getValue().orderType()).isEqualTo(OrderType.MARKET);
        verify(repository).markTriggered(eq(531L), eq(9031L), any());
    }

    @Test
    void onMarkPriceWithoutPersistedMarkPriceDoesNotClaimOrPlaceOrder() {
        TriggerOrderRepository repository = mock(TriggerOrderRepository.class);
        OrderRpcApi orderRpcApi = mock(OrderRpcApi.class);
        TriggerOrderService service = new TriggerOrderService(repository, orderRpcApi, new TriggerProperties());
        when(repository.markPriceTicks("BTC-USDT", 98L)).thenReturn(OptionalLong.empty());
        when(repository.hasPendingOrders("BTC-USDT")).thenReturn(true);

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
        when(repository.claimTriggered(eq("BTC-USDT"),
                eq(71_001L), eq(43L), any(), anyInt(), any()))
                .thenReturn(List.of(claimed));
        stubNoTrailingClaim(repository);
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
        when(repository.claimTriggered(eq("BTC-USDT"),
                eq(72_001L), eq(44L), any(), anyInt(), any()))
                .thenReturn(List.of(firstLevel, secondLevel));
        stubNoTrailingClaim(repository);
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
        when(repository.claimTriggered(eq("BTC-USDT"),
                eq(59_999L), eq(45L), any(), anyInt(), any()))
                .thenReturn(List.of(claimed));
        stubNoTrailingClaim(repository);
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
        when(repository.claimTriggered(eq("BTC-USDT"),
                eq(50_000L), eq(42L), any(), anyInt(), any()))
                .thenReturn(List.of(claimed));
        stubNoTrailingClaim(repository);
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
    void adminOrdersDelegatesProductLineAsContractType() {
        TriggerOrderRepository repository = mock(TriggerOrderRepository.class);
        TriggerOrderService service = new TriggerOrderService(repository, mock(OrderRpcApi.class),
                new TriggerProperties());
        TriggerOrderRecord row = record(501L, TriggerOrderStatus.PENDING);
        when(repository.adminOrderPage(1001L, "BTC-USDT", TriggerOrderStatus.PENDING, 501L, 50,
                "VANILLA_OPTION", "cursor-1", "createdAt.asc"))
                .thenReturn(new AdminCursorPage.CursorPage<>(List.of(row),
                        "cursor-2", true, "createdAt.asc", 50));

        var response = service.adminOrders(1001L, "btc-usdt", "pending", 501L, 50,
                "cursor-1", "createdAt.asc", ProductLine.OPTION);

        assertThat(response.orders()).extracting("triggerOrderId").containsExactly(501L);
        verify(repository).adminOrderPage(1001L, "BTC-USDT", TriggerOrderStatus.PENDING, 501L, 50,
                "VANILLA_OPTION", "cursor-1", "createdAt.asc");
    }

    @Test
    void adminTimelineRejectsMismatchedProductLine() {
        TriggerOrderRepository repository = mock(TriggerOrderRepository.class);
        TriggerOrderService service = new TriggerOrderService(repository, mock(OrderRpcApi.class),
                new TriggerProperties());
        TriggerOrderRecord row = record(501L, TriggerOrderStatus.PENDING);
        when(repository.findById(501L)).thenReturn(Optional.of(row));
        when(repository.triggerOrderMatchesContractType(501L, "LINEAR_DELIVERY")).thenReturn(false);

        assertThatThrownBy(() -> service.adminTimeline(501L, ProductLine.LINEAR_DELIVERY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("trigger order not found: 501");
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
                OrderSide.SELL, TriggerOrderType.TAKE_PROFIT,
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
                triggerType, triggerCondition, triggerPriceTicks,
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
        when(repository.lockedPosition(any(), anyLong(), anyString(), any(), any()))
                .thenReturn(Optional.of(new TriggerPosition(signedQuantitySteps, 1L)));
        when(repository.openReduceOnlySteps(any(), anyLong(), anyString(), any(), any(), anyLong(), any()))
                .thenReturn(openReduceOnlySteps);
        when(repository.pendingTriggerCloseSteps(any(), anyLong(), anyString(), any(), any(), any()))
                .thenReturn(pendingTriggerCloseSteps);
        when(repository.pendingTriggerOcoGroupMaxSteps(any(), anyLong(), anyString(), any(), any(), any(), any()))
                .thenReturn(sameOcoGroupMaxSteps);
    }

    private void stubNoTrailingClaim(TriggerOrderRepository repository) {
        when(repository.claimTrailingTriggered(anyString(), anyLong(), anyLong(), any(), anyInt(), any()))
                .thenReturn(List.of());
    }
}
