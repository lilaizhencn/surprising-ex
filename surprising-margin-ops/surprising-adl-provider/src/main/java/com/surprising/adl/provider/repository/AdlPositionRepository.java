package com.surprising.adl.provider.repository;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.PositionSide;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 只负责读取和锁定 {@code account_positions} 表。
 */
@Repository
public class AdlPositionRepository {

    private final JdbcTemplate jdbcTemplate;

    public AdlPositionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<PositionRow> open(ProductLine productLine) {
        return jdbcTemplate.query("""
                SELECT product_line, user_id, symbol, margin_mode, position_side,
                       instrument_version, signed_quantity_steps, entry_price_ticks
                  FROM account_positions
                 WHERE product_line = ?
                   AND signed_quantity_steps <> 0
                """, (rs, rowNum) -> row(rs), productLine.name());
    }

    public Optional<PositionRow> lock(ProductLine productLine,
                                      long userId,
                                      String symbol,
                                      MarginMode marginMode,
                                      PositionSide positionSide) {
        return jdbcTemplate.query("""
                SELECT product_line, user_id, symbol, margin_mode, position_side,
                       instrument_version, signed_quantity_steps, entry_price_ticks
                  FROM account_positions
                 WHERE product_line = ?
                   AND user_id = ?
                   AND symbol = ?
                   AND margin_mode = ?
                   AND position_side = ?
                   AND signed_quantity_steps <> 0
                 FOR UPDATE
                """, (rs, rowNum) -> row(rs), productLine.name(), userId, symbol,
                MarginMode.defaultIfNull(marginMode).name(), PositionSide.defaultIfNull(positionSide).name())
                .stream().findFirst();
    }

    private PositionRow row(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new PositionRow(
                ProductLine.valueOf(rs.getString("product_line")), rs.getLong("user_id"),
                rs.getString("symbol"), MarginMode.fromNullableDbValue(rs.getString("margin_mode")),
                PositionSide.fromNullableDbValue(rs.getString("position_side")),
                rs.getLong("instrument_version"), rs.getLong("signed_quantity_steps"),
                rs.getLong("entry_price_ticks"));
    }

    public record PositionRow(ProductLine productLine,
                              long userId,
                              String symbol,
                              MarginMode marginMode,
                              PositionSide positionSide,
                              long instrumentVersion,
                              long signedQuantitySteps,
                              long entryPriceTicks) {
    }
}
