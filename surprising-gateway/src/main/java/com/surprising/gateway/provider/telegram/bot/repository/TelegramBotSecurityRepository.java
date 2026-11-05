package com.surprising.gateway.provider.telegram.bot.repository;

import com.surprising.gateway.provider.telegram.bot.model.TelegramSecurityMode;
import com.surprising.gateway.provider.telegram.bot.model.TelegramSecuritySettings;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

@Repository
public class TelegramBotSecurityRepository {

    private final JdbcTemplate jdbc;

    public TelegramBotSecurityRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<TelegramSecuritySettings> findSettings(long userId) {
        String sql = "SELECT user_id, security_mode, trade_pin_hash IS NOT NULL as has_pin, updated_at " +
                "FROM gateway_user_telegram_security_settings WHERE user_id = ?";
        return jdbc.query(sql, (rs, rowNum) -> new TelegramSecuritySettings(
                rs.getLong("user_id"),
                TelegramSecurityMode.fromString(rs.getString("security_mode")),
                rs.getBoolean("has_pin"),
                rs.getTimestamp("updated_at").toInstant()
        ), userId).stream().findFirst();
    }

    public Optional<String> findTradePinHash(long userId) {
        String sql = "SELECT trade_pin_hash FROM gateway_user_telegram_security_settings WHERE user_id = ?";
        return jdbc.query(sql, (rs, rowNum) -> rs.getString("trade_pin_hash"), userId)
                .stream().findFirst().filter(h -> h != null && !h.isBlank());
    }

    public void upsertMode(long userId, TelegramSecurityMode mode, Instant now) {
        String sql = "INSERT INTO gateway_user_telegram_security_settings(user_id, security_mode, updated_at) " +
                "VALUES (?, ?, ?) " +
                "ON CONFLICT (user_id) DO UPDATE SET security_mode = EXCLUDED.security_mode, updated_at = EXCLUDED.updated_at";
        jdbc.update(sql, userId, mode.name(), Timestamp.from(now));
    }

    public void upsertTradePin(long userId, String pinHash, Instant now) {
        String sql = "INSERT INTO gateway_user_telegram_security_settings(user_id, security_mode, trade_pin_hash, updated_at) " +
                "VALUES (?, 'TRADE_PASSWORD', ?, ?) " +
                "ON CONFLICT (user_id) DO UPDATE SET trade_pin_hash = EXCLUDED.trade_pin_hash, updated_at = EXCLUDED.updated_at";
        jdbc.update(sql, userId, pinHash, Timestamp.from(now));
    }
}
