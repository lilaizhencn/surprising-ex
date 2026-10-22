package com.surprising.trading.order.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import com.surprising.trading.api.model.OrderCommandReceipt;
import com.surprising.trading.api.model.PlaceOrderRequest;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.OrderType;
import com.surprising.trading.api.model.TimeInForce;
import com.surprising.trading.order.repository.ProjectionReadResult;
import com.surprising.trading.order.service.AlgoOrderService;
import com.surprising.trading.order.service.CancelAllAfterService;
import com.surprising.trading.order.service.OrderService;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

class OrderControllerTest {
    @Test void fillQueryBindsExactCursorCoordinatesAndRejectsInvalidInputsBeforeBusinessRead() {
        var service=mock(OrderService.class);
        var requests=new OrderRequestHandler(service,null,null);
        var line=com.surprising.product.api.ProductLine.OPTION;
        when(service.currentProductLine()).thenReturn(line);
        var after=new com.surprising.trading.order.model.TradeReadCursor.Fill(9007199254740993L,Long.MAX_VALUE,Integer.MAX_VALUE,"MAKER");
        String scope=com.surprising.trading.order.utils.TradeReadCursors.fillScope(line,7,"604",Long.MAX_VALUE,1L,Long.MAX_VALUE);
        String cursor=com.surprising.trading.order.utils.TradeReadCursors.encode(scope,after);
        requests.ownFills(7," 604 ",Long.MAX_VALUE,1L,Long.MAX_VALUE,10,cursor);
        org.mockito.Mockito.verify(service).ownFills(7,"604",Long.MAX_VALUE,1L,Long.MAX_VALUE,10,after);
        org.mockito.Mockito.clearInvocations(service);
        for (String bad:List.of("garbage",cursor+"!")) {
            assertThatThrownBy(()->requests.ownFills(7,"604",Long.MAX_VALUE,1L,Long.MAX_VALUE,10,bad))
                    .isInstanceOfSatisfying(ResponseStatusException.class, failure->assertThat(failure.getStatusCode().value()).isEqualTo(400));
        }
        assertThatThrownBy(()->requests.ownFills(7,null,null,2L,1L,10,null)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->requests.ownFills(0,null,null,null,null,10,null)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->requests.ownFills(7,"BTC-USDT",null,null,null,10,null)).isInstanceOf(ResponseStatusException.class);
        org.mockito.Mockito.verify(service,org.mockito.Mockito.never()).ownFills(org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyInt(),org.mockito.ArgumentMatchers.any());
    }

