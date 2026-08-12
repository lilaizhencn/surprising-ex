package com.surprising.risk.provider.repository;

import com.surprising.product.api.ProductLine;
import com.surprising.risk.api.model.RiskPositionSnapshotResponse;
import com.surprising.risk.api.model.RiskStatus;
import com.surprising.risk.provider.config.RiskProperties;
import com.surprising.risk.provider.model.CalculatedPositionRisk;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.PositionSide;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 风险持仓快照仓储，只负责 {@code risk_position_snapshots} 表。 */
@Repository
public class RiskPositionSnapshotRepository {

    private final JdbcTemplate jdbcTemplate;
    private final RiskProperties properties;

    public RiskPositionSnapshotRepository(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, new RiskProperties());
    }

    @Autowired
    public RiskPositionSnapshotRepository(JdbcTemplate jdbcTemplate, RiskProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties == null ? new RiskProperties() : properties;
    }

    public void saveAll(List<PositionSnapshotWrite> snapshots) {
        if (snapshots.isEmpty()) {
            return;
        }
        int[] rows = jdbcTemplate.batchUpdate("""
                INSERT INTO risk_position_snapshots (
                    product_line, snapshot_id, user_id, symbol, margin_mode, position_side, instrument_version,
                    settle_asset, signed_quantity_steps, entry_price_ticks, mark_price_ticks, notional_units,
                    unrealized_pnl_units, maintenance_margin_units, position_margin_units, margin_ratio_ppm,
                    status, event_time, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
                """, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(java.sql.PreparedStatement statement, int index) throws java.sql.SQLException {
                PositionSnapshotWrite row = snapshots.get(index);
                CalculatedPositionRisk position = row.position();
                statement.setString(1, currentProductLine().name());
                statement.setLong(2, row.snapshotId());
                statement.setLong(3, position.userId());
                statement.setString(4, position.symbol());
                statement.setString(5, position.marginMode().name());
                statement.setString(6, position.positionSide().name());
                statement.setLong(7, position.instrumentVersion());
                statement.setString(8, position.settleAsset());
                statement.setLong(9, position.signedQuantitySteps());
                statement.setLong(10, position.entryPriceTicks());
                statement.setLong(11, position.markPriceTicks());
                statement.setLong(12, position.notionalUnits());
                statement.setLong(13, position.unrealizedPnlUnits());
                statement.setLong(14, position.maintenanceMarginUnits());
                statement.setLong(15, position.positionMarginUnits());
                statement.setLong(16, row.marginRatioPpm());
                statement.setString(17, row.status().name());
                statement.setTimestamp(18, Timestamp.from(row.eventTime()));
            }

            @Override
            public int getBatchSize() {
                return snapshots.size();
            }
        });
        requireCompleteBatch(rows, snapshots.size());
    }

    public List<RiskPositionSnapshotResponse> latest(long userId) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("""
                SELECT DISTINCT ON (s.symbol, s.margin_mode, s.position_side) s.*
                  FROM risk_position_snapshots s
                 WHERE s.user_id = ?
                """);
        args.add(userId);
        if (properties.getKafka().isProductTopicsEnabled()) {
            ProductLine line = currentProductLine();
            if (line.isMarginProduct()) {
                sql.append("                   AND s.product_line = ?\n");
                args.add(line.name());
            } else {
                sql.append("                   AND 1 = 0\n");
            }
        }
        sql.append("""
                 ORDER BY s.symbol ASC, s.margin_mode ASC, s.position_side ASC, s.event_time DESC
                """);
        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> new RiskPositionSnapshotResponse(
                rs.getLong("snapshot_id"),
                rs.getLong("user_id"),
                rs.getString("symbol"),
                MarginMode.fromNullableDbValue(rs.getString("margin_mode")),
                PositionSide.fromNullableDbValue(rs.getString("position_side")),
                rs.getLong("instrument_version"),
                rs.getString("settle_asset"),
                rs.getLong("signed_quantity_steps"),
                rs.getLong("entry_price_ticks"),
                rs.getLong("mark_price_ticks"),
                rs.getLong("notional_units"),
                rs.getLong("unrealized_pnl_units"),
                rs.getLong("maintenance_margin_units"),
                rs.getLong("position_margin_units"),
                rs.getLong("margin_ratio_ppm"),
                RiskStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("event_time").toInstant()), args.toArray());
    }

    private ProductLine currentProductLine() {
        return properties.getKafka().isProductTopicsEnabled()
                ? properties.getKafka().getProductLine()
                : ProductLine.LINEAR_PERPETUAL;
    }

    private void requireCompleteBatch(int[] rows, int expectedSize) {
        if (rows.length != expectedSize) {
            throw new IllegalStateException("写入风险持仓快照批次不完整");
        }
        for (int row : rows) {
            if (row != 1 && row != Statement.SUCCESS_NO_INFO) {
                throw new IllegalStateException("写入风险持仓快照失败");
            }
        }
    }

    public record PositionSnapshotWrite(long snapshotId,
                                        CalculatedPositionRisk position,
                                        long marginRatioPpm,
                                        RiskStatus status,
                                        Instant eventTime) {
    }
}
