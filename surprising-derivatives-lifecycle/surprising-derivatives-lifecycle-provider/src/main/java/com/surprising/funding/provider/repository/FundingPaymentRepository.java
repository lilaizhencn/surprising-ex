package com.surprising.funding.provider.repository;

import com.surprising.trading.api.utils.AdminCursorPage;
import com.surprising.funding.provider.model.FundingPageQuery;
import com.surprising.funding.api.model.FundingPaymentResponse;
import com.surprising.funding.provider.config.FundingProperties;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.PositionSide;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class FundingPaymentRepository {

    private final JdbcTemplate jdbcTemplate;
    private final FundingProperties properties;

    public FundingPaymentRepository(JdbcTemplate jdbcTemplate, FundingProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
    }

    public AdminCursorPage.CursorPage<FundingPaymentResponse> corePage(long userId,
                                                                       String instrumentId,
                                                                       FundingPageQuery query) {
        int safeLimit = query.limit();
        var sortSpec = new AdminCursorPage.SortSpec("createdAt", "occurred_at_epoch_ms", "payment_id", query.descending());
        var decodedCursor = query.cursor();
        List<Object> args = new ArrayList<>();
        args.add(properties.getKafka().getProductLine().name());
        args.add(userId);
        args.add(instrumentId);
        args.add(instrumentId);
        if (decodedCursor != null) {
            args.add(decodedCursor.timestamp().toEpochMilli());
            args.add(decodedCursor.timestamp().toEpochMilli());
            args.add(decodedCursor.id());
        }
        args.add(safeLimit + 1);
        List<FundingPaymentResponse> rows = jdbcTemplate.query("""
                SELECT *
                  FROM core_funding_payment_projection
                 WHERE product_line = ? AND user_id = ?
                   AND (CAST(? AS text) IS NULL OR instrument_id = ?)
                %s
                 ORDER BY %s %s, %s %s
                 LIMIT ?
                """.formatted(AdminCursorPage.seekCondition(sortSpec, decodedCursor),
                        sortSpec.column(), sortSpec.directionSql(), sortSpec.idColumn(), sortSpec.directionSql()),
                (rs, rowNum) -> new FundingPaymentResponse(rs.getLong("payment_id"),
                        rs.getLong("settlement_id"), rs.getLong("user_id"), rs.getString("instrument_id"),
                        MarginMode.valueOf(rs.getString("margin_mode")),
                        PositionSide.valueOf(rs.getString("position_side")), rs.getString("asset"),
                        rs.getLong("signed_quantity_steps"), rs.getLong("notional_units"),
                        rs.getLong("funding_rate_ppm"), rs.getLong("amount_units"),
                        Instant.ofEpochMilli(rs.getLong("occurred_at_epoch_ms"))), args.toArray());
        return AdminCursorPage.page(rows, safeLimit, sortSpec, FundingPaymentResponse::createdAt,
                FundingPaymentResponse::paymentId);
    }
}
