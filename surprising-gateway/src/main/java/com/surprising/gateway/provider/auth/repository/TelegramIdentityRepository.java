package com.surprising.gateway.provider.auth.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 只访问 gateway_telegram_identities。读取是普通查询，不持有行锁或咨询锁。 */
@Repository
public class TelegramIdentityRepository {
    private final JdbcTemplate jdbc;
    public TelegramIdentityRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public Optional<Long> find(String subject) {
        return jdbc.query("SELECT user_id FROM gateway_telegram_identities WHERE subject = ? AND revoked_at IS NULL",
                (rs, row) -> rs.getLong(1), subject).stream().findFirst();
    }
    public boolean revoked(String subject) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM gateway_telegram_identities WHERE subject = ? AND revoked_at IS NOT NULL)", Boolean.class, subject));
    }
    public Optional<Long> revoke(String subject, Instant now) {
        return jdbc.query("UPDATE gateway_telegram_identities SET revoked_at = ? WHERE subject = ? AND revoked_at IS NULL RETURNING user_id",
                (rs, row) -> rs.getLong(1), Timestamp.from(now), subject).stream().findFirst();
    }
    public void bind(String subject, long userId, Instant now) {
        jdbc.update("INSERT INTO gateway_telegram_identities(subject, user_id, created_at) VALUES (?, ?, ?)",
                subject, userId, Timestamp.from(now));
    }
}
