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
import com.surprising.product.api.ProductLine;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
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
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestHeader(value = "X-Product-Line", required = false) String productLineHeader,
            @RequestParam(value = "productLine", required = false) String productLineValue) {
        requireAdmin(adminUserId);
        return marketMakerService.strategies(productLine(productLineValue, productLineHeader));
    }

    @GetMapping("/strategies/{strategyId}")
    public MarketMakerStrategyResponse strategy(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestHeader(value = "X-Product-Line", required = false) String productLineHeader,
            @RequestParam(value = "productLine", required = false) String productLineValue,
            @PathVariable("strategyId") String strategyId) {
        requireAdmin(adminUserId);
        try {
            return marketMakerService.strategy(strategyId, productLine(productLineValue, productLineHeader));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        }
    }

    @GetMapping("/metrics")
    public MarketMakerAdminMetricsResponse metrics(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestHeader(value = "X-Product-Line", required = false) String productLineHeader,
            @RequestParam(value = "productLine", required = false) String productLineValue,
            @RequestParam(value = "limit", defaultValue = "200") int limit) {
        requireAdmin(adminUserId);
        return marketMakerService.adminMetrics(limit, productLine(productLineValue, productLineHeader));
    }

    @GetMapping("/strategy-logs")
    public MarketMakerRunLogQueryResponse strategyLogs(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestHeader(value = "X-Product-Line", required = false) String productLineHeader,
            @RequestParam(value = "productLine", required = false) String productLineValue,
            @RequestParam(value = "strategyId", required = false) String strategyId,
            @RequestParam(value = "symbol", required = false) String symbol,
            @RequestParam(value = "accountId", required = false) Long accountId,
            @RequestParam(value = "eventType", required = false) String eventType,
            @RequestParam(value = "limit", defaultValue = "200") int limit,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "sort", required = false) String sort) {
        requireAdmin(adminUserId);
        return marketMakerService.runLogs(productLine(productLineValue, productLineHeader),
                strategyId, symbol, accountId, eventType, limit, cursor, sort);
    }

    @GetMapping("/pnl-attribution")
    public MarketMakerPnlAttributionResponse pnlAttribution(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestHeader(value = "X-Product-Line", required = false) String productLineHeader,
            @RequestParam(value = "productLine", required = false) String productLineValue,
            @RequestParam(value = "strategyId", required = false) String strategyId,
            @RequestParam(value = "symbol", required = false) String symbol,
            @RequestParam(value = "accountId", required = false) Long accountId,
            @RequestParam(value = "windowHours", defaultValue = "24") int windowHours,
            @RequestParam(value = "limit", defaultValue = "100") int limit) {
        requireAdmin(adminUserId);
        return marketMakerService.pnlAttribution(productLine(productLineValue, productLineHeader),
                strategyId, symbol, accountId, windowHours, limit);
    }

    @GetMapping("/strategies/{strategyId}/config")
    public MarketMakerStrategyConfigResponse strategyConfig(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestHeader(value = "X-Product-Line", required = false) String productLineHeader,
            @RequestParam(value = "productLine", required = false) String productLineValue,
            @PathVariable("strategyId") String strategyId) {
        requireAdmin(adminUserId);
        try {
            return marketMakerService.strategyConfig(strategyId, productLine(productLineValue, productLineHeader));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        }
    }

    @PostMapping("/strategies/{strategyId}/config")
    public MarketMakerStrategyConfigResponse updateStrategyConfig(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestHeader(value = "X-Product-Line", required = false) String productLineHeader,
            @RequestParam(value = "productLine", required = false) String productLineValue,
            @PathVariable("strategyId") String strategyId,
            @RequestBody(required = false) MarketMakerStrategyConfigUpdateRequest request) {
        requireAdmin(adminUserId);
        try {
            return marketMakerService.updateStrategyConfig(strategyId, productLine(productLineValue, productLineHeader),
                    request, adminUserId);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @PostMapping("/strategies/{strategyId}/pause")
    public MarketMakerStrategyResponse pause(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestHeader(value = "X-Product-Line", required = false) String productLineHeader,
            @RequestParam(value = "productLine", required = false) String productLineValue,
            @PathVariable("strategyId") String strategyId) {
        requireAdmin(adminUserId);
        try {
            return marketMakerService.pause(strategyId, productLine(productLineValue, productLineHeader));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        }
    }

    @PostMapping("/strategies/{strategyId}/resume")
    public MarketMakerStrategyResponse resume(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestHeader(value = "X-Product-Line", required = false) String productLineHeader,
            @RequestParam(value = "productLine", required = false) String productLineValue,
            @PathVariable("strategyId") String strategyId) {
        requireAdmin(adminUserId);
        try {
            return marketMakerService.resume(strategyId, productLine(productLineValue, productLineHeader));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        }
    }

    @PostMapping("/run-once")
    public MarketMakerStrategyQueryResponse runOnce(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestHeader(value = "X-Product-Line", required = false) String productLineHeader,
            @RequestParam(value = "productLine", required = false) String productLineValue,
            @RequestBody(required = false) MarketMakerRunRequest request) {
        requireAdmin(adminUserId);
        try {
            ProductLine productLine = productLine(productLineValue, productLineHeader);
            MarketMakerRunRequest safeRequest = request == null
                    ? new MarketMakerRunRequest(null, null, productLine)
                    : new MarketMakerRunRequest(request.strategyId(), request.symbol(),
                    request.productLine() == null ? productLine : request.productLine());
            return marketMakerService.runOnce(safeRequest);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    private void requireAdmin(String adminUserId) {
        if (adminUserId == null || adminUserId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "admin identity header is required");
        }
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public void handleBadRequest(IllegalArgumentException ex) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
    }

    private ProductLine productLine(String queryValue, String headerValue) {
        String value = queryValue == null || queryValue.isBlank() ? headerValue : queryValue;
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        ProductLine byAccountType = ProductLine.fromAccountTypeCode(normalized).orElse(null);
        if (byAccountType != null) {
            return byAccountType;
        }
        ProductLine byContractType = ProductLine.fromContractTypeCode(normalized).orElse(null);
        if (byContractType != null) {
            return byContractType;
        }
        String enumName = normalized.replace('-', '_');
        for (ProductLine productLine : ProductLine.values()) {
            if (productLine.name().equals(enumName) || productLine.topicSegment().equalsIgnoreCase(value.trim())) {
                return productLine;
            }
        }
        throw new IllegalArgumentException("unsupported productLine: " + value);
    }
}
