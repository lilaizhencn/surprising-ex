package com.surprising.trading.trigger.controller;

import com.surprising.trading.api.TradingApiPaths;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 用户管理止盈止损触发单的 REST 门面。
 *
 * <p>前端流量通常通过公共网关服务名 {@code trading-trigger} 访问这些路由；
 * 内部服务可以直接调用 RPC API。</p>
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
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage(), ex);
        }
    }

    @PostMapping(TradingApiPaths.TRIGGER_ORDER_BASE_PATH + "/batch")
    public TriggerOrderBatchResponse placeBatch(@RequestBody BatchPlaceTriggerOrderRequest request) {
        try {
            return triggerOrderService.placeBatch(request);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage(), ex);
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

    @PostMapping(TradingApiPaths.TRIGGER_ORDER_BASE_PATH + "/batch-cancel")
    public TriggerOrderBatchResponse cancelBatch(@RequestBody BatchCancelTriggerOrdersRequest request) {
        try {
            return triggerOrderService.cancelBatch(request);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @PostMapping(TradingApiPaths.TRIGGER_ORDER_BASE_PATH + "/cancel-open")
    public TriggerOrderBatchResponse cancelOpen(@RequestBody CancelOpenTriggerOrdersRequest request) {
        try {
            return triggerOrderService.cancelOpenOrders(request);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @GetMapping(TradingApiPaths.TRIGGER_ORDER_BASE_PATH + "/{triggerOrderId}")
    public TriggerOrderResponse get(@RequestParam("userId") long userId,
                                    @PathVariable("triggerOrderId") long triggerOrderId) {
        try {
            return triggerOrderService.get(userId, triggerOrderId);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        }
    }

    @GetMapping(TradingApiPaths.TRIGGER_ORDER_BASE_PATH + "/open")
    public TriggerOrderQueryResponse openOrders(@RequestParam("userId") long userId,
                                                @RequestParam(value = "symbol", required = false) String symbol,
                                                @RequestParam(value = "limit", defaultValue = "100") int limit,
                                                @RequestParam(value = "cursor", required = false) String cursor) {
        try {
            return triggerOrderService.openOrders(userId, symbol, limit, cursor);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }
}
