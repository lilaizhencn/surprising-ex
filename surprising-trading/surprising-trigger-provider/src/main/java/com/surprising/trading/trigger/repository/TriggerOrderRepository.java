package com.surprising.trading.trigger.repository;

import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.OrderType;
import com.surprising.trading.api.model.TimeInForce;
import com.surprising.trading.api.model.TriggerCondition;
import com.surprising.trading.api.model.TriggerOrderStatus;
import com.surprising.trading.api.model.TriggerOrderType;
import com.surprising.trading.api.model.TriggerPriceType;
import com.surprising.trading.trigger.model.TriggerOrderRecord;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
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
                    trigger_price_type, trigger_condition, trigger_price_ticks, order_type, time_in_force,
                    price_ticks, quantity_steps, margin_mode, status, placed_order_id, trigger_sequence,
                    triggered_price_ticks, reject_reason, trace_id, expires_at, triggered_at, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT DO NOTHING
                """, order.triggerOrderId(), order.userId(), order.clientTriggerOrderId(), order.ocoGroupId(),
                order.symbol(),
                order.side().name(), order.triggerType().name(), order.triggerPriceType().name(),
                order.triggerCondition().name(), order.triggerPriceTicks(), order.orderType().name(),
                order.timeInForce().name(), order.priceTicks(), order.quantitySteps(), order.marginMode().name(),
                order.status().name(), order.placedOrderId(), order.triggerSequence(), order.triggeredPriceTicks(),
                order.rejectReason(), order.traceId(), timestampOrNull(order.expiresAt()),
                timestampOrNull(order.triggeredAt()), Timestamp.from(order.createdAt()),
                Timestamp.from(order.updatedAt())) == 1;
    }

    public void lockUserSymbolMarginScope(long userId, String symbol) {
        jdbcTemplate.query("""
                SELECT pg_advisory_xact_lock(hashtext('trading-margin-mode'), hashtext(?))
                """, rs -> null, userId + ":" + symbol);
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

    public Optional<TriggerOrderRecord> findById(long triggerOrderId) {
        return jdbcTemplate.query("""
                SELECT *
                  FROM trading_trigger_orders
                 WHERE trigger_order_id = ?
                """, (rs, rowNum) -> toRecord(rs), triggerOrderId).stream().findFirst();
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
                   AND (? IS NULL OR symbol = ?)
                   AND status IN ('PENDING', 'TRIGGERING')
                 ORDER BY created_at DESC, trigger_order_id DESC
                 LIMIT ?
                """, (rs, rowNum) -> toRecord(rs), userId, symbol, symbol, normalizedLimit);
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

    public List<TriggerOrderRecord> claimTriggered(String symbol,
                                                   long markPriceTicks,
                                                   long triggerSequence,
                                                   Instant triggeredAt,
                                                   int limit,
                                                   Instant now) {
        int normalizedLimit = Math.max(1, Math.min(limit, 1000));
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
                       AND trigger_price_type = 'MARK_PRICE'
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
                """, (rs, rowNum) -> toRecord(rs), symbol, Timestamp.from(now), markPriceTicks,
                markPriceTicks, normalizedLimit, triggerSequence, markPriceTicks, Timestamp.from(triggeredAt),
                Timestamp.from(now), Timestamp.from(now));
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
                OrderType.valueOf(rs.getString("order_type")),
                TimeInForce.valueOf(rs.getString("time_in_force")),
                rs.getLong("price_ticks"),
                rs.getLong("quantity_steps"),
                MarginMode.fromNullableDbValue(rs.getString("margin_mode")),
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
