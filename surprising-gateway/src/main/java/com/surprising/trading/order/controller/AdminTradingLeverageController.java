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
 * Administrative trading leverage settings endpoints.
 */
@RestController
public class AdminTradingLeverageController {

    private final LeverageService leverageService;

    public AdminTradingLeverageController(LeverageService leverageService) {
        this.leverageService = leverageService;
    }

    @PostMapping("/api/v1/admin/trading/leverage/settings")
    public LeverageSettingResponse adminSetLeverage(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestBody LeverageSettingRequest request,
            @RequestHeader(value = "X-Product-Line", required = false) String headerProductLine,
            @RequestParam(value = "productLine", required = false) String queryProductLine) {
        GatewayUserContext.requireAdminUserId(adminUserId);
        return TradingControllerSupport.setLeverage(leverageService, request, headerProductLine, queryProductLine);
    }

    @GetMapping("/api/v1/admin/trading/leverage/settings")
    public LeverageSettingResponse adminGetLeverage(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestParam("userId") long userId,
            @RequestParam("instrumentId") String instrumentId,
            @RequestParam(value = "marginMode", required = false) MarginMode marginMode,
            @RequestHeader(value = "X-Product-Line", required = false) String headerProductLine,
            @RequestParam(value = "productLine", required = false) String queryProductLine) {
        GatewayUserContext.requireAdminUserId(adminUserId);
        return TradingControllerSupport.getLeverage(leverageService, userId, instrumentId,
                marginMode, headerProductLine, queryProductLine);
    }
}
