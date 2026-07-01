package com.surprising.insurance.api.client;

import com.surprising.insurance.api.InsuranceApiPaths;
import com.surprising.insurance.api.model.InsuranceCoverageQueryResponse;
import com.surprising.insurance.api.model.InsuranceFundAdjustmentRequest;
import com.surprising.insurance.api.model.InsuranceFundBalanceQueryResponse;
import com.surprising.insurance.api.model.InsuranceFundBalanceResponse;
import com.surprising.insurance.api.model.InsuranceLedgerQueryResponse;
import jakarta.validation.Valid;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(
        name = "surprising-insurance-provider",
        path = InsuranceApiPaths.API_V1,
        url = "${surprising.clients.insurance.base-url:http://localhost:9090}")
public interface InsuranceRpcApi {

    @PostMapping("/admin/fund-adjustments")
    InsuranceFundBalanceResponse adjustFund(@Valid @RequestBody InsuranceFundAdjustmentRequest request);

    @GetMapping("/balances")
    InsuranceFundBalanceQueryResponse balances(@RequestParam(value = "asset", required = false) String asset);

    @GetMapping("/ledger")
    InsuranceLedgerQueryResponse ledger(@RequestParam(value = "asset", required = false) String asset,
                                        @RequestParam(value = "limit", defaultValue = "100") int limit);

    @GetMapping("/coverages")
    InsuranceCoverageQueryResponse coverages(@RequestParam(value = "userId", required = false) Long userId,
                                             @RequestParam(value = "asset", required = false) String asset,
                                             @RequestParam(value = "limit", defaultValue = "100") int limit);
}
