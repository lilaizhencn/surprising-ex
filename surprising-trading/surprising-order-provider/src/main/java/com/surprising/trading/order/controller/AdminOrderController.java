package com.surprising.trading.order.controller;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.AdminBatchCancelOrdersRequest;
import com.surprising.trading.api.model.AdminCancelBySymbolRequest;
import com.surprising.trading.api.model.AdminCancelOrderRequest;
import com.surprising.trading.api.model.AdminCancelOrderResult;
import com.surprising.trading.api.model.AdminCancelOrdersResponse;
import com.surprising.trading.api.model.AdminCancelOrdersPreviewResponse;
import com.surprising.trading.api.model.AdminMatchResultQueryResponse;
import com.surprising.trading.api.model.AdminMatchTradeQueryResponse;
import com.surprising.trading.api.model.AdminOrderEventQueryResponse;
import com.surprising.trading.api.model.AdminOrderTimelineResponse;
import com.surprising.trading.api.model.OrderQueryResponse;
import com.surprising.trading.order.service.OrderService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/admin/trading")
public class AdminOrderController {

    private final OrderService orderService;

    public AdminOrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @GetMapping("/orders")
    public OrderQueryResponse orders(@RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
                                     @RequestHeader(value = "X-Product-Line", required = false) String productLineHeader,
                                     @RequestParam(value = "productLine", required = false) String productLineValue,
                                     @RequestParam(value = "userId", required = false) Long userId,
                                     @RequestParam(value = "symbol", required = false) String symbol,
                                     @RequestParam(value = "status", required = false) String status,
                                     @RequestParam(value = "orderId", required = false) Long orderId,
                                     @RequestParam(value = "limit", defaultValue = "100") int limit,
                                     @RequestParam(value = "cursor", required = false) String cursor,
                                     @RequestParam(value = "sort", required = false) String sort) {
        requireAdmin(adminUserId);
        try {
            ProductLine productLine = productLine(productLineValue, productLineHeader);
            return orderService.adminOrders(userId, symbol, status, orderId, limit, cursor, sort, productLine);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @GetMapping("/orders/{orderId}/events")
    public AdminOrderEventQueryResponse events(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @PathVariable("orderId") long orderId,
            @RequestParam(value = "limit", defaultValue = "100") int limit) {
        requireAdmin(adminUserId);
        try {
            return orderService.adminOrderEvents(orderId, limit);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @GetMapping("/orders/{orderId}/match-results")
    public AdminMatchResultQueryResponse matchResults(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @PathVariable("orderId") long orderId,
            @RequestParam(value = "limit", defaultValue = "100") int limit) {
        requireAdmin(adminUserId);
        try {
            return orderService.adminMatchResults(orderId, limit);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @GetMapping("/orders/{orderId}/timeline")
    public AdminOrderTimelineResponse timeline(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestHeader(value = "X-Product-Line", required = false) String productLineHeader,
            @RequestParam(value = "productLine", required = false) String productLineValue,
            @PathVariable("orderId") long orderId) {
        requireAdmin(adminUserId);
        try {
            ProductLine productLine = productLine(productLineValue, productLineHeader);
            return orderService.adminOrderTimeline(orderId, productLine);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        }
    }

    @GetMapping("/orders/trades")
    public AdminMatchTradeQueryResponse trades(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestHeader(value = "X-Product-Line", required = false) String productLineHeader,
            @RequestParam(value = "productLine", required = false) String productLineValue,
            @RequestParam(value = "userId", required = false) Long userId,
            @RequestParam(value = "orderId", required = false) Long orderId,
            @RequestParam(value = "symbol", required = false) String symbol,
            @RequestParam(value = "limit", defaultValue = "100") int limit,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "sort", required = false) String sort) {
        requireAdmin(adminUserId);
        try {
            ProductLine productLine = productLine(productLineValue, productLineHeader);
            return orderService.adminMatchTrades(userId, orderId, symbol, limit, cursor, sort, productLine);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @PostMapping("/orders/{orderId}/cancel")
    public AdminCancelOrderResult cancelOrder(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestHeader(value = "X-Product-Line", required = false) String productLineHeader,
            @RequestParam(value = "productLine", required = false) String productLineValue,
            @PathVariable("orderId") long orderId,
            @Valid @RequestBody(required = false) AdminCancelOrderRequest request) {
        requireAdmin(adminUserId);
        try {
            ProductLine productLine = productLine(productLineValue, productLineHeader);
            return orderService.adminCancelOrder(orderId, request == null ? null : request.reason(), productLine);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        }
    }

    @GetMapping("/orders/cancel-preview")
    public AdminCancelOrdersPreviewResponse cancelPreview(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestHeader(value = "X-Product-Line", required = false) String productLineHeader,
            @RequestParam(value = "productLine", required = false) String productLineValue,
            @RequestParam(value = "userId", required = false) Long userId,
            @RequestParam(value = "symbol", required = false) String symbol,
            @RequestParam(value = "limit", defaultValue = "100") int limit) {
        requireAdmin(adminUserId);
        try {
            ProductLine productLine = productLine(productLineValue, productLineHeader);
            return orderService.adminCancelPreview(userId, symbol, limit, productLine);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @PostMapping("/orders/cancel")
    public AdminCancelOrdersResponse cancelOrders(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestHeader(value = "X-Product-Line", required = false) String productLineHeader,
            @RequestParam(value = "productLine", required = false) String productLineValue,
            @Valid @RequestBody(required = false) AdminBatchCancelOrdersRequest request) {
        requireAdmin(adminUserId);
        try {
            ProductLine productLine = productLine(productLineValue, productLineHeader);
            return orderService.adminCancelOrders(request, productLine);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @PostMapping("/orders/cancel-by-symbol")
    public AdminCancelOrdersResponse cancelBySymbol(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestHeader(value = "X-Product-Line", required = false) String productLineHeader,
            @RequestParam(value = "productLine", required = false) String productLineValue,
            @Valid @RequestBody AdminCancelBySymbolRequest request) {
        requireAdmin(adminUserId);
        try {
            ProductLine productLine = productLine(productLineValue, productLineHeader);
            return orderService.adminCancelBySymbol(request, productLine);
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
