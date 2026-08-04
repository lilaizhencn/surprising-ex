package com.surprising.trading.matching.controller;

import com.surprising.trading.api.TradingApiPaths;
import com.surprising.trading.api.model.MarketTickerSummary;
import com.surprising.trading.api.model.OrderBookSnapshotResponse;
import com.surprising.trading.api.model.PublicTradeEvent;
import com.surprising.trading.matching.repository.MatchingTradeRepository;
import com.surprising.trading.matching.service.MatchingService;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class MarketDataController {

    private final MatchingService matchingService;
    private final MatchingTradeRepository tradeRepository;

    public MarketDataController(MatchingService matchingService) {
        this(matchingService, null);
    }

    @Autowired
    public MarketDataController(MatchingService matchingService, MatchingTradeRepository tradeRepository) {
        this.matchingService = matchingService;
        this.tradeRepository = tradeRepository;
    }

    @GetMapping(TradingApiPaths.MARKET_BASE_PATH + "/orderbook")
    public OrderBookSnapshotResponse orderBook(@RequestParam("symbol") String symbol,
                                               @RequestParam(value = "depth", defaultValue = "50") int depth) {
        try {
            return matchingService.orderBookSnapshot(symbol, depth);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @GetMapping(TradingApiPaths.MARKET_BASE_PATH + "/latest-trade")
    public PublicTradeEvent latestTrade(@RequestParam("symbol") String symbol) {
        return matchingService.latestPublicTrade(symbol)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "latest trade not found"));
    }

    @GetMapping(TradingApiPaths.MARKET_BASE_PATH + "/ticker-24hr")
    public MarketTickerSummary ticker24hr(@RequestParam("symbol") String symbol) {
        if (tradeRepository == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "trade history is not configured");
        }
        Instant to = Instant.now();
        return tradeRepository.summary(symbol, to.minusSeconds(86_400L), to);
    }
}
