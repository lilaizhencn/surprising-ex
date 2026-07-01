package com.surprising.risk.api.client;

import com.surprising.risk.api.RiskApiPaths;
import com.surprising.risk.api.model.LiquidationCandidateQueryResponse;
import com.surprising.risk.api.model.RiskAccountSnapshotResponse;
import com.surprising.risk.api.model.RiskPositionQueryResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(name = "surprising-risk-provider", contextId = "riskRpcApi")
@RequestMapping(RiskApiPaths.RISK_BASE_PATH)
public interface RiskRpcApi {

    @GetMapping("/account/latest")
    RiskAccountSnapshotResponse latestAccountRisk(@RequestParam("userId") @Positive long userId,
                                                  @RequestParam("settleAsset") @NotBlank String settleAsset);

    @GetMapping("/positions/latest")
    RiskPositionQueryResponse latestPositionRisk(@RequestParam("userId") @Positive long userId);

    @GetMapping("/liquidation-candidates")
    LiquidationCandidateQueryResponse liquidationCandidates(
            @RequestParam(value = "status", defaultValue = "NEW") String status,
            @RequestParam(value = "limit", defaultValue = "100") @Min(1) @Max(1000) int limit);
}
