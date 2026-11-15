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
import com.surprising.trading.api.model.EffectiveTradingFeeResponse;
import com.surprising.trading.api.model.FeeScheduleQueryResponse;
import com.surprising.trading.api.model.FeeScheduleResponse;
import com.surprising.trading.api.model.FeeScheduleStatus;
import com.surprising.trading.api.model.FeeScheduleUpsertRequest;
import com.surprising.trading.api.model.LeverageSettingRequest;
import com.surprising.trading.api.model.LeverageSettingResponse;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.OrderQueryResponse;
import com.surprising.trading.api.model.TriggerOrderQueryResponse;
import com.surprising.trading.api.model.TriggerOrderResponse;
import com.surprising.trading.api.utils.AdminCursorPage;
import com.surprising.trading.maintenance.controller.MaintenanceRequest;
import com.surprising.trading.maintenance.model.MaintenanceAction;
import com.surprising.trading.maintenance.model.MaintenanceCommand;
import com.surprising.trading.maintenance.model.MaintenanceTask;
import com.surprising.trading.maintenance.service.MaintenanceService;
import com.surprising.trading.order.model.FeeSchedulePageQuery;
import com.surprising.trading.order.repository.ProjectionReadResult;
import com.surprising.trading.order.service.InstrumentCoreSyncService;
import com.surprising.trading.order.service.LeverageService;
import com.surprising.trading.order.service.OrderQueryService;
import com.surprising.trading.order.service.OrderService;
import com.surprising.trading.order.service.TradingFeeService;
import com.surprising.trading.order.utils.TradeReadCursors;
import com.surprising.trading.trigger.service.TriggerOrderService;
import com.surprising.trading.trigger.utils.TriggerReadCursors;
import jakarta.validation.Valid;
import java.time.DateTimeException;
import java.util.Arrays;
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
    private final MaintenanceService maintenanceService;
    private final LeverageService leverageService;
    private final TradingFeeService tradingFeeService;
    private final InstrumentCoreSyncService instrumentCoreSyncService;

    @org.springframework.beans.factory.annotation.Autowired
    public AdminTradingController(OrderService orderService,
                                  OrderQueryService orderQueryService,
                                  TriggerOrderService triggerOrderService,
                                  MaintenanceService maintenanceService,
                                  LeverageService leverageService,
                                  TradingFeeService tradingFeeService,
                                  InstrumentCoreSyncService instrumentCoreSyncService) {
        this.orderService = orderService;
        this.orderQueryService = orderQueryService;
        this.triggerOrderService = triggerOrderService;
        this.maintenanceService = maintenanceService;
        this.leverageService = leverageService;
        this.tradingFeeService = tradingFeeService;
        this.instrumentCoreSyncService = instrumentCoreSyncService;
    }

    public AdminTradingController(OrderService orderService,
                                  TriggerOrderService triggerOrderService,
                                  MaintenanceService maintenanceService,
                                  LeverageService leverageService,
                                  TradingFeeService tradingFeeService,
                                  InstrumentCoreSyncService instrumentCoreSyncService) {
        this(orderService, null, triggerOrderService, maintenanceService, leverageService, tradingFeeService, instrumentCoreSyncService);
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
            return orderQueryService.tradingMetrics(windowMinutes, limit, TradingController.productLine(queryProductLine, headerProductLine));
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
            ProductLine requested = TradingController.productLine(queryProductLine, headerProductLine);
            ProductLine effective = requested == null ? orderQueryService.currentProductLine() : requested;
            var after = cursor == null || cursor.isBlank() ? null : TradeReadCursors.execution(cursor,
                    TradeReadCursors.executionScope(effective, userId, instrumentId, orderId, ascending));
            return orderQueryService.adminTrades(userId, instrumentId, orderId, limit, after, ascending, requested);
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage(), invalid);
        }
    }

    @GetMapping("/api/v1/admin/trading/fees/effective")
    public EffectiveTradingFeeResponse effectiveFee(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestParam("userId") long userId,
            @RequestParam("instrumentId") String instrumentId,
            @RequestParam(value = "instrumentChangeId", defaultValue = "0") long instrumentChangeId,
            @RequestHeader(value = "X-Product-Line", required = false) String headerProductLine,
            @RequestParam(value = "productLine", required = false) String queryProductLine) {
        GatewayUserContext.requireAdminUserId(adminUserId);
        return TradingController.effectiveFee(tradingFeeService, userId, instrumentId, instrumentChangeId,
                headerProductLine, queryProductLine);
    }

    @GetMapping("/api/v1/admin/trading/orders/maintenance/preview")
    public MaintenanceService.Preview maintenancePreview(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestHeader("X-Product-Line") String headerProductLine,
            @RequestParam("productLine") ProductLine productLine,
            @RequestParam("instrumentId") String instrumentId,
            @RequestParam(value = "userId", defaultValue = "0") long userId,
            @RequestParam(value = "afterUserId", defaultValue = "0") long afterUserId) {
        authorizeMaintenance(GatewayUserContext.requireAdminUserId(adminUserId), headerProductLine, productLine);
        if (!InstrumentIds.valid(instrumentId)) badRequest("explicit instrument_id is required");
        nonnegative(userId, "invalid user cursor");
        nonnegative(afterUserId, "invalid user cursor");
        return maintenanceService.preview(instrumentId, userId, afterUserId);
    }

    @GetMapping("/api/v1/admin/trading/orders/maintenance")
    public List<MaintenanceTask> maintenanceList(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestHeader("X-Product-Line") String headerProductLine,
            @RequestParam("productLine") ProductLine productLine,
            @RequestParam(value = "beforeId", defaultValue = "0") long beforeId) {
        authorizeMaintenance(GatewayUserContext.requireAdminUserId(adminUserId), headerProductLine, productLine);
        nonnegative(beforeId, "invalid cursor");
        return maintenanceService.list(beforeId);
    }

    @PostMapping("/api/v1/admin/trading/orders/maintenance")
    public MaintenanceTask maintenanceCreate(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestHeader("X-Product-Line") String headerProductLine,
            @RequestParam("productLine") ProductLine productLine,
            @RequestBody MaintenanceRequest request) {
        String verifiedAdmin = GatewayUserContext.requireAdminUserId(adminUserId);
        authorizeMaintenance(verifiedAdmin, headerProductLine, productLine);
        if (request == null || request.requestId() == null || request.mode() == null
                || request.instrumentId() == null || !InstrumentIds.valid(request.instrumentId())
                || request.reason() == null || request.reason().isBlank() || request.reason().length() > 1024) {
            badRequest("requestId, explicit instrument_id, mode and maintenance reason are required");
        }
        long userId = parseDecimalLong(request.userId());
        long priceTicks = parseDecimalLong(request.priceTicks());
        return maintenanceService.create(verifiedAdmin, new MaintenanceCommand(request.requestId(),
                request.instrumentId(), userId, request.mode(), priceTicks, request.reason().trim()));
    }

    @GetMapping("/api/v1/admin/trading/orders/maintenance/{id}/actions")
    public List<MaintenanceAction> maintenanceActions(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestHeader("X-Product-Line") String headerProductLine,
            @RequestParam("productLine") ProductLine productLine,
            @PathVariable("id") long id,
            @RequestParam(value = "afterKey", defaultValue = "") String afterKey) {
        authorizeMaintenance(GatewayUserContext.requireAdminUserId(adminUserId), headerProductLine, productLine);
        positiveId(id);
        return maintenanceService.actions(id, afterKey == null ? "" : afterKey);
    }

    @PostMapping("/api/v1/admin/trading/orders/maintenance/{id}/release")
    public MaintenanceTask maintenanceRelease(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestHeader("X-Product-Line") String headerProductLine,
            @RequestParam("productLine") ProductLine productLine,
            @PathVariable("id") long id) {
        authorizeMaintenance(GatewayUserContext.requireAdminUserId(adminUserId), headerProductLine, productLine);
        positiveId(id);
        return maintenanceService.release(id);
    }

    @PostMapping("/api/v1/admin/trading/orders/maintenance/{id}/retry")
    public MaintenanceTask maintenanceRetry(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestHeader("X-Product-Line") String headerProductLine,
            @RequestParam("productLine") ProductLine productLine,
            @PathVariable("id") long id) {
        authorizeMaintenance(GatewayUserContext.requireAdminUserId(adminUserId), headerProductLine, productLine);
        positiveId(id);
        return maintenanceService.retry(id);
    }

    @GetMapping("/api/v1/admin/trading/orders/maintenance/{id}")
    public MaintenanceTask maintenanceGet(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestHeader("X-Product-Line") String headerProductLine,
            @RequestParam("productLine") ProductLine productLine,
            @PathVariable("id") long id) {
        authorizeMaintenance(GatewayUserContext.requireAdminUserId(adminUserId), headerProductLine, productLine);
        positiveId(id);
        return maintenanceService.get(id);
    }

    @PostMapping("/api/v1/admin/trading/orders/cancel-by-instrument")
    public AdminCancelOrdersResponse cancelByInstrument(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestHeader(value = "X-Product-Line", required = false) String headerProductLine,
            @RequestParam(value = "productLine", required = false) String queryProductLine,
            @Valid @RequestBody AdminCancelByInstrumentRequest request) {
        GatewayUserContext.requireAdminUserId(adminUserId);
        try {
            return orderService.adminCancelByInstrument(request, TradingController.productLine(queryProductLine, headerProductLine));
        } catch (ProjectionReadResult.ResponseTooLargeException ex) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, ex.getMessage(), ex);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
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
        try {
            return orderService.adminCancelPreview(userId, instrumentId, limit,
                    TradingController.productLine(queryProductLine, headerProductLine));
        } catch (ProjectionReadResult.ResponseTooLargeException ex) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, ex.getMessage(), ex);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
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
        try {
            ProductLine productLine = TradingController.productLine(queryProductLine, headerProductLine);
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
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @GetMapping("/api/v1/admin/trading/trigger-orders/{triggerOrderId}/timeline")
    public AdminTriggerOrderTimelineResponse triggerOrderTimeline(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestHeader(value = "X-Product-Line", required = false) String headerProductLine,
            @RequestParam(value = "productLine", required = false) String queryProductLine,
            @PathVariable("triggerOrderId") long triggerOrderId) {
        GatewayUserContext.requireAdminUserId(adminUserId);
        try {
            return triggerOrderService.adminTimeline(triggerOrderId, TradingController.productLine(queryProductLine, headerProductLine));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        }
    }

    @GetMapping("/api/v1/admin/trading/trigger-orders/{triggerOrderId}")
    public TriggerOrderResponse triggerOrder(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestHeader(value = "X-Product-Line", required = false) String headerProductLine,
            @RequestParam(value = "productLine", required = false) String queryProductLine,
            @PathVariable("triggerOrderId") long triggerOrderId) {
        GatewayUserContext.requireAdminUserId(adminUserId);
        try {
            return triggerOrderService.get(triggerOrderId, TradingController.productLine(queryProductLine, headerProductLine));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        }
    }

    @PostMapping("/api/v1/admin/trading/fees/schedules")
    public FeeScheduleResponse upsertFeeSchedule(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestBody FeeScheduleUpsertRequest request,
            @RequestHeader(value = "X-Product-Line", required = false) String headerProductLine,
            @RequestParam(value = "productLine", required = false) String queryProductLine) {
        GatewayUserContext.requireAdminUserId(adminUserId);
        try {
            ProductLine productLine = TradingController.productLine(queryProductLine, headerProductLine);
            FeeScheduleUpsertRequest bound = request == null || request.productLine() != null || productLine == null
                    ? request
                    : new FeeScheduleUpsertRequest(request.feeScheduleId(), productLine, request.userId(), request.instrumentId(),
                            request.makerFeeRatePpm(), request.takerFeeRatePpm(), request.sourceType(), request.tierCode(),
                            request.reason(), request.status(), request.effectiveTime(), request.expireTime());
            return tradingFeeService.upsertSchedule(bindSchedule(bound));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @GetMapping("/api/v1/admin/trading/fees/schedules")
    public FeeScheduleQueryResponse queryFeeSchedules(
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
        try {
            return tradingFeeService.querySchedules(TradingController.productLine(queryProductLine, headerProductLine),
                    userId, TradingController.normalizeOptionalSymbol(instrumentId), status, feePage(limit, cursor, sort));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @PostMapping("/api/v1/admin/trading/fees/schedules/{feeScheduleId}/disable")
    public FeeScheduleResponse disableFeeSchedule(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @PathVariable("feeScheduleId") long feeScheduleId,
            @RequestHeader(value = "X-Product-Line", required = false) String headerProductLine,
            @RequestParam(value = "productLine", required = false) String queryProductLine) {
        GatewayUserContext.requireAdminUserId(adminUserId);
        try {
            return tradingFeeService.disableSchedule(feeScheduleId, TradingController.productLine(queryProductLine, headerProductLine));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        }
    }

    @PostMapping("/api/v1/admin/trading/orders/cancel")
    public AdminCancelOrdersResponse cancelOrders(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestHeader(value = "X-Product-Line", required = false) String headerProductLine,
            @RequestParam(value = "productLine", required = false) String queryProductLine,
            @Valid @RequestBody(required = false) AdminBatchCancelOrdersRequest request) {
        GatewayUserContext.requireAdminUserId(adminUserId);
        try {
            return orderService.adminCancelOrders(request, TradingController.productLine(queryProductLine, headerProductLine));
        } catch (ProjectionReadResult.ResponseTooLargeException ex) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, ex.getMessage(), ex);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @PostMapping("/api/v1/admin/trading/leverage/settings")
    public LeverageSettingResponse adminSetLeverage(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestBody LeverageSettingRequest request,
            @RequestHeader(value = "X-Product-Line", required = false) String headerProductLine,
            @RequestParam(value = "productLine", required = false) String queryProductLine) {
        GatewayUserContext.requireAdminUserId(adminUserId);
        return TradingController.setLeverage(leverageService, request, headerProductLine, queryProductLine);
    }

    @GetMapping("/api/v1/admin/trading/leverage/settings")
    public LeverageSettingResponse adminGetLeverage(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestParam("userId") long userId,
            @RequestParam("instrumentId") String instrumentId,
            @RequestParam(value = "marginMode", required = false) MarginMode marginMode,
            @RequestHeader(value = "X-Product-Line", required = false) String headerProductLine,
            @RequestParam(value = "productLine", required = false) String queryProductLine) {
        GatewayUserContext.requireAdminUserId(adminUserId);
        return TradingController.getLeverage(leverageService, userId, instrumentId, marginMode, headerProductLine, queryProductLine);
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
        try {
            return orderQueryService.adminOrders(userId, instrumentId, status, orderId, limit, cursor, sort,
                    TradingController.productLine(queryProductLine, headerProductLine));
        } catch (ProjectionReadResult.ResponseTooLargeException ex) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, ex.getMessage(), ex);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
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
    public AdminCancelOrderResult cancelOrder(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestHeader(value = "X-Product-Line", required = false) String headerProductLine,
            @RequestParam(value = "productLine", required = false) String queryProductLine,
            @PathVariable("orderId") long orderId,
            @Valid @RequestBody(required = false) AdminCancelOrderRequest request) {
        GatewayUserContext.requireAdminUserId(adminUserId);
        try {
            return orderService.adminCancelOrder(orderId, request == null ? null : request.reason(),
                    TradingController.productLine(queryProductLine, headerProductLine));
        } catch (ProjectionReadResult.ResponseTooLargeException ex) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, ex.getMessage(), ex);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        }
    }

    private void authorizeMaintenance(String admin, String header, ProductLine productLine) {
        if (admin == null || !admin.matches("[1-9][0-9]{0,18}")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "trusted admin identity is required");
        }
        if (productLine != maintenanceService.productLine() || header == null || !header.equals(productLine.name())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "explicit matching product line is required");
        }
    }

    private static FeeScheduleUpsertRequest bindSchedule(FeeScheduleUpsertRequest request) {
        if (request == null) throw new IllegalArgumentException("fee schedule request is required");
        if (request.feeScheduleId() == null || request.feeScheduleId() <= 0) throw new IllegalArgumentException("feeScheduleId must be positive");
        if (request.userId() <= 0) throw new IllegalArgumentException("userId must be positive");
        if (request.productLine() == null) throw new IllegalArgumentException("productLine is required");
        String symbol = TradingController.normalizeOptionalSymbol(request.instrumentId());
        validateFeeRate(request.makerFeeRatePpm(), "makerFeeRatePpm");
        validateFeeRate(request.takerFeeRatePpm(), "takerFeeRatePpm");
        if (request.reason() == null || request.reason().isBlank()) throw new IllegalArgumentException("reason is required");
        if (request.tierCode() != null && !request.tierCode().isBlank()
                && !request.tierCode().trim().toUpperCase().matches("[A-Z0-9][A-Z0-9_-]{0,31}")) {
            throw new IllegalArgumentException("invalid tierCode: " + request.tierCode());
        }
        return new FeeScheduleUpsertRequest(request.feeScheduleId(), request.productLine(), request.userId(), symbol,
                request.makerFeeRatePpm(), request.takerFeeRatePpm(), request.sourceType(),
                request.tierCode() == null || request.tierCode().isBlank() ? null : request.tierCode().trim(),
                request.reason().trim(), request.status(), request.effectiveTime(), request.expireTime());
    }

    private static FeeSchedulePageQuery feePage(int limit, String cursor, String sort) {
        String token = sort == null || sort.isBlank() ? FeeSchedulePageQuery.Order.UPDATED_DESC.token() : sort.trim();
        FeeSchedulePageQuery.Order order = Arrays.stream(FeeSchedulePageQuery.Order.values())
                .filter(value -> value.token().equals(token)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unsupported sort: " + sort));
        try {
            return new FeeSchedulePageQuery(AdminCursorPage.limit(limit <= 0 ? 100 : limit, 500),
                    AdminCursorPage.decodeCursor(cursor), order);
        } catch (DateTimeException invalid) {
            throw new IllegalArgumentException("invalid cursor", invalid);
        }
    }

    private static void validateFeeRate(long feeRatePpm, String field) {
        if (feeRatePpm < -1_000_000L || feeRatePpm > 1_000_000L) {
            throw new IllegalArgumentException(field + " must be within +/- 100%");
        }
    }

    private static long parseDecimalLong(String value) {
        if (value == null || value.isBlank()) return 0;
        if (!value.matches("0|[1-9][0-9]*")) badRequest("identifiers and prices must be decimal integer strings");
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "identifiers and prices exceed 64-bit range", ex);
        }
    }

    private static void positiveId(long id) {
        if (id <= 0) badRequest("maintenance task id must be positive");
    }

    private static void nonnegative(long value, String message) {
        if (value < 0) badRequest(message);
    }

    private static void badRequest(String message) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
