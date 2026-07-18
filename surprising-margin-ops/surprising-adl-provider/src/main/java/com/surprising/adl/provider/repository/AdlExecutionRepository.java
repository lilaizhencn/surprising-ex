package com.surprising.adl.provider.repository;

import com.surprising.account.api.model.AccountCommandStatus;
import com.surprising.account.api.model.AccountUserCommand;
import com.surprising.account.api.model.AccountUserCommandType;
import com.surprising.account.api.model.AdlTargetSettlementAccountCommand;
import com.surprising.account.api.model.DeficitReservationAccountCommand;
import com.surprising.adl.provider.config.AdlProperties;
import com.surprising.adl.provider.model.AdlExecutionPlan;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

@Repository
public class AdlExecutionRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final AdlProperties properties;

    public AdlExecutionRepository(JdbcTemplate jdbcTemplate,
                                  ObjectMapper objectMapper,
                                  AdlProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public void create(AdlExecutionPlan plan, Instant now) {
        int rows = jdbcTemplate.update("""
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
                plan.finalizeCommandId(), Timestamp.from(now), Timestamp.from(now));
        requireSingleRow(rows, "ADL execution saga insert");

        DeficitReservationAccountCommand deficitPayload =
                new DeficitReservationAccountCommand(plan.asset(), plan.coveredUnits());
        AccountUserCommand reserve = command(plan.reserveCommandId(), plan.productLine(), plan.deficitUserId(),
                AccountUserCommandType.ADL_DEFICIT_RESERVE, plan.executionId(), null, deficitPayload, now);
        AccountUserCommand target = command(plan.targetCommandId(), plan.productLine(), plan.targetUserId(),
                AccountUserCommandType.ADL_TARGET_SETTLE, plan.executionId(), plan.reserveCommandId(),
                new AdlTargetSettlementAccountCommand(
                        plan.executionId(), plan.deficitUserId(), plan.asset(), plan.symbol(),
                        plan.targetMarginMode(), plan.targetPositionSide(), plan.expectedSignedSteps(),
                        plan.closedQuantitySteps(), plan.entryPriceTicks(), plan.markPriceTicks(),
                        plan.realizedProfitUnits(), plan.coveredUnits()), now);
        AccountUserCommand finalize = command(plan.finalizeCommandId(), plan.productLine(), plan.deficitUserId(),
                AccountUserCommandType.ADL_DEFICIT_FINALIZE, plan.executionId(), plan.targetCommandId(),
                deficitPayload, now);
        enqueue(plan.executionId(), reserve, now);
        enqueue(plan.executionId(), target, now);
        enqueue(plan.executionId(), finalize, now);
    }

    public List<SagaState> lockPending(int limit) {
        return jdbcTemplate.query("""
                SELECT s.*,
                       reserve.status AS reserve_status,
                       target.status AS target_status,
                       finalize.status AS finalize_status,
                       release.status AS release_status,
                       finalize.result_payload::text AS finalize_result,
                       COALESCE(target.error_code, reserve.error_code, finalize.error_code, release.error_code)
                           AS terminal_error_code,
                       COALESCE(target.error_message, reserve.error_message, finalize.error_message,
                                release.error_message) AS terminal_error_message
                  FROM adl_execution_sagas s
                  LEFT JOIN account_commands reserve ON reserve.command_id = s.reserve_command_id
                  LEFT JOIN account_commands target ON target.command_id = s.target_command_id
                  LEFT JOIN account_commands finalize ON finalize.command_id = s.finalize_command_id
                  LEFT JOIN account_commands release ON release.command_id = s.release_command_id
                 WHERE s.product_line = ? AND s.status IN ('PENDING', 'RELEASING')
                 ORDER BY s.created_at ASC, s.execution_id ASC
                 LIMIT ?
                 FOR UPDATE OF s SKIP LOCKED
                """, (rs, rowNum) -> new SagaState(
                rs.getLong("execution_id"), rs.getString("product_line"), rs.getString("account_type"),
                rs.getLong("deficit_user_id"), rs.getLong("target_user_id"), rs.getString("asset"),
                rs.getString("symbol"), rs.getString("target_side"), rs.getString("target_position_side"),
                rs.getLong("closed_quantity_steps"), rs.getLong("entry_price_ticks"),
                rs.getLong("mark_price_ticks"), rs.getLong("requested_deficit_units"),
                rs.getLong("realized_profit_units"), rs.getLong("covered_units"),
                rs.getLong("priority_score_ppm"), rs.getString("reserve_command_id"),
                rs.getString("target_command_id"), rs.getString("finalize_command_id"),
                rs.getString("release_command_id"), rs.getString("status"),
                rs.getString("reserve_status"), rs.getString("target_status"),
                rs.getString("finalize_status"), rs.getString("release_status"),
                rs.getString("finalize_result"), rs.getString("terminal_error_code"),
                rs.getString("terminal_error_message")),
                properties.getKafka().getProductLine().name(), Math.max(1, limit));
    }

    public void beginRelease(SagaState saga, Instant now) {
        String releaseCommandId = "ADL_RELEASE:" + saga.productLine() + ":" + saga.executionId();
        AccountUserCommand release = command(
                releaseCommandId,
                com.surprising.product.api.ProductLine.valueOf(saga.productLine()),
                saga.deficitUserId(),
                AccountUserCommandType.ADL_DEFICIT_RELEASE,
                saga.executionId(),
                null,
                new DeficitReservationAccountCommand(saga.asset(), saga.coveredUnits()),
                now);
        int rows = jdbcTemplate.update("""
                UPDATE adl_execution_sagas
                   SET release_command_id = ?, status = 'RELEASING',
                       error_code = ?, error_message = ?, updated_at = ?
                 WHERE execution_id = ? AND status = 'PENDING' AND release_command_id IS NULL
                """, releaseCommandId, saga.terminalErrorCode(), truncate(saga.terminalErrorMessage()),
                Timestamp.from(now), saga.executionId());
        requireSingleRow(rows, "ADL release transition");
        enqueue(saga.executionId(), release, now);
    }

    public void failWithoutReservation(SagaState saga, Instant now) {
        int rows = jdbcTemplate.update("""
                UPDATE adl_execution_sagas
                   SET status = 'FAILED', error_code = ?, error_message = ?,
                       completed_at = ?, updated_at = ?
                 WHERE execution_id = ? AND status = 'PENDING'
                """, saga.terminalErrorCode(), truncate(saga.terminalErrorMessage()),
                Timestamp.from(now), Timestamp.from(now), saga.executionId());
        requireSingleRow(rows, "ADL failed transition");
    }

    public void completeRelease(SagaState saga, Instant now) {
        int rows = jdbcTemplate.update("""
                UPDATE adl_execution_sagas
                   SET status = 'FAILED', completed_at = ?, updated_at = ?
                 WHERE execution_id = ? AND status = 'RELEASING'
                """, Timestamp.from(now), Timestamp.from(now), saga.executionId());
        requireSingleRow(rows, "ADL release completion");
    }

    public void complete(SagaState saga, long remainingDeficitUnits, Instant now) {
        int eventRows = jdbcTemplate.update("""
                INSERT INTO adl_events (
                    event_id, account_type, deficit_user_id, target_user_id, asset, symbol,
                    target_side, target_position_side, closed_quantity_steps,
                    entry_price_ticks, mark_price_ticks, requested_deficit_units,
                    realized_profit_units, covered_units, remaining_deficit_units,
                    priority_score_ppm, reason, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                          'ADL_DEFICIT_COVERAGE', ?)
                ON CONFLICT (event_id) DO NOTHING
                """, saga.executionId(), saga.accountType(), saga.deficitUserId(), saga.targetUserId(),
                saga.asset(), saga.symbol(), saga.targetSide(), saga.targetPositionSide(),
                saga.closedQuantitySteps(), saga.entryPriceTicks(), saga.markPriceTicks(),
                saga.requestedDeficitUnits(), saga.realizedProfitUnits(), saga.coveredUnits(),
                remainingDeficitUnits, saga.priorityScorePpm(), Timestamp.from(now));
        requireSingleRow(eventRows, "ADL event completion insert");
        int rows = jdbcTemplate.update("""
                UPDATE adl_execution_sagas
                   SET status = 'COMPLETED', completed_at = ?, updated_at = ?
                 WHERE execution_id = ? AND status = 'PENDING'
                """, Timestamp.from(now), Timestamp.from(now), saga.executionId());
        requireSingleRow(rows, "ADL completed transition");
    }

    private AccountUserCommand command(String commandId,
                                       com.surprising.product.api.ProductLine productLine,
                                       long userId,
                                       AccountUserCommandType type,
                                       long executionId,
                                       String dependency,
                                       Object payload,
                                       Instant now) {
        return new AccountUserCommand(
                AccountUserCommand.CURRENT_SCHEMA_VERSION,
                commandId,
                productLine,
                userId,
                type,
                "ADL",
                Long.toString(executionId),
                dependency,
                objectMapper.writeValueAsString(payload),
                now,
                null);
    }

    private void enqueue(long executionId, AccountUserCommand command, Instant now) {
        int rows = jdbcTemplate.update("""
                INSERT INTO account_outbox_events (
                    product_line, aggregate_type, aggregate_id, topic, event_key, event_type,
                    payload, next_attempt_at, created_at, updated_at
                ) VALUES (?, 'ADL_ACCOUNT_COMMAND', ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
                """, command.productLine().name(), executionId, properties.getKafka().getUserCommandsTopic(),
                command.partitionKey(), command.commandType().name(), objectMapper.writeValueAsString(command),
                Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
        requireSingleRow(rows, "ADL account command enqueue");
    }

    private void requireSingleRow(int rows, String operation) {
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

    public record SagaState(
            long executionId,
            String productLine,
            String accountType,
            long deficitUserId,
            long targetUserId,
            String asset,
            String symbol,
            String targetSide,
            String targetPositionSide,
            long closedQuantitySteps,
            long entryPriceTicks,
            long markPriceTicks,
            long requestedDeficitUnits,
            long realizedProfitUnits,
            long coveredUnits,
            long priorityScorePpm,
            String reserveCommandId,
            String targetCommandId,
            String finalizeCommandId,
            String releaseCommandId,
            String sagaStatus,
            String reserveStatus,
            String targetStatus,
            String finalizeStatus,
            String releaseStatus,
            String finalizeResult,
            String terminalErrorCode,
            String terminalErrorMessage) {

        public boolean reserveRejected() {
            return AccountCommandStatus.REJECTED.name().equals(reserveStatus);
        }

        public boolean targetRejectedAfterReservation() {
            return AccountCommandStatus.APPLIED.name().equals(reserveStatus)
                    && AccountCommandStatus.REJECTED.name().equals(targetStatus);
        }

        public boolean finalizeApplied() {
            return AccountCommandStatus.APPLIED.name().equals(finalizeStatus);
        }

        public boolean releaseApplied() {
            return AccountCommandStatus.APPLIED.name().equals(releaseStatus);
        }
    }
}
