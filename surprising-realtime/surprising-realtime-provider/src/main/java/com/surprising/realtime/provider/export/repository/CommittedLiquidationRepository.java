package com.surprising.realtime.provider.export.repository;

import com.surprising.aeron.service.realtime.state.RealtimeStateCapture.LiquidationChange;
import com.surprising.aeron.service.risk.LiquidationRuntime;
import com.surprising.product.api.ProductLine;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 仅访问 core_liquidation_projection；原日志位置确定先后，同位置事实必须完全一致。 */
@Repository
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(prefix = "surprising.trade-export", name = "enabled", havingValue = "true")
public class CommittedLiquidationRepository {
    private final JdbcTemplate jdbc;

    public CommittedLiquidationRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void persist(ProductLine product, List<LiquidationChange> changes) {
        if (changes.isEmpty()) return;
        var latest = new LinkedHashMap<Long, LiquidationChange>();
        for (var change : changes) {
            var value = change.state();
            if (product == null || value == null || !value.instrument().contractType().name().equals(product.contractTypeCode())
                    || change.logPosition() <= 0 || change.occurredAt() <= 0)
                throw new IllegalArgumentException("invalid committed liquidation");
            var prior = latest.put(value.liquidationId(), change);
            if (prior != null && (!sameIdentity(prior.state(), value)
                    || prior.logPosition() > change.logPosition()
                    || prior.logPosition() == change.logPosition()
                    && (prior.occurredAt() != change.occurredAt() || !sameState(prior.state(), value))))
                throw new IllegalStateException("conflicting committed liquidation batch");
        }
        jdbc.batchUpdate("""
                INSERT INTO core_liquidation_projection(product_line,liquidation_id,user_id,instrument_id,
                  asset,margin_mode,position_side,signed_quantity_steps,
                  close_quantity_steps,execution_price_ticks,liquidation_fee_rate_ppm,
                  liquidation_fee_units,status,cluster_position,updated_at_epoch_ms)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(product_line,liquidation_id) DO UPDATE SET
                  signed_quantity_steps=EXCLUDED.signed_quantity_steps,close_quantity_steps=EXCLUDED.close_quantity_steps,
                  execution_price_ticks=EXCLUDED.execution_price_ticks,
                  liquidation_fee_rate_ppm=EXCLUDED.liquidation_fee_rate_ppm,
                  liquidation_fee_units=EXCLUDED.liquidation_fee_units,status=EXCLUDED.status,
                  cluster_position=EXCLUDED.cluster_position,updated_at_epoch_ms=EXCLUDED.updated_at_epoch_ms
                WHERE core_liquidation_projection.cluster_position<EXCLUDED.cluster_position
                  AND core_liquidation_projection.user_id=EXCLUDED.user_id
                  AND core_liquidation_projection.instrument_id=EXCLUDED.instrument_id
                  AND core_liquidation_projection.asset=EXCLUDED.asset
                  AND core_liquidation_projection.margin_mode=EXCLUDED.margin_mode
                  AND core_liquidation_projection.position_side=EXCLUDED.position_side
                """, latest.values(), 256, (s, change) -> {
            var v = change.state();
            s.setString(1, product.name()); s.setLong(2, v.liquidationId()); s.setLong(3, v.userId());
            s.setString(4, v.instrument().instrumentId()); s.setString(5, v.instrument().settleAsset());
            s.setString(6, v.marginMode().name()); s.setString(7, v.positionSide().name());
            s.setLong(8, v.signedQuantitySteps()); s.setLong(9, v.closeQuantitySteps());
            s.setLong(10, v.executionPriceTicks()); s.setLong(11, v.liquidationFeeRatePpm());
            s.setLong(12, v.liquidationFeeUnits()); s.setString(13, v.status().name());
            s.setLong(14, change.logPosition()); s.setLong(15, change.occurredAt());
        });
        var args = new ArrayList<Object>(); args.add(product.name()); args.addAll(latest.keySet());
        jdbc.query("SELECT * FROM core_liquidation_projection WHERE product_line=? AND liquidation_id IN ("
                + String.join(",", Collections.nCopies(latest.size(), "?")) + ") ORDER BY liquidation_id FOR UPDATE", rs -> {
            var change = latest.remove(rs.getLong("liquidation_id"));
            var v = change.state();
            if (rs.getLong("user_id") != v.userId()
                    || !rs.getString("instrument_id").equals(v.instrument().instrumentId())
                    || !rs.getString("asset").equals(v.instrument().settleAsset())
                    || !rs.getString("margin_mode").equals(v.marginMode().name())
                    || !rs.getString("position_side").equals(v.positionSide().name()))
                throw new IllegalStateException("conflicting committed liquidation identity");
            long position = rs.getLong("cluster_position");
            if (position > change.logPosition()) return;
            if (position != change.logPosition() || rs.getLong("updated_at_epoch_ms") != change.occurredAt()
                    || rs.getLong("signed_quantity_steps") != v.signedQuantitySteps()
                    || rs.getLong("close_quantity_steps") != v.closeQuantitySteps()
                    || rs.getLong("execution_price_ticks") != v.executionPriceTicks()
                    || rs.getLong("liquidation_fee_rate_ppm") != v.liquidationFeeRatePpm()
                    || rs.getLong("liquidation_fee_units") != v.liquidationFeeUnits()
                    || !rs.getString("status").equals(v.status().name()))
                throw new IllegalStateException("conflicting committed liquidation fact");
        }, args.toArray());
        if (!latest.isEmpty()) throw new IllegalStateException("incomplete committed liquidations");
    }

    private static boolean sameIdentity(LiquidationRuntime a, LiquidationRuntime b) {
        return a.userId() == b.userId() && a.instrument().instrumentIdValue() == b.instrument().instrumentIdValue()
                && a.instrument().settleAsset().equals(b.instrument().settleAsset())
                && a.marginMode() == b.marginMode() && a.positionSide() == b.positionSide();
    }

    private static boolean sameState(LiquidationRuntime a, LiquidationRuntime b) {
        return a.signedQuantitySteps() == b.signedQuantitySteps()
                && a.closeQuantitySteps() == b.closeQuantitySteps()
                && a.executionPriceTicks() == b.executionPriceTicks() && a.liquidationFeeRatePpm() == b.liquidationFeeRatePpm()
                && a.liquidationFeeUnits() == b.liquidationFeeUnits() && a.status() == b.status();
    }
}
