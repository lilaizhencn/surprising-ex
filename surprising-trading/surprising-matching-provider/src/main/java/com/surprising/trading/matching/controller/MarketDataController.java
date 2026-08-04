package com.surprising.trading.matching.controller;

import com.surprising.trading.api.TradingApiPaths;
import com.surprising.trading.api.model.MarketTickerSummary;
import com.surprising.trading.api.model.OrderBookSnapshotResponse;
import com.surprising.trading.api.model.PublicTradeEvent;
import com.surprising.trading.matching.service.MatchingMarketDataService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class MarketDataController {

    private final MatchingMarketDataService marketDataService;

    public MarketDataController(MatchingMarketDataService marketDataService) {
        this.marketDataService = marketDataService;
    }

    @GetMapping(TradingApiPaths.MARKET_BASE_PATH + "/orderbook")
    public OrderBookSnapshotResponse orderBook(@RequestParam("symbol") String symbol,
                                               @RequestParam(value = "depth", defaultValue = "50") int depth) {
        try {
            return marketDataService.orderBookSnapshot(symbol, depth);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @GetMapping(TradingApiPaths.MARKET_BASE_PATH + "/latest-trade")
    public PublicTradeEvent latestTrade(@RequestParam("symbol") String symbol) {
        return marketDataService.latestPublicTrade(symbol)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "latest trade not found"));
    }

    @GetMapping(TradingApiPaths.MARKET_BASE_PATH + "/ticker-24hr")
    public MarketTickerSummary ticker24hr(@RequestParam("symbol") String symbol) {
        return marketDataService.ticker24hr(symbol);
    }
}
