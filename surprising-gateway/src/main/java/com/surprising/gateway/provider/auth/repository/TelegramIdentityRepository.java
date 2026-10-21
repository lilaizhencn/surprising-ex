package com.surprising.gateway.provider.auth.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 只访问gateway_telegram_identities；subject级事务锁串行化不存在映射时的首次注册。 */
@Repository
public class TelegramIdentityRepository {
    private final JdbcTemplate jdbc;
    public TelegramIdentityRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
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
}
