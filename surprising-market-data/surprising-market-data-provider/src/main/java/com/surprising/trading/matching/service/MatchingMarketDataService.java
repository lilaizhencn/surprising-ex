package com.surprising.trading.matching.service;

import com.surprising.aeron.protocol.CoreBookLevelView;
import com.surprising.aeron.protocol.CoreOrderBookQuery;
import com.surprising.aeron.protocol.CoreOrderBookView;
import com.surprising.trading.api.model.OrderBookLevel;
import com.surprising.trading.api.model.OrderBookSnapshotResponse;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;

@Service
public class MatchingMarketDataService {

    private final MatchingAeronGateway aeronGateway;

    public MatchingMarketDataService(MatchingAeronGateway aeronGateway) {
        this.aeronGateway = aeronGateway;
    }

    public OrderBookSnapshotResponse orderBookSnapshot(String symbol, int depth) {
        CoreOrderBookView book = aeronGateway.orderBookProjection(new CoreOrderBookQuery(symbol, depth));
        String normalized = symbol.trim().toUpperCase(Locale.ROOT);
        List<OrderBookLevel> bids = book.levels().stream()
                .filter(level -> level.side() == com.surprising.aeron.protocol.CoreOrderSide.BUY)
                .map(MatchingMarketDataService::toLevel)
                .toList();
        List<OrderBookLevel> asks = book.levels().stream()
                .filter(level -> level.side() == com.surprising.aeron.protocol.CoreOrderSide.SELL)
                .map(MatchingMarketDataService::toLevel)
                .toList();
        return new OrderBookSnapshotResponse(normalized, book.exportSequence(), depth,
                bids, asks, Instant.now());
    }

    private static OrderBookLevel toLevel(CoreBookLevelView level) {
        return new OrderBookLevel(level.priceTicks(), level.quantitySteps(), level.orderCount());
    }
}
