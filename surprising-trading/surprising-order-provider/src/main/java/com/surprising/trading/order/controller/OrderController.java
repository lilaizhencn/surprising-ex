package com.surprising.trading.order.controller;

import com.surprising.trading.api.TradingApiPaths;
import com.surprising.trading.api.model.CancelOrderRequest;
import com.surprising.trading.api.model.OrderQueryResponse;
import com.surprising.trading.api.model.OrderResponse;
import com.surprising.trading.api.model.PlaceOrderRequest;
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

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping(TradingApiPaths.ORDER_BASE_PATH)
    public OrderResponse place(@RequestBody PlaceOrderRequest request) {
        try {
            return orderService.place(request);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
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
                                         @RequestParam(value = "limit", defaultValue = "100") int limit) {
        try {
            return orderService.openOrders(userId, symbol, limit);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }
}
