package com.surprising.account.provider.repository;

import com.surprising.account.api.model.PositionResponse;
import com.surprising.account.api.model.PositionUpdatedEvent;
import com.surprising.account.provider.model.AccountOutboxRecord;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

/**
 * Transactional outbox for account-side events.
 *
 * <p>Position updates are written in the same database transaction as account settlement, then a
 * scheduled publisher sends them to Kafka. This keeps WebSocket position pushes downstream of the
 * authoritative account state instead of publishing from raw matching events.</p>
 */
@Repository
public class AccountOutboxRepository {

    private final JdbcTemplate jdbcTemplate;
    private final AccountSequenceRepository sequenceRepository;
    private final ObjectMapper objectMapper;

    public AccountOutboxRepository(JdbcTemplate jdbcTemplate,
                                   AccountSequenceRepository sequenceRepository,
                                   ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.sequenceRepository = sequenceRepository;
        this.objectMapper = objectMapper;
    }

    public PositionUpdatedEvent enqueuePositionUpdated(String topic,
                                                       long tradeId,
                                                       PositionResponse position,
                                                       Instant now,
                                                       String traceId) {
        long eventId = sequenceRepository.nextSequence("position-event");
        PositionUpdatedEvent event = new PositionUpdatedEvent(eventId, tradeId, position.userId(), position.symbol(),
                position.instrumentVersion(), position.marginMode(), position.signedQuantitySteps(),
                position.entryPriceTicks(), position.realizedPnlUnits(), now, traceId);
        long outboxId = sequenceRepository.nextSequence("account-outbox");
        int rows = jdbcTemplate.update("""
                INSERT INTO account_outbox_events (
                    id, aggregate_type, aggregate_id, topic, event_key, event_type, payload,
                    next_attempt_at, created_at, updated_at
                ) VALUES (?, 'POSITION', ?, ?, ?, 'POSITION_UPDATED', ?::jsonb, ?, ?, ?)
                """, outboxId, eventId, topic, position.symbol(), objectMapper.writeValueAsString(event),
                Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
        requireSingleRow(rows, "account position outbox enqueue");
        return event;
    }

    public List<AccountOutboxRecord> lockPending(int limit) {
        return jdbcTemplate.query("""
                SELECT id, topic, event_key, payload::text AS payload
                  FROM account_outbox_events
                 WHERE published_at IS NULL
                   AND next_attempt_at <= now()
                 ORDER BY next_attempt_at ASC, id ASC
                 LIMIT ?
                 FOR UPDATE SKIP LOCKED
                """, (rs, rowNum) -> new AccountOutboxRecord(
                rs.getLong("id"),
                rs.getString("topic"),
                rs.getString("event_key"),
                rs.getString("payload")), limit);
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

    private String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 1000 ? value : value.substring(0, 1000);
    }

    private void requireSingleRow(int rows, String operation) {
        if (rows != 1) {
            throw new IllegalStateException("failed to write " + operation);
        }
    }
}
