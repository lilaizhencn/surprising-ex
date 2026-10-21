package com.surprising.realtime.provider.export.repository;

import com.surprising.product.api.ProductLine;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import com.surprising.realtime.provider.export.model.CommittedProjectionRows.FundingTotals;

/** 仅访问core_funding_payment_projection；按提交页写明细，并以NUMERIC精度汇总同一结算。 */
@Repository
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(prefix = "surprising.trade-export", name = "enabled", havingValue = "true")
public class CommittedFundingPaymentRepository {
    private final JdbcTemplate jdbc;

    public CommittedFundingPaymentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void persist(com.surprising.aeron.service.orchestration.replay.CommittedTradeReplay.CommittedFundingPage page) {
        if (!page.payments().isEmpty()) jdbc.batchUpdate("""
                INSERT INTO core_funding_payment_projection(product_line,export_sequence,payment_index,
                  settlement_id,user_id,instrument_id,margin_mode,position_side,asset,signed_quantity_steps,
                  notional_units,funding_rate_ppm,amount_units,occurred_at_epoch_ms)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, new org.springframework.jdbc.core.BatchPreparedStatementSetter() {
            @Override public int getBatchSize() { return page.payments().size(); }
            @Override public void setValues(java.sql.PreparedStatement s, int index) throws java.sql.SQLException {
                var payment = page.payments().get(index);
                s.setString(1, page.product().name()); s.setLong(2, page.clusterPosition()); s.setInt(3, index);
                s.setLong(4, payment.settlementId()); s.setLong(5, payment.userId()); s.setString(6, payment.instrumentId());
                s.setString(7, payment.marginMode().name()); s.setString(8, payment.positionSide().name());
                s.setString(9, payment.asset()); s.setLong(10, payment.signedQuantitySteps());
                s.setLong(11, payment.notionalUnits()); s.setLong(12, payment.fundingRatePpm());
                s.setLong(13, payment.amountUnits()); s.setLong(14, page.occurredAt());
            }
        });
    }
    public FundingTotals totals(com.surprising.aeron.service.orchestration.replay.CommittedTradeReplay.CommittedFundingPage page) {
        return jdbc.queryForObject("""
            SELECT COALESCE(sum(amount_units) FILTER (WHERE signed_quantity_steps>0),0),
                   COALESCE(sum(amount_units) FILTER (WHERE signed_quantity_steps<0),0),count(*)::integer
            FROM core_funding_payment_projection WHERE product_line=? AND instrument_id=? AND settlement_id=?
            """, (rs,row) -> new FundingTotals(rs.getBigDecimal(1),rs.getBigDecimal(2),rs.getInt(3)),
            page.product().name(), page.command().instrumentId(), page.command().settlementId());
    }
}
