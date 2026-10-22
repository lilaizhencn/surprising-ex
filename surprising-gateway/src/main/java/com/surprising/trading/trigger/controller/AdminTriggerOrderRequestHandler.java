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
 * HTTP/本地路由共用的协议适配：绑定格式和错误响应；业务由实际Service处理，不声明HTTP路由。
 */
@Component
public class AdminTriggerOrderRequestHandler {

    private final TriggerOrderService triggerOrderService;

    public AdminTriggerOrderRequestHandler(TriggerOrderService triggerOrderService) {
        this.triggerOrderService = triggerOrderService;
    }

    public TriggerOrderQueryResponse orders(String adminUserId, String productLineHeader, String productLineValue, Long userId, String instrumentId, String status, Long triggerOrderId, int limit, String cursor, String sort) {
        requireAdmin(adminUserId);
        try {
            ProductLine productLine = productLine(productLineValue, productLineHeader);
            if (userId != null && userId <= 0) throw new IllegalArgumentException("userId must be positive");
            if (triggerOrderId != null && triggerOrderId <= 0) throw new IllegalArgumentException("triggerOrderId must be positive");
            if (limit < 1 || limit > 1000) throw new IllegalArgumentException("limit must be in [1, 1000]");
            String instrument = instrumentId == null || instrumentId.isBlank() ? null : instrumentId.trim().toUpperCase(java.util.Locale.ROOT);
            if (instrument != null) com.surprising.product.api.utils.InstrumentIds.parse(instrument);
            var state = status == null || status.isBlank() ? null : com.surprising.aeron.protocol.CoreTriggerOrderStatus.valueOf(status.trim().toUpperCase(java.util.Locale.ROOT));
            String ordering = sort == null || sort.isBlank() ? "createdAt.desc" : sort.trim();
            if (!java.util.Set.of("createdAt.desc", "createdAt.asc").contains(ordering)) throw new IllegalArgumentException("invalid trigger sort");
            boolean ascending = ordering.endsWith("asc");
            ProductLine effective = productLine == null ? triggerOrderService.currentProductLine() : productLine;
            var after = cursor == null || cursor.isBlank() ? null : com.surprising.trading.trigger.utils.TriggerReadCursors.decode(cursor,
                    com.surprising.trading.trigger.utils.TriggerReadCursors.scope(effective,userId,instrument,state,triggerOrderId,ascending));
            return triggerOrderService.adminOrders(userId,instrument,state,triggerOrderId,limit,after,ascending,productLine);
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
