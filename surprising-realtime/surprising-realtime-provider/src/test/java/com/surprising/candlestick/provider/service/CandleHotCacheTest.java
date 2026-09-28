package com.surprising.candlestick.provider.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.candlestick.api.model.CandleStatus;
import com.surprising.candlestick.api.model.CandleUpdatedEvent;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class CandleHotCacheTest {

    @Test
    void rangeReadsOnlyTheRequestedSymbolPeriodBucket() {
        CandleHotCache cache = new CandleHotCache();
        Instant first = Instant.parse("2026-07-01T00:00:00Z");
        cache.put(event("1", "1m", first));
        cache.put(event("1", "5m", first));
        cache.put(event("2", "1m", first));

        assertThat(cache.range("1", "1m", first, first.plusSeconds(60), 10))
                .extracting(value -> value.instrumentId() + ":" + value.period())
                .containsExactly("1:1m");
        assertThat(cache.size()).isEqualTo(3);
    }

    @Test
    void latestUsesTheLastEntryInTheTimeOrderedBucket() {
        CandleHotCache cache = new CandleHotCache();
        Instant first = Instant.parse("2026-07-01T00:00:00Z");
        cache.put(event("1", "1m", first));
        cache.put(event("1", "1m", first.plusSeconds(60)));

        assertThat(cache.latest("1", "1m")).get()
                .extracting(value -> value.openTime())
                .isEqualTo(first.plusSeconds(60));
    }

    private CandleUpdatedEvent event(String instrumentId, String period, Instant openTime) {
        return new CandleUpdatedEvent(instrumentId, period, openTime, openTime.plusSeconds(60), BigDecimal.ONE,
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, 1L,
                "trade-1", "trade-1", 1L, 1L, CandleStatus.PARTIAL, openTime, openTime, 0, 1L);
    }
}
