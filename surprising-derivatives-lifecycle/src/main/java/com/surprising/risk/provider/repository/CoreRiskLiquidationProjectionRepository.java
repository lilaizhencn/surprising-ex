package com.surprising.risk.provider.repository;

import com.surprising.product.api.ProductLine;
import com.surprising.risk.api.model.LiquidationCandidateResponse;
import com.surprising.risk.api.model.LiquidationCandidateStatus;
import com.surprising.risk.provider.config.RiskProperties;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.PositionSide;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CoreRiskLiquidationProjectionRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ProductLine productLine;

    public CoreRiskLiquidationProjectionRepository(JdbcTemplate jdbcTemplate, RiskProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.productLine = properties.getProductLine();
    }

    public List<LiquidationCandidateResponse> find(LiquidationCandidateStatus status, int limit, Long afterId,
                                                    boolean descending) {
        String comparison = descending ? "<" : ">";
        String direction = descending ? "DESC" : "ASC";
        String statuses = switch (status) {
            case NEW -> "('PLANNED')";
            case PROCESSING -> "('ORDERED','INSURANCE_REQUIRED','ADL_REQUIRED')";
            case COMPLETED -> "('COMPLETED')";
            case CANCELED -> "('CANCELED')";
        };
        long cursor = afterId == null ? (descending ? Long.MAX_VALUE : 0L) : afterId;
        return jdbcTemplate.query("""
                SELECT liquidation_id, user_id, symbol, asset, position_side, instrument_version,
                       signed_quantity_steps, status, updated_at_epoch_ms
                  FROM core_liquidation_projection
                 WHERE product_line = ? AND status IN """ + statuses + " AND liquidation_id " + comparison
                        + " ? ORDER BY liquidation_id "
                        + direction + " LIMIT ?", (rs, rowNum) -> new LiquidationCandidateResponse(
                rs.getLong("liquidation_id"), rs.getLong("liquidation_id"), rs.getLong("user_id"),
                rs.getString("symbol"), MarginMode.CROSS,
                PositionSide.valueOf(rs.getString("position_side")), rs.getLong("instrument_version"),
                productLine.accountTypeCode(), rs.getString("asset"), rs.getLong("signed_quantity_steps"),
                0L, 0L, 0L, 0L, mapStatus(rs.getString("status")),
                Instant.ofEpochMilli(rs.getLong("updated_at_epoch_ms"))), productLine.name(), cursor, limit);
    }

    private static LiquidationCandidateStatus mapStatus(String value) {
        return switch (value) {
            case "ORDERED", "INSURANCE_REQUIRED", "ADL_REQUIRED" -> LiquidationCandidateStatus.PROCESSING;
            case "COMPLETED" -> LiquidationCandidateStatus.COMPLETED;
            case "PLANNED" -> LiquidationCandidateStatus.NEW;
            default -> LiquidationCandidateStatus.CANCELED;
        };
    }
}
