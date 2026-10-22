package com.surprising.gateway.provider.local;

import com.surprising.instrument.api.model.InstrumentStatus;
import com.surprising.instrument.api.model.InstrumentType;
import com.surprising.instrument.api.model.InstrumentUpsertRequest;
import com.surprising.instrument.provider.controller.InstrumentRequestHandler;
import com.surprising.product.api.ProductLine;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/** 网关协议到合约入口的本地调用；不建立内部 HTTP 连接。 */
public final class InstrumentLocalRoutes {
    private static final PathPattern INSTRUMENT_DEFAULT = PathPatternParser.defaultInstance.parse("/api/v1/instruments/default");
    private static final PathPattern INSTRUMENT_LATEST = PathPatternParser.defaultInstance.parse("/api/v1/instruments/latest");
    private static final PathPattern INSTRUMENT_ASSET_SCALES = PathPatternParser.defaultInstance.parse("/api/v1/instruments/asset-scales");
    private static final PathPattern INSTRUMENT_LIST = PathPatternParser.defaultInstance.parse("/api/v1/instruments/list");
    private static final PathPattern INSTRUMENT_ADMIN_LATEST = PathPatternParser.defaultInstance.parse("/api/v1/instruments/admin/{instrumentId}");
    private static final PathPattern INSTRUMENT_ADMIN_LIST = PathPatternParser.defaultInstance.parse("/api/v1/instruments/admin/list");
    private static final PathPattern INSTRUMENT_CHANGES = PathPatternParser.defaultInstance.parse("/api/v1/instruments/admin/{instrumentId}/changes");
    private static final PathPattern INSTRUMENT_UPSERT = PathPatternParser.defaultInstance.parse("/api/v1/instruments/admin/upsert");
    private static final PathPattern INSTRUMENT_UPDATE_STATUS = PathPatternParser.defaultInstance.parse("/api/v1/instruments/admin/{instrumentId}/status");
    private static final PathPattern INSTRUMENT_CLOSE_FOR_SETTLEMENT = PathPatternParser.defaultInstance.parse("/api/v1/instruments/admin/{instrumentId}/settlement");

    private final InstrumentRequestHandler instrumentRequests;
    private final com.surprising.trading.order.controller.OrderBusinessSettingsRequestHandler orderSettings;
    private static final PathPattern ORDER_SETTINGS = PathPatternParser.defaultInstance.parse("/api/v1/instruments/admin/order-settings");

    public InstrumentLocalRoutes(InstrumentRequestHandler instrumentRequests, com.surprising.trading.order.controller.OrderBusinessSettingsRequestHandler orderSettings) {
        this.orderSettings=orderSettings;
        this.instrumentRequests = instrumentRequests;
    }

    public Object invoke(LocalApiRequest r) {
        if(r.matches(HttpMethod.GET, ORDER_SETTINGS)) return orderSettings.current(r.query("productLine",ProductLine.class,null,true));
        if(r.matches(HttpMethod.POST, ORDER_SETTINGS)) return orderSettings.save(r.query("productLine",ProductLine.class,null,true),
                r.body(tools.jackson.databind.JsonNode.class,true,false),r.header("X-Admin-User-Id",String.class,null,true));
        if (r.matches(HttpMethod.GET, INSTRUMENT_DEFAULT)) {
            return instrumentRequests.defaultInstrument(r.header("X-Product-Line", String.class, null, false),
                    r.query("productLine", String.class, null, false));
        }
        if (r.matches(HttpMethod.GET, INSTRUMENT_ASSET_SCALES)) {
            return instrumentRequests.assetScales();
        }
        if (r.matches(HttpMethod.POST, INSTRUMENT_UPSERT)) {
            return instrumentRequests.edit(r.body(InstrumentUpsertRequest.class, true, false),
                    r.header("X-Admin-User-Id", String.class, null, true),
                    r.query("reason", String.class, null, true),
                    r.query("expectedChangeId", long.class, null, true));
        }
        if (r.matches(HttpMethod.GET, INSTRUMENT_ADMIN_LIST)) {
            return instrumentRequests.adminList(r.query("type", InstrumentType.class, null, false),
                    r.query("status", InstrumentStatus.class, null, false),
                    r.query("limit", int.class, "100", false),
                    r.query("cursor", String.class, null, false),
                    r.query("sort", String.class, null, false),
                    r.header("X-Product-Line", String.class, null, false),
                    r.query("productLine", String.class, null, false));
        }
        if (r.matches(HttpMethod.GET, INSTRUMENT_LATEST)) {
            return instrumentRequests.latest(r.query("instrumentId", int.class, null, true),
                    r.header("X-Product-Line", String.class, null, false),
                    r.query("productLine", String.class, null, false));
        }
        if (r.matches(HttpMethod.GET, INSTRUMENT_LIST)) {
            return instrumentRequests.list(r.query("type", InstrumentType.class, null, false),
                    r.query("status", InstrumentStatus.class, null, false),
                    r.header("X-Product-Line", String.class, null, false),
                    r.query("productLine", String.class, null, false),
                    r.query("includeMarketSummary", boolean.class, "false", false),
                    r.query("includeTrend", boolean.class, "false", false));
        }
        if (r.matches(HttpMethod.POST, INSTRUMENT_CLOSE_FOR_SETTLEMENT)) {
            return instrumentRequests.closeForSettlement(r.path("instrumentId", int.class),
                    r.header("X-Product-Line", String.class, null, false),
                    r.query("productLine", String.class, null, false),
                    r.query("settlementPriceTicks", long.class, null, true),
                    r.query("underlyingSettlementPriceUnits", long.class, "0", false),
                    r.header("X-Admin-User-Id", String.class, null, true),
                    r.query("reason", String.class, "Admin settlement confirmation", false));
        }
        if (r.matches(HttpMethod.GET, INSTRUMENT_CHANGES)) {
            return instrumentRequests.changes(r.path("instrumentId", int.class),
                    r.query("productLine", ProductLine.class, null, true),
                    r.query("beforeId", long.class, "0", false),
                    r.query("limit", int.class, "50", false));
        }
        if (r.matches(HttpMethod.POST, INSTRUMENT_UPDATE_STATUS)) {
            return instrumentRequests.editStatus(r.path("instrumentId", int.class),
                    r.query("status", InstrumentStatus.class, null, true),
                    r.header("X-Product-Line", String.class, null, false),
                    r.query("productLine", String.class, null, false),
                    r.header("X-Admin-User-Id", String.class, null, true),
                    r.query("reason", String.class, null, true),
                    r.query("expectedChangeId", long.class, null, true));
        }
        if (r.matches(HttpMethod.GET, INSTRUMENT_ADMIN_LATEST)) {
            return instrumentRequests.adminLatest(r.path("instrumentId", int.class),
                    r.header("X-Product-Line", String.class, null, false),
                    r.query("productLine", String.class, null, false));
        }
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown local instrument endpoint");
    }
}
