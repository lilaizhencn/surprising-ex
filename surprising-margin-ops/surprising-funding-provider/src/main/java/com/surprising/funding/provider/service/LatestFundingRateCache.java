package com.surprising.funding.provider.service;

import com.surprising.funding.api.model.FundingRateResponse;
import com.surprising.funding.provider.config.FundingProperties;
import com.surprising.price.api.model.PerpFundingRateEvent;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Component;

/** Kafka-backed latest predicted funding rates. Database rows are reserved for frozen settlement rates. */
@Component
public class LatestFundingRateCache {

    private final FundingProperties properties;
    private final Clock clock;
    private final ConcurrentMap<FundingRateKey, FundingRateResponse> ratesByFundingTime = new ConcurrentHashMap<>();

    public LatestFundingRateCache(FundingProperties properties) {
        this(properties, Clock.systemUTC());
    }

    LatestFundingRateCache(FundingProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public boolean update(FundingRateResponse rate) {
        validate(rate, clock.instant());
        String symbol = normalizeSymbol(rate.symbol());
        FundingRateResponse normalized = new FundingRateResponse(symbol, rate.sequence(), rate.fundingRatePpm(),
                rate.premiumRatePpm(), rate.interestRatePpm(), rate.fundingTime(), rate.fundingIntervalHours(),
                rate.status(), rate.eventTime());
        FundingRateKey key = new FundingRateKey(symbol, rate.fundingTime());
        FundingRateResponse updated = ratesByFundingTime.compute(key, (ignored, current) ->
                current == null || newer(normalized, current) ? normalized : current);
        return updated == normalized;
    }

    public boolean update(PerpFundingRateEvent event) {
        if (event == null || event.fundingRate() == null) {
            throw new IllegalArgumentException("funding rate event is required");
        }
        long fundingRatePpm = event.fundingRate().movePointRight(6).longValueExact();
        return update(new FundingRateResponse(event.symbol(), event.sequence(), fundingRatePpm, 0L, 0L,
                event.nextFundingTime(), event.fundingIntervalHours(), "PREDICTED", event.eventTime()));
    }

    public FundingRateResponse requireFresh(String symbol) {
        String normalizedSymbol = normalizeSymbol(symbol);
        FundingRateResponse rate = ratesByFundingTime.entrySet().stream()
                .filter(entry -> entry.getKey().symbol().equals(normalizedSymbol))
                .map(java.util.Map.Entry::getValue)
                .max(Comparator.comparing(FundingRateResponse::fundingTime)
                        .thenComparing(FundingRateResponse::sequence)
                        .thenComparing(FundingRateResponse::eventTime))
                .orElse(null);
        if (rate == null || !fresh(rate, clock.instant())) {
            throw new StaleFundingRateException("funding rate unavailable or stale: " + symbol);
        }
        return rate;
    }

    public List<FundingRateResponse> duePredictions(Instant now) {
        return ratesByFundingTime.values().stream()
                .filter(rate -> rate.fundingTime() != null && !rate.fundingTime().isAfter(now))
                .sorted(Comparator.comparing(FundingRateResponse::fundingTime)
                        .thenComparing(FundingRateResponse::symbol))
                .toList();
    }

    public void removeIfCurrent(FundingRateResponse rate) {
        if (rate != null) {
            ratesByFundingTime.remove(new FundingRateKey(normalizeSymbol(rate.symbol()), rate.fundingTime()), rate);
        }
    }

    private boolean fresh(FundingRateResponse rate, Instant now) {
        return rate.eventTime() != null
                && !rate.eventTime().isAfter(now.plusSeconds(1))
                && !rate.eventTime().isBefore(now.minus(properties.getCalculation().getMaxRateAge()));
    }

    private boolean newer(FundingRateResponse candidate, FundingRateResponse current) {
        return candidate.sequence() > current.sequence()
                || candidate.sequence() == current.sequence()
                && candidate.eventTime().isAfter(current.eventTime());
    }

    private void validate(FundingRateResponse rate, Instant now) {
        if (rate == null || rate.sequence() <= 0 || rate.eventTime() == null || rate.fundingTime() == null
                || rate.fundingIntervalHours() <= 0) {
            throw new IllegalArgumentException("funding rate sequence and timestamps are required");
        }
        if (rate.eventTime().isAfter(now.plusSeconds(1))) {
            throw new IllegalArgumentException("funding rate eventTime is in the future: " + rate.eventTime());
        }
        normalizeSymbol(rate.symbol());
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

    private record FundingRateKey(String symbol, Instant fundingTime) {
    }
}
