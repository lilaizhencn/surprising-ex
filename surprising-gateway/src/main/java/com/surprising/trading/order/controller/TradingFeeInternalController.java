package com.surprising.trading.order.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import com.surprising.trading.api.TradingApiPaths;
import com.surprising.trading.api.model.EffectiveTradingFeeResponse;
import com.surprising.trading.order.service.TradingFeeRequestService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Host-local fee lookup for the maker, using the same selection as order submission. */
@RestController
public class TradingFeeInternalController {
    private final TradingFeeRequestService fees;
    public TradingFeeInternalController(TradingFeeRequestService fees) { this.fees = fees; }

    @GetMapping(TradingApiPaths.INTERNAL_FEE_BASE_PATH + "/effective")
    public EffectiveTradingFeeResponse effective(@RequestParam long userId, @RequestParam String instrumentId,
            @RequestParam long instrumentChangeId, @RequestParam String productLine, HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        if (!("127.0.0.1".equals(remote) || "::1".equals(remote) || "0:0:0:0:0:0:0:1".equals(remote))
                || request.getHeader("X-Forwarded-For") != null || request.getHeader("Forwarded") != null
                || request.getHeader("X-Real-IP") != null)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "host-local maker endpoint");
        return fees.effective(userId, instrumentId, instrumentChangeId, productLine, productLine);
    }
}
