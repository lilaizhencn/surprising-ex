package com.surprising.trading.order.controller;

import com.surprising.gateway.provider.product.GatewayUserContext;
import com.surprising.trading.api.model.BatchCancelTriggerOrdersRequest;
import com.surprising.trading.api.model.BatchPlaceTriggerOrderRequest;
import com.surprising.trading.api.model.CancelOpenTriggerOrdersRequest;
import com.surprising.trading.api.model.CancelTriggerOrderRequest;
import com.surprising.trading.api.model.OrderCommandReceipt;
import com.surprising.trading.api.model.PlaceTriggerOrderRequest;
import com.surprising.trading.api.model.TriggerOrderBatchResponse;
import com.surprising.trading.api.model.TriggerOrderQueryResponse;
import com.surprising.trading.api.model.TriggerOrderResponse;
import com.surprising.trading.order.service.OrderQueryService;
import com.surprising.trading.trigger.model.OpenTriggerPageQuery;
import com.surprising.trading.trigger.service.TriggerOrderAeronGateway;
import com.surprising.trading.trigger.service.TriggerOrderService;
import com.surprising.trading.trigger.utils.OpenTriggerCursors;
import java.util.concurrent.CompletionStage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * REST endpoints for trigger (stop/TP/SL/OCO) order placement, cancellation, and queries.
 */
@RestController
public class TradingTriggerController {
    private final OrderQueryService orderQueryService;
    private final TriggerOrderService triggerOrderService;

    @Autowired
    public TradingTriggerController(OrderQueryService orderQueryService, TriggerOrderService triggerOrderService) {
        this.orderQueryService = orderQueryService;
        this.triggerOrderService = triggerOrderService;
    }

    @PostMapping("/api/v1/trading/trigger-orders")
    public CompletionStage<TriggerOrderResponse> placeTrigger(@RequestBody PlaceTriggerOrderRequest request) {
        try {
            return TradingControllerSupport.mapAsyncFailure(triggerOrderService.placeAsync(request), HttpStatus.CONFLICT);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage(), ex);
        }
    }

    @PostMapping("/api/v1/trading/trigger-orders/batch")
    public CompletionStage<TriggerOrderBatchResponse> placeTriggerBatch(@RequestBody BatchPlaceTriggerOrderRequest request) {
        try {
            return TradingControllerSupport.mapAsyncFailure(triggerOrderService.placeBatchAsync(request), HttpStatus.CONFLICT);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage(), ex);
        }
    }

    @PostMapping("/api/v1/trading/trigger-orders/cancel")
    public CompletionStage<TriggerOrderResponse> cancelTrigger(@RequestBody CancelTriggerOrderRequest request) {
        try {
            return TradingControllerSupport.mapAsyncFailure(triggerOrderService.cancelAsync(request), HttpStatus.NOT_FOUND);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        }
    }

    @PostMapping("/api/v1/trading/trigger-orders/batch-cancel")
    public TriggerOrderBatchResponse cancelTriggerBatch(@RequestBody BatchCancelTriggerOrdersRequest request) {
        try {
            return triggerOrderService.cancelBatch(request);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @PostMapping("/api/v1/trading/trigger-orders/cancel-open")
    public TriggerOrderBatchResponse cancelTriggerOpen(@RequestBody CancelOpenTriggerOrdersRequest request) {
        try {
            return triggerOrderService.cancelOpenOrders(request);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @GetMapping("/api/v1/trading/trigger-orders/open")
    public TriggerOrderQueryResponse openTriggerOrders(
            @RequestParam(value = "userId", required = false) Long queryUserId,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestParam(value = "instrumentId", required = false) String instrumentId,
            @RequestParam(value = "limit", defaultValue = "100") int limit,
            @RequestParam(value = "cursor", required = false) String cursor) {
        long userId = GatewayUserContext.requireUserId(queryUserId, headerUserId, adminUserId);
        try {
            if (userId <= 0) throw new IllegalArgumentException("userId must be positive");
            return triggerOrderService.openOrders(userId, TradingControllerSupport.activeInstrument(instrumentId), activeTriggerPage(limit, cursor));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @GetMapping("/api/v1/trading/trigger-orders/oco-submission")
    public OrderCommandReceipt triggerOcoSubmission(
            @RequestParam(value = "userId", required = false) Long queryUserId,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestParam("takeProfitClientId") String takeProfitClientId,
            @RequestParam("stopLossClientId") String stopLossClientId) {
        long userId = GatewayUserContext.requireUserId(queryUserId, headerUserId, adminUserId);
        try {
            return orderQueryService.commandResult(userId, triggerOrderService.ocoCommandId(userId, takeProfitClientId, stopLossClientId));
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage(), invalid);
        }
    }

    @GetMapping("/api/v1/trading/trigger-orders/submission")
    public OrderCommandReceipt triggerSubmission(
            @RequestParam(value = "userId", required = false) Long queryUserId,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestParam("clientTriggerOrderId") String clientTriggerOrderId) {
        long userId = GatewayUserContext.requireUserId(queryUserId, headerUserId, adminUserId);
        try {
            return orderQueryService.commandResult(userId, triggerOrderService.submissionCommandId(userId, clientTriggerOrderId));
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage(), invalid);
        }
    }

    @GetMapping("/api/v1/trading/trigger-orders/cancellations/{triggerOrderId}")
    public OrderCommandReceipt triggerCancellation(
            @RequestParam(value = "userId", required = false) Long queryUserId,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @PathVariable("triggerOrderId") long triggerOrderId) {
        long userId = GatewayUserContext.requireUserId(queryUserId, headerUserId, adminUserId);
        try {
            return orderQueryService.commandResult(userId, TriggerOrderAeronGateway.cancellationCommandId(userId, triggerOrderId));
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage(), invalid);
        }
    }

    @GetMapping("/api/v1/trading/trigger-orders/{triggerOrderId}")
    public TriggerOrderResponse getTrigger(
            @RequestParam(value = "userId", required = false) Long queryUserId,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @PathVariable("triggerOrderId") long triggerOrderId) {
        long userId = GatewayUserContext.requireUserId(queryUserId, headerUserId, adminUserId);
        try {
            return triggerOrderService.get(userId, triggerOrderId);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        }
    }

    public static OpenTriggerPageQuery activeTriggerPage(int limit, String cursor) {
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("limit must be in [1, 1000]");
        return new OpenTriggerPageQuery(limit, OpenTriggerCursors.decode(cursor));
    }
}
