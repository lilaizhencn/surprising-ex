package com.surprising.trading.order.controller;

import com.surprising.trading.api.model.AmendOrderRequest;
import com.surprising.trading.api.model.CancellationPlanResponse;
import com.surprising.trading.api.model.AlgoOrderBatchResponse;
import com.surprising.trading.api.model.AlgoOrderQueryResponse;
import com.surprising.trading.api.model.AlgoOrderResponse;
import com.surprising.trading.api.model.BatchAmendOrdersRequest;
import com.surprising.trading.api.model.BatchCancelOrdersRequest;
import com.surprising.trading.api.model.BatchPlaceOrderRequest;
import com.surprising.trading.api.model.CancelAlgoOrderRequest;
import com.surprising.trading.api.model.CancelAllAfterRequest;
import com.surprising.trading.api.model.CancelAllAfterResponse;
import com.surprising.trading.api.model.CancelOrderRequest;
import com.surprising.trading.api.model.CancelOpenAlgoOrdersRequest;
import com.surprising.trading.api.model.CancelOpenOrdersRequest;
import com.surprising.trading.api.model.ClosePositionRequest;
import com.surprising.trading.api.model.OrderBatchResponse;
import com.surprising.trading.api.model.OrderCommandReceipt;
import com.surprising.trading.api.model.OrderQueryResponse;
import com.surprising.trading.api.model.OrderResponse;
import com.surprising.trading.api.model.PlaceAlgoOrderRequest;
import com.surprising.trading.api.model.PlaceOrderRequest;
import com.surprising.trading.api.model.TestOrderResponse;
import com.surprising.trading.order.repository.ProjectionReadResult;
import com.surprising.trading.order.service.AlgoOrderService;
import com.surprising.trading.order.service.CancelAllAfterService;
import com.surprising.trading.order.service.OrderService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import org.springframework.stereotype.Component;

/**
 * 本地协议与内部HTTP共用的请求绑定、参数校验及响应转换；业务由实际Service处理，不声明HTTP路由。
 */
@Component
public class OrderRequestController {

    private final OrderService orderService;

    private final AlgoOrderService algoOrderService;

    private final CancelAllAfterService cancelAllAfterService;

    public OrderRequestController(OrderService orderService, AlgoOrderService algoOrderService, CancelAllAfterService cancelAllAfterService) {
        this.orderService = orderService;
        this.algoOrderService = algoOrderService;
        this.cancelAllAfterService = cancelAllAfterService;
    }

    public com.surprising.trading.api.model.OrderPriceProtectionResponse priceProtection() {
        return orderService.priceProtection();
    }

