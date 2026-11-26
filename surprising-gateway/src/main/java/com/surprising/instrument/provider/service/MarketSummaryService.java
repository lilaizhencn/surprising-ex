package com.surprising.instrument.provider.service;

import com.surprising.instrument.provider.model.MarketSummaryRows.DaySummary;
import com.surprising.instrument.provider.model.MarketSummaryRows.Sample;
import com.surprising.instrument.provider.model.MarketSummaryRows.Summary;
import com.surprising.instrument.provider.repository.MarketSummaryRepository;
import com.surprising.product.api.ProductLine;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.stereotype.Service;

/** 聚合24小时行情；仅使用持久化的真实已关闭K线与实时1m内存滑动窗口，不制造静默时段交易量。 */
@Service
public class MarketSummaryService {
    private static final int TREND_POINTS = 32;
    private static final long DAY_MINUTES = 1440L;
    private final MarketSummaryRepository repository;
    private final Map<ProductLine, CachedSummary> cachedSummaries = new ConcurrentHashMap<>();
    private final ReentrantLock summaryLock = new ReentrantLock();
    private final Map<WindowKey, DayWindow> dayWindows = new ConcurrentHashMap<>();
    private record CachedSummary(List<Integer> instrumentIds, Instant asOf, Map<Integer, Summary> summaries) {}
    private record WindowKey(ProductLine productLine, int instrumentId) {}
    private record MinuteSlot(BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close,
                              BigDecimal volume, BigDecimal quoteVolume) {}

    private static final class DayWindow {
        private final ReentrantLock lock = new ReentrantLock();
        private final NavigableMap<Long, MinuteSlot> slots = new TreeMap<>();
        private volatile boolean initialized;

        void seedIfEmpty(List<Sample> samples, long nowEpochMillis) {
            lock.lock();
            try {
                if (initialized) return;
                long currentMinute = nowEpochMillis / 60_000L;
                long startMinute = currentMinute - Math.max(0, samples.size() - 1);
                for (int i = 0; i < samples.size(); i++) {
                    Sample s = samples.get(i);
                    slots.put(startMinute + i, new MinuteSlot(
                            s.open(), s.high(), s.low(), s.close(), s.volume(), s.quoteVolume()));
                }
                evictBefore(currentMinute - DAY_MINUTES);
                initialized = true;
            } finally {
                lock.unlock();
            }
        }

        void upsert(long openTimeMillis, BigDecimal open, BigDecimal high, BigDecimal low,
                    BigDecimal close, BigDecimal volume, BigDecimal quoteVolume) {
            lock.lock();
            try {
                long minute = openTimeMillis / 60_000L;
                slots.put(minute, new MinuteSlot(open, high, low, close, volume, quoteVolume));
                evictBefore(minute - DAY_MINUTES);
            } finally {
                lock.unlock();
            }
        }

        DaySummary summarize(long nowEpochMillis) {
            lock.lock();
            try {
                long cutoffMinute = (nowEpochMillis / 60_000L) - DAY_MINUTES;
                evictBefore(cutoffMinute);
                if (slots.isEmpty()) return null;
                MinuteSlot first = slots.firstEntry().getValue();
                MinuteSlot last = slots.lastEntry().getValue();
                BigDecimal open = first.open();
                BigDecimal close = last.close();
                BigDecimal high = first.high();
                BigDecimal low = first.low();
                BigDecimal volume = BigDecimal.ZERO;
                BigDecimal quoteVolume = BigDecimal.ZERO;
                for (MinuteSlot slot : slots.values()) {
                    high = high.max(slot.high());
                    low = low.min(slot.low());
                    volume = volume.add(slot.volume());
                    quoteVolume = quoteVolume.add(slot.quoteVolume());
                }
                BigDecimal change = open.signum() > 0
                        ? close.subtract(open).multiply(BigDecimal.valueOf(100)).divide(open, 6, RoundingMode.HALF_UP)
                        : BigDecimal.ZERO;
                return new DaySummary(open, close, high, low, volume, quoteVolume, change);
            } finally {
                lock.unlock();
            }
        }

        private void evictBefore(long minMinuteInclusive) {
            while (!slots.isEmpty() && slots.firstKey() < minMinuteInclusive) {
                slots.pollFirstEntry();
            }
        }
    }

