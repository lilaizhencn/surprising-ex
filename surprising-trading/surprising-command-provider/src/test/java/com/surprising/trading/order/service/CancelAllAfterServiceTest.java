package com.surprising.trading.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.trading.api.model.CancelAllAfterRequest;
import com.surprising.trading.api.model.CancelOpenOrdersRequest;
import com.surprising.trading.api.model.CancelOpenTriggerOrdersRequest;
import com.surprising.trading.api.model.OrderBatchResponse;
import com.surprising.trading.api.model.TriggerOrderBatchResponse;
import com.surprising.trading.order.model.CancelAllAfterTimer;
import com.surprising.trading.trigger.service.TriggerOrderService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CancelAllAfterServiceTest {

    @Test
    void zeroCountdownDisablesAuthoritativeTimer() {
        AeronCancelAllAfterStore store = mock(AeronCancelAllAfterStore.class);
        CancelAllAfterService service = service(store, mock(OrderService.class), mock(TriggerOrderService.class));
        when(store.set(org.mockito.ArgumentMatchers.eq(1001L), org.mockito.ArgumentMatchers.eq("BTC-USDT"),
                org.mockito.ArgumentMatchers.eq(0L), org.mockito.ArgumentMatchers.isNull(), any()))
                .thenReturn(timer("DISABLED", null));

        var response = service.set(new CancelAllAfterRequest(1001L, "btc-usdt", 0L));

        assertThat(response.active()).isFalse();
        assertThat(response.symbol()).isEqualTo("BTC-USDT");
        assertThat(response.triggerAt()).isNull();
    }

    @Test
    void dueTimerCancelsBothOrderKindsAndCompletesInAeron() {
        AeronCancelAllAfterStore store = mock(AeronCancelAllAfterStore.class);
        OrderService orderService = mock(OrderService.class);
        TriggerOrderService triggerOrderService = mock(TriggerOrderService.class);
        CancelAllAfterService service = service(store, orderService, triggerOrderService);
        CancelAllAfterTimer claimed = timer("TRIGGERING", Instant.now().minusMillis(1));
        when(orderService.cancelOpenOrders(any())).thenReturn(new OrderBatchResponse(2, 2, 0, List.of()));
        when(triggerOrderService.cancelOpenOrders(any())).thenReturn(new TriggerOrderBatchResponse(1, 1, 0, List.of()));

        service.cancelDueTimer(claimed);

        verify(orderService).cancelOpenOrders(any(CancelOpenOrdersRequest.class));
        verify(triggerOrderService).cancelOpenOrders(any(CancelOpenTriggerOrdersRequest.class));
        verify(store).complete(org.mockito.ArgumentMatchers.eq(claimed),
                org.mockito.ArgumentMatchers.eq(2), org.mockito.ArgumentMatchers.eq(1), any());
    }

    @Test
    void scanClaimsAeronDueTimerBeforeExecuting() {
        AeronCancelAllAfterStore store = mock(AeronCancelAllAfterStore.class);
        OrderService orderService = mock(OrderService.class);
        TriggerOrderService triggerOrderService = mock(TriggerOrderService.class);
        CancelAllAfterService service = service(store, orderService, triggerOrderService);
        CancelAllAfterTimer active = timer("ACTIVE", Instant.now().minusMillis(1));
        CancelAllAfterTimer claimed = timer("TRIGGERING", active.triggerAt());
        when(store.due(any(), org.mockito.ArgumentMatchers.eq(100))).thenReturn(List.of(active));
        when(store.claim(org.mockito.ArgumentMatchers.eq(active), any())).thenReturn(Optional.of(claimed));
        when(orderService.cancelOpenOrders(any())).thenReturn(new OrderBatchResponse(0, 0, 0, List.of()));
        when(triggerOrderService.cancelOpenOrders(any())).thenReturn(new TriggerOrderBatchResponse(0, 0, 0, List.of()));

        service.scanDueTimers();

        verify(store).claim(org.mockito.ArgumentMatchers.eq(active), any());
        verify(store).complete(org.mockito.ArgumentMatchers.eq(claimed),
                org.mockito.ArgumentMatchers.eq(0), org.mockito.ArgumentMatchers.eq(0), any());
    }

    private CancelAllAfterService service(AeronCancelAllAfterStore store, OrderService orderService,
                                          TriggerOrderService triggerOrderService) {
        return new CancelAllAfterService(orderService, triggerOrderService, store);
    }

    private CancelAllAfterTimer timer(String status, Instant triggerAt) {
        return new CancelAllAfterTimer(1001L, "BTC-USDT", "DISABLED".equals(status) ? 0 : 1000,
                status, triggerAt, Instant.now(), 0, 0);
    }
}
