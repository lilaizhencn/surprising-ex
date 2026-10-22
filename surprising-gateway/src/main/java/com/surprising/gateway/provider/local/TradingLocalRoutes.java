package com.surprising.gateway.provider.local;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.AdminBatchCancelOrdersRequest;
import com.surprising.trading.api.model.AdminCancelByInstrumentRequest;
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
import com.surprising.trading.maintenance.controller.AdminMaintenanceRequestHandler;
import com.surprising.trading.order.controller.AdminOrderRequestHandler;
import com.surprising.trading.order.controller.LeverageRequestHandler;
import com.surprising.trading.order.controller.OrderRequestHandler;
import com.surprising.trading.order.controller.TradingFeeRequestHandler;
import com.surprising.trading.order.service.InstrumentCoreSyncService;
import com.surprising.trading.trigger.controller.AdminTriggerOrderRequestHandler;
import com.surprising.trading.trigger.controller.TriggerOrderRequestHandler;
import java.util.UUID;
import com.surprising.trading.maintenance.controller.MaintenanceRequest;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/** 网关协议到订单入口的本地调用；不建立内部 HTTP 连接。 */
public final class TradingLocalRoutes {
    private static final PathPattern OWN_FILLS = PathPatternParser.defaultInstance.parse("/api/v1/trading/orders/trades");
    private static final PathPattern ADMIN_METRICS = PathPatternParser.defaultInstance.parse("/api/v1/admin/trading/orders/metrics");
    private static final PathPattern ADMIN_TRADES = PathPatternParser.defaultInstance.parse("/api/v1/admin/trading/orders/trades");
    private static final PathPattern TRIGGER_OCO_SUBMISSION = PathPatternParser.defaultInstance.parse("/api/v1/trading/trigger-orders/oco-submission");
    private static final PathPattern TRIGGER_SUBMISSION = PathPatternParser.defaultInstance.parse("/api/v1/trading/trigger-orders/submission");
    private static final PathPattern TRIGGER_CANCELLATION = PathPatternParser.defaultInstance.parse("/api/v1/trading/trigger-orders/cancellations/{triggerOrderId}");
    private static final PathPattern CANCELLATION_PLAN = PathPatternParser.defaultInstance.parse("/api/v1/trading/orders/cancellation-plan");
    private static final PathPattern CANCELLATION_BATCH = PathPatternParser.defaultInstance.parse("/api/v1/trading/orders/cancellation-batches");
    private static final PathPattern AMENDMENT = PathPatternParser.defaultInstance.parse("/api/v1/trading/orders/amendments");
    private static final PathPattern CANCELLATION = PathPatternParser.defaultInstance.parse("/api/v1/trading/orders/cancellations/{orderId}");
    private static final PathPattern SUBMISSION = PathPatternParser.defaultInstance.parse("/api/v1/trading/orders/submission");
    private static final PathPattern ALGO_SUBMISSION = PathPatternParser.defaultInstance.parse("/api/v1/trading/orders/algo/submission");
    private static final PathPattern PRICE_PROTECTION = PathPatternParser.defaultInstance.parse("/api/v1/trading/orders/price-protection");
    private static final PathPattern EFFECTIVE_FEE = PathPatternParser.defaultInstance.parse("/api/v1/trading/fees/effective");
    private static final PathPattern ADMIN_EFFECTIVE_FEE = PathPatternParser.defaultInstance.parse("/api/v1/admin/trading/fees/effective");
    private static final PathPattern ADMIN_MAINTENANCE_TASKS = PathPatternParser.defaultInstance.parse("/api/v1/admin/trading/orders/maintenance");
    private static final PathPattern ADMIN_MAINTENANCE_PREVIEW = PathPatternParser.defaultInstance.parse("/api/v1/admin/trading/orders/maintenance/preview");
    private static final PathPattern ADMIN_MAINTENANCE_GET = PathPatternParser.defaultInstance.parse("/api/v1/admin/trading/orders/maintenance/{id}");
    private static final PathPattern ADMIN_MAINTENANCE_ACTIONS = PathPatternParser.defaultInstance.parse("/api/v1/admin/trading/orders/maintenance/{id}/actions");
    private static final PathPattern ADMIN_MAINTENANCE_RETRY = PathPatternParser.defaultInstance.parse("/api/v1/admin/trading/orders/maintenance/{id}/retry");
    private static final PathPattern ADMIN_MAINTENANCE_RELEASE = PathPatternParser.defaultInstance.parse("/api/v1/admin/trading/orders/maintenance/{id}/release");
    private static final PathPattern TRIGGER_ORDER_PLACE = PathPatternParser.defaultInstance.parse("/api/v1/trading/trigger-orders");
    private static final PathPattern TRIGGER_ORDER_PLACE_BATCH = PathPatternParser.defaultInstance.parse("/api/v1/trading/trigger-orders/batch");
    private static final PathPattern TRIGGER_ORDER_CANCEL = PathPatternParser.defaultInstance.parse("/api/v1/trading/trigger-orders/cancel");
    private static final PathPattern TRIGGER_ORDER_CANCEL_BATCH = PathPatternParser.defaultInstance.parse("/api/v1/trading/trigger-orders/batch-cancel");
    private static final PathPattern TRIGGER_ORDER_CANCEL_OPEN = PathPatternParser.defaultInstance.parse("/api/v1/trading/trigger-orders/cancel-open");
    private static final PathPattern TRIGGER_ORDER_GET = PathPatternParser.defaultInstance.parse("/api/v1/trading/trigger-orders/{triggerOrderId}");
    private static final PathPattern TRIGGER_ORDER_OPEN_ORDERS = PathPatternParser.defaultInstance.parse("/api/v1/trading/trigger-orders/open");
    private static final PathPattern ADMIN_TRIGGER_ORDER_ORDERS = PathPatternParser.defaultInstance.parse("/api/v1/admin/trading/trigger-orders");
    private static final PathPattern ADMIN_TRIGGER_ORDER_ORDER = PathPatternParser.defaultInstance.parse("/api/v1/admin/trading/trigger-orders/{triggerOrderId}");
    private static final PathPattern ADMIN_TRIGGER_ORDER_TIMELINE = PathPatternParser.defaultInstance.parse("/api/v1/admin/trading/trigger-orders/{triggerOrderId}/timeline");
    private static final PathPattern LEVERAGE_SETTINGS = PathPatternParser.defaultInstance.parse("/api/v1/trading/leverage/settings");
    private static final PathPattern ADMIN_LEVERAGE_SET = PathPatternParser.defaultInstance.parse("/api/v1/admin/trading/leverage/settings");
    private static final PathPattern FEE_SCHEDULES = PathPatternParser.defaultInstance.parse("/api/v1/admin/trading/fees/schedules");
    private static final PathPattern TRADING_FEE_DISABLE = PathPatternParser.defaultInstance.parse("/api/v1/admin/trading/fees/schedules/{feeScheduleId}/disable");
    private static final PathPattern ORDER_PLACE = PathPatternParser.defaultInstance.parse("/api/v1/trading/orders");
    private static final PathPattern ORDER_PLACE_BATCH = PathPatternParser.defaultInstance.parse("/api/v1/trading/orders/batch");
    private static final PathPattern ORDER_TEST = PathPatternParser.defaultInstance.parse("/api/v1/trading/orders/test");
    private static final PathPattern ORDER_AMEND = PathPatternParser.defaultInstance.parse("/api/v1/trading/orders/amend");
    private static final PathPattern ORDER_AMEND_BATCH = PathPatternParser.defaultInstance.parse("/api/v1/trading/orders/batch-amend");
    private static final PathPattern ORDER_CLOSE_POSITION = PathPatternParser.defaultInstance.parse("/api/v1/trading/orders/close-position");
    private static final PathPattern ORDER_CANCEL = PathPatternParser.defaultInstance.parse("/api/v1/trading/orders/cancel");
    private static final PathPattern ORDER_CANCEL_BATCH = PathPatternParser.defaultInstance.parse("/api/v1/trading/orders/batch-cancel");
    private static final PathPattern ORDER_CANCEL_OPEN = PathPatternParser.defaultInstance.parse("/api/v1/trading/orders/cancel-open");
    private static final PathPattern ORDER_CANCEL_ALL_AFTER = PathPatternParser.defaultInstance.parse("/api/v1/trading/orders/cancel-all-after");
    private static final PathPattern ORDER_PLACE_ALGO = PathPatternParser.defaultInstance.parse("/api/v1/trading/orders/algo");
    private static final PathPattern ORDER_CANCEL_ALGO = PathPatternParser.defaultInstance.parse("/api/v1/trading/orders/algo/cancel");
    private static final PathPattern ORDER_CANCEL_OPEN_ALGO = PathPatternParser.defaultInstance.parse("/api/v1/trading/orders/algo/cancel-open");
    private static final PathPattern ORDER_GET_ALGO = PathPatternParser.defaultInstance.parse("/api/v1/trading/orders/algo/{algoOrderId}");
    private static final PathPattern ORDER_OPEN_ALGO_ORDERS = PathPatternParser.defaultInstance.parse("/api/v1/trading/orders/algo/open");
    private static final PathPattern ORDER_GET = PathPatternParser.defaultInstance.parse("/api/v1/trading/orders/{orderId}");
    private static final PathPattern ORDER_COMMAND_RESULT = PathPatternParser.defaultInstance.parse("/api/v1/trading/orders/commands/{commandId}");
    private static final PathPattern ORDER_GET_BY_CLIENT_ORDER_ID = PathPatternParser.defaultInstance.parse("/api/v1/trading/orders/by-client-order-id");
    private static final PathPattern ORDER_OPEN_ORDERS = PathPatternParser.defaultInstance.parse("/api/v1/trading/orders/open");
    private static final PathPattern ORDER_HISTORY_ORDERS = PathPatternParser.defaultInstance.parse("/api/v1/trading/orders/history");
    private static final PathPattern ADMIN_ORDER_ORDERS = PathPatternParser.defaultInstance.parse("/api/v1/admin/trading/orders");
    private static final PathPattern ADMIN_ORDER_CANCEL_ORDER = PathPatternParser.defaultInstance.parse("/api/v1/admin/trading/orders/{orderId}/cancel");
    private static final PathPattern ADMIN_ORDER_CANCEL_PREVIEW = PathPatternParser.defaultInstance.parse("/api/v1/admin/trading/orders/cancel-preview");
    private static final PathPattern ADMIN_ORDER_CANCEL_ORDERS = PathPatternParser.defaultInstance.parse("/api/v1/admin/trading/orders/cancel");
    private static final PathPattern ADMIN_ORDER_CANCEL_BY_INSTRUMENT = PathPatternParser.defaultInstance.parse("/api/v1/admin/trading/orders/cancel-by-instrument");
    private static final PathPattern INSTRUMENT_CORE_SYNC_STATE = PathPatternParser.defaultInstance.parse("/api/v1/admin/trading/orders/instrument-sync/{instrumentId}");

