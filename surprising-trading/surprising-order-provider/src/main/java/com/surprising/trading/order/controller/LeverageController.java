package com.surprising.trading.order.controller;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.TradingApiPaths;
import com.surprising.trading.api.model.LeverageSettingRequest;
import com.surprising.trading.api.model.LeverageSettingResponse;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.order.service.LeverageService;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class LeverageController {

    private final LeverageService leverageService;

    public LeverageController(LeverageService leverageService) {
        this.leverageService = leverageService;
    }

    @PostMapping(TradingApiPaths.LEVERAGE_BASE_PATH + "/settings")
    public LeverageSettingResponse set(@RequestBody LeverageSettingRequest request,
                                       @RequestHeader(value = "X-Product-Line", required = false)
                                       String productLineHeader,
                                       @RequestParam(value = "productLine", required = false)
                                       String productLineValue) {
        try {
            return leverageService.set(withProductLine(request, productLine(productLineValue, productLineHeader)));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        }
    }

    @GetMapping(TradingApiPaths.LEVERAGE_BASE_PATH + "/settings")
    public LeverageSettingResponse get(@RequestParam("userId") long userId,
                                       @RequestParam("symbol") String symbol,
                                       @RequestParam(value = "marginMode", required = false) MarginMode marginMode,
                                       @RequestHeader(value = "X-Product-Line", required = false)
                                       String productLineHeader,
                                       @RequestParam(value = "productLine", required = false)
                                       String productLineValue) {
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
        return new LeverageSettingRequest(
                request.userId(),
                productLine,
                request.symbol(),
                request.marginMode(),
                request.leveragePpm(),
                request.reason());
    }

    private ProductLine productLine(String queryValue, String headerValue) {
        String value = queryValue == null || queryValue.isBlank() ? headerValue : queryValue;
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        ProductLine byAccountType = ProductLine.fromAccountTypeCode(normalized).orElse(null);
        if (byAccountType != null) {
            return byAccountType;
        }
        ProductLine byContractType = ProductLine.fromContractTypeCode(normalized).orElse(null);
        if (byContractType != null) {
            return byContractType;
        }
        String enumName = normalized.replace('-', '_');
        for (ProductLine productLine : ProductLine.values()) {
            if (productLine.name().equals(enumName) || productLine.topicSegment().equalsIgnoreCase(value.trim())) {
                return productLine;
            }
        }
        throw new IllegalArgumentException("unsupported productLine: " + value);
    }
}
