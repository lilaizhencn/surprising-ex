package com.surprising.trading.order.controller;

import com.surprising.trading.api.TradingApiPaths;
import com.surprising.trading.api.model.LeverageSettingRequest;
import com.surprising.trading.api.model.LeverageSettingResponse;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.order.service.LeverageService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class LeverageController {

    private final LeverageService leverageService;

    public LeverageController(LeverageService leverageService) {
        this.leverageService = leverageService;
    }

    @PostMapping(TradingApiPaths.LEVERAGE_BASE_PATH + "/settings")
    public LeverageSettingResponse set(@RequestBody LeverageSettingRequest request) {
        try {
            return leverageService.set(request);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        }
    }

    @GetMapping(TradingApiPaths.LEVERAGE_BASE_PATH + "/settings")
    public LeverageSettingResponse get(@RequestParam("userId") long userId,
                                       @RequestParam("symbol") String symbol,
                                       @RequestParam(value = "marginMode", required = false) MarginMode marginMode) {
        try {
            return leverageService.get(userId, symbol, marginMode);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        }
    }
}
