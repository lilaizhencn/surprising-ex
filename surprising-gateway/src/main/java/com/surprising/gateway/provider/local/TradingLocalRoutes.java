package com.surprising.gateway.provider.local;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.AdminBatchCancelOrdersRequest;
import com.surprising.trading.api.model.AdminCancelBySymbolRequest;
import com.surprising.trading.api.model.AdminCancelOrderRequest;
import com.surprising.trading.api.model.AmendOrderRequest;
import com.surprising.trading.api.model.BatchAmendOrdersRequest;
import com.surprising.trading.api.model.BatchCancelOrdersRequest;
import com.surprising.trading.api.model.BatchCancelTriggerOrdersRequest;
import com.surprising.trading.api.model.BatchPlaceOrderRequest;
import com.surprising.trading.api.model.BatchPlaceTriggerOrderRequest;
import com.surprising.trading.api.model.CancelAlgoOrderRequest;
import com.surprising.trading.api.model.CancelAllAfterRequest;
import com.surprising.trading.api.model.CancelOpenAlgoOrdersRequest;
import com.surprising.trading.api.model.CancelOpenOrdersRequest;
import com.surprising.trading.api.model.CancelOpenTriggerOrdersRequest;
import com.surprising.trading.api.model.CancelOrderRequest;
import com.surprising.trading.api.model.CancelTriggerOrderRequest;
import com.surprising.trading.api.model.ClosePositionRequest;
import com.surprising.trading.api.model.FeeScheduleStatus;
import com.surprising.trading.api.model.FeeScheduleUpsertRequest;
import com.surprising.trading.api.model.LeverageSettingRequest;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.PlaceAlgoOrderRequest;
import com.surprising.trading.api.model.PlaceOrderRequest;
import com.surprising.trading.api.model.PlaceTriggerOrderRequest;
import com.surprising.trading.maintenance.AdminMaintenanceRequestService;
import com.surprising.trading.order.service.AdminOrderRequestService;
import com.surprising.trading.order.service.LeverageRequestService;
import com.surprising.trading.order.service.OrderRequestService;
import com.surprising.trading.order.service.TradingFeeRequestService;
import com.surprising.trading.order.service.InstrumentCoreSyncService;
import com.surprising.trading.trigger.service.AdminTriggerOrderRequestService;
import com.surprising.trading.trigger.service.TriggerOrderRequestService;
import java.util.UUID;
import com.surprising.trading.maintenance.MaintenanceRequest;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/** 网关协议到订单入口的本地调用；不建立内部 HTTP 连接。 */
@Component
public final class TradingLocalRoutes {
    private static final PathPattern EFFECTIVE_FEE = PathPatternParser.defaultInstance.parse("/api/v1/trading/fees/effective");
    private static final PathPattern ADMIN_MAINTENANCE_CONTROLLER_LIST = PathPatternParser.defaultInstance.parse("/api/v1/admin/trading/orders/maintenance");
    private static final PathPattern ADMIN_MAINTENANCE_CONTROLLER_PREVIEW = PathPatternParser.defaultInstance.parse("/api/v1/admin/trading/orders/maintenance/preview");
    private static final PathPattern ADMIN_MAINTENANCE_CONTROLLER_GET = PathPatternParser.defaultInstance.parse("/api/v1/admin/trading/orders/maintenance/{id}");
    private static final PathPattern ADMIN_MAINTENANCE_CONTROLLER_ACTIONS = PathPatternParser.defaultInstance.parse("/api/v1/admin/trading/orders/maintenance/{id}/actions");
    private static final PathPattern ADMIN_MAINTENANCE_CONTROLLER_CREATE = PathPatternParser.defaultInstance.parse("/api/v1/admin/trading/orders/maintenance");
    private static final PathPattern ADMIN_MAINTENANCE_CONTROLLER_RETRY = PathPatternParser.defaultInstance.parse("/api/v1/admin/trading/orders/maintenance/{id}/retry");
    private static final PathPattern ADMIN_MAINTENANCE_CONTROLLER_RELEASE = PathPatternParser.defaultInstance.parse("/api/v1/admin/trading/orders/maintenance/{id}/release");
    private static final PathPattern TRIGGER_ORDER_CONTROLLER_PLACE = PathPatternParser.defaultInstance.parse("/api/v1/trading/trigger-orders");
    private static final PathPattern TRIGGER_ORDER_CONTROLLER_PLACEBATCH = PathPatternParser.defaultInstance.parse("/api/v1/trading/trigger-orders/batch");
    private static final PathPattern TRIGGER_ORDER_CONTROLLER_CANCEL = PathPatternParser.defaultInstance.parse("/api/v1/trading/trigger-orders/cancel");
    private static final PathPattern TRIGGER_ORDER_CONTROLLER_CANCELBATCH = PathPatternParser.defaultInstance.parse("/api/v1/trading/trigger-orders/batch-cancel");
    private static final PathPattern TRIGGER_ORDER_CONTROLLER_CANCELOPEN = PathPatternParser.defaultInstance.parse("/api/v1/trading/trigger-orders/cancel-open");
    private static final PathPattern TRIGGER_ORDER_CONTROLLER_GET = PathPatternParser.defaultInstance.parse("/api/v1/trading/trigger-orders/{triggerOrderId}");
    private static final PathPattern TRIGGER_ORDER_CONTROLLER_OPENORDERS = PathPatternParser.defaultInstance.parse("/api/v1/trading/trigger-orders/open");
    private static final PathPattern ADMIN_TRIGGER_ORDER_CONTROLLER_ORDERS = PathPatternParser.defaultInstance.parse("/api/v1/admin/trading/trigger-orders");
    private static final PathPattern ADMIN_TRIGGER_ORDER_CONTROLLER_ORDER = PathPatternParser.defaultInstance.parse("/api/v1/admin/trading/trigger-orders/{triggerOrderId}");
    private static final PathPattern ADMIN_TRIGGER_ORDER_CONTROLLER_TIMELINE = PathPatternParser.defaultInstance.parse("/api/v1/admin/trading/trigger-orders/{triggerOrderId}/timeline");
    private static final PathPattern LEVERAGE_CONTROLLER_SET = PathPatternParser.defaultInstance.parse("/api/v1/trading/leverage/settings");
    private static final PathPattern ADMIN_LEVERAGE_CONTROLLER_SET = PathPatternParser.defaultInstance.parse("/api/v1/admin/trading/leverage/settings");
    private static final PathPattern LEVERAGE_CONTROLLER_GET = PathPatternParser.defaultInstance.parse("/api/v1/trading/leverage/settings");
    private static final PathPattern TRADING_FEE_CONTROLLER_UPSERT = PathPatternParser.defaultInstance.parse("/api/v1/admin/trading/fees/schedules");
    private static final PathPattern TRADING_FEE_CONTROLLER_DISABLE = PathPatternParser.defaultInstance.parse("/api/v1/admin/trading/fees/schedules/{feeScheduleId}/disable");
    private static final PathPattern TRADING_FEE_CONTROLLER_QUERY = PathPatternParser.defaultInstance.parse("/api/v1/admin/trading/fees/schedules");
    private static final PathPattern ORDER_CONTROLLER_PLACE = PathPatternParser.defaultInstance.parse("/api/v1/trading/orders");
    private static final PathPattern ORDER_CONTROLLER_PLACEBATCH = PathPatternParser.defaultInstance.parse("/api/v1/trading/orders/batch");
    private static final PathPattern ORDER_CONTROLLER_TEST = PathPatternParser.defaultInstance.parse("/api/v1/trading/orders/test");
    private static final PathPattern ORDER_CONTROLLER_AMEND = PathPatternParser.defaultInstance.parse("/api/v1/trading/orders/amend");
    private static final PathPattern ORDER_CONTROLLER_AMENDBATCH = PathPatternParser.defaultInstance.parse("/api/v1/trading/orders/batch-amend");
    private static final PathPattern ORDER_CONTROLLER_CLOSEPOSITION = PathPatternParser.defaultInstance.parse("/api/v1/trading/orders/close-position");
    private static final PathPattern ORDER_CONTROLLER_CANCEL = PathPatternParser.defaultInstance.parse("/api/v1/trading/orders/cancel");
    private static final PathPattern ORDER_CONTROLLER_CANCELBATCH = PathPatternParser.defaultInstance.parse("/api/v1/trading/orders/batch-cancel");
    private static final PathPattern ORDER_CONTROLLER_CANCELOPEN = PathPatternParser.defaultInstance.parse("/api/v1/trading/orders/cancel-open");
    private static final PathPattern ORDER_CONTROLLER_CANCELALLAFTER = PathPatternParser.defaultInstance.parse("/api/v1/trading/orders/cancel-all-after");
    private static final PathPattern ORDER_CONTROLLER_PLACEALGO = PathPatternParser.defaultInstance.parse("/api/v1/trading/orders/algo");
    private static final PathPattern ORDER_CONTROLLER_CANCELALGO = PathPatternParser.defaultInstance.parse("/api/v1/trading/orders/algo/cancel");
    private static final PathPattern ORDER_CONTROLLER_CANCELOPENALGO = PathPatternParser.defaultInstance.parse("/api/v1/trading/orders/algo/cancel-open");
    private static final PathPattern ORDER_CONTROLLER_GETALGO = PathPatternParser.defaultInstance.parse("/api/v1/trading/orders/algo/{algoOrderId}");
    private static final PathPattern ORDER_CONTROLLER_OPENALGOORDERS = PathPatternParser.defaultInstance.parse("/api/v1/trading/orders/algo/open");
    private static final PathPattern ORDER_CONTROLLER_GET = PathPatternParser.defaultInstance.parse("/api/v1/trading/orders/{orderId}");
    private static final PathPattern ORDER_CONTROLLER_COMMANDRESULT = PathPatternParser.defaultInstance.parse("/api/v1/trading/orders/commands/{commandId}");
    private static final PathPattern ORDER_CONTROLLER_GETBYCLIENTORDERID = PathPatternParser.defaultInstance.parse("/api/v1/trading/orders/by-client-order-id");
    private static final PathPattern ORDER_CONTROLLER_OPENORDERS = PathPatternParser.defaultInstance.parse("/api/v1/trading/orders/open");
    private static final PathPattern ORDER_CONTROLLER_HISTORYORDERS = PathPatternParser.defaultInstance.parse("/api/v1/trading/orders/history");
    private static final PathPattern ADMIN_ORDER_CONTROLLER_ORDERS = PathPatternParser.defaultInstance.parse("/api/v1/admin/trading/orders");
    private static final PathPattern ADMIN_ORDER_CONTROLLER_CANCELORDER = PathPatternParser.defaultInstance.parse("/api/v1/admin/trading/orders/{orderId}/cancel");
    private static final PathPattern ADMIN_ORDER_CONTROLLER_CANCELPREVIEW = PathPatternParser.defaultInstance.parse("/api/v1/admin/trading/orders/cancel-preview");
    private static final PathPattern ADMIN_ORDER_CONTROLLER_CANCELORDERS = PathPatternParser.defaultInstance.parse("/api/v1/admin/trading/orders/cancel");
    private static final PathPattern ADMIN_ORDER_CONTROLLER_CANCELBYSYMBOL = PathPatternParser.defaultInstance.parse("/api/v1/admin/trading/orders/cancel-by-symbol");
    private static final PathPattern INSTRUMENT_CORE_SYNC_CONTROLLER_STATE = PathPatternParser.defaultInstance.parse("/api/v1/admin/trading/orders/instrument-sync/{symbol}");

