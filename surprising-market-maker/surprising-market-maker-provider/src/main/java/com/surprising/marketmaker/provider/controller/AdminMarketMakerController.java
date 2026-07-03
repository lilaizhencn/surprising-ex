package com.surprising.marketmaker.provider.controller;

import com.surprising.marketmaker.api.model.MarketMakerRunRequest;
import com.surprising.marketmaker.api.model.MarketMakerStrategyQueryResponse;
import com.surprising.marketmaker.api.model.MarketMakerStrategyResponse;
import com.surprising.marketmaker.provider.service.MarketMakerService;
import com.surprising.marketmaker.provider.service.MarketMakerService.MarketMakerAdminMetricsResponse;
import com.surprising.marketmaker.provider.service.MarketMakerService.MarketMakerPnlAttributionResponse;
import com.surprising.marketmaker.provider.service.MarketMakerService.MarketMakerRunLogQueryResponse;
import com.surprising.marketmaker.provider.service.MarketMakerService.MarketMakerStrategyConfigResponse;
import com.surprising.marketmaker.provider.service.MarketMakerService.MarketMakerStrategyConfigUpdateRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/admin/market-maker")
public class AdminMarketMakerController {

    private final MarketMakerService marketMakerService;

    public AdminMarketMakerController(MarketMakerService marketMakerService) {
        this.marketMakerService = marketMakerService;
    }

    @GetMapping("/strategies")
    public MarketMakerStrategyQueryResponse strategies(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId) {
        requireAdmin(adminUserId);
        return marketMakerService.strategies();
    }

    @GetMapping("/strategies/{strategyId}")
    public MarketMakerStrategyResponse strategy(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @PathVariable("strategyId") String strategyId) {
        requireAdmin(adminUserId);
        try {
            return marketMakerService.strategy(strategyId);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        }
    }

    @GetMapping("/metrics")
    public MarketMakerAdminMetricsResponse metrics(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestParam(value = "limit", defaultValue = "200") int limit) {
        requireAdmin(adminUserId);
        return marketMakerService.adminMetrics(limit);
    }

    @GetMapping("/strategy-logs")
    public MarketMakerRunLogQueryResponse strategyLogs(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestParam(value = "strategyId", required = false) String strategyId,
            @RequestParam(value = "symbol", required = false) String symbol,
            @RequestParam(value = "accountId", required = false) Long accountId,
            @RequestParam(value = "eventType", required = false) String eventType,
            @RequestParam(value = "limit", defaultValue = "200") int limit,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "sort", required = false) String sort) {
        requireAdmin(adminUserId);
        return marketMakerService.runLogs(strategyId, symbol, accountId, eventType, limit, cursor, sort);
    }

    @GetMapping("/pnl-attribution")
    public MarketMakerPnlAttributionResponse pnlAttribution(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestParam(value = "strategyId", required = false) String strategyId,
            @RequestParam(value = "symbol", required = false) String symbol,
            @RequestParam(value = "accountId", required = false) Long accountId,
            @RequestParam(value = "windowHours", defaultValue = "24") int windowHours,
            @RequestParam(value = "limit", defaultValue = "100") int limit) {
        requireAdmin(adminUserId);
        return marketMakerService.pnlAttribution(strategyId, symbol, accountId, windowHours, limit);
    }

    @GetMapping("/strategies/{strategyId}/config")
    public MarketMakerStrategyConfigResponse strategyConfig(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @PathVariable("strategyId") String strategyId) {
        requireAdmin(adminUserId);
        try {
            return marketMakerService.strategyConfig(strategyId);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        }
    }

    @PostMapping("/strategies/{strategyId}/config")
    public MarketMakerStrategyConfigResponse updateStrategyConfig(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @PathVariable("strategyId") String strategyId,
            @RequestBody(required = false) MarketMakerStrategyConfigUpdateRequest request) {
        requireAdmin(adminUserId);
        try {
            return marketMakerService.updateStrategyConfig(strategyId, request, adminUserId);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @PostMapping("/strategies/{strategyId}/pause")
    public MarketMakerStrategyResponse pause(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @PathVariable("strategyId") String strategyId) {
        requireAdmin(adminUserId);
        try {
            return marketMakerService.pause(strategyId);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        }
    }

    @PostMapping("/strategies/{strategyId}/resume")
    public MarketMakerStrategyResponse resume(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @PathVariable("strategyId") String strategyId) {
        requireAdmin(adminUserId);
        try {
            return marketMakerService.resume(strategyId);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        }
    }

    @PostMapping("/run-once")
    public MarketMakerStrategyQueryResponse runOnce(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestBody(required = false) MarketMakerRunRequest request) {
        requireAdmin(adminUserId);
        try {
            return marketMakerService.runOnce(request == null ? new MarketMakerRunRequest(null, null) : request);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    private void requireAdmin(String adminUserId) {
        if (adminUserId == null || adminUserId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "admin identity header is required");
        }
    }
}
