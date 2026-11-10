package com.surprising.marketmaker.provider.service;

import com.surprising.price.api.model.MarkPriceEvent;
import com.surprising.product.api.ProductLine;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Component;

/** 做市商本地标记价格缓存；通过 UDP 实时接收最新帧，内存原子更新，零 Kafka 依赖。 */
@Component
public class MarketMakerMarkPriceCache {

    private final Clock clock;
    private final Duration maxAge;
    private final Duration allowedFutureSkew;
    private final ConcurrentMap<String, MarkPriceEvent> latestBySymbol = new ConcurrentHashMap<>();

    public MarketMakerMarkPriceCache() {
        this(Clock.systemUTC(), Duration.ofSeconds(3), Duration.ofSeconds(1));
    }

    public MarketMakerMarkPriceCache(Clock clock, Duration maxAge, Duration allowedFutureSkew) {
        this.clock = clock;
        this.maxAge = maxAge != null ? maxAge : Duration.ofSeconds(3);
        this.allowedFutureSkew = allowedFutureSkew != null ? allowedFutureSkew : Duration.ofSeconds(1);
    }

    public boolean update(MarkPriceEvent event) {
        if (event == null || event.instrumentId() == null) return false;
        String instrumentId = event.instrumentId().trim();
        MarkPriceEvent updated = latestBySymbol.compute(instrumentId, (ignored, current) ->
                current == null || event.sequence() >= current.sequence() ? event : current);
        return updated == event;
    }

    public Optional<MarkPriceEvent> latest(String instrumentId) {
        if (instrumentId == null) return Optional.empty();
        return Optional.ofNullable(latestBySymbol.get(instrumentId.trim()));
    }

    public Optional<MarkPriceEvent> fresh(String instrumentId) {
        return latest(instrumentId).filter(this::isFresh);
    }

    public MarkPriceEvent requireFresh(String instrumentId) {
        MarkPriceEvent event = latest(instrumentId)
                .orElseThrow(() -> new IllegalStateException("做市标记价格不可用: " + instrumentId));
        if (!isFresh(event)) {
            throw new IllegalStateException("做市标记价格已过期: " + instrumentId + ", eventTime=" + event.eventTime());
        }
        return event;
    }

    public boolean isFresh(MarkPriceEvent event) {
        if (event == null || event.eventTime() == null) return false;
        Instant now = clock.instant();
        return !event.eventTime().isAfter(now.plus(allowedFutureSkew))
                && !event.eventTime().isBefore(now.minus(maxAge));
    }
}
