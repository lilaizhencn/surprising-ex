package com.surprising.gateway.provider.auth.repository;

import com.surprising.gateway.provider.auth.model.AuthChallenge;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class GatewayAuthChallengeRepository {

    private final JdbcTemplate jdbcTemplate;

    public GatewayAuthChallengeRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public AuthChallenge create(long userId,
                            String purpose,
                            String channel,
                            String destination,
                            String codeHash,
                            Instant expiresAt,
                            String requestIp,
                            Instant now) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO gateway_auth_challenges (
                    user_id, purpose, channel, destination, code_hash,
                    expires_at, attempts, request_ip, created_at
) VALUES (?, ?, ?, ?, ?, ?, 0, CAST(? AS inet), ?)
                RETURNING challenge_id, user_id, purpose, channel, destination,
                          code_hash, expires_at, attempts, consumed_at
                """, (rs, rowNum) -> new AuthChallenge(
                        rs.getLong("challenge_id"),
                        rs.getLong("user_id"),
                        rs.getString("purpose"),
                        rs.getString("channel"),
                        rs.getString("destination"),
                        rs.getString("code_hash"),
                        rs.getTimestamp("expires_at").toInstant(),
                        rs.getInt("attempts"),
                        nullableInstant(rs, "consumed_at")),
                userId, purpose, channel, destination, codeHash,
                Timestamp.from(expiresAt), requestIp, Timestamp.from(now));
    }

    public Optional<AuthChallenge> findActive(long userId, String purpose, String destination, Instant now) {
        return jdbcTemplate.query("""
                SELECT challenge_id, user_id, purpose, channel, destination,
                       code_hash, expires_at, attempts, consumed_at
                  FROM gateway_auth_challenges
                 WHERE user_id = ?
                   AND purpose = ?
                   AND destination = ?
                   AND consumed_at IS NULL
                   AND expires_at > ?
                 ORDER BY created_at DESC, challenge_id DESC
                 LIMIT 1
                """, (rs, rowNum) -> toChallenge(rs), userId, purpose, destination,
                Timestamp.from(now)).stream().findFirst();
    }

    public Optional<AuthChallenge> findActive(long challengeId, long userId, String purpose, Instant now) {
        return jdbcTemplate.query("""
                SELECT challenge_id, user_id, purpose, channel, destination, code_hash,
                       expires_at, attempts, consumed_at
                  FROM gateway_auth_challenges
                 WHERE challenge_id = ? AND user_id = ? AND purpose = ?
                   AND consumed_at IS NULL AND expires_at > ? AND attempts < 5
                """, (rs, rowNum) -> toChallenge(rs), challengeId, userId, purpose, Timestamp.from(now))
                .stream().findFirst();
    }

    public boolean incrementAttempts(long challengeId, long userId, Instant now) {
        return jdbcTemplate.update("""
                UPDATE gateway_auth_challenges
                   SET attempts = attempts + 1,
                       updated_at = ?
                 WHERE challenge_id = ?
                   AND user_id = ?
                   AND consumed_at IS NULL
                   AND expires_at > ?
                   AND attempts < 5
                """, Timestamp.from(now), challengeId, userId, Timestamp.from(now)) > 0;
    }

    public boolean consume(long challengeId, long userId, Instant now) {
        return jdbcTemplate.update("""
                UPDATE gateway_auth_challenges
                   SET consumed_at = ?,
                       updated_at = ?
                 WHERE challenge_id = ?
                   AND user_id = ?
                   AND consumed_at IS NULL
                   AND expires_at > ?
                   AND attempts < 5
                """, Timestamp.from(now), Timestamp.from(now), challengeId, userId,
                Timestamp.from(now)) > 0;
    }


    private AuthChallenge toChallenge(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new AuthChallenge(
                rs.getLong("challenge_id"),
                rs.getLong("user_id"),
                rs.getString("purpose"),
                rs.getString("channel"),
                rs.getString("destination"),
                rs.getString("code_hash"),
                rs.getTimestamp("expires_at").toInstant(),
                rs.getInt("attempts"),
                nullableInstant(rs, "consumed_at"));
    }

    private Instant nullableInstant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

}
