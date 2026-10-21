package com.surprising.instrument.provider.service;

import com.surprising.instrument.provider.model.MarketSummaryRows.Sample;
import com.surprising.instrument.provider.model.MarketSummaryRows.Summary;
import com.surprising.instrument.provider.repository.MarketSummaryRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** 聚合24小时行情；仅使用持久化的真实已关闭K线，不制造静默时段交易量。 */
@Service
public class MarketSummaryService {
    private static final int TREND_POINTS = 32;
    private final MarketSummaryRepository repository;

    public MarketSummaryService(MarketSummaryRepository repository) {
        this.repository = repository;
    }

    public Map<Integer, Summary> summaries(List<Integer> instrumentIds, Instant now) {
        if (instrumentIds.isEmpty()) return Map.of();
        var rows = repository.samples(instrumentIds, now.minusSeconds(86_400), now);
        Map<Integer, List<Sample>> grouped = new HashMap<>();
        for (Sample row : rows) grouped.computeIfAbsent(row.instrumentId(), ignored -> new ArrayList<>()).add(row);
        Map<Integer, Summary> result = new HashMap<>();
        grouped.forEach((instrumentId, samples) -> result.put(instrumentId, summarize(samples)));
        return result;
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
