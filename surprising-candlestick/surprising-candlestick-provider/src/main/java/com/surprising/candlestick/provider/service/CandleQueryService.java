package com.surprising.candlestick.provider.service;

import com.surprising.candlestick.api.model.CandlePeriod;
import com.surprising.candlestick.api.model.CandleQueryResponse;
import com.surprising.candlestick.api.model.CandleResponse;
import com.surprising.candlestick.provider.aggregation.CandleKey;
import com.surprising.candlestick.provider.config.CandlestickProperties;
import com.surprising.candlestick.provider.repository.CandleQueryRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class CandleQueryService {

    private final CandleQueryRepository candleQueryRepository;
    private final CandlestickProperties properties;

    public CandleQueryService(CandleQueryRepository candleQueryRepository, CandlestickProperties properties) {
        this.candleQueryRepository = candleQueryRepository;
        this.properties = properties;
    }

    public CandleQueryResponse query(String symbol, String period, Instant startTime, Instant endTime, int limit) {
        String normalizedSymbol = CandleKey.normalizeSymbol(symbol);
        CandlePeriod candlePeriod = CandlePeriod.fromCode(period);
        validateRange(startTime, endTime);
        int safeLimit = Math.min(limit, properties.getQuery().getMaxLimit());
        List<CandleResponse> candles = candleQueryRepository.findRange(
                normalizedSymbol, candlePeriod.code(), startTime, endTime, safeLimit);
        return new CandleQueryResponse(normalizedSymbol, candlePeriod.code(), safeLimit, candles);
    }

    public Optional<CandleResponse> latest(String symbol, String period) {
        String normalizedSymbol = CandleKey.normalizeSymbol(symbol);
        CandlePeriod candlePeriod = CandlePeriod.fromCode(period);
        return candleQueryRepository.findLatest(normalizedSymbol, candlePeriod.code());
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
