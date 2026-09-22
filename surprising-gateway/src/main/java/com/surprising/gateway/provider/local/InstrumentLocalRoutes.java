package com.surprising.gateway.provider.local;

import com.surprising.instrument.api.model.InstrumentStatus;
import com.surprising.instrument.api.model.InstrumentType;
import com.surprising.instrument.api.model.InstrumentUpsertRequest;
import com.surprising.instrument.provider.service.InstrumentRequestService;
import com.surprising.product.api.ProductLine;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/** 网关协议到合约入口的本地调用；不建立内部 HTTP 连接。 */
@Component
public final class InstrumentLocalRoutes {
    private static final PathPattern INSTRUMENT_CONTROLLER_LATEST = PathPatternParser.defaultInstance.parse("/api/v1/instruments/latest");
    private static final PathPattern INSTRUMENT_CONTROLLER_ASSETSCALES = PathPatternParser.defaultInstance.parse("/api/v1/instruments/asset-scales");
    private static final PathPattern INSTRUMENT_CONTROLLER_LIST = PathPatternParser.defaultInstance.parse("/api/v1/instruments/list");
    private static final PathPattern INSTRUMENT_CONTROLLER_ADMINLATEST = PathPatternParser.defaultInstance.parse("/api/v1/instruments/admin/{symbol}");
    private static final PathPattern INSTRUMENT_CONTROLLER_ADMINLIST = PathPatternParser.defaultInstance.parse("/api/v1/instruments/admin/list");
    private static final PathPattern INSTRUMENT_CONTROLLER_CHANGES = PathPatternParser.defaultInstance.parse("/api/v1/instruments/admin/{symbol}/changes");
    private static final PathPattern INSTRUMENT_CONTROLLER_UPSERT = PathPatternParser.defaultInstance.parse("/api/v1/instruments/admin/upsert");
    private static final PathPattern INSTRUMENT_CONTROLLER_UPDATESTATUS = PathPatternParser.defaultInstance.parse("/api/v1/instruments/admin/{symbol}/status");
    private static final PathPattern INSTRUMENT_CONTROLLER_CLOSEFORSETTLEMENT = PathPatternParser.defaultInstance.parse("/api/v1/instruments/admin/{symbol}/settlement");

    private final InstrumentRequestService instrumentRequests;

    public InstrumentLocalRoutes(InstrumentRequestService instrumentRequests) {
        this.instrumentRequests = instrumentRequests;
    }

    public Object invoke(LocalApiRequest r) {
        if (r.matches(HttpMethod.GET, INSTRUMENT_CONTROLLER_ASSETSCALES)) {
            return instrumentRequests.assetScales();
        }
        if (r.matches(HttpMethod.POST, INSTRUMENT_CONTROLLER_UPSERT)) {
            return instrumentRequests.upsert(r.body(InstrumentUpsertRequest.class, true, false),
                    r.header("X-Admin-User-Id", String.class, null, true),
                    r.query("reason", String.class, "Admin configuration update", false));
        }
        if (r.matches(HttpMethod.GET, INSTRUMENT_CONTROLLER_ADMINLIST)) {
            return instrumentRequests.adminList(r.query("type", InstrumentType.class, null, false),
                    r.query("status", InstrumentStatus.class, null, false),
                    r.query("limit", int.class, "100", false),
                    r.query("cursor", String.class, null, false),
                    r.query("sort", String.class, null, false),
                    r.header("X-Product-Line", String.class, null, false),
                    r.query("productLine", String.class, null, false));
        }
        if (r.matches(HttpMethod.GET, INSTRUMENT_CONTROLLER_LATEST)) {
            return instrumentRequests.latest(r.query("symbol", String.class, null, true),
                    r.header("X-Product-Line", String.class, null, false),
                    r.query("productLine", String.class, null, false));
        }
        if (r.matches(HttpMethod.GET, INSTRUMENT_CONTROLLER_LIST)) {
            return instrumentRequests.list(r.query("type", InstrumentType.class, null, false),
                    r.query("status", InstrumentStatus.class, null, false),
                    r.header("X-Product-Line", String.class, null, false),
                    r.query("productLine", String.class, null, false));
        }
        if (r.matches(HttpMethod.POST, INSTRUMENT_CONTROLLER_CLOSEFORSETTLEMENT)) {
            return instrumentRequests.closeForSettlement(r.path("symbol", String.class),
                    r.header("X-Product-Line", String.class, null, false),
                    r.query("productLine", String.class, null, false),
                    r.query("settlementPriceTicks", long.class, null, true),
                    r.query("underlyingSettlementPriceUnits", long.class, "0", false),
                    r.header("X-Admin-User-Id", String.class, null, true),
                    r.query("reason", String.class, "Admin settlement confirmation", false));
        }
        if (r.matches(HttpMethod.GET, INSTRUMENT_CONTROLLER_CHANGES)) {
            return instrumentRequests.changes(r.path("symbol", String.class),
                    r.query("productLine", ProductLine.class, null, true),
                    r.query("beforeId", long.class, "0", false),
                    r.query("limit", int.class, "50", false));
        }
        if (r.matches(HttpMethod.POST, INSTRUMENT_CONTROLLER_UPDATESTATUS)) {
            return instrumentRequests.updateStatus(r.path("symbol", String.class),
                    r.query("status", InstrumentStatus.class, null, true),
                    r.header("X-Product-Line", String.class, null, false),
                    r.query("productLine", String.class, null, false),
                    r.header("X-Admin-User-Id", String.class, null, true),
                    r.query("reason", String.class, "Admin trading status update", false));
        }
        if (r.matches(HttpMethod.GET, INSTRUMENT_CONTROLLER_ADMINLATEST)) {
            return instrumentRequests.adminLatest(r.path("symbol", String.class),
                    r.header("X-Product-Line", String.class, null, false),
                    r.query("productLine", String.class, null, false));
        }
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown local instrument endpoint");
    }
}
