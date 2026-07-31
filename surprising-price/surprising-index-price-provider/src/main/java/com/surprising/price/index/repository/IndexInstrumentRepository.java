package com.surprising.price.index.repository;

import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 只负责 {@code instruments} 表。 */
@Repository
public class IndexInstrumentRepository {

    private final JdbcTemplate jdbcTemplate;

    public IndexInstrumentRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<IndexInstrument> findTradingVersions(String contractType) {
        List<Object> args = new ArrayList<>();
        String productCondition;
        if (contractType == null) {
            productCondition = "instrument_type = 'PERPETUAL'";
        } else {
            productCondition = "contract_type = ?";
            args.add(contractType);
        }
        return jdbcTemplate.query("""
                SELECT symbol, version, min_valid_index_sources
                  FROM instruments
                 WHERE %s
                   AND status = 'TRADING'
                 ORDER BY symbol ASC, version ASC
                """.formatted(productCondition), (rs, rowNum) -> new IndexInstrument(
                rs.getString("symbol"),
                rs.getLong("version"),
                rs.getInt("min_valid_index_sources")), args.toArray());
    }

    public record IndexInstrument(String symbol, long version, int minValidSources) {

        public IndexInstrumentKey key() {
            return new IndexInstrumentKey(symbol, version);
        }
    }
}
