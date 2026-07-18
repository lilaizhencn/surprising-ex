package com.surprising.trading.order.controller;

import com.surprising.trading.api.TradingApiPaths;
import com.surprising.trading.api.model.AmendOrderBatchResponse;
import com.surprising.trading.api.model.AmendOrderRequest;
import com.surprising.trading.api.model.AmendOrderResponse;
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
import com.surprising.trading.api.model.OrderQueryResponse;
import com.surprising.trading.api.model.OrderResponse;
import com.surprising.trading.api.model.PlaceAlgoOrderRequest;
import com.surprising.trading.api.model.PlaceOrderRequest;
import com.surprising.trading.api.model.TestOrderResponse;
import com.surprising.trading.order.service.AlgoOrderService;
import com.surprising.trading.order.service.CancelAllAfterService;
import com.surprising.trading.order.service.OrderService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

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
    public OrderResponse place(@RequestBody PlaceOrderRequest request) {
        try {
            return orderService.place(request);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @PostMapping(TradingApiPaths.ORDER_BASE_PATH + "/batch")
    public OrderBatchResponse placeBatch(@RequestBody BatchPlaceOrderRequest request) {
        try {
            return orderService.placeBatch(request);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @PostMapping(TradingApiPaths.ORDER_BASE_PATH + "/test")
    public TestOrderResponse test(@RequestBody PlaceOrderRequest request) {
        try {
            return orderService.test(request);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @PostMapping(TradingApiPaths.ORDER_BASE_PATH + "/amend")
    public AmendOrderResponse amend(@RequestBody AmendOrderRequest request) {
        try {
            return orderService.amend(request);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage(), ex);
        }
    }

    @PostMapping(TradingApiPaths.ORDER_BASE_PATH + "/batch-amend")
    public AmendOrderBatchResponse amendBatch(@RequestBody BatchAmendOrdersRequest request) {
        try {
            return orderService.amendBatch(request);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @PostMapping(TradingApiPaths.ORDER_BASE_PATH + "/close-position")
    public OrderResponse closePosition(@RequestBody ClosePositionRequest request) {
        try {
            return orderService.closePosition(request);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        }
    }

    @PostMapping(TradingApiPaths.ORDER_BASE_PATH + "/cancel")
    public OrderResponse cancel(@RequestBody CancelOrderRequest request) {
        try {
            return orderService.cancel(request);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        }
    }

    @PostMapping(TradingApiPaths.ORDER_BASE_PATH + "/batch-cancel")
    public OrderBatchResponse cancelBatch(@RequestBody BatchCancelOrdersRequest request) {
        try {
            return orderService.cancelBatch(request);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
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
    public OrderResponse get(@PathVariable("orderId") long orderId) {
        try {
            return orderService.get(orderId);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        }
    }

    @GetMapping(TradingApiPaths.ORDER_BASE_PATH + "/by-client-order-id")
    public OrderResponse getByClientOrderId(@RequestParam("userId") long userId,
                                            @RequestParam("clientOrderId") String clientOrderId) {
        try {
            return orderService.getByClientOrderId(userId, clientOrderId);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        }
    }

    @GetMapping(TradingApiPaths.ORDER_BASE_PATH + "/open")
    public OrderQueryResponse openOrders(@RequestParam("userId") long userId,
                                         @RequestParam(value = "symbol", required = false) String symbol,
                                         @RequestParam(value = "limit", defaultValue = "100") int limit,
                                         @RequestParam(value = "cursor", required = false) String cursor) {
        try {
            return orderService.openOrders(userId, symbol, limit, cursor);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }
}
