package com.surprising.candlestick.provider.service;

import com.surprising.candlestick.api.model.CandleResponse;
import com.surprising.candlestick.api.model.CandleStatus;
import com.surprising.candlestick.api.model.CandleUpdatedEvent;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Component;

/** K 线热数据本地缓存；Kafka Streams 的 RocksDB 是恢复源，PostgreSQL 只保存关闭快照。 */
@Component
public class CandleHotCache {

    private static final int MAX_ENTRIES = 100_000;
    private final ConcurrentMap<Key, CandleResponse> values = new ConcurrentHashMap<>();

    public void put(CandleUpdatedEvent event) {
        if (event == null || event.symbol() == null || event.period() == null || event.openTime() == null) {
            return;
        }
        String symbol = event.symbol().trim().toUpperCase(Locale.ROOT);
        values.put(new Key(symbol, event.period(), event.openTime()), new CandleResponse(
                symbol, event.period(), event.openTime(), event.closeTime(), event.openPrice(),
                event.highPrice(), event.lowPrice(), event.closePrice(), event.baseVolume(), event.quoteVolume(),
                event.tradeCount(), event.firstTradeId(), event.lastTradeId(), event.firstSequence(),
                event.lastSequence(), event.status(), event.eventTime()));
        trimIfNeeded();
    }

    public List<CandleResponse> range(String symbol, String period, Instant startTime, Instant endTime, int limit) {
        return values.values().stream()
                .filter(value -> value.symbol().equals(normalizeSymbol(symbol)) && value.period().equals(period))
                .map(this::closeIfExpired)
                .filter(value -> !value.openTime().isBefore(startTime) && value.openTime().isBefore(endTime))
                .sorted(Comparator.comparing(CandleResponse::openTime))
                .limit(Math.max(1, limit))
                .toList();
    }

    public java.util.Optional<CandleResponse> latest(String symbol, String period) {
        return values.values().stream()
                .filter(value -> value.symbol().equals(normalizeSymbol(symbol)) && value.period().equals(period))
                .max(Comparator.comparing(CandleResponse::openTime))
                .map(this::closeIfExpired);
    }

    public int size() {
        return values.size();
    }

    private void trimIfNeeded() {
        if (values.size() <= MAX_ENTRIES) {
            return;
        }
        values.entrySet().stream()
                .min(Comparator.comparing(entry -> entry.getKey().openTime()))
                .ifPresent(entry -> values.remove(entry.getKey(), entry.getValue()));
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

    private record Key(String symbol, String period, Instant openTime) {
    }
}
