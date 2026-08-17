package com.surprising.trading.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.AdminBatchCancelOrdersRequest;
import com.surprising.trading.api.model.AdminCancelOrdersResponse;
import com.surprising.trading.api.model.OrderBatchResponse;
import com.surprising.trading.api.model.OrderQueryResponse;
import com.surprising.trading.api.model.CancelOrderRequest;
import com.surprising.trading.api.model.ClosePositionRequest;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.OrderResponse;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.OrderStatus;
import com.surprising.trading.api.model.OrderType;
import com.surprising.trading.api.model.PlaceOrderRequest;
import com.surprising.trading.api.model.PositionSide;
import com.surprising.trading.api.model.TimeInForce;
import com.surprising.trading.order.config.TradingOrderProperties;
import com.surprising.trading.order.model.OrderFeeSnapshot;
import com.surprising.trading.order.model.OrderRecord;
import com.surprising.trading.order.repository.AeronOrderProjectionRepository;
import com.surprising.trading.order.repository.ProjectionReadResult;
import com.surprising.trading.order.model.ReduceOnlyPosition;
import com.surprising.trading.order.model.ValidationResult;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
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
    private OrderPlacementStateService placementStateService;
    @Mock
    private OrderFeeSnapshotLookup feeSnapshotLookup;
    @Mock
    private AeronOrderCommandService aeronOrders;
    @Mock
    private AeronOrderProjectionRepository projection;

    @Test
    void placeFailsClosedWithoutAeronGateway() {
        OrderService service = service(ProductLine.LINEAR_PERPETUAL);
        PlaceOrderRequest request = request("client-1");
        assertThatThrownBy(() -> service.place(request)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Aeron order gateway");
        verifyNoInteractions(aeronOrders);
    }

    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void placeRejectsMissingClientOrderIdAtServiceBoundary(ProductLine productLine) {
        OrderService service = service(productLine, aeronOrders);

        assertThatThrownBy(() -> service.place(request(null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("clientOrderId is required");
        verifyNoInteractions(aeronOrders);
    }

    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void closePositionRejectsMissingClientOrderIdAtServiceBoundary(ProductLine productLine) {
        OrderService service = service(productLine, aeronOrders);

        assertThatThrownBy(() -> service.closePosition(new ClosePositionRequest(
                1001L, null, "BTC-USDT", MarginMode.CROSS, PositionSide.NET)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("clientOrderId is required");
        verifyNoInteractions(aeronOrders, placementStateService);
    }

    @Test
    void closePositionUsesExplicitClientOrderIdForTheStableCloseOrder() {
        OrderService service = service(ProductLine.LINEAR_PERPETUAL, aeronOrders);
        when(placementStateService.position(ProductLine.LINEAR_PERPETUAL, 1001L, "BTC-USDT",
                MarginMode.CROSS, PositionSide.NET))
                .thenReturn(Optional.of(new ReduceOnlyPosition(5L, 7L)));
        when(aeronOrders.place(any(), any(), any())).thenReturn(response(91, "close-1", OrderStatus.ACCEPTED));

        service.closePosition(new ClosePositionRequest(1001L, "close-1", "BTC-USDT",
                MarginMode.CROSS, PositionSide.NET));

        ArgumentCaptor<PlaceOrderRequest> request = ArgumentCaptor.forClass(PlaceOrderRequest.class);
        verify(aeronOrders).place(request.capture(), any(), any());
        assertThat(request.getValue().clientOrderId()).isEqualTo("close-1");
    }

    @Test
    void missingFeeSnapshotFailsClosedBeforeAppendingOrderFact() {
        OrderService service = service(ProductLine.LINEAR_PERPETUAL, aeronOrders);
        when(feeSnapshotLookup.lookup(any(), anyLong(), anyString(), anyLong(), any()))
                .thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.place(request("no-fee"))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void cancelAndReadCommandsDelegateToLocalUserPartition() {
        OrderService service = service(ProductLine.LINEAR_PERPETUAL);
        assertThatThrownBy(() -> service.cancel(new CancelOrderRequest(1001L, 902L)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Aeron order gateway");
    }

    @Test
    void adminCancelUsesLocalPartitionAndProductLine() {
        OrderService service = service(ProductLine.LINEAR_PERPETUAL);
        when(projection.openOrders(ProductLine.LINEAR_PERPETUAL, 1001L, "BTC-USDT", null, 10, null))
                .thenReturn(ProjectionReadResult.ok(java.util.List.of(), null, false, 12L, 0L));
        assertThatThrownBy(() -> service.adminCancelOrders(new AdminBatchCancelOrdersRequest(
                1001L, "BTC-USDT", 10, "risk"))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void adminCancelSelectsOpenOrdersFromProjection() {
        OrderService service = service(ProductLine.LINEAR_PERPETUAL, aeronOrders);
        OrderResponse open = response(91, "client-91", OrderStatus.ACCEPTED);
        OrderResponse canceled = response(91, "client-91", OrderStatus.CANCELED);
        when(projection.openOrders(ProductLine.LINEAR_PERPETUAL, null, "BTC-USDT", null, 10, null))
                .thenReturn(ProjectionReadResult.ok(java.util.List.of(open), null, false, 12L, 0L));
        when(aeronOrders.cancel(1001L, 91L)).thenReturn(canceled);

        AdminCancelOrdersResponse result = service.adminCancelOrders(
                new AdminBatchCancelOrdersRequest(null, "BTC-USDT", 10, "risk"));

        assertThat(result.canceled()).isEqualTo(1);
        verify(projection).openOrders(ProductLine.LINEAR_PERPETUAL, null, "BTC-USDT", null, 10, null);
        verify(aeronOrders).cancel(1001L, 91L);
    }

    @Test
    void adminSingleCancelUsesTheProductLineScopedProjectionOrder() {
        OrderService service = service(ProductLine.LINEAR_PERPETUAL, aeronOrders);
        OrderResponse open = response(91, "client-91", OrderStatus.ACCEPTED);
        OrderResponse canceled = response(91, "client-91", OrderStatus.CANCELED);
        when(projection.byOrder(ProductLine.LINEAR_PERPETUAL, (Long) null, 91L, null))
                .thenReturn(ProjectionReadResult.ok(java.util.List.of(open), null, false, 12L, 0L));
        when(aeronOrders.cancel(1001L, 91L)).thenReturn(canceled);

        assertThat(service.adminCancelOrder(91L, "risk", ProductLine.LINEAR_PERPETUAL).orderId()).isEqualTo(91L);

        verify(projection).byOrder(ProductLine.LINEAR_PERPETUAL, (Long) null, 91L, null);
        verify(aeronOrders).cancel(1001L, 91L);
    }

    @Test
    void optionProductLineUsesTheLocalAccountFactStream() {
        OrderService service = service(ProductLine.OPTION);
        PlaceOrderRequest optionRequest = new PlaceOrderRequest(1001L, "option-1", "BTC-USDT",
                OrderSide.BUY, OrderType.LIMIT, TimeInForce.GTC, 60_000L, 10L,
                MarginMode.CROSS, PositionSide.NET, false, false);

        assertThatThrownBy(() -> service.place(optionRequest)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void readsProjectionAtRequiredExportSequenceWithoutAeron() {
        OrderService service = service(ProductLine.LINEAR_PERPETUAL, null);
        OrderResponse open = response(91, "client-91", OrderStatus.ACCEPTED);
        when(projection.byOrder(ProductLine.LINEAR_PERPETUAL, 1001L, 91L, 12L))
                .thenReturn(ProjectionReadResult.ok(java.util.List.of(open), null, false, 12L, 12L));

        OrderResponse result = service.get(1001L, 91L, 12L);

        assertThat(result).isEqualTo(open);
        verify(projection).byOrder(ProductLine.LINEAR_PERPETUAL, 1001L, 91L, 12L);
        verifyNoInteractions(aeronOrders);
    }

    @Test
    void allOrdinaryOrderReadsUseProductLineAndUserScopedProjection() {
        OrderService service = service(ProductLine.LINEAR_PERPETUAL, aeronOrders);
        OrderResponse open = response(91, "client-91", OrderStatus.ACCEPTED);
        when(projection.byClientOrderId(ProductLine.LINEAR_PERPETUAL, 1001L, "client-91", null))
                .thenReturn(ProjectionReadResult.ok(java.util.List.of(open), null, false, 12L, 0L));
        when(projection.openOrders(ProductLine.LINEAR_PERPETUAL, 1001L, "BTC-USDT", null, 10, null))
                .thenReturn(ProjectionReadResult.ok(java.util.List.of(open), null, false, 12L, 0L));
        when(projection.historyOrders(ProductLine.LINEAR_PERPETUAL, 1001L, "BTC-USDT", 10,
                null, null, null, null, null))
                .thenReturn(ProjectionReadResult.ok(java.util.List.of(open), null, false, 12L, 0L));

        assertThat(service.getByClientOrderId(1001L, "client-91")).isEqualTo(open);
        assertThat(service.openOrders(1001L, "BTC-USDT", 10).orders()).containsExactly(open);
        assertThat(service.historyOrders(1001L, "BTC-USDT", 10, null, null, null).orders())
                .containsExactly(open);

        verify(projection).byClientOrderId(ProductLine.LINEAR_PERPETUAL, 1001L, "client-91", null);
        verify(projection).openOrders(ProductLine.LINEAR_PERPETUAL, 1001L, "BTC-USDT", null, 10, null);
        verify(projection).historyOrders(ProductLine.LINEAR_PERPETUAL, 1001L, "BTC-USDT", 10,
                null, null, null, null, null);
        verifyNoInteractions(aeronOrders);
    }

    @Test
    void cancelOpenOrdersSelectsFromProjectionAndSendsOnlyCancelCommandToAeron() {
        OrderService service = service(ProductLine.LINEAR_PERPETUAL, aeronOrders);
        OrderResponse open = response(91, "client-91", OrderStatus.ACCEPTED);
        OrderResponse canceled = response(91, "client-91", OrderStatus.CANCELED);
        when(projection.openOrders(ProductLine.LINEAR_PERPETUAL, 1001L, "BTC-USDT", null, 1000, null))
                .thenReturn(ProjectionReadResult.ok(java.util.List.of(open), null, false, 12L, 0L));
        when(aeronOrders.cancel(1001L, 91L)).thenReturn(canceled);

        OrderBatchResponse result = service.cancelOpenOrders(
                new com.surprising.trading.api.model.CancelOpenOrdersRequest(1001L, "BTC-USDT", 1000));

        assertThat(result.completed()).isEqualTo(1);
        verify(projection).openOrders(ProductLine.LINEAR_PERPETUAL, 1001L, "BTC-USDT", null, 1000, null);
        verify(aeronOrders).cancel(1001L, 91L);
    }

    @Test
    void lifecycleCancellationUsesAeronAuthorityInsteadOfStaleProjection() {
        OrderService service = service(ProductLine.LINEAR_PERPETUAL, aeronOrders);
        OrderResponse open = response(91, "client-91", OrderStatus.ACCEPTED);
        OrderResponse canceled = response(91, "client-91", OrderStatus.CANCELED);
        when(aeronOrders.lifecycleOpenOrders("BTC-USDT", 1000)).thenReturn(java.util.List.of(open));
        when(aeronOrders.cancel(1001L, 91L)).thenReturn(canceled);

        assertThat(service.requestLifecycleCancellation("BTC-USDT", 1000)).isEqualTo(1);

        verify(aeronOrders).lifecycleOpenOrders("BTC-USDT", 1000);
        verify(aeronOrders).cancel(1001L, 91L);
        verifyNoInteractions(projection);
    }

    @Test
    void lifecycleActiveCheckUsesAeronAuthorityInsteadOfStaleProjection() {
        OrderService service = service(ProductLine.LINEAR_PERPETUAL, aeronOrders);
        when(aeronOrders.lifecycleOpenOrders("BTC-USDT", 1))
                .thenReturn(java.util.List.of(response(91, "client-91", OrderStatus.ACCEPTED)));

        assertThat(service.hasLifecycleActiveOrders("BTC-USDT")).isTrue();

        verify(aeronOrders).lifecycleOpenOrders("BTC-USDT", 1);
        verifyNoInteractions(projection);
    }

    @Test
    void adminSingleCancelRejectsProductLineMismatchBeforeProjectionOrAeronOffer() {
        OrderService service = service(ProductLine.LINEAR_PERPETUAL, aeronOrders);

        assertThatThrownBy(() -> service.adminCancelOrder(91L, "risk", ProductLine.OPTION))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("product line");

        verifyNoInteractions(projection, aeronOrders);
    }

    @Test
    void oversizedFirstProjectionRowPreservesContinuationCursorAtServiceBoundary() {
        OrderService service = service(ProductLine.LINEAR_PERPETUAL, null);
        String continuation = "eyJvcmRlciI6MTIzfQ";
        when(projection.openOrders(ProductLine.LINEAR_PERPETUAL, 1001L, "BTC-USDT", null, 10, null))
                .thenReturn(ProjectionReadResult.responseTooLarge(12L, 0L, continuation));

        assertThatThrownBy(() -> service.openOrders(1001L, "BTC-USDT", 10))
                .isInstanceOf(ProjectionReadResult.ResponseTooLargeException.class)
                .satisfies(throwable -> assertThat(
                        ((ProjectionReadResult.ResponseTooLargeException) throwable).nextCursor())
                        .isEqualTo(continuation));
        verify(projection).openOrders(ProductLine.LINEAR_PERPETUAL, 1001L, "BTC-USDT", null, 10, null);
        verifyNoInteractions(aeronOrders);
    }

    private OrderService service(ProductLine productLine) {
        TradingOrderProperties properties = new TradingOrderProperties();
        properties.getKafka().setProductLine(productLine);
        return service(productLine, null);
    }

    private OrderService service(ProductLine productLine, AeronOrderCommandService aeron) {
        TradingOrderProperties properties = new TradingOrderProperties();
        properties.getKafka().setProductLine(productLine);
        when(orderValidator.validate(any())).thenReturn(ValidationResult.ok(7L));
        when(feeSnapshotLookup.lookup(any(), anyLong(), anyString(), anyLong(), any()))
                .thenReturn(Optional.of(new OrderFeeSnapshot(ProductLine.LINEAR_PERPETUAL, 100L, 200L, "JVM")));
        return new OrderService(properties, orderValidator, placementStateService, feeSnapshotLookup, aeron, projection);
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
