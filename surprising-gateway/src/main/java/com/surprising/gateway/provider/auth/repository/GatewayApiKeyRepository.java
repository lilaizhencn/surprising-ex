package com.surprising.gateway.provider.auth.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class GatewayApiKeyRepository {

    private final JdbcTemplate jdbcTemplate;

    public GatewayApiKeyRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private com.surprising.gateway.provider.auth.state.AuthStateSync authState;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setAuthState(com.surprising.gateway.provider.auth.state.AuthStateSync authState) {
        this.authState = authState;
    }

    public ApiKeyRecord create(UUID apiKeyId, long userId, String apiKey, String secretCiphertext,
                               String label, String permissions, String ipAllowlist, Instant now) {
        ApiKeyRecord created = jdbcTemplate.queryForObject("""
                INSERT INTO gateway_api_keys (
                    api_key_id, user_id, api_key, secret_ciphertext, label, permissions, ip_allowlist, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING api_key_id, user_id, api_key, secret_ciphertext, label, permissions, ip_allowlist,
                          status, created_at, revoked_at
                """, (rs, rowNum) -> toRecord(rs), apiKeyId, userId, apiKey, secretCiphertext,
                label, permissions, ipAllowlist, Timestamp.from(now));
        if (authState != null) authState.apiKeyChanged(apiKey);
        return created;
    }

    public Optional<ApiKeyRecord> active(String apiKey) {
        return jdbcTemplate.query("""
                SELECT api_key_id, user_id, api_key, secret_ciphertext, label, permissions, ip_allowlist,
                       status, created_at, revoked_at
                  FROM gateway_api_keys
                 WHERE api_key = ? AND status = 'ACTIVE'
                """, (rs, rowNum) -> toRecord(rs), apiKey).stream().findFirst();
    }

    public List<ApiKeyRecord> list(long userId) {
        return listForUsers(List.of(userId));
    }

    public List<ApiKeyRecord> listForUsers(List<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) return List.of();
        return jdbcTemplate.query("""
                SELECT api_key_id, user_id, api_key, secret_ciphertext, label, permissions, ip_allowlist,
                       status, created_at, revoked_at
                  FROM gateway_api_keys
                 WHERE user_id = ANY(?)
                 ORDER BY created_at DESC, api_key_id DESC
                """, (rs, rowNum) -> toRecord(rs), (Object) userIds.toArray(Long[]::new));
    }

    public boolean revoke(long userId, String apiKey, Instant now) {
        return revokeForUsers(List.of(userId), apiKey, now);
    }

    public boolean revokeForUsers(List<Long> userIds, String apiKey, Instant now) {
        if (userIds == null || userIds.isEmpty()) return false;
        boolean revoked = jdbcTemplate.update("""
                UPDATE gateway_api_keys
                   SET status = 'REVOKED', revoked_at = ?
                 WHERE user_id = ANY(?) AND api_key = ? AND status = 'ACTIVE'
                """, Timestamp.from(now), (Object) userIds.toArray(Long[]::new), apiKey) == 1;
        if (revoked && authState != null) authState.apiKeyChanged(apiKey);
        return revoked;
    }

    public boolean update(long userId, String apiKey, String label, String permissions, String ipAllowlist) {
        return updateForUsers(List.of(userId), apiKey, label, permissions, ipAllowlist);
    }

    public boolean updateForUsers(List<Long> userIds, String apiKey, String label, String permissions, String ipAllowlist) {
        if (userIds == null || userIds.isEmpty()) return false;
        boolean updated = jdbcTemplate.update("""
                UPDATE gateway_api_keys
                   SET label = ?, permissions = ?, ip_allowlist = ?
                 WHERE user_id = ANY(?) AND api_key = ? AND status = 'ACTIVE'
                """, label, permissions, ipAllowlist, (Object) userIds.toArray(Long[]::new), apiKey) == 1;
        if (updated && authState != null) authState.apiKeyChanged(apiKey);
        return updated;
    }

    public java.util.Optional<ApiKeyRecord> findActiveForUsers(List<Long> userIds, String apiKey) {
        if (userIds == null || userIds.isEmpty() || apiKey == null || apiKey.isBlank()) return java.util.Optional.empty();
        List<ApiKeyRecord> rows = jdbcTemplate.query("""
                SELECT api_key_id, user_id, api_key, secret_ciphertext, label, permissions, ip_allowlist,
                       status, created_at, revoked_at
                  FROM gateway_api_keys
                 WHERE user_id = ANY(?) AND api_key = ? AND status = 'ACTIVE'
                """, (rs, rowNum) -> toRecord(rs), (Object) userIds.toArray(Long[]::new), apiKey);
        return rows.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(rows.getFirst());
    }

    private ApiKeyRecord toRecord(java.sql.ResultSet rs) throws java.sql.SQLException {
        Timestamp revokedAt = rs.getTimestamp("revoked_at");
        return new ApiKeyRecord(
                rs.getObject("api_key_id", UUID.class),
                rs.getLong("user_id"),
                rs.getString("api_key"),
                rs.getString("secret_ciphertext"),
                rs.getString("label"),
                rs.getString("permissions"),
                rs.getString("ip_allowlist"),
                rs.getString("status"),
                rs.getTimestamp("created_at").toInstant(),
                revokedAt == null ? null : revokedAt.toInstant());
    }

    public record ApiKeyRecord(UUID apiKeyId, long userId, String apiKey, String secretCiphertext,
                               String label, String permissions, String ipAllowlist, String status, Instant createdAt,
                               Instant revokedAt) {
    }
}
