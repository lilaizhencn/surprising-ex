package com.surprising.trading.matching.repository;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.matching.config.MatchingProperties;
import com.surprising.trading.matching.model.InstrumentSymbol;
import com.surprising.trading.matching.model.MatchingSymbol;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class MatchingSymbolRepository {

    private final JdbcTemplate jdbcTemplate;
    private final MatchingSequenceRepository sequenceRepository;
    private final MatchingProperties properties;

    public MatchingSymbolRepository(JdbcTemplate jdbcTemplate, MatchingSequenceRepository sequenceRepository) {
        this(jdbcTemplate, sequenceRepository, new MatchingProperties());
    }

    @Autowired
    public MatchingSymbolRepository(JdbcTemplate jdbcTemplate,
                                    MatchingSequenceRepository sequenceRepository,
                                    MatchingProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.sequenceRepository = sequenceRepository;
        this.properties = properties;
    }

    public List<InstrumentSymbol> currentTradingSymbols() {
        StringBuilder sql = new StringBuilder("""
                SELECT i.symbol, i.base_asset, i.quote_asset, i.settle_asset
                  FROM instruments i
                  JOIN instrument_current_versions c
                    ON c.symbol = i.symbol AND c.version = i.version
                 WHERE i.status IN ('TRADING', 'HALT')
                """);
        List<Object> args = new ArrayList<>();
        productContractTypeFilter().ifPresent(contractType -> {
            sql.append("   AND i.contract_type = ?\n");
            args.add(contractType);
        });
        sql.append(" ORDER BY i.symbol ASC");
        return jdbcTemplate.query(sql.toString(), instrumentMapper(), args.toArray());
    }

    public Optional<InstrumentSymbol> currentTradingSymbol(String symbol) {
        StringBuilder sql = new StringBuilder("""
                SELECT i.symbol, i.base_asset, i.quote_asset, i.settle_asset
                  FROM instruments i
                  JOIN instrument_current_versions c
                    ON c.symbol = i.symbol AND c.version = i.version
                 WHERE i.symbol = ? AND i.status IN ('TRADING', 'HALT')
                """);
        List<Object> args = new ArrayList<>();
        args.add(symbol);
        productContractTypeFilter().ifPresent(contractType -> {
            sql.append("   AND i.contract_type = ?\n");
            args.add(contractType);
        });
        return jdbcTemplate.query(sql.toString(), instrumentMapper(), args.toArray()).stream().findFirst();
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
                    product_line, symbol, symbol_id, base_asset, quote_asset, settle_asset,
                    base_currency_id, quote_currency_id, enabled, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, TRUE, ?, ?)
                ON CONFLICT (product_line, symbol) DO NOTHING
                """, productLine(), instrument.symbol(), symbolId, instrument.baseAsset(), instrument.quoteAsset(),
                instrument.settleAsset(), baseCurrencyId, quoteCurrencyId, Timestamp.from(now), Timestamp.from(now));
        return findMatchingSymbol(instrument.symbol())
                .orElseThrow(() -> new IllegalStateException("failed to ensure matching symbol " + instrument.symbol()));
    }

    public Optional<MatchingSymbol> findMatchingSymbol(String symbol) {
        return jdbcTemplate.query("""
                SELECT symbol, symbol_id, base_currency_id, quote_currency_id
                  FROM trading_matching_symbols
                 WHERE product_line = ?
                   AND symbol = ?
                   AND enabled = TRUE
                """, (rs, rowNum) -> new MatchingSymbol(
                rs.getString("symbol"),
                rs.getInt("symbol_id"),
                rs.getInt("base_currency_id"),
                rs.getInt("quote_currency_id")), productLine(), symbol).stream().findFirst();
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

    private Optional<String> productContractTypeFilter() {
        MatchingProperties.Kafka kafka = properties.getKafka();
        return kafka.isProductTopicsEnabled()
                ? Optional.of(kafka.getProductLine().contractTypeCode())
                : Optional.empty();
    }

    private String productLine() {
        MatchingProperties.Kafka kafka = properties.getKafka();
        return kafka.isProductTopicsEnabled()
                ? kafka.getProductLine().name()
                : ProductLine.LINEAR_PERPETUAL.name();
    }

    private RowMapper<InstrumentSymbol> instrumentMapper() {
        return (rs, rowNum) -> new InstrumentSymbol(
                rs.getString("symbol"),
                rs.getString("base_asset"),
                rs.getString("quote_asset"),
                rs.getString("settle_asset"));
    }
}
