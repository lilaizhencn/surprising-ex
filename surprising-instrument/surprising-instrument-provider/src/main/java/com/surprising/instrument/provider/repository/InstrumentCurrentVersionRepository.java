package com.surprising.instrument.provider.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.OptionalLong;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 只负责 {@code instrument_current_versions} 表。 */
@Repository
public class InstrumentCurrentVersionRepository {

    private final JdbcTemplate jdbcTemplate;

    public InstrumentCurrentVersionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void set(String symbol, long version, Instant now) {
        jdbcTemplate.update("""
                INSERT INTO instrument_current_versions (symbol, version, updated_at)
                VALUES (?, ?, ?)
                ON CONFLICT (symbol) DO UPDATE SET
                    version = EXCLUDED.version,
                    updated_at = EXCLUDED.updated_at
                """, symbol, version, Timestamp.from(now));
    }

    public OptionalLong findVersion(String symbol) {
        List<Long> versions = jdbcTemplate.query(
                "SELECT version FROM instrument_current_versions WHERE symbol = ?",
                (rs, rowNum) -> rs.getLong("version"), symbol);
        return versions.isEmpty() ? OptionalLong.empty() : OptionalLong.of(versions.getFirst());
    }

    public List<InstrumentVersionKey> findAll() {
        return jdbcTemplate.query("""
                SELECT symbol, version
                  FROM instrument_current_versions
                 ORDER BY symbol
                """, (rs, rowNum) -> new InstrumentVersionKey(
                rs.getString("symbol"), rs.getLong("version")));
    }
}
