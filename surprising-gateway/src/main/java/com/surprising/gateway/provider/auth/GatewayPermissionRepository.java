package com.surprising.gateway.provider.auth;

import com.surprising.gateway.provider.auth.AuthModels.AdminPermissionResponse;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 只负责 {@code gateway_permissions} 表。
 */
@Repository
public class GatewayPermissionRepository {

    private final JdbcTemplate jdbcTemplate;

    public GatewayPermissionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<AdminPermissionResponse> findAllResponses() {
        return jdbcTemplate.query("""
                SELECT permission_code, permission_name, description, created_at
                  FROM gateway_permissions
                 ORDER BY permission_code
                """, (rs, rowNum) -> new AdminPermissionResponse(
                        rs.getString("permission_code"),
                        rs.getString("permission_name"),
                        rs.getString("description"),
                        rs.getTimestamp("created_at").toInstant()));
    }

    public Map<String, PermissionRecord> findByCodes(Collection<String> permissionCodes) {
        if (permissionCodes == null || permissionCodes.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(permissionCodes.size(), "?"));
        List<PermissionRecord> rows = jdbcTemplate.query("""
                SELECT permission_id, permission_code
                  FROM gateway_permissions
                 WHERE permission_code IN (%s)
                """.formatted(placeholders), (rs, rowNum) -> new PermissionRecord(
                        rs.getLong("permission_id"),
                        rs.getString("permission_code")), permissionCodes.toArray());
        return rows.stream().collect(Collectors.toUnmodifiableMap(PermissionRecord::permissionCode, Function.identity()));
    }

    public Map<Long, PermissionRecord> findByIds(Collection<Long> permissionIds) {
        if (permissionIds == null || permissionIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(permissionIds.size(), "?"));
        List<PermissionRecord> rows = jdbcTemplate.query("""
                SELECT permission_id, permission_code
                  FROM gateway_permissions
                 WHERE permission_id IN (%s)
                """.formatted(placeholders), (rs, rowNum) -> new PermissionRecord(
                        rs.getLong("permission_id"),
                        rs.getString("permission_code")), permissionIds.toArray());
        return rows.stream().collect(Collectors.toUnmodifiableMap(PermissionRecord::permissionId, Function.identity()));
    }

    public static String normalizePermissionCode(String permissionCode) {
        String normalized = permissionCode == null ? null : permissionCode.trim().toLowerCase(Locale.ROOT);
        if (normalized == null || !normalized.matches("[a-z0-9*][a-z0-9.*_-]{1,127}")) {
            throw new IllegalArgumentException("invalid permission code");
        }
        return normalized;
    }

    public record PermissionRecord(long permissionId, String permissionCode) {
    }
}
