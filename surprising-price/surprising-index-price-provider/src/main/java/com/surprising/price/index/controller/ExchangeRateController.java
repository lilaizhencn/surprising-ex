package com.surprising.price.index.controller;

import com.surprising.price.api.client.ExchangeRateRpcApi;
import com.surprising.price.api.model.ExchangeRateConvertResponse;
import com.surprising.price.api.model.ExchangeRateQueryResponse;
import com.surprising.price.api.model.ExchangeRateResponse;
import com.surprising.price.index.service.ExchangeRateService;
import java.math.BigDecimal;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class ExchangeRateController implements ExchangeRateRpcApi {

    private final ExchangeRateService exchangeRateService;

    public ExchangeRateController(ExchangeRateService exchangeRateService) {
        this.exchangeRateService = exchangeRateService;
    }

    @Override
    public ExchangeRateResponse latest(String baseCurrency, String quoteCurrency) {
        try {
            return exchangeRateService.latest(baseCurrency, quoteCurrency);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        }
    }

    @Override
    public ExchangeRateQueryResponse rates(String baseCurrency) {
        try {
            return exchangeRateService.rates(baseCurrency);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @Override
    public ExchangeRateConvertResponse convert(BigDecimal amount, String fromCurrency, String toCurrency) {
        try {
            return exchangeRateService.convert(amount, fromCurrency, toCurrency);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        }
    }
}
