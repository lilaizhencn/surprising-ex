package com.surprising.risk.provider.repository;

import com.surprising.instrument.api.model.ContractType;
import com.surprising.risk.api.model.LiquidationCandidateResponse;
import com.surprising.risk.api.model.LiquidationCandidateStatus;
import com.surprising.risk.api.model.RiskAccountSnapshotResponse;
import com.surprising.risk.api.model.RiskPositionSnapshotResponse;
import com.surprising.risk.api.model.RiskStatus;
import com.surprising.risk.provider.model.CalculatedPositionRisk;
import com.surprising.risk.provider.model.RiskGroupKey;
import com.surprising.risk.provider.service.RiskMath;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class RiskRepository {

    private final JdbcTemplate jdbcTemplate;

    public RiskRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<RiskGroupKey> riskGroups(Duration maxMarkAge, RiskGroupKey after, int limit) {
        long afterUserId = after == null ? 0L : after.userId();
        String afterSettleAsset = after == null ? "" : after.settleAsset();
        int cappedLimit = Math.max(1, limit);
        String sql = """
                WITH open_groups AS (
                    SELECT p.user_id,
                           i.settle_asset,
                           bool_and(pm.event_time IS NOT NULL
                               AND pm.event_time >= now() - (? * INTERVAL '1 millisecond')) AS all_marks_fresh
                      FROM account_positions p
                      JOIN instruments i ON i.symbol = p.symbol AND i.version = p.instrument_version
                      LEFT JOIN LATERAL (
                          SELECT event_time
                            FROM price_mark_ticks m
                           WHERE m.symbol = p.symbol
                           ORDER BY event_time DESC
                           LIMIT 1
                      ) pm ON TRUE
                     WHERE p.signed_quantity_steps <> 0
                       AND (? = 0 OR p.user_id > ? OR (p.user_id = ? AND i.settle_asset > ?))
                     GROUP BY p.user_id, i.settle_asset
                )
                SELECT user_id, settle_asset
                  FROM open_groups
                 WHERE all_marks_fresh
                 ORDER BY user_id ASC, settle_asset ASC
                 LIMIT ?
                """;
        return jdbcTemplate.query(sql, (rs, rowNum) -> new RiskGroupKey(rs.getLong("user_id"),
                rs.getString("settle_asset")), maxMarkAge.toMillis(), afterUserId, afterUserId,
                afterUserId, afterSettleAsset, cappedLimit);
    }

    public List<CalculatedPositionRisk> calculatePositions(Duration maxMarkAge) {
        String sql = """
                WITH position_inputs AS (
                    SELECT p.user_id,
                           p.symbol,
                           p.instrument_version,
                           i.contract_type,
                           i.settle_asset,
                           i.notional_multiplier_units,
                           i.price_tick_units,
                           i.maintenance_margin_rate_ppm AS base_maintenance_margin_rate_ppm,
                           ss.scale_units AS settle_scale_units,
                           p.signed_quantity_steps,
                           p.entry_price_ticks,
                           pm.mark_price_ticks,
                           CASE
                               WHEN i.contract_type = 'INVERSE_PERPETUAL' THEN
                                   ROUND((abs(p.signed_quantity_steps)::numeric
                                      * i.notional_multiplier_units::numeric
                                      * ss.scale_units::numeric)
                                     / (pm.mark_price_ticks::numeric * i.price_tick_units::numeric))
                               ELSE abs(p.signed_quantity_steps)::numeric
                                      * pm.mark_price_ticks::numeric
                                      * i.notional_multiplier_units::numeric
                           END AS bracket_notional_units
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
                     WHERE p.signed_quantity_steps <> 0
                       AND pm.event_time >= now() - (? * INTERVAL '1 millisecond')
                )
                SELECT pi.user_id,
                       pi.symbol,
                       pi.instrument_version,
                       pi.contract_type,
                       pi.settle_asset,
                       pi.notional_multiplier_units,
                       pi.price_tick_units,
                       COALESCE(br.maintenance_margin_rate_ppm,
                                pi.base_maintenance_margin_rate_ppm) AS maintenance_margin_rate_ppm,
                       pi.settle_scale_units,
                       pi.signed_quantity_steps,
                       pi.entry_price_ticks,
                       pi.mark_price_ticks
                  FROM position_inputs pi
                  LEFT JOIN LATERAL (
                      SELECT b.maintenance_margin_rate_ppm
                        FROM instrument_risk_brackets b
                       WHERE b.symbol = pi.symbol
                         AND b.version = pi.instrument_version
                         AND b.notional_floor_units <= pi.bracket_notional_units
                       ORDER BY b.notional_floor_units DESC
                       LIMIT 1
                  ) br ON TRUE
                """;
        return queryCalculatedPositions(sql, maxMarkAge.toMillis());
    }

    public List<CalculatedPositionRisk> calculatePositions(RiskGroupKey key, Duration maxMarkAge) {
        String sql = """
                WITH group_freshness AS (
                    SELECT COALESCE(bool_and(pm.event_time IS NOT NULL
                               AND pm.event_time >= now() - (? * INTERVAL '1 millisecond')), false) AS all_marks_fresh
                      FROM account_positions p
                      JOIN instruments i ON i.symbol = p.symbol AND i.version = p.instrument_version
                      LEFT JOIN LATERAL (
                          SELECT event_time
                            FROM price_mark_ticks m
                           WHERE m.symbol = p.symbol
                           ORDER BY event_time DESC
                           LIMIT 1
                      ) pm ON TRUE
                     WHERE p.signed_quantity_steps <> 0
                       AND p.user_id = ?
                       AND i.settle_asset = ?
                ),
                position_inputs AS (
                    SELECT p.user_id,
                           p.symbol,
                           p.instrument_version,
                           i.contract_type,
                           i.settle_asset,
                           i.notional_multiplier_units,
                           i.price_tick_units,
                           i.maintenance_margin_rate_ppm AS base_maintenance_margin_rate_ppm,
                           ss.scale_units AS settle_scale_units,
                           p.signed_quantity_steps,
                           p.entry_price_ticks,
                           pm.mark_price_ticks,
                           CASE
                               WHEN i.contract_type = 'INVERSE_PERPETUAL' THEN
                                   ROUND((abs(p.signed_quantity_steps)::numeric
                                      * i.notional_multiplier_units::numeric
                                      * ss.scale_units::numeric)
                                     / (pm.mark_price_ticks::numeric * i.price_tick_units::numeric))
                               ELSE abs(p.signed_quantity_steps)::numeric
                                      * pm.mark_price_ticks::numeric
                                      * i.notional_multiplier_units::numeric
                           END AS bracket_notional_units
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
                      CROSS JOIN group_freshness gf
                     WHERE p.signed_quantity_steps <> 0
                       AND p.user_id = ?
                       AND i.settle_asset = ?
                       AND gf.all_marks_fresh
                       AND pm.event_time >= now() - (? * INTERVAL '1 millisecond')
                )
                SELECT pi.user_id,
                       pi.symbol,
                       pi.instrument_version,
                       pi.contract_type,
                       pi.settle_asset,
                       pi.notional_multiplier_units,
                       pi.price_tick_units,
                       COALESCE(br.maintenance_margin_rate_ppm,
                                pi.base_maintenance_margin_rate_ppm) AS maintenance_margin_rate_ppm,
                       pi.settle_scale_units,
                       pi.signed_quantity_steps,
                       pi.entry_price_ticks,
                       pi.mark_price_ticks
                  FROM position_inputs pi
                  LEFT JOIN LATERAL (
                      SELECT b.maintenance_margin_rate_ppm
                        FROM instrument_risk_brackets b
                       WHERE b.symbol = pi.symbol
                         AND b.version = pi.instrument_version
                         AND b.notional_floor_units <= pi.bracket_notional_units
                       ORDER BY b.notional_floor_units DESC
                       LIMIT 1
                  ) br ON TRUE
                """;
        return queryCalculatedPositions(sql, maxMarkAge.toMillis(), key.userId(), key.settleAsset(),
                key.userId(), key.settleAsset(), maxMarkAge.toMillis());
    }

    public boolean acquireScanLease(RiskGroupKey key, String ownerId, Duration leaseDuration) {
        Instant now = Instant.now();
        Instant leaseUntil = now.plus(leaseDuration);
        return !jdbcTemplate.query("""
                INSERT INTO risk_scan_leases (user_id, settle_asset, owner_id, lease_until, updated_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (user_id, settle_asset) DO UPDATE SET
                    owner_id = EXCLUDED.owner_id,
                    lease_until = EXCLUDED.lease_until,
                    updated_at = EXCLUDED.updated_at
                WHERE risk_scan_leases.owner_id = EXCLUDED.owner_id
                   OR risk_scan_leases.lease_until <= EXCLUDED.updated_at
                RETURNING owner_id
                """, (rs, rowNum) -> rs.getString("owner_id"),
                key.userId(), key.settleAsset(), ownerId, Timestamp.from(leaseUntil), Timestamp.from(now)).isEmpty();
    }

    public void savePositionSnapshot(long snapshotId,
                                     CalculatedPositionRisk position,
                                     long marginRatioPpm,
                                     RiskStatus status,
                                     Instant now) {
        int rows = jdbcTemplate.update("""
                INSERT INTO risk_position_snapshots (
                    snapshot_id, user_id, symbol, instrument_version, settle_asset, signed_quantity_steps,
                    entry_price_ticks, mark_price_ticks, notional_units, unrealized_pnl_units,
                    maintenance_margin_units, margin_ratio_ppm, status, event_time, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
                """, snapshotId, position.userId(), position.symbol(), position.instrumentVersion(),
                position.settleAsset(),
                position.signedQuantitySteps(), position.entryPriceTicks(), position.markPriceTicks(),
                position.notionalUnits(), position.unrealizedPnlUnits(), position.maintenanceMarginUnits(),
                marginRatioPpm, status.name(), Timestamp.from(now));
        requireSingleRow(rows, "risk position snapshot insert");
    }

    public void saveAccountSnapshot(RiskAccountSnapshotResponse snapshot) {
        int rows = jdbcTemplate.update("""
                INSERT INTO risk_account_snapshots (
                    snapshot_id, user_id, settle_asset, wallet_balance_units,
                    unrealized_pnl_units, equity_units, maintenance_margin_units,
                    margin_ratio_ppm, status, event_time, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
                """, snapshot.snapshotId(), snapshot.userId(), snapshot.settleAsset(),
                snapshot.walletBalanceUnits(), snapshot.unrealizedPnlUnits(), snapshot.equityUnits(),
                snapshot.maintenanceMarginUnits(), snapshot.marginRatioPpm(), snapshot.status().name(),
                Timestamp.from(snapshot.eventTime()));
        requireSingleRow(rows, "risk account snapshot insert");
    }

    public long walletBalanceUnits(long userId, String settleAsset) {
        return jdbcTemplate.query("""
                SELECT COALESCE(b.available_units + b.locked_units - COALESCE(d.deficit_units, 0), 0)
                  FROM account_balances b
                  LEFT JOIN account_deficits d USING (user_id, asset)
                 WHERE b.user_id = ? AND b.asset = ?
                """, (rs, rowNum) -> rs.getLong(1), userId, settleAsset).stream().findFirst().orElse(0L);
    }

    public Optional<RiskAccountSnapshotResponse> latestAccount(long userId, String settleAsset) {
        return jdbcTemplate.query("""
                SELECT *
                  FROM risk_account_snapshots
                 WHERE user_id = ? AND settle_asset = ?
                 ORDER BY event_time DESC
                 LIMIT 1
                """, (rs, rowNum) -> toAccountSnapshot(rs), userId, settleAsset).stream().findFirst();
    }

    public List<RiskPositionSnapshotResponse> latestPositions(long userId) {
        return jdbcTemplate.query("""
                SELECT DISTINCT ON (symbol) *
                  FROM risk_position_snapshots
                 WHERE user_id = ?
                 ORDER BY symbol ASC, event_time DESC
                """, (rs, rowNum) -> toPositionSnapshot(rs), userId);
    }

    public long createLiquidationCandidate(RiskAccountSnapshotResponse account,
                                           CalculatedPositionRisk position,
                                           RiskStatus positionStatus,
                                           long positionMarginRatioPpm,
                                           long candidateId,
                                           Instant now) {
        int rows = jdbcTemplate.update("""
                INSERT INTO risk_liquidation_candidates (
                    candidate_id, snapshot_id, user_id, symbol, instrument_version, settle_asset,
                    signed_quantity_steps, mark_price_ticks, equity_units,
                    maintenance_margin_units, margin_ratio_ppm, status, event_time, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'NEW', ?, now(), now())
                ON CONFLICT (user_id, symbol) WHERE status IN ('NEW', 'PROCESSING') DO NOTHING
                """, candidateId, account.snapshotId(), position.userId(), position.symbol(),
                position.instrumentVersion(), position.settleAsset(),
                position.signedQuantitySteps(), position.markPriceTicks(), account.equityUnits(),
                position.maintenanceMarginUnits(), Math.max(account.marginRatioPpm(), positionMarginRatioPpm),
                Timestamp.from(now));
        return rows == 1 ? candidateId : 0L;
    }

    public Optional<LiquidationCandidateResponse> liquidationCandidate(long candidateId) {
        return jdbcTemplate.query("SELECT * FROM risk_liquidation_candidates WHERE candidate_id = ?",
                (rs, rowNum) -> toCandidate(rs), candidateId).stream().findFirst();
    }

    public List<LiquidationCandidateResponse> liquidationCandidates(LiquidationCandidateStatus status, int limit) {
        return jdbcTemplate.query("""
                SELECT *
                  FROM risk_liquidation_candidates
                 WHERE status = ?
                 ORDER BY event_time ASC
                 LIMIT ?
                """, (rs, rowNum) -> toCandidate(rs), status.name(), limit);
    }

    private RiskAccountSnapshotResponse toAccountSnapshot(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new RiskAccountSnapshotResponse(
                rs.getLong("snapshot_id"),
                rs.getLong("user_id"),
                rs.getString("settle_asset"),
                rs.getLong("wallet_balance_units"),
                rs.getLong("unrealized_pnl_units"),
                rs.getLong("equity_units"),
                rs.getLong("maintenance_margin_units"),
                rs.getLong("margin_ratio_ppm"),
                RiskStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("event_time").toInstant());
    }

    private RiskPositionSnapshotResponse toPositionSnapshot(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new RiskPositionSnapshotResponse(
                rs.getLong("snapshot_id"),
                rs.getLong("user_id"),
                rs.getString("symbol"),
                rs.getLong("instrument_version"),
                rs.getString("settle_asset"),
                rs.getLong("signed_quantity_steps"),
                rs.getLong("entry_price_ticks"),
                rs.getLong("mark_price_ticks"),
                rs.getLong("notional_units"),
                rs.getLong("unrealized_pnl_units"),
                rs.getLong("maintenance_margin_units"),
                rs.getLong("margin_ratio_ppm"),
                RiskStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("event_time").toInstant());
    }

    private LiquidationCandidateResponse toCandidate(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new LiquidationCandidateResponse(
                rs.getLong("candidate_id"),
                rs.getLong("snapshot_id"),
                rs.getLong("user_id"),
                rs.getString("symbol"),
                rs.getLong("instrument_version"),
                rs.getString("settle_asset"),
                rs.getLong("signed_quantity_steps"),
                rs.getLong("mark_price_ticks"),
                rs.getLong("equity_units"),
                rs.getLong("maintenance_margin_units"),
                rs.getLong("margin_ratio_ppm"),
                LiquidationCandidateStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("event_time").toInstant());
    }

    private List<CalculatedPositionRisk> queryCalculatedPositions(String sql, Object... args) {
        return jdbcTemplate.query(sql, calculatedPositionMapper(), args);
    }

    private RowMapper<CalculatedPositionRisk> calculatedPositionMapper() {
        return (rs, rowNum) -> {
            ContractType contractType = ContractType.valueOf(rs.getString("contract_type"));
            long signedQuantitySteps = rs.getLong("signed_quantity_steps");
            long entryPriceTicks = rs.getLong("entry_price_ticks");
            long markPriceTicks = rs.getLong("mark_price_ticks");
            long notionalMultiplierUnits = rs.getLong("notional_multiplier_units");
            long priceTickUnits = rs.getLong("price_tick_units");
            long settleScaleUnits = rs.getLong("settle_scale_units");
            long notionalUnits = RiskMath.notionalUnits(contractType, signedQuantitySteps, markPriceTicks,
                    notionalMultiplierUnits, priceTickUnits, settleScaleUnits);
            long unrealizedPnlUnits = RiskMath.unrealizedPnlUnits(contractType, signedQuantitySteps,
                    entryPriceTicks, markPriceTicks, notionalMultiplierUnits, priceTickUnits, settleScaleUnits);
            long maintenanceMarginUnits = RiskMath.maintenanceMarginUnits(contractType, signedQuantitySteps,
                    markPriceTicks, notionalMultiplierUnits, priceTickUnits, settleScaleUnits,
                    rs.getLong("maintenance_margin_rate_ppm"));
            return new CalculatedPositionRisk(
                    rs.getLong("user_id"),
                    rs.getString("symbol"),
                    rs.getLong("instrument_version"),
                    rs.getString("settle_asset"),
                    signedQuantitySteps,
                    entryPriceTicks,
                    markPriceTicks,
                    notionalUnits,
                    unrealizedPnlUnits,
                    maintenanceMarginUnits);
        };
    }

    private void requireSingleRow(int rows, String operation) {
        if (rows != 1) {
            throw new IllegalStateException("failed to write " + operation);
        }
    }
}
