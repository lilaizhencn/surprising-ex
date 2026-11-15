package com.surprising.trading.order.controller;

import com.surprising.gateway.provider.product.GatewayUserContext;
import com.surprising.trading.api.model.LeverageSettingRequest;
import com.surprising.trading.api.model.LeverageSettingResponse;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.order.service.LeverageService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * User-facing trading leverage settings endpoints.
 */
@RestController
public class TradingLeverageController {

    private final LeverageService leverageService;

    public TradingLeverageController(LeverageService leverageService) {
        this.leverageService = leverageService;
    }

    @PostMapping("/api/v1/trading/leverage/settings")
    public LeverageSettingResponse setLeverage(
            @RequestBody LeverageSettingRequest request,
            @RequestHeader(value = "X-Product-Line", required = false) String headerProductLine,
            @RequestParam(value = "productLine", required = false) String queryProductLine) {
        return TradingControllerSupport.setLeverage(leverageService, request, headerProductLine, queryProductLine);
    }

    @GetMapping("/api/v1/trading/leverage/settings")
    public LeverageSettingResponse getLeverage(
            @RequestParam(value = "userId", required = false) Long queryUserId,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestParam("instrumentId") String instrumentId,
            @RequestParam(value = "marginMode", required = false) MarginMode marginMode,
            @RequestHeader(value = "X-Product-Line", required = false) String headerProductLine,
            @RequestParam(value = "productLine", required = false) String queryProductLine) {
        long userId = GatewayUserContext.requireUserId(queryUserId, headerUserId, adminUserId);
        return TradingControllerSupport.getLeverage(leverageService, userId, instrumentId,
                marginMode, headerProductLine, queryProductLine);
    }
}
