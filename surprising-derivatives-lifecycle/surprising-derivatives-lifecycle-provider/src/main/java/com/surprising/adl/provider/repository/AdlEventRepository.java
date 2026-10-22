package com.surprising.adl.provider.repository;

import com.surprising.adl.api.utils.AdminCursorPage;
import com.surprising.adl.provider.model.AdlEventPageQuery;
import com.surprising.adl.api.model.AdlEventResponse;
import com.surprising.adl.api.model.AdlSide;
import com.surprising.adl.provider.model.CoreAdlLiquidationProjection;
import com.surprising.aeron.protocol.CoreAdlCandidateView;
import com.surprising.trading.api.model.PositionSide;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 仅负责 adl_events 表。
 */
@Repository
public class AdlEventRepository {

    private final JdbcTemplate jdbcTemplate;

    public AdlEventRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insertCompleted(long eventId, String accountType, CoreAdlLiquidationProjection liquidation,
                                CoreAdlCandidateView candidate, long closeSteps, long realizedProfitUnits,
                                long coveredUnits, long remainingDeficitUnits, Instant now) {
        int rows = jdbcTemplate.update("""
                INSERT INTO adl_events (
                    event_id, account_type, deficit_user_id, target_user_id, asset, instrument_id,
                    target_side, target_position_side, closed_quantity_steps,
                    entry_price_ticks, mark_price_ticks, requested_deficit_units,
                    realized_profit_units, covered_units, remaining_deficit_units,
                    priority_score_ppm, reason, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'AERON_ADL_COVERAGE', ?)
                ON CONFLICT (event_id) DO NOTHING
                """, eventId, accountType, liquidation.userId(), candidate.userId(), liquidation.asset(),
                candidate.instrumentId(), candidate.signedQuantitySteps() > 0 ? "LONG" : "SHORT",
                candidate.positionSide().name(), closeSteps, candidate.entryPriceTicks(),
                candidate.markPriceTicks(), liquidation.deficitUnits(), realizedProfitUnits, coveredUnits,
                remainingDeficitUnits, candidate.priorityScorePpm(), Timestamp.from(now));
        if (rows != 0 && rows != 1) throw new IllegalStateException("failed to write ADL event audit");
    }

    public AdminCursorPage.CursorPage<AdlEventResponse> page(String accountType,
                                                             Long userId,
                                                             String asset,
                                                             String instrumentId,
                                                             AdlEventPageQuery query) {
        int safeLimit = query.limit();
        AdminCursorPage.SortSpec sortSpec =
                new AdminCursorPage.SortSpec("createdAt", "created_at", "event_id", query.descending());
        AdminCursorPage.Cursor decodedCursor = query.afterTime() == null ? null
                : new AdminCursorPage.Cursor(query.afterTime(), query.afterId());
        List<Object> args = new ArrayList<>();
        args.add(accountType);
        args.add(userId);
        args.add(userId);
        args.add(userId);
        args.add(asset);
        args.add(asset);
        args.add(instrumentId);
        args.add(instrumentId);
        AdminCursorPage.addCursorArgs(args, decodedCursor);
        args.add(safeLimit + 1);
        String sql = """
                SELECT *
                  FROM adl_events
                 WHERE account_type = ?
                   AND (CAST(? AS text) IS NULL OR deficit_user_id = ? OR target_user_id = ?)
                   AND (CAST(? AS text) IS NULL OR asset = ?)
                   AND (CAST(? AS text) IS NULL OR instrument_id = ?)
                """ + AdminCursorPage.seekCondition(sortSpec, decodedCursor) + """
                 ORDER BY created_at %s, event_id %s
                 LIMIT ?
                """.formatted(sortSpec.directionSql(), sortSpec.directionSql());
        List<AdlEventResponse> rows = jdbcTemplate.query(sql, (rs, rowNum) -> toEvent(rs), args.toArray());
        return AdminCursorPage.page(rows, safeLimit, sortSpec, AdlEventResponse::createdAt,
                AdlEventResponse::eventId);
    }

    private AdlEventResponse toEvent(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new AdlEventResponse(
                rs.getLong("event_id"),
                rs.getLong("deficit_user_id"),
                rs.getLong("target_user_id"),
                rs.getString("asset"),
                rs.getString("instrument_id"),
                AdlSide.valueOf(rs.getString("target_side")),
                PositionSide.fromNullableDbValue(rs.getString("target_position_side")),
                rs.getLong("closed_quantity_steps"),
                rs.getLong("entry_price_ticks"),
                rs.getLong("mark_price_ticks"),
                rs.getLong("requested_deficit_units"),
                rs.getLong("realized_profit_units"),
                rs.getLong("covered_units"),
                rs.getLong("remaining_deficit_units"),
                rs.getLong("priority_score_ppm"),
                rs.getString("reason"),
                rs.getTimestamp("created_at").toInstant());
    }
}
