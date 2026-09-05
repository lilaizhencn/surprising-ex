package com.surprising.instrument.provider.repository;

import com.surprising.instrument.api.model.RiskLimitBracket;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 只负责 {@code instrument_risk_brackets} 表。 */
@Repository
public class InstrumentRiskBracketRepository {

    private static final String INSERT_SQL = """
            INSERT INTO instrument_risk_brackets (
                symbol, version, bracket_no, notional_floor_units, notional_cap_units,
                max_leverage_ppm, initial_margin_rate_ppm, maintenance_margin_rate_ppm,
                option_margin_factor_ppm
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbcTemplate;

    public InstrumentRiskBracketRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insertBatch(String symbol, long version, List<RiskLimitBracket> brackets) {
        if (brackets == null || brackets.isEmpty()) {
            return;
        }
        jdbcTemplate.batchUpdate(INSERT_SQL, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int index) throws java.sql.SQLException {
                RiskLimitBracket bracket = brackets.get(index);
                ps.setString(1, symbol);
                ps.setLong(2, version);
                ps.setInt(3, bracket.bracketNo());
                ps.setLong(4, bracket.notionalFloorUnits());
                ps.setLong(5, bracket.notionalCapUnits());
                ps.setLong(6, bracket.maxLeveragePpm());
                ps.setLong(7, bracket.initialMarginRatePpm());
                ps.setLong(8, bracket.maintenanceMarginRatePpm());
                ps.setLong(9, bracket.optionMarginFactorPpm());
            }

            @Override
            public int getBatchSize() {
                return brackets.size();
            }
        });
    }

    public Map<InstrumentVersionKey, List<RiskLimitBracket>> findAll(List<InstrumentVersionKey> keys) {
        if (keys == null || keys.isEmpty()) {
            return Map.of();
        }
        List<Object> args = new ArrayList<>(keys.size() * 2);
        String tuplePredicate = tuplePredicate(keys, args);
        List<RiskBracketRow> rows = jdbcTemplate.query("""
                SELECT symbol, version, bracket_no, notional_floor_units, notional_cap_units,
                       max_leverage_ppm, initial_margin_rate_ppm, maintenance_margin_rate_ppm,
                       option_margin_factor_ppm
                  FROM instrument_risk_brackets
                 WHERE (symbol, version) IN (%s)
                 ORDER BY symbol, version, bracket_no
                """.formatted(tuplePredicate), (rs, rowNum) -> new RiskBracketRow(
                new InstrumentVersionKey(rs.getString("symbol"), rs.getLong("version")),
                new RiskLimitBracket(
                        rs.getInt("bracket_no"),
                        rs.getLong("notional_floor_units"),
                        rs.getLong("notional_cap_units"),
                        rs.getLong("max_leverage_ppm"),
                        rs.getLong("initial_margin_rate_ppm"),
                        rs.getLong("maintenance_margin_rate_ppm"),
                        rs.getLong("option_margin_factor_ppm"))), args.toArray());
        Map<InstrumentVersionKey, List<RiskLimitBracket>> grouped = new LinkedHashMap<>();
        keys.forEach(key -> grouped.put(key, new ArrayList<>()));
        rows.forEach(row -> grouped.computeIfAbsent(row.key(), ignored -> new ArrayList<>()).add(row.bracket()));
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

    private record RiskBracketRow(InstrumentVersionKey key, RiskLimitBracket bracket) {
    }
}
