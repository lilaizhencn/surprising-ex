package com.surprising.account.provider.service;

import com.surprising.account.api.model.PositionCacheEvent;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.PositionSide;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 为 Redis 读模型提供 PostgreSQL 最终状态快照、启动扫描和核对扫描。 */
@Service
public class PositionCacheProjectionService {

    private final JdbcTemplate jdbcTemplate;

    public PositionCacheProjectionService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<PositionCacheEvent> page(ProductLine productLine, Cursor after, int limit) {
        Cursor cursor = after == null ? Cursor.start() : after;
        return jdbcTemplate.query("""
                SELECT p.product_line,
                       p.user_id,
                       p.symbol,
                       p.instrument_version,
                       p.margin_mode,
                       p.position_side,
                       p.signed_quantity_steps,
                       p.entry_price_ticks,
                       p.entry_value_ticks,
                       p.realized_pnl_units,
                       COALESCE(m.asset, i.settle_asset, '') AS margin_asset,
                       COALESCE(m.margin_units, 0) AS margin_units,
                       p.updated_at AS position_updated_at,
                       COALESCE(m.updated_at, p.updated_at) AS margin_updated_at,
                       GREATEST(p.cache_revision, COALESCE(m.cache_revision, 0)) AS revision
                  FROM account_positions p
                  LEFT JOIN instruments i
                    ON i.symbol = p.symbol
                   AND i.version = p.instrument_version
                  LEFT JOIN LATERAL (
                      SELECT MIN(pm.asset) AS asset,
                             COALESCE(SUM(pm.margin_units), 0)::BIGINT AS margin_units,
                             MAX(pm.updated_at) AS updated_at,
                             MAX(pm.cache_revision) AS cache_revision
                        FROM account_position_margins pm
                       WHERE pm.product_line = p.product_line
                         AND pm.user_id = p.user_id
                         AND pm.symbol = p.symbol
                         AND pm.margin_mode = p.margin_mode
                         AND pm.position_side = p.position_side
                  ) m ON TRUE
                 WHERE p.product_line = ?
                   AND (p.user_id, p.symbol, p.margin_mode, p.position_side) > (?, ?, ?, ?)
                 ORDER BY p.user_id, p.symbol, p.margin_mode, p.position_side
                 LIMIT ?
                """, (rs, rowNum) -> {
            long revision = rs.getLong("revision");
            Number version = (Number) rs.getObject("instrument_version");
            return new PositionCacheEvent(
                    revision,
                    ProductLine.valueOf(rs.getString("product_line")),
                    rs.getLong("user_id"),
                    rs.getString("symbol"),
                    version == null ? null : version.longValue(),
                    MarginMode.fromNullableDbValue(rs.getString("margin_mode")),
                    PositionSide.fromNullableDbValue(rs.getString("position_side")),
                    rs.getLong("signed_quantity_steps"),
                    rs.getLong("entry_price_ticks"),
                    rs.getLong("entry_value_ticks"),
                    rs.getLong("realized_pnl_units"),
                    rs.getString("margin_asset"),
                    rs.getLong("margin_units"),
                    rs.getTimestamp("position_updated_at").toInstant(),
                    rs.getTimestamp("margin_updated_at").toInstant(),
                    revision);
        }, productLine.name(), cursor.userId(), cursor.symbol(), cursor.marginMode(), cursor.positionSide(),
                Math.max(1, limit));
    }

    /**
     * 单次数据库往返读取一个持仓的最终形态。该方法必须与持仓变更处于同一事务，
     * 返回的快照只能在事务提交后写入 Redis。
     */
    public PositionCacheEvent captureFinalSnapshot(ProductLine productLine,
                                                   long userId,
                                                   String symbol,
                                                   MarginMode marginMode,
                                                   PositionSide positionSide) {
        MarginMode normalizedMarginMode = MarginMode.defaultIfNull(marginMode);
        PositionSide normalizedPositionSide = PositionSide.defaultIfNull(positionSide);
        List<PositionCacheEvent> snapshots = jdbcTemplate.query("""
                SELECT GREATEST(p.cache_revision, COALESCE(m.cache_revision, 0)) AS revision,
                       p.product_line,
                       p.user_id,
                       p.symbol,
                       p.instrument_version,
                       p.margin_mode,
                       p.position_side,
                       p.signed_quantity_steps,
                       p.entry_price_ticks,
                       p.entry_value_ticks,
                       p.realized_pnl_units,
                       COALESCE(m.asset, i.settle_asset, '') AS margin_asset,
                       COALESCE(m.margin_units, 0) AS margin_units,
                       p.updated_at AS position_updated_at,
                       COALESCE(m.updated_at, p.updated_at) AS margin_updated_at
                  FROM account_positions p
                  LEFT JOIN instruments i
                    ON i.symbol = p.symbol
                   AND i.version = p.instrument_version
                  LEFT JOIN LATERAL (
                      SELECT MIN(pm.asset) AS asset,
                             COALESCE(SUM(pm.margin_units), 0)::BIGINT AS margin_units,
                             MAX(pm.updated_at) AS updated_at,
                             MAX(pm.cache_revision) AS cache_revision
                        FROM account_position_margins pm
                       WHERE pm.product_line = p.product_line
                         AND pm.user_id = p.user_id
                         AND pm.symbol = p.symbol
                         AND pm.margin_mode = p.margin_mode
                         AND pm.position_side = p.position_side
                  ) m ON TRUE
                 WHERE p.product_line = ?
                   AND p.user_id = ?
                   AND p.symbol = ?
                   AND p.margin_mode = ?
                   AND p.position_side = ?
                """, (rs, rowNum) -> toEvent(rs), productLine.name(), userId, symbol,
                normalizedMarginMode.name(), normalizedPositionSide.name());
        if (snapshots.size() != 1) {
            throw new IllegalStateException("position cache projection source is missing or ambiguous: line="
                    + productLine + " user=" + userId + " symbol=" + symbol + " mode="
                    + normalizedMarginMode + " side=" + normalizedPositionSide);
        }
        return snapshots.getFirst();
    }

    public Cursor cursor(PositionCacheEvent event) {
        return new Cursor(
                event.userId(), event.symbol(), event.marginMode().name(), event.positionSide().name());
    }

    public record Cursor(long userId, String symbol, String marginMode, String positionSide) {
        public static Cursor start() {
            return new Cursor(0L, "", "", "");
        }
    }

    private PositionCacheEvent toEvent(java.sql.ResultSet rs) throws java.sql.SQLException {
        long revision = rs.getLong("revision");
        Number version = (Number) rs.getObject("instrument_version");
        return new PositionCacheEvent(
                revision,
                ProductLine.valueOf(rs.getString("product_line")),
                rs.getLong("user_id"),
                rs.getString("symbol"),
                version == null ? null : version.longValue(),
                MarginMode.fromNullableDbValue(rs.getString("margin_mode")),
                PositionSide.fromNullableDbValue(rs.getString("position_side")),
                rs.getLong("signed_quantity_steps"),
                rs.getLong("entry_price_ticks"),
                rs.getLong("entry_value_ticks"),
                rs.getLong("realized_pnl_units"),
                rs.getString("margin_asset"),
                rs.getLong("margin_units"),
                rs.getTimestamp("position_updated_at").toInstant(),
                rs.getTimestamp("margin_updated_at").toInstant(),
                revision);
    }
}
