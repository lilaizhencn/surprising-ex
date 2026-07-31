package com.surprising.funding.provider.repository;

import com.surprising.funding.api.model.AdminCursorPage;
import com.surprising.funding.api.model.FundingPaymentResponse;
import com.surprising.funding.provider.config.FundingProperties;
import com.surprising.funding.provider.model.FundingPaymentCandidate;
import com.surprising.funding.provider.model.FundingPaymentWrite;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.PositionSide;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 仅负责 funding_payments 表。
 */
@Repository
public class FundingPaymentRepository {

    private final JdbcTemplate jdbcTemplate;
    private final FundingProperties properties;

    public FundingPaymentRepository(JdbcTemplate jdbcTemplate, FundingProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
    }

    public List<FundingPaymentWrite> insert(long settlementId,
                                            List<FundingPaymentCandidate> payments,
                                            Instant now) {
        if (payments == null || payments.isEmpty()) {
            return List.of();
        }
        if (payments.stream().anyMatch(payment -> payment.amountUnits() == 0L)) {
            throw new IllegalArgumentException("zero funding payments must be filtered before insert");
        }
        List<Long> paymentIds = jdbcTemplate.query("""
                SELECT nextval('funding_payment_id_seq') AS payment_id
                  FROM generate_series(1, ?) AS n
                 ORDER BY n
                """, (rs, rowNum) -> rs.getLong("payment_id"), payments.size());
        if (paymentIds.size() != payments.size()) {
            throw new IllegalStateException("failed to allocate funding payment ids");
        }
        List<FundingPaymentWrite> writes = new ArrayList<>(payments.size());
        String productLine = properties.getKafka().getProductLine().name();
        for (int i = 0; i < payments.size(); i++) {
            long paymentId = paymentIds.get(i);
            writes.add(new FundingPaymentWrite(paymentId,
                    "FUNDING:" + productLine + ":" + settlementId + ":" + paymentId, payments.get(i)));
        }
        int[] rows = jdbcTemplate.batchUpdate("""
                INSERT INTO funding_payments (
                    payment_id, settlement_id, user_id, symbol, margin_mode, position_side, asset,
                    signed_quantity_steps, notional_units, funding_rate_ppm,
                    amount_units, command_id, status, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, ?)
                """, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement statement, int index) throws java.sql.SQLException {
                FundingPaymentWrite write = writes.get(index);
                FundingPaymentCandidate payment = write.payment();
                statement.setLong(1, write.paymentId());
                statement.setLong(2, settlementId);
                statement.setLong(3, payment.userId());
                statement.setString(4, payment.symbol());
                statement.setString(5, payment.marginMode().name());
                statement.setString(6, payment.positionSide().name());
                statement.setString(7, payment.asset());
                statement.setLong(8, payment.signedQuantitySteps());
                statement.setLong(9, payment.notionalUnits());
                statement.setLong(10, payment.fundingRatePpm());
                statement.setLong(11, payment.amountUnits());
                statement.setString(12, write.commandId());
                statement.setTimestamp(13, Timestamp.from(now));
                statement.setTimestamp(14, Timestamp.from(now));
            }

            @Override
            public int getBatchSize() {
                return writes.size();
            }
        });
        requireCompleteBatch(rows, writes.size());
        return List.copyOf(writes);
    }

    public AdminCursorPage.CursorPage<FundingPaymentResponse> page(long userId,
                                                                   String symbol,
                                                                   int limit,
                                                                   String cursor,
                                                                   String sort) {
        String normalizedSymbol = symbol == null || symbol.isBlank() ? null : symbol.trim().toUpperCase();
        int safeLimit = AdminCursorPage.limit(limit, 1000);
        AdminCursorPage.SortSpec desc =
                new AdminCursorPage.SortSpec("createdAt", "created_at", "payment_id", true);
        AdminCursorPage.SortSpec asc =
                new AdminCursorPage.SortSpec("createdAt", "created_at", "payment_id", false);
        AdminCursorPage.SortSpec sortSpec = AdminCursorPage.parseSort(sort, desc, List.of(desc, asc));
        AdminCursorPage.Cursor decodedCursor = AdminCursorPage.decodeCursor(cursor);
        List<Object> args = new ArrayList<>();
        args.add(userId);
        args.add(normalizedSymbol);
        args.add(normalizedSymbol);
        AdminCursorPage.addCursorArgs(args, decodedCursor);
        args.add(safeLimit + 1);
        List<FundingPaymentResponse> rows = jdbcTemplate.query("""
                SELECT *
                  FROM funding_payments
                 WHERE user_id = ?
                   AND (CAST(? AS text) IS NULL OR symbol = ?)
                %s
                 ORDER BY %s %s, %s %s
                 LIMIT ?
                """.formatted(AdminCursorPage.seekCondition(sortSpec, decodedCursor),
                        sortSpec.column(), sortSpec.directionSql(), sortSpec.idColumn(), sortSpec.directionSql()),
                (rs, rowNum) -> new FundingPaymentResponse(
                        rs.getLong("payment_id"),
                        rs.getLong("settlement_id"),
                        rs.getLong("user_id"),
                        rs.getString("symbol"),
                        MarginMode.fromNullableDbValue(rs.getString("margin_mode")),
                        PositionSide.fromNullableDbValue(rs.getString("position_side")),
                        rs.getString("asset"),
                        rs.getLong("signed_quantity_steps"),
                        rs.getLong("notional_units"),
                        rs.getLong("funding_rate_ppm"),
                        rs.getLong("amount_units"),
                        rs.getTimestamp("created_at").toInstant()), args.toArray());
        return AdminCursorPage.page(rows, safeLimit, sortSpec, FundingPaymentResponse::createdAt,
                FundingPaymentResponse::paymentId);
    }

    private void requireCompleteBatch(int[] rows, int expectedRows) {
        if (rows == null || rows.length != expectedRows) {
            throw new IllegalStateException("failed to write funding payments");
        }
        for (int row : rows) {
            if (row != 1 && row != Statement.SUCCESS_NO_INFO) {
                throw new IllegalStateException("failed to write funding payments");
            }
        }
    }
}
