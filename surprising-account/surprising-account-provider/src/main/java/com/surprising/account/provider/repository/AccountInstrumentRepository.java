package com.surprising.account.provider.repository;

import com.surprising.account.provider.model.SpotInstrumentSpec;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.instrument.api.model.InstrumentType;
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

    public Optional<ContractInstrumentRow> findContractSpec(String symbol, long instrumentVersion) {
        return jdbcTemplate.query("""
                SELECT version, contract_type, settle_asset, notional_multiplier_units,
                       price_tick_units, initial_margin_rate_ppm, maker_fee_rate_ppm,
                       taker_fee_rate_ppm
                  FROM instruments
                 WHERE symbol = ?
                   AND version = ?
                """, (rs, rowNum) -> new ContractInstrumentRow(
                        rs.getLong("version"),
                        ContractType.valueOf(rs.getString("contract_type")),
                        rs.getString("settle_asset"),
                        rs.getLong("notional_multiplier_units"),
                        rs.getLong("price_tick_units"),
                        rs.getLong("initial_margin_rate_ppm"),
                        rs.getLong("maker_fee_rate_ppm"),
                        rs.getLong("taker_fee_rate_ppm")), symbol, instrumentVersion)
                .stream().findFirst();
    }

    public Optional<InstrumentType> findInstrumentType(String symbol, long instrumentVersion) {
        return jdbcTemplate.query("""
                SELECT instrument_type
                  FROM instruments
                 WHERE symbol = ?
                   AND version = ?
                """, (rs, rowNum) -> InstrumentType.valueOf(rs.getString("instrument_type")),
                        symbol, instrumentVersion)
                .stream().findFirst();
    }

    public record ContractInstrumentRow(
            long version,
            ContractType contractType,
            String settleAsset,
            long notionalMultiplierUnits,
            long priceTickUnits,
            long initialMarginRatePpm,
            long makerFeeRatePpm,
            long takerFeeRatePpm) {
    }
}
