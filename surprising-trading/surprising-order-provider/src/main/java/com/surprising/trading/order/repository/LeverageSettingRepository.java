package com.surprising.trading.order.repository;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.LeverageSettingRequest;
import com.surprising.trading.api.model.MarginMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
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
        ProductLine productLine = productLine(request.productLine());
        jdbcTemplate.update("""
                INSERT INTO trading_leverage_settings (
                    product_line, user_id, symbol, margin_mode, leverage_ppm, reason, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (product_line, user_id, symbol, margin_mode) DO UPDATE SET
                    leverage_ppm = EXCLUDED.leverage_ppm,
                    reason = EXCLUDED.reason,
                    updated_at = EXCLUDED.updated_at
                """, productLine.name(), request.userId(), normalizeSymbol(request.symbol()), marginMode.name(),
                request.leveragePpm(), emptyToNull(request.reason()), Timestamp.from(now), Timestamp.from(now));
    }

    /** 启动恢复使用的单表快照，不参与下单热路径。 */
    public List<LeverageSnapshot> snapshot(ProductLine productLine) {
        return jdbcTemplate.query("""
                SELECT user_id, symbol, margin_mode, leverage_ppm
                  FROM trading_leverage_settings
                 WHERE product_line = ?
                 ORDER BY user_id ASC, symbol ASC, margin_mode ASC
                """, (rs, rowNum) -> new LeverageSnapshot(
                rs.getLong("user_id"), rs.getString("symbol"),
                MarginMode.fromNullableDbValue(rs.getString("margin_mode")), rs.getLong("leverage_ppm")),
                productLine(productLine).name());
    }

    public record LeverageSnapshot(long userId, String symbol, MarginMode marginMode, long leveragePpm) {
    }

    private static ProductLine productLine(ProductLine productLine) {
        if (productLine == null) {
            throw new IllegalArgumentException("productLine is required");
        }
        return productLine;
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
