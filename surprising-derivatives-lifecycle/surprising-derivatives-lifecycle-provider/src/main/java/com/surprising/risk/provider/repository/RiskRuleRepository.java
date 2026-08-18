package com.surprising.risk.provider.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 风控管理规则仓储，只负责 {@code risk_admin_rule_overrides} 表。 */
@Repository
public class RiskRuleRepository {

    private final JdbcTemplate jdbcTemplate;

    public RiskRuleRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<RiskRuleOverride> findAll() {
        return jdbcTemplate.query("""
                SELECT *
                  FROM risk_admin_rule_overrides
                 ORDER BY rule_type ASC, rule_code ASC
                """, (rs, rowNum) -> toRecord(rs));
    }

    public RiskRuleOverride upsert(String ruleCode,
                                   String ruleName,
                                   String ruleType,
                                   boolean enabled,
                                   Long scanDelayMs,
                                   Integer scanBatchSize,
                                   String adminUserId,
                                   String reason,
                                   Instant now) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO risk_admin_rule_overrides (
                    rule_code, rule_name, rule_type, enabled, scan_delay_ms, scan_batch_size,
                    admin_user_id, reason, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (rule_code) DO UPDATE SET
                    rule_name = EXCLUDED.rule_name,
                    rule_type = EXCLUDED.rule_type,
                    enabled = EXCLUDED.enabled,
                    scan_delay_ms = EXCLUDED.scan_delay_ms,
                    scan_batch_size = EXCLUDED.scan_batch_size,
                    admin_user_id = EXCLUDED.admin_user_id,
                    reason = EXCLUDED.reason,
                    updated_at = EXCLUDED.updated_at
                RETURNING *
                """, (rs, rowNum) -> toRecord(rs), ruleCode, ruleName, ruleType, enabled,
                scanDelayMs, scanBatchSize,
                adminUserId, reason, Timestamp.from(now), Timestamp.from(now));
    }

    private RiskRuleOverride toRecord(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new RiskRuleOverride(
                rs.getString("rule_code"),
                rs.getString("rule_name"),
                rs.getString("rule_type"),
                rs.getBoolean("enabled"),
                nullableLong(rs, "scan_delay_ms"),
                nullableInteger(rs, "scan_batch_size"),
                rs.getString("admin_user_id"),
                rs.getString("reason"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private Long nullableLong(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private Integer nullableInteger(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    public record RiskRuleOverride(String ruleCode,
                                   String ruleName,
                                   String ruleType,
                                   boolean enabled,
                                   Long scanDelayMs,
                                   Integer scanBatchSize,
                                   String adminUserId,
                                   String reason,
                                   Instant createdAt,
                                   Instant updatedAt) {
    }
}
