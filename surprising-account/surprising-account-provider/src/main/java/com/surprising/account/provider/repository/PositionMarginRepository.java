package com.surprising.account.provider.repository;

import com.surprising.account.api.model.PerpetualAccountStateUpdatedEvent;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.PositionSide;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.LinkedHashMap;
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

    /** 汇总一个用户永续隔离仓位的保证金，查询只触及 account_position_margins 单表。 */
    public Map<String, Long> sumOpenIsolatedByAsset(ProductLine productLine, long userId) {
        return jdbcTemplate.query("""
                SELECT asset, COALESCE(SUM(margin_units), 0) AS margin_units
                  FROM account_position_margins
                 WHERE product_line = ?
                   AND user_id = ?
                   AND margin_mode = 'ISOLATED'
                   AND margin_units > 0
                 GROUP BY asset
                """, (rs, rowNum) -> Map.entry(rs.getString("asset"), rs.getLong("margin_units")),
                productLine.name(), userId)
                .stream()
                .collect(java.util.stream.Collectors.toMap(
                        Map.Entry::getKey, Map.Entry::getValue, Math::addExact, LinkedHashMap::new));
    }

    /** 读取用户永续逐仓保证金完整快照，查询只触及本仓储负责的单表。 */
    public List<PositionMarginRow> findByUser(ProductLine productLine, long userId) {
        return jdbcTemplate.query("""
                SELECT symbol, asset, margin_mode, position_side, margin_units, updated_at
                  FROM account_position_margins
                 WHERE product_line = ?
                   AND user_id = ?
                   AND margin_units > 0
                 ORDER BY symbol ASC, asset ASC, margin_mode ASC, position_side ASC
                """, (rs, rowNum) -> toRow(rs), productLine.name(), userId);
    }

    /** 只由异步账户状态投影替换用户逐仓保证金快照。 */
    public void replaceProjection(ProductLine productLine,
                                  long userId,
                                  List<PerpetualAccountStateUpdatedEvent.PositionMargin> margins,
                                  Instant projectedAt) {
        jdbcTemplate.update("""
                DELETE FROM account_position_margins
                 WHERE product_line = ?
                   AND user_id = ?
                """, productLine.name(), userId);
        if (margins == null) {
            return;
        }
        for (PerpetualAccountStateUpdatedEvent.PositionMargin margin : margins) {
            if (margin.marginUnits() <= 0L) {
                continue;
            }
            jdbcTemplate.update("""
                    INSERT INTO account_position_margins (
                        product_line, user_id, symbol, asset, margin_mode,
                        position_side, margin_units, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, productLine.name(), userId, margin.symbol(), margin.asset(), margin.marginMode().name(),
                    margin.positionSide().name(), margin.marginUnits(), Timestamp.from(projectedAt));
        }
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
