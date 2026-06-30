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

    public void put(String symbol, IndexPriceProperties.SourceConfig source, SourceQuote quote) {
        quotes.put(key(symbol, source), quote);
    }

    public Optional<SourceQuote> latest(String symbol, IndexPriceProperties.SourceConfig source) {
        return Optional.ofNullable(quotes.get(key(symbol, source)));
    }

    private String key(String symbol, IndexPriceProperties.SourceConfig source) {
        return normalize(symbol) + "|" + normalize(source.getName()) + "|" + normalize(source.getSourceSymbol());
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
