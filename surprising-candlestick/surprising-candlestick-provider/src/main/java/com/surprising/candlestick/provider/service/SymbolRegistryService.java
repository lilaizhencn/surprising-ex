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
 * <p>By default strict mode reads the current instrument snapshot. Legacy deployments can switch
 * {@code surprising.candlestick.symbols.source} back to {@code CANDLESTICK_SYMBOLS}.</p>
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
            jdbcTemplate.query(symbolQuery(), rs -> {
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

    private String symbolQuery() {
        String source = properties.getSymbols().getSource();
        if ("CANDLESTICK_SYMBOLS".equalsIgnoreCase(source)) {
            return "SELECT symbol FROM candlestick_symbols WHERE enabled = TRUE";
        }
        return """
                SELECT i.symbol
                  FROM instruments i
                  JOIN instrument_current_versions c
                    ON c.symbol = i.symbol AND c.version = i.version
                 WHERE i.instrument_type = 'PERPETUAL'
                   AND i.status IN ('PRE_TRADING', 'TRADING', 'HALT')
                """;
    }
}
