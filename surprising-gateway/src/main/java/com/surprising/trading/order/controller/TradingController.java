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
        return TradingControllerSupport.call(() -> ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(orderQueryService.cancellationPlan(userId, instrumentId)));
    }

    @GetMapping("/api/v1/trading/orders/cancellation-batches")
    public ResponseEntity<OrderCommandReceipt> cancellationBatch(
            @RequestParam(value = "userId", required = false) Long queryUserId,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestParam("batchKey") String batchKey) {
        long userId = GatewayUserContext.requireUserId(queryUserId, headerUserId, adminUserId);
        return TradingControllerSupport.callUnavailable(
                () -> commandResponse(orderQueryService.cancellationBatch(userId, batchKey)),
                "committed cancellation batch is unavailable");
    }

    @GetMapping("/api/v1/trading/orders/amendments")
    public ResponseEntity<OrderCommandReceipt> amendment(
            @RequestParam(value = "userId", required = false) Long queryUserId,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestParam("clientRequestId") String clientRequestId) {
        long userId = GatewayUserContext.requireUserId(queryUserId, headerUserId, adminUserId);
        return TradingControllerSupport.callUnavailable(
                () -> commandResponse(orderQueryService.amendment(userId, clientRequestId)),
                "committed amendment result is unavailable");
    }

    @GetMapping("/api/v1/trading/orders/cancellations/{orderId}")
    public ResponseEntity<OrderCommandReceipt> cancellation(
            @RequestParam(value = "userId", required = false) Long queryUserId,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @PathVariable("orderId") long orderId) {
        long userId = GatewayUserContext.requireUserId(queryUserId, headerUserId, adminUserId);
        return TradingControllerSupport.callUnavailable(
                () -> commandResponse(orderQueryService.cancellation(userId, orderId)),
                "committed cancellation result is unavailable");
    }

    @GetMapping("/api/v1/trading/orders/submission")
    public ResponseEntity<OrderSubmissionResponse> submission(
            @RequestParam(value = "userId", required = false) Long queryUserId,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestParam("clientOrderId") String clientOrderId) {
        long userId = GatewayUserContext.requireUserId(queryUserId, headerUserId, adminUserId);
        return TradingControllerSupport.callUnavailable(() -> {
            var result = orderQueryService.submission(userId, clientOrderId);
            return ResponseEntity.status("TERMINAL".equals(result.outcome()) ? HttpStatus.OK : HttpStatus.ACCEPTED)
                    .cacheControl(CacheControl.noStore()).body(result);
        }, "committed submission result is unavailable");
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
        return TradingControllerSupport.call(() -> orderQueryService.getByClientOrderId(userId, clientOrderId, minExportSequence));
    }

    @PostMapping("/api/v1/trading/orders/cancel-all-after")
    public CancelAllAfterResponse cancelAllAfter(@RequestBody CancelAllAfterRequest request) {
        return TradingControllerSupport.call(() -> cancelAllAfterService.set(request));
    }

    @PostMapping("/api/v1/trading/orders/close-position")
    public OrderResponse closePosition(@Valid @RequestBody ClosePositionRequest request) {
        return TradingControllerSupport.call(() -> orderService.closePosition(request));
    }

    @PostMapping("/api/v1/trading/orders/batch-cancel")
    public CompletionStage<ResponseEntity<OrderCommandReceipt>> cancelBatch(@Valid @RequestBody BatchCancelOrdersRequest request) {
        return TradingControllerSupport.callAsync(
                () -> orderService.cancelBatchCommandAsync(request).thenApply(TradingControllerSupport::commandResponse),
                HttpStatus.NOT_FOUND);
    }

    @PostMapping("/api/v1/trading/orders/batch-amend")
    public CompletionStage<ResponseEntity<OrderCommandReceipt>> amendBatch(@Valid @RequestBody BatchAmendOrdersRequest request) {
        return TradingControllerSupport.callAsync(
                () -> orderService.amendBatchCommandAsync(request).thenApply(TradingControllerSupport::commandResponse),
                HttpStatus.CONFLICT);
    }

    @PostMapping("/api/v1/trading/orders/cancel-open")
    public OrderBatchResponse cancelOpen(@RequestBody CancelOpenOrdersRequest request) {
        return TradingControllerSupport.call(() -> orderService.cancelOpenOrders(request));
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
        return TradingControllerSupport.call(() ->
                orderQueryService.historyOrders(userId, instrumentId, limit, orderId, startTime, endTime, cursor, minExportSequence, status));
    }

    @PostMapping("/api/v1/trading/orders/cancel")
    public CompletionStage<ResponseEntity<OrderCommandReceipt>> cancel(@RequestBody CancelOrderRequest request) {
        return TradingControllerSupport.callAsync(
                () -> orderService.cancelCommandAsync(request).thenApply(TradingControllerSupport::commandResponse),
                HttpStatus.NOT_FOUND);
    }

    @PostMapping("/api/v1/trading/orders/batch")
    public CompletionStage<ResponseEntity<OrderCommandReceipt>> placeBatch(@Valid @RequestBody BatchPlaceOrderRequest request) {
        return TradingControllerSupport.callAsync(
                () -> orderService.placeBatchCommandAsync(request).thenApply(TradingControllerSupport::commandResponse),
                HttpStatus.CONFLICT);
    }

    @PostMapping("/api/v1/trading/orders/amend")
    public CompletionStage<ResponseEntity<OrderCommandReceipt>> amend(@Valid @RequestBody AmendOrderRequest request) {
        return TradingControllerSupport.callAsync(
                () -> orderService.amendCommandAsync(request).thenApply(TradingControllerSupport::commandResponse),
                HttpStatus.CONFLICT);
    }

    @PostMapping("/api/v1/trading/orders/test")
    public TestOrderResponse test(@Valid @RequestBody PlaceOrderRequest request) {
        return TradingControllerSupport.call(() -> orderService.test(request));
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
        return TradingControllerSupport.call(() -> {
            if (userId <= 0) throw new IllegalArgumentException("userId must be positive");
            return orderQueryService.openOrders(userId, activeInstrument(instrumentId), activePage(limit, cursor), minExportSequence);
        });
    }

    @PostMapping("/api/v1/trading/orders")
    public CompletionStage<ResponseEntity<OrderCommandReceipt>> place(@Valid @RequestBody PlaceOrderRequest request) {
        return TradingControllerSupport.callAsync(
                () -> orderService.placeCommandAsync(request).thenApply(TradingControllerSupport::commandResponse),
                HttpStatus.CONFLICT);
    }

    @GetMapping("/api/v1/trading/orders/commands/{commandId}")
    public ResponseEntity<OrderCommandReceipt> commandResult(
            @RequestParam(value = "userId", required = false) Long queryUserId,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @PathVariable("commandId") UUID commandId) {
        long userId = GatewayUserContext.requireUserId(queryUserId, headerUserId, adminUserId);
        return TradingControllerSupport.callUnavailable(
                () -> commandResponse(orderQueryService.commandResult(userId, commandId)),
                "committed command result is unavailable");
    }

    @GetMapping("/api/v1/trading/orders/{orderId}")
    public OrderResponse get(
            @RequestParam(value = "userId", required = false) Long queryUserId,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @PathVariable("orderId") long orderId,
            @RequestParam(value = "minExportSequence", required = false) Long minExportSequence) {
        long userId = GatewayUserContext.requireUserId(queryUserId, headerUserId, adminUserId);
        return TradingControllerSupport.call(() -> orderQueryService.get(userId, orderId, minExportSequence));
    }
}
