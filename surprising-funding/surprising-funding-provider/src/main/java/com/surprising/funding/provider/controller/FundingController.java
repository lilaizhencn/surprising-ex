package com.surprising.funding.provider.controller;

import com.surprising.funding.api.FundingApiPaths;
import com.surprising.funding.api.model.FundingPaymentQueryResponse;
import com.surprising.funding.api.model.FundingRateQueryResponse;
import com.surprising.funding.api.model.FundingRateResponse;
import com.surprising.funding.api.model.FundingSettlementResponse;
import com.surprising.funding.provider.service.FundingService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(FundingApiPaths.API_V1)
public class FundingController {

    private final FundingService fundingService;

    public FundingController(FundingService fundingService) {
        this.fundingService = fundingService;
    }

    @GetMapping("/rates/latest")
    public FundingRateResponse latestRate(@RequestParam String symbol) {
        return fundingService.latestRate(symbol);
    }

    @GetMapping("/rates/history")
    public FundingRateQueryResponse rateHistory(@RequestParam String symbol,
                                                @RequestParam(defaultValue = "100") int limit) {
        return fundingService.rateHistory(symbol, limit);
    }

    @GetMapping("/settlements/latest")
    public FundingSettlementResponse latestSettlement(@RequestParam String symbol) {
        return fundingService.latestSettlement(symbol);
    }

    @GetMapping("/payments")
    public FundingPaymentQueryResponse payments(@RequestParam long userId,
                                                @RequestParam(required = false) String symbol,
                                                @RequestParam(defaultValue = "100") int limit) {
        return fundingService.payments(userId, symbol, limit);
    }
}
