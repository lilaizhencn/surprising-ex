package com.surprising.liquidation.provider.repository;

import com.surprising.instrument.api.math.PerpetualContractMath;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.liquidation.api.model.LiquidationOrderResponse;
import com.surprising.liquidation.api.model.LiquidationOrderStatus;
import com.surprising.liquidation.provider.model.ClaimedCandidate;
import com.surprising.liquidation.provider.model.LiquidationCloseState;
import com.surprising.liquidation.provider.model.LiquidationSizingInput;
import com.surprising.risk.api.model.RiskStatus;
import com.surprising.trading.api.model.OrderSide;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class LiquidationRepository {

    private final JdbcTemplate jdbcTemplate;

    public LiquidationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<ClaimedCandidate> claimCandidate(long candidateId) {
        return jdbcTemplate.query("""
                UPDATE risk_liquidation_candidates
                   SET status = 'PROCESSING',
                       updated_at = now()
                 WHERE candidate_id = ?
                   AND status = 'NEW'
                RETURNING candidate_id, snapshot_id, user_id, symbol, settle_asset,
                          instrument_version, signed_quantity_steps, mark_price_ticks, margin_ratio_ppm
                """, (rs, rowNum) -> new ClaimedCandidate(
                rs.getLong("candidate_id"),
                rs.getLong("snapshot_id"),
                rs.getLong("user_id"),
                rs.getString("symbol"),
                rs.getLong("instrument_version"),
                rs.getString("settle_asset"),
                rs.getLong("signed_quantity_steps"),
                rs.getLong("mark_price_ticks"),
                rs.getLong("margin_ratio_ppm")), candidateId).stream().findFirst();
    }

    public RiskStatus latestRiskStatus(long userId, String settleAsset, Duration maxSnapshotAge) {
        return jdbcTemplate.query("""
                SELECT status
                  FROM risk_account_snapshots
                 WHERE user_id = ? AND settle_asset = ?
                   AND event_time >= now() - (? * INTERVAL '1 millisecond')
                 ORDER BY event_time DESC
                 LIMIT 1
                """, (rs, rowNum) -> RiskStatus.valueOf(rs.getString("status")),
                userId, settleAsset, Math.max(1L, maxSnapshotAge.toMillis()))
                .stream()
                .findFirst()
                .orElse(RiskStatus.NORMAL);
    }

    public Optional<LiquidationCloseState> lockCloseState(long userId, String symbol, long instrumentVersion) {
        return jdbcTemplate.query("""
                SELECT signed_quantity_steps
                  FROM account_positions
                 WHERE user_id = ? AND symbol = ? AND instrument_version = ?
                 FOR UPDATE
                """, (rs, rowNum) -> new LiquidationCloseState(
                rs.getLong("signed_quantity_steps")), userId, symbol, instrumentVersion).stream().findFirst();
    }

    public long lockOpenReduceOnlySteps(long userId, String symbol, long instrumentVersion, OrderSide closeSide) {
        return jdbcTemplate.query("""
                SELECT remaining_quantity_steps
                  FROM trading_orders
                 WHERE user_id = ?
                   AND symbol = ?
                   AND instrument_version = ?
                   AND side = ?
                   AND reduce_only = TRUE
                   AND status IN ('ACCEPTED', 'PARTIALLY_FILLED', 'CANCEL_REQUESTED')
                 FOR UPDATE
                """, (rs, rowNum) -> rs.getLong("remaining_quantity_steps"), userId, symbol,
                instrumentVersion, closeSide.name())
                .stream()
                .mapToLong(Long::longValue)
                .reduce(0L, Math::addExact);
    }

    public Optional<LiquidationSizingInput> sizingInput(long userId,
                                                        String symbol,
                                                        long instrumentVersion,
                                                        long availableCloseSteps) {
        String sql = """
                SELECT p.symbol,
                       p.instrument_version AS version,
                       i.contract_type,
                       p.signed_quantity_steps,
                       i.notional_multiplier_units,
                       i.price_tick_units,
                       ss.scale_units AS settle_scale_units,
                       pm.mark_price_ticks
                  FROM account_positions p
                  JOIN instruments i ON i.symbol = p.symbol AND i.version = p.instrument_version
                  JOIN account_asset_scales ss ON ss.asset = i.settle_asset
                  JOIN LATERAL (
                      SELECT ((m.mark_price_units + i.price_tick_units / 2) / i.price_tick_units) AS mark_price_ticks,
                             event_time
                        FROM price_mark_ticks m
                       WHERE m.symbol = p.symbol
                       ORDER BY event_time DESC
                       LIMIT 1
                  ) pm ON TRUE
                 WHERE p.user_id = ? AND p.symbol = ? AND p.instrument_version = ?
                   AND p.signed_quantity_steps <> 0
                   AND pm.mark_price_ticks > 0
                """;
        return jdbcTemplate.query(sql, (rs, rowNum) -> {
                    ContractType contractType = ContractType.valueOf(rs.getString("contract_type"));
                    long signedQuantitySteps = rs.getLong("signed_quantity_steps");
                    long markPriceTicks = rs.getLong("mark_price_ticks");
                    long notionalMultiplierUnits = rs.getLong("notional_multiplier_units");
                    long priceTickUnits = rs.getLong("price_tick_units");
                    long settleScaleUnits = rs.getLong("settle_scale_units");
                    long notionalUnits = PerpetualContractMath.notionalUnits(contractType, signedQuantitySteps,
                            markPriceTicks, notionalMultiplierUnits, priceTickUnits, settleScaleUnits);
                    long notionalPerStepUnits = Math.max(1L, PerpetualContractMath.notionalPerStepUnits(contractType,
                            markPriceTicks, notionalMultiplierUnits, priceTickUnits, settleScaleUnits));
                    return new SizingRow(
                            rs.getString("symbol"),
                            rs.getLong("version"),
                            Math.absExact(signedQuantitySteps),
                            notionalUnits,
                            notionalPerStepUnits);
                }, userId, symbol, instrumentVersion)
                .stream()
                .findFirst()
                .map(row -> new LiquidationSizingInput(
                        row.positionAbsSteps(),
                        availableCloseSteps,
                        row.notionalUnits(),
                        row.notionalPerStepUnits(),
                        bracketFloorNotionalUnits(row.symbol(), row.version(), row.notionalUnits())));
    }

    private long bracketFloorNotionalUnits(String symbol, long instrumentVersion, long notionalUnits) {
        Long value = jdbcTemplate.queryForObject("""
                SELECT COALESCE(MAX(notional_floor_units), 0)
                  FROM instrument_risk_brackets
                 WHERE symbol = ?
                   AND version = ?
                   AND notional_floor_units <= ?
                """, Long.class, symbol, instrumentVersion, notionalUnits);
        return value == null ? 0L : value;
    }

    public void markCandidate(long candidateId, String status) {
        int rows = jdbcTemplate.update("""
                UPDATE risk_liquidation_candidates
                   SET status = ?,
                       updated_at = now()
                 WHERE candidate_id = ?
                """, status, candidateId);
        requireSingleRow(rows, "liquidation candidate status update");
    }

    public Optional<Long> updateOrderLifecycle(long orderId,
                                               LiquidationOrderStatus orderStatus,
                                               String candidateStatus) {
        List<Long> candidateIds = jdbcTemplate.query("""
                UPDATE liquidation_orders
                   SET status = ?
                 WHERE order_id = ?
                   AND status IN ('SUBMITTED', 'PARTIALLY_FILLED')
                RETURNING candidate_id
                """, (rs, rowNum) -> rs.getLong("candidate_id"), orderStatus.name(), orderId);
        if (candidateIds.isEmpty()) {
            return Optional.empty();
        }
        long candidateId = candidateIds.get(0);
        int rows = jdbcTemplate.update("""
                UPDATE risk_liquidation_candidates
                   SET status = ?,
                       updated_at = now()
                 WHERE candidate_id = ?
                   AND status = 'PROCESSING'
                """, candidateStatus, candidateId);
        requireSingleRow(rows, "liquidation candidate lifecycle update");
        return Optional.of(candidateId);
    }

    public boolean insertLiquidationOrder(long liquidationOrderId,
                                          long candidateId,
                                          long orderId,
                                          long userId,
                                          String symbol,
                                          OrderSide side,
                                          long quantitySteps,
                                          LiquidationOrderStatus status,
                                          String reason,
                                          Instant now) {
        int rows = jdbcTemplate.update("""
                INSERT INTO liquidation_orders (
                    liquidation_order_id, candidate_id, order_id, user_id, symbol,
                    side, quantity_steps, status, reason, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (candidate_id) DO NOTHING
                """, liquidationOrderId, candidateId, orderId, userId, symbol, side.name(),
                quantitySteps, status.name(), reason, Timestamp.from(now));
        return rows == 1;
    }

    public List<LiquidationOrderResponse> orders(Long userId, int limit) {
        return jdbcTemplate.query("""
                SELECT *
                  FROM liquidation_orders
                 WHERE (? IS NULL OR user_id = ?)
                 ORDER BY created_at DESC
                 LIMIT ?
                """, (rs, rowNum) -> toOrder(rs), userId, userId, limit);
    }

    public List<LiquidationOrderResponse> ordersByCandidate(long candidateId) {
        return jdbcTemplate.query("""
                SELECT *
                  FROM liquidation_orders
                 WHERE candidate_id = ?
                 ORDER BY created_at DESC
                """, (rs, rowNum) -> toOrder(rs), candidateId);
    }

    private LiquidationOrderResponse toOrder(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new LiquidationOrderResponse(
                rs.getLong("liquidation_order_id"),
                rs.getLong("candidate_id"),
                rs.getLong("order_id"),
                rs.getLong("user_id"),
                rs.getString("symbol"),
                OrderSide.valueOf(rs.getString("side")),
                rs.getLong("quantity_steps"),
                LiquidationOrderStatus.valueOf(rs.getString("status")),
                rs.getString("reason"),
                rs.getTimestamp("created_at").toInstant());
    }

    private record SizingRow(
            String symbol,
            long version,
            long positionAbsSteps,
            long notionalUnits,
            long notionalPerStepUnits) {
    }

    private void requireSingleRow(int rows, String operation) {
        if (rows != 1) {
            throw new IllegalStateException("failed to write " + operation);
        }
    }
}
