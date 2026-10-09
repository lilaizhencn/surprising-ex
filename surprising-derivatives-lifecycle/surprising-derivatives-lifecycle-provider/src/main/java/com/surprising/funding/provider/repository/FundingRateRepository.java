package com.surprising.funding.provider.repository;

import com.surprising.funding.api.model.AdminCursorPage;
import com.surprising.funding.api.model.FundingRateResponse;
import com.surprising.funding.api.model.FundingRateHistoryResponse;
import com.surprising.funding.provider.config.FundingProperties;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Final rate history is the product-isolated committed settlement projection. Prediction
 * ticks are not proof that a rate was actually used to settle an interval. */
@Repository
public class FundingRateRepository {
    private final JdbcTemplate jdbcTemplate;
    private final FundingProperties properties;

    public FundingRateRepository(JdbcTemplate jdbcTemplate, FundingProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
    }

    public AdminCursorPage.CursorPage<FundingRateHistoryResponse> historyPage(String instrumentId,
            int limit, String cursor, String sort) {
        int safeLimit = AdminCursorPage.limit(limit, 1000);
        var desc = new AdminCursorPage.SortSpec("eventTime", "event_time", "sequence", true);
        var asc = new AdminCursorPage.SortSpec("eventTime", "event_time", "sequence", false);
        var sortSpec = AdminCursorPage.parseSort(sort, desc, List.of(desc, asc));
        var decoded = AdminCursorPage.decodeCursor(cursor);
        var args = new ArrayList<Object>();
        args.add(properties.getKafka().getProductLine().name());
        args.add(instrumentId);
        AdminCursorPage.addCursorArgs(args, decoded);
        args.add(safeLimit + 1);
        var rows = jdbcTemplate.query("""
                SELECT * FROM (
                  SELECT p.instrument_id, p.export_sequence AS sequence, p.funding_rate_ppm,
                    to_timestamp(p.settlement_id/1000.0) AS funding_time,
                    f.premium_rate_ppm, f.interest_rate_ppm, f.funding_interval_hours,
                    'FINAL' AS status, to_timestamp(p.occurred_at_epoch_ms/1000.0) AS event_time
                  FROM core_funding_settlement_projection p
                  LEFT JOIN funding_settlement_rates f ON f.product_line=p.product_line
                    AND f.instrument_id=p.instrument_id AND f.settlement_id=p.settlement_id
                    AND f.funding_rate_ppm=p.funding_rate_ppm
                  WHERE p.product_line=? AND p.instrument_id=? AND p.command_status='APPLIED'
                ) settled WHERE true %s ORDER BY %s %s, %s %s LIMIT ?
                """.formatted(AdminCursorPage.seekCondition(sortSpec, decoded), sortSpec.column(),
                        sortSpec.directionSql(), sortSpec.idColumn(), sortSpec.directionSql()),
                (rs, row) -> new FundingRateHistoryResponse(rs.getString("instrument_id"), rs.getLong("sequence"),
                        rs.getLong("funding_rate_ppm"), (Long) rs.getObject("premium_rate_ppm"),
                        (Long) rs.getObject("interest_rate_ppm"), rs.getTimestamp("funding_time").toInstant(),
                        (Integer) rs.getObject("funding_interval_hours"), "FINAL", rs.getTimestamp("event_time").toInstant()),
                args.toArray());
        return AdminCursorPage.page(rows, safeLimit, sortSpec, FundingRateHistoryResponse::eventTime,
                FundingRateHistoryResponse::sequence);
    }

    static FundingRateResponse toRate(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new FundingRateResponse(
                rs.getString("instrument_id"),
                rs.getLong("sequence"),
                rs.getLong("funding_rate_ppm"),
                rs.getLong("premium_rate_ppm"),
                rs.getLong("interest_rate_ppm"),
                rs.getTimestamp("funding_time").toInstant(),
                rs.getInt("funding_interval_hours"),
                rs.getString("status"),
                rs.getTimestamp("event_time").toInstant());
    }
}
