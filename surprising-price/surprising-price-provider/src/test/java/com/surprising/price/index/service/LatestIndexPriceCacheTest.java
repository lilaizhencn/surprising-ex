package com.surprising.price.index.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.surprising.price.api.model.IndexPriceEvent;
import com.surprising.price.api.model.PriceStatus;
import com.surprising.price.index.config.IndexPriceProperties;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class LatestIndexPriceCacheTest {

    private static final Instant NOW = Instant.parse("2026-07-17T00:00:00Z");

    @Test
    void unavailableSnapshotReplacesAndInvalidatesAnEarlierHealthyPrice() {
        LatestIndexPriceCache cache = cache();
        cache.update(event(10, PriceStatus.HEALTHY, new BigDecimal("100"), NOW.minusSeconds(1)));
        cache.update(event(11, PriceStatus.INSUFFICIENT_SOURCES, null, NOW));

        assertThatThrownBy(() -> cache.requireFresh("BTC-USDT"))
                .isInstanceOf(StaleIndexPriceException.class)
                .hasMessageContaining("unavailable")
                .hasMessageContaining("INSUFFICIENT_SOURCES");
    }

    @Test
    void rejectsStaleSnapshots() {
        LatestIndexPriceCache cache = cache();
        cache.update(event(10, PriceStatus.HEALTHY, new BigDecimal("100"), NOW.minusSeconds(6)));

        assertThatThrownBy(() -> cache.requireFresh("BTC-USDT"))
                .isInstanceOf(StaleIndexPriceException.class)
                .hasMessageContaining("stale");
    }

    @Test
    void returnsTheLatestUsableKafkaSnapshot() {
        LatestIndexPriceCache cache = cache();
        cache.update(event(10, PriceStatus.HEALTHY, new BigDecimal("100"), NOW));

        assertThat(cache.requireFresh("btc-usdt").indexPrice()).isEqualByComparingTo("100");
    }

    private LatestIndexPriceCache cache() {
        IndexPriceProperties properties = new IndexPriceProperties();
        properties.getCalculation().setMaxSourceAge(java.time.Duration.ofSeconds(5));
        return new LatestIndexPriceCache(properties, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private IndexPriceEvent event(long sequence, PriceStatus status, BigDecimal price, Instant eventTime) {
        return new IndexPriceEvent("BTC-USDT", price, sequence, status, 3, price == null ? 1 : 3,
                BigDecimal.valueOf(3), eventTime, List.of());
    }
}
