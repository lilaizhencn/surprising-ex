package com.surprising.price.index.controller;

import com.surprising.price.api.PriceApiPaths;
import com.surprising.price.api.model.IndexPriceQueryResponse;
import com.surprising.price.api.model.IndexPriceResponse;
import com.surprising.price.index.service.IndexPriceQueryService;
import com.surprising.price.index.service.LatestIndexPriceCache;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class IndexPriceController {

    private final IndexPriceQueryService indexPriceQueryService;
    private final LatestIndexPriceCache latestIndexPriceCache;

    public IndexPriceController(IndexPriceQueryService indexPriceQueryService,
                                LatestIndexPriceCache latestIndexPriceCache) {
        this.indexPriceQueryService = indexPriceQueryService;
        this.latestIndexPriceCache = latestIndexPriceCache;
    }

    @GetMapping(PriceApiPaths.INDEX_BASE_PATH + "/latest")
    public IndexPriceResponse latestIndexPrice(@RequestParam("instrumentId") String instrumentId) {
        try {
            return latestIndexPriceCache.requireFresh(normalizeSymbol(instrumentId));
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage(), ex);
        }
    }

    @GetMapping(PriceApiPaths.INDEX_BASE_PATH + "/history")
    public IndexPriceQueryResponse history(@RequestParam("instrumentId") String instrumentId,
                                           @RequestParam("startTime")
                                           @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant startTime,
                                           @RequestParam("endTime")
                                           @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant endTime,
                                           @RequestParam(value = "limit", defaultValue = "500") int limit) {
        validateRange(startTime, endTime);
        String normalized = normalizeSymbol(instrumentId);
        int safeLimit = Math.min(limit, 5000);
        return new IndexPriceQueryResponse(normalized, safeLimit,
                indexPriceQueryService.history(normalized, startTime, endTime, safeLimit));
    }

    private String normalizeSymbol(String instrumentId) {
        if (instrumentId == null || !com.surprising.product.api.InstrumentIds.valid(instrumentId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid instrumentId");
        }
        return instrumentId;
    }

    private void validateRange(Instant startTime, Instant endTime) {
        if (startTime == null || endTime == null || !startTime.isBefore(endTime)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid time range");
        }
    }
}
