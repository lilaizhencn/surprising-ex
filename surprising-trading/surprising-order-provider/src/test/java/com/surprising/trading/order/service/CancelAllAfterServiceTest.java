package com.surprising.trading.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class CancelAllAfterServiceTest {

    @Test
    void zeroCountdownDisablesTimerInLocalState() throws Exception {
        CancelAllAfterLocalStateStore store = store();
        CancelAllAfterService service = service(ProductLine.LINEAR_DELIVERY, store,
                mock(OrderService.class), mock(TriggerOrderRpcApi.class));

        var response = service.set(new CancelAllAfterRequest(1001L, "btc-usdt", 0L));

        assertThat(response.active()).isFalse();
        assertThat(response.symbol()).isEqualTo("BTC-USDT");
        assertThat(response.triggerAt()).isNull();
    }

    @Test
    void dueTimerCancelsOpenOrdersAndTriggerOrders() throws Exception {
        CancelAllAfterLocalStateStore store = store();
        OrderService orderService = mock(OrderService.class);
        TriggerOrderRpcApi triggerOrderRpcApi = mock(TriggerOrderRpcApi.class);
        CancelAllAfterService service = service(ProductLine.OPTION, store, orderService, triggerOrderRpcApi);
        Instant now = Instant.now();
        store.upsert(ProductLine.OPTION, 1001L, "BTC-USDT", 1000L, now.minusMillis(1), "TRIGGERING", now);
        when(orderService.cancelOpenOrders(any())).thenReturn(new OrderBatchResponse(2, 2, 0, List.of()));
        when(triggerOrderRpcApi.cancelOpen(any())).thenReturn(new TriggerOrderBatchResponse(1, 1, 0, List.of()));

        service.cancelDueTimer(new com.surprising.trading.order.model.CancelAllAfterTimer(
                1001L, "BTC-USDT", 1000L, "TRIGGERING", now.minusMillis(1), now, 0, 0));

        verify(orderService).cancelOpenOrders(any(CancelOpenOrdersRequest.class));
        verify(triggerOrderRpcApi).cancelOpen(any(CancelOpenTriggerOrdersRequest.class));
        assertThat(store.read(ProductLine.OPTION, 1001L, "BTC-USDT").orElseThrow().status())
                .isEqualTo("TRIGGERED");
    }

    @Test
    void scanDueTimersClaimsOnlyCurrentProductLine() throws Exception {
        CancelAllAfterLocalStateStore store = store();
        OrderService orderService = mock(OrderService.class);
        TriggerOrderRpcApi triggerOrderRpcApi = mock(TriggerOrderRpcApi.class);
        CancelAllAfterService service = service(ProductLine.LINEAR_DELIVERY, store, orderService, triggerOrderRpcApi);
        Instant now = Instant.now();
        store.upsert(ProductLine.LINEAR_DELIVERY, 1001L, "BTC-USDT", 1000L, now.minusMillis(1), "ACTIVE", now);
        when(orderService.cancelOpenOrders(any())).thenReturn(new OrderBatchResponse(0, 0, 0, List.of()));
        when(triggerOrderRpcApi.cancelOpen(any())).thenReturn(new TriggerOrderBatchResponse(0, 0, 0, List.of()));

        service.scanDueTimers();

        assertThat(store.read(ProductLine.LINEAR_DELIVERY, 1001L, "BTC-USDT").orElseThrow().status())
                .isEqualTo("TRIGGERED");
    }

    private CancelAllAfterService service(ProductLine line,
                                          CancelAllAfterLocalStateStore store,
                                          OrderService orderService,
                                          TriggerOrderRpcApi triggerOrderRpcApi) {
        TradingOrderProperties properties = new TradingOrderProperties();
        properties.getKafka().setProductLine(line);
        return new CancelAllAfterService(properties, orderService, triggerOrderRpcApi, store,
                OrderScheduleIndex.disabled());
    }

    private CancelAllAfterLocalStateStore store() throws Exception {
        return new CancelAllAfterLocalStateStore(Files.createTempDirectory("cancel-all-after-test"),
                new ObjectMapper());
    }
}