    private final com.surprising.trading.matching.service.MatchingMarketDataService marketDataService;
    private static final PathPattern ORDER_BOOK = PathPatternParser.defaultInstance.parse("/api/v1/trading/market/orderbook");
    private final AdminMaintenanceRequestService adminMaintenanceRequests;
    private final TriggerOrderRequestService triggerOrderRequests;
    private final AdminTriggerOrderRequestService adminTriggerOrderRequests;
    private final LeverageRequestService leverageRequests;
    private final TradingFeeRequestService tradingFeeRequests;
    private final OrderRequestService orderRequests;
    private final AdminOrderRequestService adminOrderRequests;
    private final InstrumentCoreSyncService instrumentCoreSyncService;

    public TradingLocalRoutes(AdminMaintenanceRequestService adminMaintenanceRequests,
            TriggerOrderRequestService triggerOrderRequests,
            AdminTriggerOrderRequestService adminTriggerOrderRequests,
            LeverageRequestService leverageRequests,
            TradingFeeRequestService tradingFeeRequests,
            OrderRequestService orderRequests,
            AdminOrderRequestService adminOrderRequests,
            InstrumentCoreSyncService instrumentCoreSyncService,
            com.surprising.trading.matching.service.MatchingMarketDataService marketDataService) {
        this.marketDataService = marketDataService;
        this.adminMaintenanceRequests = adminMaintenanceRequests;
        this.triggerOrderRequests = triggerOrderRequests;
        this.adminTriggerOrderRequests = adminTriggerOrderRequests;
        this.leverageRequests = leverageRequests;
        this.tradingFeeRequests = tradingFeeRequests;
        this.orderRequests = orderRequests;
        this.adminOrderRequests = adminOrderRequests;
        this.instrumentCoreSyncService = instrumentCoreSyncService;
    }

