package com.surprising.funding.provider.repository;

import com.surprising.funding.api.model.AdminCursorPage;
import com.surprising.funding.api.model.FundingRateResponse;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 仅负责 funding_rate_ticks 表。
 */
@Repository
public class FundingRateRepository {

    private final JdbcTemplate jdbcTemplate;

    public FundingRateRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean saveFinal(FundingRateResponse rate) {
        int rows = jdbcTemplate.update("""
                INSERT INTO funding_rate_ticks (
                    symbol, sequence, funding_time, funding_interval_hours,
                    premium_rate_ppm, interest_rate_ppm, funding_rate_ppm,
                    status, event_time, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 'FINAL', ?, now())
                ON CONFLICT (symbol, sequence) DO NOTHING
                """, rate.symbol(), rate.sequence(), Timestamp.from(rate.fundingTime()), rate.fundingIntervalHours(),
                rate.premiumRatePpm(), rate.interestRatePpm(), rate.fundingRatePpm(),
                Timestamp.from(rate.eventTime()));
        return rows == 1;
    }

    public Optional<FundingRateResponse> latest(String symbol) {
        return jdbcTemplate.query("""
                SELECT *
                  FROM funding_rate_ticks
                 WHERE symbol = ?
                 ORDER BY event_time DESC, sequence DESC
                 LIMIT 1
                """, (rs, rowNum) -> toRate(rs), symbol).stream().findFirst();
    }

    public AdminCursorPage.CursorPage<FundingRateResponse> historyPage(String symbol,
                                                                       int limit,
                                                                       String cursor,
                                                                       String sort) {
        int safeLimit = AdminCursorPage.limit(limit, 1000);
        AdminCursorPage.SortSpec desc =
                new AdminCursorPage.SortSpec("eventTime", "event_time", "sequence", true);
        AdminCursorPage.SortSpec asc =
                new AdminCursorPage.SortSpec("eventTime", "event_time", "sequence", false);
        AdminCursorPage.SortSpec sortSpec = AdminCursorPage.parseSort(sort, desc, List.of(desc, asc));
        AdminCursorPage.Cursor decodedCursor = AdminCursorPage.decodeCursor(cursor);
        List<Object> args = new ArrayList<>();
        args.add(symbol);
        AdminCursorPage.addCursorArgs(args, decodedCursor);
        args.add(safeLimit + 1);
        List<FundingRateResponse> rows = jdbcTemplate.query("""
                SELECT *
                  FROM funding_rate_ticks
                 WHERE symbol = ?
                %s
                 ORDER BY %s %s, %s %s
                 LIMIT ?
                """.formatted(AdminCursorPage.seekCondition(sortSpec, decodedCursor),
                        sortSpec.column(), sortSpec.directionSql(), sortSpec.idColumn(), sortSpec.directionSql()),
                (rs, rowNum) -> toRate(rs), args.toArray());
        return AdminCursorPage.page(rows, safeLimit, sortSpec, FundingRateResponse::eventTime,
                FundingRateResponse::sequence);
    }

    static FundingRateResponse toRate(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new FundingRateResponse(
                rs.getString("symbol"),
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
