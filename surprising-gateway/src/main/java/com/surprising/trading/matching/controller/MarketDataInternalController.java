package com.surprising.trading.matching.controller;

import com.surprising.trading.api.TradingApiPaths;
import com.surprising.trading.api.model.OrderBookSnapshotResponse;
import com.surprising.trading.matching.service.MatchingMarketDataService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** 独立 maker 使用的内部 HTTP 契约，内部调用无需凭证。 */
@RestController
public class MarketDataInternalController {

    private final MatchingMarketDataService marketDataService;

    public MarketDataInternalController(MatchingMarketDataService marketDataService) {
        this.marketDataService = marketDataService;
    }

    @GetMapping(TradingApiPaths.MARKET_BASE_PATH + "/orderbook")
    public OrderBookSnapshotResponse orderBook(@RequestParam("symbol") String symbol,
                                               @RequestParam(value = "depth", defaultValue = "30") int depth) {
        try {
            return marketDataService.orderBookSnapshot(symbol, depth);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

}
