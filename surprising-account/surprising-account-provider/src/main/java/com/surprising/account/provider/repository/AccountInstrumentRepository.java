package com.surprising.account.provider.repository;

import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AccountInstrumentRepository {

    private final JdbcTemplate jdbcTemplate;

    public AccountInstrumentRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<String> findSettleAsset(String symbol, long instrumentVersion) {
        return jdbcTemplate.query("""
                SELECT settle_asset
                  FROM instruments
                 WHERE symbol = ?
                   AND version = ?
                """, (rs, rowNum) -> rs.getString("settle_asset"), symbol, instrumentVersion)
                .stream().findFirst();
    }
}
