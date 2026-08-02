package com.surprising.trading.order.repository;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.order.model.OrderRecord;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 订单数据库投影仓储，只负责 {@code trading_orders} 表和投影所需的序列。
 *
 * <p>订单下单、撤单、预占结果和撮合结果均由用户分区 WAL/RocksDB 裁决，本仓储没有任何
 * 在线状态查询或状态更新方法，避免数据库重新成为事实源。</p>
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

    /** 仅供费率配置和异步通知投影分配不重复的技术编号。 */
    public long nextSequence(String sequenceName) {
        Long value = jdbcTemplate.queryForObject("SELECT nextval(CAST(? AS regclass))", Long.class,
                tradingSequenceIdentifier(sequenceName));
        if (value == null) {
            throw new IllegalStateException("无法分配数据库技术序列: " + sequenceName);
        }
        return value;
    }

    /** 批量分配技术编号；不参与订单状态裁决。 */
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
            throw new IllegalStateException("无法批量分配数据库技术序列: " + sequenceName);
        }
        return List.copyOf(values);
    }

    /** 写入一条完整订单投影；调用方必须处于异步投影事务中。 */
    public boolean insert(OrderRecord order) {
        if (order == null) {
            throw new IllegalArgumentException("订单投影不能为空");
        }
        int rows = jdbcTemplate.update(INSERT_ORDER_SQL,
                order.orderId(), order.productLine().name(), order.userId(), emptyToNull(order.clientOrderId()),
                order.symbol(), nullableVersion(order.instrumentVersion()), order.side().name(),
                order.orderType().name(), order.timeInForce().name(), order.priceTicks(), order.quantitySteps(),
                order.executedQuantitySteps(), order.remainingQuantitySteps(), order.marginMode().name(),
                order.positionSide().name(), order.makerFeeRatePpm(), order.takerFeeRatePpm(), order.reduceOnly(),
                order.postOnly(), order.reservationAccountType(), order.reservationAsset(), order.reservedUnits(),
                order.status().name(), order.rejectReason(), Timestamp.from(order.createdAt()),
                Timestamp.from(order.updatedAt()), order.revision());
        return rows == 1;
    }

    /**
     * 用用户分区 RocksDB 完整快照替换订单数据库投影。
     *
     * <p>事务提交成功后，调用方才可以推进 WAL 投影水位；重复执行是安全的。</p>
     */
    public void replaceProjection(ProductLine productLine, long userId, List<OrderRecord> orders) {
        ProductLine line = requireProductLine(productLine);
        if (userId <= 0L) {
            throw new IllegalArgumentException("订单投影用户编号必须为正数");
        }
        jdbcTemplate.update("""
                DELETE FROM trading_orders
                 WHERE product_line = ? AND user_id = ?
                """, line.name(), userId);
        if (orders == null) {
            return;
        }
        for (OrderRecord order : orders) {
            if (order == null || order.productLine() != line || order.userId() != userId) {
                throw new IllegalArgumentException("订单投影分区元数据不一致");
            }
            if (!insert(order)) {
                throw new IllegalStateException("订单投影写入失败 orderId=" + order.orderId());
            }
        }
    }

    private ProductLine requireProductLine(ProductLine productLine) {
        if (productLine == null) {
            throw new IllegalArgumentException("订单投影产品线不能为空");
        }
        return productLine;
    }

    private String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private Long nullableVersion(long version) {
        return version <= 0L ? null : version;
    }

    private String tradingSequenceIdentifier(String sequenceName) {
        if (sequenceName == null || !sequenceName.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")) {
            throw new IllegalArgumentException("数据库技术序列名称无效: " + sequenceName);
        }
        return "public.trading_" + sequenceName.toLowerCase().replace('-', '_') + "_seq";
    }
}
