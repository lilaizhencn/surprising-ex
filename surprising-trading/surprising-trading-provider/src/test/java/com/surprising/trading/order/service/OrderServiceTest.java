package com.surprising.trading.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import com.surprising.aeron.client.CoreCommandOutcome;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.AdminBatchCancelOrdersRequest;
import com.surprising.trading.api.model.AdminCancelOrdersResponse;
import com.surprising.trading.api.model.BatchCancelOrdersRequest;
import com.surprising.trading.api.model.OrderBatchItemResponse;
import com.surprising.trading.api.model.OrderBatchResponse;
import com.surprising.trading.api.model.OrderCommandReceipt;
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
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
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
        assertThatThrownBy(() -> service.adminCancelOrders(new AdminBatchCancelOrdersRequest(
                1001L, "BTC-USDT", 10, "risk"))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void adminCancelSelectsOpenOrdersFromCore() {
        OrderService service = service(ProductLine.LINEAR_PERPETUAL, aeronOrders);
        List<OrderResponse> open = openOrders(123);
        when(aeronOrders.openOrders(0, "BTC-USDT", 0, 123)).thenReturn(open);
        stubCancelBatches();

        AdminCancelOrdersResponse result = service.adminCancelOrders(
                new AdminBatchCancelOrdersRequest(null, "BTC-USDT", 123, "risk"));

        assertThat(result.requested()).isEqualTo(123);
        assertThat(result.canceled()).isEqualTo(123);
        assertThat(result.skipped()).isZero();
        verify(aeronOrders).openOrders(0, "BTC-USDT", 0, 123);
        verify(aeronOrders, times(3)).cancelBatchCommand(anyString(), anyList());
        verify(aeronOrders, never()).cancel(anyLong(), anyLong());
    }

    @Test
    void adminSingleCancelUsesTheProductLineScopedCoreOrder() {
        OrderService service = service(ProductLine.LINEAR_PERPETUAL, aeronOrders);
        OrderResponse open = response(91, "client-91", OrderStatus.ACCEPTED);
        OrderResponse canceled = response(91, "client-91", OrderStatus.CANCELED);
        when(aeronOrders.orderState(0, 91L)).thenReturn(open);
        when(aeronOrders.cancel(1001L, 91L)).thenReturn(canceled);

        assertThat(service.adminCancelOrder(91L, "risk", ProductLine.LINEAR_PERPETUAL).orderId()).isEqualTo(91L);

        verify(aeronOrders).orderState(0, 91L);
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
    void currentOrderReadUsesCoreEvenWhenExportSequenceIsProvided() {
        OrderService service = service(ProductLine.LINEAR_PERPETUAL, aeronOrders);
        OrderResponse open = response(91, "client-91", OrderStatus.ACCEPTED);
        when(aeronOrders.orderState(1001L, 91L)).thenReturn(open);

        OrderResponse result = service.get(1001L, 91L, 12L);

        assertThat(result).isEqualTo(open);
        verify(aeronOrders).orderState(1001L, 91L);
        verifyNoInteractions(projection);
    }

    @Test
    void currentReadsUseCoreWhileHistoryUsesProjection() {
        OrderService service = service(ProductLine.LINEAR_PERPETUAL, aeronOrders);
        OrderResponse open = response(91, "client-91", OrderStatus.ACCEPTED);
        when(aeronOrders.orderStateByClientOrderId(1001L, "client-91")).thenReturn(open);
        when(aeronOrders.openOrders(1001L, "BTC-USDT", 0, 11)).thenReturn(java.util.List.of(open));
        when(projection.historyOrders(ProductLine.LINEAR_PERPETUAL, 1001L, "BTC-USDT", 10,
                null, null, null, null, null))
                .thenReturn(ProjectionReadResult.ok(java.util.List.of(open), null, false, 12L, 0L));

        assertThat(service.getByClientOrderId(1001L, "client-91")).isEqualTo(open);
        assertThat(service.openOrders(1001L, "BTC-USDT", 10).orders()).containsExactly(open);
        assertThat(service.historyOrders(1001L, "BTC-USDT", 10, null, null, null).orders())
                .containsExactly(open);

        verify(aeronOrders).orderStateByClientOrderId(1001L, "client-91");
        verify(aeronOrders).openOrders(1001L, "BTC-USDT", 0, 11);
        verify(projection).historyOrders(ProductLine.LINEAR_PERPETUAL, 1001L, "BTC-USDT", 10,
                null, null, null, null, null);
    }

    @Test
    void cancelOpenOrdersSelectsFromCoreAndSendsOnlyCancelCommandToAeron() {
        OrderService service = service(ProductLine.LINEAR_PERPETUAL, aeronOrders);
        List<OrderResponse> open = openOrders(123);
        when(aeronOrders.openOrders(1001L, "BTC-USDT", 0, 1000)).thenReturn(open);
        stubCancelBatches();

        OrderBatchResponse first = service.cancelOpenOrders(
                new com.surprising.trading.api.model.CancelOpenOrdersRequest(1001L, "BTC-USDT", 1000));
        OrderBatchResponse second = service.cancelOpenOrders(
                new com.surprising.trading.api.model.CancelOpenOrdersRequest(1001L, "BTC-USDT", 1000));

        assertThat(first.completed()).isEqualTo(123);
        assertThat(first.results()).extracting(OrderBatchItemResponse::index)
                .containsExactlyElementsOf(java.util.stream.IntStream.range(0, 123).boxed().toList());
        assertThat(first.results().getFirst().order().orderId()).isEqualTo(10_000L);
        assertThat(first.results().getLast().order().orderId()).isEqualTo(10_122L);
        assertThat(second).isEqualTo(first);
        verify(aeronOrders, times(2)).openOrders(1001L, "BTC-USDT", 0, 1000);
        ArgumentCaptor<String> batchKeys = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<List> batchOrders = ArgumentCaptor.forClass(List.class);
        verify(aeronOrders, times(6)).cancelBatchCommand(batchKeys.capture(), batchOrders.capture());
        assertThat(batchKeys.getAllValues().subList(0, 3))
                .containsExactlyElementsOf(batchKeys.getAllValues().subList(3, 6));
        assertThat(batchOrders.getAllValues().subList(0, 3).stream()
                .map(List::size).toList()).containsExactly(50, 50, 23);
        verify(aeronOrders, never()).cancel(anyLong(), anyLong());
    }

    @Test
    void lifecycleCancellationUsesAeronAuthorityInsteadOfStaleProjection() {
        OrderService service = service(ProductLine.LINEAR_PERPETUAL, aeronOrders);
        when(aeronOrders.lifecycleOpenOrders("BTC-USDT", 123)).thenReturn(openOrders(123));
        stubCancelBatches();

        assertThat(service.requestLifecycleCancellation("BTC-USDT", 123)).isEqualTo(123);

        verify(aeronOrders).lifecycleOpenOrders("BTC-USDT", 123);
        verify(aeronOrders, times(3)).cancelBatchCommand(anyString(), anyList());
        verify(aeronOrders, never()).cancel(anyLong(), anyLong());
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
    void currentOpenOrdersPaginatesWithCoreCursor() {
        OrderService service = service(ProductLine.LINEAR_PERPETUAL, aeronOrders);
        when(aeronOrders.openOrders(1001L, "BTC-USDT", 0, 3)).thenReturn(openOrders(3));

        OrderQueryResponse first = service.openOrders(1001L, "BTC-USDT", 2);

        assertThat(first.orders()).hasSize(2);
        assertThat(first.hasMore()).isTrue();
        assertThat(first.nextCursor()).isNotBlank();
        verify(aeronOrders).openOrders(1001L, "BTC-USDT", 0, 3);
        verifyNoInteractions(projection);
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

    private List<OrderResponse> openOrders(int count) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(index -> response(10_000L + index, "client-" + index, OrderStatus.ACCEPTED))
                .toList();
    }

    private void stubCancelBatches() {
        when(aeronOrders.cancelBatchCommand(anyString(), anyList())).thenAnswer(invocation -> {
            String batchKey = invocation.getArgument(0);
            List<CancelOrderRequest> requests = invocation.getArgument(1);
            return new AeronOrderCommandService.CommandExecution(
                    UUID.nameUUIDFromBytes(batchKey.getBytes(StandardCharsets.UTF_8)),
                    requests.stream().map(CancelOrderRequest::orderId).toList(),
                    new CoreCommandOutcome.Terminal(new CoreResponse(
                            ResponseStatus.APPLIED, ResponseStatus.APPLIED, CoreResultCode.NONE,
                            1L, 1L, 17L, new byte[0])),
                    AeronOrderCommandService.CommandKind.CANCEL_BATCH);
        });
        when(aeronOrders.receipt(any(AeronOrderCommandService.CommandExecution.class)))
                .thenAnswer(invocation -> {
                    AeronOrderCommandService.CommandExecution execution = invocation.getArgument(0);
                    List<OrderBatchItemResponse> items = java.util.stream.IntStream
                            .range(0, execution.prospectiveOrderIds().size())
                            .mapToObj(index -> new OrderBatchItemResponse(index, true, "completed",
                                    response(execution.prospectiveOrderIds().get(index),
                                            "client-" + execution.prospectiveOrderIds().get(index),
                                            OrderStatus.CANCELED)))
                            .toList();
                    OrderBatchResponse aggregate = new OrderBatchResponse(items.size(), items.size(), 0, items);
                    return new OrderCommandReceipt(execution.commandId(), "TERMINAL", "NONE", "completed",
                            OrderCommandReceipt.commandResultUrl(execution.commandId()),
                            execution.prospectiveOrderIds(), 1L, aggregate, null);
                });
    }

    private OrderBatchResponse canceledBatch(BatchCancelOrdersRequest request) {
        List<OrderBatchItemResponse> results = java.util.stream.IntStream.range(0, request.orders().size())
                .mapToObj(index -> {
                    CancelOrderRequest cancel = request.orders().get(index);
                    return new OrderBatchItemResponse(index, true, "completed",
                            response(cancel.orderId(), "client-" + cancel.orderId(), OrderStatus.CANCELED));
                })
                .toList();
        return new OrderBatchResponse(results.size(), results.size(), 0, results);
    }
}
