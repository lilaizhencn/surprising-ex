package com.surprising.price.index.repository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 只负责 {@code instrument_index_sources} 表。 */
@Repository
public class IndexInstrumentSourceRepository {

    private final JdbcTemplate jdbcTemplate;

    public IndexInstrumentSourceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Map<IndexInstrumentKey, List<IndexSource>> findEnabled(List<IndexInstrumentKey> keys) {
        if (keys == null || keys.isEmpty()) {
            return Map.of();
        }
        List<Object> args = new ArrayList<>(keys.size() * 2);
        String tuplePredicate = tuplePredicate(keys, args);
        List<IndexSourceRow> rows = jdbcTemplate.query("""
                SELECT symbol, version, source, enabled, base_url, path, source_symbol, parser,
                       quote_currency, target_quote_currency, conversion_base_url, conversion_path,
                       conversion_parser, conversion_mode, conversion_operation,
                       fallback_weight_multiplier_ppm, websocket_enabled, websocket_url,
                       websocket_subscribe_message, websocket_parser, weight_ppm
                  FROM instrument_index_sources
                 WHERE enabled = TRUE
                   AND (symbol, version) IN (%s)
                 ORDER BY symbol ASC, version ASC, source ASC
                """.formatted(tuplePredicate), (rs, rowNum) -> new IndexSourceRow(
                new IndexInstrumentKey(rs.getString("symbol"), rs.getLong("version")),
                new IndexSource(
                        rs.getString("source"),
                        rs.getBoolean("enabled"),
                        rs.getString("base_url"),
                        rs.getString("path"),
                        rs.getString("source_symbol"),
                        rs.getString("parser"),
                        rs.getString("quote_currency"),
                        rs.getString("target_quote_currency"),
                        rs.getString("conversion_base_url"),
                        rs.getString("conversion_path"),
                        rs.getString("conversion_parser"),
                        rs.getString("conversion_mode"),
                        rs.getString("conversion_operation"),
                        rs.getLong("fallback_weight_multiplier_ppm"),
                        rs.getBoolean("websocket_enabled"),
                        rs.getString("websocket_url"),
                        rs.getString("websocket_subscribe_message"),
                        rs.getString("websocket_parser"),
                        rs.getLong("weight_ppm"))), args.toArray());
        Map<IndexInstrumentKey, List<IndexSource>> grouped = new LinkedHashMap<>();
        keys.forEach(key -> grouped.put(key, new ArrayList<>()));
        rows.forEach(row -> grouped.computeIfAbsent(row.key(), ignored -> new ArrayList<>()).add(row.source()));
        return grouped;
    }

    private String tuplePredicate(List<IndexInstrumentKey> keys, List<Object> args) {
        StringBuilder sql = new StringBuilder();
        for (int index = 0; index < keys.size(); index++) {
            if (index > 0) {
                sql.append(", ");
            }
            sql.append("(?, ?)");
            IndexInstrumentKey key = keys.get(index);
            args.add(key.symbol());
            args.add(key.version());
        }
        return sql.toString();
    }

    public record IndexSource(
            String name,
            boolean enabled,
            String baseUrl,
            String path,
            String sourceSymbol,
            String parser,
            String quoteCurrency,
            String targetQuoteCurrency,
            String conversionBaseUrl,
            String conversionPath,
            String conversionParser,
            String conversionMode,
            String conversionOperation,
            long fallbackWeightMultiplierPpm,
            boolean websocketEnabled,
            String websocketUrl,
            String websocketSubscribeMessage,
            String websocketParser,
            long weightPpm) {
    }

    private record IndexSourceRow(IndexInstrumentKey key, IndexSource source) {
    }
}
