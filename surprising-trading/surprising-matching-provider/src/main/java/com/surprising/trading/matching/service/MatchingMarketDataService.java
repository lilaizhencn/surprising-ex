package com.surprising.trading.matching.service;

import com.surprising.trading.api.model.MarketTickerSummary;
import com.surprising.trading.api.model.OrderBookSnapshotResponse;
import com.surprising.trading.api.model.PublicTradeEvent;
import com.surprising.trading.api.model.UserMatchTradeQueryResponse;
import com.surprising.trading.matching.repository.CoreMarketDataRepository;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class MatchingMarketDataService {

    private final CoreMarketDataProjection projection;
    private final CoreMarketDataRepository repository;
    private final LatestPublicTradeCache latestTradeCache;

    public MatchingMarketDataService(CoreMarketDataProjection projection,
                                     CoreMarketDataRepository repository,
                                     LatestPublicTradeCache latestTradeCache) {
        this.projection = projection;
        this.repository = repository;
        this.latestTradeCache = latestTradeCache;
    }

    public OrderBookSnapshotResponse orderBookSnapshot(String symbol, int depth) {
        return projection.snapshot(symbol, depth);
    }

    public Optional<PublicTradeEvent> latestPublicTrade(String symbol) {
        return latestTradeCache.latest(symbol);
    }

    public MarketTickerSummary ticker24hr(String symbol) {
        Instant to = Instant.now();
        return repository.summary(symbol, to.minusSeconds(86_400), to);
    }

    public UserMatchTradeQueryResponse userTrades(long userId, String symbol, int limit, String cursor) {
        return repository.userTrades(userId, symbol, limit, cursor);
    }
}
