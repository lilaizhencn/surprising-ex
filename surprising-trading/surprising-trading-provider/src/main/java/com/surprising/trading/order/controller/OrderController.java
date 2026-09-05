package com.surprising.trading.order.controller;

import com.surprising.trading.api.TradingApiPaths;
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
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

@RestController
public class OrderController {

    private final OrderService orderService;
    private final AlgoOrderService algoOrderService;
    private final CancelAllAfterService cancelAllAfterService;

    public OrderController(OrderService orderService,
                           AlgoOrderService algoOrderService,
                           CancelAllAfterService cancelAllAfterService) {
        this.orderService = orderService;
        this.algoOrderService = algoOrderService;
        this.cancelAllAfterService = cancelAllAfterService;
    }

    @PostMapping(TradingApiPaths.ORDER_BASE_PATH)
    public CompletionStage<ResponseEntity<OrderCommandReceipt>> place(@Valid @RequestBody PlaceOrderRequest request) {
        try {
            return mapAsyncFailure(
                    orderService.placeCommandAsync(request).thenApply(this::commandResponse), HttpStatus.CONFLICT);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage(), ex);
        }
    }

    @PostMapping(TradingApiPaths.ORDER_BASE_PATH + "/batch")
    public CompletionStage<ResponseEntity<OrderCommandReceipt>> placeBatch(
            @Valid @RequestBody BatchPlaceOrderRequest request) {
        try {
            return mapAsyncFailure(
                    orderService.placeBatchCommandAsync(request).thenApply(this::commandResponse), HttpStatus.CONFLICT);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage(), ex);
        }
    }

    @PostMapping(TradingApiPaths.ORDER_BASE_PATH + "/test")
    public TestOrderResponse test(@Valid @RequestBody PlaceOrderRequest request) {
        try {
            return orderService.test(request);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @PostMapping(TradingApiPaths.ORDER_BASE_PATH + "/amend")
    public CompletionStage<ResponseEntity<OrderCommandReceipt>> amend(@Valid @RequestBody AmendOrderRequest request) {
        try {
            return mapAsyncFailure(
                    orderService.amendCommandAsync(request).thenApply(this::commandResponse), HttpStatus.CONFLICT);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage(), ex);
        }
    }

    @PostMapping(TradingApiPaths.ORDER_BASE_PATH + "/batch-amend")
    public CompletionStage<ResponseEntity<OrderCommandReceipt>> amendBatch(
            @Valid @RequestBody BatchAmendOrdersRequest request) {
        try {
            return mapAsyncFailure(
                    orderService.amendBatchCommandAsync(request).thenApply(this::commandResponse), HttpStatus.CONFLICT);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @PostMapping(TradingApiPaths.ORDER_BASE_PATH + "/close-position")
    public OrderResponse closePosition(@Valid @RequestBody ClosePositionRequest request) {
        try {
            return orderService.closePosition(request);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        }
    }

    @PostMapping(TradingApiPaths.ORDER_BASE_PATH + "/cancel")
    public CompletionStage<ResponseEntity<OrderCommandReceipt>> cancel(@RequestBody CancelOrderRequest request) {
        try {
            return mapAsyncFailure(
                    orderService.cancelCommandAsync(request).thenApply(this::commandResponse), HttpStatus.NOT_FOUND);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        }
    }

    @PostMapping(TradingApiPaths.ORDER_BASE_PATH + "/batch-cancel")
    public CompletionStage<ResponseEntity<OrderCommandReceipt>> cancelBatch(
            @Valid @RequestBody BatchCancelOrdersRequest request) {
        try {
            return mapAsyncFailure(
                    orderService.cancelBatchCommandAsync(request).thenApply(this::commandResponse), HttpStatus.NOT_FOUND);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    private static <T> CompletionStage<T> mapAsyncFailure(
            CompletionStage<T> stage, HttpStatus illegalStateStatus) {
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
                mapped.completeExceptionally(
                        new ResponseStatusException(HttpStatus.BAD_REQUEST, cause.getMessage(), cause));
            } else if (cause instanceof IllegalStateException) {
                mapped.completeExceptionally(
                        new ResponseStatusException(illegalStateStatus, cause.getMessage(), cause));
            } else {
                mapped.completeExceptionally(cause);
            }
        });
        return mapped;
    }

    @PostMapping(TradingApiPaths.ORDER_BASE_PATH + "/cancel-open")
    public OrderBatchResponse cancelOpen(@RequestBody CancelOpenOrdersRequest request) {
        try {
            return orderService.cancelOpenOrders(request);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @PostMapping(TradingApiPaths.ORDER_BASE_PATH + "/cancel-all-after")
    public CancelAllAfterResponse cancelAllAfter(@RequestBody CancelAllAfterRequest request) {
        try {
            return cancelAllAfterService.set(request);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @PostMapping(TradingApiPaths.ORDER_BASE_PATH + "/algo")
    public AlgoOrderResponse placeAlgo(@RequestBody PlaceAlgoOrderRequest request) {
        try {
            return algoOrderService.place(request);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @PostMapping(TradingApiPaths.ORDER_BASE_PATH + "/algo/cancel")
    public AlgoOrderResponse cancelAlgo(@RequestBody CancelAlgoOrderRequest request) {
        try {
            return algoOrderService.cancel(request);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        }
    }

    @PostMapping(TradingApiPaths.ORDER_BASE_PATH + "/algo/cancel-open")
    public AlgoOrderBatchResponse cancelOpenAlgo(@RequestBody CancelOpenAlgoOrdersRequest request) {
        try {
            return algoOrderService.cancelOpen(request);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @GetMapping(TradingApiPaths.ORDER_BASE_PATH + "/algo/{algoOrderId}")
    public AlgoOrderResponse getAlgo(@PathVariable("algoOrderId") long algoOrderId) {
        try {
            return algoOrderService.get(algoOrderId);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        }
    }

    @GetMapping(TradingApiPaths.ORDER_BASE_PATH + "/algo/open")
    public AlgoOrderQueryResponse openAlgoOrders(@RequestParam("userId") long userId,
                                                @RequestParam(value = "symbol", required = false) String symbol,
                                                @RequestParam(value = "limit", defaultValue = "100") int limit) {
        try {
            return algoOrderService.openOrders(userId, symbol, limit);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @GetMapping(TradingApiPaths.ORDER_BASE_PATH + "/{orderId}")
    public OrderResponse get(@RequestParam("userId") long userId,
                             @PathVariable("orderId") long orderId,
                             @RequestParam(value = "minExportSequence", required = false)
                             Long minExportSequence) {
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

    @GetMapping(TradingApiPaths.ORDER_BASE_PATH + "/commands/{commandId}")
    public ResponseEntity<OrderCommandReceipt> commandResult(@PathVariable("commandId") UUID commandId) {
        try {
            return commandResponse(orderService.commandResult(commandId));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @GetMapping(TradingApiPaths.ORDER_BASE_PATH + "/by-client-order-id")
    public OrderResponse getByClientOrderId(@RequestParam("userId") long userId,
                                            @RequestParam("clientOrderId") String clientOrderId,
                                            @RequestParam(value = "minExportSequence", required = false)
                                            Long minExportSequence) {
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

    @GetMapping(TradingApiPaths.ORDER_BASE_PATH + "/open")
    public OrderQueryResponse openOrders(@RequestParam("userId") long userId,
                                         @RequestParam(value = "symbol", required = false) String symbol,
                                         @RequestParam(value = "limit", defaultValue = "100") int limit,
                                         @RequestParam(value = "cursor", required = false) String cursor,
                                         @RequestParam(value = "minExportSequence", required = false)
                                         Long minExportSequence) {
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

    @GetMapping(TradingApiPaths.ORDER_BASE_PATH + "/history")
    public OrderQueryResponse historyOrders(@RequestParam("userId") long userId,
                                            @RequestParam(value = "symbol", required = false) String symbol,
                                            @RequestParam(value = "limit", defaultValue = "100") int limit,
                                            @RequestParam(value = "orderId", required = false) Long orderId,
                                            @RequestParam(value = "startTime", required = false) Long startTime,
                                            @RequestParam(value = "endTime", required = false) Long endTime,
                                            @RequestParam(value = "cursor", required = false) String cursor,
                                            @RequestParam(value = "minExportSequence", required = false)
                                            Long minExportSequence) {
        try {
            return orderService.historyOrders(userId, symbol, limit, orderId, startTime, endTime, cursor,
                    minExportSequence);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (ProjectionReadResult.ProjectionLagException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage(), ex);
        } catch (ProjectionReadResult.ResponseTooLargeException ex) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, ex.getMessage(), ex);
        }
    }

    private ResponseEntity<OrderCommandReceipt> commandResponse(OrderCommandReceipt receipt) {
        HttpStatus status = switch (receipt.code()) {
            case "IDEMPOTENCY_CONFLICT" -> HttpStatus.CONFLICT;
            case "CLIENT_BACKPRESSURED" -> HttpStatus.TOO_MANY_REQUESTS;
            case "MATCHING_PENDING", "RESULT_UNKNOWN" -> HttpStatus.ACCEPTED;
            case "RESULT_UNKNOWN_OUTSIDE_RETENTION" -> HttpStatus.GONE;
            case "NOT_CONNECTED", "ADMIN_ACTION", "CLOSED", "MAX_POSITION_EXCEEDED", "UNKNOWN" ->
                    HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.OK;
        };
        return ResponseEntity.status(status).body(receipt);
    }
}
