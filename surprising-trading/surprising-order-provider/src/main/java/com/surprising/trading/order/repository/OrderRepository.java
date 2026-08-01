package com.surprising.trading.order.repository;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.AdminCursorPage;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.OrderStatus;
import com.surprising.trading.api.model.OrderType;
import com.surprising.trading.api.model.PositionSide;
import com.surprising.trading.api.model.TimeInForce;
import com.surprising.trading.order.model.OrderRecord;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 普通订单仓储，只负责 {@code trading_orders} 表。
 *
 * <p>订单号、事件号和命令号使用 PostgreSQL 原生序列分配，不读取其他业务表，
 * 用于避免基于计数表分配编号时产生行锁热点。</p>
 */
@Repository
public class OrderRepository {

    private static final String INSERT_ORDER_SQL = """
            INSERT INTO trading_orders (
                order_id, product_line, user_id, client_order_id, symbol, instrument_version, side, order_type, time_in_force,
                price_ticks, quantity_steps, executed_quantity_steps, remaining_quantity_steps,
                margin_mode, position_side, maker_fee_rate_ppm, taker_fee_rate_ppm,
                reduce_only, post_only, reservation_account_type, reservation_asset, reserved_units,
                status, reject_reason, created_at, updated_at, revision
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (product_line, user_id, client_order_id) WHERE client_order_id IS NOT NULL DO NOTHING
            """;

    private final JdbcTemplate jdbcTemplate;

    public OrderRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long nextSequence(String sequenceName) {
        // PostgreSQL 原生序列可避免基于计数表分配编号时产生行锁热点。
        Long value = jdbcTemplate.queryForObject("SELECT nextval(CAST(? AS regclass))", Long.class,
                tradingSequenceIdentifier(sequenceName));
        if (value == null) {
            throw new IllegalStateException("Failed to allocate sequence " + sequenceName);
        }
        return value;
    }

    public List<Long> nextSequenceBatch(String sequenceName, int count) {
        if (count <= 0) {
            return List.of();
        }
        List<Long> values = jdbcTemplate.query("""
                SELECT nextval(CAST(? AS regclass)) AS id
                  FROM generate_series(1, ?) AS n
                 ORDER BY n
                """, (rs, rowNum) -> rs.getLong("id"), tradingSequenceIdentifier(sequenceName), count);
        if (values.size() != count) {
            throw new IllegalStateException("Failed to allocate " + count + " sequence values for " + sequenceName);
        }
        return List.copyOf(values);
    }

    public boolean insert(OrderRecord order) {
        int rows = jdbcTemplate.update(INSERT_ORDER_SQL,
                order.orderId(), order.productLine().name(), order.userId(), emptyToNull(order.clientOrderId()), order.symbol(),
                nullableVersion(order.instrumentVersion()), order.side().name(), order.orderType().name(),
                order.timeInForce().name(),
                order.priceTicks(), order.quantitySteps(), order.executedQuantitySteps(), order.remainingQuantitySteps(),
                order.marginMode().name(), order.positionSide().name(), order.makerFeeRatePpm(), order.takerFeeRatePpm(),
                order.reduceOnly(), order.postOnly(), order.reservationAccountType(), order.reservationAsset(),
                order.reservedUnits(), order.status().name(), order.rejectReason(),
                Timestamp.from(order.createdAt()), Timestamp.from(order.updatedAt()), order.revision());
        return rows == 1;
    }

    private ProductLine productLine(ProductLine productLine) {
        return productLine == null ? ProductLine.LINEAR_PERPETUAL : productLine;
    }

    public boolean hasActiveMarginModeConflict(long userId, String symbol, MarginMode marginMode) {
        return hasActiveMarginModeConflict(ProductLine.LINEAR_PERPETUAL, userId, symbol, marginMode);
    }

