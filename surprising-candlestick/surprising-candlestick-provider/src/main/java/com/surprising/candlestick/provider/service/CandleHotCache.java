package com.surprising.candlestick.provider.service;

import com.surprising.candlestick.api.model.CandleResponse;
import com.surprising.candlestick.api.model.CandleStatus;
import com.surprising.candlestick.api.model.CandleUpdatedEvent;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** K 线热数据本地缓存；Kafka Streams 的 RocksDB 是恢复源，PostgreSQL 只保存关闭快照。 */
@Component
public class CandleHotCache {

    private static final int MAX_ENTRIES = 100_000;
    /** 按交易对和周期分桶，查询时只访问目标桶的时间范围。 */
    private final ConcurrentMap<BucketKey, NavigableMap<Instant, CandleResponse>> buckets =
            new ConcurrentHashMap<>();
    private final AtomicInteger entryCount = new AtomicInteger();
    private final LongAdder rangeHits = new LongAdder();
    private final LongAdder rangeMisses = new LongAdder();
    private final LongAdder latestHits = new LongAdder();
    private final LongAdder latestMisses = new LongAdder();

    public CandleHotCache() {
        this(null);
    }

    @Autowired
    public CandleHotCache(MeterRegistry meterRegistry) {
        if (meterRegistry != null) {
            Gauge.builder("surprising.candlestick.hot-cache.entries", entryCount, AtomicInteger::get)
                    .description("K 线热缓存当前条目数")
                    .register(meterRegistry);
            Gauge.builder("surprising.candlestick.hot-cache.range.hits", rangeHits, LongAdder::sum)
                    .description("K 线区间查询命中热缓存次数")
                    .register(meterRegistry);
            Gauge.builder("surprising.candlestick.hot-cache.range.misses", rangeMisses, LongAdder::sum)
                    .description("K 线区间查询未命中热缓存次数")
                    .register(meterRegistry);
            Gauge.builder("surprising.candlestick.hot-cache.latest.hits", latestHits, LongAdder::sum)
                    .description("K 线最新查询命中热缓存次数")
                    .register(meterRegistry);
            Gauge.builder("surprising.candlestick.hot-cache.latest.misses", latestMisses, LongAdder::sum)
                    .description("K 线最新查询未命中热缓存次数")
                    .register(meterRegistry);
        }
    }

    public void put(CandleUpdatedEvent event) {
        if (event == null || event.symbol() == null || event.period() == null || event.openTime() == null) {
            return;
        }
        String symbol = event.symbol().trim().toUpperCase(Locale.ROOT);
        CandleResponse candle = new CandleResponse(
                symbol, event.period(), event.openTime(), event.closeTime(), event.openPrice(),
                event.highPrice(), event.lowPrice(), event.closePrice(), event.baseVolume(), event.quoteVolume(),
                event.tradeCount(), event.firstTradeId(), event.lastTradeId(), event.firstSequence(),
                event.lastSequence(), event.status(), event.eventTime());
        NavigableMap<Instant, CandleResponse> bucket = buckets.computeIfAbsent(
                new BucketKey(symbol, event.period()), ignored -> new ConcurrentSkipListMap<>());
        if (bucket.put(event.openTime(), candle) == null) {
            entryCount.incrementAndGet();
        }
        trimIfNeeded();
    }

    public List<CandleResponse> range(String symbol, String period, Instant startTime, Instant endTime, int limit) {
        NavigableMap<Instant, CandleResponse> bucket = buckets.get(new BucketKey(normalizeSymbol(symbol), period));
        if (bucket == null || bucket.isEmpty()) {
            rangeMisses.increment();
            return List.of();
        }
        rangeHits.increment();
        return bucket.subMap(startTime, true, endTime, false).values().stream()
                .map(this::closeIfExpired)
                .limit(Math.max(1, limit))
                .toList();
    }

    public Optional<CandleResponse> latest(String symbol, String period) {
        NavigableMap<Instant, CandleResponse> bucket = buckets.get(new BucketKey(normalizeSymbol(symbol), period));
        if (bucket == null) {
            latestMisses.increment();
            return Optional.empty();
        }
        Map.Entry<Instant, CandleResponse> latest = bucket.lastEntry();
        if (latest == null) {
            latestMisses.increment();
            return Optional.empty();
        }
        latestHits.increment();
        return Optional.of(closeIfExpired(latest.getValue()));
    }

    public int size() {
        return entryCount.get();
    }

    private void trimIfNeeded() {
        while (entryCount.get() > MAX_ENTRIES) {
            OldestEntry oldest = buckets.entrySet().stream()
                    .map(entry -> {
                        Map.Entry<Instant, CandleResponse> first = entry.getValue().firstEntry();
                        return first == null ? null : new OldestEntry(entry.getKey(), entry.getValue(), first);
                    })
                    .filter(entry -> entry != null)
                    .min(Comparator.comparing(entry -> entry.entry().getKey()))
                    .orElse(null);
            if (oldest == null || !oldest.bucket().remove(oldest.entry().getKey(), oldest.entry().getValue())) {
                return;
            }
            entryCount.decrementAndGet();
            if (oldest.bucket().isEmpty()) {
                buckets.remove(oldest.key(), oldest.bucket());
            }
        }
    }

    private String normalizeSymbol(String symbol) {
        return symbol == null ? "" : symbol.trim().toUpperCase(Locale.ROOT);
    }

    private CandleResponse closeIfExpired(CandleResponse value) {
        if (value.status() == CandleStatus.CLOSED
                || value.closeTime() == null
                || value.closeTime().isAfter(Instant.now())) {
            return value;
        }
        return new CandleResponse(value.symbol(), value.period(), value.openTime(), value.closeTime(),
                value.openPrice(), value.highPrice(), value.lowPrice(), value.closePrice(), value.baseVolume(),
                value.quoteVolume(), value.tradeCount(), value.firstTradeId(), value.lastTradeId(),
                value.firstSequence(), value.lastSequence(), CandleStatus.CLOSED, value.updatedAt());
    }

    private record BucketKey(String symbol, String period) {
    }

    private record OldestEntry(BucketKey key,
                               NavigableMap<Instant, CandleResponse> bucket,
                               Map.Entry<Instant, CandleResponse> entry) {
    }
}
