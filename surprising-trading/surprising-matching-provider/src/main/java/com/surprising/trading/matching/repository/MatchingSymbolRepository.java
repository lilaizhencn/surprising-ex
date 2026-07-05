package com.surprising.trading.matching.repository;

import com.surprising.trading.matching.model.InstrumentSymbol;
import com.surprising.trading.matching.model.MatchingSymbol;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class MatchingSymbolRepository {

    private final JdbcTemplate jdbcTemplate;
    private final MatchingSequenceRepository sequenceRepository;

    public MatchingSymbolRepository(JdbcTemplate jdbcTemplate, MatchingSequenceRepository sequenceRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.sequenceRepository = sequenceRepository;
    }

    public List<InstrumentSymbol> currentTradingSymbols() {
        return jdbcTemplate.query("""
                SELECT i.symbol, i.base_asset, i.quote_asset, i.settle_asset
                  FROM instruments i
                  JOIN instrument_current_versions c
                    ON c.symbol = i.symbol AND c.version = i.version
                 WHERE i.status IN ('TRADING', 'HALT', 'SETTLING')
                 ORDER BY i.symbol ASC
                """, (rs, rowNum) -> new InstrumentSymbol(
                rs.getString("symbol"),
                rs.getString("base_asset"),
                rs.getString("quote_asset"),
                rs.getString("settle_asset")));
    }

    public Optional<InstrumentSymbol> currentTradingSymbol(String symbol) {
        return jdbcTemplate.query("""
                SELECT i.symbol, i.base_asset, i.quote_asset, i.settle_asset
                  FROM instruments i
                  JOIN instrument_current_versions c
                    ON c.symbol = i.symbol AND c.version = i.version
                 WHERE i.symbol = ? AND i.status IN ('TRADING', 'HALT', 'SETTLING')
                """, (rs, rowNum) -> new InstrumentSymbol(
                rs.getString("symbol"),
                rs.getString("base_asset"),
                rs.getString("quote_asset"),
                rs.getString("settle_asset")), symbol).stream().findFirst();
    }

    @Transactional
    public MatchingSymbol ensureMatchingSymbol(InstrumentSymbol instrument) {
        int baseCurrencyId = ensureAsset(instrument.baseAsset());
        ensureAsset(instrument.quoteAsset());
        int quoteCurrencyId = ensureAsset(instrument.settleAsset());
        Optional<MatchingSymbol> existing = findMatchingSymbol(instrument.symbol());
        if (existing.isPresent()) {
            return existing.get();
        }

        int symbolId = sequenceRepository.nextIntSequence("matching-symbol");
        Instant now = Instant.now();
        jdbcTemplate.update("""
                INSERT INTO trading_matching_symbols (
                    symbol, symbol_id, base_asset, quote_asset, settle_asset,
                    base_currency_id, quote_currency_id, enabled, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, TRUE, ?, ?)
                ON CONFLICT (symbol) DO NOTHING
                """, instrument.symbol(), symbolId, instrument.baseAsset(), instrument.quoteAsset(), instrument.settleAsset(),
                baseCurrencyId, quoteCurrencyId, Timestamp.from(now), Timestamp.from(now));
        return findMatchingSymbol(instrument.symbol())
                .orElseThrow(() -> new IllegalStateException("failed to ensure matching symbol " + instrument.symbol()));
    }

    public Optional<MatchingSymbol> findMatchingSymbol(String symbol) {
        return jdbcTemplate.query("""
                SELECT symbol, symbol_id, base_currency_id, quote_currency_id
                  FROM trading_matching_symbols
                 WHERE symbol = ? AND enabled = TRUE
                """, (rs, rowNum) -> new MatchingSymbol(
                rs.getString("symbol"),
                rs.getInt("symbol_id"),
                rs.getInt("base_currency_id"),
                rs.getInt("quote_currency_id")), symbol).stream().findFirst();
    }

    private int ensureAsset(String asset) {
        Optional<Integer> existing = jdbcTemplate.query("""
                SELECT asset_id FROM trading_matching_assets WHERE asset = ?
                """, (rs, rowNum) -> rs.getInt("asset_id"), asset).stream().findFirst();
        if (existing.isPresent()) {
            return existing.get();
        }
        int assetId = sequenceRepository.nextIntSequence("matching-asset");
        jdbcTemplate.update("""
                INSERT INTO trading_matching_assets (asset, asset_id, created_at)
                VALUES (?, ?, now())
                ON CONFLICT (asset) DO NOTHING
                """, asset, assetId);
        return jdbcTemplate.query("""
                SELECT asset_id FROM trading_matching_assets WHERE asset = ?
                """, (rs, rowNum) -> rs.getInt("asset_id"), asset).stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("failed to ensure matching asset " + asset));
    }
}
