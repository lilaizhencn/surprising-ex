package com.surprising.trading.order.controller;

import com.surprising.trading.api.TradingApiPaths;
import com.surprising.trading.api.model.EffectiveTradingFeeResponse;
import com.surprising.trading.api.model.FeeScheduleQueryResponse;
import com.surprising.trading.api.model.FeeScheduleResponse;
import com.surprising.trading.api.model.FeeScheduleStatus;
import com.surprising.trading.api.model.FeeScheduleUpsertRequest;
import com.surprising.trading.order.service.TradingFeeService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class TradingFeeController {

    private final TradingFeeService tradingFeeService;

    public TradingFeeController(TradingFeeService tradingFeeService) {
        this.tradingFeeService = tradingFeeService;
    }

    @GetMapping(TradingApiPaths.FEE_BASE_PATH + "/effective")
    public EffectiveTradingFeeResponse effective(@RequestParam("userId") long userId,
                                                 @RequestParam("symbol") String symbol,
                                                 @RequestParam(value = "instrumentVersion", defaultValue = "0")
                                                 long instrumentVersion) {
        try {
            return tradingFeeService.effectiveFee(userId, symbol, instrumentVersion);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        }
    }

    @PostMapping(TradingApiPaths.ADMIN_FEE_BASE_PATH + "/schedules")
    public FeeScheduleResponse upsert(@RequestBody FeeScheduleUpsertRequest request) {
        try {
            return tradingFeeService.upsertSchedule(request);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @PostMapping(TradingApiPaths.ADMIN_FEE_BASE_PATH + "/schedules/{feeScheduleId}/disable")
    public FeeScheduleResponse disable(@PathVariable("feeScheduleId") long feeScheduleId) {
        try {
            return tradingFeeService.disableSchedule(feeScheduleId);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        }
    }

    @GetMapping(TradingApiPaths.ADMIN_FEE_BASE_PATH + "/schedules")
    public FeeScheduleQueryResponse query(@RequestParam(value = "userId", defaultValue = "0") long userId,
                                          @RequestParam(value = "symbol", required = false) String symbol,
                                          @RequestParam(value = "status", required = false)
                                          FeeScheduleStatus status,
                                          @RequestParam(value = "limit", defaultValue = "100") int limit) {
        try {
            return tradingFeeService.querySchedules(userId, symbol, status, limit);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }
}
