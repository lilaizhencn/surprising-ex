package com.surprising.gateway.provider.repository;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 只追加gateway_wallet_withdrawal_events，业务状态和事务由Service负责。 */
@Repository
public class CustodyWithdrawalEventRepository {
    private final JdbcTemplate jdbcTemplate;

    public CustodyWithdrawalEventRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void append(UUID id, String eventType, String source, String fromStatus, String toStatus,
                       String walletWithdrawalId, String providerEventId, String payload, String reason) {
        jdbcTemplate.update("""
                INSERT INTO gateway_wallet_withdrawal_events (
                    event_id, withdrawal_id, event_type, source, from_status, to_status,
                    wallet_withdrawal_id, provider_event_id, payload, reason
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                """, UUID.randomUUID(), id, eventType, source, fromStatus, toStatus,
                walletWithdrawalId, providerEventId, payload == null || payload.isBlank() ? "{}" : payload,
                reason == null ? null : reason.substring(0, Math.min(reason.length(), 2000)));
    }
}
