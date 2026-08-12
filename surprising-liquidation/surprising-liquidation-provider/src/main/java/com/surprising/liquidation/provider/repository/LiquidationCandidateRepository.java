package com.surprising.liquidation.provider.repository;

import com.surprising.liquidation.provider.config.LiquidationProperties;
import com.surprising.liquidation.provider.model.ClaimedCandidate;
import com.surprising.product.api.ProductLine;
import com.surprising.risk.api.model.LiquidationCandidateEvent;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.PositionSide;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 强平候选仓储，只负责 {@code risk_liquidation_candidates} 表。 */
@Repository
public class LiquidationCandidateRepository {

    private final JdbcTemplate jdbcTemplate;
    private final LiquidationProperties properties;

    public LiquidationCandidateRepository(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, new LiquidationProperties());
    }

    @Autowired
    public LiquidationCandidateRepository(JdbcTemplate jdbcTemplate, LiquidationProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties == null ? new LiquidationProperties() : properties;
    }

    public List<ClaimedCandidate> claimAll(List<Long> candidateIds) {
        if (candidateIds == null || candidateIds.isEmpty()) {
            return List.of();
        }
        List<Long> uniqueIds = candidateIds.stream().distinct().toList();
        List<Object> args = new ArrayList<>(uniqueIds);
        String placeholders = String.join(", ", java.util.Collections.nCopies(uniqueIds.size(), "?"));
        StringBuilder sql = new StringBuilder("""
                UPDATE risk_liquidation_candidates c
                   SET status = 'PROCESSING',
                       updated_at = now()
                 WHERE c.status = 'NEW'
                   AND c.candidate_id IN (%s)
                """.formatted(placeholders));
        appendProductLineFilter(sql, "c", args);
        sql.append("""
                RETURNING c.candidate_id, c.snapshot_id, c.user_id, c.symbol, c.margin_mode, c.position_side,
                          c.account_type, c.settle_asset, c.instrument_version, c.signed_quantity_steps,
                          c.mark_price_ticks, c.equity_units, c.maintenance_margin_units, c.margin_ratio_ppm,
                          c.position_revision
                """);
        Map<Long, ClaimedCandidate> claimed = jdbcTemplate.query(sql.toString(), (rs, rowNum) ->
                new ClaimedCandidate(
                        rs.getLong("candidate_id"),
                        rs.getLong("snapshot_id"),
                        rs.getLong("user_id"),
                        rs.getString("symbol"),
                        MarginMode.fromNullableDbValue(rs.getString("margin_mode")),
                        PositionSide.fromNullableDbValue(rs.getString("position_side")),
                        rs.getLong("instrument_version"),
                        rs.getString("account_type"),
                        rs.getString("settle_asset"),
                        rs.getLong("signed_quantity_steps"),
                        rs.getLong("mark_price_ticks"),
                        rs.getLong("equity_units"),
                        rs.getLong("maintenance_margin_units"),
                        rs.getLong("margin_ratio_ppm"),
                        rs.getLong("position_revision")), args.toArray()).stream()
                .collect(Collectors.toMap(ClaimedCandidate::candidateId, Function.identity()));
        return uniqueIds.stream().map(claimed::get).filter(java.util.Objects::nonNull).toList();
    }

    public List<LiquidationCandidateEvent> findNewEvents(int limit) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("""
                SELECT candidate_id, snapshot_id, user_id, symbol, margin_mode, position_side, instrument_version,
                       settle_asset, signed_quantity_steps, mark_price_ticks, equity_units,
                       maintenance_margin_units, margin_ratio_ppm, event_time, position_revision
                  FROM risk_liquidation_candidates c
                 WHERE status = 'NEW'
                """);
        appendProductLineFilter(sql, "c", args);
        sql.append(" ORDER BY margin_ratio_ppm ASC, event_time ASC, candidate_id ASC LIMIT ?");
        args.add(Math.max(1, limit));
        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> new LiquidationCandidateEvent(
                rs.getLong("candidate_id"), rs.getLong("snapshot_id"), rs.getLong("user_id"), rs.getString("symbol"),
                MarginMode.fromNullableDbValue(rs.getString("margin_mode")),
                PositionSide.fromNullableDbValue(rs.getString("position_side")), rs.getLong("instrument_version"),
                rs.getString("settle_asset"), rs.getLong("signed_quantity_steps"), rs.getLong("mark_price_ticks"),
                rs.getLong("equity_units"), rs.getLong("maintenance_margin_units"), rs.getLong("margin_ratio_ppm"),
                rs.getTimestamp("event_time").toInstant(), rs.getLong("position_revision")), args.toArray());
    }

    public void updateStatus(long candidateId, String status) {
        List<Object> args = new ArrayList<>();
        args.add(status);
        args.add(candidateId);
        StringBuilder sql = new StringBuilder("""
                UPDATE risk_liquidation_candidates c
                   SET status = ?,
                       updated_at = now()
                 WHERE c.candidate_id = ?
                """);
        appendProductLineFilter(sql, "c", args);
        requireSingleRow(jdbcTemplate.update(sql.toString(), args.toArray()), "更新强平候选状态");
    }

    public void updateProcessingStatus(long candidateId, String status) {
        List<Object> args = new ArrayList<>();
        args.add(status);
        args.add(candidateId);
        StringBuilder sql = new StringBuilder("""
                UPDATE risk_liquidation_candidates c
                   SET status = ?,
                       updated_at = now()
                 WHERE c.candidate_id = ?
                   AND c.status = 'PROCESSING'
                """);
        appendProductLineFilter(sql, "c", args);
        requireSingleRow(jdbcTemplate.update(sql.toString(), args.toArray()), "更新强平候选生命周期");
    }

    private void appendProductLineFilter(StringBuilder sql, String alias, List<Object> args) {
        if (!properties.getKafka().isProductTopicsEnabled()) {
            return;
        }
        ProductLine productLine = properties.getKafka().getProductLine();
        if (!productLine.isMarginProduct()) {
            sql.append(" AND 1 = 0\n");
            return;
        }
        args.add(productLine.name());
        sql.append(" AND ").append(alias).append(".product_line = ?\n");
    }

    private void requireSingleRow(int rows, String operation) {
        if (rows != 1) {
            throw new IllegalStateException(operation + "失败");
        }
    }
}
