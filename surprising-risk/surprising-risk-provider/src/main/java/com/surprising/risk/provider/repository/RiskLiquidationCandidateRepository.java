package com.surprising.risk.provider.repository;

import com.surprising.product.api.ProductLine;
import com.surprising.risk.api.model.AdminCursorPage;
import com.surprising.risk.api.model.LiquidationCandidateResponse;
import com.surprising.risk.api.model.LiquidationCandidateStatus;
import com.surprising.risk.api.model.RiskAccountSnapshotResponse;
import com.surprising.risk.provider.config.RiskProperties;
import com.surprising.risk.provider.model.CalculatedPositionRisk;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.PositionSide;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 强平候选仓储，只负责 {@code risk_liquidation_candidates} 表。 */
@Repository
public class RiskLiquidationCandidateRepository {

    private final JdbcTemplate jdbcTemplate;
    private final RiskProperties properties;

    public RiskLiquidationCandidateRepository(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, new RiskProperties());
    }

    @Autowired
    public RiskLiquidationCandidateRepository(JdbcTemplate jdbcTemplate, RiskProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties == null ? new RiskProperties() : properties;
    }

    public Set<Long> createAll(List<LiquidationCandidateWrite> candidates) {
        if (candidates.isEmpty()) {
            return Set.of();
        }
        int[] rows = jdbcTemplate.batchUpdate("""
                INSERT INTO risk_liquidation_candidates (
                    product_line, candidate_id, snapshot_id, user_id, symbol, margin_mode, position_side,
                    instrument_version, account_type, settle_asset,
                    signed_quantity_steps, mark_price_ticks, equity_units,
                    maintenance_margin_units, margin_ratio_ppm, position_revision, status, event_time, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'NEW', ?, now(), now())
                ON CONFLICT (product_line, user_id, symbol, margin_mode, position_side)
                    WHERE status IN ('NEW', 'PROCESSING') DO NOTHING
                """, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(java.sql.PreparedStatement statement, int index) throws java.sql.SQLException {
                LiquidationCandidateWrite row = candidates.get(index);
                RiskAccountSnapshotResponse account = row.account();
                CalculatedPositionRisk position = row.position();
                statement.setString(1, productLineForAccountType(account.accountType()).name());
                statement.setLong(2, row.candidateId());
                statement.setLong(3, account.snapshotId());
                statement.setLong(4, position.userId());
                statement.setString(5, position.symbol());
                statement.setString(6, position.marginMode().name());
                statement.setString(7, position.positionSide().name());
                statement.setLong(8, position.instrumentVersion());
                statement.setString(9, account.accountType());
                statement.setString(10, position.settleAsset());
                statement.setLong(11, position.signedQuantitySteps());
                statement.setLong(12, position.markPriceTicks());
                statement.setLong(13, row.equityUnits());
                statement.setLong(14, position.maintenanceMarginUnits());
                statement.setLong(15, Math.max(account.marginRatioPpm(), row.positionMarginRatioPpm()));
                statement.setLong(16, position.positionRevision() > 0L
                        ? position.positionRevision() : account.snapshotId());
                statement.setTimestamp(17, Timestamp.from(row.eventTime()));
            }

            @Override
            public int getBatchSize() {
                return candidates.size();
            }
        });
        if (rows.length != candidates.size()) {
            throw new IllegalStateException("强平候选批量写入结果数量不一致");
        }
        Set<Long> inserted = new HashSet<>();
        for (int index = 0; index < rows.length; index++) {
            if (rows[index] == 1 || rows[index] == Statement.SUCCESS_NO_INFO) {
                inserted.add(candidates.get(index).candidateId());
            } else if (rows[index] != 0) {
                throw new IllegalStateException("强平候选写入失败，批次索引：" + index);
            }
        }
        return inserted;
    }

    public List<LiquidationCandidateResponse> findByStatus(LiquidationCandidateStatus status, int limit) {
        List<Object> args = new ArrayList<>();
        args.add(status.name());
        String sql = """
                SELECT c.*
                  FROM risk_liquidation_candidates c
                 WHERE c.status = ?
                   %s
                 ORDER BY c.event_time ASC
                 LIMIT ?
                """.formatted(productAccountTypeFilter("c", args));
        args.add(limit);
        return jdbcTemplate.query(sql, (rs, rowNum) -> toResponse(rs), args.toArray());
    }

    public AdminCursorPage.CursorPage<LiquidationCandidateResponse> page(
            LiquidationCandidateStatus status, int limit, String cursor, String sort) {
        int safeLimit = AdminCursorPage.limit(limit, 1000);
        AdminCursorPage.SortSpec eventTimeAsc = new AdminCursorPage.SortSpec(
                "eventTime", "event_time", "candidate_id", false);
        AdminCursorPage.SortSpec eventTimeDesc = new AdminCursorPage.SortSpec(
                "eventTime", "event_time", "candidate_id", true);
        AdminCursorPage.SortSpec sortSpec = AdminCursorPage.parseSort(
                sort, eventTimeAsc, List.of(eventTimeAsc, eventTimeDesc));
        AdminCursorPage.Cursor decodedCursor = AdminCursorPage.decodeCursor(cursor);
        List<Object> args = new ArrayList<>();
        args.add(status.name());
        String accountTypeFilter = productAccountTypeFilter("c", args);
        AdminCursorPage.addCursorArgs(args, decodedCursor);
        args.add(safeLimit + 1);
        String sql = ("""
                SELECT c.*
                  FROM risk_liquidation_candidates c
                 WHERE c.status = ?
                   %s
                """ + AdminCursorPage.seekCondition(sortSpec, decodedCursor) + """
                 ORDER BY c.event_time %s, c.candidate_id %s
                 LIMIT ?
                """).formatted(accountTypeFilter, sortSpec.directionSql(), sortSpec.directionSql());
        List<LiquidationCandidateResponse> rows =
                jdbcTemplate.query(sql, (rs, rowNum) -> toResponse(rs), args.toArray());
        return AdminCursorPage.page(rows, safeLimit, sortSpec, LiquidationCandidateResponse::eventTime,
                LiquidationCandidateResponse::candidateId);
    }

    private String productAccountTypeFilter(String alias, List<Object> args) {
        if (!properties.getKafka().isProductTopicsEnabled()) {
            return "";
        }
        ProductLine line = properties.getKafka().getProductLine();
        if (!line.isMarginProduct()) {
            return "AND 1 = 0";
        }
        args.add(line.name());
        args.add(line.accountTypeCode());
        return "AND " + alias + ".product_line = ? AND " + alias + ".account_type = ?";
    }

    private ProductLine productLineForAccountType(String accountType) {
        return ProductLine.fromAccountTypeCode(accountType)
                .orElse(properties.getKafka().isProductTopicsEnabled()
                        ? properties.getKafka().getProductLine()
                        : ProductLine.LINEAR_PERPETUAL);
    }

    private LiquidationCandidateResponse toResponse(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new LiquidationCandidateResponse(
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
                LiquidationCandidateStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("event_time").toInstant());
    }

    public record LiquidationCandidateWrite(long candidateId,
                                            RiskAccountSnapshotResponse account,
                                            CalculatedPositionRisk position,
                                            long positionMarginRatioPpm,
                                            long equityUnits,
                                            Instant eventTime) {
    }
}
