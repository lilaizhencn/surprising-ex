package com.surprising.price.index.service;

import com.surprising.price.api.model.ExchangeRateResponse;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Component;

/** 指数模块的汇率 JVM 快照；数据库只用于启动恢复、更新落账和历史查询。 */
@Component
public class ExchangeRateSnapshotCache {

    private final ConcurrentMap<CurrencyPair, ExchangeRateResponse> rates = new ConcurrentHashMap<>();
    private volatile boolean ready;

    public boolean ready() {
        return ready;
    }

    public void markNotReady() {
        ready = false;
    }

    public void restore(List<ExchangeRateResponse> snapshot) {
        ready = false;
        Map<CurrencyPair, ExchangeRateResponse> restored = new java.util.HashMap<>();
        if (snapshot != null) {
            for (ExchangeRateResponse rate : snapshot) {
                if (rate != null && rate.rate() != null && rate.rate().signum() > 0) {
                    restored.put(key(rate.baseCurrency(), rate.quoteCurrency()), rate);
                }
            }
        }
        rates.clear();
        rates.putAll(restored);
        ready = true;
    }

    public void put(ExchangeRateResponse rate) {
        if (rate == null || rate.rate() == null || rate.rate().signum() <= 0) {
            return;
        }
        rates.put(key(rate.baseCurrency(), rate.quoteCurrency()), rate);
    }

    public Optional<ExchangeRateResponse> latest(String baseCurrency, String quoteCurrency) {
        if (!ready) {
            return Optional.empty();
        }
        return Optional.ofNullable(rates.get(key(baseCurrency, quoteCurrency)));
    }

    public List<ExchangeRateResponse> byBaseCurrency(String baseCurrency) {
        if (!ready) {
            return List.of();
        }
        String normalizedBase = normalize(baseCurrency);
        return rates.entrySet().stream()
                .filter(entry -> entry.getKey().baseCurrency().equals(normalizedBase))
                .map(Map.Entry::getValue)
                .sorted(java.util.Comparator.comparing(ExchangeRateResponse::quoteCurrency))
                .toList();
    }

    private CurrencyPair key(String baseCurrency, String quoteCurrency) {
        return new CurrencyPair(normalize(baseCurrency), normalize(quoteCurrency));
    }

    private String normalize(String currency) {
        return currency == null ? "" : currency.trim().toUpperCase(Locale.ROOT);
    }

    private record CurrencyPair(String baseCurrency, String quoteCurrency) {
    }
}
