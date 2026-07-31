package com.surprising.price.mark.repository;

import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 只负责 {@code instrument_current_versions} 表。 */
@Repository
public class MarkInstrumentCurrentVersionRepository {

    private final JdbcTemplate jdbcTemplate;

    public MarkInstrumentCurrentVersionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<Long> findVersion(String symbol) {
        return jdbcTemplate.query("""
                SELECT version
                  FROM instrument_current_versions
                 WHERE symbol = ?
                """, (rs, rowNum) -> rs.getLong("version"), symbol).stream().findFirst();
    }
}
