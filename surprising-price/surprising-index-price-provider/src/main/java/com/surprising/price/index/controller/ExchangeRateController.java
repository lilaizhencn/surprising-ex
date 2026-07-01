package com.surprising.price.index.controller;

import com.surprising.price.api.PriceApiPaths;
import com.surprising.price.api.model.ExchangeRateConvertResponse;
import com.surprising.price.api.model.ExchangeRateQueryResponse;
import com.surprising.price.api.model.ExchangeRateResponse;
import com.surprising.price.index.service.ExchangeRateService;
import java.math.BigDecimal;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class ExchangeRateController {

    private final ExchangeRateService exchangeRateService;

    public ExchangeRateController(ExchangeRateService exchangeRateService) {
        this.exchangeRateService = exchangeRateService;
    }

    @GetMapping(PriceApiPaths.EXCHANGE_RATE_BASE_PATH + "/latest")
    public ExchangeRateResponse latest(@RequestParam("baseCurrency") String baseCurrency,
                                       @RequestParam("quoteCurrency") String quoteCurrency) {
        try {
            return exchangeRateService.latest(baseCurrency, quoteCurrency);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        }
    }

    @GetMapping(PriceApiPaths.EXCHANGE_RATE_BASE_PATH + "/rates")
    public ExchangeRateQueryResponse rates(@RequestParam("baseCurrency") String baseCurrency) {
        try {
            return exchangeRateService.rates(baseCurrency);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @GetMapping(PriceApiPaths.EXCHANGE_RATE_BASE_PATH + "/convert")
    public ExchangeRateConvertResponse convert(@RequestParam("amount") BigDecimal amount,
                                               @RequestParam("fromCurrency") String fromCurrency,
                                               @RequestParam("toCurrency") String toCurrency) {
        try {
            return exchangeRateService.convert(amount, fromCurrency, toCurrency);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        }
    }
}
