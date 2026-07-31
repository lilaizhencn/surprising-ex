package com.surprising.price.mark.service;

import com.surprising.price.api.model.MarkPriceEvent;
import com.surprising.price.api.model.MarkPriceQueryResponse;
import com.surprising.price.api.model.MarkPriceResponse;
import com.surprising.price.consumer.LatestMarkPriceCache;
import com.surprising.price.mark.repository.MarkPriceTickRepository;
import java.time.Instant;
import org.springframework.stereotype.Service;

/**
 * 聚合最新标记价格缓存与历史标记价格仓储，统一执行查询校验和响应组装。
 */
@Service
public class MarkPriceQueryService {

    private final MarkPriceTickRepository tickRepository;
    private final LatestMarkPriceCache markPriceCache;

    public MarkPriceQueryService(MarkPriceTickRepository tickRepository,
                                 LatestMarkPriceCache markPriceCache) {
        this.tickRepository = tickRepository;
        this.markPriceCache = markPriceCache;
    }

    public MarkPriceResponse latest(String symbol) {
        return toResponse(markPriceCache.requireFresh(normalizeSymbol(symbol)));
    }

    public MarkPriceQueryResponse history(String symbol,
                                          Instant startTime,
                                          Instant endTime,
                                          int limit) {
        validateRange(startTime, endTime);
        String normalized = normalizeSymbol(symbol);
        int safeLimit = Math.min(limit, 5000);
        return new MarkPriceQueryResponse(
                normalized,
                safeLimit,
                tickRepository.history(normalized, startTime, endTime, safeLimit));
    }

    private String normalizeSymbol(String symbol) {
        if (symbol == null || !symbol.matches("[A-Z0-9][A-Z0-9_-]{1,63}")) {
            throw new IllegalArgumentException("invalid symbol");
        }
        return symbol;
    }

    private MarkPriceResponse toResponse(MarkPriceEvent event) {
        return new MarkPriceResponse(event.symbol(), event.markPrice(), event.markPriceUnits(), event.indexPrice(),
                event.price1(), event.price2(), event.lastTradePrice(), event.bestBidPrice(), event.bestAskPrice(),
                event.fundingRate(), event.nextFundingTime(), event.timeUntilFundingSeconds(), event.basisAverage(),
                event.basisWindowSeconds(), event.clampLow(), event.clampHigh(), event.sequence(), event.status(),
                event.eventTime());
    }

    private void validateRange(Instant startTime, Instant endTime) {
        if (startTime == null || endTime == null || !startTime.isBefore(endTime)) {
            throw new IllegalArgumentException("invalid time range");
        }
    }
}
