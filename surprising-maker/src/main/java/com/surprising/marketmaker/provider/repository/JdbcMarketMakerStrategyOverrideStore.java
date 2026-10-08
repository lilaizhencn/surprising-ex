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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 后台策略定义及覆盖的持久化边界；周期读取共享提交后的不可变配置。 */
@Repository
public class JdbcMarketMakerStrategyOverrideStore implements MarketMakerStrategyOverrideStore {

    private final JdbcTemplate jdbcTemplate;
    /** Derived immutable configuration snapshots; invalidated only after committed administrator writes. */
    private volatile List<com.surprising.marketmaker.provider.model.MarketMakerStrategyDefinition> definitions;
    private volatile List<StrategyConfigOverride> overrides;

    public JdbcMarketMakerStrategyOverrideStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<com.surprising.marketmaker.provider.model.MarketMakerStrategyDefinition> definitions() {
        var current = definitions;
        if (current != null) return current;
        synchronized (this) {
            if (definitions == null) {
                var loaded = List.copyOf(jdbcTemplate.query(
                        "SELECT * FROM market_maker_strategies ORDER BY product_line, strategy_id", this::definition));
                if (TransactionSynchronizationManager.isActualTransactionActive()) return loaded;
                definitions = loaded;
            }
            return definitions;
        }
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public com.surprising.marketmaker.provider.model.MarketMakerStrategyDefinition saveDefinition(
            com.surprising.marketmaker.provider.model.MarketMakerStrategyDefinition d, String adminUserId, String reason) {
        if (adminUserId == null || adminUserId.isBlank() || reason == null || reason.isBlank() || reason.length() > 1000)
            throw new IllegalArgumentException("admin identity and reason (1-1000 characters) are required");
        var rows = jdbcTemplate.query("""
                INSERT INTO market_maker_strategies (product_line, strategy_id, enabled, account_ids, instrument_ids,
                    base_quantity_steps, margin_mode, spread_ticks, level_spacing_ticks, max_inventory_steps,
                    max_inventory_skew_ppm, order_levels, initial_anchor_price_ticks, version, updated_by, reason)
                SELECT ?, ?, ?, ?::bigint[], ?::text[], ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ? WHERE ? = 0
                ON CONFLICT (product_line, strategy_id) DO NOTHING RETURNING *
                """, this::definition, d.productLine().name(), d.strategyId(), d.enabled(),
                array(d.accountIds()), array(d.instrumentIds()), d.baseQuantitySteps(), d.marginMode().name(),
                d.spreadTicks(), d.levelSpacingTicks(), d.maxInventorySteps(), d.maxInventorySkewPpm(),
                d.orderLevels(), d.initialAnchorPriceTicks(), adminUserId, reason, d.version());
        if (d.version() > 0) {
            rows = jdbcTemplate.query("""
                    UPDATE market_maker_strategies SET enabled=?, account_ids=?::bigint[], instrument_ids=?::text[],
                        base_quantity_steps=?, margin_mode=?, spread_ticks=?, level_spacing_ticks=?, max_inventory_steps=?,
                        max_inventory_skew_ppm=?, order_levels=?, initial_anchor_price_ticks=?, version=version+1,
                        updated_by=?, reason=?, updated_at=now()
                    WHERE product_line=? AND strategy_id=? AND version=? RETURNING *
                    """, this::definition, d.enabled(), array(d.accountIds()), array(d.instrumentIds()),
                    d.baseQuantitySteps(), d.marginMode().name(), d.spreadTicks(), d.levelSpacingTicks(),
                    d.maxInventorySteps(), d.maxInventorySkewPpm(), d.orderLevels(), d.initialAnchorPriceTicks(),
                    adminUserId, reason, d.productLine().name(), d.strategyId(), d.version());
        }
        if (rows.isEmpty()) throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.CONFLICT, "做市配置已更新，请重新加载");
        delete(d.productLine(), d.strategyId());
        invalidateAfterCommit(true);
        return rows.getFirst();
    }

    private String array(List<?> values) {
        return "{" + values.stream().map(Object::toString).collect(java.util.stream.Collectors.joining(",")) + "}";
    }

    private com.surprising.marketmaker.provider.model.MarketMakerStrategyDefinition definition(ResultSet rs, int row) throws SQLException {
        return new com.surprising.marketmaker.provider.model.MarketMakerStrategyDefinition(
                rs.getString("strategy_id"), ProductLine.valueOf(rs.getString("product_line")), rs.getBoolean("enabled"),
                java.util.Arrays.stream((Object[]) rs.getArray("account_ids").getArray()).map(v -> ((Number) v).longValue()).toList(),
                java.util.Arrays.stream((Object[]) rs.getArray("instrument_ids").getArray()).map(Object::toString).toList(),
                rs.getLong("base_quantity_steps"), MarginMode.valueOf(rs.getString("margin_mode")),
                rs.getLong("spread_ticks"), rs.getLong("level_spacing_ticks"), rs.getLong("max_inventory_steps"),
                rs.getLong("max_inventory_skew_ppm"), rs.getInt("order_levels"), rs.getLong("initial_anchor_price_ticks"),
                rs.getLong("version"));
    }

    @Override
    public List<StrategyConfigOverride> findAll() {
        var current = overrides;
        if (current != null) return current;
        synchronized (this) {
            if (overrides == null) {
                var loaded = List.copyOf(jdbcTemplate.query("""
                SELECT product_line, strategy_id, enabled, base_quantity_steps, margin_mode, spread_ticks,
                       level_spacing_ticks, max_inventory_steps, max_inventory_skew_ppm,
                       order_levels, updated_by_admin_user_id, reason, updated_at, version
                  FROM market_maker_strategy_overrides
                 ORDER BY product_line ASC, strategy_id ASC
                """, this::map));
                if (TransactionSynchronizationManager.isActualTransactionActive()) return loaded;
                overrides = loaded;
            }
            return overrides;
        }
    }

    @Override
    public Optional<StrategyConfigOverride> find(ProductLine productLine, String strategyId) {
        return findAll().stream().filter(value -> value.productLine() == productLine
                && value.strategyId().equals(strategyId)).findFirst();
    }

    @Override
    public StrategyConfigOverride save(StrategyConfigOverride override) {
        Instant now = Instant.now();
        var saved = jdbcTemplate.query("""
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
        invalidateAfterCommit(false);
        return saved;
    }

    @Override
    public void delete(ProductLine productLine, String strategyId) {
        jdbcTemplate.update("""
                DELETE FROM market_maker_strategy_overrides
                 WHERE product_line = ?
                   AND strategy_id = ?
                """, productLine.name(), strategyId);
        invalidateAfterCommit(false);
    }

    private void invalidateAfterCommit(boolean definitionChanged) {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { invalidate(definitionChanged); }
            });
        } else invalidate(definitionChanged);
    }

    private synchronized void invalidate(boolean definitionChanged) {
        if (definitionChanged) definitions = null;
        overrides = null;
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
