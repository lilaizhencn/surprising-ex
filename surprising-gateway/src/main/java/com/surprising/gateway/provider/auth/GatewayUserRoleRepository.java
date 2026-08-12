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
 * 只负责 {@code gateway_user_roles} 表；角色编码由服务层通过角色仓储解析。
 */
@Repository
public class GatewayUserRoleRepository {

    private final JdbcTemplate jdbcTemplate;

    public GatewayUserRoleRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<Long> findRoleIds(long userId) {
        return jdbcTemplate.query("""
                SELECT role_id
                  FROM gateway_user_roles
                 WHERE user_id = ?
                 ORDER BY role_id
                """, (rs, rowNum) -> rs.getLong("role_id"), userId);
    }

    public Map<Long, List<Long>> findRoleIdsByUserIds(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(userIds.size(), "?"));
        Map<Long, List<Long>> roleIdsByUser = new LinkedHashMap<>();
        List<UserRoleLink> links = jdbcTemplate.query("""
                SELECT user_id, role_id
                  FROM gateway_user_roles
                 WHERE user_id IN (%s)
                 ORDER BY user_id, role_id
                """.formatted(placeholders), (rs, rowNum) -> new UserRoleLink(
                        rs.getLong("user_id"),
                        rs.getLong("role_id")), userIds.toArray());
        links.forEach(link -> roleIdsByUser
                .computeIfAbsent(link.userId(), ignored -> new java.util.ArrayList<>())
                .add(link.roleId()));
        return Map.copyOf(roleIdsByUser);
    }

    public void add(long userId, long roleId, Instant now) {
        jdbcTemplate.update("""
                INSERT INTO gateway_user_roles (user_id, role_id, created_at)
                VALUES (?, ?, ?)
                ON CONFLICT (user_id, role_id) DO NOTHING
                """, userId, roleId, Timestamp.from(now));
    }

    public void replace(long userId, Collection<Long> roleIds, Instant now) {
        jdbcTemplate.update("DELETE FROM gateway_user_roles WHERE user_id = ?", userId);
        for (Long roleId : roleIds) {
            add(userId, roleId, now);
        }
    }

    private record UserRoleLink(long userId, long roleId) {
    }
}
