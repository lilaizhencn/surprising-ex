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
import org.springframework.dao.DataAccessException;
import com.surprising.realtime.api.exception.ReadViewUnavailableException;
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
        try {
            return jdbcTemplate.query("""
                SELECT initialized.__ready, projection_rows.*
                  FROM (SELECT count(*)=1 AS __ready FROM core_liquidation_projection_initialization WHERE product_line=?) initialized
                  LEFT JOIN LATERAL (
                SELECT liquidation_id, user_id, instrument_id, asset, margin_mode, position_side,
                       signed_quantity_steps, status, updated_at_epoch_ms
                  FROM core_liquidation_projection
                 WHERE product_line = ? AND status IN """ + statuses + " AND liquidation_id " + comparison
                        + " ? ORDER BY liquidation_id "
                        + direction + " LIMIT ?) projection_rows ON initialized.__ready ORDER BY liquidation_id " + direction, (rs, rowNum) -> {
                if (!rs.getBoolean("__ready")) throw new ReadViewUnavailableException("LIQUIDATION_READ_VIEW_NOT_READY");
                if (rs.getObject("liquidation_id") == null) return null;
                return new LiquidationCandidateResponse(
                rs.getLong("liquidation_id"), null, rs.getLong("user_id"),
                rs.getString("instrument_id"), MarginMode.valueOf(rs.getString("margin_mode")),
                PositionSide.valueOf(rs.getString("position_side")),
                productLine.accountTypeCode(), rs.getString("asset"), rs.getLong("signed_quantity_steps"),
                null, null, null, null, mapStatus(rs.getString("status")),
                Instant.ofEpochMilli(rs.getLong("updated_at_epoch_ms")));
            }, productLine.name(), productLine.name(), cursor, limit).stream().filter(java.util.Objects::nonNull).toList();
        } catch (DataAccessException failure) {
            throw new ReadViewUnavailableException("LIQUIDATION_READ_VIEW_UNAVAILABLE", failure);
        } catch (IllegalArgumentException invalid) {
            throw new ReadViewUnavailableException("LIQUIDATION_READ_VIEW_INVALID", invalid);
        }
    }

    private static LiquidationCandidateStatus mapStatus(String value) {
        return switch (value) {
            case "ORDERED", "INSURANCE_REQUIRED", "ADL_REQUIRED" -> LiquidationCandidateStatus.PROCESSING;
            case "COMPLETED" -> LiquidationCandidateStatus.COMPLETED;
            case "PLANNED" -> LiquidationCandidateStatus.NEW;
            case "CANCELED" -> LiquidationCandidateStatus.CANCELED;
            default -> throw new ReadViewUnavailableException("LIQUIDATION_READ_VIEW_INVALID_STATUS");
        };
    }
}
