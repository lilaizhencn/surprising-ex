package com.surprising.trading.order.service;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.LeverageSettingRequest;
import com.surprising.trading.api.model.LeverageSettingResponse;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.order.service.LeverageService;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.stereotype.Service;

/**
 * 共享原始 HTTP 与网关入口的请求校验、业务编排和结果转换；不持有 HTTP 路由。
 */
@Service()
public class LeverageRequestService {

    private final LeverageService leverageService;

    public LeverageRequestService(LeverageService leverageService) {
        this.leverageService = leverageService;
    }

    public LeverageSettingResponse set(LeverageSettingRequest request, String productLineHeader, String productLineValue) {
        try {
            return leverageService.set(withProductLine(request, productLine(productLineValue, productLineHeader)));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        }
    }

    public LeverageSettingResponse get(long userId, String symbol, MarginMode marginMode, String productLineHeader, String productLineValue) {
        try {
            return leverageService.get(userId, symbol, marginMode, productLine(productLineValue, productLineHeader));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        }
    }

    private LeverageSettingRequest withProductLine(LeverageSettingRequest request, ProductLine productLine) {
        if (request == null || request.productLine() != null || productLine == null) {
            return request;
        }
        return new LeverageSettingRequest(request.userId(), productLine, request.symbol(), request.marginMode(), request.leveragePpm(), request.reason());
    }

    private ProductLine productLine(String queryValue, String headerValue) {
        String value = queryValue == null || queryValue.isBlank() ? headerValue : queryValue;
        if (value == null || value.isBlank()) {
            return null;
        }
        return ProductLine.requireExternalCode(value);
    }
}
