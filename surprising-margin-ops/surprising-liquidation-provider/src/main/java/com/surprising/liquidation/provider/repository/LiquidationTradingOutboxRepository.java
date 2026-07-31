package com.surprising.liquidation.provider.repository;

import com.surprising.liquidation.provider.config.LiquidationProperties;
import com.surprising.liquidation.provider.model.TradingOutboxRecord;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 强平交易 outbox 仓储，只负责 {@code trading_outbox_events} 表。 */
@Repository
public class LiquidationTradingOutboxRepository {

    private final JdbcTemplate jdbcTemplate;
    private final LiquidationProperties properties;

    public LiquidationTradingOutboxRepository(JdbcTemplate jdbcTemplate, LiquidationProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties == null ? new LiquidationProperties() : properties;
    }

    public void insertAll(List<NewOutboxEvent> events) {
        if (events == null || events.isEmpty()) {
            return;
        }
        events.forEach(event -> requireCurrentProductTopic(event.topic()));
        int[] rows = jdbcTemplate.batchUpdate("""
                INSERT INTO trading_outbox_events (
                    id, aggregate_type, aggregate_id, topic, event_key, event_type,
                    payload, next_attempt_at, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
                """, events.stream().map(this::arguments).toList());
        requireBatchRows(rows, events.size());
    }

    public void insert(NewOutboxEvent event) {
        requireCurrentProductTopic(event.topic());
        int rows = jdbcTemplate.update("""
                INSERT INTO trading_outbox_events (
                    id, aggregate_type, aggregate_id, topic, event_key, event_type,
                    payload, next_attempt_at, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
                """, arguments(event));
        if (rows != 1) {
            throw new IllegalStateException("写入强平交易 outbox 失败");
        }
    }

