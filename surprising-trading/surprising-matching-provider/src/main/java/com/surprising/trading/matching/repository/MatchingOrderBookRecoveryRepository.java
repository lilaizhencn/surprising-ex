package com.surprising.trading.matching.repository;

import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.TimeInForce;
import com.surprising.trading.matching.config.MatchingProperties;
import com.surprising.trading.matching.model.RecoveredOrderBookOrder;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class MatchingOrderBookRecoveryRepository {

    private final JdbcTemplate jdbcTemplate;
    private final MatchingProperties properties;

    public MatchingOrderBookRecoveryRepository(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, new MatchingProperties());
    }

    @Autowired
    public MatchingOrderBookRecoveryRepository(JdbcTemplate jdbcTemplate, MatchingProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
    }

    public List<RecoveredOrderBookOrder> recoverableOpenOrdersAfter(Instant lastCreatedAt,
                                                                    long lastOrderId,
                                                                    int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT o.order_id, o.user_id, o.symbol, o.side, o.time_in_force,
                       o.price_ticks, o.remaining_quantity_steps, o.created_at
                  FROM trading_orders o
                  JOIN instruments i
                    ON i.symbol = o.symbol AND i.version = o.instrument_version
                 WHERE i.status IN ('TRADING', 'HALT', 'SETTLING')
                   AND o.status IN ('ACCEPTED', 'PARTIALLY_FILLED', 'CANCEL_REQUESTED')
                   AND o.order_type = 'LIMIT'
                   AND o.time_in_force IN ('GTC', 'GTX')
                   AND o.remaining_quantity_steps > 0
                """);
        List<Object> args = new ArrayList<>();
        productLineFilter().ifPresent(productLine -> {
            sql.append("   AND o.product_line = ?\n");
            args.add(productLine);
        });
        sql.append("""
                   AND EXISTS (
                       SELECT 1
                         FROM trading_match_results r
                        WHERE r.order_id = o.order_id
                          AND r.product_line = o.product_line
                          AND r.command_type = 'PLACE'
                          AND r.result_code = 'SUCCESS'
                   )
                   AND (
                       o.created_at > ?
                       OR (o.created_at = ? AND o.order_id > ?)
                   )
                 ORDER BY o.created_at ASC, o.order_id ASC
                 LIMIT ?
                """);
        args.add(Timestamp.from(lastCreatedAt));
        args.add(Timestamp.from(lastCreatedAt));
        args.add(lastOrderId);
        args.add(Math.max(1, limit));
        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> new RecoveredOrderBookOrder(
                rs.getLong("order_id"),
                rs.getLong("user_id"),
                rs.getString("symbol"),
                OrderSide.valueOf(rs.getString("side")),
                TimeInForce.valueOf(rs.getString("time_in_force")),
                rs.getLong("price_ticks"),
                rs.getLong("remaining_quantity_steps"),
                rs.getTimestamp("created_at").toInstant()),
                args.toArray());
    }

    private Optional<String> productLineFilter() {
        MatchingProperties.Kafka kafka = properties.getKafka();
        return kafka.isProductTopicsEnabled()
                ? Optional.of(kafka.getProductLine().name())
                : Optional.empty();
    }
}
