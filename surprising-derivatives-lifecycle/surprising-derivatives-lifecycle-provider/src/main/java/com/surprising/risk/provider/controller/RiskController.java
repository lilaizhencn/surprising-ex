package com.surprising.risk.provider.controller;

import com.surprising.risk.api.RiskApiPaths;
import com.surprising.risk.api.model.LiquidationCandidateQueryResponse;
import com.surprising.risk.api.model.RiskAccountSnapshotResponse;
import com.surprising.risk.api.model.RiskPositionQueryResponse;
import com.surprising.risk.provider.service.RiskRuntimeConfigService;
import com.surprising.risk.provider.service.RiskService;
import com.surprising.risk.provider.service.RiskAeronGateway.RiskScanControlConflictException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class RiskController {

    private final RiskService riskService;
    private final RiskRuntimeConfigService runtimeConfigService;

    public RiskController(RiskService riskService,
                          RiskRuntimeConfigService runtimeConfigService) {
        this.riskService = riskService;
        this.runtimeConfigService = runtimeConfigService;
    }

    @GetMapping(RiskApiPaths.RISK_BASE_PATH + "/account/latest")
    public RiskAccountSnapshotResponse latestAccountRisk(@RequestParam("userId") long userId,
                                                         @RequestParam("accountType") String accountType,
                                                         @RequestParam("settleAsset") String settleAsset) {
        try {
            return riskService.latestAccount(userId, accountType, settleAsset);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        }
    }

    @GetMapping(RiskApiPaths.RISK_BASE_PATH + "/positions/latest")
    public RiskPositionQueryResponse latestPositionRisk(@RequestParam("userId") long userId) {
        return riskService.latestPositions(userId);
    }

    @GetMapping(RiskApiPaths.RISK_BASE_PATH + "/liquidation-candidates")
    public LiquidationCandidateQueryResponse liquidationCandidates(
            @RequestParam(value = "status", defaultValue = "NEW") String status,
            @RequestParam(value = "limit", defaultValue = "100") int limit) {
        try {
            return riskService.liquidationCandidates(status, limit);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @GetMapping(RiskApiPaths.RISK_BASE_PATH + "/admin/runtime-config")
    public Map<String, Object> runtimeConfig(@RequestHeader("X-Admin-User-Id") String adminUserId) {
        requireAdmin(adminUserId);
        return runtimeConfigService.current();
    }

    @PostMapping(RiskApiPaths.RISK_BASE_PATH + "/admin/runtime-config")
    public Map<String, Object> updateRuntimeConfig(@RequestHeader("X-Admin-User-Id") String adminUserId,
                                                   @RequestBody RuntimeConfigUpdate request) {
        try {
            return runtimeConfigService.update(
                    requireAdmin(adminUserId),
                    request.expectedVersion(),
                    request.calculationEnabled(),
                    request.scanDelayMs(),
                    request.scanBatchSize(),
                    request.reason());
        } catch (RiskScanControlConflictException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage(), ex);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    public record RuntimeConfigUpdate(
            Long expectedVersion,
            Boolean calculationEnabled,
            Long scanDelayMs,
            Integer scanBatchSize,
            String reason) {
    }

    private static String requireAdmin(String adminUserId) {
        if (adminUserId == null || adminUserId.isBlank()) {
            throw new IllegalArgumentException("admin user header is required");
        }
        return adminUserId.trim();
    }
}
