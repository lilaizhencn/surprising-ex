package com.surprising.price.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.surprising.price.api.model.MarkPriceEvent;
import com.surprising.price.api.model.PriceStatus;
import com.surprising.product.api.ProductLine;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class LatestMarkPriceCacheTest {

    private static final Instant NOW = Instant.parse("2026-07-17T12:00:00Z");

    @Test
    void keepsNewestSequenceAndReturnsFixedPointPriceWhileFresh() {
        LatestMarkPriceCache cache = cache(Duration.ofSeconds(3));
        MarkPriceEvent newest = event(12L, NOW.minusMillis(100), PriceStatus.HEALTHY);

        assertThat(cache.update(event(11L, NOW.minusMillis(200), PriceStatus.HEALTHY))).isTrue();
        assertThat(cache.update(newest)).isTrue();
        assertThat(cache.update(event(10L, NOW, PriceStatus.HEALTHY))).isFalse();

        assertThat(cache.requireFresh("btc-usdt")).isEqualTo(newest);
        assertThat(cache.requireFresh("BTC-USDT").markPriceTicks()).isEqualTo(59_001L);
    }

    @Test
    void staleEventRemainsAuditableInCacheButCannotBeUsed() {
        LatestMarkPriceCache cache = cache(Duration.ofSeconds(3));
        cache.update(event(12L, NOW.minusSeconds(4), PriceStatus.HEALTHY));

        assertThat(cache.latest("BTC-USDT")).isPresent();
        assertThat(cache.fresh("BTC-USDT")).isEmpty();
        assertThat(cache.freshSnapshots()).isEmpty();
        assertThatThrownBy(() -> cache.requireFresh("BTC-USDT"))
                .isInstanceOf(StaleMarkPriceException.class)
                .hasMessageContaining("mark price is stale");
    }

    @Test
    void supportsConsumerSpecificFreshnessForBatchSnapshots() {
        LatestMarkPriceCache cache = cache(Duration.ofSeconds(10));
        MarkPriceEvent event = event(12L, NOW.minusSeconds(4), PriceStatus.HEALTHY);
        cache.update(event);

        assertThat(cache.freshSnapshots(Duration.ofSeconds(3))).isEmpty();
        assertThat(cache.freshSnapshots(Duration.ofSeconds(5))).containsExactly(event);
    }

    @Test
    void rejectsWrongProductFutureTimestampAndUnusableStatus() {
        LatestMarkPriceCache cache = cache(Duration.ofSeconds(3));

        assertThatThrownBy(() -> cache.update(event(ProductLine.LINEAR_DELIVERY, 1L, NOW,
                PriceStatus.HEALTHY))).hasMessageContaining("product line");
        assertThatThrownBy(() -> cache.update(event(1L, NOW.plusSeconds(2), PriceStatus.HEALTHY)))
                .hasMessageContaining("future");
        assertThatThrownBy(() -> cache.update(event(1L, NOW, PriceStatus.STALE)))
                .hasMessageContaining("not usable");
    }

    private LatestMarkPriceCache cache(Duration maxAge) {
        MarkPriceConsumerProperties properties = new MarkPriceConsumerProperties();
        properties.setProductLine(ProductLine.LINEAR_PERPETUAL);
        properties.setMaxAge(maxAge);
        properties.setAllowedFutureSkew(Duration.ofSeconds(1));
        return new LatestMarkPriceCache(properties, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private MarkPriceEvent event(long sequence, Instant eventTime, PriceStatus status) {
        return event(ProductLine.LINEAR_PERPETUAL, sequence, eventTime, status);
    }

    private MarkPriceEvent event(ProductLine productLine, long sequence, Instant eventTime, PriceStatus status) {
        BigDecimal price = new BigDecimal("59001.00");
        return new MarkPriceEvent(productLine, "BTC-USDT", 7L, 5_900_100L, 59_001L,
                price, price, price, price, price, price, price, BigDecimal.ZERO,
                eventTime.plusSeconds(3600), 3600L, BigDecimal.ZERO, 60L, price, price,
                sequence, status, eventTime, eventTime);
    }
}
