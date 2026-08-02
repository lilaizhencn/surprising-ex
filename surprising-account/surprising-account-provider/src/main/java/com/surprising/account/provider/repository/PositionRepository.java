package com.surprising.account.provider.repository;

import com.surprising.account.api.model.PerpetualAccountStateUpdatedEvent;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.PositionSide;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 账户持仓单表仓储。
 *
 * <p>这里只保留启动恢复和异步投影所需的读写。持仓查询、成交、风控和强平不得调用本类，
 * 必须读取账户用户分区的 JVM 快照。</p>
 */
@Repository
public class PositionRepository {

    private final JdbcTemplate jdbcTemplate;

    public PositionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 启动恢复读取用户全部持仓，包括零仓位墓碑。 */
    public List<PositionSnapshotRow> findSnapshotByUser(ProductLine productLine, long userId) {
        if (productLine == null || userId <= 0L) {
            throw new IllegalArgumentException("产品线和用户编号不能为空");
        }
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
                timestamp(rs.getTimestamp("updated_at"))), productLine.name(), userId);
    }

    /** 用单用户完整 JVM 快照替换数据库投影；零仓位表示删除数据库行。 */
    public void replaceProjection(ProductLine productLine,
                                  long userId,
                                  List<PerpetualAccountStateUpdatedEvent.Position> positions,
                                  Instant projectedAt) {
        if (productLine == null || userId <= 0L || projectedAt == null) {
            throw new IllegalArgumentException("持仓投影参数不能为空");
        }
        jdbcTemplate.update("""
                DELETE FROM account_positions
                 WHERE product_line = ?
                   AND user_id = ?
                """, productLine.name(), userId);
        if (positions == null) {
            return;
        }
        for (PerpetualAccountStateUpdatedEvent.Position position : positions) {
            if (position == null || position.signedQuantitySteps() == 0L) {
                continue;
            }
            if (position.instrumentVersion() <= 0L) {
                throw new IllegalArgumentException("持仓投影缺少合约版本");
            }
            jdbcTemplate.update("""
                    INSERT INTO account_positions (
                        product_line, user_id, symbol, margin_mode, position_side,
                        instrument_version, signed_quantity_steps, entry_price_ticks,
                        entry_value_ticks, realized_pnl_units, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, productLine.name(), userId, position.symbol(), position.marginMode().name(),
                    position.positionSide().name(), position.instrumentVersion(), position.signedQuantitySteps(),
                    position.entryPriceTicks(), position.entryValueTicks(), position.realizedPnlUnits(),
                    Timestamp.from(position.updatedAt() == null ? projectedAt : position.updatedAt()));
        }
    }

    private static long longOrZero(ResultSet resultSet, String column) throws SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? 0L : value;
    }

    private static Instant timestamp(Timestamp value) {
        return value == null ? Instant.EPOCH : value.toInstant();
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
