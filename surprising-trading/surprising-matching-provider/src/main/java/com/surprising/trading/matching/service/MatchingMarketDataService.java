package com.surprising.trading.matching.service;

import com.surprising.trading.api.model.MarketTickerSummary;
import com.surprising.trading.api.model.OrderBookSnapshotResponse;
import com.surprising.trading.api.model.PublicTradeEvent;
import com.surprising.trading.matching.repository.MatchingTradeRepository;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class MatchingMarketDataService {

    private final MatchingService matchingService;
    private final MatchingTradeRepository tradeRepository;

    public MatchingMarketDataService(MatchingService matchingService,
                                     MatchingTradeRepository tradeRepository) {
        this.matchingService = matchingService;
        this.tradeRepository = tradeRepository;
    }

    public OrderBookSnapshotResponse orderBookSnapshot(String symbol, int depth) {
        return matchingService.orderBookSnapshot(symbol, depth);
    }

    public Optional<PublicTradeEvent> latestPublicTrade(String symbol) {
        return matchingService.latestPublicTrade(symbol);
    }

    public MarketTickerSummary ticker24hr(String symbol) {
        Instant to = Instant.now();
        return tradeRepository.summary(symbol, to.minusSeconds(86_400L), to);
    }
}
