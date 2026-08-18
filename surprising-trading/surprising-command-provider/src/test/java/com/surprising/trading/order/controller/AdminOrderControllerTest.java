package com.surprising.trading.order.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.surprising.trading.order.repository.ProjectionReadResult;
import com.surprising.trading.order.service.OrderService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class AdminOrderControllerTest {

    @Test
    void oversizedAdminProjectionReadIsTypedAndCarriesContinuationCursor() {
        OrderService orderService = mock(OrderService.class);
        String continuation = "eyJvcmRlciI6MTIzfQ";
        when(orderService.adminOrders(null, null, null, null, 100, null, null, null))
                .thenThrow(new ProjectionReadResult.ResponseTooLargeException(12L, 0L, continuation));
        AdminOrderController controller = new AdminOrderController(orderService);

        assertThatThrownBy(() -> controller.orders("admin-1", null, null, null, null, null, null,
                100, null, null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(throwable -> {
                    ResponseStatusException response = (ResponseStatusException) throwable;
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
                    assertThat(response.getReason()).contains("PROJECTION_RESPONSE_TOO_LARGE", continuation);
                });
    }
}
