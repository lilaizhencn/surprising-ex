package com.surprising.adl.provider.repository;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.PositionSide;
import java.util.HashMap;
import java.util.Map;
import java.util.OptionalLong;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 只负责读取 {@code account_position_margins} 表。
 */
@Repository
public class AdlPositionMarginRepository {

    private final JdbcTemplate jdbcTemplate;

    public AdlPositionMarginRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Map<MarginKey, Long> findAll(ProductLine productLine) {
        Map<MarginKey, Long> result = new HashMap<>();
        jdbcTemplate.query("""
                SELECT user_id, symbol, asset, margin_mode, position_side, margin_units
                  FROM account_position_margins
                 WHERE product_line = ?
                """, rs -> {
            while (rs.next()) {
                result.put(new MarginKey(rs.getLong("user_id"), rs.getString("symbol"),
                                rs.getString("asset"), MarginMode.fromNullableDbValue(rs.getString("margin_mode")),
                                PositionSide.fromNullableDbValue(rs.getString("position_side"))),
                        rs.getLong("margin_units"));
            }
            return null;
        }, productLine.name());
        return Map.copyOf(result);
    }

    public OptionalLong find(ProductLine productLine,
                             long userId,
                             String symbol,
                             String asset,
                             MarginMode marginMode,
                             PositionSide positionSide) {
        return jdbcTemplate.query("""
                SELECT margin_units
                  FROM account_position_margins
                 WHERE product_line = ?
                   AND user_id = ?
                   AND symbol = ?
                   AND asset = ?
                   AND margin_mode = ?
                   AND position_side = ?
                """, (rs, rowNum) -> rs.getLong("margin_units"), productLine.name(), userId, symbol,
                asset, MarginMode.defaultIfNull(marginMode).name(), PositionSide.defaultIfNull(positionSide).name())
                .stream().mapToLong(Long::longValue).findFirst();
    }

    public record MarginKey(long userId,
                            String symbol,
                            String asset,
                            MarginMode marginMode,
                            PositionSide positionSide) {
    }
}
