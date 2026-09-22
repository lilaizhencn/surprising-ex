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
import com.surprising.trading.maintenance.AdminMaintenanceController;
import com.surprising.trading.order.controller.AdminOrderController;
import com.surprising.trading.order.controller.LeverageController;
import com.surprising.trading.order.controller.OrderController;
import com.surprising.trading.order.controller.TradingFeeController;
import com.surprising.trading.order.service.InstrumentCoreSyncController;
import com.surprising.trading.trigger.controller.AdminTriggerOrderController;
import com.surprising.trading.trigger.controller.TriggerOrderController;
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

    private final AdminMaintenanceController adminMaintenanceController;
    private final TriggerOrderController triggerOrderController;
    private final AdminTriggerOrderController adminTriggerOrderController;
    private final LeverageController leverageController;
    private final TradingFeeController tradingFeeController;
    private final OrderController orderController;
    private final AdminOrderController adminOrderController;
    private final InstrumentCoreSyncController instrumentCoreSyncController;

    public TradingLocalRoutes(AdminMaintenanceController adminMaintenanceController,
            TriggerOrderController triggerOrderController,
            AdminTriggerOrderController adminTriggerOrderController,
            LeverageController leverageController,
            TradingFeeController tradingFeeController,
            OrderController orderController,
            AdminOrderController adminOrderController,
            InstrumentCoreSyncController instrumentCoreSyncController) {
        this.adminMaintenanceController = adminMaintenanceController;
        this.triggerOrderController = triggerOrderController;
        this.adminTriggerOrderController = adminTriggerOrderController;
        this.leverageController = leverageController;
        this.tradingFeeController = tradingFeeController;
        this.orderController = orderController;
        this.adminOrderController = adminOrderController;
        this.instrumentCoreSyncController = instrumentCoreSyncController;
    }

    public Object invoke(LocalApiRequest r) {
        if (r.matches(HttpMethod.GET, EFFECTIVE_FEE)) {
            return tradingFeeController.effective(r.query("userId", long.class, null, true),
                    r.query("symbol", String.class, null, true),
                    r.query("instrumentChangeId", long.class, "0", false),
                    r.header("X-Product-Line", String.class, null, false),
                    r.query("productLine", String.class, null, false));
        }
        if (r.isMaintenanceRequest()) {
            adminMaintenanceController.authorize(
                    r.header("X-Admin-User-Id", String.class, null, true),
                    r.header("X-Product-Line", String.class, null, true),
                    r.query("productLine", ProductLine.class, null, true));
        }
        if (r.matches(HttpMethod.GET, ADMIN_MAINTENANCE_CONTROLLER_PREVIEW)) {
            return adminMaintenanceController.preview(r.query("symbol", String.class, null, true),
                    r.query("userId", long.class, "0", false),
                    r.query("afterUserId", long.class, "0", false));
        }
        if (r.matches(HttpMethod.POST, ADMIN_ORDER_CONTROLLER_CANCELBYSYMBOL)) {
            return adminOrderController.cancelBySymbol(r.header("X-Admin-User-Id", String.class, null, false),
                    r.header("X-Product-Line", String.class, null, false),
                    r.query("productLine", String.class, null, false),
                    r.body(AdminCancelBySymbolRequest.class, true, true));
        }
        if (r.matches(HttpMethod.POST, TRIGGER_ORDER_CONTROLLER_CANCELBATCH)) {
            return triggerOrderController.cancelBatch(r.body(BatchCancelTriggerOrdersRequest.class, true, false));
        }
        if (r.matches(HttpMethod.GET, ADMIN_ORDER_CONTROLLER_CANCELPREVIEW)) {
            return adminOrderController.cancelPreview(r.header("X-Admin-User-Id", String.class, null, false),
                    r.header("X-Product-Line", String.class, null, false),
                    r.query("productLine", String.class, null, false),
                    r.query("userId", Long.class, null, false),
                    r.query("symbol", String.class, null, false),
                    r.query("limit", int.class, "100", false));
        }
        if (r.matches(HttpMethod.POST, TRIGGER_ORDER_CONTROLLER_CANCELOPEN)) {
            return triggerOrderController.cancelOpen(r.body(CancelOpenTriggerOrdersRequest.class, true, false));
        }
        if (r.matches(HttpMethod.GET, ORDER_CONTROLLER_GETBYCLIENTORDERID)) {
            return orderController.getByClientOrderId(r.query("userId", long.class, null, true),
                    r.query("clientOrderId", String.class, null, true),
                    r.query("minExportSequence", Long.class, null, false));
        }
        if (r.matches(HttpMethod.GET, ADMIN_MAINTENANCE_CONTROLLER_LIST)) {
            return adminMaintenanceController.list(r.query("beforeId", long.class, "0", false));
        }
        if (r.matches(HttpMethod.POST, ADMIN_MAINTENANCE_CONTROLLER_CREATE)) {
            return adminMaintenanceController.create(r.header("X-Admin-User-Id", String.class, null, true),
                    r.body(MaintenanceRequest.class, true, false));
        }
        if (r.matches(HttpMethod.POST, ORDER_CONTROLLER_CANCELALLAFTER)) {
            return orderController.cancelAllAfter(r.body(CancelAllAfterRequest.class, true, false));
        }
        if (r.matches(HttpMethod.POST, ORDER_CONTROLLER_CANCELOPENALGO)) {
            return orderController.cancelOpenAlgo(r.body(CancelOpenAlgoOrdersRequest.class, true, false));
        }
        if (r.matches(HttpMethod.POST, TRIGGER_ORDER_CONTROLLER_CANCEL)) {
            return triggerOrderController.cancel(r.body(CancelTriggerOrderRequest.class, true, false));
        }
        if (r.matches(HttpMethod.POST, ORDER_CONTROLLER_CLOSEPOSITION)) {
            return orderController.closePosition(r.body(ClosePositionRequest.class, true, true));
        }
        if (r.matches(HttpMethod.POST, TRIGGER_ORDER_CONTROLLER_PLACEBATCH)) {
            return triggerOrderController.placeBatch(r.body(BatchPlaceTriggerOrderRequest.class, true, false));
        }
        if (r.matches(HttpMethod.GET, ADMIN_TRIGGER_ORDER_CONTROLLER_ORDERS)) {
            return adminTriggerOrderController.orders(r.header("X-Admin-User-Id", String.class, null, false),
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
            return tradingFeeController.upsert(r.body(FeeScheduleUpsertRequest.class, true, false),
                    r.header("X-Product-Line", String.class, null, false),
                    r.query("productLine", String.class, null, false));
        }
        if (r.matches(HttpMethod.GET, TRADING_FEE_CONTROLLER_QUERY)) {
            return tradingFeeController.query(r.query("userId", long.class, "0", false),
                    r.header("X-Product-Line", String.class, null, false),
                    r.query("productLine", String.class, null, false),
                    r.query("symbol", String.class, null, false),
                    r.query("status", FeeScheduleStatus.class, null, false),
                    r.query("limit", int.class, "100", false),
                    r.query("cursor", String.class, null, false),
                    r.query("sort", String.class, null, false));
        }
        if (r.matches(HttpMethod.GET, TRIGGER_ORDER_CONTROLLER_OPENORDERS)) {
            return triggerOrderController.openOrders(r.query("userId", long.class, null, true),
                    r.query("symbol", String.class, null, false),
                    r.query("limit", int.class, "100", false),
                    r.query("cursor", String.class, null, false));
        }
        if (r.matches(HttpMethod.POST, ORDER_CONTROLLER_CANCELBATCH)) {
            return orderController.cancelBatch(r.body(BatchCancelOrdersRequest.class, true, true));
        }
        if (r.matches(HttpMethod.POST, ADMIN_ORDER_CONTROLLER_CANCELORDERS)) {
            return adminOrderController.cancelOrders(r.header("X-Admin-User-Id", String.class, null, false),
                    r.header("X-Product-Line", String.class, null, false),
                    r.query("productLine", String.class, null, false),
                    r.body(AdminBatchCancelOrdersRequest.class, false, true));
        }
        if (r.matches(HttpMethod.POST, ORDER_CONTROLLER_AMENDBATCH)) {
            return orderController.amendBatch(r.body(BatchAmendOrdersRequest.class, true, true));
        }
        if (r.matches(HttpMethod.POST, ORDER_CONTROLLER_CANCELOPEN)) {
            return orderController.cancelOpen(r.body(CancelOpenOrdersRequest.class, true, false));
        }
        if (r.matches(HttpMethod.POST, ORDER_CONTROLLER_CANCELALGO)) {
            return orderController.cancelAlgo(r.body(CancelAlgoOrderRequest.class, true, false));
        }
        if (r.matches(HttpMethod.POST, LEVERAGE_CONTROLLER_SET)) {
            return leverageController.set(r.body(LeverageSettingRequest.class, true, false),
                    r.header("X-Product-Line", String.class, null, false),
                    r.query("productLine", String.class, null, false));
        }
        if (r.matches(HttpMethod.GET, LEVERAGE_CONTROLLER_GET)) {
            return leverageController.get(r.query("userId", long.class, null, true),
                    r.query("symbol", String.class, null, true),
                    r.query("marginMode", MarginMode.class, null, false),
                    r.header("X-Product-Line", String.class, null, false),
                    r.query("productLine", String.class, null, false));
        }
        if (r.matches(HttpMethod.GET, ORDER_CONTROLLER_OPENALGOORDERS)) {
            return orderController.openAlgoOrders(r.query("userId", long.class, null, true),
                    r.query("symbol", String.class, null, false),
                    r.query("limit", int.class, "100", false));
        }
        if (r.matches(HttpMethod.POST, TRIGGER_ORDER_CONTROLLER_PLACE)) {
            return triggerOrderController.place(r.body(PlaceTriggerOrderRequest.class, true, false));
        }
        if (r.matches(HttpMethod.GET, ORDER_CONTROLLER_HISTORYORDERS)) {
            return orderController.historyOrders(r.query("userId", long.class, null, true),
                    r.query("symbol", String.class, null, false),
                    r.query("limit", int.class, "100", false),
                    r.query("orderId", Long.class, null, false),
                    r.query("startTime", Long.class, null, false),
                    r.query("endTime", Long.class, null, false),
                    r.query("cursor", String.class, null, false),
                    r.query("minExportSequence", Long.class, null, false));
        }
        if (r.matches(HttpMethod.POST, ORDER_CONTROLLER_CANCEL)) {
            return orderController.cancel(r.body(CancelOrderRequest.class, true, false));
        }
        if (r.matches(HttpMethod.POST, ORDER_CONTROLLER_PLACEBATCH)) {
            return orderController.placeBatch(r.body(BatchPlaceOrderRequest.class, true, true));
        }
        if (r.matches(HttpMethod.POST, ORDER_CONTROLLER_AMEND)) {
            return orderController.amend(r.body(AmendOrderRequest.class, true, true));
        }
        if (r.matches(HttpMethod.GET, ADMIN_ORDER_CONTROLLER_ORDERS)) {
            return adminOrderController.orders(r.header("X-Admin-User-Id", String.class, null, false),
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
            return orderController.test(r.body(PlaceOrderRequest.class, true, true));
        }
        if (r.matches(HttpMethod.POST, ORDER_CONTROLLER_PLACEALGO)) {
            return orderController.placeAlgo(r.body(PlaceAlgoOrderRequest.class, true, false));
        }
        if (r.matches(HttpMethod.GET, ORDER_CONTROLLER_OPENORDERS)) {
            return orderController.openOrders(r.query("userId", long.class, null, true),
                    r.query("symbol", String.class, null, false),
                    r.query("limit", int.class, "100", false),
                    r.query("cursor", String.class, null, false),
                    r.query("minExportSequence", Long.class, null, false));
        }
        if (r.matches(HttpMethod.POST, ORDER_CONTROLLER_PLACE)) {
            return orderController.place(r.body(PlaceOrderRequest.class, true, true));
        }
        if (r.matches(HttpMethod.GET, ADMIN_TRIGGER_ORDER_CONTROLLER_TIMELINE)) {
            return adminTriggerOrderController.timeline(r.header("X-Admin-User-Id", String.class, null, false),
                    r.header("X-Product-Line", String.class, null, false),
                    r.query("productLine", String.class, null, false),
                    r.path("triggerOrderId", long.class));
        }
        if (r.matches(HttpMethod.POST, TRADING_FEE_CONTROLLER_DISABLE)) {
            return tradingFeeController.disable(r.path("feeScheduleId", long.class),
                    r.header("X-Product-Line", String.class, null, false),
                    r.query("productLine", String.class, null, false));
        }
        if (r.matches(HttpMethod.GET, ADMIN_MAINTENANCE_CONTROLLER_ACTIONS)) {
            return adminMaintenanceController.actions(r.path("id", long.class),
                    r.query("afterKey", String.class, "", false));
        }
        if (r.matches(HttpMethod.POST, ADMIN_MAINTENANCE_CONTROLLER_RELEASE)) {
            return adminMaintenanceController.release(r.path("id", long.class));
        }
        if (r.matches(HttpMethod.GET, ADMIN_TRIGGER_ORDER_CONTROLLER_ORDER)) {
            return adminTriggerOrderController.order(r.header("X-Admin-User-Id", String.class, null, false),
                    r.header("X-Product-Line", String.class, null, false),
                    r.query("productLine", String.class, null, false),
                    r.path("triggerOrderId", long.class));
        }
        if (r.matches(HttpMethod.GET, INSTRUMENT_CORE_SYNC_CONTROLLER_STATE)) {
            return instrumentCoreSyncController.state(r.path("symbol", String.class),
                    r.query("productLine", ProductLine.class, null, true));
        }
        if (r.matches(HttpMethod.POST, ADMIN_MAINTENANCE_CONTROLLER_RETRY)) {
            return adminMaintenanceController.retry(r.path("id", long.class));
        }
        if (r.matches(HttpMethod.GET, TRIGGER_ORDER_CONTROLLER_GET)) {
            return triggerOrderController.get(r.query("userId", long.class, null, true),
                    r.path("triggerOrderId", long.class));
        }
        if (r.matches(HttpMethod.GET, ADMIN_MAINTENANCE_CONTROLLER_GET)) {
            return adminMaintenanceController.get(r.path("id", long.class));
        }
        if (r.matches(HttpMethod.POST, ADMIN_ORDER_CONTROLLER_CANCELORDER)) {
            return adminOrderController.cancelOrder(r.header("X-Admin-User-Id", String.class, null, false),
                    r.header("X-Product-Line", String.class, null, false),
                    r.query("productLine", String.class, null, false),
                    r.path("orderId", long.class),
                    r.body(AdminCancelOrderRequest.class, false, true));
        }
        if (r.matches(HttpMethod.GET, ORDER_CONTROLLER_COMMANDRESULT)) {
            return orderController.commandResult(r.path("commandId", UUID.class));
        }
        if (r.matches(HttpMethod.GET, ORDER_CONTROLLER_GETALGO)) {
            return orderController.getAlgo(r.path("algoOrderId", long.class));
        }
        if (r.matches(HttpMethod.GET, ORDER_CONTROLLER_GET)) {
            return orderController.get(r.query("userId", long.class, null, true),
                    r.path("orderId", long.class),
                    r.query("minExportSequence", Long.class, null, false));
        }
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown local trading endpoint");
    }
}
