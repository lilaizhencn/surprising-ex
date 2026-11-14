package com.surprising.instrument.provider.controller;

import com.surprising.gateway.provider.product.GatewayUserContext;
import com.surprising.instrument.api.model.InstrumentStatus;
import com.surprising.instrument.api.model.InstrumentType;
import com.surprising.instrument.api.model.InstrumentUpsertRequest;
import com.surprising.product.api.ProductLine;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/instruments")
public class InstrumentMvcController {
    private final InstrumentRequestHandler instrumentRequests;

    public InstrumentMvcController(InstrumentRequestHandler instrumentRequests) {
        this.instrumentRequests = instrumentRequests;
    }

    @GetMapping("/default")
    public Object defaultInstrument(
            @RequestHeader(value = "X-Product-Line", required = false) String headerProductLine,
            @RequestParam(value = "productLine", required = false) String queryProductLine) {
        return instrumentRequests.defaultInstrument(headerProductLine, queryProductLine);
    }

    @GetMapping("/asset-scales")
    public Object assetScales() {
        return instrumentRequests.assetScales();
    }

    @GetMapping("/latest")
    public Object latest(
            @RequestParam("instrumentId") int instrumentId,
            @RequestHeader(value = "X-Product-Line", required = false) String headerProductLine,
            @RequestParam(value = "productLine", required = false) String queryProductLine) {
        return instrumentRequests.latest(instrumentId, headerProductLine, queryProductLine);
    }

    @GetMapping("/list")
    public Object list(
            @RequestParam(value = "type", required = false) InstrumentType type,
            @RequestParam(value = "status", required = false) InstrumentStatus status,
            @RequestHeader(value = "X-Product-Line", required = false) String headerProductLine,
            @RequestParam(value = "productLine", required = false) String queryProductLine,
            @RequestParam(value = "includeMarketSummary", defaultValue = "false") boolean includeMarketSummary,
            @RequestParam(value = "includeTrend", defaultValue = "false") boolean includeTrend) {
        return instrumentRequests.list(type, status, headerProductLine, queryProductLine,
                includeMarketSummary, includeTrend);
    }

    @PostMapping("/admin/upsert")
    public Object upsert(
            @RequestBody InstrumentUpsertRequest request,
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestParam("reason") String reason,
            @RequestParam("expectedChangeId") long expectedChangeId) {
        return instrumentRequests.edit(request, GatewayUserContext.requireAdminUserId(adminUserId),
                reason, expectedChangeId);
    }

    @GetMapping("/admin/list")
    public Object adminList(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestParam(value = "type", required = false) InstrumentType type,
            @RequestParam(value = "status", required = false) InstrumentStatus status,
            @RequestParam(value = "limit", defaultValue = "100") int limit,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "sort", required = false) String sort,
            @RequestHeader(value = "X-Product-Line", required = false) String headerProductLine,
            @RequestParam(value = "productLine", required = false) String queryProductLine) {
        GatewayUserContext.requireAdminUserId(adminUserId);
        return instrumentRequests.adminList(type, status, limit, cursor, sort, headerProductLine, queryProductLine);
    }

    @PostMapping("/admin/{instrumentId}/settlement")
    public Object closeForSettlement(
            @PathVariable("instrumentId") int instrumentId,
            @RequestHeader(value = "X-Product-Line", required = false) String headerProductLine,
            @RequestParam(value = "productLine", required = false) String queryProductLine,
            @RequestParam("settlementPriceTicks") long settlementPriceTicks,
            @RequestParam(value = "underlyingSettlementPriceUnits", defaultValue = "0") long underlyingSettlementPriceUnits,
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestParam(value = "reason", defaultValue = "Admin settlement confirmation") String reason) {
        return instrumentRequests.closeForSettlement(instrumentId, headerProductLine, queryProductLine,
                settlementPriceTicks, underlyingSettlementPriceUnits,
                GatewayUserContext.requireAdminUserId(adminUserId), reason);
    }

    @GetMapping("/admin/{instrumentId}/changes")
    public Object changes(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @PathVariable("instrumentId") int instrumentId,
            @RequestParam("productLine") ProductLine productLine,
            @RequestParam(value = "beforeId", defaultValue = "0") long beforeId,
            @RequestParam(value = "limit", defaultValue = "50") int limit) {
        GatewayUserContext.requireAdminUserId(adminUserId);
        return instrumentRequests.changes(instrumentId, productLine, beforeId, limit);
    }

    @PostMapping("/admin/{instrumentId}/status")
    public Object updateStatus(
            @PathVariable("instrumentId") int instrumentId,
            @RequestParam("status") InstrumentStatus status,
            @RequestHeader(value = "X-Product-Line", required = false) String headerProductLine,
            @RequestParam(value = "productLine", required = false) String queryProductLine,
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestParam("reason") String reason,
            @RequestParam("expectedChangeId") long expectedChangeId) {
        return instrumentRequests.editStatus(instrumentId, status, headerProductLine, queryProductLine,
                GatewayUserContext.requireAdminUserId(adminUserId), reason, expectedChangeId);
    }

    @GetMapping("/admin/{instrumentId}")
    public Object adminLatest(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @PathVariable("instrumentId") int instrumentId,
            @RequestHeader(value = "X-Product-Line", required = false) String headerProductLine,
            @RequestParam(value = "productLine", required = false) String queryProductLine) {
        GatewayUserContext.requireAdminUserId(adminUserId);
        return instrumentRequests.adminLatest(instrumentId, headerProductLine, queryProductLine);
    }
}
