package com.surprising.trading.order.controller;

import com.surprising.aeron.protocol.CoreTriggerOrderStatus;
import com.surprising.gateway.provider.product.GatewayUserContext;
import com.surprising.product.api.ProductLine;
import com.surprising.product.api.utils.InstrumentIds;
import com.surprising.trading.api.model.AdminBatchCancelOrdersRequest;
import com.surprising.trading.api.model.AdminCancelByInstrumentRequest;
import com.surprising.trading.api.model.AdminCancelOrderRequest;
import com.surprising.trading.api.model.AdminCancelOrderResult;
import com.surprising.trading.api.model.AdminCancelOrdersPreviewResponse;
import com.surprising.trading.api.model.AdminCancelOrdersResponse;
import com.surprising.trading.api.model.AdminTradeQueryResponse;
import com.surprising.trading.api.model.AdminTriggerOrderTimelineResponse;
import com.surprising.trading.api.model.OrderQueryResponse;
import com.surprising.trading.api.model.TriggerOrderQueryResponse;
import com.surprising.trading.api.model.TriggerOrderResponse;
import com.surprising.trading.order.repository.ProjectionReadResult;
import com.surprising.trading.order.service.OrderQueryService;
import com.surprising.trading.order.service.OrderService;
import com.surprising.trading.order.utils.TradeReadCursors;
import com.surprising.trading.trigger.service.TriggerOrderService;
import com.surprising.trading.trigger.utils.TriggerReadCursors;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.node.ObjectNode;

@RestController
public class AdminTradingController {
    private final OrderService orderService;
    private final OrderQueryService orderQueryService;
    private final TriggerOrderService triggerOrderService;

    @org.springframework.beans.factory.annotation.Autowired
    public AdminTradingController(OrderService orderService,
                                  OrderQueryService orderQueryService,
                                  TriggerOrderService triggerOrderService) {
        this.orderService = orderService;
        this.orderQueryService = orderQueryService;
        this.triggerOrderService = triggerOrderService;
    }

    public AdminTradingController(OrderService orderService,
                                  TriggerOrderService triggerOrderService) {
        this(orderService, null, triggerOrderService);
    }

