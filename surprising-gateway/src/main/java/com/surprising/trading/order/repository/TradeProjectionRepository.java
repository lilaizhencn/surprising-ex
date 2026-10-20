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

/** User fills and admin history read the realtime-owned committed execution table only. */
@Repository
public final class TradeProjectionRepository {
    private final JdbcTemplate jdbc;
    public TradeProjectionRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public TradeFillQueryResponse ownFills(ProductLine product, long userId, String instrumentId,
            Long orderId, Long startTime, Long endTime, int limit, String cursor) {
        if (product == null || userId <= 0 || orderId != null && orderId <= 0 || limit < 1 || limit > 1000
                || startTime != null && startTime <= 0 || endTime != null && endTime <= 0
                || startTime != null && endTime != null && startTime > endTime)
            throw new IllegalArgumentException("invalid private fill query");
        if (instrumentId != null && !com.surprising.product.api.InstrumentIds.valid(instrumentId))
            throw new IllegalArgumentException("invalid instrumentId");
        String scope = digest(product.name() + '|' + userId + '|' + instrumentId + '|' + orderId + '|' + startTime + '|' + endTime);
        String[] after = cursor == null || cursor.isBlank() ? null : decodeFillCursor(cursor, scope);
        // Each branch selects only this account's side. Self-trades intentionally
        // produce two fills, with independent order, side and historical fee rate.
        var sql = new StringBuilder("""
                SELECT * FROM (
                  SELECT trade_id,instrument_id,price_ticks,quantity_steps,occurred_at_epoch_ms,export_sequence,execution_index,
                         taker_order_id AS order_id,taker_side AS side,taker_position_side AS position_side,
                         taker_fee_rate_ppm AS fee_rate_ppm,'TAKER' AS liquidity_role
                    FROM core_execution_projection WHERE product_line=? AND taker_user_id=?
                  UNION ALL
                  SELECT trade_id,instrument_id,price_ticks,quantity_steps,occurred_at_epoch_ms,export_sequence,execution_index,
                         maker_order_id AS order_id,CASE taker_side WHEN 'BUY' THEN 'SELL' WHEN 'SELL' THEN 'BUY' END AS side,
                         maker_position_side AS position_side,maker_fee_rate_ppm AS fee_rate_ppm,'MAKER' AS liquidity_role
                    FROM core_execution_projection WHERE product_line=? AND maker_user_id=?
                ) own_fills WHERE true
                """);
        var args = new ArrayList<Object>(List.of(product.name(), userId, product.name(), userId));
        if (instrumentId != null) { sql.append(" AND instrument_id=?"); args.add(instrumentId); }
        if (orderId != null) { sql.append(" AND order_id=?"); args.add(orderId); }
        if (startTime != null) { sql.append(" AND occurred_at_epoch_ms>=?"); args.add(startTime); }
        if (endTime != null) { sql.append(" AND occurred_at_epoch_ms<=?"); args.add(endTime); }
        if (after != null) {
            sql.append(" AND (occurred_at_epoch_ms,export_sequence,execution_index,liquidity_role)<(?,?,?,?)");
            args.add(Long.parseLong(after[0])); args.add(Long.parseLong(after[1]));
            args.add(Integer.parseInt(after[2])); args.add(after[3]);
        }
        sql.append(" ORDER BY occurred_at_epoch_ms DESC,export_sequence DESC,execution_index DESC,liquidity_role DESC LIMIT ?");
        args.add(limit + 1);
        try {
            if (!Integer.valueOf(1).equals(jdbc.queryForObject("SELECT count(*) FROM core_execution_projection_initialization WHERE product_line=?", Integer.class, product.name())))
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "TRADE_READ_VIEW_INITIALIZING");
            var values = jdbc.query(sql.toString(), (rs, row) -> {
                try {
                    var fill = new TradeFillResponse(rs.getString("trade_id"), product, rs.getString("instrument_id"),
                            rs.getLong("order_id"), OrderSide.valueOf(rs.getString("side")), PositionSide.valueOf(rs.getString("position_side")),
                            rs.getString("liquidity_role"), rs.getLong("price_ticks"), rs.getLong("quantity_steps"),
                            rs.getLong("fee_rate_ppm"), Instant.ofEpochMilli(rs.getLong("occurred_at_epoch_ms")));
                    long sequence = rs.getLong("export_sequence"); int index = rs.getInt("execution_index");
                    if (fill.tradeId() == null || !fill.tradeId().startsWith(product.name() + ":")
                            || !com.surprising.product.api.InstrumentIds.valid(fill.instrumentId()) || fill.orderId() <= 0
                            || fill.priceTicks() <= 0 || fill.quantitySteps() <= 0 || fill.feeRatePpm() < -1_000_000
                            || fill.feeRatePpm() > 1_000_000 || fill.eventTime().toEpochMilli() <= 0 || sequence <= 0 || index < 0)
                        throw new IllegalStateException("invalid committed private fill");
                    return new ProjectedFill(fill, sequence, index);
                } catch (RuntimeException invalid) {
                    throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "TRADE_READ_VIEW_INVALID", invalid);
                }
            }, args.toArray());
            boolean more = values.size() > limit;
            var page = more ? values.subList(0, limit) : values;
            var last = page.isEmpty() ? null : page.getLast();
            String next = !more ? null : Base64.getUrlEncoder().withoutPadding().encodeToString(
                    ("user-fill-v1:" + scope + ':' + last.fill().eventTime().toEpochMilli() + ':' + last.sequence()
                            + ':' + last.index() + ':' + last.fill().liquidityRole()).getBytes(StandardCharsets.UTF_8));
            return new TradeFillQueryResponse(userId, product, page.size(), page.stream().map(ProjectedFill::fill).toList(), next, more, "eventTime.desc", limit);
        } catch (DataAccessException unavailable) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "TRADE_READ_VIEW_UNAVAILABLE", unavailable);
        }
    }

    /** SQL cursor coordinates stay at this query boundary, outside the public fill. */
    private record ProjectedFill(TradeFillResponse fill, long sequence, int index) {}

    private static String[] decodeFillCursor(String cursor, String scope) {
        try {
            var parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split(":", -1);
            if (parts.length != 6 || !parts[0].equals("user-fill-v1") || !parts[1].equals(scope)) throw new IllegalArgumentException();
            long time = Long.parseLong(parts[2]), sequence = Long.parseLong(parts[3]), index = Long.parseLong(parts[4]);
            if (time <= 0 || sequence <= 0 || index < 0 || index > Integer.MAX_VALUE || !Set.of("MAKER", "TAKER").contains(parts[5]))
                throw new IllegalArgumentException();
            return new String[]{parts[2], parts[3], parts[4], parts[5]};
        } catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("fill cursor does not match query", invalid); }
    }

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
