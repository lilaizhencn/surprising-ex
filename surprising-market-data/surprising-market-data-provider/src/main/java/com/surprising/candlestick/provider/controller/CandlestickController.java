package com.surprising.candlestick.provider.controller;

import com.surprising.candlestick.api.CandlestickApiPaths;
import com.surprising.candlestick.api.model.CandleQueryResponse;
import com.surprising.candlestick.api.model.CandleResponse;
import com.surprising.candlestick.provider.service.CandleQueryService;
import jakarta.validation.Valid;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@Validated
@RestController
public class CandlestickController {

    private final CandleQueryService candleQueryService;

    public CandlestickController(CandleQueryService candleQueryService) {
        this.candleQueryService = candleQueryService;
    }

    @GetMapping(CandlestickApiPaths.BASE_PATH + "/candles")
    public CandleQueryResponse queryCandles(@RequestParam("symbol") String symbol,
                                            @RequestParam("period") String period,
                                            @RequestParam("startTime")
                                            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                                            @Valid Instant startTime,
                                            @RequestParam("endTime")
                                            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                                            @Valid Instant endTime,
                                            @RequestParam(value = "limit", defaultValue = "500") int limit) {
        try {
            return candleQueryService.query(symbol, period, startTime, endTime, limit);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @GetMapping(CandlestickApiPaths.BASE_PATH + "/candles/latest")
    public CandleResponse latestCandle(@RequestParam("symbol") String symbol, @RequestParam("period") String period) {
        try {
            return candleQueryService.latest(symbol, period)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "candle not found"));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }
}
