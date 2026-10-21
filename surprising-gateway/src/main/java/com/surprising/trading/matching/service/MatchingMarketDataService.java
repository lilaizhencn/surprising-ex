package com.surprising.trading.matching.service;

import com.surprising.aeron.protocol.CoreBookLevelView;
import com.surprising.aeron.protocol.CoreOrderBookView;
import com.surprising.trading.api.model.OrderBookLevel;
import com.surprising.trading.api.model.OrderBookSnapshotResponse;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;
import com.surprising.realtime.api.ValkeyMarketData;
import com.surprising.trading.order.config.TradingOrderProperties;
import com.surprising.product.api.ProductLine;
import org.springframework.data.redis.core.StringRedisTemplate;

@Service
public class MatchingMarketDataService {

    private final ValkeyMarketData marketData;
    private final ProductLine product;

    @org.springframework.beans.factory.annotation.Autowired
    public MatchingMarketDataService(StringRedisTemplate redis, TradingOrderProperties properties) {
        this(new ValkeyMarketData(redis), properties.getKafka().getProductLine());
    }

    public MatchingMarketDataService(ValkeyMarketData marketData, ProductLine product) {
        this.marketData = marketData;
        this.product = java.util.Objects.requireNonNull(product, "market product is required");
    }

    public OrderBookSnapshotResponse orderBookSnapshot(String instrumentId, int depth) {
        com.surprising.product.api.utils.InstrumentIds.parse(instrumentId);
        if (depth == 0) depth = 30;
        if (depth < 1 || depth > 100) throw new IllegalArgumentException("invalid book depth");
        int availableDepth = Math.min(depth, 50);
        var source = marketData.requireBook(product, instrumentId);
        CoreOrderBookView book = source.view();
        String normalized = instrumentId.trim().toUpperCase(Locale.ROOT);
        List<OrderBookLevel> bids = book.levels().stream()
                .filter(level -> level.side() == com.surprising.aeron.protocol.CoreOrderSide.BUY)
                .sorted(Comparator.comparingLong(CoreBookLevelView::priceTicks).reversed())
                .limit(availableDepth)
                .map(MatchingMarketDataService::toLevel)
                .toList();
        List<OrderBookLevel> asks = book.levels().stream()
                .filter(level -> level.side() == com.surprising.aeron.protocol.CoreOrderSide.SELL)
                .sorted(Comparator.comparingLong(CoreBookLevelView::priceTicks))
                .limit(availableDepth)
                .map(MatchingMarketDataService::toLevel)
                .toList();
        return new OrderBookSnapshotResponse(normalized, book.exportSequence(), availableDepth,
                bids, asks, Instant.ofEpochMilli(source.refreshedAt()));
    }

    private static OrderBookLevel toLevel(CoreBookLevelView level) {
        return new OrderBookLevel(level.priceTicks(), level.quantitySteps(), level.orderCount());
    }
}
