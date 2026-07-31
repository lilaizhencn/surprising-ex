package com.surprising.trading.matching.repository;

import com.surprising.trading.matching.model.InstrumentSymbol;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 只负责 {@code instruments} 表。
 */
@Repository
public class MatchingInstrumentRepository {

    private final JdbcTemplate jdbcTemplate;

    public MatchingInstrumentRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<InstrumentVersion> findTradingVersions(String contractType) {
        List<Object> args = new ArrayList<>();
        String productCondition = "";
        if (contractType != null) {
            productCondition = "   AND contract_type = ?\n";
            args.add(contractType);
        }
        return jdbcTemplate.query("""
                SELECT symbol, version, base_asset, quote_asset, settle_asset
                  FROM instruments
                 WHERE status IN ('TRADING', 'HALT')
                %s
                 ORDER BY symbol ASC, version ASC
                """.formatted(productCondition), (rs, rowNum) -> toVersion(rs), args.toArray());
    }

    public Optional<InstrumentVersion> findTrading(String symbol, long version, String contractType) {
        List<Object> args = new ArrayList<>(List.of(symbol, version));
        String productCondition = "";
        if (contractType != null) {
            productCondition = "   AND contract_type = ?\n";
            args.add(contractType);
        }
        return jdbcTemplate.query("""
                SELECT symbol, version, base_asset, quote_asset, settle_asset
                  FROM instruments
                 WHERE symbol = ?
                   AND version = ?
                   AND status IN ('TRADING', 'HALT')
                %s
                """.formatted(productCondition), (rs, rowNum) -> toVersion(rs), args.toArray())
                .stream()
                .findFirst();
    }

    private InstrumentVersion toVersion(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new InstrumentVersion(
                rs.getString("symbol"),
                rs.getLong("version"),
                rs.getString("base_asset"),
                rs.getString("quote_asset"),
                rs.getString("settle_asset"));
    }

    public record InstrumentVersion(
            String symbol,
            long version,
            String baseAsset,
            String quoteAsset,
            String settleAsset) {

        public InstrumentSymbol toInstrumentSymbol() {
            return new InstrumentSymbol(symbol, baseAsset, quoteAsset, settleAsset);
        }
    }
}