    @GetMapping("/api/v1/admin/trading/orders/metrics")
    public ObjectNode metrics(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestHeader(value = "X-Product-Line", required = false) String headerProductLine,
            @RequestParam(value = "productLine", required = false) String queryProductLine,
            @RequestParam(value = "windowMinutes", defaultValue = "1440") int windowMinutes,
            @RequestParam(value = "limit", defaultValue = "20") int limit) {
        GatewayUserContext.requireAdminUserId(adminUserId);
        try {
            if (!Set.of(60, 360, 1440, 10080).contains(windowMinutes) || limit < 1 || limit > 100) {
                throw new IllegalArgumentException("statistics window must be 60,360,1440,10080 and limit in [1,100]");
            }
            return orderQueryService.tradingMetrics(windowMinutes, limit, TradingControllerSupport.productLine(queryProductLine, headerProductLine));
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage(), invalid);
        }
    }

    @GetMapping("/api/v1/admin/trading/orders/trades")
    public AdminTradeQueryResponse trades(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestHeader(value = "X-Product-Line", required = false) String headerProductLine,
            @RequestParam(value = "productLine", required = false) String queryProductLine,
            @RequestParam(value = "userId", required = false) Long userId,
            @RequestParam(value = "instrumentId", required = false) String instrumentId,
            @RequestParam(value = "orderId", required = false) Long orderId,
            @RequestParam(value = "limit", defaultValue = "100") int limit,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "sort", required = false) String sort) {
        GatewayUserContext.requireAdminUserId(adminUserId);
        try {
            if (userId != null && userId <= 0 || orderId != null && orderId <= 0 || limit < 1 || limit > 1000) {
                throw new IllegalArgumentException("invalid trade query");
            }
            instrumentId = instrumentId == null || instrumentId.isBlank() ? null : instrumentId.trim().toUpperCase(Locale.ROOT);
            if (instrumentId != null) InstrumentIds.parse(instrumentId);
            String ordering = sort == null || sort.isBlank() ? "eventTime.desc" : sort.trim();
            if (!Set.of("eventTime.asc", "eventTime.desc").contains(ordering)) {
                throw new IllegalArgumentException("invalid trade sort");
            }
            boolean ascending = ordering.endsWith("asc");
            ProductLine requested = TradingControllerSupport.productLine(queryProductLine, headerProductLine);
            ProductLine effective = requested == null ? orderQueryService.currentProductLine() : requested;
            var after = cursor == null || cursor.isBlank() ? null : TradeReadCursors.execution(cursor,
                    TradeReadCursors.executionScope(effective, userId, instrumentId, orderId, ascending));
            return orderQueryService.adminTrades(userId, instrumentId, orderId, limit, after, ascending, requested);
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage(), invalid);
        }
    }

    @PostMapping("/api/v1/admin/trading/orders/cancel-by-instrument")
    public AdminCancelOrdersResponse cancelByInstrument(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestHeader(value = "X-Product-Line", required = false) String headerProductLine,
            @RequestParam(value = "productLine", required = false) String queryProductLine,
            @Valid @RequestBody AdminCancelByInstrumentRequest request) {
        GatewayUserContext.requireAdminUserId(adminUserId);
        return TradingControllerSupport.call(() -> orderService.adminCancelByInstrument(
                request, TradingControllerSupport.productLine(queryProductLine, headerProductLine)));
    }

    @GetMapping("/api/v1/admin/trading/orders/cancel-preview")
    public AdminCancelOrdersPreviewResponse cancelPreview(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestHeader(value = "X-Product-Line", required = false) String headerProductLine,
            @RequestParam(value = "productLine", required = false) String queryProductLine,
            @RequestParam(value = "userId", required = false) Long userId,
            @RequestParam(value = "instrumentId", required = false) String instrumentId,
            @RequestParam(value = "limit", defaultValue = "100") int limit) {
        GatewayUserContext.requireAdminUserId(adminUserId);
        return TradingControllerSupport.call(() -> orderService.adminCancelPreview(
                userId, instrumentId, limit, TradingControllerSupport.productLine(queryProductLine, headerProductLine)));
    }

    @GetMapping("/api/v1/admin/trading/trigger-orders")
    public TriggerOrderQueryResponse triggerOrders(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestHeader(value = "X-Product-Line", required = false) String headerProductLine,
            @RequestParam(value = "productLine", required = false) String queryProductLine,
            @RequestParam(value = "userId", required = false) Long userId,
            @RequestParam(value = "instrumentId", required = false) String instrumentId,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "triggerOrderId", required = false) Long triggerOrderId,
            @RequestParam(value = "limit", defaultValue = "100") int limit,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "sort", required = false) String sort) {
        GatewayUserContext.requireAdminUserId(adminUserId);
        return TradingControllerSupport.call(() -> {
            ProductLine productLine = TradingControllerSupport.productLine(queryProductLine, headerProductLine);
            if (userId != null && userId <= 0) throw new IllegalArgumentException("userId must be positive");
            if (triggerOrderId != null && triggerOrderId <= 0) throw new IllegalArgumentException("triggerOrderId must be positive");
            if (limit < 1 || limit > 1000) throw new IllegalArgumentException("limit must be in [1, 1000]");
            String instrument = instrumentId == null || instrumentId.isBlank() ? null : instrumentId.trim().toUpperCase(Locale.ROOT);
            if (instrument != null) InstrumentIds.parse(instrument);
            var state = status == null || status.isBlank() ? null : CoreTriggerOrderStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
            String ordering = sort == null || sort.isBlank() ? "createdAt.desc" : sort.trim();
            if (!Set.of("createdAt.desc", "createdAt.asc").contains(ordering)) {
                throw new IllegalArgumentException("invalid trigger sort");
            }
            boolean ascending = ordering.endsWith("asc");
            ProductLine effective = productLine == null ? triggerOrderService.currentProductLine() : productLine;
            var after = cursor == null || cursor.isBlank() ? null : TriggerReadCursors.decode(cursor,
                    TriggerReadCursors.scope(effective, userId, instrument, state, triggerOrderId, ascending));
            return triggerOrderService.adminOrders(userId, instrument, state, triggerOrderId, limit, after, ascending, productLine);
        });
    }

    @GetMapping("/api/v1/admin/trading/trigger-orders/{triggerOrderId}/timeline")
    public AdminTriggerOrderTimelineResponse triggerOrderTimeline(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestHeader(value = "X-Product-Line", required = false) String headerProductLine,
            @RequestParam(value = "productLine", required = false) String queryProductLine,
            @PathVariable("triggerOrderId") long triggerOrderId) {
        GatewayUserContext.requireAdminUserId(adminUserId);
        return TradingControllerSupport.call(() -> triggerOrderService.adminTimeline(
                triggerOrderId, TradingControllerSupport.productLine(queryProductLine, headerProductLine)));
    }

    @GetMapping("/api/v1/admin/trading/trigger-orders/{triggerOrderId}")
    public TriggerOrderResponse triggerOrder(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestHeader(value = "X-Product-Line", required = false) String headerProductLine,
            @RequestParam(value = "productLine", required = false) String queryProductLine,
            @PathVariable("triggerOrderId") long triggerOrderId) {
        GatewayUserContext.requireAdminUserId(adminUserId);
        return TradingControllerSupport.call(() -> triggerOrderService.get(
                triggerOrderId, TradingControllerSupport.productLine(queryProductLine, headerProductLine)));
    }

    @PostMapping("/api/v1/admin/trading/orders/cancel")
    public AdminCancelOrdersResponse cancelOrders(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestHeader(value = "X-Product-Line", required = false) String headerProductLine,
            @RequestParam(value = "productLine", required = false) String queryProductLine,
            @Valid @RequestBody(required = false) AdminBatchCancelOrdersRequest request) {
        GatewayUserContext.requireAdminUserId(adminUserId);
        return TradingControllerSupport.call(() -> orderService.adminCancelOrders(
                request, TradingControllerSupport.productLine(queryProductLine, headerProductLine)));
    }

    @GetMapping("/api/v1/admin/trading/orders")
    public OrderQueryResponse orders(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestHeader(value = "X-Product-Line", required = false) String headerProductLine,
            @RequestParam(value = "productLine", required = false) String queryProductLine,
            @RequestParam(value = "userId", required = false) Long userId,
            @RequestParam(value = "instrumentId", required = false) String instrumentId,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "orderId", required = false) Long orderId,
            @RequestParam(value = "limit", defaultValue = "100") int limit,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "sort", required = false) String sort) {
        GatewayUserContext.requireAdminUserId(adminUserId);
        return TradingControllerSupport.call(() -> orderQueryService.adminOrders(
                userId, instrumentId, status, orderId, limit, cursor, sort,
                TradingControllerSupport.productLine(queryProductLine, headerProductLine)));
    }

    @PostMapping("/api/v1/admin/trading/orders/{orderId}/cancel")
    public AdminCancelOrderResult cancelOrder(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestHeader(value = "X-Product-Line", required = false) String headerProductLine,
            @RequestParam(value = "productLine", required = false) String queryProductLine,
            @PathVariable("orderId") long orderId,
            @Valid @RequestBody(required = false) AdminCancelOrderRequest request) {
        GatewayUserContext.requireAdminUserId(adminUserId);
        return TradingControllerSupport.call(() -> orderService.adminCancelOrder(
                orderId, request == null ? null : request.reason(),
                TradingControllerSupport.productLine(queryProductLine, headerProductLine)));
    }
}
