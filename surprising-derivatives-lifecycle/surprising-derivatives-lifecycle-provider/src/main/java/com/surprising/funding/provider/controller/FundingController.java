package com.surprising.funding.provider.controller;

import com.surprising.funding.api.FundingApiPaths;
import com.surprising.funding.api.model.FundingPaymentQueryResponse;
import com.surprising.funding.api.model.FundingRateQueryResponse;
import com.surprising.funding.api.model.FundingRateResponse;
import com.surprising.funding.api.model.FundingSettlementResponse;
import com.surprising.funding.provider.service.FundingRuntimeConfigService;
import com.surprising.funding.provider.service.FundingService;
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
@RequestMapping(FundingApiPaths.API_V1)
public class FundingController {

    private final FundingService fundingService;
    private final FundingRuntimeConfigService runtimeConfigService;

    public FundingController(FundingService fundingService,
                             FundingRuntimeConfigService runtimeConfigService) {
        this.fundingService = fundingService;
        this.runtimeConfigService = runtimeConfigService;
    }

    @GetMapping("/rates/latest")
    public FundingRateResponse latestRate(@RequestParam String symbol) {
        return fundingService.latestRate(symbol);
    }

    @GetMapping("/rates/history")
    public FundingRateQueryResponse rateHistory(@RequestParam String symbol,
                                                @RequestParam(defaultValue = "100") int limit) {
        return fundingService.rateHistory(symbol, limit);
    }

    @GetMapping("/settlements/latest")
    public FundingSettlementResponse latestSettlement(@RequestParam String symbol) {
        return fundingService.latestSettlement(symbol);
    }

    @GetMapping("/payments")
    public FundingPaymentQueryResponse payments(@RequestParam long userId,
                                                @RequestParam(required = false) String symbol,
                                                @RequestParam(defaultValue = "100") int limit) {
        return fundingService.payments(userId, symbol, limit);
    }

    @GetMapping("/admin/rates/latest")
    public FundingRateResponse adminLatestRate(@RequestHeader("X-Admin-User-Id") String adminUserId,
                                               @RequestParam String symbol) {
        return latestRate(symbol);
    }

    @GetMapping("/admin/rates/history")
    public FundingRateQueryResponse adminRateHistory(
            @RequestHeader("X-Admin-User-Id") String adminUserId,
            @RequestParam String symbol,
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) String sort) {
        try {
            return fundingService.rateHistory(symbol, limit, cursor, sort);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @GetMapping("/admin/settlements/latest")
    public FundingSettlementResponse adminLatestSettlement(@RequestHeader("X-Admin-User-Id") String adminUserId,
                                                           @RequestParam String symbol) {
        return latestSettlement(symbol);
    }

    @GetMapping("/admin/payments")
    public FundingPaymentQueryResponse adminPayments(
            @RequestHeader("X-Admin-User-Id") String adminUserId,
            @RequestParam long userId,
            @RequestParam(required = false) String symbol,
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) String sort) {
        try {
            return fundingService.payments(userId, symbol, limit, cursor, sort);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @GetMapping("/admin/runtime-config")
    public Map<String, Object> runtimeConfig(@RequestHeader("X-Admin-User-Id") String adminUserId) {
        return runtimeConfigService.current();
    }

    @PostMapping("/admin/run-cycle")
    public FundingService.SettlementCycle runCycle(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId) {
        requireAdmin(adminUserId);
        return fundingService.settleDueRates();
    }

    @PostMapping("/admin/runtime-config")
    public Map<String, Object> updateRuntimeConfig(@RequestHeader("X-Admin-User-Id") String adminUserId,
                                                   @RequestBody RuntimeConfigUpdate request) {
        try {
            return runtimeConfigService.update(
                    request.calculationEnabled(),
                    request.settlementEnabled(),
                    request.coordinationEnabled(),
                    request.calculationPublishDelayMs(),
                    request.settleDelayMs(),
                    request.settlementBatchSize(),
                    request.paymentPageSize(),
                    request.maxPagesPerRun(),
                    request.reconcileBatchSize());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    public record RuntimeConfigUpdate(
            Boolean calculationEnabled,
            Boolean settlementEnabled,
            Boolean coordinationEnabled,
            Long calculationPublishDelayMs,
            Long settleDelayMs,
            Integer settlementBatchSize,
            Integer paymentPageSize,
            Integer maxPagesPerRun,
            Integer reconcileBatchSize) {
    }

    private void requireAdmin(String adminUserId) {
        if (adminUserId == null || adminUserId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "admin identity header is required");
        }
    }
}
