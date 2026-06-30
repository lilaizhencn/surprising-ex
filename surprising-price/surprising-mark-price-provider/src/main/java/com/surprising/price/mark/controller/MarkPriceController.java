package com.surprising.price.mark.controller;

import com.surprising.price.api.client.MarkPriceRpcApi;
import com.surprising.price.api.model.MarkPriceQueryResponse;
import com.surprising.price.api.model.MarkPriceResponse;
import com.surprising.price.mark.repository.MarkPriceRepository;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class MarkPriceController implements MarkPriceRpcApi {

    private final MarkPriceRepository markPriceRepository;

    public MarkPriceController(MarkPriceRepository markPriceRepository) {
        this.markPriceRepository = markPriceRepository;
    }

    @Override
    public MarkPriceResponse latestMarkPrice(String symbol) {
        return markPriceRepository.latest(normalizeSymbol(symbol))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "mark price not found"));
    }

    @Override
    public MarkPriceQueryResponse history(String symbol, Instant startTime, Instant endTime, int limit) {
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

    private void validateRange(Instant startTime, Instant endTime) {
        if (startTime == null || endTime == null || !startTime.isBefore(endTime)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid time range");
        }
    }
}
