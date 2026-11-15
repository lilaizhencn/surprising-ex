package com.surprising.trading.order.controller;

import com.surprising.gateway.provider.product.GatewayUserContext;
import com.surprising.product.api.ProductLine;
import com.surprising.product.api.utils.InstrumentIds;
import com.surprising.realtime.api.exception.ReadViewUnavailableException;
import com.surprising.trading.api.model.AlgoOrderBatchResponse;
import com.surprising.trading.api.model.AlgoOrderQueryResponse;
import com.surprising.trading.api.model.AlgoOrderResponse;
import com.surprising.trading.api.model.AlgoOrderSubmissionResponse;
import com.surprising.trading.api.model.AmendOrderRequest;
import com.surprising.trading.api.model.BatchAmendOrdersRequest;
import com.surprising.trading.api.model.BatchCancelOrdersRequest;
import com.surprising.trading.api.model.BatchCancelTriggerOrdersRequest;
import com.surprising.trading.api.model.BatchPlaceOrderRequest;
import com.surprising.trading.api.model.BatchPlaceTriggerOrderRequest;
import com.surprising.trading.api.model.CancelAlgoOrderRequest;
import com.surprising.trading.api.model.CancelAllAfterRequest;
import com.surprising.trading.api.model.CancelAllAfterResponse;
import com.surprising.trading.api.model.CancelOpenAlgoOrdersRequest;
import com.surprising.trading.api.model.CancelOpenOrdersRequest;
import com.surprising.trading.api.model.CancelOpenTriggerOrdersRequest;
import com.surprising.trading.api.model.CancelOrderRequest;
import com.surprising.trading.api.model.CancelTriggerOrderRequest;
import com.surprising.trading.api.model.CancellationPlanResponse;
import com.surprising.trading.api.model.ClosePositionRequest;
import com.surprising.trading.api.model.EffectiveTradingFeeResponse;
import com.surprising.trading.api.model.LeverageSettingRequest;
import com.surprising.trading.api.model.LeverageSettingResponse;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.OrderBatchResponse;
import com.surprising.trading.api.model.OrderBookSnapshotResponse;
import com.surprising.trading.api.model.OrderCommandReceipt;
import com.surprising.trading.api.model.OrderPriceProtectionResponse;
import com.surprising.trading.api.model.OrderQueryResponse;
import com.surprising.trading.api.model.OrderResponse;
import com.surprising.trading.api.model.OrderSubmissionResponse;
import com.surprising.trading.api.model.PlaceAlgoOrderRequest;
import com.surprising.trading.api.model.PlaceOrderRequest;
import com.surprising.trading.api.model.PlaceTriggerOrderRequest;
import com.surprising.trading.api.model.TestOrderResponse;
import com.surprising.trading.api.model.TradeFillQueryResponse;
import com.surprising.trading.api.model.TriggerOrderBatchResponse;
import com.surprising.trading.api.model.TriggerOrderQueryResponse;
import com.surprising.trading.api.model.TriggerOrderResponse;
import com.surprising.trading.matching.service.MatchingMarketDataService;
import com.surprising.trading.order.exception.OrderCommandRejectedException;
import com.surprising.trading.order.model.ActiveOrderPageQuery;
import com.surprising.trading.order.repository.ProjectionReadResult;
import com.surprising.trading.order.service.AlgoOrderService;
import com.surprising.trading.order.service.CancelAllAfterService;
import com.surprising.trading.order.service.LeverageService;
import com.surprising.trading.order.service.OrderService;
import com.surprising.trading.order.service.TradingFeeService;
import com.surprising.trading.order.utils.ActiveOrderCursors;
import com.surprising.trading.order.utils.TradeReadCursors;
import com.surprising.trading.trigger.model.OpenTriggerPageQuery;
import com.surprising.trading.trigger.service.TriggerOrderAeronGateway;
import com.surprising.trading.trigger.service.TriggerOrderService;
import com.surprising.trading.trigger.utils.OpenTriggerCursors;
import jakarta.validation.Valid;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
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
    private final AlgoOrderService algoOrderService;
    private final CancelAllAfterService cancelAllAfterService;
    private final TriggerOrderService triggerOrderService;
    private final LeverageService leverageService;
    private final TradingFeeService tradingFeeService;
    private final MatchingMarketDataService marketDataService;

    @Autowired
    public TradingController(OrderService orderService,
                             AlgoOrderService algoOrderService,
                             CancelAllAfterService cancelAllAfterService,
                             TriggerOrderService triggerOrderService,
                             LeverageService leverageService,
                             TradingFeeService tradingFeeService,
                             MatchingMarketDataService marketDataService) {
        this.orderService = orderService;
        this.algoOrderService = algoOrderService;
        this.cancelAllAfterService = cancelAllAfterService;
        this.triggerOrderService = triggerOrderService;
        this.leverageService = leverageService;
        this.tradingFeeService = tradingFeeService;
        this.marketDataService = marketDataService;
    }

    public TradingController(OrderService orderService,
                             AlgoOrderService algoOrderService,
                             CancelAllAfterService cancelAllAfterService) {
        this(orderService, algoOrderService, cancelAllAfterService, null, null, null, null);
    }

    public TradingController(MatchingMarketDataService marketDataService) {
        this(null, null, null, null, null, null, marketDataService);
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
                    TradeReadCursors.fillScope(orderService.currentProductLine(), userId, instrumentId, orderId, startTime, endTime));
            return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                    .body(orderService.ownFills(userId, instrumentId, orderId, startTime, endTime, limit, after));
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
                    .body(orderService.cancellationPlan(userId, instrumentId));
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
            return commandResponse(orderService.cancellationBatch(userId, batchKey));
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
            return commandResponse(orderService.amendment(userId, clientRequestId));
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
            return commandResponse(orderService.cancellation(userId, orderId));
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
            var result = orderService.submission(userId, clientOrderId);
            return ResponseEntity.status("TERMINAL".equals(result.outcome()) ? HttpStatus.OK : HttpStatus.ACCEPTED)
                    .cacheControl(CacheControl.noStore()).body(result);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (DataAccessException | IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "committed submission result is unavailable", ex);
        }
    }

    @GetMapping("/api/v1/trading/orders/algo/submission")
    public ResponseEntity<AlgoOrderSubmissionResponse> algoSubmission(
            @RequestParam(value = "userId", required = false) Long queryUserId,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestParam("clientAlgoOrderId") String clientAlgoOrderId) {
        long userId = GatewayUserContext.requireUserId(queryUserId, headerUserId, adminUserId);
        try {
            if (userId <= 0) throw new IllegalArgumentException("userId must be positive");
            if (clientAlgoOrderId == null || clientAlgoOrderId.isBlank() || clientAlgoOrderId.trim().length() > 64) {
                throw new IllegalArgumentException("clientAlgoOrderId is required and must be at most 64 characters");
            }
            var result = algoOrderService.submission(userId, clientAlgoOrderId.trim());
            return ResponseEntity.status("TERMINAL".equals(result.outcome()) ? HttpStatus.OK : HttpStatus.ACCEPTED)
                    .cacheControl(CacheControl.noStore()).body(result);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (DataAccessException | IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "committed algo creation result is unavailable", ex);
        }
    }

    @GetMapping("/api/v1/trading/orders/price-protection")
    public OrderPriceProtectionResponse priceProtection() {
        return orderService.priceProtection();
    }

    @GetMapping("/api/v1/trading/market/orderbook")
    public OrderBookSnapshotResponse orderBook(
            @RequestParam("instrumentId") String instrumentId,
            @RequestParam(value = "depth", defaultValue = "30") int depth) {
        try {
            return marketDataService.orderBookSnapshot(instrumentId, depth);
        } catch (ReadViewUnavailableException unavailable) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, unavailable.getMessage(), unavailable);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @GetMapping("/api/v1/trading/fees/effective")
    public EffectiveTradingFeeResponse effectiveFee(
            @RequestParam(value = "userId", required = false) Long queryUserId,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestParam("instrumentId") String instrumentId,
            @RequestParam(value = "instrumentChangeId", defaultValue = "0") long instrumentChangeId,
            @RequestHeader(value = "X-Product-Line", required = false) String headerProductLine,
            @RequestParam(value = "productLine", required = false) String queryProductLine) {
        long userId = GatewayUserContext.requireUserId(queryUserId, headerUserId, adminUserId);
        return effectiveFee(tradingFeeService, userId, instrumentId, instrumentChangeId, headerProductLine, queryProductLine);
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

    @GetMapping("/api/v1/trading/orders/by-client-order-id")
    public OrderResponse getByClientOrderId(
            @RequestParam(value = "userId", required = false) Long queryUserId,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestParam("clientOrderId") String clientOrderId,
            @RequestParam(value = "minExportSequence", required = false) Long minExportSequence) {
        long userId = GatewayUserContext.requireUserId(queryUserId, headerUserId, adminUserId);
        try {
            return orderService.getByClientOrderId(userId, clientOrderId, minExportSequence);
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

    @PostMapping("/api/v1/trading/orders/algo/cancel-open")
    public AlgoOrderBatchResponse cancelOpenAlgo(@RequestBody CancelOpenAlgoOrdersRequest request) {
        try {
            return algoOrderService.cancelOpen(request);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @PostMapping("/api/v1/trading/trigger-orders/cancel")
    public CompletionStage<TriggerOrderResponse> cancelTrigger(@RequestBody CancelTriggerOrderRequest request) {
        try {
            return mapAsyncFailure(triggerOrderService.cancelAsync(request), HttpStatus.NOT_FOUND);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
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

    @PostMapping("/api/v1/trading/trigger-orders/batch")
    public CompletionStage<TriggerOrderBatchResponse> placeTriggerBatch(@RequestBody BatchPlaceTriggerOrderRequest request) {
        try {
            return mapAsyncFailure(triggerOrderService.placeBatchAsync(request), HttpStatus.CONFLICT);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage(), ex);
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
            return triggerOrderService.openOrders(userId, activeInstrument(instrumentId), activeTriggerPage(limit, cursor));
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
            return orderService.commandResult(userId, triggerOrderService.ocoCommandId(userId, takeProfitClientId, stopLossClientId));
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
            return orderService.commandResult(userId, triggerOrderService.submissionCommandId(userId, clientTriggerOrderId));
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
            return orderService.commandResult(userId, TriggerOrderAeronGateway.cancellationCommandId(userId, triggerOrderId));
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage(), invalid);
        }
    }

    @PostMapping("/api/v1/trading/orders/batch-cancel")
    public CompletionStage<ResponseEntity<OrderCommandReceipt>> cancelBatch(@Valid @RequestBody BatchCancelOrdersRequest request) {
        try {
            return mapAsyncFailure(orderService.cancelBatchCommandAsync(request).thenApply(TradingController::commandResponse), HttpStatus.NOT_FOUND);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @PostMapping("/api/v1/trading/orders/batch-amend")
    public CompletionStage<ResponseEntity<OrderCommandReceipt>> amendBatch(@Valid @RequestBody BatchAmendOrdersRequest request) {
        try {
            return mapAsyncFailure(orderService.amendBatchCommandAsync(request).thenApply(TradingController::commandResponse), HttpStatus.CONFLICT);
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

    @PostMapping("/api/v1/trading/orders/algo/cancel")
    public AlgoOrderResponse cancelAlgo(@RequestBody CancelAlgoOrderRequest request) {
        try {
            return algoOrderService.cancel(request);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        }
    }

    @PostMapping("/api/v1/trading/leverage/settings")
    public LeverageSettingResponse setLeverage(
            @RequestBody LeverageSettingRequest request,
            @RequestHeader(value = "X-Product-Line", required = false) String headerProductLine,
            @RequestParam(value = "productLine", required = false) String queryProductLine) {
        return setLeverage(leverageService, request, headerProductLine, queryProductLine);
    }

    @GetMapping("/api/v1/trading/leverage/settings")
    public LeverageSettingResponse getLeverage(
            @RequestParam(value = "userId", required = false) Long queryUserId,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestParam("instrumentId") String instrumentId,
            @RequestParam(value = "marginMode", required = false) MarginMode marginMode,
            @RequestHeader(value = "X-Product-Line", required = false) String headerProductLine,
            @RequestParam(value = "productLine", required = false) String queryProductLine) {
        long userId = GatewayUserContext.requireUserId(queryUserId, headerUserId, adminUserId);
        return getLeverage(leverageService, userId, instrumentId, marginMode, headerProductLine, queryProductLine);
    }

    @GetMapping("/api/v1/trading/orders/algo/open")
    public AlgoOrderQueryResponse openAlgoOrders(
            @RequestParam(value = "userId", required = false) Long queryUserId,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestParam(value = "instrumentId", required = false) String instrumentId,
            @RequestParam(value = "limit", defaultValue = "100") int limit) {
        long userId = GatewayUserContext.requireUserId(queryUserId, headerUserId, adminUserId);
        try {
            if (userId <= 0) throw new IllegalArgumentException("userId must be positive");
            if (limit < 1 || limit > 1000) throw new IllegalArgumentException("limit must be in [1, 1000]");
            instrumentId = instrumentId == null || instrumentId.isBlank() ? null : instrumentId;
            if (instrumentId != null) InstrumentIds.parse(instrumentId);
            return algoOrderService.openOrders(userId, instrumentId, limit);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @PostMapping("/api/v1/trading/trigger-orders")
    public CompletionStage<TriggerOrderResponse> placeTrigger(@RequestBody PlaceTriggerOrderRequest request) {
        try {
            return mapAsyncFailure(triggerOrderService.placeAsync(request), HttpStatus.CONFLICT);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage(), ex);
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
            return orderService.historyOrders(userId, instrumentId, limit, orderId, startTime, endTime, cursor, minExportSequence, status);
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
            return mapAsyncFailure(orderService.cancelCommandAsync(request).thenApply(TradingController::commandResponse), HttpStatus.NOT_FOUND);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        }
    }

    @PostMapping("/api/v1/trading/orders/batch")
    public CompletionStage<ResponseEntity<OrderCommandReceipt>> placeBatch(@Valid @RequestBody BatchPlaceOrderRequest request) {
        try {
            return mapAsyncFailure(orderService.placeBatchCommandAsync(request).thenApply(TradingController::commandResponse), HttpStatus.CONFLICT);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage(), ex);
        }
    }

    @PostMapping("/api/v1/trading/orders/amend")
    public CompletionStage<ResponseEntity<OrderCommandReceipt>> amend(@Valid @RequestBody AmendOrderRequest request) {
        try {
            return mapAsyncFailure(orderService.amendCommandAsync(request).thenApply(TradingController::commandResponse), HttpStatus.CONFLICT);
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

    @PostMapping("/api/v1/trading/orders/algo")
    public AlgoOrderResponse placeAlgo(@RequestBody PlaceAlgoOrderRequest request) {
        try {
            return algoOrderService.place(request);
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
            return orderService.openOrders(userId, activeInstrument(instrumentId), activePage(limit, cursor), minExportSequence);
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
            return mapAsyncFailure(orderService.placeCommandAsync(request).thenApply(TradingController::commandResponse), HttpStatus.CONFLICT);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage(), ex);
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

    @GetMapping("/api/v1/trading/orders/commands/{commandId}")
    public ResponseEntity<OrderCommandReceipt> commandResult(
            @RequestParam(value = "userId", required = false) Long queryUserId,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @PathVariable("commandId") UUID commandId) {
        long userId = GatewayUserContext.requireUserId(queryUserId, headerUserId, adminUserId);
        try {
            return commandResponse(orderService.commandResult(userId, commandId));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (DataAccessException | IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "committed command result is unavailable", ex);
        }
    }

    @GetMapping("/api/v1/trading/orders/algo/{algoOrderId}")
    public AlgoOrderResponse getAlgo(
            @RequestParam(value = "userId", required = false) Long queryUserId,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @PathVariable("algoOrderId") long algoOrderId) {
        long userId = GatewayUserContext.requireUserId(queryUserId, headerUserId, adminUserId);
        try {
            if (algoOrderId <= 0) throw new IllegalArgumentException("algoOrderId must be positive");
            if (userId <= 0) throw new IllegalArgumentException("invalid algo owner");
            return algoOrderService.get(userId, algoOrderId);
        } catch (NoSuchElementException missing) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, missing.getMessage(), missing);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage(), ex);
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
            return orderService.get(userId, orderId, minExportSequence);
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

    static EffectiveTradingFeeResponse effectiveFee(TradingFeeService service, long userId, String instrumentId,
                                                      long instrumentChangeId, String headerProductLine, String queryProductLine) {
        try {
            String normalized = normalizeOptionalSymbol(instrumentId);
            if (normalized == null) throw new IllegalArgumentException("instrumentId is required");
            return service.effectiveFee(userId, normalized, instrumentChangeId, productLine(queryProductLine, headerProductLine));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        }
    }

    static LeverageSettingResponse setLeverage(LeverageService service, LeverageSettingRequest request,
                                               String headerProductLine, String queryProductLine) {
        try {
            ProductLine line = productLine(queryProductLine, headerProductLine);
            LeverageSettingRequest bound = request == null || request.productLine() != null || line == null
                    ? request
                    : new LeverageSettingRequest(request.userId(), line, request.instrumentId(),
                            request.marginMode(), request.leveragePpm(), request.reason(), request.repriceCrossMargin());
            return service.set(bound);
        } catch (OrderCommandRejectedException rejected) {
            throw conflict(rejected);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        }
    }

    static LeverageSettingResponse getLeverage(LeverageService service, long userId, String instrumentId,
                                               MarginMode marginMode, String headerProductLine, String queryProductLine) {
        try {
            return service.get(userId, instrumentId, marginMode, productLine(queryProductLine, headerProductLine));
        } catch (OrderCommandRejectedException rejected) {
            throw conflict(rejected);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        }
    }

    static ProductLine productLine(String queryValue, String headerValue) {
        String value = queryValue == null || queryValue.isBlank() ? headerValue : queryValue;
        if (value == null || value.isBlank()) {
            return null;
        }
        return ProductLine.requireExternalCode(value);
    }

    static String normalizeOptionalSymbol(String instrumentId) {
        if (instrumentId == null || instrumentId.isBlank()) {
            return null;
        }
        String normalized = instrumentId.trim().toUpperCase(Locale.ROOT);
        if (!InstrumentIds.valid(normalized)) {
            throw new IllegalArgumentException("invalid instrumentId: " + instrumentId);
        }
        return normalized;
    }

    public static ActiveOrderPageQuery activePage(int limit, String cursor) {
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("limit must be in [1, 1000]");
        return new ActiveOrderPageQuery(limit, ActiveOrderCursors.decode(cursor));
    }

    public static OpenTriggerPageQuery activeTriggerPage(int limit, String cursor) {
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("limit must be in [1, 1000]");
        return new OpenTriggerPageQuery(limit, OpenTriggerCursors.decode(cursor));
    }

    private static String activeInstrument(String instrumentId) {
        if (instrumentId == null || instrumentId.isBlank()) return null;
        String value = instrumentId.trim().toUpperCase(Locale.ROOT);
        if (!InstrumentIds.valid(value)) {
            throw new IllegalArgumentException("invalid instrumentId: " + instrumentId);
        }
        return value;
    }

    private static ResponseStatusException conflict(OrderCommandRejectedException rejected) {
        var response = new ResponseStatusException(HttpStatus.CONFLICT, rejected.getMessage(), rejected);
        response.getBody().setProperty("code", rejected.code().name());
        return response;
    }

    private static <T> CompletionStage<T> mapAsyncFailure(CompletionStage<T> stage, HttpStatus illegalStateStatus) {
        CompletableFuture<T> mapped = new CompletableFuture<>();
        stage.whenComplete((value, failure) -> {
            if (failure == null) {
                mapped.complete(value);
                return;
            }
            Throwable cause = failure;
            while (cause instanceof CompletionException && cause.getCause() != null) {
                cause = cause.getCause();
            }
            if (cause instanceof IllegalArgumentException) {
                mapped.completeExceptionally(new ResponseStatusException(HttpStatus.BAD_REQUEST, cause.getMessage(), cause));
            } else if (cause instanceof IllegalStateException) {
                mapped.completeExceptionally(new ResponseStatusException(illegalStateStatus, cause.getMessage(), cause));
            } else {
                mapped.completeExceptionally(cause);
            }
        });
        return mapped;
    }

    private static ResponseEntity<OrderCommandReceipt> commandResponse(OrderCommandReceipt receipt) {
        HttpStatus status = switch (receipt.code()) {
            case "IDEMPOTENCY_CONFLICT" -> HttpStatus.CONFLICT;
            case "CLIENT_BACKPRESSURED" -> HttpStatus.TOO_MANY_REQUESTS;
            case "MATCHING_PENDING", "RESULT_UNKNOWN" -> HttpStatus.ACCEPTED;
            case "RESULT_UNKNOWN_OUTSIDE_RETENTION" -> HttpStatus.GONE;
            case "NOT_CONNECTED", "ADMIN_ACTION", "CLOSED", "MAX_POSITION_EXCEEDED", "UNKNOWN" -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.OK;
        };
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(receipt);
    }
}
