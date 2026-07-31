package com.surprising.trading.matching.repository;

import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 只负责 {@code instrument_current_versions} 表。
 */
@Repository
public class MatchingInstrumentCurrentVersionRepository {

    private final JdbcTemplate jdbcTemplate;

    public MatchingInstrumentCurrentVersionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Map<String, Long> findAll() {
        return jdbcTemplate.query("""
                SELECT symbol, version
                  FROM instrument_current_versions
                """, (rs, rowNum) -> new CurrentVersion(
                        rs.getString("symbol"),
                        rs.getLong("version")))
                .stream()
                .collect(Collectors.toUnmodifiableMap(CurrentVersion::symbol, CurrentVersion::version));
    }

    public Optional<Long> findVersion(String symbol) {
        return jdbcTemplate.query("""
                SELECT version
                  FROM instrument_current_versions
                 WHERE symbol = ?
                """, (rs, rowNum) -> rs.getLong("version"), symbol).stream().findFirst();
    }

    private record CurrentVersion(String symbol, long version) {
    }
}
