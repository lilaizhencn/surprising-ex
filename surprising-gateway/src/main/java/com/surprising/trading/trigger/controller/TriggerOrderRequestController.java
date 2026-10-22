package com.surprising.trading.trigger.controller;

import com.surprising.trading.trigger.model.OpenTriggerPageQuery;
import com.surprising.trading.trigger.utils.OpenTriggerCursors;

import com.surprising.trading.trigger.service.TriggerOrderAeronGateway;

import com.surprising.trading.api.model.BatchCancelTriggerOrdersRequest;
import com.surprising.trading.api.model.BatchPlaceTriggerOrderRequest;
import com.surprising.trading.api.model.CancelOpenTriggerOrdersRequest;
import com.surprising.trading.api.model.CancelTriggerOrderRequest;
import com.surprising.trading.api.model.PlaceTriggerOrderRequest;
import com.surprising.trading.api.model.TriggerOrderBatchResponse;
import com.surprising.trading.api.model.TriggerOrderQueryResponse;
import com.surprising.trading.api.model.TriggerOrderResponse;
import com.surprising.trading.trigger.service.TriggerOrderService;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import org.springframework.stereotype.Component;

/**
 * 本地协议与内部HTTP共用的请求绑定、参数校验及响应转换；业务由实际Service处理，不声明HTTP路由。
 */
@Component
public class TriggerOrderRequestController {

    private final TriggerOrderService triggerOrderService;
    private final com.surprising.trading.order.service.OrderService orderResults;

    public TriggerOrderRequestController(TriggerOrderService triggerOrderService,
            com.surprising.trading.order.service.OrderService orderResults) {
        this.triggerOrderService = triggerOrderService;
        this.orderResults = orderResults;
    }

    public com.surprising.trading.api.model.OrderCommandReceipt ocoSubmission(
            long userId, String takeProfitClientId, String stopLossClientId) {
        try {
            return orderResults.commandResult(userId,
                    triggerOrderService.ocoCommandId(userId, takeProfitClientId, stopLossClientId));
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage(), invalid);
        }
    }

    public com.surprising.trading.api.model.OrderCommandReceipt submission(long userId, String clientTriggerOrderId) {
        try {
            return orderResults.commandResult(userId, triggerOrderService.submissionCommandId(userId, clientTriggerOrderId));
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage(), invalid);
        }
    }

    public com.surprising.trading.api.model.OrderCommandReceipt cancellation(long userId, long triggerOrderId) {
        try {
            return orderResults.commandResult(userId, TriggerOrderAeronGateway.cancellationCommandId(userId, triggerOrderId));
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage(), invalid);
        }
    }

    public CompletionStage<TriggerOrderResponse> place(PlaceTriggerOrderRequest request) {
        try {
            return mapAsyncFailure(triggerOrderService.placeAsync(request), HttpStatus.CONFLICT);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage(), ex);
        }
    }

    public CompletionStage<TriggerOrderBatchResponse> placeBatch(BatchPlaceTriggerOrderRequest request) {
        try {
            return mapAsyncFailure(triggerOrderService.placeBatchAsync(request), HttpStatus.CONFLICT);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage(), ex);
        }
    }

    public CompletionStage<TriggerOrderResponse> cancel(CancelTriggerOrderRequest request) {
        try {
            return mapAsyncFailure(triggerOrderService.cancelAsync(request), HttpStatus.NOT_FOUND);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
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

    public TriggerOrderBatchResponse cancelBatch(BatchCancelTriggerOrdersRequest request) {
        try {
            return triggerOrderService.cancelBatch(request);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    public TriggerOrderBatchResponse cancelOpen(CancelOpenTriggerOrdersRequest request) {
        try {
            return triggerOrderService.cancelOpenOrders(request);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    public TriggerOrderResponse get(long userId, long triggerOrderId) {
        try {
            return triggerOrderService.get(userId, triggerOrderId);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        }
    }

    public TriggerOrderQueryResponse openOrders(long userId, String instrumentId, int limit, String cursor) {
        try {
            if (userId <= 0) throw new IllegalArgumentException("userId must be positive");
            return triggerOrderService.openOrders(userId, activeInstrument(instrumentId), activePage(limit, cursor));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    public static OpenTriggerPageQuery activePage(int limit, String cursor) {
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("limit must be in [1, 1000]");
        return new OpenTriggerPageQuery(limit, OpenTriggerCursors.decode(cursor));
    }

    private static String activeInstrument(String instrumentId) {
        if (instrumentId == null || instrumentId.isBlank()) return null;
        String value = instrumentId.trim().toUpperCase(java.util.Locale.ROOT);
        if (!com.surprising.product.api.utils.InstrumentIds.valid(value))
            throw new IllegalArgumentException("invalid instrumentId: " + instrumentId);
        return value;
    }
}
