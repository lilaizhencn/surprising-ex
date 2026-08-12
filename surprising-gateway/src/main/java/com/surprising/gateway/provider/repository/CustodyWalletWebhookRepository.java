package com.surprising.gateway.provider.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CustodyWalletWebhookRepository {

    private final JdbcTemplate jdbcTemplate;

    public CustodyWalletWebhookRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public ClaimResult claim(String eventId, String eventType, String bodySha256, Instant now) {
        int inserted = jdbcTemplate.update("""
                INSERT INTO gateway_wallet_webhook_events (
                    event_id, event_type, body_sha256, status, attempts, received_at, updated_at
                ) VALUES (?, ?, ?, 'PROCESSING', 1, ?, ?)
                ON CONFLICT (event_id) DO NOTHING
                """, eventId, eventType, bodySha256, Timestamp.from(now), Timestamp.from(now));
        if (inserted == 1) {
            return ClaimResult.CLAIMED;
        }
        List<EventState> states = jdbcTemplate.query("""
                SELECT event_type, body_sha256, status
                  FROM gateway_wallet_webhook_events
                 WHERE event_id = ?
                """, (rs, rowNum) -> new EventState(
                rs.getString("event_type"), rs.getString("body_sha256"), rs.getString("status")), eventId);
        if (states.isEmpty()) {
            throw new IllegalStateException("wallet webhook event disappeared during claim");
        }
        EventState state = states.getFirst();
        if (!state.bodySha256().equals(bodySha256) || !state.eventType().equals(eventType)) {
            throw new IllegalArgumentException("wallet webhook event payload changed");
        }
        if ("PROCESSED".equals(state.status())) {
            return ClaimResult.PROCESSED;
        }
        int reclaimed = jdbcTemplate.update("""
                UPDATE gateway_wallet_webhook_events
                   SET status = 'PROCESSING', attempts = attempts + 1, error_message = NULL, updated_at = ?
                 WHERE event_id = ?
                   AND (status = 'FAILED' OR (status = 'PROCESSING' AND updated_at < now() - interval '5 minutes'))
                """, Timestamp.from(now), eventId);
        return reclaimed == 1 ? ClaimResult.CLAIMED : ClaimResult.IN_PROGRESS;
    }

    public void markProcessed(String eventId, Instant now) {
        jdbcTemplate.update("""
                UPDATE gateway_wallet_webhook_events
                   SET status = 'PROCESSED', error_message = NULL, processed_at = ?, updated_at = ?
                 WHERE event_id = ?
                """, Timestamp.from(now), Timestamp.from(now), eventId);
    }

    public void markFailed(String eventId, String errorMessage, Instant now) {
        jdbcTemplate.update("""
                UPDATE gateway_wallet_webhook_events
                   SET status = 'FAILED', error_message = ?, updated_at = ?
                 WHERE event_id = ?
                """, errorMessage == null ? "wallet webhook processing failed" : errorMessage.substring(0, Math.min(errorMessage.length(), 500)),
                Timestamp.from(now), eventId);
    }

    public enum ClaimResult {
        CLAIMED,
        PROCESSED,
        IN_PROGRESS
    }

    private record EventState(String eventType, String bodySha256, String status) {
    }
}
