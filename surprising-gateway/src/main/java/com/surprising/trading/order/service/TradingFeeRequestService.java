package com.surprising.trading.order.service;

import com.surprising.trading.api.model.EffectiveTradingFeeResponse;
import com.surprising.trading.api.model.FeeScheduleQueryResponse;
import com.surprising.trading.api.model.FeeScheduleResponse;
import com.surprising.trading.api.model.FeeScheduleStatus;
import com.surprising.trading.api.model.FeeScheduleUpsertRequest;
import com.surprising.trading.api.model.FeeScheduleSnapshotResponse;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.order.service.TradingFeeService;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.stereotype.Service;

/**
 * 共享原始 HTTP 与网关入口的请求校验、业务编排和结果转换；不持有 HTTP 路由。
 */
@Service()
public class TradingFeeRequestService {

    private final TradingFeeService tradingFeeService;

    public TradingFeeRequestService(TradingFeeService tradingFeeService) {
        this.tradingFeeService = tradingFeeService;
    }

    public EffectiveTradingFeeResponse effective(long userId, String symbol, long instrumentChangeId, String productLineHeader, String productLineValue) {
        try {
            return tradingFeeService.effectiveFee(userId, symbol, instrumentChangeId, productLine(productLineValue, productLineHeader));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        }
    }

    public FeeScheduleResponse upsert(FeeScheduleUpsertRequest request, String productLineHeader, String productLineValue) {
        try {
            ProductLine productLine = productLine(productLineValue, productLineHeader);
            return tradingFeeService.upsertSchedule(withProductLine(request, productLine));
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

    public FeeScheduleQueryResponse query(long userId, String productLineHeader, String productLineValue, String symbol, FeeScheduleStatus status, int limit, String cursor, String sort) {
        try {
            return tradingFeeService.querySchedules(productLine(productLineValue, productLineHeader), userId, symbol, status, limit, cursor, sort);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    private FeeScheduleUpsertRequest withProductLine(FeeScheduleUpsertRequest request, ProductLine productLine) {
        if (request == null || request.productLine() != null || productLine == null) {
            return request;
        }
        return new FeeScheduleUpsertRequest(request.feeScheduleId(), productLine, request.userId(), request.symbol(), request.makerFeeRatePpm(), request.takerFeeRatePpm(), request.sourceType(), request.tierCode(), request.reason(), request.status(), request.effectiveTime(), request.expireTime());
    }

    private ProductLine productLine(String queryValue, String headerValue) {
        String value = queryValue == null || queryValue.isBlank() ? headerValue : queryValue;
        if (value == null || value.isBlank()) {
            return null;
        }
        return ProductLine.requireExternalCode(value);
    }
}
