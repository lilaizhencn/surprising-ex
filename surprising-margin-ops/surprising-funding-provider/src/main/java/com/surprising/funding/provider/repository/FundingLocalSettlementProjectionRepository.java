package com.surprising.funding.provider.repository;

import com.surprising.funding.provider.service.FundingLocalSettlementStore;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class FundingLocalSettlementProjectionRepository {

    private static final String MULTI_TABLE_PROJECTION_REASON =
            "不可拆原因：结算汇总与支付明细必须由同一投影批次写入，才能让异步运营读模型按同一资金费结算快照核对。";

    private final JdbcTemplate jdbcTemplate;

    public FundingLocalSettlementProjectionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void project(List<FundingLocalSettlementStore.ProjectionSnapshot> snapshots, Instant now) {
        if (snapshots == null || snapshots.isEmpty()) {
            return;
        }
        for (FundingLocalSettlementStore.ProjectionSnapshot snapshot : snapshots) {
            projectSettlement(snapshot, now);
        }
    }

    private void projectSettlement(FundingLocalSettlementStore.ProjectionSnapshot snapshot, Instant now) {
        var settlement = snapshot.settlement();
        var payments = snapshot.payments();
        long totalLong = 0L;
        long totalShort = 0L;
        int applied = 0;
        int rejected = 0;
        for (var payment : payments) {
            if (payment.payment().signedQuantitySteps() > 0L) {
                totalLong = Math.addExact(totalLong, payment.payment().amountUnits());
            } else {
                totalShort = Math.addExact(totalShort, payment.payment().amountUnits());
            }
            if ("APPLIED".equals(payment.status())) {
                applied++;
            } else if ("REJECTED".equals(payment.status())) {
                rejected++;
            }
        }
        String status = !snapshot.completed() ? "PROCESSING"
                : rejected > 0 ? "FAILED"
                : applied == payments.size() ? "COMPLETED" : "WAITING_ACCOUNTS";
        int settlementRows = jdbcTemplate.update("""
                INSERT INTO funding_settlements (
                    settlement_id, symbol, funding_time, funding_rate_ppm,
                    instrument_version, mark_price_ticks,
                    total_long_payment_units, total_short_payment_units,
                    position_count, expected_payment_count, applied_payment_count,
                    rejected_payment_count, scan_user_id, scan_margin_mode,
                    scan_position_side, scan_completed, status, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (settlement_id) DO UPDATE SET
                    total_long_payment_units = EXCLUDED.total_long_payment_units,
                    total_short_payment_units = EXCLUDED.total_short_payment_units,
                    position_count = EXCLUDED.position_count,
                    expected_payment_count = EXCLUDED.expected_payment_count,
                    applied_payment_count = EXCLUDED.applied_payment_count,
                    rejected_payment_count = EXCLUDED.rejected_payment_count,
                    scan_user_id = EXCLUDED.scan_user_id,
                    scan_margin_mode = EXCLUDED.scan_margin_mode,
                    scan_position_side = EXCLUDED.scan_position_side,
                    scan_completed = EXCLUDED.scan_completed,
                    status = EXCLUDED.status,
                    updated_at = EXCLUDED.updated_at
                """, settlement.settlementId(), settlement.symbol(), Timestamp.from(settlement.fundingTime()),
                settlement.fundingRatePpm(), settlement.instrumentVersion(), settlement.markPriceTicks(), totalLong,
                totalShort, payments.size(), payments.size(), applied, rejected, settlement.cursor().userId(),
                settlement.cursor().marginMode(), settlement.cursor().positionSide(), snapshot.completed(), status,
                Timestamp.from(now), Timestamp.from(now));
        if (settlementRows != 1) {
            throw new IllegalStateException("资金费结算投影写入失败: " + settlement.settlementId());
        }
        for (var payment : payments) {
            projectPayment(settlement, payment, now);
        }
    }

    private void projectPayment(com.surprising.funding.provider.model.FundingSettlementWork settlement,
                                FundingLocalSettlementStore.PendingPayment payment,
                                Instant now) {
        var candidate = payment.payment();
        String status = payment.status();
        Timestamp terminalAt = payment.completedAt() == null ? null : Timestamp.from(payment.completedAt());
        int rows = jdbcTemplate.update("""
                INSERT INTO funding_payments (
                    payment_id, settlement_id, user_id, symbol, margin_mode, position_side, asset,
                    signed_quantity_steps, notional_units, funding_rate_ppm, amount_units,
                    command_id, status, applied_at, rejected_at, error_code, error_message, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (payment_id) DO UPDATE SET
                    status = EXCLUDED.status,
                    applied_at = EXCLUDED.applied_at,
                    rejected_at = EXCLUDED.rejected_at,
                    error_code = EXCLUDED.error_code,
                    error_message = EXCLUDED.error_message,
                    updated_at = EXCLUDED.updated_at
                WHERE funding_payments.command_id = EXCLUDED.command_id
                  AND funding_payments.settlement_id = EXCLUDED.settlement_id
                  AND funding_payments.user_id = EXCLUDED.user_id
                """, payment.paymentId(), settlement.settlementId(), candidate.userId(), candidate.symbol(),
                candidate.marginMode().name(), candidate.positionSide().name(), candidate.asset(),
                candidate.signedQuantitySteps(), candidate.notionalUnits(), candidate.fundingRatePpm(),
                candidate.amountUnits(), payment.commandId(), status,
                "APPLIED".equals(status) ? terminalAt : null,
                "REJECTED".equals(status) ? terminalAt : null, payment.errorCode(), payment.errorMessage(),
                Timestamp.from(now), Timestamp.from(now));
        if (rows != 1) {
            throw new IllegalStateException("资金费支付投影冲突: " + payment.commandId());
        }
    }
}
