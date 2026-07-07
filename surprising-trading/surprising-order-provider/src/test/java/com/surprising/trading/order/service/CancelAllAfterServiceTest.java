package com.surprising.trading.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.client.TriggerOrderRpcApi;
import com.surprising.trading.api.model.CancelAllAfterRequest;
import com.surprising.trading.api.model.CancelOpenOrdersRequest;
import com.surprising.trading.api.model.CancelOpenTriggerOrdersRequest;
import com.surprising.trading.api.model.OrderBatchResponse;
import com.surprising.trading.api.model.TriggerOrderBatchResponse;
import com.surprising.trading.order.config.TradingOrderProperties;
import com.surprising.trading.order.model.CancelAllAfterTimer;
import com.surprising.trading.order.repository.CancelAllAfterRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CancelAllAfterServiceTest {

    @Test
    void zeroCountdownDisablesTimer() {
        CancelAllAfterRepository repository = mock(CancelAllAfterRepository.class);
        CancelAllAfterService service = new CancelAllAfterService(properties(ProductLine.LINEAR_DELIVERY), repository,
                mock(OrderService.class), mock(TriggerOrderRpcApi.class));
        when(repository.upsert(eq(ProductLine.LINEAR_DELIVERY), eq(1001L), eq("BTC-USDT"), eq(0L), isNull(),
                eq("DISABLED"), any(), any()))
                .thenReturn(new CancelAllAfterTimer(1001L, "BTC-USDT", 0L,
                        "DISABLED", null, Instant.parse("2026-07-01T00:00:00Z"), 0, 0));

        var response = service.set(new CancelAllAfterRequest(1001L, "btc-usdt", 0L));

        assertThat(response.active()).isFalse();
        assertThat(response.symbol()).isEqualTo("BTC-USDT");
        assertThat(response.triggerAt()).isNull();
    }

    @Test
    void dueTimerCancelsOpenOrdersAndTriggerOrders() {
        CancelAllAfterRepository repository = mock(CancelAllAfterRepository.class);
        OrderService orderService = mock(OrderService.class);
        TriggerOrderRpcApi triggerOrderRpcApi = mock(TriggerOrderRpcApi.class);
        CancelAllAfterService service = new CancelAllAfterService(properties(ProductLine.OPTION), repository,
                orderService, triggerOrderRpcApi);
        when(orderService.cancelOpenOrders(any())).thenReturn(new OrderBatchResponse(2, 2, 0, List.of()));
        when(triggerOrderRpcApi.cancelOpen(any())).thenReturn(new TriggerOrderBatchResponse(1, 1, 0, List.of()));

        service.cancelDueTimer(new CancelAllAfterTimer(1001L, "BTC-USDT", 1000L,
                "TRIGGERING", Instant.parse("2026-07-01T00:00:01Z"),
                Instant.parse("2026-07-01T00:00:01Z"), 0, 0));

        ArgumentCaptor<CancelOpenOrdersRequest> orderRequest = ArgumentCaptor.forClass(CancelOpenOrdersRequest.class);
        ArgumentCaptor<CancelOpenTriggerOrdersRequest> triggerRequest =
                ArgumentCaptor.forClass(CancelOpenTriggerOrdersRequest.class);
        verify(orderService).cancelOpenOrders(orderRequest.capture());
        verify(triggerOrderRpcApi).cancelOpen(triggerRequest.capture());
        assertThat(orderRequest.getValue().userId()).isEqualTo(1001L);
        assertThat(orderRequest.getValue().symbol()).isEqualTo("BTC-USDT");
        assertThat(triggerRequest.getValue().userId()).isEqualTo(1001L);
        assertThat(triggerRequest.getValue().symbol()).isEqualTo("BTC-USDT");
        verify(repository).markTriggered(eq(ProductLine.OPTION), eq(1001L), eq("BTC-USDT"), eq(2), eq(1), any());
    }

    @Test
    void scanDueTimersClaimsOnlyCurrentProductLine() {
        CancelAllAfterRepository repository = mock(CancelAllAfterRepository.class);
        CancelAllAfterService service = new CancelAllAfterService(properties(ProductLine.LINEAR_DELIVERY), repository,
                mock(OrderService.class), mock(TriggerOrderRpcApi.class));
        when(repository.claimDueTimers(eq(ProductLine.LINEAR_DELIVERY), any(), eq(100))).thenReturn(List.of());

        service.scanDueTimers();

        verify(repository).claimDueTimers(eq(ProductLine.LINEAR_DELIVERY), any(), eq(100));
    }

    private TradingOrderProperties properties(ProductLine productLine) {
        TradingOrderProperties properties = new TradingOrderProperties();
        properties.getKafka().setProductLine(productLine);
        return properties;
    }
}
