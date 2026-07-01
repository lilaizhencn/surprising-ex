package com.surprising.funding.api.client;

import com.surprising.funding.api.FundingApiPaths;
import com.surprising.funding.api.model.FundingPaymentQueryResponse;
import com.surprising.funding.api.model.FundingRateQueryResponse;
import com.surprising.funding.api.model.FundingRateResponse;
import com.surprising.funding.api.model.FundingSettlementResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(name = "surprising-funding-provider", path = FundingApiPaths.API_V1)
public interface FundingRpcApi {

    @GetMapping("/rates/latest")
    FundingRateResponse latestRate(@RequestParam("symbol") String symbol);

    @GetMapping("/rates/history")
    FundingRateQueryResponse rateHistory(@RequestParam("symbol") String symbol,
                                         @RequestParam(value = "limit", defaultValue = "100") int limit);

    @GetMapping("/settlements/latest")
    FundingSettlementResponse latestSettlement(@RequestParam("symbol") String symbol);

    @GetMapping("/payments")
    FundingPaymentQueryResponse payments(@RequestParam("userId") long userId,
                                         @RequestParam(value = "symbol", required = false) String symbol,
                                         @RequestParam(value = "limit", defaultValue = "100") int limit);
}
