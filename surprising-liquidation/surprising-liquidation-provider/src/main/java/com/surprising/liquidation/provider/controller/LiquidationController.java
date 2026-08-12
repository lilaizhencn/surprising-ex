package com.surprising.liquidation.provider.controller;

import com.surprising.liquidation.api.LiquidationApiPaths;
import com.surprising.liquidation.api.model.LiquidationOrderQueryResponse;
import com.surprising.liquidation.provider.service.LiquidationRuntimeConfigService;
import com.surprising.liquidation.provider.service.LiquidationService;
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
    private final LiquidationRuntimeConfigService runtimeConfigService;

    public LiquidationController(LiquidationService liquidationService,
                                 LiquidationRuntimeConfigService runtimeConfigService) {
        this.liquidationService = liquidationService;
        this.runtimeConfigService = runtimeConfigService;
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
        return runtimeConfigService.current();
    }

    @PostMapping(LiquidationApiPaths.BASE_PATH + "/admin/runtime-config")
    public Map<String, Object> updateRuntimeConfig(@RequestHeader("X-Admin-User-Id") String adminUserId,
                                                   @RequestBody RuntimeConfigUpdate request) {
        try {
            return runtimeConfigService.update(
                    request.executionEnabled(),
                    request.liquidationFeeRatePpm(),
                    request.normalCloseRatioPpm(),
                    request.severeCloseRatioPpm(),
                    request.fullCloseMarginRatioPpm(),
                    request.minCloseSteps());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
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
