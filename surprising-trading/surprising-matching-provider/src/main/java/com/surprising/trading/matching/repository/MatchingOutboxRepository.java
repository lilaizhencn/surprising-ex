package com.surprising.trading.matching.repository;

import com.surprising.trading.matching.config.MatchingProperties;
import com.surprising.trading.matching.model.StoredOutboxRecord;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class MatchingOutboxRepository {

    private final JdbcTemplate jdbcTemplate;
    private final MatchingProperties properties;

    public MatchingOutboxRepository(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, new MatchingProperties());
    }

    @Autowired
    public MatchingOutboxRepository(JdbcTemplate jdbcTemplate,
                                    MatchingProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties == null ? new MatchingProperties() : properties;
    }

    public void enqueue(String aggregateType,
                        long aggregateId,
                        String topic,
                        String eventKey,
                        String eventType,
                        String payload,
                        Instant now) {
        enqueueBatch(List.of(new MatchingOutboxWrite(
                aggregateType, aggregateId, topic, eventKey, eventType, payload, now)));
    }

    public void enqueueBatch(List<MatchingOutboxWrite> writes) {
        if (writes == null || writes.isEmpty()) {
            return;
        }
        for (MatchingOutboxWrite write : writes) {
            if (write == null || write.now() == null) {
                throw new IllegalArgumentException("matching outbox write and timestamp are required");
            }
            if ("ORDER_BOOK_DEPTH".equals(write.aggregateType())
                    || "MATCH_TRADE".equals(write.aggregateType())) {
                throw new IllegalArgumentException("public market data must use its dedicated in-memory publisher");
            }
            requireCurrentProductTopic(write.aggregateType(), write.topic());
        }
        List<Long> ids = jdbcTemplate.query("""
                SELECT nextval('trading_outbox_seq') AS id
                  FROM generate_series(1, ?) AS n
                 ORDER BY n
                """, (rs, rowNum) -> rs.getLong("id"), writes.size());
        if (ids.size() != writes.size()) {
            throw new IllegalStateException("failed to allocate matching outbox ids");
        }
        int[] rows = jdbcTemplate.batchUpdate("""
                INSERT INTO trading_outbox_events (
                    id, aggregate_type, aggregate_id, topic, event_key, event_type,
                    payload, next_attempt_at, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
                """, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement statement, int index) throws java.sql.SQLException {
                MatchingOutboxWrite write = writes.get(index);
                Timestamp timestamp = Timestamp.from(write.now());
                statement.setLong(1, ids.get(index));
                statement.setString(2, write.aggregateType());
                statement.setLong(3, write.aggregateId());
                statement.setString(4, write.topic());
                statement.setString(5, write.eventKey());
                statement.setString(6, write.eventType());
                statement.setString(7, write.payload());
                statement.setTimestamp(8, timestamp);
                statement.setTimestamp(9, timestamp);
                statement.setTimestamp(10, timestamp);
            }

            @Override
            public int getBatchSize() {
                return writes.size();
            }
        });
        requireCompleteBatch(rows, writes.size(), "matching outbox enqueue");
    }

    public List<StoredOutboxRecord> claimPending(int limit, Instant leaseUntil, Instant now) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("""
                WITH earliest AS MATERIALIZED (
                    SELECT DISTINCT ON (topic, event_key)
                           id
                      FROM trading_outbox_events
                     WHERE published_at IS NULL
                       AND aggregate_type IN ('MATCH_RESULT', 'ACCOUNT_COMMAND')
                     ORDER BY topic, event_key, id
                ),
                candidates AS MATERIALIZED (
                    SELECT e.id
                  FROM trading_outbox_events e
                 WHERE e.published_at IS NULL
                   AND e.aggregate_type IN ('MATCH_RESULT', 'ACCOUNT_COMMAND')
                   AND e.id = ANY (ARRAY(SELECT id FROM earliest))
                """);
        appendTopicScope(sql, "e", args);
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
             RETURNING e.id, e.topic, e.event_key, e.payload::text AS payload, e.next_attempt_at
                """);
        args.add(Timestamp.from(now));
        args.add(Math.max(1, limit));
        args.add(Timestamp.from(leaseUntil));
        args.add(Timestamp.from(now));
        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> new StoredOutboxRecord(
                rs.getLong("id"),
                rs.getString("topic"),
                rs.getString("event_key"),
                rs.getString("payload"),
                rs.getTimestamp("next_attempt_at").toInstant()),
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
        requireSingleRow(rows, "matching outbox publish mark");
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
                   AND id IN (%s)
                """.formatted(placeholders);
        List<Object> args = new ArrayList<>(uniqueIds.size() + 2);
        args.add(timestamp);
        args.add(timestamp);
        args.addAll(uniqueIds);
        int rows = jdbcTemplate.update(sql, args.toArray());
        if (rows != uniqueIds.size()) {
            throw new IllegalStateException("failed to mark all matching outbox events published: expected="
                    + uniqueIds.size() + " actual=" + rows);
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
        requireSingleRow(rows, "matching outbox failure mark");
    }

    public int deletePublishedBefore(Instant cutoff, int limit) {
        return jdbcTemplate.update("""
                WITH candidates AS (
                    SELECT id
                      FROM trading_outbox_events
                     WHERE aggregate_type IN (
                               'MATCH_RESULT', 'ACCOUNT_COMMAND'
                           )
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

    private String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 1000 ? value : value.substring(0, 1000);
    }

    private void appendTopicScope(StringBuilder sql, String alias, List<Object> args) {
        MatchingProperties.Kafka kafka = properties.getKafka();
        if (!kafka.isProductTopicsEnabled()) {
            return;
        }
        sql.append("   AND ").append(alias).append(".topic IN (?, ?)\n");
        args.add(kafka.getMatchResultsTopic());
        args.add(kafka.getAccountUserCommandsTopic());
    }

    private void requireCurrentProductTopic(String aggregateType, String topic) {
        MatchingProperties.Kafka kafka = properties.getKafka();
        if (!kafka.isProductTopicsEnabled() || !isMatchingAggregate(aggregateType)) {
            return;
        }
        String matchResultsTopic = kafka.getMatchResultsTopic();
        String accountUserCommandsTopic = kafka.getAccountUserCommandsTopic();
        if (!matchResultsTopic.equals(topic)
                && !accountUserCommandsTopic.equals(topic)) {
            throw new IllegalStateException("matching outbox topic must match current product line: expected one of ["
                    + matchResultsTopic + ", " + accountUserCommandsTopic
                    + "] actual=" + topic);
        }
    }

    private boolean isMatchingAggregate(String aggregateType) {
        return "MATCH_RESULT".equals(aggregateType)
                || "ACCOUNT_COMMAND".equals(aggregateType);
    }

    private void requireSingleRow(int rows, String operation) {
        if (rows != 1) {
            throw new IllegalStateException("failed to write " + operation);
        }
    }

    private void requireCompleteBatch(int[] rows, int expected, String operation) {
        if (rows == null || rows.length != expected) {
            throw new IllegalStateException("failed to write " + operation);
        }
        for (int row : rows) {
            if (row != 1 && row != Statement.SUCCESS_NO_INFO) {
                throw new IllegalStateException("failed to write " + operation);
            }
        }
    }

    public record MatchingOutboxWrite(
            String aggregateType,
            long aggregateId,
            String topic,
            String eventKey,
            String eventType,
            String payload,
            Instant now) {
    }
}
