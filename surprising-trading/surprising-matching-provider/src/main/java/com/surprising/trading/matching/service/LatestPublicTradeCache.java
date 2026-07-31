package com.surprising.trading.matching.service;

import com.surprising.trading.api.model.PublicTradeEvent;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Component;

/** 撮合进程内的最新公共成交缓存；成交查询不需要为展示数据访问主库。 */
@Component
public class LatestPublicTradeCache {

    private final ConcurrentMap<String, PublicTradeEvent> latestBySymbol = new ConcurrentHashMap<>();

    public void put(PublicTradeEvent event) {
        if (event == null || event.symbol() == null || event.symbol().isBlank()) {
            return;
        }
        String symbol = event.symbol().trim().toUpperCase(Locale.ROOT);
        latestBySymbol.compute(symbol, (ignored, previous) -> {
            if (previous == null || event.sequence() >= previous.sequence()) {
                return event;
            }
            return previous;
        });
    }

    public Optional<PublicTradeEvent> latest(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(latestBySymbol.get(symbol.trim().toUpperCase(Locale.ROOT)));
    }

    public int size() {
        return latestBySymbol.size();
    }
}
