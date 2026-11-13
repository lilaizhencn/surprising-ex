package com.surprising.price.index.service;

import com.surprising.price.index.config.IndexPriceProperties;
import com.surprising.price.index.model.SourceQuote;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Component;

@Component
public class LatestSourceQuoteStore {

    private final ConcurrentMap<String, SourceQuote> quotes = new ConcurrentHashMap<>();

    public void put(String instrumentId, IndexPriceProperties.SourceConfig source, SourceQuote quote) {
        quotes.put(key(instrumentId, source), quote);
    }

    public Optional<SourceQuote> latest(String instrumentId, IndexPriceProperties.SourceConfig source) {
        return Optional.ofNullable(quotes.get(key(instrumentId, source)));
    }

    private String key(String instrumentId, IndexPriceProperties.SourceConfig source) {
        return normalize(instrumentId) + "|" + normalize(source.getName()) + "|" + normalize(source.getSourceSymbol());
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
