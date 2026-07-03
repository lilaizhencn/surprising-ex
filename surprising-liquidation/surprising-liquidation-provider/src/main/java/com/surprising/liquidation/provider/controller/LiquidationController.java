package com.surprising.liquidation.provider.controller;

import com.surprising.liquidation.api.LiquidationApiPaths;
import com.surprising.liquidation.api.model.LiquidationOrderQueryResponse;
import com.surprising.liquidation.provider.config.LiquidationProperties;
import com.surprising.liquidation.provider.service.LiquidationService;
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
public class LiquidationController {

    private final LiquidationService liquidationService;
    private final LiquidationProperties properties;

    public LiquidationController(LiquidationService liquidationService, LiquidationProperties properties) {
        this.liquidationService = liquidationService;
        this.properties = properties;
    }

    @GetMapping(LiquidationApiPaths.BASE_PATH + "/orders")
    public LiquidationOrderQueryResponse orders(@RequestParam(value = "userId", required = false) Long userId,
                                                @RequestParam(value = "limit", defaultValue = "100") int limit) {
        try {
            return liquidationService.orders(userId, limit);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @GetMapping(LiquidationApiPaths.BASE_PATH + "/orders/by-candidate")
    public LiquidationOrderQueryResponse ordersByCandidate(@RequestParam("candidateId") long candidateId) {
        return liquidationService.ordersByCandidate(candidateId);
    }

    @GetMapping(LiquidationApiPaths.BASE_PATH + "/admin/runtime-config")
    public Map<String, Object> runtimeConfig(@RequestHeader("X-Admin-User-Id") String adminUserId) {
        return runtimeConfig();
    }

    @PostMapping(LiquidationApiPaths.BASE_PATH + "/admin/runtime-config")
    public Map<String, Object> updateRuntimeConfig(@RequestHeader("X-Admin-User-Id") String adminUserId,
                                                   @RequestBody RuntimeConfigUpdate request) {
        if (request.executionEnabled() != null) {
            properties.getExecution().setEnabled(request.executionEnabled());
        }
        if (request.liquidationFeeRatePpm() != null) {
            properties.getExecution().setLiquidationFeeRatePpm(nonNegative(request.liquidationFeeRatePpm(), "liquidationFeeRatePpm"));
        }
        if (request.normalCloseRatioPpm() != null) {
            properties.getSizing().setNormalCloseRatioPpm(nonNegative(request.normalCloseRatioPpm(), "normalCloseRatioPpm"));
        }
        if (request.severeCloseRatioPpm() != null) {
            properties.getSizing().setSevereCloseRatioPpm(nonNegative(request.severeCloseRatioPpm(), "severeCloseRatioPpm"));
        }
        if (request.fullCloseMarginRatioPpm() != null) {
            properties.getSizing().setFullCloseMarginRatioPpm(nonNegative(request.fullCloseMarginRatioPpm(), "fullCloseMarginRatioPpm"));
        }
        if (request.minCloseSteps() != null) {
            properties.getSizing().setMinCloseSteps(positive(request.minCloseSteps(), "minCloseSteps"));
        }
        return runtimeConfig();
    }

    private Map<String, Object> runtimeConfig() {
        Map<String, Object> execution = new LinkedHashMap<>();
        execution.put("enabled", properties.getExecution().isEnabled());
        execution.put("liquidationFeeRatePpm", properties.getExecution().getLiquidationFeeRatePpm());

        Map<String, Object> sizing = new LinkedHashMap<>();
        sizing.put("normalCloseRatioPpm", properties.getSizing().getNormalCloseRatioPpm());
        sizing.put("severeMarginRatioPpm", properties.getSizing().getSevereMarginRatioPpm());
        sizing.put("severeCloseRatioPpm", properties.getSizing().getSevereCloseRatioPpm());
        sizing.put("fullCloseMarginRatioPpm", properties.getSizing().getFullCloseMarginRatioPpm());
        sizing.put("minCloseSteps", properties.getSizing().getMinCloseSteps());

        Map<String, Object> risk = new LinkedHashMap<>();
        risk.put("maxSnapshotAge", properties.getRisk().getMaxSnapshotAge().toString());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("scope", "runtime");
        response.put("execution", execution);
        response.put("sizing", sizing);
        response.put("risk", risk);
        return response;
    }

    private long nonNegative(long value, String field) {
        if (value < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " must be non-negative");
        }
        return value;
    }

    private long positive(long value, String field) {
        if (value <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " must be positive");
        }
        return value;
    }

    public record RuntimeConfigUpdate(
            Boolean executionEnabled,
            Long liquidationFeeRatePpm,
            Long normalCloseRatioPpm,
            Long severeCloseRatioPpm,
            Long fullCloseMarginRatioPpm,
            Long minCloseSteps) {
    }
}
