package com.surprising.trading.order.controller;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.AdminBatchCancelOrdersRequest;
import com.surprising.trading.api.model.AdminCancelBySymbolRequest;
import com.surprising.trading.api.model.AdminCancelOrderRequest;
import com.surprising.trading.api.model.AdminCancelOrderResult;
import com.surprising.trading.api.model.AdminCancelOrdersResponse;
import com.surprising.trading.api.model.AdminCancelOrdersPreviewResponse;
import com.surprising.trading.api.model.OrderQueryResponse;
import com.surprising.trading.order.repository.ProjectionReadResult;
import com.surprising.trading.order.service.OrderService;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.stereotype.Component;

/**
 * 本地协议与内部HTTP共用的请求绑定、参数校验及响应转换；业务由实际Service处理，不声明HTTP路由。
 */
@Component
public class AdminOrderRequestController {
    public tools.jackson.databind.node.ObjectNode metrics(String adminUserId,String productHeader,String productValue,int window,int limit) {
        requireAdmin(adminUserId);
        try {
            if (!java.util.Set.of(60,360,1440,10080).contains(window) || limit < 1 || limit > 100)
                throw new IllegalArgumentException("statistics window must be 60,360,1440,10080 and limit in [1,100]");
            return orderService.tradingMetrics(window,limit,productLine(productValue,productHeader));
        }
        catch(IllegalArgumentException invalid) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,invalid.getMessage(),invalid); }
    }

    private final OrderService orderService;

    public AdminOrderRequestController(OrderService orderService) {
        this.orderService = orderService;
    }

    public OrderQueryResponse orders(String adminUserId, String productLineHeader, String productLineValue, Long userId, String instrumentId, String status, Long orderId, int limit, String cursor, String sort) {
        requireAdmin(adminUserId);
        try {
            ProductLine productLine = productLine(productLineValue, productLineHeader);
            return orderService.adminOrders(userId, instrumentId, status, orderId, limit, cursor, sort, productLine);
        } catch (ProjectionReadResult.ResponseTooLargeException ex) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, ex.getMessage(), ex);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    public com.surprising.trading.api.model.AdminTradeQueryResponse trades(String adminUserId, String productLineHeader,
            String productLineValue, Long userId, String instrumentId, Long orderId, int limit, String cursor, String sort) {
        requireAdmin(adminUserId);
        try {
            if (userId != null && userId <= 0 || orderId != null && orderId <= 0 || limit < 1 || limit > 1000)
                throw new IllegalArgumentException("invalid trade query");
            instrumentId = instrumentId == null || instrumentId.isBlank() ? null : instrumentId.trim().toUpperCase(java.util.Locale.ROOT);
            if (instrumentId != null) com.surprising.product.api.utils.InstrumentIds.parse(instrumentId);
            String ordering = sort == null || sort.isBlank() ? "eventTime.desc" : sort.trim();
            if (!java.util.Set.of("eventTime.asc", "eventTime.desc").contains(ordering)) throw new IllegalArgumentException("invalid trade sort");
            boolean ascending = ordering.endsWith("asc");
            ProductLine requested = productLine(productLineValue, productLineHeader);
            ProductLine effective = requested == null ? orderService.currentProductLine() : requested;
            var after = cursor == null || cursor.isBlank() ? null : com.surprising.trading.order.utils.TradeReadCursors.execution(cursor,
                    com.surprising.trading.order.utils.TradeReadCursors.executionScope(effective,userId,instrumentId,orderId,ascending));
            return orderService.adminTrades(userId, instrumentId, orderId, limit, after, ascending, requested);
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage(), invalid);
        }
    }

    public AdminCancelOrderResult cancelOrder(String adminUserId, String productLineHeader, String productLineValue, long orderId, AdminCancelOrderRequest request) {
        requireAdmin(adminUserId);
        try {
            ProductLine productLine = productLine(productLineValue, productLineHeader);
            return orderService.adminCancelOrder(orderId, request == null ? null : request.reason(), productLine);
        } catch (ProjectionReadResult.ResponseTooLargeException ex) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, ex.getMessage(), ex);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        }
    }

    public AdminCancelOrdersPreviewResponse cancelPreview(String adminUserId, String productLineHeader, String productLineValue, Long userId, String instrumentId, int limit) {
        requireAdmin(adminUserId);
        try {
            ProductLine productLine = productLine(productLineValue, productLineHeader);
            return orderService.adminCancelPreview(userId, instrumentId, limit, productLine);
        } catch (ProjectionReadResult.ResponseTooLargeException ex) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, ex.getMessage(), ex);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    public AdminCancelOrdersResponse cancelOrders(String adminUserId, String productLineHeader, String productLineValue, AdminBatchCancelOrdersRequest request) {
        requireAdmin(adminUserId);
        try {
            ProductLine productLine = productLine(productLineValue, productLineHeader);
            return orderService.adminCancelOrders(request, productLine);
        } catch (ProjectionReadResult.ResponseTooLargeException ex) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, ex.getMessage(), ex);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    public AdminCancelOrdersResponse cancelBySymbol(String adminUserId, String productLineHeader, String productLineValue, AdminCancelBySymbolRequest request) {
        requireAdmin(adminUserId);
        try {
            ProductLine productLine = productLine(productLineValue, productLineHeader);
            return orderService.adminCancelBySymbol(request, productLine);
        } catch (ProjectionReadResult.ResponseTooLargeException ex) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, ex.getMessage(), ex);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
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
