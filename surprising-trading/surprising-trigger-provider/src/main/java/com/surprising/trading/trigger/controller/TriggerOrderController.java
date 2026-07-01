package com.surprising.trading.trigger.controller;

import com.surprising.trading.api.TradingApiPaths;
import com.surprising.trading.api.model.CancelTriggerOrderRequest;
import com.surprising.trading.api.model.PlaceTriggerOrderRequest;
import com.surprising.trading.api.model.TriggerOrderQueryResponse;
import com.surprising.trading.api.model.TriggerOrderResponse;
import com.surprising.trading.trigger.service.TriggerOrderService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * REST facade for user-managed TP/SL trigger orders.
 *
 * <p>Frontend traffic should normally reach these routes through the public gateway service name
 * {@code trading-trigger}; internal services can call the RPC API directly.</p>
 */
@RestController
public class TriggerOrderController {

    private final TriggerOrderService triggerOrderService;

    public TriggerOrderController(TriggerOrderService triggerOrderService) {
        this.triggerOrderService = triggerOrderService;
    }

    @PostMapping(TradingApiPaths.TRIGGER_ORDER_BASE_PATH)
    public TriggerOrderResponse place(@RequestBody PlaceTriggerOrderRequest request) {
        try {
            return triggerOrderService.place(request);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @PostMapping(TradingApiPaths.TRIGGER_ORDER_BASE_PATH + "/cancel")
    public TriggerOrderResponse cancel(@RequestBody CancelTriggerOrderRequest request) {
        try {
            return triggerOrderService.cancel(request);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        }
    }

    @GetMapping(TradingApiPaths.TRIGGER_ORDER_BASE_PATH + "/{triggerOrderId}")
    public TriggerOrderResponse get(@PathVariable("triggerOrderId") long triggerOrderId) {
        try {
            return triggerOrderService.get(triggerOrderId);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        }
    }

    @GetMapping(TradingApiPaths.TRIGGER_ORDER_BASE_PATH + "/open")
    public TriggerOrderQueryResponse openOrders(@RequestParam("userId") long userId,
                                                @RequestParam(value = "symbol", required = false) String symbol,
                                                @RequestParam(value = "limit", defaultValue = "100") int limit) {
        try {
            return triggerOrderService.openOrders(userId, symbol, limit);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }
}
