package com.surprising.trading.order.controller;

import com.surprising.realtime.api.exception.ReadViewUnavailableException;
import com.surprising.trading.api.model.OrderBookSnapshotResponse;
import com.surprising.trading.matching.service.MatchingMarketDataService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Public trading market-data snapshot endpoints.
 */
@RestController
public class TradingMarketController {

    private final MatchingMarketDataService marketDataService;

    public TradingMarketController(MatchingMarketDataService marketDataService) {
        this.marketDataService = marketDataService;
    }

    @GetMapping("/api/v1/trading/market/orderbook")
    public OrderBookSnapshotResponse orderBook(
            @RequestParam("instrumentId") String instrumentId,
            @RequestParam(value = "depth", defaultValue = "30") int depth) {
        try {
            return marketDataService.orderBookSnapshot(instrumentId, depth);
        } catch (ReadViewUnavailableException unavailable) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, unavailable.getMessage(), unavailable);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }
}
