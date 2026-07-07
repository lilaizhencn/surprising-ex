package com.surprising.trading.trigger.repository;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.AdminCursorPage;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.OrderType;
import com.surprising.trading.api.model.PositionMode;
import com.surprising.trading.api.model.PositionSide;
import com.surprising.trading.api.model.TimeInForce;
import com.surprising.trading.api.model.TriggerCondition;
import com.surprising.trading.api.model.TriggerOrderStatus;
import com.surprising.trading.api.model.TriggerOrderType;
import com.surprising.trading.api.model.TriggerPriceType;
import com.surprising.trading.trigger.model.TriggerOrderRecord;
import com.surprising.trading.trigger.model.TriggerPosition;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * PostgreSQL state store for trigger orders.
 *
 * <p>Due triggers are claimed with row locks and SKIP LOCKED so multiple provider nodes can process
 * the same mark-price stream without executing one trigger order more than once.</p>
 */
@Repository
public class TriggerOrderRepository {

    private final JdbcTemplate jdbcTemplate;

    public TriggerOrderRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long nextSequence(String sequenceName) {
        Number value = jdbcTemplate.queryForObject("SELECT nextval(CAST(? AS regclass))", Number.class,
                tradingSequenceIdentifier(sequenceName));
        if (value == null || value.longValue() <= 0) {
            throw new IllegalStateException("failed to allocate trigger sequence " + sequenceName);
        }
        return value.longValue();
    }

    public boolean insert(TriggerOrderRecord order) {
        return jdbcTemplate.update("""
                INSERT INTO trading_trigger_orders (
                    trigger_order_id, user_id, client_trigger_order_id, oco_group_id, symbol, side, trigger_type,
                    trigger_price_type, trigger_condition, trigger_price_ticks, activation_price_ticks,
                    callback_rate_ppm, highest_price_ticks, lowest_price_ticks, activated_at, order_type,
                    time_in_force, price_ticks, quantity_steps, margin_mode, position_side, status, placed_order_id,
                    trigger_sequence, triggered_price_ticks, reject_reason, trace_id, expires_at, triggered_at,
                    created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT DO NOTHING
                """, order.triggerOrderId(), order.userId(), order.clientTriggerOrderId(), order.ocoGroupId(),
                order.symbol(),
                order.side().name(), order.triggerType().name(), order.triggerPriceType().name(),
                order.triggerCondition().name(), order.triggerPriceTicks(), order.activationPriceTicks(),
                order.callbackRatePpm(), order.highestPriceTicks(), order.lowestPriceTicks(),
                timestampOrNull(order.activatedAt()), order.orderType().name(), order.timeInForce().name(),
                order.priceTicks(), order.quantitySteps(), order.marginMode().name(),
                PositionSide.defaultIfNull(order.positionSide()).name(), order.status().name(),
                order.placedOrderId(), order.triggerSequence(), order.triggeredPriceTicks(),
                order.rejectReason(), order.traceId(), timestampOrNull(order.expiresAt()),
                timestampOrNull(order.triggeredAt()), Timestamp.from(order.createdAt()),
                Timestamp.from(order.updatedAt())) == 1;
    }

    public void lockUserSymbolMarginScope(long userId, String symbol) {
        jdbcTemplate.query("""
                SELECT pg_advisory_xact_lock(hashtext('trading-margin-mode'), hashtext(?))
                """, rs -> null, userId + ":" + symbol);
    }

    public void lockUserPositionMode(long userId) {
        lockUserPositionMode(ProductLine.LINEAR_PERPETUAL, userId);
    }

    public void lockUserPositionMode(ProductLine productLine, long userId) {
        jdbcTemplate.query("""
                SELECT pg_advisory_xact_lock(hashtext('position-mode'), hashtext(?))
                """, rs -> null, productLine(productLine).name() + ":" + userId);
    }

    public PositionMode positionMode(long userId) {
        return positionMode(ProductLine.LINEAR_PERPETUAL, userId);
    }

