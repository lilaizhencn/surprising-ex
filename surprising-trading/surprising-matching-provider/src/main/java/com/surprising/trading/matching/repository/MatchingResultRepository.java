package com.surprising.trading.matching.repository;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MatchResultEvent;
import com.surprising.trading.matching.config.MatchingProperties;
import java.sql.Timestamp;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;

/** 只负责 {@code trading_match_results} 表。 */
@Repository
public class MatchingResultRepository {

    private final JdbcTemplate jdbcTemplate;
    private final MatchingProperties properties;

    public MatchingResultRepository(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, new MatchingProperties());
    }

    @Autowired
    public MatchingResultRepository(JdbcTemplate jdbcTemplate, MatchingProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
    }

    public boolean exists(long commandId) {
        Boolean exists = jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM trading_match_results WHERE command_id = ?)",
                Boolean.class, commandId);
        return Boolean.TRUE.equals(exists);
    }

    public Set<Long> existingCommandIds(Collection<Long> commandIds) {
        if (commandIds == null || commandIds.isEmpty()) {
            return Set.of();
        }
        List<Long> uniqueIds = commandIds.stream().distinct().toList();
        Set<Long> existing = new LinkedHashSet<>();
        for (int offset = 0; offset < uniqueIds.size(); offset += 1_000) {
            List<Long> batch = uniqueIds.subList(offset, Math.min(offset + 1_000, uniqueIds.size()));
            String placeholders = String.join(", ", Collections.nCopies(batch.size(), "?"));
            jdbcTemplate.query("""
                    SELECT command_id
                      FROM trading_match_results
                     WHERE command_id IN (%s)
                    """.formatted(placeholders),
                    (RowCallbackHandler) rs -> existing.add(rs.getLong("command_id")), batch.toArray());
        }
        return Set.copyOf(existing);
    }

    public boolean save(MatchResultEvent event) {
        int rows = jdbcTemplate.update("""
                INSERT INTO trading_match_results (
                    command_id, product_line, order_id, user_id, symbol, instrument_version, command_type, result_code,
                    filled_quantity_steps, order_status, trace_id, event_time, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
                ON CONFLICT (command_id) DO NOTHING
                """, event.commandId(), productLine(), event.orderId(), event.userId(), event.symbol(),
                event.instrumentVersion(), event.commandType().name(), event.resultCode(), event.filledQuantitySteps(),
                event.orderStatus().name(), event.traceId(), Timestamp.from(event.eventTime()));
        return rows == 1;
    }

    private String productLine() {
        MatchingProperties.Kafka kafka = properties.getKafka();
        return kafka.isProductTopicsEnabled()
                ? kafka.getProductLine().name()
                : ProductLine.LINEAR_PERPETUAL.name();
    }
}
