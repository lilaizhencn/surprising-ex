package com.surprising.account.provider.repository;

import com.surprising.account.provider.model.SpotInstrumentSpec;
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

    public Optional<SpotInstrumentSpec> findSpotSpec(String symbol, long instrumentVersion) {
        return jdbcTemplate.query("""
                SELECT version, base_asset, quote_asset, quantity_step_units, notional_multiplier_units
                  FROM instruments
                 WHERE symbol = ?
                   AND version = ?
                   AND instrument_type = 'SPOT'
                   AND contract_type = 'SPOT'
                """, (rs, rowNum) -> new SpotInstrumentSpec(
                rs.getLong("version"),
                rs.getString("base_asset"),
                rs.getString("quote_asset"),
                rs.getLong("quantity_step_units"),
                rs.getLong("notional_multiplier_units")), symbol, instrumentVersion)
                .stream().findFirst();
    }
}
