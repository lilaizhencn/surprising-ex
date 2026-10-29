package com.surprising.gateway.provider.auth.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class GoogleLoginRepository {
    private final JdbcTemplate jdbc;
    public GoogleLoginRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void createChallenge(String nonceHash, String device, Instant expires) {
        lock("google-device:" + device);
        jdbc.update("DELETE FROM gateway_google_login_challenges WHERE expires_at <= now()");
        // One Tap starts automatically. Keep a bounded set across tabs without locking out page refreshes.
        jdbc.update("""
                DELETE FROM gateway_google_login_challenges WHERE nonce_hash IN (
                    SELECT nonce_hash FROM gateway_google_login_challenges WHERE device_id = ?
                    ORDER BY expires_at DESC, nonce_hash OFFSET 4)
                """, device);
        jdbc.update("INSERT INTO gateway_google_login_challenges(nonce_hash, device_id, expires_at) VALUES (?, ?, ?)",
                nonceHash, device, Timestamp.from(expires));
    }

    public boolean consumeChallenge(String nonceHash, String device, Instant now) {
        return jdbc.update("DELETE FROM gateway_google_login_challenges WHERE nonce_hash = ? AND device_id = ? AND expires_at > ?",
                nonceHash, device, Timestamp.from(now)) == 1;
    }

    public Optional<Long> lockIdentity(String subject) {
        lock("google-subject:" + subject);
        return jdbc.query("SELECT user_id FROM gateway_google_identities WHERE subject = ?",
                (rs, row) -> rs.getLong(1), subject).stream().findFirst();
    }

    public void bind(String subject, long userId, Instant now) {
        jdbc.update("INSERT INTO gateway_google_identities(subject, user_id, created_at) VALUES (?, ?, ?)",
                subject, userId, Timestamp.from(now));
    }

    private void lock(String key) {
        jdbc.execute(connection -> {
            var statement = connection.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))");
            statement.setString(1, key);
            return statement;
        }, (org.springframework.jdbc.core.PreparedStatementCallback<Void>) statement -> { statement.execute(); return null; });
    }
}
