package com.surprising.funding.provider.controller;

import com.surprising.funding.api.FundingApiPaths;
import com.surprising.funding.api.model.FundingPaymentQueryResponse;
import com.surprising.funding.api.model.FundingRateQueryResponse;
import com.surprising.funding.api.model.FundingRateResponse;
import com.surprising.funding.api.model.FundingSettlementResponse;
import com.surprising.funding.provider.config.FundingProperties;
import com.surprising.funding.provider.service.FundingService;
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
@RequestMapping(FundingApiPaths.API_V1)
public class FundingController {

    private final FundingService fundingService;
    private final FundingProperties properties;

    public FundingController(FundingService fundingService, FundingProperties properties) {
        this.fundingService = fundingService;
        this.properties = properties;
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
        return runtimeConfig();
    }

    @PostMapping("/admin/runtime-config")
    public Map<String, Object> updateRuntimeConfig(@RequestHeader("X-Admin-User-Id") String adminUserId,
                                                   @RequestBody RuntimeConfigUpdate request) {
        if (request.calculationEnabled() != null) {
            properties.getCalculation().setEnabled(request.calculationEnabled());
        }
        if (request.settlementEnabled() != null) {
            properties.getSettlement().setEnabled(request.settlementEnabled());
        }
        if (request.coordinationEnabled() != null) {
            properties.getCoordination().setEnabled(request.coordinationEnabled());
        }
        if (request.calculationPublishDelayMs() != null) {
            properties.getCalculation().setPublishDelayMs(nonNegative(request.calculationPublishDelayMs(), "calculationPublishDelayMs"));
        }
        if (request.settleDelayMs() != null) {
            properties.getSettlement().setSettleDelayMs(positive(request.settleDelayMs(), "settleDelayMs"));
        }
        if (request.settlementBatchSize() != null) {
            properties.getSettlement().setBatchSize(bounded(request.settlementBatchSize(), 1, 10_000, "settlementBatchSize"));
        }
        if (request.paymentPageSize() != null) {
            properties.getSettlement().setPaymentPageSize(
                    bounded(request.paymentPageSize(), 1, 10_000, "paymentPageSize"));
        }
        if (request.maxPagesPerRun() != null) {
            properties.getSettlement().setMaxPagesPerRun(
                    bounded(request.maxPagesPerRun(), 1, 1_000, "maxPagesPerRun"));
        }
        if (request.reconcileBatchSize() != null) {
            properties.getSettlement().setReconcileBatchSize(
                    bounded(request.reconcileBatchSize(), 1, 10_000, "reconcileBatchSize"));
        }
        return runtimeConfig();
    }

    private Map<String, Object> runtimeConfig() {
        Map<String, Object> calculation = new LinkedHashMap<>();
        calculation.put("enabled", properties.getCalculation().isEnabled());
        calculation.put("publishDelayMs", properties.getCalculation().getPublishDelayMs());
        calculation.put("maxMarkAge", properties.getCalculation().getMaxMarkAge().toString());

        Map<String, Object> settlement = new LinkedHashMap<>();
        settlement.put("enabled", properties.getSettlement().isEnabled());
        settlement.put("settleDelayMs", properties.getSettlement().getSettleDelayMs());
        settlement.put("batchSize", properties.getSettlement().getBatchSize());
        settlement.put("paymentPageSize", properties.getSettlement().getPaymentPageSize());
        settlement.put("maxPagesPerRun", properties.getSettlement().getMaxPagesPerRun());
        settlement.put("reconcileBatchSize", properties.getSettlement().getReconcileBatchSize());

        Map<String, Object> coordination = new LinkedHashMap<>();
        coordination.put("enabled", properties.getCoordination().isEnabled());
        coordination.put("nodeId", properties.getCoordination().getNodeId());
        coordination.put("leaseDuration", properties.getCoordination().getLeaseDuration().toString());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("scope", "runtime");
        response.put("calculation", calculation);
        response.put("settlement", settlement);
        response.put("coordination", coordination);
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

    private long positive(long value, String field) {
        if (value <= 0L) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " must be positive");
        }
        return value;
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
}
