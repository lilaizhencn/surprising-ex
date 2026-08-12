package com.surprising.liquidation.provider.repository;

import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 强平管理员动作仓储，只负责 {@code liquidation_admin_actions} 表。 */
@Repository
public class LiquidationAdminActionRepository {

    private final JdbcTemplate jdbcTemplate;

    public LiquidationAdminActionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public LiquidationAdminAction insert(long candidateId,
                                         String actionType,
                                         String adminUserId,
                                         String reason,
                                         Instant now) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO liquidation_admin_actions (
                    candidate_id, action_type, admin_user_id, reason, created_at
                ) VALUES (?, ?, ?, ?, ?)
                RETURNING action_id, candidate_id, action_type, admin_user_id, reason, created_at
                """, (rs, rowNum) -> new LiquidationAdminAction(
                rs.getLong("action_id"),
                rs.getLong("candidate_id"),
                rs.getString("action_type"),
                rs.getString("admin_user_id"),
                rs.getString("reason"),
                rs.getTimestamp("created_at").toInstant()), candidateId, actionType, adminUserId, reason,
                Timestamp.from(now));
    }

    public record LiquidationAdminAction(long actionId,
                                         long candidateId,
                                         String actionType,
                                         String adminUserId,
                                         String reason,
                                         Instant createdAt) {
    }
}
