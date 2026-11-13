package com.surprising.realtime.provider.export.repository;

import com.surprising.realtime.provider.export.model.CommittedProjectionRows.FundingTotals;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 仅访问core_funding_settlement_projection；Service确认游标链完整后发布结算汇总。 */
@Repository
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(prefix = "surprising.trade-export", name = "enabled", havingValue = "true")
public class CommittedFundingSettlementRepository {
    private final JdbcTemplate jdbc;

    public CommittedFundingSettlementRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void persist(com.surprising.aeron.service.replay.CommittedTradeReplay.CommittedFundingPage page, FundingTotals totals) {
        var command = page.command();
        jdbc.update("""
            INSERT INTO core_funding_settlement_projection(product_line,settlement_id,export_sequence,
              instrument_id,funding_rate_ppm,command_status,result_code,total_long_payment_units,
              total_short_payment_units,position_count,occurred_at_epoch_ms)
            VALUES (?,?,?,?,?,'APPLIED',?,?,?,?,?)
            ON CONFLICT (product_line,instrument_id,settlement_id) DO NOTHING
            """, page.product().name(),command.settlementId(),page.clusterPosition(),command.instrumentId(),
            command.fundingRatePpm(),page.resultCode(),totals.longPayment(),totals.shortPayment(),totals.positions(),page.occurredAt());
    }
}
