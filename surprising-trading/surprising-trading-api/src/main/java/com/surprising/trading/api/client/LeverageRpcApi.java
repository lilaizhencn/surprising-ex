package com.surprising.trading.api.client;

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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(name = "surprising-order-provider", contextId = "leverageRpcApi")
@RequestMapping(TradingApiPaths.LEVERAGE_BASE_PATH)
public interface LeverageRpcApi {

    @PostMapping("/settings")
    LeverageSettingResponse set(@Valid @RequestBody LeverageSettingRequest request);

    @GetMapping("/settings")
    LeverageSettingResponse get(@RequestParam("userId") @Positive long userId,
                                @RequestParam("symbol") @NotBlank String symbol,
                                @RequestParam(value = "marginMode", required = false) MarginMode marginMode);
}
