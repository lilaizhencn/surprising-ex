package com.surprising.trading.trigger.controller;

import com.surprising.trading.trigger.service.TriggerOrderAeronGateway;

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
import com.surprising.trading.order.controller.TradingController;
import com.surprising.trading.trigger.service.TriggerOrderService;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class TriggerOrderRequestHandlerTest {

    @Test
    void singlePlacementAndCancellationReadOriginalCommittedResultsWithoutCore() {
        var core = mock(TriggerOrderAeronGateway.class);
        var results = mock(com.surprising.trading.order.service.OrderService.class);
        for (var product : com.surprising.product.api.ProductLine.values()) {
            var properties = new com.surprising.trading.trigger.config.TriggerProperties();
            properties.setProductLine(product);
            var service = new TriggerOrderService(properties, core, null, null);
            var requests = new TradingController(results, null, null, service, null, null, null);
            var placement = service.submissionCommandId(1001, " original-single ");
            org.assertj.core.api.Assertions.assertThat(placement).isEqualTo(
                    com.surprising.trading.order.service.StableOrderIdentity.triggerCommandId(product, 1001, "original-single"));
            var cancellation = TriggerOrderAeronGateway.cancellationCommandId(1001, Long.MAX_VALUE);
            org.assertj.core.api.Assertions.assertThat(cancellation).isEqualTo(java.util.UUID.nameUUIDFromBytes(
                    ("TRIGGER_CANCEL:1001:" + Long.MAX_VALUE).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            for (var command : java.util.List.of(placement, cancellation)) {
                var receipt = new com.surprising.trading.api.model.OrderCommandReceipt(command,
                        "RESULT_UNKNOWN", "RESULT_UNKNOWN", "pending", null, java.util.List.of(), null, null);
                when(results.commandResult(1001, command)).thenReturn(receipt);
                org.assertj.core.api.Assertions.assertThat(command.equals(placement)
                        ? requests.triggerSubmission(1001L, null, null, " original-single ")
                        : requests.triggerCancellation(1001L, null, null, Long.MAX_VALUE))
                        .isSameAs(receipt);
            }
        }
        org.mockito.Mockito.verifyNoInteractions(core);
    }

    @Test
    void invalidSingleTriggerIdentitiesCannotReadAReceipt() {
        var properties = new com.surprising.trading.trigger.config.TriggerProperties();
        properties.setProductLine(com.surprising.product.api.ProductLine.OPTION);
        var core = mock(TriggerOrderAeronGateway.class);
        var results = mock(com.surprising.trading.order.service.OrderService.class);
        var requests = new TradingController(results, null, null, new TriggerOrderService(properties, core, null, null), null, null, null);
        for (String key : java.util.List.of("", " ", "x".repeat(65)))
            assertStatus(() -> requests.triggerSubmission(1001L, null, null, key), HttpStatus.BAD_REQUEST);
        assertStatus(() -> requests.triggerSubmission(null, "0", null, "original"), HttpStatus.BAD_REQUEST);
        assertStatus(() -> requests.triggerCancellation(null, "0", null, 99), HttpStatus.BAD_REQUEST);
        assertStatus(() -> requests.triggerCancellation(1001L, null, null, 0), HttpStatus.BAD_REQUEST);
        org.mockito.Mockito.verifyNoInteractions(core, results);
    }

    @Test
    void ocoSubmissionReadsCommittedResultWithoutSendingOrQueryingCore() {
        var properties = new com.surprising.trading.trigger.config.TriggerProperties();
        properties.setProductLine(com.surprising.product.api.ProductLine.OPTION);
        var core = mock(TriggerOrderAeronGateway.class);
        var service = new TriggerOrderService(properties, core, null, null);
        var results = mock(com.surprising.trading.order.service.OrderService.class);
        var command = service.ocoCommandId(1001L, "tp-original", "sl-original");
        var receipt = new com.surprising.trading.api.model.OrderCommandReceipt(command,
                "RESULT_UNKNOWN", "RESULT_UNKNOWN", "pending", null, java.util.List.of(), null, null);
        when(results.commandResult(1001L, command)).thenReturn(receipt);
        org.assertj.core.api.Assertions.assertThat(new TradingController(results, null, null, service, null, null, null)
                .triggerOcoSubmission(1001L, null, null, "tp-original", "sl-original")).isSameAs(receipt);
        org.mockito.Mockito.verify(results).commandResult(1001L, command);
        org.mockito.Mockito.verifyNoInteractions(core);
    }

    @Test
    void invalidOcoLookupCannotReadAnotherCommand() {
        var properties = new com.surprising.trading.trigger.config.TriggerProperties();
        properties.setProductLine(com.surprising.product.api.ProductLine.OPTION);
        var core = mock(TriggerOrderAeronGateway.class);
        var results = mock(com.surprising.trading.order.service.OrderService.class);
        var requests = new TradingController(results, null, null, new TriggerOrderService(properties, core, null, null), null, null, null);
        assertStatus(() -> requests.triggerOcoSubmission(1001L, null, null, "tp", "tp"), HttpStatus.BAD_REQUEST);
        assertStatus(() -> requests.triggerOcoSubmission(null, "0", null, "tp", "sl"), HttpStatus.BAD_REQUEST);
        org.mockito.Mockito.verifyNoInteractions(core, results);
    }

    @Test
    void mapsPlacementStateRejectionToConflict() {
        TriggerOrderService service = mock(TriggerOrderService.class);
        when(service.placeAsync(any(PlaceTriggerOrderRequest.class))).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("trigger placement rejected")));
        TradingController requests = new TradingController(null, null, null, service, null, null, null);
        assertStatus(() -> requests.placeTrigger(request()).toCompletableFuture().join(), HttpStatus.CONFLICT);
    }

    @Test
    void mapsBatchPlacementStateRejectionToConflict() {
        TriggerOrderService service = mock(TriggerOrderService.class);
        when(service.placeBatchAsync(any(BatchPlaceTriggerOrderRequest.class))).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("trigger batch placement rejected")));
        TradingController requests = new TradingController(null, null, null, service, null, null, null);
        assertStatus(() -> requests.placeTriggerBatch(new BatchPlaceTriggerOrderRequest(java.util.List.of(request()))).toCompletableFuture().join(), HttpStatus.CONFLICT);
    }

    @Test
    void keepsPlacementValidationRejectionAsBadRequest() {
        TriggerOrderService service = mock(TriggerOrderService.class);
        when(service.placeAsync(any(PlaceTriggerOrderRequest.class))).thenThrow(new IllegalArgumentException("invalid trigger placement"));
        TradingController requests = new TradingController(null, null, null, service, null, null, null);
        assertStatus(() -> requests.placeTrigger(request()), HttpStatus.BAD_REQUEST);
    }

    @Test
    void keepsBatchPlacementValidationRejectionAsBadRequest() {
        TriggerOrderService service = mock(TriggerOrderService.class);
        when(service.placeBatchAsync(any(BatchPlaceTriggerOrderRequest.class))).thenThrow(new IllegalArgumentException("invalid trigger batch placement"));
        TradingController requests = new TradingController(null, null, null, service, null, null, null);
        assertStatus(() -> requests.placeTriggerBatch(new BatchPlaceTriggerOrderRequest(java.util.List.of(request()))), HttpStatus.BAD_REQUEST);
    }

    private static void assertStatus(Runnable invocation, HttpStatus expectedStatus) {
        assertThatThrownBy(invocation::run).satisfies(throwable -> {
            Throwable current = throwable;
            while (!(current instanceof ResponseStatusException) && current.getCause() != null) {
                current = current.getCause();
            }
            org.assertj.core.api.Assertions.assertThat(current).isInstanceOf(ResponseStatusException.class);
            ResponseStatusException response = (ResponseStatusException) current;
            org.assertj.core.api.Assertions.assertThat(response.getStatusCode()).isEqualTo(expectedStatus);
        });
    }

    private static PlaceTriggerOrderRequest request() {
        return new PlaceTriggerOrderRequest(1001L, "client-trigger-1", null, "1", OrderSide.BUY, TriggerOrderType.STOP_LOSS, 60_000L, OrderType.LIMIT, TimeInForce.GTC, 60_000L, 1L, null, null);
    }
}
