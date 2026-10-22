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
import com.surprising.insurance.api.utils.AdminCursorPage;
import com.surprising.insurance.provider.model.InsuranceHistoryPageQuery;
import java.time.DateTimeException;
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

    @PostMapping("/admin/run-cycle")
    public InsuranceService.CoverageCycle runCycle(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId) {
        requireAdmin(adminUserId);
        return insuranceService.coverDeficits();
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
        return insuranceService.ledger(optionalAsset(asset), historyPage(limit, null, null));
    }

    @GetMapping("/coverages")
    public InsuranceCoverageQueryResponse coverages(@RequestParam(required = false) Long userId,
                                                    @RequestParam(required = false) String asset,
                                                    @RequestParam(defaultValue = "100") int limit) {
        return insuranceService.coverages(positiveUser(userId), optionalAsset(asset), historyPage(limit, null, null));
    }

    @GetMapping("/admin/ledger")
    public InsuranceLedgerQueryResponse adminLedger(
            @RequestHeader("X-Admin-User-Id") String adminUserId,
            @RequestParam(required = false) String asset,
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) String sort) {
        try {
            return insuranceService.ledger(optionalAsset(asset), historyPage(limit, cursor, sort));
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
            return insuranceService.coverages(positiveUser(userId), optionalAsset(asset), historyPage(limit, cursor, sort));
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
            if (request == null || request.expectedVersion() == null || request.expectedVersion() < 1
                    || adminUserId == null || !adminUserId.matches("[1-9][0-9]*") || request.reason() == null
                    || request.reason().isBlank() || request.reason().length() > 1000)
                throw new IllegalArgumentException("expectedVersion, administrator and reason (1-1000 characters) are required");
            return runtimeConfigService.update(
                    adminUserId, request.expectedVersion(), request.coverageEnabled(), request.scanDelayMs(), request.batchSize(), request.reason().trim());
        } catch (org.springframework.dao.OptimisticLockingFailureException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage(), ex);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    public record RuntimeConfigUpdate(
            Boolean coverageEnabled,
            Long scanDelayMs,
            Integer batchSize, Long expectedVersion, String reason) {
    }

    private void requireAdmin(String adminUserId) {
        if (adminUserId == null || adminUserId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "admin identity header is required");
        }
    }
    private InsuranceHistoryPageQuery historyPage(int limit, String cursor, String sort) {
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("limit must be in [1,1000]");
        String order = sort == null || sort.isBlank() ? "createdAt.desc" : sort.trim();
        if (!order.equals("createdAt.desc") && !order.equals("createdAt.asc")) {
            throw new IllegalArgumentException("unsupported sort: " + sort);
        }
        try {
            var after = AdminCursorPage.decodeCursor(cursor);
            return new InsuranceHistoryPageQuery(limit, after == null ? null : after.timestamp(),
                    after == null ? 0L : after.id(), order.equals("createdAt.desc"));
        } catch (DateTimeException invalid) {
            throw new IllegalArgumentException("invalid cursor", invalid);
        }
    }

    private String optionalAsset(String asset) {
        return asset == null || asset.isBlank() ? null : normalizeAsset(asset);
    }

    private String normalizeAsset(String asset) {
        if (asset == null || asset.isBlank()) {
            throw new IllegalArgumentException("asset is required");
        }
        String normalized = asset.trim().toUpperCase();
        if (!normalized.matches("[A-Z0-9]{2,20}")) {
            throw new IllegalArgumentException("invalid asset: " + asset);
        }
        return normalized;
    }

    private Long positiveUser(Long value) {
        if (value != null && value <= 0) throw new IllegalArgumentException("userId must be positive");
        return value;
    }

}
