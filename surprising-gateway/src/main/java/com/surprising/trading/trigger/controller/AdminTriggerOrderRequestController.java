package com.surprising.trading.trigger.controller;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.AdminTriggerOrderTimelineResponse;
import com.surprising.trading.api.model.TriggerOrderQueryResponse;
import com.surprising.trading.api.model.TriggerOrderResponse;
import com.surprising.trading.trigger.service.TriggerOrderService;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.stereotype.Component;

/**
 * 本地协议与内部HTTP共用的请求绑定、参数校验及响应转换；业务由实际Service处理，不声明HTTP路由。
 */
@Component
public class AdminTriggerOrderRequestController {

    private final TriggerOrderService triggerOrderService;

    public AdminTriggerOrderRequestController(TriggerOrderService triggerOrderService) {
        this.triggerOrderService = triggerOrderService;
    }

    public TriggerOrderQueryResponse orders(String adminUserId, String productLineHeader, String productLineValue, Long userId, String instrumentId, String status, Long triggerOrderId, int limit, String cursor, String sort) {
        requireAdmin(adminUserId);
        try {
            ProductLine productLine = productLine(productLineValue, productLineHeader);
            return triggerOrderService.adminOrders(userId, instrumentId, status, triggerOrderId, limit, cursor, sort, productLine);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    public TriggerOrderResponse order(String adminUserId, String productLineHeader, String productLineValue, long triggerOrderId) {
        requireAdmin(adminUserId);
        try {
            ProductLine productLine = productLine(productLineValue, productLineHeader);
            return triggerOrderService.get(triggerOrderId, productLine);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        }
    }

    public AdminTriggerOrderTimelineResponse timeline(String adminUserId, String productLineHeader, String productLineValue, long triggerOrderId) {
        requireAdmin(adminUserId);
        try {
            ProductLine productLine = productLine(productLineValue, productLineHeader);
            return triggerOrderService.adminTimeline(triggerOrderId, productLine);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        }
    }

    private void requireAdmin(String adminUserId) {
        if (adminUserId == null || adminUserId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "admin gateway header is required");
        }
    }

    private ProductLine productLine(String queryValue, String headerValue) {
        String value = queryValue == null || queryValue.isBlank() ? headerValue : queryValue;
        if (value == null || value.isBlank()) {
            return null;
        }
        return ProductLine.requireExternalCode(value);
    }
}
