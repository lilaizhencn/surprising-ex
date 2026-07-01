package com.surprising.liquidation.api.client;

import com.surprising.liquidation.api.LiquidationApiPaths;
import com.surprising.liquidation.api.model.LiquidationOrderQueryResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(name = "surprising-liquidation-provider", contextId = "liquidationRpcApi")
@RequestMapping(LiquidationApiPaths.BASE_PATH)
public interface LiquidationRpcApi {

    @GetMapping("/orders")
    LiquidationOrderQueryResponse orders(@RequestParam(value = "userId", required = false) Long userId,
                                         @RequestParam(value = "limit", defaultValue = "100") @Min(1) @Max(1000) int limit);

    @GetMapping("/orders/by-candidate")
    LiquidationOrderQueryResponse ordersByCandidate(@RequestParam("candidateId") @Positive long candidateId);
}
