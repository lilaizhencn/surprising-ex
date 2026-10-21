package com.surprising.funding.provider.controller;

import com.surprising.funding.api.FundingApiPaths;
import com.surprising.funding.api.utils.AdminCursorPage;
import com.surprising.funding.provider.model.FundingPageQuery;
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
    public FundingRateResponse latestRate(@RequestParam String instrumentId) {
        return fundingService.latestRate(instrumentId);
    }

    @GetMapping("/rates/history")
    public FundingRateQueryResponse rateHistory(@RequestParam String instrumentId,
                                                @RequestParam(defaultValue = "100") int limit) {
        try {
            return fundingService.rateHistory(instrumentId(instrumentId), pageQuery(limit, null, null, "eventTime"));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @GetMapping("/settlements/latest")
    public FundingSettlementResponse latestSettlement(@RequestParam String instrumentId) {
        try {
            return fundingService.latestSettlement(instrumentId);
        } catch (java.util.NoSuchElementException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        }
    }

    @GetMapping("/payments")
    public FundingPaymentQueryResponse payments(@RequestParam long userId,
                                                @RequestParam(required = false) String instrumentId,
                                                @RequestParam(defaultValue = "100") int limit,
                                                @RequestParam(required = false) String cursor,
                                                @RequestParam(required = false) String sort) {
        try {
            if (userId <= 0) throw new IllegalArgumentException("userId must be positive");
            String id = instrumentId == null || instrumentId.isBlank() ? null : instrumentId(instrumentId);
            return fundingService.payments(userId, id, pageQuery(limit, cursor, sort, "createdAt"));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @GetMapping("/admin/rates/latest")
    public FundingRateResponse adminLatestRate(@RequestHeader("X-Admin-User-Id") String adminUserId,
                                               @RequestParam String instrumentId) {
        return latestRate(instrumentId);
    }

    @GetMapping("/admin/rates/history")
    public FundingRateQueryResponse adminRateHistory(
            @RequestHeader("X-Admin-User-Id") String adminUserId,
            @RequestParam String instrumentId,
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) String sort) {
        try {
            return fundingService.rateHistory(instrumentId(instrumentId), pageQuery(limit, cursor, sort, "eventTime"));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @GetMapping("/admin/settlements/latest")
    public FundingSettlementResponse adminLatestSettlement(@RequestHeader("X-Admin-User-Id") String adminUserId,
                                                           @RequestParam String instrumentId) {
        return latestSettlement(instrumentId);
    }

    @GetMapping("/admin/payments")
    public FundingPaymentQueryResponse adminPayments(
            @RequestHeader("X-Admin-User-Id") String adminUserId,
            @RequestParam long userId,
            @RequestParam(required = false) String instrumentId,
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) String sort) {
        return payments(userId, instrumentId, limit, cursor, sort);
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
            if (request == null || request.expectedVersion() == null || request.expectedVersion() < 1
                    || adminUserId == null || !adminUserId.matches("[1-9][0-9]*") || request.reason() == null
                    || request.reason().isBlank() || request.reason().length() > 1000)
                throw new IllegalArgumentException("expectedVersion, administrator and reason (1-1000 characters) are required");
            if (request.paymentPageSize() != null || request.reconcileBatchSize() != null)
                throw new IllegalArgumentException("paymentPageSize and reconcileBatchSize are not runtime business controls");
            return runtimeConfigService.update(
                    adminUserId, request.expectedVersion(),
                    request.calculationEnabled(),
                    request.settlementEnabled(),
                    request.coordinationEnabled(),
                    request.calculationPublishDelayMs(),
                    request.settleDelayMs(),
                    request.settlementBatchSize(),
                    request.maxPagesPerRun(),
                    request.maxMarkAgeMs(), request.maxRateAgeMs(), request.leaseDurationMs(), request.reason().trim());
        } catch (org.springframework.dao.OptimisticLockingFailureException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage(), ex);
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
            Integer reconcileBatchSize,
            Long expectedVersion, Long maxMarkAgeMs, Long maxRateAgeMs, Long leaseDurationMs, String reason) {
    }

    private static FundingPageQuery pageQuery(int limit, String cursor, String sort, String field) {
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("limit must be in [1, 1000]");
        String token = sort == null || sort.isBlank() ? field + ".desc" : sort.trim();
        if (!token.equals(field + ".asc") && !token.equals(field + ".desc"))
            throw new IllegalArgumentException("unsupported sort: " + sort);
        return new FundingPageQuery(limit, token.endsWith(".desc"), AdminCursorPage.decodeCursor(cursor));
    }

    private static String instrumentId(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("instrumentId is required");
        String normalized = value.trim().toUpperCase(java.util.Locale.ROOT);
        if (!com.surprising.product.api.utils.InstrumentIds.valid(normalized))
            throw new IllegalArgumentException("invalid instrumentId: " + value);
        return normalized;
    }

    private void requireAdmin(String adminUserId) {
        if (adminUserId == null || adminUserId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "admin identity header is required");
        }
    }
}
