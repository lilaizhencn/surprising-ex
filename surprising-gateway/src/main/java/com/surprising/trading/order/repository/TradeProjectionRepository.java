package com.surprising.trading.order.repository;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.*;
import com.surprising.trading.order.model.TradeReadCursor;
import com.surprising.trading.order.utils.TradeReadCursors;
import java.time.Instant;
import java.util.*;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import com.surprising.realtime.api.exception.ReadViewUnavailableException;

/** User fills and admin history read the realtime-owned committed execution table only. */
@Repository
public final class TradeProjectionRepository {
    private final JdbcTemplate jdbc;
    public TradeProjectionRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public TradeFillQueryResponse ownFills(ProductLine product, long userId, String instrumentId,
            Long orderId, Long startTime, Long endTime, int limit, TradeReadCursor.Fill after) {
        String scope = TradeReadCursors.fillScope(product,userId,instrumentId,orderId,startTime,endTime);
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
            args.add(after.time()); args.add(after.position());
            args.add(after.index()); args.add(after.role());
        }
        sql.append(" ORDER BY occurred_at_epoch_ms DESC,export_sequence DESC,execution_index DESC,liquidity_role DESC LIMIT ?");
        args.add(limit + 1);
        try {
            var values = readAssociated(product, sql.toString(), "occurred_at_epoch_ms DESC,export_sequence DESC,execution_index DESC,liquidity_role DESC", (rs, row) -> {
                try {
                    var fill = new TradeFillResponse(rs.getString("trade_id"), product, rs.getString("instrument_id"),
                            rs.getLong("order_id"), OrderSide.valueOf(rs.getString("side")), PositionSide.valueOf(rs.getString("position_side")),
                            rs.getString("liquidity_role"), rs.getLong("price_ticks"), rs.getLong("quantity_steps"),
                            rs.getLong("fee_rate_ppm"), Instant.ofEpochMilli(rs.getLong("occurred_at_epoch_ms")));
                    long sequence = rs.getLong("export_sequence"); int index = rs.getInt("execution_index");
                    if (fill.tradeId() == null || !fill.tradeId().startsWith(product.name() + ":")
                            || !com.surprising.product.api.utils.InstrumentIds.valid(fill.instrumentId()) || fill.orderId() <= 0
                            || fill.priceTicks() <= 0 || fill.quantitySteps() <= 0 || fill.feeRatePpm() < -1_000_000
                            || fill.feeRatePpm() > 1_000_000 || fill.eventTime().toEpochMilli() <= 0 || sequence <= 0 || index < 0)
                        throw new IllegalStateException("invalid committed private fill");
                    return new ProjectedFill(fill, sequence, index);
                } catch (RuntimeException invalid) {
                    throw new ReadViewUnavailableException("TRADE_READ_VIEW_INVALID", invalid);
                }
            }, args.toArray());
            boolean more = values.size() > limit;
            var page = more ? values.subList(0, limit) : values;
            var last = page.isEmpty() ? null : page.getLast();
            String next = !more ? null : TradeReadCursors.encode(scope,
                    new TradeReadCursor.Fill(last.fill().eventTime().toEpochMilli(),last.sequence(),last.index(),last.fill().liquidityRole()));
            return new TradeFillQueryResponse(userId, product, page.size(), page.stream().map(ProjectedFill::fill).toList(), next, more, "eventTime.desc", limit);
        } catch (DataAccessException unavailable) {
            throw new ReadViewUnavailableException("TRADE_READ_VIEW_UNAVAILABLE", unavailable);
        }
    }

    /** SQL cursor coordinates stay at this query boundary, outside the public fill. */
    private record ProjectedFill(TradeFillResponse fill, long sequence, int index) {}

    public AdminTradeQueryResponse search(ProductLine product, Long userId, String instrumentId,
            Long orderId, int limit, TradeReadCursor.Execution after, boolean ascending) {
        String ordering = ascending ? "eventTime.asc" : "eventTime.desc";
        String scope = TradeReadCursors.executionScope(product,userId,instrumentId,orderId,ascending);
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
        if (after != null) {
            sql.append(" AND (occurred_at_epoch_ms,export_sequence,execution_index) ").append(ascending ? ">" : "<").append(" (?,?,?)");
            args.add(after.time()); args.add(after.position()); args.add(after.index());
        }
        String direction = ascending ? " ASC" : " DESC";
        sql.append(" ORDER BY occurred_at_epoch_ms").append(direction).append(",export_sequence").append(direction)
                .append(",execution_index").append(direction).append(" LIMIT ?"); args.add(limit + 1);
        try {
            var values = readAssociated(product, sql.toString(), "occurred_at_epoch_ms" + direction + ",export_sequence" + direction + ",execution_index" + direction, (rs, row) -> {
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
                    throw new ReadViewUnavailableException("TRADE_READ_VIEW_INVALID", invalid);
                }
            }, args.toArray());
            boolean more = values.size() > limit;
            var page = List.copyOf(more ? values.subList(0, limit) : values);
            var last = page.isEmpty() ? null : page.getLast();
            String next = !more ? null : TradeReadCursors.encode(scope,
                    new TradeReadCursor.Execution(last.eventTime().toEpochMilli(),last.clusterPosition(),last.executionIndex()));
            return new AdminTradeQueryResponse(product, page.size(), page, next, more, ordering, limit);
        } catch (DataAccessException unavailable) {
            throw new ReadViewUnavailableException("TRADE_READ_VIEW_UNAVAILABLE", unavailable);
        }
    }

    /** 必要关联只读：core_execution_projection_initialization按同产品确认本投影已完整初始化。
     * 标记与数据必须由同一SQL快照读取，避免重建时把旧READY与新空数据混合；
     * LEFT JOIN保留未就绪/已就绪但无数据的区别。仅关联完成性标记，不聚合业务或写其他表。 */
    private <T> List<T> readAssociated(ProductLine product, String sql, String orderBy,
            org.springframework.jdbc.core.RowMapper<T> mapper, Object... values) {
        String associated = """
                SELECT initialized.__projection_ready, projection_rows.*
                  FROM (SELECT count(*)=1 AS __projection_ready FROM core_execution_projection_initialization WHERE product_line=?) initialized
                  LEFT JOIN LATERAL (%s) projection_rows ON initialized.__projection_ready
                 ORDER BY %s
                """.formatted(sql, orderBy);
        Object[] arguments = new Object[values.length + 1];
        arguments[0] = product.name();
        System.arraycopy(values, 0, arguments, 1, values.length);
        return jdbc.query(associated, (rs, row) -> {
            if (!rs.getBoolean("__projection_ready")) throw new ReadViewUnavailableException("TRADE_READ_VIEW_INITIALIZING");
            if (rs.getObject("trade_id") == null) return null;
            return mapper.mapRow(rs, row);
        }, arguments).stream().filter(Objects::nonNull).toList();
    }
}
