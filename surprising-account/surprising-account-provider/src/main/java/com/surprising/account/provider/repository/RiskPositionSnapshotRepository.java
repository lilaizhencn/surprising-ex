package com.surprising.account.provider.repository;

import com.surprising.trading.api.model.PositionSide;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 风险持仓快照单表仓储。 */
@Repository
public class RiskPositionSnapshotRepository {

    private final JdbcTemplate jdbcTemplate;

    public RiskPositionSnapshotRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<RiskSnapshotRow> findLatestIsolated(long userId,
                                                        String symbol,
                                                        PositionSide positionSide,
                                                        Duration maxAge) {
        return jdbcTemplate.query("""
                SELECT instrument_version, signed_quantity_steps, unrealized_pnl_units,
                       maintenance_margin_units, status, event_time
                  FROM risk_position_snapshots
                 WHERE user_id = ?
                   AND symbol = ?
                   AND margin_mode = 'ISOLATED'
                   AND position_side = ?
                   AND event_time >= now() - (? * INTERVAL '1 millisecond')
                 ORDER BY event_time DESC
                 LIMIT 1
                """, (rs, rowNum) -> new RiskSnapshotRow(
                        rs.getLong("instrument_version"),
                        rs.getLong("signed_quantity_steps"),
                        rs.getLong("unrealized_pnl_units"),
                        rs.getLong("maintenance_margin_units"),
                        rs.getString("status"),
                        rs.getTimestamp("event_time").toInstant()), userId, symbol,
                PositionSide.defaultIfNull(positionSide).name(), maxAge.toMillis())
                .stream().findFirst();
    }

    public record RiskSnapshotRow(
            long instrumentVersion,
            long signedQuantitySteps,
            long unrealizedPnlUnits,
            long maintenanceMarginUnits,
            String status,
            Instant eventTime) {
    }
}
