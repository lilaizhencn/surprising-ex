package com.surprising.trading.trigger.service;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.AdminTriggerOrderTimelineResponse;
import com.surprising.trading.api.model.TriggerOrderQueryResponse;
import com.surprising.trading.api.model.TriggerOrderResponse;
import com.surprising.trading.trigger.service.TriggerOrderService;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.stereotype.Service;

/**
 * 共享原始 HTTP 与网关入口的请求校验、业务编排和结果转换；不持有 HTTP 路由。
 */
@Service()
public class AdminTriggerOrderRequestService {

    private final TriggerOrderService triggerOrderService;

    public AdminTriggerOrderRequestService(TriggerOrderService triggerOrderService) {
        this.triggerOrderService = triggerOrderService;
    }

    public TriggerOrderQueryResponse orders(String adminUserId, String productLineHeader, String productLineValue, Long userId, String symbol, String status, Long triggerOrderId, int limit, String cursor, String sort) {
        requireAdmin(adminUserId);
        try {
            ProductLine productLine = productLine(productLineValue, productLineHeader);
            return triggerOrderService.adminOrders(userId, symbol, status, triggerOrderId, limit, cursor, sort, productLine);
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
