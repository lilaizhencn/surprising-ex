package com.surprising.candlestick.provider.repository;

import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 只负责兼容模式下的 {@code candlestick_symbols} 表。
 */
@Repository
public class CandlestickSymbolRepository {

    private final JdbcTemplate jdbcTemplate;

    public CandlestickSymbolRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Set<String> findEnabledSymbols() {
        return jdbcTemplate.query("""
                SELECT symbol
                  FROM candlestick_symbols
                 WHERE enabled = TRUE
                """, (rs, rowNum) -> rs.getString("symbol"))
                .stream()
                .collect(Collectors.toUnmodifiableSet());
    }
}
