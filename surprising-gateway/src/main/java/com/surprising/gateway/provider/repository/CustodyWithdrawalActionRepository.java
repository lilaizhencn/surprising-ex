package com.surprising.gateway.provider.repository;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 只追加gateway_wallet_withdrawal_actions，业务状态和事务由Service负责。 */
@Repository
public class CustodyWithdrawalActionRepository {
    private final JdbcTemplate jdbcTemplate;

    public CustodyWithdrawalActionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void append(UUID withdrawalId, long adminUserId, String adminUsername, String action, String reason) {
        jdbcTemplate.update("""
                INSERT INTO gateway_wallet_withdrawal_actions
                    (action_id, withdrawal_id, admin_user_id, admin_username, action, reason)
                VALUES (?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), withdrawalId, adminUserId, adminUsername, action, reason);
    }
}
