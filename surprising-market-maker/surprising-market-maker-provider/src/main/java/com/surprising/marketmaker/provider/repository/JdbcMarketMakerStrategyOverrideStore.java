package com.surprising.marketmaker.provider.repository;

import com.surprising.marketmaker.provider.model.StrategyConfigOverride;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcMarketMakerStrategyOverrideStore implements MarketMakerStrategyOverrideStore {

    private final JdbcTemplate jdbcTemplate;

    public JdbcMarketMakerStrategyOverrideStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<StrategyConfigOverride> findAll() {
        return jdbcTemplate.query("""
                SELECT product_line, strategy_id, enabled, base_quantity_steps, margin_mode, spread_ticks,
                       level_spacing_ticks, max_inventory_steps, max_inventory_skew_ppm,
                       order_levels, updated_by_admin_user_id, reason, updated_at, version
                  FROM market_maker_strategy_overrides
                 ORDER BY product_line ASC, strategy_id ASC
                """, this::map);
    }

    @Override
    public Optional<StrategyConfigOverride> find(ProductLine productLine, String strategyId) {
        return jdbcTemplate.query("""
                SELECT product_line, strategy_id, enabled, base_quantity_steps, margin_mode, spread_ticks,
                       level_spacing_ticks, max_inventory_steps, max_inventory_skew_ppm,
                       order_levels, updated_by_admin_user_id, reason, updated_at, version
                  FROM market_maker_strategy_overrides
                 WHERE product_line = ?
                   AND strategy_id = ?
                """, this::map, productLine.name(), strategyId).stream().findFirst();
    }

    @Override
    public StrategyConfigOverride save(StrategyConfigOverride override) {
        Instant now = Instant.now();
        return jdbcTemplate.query("""
                INSERT INTO market_maker_strategy_overrides (
                    product_line, strategy_id, enabled, base_quantity_steps, margin_mode, spread_ticks,
                    level_spacing_ticks, max_inventory_steps, max_inventory_skew_ppm,
                    order_levels, updated_by_admin_user_id, reason, updated_at, version
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1)
                ON CONFLICT (product_line, strategy_id) DO UPDATE
                   SET enabled = EXCLUDED.enabled,
                       base_quantity_steps = EXCLUDED.base_quantity_steps,
                       margin_mode = EXCLUDED.margin_mode,
                       spread_ticks = EXCLUDED.spread_ticks,
                       level_spacing_ticks = EXCLUDED.level_spacing_ticks,
                       max_inventory_steps = EXCLUDED.max_inventory_steps,
                       max_inventory_skew_ppm = EXCLUDED.max_inventory_skew_ppm,
                       order_levels = EXCLUDED.order_levels,
                       updated_by_admin_user_id = EXCLUDED.updated_by_admin_user_id,
                       reason = EXCLUDED.reason,
                       updated_at = EXCLUDED.updated_at,
                       version = market_maker_strategy_overrides.version + 1
                RETURNING product_line, strategy_id, enabled, base_quantity_steps, margin_mode, spread_ticks,
                          level_spacing_ticks, max_inventory_steps, max_inventory_skew_ppm,
                          order_levels, updated_by_admin_user_id, reason, updated_at, version
                """, this::map,
                override.productLine().name(),
                override.strategyId(),
                override.enabled(),
                override.baseQuantitySteps(),
                override.marginMode() == null ? null : override.marginMode().name(),
                override.spreadTicks(),
                override.levelSpacingTicks(),
                override.maxInventorySteps(),
                override.maxInventorySkewPpm(),
                override.orderLevels(),
                override.updatedByAdminUserId(),
                override.reason(),
                Timestamp.from(now)).getFirst();
    }

    @Override
    public void delete(ProductLine productLine, String strategyId) {
        jdbcTemplate.update("""
                DELETE FROM market_maker_strategy_overrides
                 WHERE product_line = ?
                   AND strategy_id = ?
                """, productLine.name(), strategyId);
    }

    private StrategyConfigOverride map(ResultSet rs, int rowNum) throws SQLException {
        String marginMode = rs.getString("margin_mode");
        Timestamp updatedAt = rs.getTimestamp("updated_at");
        return new StrategyConfigOverride(
                rs.getString("strategy_id"),
                ProductLine.valueOf(rs.getString("product_line")),
                (Boolean) rs.getObject("enabled"),
                longValue(rs, "base_quantity_steps"),
                marginMode == null ? null : MarginMode.valueOf(marginMode),
                longValue(rs, "spread_ticks"),
                longValue(rs, "level_spacing_ticks"),
                longValue(rs, "max_inventory_steps"),
                longValue(rs, "max_inventory_skew_ppm"),
                intValue(rs, "order_levels"),
                rs.getString("updated_by_admin_user_id"),
                rs.getString("reason"),
                updatedAt == null ? null : updatedAt.toInstant(),
                rs.getLong("version"));
    }

    private Long longValue(ResultSet rs, String column) throws SQLException {
        Number value = (Number) rs.getObject(column);
        return value == null ? null : value.longValue();
    }

    private Integer intValue(ResultSet rs, String column) throws SQLException {
        Number value = (Number) rs.getObject(column);
        return value == null ? null : value.intValue();
    }
}
