package com.surprising.trading.trigger.service;

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
import org.springframework.stereotype.Service;

/**
 * 共享原始 HTTP 与网关入口的请求校验、业务编排和结果转换；不持有 HTTP 路由。
 */
@Service()
public class TriggerOrderRequestService {

    private final TriggerOrderService triggerOrderService;

    public TriggerOrderRequestService(TriggerOrderService triggerOrderService) {
        this.triggerOrderService = triggerOrderService;
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

    public TriggerOrderQueryResponse openOrders(long userId, String symbol, int limit, String cursor) {
        try {
            return triggerOrderService.openOrders(userId, symbol, limit, cursor);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }
}
