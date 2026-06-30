package com.surprising.candlestick.provider.service;

import com.surprising.candlestick.provider.aggregation.CandleKey;
import com.surprising.candlestick.provider.config.CandlestickProperties;
import jakarta.annotation.PostConstruct;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
/**
 * Runtime symbol gate for dynamic market enablement.
 *
 * <p>By default the service accepts new symbols automatically. In strict mode it periodically
 * refreshes {@code candlestick_symbols} so operations can enable or disable markets without
 * restarting K-line nodes.</p>
 */
public class SymbolRegistryService {

    private static final Logger log = LoggerFactory.getLogger(SymbolRegistryService.class);

    private final CandlestickProperties properties;
    private final JdbcTemplate jdbcTemplate;
    private volatile Set<String> enabledSymbols = Set.of();

    public SymbolRegistryService(CandlestickProperties properties, JdbcTemplate jdbcTemplate) {
        this.properties = properties;
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void initialize() {
        refresh();
    }

    /**
     * Refreshes the enabled symbol snapshot in strict registry mode.
     */
    @Scheduled(fixedDelayString = "${surprising.candlestick.symbols.refresh-delay-ms:30000}")
    public void refresh() {
        if (properties.getSymbols().isAcceptUnknownSymbols()) {
            return;
        }
        try {
            Set<String> symbols = ConcurrentHashMap.newKeySet();
            jdbcTemplate.query("SELECT symbol FROM candlestick_symbols WHERE enabled = TRUE", rs -> {
                symbols.add(CandleKey.normalizeSymbol(rs.getString("symbol")));
            });
            enabledSymbols = Set.copyOf(symbols);
        } catch (Exception ex) {
            log.error("Failed to refresh candlestick symbol registry; keeping previous snapshot", ex);
        }
    }

    public boolean isEnabled(String symbol) {
        if (properties.getSymbols().isAcceptUnknownSymbols()) {
            return true;
        }
        return enabledSymbols.contains(CandleKey.normalizeSymbol(symbol));
    }
}
