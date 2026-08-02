package com.surprising.account.provider.repository;

import com.surprising.account.api.model.PerpetualAccountStateUpdatedEvent;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.PositionSide;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 账户持仓保证金单表仓储。
 *
 * <p>只用于启动恢复和异步投影。保证金增减、资金费和强平全部由用户分区 reducer 处理，
 * 不允许通过数据库行锁重新形成第二套事实状态。</p>
 */
@Repository
public class PositionMarginRepository {

    private final JdbcTemplate jdbcTemplate;

    public PositionMarginRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 启动恢复读取用户的逐仓保证金快照。 */
    public List<PositionMarginRow> findByUser(ProductLine productLine, long userId) {
        if (productLine == null || userId <= 0L) {
            throw new IllegalArgumentException("产品线和用户编号不能为空");
        }
        return jdbcTemplate.query("""
                SELECT symbol, asset, margin_mode, position_side, margin_units, updated_at
                  FROM account_position_margins
                 WHERE product_line = ?
                   AND user_id = ?
                   AND margin_units > 0
                 ORDER BY symbol ASC, asset ASC, margin_mode ASC, position_side ASC
                """, (rs, rowNum) -> new PositionMarginRow(
                rs.getString("symbol"), rs.getString("asset"),
                MarginMode.fromNullableDbValue(rs.getString("margin_mode")),
                PositionSide.fromNullableDbValue(rs.getString("position_side")),
                rs.getLong("margin_units"),
                rs.getTimestamp("updated_at") == null ? Instant.EPOCH : rs.getTimestamp("updated_at").toInstant()),
                productLine.name(), userId);
    }

    /** 用账户 JVM 快照替换用户逐仓保证金投影。 */
    public void replaceProjection(ProductLine productLine,
                                  long userId,
                                  List<PerpetualAccountStateUpdatedEvent.PositionMargin> margins,
                                  Instant projectedAt) {
        if (productLine == null || userId <= 0L || projectedAt == null) {
            throw new IllegalArgumentException("保证金投影参数不能为空");
        }
        jdbcTemplate.update("""
                DELETE FROM account_position_margins
                 WHERE product_line = ?
                   AND user_id = ?
                """, productLine.name(), userId);
        if (margins == null) {
            return;
        }
        for (PerpetualAccountStateUpdatedEvent.PositionMargin margin : margins) {
            if (margin == null || margin.marginUnits() <= 0L) {
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

    public record PositionMarginRow(String symbol,
                                    String asset,
                                    MarginMode marginMode,
                                    PositionSide positionSide,
                                    long marginUnits,
                                    Instant updatedAt) {
    }
}
