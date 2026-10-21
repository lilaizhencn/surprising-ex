package com.surprising.gateway.provider.auth;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Shared database state makes callbacks and redemption work across gateway instances. */
@Repository
public class TelegramLoginRepository {
    private final JdbcTemplate jdbc;

    public TelegramLoginRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void create(String stateHash, String verifierHash, String nonce, String pkce,
                       String deviceId, Instant expiresAt, boolean returnToApp) {
        jdbc.update("DELETE FROM gateway_telegram_login_flows WHERE expires_at < now()");
        Integer active = jdbc.queryForObject("""
                SELECT count(*) FROM gateway_telegram_login_flows WHERE device_id = ?
                """, Integer.class, deviceId);
        if (active != null && active >= 5) throw new IllegalArgumentException("too many Telegram login attempts");
        jdbc.update("""
                INSERT INTO gateway_telegram_login_flows
                    (state_hash, verifier_hash, nonce, code_verifier, device_id, expires_at, return_to_app)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, stateHash, verifierHash, nonce, pkce, deviceId, Timestamp.from(expiresAt), returnToApp);
    }

    public Optional<Flow> claimCallback(String stateHash, Instant now) {
        return jdbc.query("""
                UPDATE gateway_telegram_login_flows SET callback_claimed = true
                 WHERE state_hash = ? AND expires_at > ? AND NOT callback_claimed
                RETURNING nonce, code_verifier, return_to_app
                """, (rs, row) -> new Flow(rs.getString("nonce"), rs.getString("code_verifier"), rs.getBoolean("return_to_app")),
                stateHash, Timestamp.from(now)).stream().findFirst();
    }

    public void authorized(String stateHash, String subject, String displayName) {
        jdbc.update("""
                UPDATE gateway_telegram_login_flows SET subject = ?, display_name = ?
                 WHERE state_hash = ? AND callback_claimed AND NOT failed
                """, subject, displayName, stateHash);
    }

    public void failed(String stateHash) {
        jdbc.update("UPDATE gateway_telegram_login_flows SET failed = true WHERE state_hash = ?", stateHash);
    }

    /** Caller holds the row lock until account creation and session issuance commit. */
    public Optional<Identity> lockResult(String stateHash, String verifierHash, String deviceId, Instant now) {
        return jdbc.query("""
                SELECT subject, display_name, failed FROM gateway_telegram_login_flows
                 WHERE state_hash = ? AND verifier_hash = ? AND device_id = ? AND expires_at > ?
                 FOR UPDATE
                """, (rs, row) -> new Identity(rs.getString("subject"), rs.getString("display_name"),
                rs.getBoolean("failed")), stateHash, verifierHash, deviceId, Timestamp.from(now))
                .stream().findFirst();
    }

    public void consume(String stateHash) {
        jdbc.update("DELETE FROM gateway_telegram_login_flows WHERE state_hash = ?", stateHash);
    }

    public Optional<Long> lockIdentity(String subject) {
        // Serializes concurrent first registrations for this Telegram identity only.
        jdbc.execute(connection -> {
            var statement = connection.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))");
            statement.setString(1, "telegram:" + subject);
            return statement;
        }, (org.springframework.jdbc.core.PreparedStatementCallback<Void>) statement -> {
            statement.execute();
            return null;
        });
        return jdbc.query("SELECT user_id FROM gateway_telegram_identities WHERE subject = ?",
                (rs, row) -> rs.getLong(1), subject).stream().findFirst();
    }

    public void bind(String subject, long userId, Instant now) {
        jdbc.update("INSERT INTO gateway_telegram_identities(subject, user_id, created_at) VALUES (?, ?, ?)",
                subject, userId, Timestamp.from(now));
    }

    public void updateNickname(long userId, String name, Instant now) {
        jdbc.update("UPDATE gateway_users SET display_name = ?, updated_at = ? WHERE user_id = ?",
                name, Timestamp.from(now), userId);
    }

    public record Flow(String nonce, String codeVerifier, boolean returnToApp) {}
    public record Identity(String subject, String displayName, boolean failed) {}
}
