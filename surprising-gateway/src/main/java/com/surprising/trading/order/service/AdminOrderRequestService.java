package com.surprising.trading.order.service;

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
import org.springframework.stereotype.Service;

/**
 * 共享原始 HTTP 与网关入口的请求校验、业务编排和结果转换；不持有 HTTP 路由。
 */
@Service()
public class AdminOrderRequestService {

    private final OrderService orderService;

    public AdminOrderRequestService(OrderService orderService) {
        this.orderService = orderService;
    }

    public OrderQueryResponse orders(String adminUserId, String productLineHeader, String productLineValue, Long userId, String symbol, String status, Long orderId, int limit, String cursor, String sort) {
        requireAdmin(adminUserId);
        try {
            ProductLine productLine = productLine(productLineValue, productLineHeader);
            return orderService.adminOrders(userId, symbol, status, orderId, limit, cursor, sort, productLine);
        } catch (ProjectionReadResult.ResponseTooLargeException ex) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, ex.getMessage(), ex);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
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

    public AdminCancelOrdersPreviewResponse cancelPreview(String adminUserId, String productLineHeader, String productLineValue, Long userId, String symbol, int limit) {
        requireAdmin(adminUserId);
        try {
            ProductLine productLine = productLine(productLineValue, productLineHeader);
            return orderService.adminCancelPreview(userId, symbol, limit, productLine);
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