    private final com.surprising.trading.matching.service.MatchingMarketDataService marketDataService;
    private static final PathPattern ORDER_BOOK = PathPatternParser.defaultInstance.parse("/api/v1/trading/market/orderbook");
    private final AdminMaintenanceRequestHandler adminMaintenanceRequests;
    private final TriggerOrderRequestHandler triggerOrderRequests;
    private final AdminTriggerOrderRequestHandler adminTriggerOrderRequests;
    private final LeverageRequestHandler leverageRequests;
    private final TradingFeeRequestHandler tradingFeeRequests;
    private final OrderRequestHandler orderRequests;
    private final AdminOrderRequestHandler adminOrderRequests;
    private final InstrumentCoreSyncService instrumentCoreSyncService;

    public TradingLocalRoutes(AdminMaintenanceRequestHandler adminMaintenanceRequests,
            TriggerOrderRequestHandler triggerOrderRequests,
            AdminTriggerOrderRequestHandler adminTriggerOrderRequests,
            LeverageRequestHandler leverageRequests,
            TradingFeeRequestHandler tradingFeeRequests,
            OrderRequestHandler orderRequests,
            AdminOrderRequestHandler adminOrderRequests,
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
        if (r.matches(HttpMethod.GET, OWN_FILLS)) return orderRequests.ownFills(
                r.query("userId", long.class, null, true), r.query("instrumentId", String.class, null, false),
                r.query("orderId", Long.class, null, false), r.query("startTime", Long.class, null, false),
                r.query("endTime", Long.class, null, false), r.query("limit", int.class, "100", false),
                r.query("cursor", String.class, null, false));
        if(r.matches(HttpMethod.GET,ADMIN_METRICS))return adminOrderRequests.metrics(r.header("X-Admin-User-Id",String.class,null,true),
                r.header("X-Product-Line",String.class,null,false),r.query("productLine",String.class,null,false),
                r.query("windowMinutes",int.class,"1440",false),r.query("limit",int.class,"20",false));
        if (r.matches(HttpMethod.GET, ADMIN_TRADES)) {
            return adminOrderRequests.trades(r.header("X-Admin-User-Id", String.class, null, true),
                    r.header("X-Product-Line", String.class, null, false), r.query("productLine", String.class, null, false),
                    r.query("userId", Long.class, null, false), r.query("instrumentId", String.class, null, false),
                    r.query("orderId", Long.class, null, false), r.query("limit", int.class, "100", false),
                    r.query("cursor", String.class, null, false), r.query("sort", String.class, null, false));
        }
        if (r.matches(HttpMethod.GET, CANCELLATION_PLAN)) return orderRequests.cancellationPlan(
                r.query("userId", long.class, null, true), r.query("instrumentId", String.class, null, false));
        if (r.matches(HttpMethod.GET, CANCELLATION_BATCH)) return orderRequests.cancellationBatch(
                r.query("userId", long.class, null, true), r.query("batchKey", String.class, null, true));
        if (r.matches(HttpMethod.GET, AMENDMENT)) return orderRequests.amendment(
                r.query("userId", long.class, null, true), r.query("clientRequestId", String.class, null, true));
        if (r.matches(HttpMethod.GET, CANCELLATION)) return orderRequests.cancellation(
                r.query("userId", long.class, null, true), r.path("orderId", long.class));
        if (r.matches(HttpMethod.GET, SUBMISSION)) return orderRequests.submission(
                r.query("userId", long.class, null, true), r.query("clientOrderId", String.class, null, true));
        if (r.matches(HttpMethod.GET, ALGO_SUBMISSION)) return orderRequests.algoSubmission(
                r.query("userId", long.class, null, true), r.query("clientAlgoOrderId", String.class, null, true));
        if (r.matches(HttpMethod.GET, PRICE_PROTECTION)) return orderRequests.priceProtection();
        if (r.matches(HttpMethod.GET, ORDER_BOOK)) {
            return marketDataService.orderBookSnapshot(r.query("instrumentId", String.class, null, true),
                    r.query("depth", int.class, "30", false));
        }
        if (r.matches(HttpMethod.GET, EFFECTIVE_FEE) || r.matches(HttpMethod.GET, ADMIN_EFFECTIVE_FEE)) {
            return tradingFeeRequests.effective(r.query("userId", long.class, null, true),
                    r.query("instrumentId", String.class, null, true),
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
        if (r.matches(HttpMethod.GET, ADMIN_MAINTENANCE_PREVIEW)) {
            return adminMaintenanceRequests.preview(r.query("instrumentId", String.class, null, true),
                    r.query("userId", long.class, "0", false),
                    r.query("afterUserId", long.class, "0", false));
        }
        if (r.matches(HttpMethod.POST, ADMIN_ORDER_CANCEL_BY_INSTRUMENT)) {
            return adminOrderRequests.cancelByInstrument(r.header("X-Admin-User-Id", String.class, null, false),
                    r.header("X-Product-Line", String.class, null, false),
                    r.query("productLine", String.class, null, false),
                    r.body(AdminCancelByInstrumentRequest.class, true, true));
        }
        if (r.matches(HttpMethod.POST, TRIGGER_ORDER_CANCEL_BATCH)) {
            return triggerOrderRequests.cancelBatch(r.body(BatchCancelTriggerOrdersRequest.class, true, false));
        }
        if (r.matches(HttpMethod.GET, ADMIN_ORDER_CANCEL_PREVIEW)) {
            return adminOrderRequests.cancelPreview(r.header("X-Admin-User-Id", String.class, null, false),
                    r.header("X-Product-Line", String.class, null, false),
                    r.query("productLine", String.class, null, false),
                    r.query("userId", Long.class, null, false),
                    r.query("instrumentId", String.class, null, false),
                    r.query("limit", int.class, "100", false));
        }
        if (r.matches(HttpMethod.POST, TRIGGER_ORDER_CANCEL_OPEN)) {
            return triggerOrderRequests.cancelOpen(r.body(CancelOpenTriggerOrdersRequest.class, true, false));
        }
        if (r.matches(HttpMethod.GET, ORDER_GET_BY_CLIENT_ORDER_ID)) {
            return orderRequests.getByClientOrderId(r.query("userId", long.class, null, true),
                    r.query("clientOrderId", String.class, null, true),
                    r.query("minExportSequence", Long.class, null, false));
        }
        if (r.matches(HttpMethod.GET, ADMIN_MAINTENANCE_TASKS)) {
            return adminMaintenanceRequests.list(r.query("beforeId", long.class, "0", false));
        }
        if (r.matches(HttpMethod.POST, ADMIN_MAINTENANCE_TASKS)) {
            return adminMaintenanceRequests.create(r.header("X-Admin-User-Id", String.class, null, true),
                    r.body(MaintenanceRequest.class, true, false));
        }
        if (r.matches(HttpMethod.POST, ORDER_CANCEL_ALL_AFTER)) {
            return orderRequests.cancelAllAfter(r.body(CancelAllAfterRequest.class, true, false));
        }
        if (r.matches(HttpMethod.POST, ORDER_CANCEL_OPEN_ALGO)) {
            return orderRequests.cancelOpenAlgo(r.body(CancelOpenAlgoOrdersRequest.class, true, false));
        }
        if (r.matches(HttpMethod.POST, TRIGGER_ORDER_CANCEL)) {
            return triggerOrderRequests.cancel(r.body(CancelTriggerOrderRequest.class, true, false));
        }
        if (r.matches(HttpMethod.POST, ORDER_CLOSE_POSITION)) {
            return orderRequests.closePosition(r.body(ClosePositionRequest.class, true, true));
        }
        if (r.matches(HttpMethod.POST, TRIGGER_ORDER_PLACE_BATCH)) {
            return triggerOrderRequests.placeBatch(r.body(BatchPlaceTriggerOrderRequest.class, true, false));
        }
        if (r.matches(HttpMethod.GET, ADMIN_TRIGGER_ORDER_ORDERS)) {
            return adminTriggerOrderRequests.orders(r.header("X-Admin-User-Id", String.class, null, false),
                    r.header("X-Product-Line", String.class, null, false),
                    r.query("productLine", String.class, null, false),
                    r.query("userId", Long.class, null, false),
                    r.query("instrumentId", String.class, null, false),
                    r.query("status", String.class, null, false),
                    r.query("triggerOrderId", Long.class, null, false),
                    r.query("limit", int.class, "100", false),
                    r.query("cursor", String.class, null, false),
                    r.query("sort", String.class, null, false));
        }
        if (r.matches(HttpMethod.POST, FEE_SCHEDULES)) {
            return tradingFeeRequests.upsert(r.body(FeeScheduleUpsertRequest.class, true, false),
                    r.header("X-Product-Line", String.class, null, false),
                    r.query("productLine", String.class, null, false));
        }
        if (r.matches(HttpMethod.GET, FEE_SCHEDULES)) {
            return tradingFeeRequests.query(r.query("userId", long.class, "0", false),
                    r.header("X-Product-Line", String.class, null, false),
                    r.query("productLine", String.class, null, false),
                    r.query("instrumentId", String.class, null, false),
                    r.query("status", FeeScheduleStatus.class, null, false),
                    r.query("limit", int.class, "100", false),
                    r.query("cursor", String.class, null, false),
                    r.query("sort", String.class, null, false));
        }
        if (r.matches(HttpMethod.GET, TRIGGER_ORDER_OPEN_ORDERS)) {
            return triggerOrderRequests.openOrders(r.query("userId", long.class, null, true),
                    r.query("instrumentId", String.class, null, false),
                    r.query("limit", int.class, "100", false),
                    r.query("cursor", String.class, null, false));
        }
        if (r.matches(HttpMethod.GET, TRIGGER_OCO_SUBMISSION)) {
            return triggerOrderRequests.ocoSubmission(r.query("userId", long.class, null, true),
                    r.query("takeProfitClientId", String.class, null, true),
                    r.query("stopLossClientId", String.class, null, true));
        }
        if (r.matches(HttpMethod.GET, TRIGGER_SUBMISSION)) {
            return triggerOrderRequests.submission(r.query("userId", long.class, null, true),
                    r.query("clientTriggerOrderId", String.class, null, true));
        }
        if (r.matches(HttpMethod.GET, TRIGGER_CANCELLATION)) {
            return triggerOrderRequests.cancellation(r.query("userId", long.class, null, true),
                    r.path("triggerOrderId", long.class));
        }
        if (r.matches(HttpMethod.POST, ORDER_CANCEL_BATCH)) {
            return orderRequests.cancelBatch(r.body(BatchCancelOrdersRequest.class, true, true));
        }
        if (r.matches(HttpMethod.POST, ADMIN_ORDER_CANCEL_ORDERS)) {
            return adminOrderRequests.cancelOrders(r.header("X-Admin-User-Id", String.class, null, false),
                    r.header("X-Product-Line", String.class, null, false),
                    r.query("productLine", String.class, null, false),
                    r.body(AdminBatchCancelOrdersRequest.class, false, true));
        }
        if (r.matches(HttpMethod.POST, ORDER_AMEND_BATCH)) {
            return orderRequests.amendBatch(r.body(BatchAmendOrdersRequest.class, true, true));
        }
        if (r.matches(HttpMethod.POST, ORDER_CANCEL_OPEN)) {
            return orderRequests.cancelOpen(r.body(CancelOpenOrdersRequest.class, true, false));
        }
        if (r.matches(HttpMethod.POST, ORDER_CANCEL_ALGO)) {
            return orderRequests.cancelAlgo(r.body(CancelAlgoOrderRequest.class, true, false));
        }
        if (r.matches(HttpMethod.POST, ADMIN_LEVERAGE_SET)) {
            return leverageRequests.adminSet(r.header("X-Admin-User-Id", String.class, null, true),
                    r.body(LeverageSettingRequest.class, true, false),
                    r.header("X-Product-Line", String.class, null, false),
                    r.query("productLine", String.class, null, false));
        }
        if (r.matches(HttpMethod.POST, LEVERAGE_SETTINGS)) {
            return leverageRequests.set(r.body(LeverageSettingRequest.class, true, false),
                    r.header("X-Product-Line", String.class, null, false),
                    r.query("productLine", String.class, null, false));
        }
        if (r.matches(HttpMethod.GET, LEVERAGE_SETTINGS)
                || r.matches(HttpMethod.GET, ADMIN_LEVERAGE_SET)) {
            return leverageRequests.get(r.query("userId", long.class, null, true),
                    r.query("instrumentId", String.class, null, true),
                    r.query("marginMode", MarginMode.class, null, false),
                    r.header("X-Product-Line", String.class, null, false),
                    r.query("productLine", String.class, null, false));
        }
        if (r.matches(HttpMethod.GET, ORDER_OPEN_ALGO_ORDERS)) {
            return orderRequests.openAlgoOrders(r.query("userId", long.class, null, true),
                    r.query("instrumentId", String.class, null, false),
                    r.query("limit", int.class, "100", false));
        }
        if (r.matches(HttpMethod.POST, TRIGGER_ORDER_PLACE)) {
            return triggerOrderRequests.place(r.body(PlaceTriggerOrderRequest.class, true, false));
        }
        if (r.matches(HttpMethod.GET, ORDER_HISTORY_ORDERS)) {
            return orderRequests.historyOrders(r.query("userId", long.class, null, true),
                    r.query("instrumentId", String.class, null, false),
                    r.query("limit", int.class, "100", false),
                    r.query("orderId", Long.class, null, false),
                    r.query("startTime", Long.class, null, false),
                    r.query("endTime", Long.class, null, false),
                    r.query("cursor", String.class, null, false),
                    r.query("minExportSequence", Long.class, null, false),
                    r.query("status", String.class, null, false));
        }
        if (r.matches(HttpMethod.POST, ORDER_CANCEL)) {
            return orderRequests.cancel(r.body(CancelOrderRequest.class, true, false));
        }
        if (r.matches(HttpMethod.POST, ORDER_PLACE_BATCH)) {
            return orderRequests.placeBatch(r.body(BatchPlaceOrderRequest.class, true, true));
        }
        if (r.matches(HttpMethod.POST, ORDER_AMEND)) {
            return orderRequests.amend(r.body(AmendOrderRequest.class, true, true));
        }
        if (r.matches(HttpMethod.GET, ADMIN_ORDER_ORDERS)) {
            return adminOrderRequests.orders(r.header("X-Admin-User-Id", String.class, null, false),
                    r.header("X-Product-Line", String.class, null, false),
                    r.query("productLine", String.class, null, false),
                    r.query("userId", Long.class, null, false),
                    r.query("instrumentId", String.class, null, false),
                    r.query("status", String.class, null, false),
                    r.query("orderId", Long.class, null, false),
                    r.query("limit", int.class, "100", false),
                    r.query("cursor", String.class, null, false),
                    r.query("sort", String.class, null, false));
        }
        if (r.matches(HttpMethod.POST, ORDER_TEST)) {
            return orderRequests.test(r.body(PlaceOrderRequest.class, true, true));
        }
        if (r.matches(HttpMethod.POST, ORDER_PLACE_ALGO)) {
            return orderRequests.placeAlgo(r.body(PlaceAlgoOrderRequest.class, true, false));
        }
        if (r.matches(HttpMethod.GET, ORDER_OPEN_ORDERS)) {
            return orderRequests.openOrders(r.query("userId", long.class, null, true),
                    r.query("instrumentId", String.class, null, false),
                    r.query("limit", int.class, "100", false),
                    r.query("cursor", String.class, null, false),
                    r.query("minExportSequence", Long.class, null, false));
        }
        if (r.matches(HttpMethod.POST, ORDER_PLACE)) {
            return orderRequests.place(r.body(PlaceOrderRequest.class, true, true));
        }
        if (r.matches(HttpMethod.GET, ADMIN_TRIGGER_ORDER_TIMELINE)) {
            return adminTriggerOrderRequests.timeline(r.header("X-Admin-User-Id", String.class, null, false),
                    r.header("X-Product-Line", String.class, null, false),
                    r.query("productLine", String.class, null, false),
                    r.path("triggerOrderId", long.class));
        }
        if (r.matches(HttpMethod.POST, TRADING_FEE_DISABLE)) {
            return tradingFeeRequests.disable(r.path("feeScheduleId", long.class),
                    r.header("X-Product-Line", String.class, null, false),
                    r.query("productLine", String.class, null, false));
        }
        if (r.matches(HttpMethod.GET, ADMIN_MAINTENANCE_ACTIONS)) {
            return adminMaintenanceRequests.actions(r.path("id", long.class),
                    r.query("afterKey", String.class, "", false));
        }
        if (r.matches(HttpMethod.POST, ADMIN_MAINTENANCE_RELEASE)) {
            return adminMaintenanceRequests.release(r.path("id", long.class));
        }
        if (r.matches(HttpMethod.GET, ADMIN_TRIGGER_ORDER_ORDER)) {
            return adminTriggerOrderRequests.order(r.header("X-Admin-User-Id", String.class, null, false),
                    r.header("X-Product-Line", String.class, null, false),
                    r.query("productLine", String.class, null, false),
                    r.path("triggerOrderId", long.class));
        }
        if (r.matches(HttpMethod.GET, INSTRUMENT_CORE_SYNC_STATE)) {
            return instrumentCoreSyncService.state(r.path("instrumentId", String.class),
                    r.query("productLine", ProductLine.class, null, true));
        }
        if (r.matches(HttpMethod.POST, ADMIN_MAINTENANCE_RETRY)) {
            return adminMaintenanceRequests.retry(r.path("id", long.class));
        }
        if (r.matches(HttpMethod.GET, TRIGGER_ORDER_GET)) {
            return triggerOrderRequests.get(r.query("userId", long.class, null, true),
                    r.path("triggerOrderId", long.class));
        }
        if (r.matches(HttpMethod.GET, ADMIN_MAINTENANCE_GET)) {
            return adminMaintenanceRequests.get(r.path("id", long.class));
        }
        if (r.matches(HttpMethod.POST, ADMIN_ORDER_CANCEL_ORDER)) {
            return adminOrderRequests.cancelOrder(r.header("X-Admin-User-Id", String.class, null, false),
                    r.header("X-Product-Line", String.class, null, false),
                    r.query("productLine", String.class, null, false),
                    r.path("orderId", long.class),
                    r.body(AdminCancelOrderRequest.class, false, true));
        }
        if (r.matches(HttpMethod.GET, ORDER_COMMAND_RESULT)) {
            return orderRequests.commandResult(r.query("userId", long.class, null, true), r.path("commandId", UUID.class));
        }
        if (r.matches(HttpMethod.GET, ORDER_GET_ALGO)) {
            return orderRequests.getAlgo(r.query("userId", long.class, null, true), r.path("algoOrderId", long.class));
        }
        if (r.matches(HttpMethod.GET, ORDER_GET)) {
            return orderRequests.get(r.query("userId", long.class, null, true),
                    r.path("orderId", long.class),
                    r.query("minExportSequence", Long.class, null, false));
        }
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown local trading endpoint");
    }
}
