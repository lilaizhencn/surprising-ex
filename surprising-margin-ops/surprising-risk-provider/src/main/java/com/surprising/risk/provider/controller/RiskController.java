package com.surprising.risk.provider.controller;

import com.surprising.risk.api.RiskApiPaths;
import com.surprising.risk.api.model.LiquidationCandidateQueryResponse;
import com.surprising.risk.api.model.RiskAccountSnapshotResponse;
import com.surprising.risk.api.model.RiskPositionQueryResponse;
import com.surprising.risk.provider.config.RiskProperties;
import com.surprising.risk.provider.service.RiskService;
import java.util.LinkedHashMap;
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
    private final RiskProperties properties;

    public RiskController(RiskService riskService, RiskProperties properties) {
        this.riskService = riskService;
        this.properties = properties;
    }

    @GetMapping(RiskApiPaths.RISK_BASE_PATH + "/account/latest")
    public RiskAccountSnapshotResponse latestAccountRisk(@RequestParam("userId") long userId,
                                                         @RequestParam(value = "accountType",
                                                                 defaultValue = "USDT_PERPETUAL") String accountType,
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
        return runtimeConfig();
    }

    @PostMapping(RiskApiPaths.RISK_BASE_PATH + "/admin/runtime-config")
    public Map<String, Object> updateRuntimeConfig(@RequestHeader("X-Admin-User-Id") String adminUserId,
                                                   @RequestBody RuntimeConfigUpdate request) {
        if (request.calculationEnabled() != null) {
            properties.getCalculation().setEnabled(request.calculationEnabled());
        }
        if (request.scanDelayMs() != null) {
            properties.getCalculation().setScanDelayMs(nonNegative(request.scanDelayMs(), "scanDelayMs"));
        }
        if (request.warningMarginRatioPpm() != null) {
            properties.getCalculation().setWarningMarginRatioPpm(nonNegative(request.warningMarginRatioPpm(), "warningMarginRatioPpm"));
        }
        if (request.liquidationMarginRatioPpm() != null) {
            properties.getCalculation().setLiquidationMarginRatioPpm(nonNegative(request.liquidationMarginRatioPpm(), "liquidationMarginRatioPpm"));
        }
        if (request.scanBatchSize() != null) {
            properties.getCalculation().setScanBatchSize(bounded(request.scanBatchSize(), 1, 10_000, "scanBatchSize"));
        }
        return runtimeConfig();
    }

    private Map<String, Object> runtimeConfig() {
        Map<String, Object> calculation = new LinkedHashMap<>();
        calculation.put("enabled", properties.getCalculation().isEnabled());
        calculation.put("scanDelayMs", properties.getCalculation().getScanDelayMs());
        calculation.put("warningMarginRatioPpm", properties.getCalculation().getWarningMarginRatioPpm());
        calculation.put("liquidationMarginRatioPpm", properties.getCalculation().getLiquidationMarginRatioPpm());
        calculation.put("maxMarkAge", properties.getCalculation().getMaxMarkAge().toString());
        calculation.put("scanBatchSize", properties.getCalculation().getScanBatchSize());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("scope", "runtime");
        response.put("calculation", calculation);
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
            Boolean calculationEnabled,
            Long scanDelayMs,
            Long warningMarginRatioPpm,
            Long liquidationMarginRatioPpm,
            Integer scanBatchSize) {
    }
}
