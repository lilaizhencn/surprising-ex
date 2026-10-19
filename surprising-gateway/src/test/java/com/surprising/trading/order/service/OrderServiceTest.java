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
import com.surprising.trading.order.repository.AeronOrderProjectionRepository;
import com.surprising.trading.order.repository.ProjectionReadResult;
import com.surprising.trading.order.model.ValidationResult;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.List;
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

/** 普通订单当前状态读取 realtime，终态与历史读取已提交投影；写入交由 Core 裁决。 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderServiceTest {
    @ParameterizedTest @EnumSource(ProductLine.class)
    void cancellationLookupUsesTheOriginalStableIdentityAndOnlyTheProjection(ProductLine line) {
        var service = service(line, aeronOrders);
        var id = StableOrderIdentity.commandId(line, 1001, "cancel:91");
        var expected = new com.surprising.trading.api.model.OrderCommandReceipt(id, "TERMINAL", "NONE",
                "committed cancellation", null, List.of(91L), null, null);
        when(projection.cancellation(line, 1001, 91, id)).thenReturn(expected);
        assertThat(service.cancellation(1001, 91)).isSameAs(expected);
        assertThatThrownBy(() -> service.cancellation(1001, 0)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(aeronOrders);
    }

    @ParameterizedTest @EnumSource(ProductLine.class)
    void publicCommandReceiptsUseTheCommittedProjectionForTheAuthenticatedUser(ProductLine line) {
        var service = service(line, aeronOrders);
        var id = new UUID(4, 5);
        var expected = new com.surprising.trading.api.model.OrderCommandReceipt(id, "TERMINAL", "ORDER_NOT_FOUND",
                "committed rejection", null, List.of(), null, null);
        when(projection.commandResult(line, 1001, id)).thenReturn(expected);
        assertThat(service.commandResult(1001, id)).isSameAs(expected);
        verifyNoInteractions(aeronOrders);
    }

    @Test void submissionLookupNormalizesTheClientKeyAndNeverQueriesCore() {
        var service = service(ProductLine.SPOT, aeronOrders);
        var id = StableOrderIdentity.commandId(ProductLine.SPOT, 1001, "client-91");
        var expected = new com.surprising.trading.api.model.OrderSubmissionResponse(1001, ProductLine.SPOT,
                "client-91", "1", id, "TERMINAL", "INSUFFICIENT_FUNDS");
        when(projection.submission(ProductLine.SPOT, 1001, "client-91", id)).thenReturn(expected);
        assertThat(service.submission(1001, " client-91 ")).isSameAs(expected);
        verifyNoInteractions(aeronOrders);
    }

    @ParameterizedTest @EnumSource(ProductLine.class)
    void priceProtectionUsesTheCurrentProductSettingsWithoutQueryingCore(ProductLine line) {
        var properties = new TradingOrderProperties();
        properties.getKafka().setProductLine(line);
        var service = new OrderService(properties, orderValidator, placementStateService, aeronOrders, projection);
        properties.getRisk().setMarketMaxSlippagePpm(12345);
        assertThat(service.priceProtection()).isEqualTo(
                new com.surprising.trading.api.model.OrderPriceProtectionResponse(line, 12345));
        properties.getRisk().setMarketMaxSlippagePpm(6789);
        assertThat(service.priceProtection().marketMaxSlippagePpm()).isEqualTo(6789);
        verifyNoInteractions(aeronOrders, projection);
    }

    @Mock
    private OrderValidator orderValidator;
    @Mock
    private OrderPlacementStateService placementStateService;
    @Mock
    private AeronOrderCommandService aeronOrders;
    @Mock
    private AeronOrderProjectionRepository projection;
    @Mock
    private com.surprising.realtime.api.ValkeyUserQueries realtimeQueries;

    @ParameterizedTest @EnumSource(ProductLine.class)
    void enabledOpenOrderQueryUsesOnlyValkeyAndPassesTheRequiredWatermark(ProductLine product) {
        var service=service(product,aeronOrders);
        var queries=org.mockito.Mockito.mock(com.surprising.realtime.api.ValkeyUserQueries.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"realtimeQueries",queries);
        when(queries.require(product,1001L,9L)).thenReturn(new com.surprising.realtime.api.UserReadView(
            "READY","v",1,null,List.of(),List.of(),9,List.of()));
        assertThat(service.openOrders(1001L,null,10,null,9L).orders()).isEmpty();
        when(queries.require(product,1001L,9L)).thenThrow(new org.springframework.web.server.ResponseStatusException(
            org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE));
        assertThatThrownBy(()->service.openOrders(1001L,null,10,null,9L))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        verifyNoInteractions(aeronOrders,projection);
    }

    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void pointOrderQueryPassesIdentityAndWatermarkWithoutReadingTheWholeUser(ProductLine product) {
        var service = service(product, aeronOrders);
        var queries = org.mockito.Mockito.mock(com.surprising.realtime.api.ValkeyUserQueries.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "realtimeQueries", queries);
        var order = new com.surprising.aeron.protocol.CoreOrderStateView(91, product, 1001, "1",
                com.surprising.aeron.protocol.CoreOrderSide.BUY, 100, 2, 0, 2, false, "OPEN", 1);
        when(queries.order(product, 1001, 91, 9L)).thenReturn(order);
        assertThat(service.get(1001, 91, 9L).orderId()).isEqualTo(91);
        when(queries.order(product, 1001, 91, 9L)).thenThrow(new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "READ_VIEW_BEHIND"));
        assertThatThrownBy(() -> service.get(1001, 91, 9L))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        org.mockito.Mockito.verify(queries, never()).require(any(), anyLong(), any());
        verifyNoInteractions(aeronOrders, projection);
    }

    @Test
    void missingRealtimeDependencyCannotFallBackToCore() {
        var service = service(ProductLine.SPOT, aeronOrders);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "realtimeQueries", null);
        assertThatThrownBy(() -> service.get(1001, 91))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("READ_VIEW_UNAVAILABLE");
        verifyNoInteractions(aeronOrders, projection);
    }

    @ParameterizedTest @EnumSource(ProductLine.class)
    void terminalOrderRemainsQueryableFromCommittedRealtimeProjection(ProductLine product) {
        var service = service(product, aeronOrders);
        var queries = org.mockito.Mockito.mock(com.surprising.realtime.api.ValkeyUserQueries.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "realtimeQueries", queries);
        var filled = response(91, "client-91", OrderStatus.FILLED);
        when(projection.byOrder(product, 1001L, 91, 9L))
                .thenReturn(ProjectionReadResult.ok(List.of(filled), null, false, 9, 9));
        assertThat(service.get(1001, 91, 9L)).isEqualTo(filled);
        verifyNoInteractions(aeronOrders);
    }

    @ParameterizedTest @EnumSource(ProductLine.class)
    void terminalClientOrderRemainsQueryableAfterItLeavesTheActiveReadView(ProductLine product) {
        var service = service(product, aeronOrders);
        var queries = org.mockito.Mockito.mock(com.surprising.realtime.api.ValkeyUserQueries.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "realtimeQueries", queries);
        when(queries.require(product, 1001, 9L)).thenReturn(new com.surprising.realtime.api.UserReadView(
                "READY", "v", 1, null, List.of(), List.of(), 9, List.of()));
        var filled = response(91, "client-91", OrderStatus.FILLED);
        when(projection.byClientOrderId(product, 1001, "client-91", 9L))
                .thenReturn(ProjectionReadResult.ok(List.of(filled), null, false, 9, 9));
        assertThat(service.getByClientOrderId(1001, "client-91", 9L)).isEqualTo(filled);
        verifyNoInteractions(aeronOrders);
    }

    @Test
    void staleProjectedOpenOrderCannotReplaceAnAbsentRealtimeOrder() {
        var service = service(ProductLine.SPOT, aeronOrders);
        when(projection.byOrder(ProductLine.SPOT, 1001L, 91, null)).thenReturn(
                ProjectionReadResult.ok(List.of(response(91, "client-91", OrderStatus.ACCEPTED)), null, false, 1, 0));
        assertThatThrownBy(() -> service.get(1001, 91)).hasMessageContaining("order not found");
        verifyNoInteractions(aeronOrders);
    }

    @Test
    void terminalOrderQueryPreservesCommittedProjectionLag() {
        var service = service(ProductLine.SPOT, aeronOrders);
        when(projection.byOrder(ProductLine.SPOT, 1001L, 91, 9L)).thenReturn(ProjectionReadResult.lag(8, 9));
        assertThatThrownBy(() -> service.get(1001, 91, 9L))
                .isInstanceOf(ProjectionReadResult.ProjectionLagException.class);
        verifyNoInteractions(aeronOrders);
    }

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
                1001L, null, "1", MarginMode.CROSS, PositionSide.NET)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("clientOrderId is required");
        verifyNoInteractions(aeronOrders, placementStateService);
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("closePositions")
    void closePositionReadsAccountOnceAndSubmitsAuthoritativeReduceOnlyOrder(ProductLine line, long quantity) {
        var gateway = org.mockito.Mockito.mock(OrderAeronGateway.class);
        var position = new com.surprising.aeron.protocol.CorePositionView(
                "1", "USDT", quantity, 100, 500, 0, 50);
        when(gateway.userState(1001L)).thenReturn(new com.surprising.aeron.protocol.CoreUserStateView(
                line, 1001, 7, List.of(), List.of(), List.of(position)));
        TradingOrderProperties properties = new TradingOrderProperties();
        properties.getKafka().setProductLine(line);
        when(orderValidator.validate(any())).thenReturn(ValidationResult.ok(7L));
        var service = new OrderService(properties, orderValidator, new OrderPlacementStateService(gateway),
                aeronOrders, projection);
        when(aeronOrders.place(any())).thenReturn(response(91, "close-1", OrderStatus.ACCEPTED));

        service.closePosition(new ClosePositionRequest(1001L, "close-1", "1",
                MarginMode.CROSS, PositionSide.NET));

        ArgumentCaptor<PlaceOrderRequest> request = ArgumentCaptor.forClass(PlaceOrderRequest.class);
        verify(aeronOrders).place(request.capture());
        assertThat(request.getValue().clientOrderId()).isEqualTo("close-1");
        assertThat(request.getValue().side()).isEqualTo(quantity > 0 ? OrderSide.SELL : OrderSide.BUY);
        assertThat(request.getValue().quantitySteps()).isEqualTo(Math.absExact(quantity));
        assertThat(request.getValue().orderType()).isEqualTo(OrderType.MARKET);
        assertThat(request.getValue().timeInForce()).isEqualTo(TimeInForce.IOC);
        assertThat(request.getValue().reduceOnly()).isTrue();
        verify(gateway, times(1)).userState(1001L);
        org.mockito.Mockito.verifyNoMoreInteractions(gateway);
    }

    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> closePositions() {
        return java.util.Arrays.stream(ProductLine.values()).filter(ProductLine::isDerivative)
                .flatMap(line -> java.util.stream.Stream.of(5L, -5L)
                        .map(quantity -> org.junit.jupiter.params.provider.Arguments.of(line, quantity)));
    }

    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void normalizesProductPositionSemanticsBeforeSubmittingTheOrder(ProductLine product) {
        var service = service(product, aeronOrders);
        for (PositionSide position : List.of(PositionSide.LONG, PositionSide.SHORT)) {
            for (OrderSide side : OrderSide.values()) {
                var request = new PlaceOrderRequest(1001L, " client-normalized ", " 1 ", side,
                        OrderType.LIMIT, TimeInForce.GTC, 100, 2, MarginMode.ISOLATED,
                        position, false, true);
                if (product == ProductLine.SPOT) {
                    assertThatThrownBy(() -> service.place(request)).isInstanceOf(IllegalArgumentException.class);
                } else {
                    service.place(request);
                    boolean closing = position == PositionSide.LONG && side == OrderSide.SELL
                            || position == PositionSide.SHORT && side == OrderSide.BUY;
                    verify(aeronOrders).place(new PlaceOrderRequest(1001L, "client-normalized", "1", side,
                            OrderType.LIMIT, TimeInForce.GTC, 100, 2, MarginMode.ISOLATED,
                            position, closing, true));
                }
            }
        }
        if (product == ProductLine.SPOT) verifyNoInteractions(aeronOrders);
    }

    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void rejectsTheWholeBatchBeforeSubmittingWhenAnItemFailsValidation(ProductLine product) {
        var service = service(product, aeronOrders);
        when(orderValidator.validate(any())).thenReturn(ValidationResult.ok(7L),
                ValidationResult.reject("quantity exceeds limit", 7L));
        var batch = new com.surprising.trading.api.model.BatchPlaceOrderRequest("batch-validation",
                List.of(request("first"), request("second")));
        assertThatThrownBy(() -> service.placeBatchCommand(batch))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("quantity exceeds limit");
        verifyNoInteractions(aeronOrders);
    }

    @Test
    void placeDelegatesFundsAndFeeDecisionToCore() {
        OrderService service = service(ProductLine.LINEAR_PERPETUAL, aeronOrders);
        when(aeronOrders.place(any())).thenReturn(response(91, "core-fee", OrderStatus.ACCEPTED));

        assertThat(service.place(request("core-fee")).orderId()).isEqualTo(91);

        verify(aeronOrders).place(any());
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
                1001L, "1", 10, "risk"))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void adminCancelSelectsOpenOrdersFromCommittedRealtimeProjection() {
        OrderService service = service(ProductLine.LINEAR_PERPETUAL, aeronOrders);
        List<OrderResponse> open = openOrders(123);
        when(projection.openOrders(ProductLine.LINEAR_PERPETUAL, null, "1", null, 123, null))
                .thenReturn(ProjectionReadResult.ok(open, null, false, 1, 0));
        stubCancelBatches();

        AdminCancelOrdersResponse result = service.adminCancelOrders(
                new AdminBatchCancelOrdersRequest(null, "1", 123, "risk"));

        assertThat(result.requested()).isEqualTo(123);
        assertThat(result.canceled()).isEqualTo(123);
        assertThat(result.skipped()).isZero();
        verify(aeronOrders, never()).openOrders(anyLong(), any(), anyLong(), org.mockito.ArgumentMatchers.anyInt());
        verify(aeronOrders, times(3)).cancelBatchCommand(anyString(), anyList());
        verify(aeronOrders, never()).cancel(anyLong(), anyLong());
    }

    @Test
    void adminSingleCancelUsesTheProductLineScopedCommittedOrder() {
        OrderService service = service(ProductLine.LINEAR_PERPETUAL, aeronOrders);
        OrderResponse open = response(91, "client-91", OrderStatus.ACCEPTED);
        OrderResponse canceled = response(91, "client-91", OrderStatus.CANCELED);
        when(projection.byOrder(ProductLine.LINEAR_PERPETUAL, null, 91L, null))
                .thenReturn(ProjectionReadResult.ok(List.of(open), null, false, 1, 0));
        when(aeronOrders.cancel(1001L, 91L)).thenReturn(canceled);

        assertThat(service.adminCancelOrder(91L, "risk", ProductLine.LINEAR_PERPETUAL).orderId()).isEqualTo(91L);

        verify(aeronOrders, never()).orderState(anyLong(), anyLong());
        verify(aeronOrders).cancel(1001L, 91L);
    }

    @Test
    void optionProductLineUsesTheLocalAccountFactStream() {
        OrderService service = service(ProductLine.OPTION);
        PlaceOrderRequest optionRequest = new PlaceOrderRequest(1001L, "option-1", "1",
                OrderSide.BUY, OrderType.LIMIT, TimeInForce.GTC, 60_000L, 10L,
                MarginMode.CROSS, PositionSide.NET, false, false);

        assertThatThrownBy(() -> service.place(optionRequest)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void currentOrderReadUsesRealtimeWithTheRequiredExportSequence() {
        OrderService service = service(ProductLine.LINEAR_PERPETUAL, aeronOrders);
        OrderResponse open = response(91, "client-91", OrderStatus.ACCEPTED);
        when(realtimeQueries.order(ProductLine.LINEAR_PERPETUAL, 1001L, 91L, 12L)).thenReturn(view(open));

        OrderResponse result = service.get(1001L, 91L, 12L);

        assertThat(result).isEqualTo(open);
        verify(realtimeQueries).order(ProductLine.LINEAR_PERPETUAL, 1001L, 91L, 12L);
        verifyNoInteractions(aeronOrders, projection);
    }

    @Test
    void currentReadsUseRealtimeWhileHistoryUsesCommittedProjection() {
        OrderService service = service(ProductLine.LINEAR_PERPETUAL, aeronOrders);
        OrderResponse open = response(91, "client-91", OrderStatus.ACCEPTED);
        when(realtimeQueries.require(ProductLine.LINEAR_PERPETUAL, 1001L, null))
                .thenReturn(readView(List.of(open)));
        when(projection.historyOrders(ProductLine.LINEAR_PERPETUAL, 1001L, "1", 10,
                null, null, null, null, null))
                .thenReturn(ProjectionReadResult.ok(java.util.List.of(open), null, false, 12L, 0L));

        assertThat(service.getByClientOrderId(1001L, "client-91")).isEqualTo(open);
        assertThat(service.openOrders(1001L, "1", 10).orders()).containsExactly(open);
        assertThat(service.historyOrders(1001L, "1", 10, null, null, null).orders())
                .containsExactly(open);

        verifyNoInteractions(aeronOrders);
        verify(projection).historyOrders(ProductLine.LINEAR_PERPETUAL, 1001L, "1", 10,
                null, null, null, null, null);
    }

    @Test
    void cancelOpenOrdersSelectsFromRealtimeAndSendsOnlyCancelCommandToAeron() {
        OrderService service = service(ProductLine.LINEAR_PERPETUAL, aeronOrders);
        List<OrderResponse> open = openOrders(123);
        when(realtimeQueries.require(ProductLine.LINEAR_PERPETUAL, 1001L, null)).thenReturn(readView(open));
        stubCancelBatches();

        OrderBatchResponse first = service.cancelOpenOrders(
                new com.surprising.trading.api.model.CancelOpenOrdersRequest(1001L, "1", 1000));
        OrderBatchResponse second = service.cancelOpenOrders(
                new com.surprising.trading.api.model.CancelOpenOrdersRequest(1001L, "1", 1000));

        assertThat(first.completed()).isEqualTo(123);
        assertThat(first.results()).extracting(OrderBatchItemResponse::index)
                .containsExactlyElementsOf(java.util.stream.IntStream.range(0, 123).boxed().toList());
        assertThat(first.results().getFirst().order().orderId()).isEqualTo(10_122L);
        assertThat(first.results().getLast().order().orderId()).isEqualTo(10_000L);
        assertThat(second).isEqualTo(first);
        verify(realtimeQueries, times(2)).require(ProductLine.LINEAR_PERPETUAL, 1001L, null);
        verify(aeronOrders, never()).openOrders(anyLong(), any(), anyLong(), org.mockito.ArgumentMatchers.anyInt());
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
        when(aeronOrders.lifecycleOpenOrders("1", 123)).thenReturn(openOrders(123));
        stubCancelBatches();

        assertThat(service.requestLifecycleCancellation("1", 123)).isEqualTo(123);

        verify(aeronOrders).lifecycleOpenOrders("1", 123);
        verify(aeronOrders, times(3)).cancelBatchCommand(anyString(), anyList());
        verify(aeronOrders, never()).cancel(anyLong(), anyLong());
        verifyNoInteractions(projection);
    }

    @Test
    void lifecycleActiveCheckUsesAeronAuthorityInsteadOfStaleProjection() {
        OrderService service = service(ProductLine.LINEAR_PERPETUAL, aeronOrders);
        when(aeronOrders.lifecycleOpenOrders("1", 1))
                .thenReturn(java.util.List.of(response(91, "client-91", OrderStatus.ACCEPTED)));

        assertThat(service.hasLifecycleActiveOrders("1")).isTrue();

        verify(aeronOrders).lifecycleOpenOrders("1", 1);
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
    void currentOpenOrdersPaginatesTheRealtimeView() {
        OrderService service = service(ProductLine.LINEAR_PERPETUAL, aeronOrders);
        when(realtimeQueries.require(ProductLine.LINEAR_PERPETUAL, 1001L, null)).thenReturn(readView(openOrders(3)));

        OrderQueryResponse first = service.openOrders(1001L, "1", 2);

        assertThat(first.orders()).hasSize(2);
        assertThat(first.hasMore()).isTrue();
        assertThat(first.nextCursor()).isNotBlank();
        var second = service.openOrders(1001L, "1", 2, first.nextCursor());
        assertThat(second.orders()).extracting(OrderResponse::orderId).containsExactly(10_000L);
        assertThat(second.hasMore()).isFalse();
        verifyNoInteractions(aeronOrders, projection);
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
        var service = new OrderService(properties, orderValidator, placementStateService, aeron, projection);
        when(realtimeQueries.require(productLine, 1001L, null)).thenReturn(readView(List.of()));
        org.springframework.test.util.ReflectionTestUtils.setField(service, "realtimeQueries", realtimeQueries);
        return service;
    }

    private PlaceOrderRequest request(String clientOrderId) {
        return new PlaceOrderRequest(1001L, clientOrderId, "1", OrderSide.BUY, OrderType.LIMIT,
                TimeInForce.GTC, 60_000L, 10L, MarginMode.CROSS, PositionSide.NET, true, false);
    }

    private OrderResponse response(long orderId, String clientOrderId, OrderStatus status) {
        Instant now = Instant.parse("2026-08-02T00:00:00Z");
        return new OrderResponse(orderId, 1001L, clientOrderId, "1", OrderSide.BUY,
                OrderType.LIMIT, TimeInForce.GTC, 60_000L, 10L, 0L, 10L, MarginMode.CROSS,
                PositionSide.NET, 100L, 200L, true, false, status, null, now, now, 0, "0", null);
    }

    private com.surprising.aeron.protocol.CoreOrderStateView view(OrderResponse order) {
        return new com.surprising.aeron.protocol.CoreOrderStateView(order.orderId(), ProductLine.LINEAR_PERPETUAL,
                order.userId(), order.instrumentId(), com.surprising.aeron.protocol.CoreOrderSide.BUY,
                order.priceTicks(), order.quantitySteps(), order.executedQuantitySteps(), order.remainingQuantitySteps(),
                order.reduceOnly(), com.surprising.aeron.protocol.CoreMarginMode.CROSS,
                com.surprising.aeron.protocol.CorePositionSide.NET, com.surprising.aeron.protocol.CoreOrderType.LIMIT,
                com.surprising.aeron.protocol.CoreTimeInForce.GTC, false, order.clientOrderId(), new UUID(0, order.orderId()),
                order.makerFeeRatePpm(), order.takerFeeRatePpm(), order.createdAt().toEpochMilli(),
                order.updatedAt().toEpochMilli(), 1, "OPEN", 1);
    }

    private com.surprising.realtime.api.UserReadView readView(List<OrderResponse> orders) {
        return new com.surprising.realtime.api.UserReadView("READY", "v", 1, null,
                orders.stream().map(this::view).toList(), List.of(), 1, List.of());
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
                            1L, new byte[0])),
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
                            execution.prospectiveOrderIds(), aggregate, null);
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