    public MarketSummaryService(MarketSummaryRepository repository) {
        this.repository = repository;
    }

    public Map<Integer, Summary> summaries(com.surprising.product.api.ProductLine productLine,
                                           List<Integer> instrumentIds, Instant now) {
        if (instrumentIds.isEmpty()) return Map.of();
        var ids = instrumentIds.stream().distinct().sorted().toList();
        var cached = cachedSummaries.get(productLine);
        if (cached != null && cached.instrumentIds().equals(ids)
                && !now.isBefore(cached.asOf()) && now.isBefore(cached.asOf().plusSeconds(5))) {
            return cached.summaries();
        }
        summaryLock.lock();
        try {
            cached = cachedSummaries.get(productLine);
            if (cached != null && cached.instrumentIds().equals(ids)
                    && !now.isBefore(cached.asOf()) && now.isBefore(cached.asOf().plusSeconds(5))) {
                return cached.summaries();
            }
            var rows = repository.samples(ids, now.minusSeconds(86_400), now);
            Map<Integer, List<Sample>> grouped = new HashMap<>();
            for (Sample row : rows) grouped.computeIfAbsent(row.instrumentId(), ignored -> new ArrayList<>()).add(row);
            Map<Integer, Summary> result = new HashMap<>();
            grouped.forEach((instrumentId, samples) -> result.put(instrumentId, summarize(samples)));
            var summaries = Map.copyOf(result);
            cachedSummaries.put(productLine, new CachedSummary(ids, now, summaries));
            return summaries;
        } finally {
            summaryLock.unlock();
        }
    }

    /** 实时接收 1m K 线更新，O(log N) 写入内存 1440 分钟滑动窗口。 */
    public void onMinuteCandle(ProductLine productLine, int instrumentId, long openTimeMillis,
                               BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close,
                               BigDecimal volume, BigDecimal quoteVolume) {
        if (productLine == null || instrumentId <= 0 || open == null || high == null || low == null || close == null) {
            return;
        }
        DayWindow window = dayWindows.computeIfAbsent(new WindowKey(productLine, instrumentId), ignored -> new DayWindow());
        window.upsert(openTimeMillis, open, high, low, close,
                volume == null ? BigDecimal.ZERO : volume,
                quoteVolume == null ? BigDecimal.ZERO : quoteVolume);
    }

    /** 从内存 1440 分钟滑动窗口返回指定币对的 24h 滚动摘要；冷启动首次访问时从 DB 补齐历史窗口。 */
    public DaySummary daySummary(ProductLine productLine, int instrumentId, Instant now) {
        if (productLine == null || instrumentId <= 0) return null;
        DayWindow window = dayWindows.computeIfAbsent(new WindowKey(productLine, instrumentId), ignored -> new DayWindow());
        if (!window.initialized) {
            List<Sample> samples = repository.samples(List.of(instrumentId), now.minusSeconds(86_400), now);
            window.seedIfEmpty(samples, now.toEpochMilli());
        }
        return window.summarize(now.toEpochMilli());
    }

    static Summary summarize(List<Sample> rows) {
        BigDecimal open = rows.getFirst().open();
        BigDecimal close = rows.getLast().close();
        BigDecimal high = open;
        BigDecimal low = open;
        BigDecimal volume = BigDecimal.ZERO;
        BigDecimal quoteVolume = BigDecimal.ZERO;
        for (Sample row : rows) {
            high = high.max(row.high());
            low = low.min(row.low());
            volume = volume.add(row.volume());
            quoteVolume = quoteVolume.add(row.quoteVolume());
        }
        List<BigDecimal> trend = new ArrayList<>(Math.min(rows.size(), TREND_POINTS));
        int count = Math.min(rows.size(), TREND_POINTS);
        for (int i = 0; i < count; i++) {
            int index = count == 1 ? 0 : i * (rows.size() - 1) / (count - 1);
            trend.add(rows.get(index).close());
        }
        // With no executions in the window, both endpoints remain the last real close.
        if (trend.size() == 1) trend.add(trend.getFirst());
        BigDecimal change = close.subtract(open).multiply(BigDecimal.valueOf(100))
                .divide(open, 6, RoundingMode.HALF_UP);
        return new Summary(close, change, high, low, volume, quoteVolume, List.copyOf(trend));
    }

}
