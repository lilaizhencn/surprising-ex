package com.surprising.account.provider.repository;

import com.surprising.account.provider.config.AccountProperties;
import com.surprising.account.provider.model.AccountOutboxRecord;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 账户侧事件的事务 outbox。
 *
 * <p>持仓更新与账户结算在同一数据库事务内写入，之后由定时发布器发送到 Kafka。
 * 这样可以让 WebSocket 持仓推送以下游权威账户状态为准，而不是直接从原始撮合事件发布。</p>
 */
@Repository
public class AccountOutboxRepository {

    private final JdbcTemplate jdbcTemplate;
    private final AccountProperties properties;

    public AccountOutboxRepository(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, new AccountProperties());
    }

    @Autowired
    public AccountOutboxRepository(JdbcTemplate jdbcTemplate,
                                   AccountProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties == null ? new AccountProperties() : properties;
    }

    public void insert(String productLine,
                       String aggregateType,
                       long aggregateId,
                       String topic,
                       String eventKey,
                       String eventType,
                       String serializedPayload,
                       Instant now) {
        int rows = jdbcTemplate.update("""
                INSERT INTO account_outbox_events (
                    product_line, aggregate_type, aggregate_id, topic, event_key, event_type, payload,
                    next_attempt_at, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
                """, productLine, aggregateType, aggregateId, topic, eventKey, eventType, serializedPayload,
                Timestamp.from(now),
                Timestamp.from(now), Timestamp.from(now));
        requireSingleRow(rows, "account outbox enqueue");
    }

    public List<AccountOutboxRecord> claimPending(int limit, Instant leaseUntil, Instant now) {
        int effectiveLimit = Math.max(1, limit);
        int perKeyLimit = Math.max(1, Math.min(properties.getOutbox().getMaxRowsPerKey(), effectiveLimit));
        int lockedKeyLimit = lockedKeyLimit(effectiveLimit, perKeyLimit);
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("""
                WITH pending AS MATERIALIZED (
                    SELECT e.id,
                           e.topic,
                           e.event_key,
                           e.next_attempt_at,
                           row_number() OVER key_order AS key_rank,
                           bool_or(e.next_attempt_at > ?) OVER key_order AS blocked_by_retry
                      FROM account_outbox_events e
                     WHERE e.published_at IS NULL
                """);
        args.add(Timestamp.from(now));
        appendTopicScope(sql, "e", args);
        sql.append("""
                    WINDOW key_order AS (
                        PARTITION BY e.topic, e.event_key
                        ORDER BY e.id
                        ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW
                    )
                ),
                earliest AS MATERIALIZED (
                    SELECT topic,
                           event_key,
                           id AS first_id,
                           next_attempt_at
                      FROM pending
                     WHERE key_rank = 1
                ),
                locked_keys AS MATERIALIZED (
                    SELECT topic, event_key, first_id
                      FROM earliest
                     WHERE next_attempt_at <= ?
                       AND pg_try_advisory_xact_lock(hashtext(topic), hashtext(event_key))
                     ORDER BY first_id
                     LIMIT ?
                ),
                candidates AS MATERIALIZED (
                    SELECT p.id
                      FROM locked_keys k
                      JOIN pending p
                        ON p.topic = k.topic
                       AND p.event_key = k.event_key
                     WHERE p.key_rank <= ?
                       AND p.next_attempt_at <= ?
                       AND NOT p.blocked_by_retry
                     ORDER BY p.key_rank, k.first_id, p.id
                     LIMIT ?
                )
                UPDATE account_outbox_events e
                   SET next_attempt_at = ?,
                       updated_at = ?
                  FROM candidates c
                 WHERE e.id = c.id
             RETURNING e.id, e.topic, e.event_key, e.payload::text AS payload
                """);
        args.add(Timestamp.from(now));
        args.add(lockedKeyLimit);
        args.add(perKeyLimit);
        args.add(Timestamp.from(now));
        args.add(effectiveLimit);
        args.add(Timestamp.from(leaseUntil));
        args.add(Timestamp.from(now));
        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> new AccountOutboxRecord(
                rs.getLong("id"),
                rs.getString("topic"),
                rs.getString("event_key"),
                rs.getString("payload")), args.toArray());
    }

    private int lockedKeyLimit(int limit, int perKeyLimit) {
        int groupsToFillBatch = (limit + perKeyLimit - 1) / perKeyLimit;
        int concurrencyWindow = Math.max(1, properties.getOutbox().getMaxInFlight()) * 4;
        return Math.max(1, Math.min(limit, Math.max(groupsToFillBatch, concurrencyWindow)));
    }

    public void markPublished(long id, Instant now) {
        int rows = jdbcTemplate.update("""
                UPDATE account_outbox_events
                   SET published_at = ?,
                       updated_at = ?,
                       last_error = NULL
                 WHERE id = ?
                """, Timestamp.from(now), Timestamp.from(now), id);
        requireSingleRow(rows, "account outbox publish mark");
    }

    public void markPublished(List<Long> ids, Instant now) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        List<Long> uniqueIds = ids.stream().distinct().toList();
        Timestamp timestamp = Timestamp.from(now);
        String placeholders = String.join(", ", Collections.nCopies(uniqueIds.size(), "?"));
        String sql = """
                UPDATE account_outbox_events
                   SET published_at = ?,
                       updated_at = ?,
                       last_error = NULL
                 WHERE published_at IS NULL
                   AND id IN (%s)
                """.formatted(placeholders);
        List<Object> args = new ArrayList<>(uniqueIds.size() + 2);
        args.add(timestamp);
        args.add(timestamp);
        args.addAll(uniqueIds);
        int rows = jdbcTemplate.update(sql, args.toArray());
        if (rows != uniqueIds.size()) {
            throw new IllegalStateException("failed to mark all account outbox events published: expected="
                    + uniqueIds.size() + " actual=" + rows);
        }
    }

    public void markFailed(long id, String error, Instant now) {
        int rows = jdbcTemplate.update("""
                UPDATE account_outbox_events
                   SET attempts = attempts + 1,
                       last_error = ?,
                       next_attempt_at = ? + (CAST(power(2, LEAST(attempts, 6)) AS INTEGER) * INTERVAL '1 second'),
                       updated_at = ?
                 WHERE id = ?
                """, truncate(error), Timestamp.from(now), Timestamp.from(now), id);
        requireSingleRow(rows, "account outbox failure mark");
    }

    public int deletePublishedBefore(Instant cutoff, int limit) {
        return jdbcTemplate.update("""
                WITH candidates AS (
                    SELECT id
                      FROM account_outbox_events
                     WHERE product_line = ?
                       AND published_at < ?
                     ORDER BY published_at ASC, id ASC
                     LIMIT ?
                     FOR UPDATE SKIP LOCKED
                )
                DELETE FROM account_outbox_events e
                 USING candidates c
                 WHERE e.id = c.id
                """, currentProductLine().name(), Timestamp.from(cutoff), Math.max(1, limit));
    }

    private String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 1000 ? value : value.substring(0, 1000);
    }

    private void appendTopicScope(StringBuilder sql, String alias, List<Object> args) {
        AccountProperties.Kafka kafka = properties.getKafka();
        sql.append("   AND ").append(alias).append(".product_line = ?\n");
        args.add(currentProductLine().name());
        if (!kafka.isProductTopicsEnabled()) {
            return;
        }
        sql.append("   AND ").append(alias).append(".topic IN (?, ?, ?, ?, ?, ?, ?)\n");
        args.add(kafka.getPositionEventsTopic());
        args.add(kafka.getOpenInterestEventsTopic());
        args.add(kafka.getLiquidationFeeEventsTopic());
        args.add(kafka.getRiskWalletEventsTopic());
        args.add(kafka.getAccountStateEventsTopic());
        args.add(kafka.getCommandResultsTopic());
        args.add(kafka.getUserCommandsTopic());
    }

    private com.surprising.product.api.ProductLine currentProductLine() {
        return properties.getKafka().getProductLine();
    }

    private void requireSingleRow(int rows, String operation) {
        if (rows != 1) {
            throw new IllegalStateException("failed to write " + operation);
        }
    }
}
