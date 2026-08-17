package com.surprising.trading.order.repository;

import com.surprising.aeron.protocol.CoreStateQueryCodec;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.OrderResponse;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.OrderStatus;
import com.surprising.trading.api.model.OrderType;
import com.surprising.trading.api.model.PositionSide;
import com.surprising.trading.api.model.TimeInForce;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

@Repository
public class AeronOrderProjectionRepository {

    public static final int DEFAULT_MAX_ENCODED_RESPONSE_BYTES = 1_048_576;
    public static final int MAX_ENCODED_RESPONSE_BYTES = 4 * 1_048_576;
    private static final String CURSOR_PREFIX = "order:v1:";

    private final JdbcTemplate jdbcTemplate;
    private final ProjectionWatermarkWaiter watermarkWaiter;
    private final ObjectMapper objectMapper;
    private final int maxEncodedResponseBytes;

    @Autowired
    public AeronOrderProjectionRepository(
            JdbcTemplate jdbcTemplate,
            ProjectionWatermarkWaiter watermarkWaiter,
            ObjectMapper objectMapper,
            @Value("${surprising.trading.order.projection.max-encoded-bytes:1048576}")
            int maxEncodedResponseBytes) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
        this.watermarkWaiter = Objects.requireNonNull(watermarkWaiter, "watermarkWaiter");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.maxEncodedResponseBytes = validateMaxEncodedResponseBytes(maxEncodedResponseBytes);
    }

    public AeronOrderProjectionRepository(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, new ProjectionWatermarkWaiter(jdbcTemplate), new ObjectMapper(),
                DEFAULT_MAX_ENCODED_RESPONSE_BYTES);
    }

    public AeronOrderProjectionRepository(JdbcTemplate jdbcTemplate, int maxEncodedResponseBytes) {
        this(jdbcTemplate, new ProjectionWatermarkWaiter(jdbcTemplate), new ObjectMapper(),
                maxEncodedResponseBytes);
    }

    public AeronOrderProjectionRepository(JdbcTemplate jdbcTemplate,
                                          ProjectionWatermarkWaiter watermarkWaiter) {
        this(jdbcTemplate, watermarkWaiter, new ObjectMapper(), DEFAULT_MAX_ENCODED_RESPONSE_BYTES);
    }

    public AeronOrderProjectionRepository(JdbcTemplate jdbcTemplate,
                                          ProjectionWatermarkWaiter watermarkWaiter,
                                          int maxEncodedResponseBytes) {
        this(jdbcTemplate, watermarkWaiter, new ObjectMapper(), maxEncodedResponseBytes);
    }

    public ProjectionReadResult byOrder(ProductLine productLine, Long userId, long orderId,
                                        Long minExportSequence) {
        if (userId != null) requireUserId(userId);
        requirePositive(orderId, "orderId");
        return execute(productLine, minExportSequence, Query.byOrder(userId, orderId));
    }

    public ProjectionReadResult byClientOrderId(ProductLine productLine, long userId,
                                                String clientOrderId, Long minExportSequence) {
        requireUserId(userId);
        if (clientOrderId == null || clientOrderId.isBlank()) {
            throw new IllegalArgumentException("clientOrderId is required");
        }
        return execute(productLine, minExportSequence,
                Query.byClient(userId, clientOrderId.trim()));
    }

    public ProjectionReadResult openOrders(ProductLine productLine, Long userId, String symbol,
                                           String cursor, int limit, Long minExportSequence) {
        if (userId != null) requireUserId(userId);
        requireLimit(limit);
        return execute(productLine, minExportSequence,
                Query.open(userId, symbol, decodeCursor(cursor), limit));
    }

    public ProjectionReadResult historyOrders(ProductLine productLine, long userId, String symbol,
                                              int limit, Long minimumOrderId, Long startTimeMillis,
                                              Long endTimeMillis, String cursor, Long minExportSequence) {
        requireUserId(userId);
        requireLimit(limit);
        if (minimumOrderId != null) requirePositive(minimumOrderId, "orderId");
        if (startTimeMillis != null && startTimeMillis < 0) {
            throw new IllegalArgumentException("startTime must be non-negative");
        }
        if (endTimeMillis != null && endTimeMillis < 0) {
            throw new IllegalArgumentException("endTime must be non-negative");
        }
        if (startTimeMillis != null && endTimeMillis != null && startTimeMillis > endTimeMillis) {
            throw new IllegalArgumentException("startTime must not be after endTime");
        }
        return execute(productLine, minExportSequence,
                Query.history(userId, symbol, minimumOrderId, startTimeMillis, endTimeMillis,
                        decodeCursor(cursor), limit));
    }

    public ProjectionReadResult search(ProductLine productLine, Long userId, String symbol,
                                       OrderStatus status, Long orderId, String cursor,
                                       boolean ascending, int limit, Long minExportSequence) {
        if (userId != null) requireUserId(userId);
        if (orderId != null) requirePositive(orderId, "orderId");
        requireLimit(limit);
        return execute(productLine, minExportSequence,
                Query.search(userId, symbol, status, orderId, decodeCursor(cursor), ascending, limit));
    }

    private ProjectionReadResult execute(ProductLine productLine, Long minExportSequence, Query query) {
        requireProductLine(productLine);
        long required = minExportSequence == null ? 0L : minExportSequence;
        if (required < 0L) {
            throw new IllegalArgumentException("minExportSequence must not be negative");
        }
        ProjectionReadResult readiness = minExportSequence == null
                ? ProjectionReadResult.ok(List.of(), null, false, 0L, 0L)
                : watermarkWaiter.await(productLine, required,
                ProjectionWatermarkWaiter.DEFAULT_MAX_WAIT_MS);
        if (!readiness.ready()) return readiness;

        List<Object> arguments = new ArrayList<>(query.arguments());
        arguments.set(0, productLine.name());
        List<ProjectionRow> candidates = jdbcTemplate.query(query.sql(), rowMapper(), arguments.toArray());
        List<OrderResponse> orders = new ArrayList<>();
        String nextCursor = null;
        int encodedBytes = 2;
        boolean hasMore = false;
        for (int index = 0; index < candidates.size(); index++) {
            ProjectionRow candidate = candidates.get(index);
            List<OrderResponse> next = new ArrayList<>(orders);
            next.add(candidate.order());
            int nextBytes = encodedBytes(next);
            if (nextBytes > maxEncodedResponseBytes && !orders.isEmpty()) {
                hasMore = true;
                break;
            }
            if (nextBytes > maxEncodedResponseBytes) {
                return ProjectionReadResult.responseTooLarge(readiness.observedExportSequence(), required,
                        encodeCursor(candidate.cursor().updatedAtEpochMillis(), candidate.cursor().orderId()));
            }
            orders.add(candidate.order());
            encodedBytes = nextBytes;
            nextCursor = encodeCursor(candidate.cursor().updatedAtEpochMillis(), candidate.cursor().orderId());
            if (orders.size() == query.limit()) {
                hasMore = candidates.size() > index + 1;
                break;
            }
        }
        if (!hasMore) nextCursor = null;
        return ProjectionReadResult.ok(orders, hasMore ? nextCursor : null, hasMore,
                readiness.observedExportSequence(), required, encodedBytes);
    }

    private RowMapper<ProjectionRow> rowMapper() {
        return (result, rowNum) -> {
            byte[] raw = result.getBytes("raw_order_state");
            return new ProjectionRow(map(raw),
                    new Cursor(result.getLong("updated_at_epoch_ms"), result.getLong("order_id")));
        };
    }

    private int encodedBytes(List<OrderResponse> orders) {
        try {
            return objectMapper.writeValueAsBytes(orders).length;
        } catch (Exception exception) {
            throw new IllegalStateException("failed to encode projected order response", exception);
        }
    }

    public static String encodeCursor(long updatedAtEpochMillis, long orderId) {
        if (updatedAtEpochMillis < 0L || orderId <= 0L) {
            throw new IllegalArgumentException("invalid order cursor");
        }
        String value = CURSOR_PREFIX + updatedAtEpochMillis + ':' + orderId;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    public static Cursor decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) return null;
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            if (!decoded.startsWith(CURSOR_PREFIX)) throw new IllegalArgumentException("invalid order cursor");
            String[] parts = decoded.substring(CURSOR_PREFIX.length()).split(":", -1);
            if (parts.length != 2) throw new IllegalArgumentException("invalid order cursor");
            return new Cursor(Long.parseLong(parts[0]), Long.parseLong(parts[1]));
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("invalid order cursor", exception);
        }
    }

    private static OrderResponse map(byte[] raw) {
        var view = CoreStateQueryCodec.decodeOrderState(raw);
        OrderStatus status = "OPEN".equals(view.status())
                ? (view.executedQuantitySteps() == 0 ? OrderStatus.ACCEPTED : OrderStatus.PARTIALLY_FILLED)
                : OrderStatus.valueOf(view.status());
        return new OrderResponse(view.orderId(), view.userId(), emptyToNull(view.clientOrderId()), view.symbol(),
                view.instrumentVersion(), OrderSide.valueOf(view.side().name()), OrderType.valueOf(view.orderType().name()),
                TimeInForce.valueOf(view.timeInForce().name()), view.priceTicks(), view.quantitySteps(),
                view.executedQuantitySteps(), view.remainingQuantitySteps(), MarginMode.valueOf(view.marginMode().name()),
                PositionSide.valueOf(view.positionSide().name()), view.makerFeeRatePpm(), view.takerFeeRatePpm(),
                view.reduceOnly(), view.postOnly(), status, null,
                Instant.ofEpochMilli(view.createdAtEpochMillis()), Instant.ofEpochMilli(view.updatedAtEpochMillis()));
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    private static int validateMaxEncodedResponseBytes(int value) {
        if (value < 1 || value > MAX_ENCODED_RESPONSE_BYTES) {
            throw new IllegalArgumentException("maxEncodedResponseBytes must be in [1,4MiB]");
        }
        return value;
    }

    private static void requireProductLine(ProductLine productLine) {
        if (productLine == null) throw new IllegalArgumentException("productLine is required");
    }

    private static void requireUserId(long userId) {
        if (userId <= 0L) throw new IllegalArgumentException("userId must be positive");
    }

    private static void requirePositive(long value, String field) {
        if (value <= 0L) throw new IllegalArgumentException(field + " must be positive");
    }

    private static void requireLimit(int limit) {
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("limit must be in [1, 1000]");
    }

    public record Cursor(long updatedAtEpochMillis, long orderId) {
        public Cursor {
            if (updatedAtEpochMillis < 0L || orderId <= 0L) throw new IllegalArgumentException("invalid order cursor");
        }
    }

    private record ProjectionRow(OrderResponse order, Cursor cursor) {
    }

    private record Query(String sql, List<Object> arguments, int limit) {
        private static Query byOrder(Long userId, long orderId) {
            return new Query(base(userId) + " AND order_id = ? LIMIT 1", args(userId, orderId), 1);
        }

        private static Query byClient(Long userId, String clientOrderId) {
            return new Query(base(userId) + " AND client_order_id = ? LIMIT 1",
                    args(userId, clientOrderId), 1);
        }

        private static Query open(Long userId, String symbol, Cursor cursor, int limit) {
            StringBuilder sql = new StringBuilder(base(userId)).append(" AND status = 'OPEN'");
            List<Object> arguments = new ArrayList<>(args(userId));
            appendSymbol(sql, arguments, symbol);
            appendCursor(sql, arguments, cursor, false);
            sql.append(" ORDER BY updated_at_epoch_ms DESC, order_id DESC LIMIT ?");
            arguments.add(limit + 1);
            return new Query(sql.toString(), arguments, limit);
        }

        private static Query history(Long userId, String symbol, Long minimumOrderId,
                                     Long startTimeMillis, Long endTimeMillis, Cursor cursor, int limit) {
            StringBuilder sql = new StringBuilder(base(userId)).append(" AND status <> 'OPEN'");
            List<Object> arguments = new ArrayList<>(args(userId));
            appendSymbol(sql, arguments, symbol);
            append(sql, arguments, " AND order_id >= ?", minimumOrderId);
            append(sql, arguments, " AND created_at_epoch_ms >= ?", startTimeMillis);
            append(sql, arguments, " AND created_at_epoch_ms <= ?", endTimeMillis);
            appendCursor(sql, arguments, cursor, false);
            sql.append(" ORDER BY updated_at_epoch_ms DESC, order_id DESC LIMIT ?");
            arguments.add(limit + 1);
            return new Query(sql.toString(), arguments, limit);
        }

        private static Query search(Long userId, String symbol, OrderStatus status, Long orderId,
                                    Cursor cursor, boolean ascending, int limit) {
            StringBuilder sql = new StringBuilder(base(userId));
            List<Object> arguments = new ArrayList<>(args(userId));
            appendSymbol(sql, arguments, symbol);
            if (status != null) append(sql, arguments, " AND status = ?", coreStatus(status));
            append(sql, arguments, " AND order_id = ?", orderId);
            appendCursor(sql, arguments, cursor, ascending);
            sql.append(" ORDER BY updated_at_epoch_ms ").append(ascending ? "ASC" : "DESC")
                    .append(", order_id ").append(ascending ? "ASC" : "DESC").append(" LIMIT ?");
            arguments.add(limit + 1);
            return new Query(sql.toString(), arguments, limit);
        }

        private static String base(Long userId) {
            StringBuilder sql = new StringBuilder("SELECT order_id, updated_at_epoch_ms, raw_order_state "
                    + "FROM core_order_projection WHERE product_line = ?");
            return userId == null ? sql.toString() : sql + " AND user_id = ?";
        }

        private static List<Object> args(Long userId, Object... values) {
            List<Object> arguments = new ArrayList<>();
            arguments.add(null);
            if (userId != null) arguments.add(userId);
            arguments.addAll(List.of(values));
            return arguments;
        }

        private static void appendSymbol(StringBuilder sql, List<Object> arguments, String symbol) {
            append(sql, arguments, " AND symbol = ?", symbol == null || symbol.isBlank() ? null : symbol);
        }

        private static void append(StringBuilder sql, List<Object> arguments, String clause, Object value) {
            if (value != null) {
                sql.append(clause);
                arguments.add(value);
            }
        }

        private static void appendCursor(StringBuilder sql, List<Object> arguments, Cursor cursor,
                                         boolean ascending) {
            if (cursor == null) return;
            String operator = ascending ? ">" : "<";
            sql.append(" AND (updated_at_epoch_ms ").append(operator)
                    .append(" ? OR (updated_at_epoch_ms = ? AND order_id ").append(operator).append(" ?))");
            arguments.add(cursor.updatedAtEpochMillis());
            arguments.add(cursor.updatedAtEpochMillis());
            arguments.add(cursor.orderId());
        }

        private static String coreStatus(OrderStatus status) {
            return switch (status) {
                case ACCEPTED, PARTIALLY_FILLED -> "OPEN";
                default -> status.name();
            };
        }
    }
}
