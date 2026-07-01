package com.surprising.trading.api.client;

import com.surprising.trading.api.TradingApiPaths;
import com.surprising.trading.api.model.FeeTierAssignmentResponse;
import com.surprising.trading.api.model.FeeTierQueryResponse;
import com.surprising.trading.api.model.FeeTierRefreshResponse;
import com.surprising.trading.api.model.FeeTierResponse;
import com.surprising.trading.api.model.FeeTierUpsertRequest;
import com.surprising.trading.api.model.FeeScheduleQueryResponse;
import com.surprising.trading.api.model.FeeScheduleResponse;
import com.surprising.trading.api.model.FeeScheduleStatus;
import com.surprising.trading.api.model.FeeScheduleUpsertRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(name = "surprising-order-provider", contextId = "tradingFeeAdminRpcApi")
@RequestMapping(TradingApiPaths.ADMIN_FEE_BASE_PATH)
public interface TradingFeeAdminRpcApi {

    @PostMapping("/schedules")
    FeeScheduleResponse upsert(@Valid @RequestBody FeeScheduleUpsertRequest request);

    @PostMapping("/schedules/{feeScheduleId}/disable")
    FeeScheduleResponse disable(@PathVariable("feeScheduleId") @Positive long feeScheduleId);

    @GetMapping("/schedules")
    FeeScheduleQueryResponse query(@RequestParam(value = "userId", defaultValue = "0") long userId,
                                   @RequestParam(value = "symbol", required = false) String symbol,
                                   @RequestParam(value = "status", required = false) FeeScheduleStatus status,
                                   @RequestParam(value = "limit", defaultValue = "100")
                                   @Min(1) @Max(500) int limit);

    @PostMapping("/tiers")
    FeeTierResponse upsertTier(@Valid @RequestBody FeeTierUpsertRequest request);

    @GetMapping("/tiers")
    FeeTierQueryResponse queryTiers(@RequestParam(value = "status", required = false) FeeScheduleStatus status,
                                    @RequestParam(value = "limit", defaultValue = "100")
                                    @Min(1) @Max(500) int limit);

    @PostMapping("/tiers/refresh")
    FeeTierAssignmentResponse refreshUserTier(@RequestParam("userId") @Positive long userId);

    @PostMapping("/tiers/refresh-active")
    FeeTierRefreshResponse refreshActiveUserTiers(@RequestParam(value = "limit", defaultValue = "1000")
                                                  @Min(1) @Max(10000) int limit);

    @GetMapping("/tiers/users/{userId}")
    FeeTierAssignmentResponse currentUserTier(@PathVariable("userId") @Positive long userId);
}
