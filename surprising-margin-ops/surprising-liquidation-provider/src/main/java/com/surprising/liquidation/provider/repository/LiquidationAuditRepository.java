package com.surprising.liquidation.provider.repository;

import com.surprising.liquidation.api.model.AdminCursorPage;
import com.surprising.liquidation.api.model.LiquidationOrderResponse;
import com.surprising.liquidation.api.model.LiquidationOrderStatus;
import com.surprising.liquidation.provider.config.LiquidationProperties;
import com.surprising.liquidation.provider.model.LiquidationPricingDecision;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.PositionSide;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 强平订单审计仓储，只负责 {@code liquidation_orders} 表。 */
@Repository
public class LiquidationAuditRepository {

    private final JdbcTemplate jdbcTemplate;
    private final LiquidationProperties properties;

    public LiquidationAuditRepository(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, new LiquidationProperties());
    }

    @Autowired
    public LiquidationAuditRepository(JdbcTemplate jdbcTemplate, LiquidationProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties == null ? new LiquidationProperties() : properties;
    }

    public boolean insert(LiquidationOrderInsert insert) {
        LiquidationPricingDecision pricing = insert.pricing() == null
                ? LiquidationPricingDecision.empty() : insert.pricing();
        int rows = jdbcTemplate.update("""
                INSERT INTO liquidation_orders (
                    liquidation_order_id, product_line, candidate_id, order_id, user_id, symbol,
                    margin_mode, position_side, side, quantity_steps, status, reason,
                    bankruptcy_price_ticks, takeover_price_ticks, liquidation_fee_rate_ppm,
                    liquidation_fee_units, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (candidate_id) DO NOTHING
                """, insert.liquidationOrderId(), currentProductLine().name(), insert.candidateId(), insert.orderId(),
                insert.userId(), insert.symbol(), MarginMode.defaultIfNull(insert.marginMode()).name(),
                PositionSide.defaultIfNull(insert.positionSide()).name(), insert.side().name(),
                insert.quantitySteps(), insert.status().name(), insert.reason(), pricing.bankruptcyPriceTicks(),
                pricing.takeoverPriceTicks(), pricing.liquidationFeeRatePpm(), pricing.liquidationFeeUnits(),
                Timestamp.from(insert.now()));
        return rows == 1;
    }

    public void insertAll(List<LiquidationOrderInsert> inserts) {
        if (inserts == null || inserts.isEmpty()) {
            return;
        }
        int[] rows = jdbcTemplate.batchUpdate("""
                INSERT INTO liquidation_orders (
                    liquidation_order_id, product_line, candidate_id, order_id, user_id, symbol,
                    margin_mode, position_side, side, quantity_steps, status, reason,
                    bankruptcy_price_ticks, takeover_price_ticks, liquidation_fee_rate_ppm,
                    liquidation_fee_units, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (candidate_id) DO NOTHING
                """, inserts.stream().map(insert -> {
            LiquidationPricingDecision pricing = insert.pricing() == null
                    ? LiquidationPricingDecision.empty() : insert.pricing();
            return new Object[]{insert.liquidationOrderId(), currentProductLine().name(), insert.candidateId(),
                    insert.orderId(), insert.userId(), insert.symbol(),
                    MarginMode.defaultIfNull(insert.marginMode()).name(),
                    PositionSide.defaultIfNull(insert.positionSide()).name(), insert.side().name(),
                    insert.quantitySteps(), insert.status().name(), insert.reason(), pricing.bankruptcyPriceTicks(),
                    pricing.takeoverPriceTicks(), pricing.liquidationFeeRatePpm(), pricing.liquidationFeeUnits(),
                    Timestamp.from(insert.now())};
        }).toList());
        if (rows.length != inserts.size()) {
            throw new IllegalStateException("批量写入强平审计结果数量不一致");
        }
        for (int row : rows) {
            if (row != 1 && row != Statement.SUCCESS_NO_INFO) {
                throw new IllegalStateException("批量写入强平审计失败");
            }
        }
    }

    public Optional<Long> updateStatusByOrderId(long orderId, LiquidationOrderStatus status) {
        List<Object> args = new ArrayList<>();
        args.add(status.name());
        args.add(orderId);
        StringBuilder sql = new StringBuilder("""
                UPDATE liquidation_orders lo
                   SET status = ?
                 WHERE lo.order_id = ?
                   AND lo.status IN ('SUBMITTED', 'PARTIALLY_FILLED')
                """);
        appendProductLineFilter(sql, "lo", args);
        sql.append(" RETURNING lo.candidate_id\n");
        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> rs.getLong("candidate_id"), args.toArray())
                .stream().findFirst();
    }

    public List<LiquidationOrderResponse> find(Long userId, int limit) {
        List<Object> args = new ArrayList<>();
        args.add(userId);
        args.add(userId);
        StringBuilder sql = new StringBuilder("""
                SELECT lo.*
                  FROM liquidation_orders lo
                 WHERE (CAST(? AS text) IS NULL OR lo.user_id = ?)
                """);
        appendProductLineFilter(sql, "lo", args);
        sql.append(" ORDER BY lo.created_at DESC LIMIT ?\n");
        args.add(limit);
        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> toResponse(rs), args.toArray());
    }

    public AdminCursorPage.CursorPage<LiquidationOrderResponse> page(
            Long userId, int limit, String cursor, String sort) {
        int safeLimit = AdminCursorPage.limit(limit, 1000);
        AdminCursorPage.SortSpec sortSpec = parseCreatedAtSort(sort);
        AdminCursorPage.Cursor decodedCursor = AdminCursorPage.decodeCursor(cursor);
        List<Object> args = new ArrayList<>();
        args.add(userId);
        args.add(userId);
        StringBuilder sql = new StringBuilder("""
                SELECT lo.*
                  FROM liquidation_orders lo
                 WHERE (CAST(? AS text) IS NULL OR lo.user_id = ?)
                """);
        appendProductLineFilter(sql, "lo", args);
        sql.append(AdminCursorPage.seekCondition(sortSpec, decodedCursor));
        AdminCursorPage.addCursorArgs(args, decodedCursor);
        sql.append("""
                 ORDER BY lo.created_at %s, lo.liquidation_order_id %s
                 LIMIT ?
                """.formatted(sortSpec.directionSql(), sortSpec.directionSql()));
        args.add(safeLimit + 1);
        List<LiquidationOrderResponse> rows =
                jdbcTemplate.query(sql.toString(), (rs, rowNum) -> toResponse(rs), args.toArray());
        return AdminCursorPage.page(rows, safeLimit, sortSpec, LiquidationOrderResponse::createdAt,
                LiquidationOrderResponse::liquidationOrderId);
    }

    public List<LiquidationOrderResponse> findByCandidate(long candidateId) {
        List<Object> args = new ArrayList<>();
        args.add(candidateId);
        StringBuilder sql = new StringBuilder("""
                SELECT lo.*
                  FROM liquidation_orders lo
                 WHERE lo.candidate_id = ?
                """);
        appendProductLineFilter(sql, "lo", args);
        sql.append(" ORDER BY lo.created_at DESC\n");
        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> toResponse(rs), args.toArray());
    }

    private AdminCursorPage.SortSpec parseCreatedAtSort(String value) {
        AdminCursorPage.SortSpec createdAtDesc = new AdminCursorPage.SortSpec(
                "createdAt", "lo.created_at", "lo.liquidation_order_id", true);
        AdminCursorPage.SortSpec createdAtAsc = new AdminCursorPage.SortSpec(
                "createdAt", "lo.created_at", "lo.liquidation_order_id", false);
        return AdminCursorPage.parseSort(value, createdAtDesc, List.of(createdAtDesc, createdAtAsc));
    }

    private void appendProductLineFilter(StringBuilder sql, String alias, List<Object> args) {
        if (!properties.getKafka().isProductTopicsEnabled()) {
            return;
        }
        ProductLine productLine = currentProductLine();
        if (!productLine.isMarginProduct()) {
            sql.append(" AND 1 = 0\n");
            return;
        }
        args.add(productLine.name());
        sql.append(" AND ").append(alias).append(".product_line = ?\n");
    }

    private ProductLine currentProductLine() {
        return properties.getKafka().isProductTopicsEnabled()
                ? properties.getKafka().getProductLine()
                : ProductLine.LINEAR_PERPETUAL;
    }

    private LiquidationOrderResponse toResponse(java.sql.ResultSet rs) throws java.sql.SQLException {
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

    public record LiquidationOrderInsert(long liquidationOrderId,
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
    }
}
