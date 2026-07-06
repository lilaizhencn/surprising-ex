package com.surprising.price.index.service;

import com.surprising.price.index.config.IndexPriceProperties;
import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class IndexInstrumentConfigService {

    private static final Logger log = LoggerFactory.getLogger(IndexInstrumentConfigService.class);

    private final IndexPriceProperties properties;
    private final JdbcTemplate jdbcTemplate;
    private volatile List<IndexPriceProperties.SymbolConfig> symbols = List.of();

    public IndexInstrumentConfigService(IndexPriceProperties properties, JdbcTemplate jdbcTemplate) {
        this.properties = properties;
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void initialize() {
        refresh();
    }

    @Scheduled(fixedDelayString = "${surprising.price.index.instrument.refresh-delay-ms:30000}")
    public void refresh() {
        if (!properties.getInstrument().isEnabled()) {
            symbols = List.copyOf(properties.getSymbols());
            return;
        }
        try {
            List<IndexPriceProperties.SymbolConfig> loaded = loadFromInstrumentSnapshot();
            if (!loaded.isEmpty()) {
                symbols = loaded;
                return;
            }
            if (properties.getInstrument().isFallbackToStaticSymbols()) {
                symbols = List.copyOf(properties.getSymbols());
            }
        } catch (Exception ex) {
            log.error("Failed to refresh index instrument snapshot; keeping previous snapshot", ex);
            if (symbols.isEmpty() && properties.getInstrument().isFallbackToStaticSymbols()) {
                symbols = List.copyOf(properties.getSymbols());
            }
        }
    }

    public List<IndexPriceProperties.SymbolConfig> symbols() {
        if (symbols.isEmpty() && properties.getInstrument().isFallbackToStaticSymbols()) {
            return properties.getSymbols();
        }
        return symbols;
    }

    private List<IndexPriceProperties.SymbolConfig> loadFromInstrumentSnapshot() {
        List<Object> args = new ArrayList<>();
        String productCondition = productCondition(args);
        String sql = """
                SELECT i.symbol, i.min_valid_index_sources,
                       s.source, s.enabled, s.base_url, s.path, s.source_symbol, s.parser,
                       s.quote_currency, s.target_quote_currency, s.conversion_base_url, s.conversion_path,
                       s.conversion_parser, s.conversion_mode, s.conversion_operation, s.fallback_weight_multiplier_ppm,
                       s.websocket_enabled, s.websocket_url, s.websocket_subscribe_message, s.websocket_parser, s.weight_ppm
                  FROM instruments i
                  JOIN instrument_current_versions c
                    ON c.symbol = i.symbol AND c.version = i.version
                  JOIN instrument_index_sources s
                    ON s.symbol = i.symbol AND s.version = i.version
                 WHERE %s
                   AND i.status = 'TRADING'
                   AND s.enabled = TRUE
                 ORDER BY i.symbol ASC, s.source ASC
                """.formatted(productCondition);
        Map<String, IndexPriceProperties.SymbolConfig> bySymbol = new LinkedHashMap<>();
        jdbcTemplate.query(sql, args.toArray(), rs -> {
            String symbol = rs.getString("symbol");
            IndexPriceProperties.SymbolConfig symbolConfig = bySymbol.computeIfAbsent(symbol, ignored -> {
                IndexPriceProperties.SymbolConfig config = new IndexPriceProperties.SymbolConfig();
                config.setSymbol(symbol);
                try {
                    config.setMinValidSources(rs.getInt("min_valid_index_sources"));
                } catch (java.sql.SQLException ex) {
                    throw new IllegalStateException(ex);
                }
                config.setSources(new ArrayList<>());
                return config;
            });
            symbolConfig.getSources().add(toSource(rs));
        });
        return bySymbol.values().stream()
                .filter(symbol -> !symbol.getSources().isEmpty())
                .map(this::copySymbol)
                .toList();
    }

    private String productCondition(List<Object> args) {
        if (properties.getKafka().isProductTopicsEnabled()) {
            args.add(properties.getKafka().getProductLine().contractTypeCode());
            return "i.contract_type = ?";
        }
        return "i.instrument_type = 'PERPETUAL'";
    }

    private IndexPriceProperties.SourceConfig toSource(java.sql.ResultSet rs) throws java.sql.SQLException {
        IndexPriceProperties.SourceConfig source = new IndexPriceProperties.SourceConfig();
        source.setName(rs.getString("source"));
        source.setEnabled(rs.getBoolean("enabled"));
        source.setBaseUrl(rs.getString("base_url"));
        source.setPath(rs.getString("path"));
        source.setSourceSymbol(rs.getString("source_symbol"));
        source.setParser(rs.getString("parser"));
        source.setQuoteCurrency(rs.getString("quote_currency"));
        source.setTargetQuoteCurrency(rs.getString("target_quote_currency"));
        source.setConversionBaseUrl(rs.getString("conversion_base_url"));
        source.setConversionPath(rs.getString("conversion_path"));
        source.setConversionParser(rs.getString("conversion_parser"));
        source.setConversionMode(rs.getString("conversion_mode"));
        source.setConversionOperation(rs.getString("conversion_operation"));
        source.setFallbackWeightMultiplier(ppm(rs.getLong("fallback_weight_multiplier_ppm"), BigDecimal.ONE));
        source.setWebsocketEnabled(rs.getBoolean("websocket_enabled"));
        source.setWebsocketUrl(rs.getString("websocket_url"));
        source.setWebsocketSubscribeMessage(rs.getString("websocket_subscribe_message"));
        source.setWebsocketParser(rs.getString("websocket_parser"));
        source.setWeight(ppm(rs.getLong("weight_ppm"), BigDecimal.ONE));
        return source;
    }

    private BigDecimal ppm(long value, BigDecimal fallback) {
        return value > 0 ? BigDecimal.valueOf(value, 6) : fallback;
    }

    private IndexPriceProperties.SymbolConfig copySymbol(IndexPriceProperties.SymbolConfig source) {
        IndexPriceProperties.SymbolConfig copy = new IndexPriceProperties.SymbolConfig();
        copy.setSymbol(source.getSymbol());
        copy.setMinValidSources(source.getMinValidSources());
        copy.setSources(List.copyOf(source.getSources()));
        return copy;
    }
}
