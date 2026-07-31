package com.surprising.account.provider.repository;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.PositionSide;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PositionMarginRepository {

    private final JdbcTemplate jdbcTemplate;

    public PositionMarginRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<PositionMarginRow> find(ProductLine productLine,
                                            long userId,
                                            String symbol,
                                            String asset,
                                            MarginMode marginMode,
                                            PositionSide positionSide) {
        return jdbcTemplate.query("""
                SELECT symbol, asset, margin_mode, position_side, margin_units, updated_at
                  FROM account_position_margins
                 WHERE product_line = ?
                   AND user_id = ?
                   AND symbol = ?
                   AND asset = ?
                   AND margin_mode = ?
                   AND position_side = ?
                """, (rs, rowNum) -> toRow(rs), productLine.name(), userId, symbol, asset,
                MarginMode.defaultIfNull(marginMode).name(),
                PositionSide.defaultIfNull(positionSide).name())
                .stream().findFirst();
    }

    public Optional<PositionMarginRow> findLegacy(long userId,
                                                  String symbol,
                                                  String asset,
                                                  MarginMode marginMode,
                                                  PositionSide positionSide) {
        return jdbcTemplate.query("""
                SELECT symbol, asset, margin_mode, position_side, margin_units, updated_at
                  FROM account_position_margins
                 WHERE user_id = ?
                   AND symbol = ?
                   AND asset = ?
                   AND margin_mode = ?
                   AND position_side = ?
                """, (rs, rowNum) -> toRow(rs), userId, symbol, asset,
                MarginMode.defaultIfNull(marginMode).name(),
                PositionSide.defaultIfNull(positionSide).name())
                .stream().findFirst();
    }

    public long lockUnits(ProductLine productLine,
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
                 FOR UPDATE
                """, (rs, rowNum) -> rs.getLong("margin_units"), productLine.name(), userId, symbol, asset,
                MarginMode.defaultIfNull(marginMode).name(),
                PositionSide.defaultIfNull(positionSide).name())
                .stream().findFirst().orElse(0L);
    }

    public int add(ProductLine productLine,
                   long userId,
                   String symbol,
                   String asset,
                   MarginMode marginMode,
                   PositionSide positionSide,
                   long amountUnits,
                   Instant now) {
        return jdbcTemplate.update("""
                INSERT INTO account_position_margins (
                    product_line, user_id, symbol, asset, margin_mode, position_side, margin_units, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (product_line, user_id, symbol, asset, margin_mode, position_side) DO UPDATE
                   SET margin_units = account_position_margins.margin_units + EXCLUDED.margin_units,
                       updated_at = EXCLUDED.updated_at
                """, productLine.name(), userId, symbol, asset,
                MarginMode.defaultIfNull(marginMode).name(),
                PositionSide.defaultIfNull(positionSide).name(),
                amountUnits, Timestamp.from(now));
    }

    public int subtract(ProductLine productLine,
                        long userId,
                        String symbol,
                        String asset,
                        MarginMode marginMode,
                        PositionSide positionSide,
                        long amountUnits,
                        Instant now) {
        return jdbcTemplate.update("""
                UPDATE account_position_margins
                   SET margin_units = margin_units - ?,
                       updated_at = ?
                 WHERE product_line = ?
                   AND user_id = ?
                   AND symbol = ?
                   AND asset = ?
                   AND margin_mode = ?
                   AND position_side = ?
                   AND margin_units >= ?
                """, amountUnits, Timestamp.from(now), productLine.name(), userId, symbol, asset,
                MarginMode.defaultIfNull(marginMode).name(),
                PositionSide.defaultIfNull(positionSide).name(), amountUnits);
    }

    public int deleteZero(ProductLine productLine,
                          long userId,
                          String symbol,
                          String asset,
                          MarginMode marginMode,
                          PositionSide positionSide) {
        return jdbcTemplate.update("""
                DELETE FROM account_position_margins
                 WHERE product_line = ?
                   AND user_id = ?
                   AND symbol = ?
                   AND asset = ?
                   AND margin_mode = ?
                   AND position_side = ?
                   AND margin_units = 0
                """, productLine.name(), userId, symbol, asset,
                MarginMode.defaultIfNull(marginMode).name(),
                PositionSide.defaultIfNull(positionSide).name());
    }

    public List<PositionMarginRow> lockByPosition(ProductLine productLine,
                                                  long userId,
                                                  String symbol,
                                                  MarginMode marginMode,
                                                  PositionSide positionSide) {
        return jdbcTemplate.query("""
                SELECT symbol, asset, margin_mode, position_side, margin_units, updated_at
                  FROM account_position_margins
                 WHERE product_line = ?
                   AND user_id = ?
                   AND symbol = ?
                   AND margin_mode = ?
                   AND position_side = ?
                   AND margin_units > 0
                 ORDER BY asset ASC, position_side ASC
                 FOR UPDATE
                """, (rs, rowNum) -> toLockedRow(rs), productLine.name(), userId, symbol,
                MarginMode.defaultIfNull(marginMode).name(),
                PositionSide.defaultIfNull(positionSide).name());
    }

    public List<PositionMarginRow> lockByAsset(ProductLine productLine,
                                               long userId,
                                               String asset,
                                               String symbol,
                                               MarginMode marginMode) {
        if (MarginMode.defaultIfNull(marginMode) == MarginMode.ISOLATED) {
            return jdbcTemplate.query("""
                    SELECT symbol, asset, margin_mode, position_side, margin_units, updated_at
                      FROM account_position_margins
                     WHERE product_line = ?
                       AND user_id = ?
                       AND asset = ?
                       AND symbol = ?
                       AND margin_mode = 'ISOLATED'
                       AND margin_units > 0
                     ORDER BY updated_at ASC, symbol ASC, margin_mode ASC, position_side ASC
                     FOR UPDATE
                    """, (rs, rowNum) -> toLockedRow(rs), productLine.name(), userId, asset, symbol);
        }
        return jdbcTemplate.query("""
                SELECT symbol, asset, margin_mode, position_side, margin_units, updated_at
                  FROM account_position_margins
                 WHERE product_line = ?
                   AND user_id = ?
                   AND asset = ?
                   AND margin_mode = 'CROSS'
                   AND margin_units > 0
                 ORDER BY updated_at ASC, symbol ASC, margin_mode ASC, position_side ASC
                 FOR UPDATE
                """, (rs, rowNum) -> toLockedRow(rs), productLine.name(), userId, asset);
    }

    public List<PositionMarginRow> lockForFunding(ProductLine productLine,
                                                  long userId,
                                                  String symbol,
                                                  String asset,
                                                  MarginMode marginMode,
                                                  PositionSide positionSide) {
        MarginMode normalizedMarginMode = MarginMode.defaultIfNull(marginMode);
        PositionSide normalizedPositionSide = PositionSide.defaultIfNull(positionSide);
        if (normalizedMarginMode == MarginMode.ISOLATED) {
            return jdbcTemplate.query("""
                    SELECT symbol, asset, margin_mode, position_side, margin_units, updated_at
                      FROM account_position_margins
                     WHERE product_line = ?
                       AND user_id = ?
                       AND symbol = ?
                       AND asset = ?
                       AND margin_mode = ?
                       AND position_side = ?
                       AND margin_units > 0
                     ORDER BY updated_at ASC, symbol ASC, margin_mode ASC, position_side ASC
                     FOR UPDATE
                    """, (rs, rowNum) -> toLockedRow(rs), productLine.name(), userId, symbol, asset,
                    normalizedMarginMode.name(), normalizedPositionSide.name());
        }
        return jdbcTemplate.query("""
                SELECT symbol, asset, margin_mode, position_side, margin_units, updated_at
                  FROM account_position_margins
                 WHERE product_line = ?
                   AND user_id = ?
                   AND asset = ?
                   AND margin_mode = ?
                   AND position_side = ?
                   AND margin_units > 0
                 ORDER BY updated_at ASC, symbol ASC, margin_mode ASC, position_side ASC
                 FOR UPDATE
                """, (rs, rowNum) -> toLockedRow(rs), productLine.name(), userId, asset,
                normalizedMarginMode.name(), normalizedPositionSide.name());
    }

    private static PositionMarginRow toRow(java.sql.ResultSet resultSet) throws java.sql.SQLException {
        return new PositionMarginRow(
                resultSet.getString("symbol"),
                resultSet.getString("asset"),
                MarginMode.fromNullableDbValue(resultSet.getString("margin_mode")),
                PositionSide.fromNullableDbValue(resultSet.getString("position_side")),
                resultSet.getLong("margin_units"),
                resultSet.getTimestamp("updated_at").toInstant());
    }

    private static PositionMarginRow toLockedRow(java.sql.ResultSet resultSet) throws java.sql.SQLException {
        return new PositionMarginRow(
                resultSet.getString("symbol"),
                resultSet.getString("asset"),
                MarginMode.fromNullableDbValue(resultSet.getString("margin_mode")),
                PositionSide.fromNullableDbValue(resultSet.getString("position_side")),
                resultSet.getLong("margin_units"),
                Instant.EPOCH);
    }

    public record PositionMarginRow(
            String symbol,
            String asset,
            MarginMode marginMode,
            PositionSide positionSide,
            long marginUnits,
            Instant updatedAt) {
    }
}
