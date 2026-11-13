package com.surprising.realtime.provider.option.controller;

import com.surprising.realtime.provider.option.service.OptionQuoteService;
import com.surprising.realtime.provider.option.model.OptionQuote;
import com.surprising.realtime.provider.option.exception.OptionQuoteException;
import com.surprising.product.api.utils.InstrumentIds;

import com.surprising.candlestick.api.CandlestickApiPaths;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** 沿用现有 realtime 市场数据路由，不经过交易 Core。 */
@RestController
@ConditionalOnProperty(name = "surprising.candlestick.kafka.product-line", havingValue = "OPTION")
public final class OptionQuoteController {
    private final OptionQuoteService quotes;
    public OptionQuoteController(OptionQuoteService quotes) { this.quotes = quotes; }

    @GetMapping(CandlestickApiPaths.BASE_PATH + "/options/quote")
    public OptionQuote quote(@RequestParam String instrumentId,
            @RequestHeader(value = "X-Product-Line", required = false) String productLine) {
        if (productLine != null && !"OPTION".equals(productLine))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "product line is not enabled here");
        final int id;
        try { id = InstrumentIds.parse(instrumentId); }
        catch (IllegalArgumentException invalid) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid instrumentId", invalid); }
        try { return quotes.quote(id); }
        catch (OptionQuoteException failure) {
            HttpStatus status = switch (failure.reason()) {
                case INSTRUMENT_NOT_FOUND -> HttpStatus.NOT_FOUND;
                case REFERENCE_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            };
            throw new ResponseStatusException(status, failure.getMessage(), failure);
        }
    }
}
