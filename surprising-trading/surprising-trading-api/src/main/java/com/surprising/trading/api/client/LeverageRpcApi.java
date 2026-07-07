package com.surprising.trading.api.client;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.TradingApiPaths;
import com.surprising.trading.api.model.LeverageSettingRequest;
import com.surprising.trading.api.model.LeverageSettingResponse;
import com.surprising.trading.api.model.MarginMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(
        name = "surprising-order-provider",
        contextId = "leverageRpcApi",
        path = TradingApiPaths.LEVERAGE_BASE_PATH,
        url = "${surprising.clients.order.base-url:http://localhost:9084}")
public interface LeverageRpcApi {

    @PostMapping("/settings")
    LeverageSettingResponse set(@Valid @RequestBody LeverageSettingRequest request);

    @GetMapping("/settings")
    LeverageSettingResponse get(@RequestParam("userId") @Positive long userId,
                                @RequestParam("symbol") @NotBlank String symbol,
                                @RequestParam(value = "marginMode", required = false) MarginMode marginMode,
                                @RequestParam(value = "productLine", required = false) ProductLine productLine);

    default LeverageSettingResponse get(long userId, String symbol, MarginMode marginMode) {
        return get(userId, symbol, marginMode, null);
    }
}
