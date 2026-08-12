package com.surprising.adl.provider.repository;

import com.surprising.adl.provider.model.AdlExecutionPlan;
import com.surprising.adl.provider.model.AdlSagaState;
import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 仅负责 adl_execution_sagas 表。
 */
@Repository
public class AdlExecutionSagaRepository {

    private final JdbcTemplate jdbcTemplate;

    public AdlExecutionSagaRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insert(AdlExecutionPlan plan, Instant now) {
        requireSingle(jdbcTemplate.update("""
                INSERT INTO adl_execution_sagas (
                    execution_id, product_line, account_type, deficit_user_id, target_user_id,
                    asset, symbol, target_side, target_margin_mode, target_position_side,
                    expected_signed_steps, closed_quantity_steps, entry_price_ticks, mark_price_ticks,
                    requested_deficit_units, realized_profit_units, covered_units, priority_score_ppm,
                    reserve_command_id, target_command_id, finalize_command_id,
                    status, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                          'PENDING', ?, ?)
                """, plan.executionId(), plan.productLine().name(), plan.accountType(), plan.deficitUserId(),
                plan.targetUserId(), plan.asset(), plan.symbol(), plan.targetSide().name(),
                plan.targetMarginMode().name(), plan.targetPositionSide().name(), plan.expectedSignedSteps(),
                plan.closedQuantitySteps(), plan.entryPriceTicks(), plan.markPriceTicks(),
                plan.requestedDeficitUnits(), plan.realizedProfitUnits(), plan.coveredUnits(),
                plan.priorityScorePpm(), plan.reserveCommandId(), plan.targetCommandId(),
                plan.finalizeCommandId(), Timestamp.from(now), Timestamp.from(now)),
                "ADL execution saga insert");
    }

    public void beginRelease(AdlSagaState saga, String releaseCommandId, Instant now) {
        requireSingle(jdbcTemplate.update("""
                UPDATE adl_execution_sagas
                   SET release_command_id = ?, status = 'RELEASING',
                       error_code = ?, error_message = ?, updated_at = ?
                 WHERE execution_id = ? AND status = 'PENDING' AND release_command_id IS NULL
                """, releaseCommandId, saga.terminalErrorCode(), truncate(saga.terminalErrorMessage()),
                Timestamp.from(now), saga.executionId()), "ADL release transition");
    }

    public void failWithoutReservation(AdlSagaState saga, Instant now) {
        requireSingle(jdbcTemplate.update("""
                UPDATE adl_execution_sagas
                   SET status = 'FAILED', error_code = ?, error_message = ?,
                       completed_at = ?, updated_at = ?
                 WHERE execution_id = ? AND status = 'PENDING'
                """, saga.terminalErrorCode(), truncate(saga.terminalErrorMessage()),
                Timestamp.from(now), Timestamp.from(now), saga.executionId()), "ADL failed transition");
    }

    public void completeRelease(AdlSagaState saga, Instant now) {
        requireSingle(jdbcTemplate.update("""
                UPDATE adl_execution_sagas
                   SET status = 'FAILED', completed_at = ?, updated_at = ?
                 WHERE execution_id = ? AND status = 'RELEASING'
                """, Timestamp.from(now), Timestamp.from(now), saga.executionId()),
                "ADL release completion");
    }

    public void complete(long executionId, Instant now) {
        requireSingle(jdbcTemplate.update("""
                UPDATE adl_execution_sagas
                   SET status = 'COMPLETED', completed_at = ?, updated_at = ?
                 WHERE execution_id = ? AND status = 'PENDING'
                """, Timestamp.from(now), Timestamp.from(now), executionId), "ADL completed transition");
    }

    private void requireSingle(int rows, String operation) {
        if (rows != 1) {
            throw new IllegalStateException("failed to write " + operation);
        }
    }

    private String truncate(String value) {
        if (value == null || value.length() <= 1000) {
            return value;
        }
        return value.substring(0, 1000);
    }
}
