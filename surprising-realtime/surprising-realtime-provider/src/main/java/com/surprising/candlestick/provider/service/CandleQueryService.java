package com.surprising.candlestick.provider.service;

import com.surprising.candlestick.api.model.CandlePeriod;
import com.surprising.candlestick.api.model.CandleQueryResponse;
import com.surprising.candlestick.api.model.CandleResponse;
import com.surprising.candlestick.provider.aggregation.CandleKey;
import com.surprising.candlestick.provider.config.CandlestickProperties;
import com.surprising.candlestick.provider.repository.CandleQueryRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.LongAdder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class CandleQueryService {

    private final CandleQueryRepository candleQueryRepository;
    private final CandlestickProperties properties;
    private final CandleHotCache hotCache;
    private final LongAdder databaseFallbacks = new LongAdder();

    public CandleQueryService(CandleQueryRepository candleQueryRepository, CandlestickProperties properties) {
        this(candleQueryRepository, properties, null, null);
    }

    public CandleQueryService(CandleQueryRepository candleQueryRepository,
                              CandlestickProperties properties,
                              CandleHotCache hotCache) {
        this(candleQueryRepository, properties, hotCache, null);
    }

    @Autowired
    public CandleQueryService(CandleQueryRepository candleQueryRepository,
                              CandlestickProperties properties,
                              CandleHotCache hotCache,
                              MeterRegistry meterRegistry) {
        this.candleQueryRepository = candleQueryRepository;
        this.properties = properties;
        this.hotCache = hotCache;
        if (meterRegistry != null) {
            Gauge.builder("surprising.candlestick.query.database-fallbacks", databaseFallbacks, LongAdder::sum)
                    .description("K 线查询回退数据库次数")
                    .register(meterRegistry);
        }
    }

    public CandleQueryResponse query(String symbol, String period, Instant startTime, Instant endTime, int limit) {
        String normalizedSymbol = CandleKey.normalizeSymbol(symbol);
        CandlePeriod candlePeriod = CandlePeriod.fromCode(period);
        validateRange(startTime, endTime);
        int safeLimit = Math.min(limit, properties.getQuery().getMaxLimit());
        List<CandleResponse> hotCandles = hotCache == null ? List.of() : hotCache.range(
                normalizedSymbol, candlePeriod.code(), startTime, endTime, safeLimit);
        // 只从数据库读取已经进入关闭时间范围的 K 线，当前窗口由内存/RocksDB 提供。
        Instant now = Instant.now();
        Instant closedEnd = endTime.isBefore(now) ? endTime : now;
        List<CandleResponse> persistedCandles = startTime.isBefore(closedEnd)
                ? loadPersistedRange(normalizedSymbol, candlePeriod.code(), startTime, closedEnd, safeLimit)
                : List.of();
        Map<Instant, CandleResponse> merged = new LinkedHashMap<>();
        persistedCandles.forEach(candle -> merged.put(candle.openTime(), candle));
        hotCandles.forEach(candle -> merged.put(candle.openTime(), candle));
        List<CandleResponse> candles = merged.values().stream()
                .sorted(Comparator.comparing(CandleResponse::openTime))
                .limit(safeLimit)
                .toList();
        return new CandleQueryResponse(normalizedSymbol, candlePeriod.code(), safeLimit, candles);
    }

    public Optional<CandleResponse> latest(String symbol, String period) {
        String normalizedSymbol = CandleKey.normalizeSymbol(symbol);
        CandlePeriod candlePeriod = CandlePeriod.fromCode(period);
        if (hotCache != null) {
            Optional<CandleResponse> latest = hotCache.latest(normalizedSymbol, candlePeriod.code());
            if (latest.isPresent()) {
                return latest;
            }
        }
        databaseFallbacks.increment();
        return candleQueryRepository.findLatest(normalizedSymbol, candlePeriod.code());
    }

    private List<CandleResponse> loadPersistedRange(String symbol,
                                                    String period,
                                                    Instant startTime,
                                                    Instant endTime,
                                                    int limit) {
        databaseFallbacks.increment();
        return candleQueryRepository.findRange(symbol, period, startTime, endTime, limit);
    }

    private void validateRange(Instant startTime, Instant endTime) {
        if (startTime == null || endTime == null) {
            throw new IllegalArgumentException("startTime and endTime are required");
        }
        if (!startTime.isBefore(endTime)) {
            throw new IllegalArgumentException("startTime must be before endTime");
        }
    }
}
