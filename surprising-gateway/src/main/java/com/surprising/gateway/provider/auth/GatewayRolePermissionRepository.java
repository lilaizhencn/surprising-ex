package com.surprising.gateway.provider.auth;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 只负责 {@code gateway_role_permissions} 表；角色和权限详情由服务层聚合。
 */
@Repository
public class GatewayRolePermissionRepository {

    private final JdbcTemplate jdbcTemplate;

    public GatewayRolePermissionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<Long> findPermissionIds(long roleId) {
        return jdbcTemplate.query("""
                SELECT permission_id
                  FROM gateway_role_permissions
                 WHERE role_id = ?
                 ORDER BY permission_id
                """, (rs, rowNum) -> rs.getLong("permission_id"), roleId);
    }

    public List<Long> findPermissionIdsByRoleIds(Collection<Long> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(roleIds.size(), "?"));
        return jdbcTemplate.query("""
                SELECT DISTINCT permission_id
                  FROM gateway_role_permissions
                 WHERE role_id IN (%s)
                 ORDER BY permission_id
                """.formatted(placeholders), (rs, rowNum) -> rs.getLong("permission_id"), roleIds.toArray());
    }

    public Map<Long, Integer> countByRoleIds(Collection<Long> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(roleIds.size(), "?"));
        Map<Long, Integer> counts = new LinkedHashMap<>();
        List<RolePermissionCount> rows = jdbcTemplate.query("""
                SELECT role_id, COUNT(*) AS permission_count
                  FROM gateway_role_permissions
                 WHERE role_id IN (%s)
                 GROUP BY role_id
                """.formatted(placeholders), (rs, rowNum) -> new RolePermissionCount(
                        rs.getLong("role_id"),
                        rs.getInt("permission_count")), roleIds.toArray());
        rows.forEach(row -> counts.put(row.roleId(), row.permissionCount()));
        return Map.copyOf(counts);
    }

    public void replace(long roleId, Collection<Long> permissionIds, Instant now) {
        jdbcTemplate.update("DELETE FROM gateway_role_permissions WHERE role_id = ?", roleId);
        for (Long permissionId : permissionIds) {
            jdbcTemplate.update("""
                    INSERT INTO gateway_role_permissions (role_id, permission_id, created_at)
                    VALUES (?, ?, ?)
                    ON CONFLICT (role_id, permission_id) DO NOTHING
                    """, roleId, permissionId, Timestamp.from(now));
        }
    }

    private record RolePermissionCount(long roleId, int permissionCount) {
    }
}
