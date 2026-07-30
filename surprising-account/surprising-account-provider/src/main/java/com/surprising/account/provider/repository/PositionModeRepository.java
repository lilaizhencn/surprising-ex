package com.surprising.account.provider.repository;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.PositionMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PositionModeRepository {

    private final JdbcTemplate jdbcTemplate;

    public PositionModeRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<PositionModeRow> find(ProductLine productLine, long userId) {
        return jdbcTemplate.query("""
                SELECT position_mode, updated_at
                  FROM account_position_modes
                 WHERE product_line = ?
                   AND user_id = ?
                """, (rs, rowNum) -> new PositionModeRow(
                        PositionMode.fromNullableDbValue(rs.getString("position_mode")),
                        rs.getTimestamp("updated_at").toInstant()), productLine.name(), userId)
                .stream().findFirst();
    }

    public int upsert(ProductLine productLine, long userId, PositionMode positionMode, Instant now) {
        return jdbcTemplate.update("""
                INSERT INTO account_position_modes (product_line, user_id, position_mode, updated_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (product_line, user_id) DO UPDATE
                   SET position_mode = EXCLUDED.position_mode,
                       updated_at = EXCLUDED.updated_at
                """, productLine.name(), userId, positionMode.name(), Timestamp.from(now));
    }

    public record PositionModeRow(PositionMode positionMode, Instant updatedAt) {
    }
}
