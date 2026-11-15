package com.surprising.trading.order.controller;

import static com.surprising.trading.order.controller.TradingControllerSupport.activeInstrument;
import static com.surprising.trading.order.controller.TradingControllerSupport.activePage;
import static com.surprising.trading.order.controller.TradingControllerSupport.commandResponse;
import static com.surprising.trading.order.controller.TradingControllerSupport.mapAsyncFailure;

import com.surprising.gateway.provider.product.GatewayUserContext;
import com.surprising.product.api.utils.InstrumentIds;
import com.surprising.trading.api.model.AmendOrderRequest;
import com.surprising.trading.api.model.BatchAmendOrdersRequest;
import com.surprising.trading.api.model.BatchCancelOrdersRequest;
import com.surprising.trading.api.model.BatchPlaceOrderRequest;
import com.surprising.trading.api.model.CancelAllAfterRequest;
import com.surprising.trading.api.model.CancelAllAfterResponse;
import com.surprising.trading.api.model.CancelOpenOrdersRequest;
import com.surprising.trading.api.model.CancelOrderRequest;
import com.surprising.trading.api.model.CancellationPlanResponse;
import com.surprising.trading.api.model.ClosePositionRequest;
import com.surprising.trading.api.model.OrderBatchResponse;
import com.surprising.trading.api.model.OrderCommandReceipt;
import com.surprising.trading.api.model.OrderPriceProtectionResponse;
import com.surprising.trading.api.model.OrderQueryResponse;
import com.surprising.trading.api.model.OrderResponse;
import com.surprising.trading.api.model.OrderSubmissionResponse;
import com.surprising.trading.api.model.PlaceOrderRequest;
import com.surprising.trading.api.model.TestOrderResponse;
import com.surprising.trading.api.model.TradeFillQueryResponse;
import com.surprising.trading.order.repository.ProjectionReadResult;
import com.surprising.trading.order.service.CancelAllAfterService;
import com.surprising.trading.order.service.OrderQueryService;
import com.surprising.trading.order.service.OrderService;
import com.surprising.trading.order.utils.TradeReadCursors;
import jakarta.validation.Valid;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class TradingController {
    private final OrderService orderService;
    private final OrderQueryService orderQueryService;
    private final CancelAllAfterService cancelAllAfterService;

    @Autowired
    public TradingController(OrderService orderService,
                             OrderQueryService orderQueryService,
                             CancelAllAfterService cancelAllAfterService) {
        this.orderService = orderService;
        this.orderQueryService = orderQueryService;
        this.cancelAllAfterService = cancelAllAfterService;
    }

    @GetMapping("/api/v1/trading/orders/trades")
    public ResponseEntity<TradeFillQueryResponse> ownFills(
            @RequestParam(value = "userId", required = false) Long queryUserId,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestParam(value = "instrumentId", required = false) String instrumentId,
            @RequestParam(value = "orderId", required = false) Long orderId,
            @RequestParam(value = "startTime", required = false) Long startTime,
            @RequestParam(value = "endTime", required = false) Long endTime,
            @RequestParam(value = "limit", defaultValue = "100") int limit,
            @RequestParam(value = "cursor", required = false) String cursor) {
        long userId = GatewayUserContext.requireUserId(queryUserId, headerUserId, adminUserId);
        try {
            if (userId <= 0 || orderId != null && orderId <= 0 || limit < 1 || limit > 1000
                    || startTime != null && startTime <= 0 || endTime != null && endTime <= 0
                    || startTime != null && endTime != null && startTime > endTime) {
                throw new IllegalArgumentException("invalid private fill query");
            }
            instrumentId = instrumentId == null || instrumentId.isBlank() ? null : instrumentId.trim().toUpperCase(Locale.ROOT);
            if (instrumentId != null) InstrumentIds.parse(instrumentId);
            var after = cursor == null || cursor.isBlank() ? null : TradeReadCursors.fill(cursor,
                    TradeReadCursors.fillScope(orderQueryService.currentProductLine(), userId, instrumentId, orderId, startTime, endTime));
            return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                    .body(orderQueryService.ownFills(userId, instrumentId, orderId, startTime, endTime, limit, after));
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage(), invalid);
        }
    }

    @GetMapping("/api/v1/trading/orders/cancellation-plan")
    public ResponseEntity<CancellationPlanResponse> cancellationPlan(
            @RequestParam(value = "userId", required = false) Long queryUserId,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestParam(value = "instrumentId", required = false) String instrumentId) {
        long userId = GatewayUserContext.requireUserId(queryUserId, headerUserId, adminUserId);
        try {
            return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                    .body(orderQueryService.cancellationPlan(userId, instrumentId));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @GetMapping("/api/v1/trading/orders/cancellation-batches")
    public ResponseEntity<OrderCommandReceipt> cancellationBatch(
            @RequestParam(value = "userId", required = false) Long queryUserId,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestParam("batchKey") String batchKey) {
        long userId = GatewayUserContext.requireUserId(queryUserId, headerUserId, adminUserId);
        try {
            return commandResponse(orderQueryService.cancellationBatch(userId, batchKey));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (DataAccessException | IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "committed cancellation batch is unavailable", ex);
        }
    }

    @GetMapping("/api/v1/trading/orders/amendments")
    public ResponseEntity<OrderCommandReceipt> amendment(
            @RequestParam(value = "userId", required = false) Long queryUserId,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestParam("clientRequestId") String clientRequestId) {
        long userId = GatewayUserContext.requireUserId(queryUserId, headerUserId, adminUserId);
        try {
            return commandResponse(orderQueryService.amendment(userId, clientRequestId));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (DataAccessException | IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "committed amendment result is unavailable", ex);
        }
    }

    @GetMapping("/api/v1/trading/orders/cancellations/{orderId}")
    public ResponseEntity<OrderCommandReceipt> cancellation(
            @RequestParam(value = "userId", required = false) Long queryUserId,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @PathVariable("orderId") long orderId) {
        long userId = GatewayUserContext.requireUserId(queryUserId, headerUserId, adminUserId);
        try {
            return commandResponse(orderQueryService.cancellation(userId, orderId));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (DataAccessException | IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "committed cancellation result is unavailable", ex);
        }
    }

    @GetMapping("/api/v1/trading/orders/submission")
    public ResponseEntity<OrderSubmissionResponse> submission(
            @RequestParam(value = "userId", required = false) Long queryUserId,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestParam("clientOrderId") String clientOrderId) {
        long userId = GatewayUserContext.requireUserId(queryUserId, headerUserId, adminUserId);
        try {
            var result = orderQueryService.submission(userId, clientOrderId);
            return ResponseEntity.status("TERMINAL".equals(result.outcome()) ? HttpStatus.OK : HttpStatus.ACCEPTED)
                    .cacheControl(CacheControl.noStore()).body(result);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (DataAccessException | IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "committed submission result is unavailable", ex);
        }
    }

    @GetMapping("/api/v1/trading/orders/price-protection")
    public OrderPriceProtectionResponse priceProtection() {
        return orderQueryService.priceProtection();
    }

    @GetMapping("/api/v1/trading/orders/by-client-order-id")
    public OrderResponse getByClientOrderId(
            @RequestParam(value = "userId", required = false) Long queryUserId,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestParam("clientOrderId") String clientOrderId,
            @RequestParam(value = "minExportSequence", required = false) Long minExportSequence) {
        long userId = GatewayUserContext.requireUserId(queryUserId, headerUserId, adminUserId);
        try {
            return orderQueryService.getByClientOrderId(userId, clientOrderId, minExportSequence);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (ProjectionReadResult.ProjectionLagException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage(), ex);
        } catch (ProjectionReadResult.ResponseTooLargeException ex) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        }
    }

    @PostMapping("/api/v1/trading/orders/cancel-all-after")
    public CancelAllAfterResponse cancelAllAfter(@RequestBody CancelAllAfterRequest request) {
        try {
            return cancelAllAfterService.set(request);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @PostMapping("/api/v1/trading/orders/close-position")
    public OrderResponse closePosition(@Valid @RequestBody ClosePositionRequest request) {
        try {
            return orderService.closePosition(request);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        }
    }

    @PostMapping("/api/v1/trading/orders/batch-cancel")
    public CompletionStage<ResponseEntity<OrderCommandReceipt>> cancelBatch(@Valid @RequestBody BatchCancelOrdersRequest request) {
        try {
            return mapAsyncFailure(orderService.cancelBatchCommandAsync(request).thenApply(TradingControllerSupport::commandResponse), HttpStatus.NOT_FOUND);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @PostMapping("/api/v1/trading/orders/batch-amend")
    public CompletionStage<ResponseEntity<OrderCommandReceipt>> amendBatch(@Valid @RequestBody BatchAmendOrdersRequest request) {
        try {
            return mapAsyncFailure(orderService.amendBatchCommandAsync(request).thenApply(TradingControllerSupport::commandResponse), HttpStatus.CONFLICT);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @PostMapping("/api/v1/trading/orders/cancel-open")
    public OrderBatchResponse cancelOpen(@RequestBody CancelOpenOrdersRequest request) {
        try {
            return orderService.cancelOpenOrders(request);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @GetMapping("/api/v1/trading/orders/history")
    public OrderQueryResponse historyOrders(
            @RequestParam(value = "userId", required = false) Long queryUserId,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestParam(value = "instrumentId", required = false) String instrumentId,
            @RequestParam(value = "limit", defaultValue = "100") int limit,
            @RequestParam(value = "orderId", required = false) Long orderId,
            @RequestParam(value = "startTime", required = false) Long startTime,
            @RequestParam(value = "endTime", required = false) Long endTime,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "minExportSequence", required = false) Long minExportSequence,
            @RequestParam(value = "status", required = false) String status) {
        long userId = GatewayUserContext.requireUserId(queryUserId, headerUserId, adminUserId);
        try {
            return orderQueryService.historyOrders(userId, instrumentId, limit, orderId, startTime, endTime, cursor, minExportSequence, status);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (ProjectionReadResult.ProjectionLagException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage(), ex);
        } catch (ProjectionReadResult.ResponseTooLargeException ex) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, ex.getMessage(), ex);
        }
    }

    @PostMapping("/api/v1/trading/orders/cancel")
    public CompletionStage<ResponseEntity<OrderCommandReceipt>> cancel(@RequestBody CancelOrderRequest request) {
        try {
            return mapAsyncFailure(orderService.cancelCommandAsync(request).thenApply(TradingControllerSupport::commandResponse), HttpStatus.NOT_FOUND);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        }
    }

    @PostMapping("/api/v1/trading/orders/batch")
    public CompletionStage<ResponseEntity<OrderCommandReceipt>> placeBatch(@Valid @RequestBody BatchPlaceOrderRequest request) {
        try {
            return mapAsyncFailure(orderService.placeBatchCommandAsync(request).thenApply(TradingControllerSupport::commandResponse), HttpStatus.CONFLICT);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage(), ex);
        }
    }

    @PostMapping("/api/v1/trading/orders/amend")
    public CompletionStage<ResponseEntity<OrderCommandReceipt>> amend(@Valid @RequestBody AmendOrderRequest request) {
        try {
            return mapAsyncFailure(orderService.amendCommandAsync(request).thenApply(TradingControllerSupport::commandResponse), HttpStatus.CONFLICT);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage(), ex);
        }
    }

    @PostMapping("/api/v1/trading/orders/test")
    public TestOrderResponse test(@Valid @RequestBody PlaceOrderRequest request) {
        try {
            return orderService.test(request);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @GetMapping("/api/v1/trading/orders/open")
    public OrderQueryResponse openOrders(
            @RequestParam(value = "userId", required = false) Long queryUserId,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestParam(value = "instrumentId", required = false) String instrumentId,
            @RequestParam(value = "limit", defaultValue = "100") int limit,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "minExportSequence", required = false) Long minExportSequence) {
        long userId = GatewayUserContext.requireUserId(queryUserId, headerUserId, adminUserId);
        try {
            if (userId <= 0) throw new IllegalArgumentException("userId must be positive");
            return orderQueryService.openOrders(userId, activeInstrument(instrumentId), activePage(limit, cursor), minExportSequence);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (ProjectionReadResult.ProjectionLagException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage(), ex);
        } catch (ProjectionReadResult.ResponseTooLargeException ex) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, ex.getMessage(), ex);
        }
    }

    @PostMapping("/api/v1/trading/orders")
    public CompletionStage<ResponseEntity<OrderCommandReceipt>> place(@Valid @RequestBody PlaceOrderRequest request) {
        try {
            return mapAsyncFailure(orderService.placeCommandAsync(request).thenApply(TradingControllerSupport::commandResponse), HttpStatus.CONFLICT);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage(), ex);
        }
    }

    @GetMapping("/api/v1/trading/orders/commands/{commandId}")
    public ResponseEntity<OrderCommandReceipt> commandResult(
            @RequestParam(value = "userId", required = false) Long queryUserId,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @PathVariable("commandId") UUID commandId) {
        long userId = GatewayUserContext.requireUserId(queryUserId, headerUserId, adminUserId);
        try {
            return commandResponse(orderQueryService.commandResult(userId, commandId));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (DataAccessException | IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "committed command result is unavailable", ex);
        }
    }

    @GetMapping("/api/v1/trading/orders/{orderId}")
    public OrderResponse get(
            @RequestParam(value = "userId", required = false) Long queryUserId,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @PathVariable("orderId") long orderId,
            @RequestParam(value = "minExportSequence", required = false) Long minExportSequence) {
        long userId = GatewayUserContext.requireUserId(queryUserId, headerUserId, adminUserId);
        try {
            return orderQueryService.get(userId, orderId, minExportSequence);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (ProjectionReadResult.ProjectionLagException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage(), ex);
        } catch (ProjectionReadResult.ResponseTooLargeException ex) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        }
    }
}
