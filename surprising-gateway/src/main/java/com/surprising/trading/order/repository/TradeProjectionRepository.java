package com.surprising.trading.order.repository;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;

/** Admin history reads the realtime-owned committed execution table only. */
@Repository
public final class TradeProjectionRepository {
    private final JdbcTemplate jdbc;
    public TradeProjectionRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public AdminTradeQueryResponse search(ProductLine product, Long userId, String instrumentId,
            Long orderId, int limit, String cursor, String sort) {
        if (product == null || userId != null && userId <= 0 || orderId != null && orderId <= 0 || limit < 1 || limit > 1000)
            throw new IllegalArgumentException("invalid trade query");
        if (instrumentId != null && !com.surprising.product.api.InstrumentIds.valid(instrumentId))
            throw new IllegalArgumentException("invalid instrumentId");
        String ordering = sort == null || sort.isBlank() ? "eventTime.desc" : sort.trim();
        if (!Set.of("eventTime.asc", "eventTime.desc").contains(ordering)) throw new IllegalArgumentException("invalid trade sort");
        String scope = digest(product.name() + '|' + userId + '|' + instrumentId + '|' + orderId + '|' + ordering);
        long[] after = cursor == null || cursor.isBlank() ? null : decodeCursor(cursor, scope);
        var sql = new StringBuilder("SELECT * FROM core_execution_projection WHERE product_line=?");
        var args = new ArrayList<Object>(); args.add(product.name());
        if (userId != null && orderId != null) {
            // User and order must identify the same execution side.
            sql.append(" AND ((taker_user_id=? AND taker_order_id=?) OR (maker_user_id=? AND maker_order_id=?))");
            args.add(userId); args.add(orderId); args.add(userId); args.add(orderId);
        } else if (userId != null) {
            sql.append(" AND (taker_user_id=? OR maker_user_id=?)"); args.add(userId); args.add(userId);
        } else if (orderId != null) {
            sql.append(" AND (taker_order_id=? OR maker_order_id=?)"); args.add(orderId); args.add(orderId);
        }
        if (instrumentId != null) { sql.append(" AND instrument_id=?"); args.add(instrumentId); }
        boolean ascending = ordering.endsWith("asc");
        if (after != null) {
            sql.append(" AND (occurred_at_epoch_ms,export_sequence,execution_index) ").append(ascending ? ">" : "<").append(" (?,?,?)");
            for (long value : after) args.add(value);
        }
        String direction = ascending ? " ASC" : " DESC";
        sql.append(" ORDER BY occurred_at_epoch_ms").append(direction).append(",export_sequence").append(direction)
                .append(",execution_index").append(direction).append(" LIMIT ?"); args.add(limit + 1);
        try {
            if (!Integer.valueOf(1).equals(jdbc.queryForObject("SELECT count(*) FROM core_execution_projection_initialization WHERE product_line=?", Integer.class, product.name())))
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "TRADE_READ_VIEW_INITIALIZING");
            var values = jdbc.query(sql.toString(), (rs, row) -> {
                try {
                    var value = new AdminTradeResponse(rs.getString("trade_id"), product, rs.getString("instrument_id"),
                            OrderSide.valueOf(rs.getString("taker_side")), PositionSide.valueOf(rs.getString("taker_position_side")),
                            PositionSide.valueOf(rs.getString("maker_position_side")), rs.getLong("price_ticks"), rs.getLong("quantity_steps"),
                            rs.getLong("taker_order_id"), rs.getLong("maker_order_id"), rs.getLong("taker_user_id"), rs.getLong("maker_user_id"),
                            rs.getLong("taker_fee_rate_ppm"), rs.getLong("maker_fee_rate_ppm"), Instant.ofEpochMilli(rs.getLong("occurred_at_epoch_ms")),
                            rs.getLong("export_sequence"), rs.getInt("execution_index"));
                    if (value.tradeId() == null || !value.tradeId().startsWith(product.name() + ":") || value.priceTicks() <= 0
                            || value.quantitySteps() <= 0 || value.takerUserId() <= 0 || value.makerUserId() <= 0
                            || value.takerOrderId() <= 0 || value.makerOrderId() <= 0 || value.clusterPosition() <= 0
                            || value.executionIndex() < 0 || value.eventTime().toEpochMilli() <= 0)
                        throw new IllegalStateException("invalid committed execution");
                    return value;
                } catch (RuntimeException invalid) {
                    throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "TRADE_READ_VIEW_INVALID", invalid);
                }
            }, args.toArray());
            boolean more = values.size() > limit;
            var page = List.copyOf(more ? values.subList(0, limit) : values);
            var last = page.isEmpty() ? null : page.getLast();
            String next = !more ? null : Base64.getUrlEncoder().withoutPadding().encodeToString(
                    ("trade-v1:" + scope + ':' + last.eventTime().toEpochMilli() + ':' + last.clusterPosition() + ':' + last.executionIndex()).getBytes(StandardCharsets.UTF_8));
            return new AdminTradeQueryResponse(product, page.size(), page, next, more, ordering, limit);
        } catch (DataAccessException unavailable) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "TRADE_READ_VIEW_UNAVAILABLE", unavailable);
        }
    }

    private static long[] decodeCursor(String cursor, String scope) {
        try {
            var parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split(":", -1);
            if (parts.length != 5 || !parts[0].equals("trade-v1") || !parts[1].equals(scope)) throw new IllegalArgumentException();
            long time = Long.parseLong(parts[2]), position = Long.parseLong(parts[3]), index = Long.parseLong(parts[4]);
            if (time <= 0 || position <= 0 || index < 0 || index > Integer.MAX_VALUE) throw new IllegalArgumentException();
            return new long[]{time, position, index};
        } catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("trade cursor does not match query", invalid); }
    }
    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
