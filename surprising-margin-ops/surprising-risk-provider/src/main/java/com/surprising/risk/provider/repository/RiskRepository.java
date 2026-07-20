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
import com.surprising.risk.provider.model.CachedRiskGroup;
import com.surprising.risk.provider.model.CachedRiskPosition;
import com.surprising.risk.provider.model.CalculatedPositionRisk;
import com.surprising.risk.provider.model.RiskInstrumentSpec;
import com.surprising.risk.provider.model.RiskGroupKey;
import com.surprising.risk.provider.model.RiskMaintenanceBracket;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.PositionSide;
import java.sql.Timestamp;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
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

    public List<RiskGroupKey> riskGroups(RiskGroupKey after, int limit) {
        long afterUserId = after == null ? 0L : after.userId();
        String afterAccountType = after == null ? "" : after.accountType();
        String afterSettleAsset = after == null ? "" : after.settleAsset();
        int cappedLimit = Math.max(1, limit);
        List<Object> args = new ArrayList<>();
        String productLineFilter = positionProductLineFilter("p", args);
        String sql = """
                WITH open_groups AS (
                    SELECT p.user_id,
                           %s AS account_type,
                           i.settle_asset
                      FROM account_positions p
                      JOIN instruments i ON i.symbol = p.symbol AND i.version = p.instrument_version
                     WHERE p.signed_quantity_steps <> 0
                       %s
                     GROUP BY p.user_id, %s, i.settle_asset
                )
                SELECT user_id, account_type, settle_asset
                  FROM open_groups
                 WHERE (? = 0
                     OR user_id > ?
                     OR (user_id = ? AND account_type > ?)
                     OR (user_id = ? AND account_type = ? AND settle_asset > ?))
                 ORDER BY user_id ASC, account_type ASC, settle_asset ASC
                 LIMIT ?
                """.formatted(accountTypeExpression("i"), productLineFilter, accountTypeExpression("i"));
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

    public void savePositionSnapshots(List<PositionSnapshotWrite> snapshots) {
        if (snapshots.isEmpty()) {
            return;
        }
        int[] rows = jdbcTemplate.batchUpdate("""
                INSERT INTO risk_position_snapshots (
                    product_line, snapshot_id, user_id, symbol, margin_mode, position_side, instrument_version, settle_asset, signed_quantity_steps,
                    entry_price_ticks, mark_price_ticks, notional_units, unrealized_pnl_units,
                    maintenance_margin_units, position_margin_units, margin_ratio_ppm, status, event_time, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
                """, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(java.sql.PreparedStatement ps, int i) throws java.sql.SQLException {
                PositionSnapshotWrite row = snapshots.get(i);
                CalculatedPositionRisk position = row.position();
                ps.setString(1, currentProductLine().name());
                ps.setLong(2, row.snapshotId());
                ps.setLong(3, position.userId());
                ps.setString(4, position.symbol());
                ps.setString(5, position.marginMode().name());
                ps.setString(6, position.positionSide().name());
                ps.setLong(7, position.instrumentVersion());
                ps.setString(8, position.settleAsset());
                ps.setLong(9, position.signedQuantitySteps());
                ps.setLong(10, position.entryPriceTicks());
                ps.setLong(11, position.markPriceTicks());
                ps.setLong(12, position.notionalUnits());
                ps.setLong(13, position.unrealizedPnlUnits());
                ps.setLong(14, position.maintenanceMarginUnits());
                ps.setLong(15, position.positionMarginUnits());
                ps.setLong(16, row.marginRatioPpm());
                ps.setString(17, row.status().name());
                ps.setTimestamp(18, Timestamp.from(row.eventTime()));
            }

            @Override
            public int getBatchSize() {
                return snapshots.size();
            }
        });
        requireBatch(rows, snapshots.size(), "risk position snapshot insert");
    }

    public void saveAccountSnapshots(List<RiskAccountSnapshotResponse> snapshots) {
        if (snapshots.isEmpty()) {
            return;
        }
        int[] rows = jdbcTemplate.batchUpdate("""
                INSERT INTO risk_account_snapshots (
                    product_line, snapshot_id, user_id, account_type, settle_asset, wallet_balance_units,
                    unrealized_pnl_units, equity_units, maintenance_margin_units,
                    margin_ratio_ppm, status, event_time, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
                """, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(java.sql.PreparedStatement ps, int i) throws java.sql.SQLException {
                RiskAccountSnapshotResponse row = snapshots.get(i);
                ps.setString(1, productLineForAccountType(row.accountType()).name());
                ps.setLong(2, row.snapshotId());
                ps.setLong(3, row.userId());
                ps.setString(4, row.accountType());
                ps.setString(5, row.settleAsset());
                ps.setLong(6, row.walletBalanceUnits());
                ps.setLong(7, row.unrealizedPnlUnits());
                ps.setLong(8, row.equityUnits());
                ps.setLong(9, row.maintenanceMarginUnits());
                ps.setLong(10, row.marginRatioPpm());
                ps.setString(11, row.status().name());
                ps.setTimestamp(12, Timestamp.from(row.eventTime()));
            }

            @Override
            public int getBatchSize() {
                return snapshots.size();
            }
        });
        requireBatch(rows, snapshots.size(), "risk account snapshot insert");
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
                       AND p.product_line = m.product_line
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
                    SELECT COALESCE(SUM(
                               CASE WHEN o.quantity_steps = 0 THEN 0
                                    ELSE o.reserved_units * o.remaining_quantity_steps / o.quantity_steps END
                           ), 0) AS units
                      FROM trading_orders o
                     CROSS JOIN account_context ctx
                     WHERE o.user_id = ?
                       AND o.reservation_asset = ?
                       AND o.reservation_account_type = ctx.account_type
                       AND o.margin_mode = 'ISOLATED'
                       AND o.status IN ('PENDING_RESERVE', 'ACCEPTED', 'PARTIALLY_FILLED', 'CANCEL_REQUESTED')
                       AND o.remaining_quantity_steps > 0
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

    /** Loads the authoritative account inputs used to replace one Redis risk group. */
    public CachedRiskGroup cachedRiskGroup(RiskGroupKey key) {
        List<Object> args = new ArrayList<>();
        args.add(key.userId());
        args.add(key.accountType());
        args.add(key.settleAsset());
        String productLineFilter = positionProductLineFilter("p", args);
        List<CachedRiskPosition> positions = jdbcTemplate.query("""
                SELECT p.symbol,
                       p.margin_mode,
                       p.position_side,
                       p.instrument_version,
                       i.settle_asset,
                       p.signed_quantity_steps,
                       p.entry_price_ticks,
                       COALESCE(m.margin_units, 0) AS position_margin_units
                  FROM account_positions p
                  JOIN instruments i
                    ON i.symbol = p.symbol
                   AND i.version = p.instrument_version
                  LEFT JOIN LATERAL (
                      SELECT COALESCE(SUM(pm.margin_units), 0) AS margin_units
                        FROM account_position_margins pm
                       WHERE pm.product_line = p.product_line
                         AND pm.user_id = p.user_id
                         AND pm.symbol = p.symbol
                         AND pm.margin_mode = p.margin_mode
                         AND pm.position_side = p.position_side
                         AND pm.asset = i.settle_asset
                  ) m ON TRUE
                 WHERE p.user_id = ?
                   AND %s = ?
                   AND i.settle_asset = ?
                   AND p.signed_quantity_steps <> 0
                   %s
                 ORDER BY p.symbol, p.margin_mode, p.position_side
                """.formatted(accountTypeExpression("i"), productLineFilter), (rs, rowNum) ->
                new CachedRiskPosition(
                        rs.getString("symbol"),
                        MarginMode.fromNullableDbValue(rs.getString("margin_mode")),
                        PositionSide.fromNullableDbValue(rs.getString("position_side")),
                        rs.getLong("instrument_version"),
                        rs.getString("settle_asset"),
                        rs.getLong("signed_quantity_steps"),
                        rs.getLong("entry_price_ticks"),
                        rs.getLong("position_margin_units")), args.toArray());
        return new CachedRiskGroup(key,
                walletBalanceUnits(key.userId(), key.accountType(), key.settleAsset()),
                positions,
                Instant.now());
    }

    public Optional<RiskInstrumentSpec> riskInstrumentSpec(String symbol, long version) {
        List<RiskInstrumentSpecRow> rows = jdbcTemplate.query("""
                SELECT i.symbol,
                       i.version,
                       i.contract_type,
                       i.settle_asset,
                       i.notional_multiplier_units,
                       i.price_tick_units,
                       s.scale_units AS settle_scale_units,
                       i.maintenance_margin_rate_ppm,
                       b.notional_floor_units,
                       b.maintenance_margin_rate_ppm AS bracket_maintenance_margin_rate_ppm
                  FROM instruments i
                  JOIN account_asset_scales s ON s.asset = i.settle_asset
                  LEFT JOIN instrument_risk_brackets b
                    ON b.symbol = i.symbol
                   AND b.version = i.version
                 WHERE i.symbol = ?
                   AND i.version = ?
                 ORDER BY b.notional_floor_units ASC
                """, (rs, rowNum) -> new RiskInstrumentSpecRow(
                rs.getString("symbol"),
                rs.getLong("version"),
                ContractType.valueOf(rs.getString("contract_type")),
                rs.getString("settle_asset"),
                rs.getLong("notional_multiplier_units"),
                rs.getLong("price_tick_units"),
                rs.getLong("settle_scale_units"),
                rs.getLong("maintenance_margin_rate_ppm"),
                (Long) rs.getObject("notional_floor_units"),
                (Long) rs.getObject("bracket_maintenance_margin_rate_ppm")), symbol, version);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        RiskInstrumentSpecRow first = rows.getFirst();
        List<RiskMaintenanceBracket> brackets = rows.stream()
                .filter(row -> row.notionalFloorUnits() != null && row.bracketMaintenanceMarginRatePpm() != null)
                .map(row -> new RiskMaintenanceBracket(
                        row.notionalFloorUnits(), row.bracketMaintenanceMarginRatePpm()))
                .toList();
        return Optional.of(new RiskInstrumentSpec(
                first.symbol(), first.version(), first.contractType(), first.settleAsset(),
                first.notionalMultiplierUnits(), first.priceTickUnits(), first.settleScaleUnits(),
                first.baseMaintenanceMarginRatePpm(), brackets));
    }

    public Optional<RiskAccountSnapshotResponse> latestAccount(long userId, String settleAsset) {
        return latestAccount(userId, DEFAULT_ACCOUNT_TYPE, settleAsset);
    }

    public Optional<RiskAccountSnapshotResponse> latestAccount(long userId, String accountType, String settleAsset) {
        return jdbcTemplate.query("""
                SELECT *
                  FROM risk_account_snapshots
                 WHERE product_line = ? AND user_id = ? AND account_type = ? AND settle_asset = ?
                 ORDER BY event_time DESC
                 LIMIT 1
                """, (rs, rowNum) -> toAccountSnapshot(rs), productLineForAccountType(accountType).name(),
                userId, normalizeAccountType(accountType), settleAsset).stream().findFirst();
    }

    public List<RiskPositionSnapshotResponse> latestPositions(long userId) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("""
                SELECT DISTINCT ON (s.symbol, s.margin_mode, s.position_side) s.*
                  FROM risk_position_snapshots s
                """);
        sql.append("""
                 WHERE s.user_id = ?
                """);
        args.add(userId);
        if (properties.getKafka().isProductTopicsEnabled()) {
            sql.append("                   ")
                    .append(productLineColumnFilter("s", args))
                    .append("\n");
        }
        sql.append("""
                 ORDER BY s.symbol ASC, s.margin_mode ASC, s.position_side ASC, s.event_time DESC
                """);
        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> toPositionSnapshot(rs), args.toArray());
    }

    public Set<Long> createLiquidationCandidates(List<LiquidationCandidateWrite> candidates) {
        if (candidates.isEmpty()) {
            return Set.of();
        }
        int[] rows = jdbcTemplate.batchUpdate("""
                INSERT INTO risk_liquidation_candidates (
                    product_line, candidate_id, snapshot_id, user_id, symbol, margin_mode, position_side,
                    instrument_version, account_type, settle_asset,
                    signed_quantity_steps, mark_price_ticks, equity_units,
                    maintenance_margin_units, margin_ratio_ppm, status, event_time, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'NEW', ?, now(), now())
                ON CONFLICT (product_line, user_id, symbol, margin_mode, position_side) WHERE status IN ('NEW', 'PROCESSING') DO NOTHING
                """, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(java.sql.PreparedStatement ps, int i) throws java.sql.SQLException {
                LiquidationCandidateWrite row = candidates.get(i);
                RiskAccountSnapshotResponse account = row.account();
                CalculatedPositionRisk position = row.position();
                ps.setString(1, productLineForAccountType(account.accountType()).name());
                ps.setLong(2, row.candidateId());
                ps.setLong(3, account.snapshotId());
                ps.setLong(4, position.userId());
                ps.setString(5, position.symbol());
                ps.setString(6, position.marginMode().name());
                ps.setString(7, position.positionSide().name());
                ps.setLong(8, position.instrumentVersion());
                ps.setString(9, account.accountType());
                ps.setString(10, position.settleAsset());
                ps.setLong(11, position.signedQuantitySteps());
                ps.setLong(12, position.markPriceTicks());
                ps.setLong(13, row.equityUnits());
                ps.setLong(14, position.maintenanceMarginUnits());
                ps.setLong(15, Math.max(account.marginRatioPpm(), row.positionMarginRatioPpm()));
                ps.setTimestamp(16, Timestamp.from(row.eventTime()));
            }

            @Override
            public int getBatchSize() {
                return candidates.size();
            }
        });
        if (rows.length != candidates.size()) {
            throw new IllegalStateException("risk liquidation candidate batch result size mismatch");
        }
        Set<Long> inserted = new HashSet<>();
        for (int i = 0; i < rows.length; i++) {
            if (rows[i] == 1 || rows[i] == Statement.SUCCESS_NO_INFO) {
                inserted.add(candidates.get(i).candidateId());
            } else if (rows[i] != 0) {
                throw new IllegalStateException("risk liquidation candidate insert failed at batch row " + i);
            }
        }
        return inserted;
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
                         AND p.product_line = a.product_line
                         AND p.settle_asset = a.settle_asset
                  ) position_stats ON TRUE
                  LEFT JOIN LATERAL (
                      SELECT p.symbol, p.margin_mode, p.margin_ratio_ppm, p.status
                        FROM risk_position_snapshots p
                       WHERE p.snapshot_id = a.snapshot_id
                         AND p.user_id = a.user_id
                         AND p.product_line = a.product_line
                         AND p.settle_asset = a.settle_asset
                       ORDER BY p.margin_ratio_ppm DESC, p.symbol ASC, p.margin_mode ASC
                       LIMIT 1
                  ) top_position ON TRUE
                  LEFT JOIN LATERAL (
                      SELECT COUNT(*) AS active_candidate_count
                        FROM risk_liquidation_candidates c
                       WHERE c.user_id = a.user_id
                         AND c.product_line = a.product_line
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
                         AND p.product_line = a.product_line
                         AND p.settle_asset = a.settle_asset
                  ) position_stats ON TRUE
                  LEFT JOIN LATERAL (
                      SELECT p.symbol, p.margin_mode, p.margin_ratio_ppm, p.status
                        FROM risk_position_snapshots p
                       WHERE p.snapshot_id = a.snapshot_id
                         AND p.user_id = a.user_id
                         AND p.product_line = a.product_line
                         AND p.settle_asset = a.settle_asset
                       ORDER BY p.margin_ratio_ppm DESC, p.symbol ASC, p.margin_mode ASC
                       LIMIT 1
                  ) top_position ON TRUE
                  LEFT JOIN LATERAL (
                      SELECT COUNT(*) AS active_candidate_count
                        FROM risk_liquidation_candidates c
                       WHERE c.user_id = a.user_id
                         AND c.product_line = a.product_line
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

    private ProductLine currentProductLine() {
        return properties.getKafka().isProductTopicsEnabled()
                ? properties.getKafka().getProductLine()
                : ProductLine.LINEAR_PERPETUAL;
    }

    private ProductLine productLineForAccountType(String accountType) {
        return ProductLine.fromAccountTypeCode(normalizeAccountType(accountType))
                .orElse(currentProductLine());
    }

    private String instrumentProductLineFilter(String alias, List<Object> args) {
        if (!properties.getKafka().isProductTopicsEnabled()) {
            return "";
        }
        ProductLine productLine = currentProductLine();
        if (!productLine.isMarginProduct()) {
            return "AND 1 = 0";
        }
        args.add(productLine.contractTypeCode());
        return "AND " + alias + ".contract_type = ?";
    }

    private String positionProductLineFilter(String alias, List<Object> args) {
        if (!properties.getKafka().isProductTopicsEnabled()) {
            return "";
        }
        return productLineColumnFilter(alias, args);
    }

    private String productLineColumnFilter(String alias, List<Object> args) {
        ProductLine productLine = currentProductLine();
        if (!productLine.isMarginProduct()) {
            return "AND 1 = 0";
        }
        args.add(productLine.name());
        String prefix = alias == null || alias.isBlank() ? "" : alias + ".";
        return "AND " + prefix + "product_line = ?";
    }

    private String productAccountTypeFilter(String alias, List<Object> args) {
        if (!properties.getKafka().isProductTopicsEnabled()) {
            return "";
        }
        ProductLine productLine = currentProductLine();
        if (!productLine.isMarginProduct()) {
            return "AND 1 = 0";
        }
        args.add(productLine.name());
        args.add(productLine.accountTypeCode());
        String prefix = alias == null || alias.isBlank() ? "" : alias + ".";
        return "AND " + prefix + "product_line = ? AND " + prefix + "account_type = ?";
    }

    private String productAccountTypeWhereClause(List<Object> args) {
        String filter = productAccountTypeFilter(null, args);
        return filter.isBlank() ? "" : "WHERE " + filter.substring("AND ".length());
    }

    private static String accountTypeExpression(String instrumentAlias) {
        return ProductLineSql.contractTypeAccountTypeCase(instrumentAlias + ".contract_type");
    }

    private void requireSingleRow(int rows, String operation) {
        if (rows != 1) {
            throw new IllegalStateException("failed to write " + operation);
        }
    }

    private void requireBatch(int[] rows, int expectedSize, String operation) {
        if (rows.length != expectedSize) {
            throw new IllegalStateException("failed to write " + operation + " batch");
        }
        for (int row : rows) {
            if (row != 1 && row != Statement.SUCCESS_NO_INFO) {
                throw new IllegalStateException("failed to write " + operation);
            }
        }
    }

    public record PositionSnapshotWrite(long snapshotId,
                                        CalculatedPositionRisk position,
                                        long marginRatioPpm,
                                        RiskStatus status,
                                        Instant eventTime) {
    }

    public record LiquidationCandidateWrite(long candidateId,
                                            RiskAccountSnapshotResponse account,
                                            CalculatedPositionRisk position,
                                            long positionMarginRatioPpm,
                                            long equityUnits,
                                            Instant eventTime) {
    }

    private record RiskInstrumentSpecRow(
            String symbol,
            long version,
            ContractType contractType,
            String settleAsset,
            long notionalMultiplierUnits,
            long priceTickUnits,
            long settleScaleUnits,
            long baseMaintenanceMarginRatePpm,
            Long notionalFloorUnits,
            Long bracketMaintenanceMarginRatePpm) {
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
