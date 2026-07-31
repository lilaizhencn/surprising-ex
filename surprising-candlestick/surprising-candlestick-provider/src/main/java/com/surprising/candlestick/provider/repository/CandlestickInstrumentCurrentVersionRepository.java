package com.surprising.candlestick.provider.repository;

import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 只负责 {@code instrument_current_versions} 表。
 */
@Repository
public class CandlestickInstrumentCurrentVersionRepository {

    private final JdbcTemplate jdbcTemplate;

    public CandlestickInstrumentCurrentVersionRepository(JdbcTemplate jdbcTemplate) {
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

    private record CurrentVersion(String symbol, long version) {
    }
}
