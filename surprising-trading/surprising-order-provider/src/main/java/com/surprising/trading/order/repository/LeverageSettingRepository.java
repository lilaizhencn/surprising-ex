package com.surprising.trading.order.repository;

import com.surprising.trading.api.model.LeverageSettingRequest;
import com.surprising.trading.api.model.LeverageSettingResponse;
import com.surprising.trading.api.model.MarginMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class LeverageSettingRepository {

    private final JdbcTemplate jdbcTemplate;

    public LeverageSettingRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void upsert(LeverageSettingRequest request, Instant now) {
        MarginMode marginMode = MarginMode.defaultIfNull(request.marginMode());
        jdbcTemplate.update("""
                INSERT INTO trading_leverage_settings (
                    user_id, symbol, margin_mode, leverage_ppm, reason, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (user_id, symbol, margin_mode) DO UPDATE SET
                    leverage_ppm = EXCLUDED.leverage_ppm,
                    reason = EXCLUDED.reason,
                    updated_at = EXCLUDED.updated_at
                """, request.userId(), normalizeSymbol(request.symbol()), marginMode.name(), request.leveragePpm(),
                emptyToNull(request.reason()), Timestamp.from(now), Timestamp.from(now));
    }

    public Optional<LeverageSettingResponse> userSetting(long userId,
                                                         String symbol,
                                                         MarginMode marginMode,
                                                         long maxLeveragePpm) {
        MarginMode normalizedMarginMode = MarginMode.defaultIfNull(marginMode);
        return jdbcTemplate.query("""
                SELECT user_id, symbol, margin_mode, leverage_ppm, updated_at
                  FROM trading_leverage_settings
                 WHERE user_id = ? AND symbol = ? AND margin_mode = ?
                """, (rs, rowNum) -> toResponse(rs, maxLeveragePpm, "USER"),
                userId, normalizeSymbol(symbol), normalizedMarginMode.name()).stream().findFirst();
    }

    public Optional<Long> leveragePpm(long userId, String symbol, MarginMode marginMode) {
        return jdbcTemplate.query("""
                SELECT leverage_ppm
                  FROM trading_leverage_settings
                 WHERE user_id = ? AND symbol = ? AND margin_mode = ?
                """, (rs, rowNum) -> rs.getLong("leverage_ppm"),
                userId, normalizeSymbol(symbol), MarginMode.defaultIfNull(marginMode).name()).stream().findFirst();
    }

    public LeverageSettingResponse instrumentDefault(long userId,
                                                     String symbol,
                                                     MarginMode marginMode,
                                                     long maxLeveragePpm,
                                                     long initialMarginRatePpm) {
        // A missing user setting means "use the product default", capped by the instrument max leverage.
        long leveragePpm = Math.min(OrderLeverageMath.leveragePpmFromInitialMarginRate(initialMarginRatePpm),
                maxLeveragePpm);
        long effectiveInitialMarginRatePpm = Math.max(initialMarginRatePpm,
                OrderLeverageMath.initialMarginRateFromLeveragePpm(leveragePpm));
        return new LeverageSettingResponse(userId, normalizeSymbol(symbol), MarginMode.defaultIfNull(marginMode),
                leveragePpm, maxLeveragePpm, effectiveInitialMarginRatePpm,
                "INSTRUMENT_DEFAULT", Instant.EPOCH);
    }

    private LeverageSettingResponse toResponse(ResultSet rs, long maxLeveragePpm, String source) throws SQLException {
        long leveragePpm = rs.getLong("leverage_ppm");
        return new LeverageSettingResponse(
                rs.getLong("user_id"),
                rs.getString("symbol"),
                MarginMode.fromNullableDbValue(rs.getString("margin_mode")),
                leveragePpm,
                maxLeveragePpm,
                OrderLeverageMath.initialMarginRateFromLeveragePpm(leveragePpm),
                source,
                rs.getTimestamp("updated_at").toInstant());
    }

    private static String normalizeSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("symbol is required");
        }
        String normalized = symbol.trim().toUpperCase();
        if (!normalized.matches("[A-Z0-9][A-Z0-9_-]{1,63}")) {
            throw new IllegalArgumentException("invalid symbol: " + symbol);
        }
        return normalized;
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
