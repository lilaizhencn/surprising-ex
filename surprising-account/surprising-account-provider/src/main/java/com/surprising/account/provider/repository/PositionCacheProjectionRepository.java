package com.surprising.account.provider.repository;

import com.surprising.account.api.model.PositionCacheEvent;
import com.surprising.account.provider.config.AccountProperties;
import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.PositionSide;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 读取 Redis 持仓投影所需的权威最终状态。
 *
 * <p>不可拆原因：缓存 revision、持仓和逐仓保证金在数据库快照中组合，合约结算资产由本地不可变快照补齐，
 * 否则事务提交后的 Redis 投影可能混用不同版本的持仓与保证金。该查询只服务在线缓存重建和
 * 最终状态同步，不提供后台时间线、资金对账或运营报表。</p>
 */
@Repository
public class PositionCacheProjectionRepository {

    private final JdbcTemplate jdbcTemplate;
    private final AccountProperties properties;
    private final InstrumentSnapshotCache snapshotCache;

    public PositionCacheProjectionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = new AccountProperties();
        this.snapshotCache = null;
    }

    @org.springframework.beans.factory.annotation.Autowired
    public PositionCacheProjectionRepository(JdbcTemplate jdbcTemplate,
                                             AccountProperties properties,
                                             InstrumentSnapshotCache snapshotCache) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
        this.snapshotCache = snapshotCache;
    }

    /** 仅供启动重建和低频核对扫描使用，在线读取不再经过此仓储。 */
    public List<PositionCacheEvent> rebuildPage(ProductLine productLine,
                                         long afterUserId,
                                         String afterSymbol,
                                         String afterMarginMode,
                                         String afterPositionSide,
                                         int limit) {
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
                       COALESCE(m.asset, '') AS margin_asset,
                       COALESCE(m.margin_units, 0) AS margin_units,
                       p.updated_at AS position_updated_at,
                       COALESCE(m.updated_at, p.updated_at) AS margin_updated_at,
                       GREATEST(p.cache_revision, COALESCE(m.cache_revision, 0)) AS revision
                  FROM account_positions p
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
                """, (rs, rowNum) -> toEvent(rs), productLine.name(), afterUserId, afterSymbol,
                afterMarginMode, afterPositionSide, Math.max(1, limit));
    }

    /** 保留旧方法名，避免外部测试或迁移代码误把它当成在线查询入口。 */
    public List<PositionCacheEvent> page(ProductLine productLine,
                                         long afterUserId,
                                         String afterSymbol,
                                         String afterMarginMode,
                                         String afterPositionSide,
                                         int limit) {
        return rebuildPage(productLine, afterUserId, afterSymbol, afterMarginMode, afterPositionSide, limit);
    }

    /** 事务提交前读取最终快照，供 outbox 事件构造使用。 */
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
                       COALESCE(m.asset, '') AS margin_asset,
                       COALESCE(m.margin_units, 0) AS margin_units,
                       p.updated_at AS position_updated_at,
                       COALESCE(m.updated_at, p.updated_at) AS margin_updated_at
                  FROM account_positions p
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

    public PositionCacheEvent capture(ProductLine productLine,
                                      long userId,
                                      String symbol,
                                      MarginMode marginMode,
                                      PositionSide positionSide) {
        return captureFinalSnapshot(productLine, userId, symbol, marginMode, positionSide);
    }

    private PositionCacheEvent toEvent(java.sql.ResultSet rs) throws java.sql.SQLException {
        long revision = rs.getLong("revision");
        Number version = (Number) rs.getObject("instrument_version");
        String marginAsset = rs.getString("margin_asset");
        if ((marginAsset == null || marginAsset.isBlank()) && version != null
                && snapshotCache != null && snapshotCache.initialized(ProductLine.valueOf(rs.getString("product_line")))) {
            marginAsset = snapshotCache.version(ProductLine.valueOf(rs.getString("product_line")),
                            rs.getString("symbol"), version.longValue())
                    .map(value -> value.settleAsset()).orElse("");
        }
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
                marginAsset,
                rs.getLong("margin_units"),
                rs.getTimestamp("position_updated_at").toInstant(),
                rs.getTimestamp("margin_updated_at").toInstant(),
                revision);
    }
}
