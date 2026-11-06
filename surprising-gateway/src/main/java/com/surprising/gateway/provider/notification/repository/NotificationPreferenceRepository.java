package com.surprising.gateway.provider.notification.repository;

import com.surprising.gateway.provider.notification.model.NotificationPreference;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class NotificationPreferenceRepository {

    private final JdbcTemplate jdbcTemplate;

    public NotificationPreferenceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public NotificationPreference findByUserId(long userId) {
        var list = jdbcTemplate.query("""
                SELECT user_id,
                       market_volatility_push, market_volatility_in_app, market_volatility_threshold_pct,
                       security_login_push, security_login_email,
                       trading_fill_push, trading_fill_in_app,
                       liquidation_risk_push, liquidation_risk_in_app,
                       asset_transfer_push, asset_transfer_in_app,
                       c2c_order_push, c2c_order_in_app,
                       updated_at
                  FROM gateway_user_notification_preferences
                 WHERE user_id = ?
                """, (rs, rowNum) -> new NotificationPreference(
                rs.getLong("user_id"),
                rs.getBoolean("market_volatility_push"),
                rs.getBoolean("market_volatility_in_app"),
                rs.getBigDecimal("market_volatility_threshold_pct"),
                rs.getBoolean("security_login_push"),
                rs.getBoolean("security_login_email"),
                rs.getBoolean("trading_fill_push"),
                rs.getBoolean("trading_fill_in_app"),
                rs.getBoolean("liquidation_risk_push"),
                rs.getBoolean("liquidation_risk_in_app"),
                rs.getBoolean("asset_transfer_push"),
                rs.getBoolean("asset_transfer_in_app"),
                rs.getBoolean("c2c_order_push"),
                rs.getBoolean("c2c_order_in_app"),
                rs.getTimestamp("updated_at").toInstant()
        ), userId);

        if (list.isEmpty()) {
            return NotificationPreference.defaultForUser(userId);
        }
        return list.get(0);
    }

    public void upsert(NotificationPreference pref, Instant now) {
        Timestamp ts = Timestamp.from(now);
        BigDecimal threshold = pref.marketVolatilityThresholdPct() != null
                ? pref.marketVolatilityThresholdPct()
                : new BigDecimal("5.00");

        jdbcTemplate.update("""
                INSERT INTO gateway_user_notification_preferences (
                    user_id,
                    market_volatility_push, market_volatility_in_app, market_volatility_threshold_pct,
                    security_login_push, security_login_email,
                    trading_fill_push, trading_fill_in_app,
                    liquidation_risk_push, liquidation_risk_in_app,
                    asset_transfer_push, asset_transfer_in_app,
                    c2c_order_push, c2c_order_in_app,
                    created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (user_id) DO UPDATE SET
                    market_volatility_push = EXCLUDED.market_volatility_push,
                    market_volatility_in_app = EXCLUDED.market_volatility_in_app,
                    market_volatility_threshold_pct = EXCLUDED.market_volatility_threshold_pct,
                    security_login_push = EXCLUDED.security_login_push,
                    security_login_email = EXCLUDED.security_login_email,
                    trading_fill_push = EXCLUDED.trading_fill_push,
                    trading_fill_in_app = EXCLUDED.trading_fill_in_app,
                    liquidation_risk_push = EXCLUDED.liquidation_risk_push,
                    liquidation_risk_in_app = EXCLUDED.liquidation_risk_in_app,
                    asset_transfer_push = EXCLUDED.asset_transfer_push,
                    asset_transfer_in_app = EXCLUDED.asset_transfer_in_app,
                    c2c_order_push = EXCLUDED.c2c_order_push,
                    c2c_order_in_app = EXCLUDED.c2c_order_in_app,
                    updated_at = EXCLUDED.updated_at
                """,
                pref.userId(),
                pref.marketVolatilityPush(), pref.marketVolatilityInApp(), threshold,
                pref.securityLoginPush(), pref.securityLoginEmail(),
                pref.tradingFillPush(), pref.tradingFillInApp(),
                pref.liquidationRiskPush(), pref.liquidationRiskInApp(),
                pref.assetTransferPush(), pref.assetTransferInApp(),
                pref.c2cOrderPush(), pref.c2cOrderInApp(),
                ts, ts);
    }
}
