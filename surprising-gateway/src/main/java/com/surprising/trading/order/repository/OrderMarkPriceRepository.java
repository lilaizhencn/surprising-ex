package com.surprising.trading.order.repository;

import com.surprising.trading.order.model.MarkPriceLookup;
import com.surprising.price.consumer.LatestMarkPriceCache;
import java.time.Duration;
import java.util.OptionalLong;
import org.springframework.stereotype.Component;

@Component
public class OrderMarkPriceRepository implements MarkPriceLookup {

    private final LatestMarkPriceCache cache;

    public OrderMarkPriceRepository(LatestMarkPriceCache cache) {
        this.cache = cache;
    }

    @Override
    public OptionalLong latestMarkPriceTicks(String symbol, long instrumentChangeId, long maxAgeMs) {
        var event = cache.fresh(symbol, Duration.ofMillis(Math.max(1L, maxAgeMs)))
                .filter(value -> value.instrumentChangeId() == instrumentChangeId)
                .filter(value -> value.markPriceTicks() > 0);
        return event.isPresent() ? OptionalLong.of(event.orElseThrow().markPriceTicks()) : OptionalLong.empty();
    }
}
