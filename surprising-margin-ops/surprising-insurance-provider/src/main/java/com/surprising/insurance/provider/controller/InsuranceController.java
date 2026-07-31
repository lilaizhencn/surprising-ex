package com.surprising.insurance.provider.controller;

import com.surprising.insurance.api.InsuranceApiPaths;
import com.surprising.insurance.api.model.InsuranceCoverageQueryResponse;
import com.surprising.insurance.api.model.InsuranceFundAdjustmentRequest;
import com.surprising.insurance.api.model.InsuranceFundBalanceQueryResponse;
import com.surprising.insurance.api.model.InsuranceFundBalanceResponse;
import com.surprising.insurance.api.model.InsuranceLedgerQueryResponse;
import com.surprising.insurance.provider.service.InsuranceService;
import com.surprising.insurance.provider.service.InsuranceRuntimeConfigService;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping(InsuranceApiPaths.API_V1)
public class InsuranceController {

    private final InsuranceService insuranceService;
    private final InsuranceRuntimeConfigService runtimeConfigService;

    public InsuranceController(InsuranceService insuranceService,
                               InsuranceRuntimeConfigService runtimeConfigService) {
        this.insuranceService = insuranceService;
        this.runtimeConfigService = runtimeConfigService;
    }

    @PostMapping("/admin/fund-adjustments")
    public InsuranceFundBalanceResponse adjustFund(@RequestHeader("X-Admin-User-Id") String adminUserId,
                                                   @Valid @RequestBody InsuranceFundAdjustmentRequest request) {
        return insuranceService.adjustFund(request);
    }

    @GetMapping("/balances")
    public InsuranceFundBalanceQueryResponse balances(@RequestParam(required = false) String asset) {
        return insuranceService.balances(asset);
    }

    @GetMapping("/admin/balances")
    public InsuranceFundBalanceQueryResponse adminBalances(
            @RequestHeader("X-Admin-User-Id") String adminUserId,
            @RequestParam(required = false) String asset) {
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

    @GetMapping("/admin/ledger")
    public InsuranceLedgerQueryResponse adminLedger(
            @RequestHeader("X-Admin-User-Id") String adminUserId,
            @RequestParam(required = false) String asset,
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) String sort) {
        try {
            return insuranceService.ledger(asset, limit, cursor, sort);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @GetMapping("/admin/coverages")
    public InsuranceCoverageQueryResponse adminCoverages(
            @RequestHeader("X-Admin-User-Id") String adminUserId,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String asset,
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) String sort) {
        try {
            return insuranceService.coverages(userId, asset, limit, cursor, sort);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @GetMapping("/admin/runtime-config")
    public Map<String, Object> runtimeConfig(@RequestHeader("X-Admin-User-Id") String adminUserId) {
        return runtimeConfigService.current();
    }

    @PostMapping("/admin/runtime-config")
    public Map<String, Object> updateRuntimeConfig(@RequestHeader("X-Admin-User-Id") String adminUserId,
                                                   @RequestBody RuntimeConfigUpdate request) {
        try {
            return runtimeConfigService.update(
                    request.coverageEnabled(), request.scanDelayMs(), request.batchSize());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    public record RuntimeConfigUpdate(
            Boolean coverageEnabled,
            Long scanDelayMs,
            Integer batchSize) {
    }
}
