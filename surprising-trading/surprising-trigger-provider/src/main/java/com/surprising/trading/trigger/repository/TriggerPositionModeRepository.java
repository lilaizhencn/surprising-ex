package com.surprising.trading.trigger.repository;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.PositionMode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 触发单仓位模式仓储，只负责 {@code account_position_modes} 表。
 */
@Repository
public class TriggerPositionModeRepository {

    private final JdbcTemplate jdbcTemplate;

    public TriggerPositionModeRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
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
}
