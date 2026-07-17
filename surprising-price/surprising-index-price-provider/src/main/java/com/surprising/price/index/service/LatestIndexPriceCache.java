package com.surprising.price.index.service;

import com.surprising.price.api.model.IndexPriceEvent;
import com.surprising.price.api.model.IndexPriceResponse;
import com.surprising.price.api.model.PriceStatus;
import com.surprising.price.index.config.IndexPriceProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Latest Kafka index snapshot per symbol. Audit tables are deliberately not a real-time input. */
@Component
public class LatestIndexPriceCache {

    private final IndexPriceProperties properties;
    private final Clock clock;
    private final ConcurrentMap<String, IndexPriceEvent> latestBySymbol = new ConcurrentHashMap<>();

    @Autowired
    public LatestIndexPriceCache(IndexPriceProperties properties) {
        this(properties, Clock.systemUTC());
    }

    LatestIndexPriceCache(IndexPriceProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public boolean update(IndexPriceEvent event) {
        validate(event, clock.instant());
        String symbol = normalizeSymbol(event.symbol());
        IndexPriceEvent updated = latestBySymbol.compute(symbol, (ignored, current) ->
                current == null || newer(event, current) ? event : current);
        return updated == event;
    }

    public IndexPriceResponse requireFresh(String symbol) {
        IndexPriceEvent event = latestBySymbol.get(normalizeSymbol(symbol));
        if (event == null) {
            throw new StaleIndexPriceException("index price unavailable: " + symbol);
        }
        if (!isFresh(event)) {
            throw new StaleIndexPriceException("index price is stale: " + event.symbol()
                    + " eventTime=" + event.eventTime());
        }
        if (!usable(event.status()) || event.indexPrice() == null) {
            throw new StaleIndexPriceException("index price is unavailable: " + event.symbol()
                    + " status=" + event.status());
        }
        return new IndexPriceResponse(event.symbol(), event.indexPrice(), event.sequence(), event.status(),
                event.componentCount(), event.validComponentCount(), event.eventTime(), event.components());
    }

    boolean isFresh(IndexPriceEvent event) {
        Instant now = clock.instant();
        Duration maxAge = properties.getCalculation().getMaxSourceAge();
        return event.eventTime() != null
                && !event.eventTime().isAfter(now.plusSeconds(1))
                && !event.eventTime().isBefore(now.minus(maxAge));
    }

    private void validate(IndexPriceEvent event, Instant now) {
        if (event == null || event.sequence() <= 0 || event.status() == null || event.eventTime() == null) {
            throw new IllegalArgumentException("index price sequence, status, and eventTime are required");
        }
        if (event.eventTime().isAfter(now.plusSeconds(1))) {
            throw new IllegalArgumentException("index price eventTime is in the future: " + event.eventTime());
        }
        normalizeSymbol(event.symbol());
    }

    private boolean newer(IndexPriceEvent candidate, IndexPriceEvent current) {
        return candidate.sequence() > current.sequence()
                || candidate.sequence() == current.sequence()
                && candidate.eventTime().isAfter(current.eventTime());
    }

    private boolean usable(PriceStatus status) {
        return status == PriceStatus.HEALTHY || status == PriceStatus.DEGRADED;
    }

    private String normalizeSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("symbol is required");
        }
        String normalized = symbol.trim().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z0-9][A-Z0-9_-]{1,63}")) {
            throw new IllegalArgumentException("invalid symbol: " + symbol);
        }
        return normalized;
    }
}
