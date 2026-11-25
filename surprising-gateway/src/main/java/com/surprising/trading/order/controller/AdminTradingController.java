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
import com.surprising.trading.order.model.GridStrategyModels.GridStrategyRecord;
import com.surprising.trading.order.repository.ProjectionReadResult;
import com.surprising.trading.order.service.GridStrategyService;
import com.surprising.trading.order.service.OrderAdminService;
import com.surprising.trading.order.service.OrderQueryService;
import com.surprising.trading.order.utils.TradeReadCursors;
import com.surprising.trading.trigger.service.TriggerOrderService;
import com.surprising.trading.trigger.utils.TriggerReadCursors;
import jakarta.validation.Valid;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final OrderAdminService orderAdminService;
    private final OrderQueryService orderQueryService;
    private final TriggerOrderService triggerOrderService;
    private final GridStrategyService gridStrategyService;

    public AdminTradingController(OrderAdminService orderAdminService,
                                  OrderQueryService orderQueryService,
                                  TriggerOrderService triggerOrderService) {
        this(orderAdminService, orderQueryService, triggerOrderService, null);
    }

    @Autowired
    public AdminTradingController(OrderAdminService orderAdminService,
                                  OrderQueryService orderQueryService,
                                  TriggerOrderService triggerOrderService,
                                  @Autowired(required = false) GridStrategyService gridStrategyService) {
        this.orderAdminService = orderAdminService;
        this.orderQueryService = orderQueryService;
        this.triggerOrderService = triggerOrderService;
        this.gridStrategyService = gridStrategyService;
    }

    public AdminTradingController(OrderAdminService orderAdminService,
                                  TriggerOrderService triggerOrderService) {
        this(orderAdminService, null, triggerOrderService, null);
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
            var after = cursor == null || cursor.isBlank() ? null : TradeReadCursors.execution(cursor);
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
        return TradingControllerSupport.call(() -> orderAdminService.adminCancelByInstrument(
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
        return TradingControllerSupport.call(() -> orderAdminService.adminCancelPreview(
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
            var after = cursor == null || cursor.isBlank() ? null : TriggerReadCursors.decode(cursor);
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
        return TradingControllerSupport.call(() -> orderAdminService.adminCancelOrders(
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
        return TradingControllerSupport.call(() -> orderAdminService.adminCancelOrder(
                orderId, request == null ? null : request.reason(),
                TradingControllerSupport.productLine(queryProductLine, headerProductLine)));
    }

    @GetMapping("/api/v1/admin/trading/grid/list")
    public List<Map<String, Object>> listGridStrategies(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestParam(value = "symbol", required = false) String symbol,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "userId", required = false) Long userId) {
        GatewayUserContext.requireAdminUserId(adminUserId);
        if (gridStrategyService == null) {
            return List.of();
        }
        return gridStrategyService.listAllStrategies(symbol, status, userId).stream()
                .map(AdminTradingController::mapGridStrategy)
                .toList();
    }

    @PostMapping("/api/v1/admin/trading/grid/{strategyId}/stop")
    public Map<String, Object> stopGridStrategy(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @PathVariable("strategyId") long strategyId) {
        GatewayUserContext.requireAdminUserId(adminUserId);
        if (gridStrategyService == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Grid strategy service unavailable");
        }
        try {
            GridStrategyRecord stopped = gridStrategyService.adminStopGrid(strategyId);
            return Map.of("ok", true, "strategy", mapGridStrategy(stopped));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        }
    }

    private static Map<String, Object> mapGridStrategy(GridStrategyRecord r) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("strategyId", String.valueOf(r.strategyId()));
        row.put("userId", r.userId());
        row.put("symbol", r.instrumentId());
        row.put("productLine", r.productLine() == null ? "" : r.productLine().name());
        row.put("gridType", r.isArithmetic() ? "ARITHMETIC" : "GEOMETRIC");
        row.put("lowerPrice", String.valueOf(r.lowerPriceTicks()));
        row.put("upperPrice", String.valueOf(r.upperPriceTicks()));
        row.put("gridCount", r.gridCount());
        row.put("investAmount", String.valueOf(r.totalInvestmentUnits()));
        row.put("matchedCount", r.matchedTradeCount());
        row.put("profitUsd", String.valueOf(r.totalProfitUnits()));
        row.put("status", "ACTIVE".equalsIgnoreCase(r.status()) ? "RUNNING" : r.status());
        row.put("createdAt", r.createdAt());
        return row;
    }
}
