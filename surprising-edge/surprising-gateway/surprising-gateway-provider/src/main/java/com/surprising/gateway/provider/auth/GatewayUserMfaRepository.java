package com.surprising.gateway.provider.auth;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 只负责 {@code gateway_user_mfa} 表。
 */
@Repository
public class GatewayUserMfaRepository {

    private final JdbcTemplate jdbcTemplate;

    public GatewayUserMfaRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<MfaCredential> find(long userId) {
        return jdbcTemplate.query("""
                SELECT user_id, totp_secret_ciphertext, enabled, verified_at, created_at, updated_at
                  FROM gateway_user_mfa
                 WHERE user_id = ?
                """, (rs, rowNum) -> new MfaCredential(
                        rs.getLong("user_id"),
                        rs.getString("totp_secret_ciphertext"),
                        rs.getBoolean("enabled"),
                        nullableInstant(rs, "verified_at"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant()),
                userId).stream().findFirst();
    }

    public void upsertSecret(long userId, String secretCiphertext, Instant now) {
        jdbcTemplate.update("""
                INSERT INTO gateway_user_mfa (
                    user_id, totp_secret_ciphertext, enabled, verified_at, created_at, updated_at
                ) VALUES (?, ?, FALSE, NULL, ?, ?)
                ON CONFLICT (user_id) DO UPDATE
                   SET totp_secret_ciphertext = EXCLUDED.totp_secret_ciphertext,
                       enabled = FALSE,
                       verified_at = NULL,
                       updated_at = EXCLUDED.updated_at
                """, userId, secretCiphertext, Timestamp.from(now), Timestamp.from(now));
    }

    public void enable(long userId, Instant now) {
        int updated = jdbcTemplate.update("""
                UPDATE gateway_user_mfa
                   SET enabled = TRUE,
                       verified_at = ?,
                       updated_at = ?
                 WHERE user_id = ?
                """, Timestamp.from(now), Timestamp.from(now), userId);
        if (updated == 0) {
            throw new IllegalArgumentException("mfa enrollment not found");
        }
    }

    public void disable(long userId, Instant now) {
        jdbcTemplate.update("""
                UPDATE gateway_user_mfa
                   SET enabled = FALSE,
                       verified_at = NULL,
                       updated_at = ?
                 WHERE user_id = ?
                """, Timestamp.from(now), userId);
    }

    private Instant nullableInstant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    public record MfaCredential(
            long userId,
            String totpSecretCiphertext,
            boolean enabled,
            Instant verifiedAt,
            Instant createdAt,
            Instant updatedAt) {
    }
}
