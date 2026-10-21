package com.surprising.gateway.provider.auth.repository;

import com.surprising.gateway.provider.auth.model.LoginVerificationRows;
import com.surprising.gateway.provider.auth.model.LoginVerificationRows.Challenge;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 只访问gateway_login_challenges；用户行锁和跨表业务由Service聚合。 */
@Repository
public class LoginChallengeRepository {
    private final JdbcTemplate jdbc;

    public LoginChallengeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
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

}
