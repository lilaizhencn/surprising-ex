package com.surprising.trading.matching.repository;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.matching.config.MatchingProperties;
import com.surprising.trading.matching.model.InstrumentSymbol;
import com.surprising.trading.matching.model.MatchingSymbol;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 只负责 {@code trading_matching_symbols} 表。 */
@Repository
public class MatchingSymbolRepository {

    private final JdbcTemplate jdbcTemplate;
    private final MatchingProperties properties;

    public MatchingSymbolRepository(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, new MatchingProperties());
    }

    @Autowired
    public MatchingSymbolRepository(JdbcTemplate jdbcTemplate, MatchingProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
    }

    public void insert(InstrumentSymbol instrument,
                       int symbolId,
                       int baseCurrencyId,
                       int quoteCurrencyId,
                       Instant now) {
        jdbcTemplate.update("""
                INSERT INTO trading_matching_symbols (
                    product_line, symbol, symbol_id, base_asset, quote_asset, settle_asset,
                    base_currency_id, quote_currency_id, enabled, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, TRUE, ?, ?)
                ON CONFLICT (product_line, symbol) DO NOTHING
                """, productLine(), instrument.symbol(), symbolId, instrument.baseAsset(), instrument.quoteAsset(),
                instrument.settleAsset(), baseCurrencyId, quoteCurrencyId,
                Timestamp.from(now), Timestamp.from(now));
    }

    public Optional<MatchingSymbol> find(String symbol) {
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

    private String productLine() {
        MatchingProperties.Kafka kafka = properties.getKafka();
        return kafka.isProductTopicsEnabled()
                ? kafka.getProductLine().name()
                : ProductLine.LINEAR_PERPETUAL.name();
    }
}
