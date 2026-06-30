package com.surprising.price.index.controller;

import com.surprising.price.api.client.IndexPriceRpcApi;
import com.surprising.price.api.model.IndexPriceQueryResponse;
import com.surprising.price.api.model.IndexPriceResponse;
import com.surprising.price.index.repository.IndexPriceRepository;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class IndexPriceController implements IndexPriceRpcApi {

    private final IndexPriceRepository indexPriceRepository;

    public IndexPriceController(IndexPriceRepository indexPriceRepository) {
        this.indexPriceRepository = indexPriceRepository;
    }

    @Override
    public IndexPriceResponse latestIndexPrice(String symbol) {
        return indexPriceRepository.latest(normalizeSymbol(symbol))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "index price not found"));
    }

    @Override
    public IndexPriceQueryResponse history(String symbol, Instant startTime, Instant endTime, int limit) {
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
