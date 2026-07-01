package com.surprising.price.index.controller;

import com.surprising.price.api.PriceApiPaths;
import com.surprising.price.api.model.IndexPriceQueryResponse;
import com.surprising.price.api.model.IndexPriceResponse;
import com.surprising.price.index.repository.IndexPriceRepository;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class IndexPriceController {

    private final IndexPriceRepository indexPriceRepository;

    public IndexPriceController(IndexPriceRepository indexPriceRepository) {
        this.indexPriceRepository = indexPriceRepository;
    }

    @GetMapping(PriceApiPaths.INDEX_BASE_PATH + "/latest")
    public IndexPriceResponse latestIndexPrice(@RequestParam("symbol") String symbol) {
        return indexPriceRepository.latest(normalizeSymbol(symbol))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "index price not found"));
    }

    @GetMapping(PriceApiPaths.INDEX_BASE_PATH + "/history")
    public IndexPriceQueryResponse history(@RequestParam("symbol") String symbol,
                                           @RequestParam("startTime")
                                           @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant startTime,
                                           @RequestParam("endTime")
                                           @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant endTime,
                                           @RequestParam(value = "limit", defaultValue = "500") int limit) {
        validateRange(startTime, endTime);
        String normalized = normalizeSymbol(symbol);
        int safeLimit = Math.min(limit, 5000);
        return new IndexPriceQueryResponse(normalized, safeLimit,
                indexPriceRepository.history(normalized, startTime, endTime, safeLimit));
    }

    private String normalizeSymbol(String symbol) {
        if (symbol == null || !symbol.matches("[A-Z0-9][A-Z0-9_-]{1,63}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid symbol");
        }
        return symbol;
    }

    private void validateRange(Instant startTime, Instant endTime) {
        if (startTime == null || endTime == null || !startTime.isBefore(endTime)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid time range");
        }
    }
}
