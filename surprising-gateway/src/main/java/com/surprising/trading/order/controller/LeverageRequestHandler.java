package com.surprising.trading.order.controller;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.LeverageSettingRequest;
import com.surprising.trading.api.model.LeverageSettingResponse;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.order.service.LeverageService;
import com.surprising.trading.order.exception.OrderCommandRejectedException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.stereotype.Component;

/**
 * HTTP/本地路由共用的协议适配：绑定格式和错误响应；业务由实际Service处理，不声明HTTP路由。
 */
@Component
public class LeverageRequestHandler {

    private final LeverageService leverageService;

    public LeverageRequestHandler(LeverageService leverageService) {
        this.leverageService = leverageService;
    }

    public LeverageSettingResponse set(LeverageSettingRequest request, String productLineHeader, String productLineValue) {
        try {
            return leverageService.set(withProductLine(request, productLine(productLineValue, productLineHeader)));
        } catch (OrderCommandRejectedException rejected) {
            throw conflict(rejected);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        }
    }

    public LeverageSettingResponse adminSet(String adminUserId, LeverageSettingRequest request,
                                            String productLineHeader, String productLineValue) {
        if (adminUserId == null || adminUserId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "admin identity is required");
        }
        return set(request, productLineHeader, productLineValue);
    }

    public LeverageSettingResponse get(long userId, String instrumentId, MarginMode marginMode, String productLineHeader, String productLineValue) {
        try {
            return leverageService.get(userId, instrumentId, marginMode, productLine(productLineValue, productLineHeader));
        } catch (OrderCommandRejectedException rejected) {
            throw conflict(rejected);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        }
    }

    private ResponseStatusException conflict(OrderCommandRejectedException rejected) {
        var response = new ResponseStatusException(HttpStatus.CONFLICT,rejected.getMessage(),rejected);
        response.getBody().setProperty("code",rejected.code().name());
        return response;
    }

    private LeverageSettingRequest withProductLine(LeverageSettingRequest request, ProductLine productLine) {
        if (request == null || request.productLine() != null || productLine == null) {
            return request;
        }
        return new LeverageSettingRequest(request.userId(), productLine, request.instrumentId(), request.marginMode(), request.leveragePpm(), request.reason(), request.repriceCrossMargin());
    }

    private ProductLine productLine(String queryValue, String headerValue) {
        String value = queryValue == null || queryValue.isBlank() ? headerValue : queryValue;
        if (value == null || value.isBlank()) {
            return null;
        }
        return ProductLine.requireExternalCode(value);
    }
}
