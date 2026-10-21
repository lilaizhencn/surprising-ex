package com.surprising.gateway.provider.auth.repository;

import com.surprising.gateway.provider.auth.model.TelegramLoginRows;
import com.surprising.gateway.provider.auth.model.TelegramLoginRows.Flow;
import com.surprising.gateway.provider.auth.model.TelegramLoginRows.Result;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Shared database state makes callbacks and redemption work across gateway instances. */
@Repository
public class TelegramLoginFlowRepository {
    private final JdbcTemplate jdbc;

    public TelegramLoginFlowRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** 同设备新建流水需在调用方事务中串行，避免COUNT后并发INSERT突破五个未完成尝试的限制。 */
    public void lockDevice(String deviceId) {
        jdbc.execute(connection->{
            var statement=connection.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?,0))");
            statement.setString(1,"telegram-login-device:"+deviceId);return statement;
        },(org.springframework.jdbc.core.PreparedStatementCallback<Void>)statement->{statement.execute();return null;});
    }
    public void removeExpired() { jdbc.update("DELETE FROM gateway_telegram_login_flows WHERE expires_at < now()"); }
    public int activeCount(String deviceId) {
        return jdbc.queryForObject("SELECT count(*) FROM gateway_telegram_login_flows WHERE device_id=?",Integer.class,deviceId);
    }
    public void create(String stateHash, String verifierHash, String nonce, String pkce,
                       String deviceId, Instant expiresAt, boolean returnToApp) {
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
    public Optional<Result> lockResult(String stateHash, String verifierHash, String deviceId, Instant now) {
        return jdbc.query("""
                SELECT subject, display_name, failed FROM gateway_telegram_login_flows
                 WHERE state_hash = ? AND verifier_hash = ? AND device_id = ? AND expires_at > ?
                 FOR UPDATE
                """, (rs, row) -> new Result(rs.getString("subject"), rs.getString("display_name"),
                rs.getBoolean("failed")), stateHash, verifierHash, deviceId, Timestamp.from(now))
                .stream().findFirst();
    }

    public void consume(String stateHash) {
        jdbc.update("DELETE FROM gateway_telegram_login_flows WHERE state_hash = ?", stateHash);
    }

}
