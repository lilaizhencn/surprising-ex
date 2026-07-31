package com.surprising.price.mark.repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 只负责 {@code instruments} 表。 */
@Repository
public class MarkInstrumentRepository {

    private final JdbcTemplate jdbcTemplate;

    public MarkInstrumentRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<MarkInstrument> find(String symbol, long version, String contractType) {
        List<Object> args = new ArrayList<>(List.of(symbol, version));
        String productCondition = "";
        if (contractType != null) {
            productCondition = "   AND contract_type = ?\n";
            args.add(contractType);
        }
        return jdbcTemplate.query("""
                SELECT symbol, version, quote_asset, price_tick_units
                  FROM instruments
                 WHERE symbol = ?
                   AND version = ?
                %s
                """.formatted(productCondition), (rs, rowNum) -> new MarkInstrument(
                rs.getString("symbol"),
                rs.getLong("version"),
                rs.getString("quote_asset"),
                rs.getLong("price_tick_units")), args.toArray()).stream().findFirst();
    }

    public record MarkInstrument(String symbol, long version, String quoteAsset, long priceTickUnits) {
    }
}
