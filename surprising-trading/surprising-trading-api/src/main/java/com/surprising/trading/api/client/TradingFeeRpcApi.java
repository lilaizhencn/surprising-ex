package com.surprising.trading.api.client;

import com.surprising.trading.api.TradingApiPaths;
import com.surprising.trading.api.model.EffectiveTradingFeeResponse;
import com.surprising.product.api.ProductLine;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(
        name = "surprising-order-provider",
        contextId = "tradingFeeRpcApi",
        path = TradingApiPaths.FEE_BASE_PATH,
        url = "${surprising.clients.order.base-url:http://localhost:9084}")
public interface TradingFeeRpcApi {

    @GetMapping("/effective")
    EffectiveTradingFeeResponse effective(@RequestParam("userId") @Positive long userId,
                                          @RequestParam("symbol") @NotBlank String symbol,
                                          @RequestParam(value = "instrumentVersion", defaultValue = "0")
                                          long instrumentVersion,
                                          @RequestParam(value = "productLine", required = false)
                                          ProductLine productLine);

    default EffectiveTradingFeeResponse effective(long userId, String symbol, long instrumentVersion) {
        return effective(userId, symbol, instrumentVersion, null);
    }
}