    public List<TradingOutboxRecord> claimPending(int limit, Instant leaseUntil, Instant now) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("""
                WITH earliest AS (
                    SELECT DISTINCT ON (topic, event_key)
                           id
                      FROM trading_outbox_events
                     WHERE published_at IS NULL
                       AND aggregate_type = 'LIQUIDATION_ORDER'
                     ORDER BY topic, event_key, id
                ),
                candidates AS (
                    SELECT e.id
                      FROM trading_outbox_events e
                      JOIN earliest c ON c.id = e.id
                     WHERE e.published_at IS NULL
                       AND e.aggregate_type = 'LIQUIDATION_ORDER'
                """);
        appendTopicScope(sql, args);
        sql.append("""
                       AND e.next_attempt_at <= ?
                       AND pg_try_advisory_xact_lock(hashtext(e.topic), hashtext(e.event_key))
                     ORDER BY e.topic, e.event_key, e.id
                     LIMIT ?
                     FOR UPDATE OF e SKIP LOCKED
                )
                UPDATE trading_outbox_events e
                   SET next_attempt_at = ?,
                       updated_at = ?
                  FROM candidates c
                 WHERE e.id = c.id
             RETURNING e.id, e.topic, e.event_key, e.payload::text AS payload
                """);
        args.add(Timestamp.from(now));
        args.add(Math.max(1, limit));
        args.add(Timestamp.from(leaseUntil));
        args.add(Timestamp.from(now));
        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> new TradingOutboxRecord(
                rs.getLong("id"), rs.getString("topic"), rs.getString("event_key"), rs.getString("payload")),
                args.toArray());
    }

    public void markPublished(long id, Instant now) {
        int rows = jdbcTemplate.update("""
                UPDATE trading_outbox_events
                   SET published_at = ?,
                       updated_at = ?,
                       last_error = NULL
                 WHERE id = ?
                """, Timestamp.from(now), Timestamp.from(now), id);
        if (rows != 1) {
            throw new IllegalStateException("标记强平 outbox 已发布失败");
        }
    }

    public void markPublished(List<Long> ids, Instant now) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        List<Long> uniqueIds = ids.stream().distinct().toList();
        Timestamp timestamp = Timestamp.from(now);
        String placeholders = String.join(", ", Collections.nCopies(uniqueIds.size(), "?"));
        String sql = """
                UPDATE trading_outbox_events
                   SET published_at = ?,
                       updated_at = ?,
                       last_error = NULL
                 WHERE published_at IS NULL
                   AND aggregate_type = 'LIQUIDATION_ORDER'
                   AND id IN (%s)
                """.formatted(placeholders);
        List<Object> args = new ArrayList<>(uniqueIds.size() + 2);
        args.add(timestamp);
        args.add(timestamp);
        args.addAll(uniqueIds);
        int rows = jdbcTemplate.update(sql, args.toArray());
        if (rows != uniqueIds.size()) {
            throw new IllegalStateException("批量标记强平 outbox 已发布失败");
        }
    }

    public void markFailed(long id, String error, Instant now) {
        int rows = jdbcTemplate.update("""
                UPDATE trading_outbox_events
                   SET attempts = attempts + 1,
                       last_error = ?,
                       next_attempt_at = ? + (CAST(power(2, LEAST(attempts, 6)) AS INTEGER) * INTERVAL '1 second'),
                       updated_at = ?
                 WHERE id = ?
                """, truncate(error), Timestamp.from(now), Timestamp.from(now), id);
        if (rows != 1) {
            throw new IllegalStateException("标记强平 outbox 发布失败状态失败");
        }
    }

    public int deletePublishedBefore(Instant cutoff, int limit) {
        return jdbcTemplate.update("""
                WITH candidates AS (
                    SELECT id
                      FROM trading_outbox_events
                     WHERE aggregate_type = 'LIQUIDATION_ORDER'
                       AND published_at < ?
                     ORDER BY published_at ASC, id ASC
                     LIMIT ?
                     FOR UPDATE SKIP LOCKED
                )
                DELETE FROM trading_outbox_events e
                 USING candidates c
                 WHERE e.id = c.id
                """, Timestamp.from(cutoff), Math.max(1, limit));
    }

    private Object[] arguments(NewOutboxEvent event) {
        Timestamp timestamp = Timestamp.from(event.createdAt());
        return new Object[]{event.id(), event.aggregateType(), event.aggregateId(), event.topic(), event.eventKey(),
                event.eventType(), event.payload(), timestamp, timestamp, timestamp};
    }

    private void appendTopicScope(StringBuilder sql, List<Object> args) {
        if (!properties.getKafka().isProductTopicsEnabled()) {
            return;
        }
        sql.append("   AND e.topic IN (?, ?)\n");
        args.add(properties.getKafka().getOrderEventsTopic());
        args.add(properties.getKafka().getOrderCommandsTopic());
    }

    private void requireCurrentProductTopic(String topic) {
        LiquidationProperties.Kafka kafka = properties.getKafka();
        if (!kafka.isProductTopicsEnabled()) {
            return;
        }
        if (!kafka.getOrderEventsTopic().equals(topic) && !kafka.getOrderCommandsTopic().equals(topic)) {
            throw new IllegalStateException("强平交易 outbox topic 与当前产品线不一致");
        }
    }

    private void requireBatchRows(int[] rows, int expected) {
        if (rows.length != expected) {
            throw new IllegalStateException("批量写入强平交易 outbox 结果数量不一致");
        }
        for (int row : rows) {
            if (row != 1 && row != Statement.SUCCESS_NO_INFO) {
                throw new IllegalStateException("批量写入强平交易 outbox 失败");
            }
        }
    }

    private String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 1000 ? value : value.substring(0, 1000);
    }

    public record NewOutboxEvent(long id,
                                 String aggregateType,
                                 long aggregateId,
                                 String topic,
                                 String eventKey,
                                 String eventType,
                                 String payload,
                                 Instant createdAt) {
    }
}
