package com.surprising.funding.provider.repository;

import com.surprising.funding.provider.model.FundingPaymentResult;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 原子回写资金费支付结果与结算进度。
 *
 * <p>不可拆原因：funding_payments 的终态与 funding_settlements 的成功、失败计数必须由同一条 SQL
 * 原子推进。若拆成两个单表 Repository，即使 Service 开启事务，也会扩大锁窗口并允许重复结果在两次写入之间
 * 造成计数漂移。此处仅是资金安全写路径，不提供报表、时间线或对账查询。</p>
 */
@Repository
public class FundingPaymentCompletionRepository {

    private final JdbcTemplate jdbcTemplate;

    public FundingPaymentCompletionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean complete(String commandId,
                            long expectedUserId,
                            String terminalStatus,
                            String errorCode,
                            String errorMessage,
                            java.time.Instant completedAt) {
        return completeBatch(List.of(new FundingPaymentResult(commandId, expectedUserId, terminalStatus,
                errorCode, errorMessage, completedAt))) == 1;
    }

    public int completeBatch(List<FundingPaymentResult> results) {
        if (results == null || results.isEmpty()) {
            return 0;
        }
        Map<String, FundingPaymentResult> unique = new LinkedHashMap<>();
        for (FundingPaymentResult result : results) {
            validate(result);
            FundingPaymentResult previous = unique.putIfAbsent(result.commandId(), result);
            if (previous != null && !previous.equals(result)) {
                throw new IllegalStateException("conflicting funding payment results for " + result.commandId());
            }
        }
        String placeholders = String.join(", ", java.util.Collections.nCopies(unique.size(), "?"));
        List<PaymentState> states = jdbcTemplate.query("""
                SELECT payment_id, settlement_id, command_id, user_id, status
                  FROM funding_payments
                 WHERE command_id IN (%s)
                """.formatted(placeholders), (rs, rowNum) -> new PaymentState(
                rs.getLong("payment_id"), rs.getLong("settlement_id"), rs.getString("command_id"),
                rs.getLong("user_id"), rs.getString("status")), unique.keySet().toArray());
        Map<String, PaymentState> stateByCommand = new LinkedHashMap<>();
        for (PaymentState state : states) {
            stateByCommand.put(state.commandId(), state);
        }
        List<FundingPaymentResult> pending = new ArrayList<>();
        for (FundingPaymentResult result : unique.values()) {
            PaymentState state = stateByCommand.get(result.commandId());
            if (state == null) {
                throw new IllegalStateException("funding payment not found for " + result.commandId());
            }
            if (state.userId() != result.userId()) {
                throw new IllegalStateException("funding payment user mismatch for " + result.commandId());
            }
            if ("PENDING".equals(state.status())) {
                pending.add(result);
            } else if (!state.status().equals(result.status())) {
                throw new IllegalStateException("conflicting funding payment result for " + result.commandId());
            }
        }
        if (pending.isEmpty()) {
            return 0;
        }

        StringBuilder values = new StringBuilder();
        List<Object> args = new ArrayList<>(pending.size() * 6);
        for (FundingPaymentResult result : pending) {
            if (!values.isEmpty()) {
                values.append(", ");
            }
            values.append("(?::TEXT, ?::BIGINT, ?::TEXT, ?::TEXT, ?::TEXT, ?::TIMESTAMPTZ)");
            args.add(result.commandId());
            args.add(result.userId());
            args.add(result.status());
            args.add(result.errorCode());
            args.add(truncate(result.errorMessage()));
            args.add(Timestamp.from(result.completedAt()));
        }
        Integer updated = jdbcTemplate.queryForObject("""
                WITH input(command_id, user_id, status, error_code, error_message, completed_at) AS (
                    VALUES %s
                ),
                updated AS (
                    UPDATE funding_payments p
                       SET status = i.status,
                           applied_at = CASE WHEN i.status = 'APPLIED' THEN i.completed_at ELSE NULL END,
                           rejected_at = CASE WHEN i.status = 'REJECTED' THEN i.completed_at ELSE NULL END,
                           error_code = i.error_code,
                           error_message = i.error_message,
                           updated_at = i.completed_at
                      FROM input i
                     WHERE p.command_id = i.command_id
                       AND p.user_id = i.user_id
                       AND p.status = 'PENDING'
                    RETURNING p.settlement_id, p.status
                ),
                counts AS (
                    SELECT settlement_id,
                           count(*) FILTER (WHERE status = 'APPLIED')::INTEGER AS applied_count,
                           count(*) FILTER (WHERE status = 'REJECTED')::INTEGER AS rejected_count
                      FROM updated
                     GROUP BY settlement_id
                ),
                progress AS (
                    UPDATE funding_settlements s
                       SET applied_payment_count = s.applied_payment_count + c.applied_count,
                           rejected_payment_count = s.rejected_payment_count + c.rejected_count,
                           status = CASE
                               WHEN s.status = 'PROCESSING' THEN 'PROCESSING'
                               WHEN s.rejected_payment_count + c.rejected_count > 0 THEN 'FAILED'
                               WHEN s.applied_payment_count + c.applied_count = s.expected_payment_count
                                   THEN 'COMPLETED'
                               ELSE 'WAITING_ACCOUNTS'
                           END,
                           updated_at = GREATEST(s.updated_at, (
                               SELECT max(i.completed_at)
                                 FROM input i
                           ))
                      FROM counts c
                     WHERE s.settlement_id = c.settlement_id
                    RETURNING s.settlement_id
                )
                SELECT count(*)::INTEGER
                  FROM updated
                 WHERE (SELECT count(*) FROM progress) >= 0
                """.formatted(values), Integer.class, args.toArray());
        return updated == null ? 0 : updated;
    }

    private void validate(FundingPaymentResult result) {
        if (result == null || result.commandId() == null || result.commandId().isBlank()) {
            throw new IllegalArgumentException("funding payment commandId is required");
        }
        if (result.userId() <= 0L) {
            throw new IllegalArgumentException("funding payment userId must be positive");
        }
        if (!"APPLIED".equals(result.status()) && !"REJECTED".equals(result.status())) {
            throw new IllegalArgumentException("funding payment requires a terminal account status");
        }
        if (result.completedAt() == null) {
            throw new IllegalArgumentException("funding payment completedAt is required");
        }
    }

    private String truncate(String value) {
        if (value == null || value.length() <= 1000) {
            return value;
        }
        return value.substring(0, 1000);
    }

    private record PaymentState(
            long paymentId,
            long settlementId,
            String commandId,
            long userId,
            String status) {
    }
}
