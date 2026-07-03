package com.surprising.insurance.provider.controller;

import com.surprising.insurance.api.InsuranceApiPaths;
import com.surprising.insurance.api.model.InsuranceCoverageQueryResponse;
import com.surprising.insurance.api.model.InsuranceFundAdjustmentRequest;
import com.surprising.insurance.api.model.InsuranceFundBalanceQueryResponse;
import com.surprising.insurance.api.model.InsuranceFundBalanceResponse;
import com.surprising.insurance.api.model.InsuranceLedgerQueryResponse;
import com.surprising.insurance.provider.config.InsuranceProperties;
import com.surprising.insurance.provider.service.InsuranceService;
import jakarta.validation.Valid;
import java.util.LinkedHashMap;
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
    private final InsuranceProperties properties;

    public InsuranceController(InsuranceService insuranceService, InsuranceProperties properties) {
        this.insuranceService = insuranceService;
        this.properties = properties;
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
        return runtimeConfig();
    }

    @PostMapping("/admin/runtime-config")
    public Map<String, Object> updateRuntimeConfig(@RequestHeader("X-Admin-User-Id") String adminUserId,
                                                   @RequestBody RuntimeConfigUpdate request) {
        if (request.coverageEnabled() != null) {
            properties.getCoverage().setEnabled(request.coverageEnabled());
        }
        if (request.scanDelayMs() != null) {
            properties.getCoverage().setScanDelayMs(nonNegative(request.scanDelayMs(), "scanDelayMs"));
        }
        if (request.batchSize() != null) {
            properties.getCoverage().setBatchSize(bounded(request.batchSize(), 1, 10_000, "batchSize"));
        }
        return runtimeConfig();
    }

    private Map<String, Object> runtimeConfig() {
        Map<String, Object> coverage = new LinkedHashMap<>();
        coverage.put("enabled", properties.getCoverage().isEnabled());
        coverage.put("scanDelayMs", properties.getCoverage().getScanDelayMs());
        coverage.put("batchSize", properties.getCoverage().getBatchSize());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("scope", "runtime");
        response.put("coverage", coverage);
        return response;
    }

    private long nonNegative(long value, String field) {
        if (value < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " must be non-negative");
        }
        return value;
    }

    private int bounded(int value, int min, int max, String field) {
        if (value < min || value > max) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " must be between " + min + " and " + max);
        }
        return value;
    }

    public record RuntimeConfigUpdate(
            Boolean coverageEnabled,
            Long scanDelayMs,
            Integer batchSize) {
    }
}
