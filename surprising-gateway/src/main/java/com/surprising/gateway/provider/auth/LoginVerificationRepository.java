package com.surprising.gateway.provider.auth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 每个用户每种用途仅一条挑战；行锁串行化验证、失败计数与一次性消费。 */
@Repository
public class LoginVerificationRepository {
    private final JdbcTemplate jdbc;

    public LoginVerificationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void lockUser(long userId) {
        jdbc.queryForObject(
                "SELECT user_id FROM gateway_users WHERE user_id=? FOR UPDATE", Long.class, userId);
    }

    public List<Factor> factors(long userId) {
        return jdbc.query(
                "SELECT method,destination,enabled,verified_at,updated_at FROM"
                    + " gateway_login_factors WHERE user_id=? ORDER BY method",
                (r, n) ->
                        new Factor(
                                r.getString(1),
                                r.getString(2),
                                r.getBoolean(3),
                                r.getTimestamp(4).toInstant(),
                                r.getTimestamp(5).toInstant()),
                userId);
    }

    public void saveFactor(
            long userId, String method, String destination, boolean enabled, Instant now) {
        jdbc.update(
                """
                INSERT INTO gateway_login_factors(user_id,method,destination,enabled,verified_at,updated_at)
                VALUES(?,?,?,?,?,?) ON CONFLICT(user_id,method) DO UPDATE
                SET destination=EXCLUDED.destination,enabled=EXCLUDED.enabled,verified_at=EXCLUDED.verified_at,updated_at=EXCLUDED.updated_at
                """,
                userId,
                method,
                destination,
                enabled,
                Timestamp.from(now),
                Timestamp.from(now));
        if (enabled) {
            String column = "EMAIL".equals(method) ? "email" : "phone";
            jdbc.update(
                    "UPDATE gateway_users SET "
                            + column
                            + "=?,updated_at=?"
                            + ("EMAIL".equals(method) ? ",email_verified_at=CURRENT_TIMESTAMP" : "")
                            + " WHERE user_id=?",
                    destination,
                    Timestamp.from(now),
                    userId);
        }
    }

    public Optional<Challenge> find(String tokenHash) {
        return jdbc
                .query(
                        "SELECT * FROM gateway_login_challenges WHERE token_hash=?",
                        (r, n) ->
                                new Challenge(
                                        r.getLong("user_id"),
                                        r.getString("purpose"),
                                        r.getString("token_hash"),
                                        r.getString("fingerprint"),
                                        r.getString("methods"),
                                        r.getString("email_hash"),
                                        r.getString("phone_hash"),
                                        r.getString("destination"),
                                        r.getTimestamp("expires_at").toInstant(),
                                        r.getBoolean("consumed"),
                                        r.getInt("attempts"),
                                        r.getBoolean("target_enabled")),
                        tokenHash)
                .stream()
                .findFirst();
    }

    public boolean issue(
            long userId,
            String purpose,
            String tokenHash,
            String fingerprint,
            String methods,
            String emailHash,
            String phoneHash,
            String destination,
            boolean targetEnabled,
            Instant now) {
        return jdbc.update(
                        """
                        INSERT INTO gateway_login_challenges(user_id,purpose,token_hash,fingerprint,methods,email_hash,phone_hash,destination,
                          expires_at,window_start,issued_at,issued,target_enabled) VALUES(?,?,?,?,?,?,?,?,?,?,?,1,?)
                        ON CONFLICT(user_id,purpose) DO UPDATE SET token_hash=EXCLUDED.token_hash,fingerprint=EXCLUDED.fingerprint,
                          methods=EXCLUDED.methods,email_hash=EXCLUDED.email_hash,phone_hash=EXCLUDED.phone_hash,destination=EXCLUDED.destination,
                          expires_at=EXCLUDED.expires_at,consumed=false,issued_at=EXCLUDED.issued_at,target_enabled=EXCLUDED.target_enabled,
                          attempts=CASE WHEN gateway_login_challenges.window_start<=? THEN 0 ELSE gateway_login_challenges.attempts END,
                          issued=CASE WHEN gateway_login_challenges.window_start<=? THEN 1 ELSE gateway_login_challenges.issued+1 END,
                          window_start=CASE WHEN gateway_login_challenges.window_start<=? THEN EXCLUDED.window_start ELSE gateway_login_challenges.window_start END
                        WHERE gateway_login_challenges.issued_at<=? AND
                          (gateway_login_challenges.window_start<=? OR (gateway_login_challenges.attempts<5 AND gateway_login_challenges.issued<10))
                        """,
                        userId,
                        purpose,
                        tokenHash,
                        fingerprint,
                        methods,
                        emailHash,
                        phoneHash,
                        destination,
                        Timestamp.from(now.plusSeconds(300)),
                        Timestamp.from(now),
                        Timestamp.from(now),
                        targetEnabled,
                        Timestamp.from(now.minusSeconds(600)),
                        Timestamp.from(now.minusSeconds(600)),
                        Timestamp.from(now.minusSeconds(600)),
                        Timestamp.from(now.minusSeconds(60)),
                        Timestamp.from(now.minusSeconds(600)))
                == 1;
    }

    public void failed(String tokenHash) {
        jdbc.update(
                "UPDATE gateway_login_challenges SET attempts=attempts+1 WHERE token_hash=? AND"
                    + " attempts<5",
                tokenHash);
    }

    public void consume(String tokenHash) {
        jdbc.update(
                "UPDATE gateway_login_challenges SET consumed=true WHERE token_hash=?", tokenHash);
    }

    public boolean consumeTotp(long userId, long step) {
        return jdbc.update(
                        "UPDATE gateway_user_mfa SET last_login_step=? WHERE user_id=? AND"
                            + " enabled=true AND verified_at IS NOT NULL AND last_login_step<?",
                        step,
                        userId,
                        step)
                == 1;
    }

    public record Factor(
            String method,
            String destination,
            boolean enabled,
            Instant verifiedAt,
            Instant updatedAt) {}

    public record Challenge(
            long userId,
            String purpose,
            String tokenHash,
            String fingerprint,
            String methods,
            String emailHash,
            String phoneHash,
            String destination,
            Instant expiresAt,
            boolean consumed,
            int attempts,
            boolean targetEnabled) {}
}
