package com.surprising.liquidation.provider.repository;

import com.surprising.liquidation.api.model.AdminCursorPage;
import com.surprising.instrument.api.math.PerpetualContractMath;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.liquidation.api.model.LiquidationOrderResponse;
import com.surprising.liquidation.api.model.LiquidationOrderStatus;
import com.surprising.liquidation.provider.config.LiquidationProperties;
import com.surprising.liquidation.provider.model.ClaimedCandidate;
import com.surprising.liquidation.provider.model.LiquidationCloseState;
import com.surprising.liquidation.provider.model.LiquidationPricingDecision;
import com.surprising.liquidation.provider.model.LiquidationPricingInput;
import com.surprising.liquidation.provider.model.LiquidationSizingInput;
import com.surprising.price.api.model.MarkPriceEvent;
import com.surprising.price.consumer.LatestMarkPriceCache;
import com.surprising.product.api.ProductLine;
import com.surprising.risk.api.model.RiskStatus;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.PositionSide;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class LiquidationRepository {

    private static final String DEFAULT_ACCOUNT_TYPE = "USDT_PERPETUAL";

    private final JdbcTemplate jdbcTemplate;
    private final LiquidationProperties properties;
    private final LatestMarkPriceCache markPriceCache;

    public LiquidationRepository(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, new LiquidationProperties(), null);
    }

    public LiquidationRepository(JdbcTemplate jdbcTemplate, LiquidationProperties properties) {
        this(jdbcTemplate, properties, null);
    }

    @Autowired
    public LiquidationRepository(JdbcTemplate jdbcTemplate,
                                 LiquidationProperties properties,
                                 LatestMarkPriceCache markPriceCache) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties == null ? new LiquidationProperties() : properties;
        this.markPriceCache = markPriceCache;
    }

    public Optional<ClaimedCandidate> claimCandidate(long candidateId) {
        List<Object> args = new ArrayList<>();
        args.add(candidateId);
        StringBuilder sql = new StringBuilder("""
                UPDATE risk_liquidation_candidates c
                   SET status = 'PROCESSING',
                       updated_at = now()
                """);
        if (properties.getKafka().isProductTopicsEnabled()) {
            sql.append("""
                 WHERE c.candidate_id = ?
                   AND c.status = 'NEW'
                """);
            sql.append(candidateProductLineFilter("c", args)).append('\n');
        } else {
            sql.append("""
                 WHERE c.candidate_id = ?
                   AND c.status = 'NEW'
                """);
        }
        sql.append("""
                RETURNING c.candidate_id, c.snapshot_id, c.user_id, c.symbol, c.margin_mode, c.position_side,
                          c.account_type, c.settle_asset, c.instrument_version, c.signed_quantity_steps,
                          c.mark_price_ticks, c.equity_units, c.maintenance_margin_units, c.margin_ratio_ppm
                """);
        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> new ClaimedCandidate(
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
                rs.getLong("margin_ratio_ppm")), args.toArray()).stream().findFirst();
    }

    public RiskStatus latestRiskStatus(long userId,
                                       String symbol,
                                       MarginMode marginMode,
                                       PositionSide positionSide,
                                       long instrumentVersion,
                                       Duration maxSnapshotAge) {
        return jdbcTemplate.query("""
                SELECT status
                 FROM risk_position_snapshots
                 WHERE user_id = ?
                   AND product_line = ?
                   AND symbol = ?
                   AND margin_mode = ?
                   AND position_side = ?
                   AND instrument_version = ?
                   AND event_time >= now() - (? * INTERVAL '1 millisecond')
                 ORDER BY event_time DESC
                 LIMIT 1
                """, (rs, rowNum) -> RiskStatus.valueOf(rs.getString("status")),
                userId, currentProductLine().name(), symbol, MarginMode.defaultIfNull(marginMode).name(),
                PositionSide.defaultIfNull(positionSide).name(), instrumentVersion,
                Math.max(1L, maxSnapshotAge.toMillis()))
                .stream()
                .findFirst()
                .orElse(RiskStatus.NORMAL);
    }

    public RiskStatus latestRiskStatus(long userId,
                                       String symbol,
                                       MarginMode marginMode,
                                       long instrumentVersion,
                                       Duration maxSnapshotAge) {
        return latestRiskStatus(userId, symbol, marginMode, PositionSide.NET, instrumentVersion, maxSnapshotAge);
    }

    public RiskStatus latestRiskStatus(long userId, String settleAsset, Duration maxSnapshotAge) {
        return latestRiskStatus(userId, DEFAULT_ACCOUNT_TYPE, settleAsset, maxSnapshotAge);
    }

    public RiskStatus latestRiskStatus(long userId,
                                       String accountType,
                                       String settleAsset,
                                       Duration maxSnapshotAge) {
        return jdbcTemplate.query("""
                SELECT status
                 FROM risk_account_snapshots
                 WHERE user_id = ?
                   AND product_line = ?
                   AND account_type = ?
                   AND settle_asset = ?
                   AND event_time >= now() - (? * INTERVAL '1 millisecond')
                 ORDER BY event_time DESC
                 LIMIT 1
                """, (rs, rowNum) -> RiskStatus.valueOf(rs.getString("status")),
                userId, productLineForAccountType(accountType).name(), normalizeAccountType(accountType),
                settleAsset, Math.max(1L, maxSnapshotAge.toMillis()))
                .stream()
                .findFirst()
                .orElse(RiskStatus.NORMAL);
    }

    public Optional<LiquidationCloseState> lockCloseState(long userId,
                                                          String symbol,
                                                          MarginMode marginMode,
                                                          PositionSide positionSide,
                                                          long instrumentVersion) {
        return jdbcTemplate.query("""
                SELECT signed_quantity_steps
                 FROM account_positions
                 WHERE user_id = ? AND product_line = ? AND symbol = ? AND margin_mode = ?
                   AND position_side = ? AND instrument_version = ?
                 FOR UPDATE
                """, (rs, rowNum) -> new LiquidationCloseState(
                rs.getLong("signed_quantity_steps")), userId, currentProductLine().name(), symbol,
                MarginMode.defaultIfNull(marginMode).name(),
                PositionSide.defaultIfNull(positionSide).name(), instrumentVersion).stream().findFirst();
    }

    public Optional<LiquidationCloseState> lockCloseState(long userId,
                                                          String symbol,
                                                          MarginMode marginMode,
                                                          long instrumentVersion) {
        return lockCloseState(userId, symbol, marginMode, PositionSide.NET, instrumentVersion);
    }

    public Optional<LiquidationCloseState> lockCloseState(long userId, String symbol, long instrumentVersion) {
        return lockCloseState(userId, symbol, MarginMode.CROSS, instrumentVersion);
    }

    public long lockOpenReduceOnlySteps(long userId,
                                        String symbol,
                                        MarginMode marginMode,
                                        PositionSide positionSide,
                                        long instrumentVersion,
                                        OrderSide closeSide) {
        return jdbcTemplate.query("""
                SELECT remaining_quantity_steps
                  FROM trading_orders
                 WHERE user_id = ?
                   AND product_line = ?
                   AND symbol = ?
                   AND margin_mode = ?
                   AND position_side = ?
                   AND instrument_version = ?
                   AND side = ?
                   AND reduce_only = TRUE
                   AND status IN ('ACCEPTED', 'PARTIALLY_FILLED', 'CANCEL_REQUESTED')
                FOR UPDATE
                """, (rs, rowNum) -> rs.getLong("remaining_quantity_steps"), userId,
                currentProductLine().name(), symbol, MarginMode.defaultIfNull(marginMode).name(),
                PositionSide.defaultIfNull(positionSide).name(), instrumentVersion, closeSide.name())
                .stream()
                .mapToLong(Long::longValue)
                .reduce(0L, Math::addExact);
    }

    public long lockOpenReduceOnlySteps(long userId,
                                        String symbol,
                                        MarginMode marginMode,
                                        long instrumentVersion,
                                        OrderSide closeSide) {
        return lockOpenReduceOnlySteps(userId, symbol, marginMode, PositionSide.NET, instrumentVersion, closeSide);
    }

    public long lockOpenReduceOnlySteps(long userId, String symbol, long instrumentVersion, OrderSide closeSide) {
        return lockOpenReduceOnlySteps(userId, symbol, MarginMode.CROSS, instrumentVersion, closeSide);
    }

    public Optional<LiquidationSizingInput> sizingInput(long userId,
                                                        String symbol,
                                                        MarginMode marginMode,
                                                        PositionSide positionSide,
                                                        long instrumentVersion,
                                                        long availableCloseSteps) {
        long markPriceTicks = requireMarkPrice(symbol, instrumentVersion).markPriceTicks();
        String sql = """
                SELECT p.symbol,
                       p.instrument_version AS version,
                       i.contract_type,
                       p.signed_quantity_steps,
                       i.notional_multiplier_units,
                       i.price_tick_units,
                       ss.scale_units AS settle_scale_units,
                       ?::BIGINT AS mark_price_ticks
                  FROM account_positions p
                  JOIN instruments i ON i.symbol = p.symbol AND i.version = p.instrument_version
                  JOIN account_asset_scales ss ON ss.asset = i.settle_asset
                 WHERE p.user_id = ? AND p.symbol = ? AND p.margin_mode = ? AND p.position_side = ?
                   AND p.instrument_version = ?
                   AND p.product_line = ?
                   AND p.signed_quantity_steps <> 0
                """;
        return jdbcTemplate.query(sql, (rs, rowNum) -> {
                    ContractType contractType = ContractType.valueOf(rs.getString("contract_type"));
                    long signedQuantitySteps = rs.getLong("signed_quantity_steps");
                    long rowMarkPriceTicks = rs.getLong("mark_price_ticks");
                    long notionalMultiplierUnits = rs.getLong("notional_multiplier_units");
                    long priceTickUnits = rs.getLong("price_tick_units");
                    long settleScaleUnits = rs.getLong("settle_scale_units");
                    long notionalUnits = PerpetualContractMath.notionalUnits(contractType, signedQuantitySteps,
                            rowMarkPriceTicks, notionalMultiplierUnits, priceTickUnits, settleScaleUnits);
                    long notionalPerStepUnits = Math.max(1L, PerpetualContractMath.notionalPerStepUnits(contractType,
                            rowMarkPriceTicks, notionalMultiplierUnits, priceTickUnits, settleScaleUnits));
                    return new SizingRow(
                            rs.getString("symbol"),
                            rs.getLong("version"),
                            Math.absExact(signedQuantitySteps),
                            notionalUnits,
                            notionalPerStepUnits);
                }, markPriceTicks, userId, symbol, MarginMode.defaultIfNull(marginMode).name(),
                PositionSide.defaultIfNull(positionSide).name(), instrumentVersion, currentProductLine().name())
                .stream()
                .findFirst()
                .map(row -> new LiquidationSizingInput(
                        row.positionAbsSteps(),
                        availableCloseSteps,
                        row.notionalUnits(),
                        row.notionalPerStepUnits(),
                        bracketFloorNotionalUnits(row.symbol(), row.version(), row.notionalUnits())));
    }

    public Optional<LiquidationSizingInput> sizingInput(long userId,
                                                        String symbol,
                                                        MarginMode marginMode,
                                                        long instrumentVersion,
                                                        long availableCloseSteps) {
        return sizingInput(userId, symbol, marginMode, PositionSide.NET, instrumentVersion, availableCloseSteps);
    }

    public Optional<LiquidationPricingInput> latestPricingInput(long userId,
                                                                String symbol,
                                                                MarginMode marginMode,
                                                                PositionSide positionSide,
                                                                long instrumentVersion,
                                                                Duration maxSnapshotAge) {
        long markPriceTicks = requireMarkPrice(symbol, instrumentVersion).markPriceTicks();
        return jdbcTemplate.query("""
                SELECT ps.signed_quantity_steps,
                       ?::BIGINT AS mark_price_ticks,
                       CASE
                           WHEN ps.margin_mode = 'ISOLATED'
                               THEN ps.position_margin_units + ps.unrealized_pnl_units
                           ELSE acc.equity_units
                       END AS equity_units,
                       ps.maintenance_margin_units,
                       i.contract_type,
                       i.notional_multiplier_units,
                       i.price_tick_units,
                       ss.scale_units AS settle_scale_units
                  FROM risk_position_snapshots ps
                  JOIN risk_account_snapshots acc
                    ON acc.snapshot_id = ps.snapshot_id
                   AND acc.user_id = ps.user_id
                   AND acc.product_line = ps.product_line
                   AND acc.settle_asset = ps.settle_asset
                  JOIN instruments i
                    ON i.symbol = ps.symbol
                   AND i.version = ps.instrument_version
                  JOIN account_asset_scales ss
                    ON ss.asset = i.settle_asset
                 WHERE ps.user_id = ?
                   AND ps.product_line = ?
                   AND ps.symbol = ?
                   AND ps.margin_mode = ?
                   AND ps.position_side = ?
                   AND ps.instrument_version = ?
                   AND ps.event_time >= now() - (? * INTERVAL '1 millisecond')
                   AND ps.signed_quantity_steps <> 0
                 ORDER BY ps.event_time DESC
                 LIMIT 1
                """, (rs, rowNum) -> new LiquidationPricingInput(
                ContractType.valueOf(rs.getString("contract_type")),
                rs.getLong("signed_quantity_steps"),
                rs.getLong("mark_price_ticks"),
                rs.getLong("equity_units"),
                rs.getLong("maintenance_margin_units"),
                rs.getLong("notional_multiplier_units"),
                rs.getLong("price_tick_units"),
                rs.getLong("settle_scale_units")), markPriceTicks, userId, currentProductLine().name(), symbol,
                MarginMode.defaultIfNull(marginMode).name(), PositionSide.defaultIfNull(positionSide).name(),
                instrumentVersion,
                Math.max(1L, maxSnapshotAge.toMillis())).stream().findFirst();
    }

    public Optional<LiquidationPricingInput> latestPricingInput(long userId,
                                                                String symbol,
                                                                MarginMode marginMode,
                                                                long instrumentVersion,
                                                                Duration maxSnapshotAge) {
        return latestPricingInput(userId, symbol, marginMode, PositionSide.NET, instrumentVersion, maxSnapshotAge);
    }

    public Optional<LiquidationSizingInput> sizingInput(long userId,
                                                        String symbol,
                                                        long instrumentVersion,
                                                        long availableCloseSteps) {
        return sizingInput(userId, symbol, MarginMode.CROSS, instrumentVersion, availableCloseSteps);
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

    private MarkPriceEvent requireMarkPrice(String symbol, long instrumentVersion) {
        if (markPriceCache == null) {
            throw new IllegalStateException("mark price cache is not configured");
        }
        MarkPriceEvent markPrice = markPriceCache.fresh(symbol, properties.getRisk().getMaxSnapshotAge())
                .orElseThrow(() -> new IllegalStateException("fresh mark price not found for " + symbol));
        if (markPrice.instrumentVersion() != instrumentVersion) {
            throw new IllegalStateException("mark price instrument version mismatch for " + symbol
                    + ": expected=" + instrumentVersion + ", actual=" + markPrice.instrumentVersion());
        }
        return markPrice;
    }

    public void markCandidate(long candidateId, String status) {
        List<Object> args = new ArrayList<>();
        args.add(status);
        args.add(candidateId);
        StringBuilder sql = new StringBuilder("""
                UPDATE risk_liquidation_candidates c
                   SET status = ?,
                       updated_at = now()
                """);
        sql.append("""
                 WHERE c.candidate_id = ?
                """);
        appendCandidateProductLineFilter(sql, "c", args);
        int rows = jdbcTemplate.update(sql.toString(), args.toArray());
        requireSingleRow(rows, "liquidation candidate status update");
    }

    public Optional<Long> updateOrderLifecycle(long orderId,
                                               LiquidationOrderStatus orderStatus,
                                               String candidateStatus) {
        List<Object> orderArgs = new ArrayList<>();
        orderArgs.add(orderStatus.name());
        orderArgs.add(orderId);
        StringBuilder orderSql = new StringBuilder("""
                UPDATE liquidation_orders lo
                   SET status = ?
                """);
        appendOrderCandidateUpdateScope(orderSql, orderArgs);
        orderSql.append("""
                 WHERE lo.order_id = ?
                   AND lo.status IN ('SUBMITTED', 'PARTIALLY_FILLED')
                """);
        appendOrderCandidateUpdateProductLineFilter(orderSql, orderArgs);
        orderSql.append("""
                RETURNING lo.candidate_id
                """);
        List<Long> candidateIds = jdbcTemplate.query(orderSql.toString(),
                (rs, rowNum) -> rs.getLong("candidate_id"), orderArgs.toArray());
        if (candidateIds.isEmpty()) {
            return Optional.empty();
        }
        long candidateId = candidateIds.get(0);
        List<Object> candidateArgs = new ArrayList<>();
        candidateArgs.add(candidateStatus);
        candidateArgs.add(candidateId);
        StringBuilder candidateSql = new StringBuilder("""
                UPDATE risk_liquidation_candidates c
                   SET status = ?,
                       updated_at = now()
                """);
        candidateSql.append("""
                 WHERE c.candidate_id = ?
                   AND c.status = 'PROCESSING'
                """);
        appendCandidateProductLineFilter(candidateSql, "c", candidateArgs);
        int rows = jdbcTemplate.update(candidateSql.toString(), candidateArgs.toArray());
        requireSingleRow(rows, "liquidation candidate lifecycle update");
        return Optional.of(candidateId);
    }

    public boolean insertLiquidationOrder(long liquidationOrderId,
                                          long candidateId,
                                          long orderId,
                                          long userId,
                                          String symbol,
                                          MarginMode marginMode,
                                          PositionSide positionSide,
                                          OrderSide side,
                                          long quantitySteps,
                                          LiquidationOrderStatus status,
                                          String reason,
                                          LiquidationPricingDecision pricing,
                                          Instant now) {
        LiquidationPricingDecision auditPricing = pricing == null ? LiquidationPricingDecision.empty() : pricing;
        int rows = jdbcTemplate.update("""
                INSERT INTO liquidation_orders (
                    liquidation_order_id, candidate_id, order_id, user_id, symbol,
                    margin_mode, position_side, side, quantity_steps, status, reason,
                    bankruptcy_price_ticks, takeover_price_ticks, liquidation_fee_rate_ppm,
                    liquidation_fee_units, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (candidate_id) DO NOTHING
                """, liquidationOrderId, candidateId, orderId, userId, symbol,
                MarginMode.defaultIfNull(marginMode).name(), PositionSide.defaultIfNull(positionSide).name(), side.name(),
                quantitySteps, status.name(), reason, auditPricing.bankruptcyPriceTicks(),
                auditPricing.takeoverPriceTicks(), auditPricing.liquidationFeeRatePpm(),
                auditPricing.liquidationFeeUnits(), Timestamp.from(now));
        return rows == 1;
    }

    public boolean insertLiquidationOrder(long liquidationOrderId,
                                          long candidateId,
                                          long orderId,
                                          long userId,
                                          String symbol,
                                          MarginMode marginMode,
                                          OrderSide side,
                                          long quantitySteps,
                                          LiquidationOrderStatus status,
                                          String reason,
                                          LiquidationPricingDecision pricing,
                                          Instant now) {
        return insertLiquidationOrder(liquidationOrderId, candidateId, orderId, userId, symbol, marginMode,
                PositionSide.NET, side, quantitySteps, status, reason, pricing, now);
    }

    public boolean insertLiquidationOrder(long liquidationOrderId,
                                          long candidateId,
                                          long orderId,
                                          long userId,
                                          String symbol,
                                          MarginMode marginMode,
                                          OrderSide side,
                                          long quantitySteps,
                                          LiquidationOrderStatus status,
                                          String reason,
                                          Instant now) {
        return insertLiquidationOrder(liquidationOrderId, candidateId, orderId, userId, symbol, marginMode, side,
                quantitySteps, status, reason, LiquidationPricingDecision.empty(), now);
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
        return insertLiquidationOrder(liquidationOrderId, candidateId, orderId, userId, symbol, MarginMode.CROSS,
                side, quantitySteps, status, reason, LiquidationPricingDecision.empty(), now);
    }

    public List<LiquidationOrderResponse> orders(Long userId, int limit) {
        List<Object> args = new ArrayList<>();
        args.add(userId);
        args.add(userId);
        StringBuilder sql = new StringBuilder("""
                SELECT lo.*
                  FROM liquidation_orders lo
                """);
        appendOrderCandidateScope(sql, args);
        sql.append("""
                 WHERE (CAST(? AS text) IS NULL OR lo.user_id = ?)
                """);
        appendCandidateProductLineFilter(sql, "c", args);
        sql.append("""
                 ORDER BY lo.created_at DESC
                 LIMIT ?
                """);
        args.add(limit);
        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> toOrder(rs), args.toArray());
    }

    public AdminCursorPage.CursorPage<LiquidationOrderResponse> ordersPage(
            Long userId,
            int limit,
            String cursor,
            String sort) {
        int safeLimit = AdminCursorPage.limit(limit, 1000);
        AdminCursorPage.SortSpec sortSpec = parseCreatedAtSort(sort);
        AdminCursorPage.Cursor decodedCursor = AdminCursorPage.decodeCursor(cursor);
        List<Object> args = new ArrayList<>();
        args.add(userId);
        args.add(userId);
        StringBuilder sql = new StringBuilder("""
                SELECT lo.*
                  FROM liquidation_orders lo
                """);
        appendOrderCandidateScope(sql, args);
        sql.append("""
                 WHERE (CAST(? AS text) IS NULL OR lo.user_id = ?)
                """);
        appendCandidateProductLineFilter(sql, "c", args);
        sql.append(AdminCursorPage.seekCondition(sortSpec, decodedCursor));
        AdminCursorPage.addCursorArgs(args, decodedCursor);
        sql.append("""
                 ORDER BY lo.created_at %s, lo.liquidation_order_id %s
                 LIMIT ?
                """.formatted(sortSpec.directionSql(), sortSpec.directionSql()));
        args.add(safeLimit + 1);
        List<LiquidationOrderResponse> rows = jdbcTemplate.query(sql.toString(), (rs, rowNum) -> toOrder(rs),
                args.toArray());
        return AdminCursorPage.page(rows, safeLimit, sortSpec, LiquidationOrderResponse::createdAt,
                LiquidationOrderResponse::liquidationOrderId);
    }

    public List<LiquidationOrderResponse> ordersByCandidate(long candidateId) {
        List<Object> args = new ArrayList<>();
        args.add(candidateId);
        StringBuilder sql = new StringBuilder("""
                SELECT lo.*
                  FROM liquidation_orders lo
                """);
        appendOrderCandidateScope(sql, args);
        sql.append("""
                 WHERE lo.candidate_id = ?
                """);
        appendCandidateProductLineFilter(sql, "c", args);
        sql.append("""
                 ORDER BY lo.created_at DESC
                """);
        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> toOrder(rs), args.toArray());
    }

    private AdminCursorPage.SortSpec parseCreatedAtSort(String value) {
        AdminCursorPage.SortSpec createdAtDesc = new AdminCursorPage.SortSpec(
                "createdAt", "lo.created_at", "lo.liquidation_order_id", true);
        AdminCursorPage.SortSpec createdAtAsc = new AdminCursorPage.SortSpec(
                "createdAt", "lo.created_at", "lo.liquidation_order_id", false);
        return AdminCursorPage.parseSort(value, createdAtDesc, List.of(createdAtDesc, createdAtAsc));
    }

    public Optional<Map<String, Object>> candidate(long candidateId) {
        List<Object> args = new ArrayList<>();
        args.add(candidateId);
        StringBuilder sql = new StringBuilder("""
                SELECT c.candidate_id, c.snapshot_id, c.user_id, c.symbol, c.margin_mode,
                       c.instrument_version, c.settle_asset, c.signed_quantity_steps,
                       c.mark_price_ticks, c.equity_units, c.maintenance_margin_units,
                       c.margin_ratio_ppm, c.status, c.event_time, c.created_at, c.updated_at,
                       a.status AS account_risk_status
                  FROM risk_liquidation_candidates c
                  LEFT JOIN risk_account_snapshots a ON a.snapshot_id = c.snapshot_id
                """);
        sql.append("""
                 WHERE c.candidate_id = ?
                """);
        appendCandidateProductLineFilter(sql, "c", args);
        return jdbcTemplate.queryForList(sql.toString(), args.toArray()).stream().findFirst().map(this::normalizedRow);
    }

    public List<LiquidationTimelineEvent> timeline(long candidateId, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 500));
        List<LiquidationTimelineEvent> events = new ArrayList<>();
        addRows(events, "risk", "CANDIDATE_CREATED", "event_time", "candidate_id", "status", """
                SELECT candidate_id, snapshot_id, user_id, symbol, margin_mode, instrument_version,
                       settle_asset, signed_quantity_steps, mark_price_ticks, equity_units,
                       maintenance_margin_units, margin_ratio_ppm, status, event_time, created_at, updated_at
                  FROM risk_liquidation_candidates
                 WHERE candidate_id = ?
                """, candidateId);
        addRows(events, "risk", "ACCOUNT_SNAPSHOT", "event_time", "snapshot_id", "status", """
                SELECT a.snapshot_id, a.user_id, a.settle_asset, a.wallet_balance_units, a.unrealized_pnl_units,
                       a.equity_units, a.maintenance_margin_units, a.margin_ratio_ppm, a.status,
                       a.event_time, a.created_at
                  FROM risk_account_snapshots a
                  JOIN risk_liquidation_candidates c ON c.snapshot_id = a.snapshot_id
                 WHERE c.candidate_id = ?
                """, candidateId);
        addRows(events, "risk", "POSITION_SNAPSHOT", "event_time", "symbol", "status", """
                SELECT p.snapshot_id, p.user_id, p.symbol, p.margin_mode, p.instrument_version,
                       p.signed_quantity_steps, p.entry_price_ticks, p.mark_price_ticks,
                       p.unrealized_pnl_units, p.position_margin_units, p.maintenance_margin_units,
                       p.margin_ratio_ppm, p.status, p.event_time, p.created_at
                  FROM risk_position_snapshots p
                  JOIN risk_liquidation_candidates c
                    ON c.snapshot_id = p.snapshot_id
                   AND c.user_id = p.user_id
                   AND c.symbol = p.symbol
                   AND c.margin_mode = p.margin_mode
                   AND c.position_side = p.position_side
                   AND c.product_line = p.product_line
                 WHERE c.candidate_id = ?
                """, candidateId);
        addRows(events, "liquidation", "LIQUIDATION_AUDIT", "created_at", "liquidation_order_id", "status", """
                SELECT liquidation_order_id, candidate_id, order_id, user_id, symbol, margin_mode, side,
                       quantity_steps, status, reason, bankruptcy_price_ticks, takeover_price_ticks,
                       liquidation_fee_rate_ppm, liquidation_fee_units, created_at
                  FROM liquidation_orders
                 WHERE candidate_id = ?
                """, candidateId);
        addRows(events, "liquidation", "ADMIN_ACTION", "created_at", "action_id", "action_type", """
                SELECT action_id, candidate_id, action_type, admin_user_id, reason, created_at
                  FROM liquidation_admin_actions
                 WHERE candidate_id = ?
                """, candidateId);
        addRows(events, "trading", "ORDER_STATE", "updated_at", "order_id", "status", """
                SELECT o.order_id, o.user_id, o.client_order_id, o.symbol, o.instrument_version,
                       o.side, o.order_type, o.time_in_force, o.price_ticks, o.quantity_steps,
                       o.executed_quantity_steps, o.remaining_quantity_steps, o.margin_mode,
                       o.reduce_only, o.post_only, o.status, o.reject_reason, o.created_at, o.updated_at
                  FROM trading_orders o
                 WHERE o.order_id IN (
                       SELECT order_id FROM liquidation_orders WHERE candidate_id = ? AND order_id > 0
                 )
                """, candidateId);
        addRows(events, "trading", "ORDER_EVENT", "event_time", "event_id", "event_type", """
                SELECT e.event_id, e.order_id, e.user_id, e.symbol, e.event_type, e.status,
                       e.reason, e.trace_id, e.event_time, e.created_at
                  FROM trading_order_events e
                 WHERE e.order_id IN (
                       SELECT order_id FROM liquidation_orders WHERE candidate_id = ? AND order_id > 0
                 )
                """, candidateId);
        addRows(events, "matching", "MATCH_RESULT", "event_time", "command_id", "result_code", """
                SELECT r.command_id, r.order_id, r.user_id, r.symbol, r.instrument_version,
                       r.command_type, r.result_code, r.filled_quantity_steps, r.order_status,
                       r.trace_id, r.event_time, r.created_at
                  FROM trading_match_results r
                 WHERE r.order_id IN (
                       SELECT order_id FROM liquidation_orders WHERE candidate_id = ? AND order_id > 0
                 )
                """, candidateId);
        addRows(events, "matching", "MATCH_TRADE", "event_time", "trade_id", "quantity_steps", """
                SELECT t.trade_id, t.command_id, t.symbol, t.taker_order_id, t.taker_user_id,
                       t.taker_side, t.maker_order_id, t.maker_user_id, t.price_ticks,
                       t.quantity_steps, t.trace_id, t.event_time, t.created_at
                  FROM trading_match_trades t
                 WHERE t.taker_order_id IN (
                       SELECT order_id FROM liquidation_orders WHERE candidate_id = ? AND order_id > 0
                 )
                    OR t.maker_order_id IN (
                       SELECT order_id FROM liquidation_orders WHERE candidate_id = ? AND order_id > 0
                 )
                """, candidateId, candidateId);
        return events.stream()
                .sorted(Comparator.comparing(LiquidationTimelineEvent::eventTime,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .limit(safeLimit)
                .toList();
    }

    public Optional<CanceledCandidate> cancelCandidateIfSafe(long candidateId, Instant now) {
        List<Object> args = new ArrayList<>();
        args.add(Timestamp.from(now));
        args.add(candidateId);
        StringBuilder sql = new StringBuilder("""
                UPDATE risk_liquidation_candidates c
                   SET status = 'CANCELED',
                       updated_at = ?
                """);
        sql.append("""
                 WHERE c.candidate_id = ?
                   AND c.status IN ('NEW', 'PROCESSING')
                   AND NOT EXISTS (
                       SELECT 1
                         FROM liquidation_orders lo
                        WHERE lo.candidate_id = c.candidate_id
                          AND lo.status IN ('SUBMITTED', 'PARTIALLY_FILLED')
                   )
                """);
        appendCandidateProductLineFilter(sql, "c", args);
        sql.append("""
                RETURNING c.candidate_id, c.status, c.updated_at
                """);
        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> new CanceledCandidate(
                rs.getLong("candidate_id"),
                rs.getString("status"),
                rs.getTimestamp("updated_at").toInstant()), args.toArray())
                .stream().findFirst();
    }

    public LiquidationAdminAction insertAdminAction(long candidateId,
                                                    String actionType,
                                                    String adminUserId,
                                                    String reason,
                                                    Instant now) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO liquidation_admin_actions (
                    candidate_id, action_type, admin_user_id, reason, created_at
                ) VALUES (?, ?, ?, ?, ?)
                RETURNING action_id, candidate_id, action_type, admin_user_id, reason, created_at
                """, (rs, rowNum) -> new LiquidationAdminAction(
                rs.getLong("action_id"),
                rs.getLong("candidate_id"),
                rs.getString("action_type"),
                rs.getString("admin_user_id"),
                rs.getString("reason"),
                rs.getTimestamp("created_at").toInstant()), candidateId, actionType, adminUserId, reason,
                Timestamp.from(now));
    }

    private void addRows(List<LiquidationTimelineEvent> events,
                         String source,
                         String eventType,
                         String timeColumn,
                         String subjectColumn,
                         String summaryColumn,
                         String sql,
                         Object... args) {
        for (Map<String, Object> row : jdbcTemplate.queryForList(sql, args)) {
            Map<String, Object> data = normalizedRow(row);
            Object timeValue = row.get(timeColumn);
            Instant eventTime = timeValue instanceof Timestamp timestamp ? timestamp.toInstant() : null;
            String subject = stringValue(row.get(subjectColumn));
            String summary = stringValue(row.get(summaryColumn));
            events.add(new LiquidationTimelineEvent(eventTime, source, eventType, subject, summary, data));
        }
    }

    private Map<String, Object> normalizedRow(Map<String, Object> row) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            Object value = entry.getValue();
            normalized.put(entry.getKey(), value instanceof Timestamp timestamp ? timestamp.toInstant() : value);
        }
        return normalized;
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private LiquidationOrderResponse toOrder(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new LiquidationOrderResponse(
                rs.getLong("liquidation_order_id"),
                rs.getLong("candidate_id"),
                rs.getLong("order_id"),
                rs.getLong("user_id"),
                rs.getString("symbol"),
                MarginMode.fromNullableDbValue(rs.getString("margin_mode")),
                PositionSide.fromNullableDbValue(rs.getString("position_side")),
                OrderSide.valueOf(rs.getString("side")),
                rs.getLong("quantity_steps"),
                rs.getLong("bankruptcy_price_ticks"),
                rs.getLong("takeover_price_ticks"),
                rs.getLong("liquidation_fee_rate_ppm"),
                rs.getLong("liquidation_fee_units"),
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

    public record LiquidationTimelineEvent(Instant eventTime,
                                           String source,
                                           String eventType,
                                           String subject,
                                           String summary,
                                           Map<String, Object> data) {
    }

    public record CanceledCandidate(long candidateId,
                                    String status,
                                    Instant updatedAt) {
    }

    public record LiquidationAdminAction(long actionId,
                                         long candidateId,
                                         String actionType,
                                         String adminUserId,
                                         String reason,
                                         Instant createdAt) {
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

    private String candidateProductLineFilter(String alias, List<Object> args) {
        ProductLine productLine = currentProductLine();
        if (!productLine.isMarginProduct()) {
            return "AND 1 = 0";
        }
        args.add(productLine.name());
        String prefix = alias == null || alias.isBlank() ? "" : alias + ".";
        return "AND " + prefix + "product_line = ?";
    }

    private void appendCandidateProductLineFilter(StringBuilder sql, String alias, List<Object> args) {
        if (properties.getKafka().isProductTopicsEnabled()) {
            sql.append(candidateProductLineFilter(alias, args)).append('\n');
        }
    }

    private void appendOrderCandidateScope(StringBuilder sql, List<Object> args) {
        if (properties.getKafka().isProductTopicsEnabled()) {
            sql.append("""
                  JOIN risk_liquidation_candidates c
                    ON c.candidate_id = lo.candidate_id
                """);
        }
    }

    private void appendOrderCandidateUpdateScope(StringBuilder sql, List<Object> args) {
        if (properties.getKafka().isProductTopicsEnabled()) {
            sql.append("""
                  FROM risk_liquidation_candidates c
                """);
        }
    }

    private void appendOrderCandidateUpdateProductLineFilter(StringBuilder sql, List<Object> args) {
        if (properties.getKafka().isProductTopicsEnabled()) {
            sql.append("""
                   AND c.candidate_id = lo.candidate_id
                """);
            sql.append(candidateProductLineFilter("c", args)).append('\n');
        }
    }

    private String normalizeAccountType(String accountType) {
        return accountType == null || accountType.isBlank()
                ? DEFAULT_ACCOUNT_TYPE
                : accountType.trim().toUpperCase();
    }

    private void requireSingleRow(int rows, String operation) {
        if (rows != 1) {
            throw new IllegalStateException("failed to write " + operation);
        }
    }
}
