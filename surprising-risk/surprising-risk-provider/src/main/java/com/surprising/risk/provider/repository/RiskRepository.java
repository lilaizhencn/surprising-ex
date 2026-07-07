package com.surprising.risk.provider.repository;

import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import com.surprising.product.api.ProductLineSql;
import com.surprising.risk.api.model.AdminCursorPage;
import com.surprising.risk.api.model.LiquidationCandidateResponse;
import com.surprising.risk.api.model.LiquidationCandidateStatus;
import com.surprising.risk.api.model.RiskAccountSnapshotResponse;
import com.surprising.risk.api.model.RiskPositionSnapshotResponse;
import com.surprising.risk.api.model.RiskStatus;
import com.surprising.risk.provider.config.RiskProperties;
import com.surprising.risk.provider.model.CalculatedPositionRisk;
import com.surprising.risk.provider.model.PositionRiskTarget;
import com.surprising.risk.provider.model.RiskGroupKey;
import com.surprising.risk.provider.service.RiskMath;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.PositionSide;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class RiskRepository {

    private static final String DEFAULT_ACCOUNT_TYPE = "USDT_PERPETUAL";

    private final JdbcTemplate jdbcTemplate;
    private final RiskProperties properties;

    public RiskRepository(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, new RiskProperties());
    }

    @Autowired
    public RiskRepository(JdbcTemplate jdbcTemplate, RiskProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties == null ? new RiskProperties() : properties;
    }

    public List<RiskGroupKey> riskGroups(Duration maxMarkAge, RiskGroupKey after, int limit) {
        long afterUserId = after == null ? 0L : after.userId();
        String afterAccountType = after == null ? "" : after.accountType();
        String afterSettleAsset = after == null ? "" : after.settleAsset();
        int cappedLimit = Math.max(1, limit);
        List<Object> args = new ArrayList<>();
        String productLineFilter = productLineFilter("i", args);
        String sql = """
                WITH group_inputs AS (
                    SELECT p.user_id,
                           %s AS account_type,
                           i.settle_asset,
                           pm.event_time
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
                       %s
                ),
                open_groups AS (
                    SELECT user_id,
                           account_type,
                           settle_asset,
                           bool_and(event_time IS NOT NULL
                               AND event_time >= now() - (? * INTERVAL '1 millisecond')) AS all_marks_fresh
                      FROM group_inputs
                     WHERE (? = 0
                         OR user_id > ?
                         OR (user_id = ? AND account_type > ?)
                         OR (user_id = ? AND account_type = ? AND settle_asset > ?))
                     GROUP BY user_id, account_type, settle_asset
                )
                SELECT user_id, account_type, settle_asset
                  FROM open_groups
                 WHERE all_marks_fresh
                 ORDER BY user_id ASC, account_type ASC, settle_asset ASC
                 LIMIT ?
                """.formatted(accountTypeExpression("i"), productLineFilter);
        args.add(maxMarkAge.toMillis());
        args.add(afterUserId);
        args.add(afterUserId);
        args.add(afterUserId);
        args.add(afterAccountType);
        args.add(afterUserId);
        args.add(afterAccountType);
        args.add(afterSettleAsset);
        args.add(cappedLimit);
        return jdbcTemplate.query(sql, (rs, rowNum) -> new RiskGroupKey(rs.getLong("user_id"),
                rs.getString("account_type"), rs.getString("settle_asset")), args.toArray());
    }

    public Optional<PositionRiskTarget> riskTargetForPositionEvent(long userId,
                                                                   String symbol,
                                                                   MarginMode marginMode,
                                                                   PositionSide positionSide,
                                                                   long instrumentVersion) {
        List<Object> args = new ArrayList<>();
        String sql = """
                SELECT CAST(? AS bigint) AS user_id,
                       i.symbol,
                       i.version AS instrument_version,
                       %s AS account_type,
                       i.settle_asset
                  FROM instruments i
                 WHERE i.symbol = ?
                   AND i.version = CASE
                         WHEN ? > 0 THEN ?
                         ELSE COALESCE((
                             SELECT cv.version
                               FROM instrument_current_versions cv
                             WHERE cv.symbol = ?
                         ), 0)
                       END
                   %s
                 LIMIT 1
                """.formatted(accountTypeExpression("i"), productLineFilter("i", args));
        args.add(0, userId);
        args.add(1, symbol);
        args.add(2, instrumentVersion);
        args.add(3, instrumentVersion);
        args.add(4, symbol);
        return jdbcTemplate.query(sql, (rs, rowNum) -> new PositionRiskTarget(
                rs.getLong("user_id"),
                rs.getString("symbol"),
                MarginMode.defaultIfNull(marginMode),
                PositionSide.defaultIfNull(positionSide),
                rs.getLong("instrument_version"),
                rs.getString("account_type"),
                rs.getString("settle_asset")), args.toArray())
                .stream()
                .findFirst();
    }

    public Optional<PositionRiskTarget> riskTargetForPositionEvent(long userId,
                                                                   String symbol,
                                                                   MarginMode marginMode,
                                                                   long instrumentVersion) {
        return riskTargetForPositionEvent(userId, symbol, marginMode, PositionSide.NET, instrumentVersion);
    }

    public Optional<PositionRiskTarget> riskTargetForPositionEvent(long userId, String symbol, long instrumentVersion) {
        return riskTargetForPositionEvent(userId, symbol, MarginMode.CROSS, instrumentVersion);
    }

    public boolean hasOpenPositions(RiskGroupKey key) {
        List<Object> args = new ArrayList<>(List.of(key.userId(), key.accountType(), key.settleAsset()));
        String productLineFilter = productLineFilter("i", args);
        return jdbcTemplate.query("""
                SELECT 1
                 FROM account_positions p
                  JOIN instruments i ON i.symbol = p.symbol AND i.version = p.instrument_version
                 WHERE p.user_id = ?
                   AND %s = ?
                   AND i.settle_asset = ?
                   %s
                   AND p.signed_quantity_steps <> 0
                 LIMIT 1
                """.formatted(accountTypeExpression("i"), productLineFilter), (rs, rowNum) -> rs.getInt(1), args.toArray())
                .stream().findFirst().isPresent();
    }

    public List<CalculatedPositionRisk> calculatePositions(Duration maxMarkAge) {
        List<Object> args = new ArrayList<>();
        String sql = """
                WITH position_inputs AS (
                    SELECT p.user_id,
                           p.symbol,
                           p.margin_mode,
                           p.position_side,
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
                           COALESCE(position_margin.margin_units, 0) AS position_margin_units,
                           CASE
                               WHEN i.contract_type IN ('INVERSE_PERPETUAL', 'INVERSE_DELIVERY') THEN
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
                      LEFT JOIN LATERAL (
                          SELECT COALESCE(SUM(m.margin_units), 0) AS margin_units
                            FROM account_position_margins m
                           WHERE m.user_id = p.user_id
                             AND m.symbol = p.symbol
                             AND m.asset = i.settle_asset
                             AND m.margin_mode = p.margin_mode
                             AND m.position_side = p.position_side
                      ) position_margin ON TRUE
                     WHERE p.signed_quantity_steps <> 0
                       AND pm.event_time >= now() - (? * INTERVAL '1 millisecond')
                       %s
                )
                SELECT pi.user_id,
                       pi.symbol,
                       pi.margin_mode,
                       pi.position_side,
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
                       pi.mark_price_ticks,
                       pi.position_margin_units
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
                """.formatted(productLineFilter("i", args));
        args.add(0, maxMarkAge.toMillis());
        return queryCalculatedPositions(sql, args.toArray());
    }

    public List<CalculatedPositionRisk> calculatePositions(RiskGroupKey key, Duration maxMarkAge) {
        List<Object> groupArgs = new ArrayList<>();
        List<Object> positionArgs = new ArrayList<>();
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
                       AND %s = ?
                       AND i.settle_asset = ?
                       %s
                ),
                position_inputs AS (
                    SELECT p.user_id,
                           p.symbol,
                           p.margin_mode,
                           p.position_side,
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
                           COALESCE(position_margin.margin_units, 0) AS position_margin_units,
                           CASE
                               WHEN i.contract_type IN ('INVERSE_PERPETUAL', 'INVERSE_DELIVERY') THEN
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
                      LEFT JOIN LATERAL (
                          SELECT COALESCE(SUM(m.margin_units), 0) AS margin_units
                            FROM account_position_margins m
                           WHERE m.user_id = p.user_id
                             AND m.symbol = p.symbol
                             AND m.asset = i.settle_asset
                             AND m.margin_mode = p.margin_mode
                             AND m.position_side = p.position_side
                      ) position_margin ON TRUE
                      CROSS JOIN group_freshness gf
                     WHERE p.signed_quantity_steps <> 0
                       AND p.user_id = ?
                       AND %s = ?
                       AND i.settle_asset = ?
                       AND gf.all_marks_fresh
                       AND pm.event_time >= now() - (? * INTERVAL '1 millisecond')
                       %s
                )
                SELECT pi.user_id,
                       pi.symbol,
                       pi.margin_mode,
                       pi.position_side,
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
                       pi.mark_price_ticks,
                       pi.position_margin_units
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
                """.formatted(accountTypeExpression("i"), productLineFilter("i", groupArgs),
                accountTypeExpression("i"), productLineFilter("i", positionArgs));
        List<Object> args = new ArrayList<>();
        args.add(maxMarkAge.toMillis());
        args.add(key.userId());
        args.add(key.accountType());
        args.add(key.settleAsset());
        args.addAll(groupArgs);
        args.add(key.userId());
        args.add(key.accountType());
        args.add(key.settleAsset());
        args.add(maxMarkAge.toMillis());
        args.addAll(positionArgs);
        return queryCalculatedPositions(sql, args.toArray());
    }

    public boolean acquireScanLease(RiskGroupKey key, String ownerId, Duration leaseDuration) {
        Instant now = Instant.now();
        Instant leaseUntil = now.plus(leaseDuration);
        return !jdbcTemplate.query("""
                INSERT INTO risk_scan_leases (user_id, account_type, settle_asset, owner_id, lease_until, updated_at)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (user_id, account_type, settle_asset) DO UPDATE SET
                    owner_id = EXCLUDED.owner_id,
                    lease_until = EXCLUDED.lease_until,
                    updated_at = EXCLUDED.updated_at
                WHERE risk_scan_leases.owner_id = EXCLUDED.owner_id
                   OR risk_scan_leases.lease_until <= EXCLUDED.updated_at
                RETURNING owner_id
                """, (rs, rowNum) -> rs.getString("owner_id"),
                key.userId(), key.accountType(), key.settleAsset(), ownerId, Timestamp.from(leaseUntil),
                Timestamp.from(now)).isEmpty();
    }

    public void savePositionSnapshot(long snapshotId,
                                     CalculatedPositionRisk position,
                                     long marginRatioPpm,
                                     RiskStatus status,
                                     Instant now) {
        int rows = jdbcTemplate.update("""
                INSERT INTO risk_position_snapshots (
                    snapshot_id, user_id, symbol, margin_mode, position_side, instrument_version, settle_asset, signed_quantity_steps,
                    entry_price_ticks, mark_price_ticks, notional_units, unrealized_pnl_units,
                    maintenance_margin_units, position_margin_units, margin_ratio_ppm, status, event_time, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
                """, snapshotId, position.userId(), position.symbol(), position.marginMode().name(),
                position.positionSide().name(), position.instrumentVersion(), position.settleAsset(),
                position.signedQuantitySteps(), position.entryPriceTicks(), position.markPriceTicks(),
                position.notionalUnits(), position.unrealizedPnlUnits(), position.maintenanceMarginUnits(),
                position.positionMarginUnits(), marginRatioPpm, status.name(), Timestamp.from(now));
        requireSingleRow(rows, "risk position snapshot insert");
    }

    public void saveAccountSnapshot(RiskAccountSnapshotResponse snapshot) {
        int rows = jdbcTemplate.update("""
                INSERT INTO risk_account_snapshots (
                    snapshot_id, user_id, account_type, settle_asset, wallet_balance_units,
                    unrealized_pnl_units, equity_units, maintenance_margin_units,
                    margin_ratio_ppm, status, event_time, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
                """, snapshot.snapshotId(), snapshot.userId(), snapshot.accountType(), snapshot.settleAsset(),
                snapshot.walletBalanceUnits(), snapshot.unrealizedPnlUnits(), snapshot.equityUnits(),
                snapshot.maintenanceMarginUnits(), snapshot.marginRatioPpm(), snapshot.status().name(),
                Timestamp.from(snapshot.eventTime()));
        requireSingleRow(rows, "risk account snapshot insert");
    }

    public long walletBalanceUnits(long userId, String settleAsset) {
        return walletBalanceUnits(userId, DEFAULT_ACCOUNT_TYPE, settleAsset);
    }

    public long walletBalanceUnits(long userId, String accountType, String settleAsset) {
        String normalizedAccountType = normalizeAccountType(accountType);
        return jdbcTemplate.query("""
                WITH account_context AS (
                    SELECT ? AS account_type
                ),
                isolated_position_locks AS (
                    SELECT COALESCE(SUM(m.margin_units), 0) AS units
                      FROM account_position_margins m
                      JOIN account_positions p
                        ON p.user_id = m.user_id
                       AND p.symbol = m.symbol
                       AND p.margin_mode = m.margin_mode
                       AND p.position_side = m.position_side
                      JOIN instruments i
                        ON i.symbol = p.symbol
                       AND i.version = p.instrument_version
                     CROSS JOIN account_context ctx
                     WHERE m.user_id = ?
                       AND m.asset = ?
                       AND m.margin_mode = 'ISOLATED'
                       AND %s = ctx.account_type
                ),
                isolated_order_locks AS (
                    SELECT COALESCE(SUM(GREATEST(r.reserved_units - r.released_units - r.position_margin_units, 0)), 0)
                           AS units
                      FROM account_margin_reservations r
                      CROSS JOIN account_context ctx
                     WHERE r.user_id = ?
                       AND r.asset = ?
                       AND r.account_type = ctx.account_type
                       AND r.margin_mode = 'ISOLATED'
                       AND r.status IN ('ACTIVE', 'PARTIALLY_RELEASED', 'PARTIALLY_CONSUMED')
                )
                SELECT CASE
                           WHEN ctx.account_type = 'USDT_PERPETUAL' THEN COALESCE(
                               b.available_units + b.locked_units
                               - isolated_position_locks.units
                               - isolated_order_locks.units
                               - COALESCE(d.deficit_units, 0), 0)
                           ELSE COALESCE(
                               pb.available_units + pb.locked_units
                               - isolated_position_locks.units
                               - isolated_order_locks.units
                               - COALESCE(pd.deficit_units, 0), 0)
                       END
                  FROM account_context ctx
                  CROSS JOIN isolated_position_locks
                  CROSS JOIN isolated_order_locks
                  LEFT JOIN account_balances b
                    ON b.user_id = ?
                   AND b.asset = ?
                  LEFT JOIN account_deficits d
                    ON d.user_id = b.user_id
                   AND d.asset = b.asset
                  LEFT JOIN account_product_balances pb
                    ON pb.account_type = ctx.account_type
                   AND pb.user_id = ?
                   AND pb.asset = ?
                  LEFT JOIN account_product_deficits pd
                    ON pd.account_type = pb.account_type
                   AND pd.user_id = pb.user_id
                   AND pd.asset = pb.asset
                """.formatted(accountTypeExpression("i")), (rs, rowNum) -> rs.getLong(1),
                normalizedAccountType, userId, settleAsset, userId, settleAsset,
                userId, settleAsset, userId, settleAsset)
                .stream()
                .findFirst()
                .orElse(0L);
    }

    public Optional<RiskAccountSnapshotResponse> latestAccount(long userId, String settleAsset) {
        return latestAccount(userId, DEFAULT_ACCOUNT_TYPE, settleAsset);
    }

    public Optional<RiskAccountSnapshotResponse> latestAccount(long userId, String accountType, String settleAsset) {
        return jdbcTemplate.query("""
                SELECT *
                  FROM risk_account_snapshots
                 WHERE user_id = ? AND account_type = ? AND settle_asset = ?
                 ORDER BY event_time DESC
                 LIMIT 1
                """, (rs, rowNum) -> toAccountSnapshot(rs), userId, normalizeAccountType(accountType),
                settleAsset).stream().findFirst();
    }

    public List<RiskPositionSnapshotResponse> latestPositions(long userId) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("""
                SELECT DISTINCT ON (s.symbol, s.margin_mode, s.position_side) s.*
                  FROM risk_position_snapshots s
                """);
        if (properties.getKafka().isProductTopicsEnabled()) {
            sql.append("""
                  JOIN instruments i ON i.symbol = s.symbol AND i.version = s.instrument_version
                """);
        }
        sql.append("""
                 WHERE s.user_id = ?
                """);
        args.add(userId);
        if (properties.getKafka().isProductTopicsEnabled()) {
            sql.append("                   ")
                    .append(productLineFilter("i", args))
                    .append("\n");
        }
        sql.append("""
                 ORDER BY s.symbol ASC, s.margin_mode ASC, s.position_side ASC, s.event_time DESC
                """);
        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> toPositionSnapshot(rs), args.toArray());
    }

    public long createLiquidationCandidate(RiskAccountSnapshotResponse account,
                                           CalculatedPositionRisk position,
                                           RiskStatus positionStatus,
                                           long positionMarginRatioPpm,
                                           long equityUnits,
                                           long candidateId,
                                           Instant now) {
        int rows = jdbcTemplate.update("""
                INSERT INTO risk_liquidation_candidates (
                    candidate_id, snapshot_id, user_id, symbol, margin_mode, position_side,
                    instrument_version, account_type, settle_asset,
                    signed_quantity_steps, mark_price_ticks, equity_units,
                    maintenance_margin_units, margin_ratio_ppm, status, event_time, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'NEW', ?, now(), now())
                ON CONFLICT (user_id, symbol, margin_mode, position_side) WHERE status IN ('NEW', 'PROCESSING') DO NOTHING
                """, candidateId, account.snapshotId(), position.userId(), position.symbol(),
                position.marginMode().name(), position.positionSide().name(), position.instrumentVersion(),
                account.accountType(), position.settleAsset(),
                position.signedQuantitySteps(), position.markPriceTicks(), equityUnits,
                position.maintenanceMarginUnits(), Math.max(account.marginRatioPpm(), positionMarginRatioPpm),
                Timestamp.from(now));
        return rows == 1 ? candidateId : 0L;
    }

    public long createLiquidationCandidate(RiskAccountSnapshotResponse account,
                                           CalculatedPositionRisk position,
                                           RiskStatus positionStatus,
                                           long positionMarginRatioPpm,
                                           long candidateId,
                                           Instant now) {
        return createLiquidationCandidate(account, position, positionStatus, positionMarginRatioPpm,
                account.equityUnits(), candidateId, now);
    }

    public Optional<LiquidationCandidateResponse> liquidationCandidate(long candidateId) {
        List<Object> args = new ArrayList<>();
        args.add(candidateId);
        String sql = """
                SELECT c.*
                  FROM risk_liquidation_candidates c
                 WHERE c.candidate_id = ?
                   %s
                """.formatted(productAccountTypeFilter("c", args));
        return jdbcTemplate.query(sql, (rs, rowNum) -> toCandidate(rs), args.toArray()).stream().findFirst();
    }

    public List<LiquidationCandidateResponse> liquidationCandidates(LiquidationCandidateStatus status, int limit) {
        List<Object> args = new ArrayList<>();
        args.add(status.name());
        String sql = """
                SELECT c.*
                  FROM risk_liquidation_candidates c
                 WHERE c.status = ?
                   %s
                 ORDER BY c.event_time ASC
                 LIMIT ?
                """.formatted(productAccountTypeFilter("c", args));
        args.add(limit);
        return jdbcTemplate.query(sql, (rs, rowNum) -> toCandidate(rs), args.toArray());
    }

    public AdminCursorPage.CursorPage<LiquidationCandidateResponse> liquidationCandidatesPage(
            LiquidationCandidateStatus status,
            int limit,
            String cursor,
            String sort) {
        int safeLimit = AdminCursorPage.limit(limit, 1000);
        AdminCursorPage.SortSpec sortSpec = parseCandidateSort(sort);
        AdminCursorPage.Cursor decodedCursor = AdminCursorPage.decodeCursor(cursor);
        List<Object> args = new ArrayList<>();
        args.add(status.name());
        String accountTypeFilter = productAccountTypeFilter("c", args);
        AdminCursorPage.addCursorArgs(args, decodedCursor);
        args.add(safeLimit + 1);
        String sql = ("""
                SELECT c.*
                  FROM risk_liquidation_candidates c
                 WHERE c.status = ?
                   %s
                """ + AdminCursorPage.seekCondition(sortSpec, decodedCursor) + """
                 ORDER BY c.event_time %s, c.candidate_id %s
                 LIMIT ?
                """).formatted(accountTypeFilter, sortSpec.directionSql(), sortSpec.directionSql());
        List<LiquidationCandidateResponse> rows = jdbcTemplate.query(sql, (rs, rowNum) -> toCandidate(rs),
                args.toArray());
        return AdminCursorPage.page(rows, safeLimit, sortSpec, LiquidationCandidateResponse::eventTime,
                LiquidationCandidateResponse::candidateId);
    }

    public List<RiskRuleOverride> riskRuleOverrides() {
        return jdbcTemplate.query("""
                SELECT *
                  FROM risk_admin_rule_overrides
                 ORDER BY rule_type ASC, rule_code ASC
                """, (rs, rowNum) -> toRuleOverride(rs));
    }

    public RiskRuleOverride upsertRiskRuleOverride(String ruleCode,
                                                   String ruleName,
                                                   String ruleType,
                                                   boolean enabled,
                                                   Long warningMarginRatioPpm,
                                                   Long liquidationMarginRatioPpm,
                                                   Long scanDelayMs,
                                                   Integer scanBatchSize,
                                                   String adminUserId,
                                                   String reason,
                                                   Instant now) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO risk_admin_rule_overrides (
                    rule_code, rule_name, rule_type, enabled, warning_margin_ratio_ppm,
                    liquidation_margin_ratio_ppm, scan_delay_ms, scan_batch_size,
                    admin_user_id, reason, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (rule_code) DO UPDATE SET
                    rule_name = EXCLUDED.rule_name,
                    rule_type = EXCLUDED.rule_type,
                    enabled = EXCLUDED.enabled,
                    warning_margin_ratio_ppm = EXCLUDED.warning_margin_ratio_ppm,
                    liquidation_margin_ratio_ppm = EXCLUDED.liquidation_margin_ratio_ppm,
                    scan_delay_ms = EXCLUDED.scan_delay_ms,
                    scan_batch_size = EXCLUDED.scan_batch_size,
                    admin_user_id = EXCLUDED.admin_user_id,
                    reason = EXCLUDED.reason,
                    updated_at = EXCLUDED.updated_at
                RETURNING *
                """, (rs, rowNum) -> toRuleOverride(rs), ruleCode, ruleName, ruleType, enabled,
                warningMarginRatioPpm, liquidationMarginRatioPpm, scanDelayMs, scanBatchSize, adminUserId, reason,
                Timestamp.from(now), Timestamp.from(now));
    }

    public List<HighRiskAccount> highRiskAccounts(long minMarginRatioPpm, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 500));
        List<Object> args = new ArrayList<>();
        String latestAccountFilter = productAccountTypeWhereClause(args);
        String sql = ("""
                WITH latest_accounts AS (
                    SELECT DISTINCT ON (user_id, account_type, settle_asset) *
                      FROM risk_account_snapshots
                     %s
                     ORDER BY user_id ASC, account_type ASC, settle_asset ASC, event_time DESC
                )
                SELECT a.snapshot_id,
                       a.user_id,
                       a.account_type,
                       a.settle_asset,
                       a.wallet_balance_units,
                       a.unrealized_pnl_units,
                       a.equity_units,
                       a.maintenance_margin_units,
                       a.margin_ratio_ppm,
                       a.status,
                       a.event_time,
                       COALESCE(position_stats.position_count, 0) AS position_count,
                       COALESCE(position_stats.risk_position_count, 0) AS risk_position_count,
                       COALESCE(candidate_stats.active_candidate_count, 0) AS active_candidate_count,
                       top_position.symbol AS top_symbol,
                       top_position.margin_mode AS top_margin_mode,
                       top_position.margin_ratio_ppm AS top_position_margin_ratio_ppm,
                       top_position.status AS top_position_status,
                       CASE
                           WHEN a.status = 'LIQUIDATION'
                                OR COALESCE(candidate_stats.active_candidate_count, 0) > 0
                               THEN 'LIQUIDATION'
                           WHEN a.status = 'WARNING'
                                OR a.margin_ratio_ppm >= ?
                                OR COALESCE(position_stats.risk_position_count, 0) > 0
                               THEN 'WARNING'
                           ELSE 'WATCH'
                       END AS risk_level
                  FROM latest_accounts a
                  LEFT JOIN LATERAL (
                      SELECT COUNT(*) AS position_count,
                             COUNT(*) FILTER (
                                 WHERE p.status <> 'NORMAL' OR p.margin_ratio_ppm >= ?
                             ) AS risk_position_count
                        FROM risk_position_snapshots p
                       WHERE p.snapshot_id = a.snapshot_id
                         AND p.user_id = a.user_id
                         AND p.settle_asset = a.settle_asset
                  ) position_stats ON TRUE
                  LEFT JOIN LATERAL (
                      SELECT p.symbol, p.margin_mode, p.margin_ratio_ppm, p.status
                        FROM risk_position_snapshots p
                       WHERE p.snapshot_id = a.snapshot_id
                         AND p.user_id = a.user_id
                         AND p.settle_asset = a.settle_asset
                       ORDER BY p.margin_ratio_ppm DESC, p.symbol ASC, p.margin_mode ASC
                       LIMIT 1
                  ) top_position ON TRUE
                  LEFT JOIN LATERAL (
                      SELECT COUNT(*) AS active_candidate_count
                        FROM risk_liquidation_candidates c
                       WHERE c.user_id = a.user_id
                         AND c.account_type = a.account_type
                         AND c.settle_asset = a.settle_asset
                         AND c.status IN ('NEW', 'PROCESSING')
                  ) candidate_stats ON TRUE
                 WHERE a.status IN ('WARNING', 'LIQUIDATION')
                    OR a.margin_ratio_ppm >= ?
                    OR COALESCE(position_stats.risk_position_count, 0) > 0
                    OR COALESCE(candidate_stats.active_candidate_count, 0) > 0
                 ORDER BY
                       CASE
                           WHEN a.status = 'LIQUIDATION'
                                OR COALESCE(candidate_stats.active_candidate_count, 0) > 0 THEN 0
                           WHEN a.status = 'WARNING'
                                OR a.margin_ratio_ppm >= ?
                                OR COALESCE(position_stats.risk_position_count, 0) > 0 THEN 1
                           ELSE 2
                       END,
                       a.margin_ratio_ppm DESC,
                       a.event_time DESC
                 LIMIT ?
                """).formatted(latestAccountFilter);
        args.add(minMarginRatioPpm);
        args.add(minMarginRatioPpm);
        args.add(minMarginRatioPpm);
        args.add(minMarginRatioPpm);
        args.add(safeLimit);
        return jdbcTemplate.query(sql, (rs, rowNum) -> new HighRiskAccount(
                rs.getLong("snapshot_id"),
                rs.getLong("user_id"),
                rs.getString("account_type"),
                rs.getString("settle_asset"),
                rs.getLong("wallet_balance_units"),
                rs.getLong("unrealized_pnl_units"),
                rs.getLong("equity_units"),
                rs.getLong("maintenance_margin_units"),
                rs.getLong("margin_ratio_ppm"),
                RiskStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("event_time").toInstant(),
                rs.getInt("position_count"),
                rs.getInt("risk_position_count"),
                rs.getInt("active_candidate_count"),
                rs.getString("top_symbol"),
                nullableMarginMode(rs.getString("top_margin_mode")),
                nullableLong(rs, "top_position_margin_ratio_ppm"),
                nullableRiskStatus(rs.getString("top_position_status")),
                rs.getString("risk_level")), args.toArray());
    }

    public AdminCursorPage.CursorPage<HighRiskAccount> highRiskAccountsPage(
            long minMarginRatioPpm,
            int limit,
            String cursor,
            String sort) {
        int safeLimit = AdminCursorPage.limit(limit, 1000);
        AdminCursorPage.SortSpec sortSpec = parseHighRiskAccountSort(sort);
        AdminCursorPage.Cursor decodedCursor = AdminCursorPage.decodeCursor(cursor);
        List<Object> args = new ArrayList<>();
        String latestAccountFilter = productAccountTypeWhereClause(args);
        args.add(minMarginRatioPpm);
        args.add(minMarginRatioPpm);
        args.add(minMarginRatioPpm);
        AdminCursorPage.addCursorArgs(args, decodedCursor);
        args.add(safeLimit + 1);
        String sql = ("""
                WITH latest_accounts AS (
                    SELECT DISTINCT ON (user_id, account_type, settle_asset) *
                      FROM risk_account_snapshots
                     %s
                     ORDER BY user_id ASC, account_type ASC, settle_asset ASC, event_time DESC
                )
                SELECT a.snapshot_id,
                       a.user_id,
                       a.account_type,
                       a.settle_asset,
                       a.wallet_balance_units,
                       a.unrealized_pnl_units,
                       a.equity_units,
                       a.maintenance_margin_units,
                       a.margin_ratio_ppm,
                       a.status,
                       a.event_time,
                       COALESCE(position_stats.position_count, 0) AS position_count,
                       COALESCE(position_stats.risk_position_count, 0) AS risk_position_count,
                       COALESCE(candidate_stats.active_candidate_count, 0) AS active_candidate_count,
                       top_position.symbol AS top_symbol,
                       top_position.margin_mode AS top_margin_mode,
                       top_position.margin_ratio_ppm AS top_position_margin_ratio_ppm,
                       top_position.status AS top_position_status,
                       CASE
                           WHEN a.status = 'LIQUIDATION'
                                OR COALESCE(candidate_stats.active_candidate_count, 0) > 0
                               THEN 'LIQUIDATION'
                           WHEN a.status = 'WARNING'
                                OR a.margin_ratio_ppm >= ?
                                OR COALESCE(position_stats.risk_position_count, 0) > 0
                               THEN 'WARNING'
                           ELSE 'WATCH'
                       END AS risk_level
                  FROM latest_accounts a
                  LEFT JOIN LATERAL (
                      SELECT COUNT(*) AS position_count,
                             COUNT(*) FILTER (
                                 WHERE p.status <> 'NORMAL' OR p.margin_ratio_ppm >= ?
                             ) AS risk_position_count
                        FROM risk_position_snapshots p
                       WHERE p.snapshot_id = a.snapshot_id
                         AND p.user_id = a.user_id
                         AND p.settle_asset = a.settle_asset
                  ) position_stats ON TRUE
                  LEFT JOIN LATERAL (
                      SELECT p.symbol, p.margin_mode, p.margin_ratio_ppm, p.status
                        FROM risk_position_snapshots p
                       WHERE p.snapshot_id = a.snapshot_id
                         AND p.user_id = a.user_id
                         AND p.settle_asset = a.settle_asset
                       ORDER BY p.margin_ratio_ppm DESC, p.symbol ASC, p.margin_mode ASC
                       LIMIT 1
                  ) top_position ON TRUE
                  LEFT JOIN LATERAL (
                      SELECT COUNT(*) AS active_candidate_count
                        FROM risk_liquidation_candidates c
                       WHERE c.user_id = a.user_id
                         AND c.account_type = a.account_type
                         AND c.settle_asset = a.settle_asset
                         AND c.status IN ('NEW', 'PROCESSING')
                  ) candidate_stats ON TRUE
                 WHERE (
                       a.status IN ('WARNING', 'LIQUIDATION')
                    OR a.margin_ratio_ppm >= ?
                    OR COALESCE(position_stats.risk_position_count, 0) > 0
                    OR COALESCE(candidate_stats.active_candidate_count, 0) > 0
                 )
                """ + AdminCursorPage.seekCondition(sortSpec, decodedCursor) + """
                 ORDER BY a.event_time %s, a.snapshot_id %s
                 LIMIT ?
                """).formatted(latestAccountFilter, sortSpec.directionSql(), sortSpec.directionSql());
        List<HighRiskAccount> rows = jdbcTemplate.query(sql, (rs, rowNum) -> new HighRiskAccount(
                rs.getLong("snapshot_id"),
                rs.getLong("user_id"),
                rs.getString("account_type"),
                rs.getString("settle_asset"),
                rs.getLong("wallet_balance_units"),
                rs.getLong("unrealized_pnl_units"),
                rs.getLong("equity_units"),
                rs.getLong("maintenance_margin_units"),
                rs.getLong("margin_ratio_ppm"),
                RiskStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("event_time").toInstant(),
                rs.getInt("position_count"),
                rs.getInt("risk_position_count"),
                rs.getInt("active_candidate_count"),
                rs.getString("top_symbol"),
                nullableMarginMode(rs.getString("top_margin_mode")),
                nullableLong(rs, "top_position_margin_ratio_ppm"),
                nullableRiskStatus(rs.getString("top_position_status")),
                rs.getString("risk_level")), args.toArray());
        return AdminCursorPage.page(rows, safeLimit, sortSpec, HighRiskAccount::eventTime,
                HighRiskAccount::snapshotId);
    }

    private AdminCursorPage.SortSpec parseCandidateSort(String value) {
        AdminCursorPage.SortSpec eventTimeAsc = new AdminCursorPage.SortSpec(
                "eventTime", "event_time", "candidate_id", false);
        AdminCursorPage.SortSpec eventTimeDesc = new AdminCursorPage.SortSpec(
                "eventTime", "event_time", "candidate_id", true);
        return AdminCursorPage.parseSort(value, eventTimeAsc, List.of(eventTimeAsc, eventTimeDesc));
    }

    private AdminCursorPage.SortSpec parseHighRiskAccountSort(String value) {
        AdminCursorPage.SortSpec eventTimeDesc = new AdminCursorPage.SortSpec(
                "eventTime", "a.event_time", "a.snapshot_id", true);
        AdminCursorPage.SortSpec eventTimeAsc = new AdminCursorPage.SortSpec(
                "eventTime", "a.event_time", "a.snapshot_id", false);
        return AdminCursorPage.parseSort(value, eventTimeDesc, List.of(eventTimeDesc, eventTimeAsc));
    }

    private RiskAccountSnapshotResponse toAccountSnapshot(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new RiskAccountSnapshotResponse(
                rs.getLong("snapshot_id"),
                rs.getLong("user_id"),
                rs.getString("account_type"),
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
                rs.getTimestamp("event_time").toInstant());
    }

    private LiquidationCandidateResponse toCandidate(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new LiquidationCandidateResponse(
                rs.getLong("candidate_id"),
                rs.getLong("snapshot_id"),
                rs.getLong("user_id"),
                rs.getString("symbol"),
                MarginMode.fromNullableDbValue(rs.getString("margin_mode")),
                PositionSide.fromNullableDbValue(rs.getString("position_side")),
                rs.getLong("instrument_version"),
                rs.getString("account_type"),
                rs.getString("settle_asset"),
                rs.getLong("signed_quantity_steps"),
                rs.getLong("mark_price_ticks"),
                rs.getLong("equity_units"),
                rs.getLong("maintenance_margin_units"),
                rs.getLong("margin_ratio_ppm"),
                LiquidationCandidateStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("event_time").toInstant());
    }

    private RiskRuleOverride toRuleOverride(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new RiskRuleOverride(
                rs.getString("rule_code"),
                rs.getString("rule_name"),
                rs.getString("rule_type"),
                rs.getBoolean("enabled"),
                nullableLong(rs, "warning_margin_ratio_ppm"),
                nullableLong(rs, "liquidation_margin_ratio_ppm"),
                nullableLong(rs, "scan_delay_ms"),
                nullableInteger(rs, "scan_batch_size"),
                rs.getString("admin_user_id"),
                rs.getString("reason"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private Long nullableLong(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private Integer nullableInteger(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private RiskStatus nullableRiskStatus(String value) {
        return value == null ? null : RiskStatus.valueOf(value);
    }

    private MarginMode nullableMarginMode(String value) {
        return value == null || value.isBlank() ? null : MarginMode.valueOf(value);
    }

    private String normalizeAccountType(String accountType) {
        return accountType == null || accountType.isBlank()
                ? DEFAULT_ACCOUNT_TYPE
                : accountType.trim().toUpperCase();
    }

    private String productLineFilter(String alias, List<Object> args) {
        if (!properties.getKafka().isProductTopicsEnabled()) {
            return "";
        }
        ProductLine productLine = properties.getKafka().getProductLine();
        if (!productLine.isMarginProduct()) {
            return "AND 1 = 0";
        }
        args.add(productLine.contractTypeCode());
        return "AND " + alias + ".contract_type = ?";
    }

    private String productAccountTypeFilter(String alias, List<Object> args) {
        if (!properties.getKafka().isProductTopicsEnabled()) {
            return "";
        }
        ProductLine productLine = properties.getKafka().getProductLine();
        if (!productLine.isMarginProduct()) {
            return "AND 1 = 0";
        }
        args.add(productLine.accountTypeCode());
        String prefix = alias == null || alias.isBlank() ? "" : alias + ".";
        return "AND " + prefix + "account_type = ?";
    }

    private String productAccountTypeWhereClause(List<Object> args) {
        String filter = productAccountTypeFilter(null, args);
        return filter.isBlank() ? "" : "WHERE " + filter.substring("AND ".length());
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
                    MarginMode.fromNullableDbValue(rs.getString("margin_mode")),
                    PositionSide.fromNullableDbValue(rs.getString("position_side")),
                    rs.getLong("instrument_version"),
                    rs.getString("settle_asset"),
                    signedQuantitySteps,
                    entryPriceTicks,
                    markPriceTicks,
                    notionalUnits,
                    unrealizedPnlUnits,
                    maintenanceMarginUnits,
                    rs.getLong("position_margin_units"));
        };
    }

    private static String accountTypeExpression(String instrumentAlias) {
        return ProductLineSql.contractTypeAccountTypeCase(instrumentAlias + ".contract_type");
    }

    private void requireSingleRow(int rows, String operation) {
        if (rows != 1) {
            throw new IllegalStateException("failed to write " + operation);
        }
    }

    public record RiskRuleOverride(String ruleCode,
                                   String ruleName,
                                   String ruleType,
                                   boolean enabled,
                                   Long warningMarginRatioPpm,
                                   Long liquidationMarginRatioPpm,
                                   Long scanDelayMs,
                                   Integer scanBatchSize,
                                   String adminUserId,
                                   String reason,
                                   Instant createdAt,
                                   Instant updatedAt) {
    }

    public record HighRiskAccount(long snapshotId,
                                  long userId,
                                  String accountType,
                                  String settleAsset,
                                  long walletBalanceUnits,
                                  long unrealizedPnlUnits,
                                  long equityUnits,
                                  long maintenanceMarginUnits,
                                  long marginRatioPpm,
                                  RiskStatus status,
                                  Instant eventTime,
                                  int positionCount,
                                  int riskPositionCount,
                                  int activeCandidateCount,
                                  String topSymbol,
                                  MarginMode topMarginMode,
                                  Long topPositionMarginRatioPpm,
                                  RiskStatus topPositionStatus,
                                  String riskLevel) {
    }
}
