package com.surprising.instrument.provider.repository;

import com.surprising.instrument.provider.model.InstrumentOutboxRecord;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 只负责 {@code instrument_outbox_events} 表。 */
@Repository
public class InstrumentOutboxRepository {

    private final JdbcTemplate jdbcTemplate;

    public InstrumentOutboxRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long enqueue(String aggregateType,
                        long aggregateId,
                        String topic,
                        String eventKey,
                        String eventType,
                        String payload,
                        Instant now) {
        Long id = jdbcTemplate.queryForObject(
                "SELECT nextval('instrument_outbox_event_seq')", Long.class);
        if (id == null) {
            throw new IllegalStateException("无法分配 instrument outbox 事件编号");
        }
        int rows = jdbcTemplate.update("""
                INSERT INTO instrument_outbox_events (
                    id, aggregate_type, aggregate_id, topic, event_key, event_type,
                    payload, next_attempt_at, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
                """, id, aggregateType, aggregateId, topic, eventKey, eventType, payload,
                Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
        if (rows != 1) {
            throw new IllegalStateException("instrument outbox 事件写入失败: " + id);
        }
        return id;
    }

    /**
     * 每个 topic/key 只领取最早的一条消息，保证多节点发布时同一交易品种严格有序。
     */
    public List<InstrumentOutboxRecord> claimPending(int limit, Instant leaseUntil, Instant now) {
        return jdbcTemplate.query("""
                WITH heads AS MATERIALIZED (
                    SELECT DISTINCT ON (topic, event_key)
                           id, xmin::text::bigint AS row_version
                      FROM instrument_outbox_events
                     WHERE published_at IS NULL
                     ORDER BY topic, event_key, id
                ),
                candidates AS MATERIALIZED (
                    SELECT e.id, h.row_version
                      FROM instrument_outbox_events e
                      JOIN heads h ON h.id = e.id
                     WHERE e.next_attempt_at <= ?
                     ORDER BY e.next_attempt_at, e.id
                     LIMIT ?
                )
                UPDATE instrument_outbox_events e
                   SET next_attempt_at = ?,
                       updated_at = ?
                 WHERE (e.id, e.xmin::text::bigint) IN (
                           SELECT id, row_version FROM candidates
                       )
             RETURNING e.id, e.topic, e.event_key, e.event_type,
                       e.payload::text AS payload, e.next_attempt_at
                """, (rs, rowNum) -> new InstrumentOutboxRecord(
                        rs.getLong("id"),
                        rs.getString("topic"),
                        rs.getString("event_key"),
                        rs.getString("event_type"),
                        rs.getString("payload"),
                        rs.getTimestamp("next_attempt_at").toInstant()),
                Timestamp.from(now), Math.max(1, limit),
                Timestamp.from(leaseUntil), Timestamp.from(now));
    }

    public void markPublished(long id, Instant now) {
        int rows = jdbcTemplate.update("""
                UPDATE instrument_outbox_events
                   SET published_at = ?,
                       updated_at = ?,
                       last_error = NULL
                 WHERE id = ?
                   AND published_at IS NULL
                """, Timestamp.from(now), Timestamp.from(now), id);
        if (rows != 1) {
            throw new IllegalStateException("instrument outbox 发布状态更新失败: " + id);
        }
    }

    public void markFailed(long id, String error, Instant now) {
        int rows = jdbcTemplate.update("""
                UPDATE instrument_outbox_events
                   SET attempts = attempts + 1,
                       last_error = ?,
                       next_attempt_at = ? + (CAST(power(2, LEAST(attempts, 6)) AS INTEGER) * INTERVAL '1 second'),
                       updated_at = ?
                 WHERE id = ?
                   AND published_at IS NULL
                """, truncate(error), Timestamp.from(now), Timestamp.from(now), id);
        if (rows != 1) {
            throw new IllegalStateException("instrument outbox 失败状态更新失败: " + id);
        }
    }

    public int deletePublishedBefore(Instant cutoff, int limit) {
        return jdbcTemplate.update("""
                WITH candidates AS (
                    SELECT id
                      FROM instrument_outbox_events
                     WHERE published_at < ?
                     ORDER BY published_at, id
                     LIMIT ?
                     FOR UPDATE SKIP LOCKED
                )
                DELETE FROM instrument_outbox_events e
                 USING candidates c
                 WHERE e.id = c.id
                """, Timestamp.from(cutoff), Math.max(1, limit));
    }

    private String truncate(String value) {
        if (value == null || value.length() <= 1000) {
            return value;
        }
        return value.substring(0, 1000);
    }
}
