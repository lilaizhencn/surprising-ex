package com.surprising.price.mark.controller;

import com.surprising.price.api.PriceApiPaths;
import com.surprising.price.api.model.MarkPriceQueryResponse;
import com.surprising.price.api.model.MarkPriceResponse;
import com.surprising.price.mark.service.MarkPriceQueryService;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class MarkPriceController {

    private final MarkPriceQueryService queryService;
    private final com.surprising.price.mark.service.MarkPriceService prices;

    public MarkPriceController(MarkPriceQueryService queryService, com.surprising.price.mark.service.MarkPriceService prices) {
        this.queryService = queryService;
        this.prices = prices;
    }

    @GetMapping(PriceApiPaths.MARK_BASE_PATH + "/latest")
    public MarkPriceResponse latestMarkPrice(@RequestParam("instrumentId") String instrumentId) {
        try {
            return queryService.latest(instrumentId);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage(), ex);
        }
    }

    @GetMapping(PriceApiPaths.MARK_BASE_PATH + "/inputs")
    public com.surprising.price.mark.service.MarkPriceService.MarketInputs inputs(@RequestParam("instrumentId") String instrumentId) {
        if (!com.surprising.product.api.InstrumentIds.valid(instrumentId))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid instrumentId");
        return prices.inputs(instrumentId);
    }

    @GetMapping(PriceApiPaths.MARK_BASE_PATH + "/history")
    public MarkPriceQueryResponse history(@RequestParam("instrumentId") String instrumentId,
                                          @RequestParam("startTime")
                                          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant startTime,
                                          @RequestParam("endTime")
                                          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant endTime,
                                          @RequestParam(value = "limit", defaultValue = "500") int limit) {
        try {
            return queryService.history(instrumentId, startTime, endTime, limit);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }
}
