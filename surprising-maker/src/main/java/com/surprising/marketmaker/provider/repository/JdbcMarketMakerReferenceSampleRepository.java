package com.surprising.marketmaker.provider.repository;

import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * market_maker_reference_samples 表的 JDBC 实现。
 */
@Repository
public class JdbcMarketMakerReferenceSampleRepository implements MarketMakerReferenceSampleRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcMarketMakerReferenceSampleRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void record(MarketMakerReferenceSampleWrite sample) {
        jdbcTemplate.update("""
                INSERT INTO market_maker_reference_samples (
                    product_line, strategy_id, symbol, node_id, cycle_sequence, source_name, transport,
                    bid_levels, ask_levels, best_bid_ticks, best_ask_ticks, mid_price_ticks,
                    spread_ticks, received_at, trace_id, sampled_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                sample.productLine().name(),
                sample.strategyId(),
                sample.symbol(),
                sample.nodeId(),
                sample.cycleSequence(),
                truncate(sample.sourceName(), 64),
                truncate(sample.transport(), 32),
                sample.bidLevels(),
                sample.askLevels(),
                sample.bestBidTicks(),
                sample.bestAskTicks(),
                sample.midPriceTicks(),
                sample.spreadTicks(),
                Timestamp.from(sample.receivedAt() == null ? Instant.now() : sample.receivedAt()),
                truncate(sample.traceId(), 128),
                Timestamp.from(sample.sampledAt() == null ? Instant.now() : sample.sampledAt()));
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
