package com.surprising.trading.order.controller;

import com.surprising.gateway.provider.product.GatewayUserIdentity;
import com.surprising.trading.api.model.EffectiveTradingFeeResponse;
import com.surprising.trading.order.service.TradingFeeService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * User-facing effective trading fee query endpoints.
 */
@RestController
public class TradingFeeRateController {

    private final TradingFeeService tradingFeeService;

    public TradingFeeRateController(TradingFeeService tradingFeeService) {
        this.tradingFeeService = tradingFeeService;
    }

    @GetMapping("/api/v1/trading/fees/effective")
    public EffectiveTradingFeeResponse effectiveFee(
            @RequestParam(value = "userId", required = false) Long queryUserId,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestParam("instrumentId") String instrumentId,
            @RequestParam(value = "instrumentChangeId", defaultValue = "0") long instrumentChangeId,
            @RequestHeader(value = "X-Product-Line", required = false) String headerProductLine,
            @RequestParam(value = "productLine", required = false) String queryProductLine) {
        long userId = GatewayUserIdentity.requireUserId(queryUserId, headerUserId, adminUserId);
        return TradingControllerSupport.effectiveFee(tradingFeeService, userId, instrumentId,
                instrumentChangeId, headerProductLine, queryProductLine);
    }
}
