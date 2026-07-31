package com.surprising.trading.order.repository;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.PositionMode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 订单入口仓位模式仓储，只负责 {@code account_position_modes} 表。 */
@Repository
public class OrderPositionModeRepository {
    private final JdbcTemplate jdbcTemplate;

    public OrderPositionModeRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public PositionMode positionMode(ProductLine line, long userId) {
        String mode = jdbcTemplate.query("""
                SELECT position_mode FROM account_position_modes
                 WHERE product_line = ? AND user_id = ?
                """, (rs, rowNum) -> rs.getString("position_mode"), line.name(), userId)
                .stream().findFirst().orElse(null);
        return PositionMode.fromNullableDbValue(mode);
    }
}
