package com.surprising.realtime.provider.export.repository;

import com.surprising.aeron.protocol.export.LiquidationChange;
import com.surprising.aeron.protocol.export.LiquidationOrder;
import com.surprising.product.api.ProductLine;
import java.util.LinkedHashMap;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 仅访问 trading_liquidations；按 cluster_position 单句幂等更新强平投影。 */
@Repository
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(prefix = "surprising.trade-export", name = "enabled", havingValue = "true", matchIfMissing = true)
public class CommittedLiquidationRepository {
    private final JdbcTemplate jdbc;

    public CommittedLiquidationRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void persist(ProductLine product, List<LiquidationChange> changes) {
        if (changes.isEmpty()) return;
        var latest = new LinkedHashMap<Long, LiquidationChange>();
        for (var change : changes) {
            var value = change.state();
            if (product == null || value == null || !value.contractTypeCode().equals(product.contractTypeCode())
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
                INSERT INTO trading_liquidations(product_line,liquidation_id,user_id,instrument_id,
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
                WHERE trading_liquidations.cluster_position<EXCLUDED.cluster_position
                  AND trading_liquidations.user_id=EXCLUDED.user_id
                  AND trading_liquidations.instrument_id=EXCLUDED.instrument_id
                  AND trading_liquidations.asset=EXCLUDED.asset
                  AND trading_liquidations.margin_mode=EXCLUDED.margin_mode
                  AND trading_liquidations.position_side=EXCLUDED.position_side
                """, latest.values(), 256, (s, change) -> {
            var v = change.state();
            s.setString(1, product.name()); s.setLong(2, v.liquidationId()); s.setLong(3, v.userId());
            s.setString(4, v.instrumentId()); s.setString(5, v.settleAsset());
            s.setString(6, v.marginMode().name()); s.setString(7, v.positionSide().name());
            s.setLong(8, v.signedQuantitySteps()); s.setLong(9, v.closeQuantitySteps());
            s.setLong(10, v.executionPriceTicks()); s.setLong(11, v.liquidationFeeRatePpm());
            s.setLong(12, v.liquidationFeeUnits()); s.setString(13, v.status());
            s.setLong(14, change.logPosition()); s.setLong(15, change.occurredAt());
        });
    }

    private static boolean sameIdentity(LiquidationOrder a, LiquidationOrder b) {
        return a.userId() == b.userId() && a.instrumentId().equals(b.instrumentId())
                && a.settleAsset().equals(b.settleAsset())
                && a.marginMode() == b.marginMode() && a.positionSide() == b.positionSide();
    }

    private static boolean sameState(LiquidationOrder a, LiquidationOrder b) {
        return a.signedQuantitySteps() == b.signedQuantitySteps()
                && a.closeQuantitySteps() == b.closeQuantitySteps()
                && a.executionPriceTicks() == b.executionPriceTicks() && a.liquidationFeeRatePpm() == b.liquidationFeeRatePpm()
                && a.liquidationFeeUnits() == b.liquidationFeeUnits() && a.status().equals(b.status());
    }
}
