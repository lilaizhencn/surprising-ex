package com.surprising.account.provider.repository;

import com.surprising.account.api.model.PositionResponse;
import com.surprising.account.provider.model.PositionSettlementState;
import com.surprising.account.provider.model.PositionState;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.PositionSide;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PositionRepository {

    private final JdbcTemplate jdbcTemplate;

    public PositionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<PositionResponse> find(long userId,
                                           String symbol,
                                           MarginMode marginMode,
                                           PositionSide positionSide) {
        return jdbcTemplate.query("""
                SELECT user_id, symbol, margin_mode, position_side, instrument_version, signed_quantity_steps,
                       entry_price_ticks, realized_pnl_units, updated_at
                  FROM account_positions
                 WHERE user_id = ?
                   AND symbol = ?
                   AND margin_mode = ?
                   AND position_side = ?
                """, (rs, rowNum) -> toPositionResponse(rs), userId, symbol,
                MarginMode.defaultIfNull(marginMode).name(),
                PositionSide.defaultIfNull(positionSide).name())
                .stream().findFirst();
    }

    public Optional<PositionResponse> find(ProductLine productLine,
                                           long userId,
                                           String symbol,
                                           MarginMode marginMode,
                                           PositionSide positionSide) {
        return jdbcTemplate.query("""
                SELECT user_id, symbol, margin_mode, position_side, instrument_version,
                       signed_quantity_steps, entry_price_ticks, realized_pnl_units, updated_at
                  FROM account_positions
                 WHERE product_line = ?
                   AND user_id = ?
                   AND symbol = ?
                   AND margin_mode = ?
                   AND position_side = ?
                """, (rs, rowNum) -> toPositionResponse(rs), productLine.name(), userId, symbol,
                MarginMode.defaultIfNull(marginMode).name(),
                PositionSide.defaultIfNull(positionSide).name())
                .stream().findFirst();
    }

    public List<PositionResponse> findOpenByUser(long userId, PositionSide positionSide) {
        String normalizedPositionSide = positionSide == null
                ? null
                : PositionSide.defaultIfNull(positionSide).name();
        return jdbcTemplate.query("""
                SELECT user_id, symbol, margin_mode, position_side, instrument_version, signed_quantity_steps,
                       entry_price_ticks, realized_pnl_units, updated_at
                  FROM account_positions
                 WHERE user_id = ?
                   AND (CAST(? AS text) IS NULL OR position_side = ?)
                   AND signed_quantity_steps <> 0
                 ORDER BY symbol ASC, margin_mode ASC, position_side ASC
                """, (rs, rowNum) -> toPositionResponse(rs), userId,
                normalizedPositionSide, normalizedPositionSide);
    }

    public List<PositionResponse> findOpenByUser(ProductLine productLine,
                                                 long userId,
                                                 PositionSide positionSide) {
        String normalizedPositionSide = positionSide == null
                ? null
                : PositionSide.defaultIfNull(positionSide).name();
        return jdbcTemplate.query("""
                SELECT user_id, symbol, margin_mode, position_side, instrument_version,
                       signed_quantity_steps, entry_price_ticks, realized_pnl_units, updated_at
                  FROM account_positions
                 WHERE product_line = ?
                   AND user_id = ?
                   AND (CAST(? AS text) IS NULL OR position_side = ?)
                   AND signed_quantity_steps <> 0
                 ORDER BY symbol ASC, margin_mode ASC, position_side ASC
                """, (rs, rowNum) -> toPositionResponse(rs), productLine.name(), userId,
                normalizedPositionSide, normalizedPositionSide);
    }

    /** 读取用户全部永续持仓（包括零仓位墓碑），用于构造可重放的账户状态快照。 */
    public List<PositionSnapshotRow> findSnapshotByUser(ProductLine productLine, long userId) {
        return jdbcTemplate.query("""
                SELECT symbol, margin_mode, position_side, instrument_version, signed_quantity_steps,
                       entry_price_ticks, entry_value_ticks, realized_pnl_units, updated_at
                  FROM account_positions
                 WHERE product_line = ?
                   AND user_id = ?
                 ORDER BY symbol ASC, margin_mode ASC, position_side ASC
                """, (rs, rowNum) -> new PositionSnapshotRow(
                rs.getString("symbol"),
                MarginMode.fromNullableDbValue(rs.getString("margin_mode")),
                PositionSide.fromNullableDbValue(rs.getString("position_side")),
                longOrZero(rs, "instrument_version"),
                rs.getLong("signed_quantity_steps"),
                rs.getLong("entry_price_ticks"),
                rs.getLong("entry_value_ticks"),
                rs.getLong("realized_pnl_units"),
                rs.getTimestamp("updated_at").toInstant()), productLine.name(), userId);
    }

    public boolean existsOpen(ProductLine productLine, long userId) {
        Boolean exists = jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                      FROM account_positions
                     WHERE product_line = ?
                       AND user_id = ?
                       AND signed_quantity_steps <> 0
                )
                """, Boolean.class, productLine.name(), userId);
        return Boolean.TRUE.equals(exists);
    }

    public List<PositionResponse> lockOpenForSettlement(String symbol, long instrumentVersion) {
        return jdbcTemplate.query("""
                SELECT user_id, symbol, margin_mode, position_side, instrument_version, signed_quantity_steps,
                       entry_price_ticks, realized_pnl_units, updated_at
                  FROM account_positions
                 WHERE symbol = ?
                   AND instrument_version = ?
                   AND signed_quantity_steps <> 0
                 ORDER BY user_id ASC, margin_mode ASC, position_side ASC
                 FOR UPDATE
                """, (rs, rowNum) -> toPositionResponse(rs), symbol, instrumentVersion);
    }

    public List<PositionSettlementState> lockOpenStatesForSettlement(String symbol, long instrumentVersion) {
        return jdbcTemplate.query("""
                SELECT user_id, symbol, margin_mode, position_side, instrument_version, signed_quantity_steps,
                       entry_price_ticks, entry_value_ticks, realized_pnl_units, updated_at
                  FROM account_positions
                 WHERE symbol = ?
                   AND instrument_version = ?
                   AND signed_quantity_steps <> 0
                 ORDER BY user_id ASC, margin_mode ASC, position_side ASC
                 FOR UPDATE
                """, (rs, rowNum) -> toPositionSettlementState(rs), symbol, instrumentVersion);
    }

    public List<PositionResponse> lockOpenForSettlement(ProductLine productLine, String symbol) {
        return jdbcTemplate.query("""
                SELECT user_id, symbol, margin_mode, position_side, instrument_version, signed_quantity_steps,
                       entry_price_ticks, realized_pnl_units, updated_at
                  FROM account_positions
                 WHERE product_line = ?
                   AND symbol = ?
                   AND signed_quantity_steps <> 0
                 ORDER BY user_id ASC, margin_mode ASC, position_side ASC, instrument_version ASC
                 FOR UPDATE
                """, (rs, rowNum) -> toPositionResponse(rs), productLine.name(), symbol);
    }

    public List<PositionSettlementState> lockOpenStatesForSettlement(ProductLine productLine, String symbol) {
        return jdbcTemplate.query("""
                SELECT user_id, symbol, margin_mode, position_side, instrument_version, signed_quantity_steps,
                       entry_price_ticks, entry_value_ticks, realized_pnl_units, updated_at
                  FROM account_positions
                 WHERE product_line = ?
                   AND symbol = ?
                   AND signed_quantity_steps <> 0
                 ORDER BY user_id ASC, margin_mode ASC, position_side ASC, instrument_version ASC
                 FOR UPDATE
                """, (rs, rowNum) -> toPositionSettlementState(rs), productLine.name(), symbol);
    }

    public LockedPosition lockOrCreate(ProductLine productLine,
                                       long userId,
                                       String symbol,
                                       MarginMode marginMode,
                                       PositionSide positionSide,
                                       Instant now) {
        MarginMode normalizedMarginMode = MarginMode.defaultIfNull(marginMode);
        PositionSide normalizedPositionSide = PositionSide.defaultIfNull(positionSide);
        List<PositionState> current = jdbcTemplate.query("""
                SELECT instrument_version, signed_quantity_steps, entry_price_ticks, entry_value_ticks,
                       realized_pnl_units
                  FROM account_positions
                 WHERE product_line = ?
                   AND user_id = ?
                   AND symbol = ?
                   AND margin_mode = ?
                   AND position_side = ?
                 FOR UPDATE
                """, (rs, rowNum) -> toPositionState(rs), productLine.name(), userId, symbol,
                normalizedMarginMode.name(), normalizedPositionSide.name());
        if (!current.isEmpty()) {
            return new LockedPosition(current.getFirst(), false);
        }
        List<PositionState> inserted = jdbcTemplate.query("""
                INSERT INTO account_positions (
                    product_line, user_id, symbol, margin_mode, position_side, instrument_version, signed_quantity_steps,
                    entry_price_ticks, entry_value_ticks, realized_pnl_units, updated_at
                ) VALUES (?, ?, ?, ?, ?, NULL, 0, 0, 0, 0, ?)
                ON CONFLICT (product_line, user_id, symbol, margin_mode, position_side) DO NOTHING
                RETURNING instrument_version, signed_quantity_steps, entry_price_ticks, entry_value_ticks,
                          realized_pnl_units
                """, (rs, rowNum) -> toPositionState(rs), productLine.name(), userId, symbol,
                normalizedMarginMode.name(), normalizedPositionSide.name(), Timestamp.from(now));
        if (!inserted.isEmpty()) {
            return new LockedPosition(inserted.getFirst(), true);
        }
        PositionState state = jdbcTemplate.queryForObject("""
                SELECT instrument_version, signed_quantity_steps, entry_price_ticks, entry_value_ticks,
                       realized_pnl_units
                  FROM account_positions
                 WHERE product_line = ?
                   AND user_id = ?
                   AND symbol = ?
                   AND margin_mode = ?
                   AND position_side = ?
                 FOR UPDATE
                """, (rs, rowNum) -> toPositionState(rs), productLine.name(), userId, symbol,
                normalizedMarginMode.name(), normalizedPositionSide.name());
        return new LockedPosition(state, false);
    }

    public Optional<PositionState> lock(ProductLine productLine,
                                        long userId,
                                        String symbol,
                                        MarginMode marginMode,
                                        PositionSide positionSide) {
        return jdbcTemplate.query("""
                SELECT instrument_version, signed_quantity_steps, entry_price_ticks, entry_value_ticks,
                       realized_pnl_units
                  FROM account_positions
                 WHERE product_line = ?
                   AND user_id = ?
                   AND symbol = ?
                   AND margin_mode = ?
                   AND position_side = ?
                 FOR UPDATE
                """, (rs, rowNum) -> toPositionState(rs), productLine.name(), userId, symbol,
                MarginMode.defaultIfNull(marginMode).name(),
                PositionSide.defaultIfNull(positionSide).name())
                .stream().findFirst();
    }

    public long lockCurrentQuantity(ProductLine productLine,
                                    long userId,
                                    String symbol,
                                    MarginMode marginMode,
                                    PositionSide positionSide) {
        return jdbcTemplate.query("""
                SELECT signed_quantity_steps
                  FROM account_positions
                 WHERE product_line = ?
                   AND user_id = ?
                   AND symbol = ?
                   AND margin_mode = ?
                   AND position_side = ?
                 FOR UPDATE
                """, (rs, rowNum) -> rs.getLong("signed_quantity_steps"), productLine.name(), userId, symbol,
                MarginMode.defaultIfNull(marginMode).name(),
                PositionSide.defaultIfNull(positionSide).name())
                .stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("position not found before update"));
    }

    public Optional<LockedPositionTarget> lockOpenIsolated(long userId,
                                                           String symbol,
                                                           PositionSide positionSide) {
        return jdbcTemplate.query("""
                SELECT instrument_version, signed_quantity_steps
                  FROM account_positions
                 WHERE user_id = ?
                   AND symbol = ?
                   AND margin_mode = 'ISOLATED'
                   AND position_side = ?
                   AND signed_quantity_steps <> 0
                 FOR UPDATE
                """, (rs, rowNum) -> new LockedPositionTarget(
                        rs.getLong("instrument_version"),
                        rs.getLong("signed_quantity_steps")), userId, symbol,
                PositionSide.defaultIfNull(positionSide).name())
                .stream().findFirst();
    }

    public Optional<LockedPositionTarget> lockOpenIsolated(ProductLine productLine,
                                                           long userId,
                                                           String symbol,
                                                           PositionSide positionSide) {
        return jdbcTemplate.query("""
                SELECT instrument_version, signed_quantity_steps
                  FROM account_positions
                 WHERE product_line = ?
                   AND user_id = ?
                   AND symbol = ?
                   AND margin_mode = 'ISOLATED'
                   AND position_side = ?
                   AND signed_quantity_steps <> 0
                 FOR UPDATE
                """, (rs, rowNum) -> new LockedPositionTarget(
                        rs.getLong("instrument_version"),
                        rs.getLong("signed_quantity_steps")), productLine.name(), userId, symbol,
                PositionSide.defaultIfNull(positionSide).name())
                .stream().findFirst();
    }

    public int update(ProductLine productLine,
                      long userId,
                      String symbol,
                      MarginMode marginMode,
                      PositionSide positionSide,
                      PositionState state,
                      Instant now) {
        return jdbcTemplate.update("""
                UPDATE account_positions
                   SET signed_quantity_steps = ?,
                       instrument_version = ?,
                       entry_price_ticks = ?,
                       entry_value_ticks = ?,
                       realized_pnl_units = ?,
                       updated_at = ?
                 WHERE product_line = ?
                   AND user_id = ?
                   AND symbol = ?
                   AND margin_mode = ?
                   AND position_side = ?
                """, state.signedQuantitySteps(), nullableVersion(state.instrumentVersion()),
                state.entryPriceTicks(), state.entryValueTicks(), state.realizedPnlUnits(),
                Timestamp.from(now), productLine.name(), userId, symbol,
                MarginMode.defaultIfNull(marginMode).name(),
                PositionSide.defaultIfNull(positionSide).name());
    }

    private static PositionResponse toPositionResponse(ResultSet resultSet) throws SQLException {
        return new PositionResponse(
                resultSet.getLong("user_id"),
                resultSet.getString("symbol"),
                longOrZero(resultSet, "instrument_version"),
                MarginMode.fromNullableDbValue(resultSet.getString("margin_mode")),
                PositionSide.fromNullableDbValue(resultSet.getString("position_side")),
                resultSet.getLong("signed_quantity_steps"),
                resultSet.getLong("entry_price_ticks"),
                resultSet.getLong("realized_pnl_units"),
                resultSet.getTimestamp("updated_at").toInstant());
    }

    private static PositionState toPositionState(ResultSet resultSet) throws SQLException {
        return new PositionState(
                resultSet.getLong("signed_quantity_steps"),
                longOrZero(resultSet, "instrument_version"),
                resultSet.getLong("entry_price_ticks"),
                resultSet.getLong("entry_value_ticks"),
                resultSet.getLong("realized_pnl_units"));
    }

    private static PositionSettlementState toPositionSettlementState(ResultSet resultSet) throws SQLException {
        return new PositionSettlementState(
                resultSet.getLong("user_id"),
                resultSet.getString("symbol"),
                MarginMode.fromNullableDbValue(resultSet.getString("margin_mode")),
                PositionSide.fromNullableDbValue(resultSet.getString("position_side")),
                toPositionState(resultSet),
                resultSet.getTimestamp("updated_at").toInstant());
    }

    private static long longOrZero(ResultSet resultSet, String column) throws SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? 0L : value;
    }

    private static Long nullableVersion(long version) {
        return version == 0L ? null : version;
    }

    public record LockedPosition(PositionState state, boolean created) {
    }

    public record LockedPositionTarget(long instrumentVersion, long signedQuantitySteps) {
    }

    public record PositionSnapshotRow(String symbol,
                                      MarginMode marginMode,
                                      PositionSide positionSide,
                                      long instrumentVersion,
                                      long signedQuantitySteps,
                                      long entryPriceTicks,
                                      long entryValueTicks,
                                      long realizedPnlUnits,
                                      Instant updatedAt) {
    }
}
