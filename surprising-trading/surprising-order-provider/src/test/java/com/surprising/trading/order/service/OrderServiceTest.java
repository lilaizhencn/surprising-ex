package com.surprising.trading.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.AdminBatchCancelOrdersRequest;
import com.surprising.trading.api.model.CancelOrderRequest;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.OrderResponse;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.OrderStatus;
import com.surprising.trading.api.model.OrderType;
import com.surprising.trading.api.model.PlaceOrderRequest;
import com.surprising.trading.api.model.PositionMode;
import com.surprising.trading.api.model.PositionSide;
import com.surprising.trading.api.model.TimeInForce;
import com.surprising.trading.order.config.TradingOrderProperties;
import com.surprising.trading.order.model.OrderFeeSnapshot;
import com.surprising.trading.order.model.OrderRecord;
import com.surprising.trading.order.model.ValidationResult;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/** 订单入口只编排 JVM 用户事实流，不允许通过数据库仓储回退。 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderServiceTest {

    @Mock
    private OrderValidator orderValidator;
    @Mock
    private ReduceOnlyValidator reduceOnlyValidator;
    @Mock
    private OrderPlacementStateService placementStateService;
    @Mock
    private OrderMarginCalculator orderMarginCalculator;
    @Mock
    private SpotReservationCalculator spotReservationCalculator;
    @Mock
    private OrderFeeSnapshotLookup feeSnapshotLookup;
    @Mock
    private OrderUserStateService userState;
    @Mock
    private OrderUserCommandGateway commandGateway;

    @Test
    void placeUsesUserWalAndDoesNotNeedDatabaseState() {
        OrderService service = service(ProductLine.LINEAR_PERPETUAL);
        PlaceOrderRequest request = request("client-1");
        OrderResponse expected = response(901L, request.clientOrderId(), OrderStatus.ACCEPTED);
        when(userState.nextOrderId()).thenReturn(901L);
        when(commandGateway.place(any(OrderRecord.class))).thenReturn(expected);

        assertThat(service.place(request)).isEqualTo(expected);

        verify(commandGateway).place(any(OrderRecord.class));
        verify(orderMarginCalculator, never()).requirement(anyString(), anyLong(), anyLong(), any(), any(), any(),
                any(), anyLong(), anyLong(), anyLong(), anyLong());
    }

    @Test
    void missingFeeSnapshotFailsClosedBeforeAppendingOrderFact() {
        OrderService service = service(ProductLine.LINEAR_PERPETUAL);
        when(feeSnapshotLookup.lookup(any(), anyLong(), anyString(), anyLong(), any()))
                .thenReturn(Optional.empty());
        when(commandGateway.place(any(OrderRecord.class)))
                .thenReturn(response(904L, "no-fee", OrderStatus.REJECTED));

        assertThat(service.place(request("no-fee")).status()).isEqualTo(OrderStatus.REJECTED);
        verify(commandGateway).place(any(OrderRecord.class));
    }

    @Test
    void cancelAndReadCommandsDelegateToLocalUserPartition() {
        OrderService service = service(ProductLine.LINEAR_PERPETUAL);
        OrderResponse canceled = response(902L, "cancel-1", OrderStatus.CANCEL_REQUESTED);
        when(commandGateway.cancel(ProductLine.LINEAR_PERPETUAL, 1001L, 902L, null)).thenReturn(canceled);
        when(userState.get(902L)).thenReturn(canceled);

        assertThat(service.cancel(new CancelOrderRequest(1001L, 902L))).isEqualTo(canceled);
        assertThat(service.get(902L)).isEqualTo(canceled);

        verify(commandGateway).cancel(ProductLine.LINEAR_PERPETUAL, 1001L, 902L, null);
        verify(userState).get(902L);
    }

    @Test
    void adminCancelUsesLocalPartitionAndProductLine() {
        OrderService service = service(ProductLine.LINEAR_PERPETUAL);
        OrderResponse canceled = response(903L, "admin-1", OrderStatus.CANCEL_REQUESTED);
        when(commandGateway.cancelOpen(ProductLine.LINEAR_PERPETUAL, 1001L, "BTC-USDT", 10,
                "admin cancel: risk"))
                .thenReturn(new com.surprising.trading.api.model.OrderBatchResponse(1, 1, 0,
                        java.util.List.of(new com.surprising.trading.api.model.OrderBatchItemResponse(
                                0, true, "cancel requested", canceled))));

        var result = service.adminCancelOrders(new AdminBatchCancelOrdersRequest(
                1001L, "BTC-USDT", 10, "risk"));

        assertThat(result.requested()).isEqualTo(1);
        verify(commandGateway).cancelOpen(ProductLine.LINEAR_PERPETUAL, 1001L, "BTC-USDT", 10,
                "admin cancel: risk");
    }

    @Test
    void optionProductLineFailsClosedUntilItsAccountReducerIsReady() {
        OrderService service = service(ProductLine.OPTION);

        assertThatThrownBy(() -> service.place(request("option-1")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("尚未接入本地账户事实流");
        verify(userState, never()).place(any(OrderRecord.class));
    }

    private OrderService service(ProductLine productLine) {
        TradingOrderProperties properties = new TradingOrderProperties();
        properties.getKafka().setProductLine(productLine);
        properties.getKafka().setProductTopicsEnabled(true);
        when(placementStateService.localPositionMode(productLine, 1001L)).thenReturn(PositionMode.ONE_WAY);
        when(placementStateService.cachedPositionMarginModeConflict(productLine, 1001L, "BTC-USDT",
                MarginMode.CROSS)).thenReturn(false);
        when(userState.hasActiveMarginModeConflict(1001L, "BTC-USDT", MarginMode.CROSS)).thenReturn(false);
        when(orderValidator.validate(any())).thenReturn(ValidationResult.ok(7L));
        when(reduceOnlyValidator.validate(any())).thenReturn(ValidationResult.ok(7L));
        when(feeSnapshotLookup.lookup(any(), anyLong(), anyString(), anyLong(), any()))
                .thenReturn(Optional.of(new OrderFeeSnapshot(ProductLine.LINEAR_PERPETUAL, 100L, 200L, "JVM")));
        return new OrderService(properties, orderValidator, reduceOnlyValidator, placementStateService,
                orderMarginCalculator, spotReservationCalculator, feeSnapshotLookup, userState, commandGateway);
    }

    private PlaceOrderRequest request(String clientOrderId) {
        return new PlaceOrderRequest(1001L, clientOrderId, "BTC-USDT", OrderSide.BUY, OrderType.LIMIT,
                TimeInForce.GTC, 60_000L, 10L, MarginMode.CROSS, PositionSide.NET, true, false);
    }

    private OrderResponse response(long orderId, String clientOrderId, OrderStatus status) {
        Instant now = Instant.parse("2026-08-02T00:00:00Z");
        return new OrderResponse(orderId, 1001L, clientOrderId, "BTC-USDT", 7L, OrderSide.BUY,
                OrderType.LIMIT, TimeInForce.GTC, 60_000L, 10L, 0L, 10L, MarginMode.CROSS,
                PositionSide.NET, 100L, 200L, true, false, status, null, now, now);
    }
}