    public PositionMode positionMode(ProductLine productLine, long userId) {
        String mode = jdbcTemplate.query("""
                SELECT position_mode
                  FROM account_position_modes
                 WHERE product_line = ?
                   AND user_id = ?
                """, (rs, rowNum) -> rs.getString("position_mode"), productLine(productLine).name(), userId)
                .stream().findFirst().orElse(null);
        return PositionMode.fromNullableDbValue(mode);
    }

    private ProductLine productLine(ProductLine productLine) {
        return productLine == null ? ProductLine.LINEAR_PERPETUAL : productLine;
    }

    public boolean hasActiveMarginModeConflict(long userId, String symbol, MarginMode marginMode) {
        String normalizedMode = MarginMode.defaultIfNull(marginMode).name();
        Boolean conflict = jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                      FROM account_positions p
                     WHERE p.user_id = ?
                       AND p.symbol = ?
                       AND p.margin_mode <> ?
                       AND p.signed_quantity_steps <> 0
                    UNION ALL
                    SELECT 1
                      FROM trading_orders o
                     WHERE o.user_id = ?
                       AND o.symbol = ?
                       AND o.margin_mode <> ?
                       AND o.status IN ('ACCEPTED', 'PARTIALLY_FILLED', 'CANCEL_REQUESTED')
                       AND o.remaining_quantity_steps > 0
                    UNION ALL
                    SELECT 1
                      FROM trading_trigger_orders t
                     WHERE t.user_id = ?
                       AND t.symbol = ?
                       AND t.margin_mode <> ?
                       AND t.status IN ('PENDING', 'TRIGGERING')
                )
                """, Boolean.class, userId, symbol, normalizedMode, userId, symbol, normalizedMode,
                userId, symbol, normalizedMode);
        return Boolean.TRUE.equals(conflict);
    }

    public Optional<TriggerPosition> lockedPosition(long userId,
                                                    String symbol,
                                                    MarginMode marginMode,
                                                    PositionSide positionSide) {
        return jdbcTemplate.query("""
                SELECT signed_quantity_steps, instrument_version
                  FROM account_positions
                 WHERE user_id = ?
                   AND symbol = ?
                   AND margin_mode = ?
                   AND position_side = ?
                 FOR UPDATE
                """, (rs, rowNum) -> new TriggerPosition(
                rs.getLong("signed_quantity_steps"),
                rs.getLong("instrument_version")), userId, symbol, MarginMode.defaultIfNull(marginMode).name(),
                PositionSide.defaultIfNull(positionSide).name()).stream().findFirst();
    }

    public long openReduceOnlySteps(long userId,
                                    String symbol,
                                    MarginMode marginMode,
                                    PositionSide positionSide,
                                    long instrumentVersion,
                                    OrderSide closeSide) {
        Long value = jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(remaining_quantity_steps), 0)
                  FROM trading_orders
                 WHERE user_id = ?
                   AND symbol = ?
                   AND margin_mode = ?
                   AND position_side = ?
                   AND instrument_version = ?
                   AND side = ?
                   AND reduce_only = TRUE
                   AND status IN ('ACCEPTED', 'PARTIALLY_FILLED', 'CANCEL_REQUESTED')
                """, Long.class, userId, symbol, MarginMode.defaultIfNull(marginMode).name(),
                PositionSide.defaultIfNull(positionSide).name(), instrumentVersion, closeSide.name());
        return value == null ? 0L : value;
    }

    public long pendingTriggerCloseSteps(long userId,
                                         String symbol,
                                         MarginMode marginMode,
                                         PositionSide positionSide,
                                         OrderSide closeSide) {
        Long value = jdbcTemplate.queryForObject("""
                WITH trigger_capacity AS (
                    SELECT CASE
                               WHEN oco_group_id IS NULL THEN 'order:' || trigger_order_id::text
                               ELSE 'oco:' || oco_group_id
                           END AS capacity_group,
                           MAX(quantity_steps) AS quantity_steps
                      FROM trading_trigger_orders
                     WHERE user_id = ?
                       AND symbol = ?
                       AND margin_mode = ?
                       AND position_side = ?
                       AND side = ?
                       AND status IN ('PENDING', 'TRIGGERING')
                     GROUP BY capacity_group
                )
                SELECT COALESCE(SUM(quantity_steps), 0)
                  FROM trigger_capacity
                """, Long.class, userId, symbol, MarginMode.defaultIfNull(marginMode).name(),
                PositionSide.defaultIfNull(positionSide).name(), closeSide.name());
        return value == null ? 0L : value;
    }

    public long pendingTriggerOcoGroupMaxSteps(long userId,
                                               String symbol,
                                               MarginMode marginMode,
                                               PositionSide positionSide,
                                               OrderSide closeSide,
                                               String ocoGroupId) {
        if (ocoGroupId == null || ocoGroupId.isBlank()) {
            return 0L;
        }
        Long value = jdbcTemplate.queryForObject("""
                SELECT COALESCE(MAX(quantity_steps), 0)
                  FROM trading_trigger_orders
                 WHERE user_id = ?
                   AND symbol = ?
                   AND margin_mode = ?
                   AND position_side = ?
                   AND side = ?
                   AND oco_group_id = ?
                   AND status IN ('PENDING', 'TRIGGERING')
                """, Long.class, userId, symbol, MarginMode.defaultIfNull(marginMode).name(),
                PositionSide.defaultIfNull(positionSide).name(), closeSide.name(), ocoGroupId);
        return value == null ? 0L : value;
    }

    public Optional<TriggerOrderRecord> findById(long triggerOrderId) {
        return jdbcTemplate.query("""
                SELECT *
                  FROM trading_trigger_orders
                 WHERE trigger_order_id = ?
                """, (rs, rowNum) -> toRecord(rs), triggerOrderId).stream().findFirst();
    }

    public boolean triggerOrderMatchesContractType(long triggerOrderId, String contractType) {
        String normalizedContractType = emptyToNull(contractType);
        if (normalizedContractType == null) {
            return true;
        }
        Boolean matched = jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                      FROM trading_trigger_orders o
                      JOIN instruments i
                        ON i.symbol = o.symbol
                     WHERE o.trigger_order_id = ?
                       AND i.contract_type = ?
                )
                """, Boolean.class, triggerOrderId, normalizedContractType);
        return Boolean.TRUE.equals(matched);
    }

    public Optional<TriggerOrderRecord> findByClientTriggerOrderId(long userId, String clientTriggerOrderId) {
        return jdbcTemplate.query("""
                SELECT *
                  FROM trading_trigger_orders
                 WHERE user_id = ?
                   AND client_trigger_order_id = ?
                """, (rs, rowNum) -> toRecord(rs), userId, clientTriggerOrderId).stream().findFirst();
    }

    public List<TriggerOrderRecord> openOrders(long userId, String symbol, int limit) {
        int normalizedLimit = Math.max(1, Math.min(limit, 1000));
        return jdbcTemplate.query("""
                SELECT *
                  FROM trading_trigger_orders
                 WHERE user_id = ?
                   AND (CAST(? AS text) IS NULL OR symbol = ?)
                   AND status IN ('PENDING', 'TRIGGERING')
                 ORDER BY created_at DESC, trigger_order_id DESC
                 LIMIT ?
                """, (rs, rowNum) -> toRecord(rs), userId, symbol, symbol, normalizedLimit);
    }

    public List<TriggerOrderRecord> pendingCancelableOrders(long userId, String symbol, int limit) {
        int normalizedLimit = Math.max(1, Math.min(limit, 1000));
        return jdbcTemplate.query("""
                SELECT *
                  FROM trading_trigger_orders
                 WHERE user_id = ?
                   AND (CAST(? AS text) IS NULL OR symbol = ?)
                   AND status = 'PENDING'
                 ORDER BY created_at ASC, trigger_order_id ASC
                 LIMIT ?
                """, (rs, rowNum) -> toRecord(rs), userId, symbol, symbol, normalizedLimit);
    }

    public List<TriggerOrderRecord> adminOrders(Long userId,
                                                String symbol,
                                                TriggerOrderStatus status,
                                                Long triggerOrderId,
                                                int limit) {
        return adminOrderPage(userId, symbol, status, triggerOrderId, limit, null, null).items();
    }

    public AdminCursorPage.CursorPage<TriggerOrderRecord> adminOrderPage(Long userId,
                                                                         String symbol,
                                                                         TriggerOrderStatus status,
                                                                         Long triggerOrderId,
                                                                         int limit,
                                                                         String cursor,
                                                                         String sort) {
        return adminOrderPage(userId, symbol, status, triggerOrderId, limit, null, cursor, sort);
    }

    public AdminCursorPage.CursorPage<TriggerOrderRecord> adminOrderPage(Long userId,
                                                                         String symbol,
                                                                         TriggerOrderStatus status,
                                                                         Long triggerOrderId,
                                                                         int limit,
                                                                         String contractType,
                                                                         String cursor,
                                                                         String sort) {
        String normalizedStatus = status == null ? null : status.name();
        String normalizedContractType = emptyToNull(contractType);
        int normalizedLimit = AdminCursorPage.limit(limit, 1000);
        AdminCursorPage.SortSpec createdAtDesc = new AdminCursorPage.SortSpec(
                "createdAt", "created_at", "trigger_order_id", true);
        AdminCursorPage.SortSpec createdAtAsc = new AdminCursorPage.SortSpec(
                "createdAt", "created_at", "trigger_order_id", false);
        AdminCursorPage.SortSpec sortSpec = AdminCursorPage.parseSort(
                sort, createdAtDesc, List.of(createdAtDesc, createdAtAsc));
        AdminCursorPage.Cursor decodedCursor = AdminCursorPage.decodeCursor(cursor);
        List<Object> args = new ArrayList<>();
        args.add(userId);
        args.add(userId);
        args.add(symbol);
        args.add(symbol);
        args.add(normalizedStatus);
        args.add(normalizedStatus);
        args.add(triggerOrderId);
        args.add(triggerOrderId);
        args.add(normalizedContractType);
        args.add(normalizedContractType);
        AdminCursorPage.addCursorArgs(args, decodedCursor);
        args.add(normalizedLimit + 1);
        List<TriggerOrderRecord> rows = jdbcTemplate.query("""
                SELECT *
                  FROM trading_trigger_orders
                 WHERE (CAST(? AS text) IS NULL OR user_id = ?)
                   AND (CAST(? AS text) IS NULL OR symbol = ?)
                   AND (CAST(? AS text) IS NULL OR status = ?)
                   AND (CAST(? AS text) IS NULL OR trigger_order_id = ?)
                   AND (CAST(? AS text) IS NULL OR EXISTS (
                        SELECT 1
                          FROM instruments i
                         WHERE i.symbol = trading_trigger_orders.symbol
                           AND i.contract_type = ?
                   ))
                %s
                 ORDER BY %s %s, %s %s
                 LIMIT ?
                """.formatted(AdminCursorPage.seekCondition(sortSpec, decodedCursor),
                        sortSpec.column(), sortSpec.directionSql(), sortSpec.idColumn(), sortSpec.directionSql()),
                (rs, rowNum) -> toRecord(rs),
                args.toArray());
        return AdminCursorPage.page(rows, normalizedLimit, sortSpec, TriggerOrderRecord::createdAt,
                TriggerOrderRecord::triggerOrderId);
    }

    public Optional<TriggerOrderRecord> cancel(long userId, long triggerOrderId, Instant now) {
        jdbcTemplate.update("""
                UPDATE trading_trigger_orders
                   SET status = 'CANCELED',
                       updated_at = ?
                 WHERE user_id = ?
                   AND trigger_order_id = ?
                   AND status = 'PENDING'
                """, Timestamp.from(now), userId, triggerOrderId);
        return findById(triggerOrderId);
    }

    public OptionalLong markPriceTicks(String symbol, long sequence) {
        return jdbcTemplate.query("""
                SELECT ((m.mark_price_units + i.price_tick_units / 2) / i.price_tick_units) AS mark_ticks
                  FROM price_mark_ticks m
                  JOIN instrument_current_versions c
                    ON c.symbol = m.symbol
                  JOIN instruments i
                    ON i.symbol = c.symbol AND i.version = c.version
                 WHERE m.symbol = ?
                   AND m.sequence = ?
                """, (rs, rowNum) -> rs.getLong("mark_ticks"), symbol, sequence)
                .stream()
                .mapToLong(Long::longValue)
                .filter(value -> value > 0)
                .findFirst();
    }

    public OptionalLong indexPriceTicks(String symbol, long sequence) {
        return jdbcTemplate.query("""
                SELECT ((CAST(round(p.index_price * qs.scale_units) AS BIGINT) + i.price_tick_units / 2)
                        / i.price_tick_units) AS index_ticks
                  FROM price_index_ticks p
                  JOIN instrument_current_versions c
                    ON c.symbol = p.symbol
                  JOIN instruments i
                    ON i.symbol = c.symbol AND i.version = c.version
                  JOIN account_asset_scales qs
                    ON qs.asset = i.quote_asset
                 WHERE p.symbol = ?
                   AND p.sequence = ?
                   AND p.index_price IS NOT NULL
                   AND p.status IN ('HEALTHY', 'DEGRADED', 'CLAMPED')
                """, (rs, rowNum) -> rs.getLong("index_ticks"), symbol, sequence)
                .stream()
                .mapToLong(Long::longValue)
                .filter(value -> value > 0)
                .findFirst();
    }

    public boolean hasPendingOrdersForPriceType(String symbol, TriggerPriceType triggerPriceType) {
        Boolean result = jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                      FROM trading_trigger_orders
                     WHERE symbol = ?
                       AND status = 'PENDING'
                       AND trigger_price_type = ?
                )
                """, Boolean.class, symbol, triggerPriceType.name());
        return Boolean.TRUE.equals(result);
    }

    public List<TriggerOrderRecord> claimTriggered(String symbol,
                                                   TriggerPriceType triggerPriceType,
                                                   long triggerPriceTicks,
                                                   long triggerSequence,
                                                   Instant triggeredAt,
                                                   int limit,
                                                   Instant now) {
        int normalizedLimit = Math.max(1, Math.min(limit, 1000));
        Instant eventVisibleAt = triggeredAt.plusSeconds(1);
        return jdbcTemplate.query("""
                -- Claim first, then update, so concurrent trigger nodes skip rows already owned by peers.
                WITH due AS (
                    SELECT trigger_order_id,
                           CASE
                               WHEN oco_group_id IS NULL THEN 'order:' || trigger_order_id::text
                               ELSE 'oco:' || user_id::text || ':' || symbol || ':' || margin_mode || ':' || oco_group_id
                           END AS claim_group_key
                      FROM trading_trigger_orders
                     WHERE symbol = ?
                       AND status = 'PENDING'
                       AND trigger_type IN ('TAKE_PROFIT', 'STOP_LOSS')
                       AND trigger_price_type = ?
                       AND created_at <= ?
                       AND (expires_at IS NULL OR expires_at > ?)
                       AND (
                           (trigger_condition = 'GREATER_OR_EQUAL' AND trigger_price_ticks <= ?)
                        OR (trigger_condition = 'LESS_OR_EQUAL' AND trigger_price_ticks >= ?)
                       )
                     ORDER BY trigger_order_id ASC
                     LIMIT ?
                     FOR UPDATE SKIP LOCKED
                ),
                candidates AS (
                    SELECT trigger_order_id
                      FROM (
                          SELECT trigger_order_id,
                                 row_number() OVER (
                                     PARTITION BY claim_group_key
                                     ORDER BY trigger_order_id ASC
                                 ) AS group_rank
                            FROM due
                      ) ranked
                     WHERE group_rank = 1
                ),
                claimed AS (
                    UPDATE trading_trigger_orders o
                       SET status = 'TRIGGERING',
                           trigger_sequence = ?,
                           triggered_price_ticks = ?,
                           triggered_at = ?,
                           updated_at = ?
                      FROM candidates c
                     WHERE o.trigger_order_id = c.trigger_order_id
                 RETURNING o.*
                ),
                canceled_oco AS (
                    UPDATE trading_trigger_orders sibling
                       SET status = 'CANCELED',
                           updated_at = ?
                      FROM claimed c
                     WHERE c.oco_group_id IS NOT NULL
                       AND sibling.user_id = c.user_id
                       AND sibling.symbol = c.symbol
                       AND sibling.margin_mode = c.margin_mode
                       AND sibling.oco_group_id = c.oco_group_id
                       AND sibling.trigger_order_id <> c.trigger_order_id
                       AND sibling.status = 'PENDING'
                 RETURNING sibling.trigger_order_id
                )
                SELECT * FROM claimed
                """, (rs, rowNum) -> toRecord(rs), symbol, triggerPriceType.name(),
                Timestamp.from(eventVisibleAt), Timestamp.from(now),
                triggerPriceTicks, triggerPriceTicks, normalizedLimit, triggerSequence, triggerPriceTicks,
                Timestamp.from(triggeredAt),
                Timestamp.from(now), Timestamp.from(now));
    }

    public List<TriggerOrderRecord> claimTrailingTriggered(String symbol,
                                                           TriggerPriceType triggerPriceType,
                                                           long priceTicks,
                                                           long triggerSequence,
                                                           Instant triggeredAt,
                                                           int limit,
                                                           Instant now) {
        int normalizedLimit = Math.max(1, Math.min(limit, 1000));
        Instant eventVisibleAt = triggeredAt.plusSeconds(1);
        return jdbcTemplate.query("""
                WITH locked AS (
                    SELECT *,
                           CASE
                               WHEN activated_at IS NOT NULL THEN TRUE
                               WHEN activation_price_ticks IS NULL OR activation_price_ticks <= 0 THEN TRUE
                               WHEN side = 'SELL' THEN ? >= activation_price_ticks
                               ELSE ? <= activation_price_ticks
                           END AS next_activated
                      FROM trading_trigger_orders
                     WHERE symbol = ?
                       AND status = 'PENDING'
                       AND trigger_type = 'TRAILING_STOP'
                       AND trigger_price_type = ?
                       AND created_at <= ?
                       AND (expires_at IS NULL OR expires_at > ?)
                     ORDER BY trigger_order_id ASC
                     LIMIT ?
                     FOR UPDATE SKIP LOCKED
                ),
                tracked AS (
                    SELECT *,
                           CASE
                               WHEN next_activated AND side = 'SELL'
                                   THEN GREATEST(COALESCE(highest_price_ticks, ?), ?)
                               ELSE highest_price_ticks
                           END AS next_highest_price_ticks,
                           CASE
                               WHEN next_activated AND side = 'BUY'
                                   THEN LEAST(COALESCE(lowest_price_ticks, ?), ?)
                               ELSE lowest_price_ticks
                           END AS next_lowest_price_ticks
                      FROM locked
                ),
                computed AS (
                    SELECT *,
                           CASE
                               WHEN next_activated AND side = 'SELL'
                                   THEN ? <= FLOOR(
                                       (next_highest_price_ticks::numeric * (1000000 - callback_rate_ppm))
                                       / 1000000
                                   )::bigint
                               WHEN next_activated AND side = 'BUY'
                                   THEN ? >= CEIL(
                                       (next_lowest_price_ticks::numeric * (1000000 + callback_rate_ppm))
                                       / 1000000
                                   )::bigint
                               ELSE FALSE
                           END AS should_trigger,
                           CASE
                               WHEN oco_group_id IS NULL THEN 'order:' || trigger_order_id::text
                               ELSE 'oco:' || user_id::text || ':' || symbol || ':' || margin_mode || ':'
                                   || oco_group_id
                           END AS claim_group_key
                      FROM tracked
                ),
                tracking_update AS (
                    UPDATE trading_trigger_orders o
                       SET highest_price_ticks = c.next_highest_price_ticks,
                           lowest_price_ticks = c.next_lowest_price_ticks,
                           activated_at = CASE
                               WHEN o.activated_at IS NULL AND c.next_activated THEN ?
                               ELSE o.activated_at
                           END,
                           updated_at = ?
                      FROM computed c
                     WHERE o.trigger_order_id = c.trigger_order_id
                       AND c.next_activated
                       AND NOT c.should_trigger
                 RETURNING o.trigger_order_id
                ),
                due AS (
                    SELECT trigger_order_id,
                           claim_group_key,
                           next_highest_price_ticks,
                           next_lowest_price_ticks,
                           next_activated
                      FROM computed
                     WHERE should_trigger
                ),
                candidates AS (
                    SELECT trigger_order_id,
                           next_highest_price_ticks,
                           next_lowest_price_ticks,
                           next_activated
                      FROM (
                          SELECT trigger_order_id,
                                 next_highest_price_ticks,
                                 next_lowest_price_ticks,
                                 next_activated,
                                 row_number() OVER (
                                     PARTITION BY claim_group_key
                                     ORDER BY trigger_order_id ASC
                                 ) AS group_rank
                            FROM due
                      ) ranked
                     WHERE group_rank = 1
                ),
                claimed AS (
                    UPDATE trading_trigger_orders o
                       SET status = 'TRIGGERING',
                           trigger_sequence = ?,
                           triggered_price_ticks = ?,
                           triggered_at = ?,
                           highest_price_ticks = c.next_highest_price_ticks,
                           lowest_price_ticks = c.next_lowest_price_ticks,
                           activated_at = CASE
                               WHEN o.activated_at IS NULL AND c.next_activated THEN ?
                               ELSE o.activated_at
                           END,
                           updated_at = ?
                      FROM candidates c
                     WHERE o.trigger_order_id = c.trigger_order_id
                 RETURNING o.*
                ),
                canceled_oco AS (
                    UPDATE trading_trigger_orders sibling
                       SET status = 'CANCELED',
                           updated_at = ?
                      FROM claimed c
                     WHERE c.oco_group_id IS NOT NULL
                       AND sibling.user_id = c.user_id
                       AND sibling.symbol = c.symbol
                       AND sibling.margin_mode = c.margin_mode
                       AND sibling.oco_group_id = c.oco_group_id
                       AND sibling.trigger_order_id <> c.trigger_order_id
                       AND sibling.status = 'PENDING'
                 RETURNING sibling.trigger_order_id
                )
                SELECT * FROM claimed
                """, (rs, rowNum) -> toRecord(rs),
                priceTicks, priceTicks, symbol, triggerPriceType.name(), Timestamp.from(eventVisibleAt),
                Timestamp.from(now), normalizedLimit,
                priceTicks, priceTicks, priceTicks, priceTicks, priceTicks, priceTicks,
                Timestamp.from(triggeredAt), Timestamp.from(now), triggerSequence, priceTicks,
                Timestamp.from(triggeredAt), Timestamp.from(triggeredAt), Timestamp.from(now),
                Timestamp.from(now));
    }

    public void markTriggered(long triggerOrderId, long placedOrderId, Instant now) {
        int rows = jdbcTemplate.update("""
                UPDATE trading_trigger_orders
                   SET status = 'TRIGGERED',
                       placed_order_id = ?,
                       reject_reason = NULL,
                       updated_at = ?
                 WHERE trigger_order_id = ?
                   AND status = 'TRIGGERING'
                """, placedOrderId, Timestamp.from(now), triggerOrderId);
        requireSingleRow(rows, "trigger order completion");
    }

    public void markTriggerFailed(long triggerOrderId, long placedOrderId, String reason, Instant now) {
        int rows = jdbcTemplate.update("""
                UPDATE trading_trigger_orders
                   SET status = 'TRIGGER_FAILED',
                       placed_order_id = ?,
                       reject_reason = ?,
                       updated_at = ?
                 WHERE trigger_order_id = ?
                   AND status = 'TRIGGERING'
                """, placedOrderId, truncate(reason, 500), Timestamp.from(now), triggerOrderId);
        requireSingleRow(rows, "trigger order failure");
    }

    public int expirePending(Instant now, int limit) {
        int normalizedLimit = Math.max(1, Math.min(limit, 1000));
        return jdbcTemplate.update("""
                WITH expired AS (
                    SELECT trigger_order_id
                      FROM trading_trigger_orders
                     WHERE status = 'PENDING'
                       AND expires_at IS NOT NULL
                       AND expires_at <= ?
                     ORDER BY expires_at ASC, trigger_order_id ASC
                     LIMIT ?
                     FOR UPDATE SKIP LOCKED
                )
                UPDATE trading_trigger_orders o
                   SET status = 'EXPIRED',
                       updated_at = ?
                  FROM expired e
                 WHERE o.trigger_order_id = e.trigger_order_id
                """, Timestamp.from(now), normalizedLimit, Timestamp.from(now));
    }

    public int resetStaleTriggering(Instant staleBefore, Instant now, int limit) {
        int normalizedLimit = Math.max(1, Math.min(limit, 1000));
        return jdbcTemplate.update("""
                WITH stale AS (
                    SELECT trigger_order_id
                      FROM trading_trigger_orders
                     WHERE status = 'TRIGGERING'
                       AND updated_at < ?
                     ORDER BY updated_at ASC, trigger_order_id ASC
                     LIMIT ?
                     FOR UPDATE SKIP LOCKED
                )
                UPDATE trading_trigger_orders o
                   SET status = 'PENDING',
                       updated_at = ?
                  FROM stale s
                 WHERE o.trigger_order_id = s.trigger_order_id
                   AND o.placed_order_id IS NULL
                """, Timestamp.from(staleBefore), normalizedLimit, Timestamp.from(now));
    }

    private TriggerOrderRecord toRecord(ResultSet rs) throws SQLException {
        return new TriggerOrderRecord(
                rs.getLong("trigger_order_id"),
                rs.getLong("user_id"),
                stringOrNull(rs, "client_trigger_order_id"),
                stringOrNull(rs, "oco_group_id"),
                rs.getString("symbol"),
                OrderSide.valueOf(rs.getString("side")),
                TriggerOrderType.valueOf(rs.getString("trigger_type")),
                TriggerPriceType.valueOf(rs.getString("trigger_price_type")),
                TriggerCondition.valueOf(rs.getString("trigger_condition")),
                rs.getLong("trigger_price_ticks"),
                longOrNull(rs, "activation_price_ticks"),
                longOrNull(rs, "callback_rate_ppm"),
                longOrNull(rs, "highest_price_ticks"),
                longOrNull(rs, "lowest_price_ticks"),
                instantOrNull(rs, "activated_at"),
                OrderType.valueOf(rs.getString("order_type")),
                TimeInForce.valueOf(rs.getString("time_in_force")),
                rs.getLong("price_ticks"),
                rs.getLong("quantity_steps"),
                MarginMode.fromNullableDbValue(rs.getString("margin_mode")),
                PositionSide.fromNullableDbValue(rs.getString("position_side")),
                TriggerOrderStatus.valueOf(rs.getString("status")),
                longOrNull(rs, "placed_order_id"),
                longOrNull(rs, "trigger_sequence"),
                longOrNull(rs, "triggered_price_ticks"),
                stringOrNull(rs, "reject_reason"),
                stringOrNull(rs, "trace_id"),
                instantOrNull(rs, "expires_at"),
                instantOrNull(rs, "triggered_at"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private static void requireSingleRow(int rows, String operation) {
        if (rows != 1) {
            throw new IllegalStateException(operation + " affected " + rows + " rows");
        }
    }

    private static Timestamp timestampOrNull(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static Instant instantOrNull(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static String stringOrNull(ResultSet rs, String column) throws SQLException {
        String value = rs.getString(column);
        return rs.wasNull() ? null : value;
    }

    private static Long longOrNull(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String truncate(String value, int limit) {
        if (value == null || value.length() <= limit) {
            return value;
        }
        return value.substring(0, limit);
    }

    private String tradingSequenceIdentifier(String sequenceName) {
        if (sequenceName == null || !sequenceName.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")) {
            throw new IllegalArgumentException("invalid trading sequence name: " + sequenceName);
        }
        return "public.trading_" + sequenceName.toLowerCase().replace('-', '_') + "_seq";
    }
}
