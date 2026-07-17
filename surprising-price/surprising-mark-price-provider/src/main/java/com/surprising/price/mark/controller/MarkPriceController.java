package com.surprising.price.mark.controller;

import com.surprising.price.api.PriceApiPaths;
import com.surprising.price.api.model.MarkPriceQueryResponse;
import com.surprising.price.api.model.MarkPriceResponse;
import com.surprising.price.api.model.MarkPriceEvent;
import com.surprising.price.consumer.LatestMarkPriceCache;
import com.surprising.price.mark.repository.MarkPriceRepository;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class MarkPriceController {

    private final MarkPriceRepository markPriceRepository;
    private final LatestMarkPriceCache markPriceCache;

    public MarkPriceController(MarkPriceRepository markPriceRepository, LatestMarkPriceCache markPriceCache) {
        this.markPriceRepository = markPriceRepository;
        this.markPriceCache = markPriceCache;
    }

    @GetMapping(PriceApiPaths.MARK_BASE_PATH + "/latest")
    public MarkPriceResponse latestMarkPrice(@RequestParam("symbol") String symbol) {
        try {
            return toResponse(markPriceCache.requireFresh(normalizeSymbol(symbol)));
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage(), ex);
        }
    }

    @GetMapping(PriceApiPaths.MARK_BASE_PATH + "/history")
    public MarkPriceQueryResponse history(@RequestParam("symbol") String symbol,
                                          @RequestParam("startTime")
                                          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant startTime,
                                          @RequestParam("endTime")
                                          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant endTime,
                                          @RequestParam(value = "limit", defaultValue = "500") int limit) {
        validateRange(startTime, endTime);
        String normalized = normalizeSymbol(symbol);
        int safeLimit = Math.min(limit, 5000);
        return new MarkPriceQueryResponse(normalized, safeLimit,
                markPriceRepository.history(normalized, startTime, endTime, safeLimit));
    }

    private String normalizeSymbol(String symbol) {
        if (symbol == null || !symbol.matches("[A-Z0-9][A-Z0-9_-]{1,63}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid symbol");
        }
        return symbol;
    }

    private MarkPriceResponse toResponse(MarkPriceEvent event) {
        return new MarkPriceResponse(event.symbol(), event.markPrice(), event.markPriceUnits(), event.indexPrice(),
                event.price1(), event.price2(), event.lastTradePrice(), event.bestBidPrice(), event.bestAskPrice(),
                event.fundingRate(), event.nextFundingTime(), event.timeUntilFundingSeconds(), event.basisAverage(),
                event.basisWindowSeconds(), event.clampLow(), event.clampHigh(), event.sequence(), event.status(),
                event.eventTime());
    }

    private void validateRange(Instant startTime, Instant endTime) {
        if (startTime == null || endTime == null || !startTime.isBefore(endTime)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid time range");
        }
    }
}
