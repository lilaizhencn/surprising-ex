package com.surprising.funding.provider.repository;

import com.surprising.funding.provider.config.FundingProperties;
import com.surprising.funding.provider.model.FundingOutboxRecord;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class FundingOutboxRepository {

    private final JdbcTemplate jdbcTemplate;
    private final FundingRepository fundingRepository;
    private final FundingProperties properties;

    public FundingOutboxRepository(JdbcTemplate jdbcTemplate, FundingRepository fundingRepository) {
        this(jdbcTemplate, fundingRepository, new FundingProperties());
    }

    @Autowired
    public FundingOutboxRepository(JdbcTemplate jdbcTemplate,
                                   FundingRepository fundingRepository,
                                   FundingProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.fundingRepository = fundingRepository;
        this.properties = properties == null ? new FundingProperties() : properties;
    }

    public void enqueue(String topic, String eventKey, String eventType, String payload, Instant now) {
        long id = fundingRepository.nextSequence("funding-outbox");
        int rows = jdbcTemplate.update("""
                INSERT INTO funding_outbox_events (
                    id, topic, event_key, event_type, payload,
                    next_attempt_at, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?)
                """, id, topic, eventKey, eventType, payload, Timestamp.from(now), Timestamp.from(now),
                Timestamp.from(now));
        requireSingleRow(rows, "funding outbox enqueue");
    }

    public List<FundingOutboxRecord> lockPending(int limit) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("""
                SELECT id, topic, event_key, payload::text AS payload
                  FROM funding_outbox_events
                 WHERE published_at IS NULL
                """);
        appendTopicScope(sql, args);
        sql.append("""
                   AND next_attempt_at <= now()
                 ORDER BY next_attempt_at ASC, id ASC
                 LIMIT ?
                 FOR UPDATE SKIP LOCKED
                """);
        args.add(limit);
        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> new FundingOutboxRecord(
                rs.getLong("id"),
                rs.getString("topic"),
                rs.getString("event_key"),
                rs.getString("payload")), args.toArray());
    }

    public void markPublished(long id, Instant now) {
        int rows = jdbcTemplate.update("""
                UPDATE funding_outbox_events
                   SET published_at = ?,
                       updated_at = ?,
                       last_error = NULL
                 WHERE id = ?
                """, Timestamp.from(now), Timestamp.from(now), id);
        requireSingleRow(rows, "funding outbox publish mark");
    }

    public void markFailed(long id, String error, Instant now) {
        int rows = jdbcTemplate.update("""
                UPDATE funding_outbox_events
                   SET attempts = attempts + 1,
                       last_error = ?,
                       next_attempt_at = ? + (CAST(power(2, LEAST(attempts, 6)) AS INTEGER) * INTERVAL '1 second'),
                       updated_at = ?
                 WHERE id = ?
                """, truncate(error), Timestamp.from(now), Timestamp.from(now), id);
        requireSingleRow(rows, "funding outbox failure mark");
    }

    private String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 1000 ? value : value.substring(0, 1000);
    }

    private void appendTopicScope(StringBuilder sql, List<Object> args) {
        FundingProperties.Kafka kafka = properties.getKafka();
        if (!kafka.isProductTopicsEnabled()) {
            return;
        }
        if (!kafka.isFundingProductLine()) {
            sql.append("   AND 1 = 0\n");
            return;
        }
        sql.append("   AND topic = ?\n");
        args.add(kafka.getFundingRateTopic());
    }

    private void requireSingleRow(int rows, String operation) {
        if (rows != 1) {
            throw new IllegalStateException("failed to write " + operation);
        }
    }
}
