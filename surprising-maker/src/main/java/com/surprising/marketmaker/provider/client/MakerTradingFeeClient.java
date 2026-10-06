package com.surprising.marketmaker.provider.client;

import com.surprising.product.api.ProductLine;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** Uses the gateway's authoritative fee selection, including account overrides and expiry. */
@FeignClient(name = "surprising-trading-provider", contextId = "makerTradingFeeClient",
        url = "${surprising.clients.trading.base-url:http://localhost:9094}")
public interface MakerTradingFeeClient {
    @GetMapping("/internal/v1/trading/fees/effective")
    EffectiveFee effective(@RequestParam("userId") long userId,
            @RequestParam("instrumentId") String instrumentId,
            @RequestParam("instrumentChangeId") long instrumentChangeId,
            @RequestParam("productLine") ProductLine productLine);

    record EffectiveFee(long userId, ProductLine productLine, String instrumentId,
                        long instrumentChangeId, long makerFeeRatePpm) {}
}