    public ResponseEntity<com.surprising.trading.api.model.TradeFillQueryResponse> ownFills(long userId, String instrumentId,
            Long orderId, Long startTime, Long endTime, int limit, String cursor) {
        try {
            return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore())
                    .body(orderService.ownFills(userId, instrumentId, orderId, startTime, endTime, limit, cursor));
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage(), invalid);
        }
    }

    public CompletionStage<ResponseEntity<OrderCommandReceipt>> place(PlaceOrderRequest request) {
        try {
            return mapAsyncFailure(orderService.placeCommandAsync(request).thenApply(this::commandResponse), HttpStatus.CONFLICT);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage(), ex);
        }
    }

    public CompletionStage<ResponseEntity<OrderCommandReceipt>> placeBatch(BatchPlaceOrderRequest request) {
        try {
            return mapAsyncFailure(orderService.placeBatchCommandAsync(request).thenApply(this::commandResponse), HttpStatus.CONFLICT);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage(), ex);
        }
    }

    public TestOrderResponse test(PlaceOrderRequest request) {
        try {
            return orderService.test(request);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    public CompletionStage<ResponseEntity<OrderCommandReceipt>> amend(AmendOrderRequest request) {
        try {
            return mapAsyncFailure(orderService.amendCommandAsync(request).thenApply(this::commandResponse), HttpStatus.CONFLICT);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage(), ex);
        }
    }

    public CompletionStage<ResponseEntity<OrderCommandReceipt>> amendBatch(BatchAmendOrdersRequest request) {
        try {
            return mapAsyncFailure(orderService.amendBatchCommandAsync(request).thenApply(this::commandResponse), HttpStatus.CONFLICT);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    public OrderResponse closePosition(ClosePositionRequest request) {
        try {
            return orderService.closePosition(request);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        }
    }

    public CompletionStage<ResponseEntity<OrderCommandReceipt>> cancel(CancelOrderRequest request) {
        try {
            return mapAsyncFailure(orderService.cancelCommandAsync(request).thenApply(this::commandResponse), HttpStatus.NOT_FOUND);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        }
    }

    public CompletionStage<ResponseEntity<OrderCommandReceipt>> cancelBatch(BatchCancelOrdersRequest request) {
        try {
            return mapAsyncFailure(orderService.cancelBatchCommandAsync(request).thenApply(this::commandResponse), HttpStatus.NOT_FOUND);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
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

    public OrderBatchResponse cancelOpen(CancelOpenOrdersRequest request) {
        try {
            return orderService.cancelOpenOrders(request);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    public CancelAllAfterResponse cancelAllAfter(CancelAllAfterRequest request) {
        try {
            return cancelAllAfterService.set(request);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    public AlgoOrderResponse placeAlgo(PlaceAlgoOrderRequest request) {
        try {
            return algoOrderService.place(request);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    public AlgoOrderResponse cancelAlgo(CancelAlgoOrderRequest request) {
        try {
            return algoOrderService.cancel(request);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        }
    }

    public AlgoOrderBatchResponse cancelOpenAlgo(CancelOpenAlgoOrdersRequest request) {
        try {
            return algoOrderService.cancelOpen(request);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    public AlgoOrderResponse getAlgo(long userId, long algoOrderId) {
        try {
            if (algoOrderId <= 0) throw new IllegalArgumentException("algoOrderId must be positive");
            if (userId <= 0) throw new IllegalArgumentException("invalid algo owner");
            return algoOrderService.get(userId, algoOrderId);
        } catch (java.util.NoSuchElementException missing) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, missing.getMessage(), missing);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage(), ex);
        }
    }

    public ResponseEntity<com.surprising.trading.api.model.AlgoOrderSubmissionResponse> algoSubmission(long userId, String clientKey) {
        try {
            if (userId <= 0) throw new IllegalArgumentException("userId must be positive");
            if (clientKey == null || clientKey.isBlank() || clientKey.trim().length() > 64)
                throw new IllegalArgumentException("clientAlgoOrderId is required and must be at most 64 characters");
            clientKey = clientKey.trim();
            var result = algoOrderService.submission(userId, clientKey);
            return ResponseEntity.status("TERMINAL".equals(result.outcome()) ? HttpStatus.OK : HttpStatus.ACCEPTED)
                    .cacheControl(org.springframework.http.CacheControl.noStore()).body(result);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (org.springframework.dao.DataAccessException | IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "committed algo creation result is unavailable", ex);
        }
    }

    public AlgoOrderQueryResponse openAlgoOrders(long userId, String instrumentId, int limit) {
        try {
            if (userId <= 0) throw new IllegalArgumentException("userId must be positive");
            if (limit < 1 || limit > 1000) throw new IllegalArgumentException("limit must be in [1, 1000]");
            instrumentId = instrumentId == null || instrumentId.isBlank() ? null : instrumentId;
            if (instrumentId != null) com.surprising.product.api.utils.InstrumentIds.parse(instrumentId);
            return algoOrderService.openOrders(userId, instrumentId, limit);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    public OrderResponse get(long userId, long orderId, Long minExportSequence) {
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

    public ResponseEntity<OrderCommandReceipt> commandResult(long userId, UUID commandId) {
        try {
            return commandResponse(orderService.commandResult(userId, commandId));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (org.springframework.dao.DataAccessException | IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "committed command result is unavailable", ex);
        }
    }

    public ResponseEntity<CancellationPlanResponse> cancellationPlan(long userId, String instrumentId) {
        try {
            return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore())
                    .body(orderService.cancellationPlan(userId, instrumentId));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    public ResponseEntity<OrderCommandReceipt> cancellationBatch(long userId, String batchKey) {
        try {
            return commandResponse(orderService.cancellationBatch(userId, batchKey));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (org.springframework.dao.DataAccessException | IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "committed cancellation batch is unavailable", ex);
        }
    }

    public ResponseEntity<OrderCommandReceipt> amendment(long userId, String clientRequestId) {
        try {
            return commandResponse(orderService.amendment(userId, clientRequestId));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (org.springframework.dao.DataAccessException | IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "committed amendment result is unavailable", ex);
        }
    }

    public ResponseEntity<OrderCommandReceipt> cancellation(long userId, long orderId) {
        try {
            return commandResponse(orderService.cancellation(userId, orderId));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (org.springframework.dao.DataAccessException | IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "committed cancellation result is unavailable", ex);
        }
    }

    public ResponseEntity<com.surprising.trading.api.model.OrderSubmissionResponse> submission(long userId, String clientOrderId) {
        try {
            var result = orderService.submission(userId, clientOrderId);
            return ResponseEntity.status("TERMINAL".equals(result.outcome()) ? HttpStatus.OK : HttpStatus.ACCEPTED)
                    .cacheControl(org.springframework.http.CacheControl.noStore()).body(result);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (org.springframework.dao.DataAccessException | IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "committed submission result is unavailable", ex);
        }
    }

    public OrderResponse getByClientOrderId(long userId, String clientOrderId, Long minExportSequence) {
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

    public OrderQueryResponse openOrders(long userId, String instrumentId, int limit, String cursor, Long minExportSequence) {
        try {
            return orderService.openOrders(userId, instrumentId, limit, cursor, minExportSequence);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (ProjectionReadResult.ProjectionLagException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage(), ex);
        } catch (ProjectionReadResult.ResponseTooLargeException ex) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, ex.getMessage(), ex);
        }
    }

    public OrderQueryResponse historyOrders(long userId, String instrumentId, int limit, Long orderId, Long startTime, Long endTime, String cursor, Long minExportSequence, String status) {
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

    private ResponseEntity<OrderCommandReceipt> commandResponse(OrderCommandReceipt receipt) {
        HttpStatus status = switch(receipt.code()) {
            case "IDEMPOTENCY_CONFLICT" ->
                HttpStatus.CONFLICT;
            case "CLIENT_BACKPRESSURED" ->
                HttpStatus.TOO_MANY_REQUESTS;
            case "MATCHING_PENDING", "RESULT_UNKNOWN" ->
                HttpStatus.ACCEPTED;
            case "RESULT_UNKNOWN_OUTSIDE_RETENTION" ->
                HttpStatus.GONE;
            case "NOT_CONNECTED", "ADMIN_ACTION", "CLOSED", "MAX_POSITION_EXCEEDED", "UNKNOWN" ->
                HttpStatus.SERVICE_UNAVAILABLE;
            default ->
                HttpStatus.OK;
        };
        return ResponseEntity.status(status).cacheControl(org.springframework.http.CacheControl.noStore()).body(receipt);
    }
}
