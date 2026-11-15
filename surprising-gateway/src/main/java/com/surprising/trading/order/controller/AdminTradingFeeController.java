package com.surprising.trading.order.controller;

import com.surprising.gateway.provider.product.GatewayUserContext;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.EffectiveTradingFeeResponse;
import com.surprising.trading.api.model.FeeScheduleQueryResponse;
import com.surprising.trading.api.model.FeeScheduleResponse;
import com.surprising.trading.api.model.FeeScheduleStatus;
import com.surprising.trading.api.model.FeeScheduleUpsertRequest;
import com.surprising.trading.api.utils.AdminCursorPage;
import com.surprising.trading.order.model.FeeSchedulePageQuery;
import com.surprising.trading.order.service.TradingFeeService;
import java.time.DateTimeException;
import java.util.Arrays;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Administrative trading fee query and fee-schedule management endpoints.
 */
@RestController
public class AdminTradingFeeController {

    private final TradingFeeService tradingFeeService;

    public AdminTradingFeeController(TradingFeeService tradingFeeService) {
        this.tradingFeeService = tradingFeeService;
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
        return TradingControllerSupport.effectiveFee(tradingFeeService, userId, instrumentId,
                instrumentChangeId, headerProductLine, queryProductLine);
    }

    @PostMapping("/api/v1/admin/trading/fees/schedules")
    public FeeScheduleResponse upsertFeeSchedule(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestBody FeeScheduleUpsertRequest request,
            @RequestHeader(value = "X-Product-Line", required = false) String headerProductLine,
            @RequestParam(value = "productLine", required = false) String queryProductLine) {
        GatewayUserContext.requireAdminUserId(adminUserId);
        try {
            ProductLine productLine = TradingControllerSupport.productLine(queryProductLine, headerProductLine);
            FeeScheduleUpsertRequest bound = request == null || request.productLine() != null || productLine == null
                    ? request
                    : new FeeScheduleUpsertRequest(request.feeScheduleId(), productLine, request.userId(),
                            request.instrumentId(), request.makerFeeRatePpm(), request.takerFeeRatePpm(),
                            request.sourceType(), request.tierCode(), request.reason(), request.status(),
                            request.effectiveTime(), request.expireTime());
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
            return tradingFeeService.querySchedules(
                    TradingControllerSupport.productLine(queryProductLine, headerProductLine),
                    userId, TradingControllerSupport.normalizeOptionalSymbol(instrumentId), status,
                    feePage(limit, cursor, sort));
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
            return tradingFeeService.disableSchedule(feeScheduleId,
                    TradingControllerSupport.productLine(queryProductLine, headerProductLine));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        }
    }

    private static FeeScheduleUpsertRequest bindSchedule(FeeScheduleUpsertRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("fee schedule request is required");
        }
        if (request.feeScheduleId() == null || request.feeScheduleId() <= 0) {
            throw new IllegalArgumentException("feeScheduleId must be positive");
        }
        if (request.userId() <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
        if (request.productLine() == null) {
            throw new IllegalArgumentException("productLine is required");
        }
        String symbol = TradingControllerSupport.normalizeOptionalSymbol(request.instrumentId());
        validateFeeRate(request.makerFeeRatePpm(), "makerFeeRatePpm");
        validateFeeRate(request.takerFeeRatePpm(), "takerFeeRatePpm");
        if (request.reason() == null || request.reason().isBlank()) {
            throw new IllegalArgumentException("reason is required");
        }
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
}
