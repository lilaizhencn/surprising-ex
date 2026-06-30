package com.surprising.candlestick.provider.controller;

import com.surprising.candlestick.api.client.CandlestickRpcApi;
import com.surprising.candlestick.api.model.CandleQueryResponse;
import com.surprising.candlestick.api.model.CandleResponse;
import com.surprising.candlestick.provider.service.CandleQueryService;
import jakarta.validation.Valid;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@Validated
@RestController
public class CandlestickController implements CandlestickRpcApi {

    private final CandleQueryService candleQueryService;

    public CandlestickController(CandleQueryService candleQueryService) {
        this.candleQueryService = candleQueryService;
    }

    @Override
    public CandleQueryResponse queryCandles(String symbol, String period, @Valid Instant startTime,
                                            @Valid Instant endTime, int limit) {
        try {
            return candleQueryService.query(symbol, period, startTime, endTime, limit);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @Override
    public CandleResponse latestCandle(String symbol, String period) {
        try {
            return candleQueryService.latest(symbol, period)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "candle not found"));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }
}
