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

class AdminOrderRequestControllerTest {
    @Test void tradeHistoryRequiresAnAdminAndPreservesTheSelectedProductAndExactOrderId() {
        var service = mock(OrderService.class);
        var requests = new AdminOrderRequestController(service);
        assertThatThrownBy(() -> requests.trades(null,"OPTION",null,7L,"604",Long.MAX_VALUE,10,null,null))
                .isInstanceOf(ResponseStatusException.class);
        org.mockito.Mockito.verifyNoInteractions(service);
        var after = new com.surprising.trading.order.model.TradeReadCursor.Execution(1234L, Long.MAX_VALUE, Integer.MAX_VALUE);
        String scope = com.surprising.trading.order.utils.TradeReadCursors.executionScope(com.surprising.product.api.ProductLine.OPTION,7L,"604",Long.MAX_VALUE,true);
        String cursor = com.surprising.trading.order.utils.TradeReadCursors.encode(scope,after);
        requests.trades("admin-1","OPTION",null,7L,"604",Long.MAX_VALUE,10,cursor,"eventTime.asc");
        org.mockito.Mockito.verify(service).adminTrades(7L,"604",Long.MAX_VALUE,10,after,true,com.surprising.product.api.ProductLine.OPTION);
        assertThatThrownBy(() -> requests.trades("admin-1","OPTION",null,null,null,null,0,null,null))
                .isInstanceOfSatisfying(ResponseStatusException.class,e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void oversizedAdminProjectionReadIsTypedAndCarriesContinuationCursor() {
        OrderService orderService = mock(OrderService.class);
        String continuation = "eyJvcmRlciI6MTIzfQ";
        when(orderService.adminOrders(null, null, null, null, 100, null, null, null)).thenThrow(new ProjectionReadResult.ResponseTooLargeException(12L, 0L, continuation));
        com.surprising.trading.order.controller.AdminOrderRequestController requests = new com.surprising.trading.order.controller.AdminOrderRequestController(orderService);
        assertThatThrownBy(() -> requests.orders("admin-1", null, null, null, null, null, null, 100, null, null)).isInstanceOf(ResponseStatusException.class).satisfies(throwable -> {
            ResponseStatusException response = (ResponseStatusException) throwable;
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
            assertThat(response.getReason()).contains("PROJECTION_RESPONSE_TOO_LARGE", continuation);
        });
    }
    @org.junit.jupiter.api.Test void invalidStatisticsWindowAndLimitNeverReachBusinessService() {
        var orders=org.mockito.Mockito.mock(com.surprising.trading.order.service.OrderService.class);
        var requests=new AdminOrderRequestController(orders);
        for(int[] values:new int[][]{{120,20},{60,0},{60,101}}) {
            org.assertj.core.api.Assertions.assertThatThrownBy(()->requests.metrics("1",null,"SPOT",values[0],values[1]))
                    .isInstanceOfSatisfying(org.springframework.web.server.ResponseStatusException.class,
                            failure->org.assertj.core.api.Assertions.assertThat(failure.getStatusCode().value()).isEqualTo(400));
        }
        org.mockito.Mockito.verifyNoInteractions(orders);
    }
}