    public boolean hasActiveMarginModeConflict(ProductLine productLine,
                                               long userId,
                                               String symbol,
                                               MarginMode marginMode) {
        String normalizedMode = MarginMode.defaultIfNull(marginMode).name();
        String resolvedProductLine = productLine(productLine).name();
        Boolean conflict = jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                      FROM trading_orders
                     WHERE product_line = ?
                       AND user_id = ?
                       AND symbol = ?
                       AND margin_mode <> ?
                       AND status IN ('ACCEPTED', 'PARTIALLY_FILLED', 'CANCEL_REQUESTED')
                       AND remaining_quantity_steps > 0
                )
                """, Boolean.class, resolvedProductLine, userId, symbol, normalizedMode);
        return Boolean.TRUE.equals(conflict);
    }

    public boolean requestCancel(long orderId, Instant now) {
        // 条件更新可防止并发撤单请求生成重复的撤单命令。
        int rows = jdbcTemplate.update("""
                UPDATE trading_orders
                   SET status = 'CANCEL_REQUESTED',
                       updated_at = ?,
                       revision = revision + 1
                 WHERE order_id = ?
                   AND status IN ('ACCEPTED', 'PARTIALLY_FILLED')
                """, Timestamp.from(now), orderId);
        return rows == 1;
    }

    public void reject(long orderId, String rejectReason, Instant now) {
        int rows = jdbcTemplate.update("""
                UPDATE trading_orders
                   SET status = 'REJECTED',
                       remaining_quantity_steps = 0,
                       reject_reason = ?,
                       updated_at = ?,
                       revision = revision + 1
                 WHERE order_id = ?
                   AND status = 'ACCEPTED'
                   AND executed_quantity_steps = 0
                """, rejectReason, Timestamp.from(now), orderId);
        if (rows != 1) {
            throw new IllegalStateException("failed to reject order " + orderId);
        }
    }

    public boolean completeReservation(long orderId, boolean accepted, String rejectReason, Instant now) {
        int rows = jdbcTemplate.update("""
                UPDATE trading_orders
                   SET status = ?,
                       reject_reason = ?,
                       remaining_quantity_steps = CASE WHEN ? THEN remaining_quantity_steps ELSE 0 END,
                       updated_at = ?,
                       revision = revision + 1
                 WHERE order_id = ?
                   AND status = 'PENDING_RESERVE'
                   AND executed_quantity_steps = 0
                """, accepted ? OrderStatus.ACCEPTED.name() : OrderStatus.REJECTED.name(),
                accepted ? null : rejectReason, accepted, Timestamp.from(now), orderId);
        return rows == 1;
    }

    public Map<Long, OrderRecord> completeReservations(List<ReservationCompletion> completions) {
        if (completions == null || completions.isEmpty()) {
            return Map.of();
        }
        List<ReservationCompletion> unique = completions.stream()
                .collect(java.util.stream.Collectors.toMap(
                        ReservationCompletion::orderId,
                        completion -> completion,
                        (first, duplicate) -> {
                            if (!first.equals(duplicate)) {
                                throw new IllegalArgumentException(
                                        "conflicting reservation completions for order " + first.orderId());
                            }
                            return first;
                        },
                        LinkedHashMap::new))
                .values().stream().toList();
        Map<Long, OrderRecord> updated = new LinkedHashMap<>(unique.size());
        for (int offset = 0; offset < unique.size(); offset += 1_000) {
            List<ReservationCompletion> batch = unique.subList(offset, Math.min(offset + 1_000, unique.size()));
            String values = String.join(", ", Collections.nCopies(batch.size(),
                    "(?::bigint, ?::boolean, ?::text, ?::timestamptz)"));
            List<Object> args = new ArrayList<>(batch.size() * 4);
            for (ReservationCompletion completion : batch) {
                if (completion.completedAt() == null) {
                    throw new IllegalArgumentException("reservation completion timestamp is required");
                }
                args.add(completion.orderId());
                args.add(completion.accepted());
                args.add(completion.accepted() ? null : completion.rejectReason());
                args.add(Timestamp.from(completion.completedAt()));
            }
            String sql = """
                    WITH input(order_id, accepted, reject_reason, completed_at) AS (
                        VALUES %s
                    ),
                    locked AS MATERIALIZED (
                        SELECT o.order_id
                          FROM trading_orders o
                          JOIN input ON input.order_id = o.order_id
                         WHERE o.status = 'PENDING_RESERVE'
                           AND o.executed_quantity_steps = 0
                         ORDER BY o.order_id
                           FOR UPDATE OF o
                    )
                    UPDATE trading_orders o
                       SET status = CASE WHEN input.accepted THEN 'ACCEPTED' ELSE 'REJECTED' END,
                           reject_reason = CASE WHEN input.accepted THEN NULL ELSE input.reject_reason END,
                           remaining_quantity_steps = CASE
                               WHEN input.accepted THEN o.remaining_quantity_steps ELSE 0
                           END,
                           updated_at = input.completed_at,
                           revision = o.revision + 1
                      FROM input
                      JOIN locked ON locked.order_id = input.order_id
                     WHERE o.order_id = input.order_id
                       AND o.status = 'PENDING_RESERVE'
                       AND o.executed_quantity_steps = 0
                 RETURNING o.*
                    """.formatted(values);
            jdbcTemplate.query(sql, rs -> {
                OrderRecord order = toRecord(rs);
                updated.put(order.orderId(), order);
            }, args.toArray());
        }
        return Map.copyOf(updated);
    }

    public Optional<OrderRecord> findByOrderId(long orderId) {
        return jdbcTemplate.query("SELECT * FROM trading_orders WHERE order_id = ?",
                (rs, rowNum) -> toRecord(rs), orderId).stream().findFirst();
    }

    public Map<Long, OrderRecord> findByOrderIds(Collection<Long> orderIds) {
        if (orderIds == null || orderIds.isEmpty()) {
            return Map.of();
        }
        List<Long> uniqueIds = orderIds.stream().distinct().toList();
        Map<Long, OrderRecord> orders = new LinkedHashMap<>(uniqueIds.size());
        for (int offset = 0; offset < uniqueIds.size(); offset += 1_000) {
            List<Long> batch = uniqueIds.subList(offset, Math.min(offset + 1_000, uniqueIds.size()));
            String placeholders = String.join(", ", Collections.nCopies(batch.size(), "?"));
            jdbcTemplate.query("SELECT * FROM trading_orders WHERE order_id IN (" + placeholders + ")",
                    rs -> {
                        OrderRecord order = toRecord(rs);
                        orders.put(order.orderId(), order);
                    }, batch.toArray());
        }
        return Map.copyOf(orders);
    }

    public boolean orderMatchesContractType(long orderId, String contractType) {
        String productLine = productLineNameFromContractType(contractType);
        if (productLine == null) {
            return true;
        }
        Boolean matched = jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                      FROM trading_orders
                     WHERE order_id = ?
                       AND product_line = ?
                )
                """, Boolean.class, orderId, productLine);
        return Boolean.TRUE.equals(matched);
    }

    public Optional<OrderRecord> findByClientOrderId(long userId, String clientOrderId) {
        return findByClientOrderId(ProductLine.LINEAR_PERPETUAL, userId, clientOrderId);
    }

    public Optional<OrderRecord> findByClientOrderId(ProductLine productLine, long userId, String clientOrderId) {
        return jdbcTemplate.query("""
                SELECT *
                  FROM trading_orders
                 WHERE product_line = ? AND user_id = ? AND client_order_id = ?
                """, (rs, rowNum) -> toRecord(rs), productLine(productLine).name(), userId, clientOrderId).stream().findFirst();
    }

    public List<OrderRecord> openOrders(long userId, String symbol, int limit) {
        return openOrders(userId, symbol, limit, null);
    }

    public List<OrderRecord> openOrders(long userId, String symbol, int limit, String contractType) {
        String normalizedSymbol = emptyToNull(symbol);
        String productLine = productLineNameFromContractType(contractType);
        String sql = """
                SELECT *
                  FROM trading_orders
                 WHERE user_id = ?
                   AND (CAST(? AS text) IS NULL OR symbol = ?)
                   AND (CAST(? AS text) IS NULL OR product_line = ?)
                   AND status IN ('ACCEPTED', 'PARTIALLY_FILLED')
                 ORDER BY order_id DESC
                 LIMIT ?
                """;
        return jdbcTemplate.query(sql, (rs, rowNum) -> toRecord(rs),
                userId, normalizedSymbol, normalizedSymbol, productLine, productLine, limit);
    }

    public List<OrderRecord> lockOpenReduceOnlyOrders(ProductLine productLine,
                                                      long userId,
                                                      String symbol,
                                                      PositionSide positionSide,
                                                      Instant createdNoLaterThan) {
        return jdbcTemplate.query("""
                SELECT *
                  FROM trading_orders
                 WHERE product_line = ?
                   AND user_id = ?
                   AND symbol = ?
                   AND position_side = ?
                   AND reduce_only = TRUE
                   AND status IN ('ACCEPTED', 'PARTIALLY_FILLED', 'CANCEL_REQUESTED')
                   AND remaining_quantity_steps > 0
                   AND created_at <= ?
                 ORDER BY created_at ASC, order_id ASC
                 FOR UPDATE
                """, (rs, rowNum) -> toRecord(rs), productLine(productLine).name(), userId, symbol,
                PositionSide.defaultIfNull(positionSide).name(), Timestamp.from(createdNoLaterThan));
    }

    /** 稳定的键集扫描，仅用于重建可选的 Redis 活跃订单读模型。 */
    public List<Long> activeOpenOrderUsers(ProductLine productLine, long afterUserId, int limit) {
        return jdbcTemplate.query("""
                SELECT DISTINCT user_id
                  FROM trading_orders
                 WHERE product_line = ?
                   AND user_id > ?
                   AND status IN ('ACCEPTED', 'PARTIALLY_FILLED')
                 ORDER BY user_id ASC
                 LIMIT ?
                """, (rs, rowNum) -> rs.getLong(1), productLine(productLine).name(), afterUserId,
                Math.max(1, Math.min(limit, 5_000)));
    }

    /** 稳定的键集扫描，仅用于重建 Redis 活跃订单读模型。 */
    public List<OrderRecord> activeOrdersForOpenOrderView(ProductLine productLine,
                                                           long userId,
                                                           long afterOrderId,
                                                           int limit) {
        return jdbcTemplate.query("""
                SELECT *
                  FROM trading_orders
                 WHERE product_line = ?
                   AND user_id = ?
                   AND order_id > ?
                   AND status IN ('ACCEPTED', 'PARTIALLY_FILLED')
                 ORDER BY order_id ASC
                 LIMIT ?
                """, (rs, rowNum) -> toRecord(rs), productLine(productLine).name(), userId, afterOrderId, limit);
    }

    /** 启动保证金快照使用的单表扫描，包含撤单请求期间仍需计入容量的订单。 */
    public List<Long> activeMarginSnapshotUsers(ProductLine productLine, long afterUserId, int limit) {
        return jdbcTemplate.query("""
                SELECT DISTINCT user_id
                  FROM trading_orders
                 WHERE product_line = ?
                   AND user_id > ?
                   AND status IN ('ACCEPTED', 'PARTIALLY_FILLED', 'CANCEL_REQUESTED')
                 ORDER BY user_id ASC
                 LIMIT ?
                """, (rs, rowNum) -> rs.getLong(1), productLine(productLine).name(), afterUserId,
                Math.max(1, Math.min(limit, 5_000)));
    }

    /** 启动保证金快照使用的单表分页。 */
    public List<OrderRecord> activeOrdersForMarginSnapshot(ProductLine productLine,
                                                            long userId,
                                                            long afterOrderId,
                                                            int limit) {
        return jdbcTemplate.query("""
                SELECT *
                  FROM trading_orders
                 WHERE product_line = ?
                   AND user_id = ?
                   AND order_id > ?
                   AND status IN ('ACCEPTED', 'PARTIALLY_FILLED', 'CANCEL_REQUESTED')
                 ORDER BY order_id ASC
                 LIMIT ?
                """, (rs, rowNum) -> toRecord(rs), productLine(productLine).name(), userId, afterOrderId,
                Math.max(1, Math.min(limit, 5_000)));
    }

    public List<OrderRecord> openOrdersByOrderId(ProductLine productLine,
                                                   long userId,
                                                   String symbol,
                                                   long beforeOrderId,
                                                   int limit) {
        String normalizedSymbol = emptyToNull(symbol);
        return jdbcTemplate.query("""
                SELECT *
                  FROM trading_orders
                 WHERE product_line = ?
                   AND user_id = ?
                   AND (CAST(? AS text) IS NULL OR symbol = ?)
                   AND order_id < ?
                   AND status IN ('ACCEPTED', 'PARTIALLY_FILLED')
                 ORDER BY order_id DESC
                 LIMIT ?
                """, (rs, rowNum) -> toRecord(rs), productLine(productLine).name(), userId,
                normalizedSymbol, normalizedSymbol, beforeOrderId, limit);
    }

    public List<OrderRecord> adminOrders(Long userId,
                                         String symbol,
                                         OrderStatus status,
                                         Long orderId,
                                         int limit) {
        return adminOrderPage(userId, symbol, status, orderId, limit, null, null).items();
    }

    public AdminCursorPage.CursorPage<OrderRecord> adminOrderPage(Long userId,
                                                                  String symbol,
                                                                  OrderStatus status,
                                                                  Long orderId,
                                                                  int limit,
                                                                  String cursor,
                                                                  String sort) {
        return adminOrderPage(userId, symbol, status, orderId, limit, null, cursor, sort);
    }

    public AdminCursorPage.CursorPage<OrderRecord> adminOrderPage(Long userId,
                                                                  String symbol,
                                                                  OrderStatus status,
                                                                  Long orderId,
                                                                  int limit,
                                                                  String contractType,
                                                                  String cursor,
                                                                  String sort) {
        String normalizedSymbol = emptyToNull(symbol);
        String normalizedStatus = status == null ? null : status.name();
        String productLine = productLineNameFromContractType(contractType);
        int safeLimit = AdminCursorPage.limit(limit, 1000);
        AdminCursorPage.SortSpec createdAtDesc = new AdminCursorPage.SortSpec(
                "createdAt", "created_at", "order_id", true);
        AdminCursorPage.SortSpec createdAtAsc = new AdminCursorPage.SortSpec(
                "createdAt", "created_at", "order_id", false);
        AdminCursorPage.SortSpec sortSpec = AdminCursorPage.parseSort(
                sort, createdAtDesc, List.of(createdAtDesc, createdAtAsc));
        AdminCursorPage.Cursor decodedCursor = AdminCursorPage.decodeCursor(cursor);
        List<Object> args = new ArrayList<>();
        args.add(userId);
        args.add(userId);
        args.add(normalizedSymbol);
        args.add(normalizedSymbol);
        args.add(normalizedStatus);
        args.add(normalizedStatus);
        args.add(orderId);
        args.add(orderId);
        args.add(productLine);
        args.add(productLine);
        AdminCursorPage.addCursorArgs(args, decodedCursor);
        args.add(safeLimit + 1);
        List<OrderRecord> rows = jdbcTemplate.query("""
                SELECT *
                  FROM trading_orders
                 WHERE (CAST(? AS text) IS NULL OR user_id = ?)
                   AND (CAST(? AS text) IS NULL OR symbol = ?)
                   AND (CAST(? AS text) IS NULL OR status = ?)
                   AND (CAST(? AS text) IS NULL OR order_id = ?)
                   AND (CAST(? AS text) IS NULL OR product_line = ?)
                %s
                 ORDER BY %s %s, %s %s
                 LIMIT ?
                """.formatted(AdminCursorPage.seekCondition(sortSpec, decodedCursor),
                        sortSpec.column(), sortSpec.directionSql(), sortSpec.idColumn(), sortSpec.directionSql()),
                (rs, rowNum) -> toRecord(rs),
                args.toArray());
        return AdminCursorPage.page(rows, safeLimit, sortSpec, OrderRecord::createdAt, OrderRecord::orderId);
    }

    public List<OrderRecord> adminCancelableOrders(Long userId, String symbol, int limit) {
        return adminCancelableOrders(userId, symbol, null, limit);
    }

    public List<OrderRecord> lifecycleCancelableOrders(ProductLine productLine, String symbol, int limit) {
        return jdbcTemplate.query("""
                SELECT *
                  FROM trading_orders
                 WHERE product_line = ?
                   AND symbol = ?
                   AND status IN ('ACCEPTED', 'PARTIALLY_FILLED')
                   AND remaining_quantity_steps > 0
                 ORDER BY created_at, order_id
                 LIMIT ?
                 FOR UPDATE SKIP LOCKED
                """, (rs, rowNum) -> toRecord(rs), productLine(productLine).name(), symbol,
                Math.max(1, Math.min(limit, 1000)));
    }

    public boolean hasLifecycleActiveOrders(ProductLine productLine, String symbol) {
        Boolean active = jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                      FROM trading_orders
                     WHERE product_line = ?
                       AND symbol = ?
                       AND status IN (
                           'PENDING_RESERVE', 'ACCEPTED', 'PARTIALLY_FILLED', 'CANCEL_REQUESTED'
                       )
                       AND remaining_quantity_steps > 0
                )
                """, Boolean.class, productLine(productLine).name(), symbol);
        return Boolean.TRUE.equals(active);
    }

    public List<OrderRecord> adminCancelableOrders(Long userId, String symbol, String contractType, int limit) {
        String normalizedSymbol = emptyToNull(symbol);
        String productLine = productLineNameFromContractType(contractType);
        int safeLimit = Math.max(1, Math.min(limit, 1000));
        return jdbcTemplate.query("""
                SELECT *
                  FROM trading_orders
                 WHERE (CAST(? AS text) IS NULL OR user_id = ?)
                   AND (CAST(? AS text) IS NULL OR symbol = ?)
                   AND (CAST(? AS text) IS NULL OR product_line = ?)
                   AND status IN ('ACCEPTED', 'PARTIALLY_FILLED')
                   AND remaining_quantity_steps > 0
                 ORDER BY created_at ASC, order_id ASC
                 LIMIT ?
                """, (rs, rowNum) -> toRecord(rs),
                userId, userId, normalizedSymbol, normalizedSymbol,
                productLine, productLine, safeLimit);
    }

    public CancelableOrderImpact adminCancelableImpact(Long userId, String symbol) {
        return adminCancelableImpact(userId, symbol, null);
    }

    public CancelableOrderImpact adminCancelableImpact(Long userId, String symbol, String contractType) {
        String normalizedSymbol = emptyToNull(symbol);
        String productLine = productLineNameFromContractType(contractType);
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*)::int AS matched,
                       COALESCE(SUM(remaining_quantity_steps), 0)::bigint AS total_remaining_quantity_steps,
                       COUNT(*) FILTER (WHERE side = 'BUY')::int AS buy_orders,
                       COUNT(*) FILTER (WHERE side = 'SELL')::int AS sell_orders
                 FROM trading_orders
                 WHERE (CAST(? AS text) IS NULL OR user_id = ?)
                   AND (CAST(? AS text) IS NULL OR symbol = ?)
                   AND (CAST(? AS text) IS NULL OR product_line = ?)
                   AND status IN ('ACCEPTED', 'PARTIALLY_FILLED')
                   AND remaining_quantity_steps > 0
                """, (rs, rowNum) -> new CancelableOrderImpact(
                rs.getInt("matched"),
                rs.getLong("total_remaining_quantity_steps"),
                rs.getInt("buy_orders"),
                rs.getInt("sell_orders")), userId, userId, normalizedSymbol, normalizedSymbol,
                productLine, productLine);
    }

    private OrderRecord toRecord(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new OrderRecord(
                rs.getLong("order_id"),
                ProductLine.valueOf(rs.getString("product_line")),
                rs.getLong("user_id"),
                rs.getString("client_order_id"),
                rs.getString("symbol"),
                longOrZero(rs, "instrument_version"),
                OrderSide.valueOf(rs.getString("side")),
                OrderType.valueOf(rs.getString("order_type")),
                TimeInForce.valueOf(rs.getString("time_in_force")),
                rs.getLong("price_ticks"),
                rs.getLong("quantity_steps"),
                rs.getLong("executed_quantity_steps"),
                rs.getLong("remaining_quantity_steps"),
                MarginMode.fromNullableDbValue(rs.getString("margin_mode")),
                PositionSide.fromNullableDbValue(rs.getString("position_side")),
                rs.getLong("maker_fee_rate_ppm"),
                rs.getLong("taker_fee_rate_ppm"),
                rs.getBoolean("reduce_only"),
                rs.getBoolean("post_only"),
                rs.getString("reservation_account_type"),
                rs.getString("reservation_asset"),
                rs.getLong("reserved_units"),
                OrderStatus.valueOf(rs.getString("status")),
                rs.getString("reject_reason"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                rs.getLong("revision"));
    }

    private String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String productLineNameFromContractType(String contractType) {
        String normalizedFilter = emptyToNull(contractType);
        return normalizedFilter == null
                ? null
                : ProductLine.requireExternalCode(normalizedFilter).name();
    }

    private Long nullableVersion(long version) {
        return version <= 0 ? null : version;
    }

    private long longOrZero(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? 0L : value;
    }

    private String tradingSequenceIdentifier(String sequenceName) {
        if (sequenceName == null || !sequenceName.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")) {
            throw new IllegalArgumentException("invalid trading sequence name: " + sequenceName);
        }
        return "public.trading_" + sequenceName.toLowerCase().replace('-', '_') + "_seq";
    }

    private void requireCompleteBatch(int[] rows, int expected, String operation) {
        if (rows == null || rows.length != expected) {
            throw new IllegalStateException("failed to write " + operation);
        }
        for (int row : rows) {
            if (row != 1 && row != Statement.SUCCESS_NO_INFO) {
                throw new IllegalStateException("failed to write " + operation);
            }
        }
    }

    public record ReservationCompletion(
            long orderId,
            boolean accepted,
            String rejectReason,
            Instant completedAt) {
    }

    public record CancelableOrderImpact(
            int matched,
            long totalRemainingQuantitySteps,
            int buyOrders,
            int sellOrders) {
    }
}
