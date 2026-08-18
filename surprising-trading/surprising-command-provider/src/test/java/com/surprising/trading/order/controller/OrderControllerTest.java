package com.surprising.trading.order.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

class OrderControllerTest {

    @Test
    void oversizedProjectionResponseIsTypedAndCarriesContinuationCursor() {
        OrderService orderService = mock(OrderService.class);
        String continuation = "eyJvcmRlciI6MTIzfQ";
        when(orderService.openOrders(1001L, "BTC-USDT", 10, null, null))
                .thenThrow(new ProjectionReadResult.ResponseTooLargeException(12L, 0L, continuation));
        OrderController controller = new OrderController(orderService, mock(AlgoOrderService.class),
                mock(CancelAllAfterService.class));

        assertThatThrownBy(() -> controller.openOrders(1001L, "BTC-USDT", 10, null, null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(throwable -> {
                    ResponseStatusException response = (ResponseStatusException) throwable;
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
                    assertThat(response.getReason()).contains("PROJECTION_RESPONSE_TOO_LARGE", continuation);
                });
    }

    @Test
    void mapsConflictBackpressureAndUnknownSeparately() {
        OrderService orderService = mock(OrderService.class);
        OrderController controller = new OrderController(orderService, mock(AlgoOrderService.class),
                mock(CancelAllAfterService.class));
        PlaceOrderRequest request = new PlaceOrderRequest(1001L, "client-1", "BTC-USDT", OrderSide.BUY,
                OrderType.LIMIT, TimeInForce.GTC, 60_000L, 1L, false, false);

        when(orderService.placeCommand(any())).thenReturn(receipt("TERMINAL", "NONE"));
        assertThat(controller.place(request).getStatusCode()).isEqualTo(HttpStatus.OK);

        when(orderService.placeCommand(any())).thenReturn(receipt("TERMINAL", "IDEMPOTENCY_CONFLICT"));
        assertThat(controller.place(request).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(controller.place(request).getBody().code()).isEqualTo("IDEMPOTENCY_CONFLICT");

        when(orderService.placeCommand(any())).thenReturn(receipt("NOT_ACCEPTED", "CLIENT_BACKPRESSURED"));
        ResponseEntity<OrderCommandReceipt> backpressure = controller.place(request);
        assertThat(backpressure.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(backpressure.getBody().commandResultUrl()).isNull();

        when(orderService.placeCommand(any())).thenReturn(receipt("RESULT_UNKNOWN", "RESULT_UNKNOWN"));
        ResponseEntity<OrderCommandReceipt> unknown = controller.place(request);
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(unknown.getBody().commandResultUrl()).isNotBlank();

        when(orderService.placeCommand(any())).thenReturn(receipt("NOT_ACCEPTED", "NOT_CONNECTED"));
        assertThat(controller.place(request).getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN_ACTION", "CLOSED", "MAX_POSITION_EXCEEDED", "UNKNOWN"})
    void mapsEveryRawAdmissionFailureTo503(String code) {
        OrderService orderService = mock(OrderService.class);
        when(orderService.placeCommand(any())).thenReturn(receipt("NOT_ACCEPTED", code));
        OrderController controller = new OrderController(orderService, mock(AlgoOrderService.class),
                mock(CancelAllAfterService.class));

        ResponseEntity<OrderCommandReceipt> response = controller.place(new PlaceOrderRequest(
                1001L, "client-1", "BTC-USDT", OrderSide.BUY, OrderType.LIMIT, TimeInForce.GTC,
                60_000L, 1L, false, false));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody().code()).isEqualTo(code);
        assertThat(response.getBody().commandResultUrl()).isNull();
    }

    @Test
    void commandResultEndpointDistinguishesKnownPendingAndOutsideRetention() {
        OrderService orderService = mock(OrderService.class);
        OrderController controller = new OrderController(orderService, mock(AlgoOrderService.class),
                mock(CancelAllAfterService.class));
        UUID commandId = UUID.fromString("11111111-1111-1111-1111-111111111111");

        when(orderService.commandResult(commandId)).thenReturn(receipt("TERMINAL", "NONE"));
        assertThat(controller.commandResult(commandId).getStatusCode()).isEqualTo(HttpStatus.OK);

        when(orderService.commandResult(commandId)).thenReturn(receipt("RESULT_UNKNOWN", "RESULT_UNKNOWN"));
        assertThat(controller.commandResult(commandId).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        when(orderService.commandResult(commandId)).thenReturn(
                receipt("OUTSIDE_RETENTION", "RESULT_UNKNOWN_OUTSIDE_RETENTION"));
        ResponseEntity<OrderCommandReceipt> outside = controller.commandResult(commandId);
        assertThat(outside.getStatusCode()).isEqualTo(HttpStatus.GONE);
        assertThat(outside.getBody().code()).isEqualTo("RESULT_UNKNOWN_OUTSIDE_RETENTION");
    }

    @Test
    void mapsMatchingPendingAdmissionAndQueryToAcceptedWithOriginalCommandIdentity() {
        OrderService orderService = mock(OrderService.class);
        OrderController controller = new OrderController(orderService, mock(AlgoOrderService.class),
                mock(CancelAllAfterService.class));
        PlaceOrderRequest request = new PlaceOrderRequest(1001L, "client-pending", "BTC-USDT", OrderSide.BUY,
                OrderType.LIMIT, TimeInForce.GTC, 60_000L, 1L, false, false);
        UUID commandId = UUID.fromString("33333333-3333-3333-3333-333333333333");
        OrderCommandReceipt pending = receipt("MATCHING_PENDING", "MATCHING_PENDING", commandId);

        when(orderService.placeCommand(any())).thenReturn(pending);
        ResponseEntity<OrderCommandReceipt> initial = controller.place(request);

        when(orderService.commandResult(commandId)).thenReturn(pending);
        ResponseEntity<OrderCommandReceipt> queried = controller.commandResult(commandId);

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

    private static OrderCommandReceipt receipt(String outcome, String code, UUID commandId) {
        return new OrderCommandReceipt(commandId, outcome, code, code,
                "RESULT_UNKNOWN".equals(outcome) || "TERMINAL".equals(outcome)
                        || "MATCHING_PENDING".equals(outcome)
                        ? OrderCommandReceipt.commandResultUrl(commandId) : null,
                List.of(91L), "TERMINAL".equals(outcome) ? 17L : null, null, null);
    }
}
