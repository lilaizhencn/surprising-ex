package com.surprising.candlestick.provider.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 只负责 {@code instruments} 表。
 */
@Repository
public class CandlestickInstrumentRepository {

    private final JdbcTemplate jdbcTemplate;

    public CandlestickInstrumentRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<InstrumentDefinition> find(String symbol, long version) {
        return jdbcTemplate.query("""
                SELECT symbol, version, base_asset, quote_asset, price_tick_units, quantity_step_units
                  FROM instruments
                 WHERE symbol = ?
                   AND version = ?
                """, (rs, rowNum) -> toDefinition(rs), symbol, version).stream().findFirst();
    }

    public List<InstrumentVersion> findEnabledPerpetualVersions() {
        return jdbcTemplate.query("""
                SELECT symbol, version
                  FROM instruments
                 WHERE instrument_type = 'PERPETUAL'
                   AND status IN ('PRE_TRADING', 'TRADING', 'HALT')
                """, (rs, rowNum) -> new InstrumentVersion(
                        rs.getString("symbol"),
                        rs.getLong("version")));
    }

    public List<InstrumentVersion> findEnabledVersionsByContractType(String contractType) {
        return jdbcTemplate.query("""
                SELECT symbol, version
                  FROM instruments
                 WHERE contract_type = ?
                   AND status IN ('PRE_TRADING', 'TRADING', 'HALT')
                """, (rs, rowNum) -> new InstrumentVersion(
                        rs.getString("symbol"),
                        rs.getLong("version")), contractType);
    }

    private InstrumentDefinition toDefinition(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new InstrumentDefinition(
                rs.getString("symbol"),
                rs.getLong("version"),
                rs.getString("base_asset"),
                rs.getString("quote_asset"),
                rs.getLong("price_tick_units"),
                rs.getLong("quantity_step_units"));
    }

    public record InstrumentDefinition(
            String symbol,
            long version,
            String baseAsset,
            String quoteAsset,
            long priceTickUnits,
            long quantityStepUnits) {
    }

    public record InstrumentVersion(String symbol, long version) {
    }
}
