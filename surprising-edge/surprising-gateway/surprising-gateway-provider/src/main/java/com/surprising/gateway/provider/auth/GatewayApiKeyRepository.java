package com.surprising.gateway.provider.auth;

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

    public ApiKeyRecord create(UUID apiKeyId, long userId, String apiKey, String secretCiphertext,
                               String label, String permissions, Instant now) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO gateway_api_keys (
                    api_key_id, user_id, api_key, secret_ciphertext, label, permissions, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                RETURNING api_key_id, user_id, api_key, secret_ciphertext, label, permissions,
                          status, created_at, last_used_at, revoked_at
                """, (rs, rowNum) -> toRecord(rs), apiKeyId, userId, apiKey, secretCiphertext,
                label, permissions, Timestamp.from(now));
    }

    public Optional<ApiKeyRecord> active(String apiKey) {
        return jdbcTemplate.query("""
                SELECT api_key_id, user_id, api_key, secret_ciphertext, label, permissions,
                       status, created_at, last_used_at, revoked_at
                  FROM gateway_api_keys
                 WHERE api_key = ? AND status = 'ACTIVE'
                """, (rs, rowNum) -> toRecord(rs), apiKey).stream().findFirst();
    }

    public List<ApiKeyRecord> list(long userId) {
        return jdbcTemplate.query("""
                SELECT api_key_id, user_id, api_key, secret_ciphertext, label, permissions,
                       status, created_at, last_used_at, revoked_at
                  FROM gateway_api_keys
                 WHERE user_id = ?
                 ORDER BY created_at DESC, api_key_id DESC
                """, (rs, rowNum) -> toRecord(rs), userId);
    }

    public boolean revoke(long userId, String apiKey, Instant now) {
        return jdbcTemplate.update("""
                UPDATE gateway_api_keys
                   SET status = 'REVOKED', revoked_at = ?, last_used_at = last_used_at
                 WHERE user_id = ? AND api_key = ? AND status = 'ACTIVE'
                """, Timestamp.from(now), userId, apiKey) == 1;
    }

    public void markUsed(UUID apiKeyId, Instant now) {
        jdbcTemplate.update("""
                UPDATE gateway_api_keys SET last_used_at = ?
                 WHERE api_key_id = ? AND status = 'ACTIVE'
                """, Timestamp.from(now), apiKeyId);
    }

    private ApiKeyRecord toRecord(java.sql.ResultSet rs) throws java.sql.SQLException {
        Timestamp lastUsedAt = rs.getTimestamp("last_used_at");
        Timestamp revokedAt = rs.getTimestamp("revoked_at");
        return new ApiKeyRecord(
                rs.getObject("api_key_id", UUID.class),
                rs.getLong("user_id"),
                rs.getString("api_key"),
                rs.getString("secret_ciphertext"),
                rs.getString("label"),
                rs.getString("permissions"),
                rs.getString("status"),
                rs.getTimestamp("created_at").toInstant(),
                lastUsedAt == null ? null : lastUsedAt.toInstant(),
                revokedAt == null ? null : revokedAt.toInstant());
    }

    public record ApiKeyRecord(UUID apiKeyId, long userId, String apiKey, String secretCiphertext,
                               String label, String permissions, String status, Instant createdAt,
                               Instant lastUsedAt, Instant revokedAt) {
    }
}