    public Object invoke(LocalApiRequest r) {
        if (r.matches(HttpMethod.GET, ORDER_BOOK)) {
            return marketDataService.orderBookSnapshot(r.query("symbol", String.class, null, true),
                    r.query("depth", int.class, "30", false));
        }
        if (r.matches(HttpMethod.GET, EFFECTIVE_FEE)) {
            return tradingFeeRequests.effective(r.query("userId", long.class, null, true),
                    r.query("symbol", String.class, null, true),
                    r.query("instrumentChangeId", long.class, "0", false),
                    r.header("X-Product-Line", String.class, null, false),
                    r.query("productLine", String.class, null, false));
        }
        if (r.isMaintenanceRequest()) {
            adminMaintenanceRequests.authorize(
                    r.header("X-Admin-User-Id", String.class, null, true),
                    r.header("X-Product-Line", String.class, null, true),
                    r.query("productLine", ProductLine.class, null, true));
        }
        if (r.matches(HttpMethod.GET, ADMIN_MAINTENANCE_CONTROLLER_PREVIEW)) {
            return adminMaintenanceRequests.preview(r.query("symbol", String.class, null, true),
                    r.query("userId", long.class, "0", false),
                    r.query("afterUserId", long.class, "0", false));
        }
        if (r.matches(HttpMethod.POST, ADMIN_ORDER_CONTROLLER_CANCELBYSYMBOL)) {
            return adminOrderRequests.cancelBySymbol(r.header("X-Admin-User-Id", String.class, null, false),
                    r.header("X-Product-Line", String.class, null, false),
                    r.query("productLine", String.class, null, false),
                    r.body(AdminCancelBySymbolRequest.class, true, true));
        }
        if (r.matches(HttpMethod.POST, TRIGGER_ORDER_CONTROLLER_CANCELBATCH)) {
            return triggerOrderRequests.cancelBatch(r.body(BatchCancelTriggerOrdersRequest.class, true, false));
        }
        if (r.matches(HttpMethod.GET, ADMIN_ORDER_CONTROLLER_CANCELPREVIEW)) {
            return adminOrderRequests.cancelPreview(r.header("X-Admin-User-Id", String.class, null, false),
                    r.header("X-Product-Line", String.class, null, false),
                    r.query("productLine", String.class, null, false),
                    r.query("userId", Long.class, null, false),
                    r.query("symbol", String.class, null, false),
                    r.query("limit", int.class, "100", false));
        }
        if (r.matches(HttpMethod.POST, TRIGGER_ORDER_CONTROLLER_CANCELOPEN)) {
            return triggerOrderRequests.cancelOpen(r.body(CancelOpenTriggerOrdersRequest.class, true, false));
        }
        if (r.matches(HttpMethod.GET, ORDER_CONTROLLER_GETBYCLIENTORDERID)) {
            return orderRequests.getByClientOrderId(r.query("userId", long.class, null, true),
                    r.query("clientOrderId", String.class, null, true),
                    r.query("minExportSequence", Long.class, null, false));
        }
        if (r.matches(HttpMethod.GET, ADMIN_MAINTENANCE_CONTROLLER_LIST)) {
            return adminMaintenanceRequests.list(r.query("beforeId", long.class, "0", false));
        }
        if (r.matches(HttpMethod.POST, ADMIN_MAINTENANCE_CONTROLLER_CREATE)) {
            return adminMaintenanceRequests.create(r.header("X-Admin-User-Id", String.class, null, true),
                    r.body(MaintenanceRequest.class, true, false));
        }
        if (r.matches(HttpMethod.POST, ORDER_CONTROLLER_CANCELALLAFTER)) {
            return orderRequests.cancelAllAfter(r.body(CancelAllAfterRequest.class, true, false));
        }
        if (r.matches(HttpMethod.POST, ORDER_CONTROLLER_CANCELOPENALGO)) {
            return orderRequests.cancelOpenAlgo(r.body(CancelOpenAlgoOrdersRequest.class, true, false));
        }
        if (r.matches(HttpMethod.POST, TRIGGER_ORDER_CONTROLLER_CANCEL)) {
            return triggerOrderRequests.cancel(r.body(CancelTriggerOrderRequest.class, true, false));
        }
        if (r.matches(HttpMethod.POST, ORDER_CONTROLLER_CLOSEPOSITION)) {
            return orderRequests.closePosition(r.body(ClosePositionRequest.class, true, true));
        }
        if (r.matches(HttpMethod.POST, TRIGGER_ORDER_CONTROLLER_PLACEBATCH)) {
            return triggerOrderRequests.placeBatch(r.body(BatchPlaceTriggerOrderRequest.class, true, false));
        }
        if (r.matches(HttpMethod.GET, ADMIN_TRIGGER_ORDER_CONTROLLER_ORDERS)) {
            return adminTriggerOrderRequests.orders(r.header("X-Admin-User-Id", String.class, null, false),
                    r.header("X-Product-Line", String.class, null, false),
                    r.query("productLine", String.class, null, false),
                    r.query("userId", Long.class, null, false),
                    r.query("symbol", String.class, null, false),
                    r.query("status", String.class, null, false),
                    r.query("triggerOrderId", Long.class, null, false),
                    r.query("limit", int.class, "100", false),
                    r.query("cursor", String.class, null, false),
                    r.query("sort", String.class, null, false));
        }
        if (r.matches(HttpMethod.POST, TRADING_FEE_CONTROLLER_UPSERT)) {
            return tradingFeeRequests.upsert(r.body(FeeScheduleUpsertRequest.class, true, false),
                    r.header("X-Product-Line", String.class, null, false),
                    r.query("productLine", String.class, null, false));
        }
        if (r.matches(HttpMethod.GET, TRADING_FEE_CONTROLLER_QUERY)) {
            return tradingFeeRequests.query(r.query("userId", long.class, "0", false),
                    r.header("X-Product-Line", String.class, null, false),
                    r.query("productLine", String.class, null, false),
                    r.query("symbol", String.class, null, false),
                    r.query("status", FeeScheduleStatus.class, null, false),
                    r.query("limit", int.class, "100", false),
                    r.query("cursor", String.class, null, false),
                    r.query("sort", String.class, null, false));
        }
        if (r.matches(HttpMethod.GET, TRIGGER_ORDER_CONTROLLER_OPENORDERS)) {
            return triggerOrderRequests.openOrders(r.query("userId", long.class, null, true),
                    r.query("symbol", String.class, null, false),
                    r.query("limit", int.class, "100", false),
                    r.query("cursor", String.class, null, false));
        }
        if (r.matches(HttpMethod.POST, ORDER_CONTROLLER_CANCELBATCH)) {
            return orderRequests.cancelBatch(r.body(BatchCancelOrdersRequest.class, true, true));
        }
        if (r.matches(HttpMethod.POST, ADMIN_ORDER_CONTROLLER_CANCELORDERS)) {
            return adminOrderRequests.cancelOrders(r.header("X-Admin-User-Id", String.class, null, false),
                    r.header("X-Product-Line", String.class, null, false),
                    r.query("productLine", String.class, null, false),
                    r.body(AdminBatchCancelOrdersRequest.class, false, true));
        }
        if (r.matches(HttpMethod.POST, ORDER_CONTROLLER_AMENDBATCH)) {
            return orderRequests.amendBatch(r.body(BatchAmendOrdersRequest.class, true, true));
        }
        if (r.matches(HttpMethod.POST, ORDER_CONTROLLER_CANCELOPEN)) {
            return orderRequests.cancelOpen(r.body(CancelOpenOrdersRequest.class, true, false));
        }
        if (r.matches(HttpMethod.POST, ORDER_CONTROLLER_CANCELALGO)) {
            return orderRequests.cancelAlgo(r.body(CancelAlgoOrderRequest.class, true, false));
        }
        if (r.matches(HttpMethod.POST, ADMIN_LEVERAGE_CONTROLLER_SET)) {
            return leverageRequests.adminSet(r.header("X-Admin-User-Id", String.class, null, true),
                    r.body(LeverageSettingRequest.class, true, false),
                    r.header("X-Product-Line", String.class, null, false),
                    r.query("productLine", String.class, null, false));
        }
        if (r.matches(HttpMethod.POST, LEVERAGE_CONTROLLER_SET)) {
            return leverageRequests.set(r.body(LeverageSettingRequest.class, true, false),
                    r.header("X-Product-Line", String.class, null, false),
                    r.query("productLine", String.class, null, false));
        }
        if (r.matches(HttpMethod.GET, LEVERAGE_CONTROLLER_GET)) {
            return leverageRequests.get(r.query("userId", long.class, null, true),
                    r.query("symbol", String.class, null, true),
                    r.query("marginMode", MarginMode.class, null, false),
                    r.header("X-Product-Line", String.class, null, false),
                    r.query("productLine", String.class, null, false));
        }
        if (r.matches(HttpMethod.GET, ORDER_CONTROLLER_OPENALGOORDERS)) {
            return orderRequests.openAlgoOrders(r.query("userId", long.class, null, true),
                    r.query("symbol", String.class, null, false),
                    r.query("limit", int.class, "100", false));
        }
        if (r.matches(HttpMethod.POST, TRIGGER_ORDER_CONTROLLER_PLACE)) {
            return triggerOrderRequests.place(r.body(PlaceTriggerOrderRequest.class, true, false));
        }
        if (r.matches(HttpMethod.GET, ORDER_CONTROLLER_HISTORYORDERS)) {
            return orderRequests.historyOrders(r.query("userId", long.class, null, true),
                    r.query("symbol", String.class, null, false),
                    r.query("limit", int.class, "100", false),
                    r.query("orderId", Long.class, null, false),
                    r.query("startTime", Long.class, null, false),
                    r.query("endTime", Long.class, null, false),
                    r.query("cursor", String.class, null, false),
                    r.query("minExportSequence", Long.class, null, false));
        }
        if (r.matches(HttpMethod.POST, ORDER_CONTROLLER_CANCEL)) {
            return orderRequests.cancel(r.body(CancelOrderRequest.class, true, false));
        }
        if (r.matches(HttpMethod.POST, ORDER_CONTROLLER_PLACEBATCH)) {
            return orderRequests.placeBatch(r.body(BatchPlaceOrderRequest.class, true, true));
        }
        if (r.matches(HttpMethod.POST, ORDER_CONTROLLER_AMEND)) {
            return orderRequests.amend(r.body(AmendOrderRequest.class, true, true));
        }
        if (r.matches(HttpMethod.GET, ADMIN_ORDER_CONTROLLER_ORDERS)) {
            return adminOrderRequests.orders(r.header("X-Admin-User-Id", String.class, null, false),
                    r.header("X-Product-Line", String.class, null, false),
                    r.query("productLine", String.class, null, false),
                    r.query("userId", Long.class, null, false),
                    r.query("symbol", String.class, null, false),
                    r.query("status", String.class, null, false),
                    r.query("orderId", Long.class, null, false),
                    r.query("limit", int.class, "100", false),
                    r.query("cursor", String.class, null, false),
                    r.query("sort", String.class, null, false));
        }
        if (r.matches(HttpMethod.POST, ORDER_CONTROLLER_TEST)) {
            return orderRequests.test(r.body(PlaceOrderRequest.class, true, true));
        }
        if (r.matches(HttpMethod.POST, ORDER_CONTROLLER_PLACEALGO)) {
            return orderRequests.placeAlgo(r.body(PlaceAlgoOrderRequest.class, true, false));
        }
        if (r.matches(HttpMethod.GET, ORDER_CONTROLLER_OPENORDERS)) {
            return orderRequests.openOrders(r.query("userId", long.class, null, true),
                    r.query("symbol", String.class, null, false),
                    r.query("limit", int.class, "100", false),
                    r.query("cursor", String.class, null, false),
                    r.query("minExportSequence", Long.class, null, false));
        }
        if (r.matches(HttpMethod.POST, ORDER_CONTROLLER_PLACE)) {
            return orderRequests.place(r.body(PlaceOrderRequest.class, true, true));
        }
        if (r.matches(HttpMethod.GET, ADMIN_TRIGGER_ORDER_CONTROLLER_TIMELINE)) {
            return adminTriggerOrderRequests.timeline(r.header("X-Admin-User-Id", String.class, null, false),
                    r.header("X-Product-Line", String.class, null, false),
                    r.query("productLine", String.class, null, false),
                    r.path("triggerOrderId", long.class));
        }
        if (r.matches(HttpMethod.POST, TRADING_FEE_CONTROLLER_DISABLE)) {
            return tradingFeeRequests.disable(r.path("feeScheduleId", long.class),
                    r.header("X-Product-Line", String.class, null, false),
                    r.query("productLine", String.class, null, false));
        }
        if (r.matches(HttpMethod.GET, ADMIN_MAINTENANCE_CONTROLLER_ACTIONS)) {
            return adminMaintenanceRequests.actions(r.path("id", long.class),
                    r.query("afterKey", String.class, "", false));
        }
        if (r.matches(HttpMethod.POST, ADMIN_MAINTENANCE_CONTROLLER_RELEASE)) {
            return adminMaintenanceRequests.release(r.path("id", long.class));
        }
        if (r.matches(HttpMethod.GET, ADMIN_TRIGGER_ORDER_CONTROLLER_ORDER)) {
            return adminTriggerOrderRequests.order(r.header("X-Admin-User-Id", String.class, null, false),
                    r.header("X-Product-Line", String.class, null, false),
                    r.query("productLine", String.class, null, false),
                    r.path("triggerOrderId", long.class));
        }
        if (r.matches(HttpMethod.GET, INSTRUMENT_CORE_SYNC_CONTROLLER_STATE)) {
            return instrumentCoreSyncService.state(r.path("symbol", String.class),
                    r.query("productLine", ProductLine.class, null, true));
        }
        if (r.matches(HttpMethod.POST, ADMIN_MAINTENANCE_CONTROLLER_RETRY)) {
            return adminMaintenanceRequests.retry(r.path("id", long.class));
        }
        if (r.matches(HttpMethod.GET, TRIGGER_ORDER_CONTROLLER_GET)) {
            return triggerOrderRequests.get(r.query("userId", long.class, null, true),
                    r.path("triggerOrderId", long.class));
        }
        if (r.matches(HttpMethod.GET, ADMIN_MAINTENANCE_CONTROLLER_GET)) {
            return adminMaintenanceRequests.get(r.path("id", long.class));
        }
        if (r.matches(HttpMethod.POST, ADMIN_ORDER_CONTROLLER_CANCELORDER)) {
            return adminOrderRequests.cancelOrder(r.header("X-Admin-User-Id", String.class, null, false),
                    r.header("X-Product-Line", String.class, null, false),
                    r.query("productLine", String.class, null, false),
                    r.path("orderId", long.class),
                    r.body(AdminCancelOrderRequest.class, false, true));
        }
        if (r.matches(HttpMethod.GET, ORDER_CONTROLLER_COMMANDRESULT)) {
            return orderRequests.commandResult(r.path("commandId", UUID.class));
        }
        if (r.matches(HttpMethod.GET, ORDER_CONTROLLER_GETALGO)) {
            return orderRequests.getAlgo(r.path("algoOrderId", long.class));
        }
        if (r.matches(HttpMethod.GET, ORDER_CONTROLLER_GET)) {
            return orderRequests.get(r.query("userId", long.class, null, true),
                    r.path("orderId", long.class),
                    r.query("minExportSequence", Long.class, null, false));
        }
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown local trading endpoint");
    }
}
