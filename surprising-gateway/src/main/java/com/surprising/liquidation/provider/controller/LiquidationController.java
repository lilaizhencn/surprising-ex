package com.surprising.liquidation.provider.controller;

import com.surprising.liquidation.api.LiquidationApiPaths;
import com.surprising.liquidation.api.model.LiquidationOrderQueryResponse;
import com.surprising.liquidation.provider.service.LiquidationRuntimeConfigService;
import com.surprising.liquidation.provider.service.LiquidationService;
import java.util.Map;
import org.springframework.http.HttpStatus;
import com.surprising.realtime.api.exception.ReadViewUnavailableException;
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
        } catch (ReadViewUnavailableException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage(), ex);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @GetMapping(LiquidationApiPaths.BASE_PATH + "/orders/by-candidate")
    public LiquidationOrderQueryResponse ordersByCandidate(@RequestParam("candidateId") long candidateId) {
        try {
            return liquidationService.ordersByCandidate(candidateId);
        } catch (ReadViewUnavailableException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage(), ex);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @GetMapping(LiquidationApiPaths.BASE_PATH + "/admin/runtime-config")
    public Map<String, Object> runtimeConfig(@RequestHeader("X-Admin-User-Id") String adminUserId) {
        return runtimeConfigService.current();
    }

    @PostMapping(LiquidationApiPaths.BASE_PATH + "/admin/runtime-config")
    public Map<String, Object> updateRuntimeConfig(@RequestHeader("X-Admin-User-Id") String admin,
            @RequestBody RuntimeConfigUpdate request) {
        try {
            if (request == null || request.expectedVersion() == null || request.expectedVersion() < 1
                    || admin == null || !admin.matches("[1-9][0-9]*") || request.reason() == null
                    || request.reason().isBlank() || request.reason().length() > 1000)
                throw new IllegalArgumentException("expectedVersion, administrator and reason (1-1000 characters) are required");
            return runtimeConfigService.update(admin, request.expectedVersion(), request.executionEnabled(),
                    request.liquidationFeeRatePpm(), request.delayMs(), request.workBatchSize(),
                    request.maxPagesPerRun(), request.maxWorkBytes(), request.reason().trim());
        } catch (org.springframework.dao.OptimisticLockingFailureException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage(), ex);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }
    public record RuntimeConfigUpdate(Long expectedVersion, Boolean executionEnabled, Long liquidationFeeRatePpm,
            Long delayMs, Integer workBatchSize, Integer maxPagesPerRun, Integer maxWorkBytes, String reason) {}

}
