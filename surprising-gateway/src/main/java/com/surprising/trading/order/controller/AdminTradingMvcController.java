package com.surprising.trading.order.controller;

import com.surprising.gateway.provider.product.GatewayUserContext;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.AdminBatchCancelOrdersRequest;
import com.surprising.trading.api.model.AdminCancelByInstrumentRequest;
import com.surprising.trading.api.model.AdminCancelOrderRequest;
import com.surprising.trading.api.model.FeeScheduleStatus;
import com.surprising.trading.api.model.FeeScheduleUpsertRequest;
import com.surprising.trading.api.model.LeverageSettingRequest;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.maintenance.controller.AdminMaintenanceRequestHandler;
import com.surprising.trading.maintenance.controller.MaintenanceRequest;
import com.surprising.trading.order.service.InstrumentCoreSyncService;
import com.surprising.trading.trigger.controller.AdminTriggerOrderRequestHandler;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AdminTradingMvcController {
    private final AdminOrderRequestHandler adminOrderRequests;
    private final AdminTriggerOrderRequestHandler adminTriggerOrderRequests;
    private final AdminMaintenanceRequestHandler adminMaintenanceRequests;
    private final LeverageRequestHandler leverageRequests;
    private final TradingFeeRequestHandler tradingFeeRequests;
    private final InstrumentCoreSyncService instrumentCoreSyncService;

    public AdminTradingMvcController(AdminOrderRequestHandler adminOrderRequests,
                                     AdminTriggerOrderRequestHandler adminTriggerOrderRequests,
                                     AdminMaintenanceRequestHandler adminMaintenanceRequests,
                                     LeverageRequestHandler leverageRequests,
                                     TradingFeeRequestHandler tradingFeeRequests,
                                     InstrumentCoreSyncService instrumentCoreSyncService) {
        this.adminOrderRequests = adminOrderRequests;
        this.adminTriggerOrderRequests = adminTriggerOrderRequests;
        this.adminMaintenanceRequests = adminMaintenanceRequests;
        this.leverageRequests = leverageRequests;
        this.tradingFeeRequests = tradingFeeRequests;
        this.instrumentCoreSyncService = instrumentCoreSyncService;
    }

    @GetMapping("/api/v1/admin/trading/orders/metrics")
    public Object metrics(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestHeader(value = "X-Product-Line", required = false) String headerProductLine,
            @RequestParam(value = "productLine", required = false) String queryProductLine,
            @RequestParam(value = "windowMinutes", defaultValue = "1440") int windowMinutes,
            @RequestParam(value = "limit", defaultValue = "20") int limit) {
        return adminOrderRequests.metrics(GatewayUserContext.requireAdminUserId(adminUserId),
                headerProductLine, queryProductLine, windowMinutes, limit);
    }

    @GetMapping("/api/v1/admin/trading/orders/trades")
    public Object trades(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestHeader(value = "X-Product-Line", required = false) String headerProductLine,
            @RequestParam(value = "productLine", required = false) String queryProductLine,
            @RequestParam(value = "userId", required = false) Long userId,
            @RequestParam(value = "instrumentId", required = false) String instrumentId,
            @RequestParam(value = "orderId", required = false) Long orderId,
            @RequestParam(value = "limit", defaultValue = "100") int limit,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "sort", required = false) String sort) {
        return adminOrderRequests.trades(GatewayUserContext.requireAdminUserId(adminUserId),
                headerProductLine, queryProductLine, userId, instrumentId, orderId, limit, cursor, sort);
    }

    @GetMapping("/api/v1/admin/trading/fees/effective")
    public Object effectiveFee(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestParam("userId") long userId,
            @RequestParam("instrumentId") String instrumentId,
            @RequestParam(value = "instrumentChangeId", defaultValue = "0") long instrumentChangeId,
            @RequestHeader(value = "X-Product-Line", required = false) String headerProductLine,
            @RequestParam(value = "productLine", required = false) String queryProductLine) {
        GatewayUserContext.requireAdminUserId(adminUserId);
        return tradingFeeRequests.effective(userId, instrumentId, instrumentChangeId,
                headerProductLine, queryProductLine);
    }

    @GetMapping("/api/v1/admin/trading/orders/maintenance/preview")
    public Object maintenancePreview(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestHeader("X-Product-Line") String headerProductLine,
            @RequestParam("productLine") ProductLine productLine,
            @RequestParam("instrumentId") String instrumentId,
            @RequestParam(value = "userId", defaultValue = "0") long userId,
            @RequestParam(value = "afterUserId", defaultValue = "0") long afterUserId) {
        adminMaintenanceRequests.authorize(GatewayUserContext.requireAdminUserId(adminUserId),
                headerProductLine, productLine);
        return adminMaintenanceRequests.preview(instrumentId, userId, afterUserId);
    }

    @GetMapping("/api/v1/admin/trading/orders/maintenance")
    public Object maintenanceList(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestHeader("X-Product-Line") String headerProductLine,
            @RequestParam("productLine") ProductLine productLine,
            @RequestParam(value = "beforeId", defaultValue = "0") long beforeId) {
        adminMaintenanceRequests.authorize(GatewayUserContext.requireAdminUserId(adminUserId),
                headerProductLine, productLine);
        return adminMaintenanceRequests.list(beforeId);
    }

    @PostMapping("/api/v1/admin/trading/orders/maintenance")
    public Object maintenanceCreate(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestHeader("X-Product-Line") String headerProductLine,
            @RequestParam("productLine") ProductLine productLine,
            @RequestBody MaintenanceRequest request) {
        String verifiedAdmin = GatewayUserContext.requireAdminUserId(adminUserId);
        adminMaintenanceRequests.authorize(verifiedAdmin, headerProductLine, productLine);
        return adminMaintenanceRequests.create(verifiedAdmin, request);
    }

    @GetMapping("/api/v1/admin/trading/orders/maintenance/{id}/actions")
    public Object maintenanceActions(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestHeader("X-Product-Line") String headerProductLine,
            @RequestParam("productLine") ProductLine productLine,
            @PathVariable("id") long id,
            @RequestParam(value = "afterKey", defaultValue = "") String afterKey) {
        adminMaintenanceRequests.authorize(GatewayUserContext.requireAdminUserId(adminUserId),
                headerProductLine, productLine);
        return adminMaintenanceRequests.actions(id, afterKey);
    }

    @PostMapping("/api/v1/admin/trading/orders/maintenance/{id}/release")
    public Object maintenanceRelease(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestHeader("X-Product-Line") String headerProductLine,
            @RequestParam("productLine") ProductLine productLine,
            @PathVariable("id") long id) {
        adminMaintenanceRequests.authorize(GatewayUserContext.requireAdminUserId(adminUserId),
                headerProductLine, productLine);
        return adminMaintenanceRequests.release(id);
    }

    @PostMapping("/api/v1/admin/trading/orders/maintenance/{id}/retry")
    public Object maintenanceRetry(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestHeader("X-Product-Line") String headerProductLine,
            @RequestParam("productLine") ProductLine productLine,
            @PathVariable("id") long id) {
        adminMaintenanceRequests.authorize(GatewayUserContext.requireAdminUserId(adminUserId),
                headerProductLine, productLine);
        return adminMaintenanceRequests.retry(id);
    }

    @GetMapping("/api/v1/admin/trading/orders/maintenance/{id}")
    public Object maintenanceGet(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestHeader("X-Product-Line") String headerProductLine,
            @RequestParam("productLine") ProductLine productLine,
            @PathVariable("id") long id) {
        adminMaintenanceRequests.authorize(GatewayUserContext.requireAdminUserId(adminUserId),
                headerProductLine, productLine);
        return adminMaintenanceRequests.get(id);
    }

    @PostMapping("/api/v1/admin/trading/orders/cancel-by-instrument")
    public Object cancelByInstrument(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestHeader(value = "X-Product-Line", required = false) String headerProductLine,
            @RequestParam(value = "productLine", required = false) String queryProductLine,
            @Valid @RequestBody AdminCancelByInstrumentRequest request) {
        GatewayUserContext.requireAdminUserId(adminUserId);
        return adminOrderRequests.cancelByInstrument(adminUserId, headerProductLine, queryProductLine, request);
    }

    @GetMapping("/api/v1/admin/trading/orders/cancel-preview")
    public Object cancelPreview(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestHeader(value = "X-Product-Line", required = false) String headerProductLine,
            @RequestParam(value = "productLine", required = false) String queryProductLine,
            @RequestParam(value = "userId", required = false) Long userId,
            @RequestParam(value = "instrumentId", required = false) String instrumentId,
            @RequestParam(value = "limit", defaultValue = "100") int limit) {
        GatewayUserContext.requireAdminUserId(adminUserId);
        return adminOrderRequests.cancelPreview(adminUserId, headerProductLine, queryProductLine,
                userId, instrumentId, limit);
    }

    @GetMapping("/api/v1/admin/trading/trigger-orders")
    public Object triggerOrders(
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
        return adminTriggerOrderRequests.orders(adminUserId, headerProductLine, queryProductLine,
                userId, instrumentId, status, triggerOrderId, limit, cursor, sort);
    }

    @GetMapping("/api/v1/admin/trading/trigger-orders/{triggerOrderId}/timeline")
    public Object triggerOrderTimeline(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestHeader(value = "X-Product-Line", required = false) String headerProductLine,
            @RequestParam(value = "productLine", required = false) String queryProductLine,
            @PathVariable("triggerOrderId") long triggerOrderId) {
        GatewayUserContext.requireAdminUserId(adminUserId);
        return adminTriggerOrderRequests.timeline(adminUserId, headerProductLine, queryProductLine, triggerOrderId);
    }

    @GetMapping("/api/v1/admin/trading/trigger-orders/{triggerOrderId}")
    public Object triggerOrder(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestHeader(value = "X-Product-Line", required = false) String headerProductLine,
            @RequestParam(value = "productLine", required = false) String queryProductLine,
            @PathVariable("triggerOrderId") long triggerOrderId) {
        GatewayUserContext.requireAdminUserId(adminUserId);
        return adminTriggerOrderRequests.order(adminUserId, headerProductLine, queryProductLine, triggerOrderId);
    }

    @PostMapping("/api/v1/admin/trading/fees/schedules")
    public Object upsertFeeSchedule(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestBody FeeScheduleUpsertRequest request,
            @RequestHeader(value = "X-Product-Line", required = false) String headerProductLine,
            @RequestParam(value = "productLine", required = false) String queryProductLine) {
        GatewayUserContext.requireAdminUserId(adminUserId);
        return tradingFeeRequests.upsert(request, headerProductLine, queryProductLine);
    }

    @GetMapping("/api/v1/admin/trading/fees/schedules")
    public Object queryFeeSchedules(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestParam(value = "userId", defaultValue = "0") long userId,
            @RequestHeader(value = "X-Product-Line", required = false) String headerProductLine,
            @RequestParam(value = "productLine", required = false) String queryProductLine,
            @RequestParam(value = "instrumentId", required = false) String instrumentId,
            @RequestParam(value = "status", required = false) FeeScheduleStatus status,
            @RequestParam(value = "limit", defaultValue = "100") int limit,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "sort", required = false) String sort) {
        GatewayUserContext.requireAdminUserId(adminUserId);
        return tradingFeeRequests.query(userId, headerProductLine, queryProductLine, instrumentId,
                status, limit, cursor, sort);
    }

    @PostMapping("/api/v1/admin/trading/fees/schedules/{feeScheduleId}/disable")
    public Object disableFeeSchedule(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @PathVariable("feeScheduleId") long feeScheduleId,
            @RequestHeader(value = "X-Product-Line", required = false) String headerProductLine,
            @RequestParam(value = "productLine", required = false) String queryProductLine) {
        GatewayUserContext.requireAdminUserId(adminUserId);
        return tradingFeeRequests.disable(feeScheduleId, headerProductLine, queryProductLine);
    }

    @PostMapping("/api/v1/admin/trading/orders/cancel")
    public Object cancelOrders(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestHeader(value = "X-Product-Line", required = false) String headerProductLine,
            @RequestParam(value = "productLine", required = false) String queryProductLine,
            @Valid @RequestBody(required = false) AdminBatchCancelOrdersRequest request) {
        GatewayUserContext.requireAdminUserId(adminUserId);
        return adminOrderRequests.cancelOrders(adminUserId, headerProductLine, queryProductLine, request);
    }

    @PostMapping("/api/v1/admin/trading/leverage/settings")
    public Object adminSetLeverage(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestBody LeverageSettingRequest request,
            @RequestHeader(value = "X-Product-Line", required = false) String headerProductLine,
            @RequestParam(value = "productLine", required = false) String queryProductLine) {
        return leverageRequests.adminSet(GatewayUserContext.requireAdminUserId(adminUserId),
                request, headerProductLine, queryProductLine);
    }

    @GetMapping("/api/v1/admin/trading/leverage/settings")
    public Object adminGetLeverage(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestParam("userId") long userId,
            @RequestParam("instrumentId") String instrumentId,
            @RequestParam(value = "marginMode", required = false) MarginMode marginMode,
            @RequestHeader(value = "X-Product-Line", required = false) String headerProductLine,
            @RequestParam(value = "productLine", required = false) String queryProductLine) {
        GatewayUserContext.requireAdminUserId(adminUserId);
        return leverageRequests.get(userId, instrumentId, marginMode, headerProductLine, queryProductLine);
    }

    @GetMapping("/api/v1/admin/trading/orders")
    public Object orders(
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
        return adminOrderRequests.orders(adminUserId, headerProductLine, queryProductLine,
                userId, instrumentId, status, orderId, limit, cursor, sort);
    }

    @GetMapping("/api/v1/admin/trading/orders/instrument-sync/{instrumentId}")
    public Object instrumentSyncState(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @PathVariable("instrumentId") String instrumentId,
            @RequestParam("productLine") ProductLine productLine) {
        GatewayUserContext.requireAdminUserId(adminUserId);
        return instrumentCoreSyncService.state(instrumentId, productLine);
    }

    @PostMapping("/api/v1/admin/trading/orders/{orderId}/cancel")
    public Object cancelOrder(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestHeader(value = "X-Product-Line", required = false) String headerProductLine,
            @RequestParam(value = "productLine", required = false) String queryProductLine,
            @PathVariable("orderId") long orderId,
            @Valid @RequestBody(required = false) AdminCancelOrderRequest request) {
        GatewayUserContext.requireAdminUserId(adminUserId);
        return adminOrderRequests.cancelOrder(adminUserId, headerProductLine, queryProductLine, orderId, request);
    }
}
