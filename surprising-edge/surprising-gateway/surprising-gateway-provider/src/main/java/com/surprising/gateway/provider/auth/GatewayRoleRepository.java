package com.surprising.gateway.provider.auth;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 只负责 {@code gateway_roles} 表。
 */
@Repository
public class GatewayRoleRepository {

    private final JdbcTemplate jdbcTemplate;

    public GatewayRoleRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void ensure(String roleCode, String roleName, Instant now) {
        jdbcTemplate.update("""
                INSERT INTO gateway_roles (role_code, role_name, created_at)
                VALUES (?, ?, ?)
                ON CONFLICT (role_code) DO NOTHING
                """, normalizeRoleCode(roleCode), roleName, Timestamp.from(now));
    }

    public RoleRecord requireByCode(String roleCode) {
        return jdbcTemplate.query("""
                SELECT role_id, role_code, role_name, created_at
                  FROM gateway_roles
                 WHERE role_code = ?
                """, (rs, rowNum) -> toRecord(rs), normalizeRoleCode(roleCode)).stream()
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("role not found"));
    }

    public List<RoleRecord> findAll() {
        return jdbcTemplate.query("""
                SELECT role_id, role_code, role_name, created_at
                  FROM gateway_roles
                 ORDER BY role_code
                """, (rs, rowNum) -> toRecord(rs));
    }

    public Map<Long, RoleRecord> findByIds(Collection<Long> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(roleIds.size(), "?"));
        List<RoleRecord> rows = jdbcTemplate.query("""
                SELECT role_id, role_code, role_name, created_at
                  FROM gateway_roles
                 WHERE role_id IN (%s)
                """.formatted(placeholders), (rs, rowNum) -> toRecord(rs), roleIds.toArray());
        return rows.stream().collect(Collectors.toUnmodifiableMap(RoleRecord::roleId, Function.identity()));
    }

    public static String normalizeRoleCode(String roleCode) {
        String normalized = roleCode == null || roleCode.isBlank()
                ? null
                : roleCode.trim().toUpperCase(Locale.ROOT);
        if (normalized == null || !normalized.matches("[A-Z0-9_]{2,64}")) {
            throw new IllegalArgumentException("invalid role code");
        }
        return normalized;
    }

    private RoleRecord toRecord(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new RoleRecord(
                rs.getLong("role_id"),
                rs.getString("role_code"),
                rs.getString("role_name"),
                rs.getTimestamp("created_at").toInstant());
    }

    public record RoleRecord(long roleId, String roleCode, String roleName, Instant createdAt) {
    }
}
