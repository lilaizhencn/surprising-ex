package com.surprising.insurance.provider.controller;

import com.surprising.insurance.api.InsuranceApiPaths;
import com.surprising.insurance.api.model.InsuranceCoverageQueryResponse;
import com.surprising.insurance.api.model.InsuranceFundAdjustmentRequest;
import com.surprising.insurance.api.model.InsuranceFundBalanceQueryResponse;
import com.surprising.insurance.api.model.InsuranceFundBalanceResponse;
import com.surprising.insurance.api.model.InsuranceLedgerQueryResponse;
import com.surprising.insurance.provider.service.InsuranceService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(InsuranceApiPaths.API_V1)
public class InsuranceController {

    private final InsuranceService insuranceService;

    public InsuranceController(InsuranceService insuranceService) {
        this.insuranceService = insuranceService;
    }

    @PostMapping("/admin/fund-adjustments")
    public InsuranceFundBalanceResponse adjustFund(@Valid @RequestBody InsuranceFundAdjustmentRequest request) {
        return insuranceService.adjustFund(request);
    }

    @GetMapping("/balances")
    public InsuranceFundBalanceQueryResponse balances(@RequestParam(required = false) String asset) {
        return insuranceService.balances(asset);
    }

    @GetMapping("/ledger")
    public InsuranceLedgerQueryResponse ledger(@RequestParam(required = false) String asset,
                                               @RequestParam(defaultValue = "100") int limit) {
        return insuranceService.ledger(asset, limit);
    }

    @GetMapping("/coverages")
    public InsuranceCoverageQueryResponse coverages(@RequestParam(required = false) Long userId,
                                                    @RequestParam(required = false) String asset,
                                                    @RequestParam(defaultValue = "100") int limit) {
        return insuranceService.coverages(userId, asset, limit);
    }
}