    @Test void malformedAlgoQueriesAreRejectedBeforeAnyBusinessCall() {
        var algo=mock(AlgoOrderService.class);
        var requests=new OrderRequestHandler(mock(OrderService.class),algo,mock(CancelAllAfterService.class));
        for (int[] values:new int[][]{{0,10},{7,0},{7,1001}}) {
            assertThatThrownBy(()->requests.openAlgoOrders(values[0],null,values[1])).isInstanceOfSatisfying(ResponseStatusException.class,
                    failure->assertThat(failure.getStatusCode().value()).isEqualTo(400));
        }
        assertThatThrownBy(()->requests.openAlgoOrders(7,"BTC-USDT",10)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->requests.getAlgo(7,0)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->requests.getAlgo(0,99)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->requests.algoSubmission(7," ")).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->requests.algoSubmission(7,"x".repeat(65))).isInstanceOf(ResponseStatusException.class);
        org.mockito.Mockito.verifyNoInteractions(algo);
    }

    @Test void nativeMissingAlgoRemains404AtTheActualInternalHttpBoundary() throws Exception {
        var algo=mock(AlgoOrderService.class);
        when(algo.get(7,99)).thenThrow(new java.util.NoSuchElementException("algo order is not present in the committed read view"));
        var requests=new OrderRequestHandler(mock(OrderService.class),algo,mock(CancelAllAfterService.class));
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new OrderInternalController(requests)).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/trading/orders/algo/99").param("userId","7"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());
    }

    @Test void cancellationPlanAndBatchAreReadOnlyUncachedAndReportUnavailability() {
        var service = mock(OrderService.class);
        var controller = new OrderInternalController(new com.surprising.trading.order.controller.OrderRequestHandler(
                service, mock(AlgoOrderService.class), mock(CancelAllAfterService.class)));
        var plan = new com.surprising.trading.api.model.CancellationPlanResponse(1001,
                com.surprising.product.api.ProductLine.SPOT, "1", List.of(91L));
        when(service.cancellationPlan(1001, "1")).thenReturn(plan);
        assertThat(controller.cancellationPlan(1001, "1").getBody()).isEqualTo(plan);
        assertThat(controller.cancellationPlan(1001, "1").getHeaders().getCacheControl()).isEqualTo("no-store");
        for (var outcome : List.of("RESULT_UNKNOWN", "TERMINAL")) {
            when(service.cancellationBatch(1001, "batch-1")).thenReturn(receipt(outcome, outcome.equals("TERMINAL") ? "NONE" : outcome));
            var response = controller.cancellationBatch(1001, "batch-1");
            assertThat(response.getStatusCode()).isEqualTo(outcome.equals("TERMINAL") ? HttpStatus.OK : HttpStatus.ACCEPTED);
            assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        }
        when(service.cancellationBatch(1001, "batch-1")).thenReturn(receipt("TERMINAL", "ORDER_OWNER_MISMATCH"));
        assertThat(controller.cancellationBatch(1001, "batch-1").getStatusCode()).isEqualTo(HttpStatus.OK);
        doThrow(new IllegalArgumentException("batchKey required")).when(service).cancellationBatch(1001, "");
        assertThatThrownBy(() -> controller.cancellationBatch(1001, "")).isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException)error).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("offline")).when(service).cancellationBatch(1001, "batch-1");
        assertThatThrownBy(() -> controller.cancellationBatch(1001, "batch-1")).isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException)error).getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }

    @Test void amendmentLookupReturnsTerminalUnknownAndUnavailableWithoutCaching() {
        var service = mock(OrderService.class);
        var controller = new OrderInternalController(new com.surprising.trading.order.controller.OrderRequestHandler(
                service, mock(AlgoOrderService.class), mock(CancelAllAfterService.class)));
        when(service.amendment(1001, "edit-1")).thenReturn(receipt("TERMINAL", "NONE"));
        assertThat(controller.amendment(1001, "edit-1").getStatusCode()).isEqualTo(HttpStatus.OK);
        when(service.amendment(1001, "edit-1")).thenReturn(receipt("RESULT_UNKNOWN", "RESULT_UNKNOWN"));
        assertThat(controller.amendment(1001, "edit-1").getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(controller.amendment(1001, "edit-1").getHeaders().getCacheControl()).isEqualTo("no-store");
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("offline")).when(service).amendment(1001, "edit-1");
        assertThatThrownBy(() -> controller.amendment(1001, "edit-1")).isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException)error).getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }

    @Test void cancellationLookupDistinguishesTerminalUnknownInvalidAndUnavailable() {
        var service = mock(OrderService.class);
        var controller = new OrderInternalController(new com.surprising.trading.order.controller.OrderRequestHandler(
                service, mock(AlgoOrderService.class), mock(CancelAllAfterService.class)));
        when(service.cancellation(1001, 91)).thenReturn(receipt("TERMINAL", "NONE"));
        assertThat(controller.cancellation(1001, 91).getStatusCode()).isEqualTo(HttpStatus.OK);
        when(service.cancellation(1001, 91)).thenReturn(receipt("RESULT_UNKNOWN", "RESULT_UNKNOWN"));
        assertThat(controller.cancellation(1001, 91).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(controller.cancellation(1001, 91).getHeaders().getCacheControl()).isEqualTo("no-store");
        when(service.cancellation(1001, 91)).thenThrow(new IllegalArgumentException("invalid order"));
        assertThatThrownBy(() -> controller.cancellation(1001, 91)).isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException)error).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("offline")).when(service).cancellation(1001, 91);
        assertThatThrownBy(() -> controller.cancellation(1001, 91)).isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException)error).getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }


    @Test
    void oversizedProjectionResponseIsTypedAndCarriesContinuationCursor() {
        OrderService orderService = mock(OrderService.class);
        String continuation = "eyJvcmRlciI6MTIzfQ";
        when(orderService.openOrders(1001L, "1", com.surprising.trading.order.controller.OrderRequestHandler.activePage(10, null), null)).thenThrow(new ProjectionReadResult.ResponseTooLargeException(12L, 0L, continuation));
        OrderInternalController controller = new OrderInternalController(new com.surprising.trading.order.controller.OrderRequestHandler(orderService, mock(AlgoOrderService.class), mock(CancelAllAfterService.class)));
        assertThatThrownBy(() -> controller.openOrders(1001L, "1", 10, null, null)).isInstanceOf(ResponseStatusException.class).satisfies(throwable -> {
            ResponseStatusException response = (ResponseStatusException) throwable;
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
            assertThat(response.getReason()).contains("PROJECTION_RESPONSE_TOO_LARGE", continuation);
        });
    }

    @Test
    void mapsConflictBackpressureAndUnknownSeparately() {
        OrderService orderService = mock(OrderService.class);
        OrderInternalController controller = new OrderInternalController(new com.surprising.trading.order.controller.OrderRequestHandler(orderService, mock(AlgoOrderService.class), mock(CancelAllAfterService.class)));
        PlaceOrderRequest request = new PlaceOrderRequest(1001L, "client-1", "1", OrderSide.BUY, OrderType.LIMIT, TimeInForce.GTC, 60_000L, 1L, false, false);
        when(orderService.placeCommandAsync(any())).thenReturn(completedReceipt("TERMINAL", "NONE"));
        assertThat(controller.place(request).toCompletableFuture().join().getStatusCode()).isEqualTo(HttpStatus.OK);
        when(orderService.placeCommandAsync(any())).thenReturn(completedReceipt("TERMINAL", "IDEMPOTENCY_CONFLICT"));
        assertThat(controller.place(request).toCompletableFuture().join().getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(controller.place(request).toCompletableFuture().join().getBody().code()).isEqualTo("IDEMPOTENCY_CONFLICT");
        when(orderService.placeCommandAsync(any())).thenReturn(completedReceipt("NOT_ACCEPTED", "CLIENT_BACKPRESSURED"));
        ResponseEntity<OrderCommandReceipt> backpressure = controller.place(request).toCompletableFuture().join();
        assertThat(backpressure.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(backpressure.getBody().commandResultUrl()).isNull();
        when(orderService.placeCommandAsync(any())).thenReturn(completedReceipt("RESULT_UNKNOWN", "RESULT_UNKNOWN"));
        ResponseEntity<OrderCommandReceipt> unknown = controller.place(request).toCompletableFuture().join();
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(unknown.getBody().commandResultUrl()).isNotBlank();
        when(orderService.placeCommandAsync(any())).thenReturn(completedReceipt("NOT_ACCEPTED", "NOT_CONNECTED"));
        assertThat(controller.place(request).toCompletableFuture().join().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void mapsAsynchronousPlacementStateFailureToConflict() {
        OrderService orderService = mock(OrderService.class);
        when(orderService.placeCommandAsync(any())).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("core rejected order")));
        OrderInternalController controller = new OrderInternalController(new com.surprising.trading.order.controller.OrderRequestHandler(orderService, mock(AlgoOrderService.class), mock(CancelAllAfterService.class)));
        assertThatThrownBy(() -> controller.place(new PlaceOrderRequest(1001L, "client-async-failure", "1", OrderSide.BUY, OrderType.LIMIT, TimeInForce.GTC, 60_000L, 1L, false, false)).toCompletableFuture().join()).satisfies(throwable -> assertThat(responseStatus(throwable).getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }

    @ParameterizedTest
    @ValueSource(strings = { "ADMIN_ACTION", "CLOSED", "MAX_POSITION_EXCEEDED", "UNKNOWN" })
    void mapsEveryRawAdmissionFailureTo503(String code) {
        OrderService orderService = mock(OrderService.class);
        when(orderService.placeCommandAsync(any())).thenReturn(completedReceipt("NOT_ACCEPTED", code));
        OrderInternalController controller = new OrderInternalController(new com.surprising.trading.order.controller.OrderRequestHandler(orderService, mock(AlgoOrderService.class), mock(CancelAllAfterService.class)));
        ResponseEntity<OrderCommandReceipt> response = controller.place(new PlaceOrderRequest(1001L, "client-1", "1", OrderSide.BUY, OrderType.LIMIT, TimeInForce.GTC, 60_000L, 1L, false, false)).toCompletableFuture().join();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody().code()).isEqualTo(code);
        assertThat(response.getBody().commandResultUrl()).isNull();
    }

    @Test
    void commandResultEndpointDistinguishesCommittedUnknownAndUnavailable() {
        OrderService orderService = mock(OrderService.class);
        OrderInternalController controller = new OrderInternalController(new com.surprising.trading.order.controller.OrderRequestHandler(orderService, mock(AlgoOrderService.class), mock(CancelAllAfterService.class)));
        UUID commandId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        when(orderService.commandResult(1001L, commandId)).thenReturn(receipt("TERMINAL", "NONE"));
        assertThat(controller.commandResult(1001L, commandId).getStatusCode()).isEqualTo(HttpStatus.OK);
        when(orderService.commandResult(1001L, commandId)).thenReturn(receipt("RESULT_UNKNOWN", "RESULT_UNKNOWN"));
        assertThat(controller.commandResult(1001L, commandId).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(controller.commandResult(1001L, commandId).getHeaders().getCacheControl()).isEqualTo("no-store");
        when(orderService.commandResult(1001L, commandId)).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("database unavailable"));
        assertThatThrownBy(() -> controller.commandResult(1001L, commandId)).isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException)error).getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }

    @Test
    void mapsMatchingPendingAdmissionAndQueryToAcceptedWithOriginalCommandIdentity() {
        OrderService orderService = mock(OrderService.class);
        OrderInternalController controller = new OrderInternalController(new com.surprising.trading.order.controller.OrderRequestHandler(orderService, mock(AlgoOrderService.class), mock(CancelAllAfterService.class)));
        PlaceOrderRequest request = new PlaceOrderRequest(1001L, "client-pending", "1", OrderSide.BUY, OrderType.LIMIT, TimeInForce.GTC, 60_000L, 1L, false, false);
        UUID commandId = UUID.fromString("33333333-3333-3333-3333-333333333333");
        OrderCommandReceipt pending = receipt("MATCHING_PENDING", "MATCHING_PENDING", commandId);
        when(orderService.placeCommandAsync(any())).thenReturn(CompletableFuture.completedFuture(pending));
        ResponseEntity<OrderCommandReceipt> initial = controller.place(request).toCompletableFuture().join();
        when(orderService.commandResult(1001L, commandId)).thenReturn(pending);
        ResponseEntity<OrderCommandReceipt> queried = controller.commandResult(1001L, commandId);
        assertThat(initial.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(initial.getBody().outcome()).isEqualTo("MATCHING_PENDING");
        assertThat(initial.getBody().commandId()).isEqualTo(commandId);
        assertThat(initial.getBody().commandResultUrl()).isEqualTo(OrderCommandReceipt.commandResultUrl(commandId));
        assertThat(queried.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(queried.getBody().outcome()).isEqualTo("MATCHING_PENDING");
        assertThat(queried.getBody().commandId()).isEqualTo(commandId);
        assertThat(queried.getBody().commandResultUrl()).isEqualTo(OrderCommandReceipt.commandResultUrl(commandId));
    }

    private static OrderCommandReceipt receipt(String outcome, String code) {
        return receipt(outcome, code, UUID.fromString("22222222-2222-2222-2222-222222222222"));
    }

    private static CompletableFuture<OrderCommandReceipt> completedReceipt(String outcome, String code) {
        return CompletableFuture.completedFuture(receipt(outcome, code));
    }

    private static ResponseStatusException responseStatus(Throwable throwable) {
        Throwable current = throwable;
        while (!(current instanceof ResponseStatusException) && current.getCause() != null) {
            current = current.getCause();
        }
        assertThat(current).isInstanceOf(ResponseStatusException.class);
        return (ResponseStatusException) current;
    }

    private static OrderCommandReceipt receipt(String outcome, String code, UUID commandId) {
        return new OrderCommandReceipt(commandId, outcome, code, code, "RESULT_UNKNOWN".equals(outcome) || "TERMINAL".equals(outcome) || "MATCHING_PENDING".equals(outcome) ? OrderCommandReceipt.commandResultUrl(commandId) : null, List.of(91L), null, null);
    }
}
