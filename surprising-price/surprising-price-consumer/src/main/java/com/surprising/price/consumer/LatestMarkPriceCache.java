package com.surprising.price.consumer;

import com.surprising.price.api.model.MarkPriceEvent;
import com.surprising.price.api.model.PriceStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Component;

@Component
public class LatestMarkPriceCache {

    private final MarkPriceConsumerProperties properties;
    private final Clock clock;
    private final ConcurrentMap<String, MarkPriceEvent> latestBySymbol = new ConcurrentHashMap<>();

    public LatestMarkPriceCache(MarkPriceConsumerProperties properties) {
        this(properties, Clock.systemUTC());
    }

    LatestMarkPriceCache(MarkPriceConsumerProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public boolean update(MarkPriceEvent event) {
        validate(event, clock.instant());
        String symbol = normalizeSymbol(event.symbol());
        MarkPriceEvent updated = latestBySymbol.compute(symbol, (ignored, current) ->
                current == null || newer(event, current) ? event : current);
        return updated == event;
    }

    public Optional<MarkPriceEvent> latest(String symbol) {
        return Optional.ofNullable(latestBySymbol.get(normalizeSymbol(symbol)));
    }

    public Optional<MarkPriceEvent> fresh(String symbol) {
        return latest(symbol).filter(this::isFresh);
    }

    public Optional<MarkPriceEvent> fresh(String symbol, Duration maxAge) {
        if (maxAge == null || maxAge.isZero() || maxAge.isNegative()) {
            throw new IllegalArgumentException("maxAge must be positive");
        }
        return latest(symbol).filter(event -> isFresh(event, maxAge));
    }

    public MarkPriceEvent requireFresh(String symbol) {
        MarkPriceEvent event = latest(symbol)
                .orElseThrow(() -> new StaleMarkPriceException("mark price unavailable: " + normalizeSymbol(symbol)));
        if (!isFresh(event)) {
            throw new StaleMarkPriceException("mark price is stale: " + event.symbol()
                    + " eventTime=" + event.eventTime());
        }
        return event;
    }

    public boolean isFresh(MarkPriceEvent event) {
        return isFresh(event, properties.getMaxAge());
    }

    private boolean isFresh(MarkPriceEvent event, Duration maxAge) {
        Instant now = clock.instant();
        return !event.eventTime().isAfter(now.plus(properties.getAllowedFutureSkew()))
                && !event.eventTime().isBefore(now.minus(maxAge));
    }

    public List<MarkPriceEvent> freshSnapshots() {
        return latestBySymbol.values().stream()
                .filter(this::isFresh)
                .sorted(Comparator.comparing(MarkPriceEvent::symbol))
                .toList();
    }

    public Duration maxAge() {
        return properties.getMaxAge();
    }

    private void validate(MarkPriceEvent event, Instant now) {
        if (event == null || event.productLine() == null || event.productLine() != properties.getProductLine()) {
            throw new IllegalArgumentException("mark price product line does not match consumer");
        }
        if (event.instrumentVersion() <= 0 || event.markPriceUnits() <= 0 || event.markPriceTicks() <= 0
                || event.sequence() <= 0 || event.eventTime() == null || event.publishedAt() == null) {
            throw new IllegalArgumentException("mark price fixed-point fields and timestamps must be positive");
        }
        if (event.status() == null || event.status() == PriceStatus.STALE
                || event.status() == PriceStatus.INSUFFICIENT_SOURCES) {
            throw new IllegalArgumentException("mark price status is not usable: " + event.status());
        }
        if (event.eventTime().isAfter(now.plus(properties.getAllowedFutureSkew()))) {
            throw new IllegalArgumentException("mark price eventTime is in the future: " + event.eventTime());
        }
        if (event.publishedAt().isBefore(event.eventTime())) {
            throw new IllegalArgumentException("mark price publishedAt precedes eventTime");
        }
        if (event.publishedAt().isAfter(now.plus(properties.getAllowedFutureSkew()))) {
            throw new IllegalArgumentException("mark price publishedAt is in the future: " + event.publishedAt());
        }
        normalizeSymbol(event.symbol());
    }

    private boolean newer(MarkPriceEvent candidate, MarkPriceEvent current) {
        return candidate.sequence() > current.sequence()
                || candidate.sequence() == current.sequence()
                && candidate.eventTime().isAfter(current.eventTime());
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
