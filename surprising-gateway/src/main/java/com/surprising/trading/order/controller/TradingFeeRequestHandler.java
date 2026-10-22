package com.surprising.trading.order.controller;

import com.surprising.trading.api.model.EffectiveTradingFeeResponse;
import com.surprising.trading.api.model.FeeScheduleQueryResponse;
import com.surprising.trading.api.model.FeeScheduleResponse;
import com.surprising.trading.api.model.FeeScheduleStatus;
import com.surprising.trading.api.model.FeeScheduleUpsertRequest;
import com.surprising.trading.api.utils.AdminCursorPage;
import com.surprising.trading.order.model.FeeSchedulePageQuery;
import com.surprising.trading.order.model.FeeSchedulePageQuery.Order;
import java.util.Arrays;
import java.time.DateTimeException;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.order.service.TradingFeeService;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.stereotype.Component;

/**
 * HTTP/本地路由共用的协议适配：绑定格式和错误响应；业务由实际Service处理，不声明HTTP路由。
 */
@Component
public class TradingFeeRequestHandler {

    private final TradingFeeService tradingFeeService;

    public TradingFeeRequestHandler(TradingFeeService tradingFeeService) {
        this.tradingFeeService = tradingFeeService;
    }

    public EffectiveTradingFeeResponse effective(long userId, String instrumentId, long instrumentChangeId, String productLineHeader, String productLineValue) {
        try {
            return tradingFeeService.effectiveFee(userId, requiredSymbol(instrumentId), instrumentChangeId, productLine(productLineValue, productLineHeader));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        }
    }

    public FeeScheduleResponse upsert(FeeScheduleUpsertRequest request, String productLineHeader, String productLineValue) {
        try {
            ProductLine productLine = productLine(productLineValue, productLineHeader);
            return tradingFeeService.upsertSchedule(bindSchedule(withProductLine(request, productLine)));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    public FeeScheduleResponse disable(long feeScheduleId, String productLineHeader, String productLineValue) {
        try {
            return tradingFeeService.disableSchedule(feeScheduleId, productLine(productLineValue, productLineHeader));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        }
    }

    public FeeScheduleQueryResponse query(long userId, String productLineHeader, String productLineValue, String instrumentId, FeeScheduleStatus status, int limit, String cursor, String sort) {
        try {
            return tradingFeeService.querySchedules(productLine(productLineValue, productLineHeader), userId, normalizeOptionalSymbol(instrumentId), status, page(limit, cursor, sort));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    private FeeScheduleUpsertRequest bindSchedule(FeeScheduleUpsertRequest request) {
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
        String symbol = normalizeOptionalSymbol(request.instrumentId());
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

    private FeeSchedulePageQuery page(int limit, String cursor, String sort) {
        String token = sort == null || sort.isBlank() ? Order.UPDATED_DESC.token() : sort.trim();
        Order order = Arrays.stream(Order.values()).filter(value -> value.token().equals(token)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unsupported sort: " + sort));
        try {
            return new FeeSchedulePageQuery(AdminCursorPage.limit(limit <= 0 ? 100 : limit, 500),
                    AdminCursorPage.decodeCursor(cursor), order);
        } catch (DateTimeException invalid) {
            throw new IllegalArgumentException("invalid cursor", invalid);
        }
    }

    private String requiredSymbol(String instrumentId) {
        String normalized = normalizeOptionalSymbol(instrumentId);
        if (normalized == null) {
            throw new IllegalArgumentException("instrumentId is required");
        }
        return normalized;
    }

    private String normalizeOptionalSymbol(String instrumentId) {
        if (instrumentId == null || instrumentId.isBlank()) {
            return null;
        }
        String normalized = instrumentId.trim().toUpperCase();
        if (!com.surprising.product.api.utils.InstrumentIds.valid(normalized)) {
            throw new IllegalArgumentException("invalid instrumentId: " + instrumentId);
        }
        return normalized;
    }

    private static void validateFeeRate(long feeRatePpm, String field) {
        if (feeRatePpm < -1_000_000L || feeRatePpm > 1_000_000L) {
            throw new IllegalArgumentException(field + " must be within +/- 100%");
        }
    }

    private FeeScheduleUpsertRequest withProductLine(FeeScheduleUpsertRequest request, ProductLine productLine) {
        if (request == null || request.productLine() != null || productLine == null) {
            return request;
        }
        return new FeeScheduleUpsertRequest(request.feeScheduleId(), productLine, request.userId(), request.instrumentId(), request.makerFeeRatePpm(), request.takerFeeRatePpm(), request.sourceType(), request.tierCode(), request.reason(), request.status(), request.effectiveTime(), request.expireTime());
    }

    private ProductLine productLine(String queryValue, String headerValue) {
        String value = queryValue == null || queryValue.isBlank() ? headerValue : queryValue;
        if (value == null || value.isBlank()) {
            return null;
        }
        return ProductLine.requireExternalCode(value);
    }
}
