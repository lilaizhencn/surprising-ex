package com.surprising.trading.order.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.surprising.trading.order.repository.ProjectionReadResult;
import com.surprising.trading.order.service.AlgoOrderService;
import com.surprising.trading.order.service.CancelAllAfterService;
import com.surprising.trading.order.service.OrderService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
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
}
