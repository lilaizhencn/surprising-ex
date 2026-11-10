package com.surprising.trading.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.surprising.aeron.client.CoreCommandOutcome;
import com.surprising.aeron.protocol.CancelOrderBatchCommand;
import com.surprising.aeron.protocol.CoreCommandResultCodec;
import com.surprising.aeron.protocol.CoreCommandResultView;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreOrderBatchResult;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreOrderStateView;
import com.surprising.aeron.protocol.CoreOrderType;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.CoreTimeInForce;
import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.aeron.protocol.TradingOrderBatchCodec;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.AdminBatchCancelOrdersRequest;
import com.surprising.trading.api.model.AdminCancelOrdersResponse;
import com.surprising.trading.api.model.CancelOrderRequest;
import com.surprising.trading.api.model.ClosePositionRequest;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.OrderBatchItemResponse;
import com.surprising.trading.api.model.OrderBatchResponse;
import com.surprising.trading.api.model.OrderCommandReceipt;
import com.surprising.trading.api.model.OrderQueryResponse;
import com.surprising.trading.api.model.OrderResponse;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.OrderStatus;
import com.surprising.trading.api.model.OrderType;
import com.surprising.trading.api.model.PlaceOrderRequest;
import com.surprising.trading.api.model.PositionSide;
import com.surprising.trading.api.model.TimeInForce;
import com.surprising.trading.order.config.TradingOrderProperties;
import com.surprising.trading.order.model.ValidationResult;
import com.surprising.trading.order.repository.AeronOrderProjectionRepository;
import com.surprising.trading.order.repository.ProjectionReadResult;
import java.time.Instant;
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
    void terminalHistoryFiltersBeforePaginationWithoutCoreQueries(ProductLine line) {
        var service = service(line, aeron);
        when(projection.historyOrders(line, 1001L, "1", 1, null, 1000L, 2000L, "cursor", null, OrderStatus.FILLED))
                .thenReturn(ProjectionReadResult.ok(List.of(response(91, "own", OrderStatus.FILLED)), "next", true, 12L, 0L));
        var page = service.historyOrders(1001L, " 1 ", 1, null, 1000L, 2000L, "cursor", null, " filled ");
        assertThat(page.sort()).isEqualTo("updatedAt.desc");
        assertThat(page.hasMore()).isTrue();
        assertThat(page.nextCursor()).isEqualTo("next");
        assertThat(page.orders()).extracting(OrderResponse::status).containsExactly(OrderStatus.FILLED);
        verifyNoInteractions(aeron, realtimeQueries);
        assertThatThrownBy(() -> service.historyOrders(1001L, "1", 1, null, null, null, null, null, "ACCEPTED"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest @EnumSource(ProductLine.class)
    void ownFillsReadOnlyTheCommittedProductProjection(ProductLine line) {
        var trades = org.mockito.Mockito.mock(com.surprising.trading.order.repository.TradeProjectionRepository.class);
        var service = service(line, aeron, realtimeQueries, trades);
        var page = new com.surprising.trading.api.model.TradeFillQueryResponse(1001, line, 0, List.of(), null, false, "eventTime.desc", 10);
        var cursor = new com.surprising.trading.order.model.TradeReadCursor.Fill(1500L, Long.MAX_VALUE, 1, "MAKER");
        when(trades.ownFills(line, 1001, "1", 91L, 1000L, 2000L, 10, cursor)).thenReturn(page);
        assertThat(service.ownFills(1001, " 1 ", 91L, 1000L, 2000L, 10, cursor)).isSameAs(page);
        verifyNoInteractions(aeron, projection, realtimeQueries);
    }

    @ParameterizedTest @EnumSource(ProductLine.class)
    void amendmentLookupUsesTheReplacementCommandNamespaceAndNoCoreQuery(ProductLine line) {
        var service = service(line, aeron);
        var id = StableOrderIdentity.replacementCommandId(line, 1001, "amend-91");
        var receipt = new OrderCommandReceipt(id, "TERMINAL", "NONE",
                "committed", null, List.of(), null, null);
        when(projection.amendment(line, 1001, id)).thenReturn(receipt);
        assertThat(service.amendment(1001, " amend-91 ")).isSameAs(receipt);
        verifyNoInteractions(aeron);
    }

    @ParameterizedTest @EnumSource(ProductLine.class)
    void cancellationLookupUsesTheOriginalStableIdentityAndOnlyTheProjection(ProductLine line) {
        var service = service(line, aeron);
        var id = StableOrderIdentity.commandId(line, 1001, "cancel:91");
        var expected = new OrderCommandReceipt(id, "TERMINAL", "NONE",
                "committed cancellation", null, List.of(91L), null, null);
        when(projection.cancellation(line, 1001, 91, id)).thenReturn(expected);
        assertThat(service.cancellation(1001, 91)).isSameAs(expected);
        assertThatThrownBy(() -> service.cancellation(1001, 0)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(aeron);
    }

    @ParameterizedTest @EnumSource(ProductLine.class)
    void publicCommandReceiptsUseTheCommittedProjectionForTheAuthenticatedUser(ProductLine line) {
        var service = service(line, aeron);
        var id = new UUID(4, 5);
        var expected = new OrderCommandReceipt(id, "TERMINAL", "ORDER_NOT_FOUND",
                "committed rejection", null, List.of(), null, null);
        when(projection.commandResult(line, 1001, id)).thenReturn(expected);
        assertThat(service.commandResult(1001, id)).isSameAs(expected);
        verifyNoInteractions(aeron);
    }

    @Test void submissionLookupNormalizesTheClientKeyAndNeverQueriesCore() {
        var service = service(ProductLine.SPOT, aeron);
        var id = StableOrderIdentity.commandId(ProductLine.SPOT, 1001, "client-91");
        var expected = new com.surprising.trading.api.model.OrderSubmissionResponse(1001, ProductLine.SPOT,
                "client-91", "1", id, "TERMINAL", "INSUFFICIENT_FUNDS");
        when(projection.submission(ProductLine.SPOT, 1001, "client-91", id)).thenReturn(expected);
        assertThat(service.submission(1001, " client-91 ")).isSameAs(expected);
        verifyNoInteractions(aeron);
    }

    @ParameterizedTest @EnumSource(ProductLine.class)
    void priceProtectionUsesTheCurrentProductSettingsWithoutQueryingCore(ProductLine line) {
        var properties = new TradingOrderProperties();
        properties.getKafka().setProductLine(line);
        var service = new OrderService(properties, orderValidator, aeron, projection, null, null, null, null);
        properties.getRisk().setMarketMaxSlippagePpm(12345);
        assertThat(service.priceProtection()).isEqualTo(
                new com.surprising.trading.api.model.OrderPriceProtectionResponse(line, 12345));
        properties.getRisk().setMarketMaxSlippagePpm(6789);
        assertThat(service.priceProtection().marketMaxSlippagePpm()).isEqualTo(6789);
        verifyNoInteractions(aeron, projection);
    }

    @Mock
    private OrderValidator orderValidator;
    @Mock
    private OrderAeronGateway aeron;
    @Mock
    private AeronOrderProjectionRepository projection;
    @Mock
    private com.surprising.realtime.api.ValkeyUserQueries realtimeQueries;

    @ParameterizedTest @EnumSource(ProductLine.class)
    void enabledOpenOrderQueryUsesOnlyValkeyAndPassesTheRequiredWatermark(ProductLine product) {
        var queries = org.mockito.Mockito.mock(com.surprising.realtime.api.ValkeyUserQueries.class);
        var service = service(product, aeron, queries, null);
        when(queries.require(product, 1001L, 9L)).thenReturn(new com.surprising.realtime.api.UserReadView(
                "READY", "v", 1, null, List.of(), List.of(), 9, List.of()));
        assertThat(service.openOrders(1001L, null, com.surprising.trading.order.controller.OrderRequestHandler.activePage(10, null), 9L).orders()).isEmpty();
        when(queries.require(product, 1001L, 9L)).thenThrow(new com.surprising.realtime.api.exception.ReadViewUnavailableException("READ_VIEW_UNAVAILABLE"));
        assertThatThrownBy(() -> service.openOrders(1001L, null, com.surprising.trading.order.controller.OrderRequestHandler.activePage(10, null), 9L))
                .isInstanceOf(com.surprising.realtime.api.exception.ReadViewUnavailableException.class);
        verifyNoInteractions(aeron, projection);
    }

    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void pointOrderQueryPassesIdentityAndWatermarkWithoutReadingTheWholeUser(ProductLine product) {
        var queries = org.mockito.Mockito.mock(com.surprising.realtime.api.ValkeyUserQueries.class);
        var service = service(product, aeron, queries, null);
        var order = new CoreOrderStateView(91, product, 1001, "1",
                CoreOrderSide.BUY, 100, 2, 0, 2, false, "OPEN", 1);
        when(queries.order(product, 1001, 91, 9L)).thenReturn(order);
        assertThat(service.get(1001, 91, 9L).orderId()).isEqualTo(91);
        when(queries.order(product, 1001, 91, 9L)).thenThrow(new com.surprising.realtime.api.exception.ReadViewUnavailableException("READ_VIEW_BEHIND"));
        assertThatThrownBy(() -> service.get(1001, 91, 9L))
                .isInstanceOf(com.surprising.realtime.api.exception.ReadViewUnavailableException.class);
        org.mockito.Mockito.verify(queries, never()).require(any(), anyLong(), any());
        verifyNoInteractions(aeron, projection);
    }

    @Test
    void missingRealtimeDependencyCannotFallBackToCore() {
        var service = service(ProductLine.SPOT, aeron, null, null);
        assertThatThrownBy(() -> service.get(1001, 91))
                .isInstanceOf(com.surprising.realtime.api.exception.ReadViewUnavailableException.class)
                .hasMessageContaining("READ_VIEW_UNAVAILABLE");
        verifyNoInteractions(aeron, projection);
    }

    @ParameterizedTest @EnumSource(ProductLine.class)
    void terminalOrderRemainsQueryableFromCommittedRealtimeProjection(ProductLine product) {
        var queries = org.mockito.Mockito.mock(com.surprising.realtime.api.ValkeyUserQueries.class);
        var service = service(product, aeron, queries, null);
        var filled = response(91, "client-91", OrderStatus.FILLED);
        when(projection.byOrder(product, 1001L, 91, 9L))
                .thenReturn(ProjectionReadResult.ok(List.of(filled), null, false, 9, 9));
        assertThat(service.get(1001, 91, 9L)).isEqualTo(filled);
        verifyNoInteractions(aeron);
    }

    @ParameterizedTest @EnumSource(ProductLine.class)
    void terminalClientOrderRemainsQueryableAfterItLeavesTheActiveReadView(ProductLine product) {
        var queries = org.mockito.Mockito.mock(com.surprising.realtime.api.ValkeyUserQueries.class);
        var service = service(product, aeron, queries, null);
        when(queries.require(product, 1001, 9L)).thenReturn(new com.surprising.realtime.api.UserReadView(
                "READY", "v", 1, null, List.of(), List.of(), 9, List.of()));
        var filled = response(91, "client-91", OrderStatus.FILLED);
        when(projection.byClientOrderId(product, 1001, "client-91", 9L))
                .thenReturn(ProjectionReadResult.ok(List.of(filled), null, false, 9, 9));
        assertThat(service.getByClientOrderId(1001, "client-91", 9L)).isEqualTo(filled);
        verifyNoInteractions(aeron);
    }

    @Test
    void staleProjectedOpenOrderCannotReplaceAnAbsentRealtimeOrder() {
        var service = service(ProductLine.SPOT, aeron);
        when(projection.byOrder(ProductLine.SPOT, 1001L, 91, null)).thenReturn(
                ProjectionReadResult.ok(List.of(response(91, "client-91", OrderStatus.ACCEPTED)), null, false, 1, 0));
        assertThatThrownBy(() -> service.get(1001, 91)).hasMessageContaining("order not found");
        verifyNoInteractions(aeron);
    }

    @Test
    void terminalOrderQueryPreservesCommittedProjectionLag() {
        var service = service(ProductLine.SPOT, aeron);
        when(projection.byOrder(ProductLine.SPOT, 1001L, 91, 9L)).thenReturn(ProjectionReadResult.lag(8, 9));
        assertThatThrownBy(() -> service.get(1001, 91, 9L))
                .isInstanceOf(ProjectionReadResult.ProjectionLagException.class);
        verifyNoInteractions(aeron);
    }

    @Test
    void placeFailsClosedWithoutAeronGateway() {
        OrderService service = service(ProductLine.LINEAR_PERPETUAL);
        PlaceOrderRequest request = request("client-1");
        assertThatThrownBy(() -> service.place(request)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Aeron order gateway");
        verifyNoInteractions(aeron);
    }

    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void placeRejectsMissingClientOrderIdAtServiceBoundary(ProductLine productLine) {
        OrderService service = service(productLine, aeron);

        assertThatThrownBy(() -> service.place(request(null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("clientOrderId is required");
        verifyNoInteractions(aeron);
    }

    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void closePositionRejectsMissingClientOrderIdAtServiceBoundary(ProductLine productLine) {
        OrderService service = service(productLine, aeron);

        assertThatThrownBy(() -> service.closePosition(new ClosePositionRequest(
                1001L, null, "1", MarginMode.CROSS, PositionSide.NET)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("clientOrderId is required");
        verifyNoInteractions(aeron);
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("closePositions")
    void closePositionReadsAccountOnceAndSubmitsAuthoritativeReduceOnlyOrder(ProductLine line, long quantity) {
        var gateway = org.mockito.Mockito.mock(OrderAeronGateway.class);
        var position = new com.surprising.aeron.protocol.CorePositionView(
                "1", "USDT", quantity, 100, 500, 0, 50);
        when(gateway.userState(1001L)).thenReturn(new com.surprising.aeron.protocol.CoreUserStateView(
                line, 1001, 7, List.of(), List.of(), List.of(position)));
        stubPlaceOutcome(gateway, line);
        TradingOrderProperties properties = new TradingOrderProperties();
        properties.getKafka().setProductLine(line);
        when(orderValidator.validate(any())).thenReturn(ValidationResult.ok(7L));
        var service = new OrderService(properties, orderValidator, gateway, projection, null, null, null, null);

        service.closePosition(new ClosePositionRequest(1001L, "close-1", "1",
                MarginMode.CROSS, PositionSide.NET));

        ArgumentCaptor<byte[]> payload = ArgumentCaptor.forClass(byte[].class);
        verify(gateway).commandOutcome(eq(CoreMessageType.PLACE_ORDER), any(UUID.class), eq(1001L), payload.capture());
        PlaceOrderCommand command = TradingCommandCodec.decodePlaceOrder(payload.getValue());
        assertThat(command.clientOrderId()).isEqualTo("close-1");
        assertThat(command.side()).isEqualTo(quantity > 0 ? CoreOrderSide.SELL : CoreOrderSide.BUY);
        assertThat(command.quantitySteps()).isEqualTo(Math.absExact(quantity));
        assertThat(command.orderType()).isEqualTo(CoreOrderType.MARKET);
        assertThat(command.timeInForce()).isEqualTo(CoreTimeInForce.IOC);
        assertThat(command.reduceOnly()).isTrue();
        verify(gateway, times(1)).userState(1001L);
    }

    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> closePositions() {
        return java.util.Arrays.stream(ProductLine.values()).filter(ProductLine::isDerivative)
                .flatMap(line -> java.util.stream.Stream.of(5L, -5L)
                        .map(quantity -> org.junit.jupiter.params.provider.Arguments.of(line, quantity)));
    }

    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void normalizesProductPositionSemanticsBeforeSubmittingTheOrder(ProductLine product) {
        var service = service(product, aeron);
        stubPlaceOutcome(aeron, product);
        for (PositionSide position : List.of(PositionSide.LONG, PositionSide.SHORT)) {
            for (OrderSide side : OrderSide.values()) {
                var request = new PlaceOrderRequest(1001L, " client-normalized ", " 1 ", side,
                        OrderType.LIMIT, TimeInForce.GTC, 100, 2, MarginMode.ISOLATED,
                        position, false, false);
                if (product == ProductLine.SPOT) {
                    assertThatThrownBy(() -> service.place(request)).isInstanceOf(IllegalArgumentException.class);
                } else {
                    OrderResponse response = service.place(request);
                    boolean closing = position == PositionSide.LONG && side == OrderSide.SELL
                            || position == PositionSide.SHORT && side == OrderSide.BUY;
                    assertThat(response.clientOrderId()).isEqualTo("client-normalized");
                    assertThat(response.instrumentId()).isEqualTo("1");
                    assertThat(response.reduceOnly()).isEqualTo(closing);
                }
            }
        }
        if (product == ProductLine.SPOT) verifyNoInteractions(aeron);
    }

    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void rejectsTheWholeBatchBeforeSubmittingWhenAnItemFailsValidation(ProductLine product) {
        var service = service(product, aeron);
        when(orderValidator.validate(any())).thenReturn(ValidationResult.ok(7L),
                ValidationResult.reject("quantity exceeds limit", 7L));
        var batch = new com.surprising.trading.api.model.BatchPlaceOrderRequest("batch-validation",
                List.of(request("first"), request("second")));
        assertThatThrownBy(() -> service.placeBatchCommand(batch))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("quantity exceeds limit");
        verifyNoInteractions(aeron);
    }

    @Test
    void placeDelegatesFundsAndFeeDecisionToCore() {
        OrderService service = service(ProductLine.LINEAR_PERPETUAL, aeron);
        stubPlaceOutcome(aeron, ProductLine.LINEAR_PERPETUAL);

        assertThat(service.place(request("core-fee")).clientOrderId()).isEqualTo("core-fee");

        verify(aeron).commandOutcome(eq(CoreMessageType.PLACE_ORDER), any(UUID.class), eq(1001L), any(byte[].class));
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
        OrderService service = service(ProductLine.LINEAR_PERPETUAL, aeron);
        List<OrderResponse> open = openOrders(123);
        when(projection.openOrders(ProductLine.LINEAR_PERPETUAL, null, "1", null, 123, null))
                .thenReturn(ProjectionReadResult.ok(open, null, false, 1, 0));
        stubCancelBatches(ProductLine.LINEAR_PERPETUAL);

        AdminCancelOrdersResponse result = service.adminCancelOrders(
                new AdminBatchCancelOrdersRequest(null, "1", 123, "risk"));

        assertThat(result.requested()).isEqualTo(123);
        assertThat(result.canceled()).isEqualTo(123);
        assertThat(result.skipped()).isZero();
        verify(aeron, never()).openOrders(anyLong(), any(), anyLong(), org.mockito.ArgumentMatchers.anyInt());
        verify(aeron, times(3)).commandOutcome(eq(CoreMessageType.CANCEL_ORDER_BATCH), any(UUID.class), eq(1001L), any(byte[].class));
        verify(aeron, never()).commandOutcome(eq(CoreMessageType.CANCEL_ORDER), any(), anyLong(), any());
    }

    @Test
    void adminSingleCancelUsesTheProductLineScopedCommittedOrder() {
        OrderService service = service(ProductLine.LINEAR_PERPETUAL, aeron);
        OrderResponse open = response(91, "client-91", OrderStatus.ACCEPTED);
        OrderResponse canceled = response(91, "client-91", OrderStatus.CANCELED);
        when(projection.byOrder(ProductLine.LINEAR_PERPETUAL, null, 91L, null))
                .thenReturn(ProjectionReadResult.ok(List.of(open), null, false, 1, 0));
        when(aeron.commandOutcome(eq(CoreMessageType.CANCEL_ORDER), any(UUID.class), eq(1001L), any(byte[].class)))
                .thenReturn(new CoreCommandOutcome.Terminal(new CoreResponse(
                        ResponseStatus.APPLIED, ResponseStatus.APPLIED, CoreResultCode.NONE,
                        1L, CoreCommandResultCodec.encode(new CoreCommandResultView(List.of(view(canceled)), List.of())))));

        assertThat(service.adminCancelOrder(91L, "risk", ProductLine.LINEAR_PERPETUAL).orderId()).isEqualTo(91L);

        verify(aeron, never()).orderState(anyLong(), anyLong());
        verify(aeron).commandOutcome(eq(CoreMessageType.CANCEL_ORDER), any(UUID.class), eq(1001L), any(byte[].class));
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
        OrderService service = service(ProductLine.LINEAR_PERPETUAL, aeron);
        OrderResponse open = response(91, "client-91", OrderStatus.ACCEPTED);
        when(realtimeQueries.order(ProductLine.LINEAR_PERPETUAL, 1001L, 91L, 12L)).thenReturn(view(open));

        OrderResponse result = service.get(1001L, 91L, 12L);

        assertThat(result).isEqualTo(open);
        verify(realtimeQueries).order(ProductLine.LINEAR_PERPETUAL, 1001L, 91L, 12L);
        verifyNoInteractions(aeron, projection);
    }

    @Test
    void currentReadsUseRealtimeWhileHistoryUsesCommittedProjection() {
        OrderService service = service(ProductLine.LINEAR_PERPETUAL, aeron);
        OrderResponse open = response(91, "client-91", OrderStatus.ACCEPTED);
        when(realtimeQueries.require(ProductLine.LINEAR_PERPETUAL, 1001L, null))
                .thenReturn(readView(List.of(open)));
        when(projection.historyOrders(ProductLine.LINEAR_PERPETUAL, 1001L, "1", 10,
                null, null, null, null, null, null))
                .thenReturn(ProjectionReadResult.ok(java.util.List.of(open), null, false, 12L, 0L));

        assertThat(service.getByClientOrderId(1001L, "client-91")).isEqualTo(open);
        assertThat(service.openOrders(1001L, "1", com.surprising.trading.order.controller.OrderRequestHandler.activePage(10, null), null).orders()).containsExactly(open);
        var history = service.historyOrders(1001L, "1", 10, null, null, null);
        assertThat(history.orders()).containsExactly(open);
        assertThat(history.sort()).isEqualTo("updatedAt.desc");

        verifyNoInteractions(aeron);
        verify(projection).historyOrders(ProductLine.LINEAR_PERPETUAL, 1001L, "1", 10,
                null, null, null, null, null, null);
    }

    @ParameterizedTest @EnumSource(ProductLine.class)
    void timerCompletionChecksTheOwnerCoreWithoutPublicReadProjections(ProductLine line) {
        var service = service(line, aeron);
        when(aeron.openOrders(1001L, "1", 0L, 1000)).thenReturn(List.of());

        assertThat(service.cancelTimerOrders(1001L, "1", 1000).requested()).isZero();

        verify(aeron).openOrders(1001L, "1", 0L, 1000);
        verifyNoInteractions(realtimeQueries, projection);
    }

    @Test
    void cancelOpenOrdersSelectsFromRealtimeAndSendsOnlyCancelCommandToAeron() {
        OrderService service = service(ProductLine.LINEAR_PERPETUAL, aeron);
        List<OrderResponse> open = openOrders(123);
        when(realtimeQueries.require(ProductLine.LINEAR_PERPETUAL, 1001L, null)).thenReturn(readView(open));
        stubCancelBatches(ProductLine.LINEAR_PERPETUAL);

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
        verify(aeron, never()).openOrders(anyLong(), any(), anyLong(), org.mockito.ArgumentMatchers.anyInt());
        ArgumentCaptor<UUID> batchCommandIds = ArgumentCaptor.forClass(UUID.class);
        ArgumentCaptor<byte[]> batchPayloads = ArgumentCaptor.forClass(byte[].class);
        verify(aeron, times(6)).commandOutcome(eq(CoreMessageType.CANCEL_ORDER_BATCH), batchCommandIds.capture(), eq(1001L), batchPayloads.capture());
        assertThat(batchCommandIds.getAllValues().subList(0, 3))
                .containsExactlyElementsOf(batchCommandIds.getAllValues().subList(3, 6));
        assertThat(batchPayloads.getAllValues().subList(0, 3).stream()
                .map(bytes -> TradingOrderBatchCodec.decodeCancelOrderBatch(bytes).items().size())
                .toList()).containsExactly(50, 50, 23);
        verify(aeron, never()).commandOutcome(eq(CoreMessageType.CANCEL_ORDER), any(), anyLong(), any());
    }

    @ParameterizedTest @EnumSource(ProductLine.class)
    void cancellationPlanIsUnpagedProductScopedAndReadOnly(ProductLine line) {
        var service = service(line, aeron);
        var view = readView(openOrders(1205));
        when(realtimeQueries.require(line, 1001L, null)).thenReturn(view);
        assertThatThrownBy(() -> service.cancellationPlan(1001, " ")).isInstanceOf(IllegalArgumentException.class);
        var all = service.cancellationPlan(1001, null);
        assertThat(all.productLine()).isEqualTo(line);
        assertThat(all.userId()).isEqualTo(1001);
        assertThat(all.instrumentId()).isNull();
        assertThat(all.orderIds()).hasSize(1205).isSorted();
        assertThat(all.orderIds().getFirst()).isEqualTo(10000);
        assertThat(all.orderIds().getLast()).isEqualTo(11204);
        assertThat(service.cancellationPlan(1001, " 1 ").orderIds()).isEqualTo(all.orderIds());
        assertThat(service.cancellationPlan(1001, "2").orderIds()).isEmpty();
        verifyNoInteractions(aeron, projection);
        when(realtimeQueries.require(line, 1001L, null)).thenThrow(new com.surprising.realtime.api.exception.ReadViewUnavailableException("READ_VIEW_UNAVAILABLE"));
        assertThatThrownBy(() -> service.cancellationPlan(1001, null))
                .isInstanceOf(com.surprising.realtime.api.exception.ReadViewUnavailableException.class);
        verifyNoInteractions(aeron, projection);
    }

    @ParameterizedTest @EnumSource(ProductLine.class)
    void cancellationBatchLookupUsesExactlyTheNativeCancelNamespace(ProductLine line) {
        var service = service(line, aeron);
        var id = StableOrderIdentity.commandId(line, 1001, "batch:cancel:fixed-key");
        var expected = new OrderCommandReceipt(id, "RESULT_UNKNOWN", "RESULT_UNKNOWN", "pending",
                null, List.of(), null, null);
        when(projection.cancellationBatch(line, 1001, id)).thenReturn(expected);
        assertThat(service.cancellationBatch(1001, " fixed-key ")).isSameAs(expected);
        assertThatThrownBy(() -> service.cancellationBatch(0, "fixed-key")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.cancellationBatch(1001, " ")).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(aeron);
    }

    @Test
    void lifecycleCancellationUsesAeronAuthorityInsteadOfStaleProjection() {
        OrderService service = service(ProductLine.LINEAR_PERPETUAL, aeron);
        when(aeron.lifecycleOpenOrders("1", 123)).thenReturn(openOrders(123).stream().map(this::view).toList());
        stubCancelBatches(ProductLine.LINEAR_PERPETUAL);

        assertThat(service.requestLifecycleCancellation("1", 123)).isEqualTo(123);

        verify(aeron).lifecycleOpenOrders("1", 123);
        verify(aeron, times(3)).commandOutcome(eq(CoreMessageType.CANCEL_ORDER_BATCH), any(UUID.class), eq(1001L), any(byte[].class));
        verify(aeron, never()).commandOutcome(eq(CoreMessageType.CANCEL_ORDER), any(), anyLong(), any());
        verifyNoInteractions(projection);
    }

    @Test
    void lifecycleActiveCheckUsesAeronAuthorityInsteadOfStaleProjection() {
        OrderService service = service(ProductLine.LINEAR_PERPETUAL, aeron);
        when(aeron.lifecycleOpenOrders("1", 1))
                .thenReturn(java.util.List.of(view(response(91, "client-91", OrderStatus.ACCEPTED))));

        assertThat(service.hasLifecycleActiveOrders("1")).isTrue();

        verify(aeron).lifecycleOpenOrders("1", 1);
        verifyNoInteractions(projection);
    }

    @Test
    void adminSingleCancelRejectsProductLineMismatchBeforeProjectionOrAeronOffer() {
        OrderService service = service(ProductLine.LINEAR_PERPETUAL, aeron);

        assertThatThrownBy(() -> service.adminCancelOrder(91L, "risk", ProductLine.OPTION))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("product line");

        verifyNoInteractions(projection, aeron);
    }

    @Test
    void currentOpenOrdersPaginatesTheRealtimeView() {
        OrderService service = service(ProductLine.LINEAR_PERPETUAL, aeron);
        when(realtimeQueries.require(ProductLine.LINEAR_PERPETUAL, 1001L, null)).thenReturn(readView(openOrders(3)));

        OrderQueryResponse first = service.openOrders(1001L, "1", com.surprising.trading.order.controller.OrderRequestHandler.activePage(2, null), null);

        assertThat(first.orders()).hasSize(2);
        assertThat(first.hasMore()).isTrue();
        assertThat(first.nextCursor()).isNotBlank();
        var second = service.openOrders(1001L, "1", com.surprising.trading.order.controller.OrderRequestHandler.activePage(2, first.nextCursor()), null);
        assertThat(second.orders()).extracting(OrderResponse::orderId).containsExactly(10_000L);
        assertThat(second.hasMore()).isFalse();
        verifyNoInteractions(aeron, projection);
    }

    private OrderService service(ProductLine productLine) {
        return service(productLine, null);
    }

    private OrderService service(ProductLine productLine, OrderAeronGateway aeronGateway) {
        return service(productLine, aeronGateway, realtimeQueries, null);
    }

    private OrderService service(ProductLine productLine, OrderAeronGateway aeronGateway,
            com.surprising.realtime.api.ValkeyUserQueries queries,
            com.surprising.trading.order.repository.TradeProjectionRepository trades) {
        TradingOrderProperties properties = new TradingOrderProperties();
        properties.getKafka().setProductLine(productLine);
        when(orderValidator.validate(any())).thenReturn(ValidationResult.ok(7L));
        if (queries != null) when(queries.require(productLine, 1001L, null)).thenReturn(readView(List.of()));
        return new OrderService(properties, orderValidator, aeronGateway, projection,
                null, null, trades, queries);
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

    private CoreOrderStateView view(OrderResponse order) {
        String coreStatus = switch (order.status()) {
            case ACCEPTED, PARTIALLY_FILLED -> "OPEN";
            default -> order.status().name();
        };
        return new CoreOrderStateView(order.orderId(), ProductLine.LINEAR_PERPETUAL,
                order.userId(), order.instrumentId(), CoreOrderSide.BUY,
                order.priceTicks(), order.quantitySteps(), order.executedQuantitySteps(), order.remainingQuantitySteps(),
                order.reduceOnly(), CoreMarginMode.CROSS,
                CorePositionSide.NET, CoreOrderType.LIMIT,
                CoreTimeInForce.GTC, false, order.clientOrderId(), new UUID(0, order.orderId()),
                order.makerFeeRatePpm(), order.takerFeeRatePpm(), order.createdAt().toEpochMilli(),
                order.updatedAt().toEpochMilli(), 1, coreStatus, 1);
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

    private void stubPlaceOutcome(OrderAeronGateway gateway, ProductLine line) {
        when(gateway.commandOutcome(eq(CoreMessageType.PLACE_ORDER), any(UUID.class), anyLong(), any(byte[].class)))
                .thenAnswer(invocation -> {
                    PlaceOrderCommand cmd = TradingCommandCodec.decodePlaceOrder(invocation.getArgument(3));
                    CoreOrderStateView order = new CoreOrderStateView(cmd.orderId(), line, 1001L, cmd.instrumentId(),
                            cmd.side(), cmd.limitPriceTicks(), cmd.quantitySteps(), 0, cmd.quantitySteps(),
                            cmd.reduceOnly(), cmd.marginMode(), cmd.positionSide(), cmd.orderType(), cmd.timeInForce(),
                            cmd.postOnly(), cmd.clientOrderId(), UUID.randomUUID(), 100, 200,
                            Instant.parse("2026-08-02T00:00:00Z").toEpochMilli(),
                            Instant.parse("2026-08-02T00:00:00Z").toEpochMilli(), 1, "OPEN", 1);
                    return new CoreCommandOutcome.Terminal(new CoreResponse(ResponseStatus.APPLIED, ResponseStatus.APPLIED,
                            CoreResultCode.NONE, 1L, CoreCommandResultCodec.encode(new CoreCommandResultView(List.of(order), List.of()))));
                });
    }

    private void stubCancelBatches(ProductLine line) {
        when(aeron.commandOutcome(eq(CoreMessageType.CANCEL_ORDER_BATCH), any(UUID.class), anyLong(), any(byte[].class)))
                .thenAnswer(invocation -> {
                    CancelOrderBatchCommand batch = TradingOrderBatchCodec.decodeCancelOrderBatch(invocation.getArgument(3));
                    List<CoreOrderBatchResult.Item> items = new java.util.ArrayList<>(batch.items().size());
                    for (int index = 0; index < batch.items().size(); index++) {
                        long orderId = batch.items().get(index).orderId();
                        CoreOrderStateView canceled = view(response(orderId, "client-" + orderId, OrderStatus.CANCELED));
                        items.add(new CoreOrderBatchResult.Item(index, orderId, 0L, 0L,
                                ResponseStatus.APPLIED, CoreResultCode.NONE, canceled, List.of()));
                    }
                    return new CoreCommandOutcome.Terminal(new CoreResponse(
                            ResponseStatus.APPLIED, ResponseStatus.APPLIED, CoreResultCode.NONE,
                            1L, TradingOrderBatchCodec.encodeResult(new CoreOrderBatchResult(items))));
                });
    }
}
