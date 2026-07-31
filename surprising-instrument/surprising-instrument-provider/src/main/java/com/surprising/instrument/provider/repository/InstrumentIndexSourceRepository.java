package com.surprising.instrument.provider.repository;

import com.surprising.instrument.api.model.IndexSourceConfig;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 只负责 {@code instrument_index_sources} 表。 */
@Repository
public class InstrumentIndexSourceRepository {

    private static final String INSERT_SQL = """
            INSERT INTO instrument_index_sources (
                symbol, version, source, enabled, base_url, path, source_symbol, parser,
                quote_currency, target_quote_currency, conversion_base_url, conversion_path,
                conversion_parser, conversion_mode, conversion_operation, fallback_weight_multiplier_ppm,
                websocket_enabled, websocket_url, websocket_subscribe_message, websocket_parser, weight_ppm
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbcTemplate;

    public InstrumentIndexSourceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insertBatch(String symbol, long version, List<IndexSourceConfig> sources) {
        if (sources == null || sources.isEmpty()) {
            return;
        }
        jdbcTemplate.batchUpdate(INSERT_SQL, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int index) throws java.sql.SQLException {
                IndexSourceConfig source = sources.get(index);
                ps.setString(1, symbol);
                ps.setLong(2, version);
                ps.setString(3, source.source());
                ps.setBoolean(4, source.enabled());
                ps.setString(5, source.baseUrl());
                ps.setString(6, source.path());
                ps.setString(7, source.sourceSymbol());
                ps.setString(8, source.parser());
                ps.setString(9, defaultText(source.quoteCurrency(), "USDT"));
                ps.setString(10, defaultText(source.targetQuoteCurrency(), "USDT"));
                ps.setString(11, source.conversionBaseUrl());
                ps.setString(12, source.conversionPath());
                ps.setString(13, source.conversionParser());
                ps.setString(14, defaultText(source.conversionMode(), "DISCOUNT"));
                ps.setString(15, defaultText(source.conversionOperation(), "MULTIPLY"));
                ps.setLong(16, positiveOrDefault(source.fallbackWeightMultiplierPpm(), 500_000L));
                ps.setBoolean(17, source.websocketEnabled());
                ps.setString(18, source.websocketUrl());
                ps.setString(19, source.websocketSubscribeMessage());
                ps.setString(20, source.websocketParser());
                ps.setLong(21, source.weightPpm());
            }

            @Override
            public int getBatchSize() {
                return sources.size();
            }
        });
    }

    public Map<InstrumentVersionKey, List<IndexSourceConfig>> findAll(List<InstrumentVersionKey> keys) {
        if (keys == null || keys.isEmpty()) {
            return Map.of();
        }
        List<Object> args = new ArrayList<>(keys.size() * 2);
        String tuplePredicate = tuplePredicate(keys, args);
        List<IndexSourceRow> rows = jdbcTemplate.query("""
                SELECT *
                  FROM instrument_index_sources
                 WHERE (symbol, version) IN (%s)
                 ORDER BY symbol, version, source
                """.formatted(tuplePredicate), (rs, rowNum) -> new IndexSourceRow(
                new InstrumentVersionKey(rs.getString("symbol"), rs.getLong("version")),
                new IndexSourceConfig(
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
        Map<InstrumentVersionKey, List<IndexSourceConfig>> grouped = new LinkedHashMap<>();
        keys.forEach(key -> grouped.put(key, new ArrayList<>()));
        rows.forEach(row -> grouped.computeIfAbsent(row.key(), ignored -> new ArrayList<>()).add(row.source()));
        return grouped;
    }

    private String tuplePredicate(List<InstrumentVersionKey> keys, List<Object> args) {
        StringBuilder sql = new StringBuilder();
        for (int index = 0; index < keys.size(); index++) {
            if (index > 0) {
                sql.append(", ");
            }
            sql.append("(?, ?)");
            InstrumentVersionKey key = keys.get(index);
            args.add(key.symbol());
            args.add(key.version());
        }
        return sql.toString();
    }

    private String defaultText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private long positiveOrDefault(long value, long fallback) {
        return value > 0 ? value : fallback;
    }

    private record IndexSourceRow(InstrumentVersionKey key, IndexSourceConfig source) {
    }
}
