package com.surprising.trading.order.controller;

import com.surprising.trading.api.TradingApiPaths;
import com.surprising.trading.api.model.EffectiveTradingFeeResponse;
import com.surprising.trading.api.model.FeeTierAssignmentResponse;
import com.surprising.trading.api.model.FeeTierQueryResponse;
import com.surprising.trading.api.model.FeeTierRefreshResponse;
import com.surprising.trading.api.model.FeeTierResponse;
import com.surprising.trading.api.model.FeeTierUpsertRequest;
import com.surprising.trading.api.model.FeeScheduleQueryResponse;
import com.surprising.trading.api.model.FeeScheduleResponse;
import com.surprising.trading.api.model.FeeScheduleStatus;
import com.surprising.trading.api.model.FeeScheduleUpsertRequest;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.order.service.FeeTierService;
import com.surprising.trading.order.service.TradingFeeService;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class TradingFeeController {

    private final TradingFeeService tradingFeeService;
    private final FeeTierService feeTierService;

    public TradingFeeController(TradingFeeService tradingFeeService, FeeTierService feeTierService) {
        this.tradingFeeService = tradingFeeService;
        this.feeTierService = feeTierService;
    }

    @GetMapping(TradingApiPaths.FEE_BASE_PATH + "/effective")
    public EffectiveTradingFeeResponse effective(@RequestParam("userId") long userId,
                                                 @RequestParam("symbol") String symbol,
                                                 @RequestParam(value = "instrumentVersion", defaultValue = "0")
                                                 long instrumentVersion,
                                                 @RequestHeader(value = "X-Product-Line", required = false)
                                                 String productLineHeader,
                                                 @RequestParam(value = "productLine", required = false)
                                                 String productLineValue) {
        try {
            return tradingFeeService.effectiveFee(userId, symbol, instrumentVersion,
                    productLine(productLineValue, productLineHeader));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        }
    }

    @PostMapping(TradingApiPaths.ADMIN_FEE_BASE_PATH + "/schedules")
    public FeeScheduleResponse upsert(@RequestBody FeeScheduleUpsertRequest request,
                                      @RequestHeader(value = "X-Product-Line", required = false)
                                      String productLineHeader,
                                      @RequestParam(value = "productLine", required = false)
                                      String productLineValue) {
        try {
            ProductLine productLine = productLine(productLineValue, productLineHeader);
            return tradingFeeService.upsertSchedule(withProductLine(request, productLine));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @PostMapping(TradingApiPaths.ADMIN_FEE_BASE_PATH + "/schedules/{feeScheduleId}/disable")
    public FeeScheduleResponse disable(@PathVariable("feeScheduleId") long feeScheduleId,
                                       @RequestHeader(value = "X-Product-Line", required = false)
                                       String productLineHeader,
                                       @RequestParam(value = "productLine", required = false)
                                       String productLineValue) {
        try {
            return tradingFeeService.disableSchedule(feeScheduleId, productLine(productLineValue, productLineHeader));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        }
    }

    @GetMapping(TradingApiPaths.ADMIN_FEE_BASE_PATH + "/schedules")
    public FeeScheduleQueryResponse query(@RequestParam(value = "userId", defaultValue = "0") long userId,
                                          @RequestHeader(value = "X-Product-Line", required = false)
                                          String productLineHeader,
                                          @RequestParam(value = "productLine", required = false)
                                          String productLineValue,
                                          @RequestParam(value = "symbol", required = false) String symbol,
                                          @RequestParam(value = "status", required = false)
                                          FeeScheduleStatus status,
                                          @RequestParam(value = "limit", defaultValue = "100") int limit,
                                          @RequestParam(value = "cursor", required = false) String cursor,
                                          @RequestParam(value = "sort", required = false) String sort) {
        try {
            return tradingFeeService.querySchedules(productLine(productLineValue, productLineHeader),
                    userId, symbol, status, limit, cursor, sort);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @PostMapping(TradingApiPaths.ADMIN_FEE_BASE_PATH + "/tiers")
    public FeeTierResponse upsertTier(@RequestBody FeeTierUpsertRequest request) {
        try {
            return feeTierService.upsertTier(request);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @GetMapping(TradingApiPaths.ADMIN_FEE_BASE_PATH + "/tiers")
    public FeeTierQueryResponse queryTiers(@RequestParam(value = "status", required = false)
                                           FeeScheduleStatus status,
                                           @RequestParam(value = "limit", defaultValue = "100") int limit,
                                           @RequestParam(value = "cursor", required = false) String cursor,
                                           @RequestParam(value = "sort", required = false) String sort) {
        try {
            return feeTierService.queryTiers(status, limit, cursor, sort);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @PostMapping(TradingApiPaths.ADMIN_FEE_BASE_PATH + "/tiers/refresh")
    public FeeTierAssignmentResponse refreshUserTier(@RequestParam("userId") long userId,
                                                     @RequestHeader(value = "X-Product-Line", required = false)
                                                     String productLineHeader,
                                                     @RequestParam(value = "productLine", required = false)
                                                     String productLineValue) {
        try {
            return feeTierService.refreshUserTier(productLine(productLineValue, productLineHeader), userId);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @PostMapping(TradingApiPaths.ADMIN_FEE_BASE_PATH + "/tiers/refresh-active")
    public FeeTierRefreshResponse refreshActiveUserTiers(@RequestParam(value = "limit", defaultValue = "1000")
                                                         int limit,
                                                         @RequestHeader(value = "X-Product-Line", required = false)
                                                         String productLineHeader,
                                                         @RequestParam(value = "productLine", required = false)
                                                         String productLineValue) {
        try {
            return feeTierService.refreshActiveUserTiers(productLine(productLineValue, productLineHeader), limit);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @GetMapping(TradingApiPaths.ADMIN_FEE_BASE_PATH + "/tiers/users/{userId}")
    public FeeTierAssignmentResponse currentUserTier(@PathVariable("userId") long userId,
                                                    @RequestHeader(value = "X-Product-Line", required = false)
                                                    String productLineHeader,
                                                    @RequestParam(value = "productLine", required = false)
                                                    String productLineValue) {
        try {
            return feeTierService.currentUserTier(productLine(productLineValue, productLineHeader), userId);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    private FeeScheduleUpsertRequest withProductLine(FeeScheduleUpsertRequest request, ProductLine productLine) {
        if (request == null || request.productLine() != null || productLine == null) {
            return request;
        }
        return new FeeScheduleUpsertRequest(
                request.feeScheduleId(),
                productLine,
                request.userId(),
                request.symbol(),
                request.makerFeeRatePpm(),
                request.takerFeeRatePpm(),
                request.sourceType(),
                request.tierCode(),
                request.reason(),
                request.status(),
                request.effectiveTime(),
                request.expireTime());
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
