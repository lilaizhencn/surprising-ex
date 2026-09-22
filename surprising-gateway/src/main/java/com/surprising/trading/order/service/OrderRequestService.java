package com.surprising.trading.order.service;

import com.surprising.trading.api.model.AmendOrderRequest;
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
import org.springframework.stereotype.Service;

/**
 * 共享原始 HTTP 与网关入口的请求校验、业务编排和结果转换；不持有 HTTP 路由。
 */
@Service()
public class OrderRequestService {

    private final OrderService orderService;

    private final AlgoOrderService algoOrderService;

    private final CancelAllAfterService cancelAllAfterService;

    public OrderRequestService(OrderService orderService, AlgoOrderService algoOrderService, CancelAllAfterService cancelAllAfterService) {
        this.orderService = orderService;
        this.algoOrderService = algoOrderService;
        this.cancelAllAfterService = cancelAllAfterService;
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

    public AlgoOrderResponse getAlgo(long algoOrderId) {
        try {
            return algoOrderService.get(algoOrderId);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        }
    }

    public AlgoOrderQueryResponse openAlgoOrders(long userId, String symbol, int limit) {
        try {
            return algoOrderService.openOrders(userId, symbol, limit);
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

    public ResponseEntity<OrderCommandReceipt> commandResult(UUID commandId) {
        try {
            return commandResponse(orderService.commandResult(commandId));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
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

    public OrderQueryResponse openOrders(long userId, String symbol, int limit, String cursor, Long minExportSequence) {
        try {
            return orderService.openOrders(userId, symbol, limit, cursor, minExportSequence);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (ProjectionReadResult.ProjectionLagException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage(), ex);
        } catch (ProjectionReadResult.ResponseTooLargeException ex) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, ex.getMessage(), ex);
        }
    }

    public OrderQueryResponse historyOrders(long userId, String symbol, int limit, Long orderId, Long startTime, Long endTime, String cursor, Long minExportSequence) {
        try {
            return orderService.historyOrders(userId, symbol, limit, orderId, startTime, endTime, cursor, minExportSequence);
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
        return ResponseEntity.status(status).body(receipt);
    }
}
