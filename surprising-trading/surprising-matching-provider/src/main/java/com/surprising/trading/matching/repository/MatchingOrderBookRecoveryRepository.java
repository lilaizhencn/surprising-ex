package com.surprising.trading.matching.repository;

import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 恢复 exchange-core 订单簿所需的只读快照。
 *
 * <p>不可拆原因：启动恢复需要同时读取订单及撮合结果权威状态；合约是否可交易由本地不可变快照校验，
 * 避免恢复流程直接读取合约表。</p>
 */
@Repository
public class MatchingOrderBookRecoveryRepository {

    private final JdbcTemplate jdbcTemplate;
    private final MatchingProperties properties;
    private final InstrumentSnapshotCache snapshotCache;

    public MatchingOrderBookRecoveryRepository(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, new MatchingProperties(), null);
    }

    public MatchingOrderBookRecoveryRepository(JdbcTemplate jdbcTemplate, MatchingProperties properties) {
        this(jdbcTemplate, properties, null);
    }

    @Autowired
    public MatchingOrderBookRecoveryRepository(JdbcTemplate jdbcTemplate,
                                               MatchingProperties properties,
                                               @Qualifier("matchingInstrumentSnapshotCache") InstrumentSnapshotCache snapshotCache) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
        this.snapshotCache = snapshotCache;
    }

    public List<RecoveredOrderBookOrder> recoverableOpenOrdersAfter(Instant lastCreatedAt,
                                                                    long lastOrderId,
                                                                    int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT o.order_id, o.user_id, o.symbol, o.instrument_version, o.side, o.time_in_force,
                       o.price_ticks, o.remaining_quantity_steps, o.created_at
                  FROM trading_orders o
                 WHERE o.status IN ('ACCEPTED', 'PARTIALLY_FILLED', 'CANCEL_REQUESTED')
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
        List<RecoveredOrderBookOrder> candidates = jdbcTemplate.query(sql.toString(), (rs, rowNum) -> new RecoveredOrderBookOrder(
                rs.getLong("order_id"),
                rs.getLong("user_id"),
                rs.getString("symbol"),
                rs.getLong("instrument_version"),
                OrderSide.valueOf(rs.getString("side")),
                TimeInForce.valueOf(rs.getString("time_in_force")),
                rs.getLong("price_ticks"),
                rs.getLong("remaining_quantity_steps"),
                rs.getTimestamp("created_at").toInstant()),
                args.toArray());
        if (snapshotCache == null) {
            // 兼容不加载 Spring 快照组件的旧单元测试；正式运行时启动初始化保证快照存在。
            return candidates.stream().limit(Math.max(1, limit)).toList();
        }
        if (!snapshotCache.initialized(properties.getKafka().getProductLine())) {
            throw new IllegalStateException("撮合合约 JVM 快照尚未就绪");
        }
        var productLine = properties.getKafka().getProductLine();
        return candidates.stream()
                .filter(order -> snapshotCache.version(productLine, order.symbol(), order.instrumentVersion())
                        .map(instrument -> instrument.status() == com.surprising.instrument.api.model.InstrumentStatus.TRADING
                                || instrument.status() == com.surprising.instrument.api.model.InstrumentStatus.HALT)
                        .orElse(false))
                .limit(Math.max(1, limit))
                .toList();
    }

    private Optional<String> productLineFilter() {
        MatchingProperties.Kafka kafka = properties.getKafka();
        return kafka.isProductTopicsEnabled()
                ? Optional.of(kafka.getProductLine().name())
                : Optional.empty();
    }
}
