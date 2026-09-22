package com.surprising.trading.trigger.controller;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.surprising.trading.api.model.BatchPlaceTriggerOrderRequest;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.OrderType;
import com.surprising.trading.api.model.PlaceTriggerOrderRequest;
import com.surprising.trading.api.model.TimeInForce;
import com.surprising.trading.api.model.TriggerOrderType;
import com.surprising.trading.trigger.service.TriggerOrderService;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class TriggerOrderControllerTest {

    @Test
    void mapsPlacementStateRejectionToConflict() {
        TriggerOrderService service = mock(TriggerOrderService.class);
        when(service.placeAsync(any(PlaceTriggerOrderRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(
                        new IllegalStateException("trigger placement rejected")));
        TriggerOrderController controller = new TriggerOrderController(service);

        assertStatus(() -> controller.place(request()).toCompletableFuture().join(), HttpStatus.CONFLICT);
    }

    @Test
    void mapsBatchPlacementStateRejectionToConflict() {
        TriggerOrderService service = mock(TriggerOrderService.class);
        when(service.placeBatchAsync(any(BatchPlaceTriggerOrderRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(
                        new IllegalStateException("trigger batch placement rejected")));
        TriggerOrderController controller = new TriggerOrderController(service);

        assertStatus(() -> controller.placeBatch(new BatchPlaceTriggerOrderRequest(java.util.List.of(request())))
                        .toCompletableFuture().join(),
                HttpStatus.CONFLICT);
    }

    @Test
    void keepsPlacementValidationRejectionAsBadRequest() {
        TriggerOrderService service = mock(TriggerOrderService.class);
        when(service.placeAsync(any(PlaceTriggerOrderRequest.class)))
                .thenThrow(new IllegalArgumentException("invalid trigger placement"));
        TriggerOrderController controller = new TriggerOrderController(service);

        assertStatus(() -> controller.place(request()), HttpStatus.BAD_REQUEST);
    }

    @Test
    void keepsBatchPlacementValidationRejectionAsBadRequest() {
        TriggerOrderService service = mock(TriggerOrderService.class);
        when(service.placeBatchAsync(any(BatchPlaceTriggerOrderRequest.class)))
                .thenThrow(new IllegalArgumentException("invalid trigger batch placement"));
        TriggerOrderController controller = new TriggerOrderController(service);

        assertStatus(() -> controller.placeBatch(new BatchPlaceTriggerOrderRequest(java.util.List.of(request()))),
                HttpStatus.BAD_REQUEST);
    }

    private static void assertStatus(Runnable invocation, HttpStatus expectedStatus) {
        assertThatThrownBy(invocation::run)
                .satisfies(throwable -> {
                    Throwable current = throwable;
                    while (!(current instanceof ResponseStatusException) && current.getCause() != null) {
                        current = current.getCause();
                    }
                    org.assertj.core.api.Assertions.assertThat(current)
                            .isInstanceOf(ResponseStatusException.class);
                    ResponseStatusException response = (ResponseStatusException) current;
                    org.assertj.core.api.Assertions.assertThat(response.getStatusCode()).isEqualTo(expectedStatus);
                });
    }

    private static PlaceTriggerOrderRequest request() {
        return new PlaceTriggerOrderRequest(1001L, "client-trigger-1", null, "BTC-USDT", OrderSide.BUY,
                TriggerOrderType.STOP_LOSS, 60_000L, OrderType.LIMIT, TimeInForce.GTC, 60_000L, 1L, null, null);
    }
}
