package com.surprising.gateway.provider.repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class CustodyWithdrawalRepository {

    private static final Set<String> RETRYABLE_STATUSES =
            Set.of("DEBIT_UNKNOWN", "BROADCAST_UNKNOWN", "REFUND_PENDING");

    private final JdbcTemplate jdbcTemplate;

    public CustodyWithdrawalRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public CreateResult createOrGet(CreateRequest request) {
        WithdrawalRecord existing = findByUserAndIdempotency(request.userId(), request.idempotencyKey());
        if (existing != null) {
            if (!existing.requestHash().equals(request.requestHash())) {
                throw new IllegalArgumentException("idempotency key was reused with a different withdrawal request");
            }
            return new CreateResult(existing, false);
        }
        jdbcTemplate.execute("SELECT pg_advisory_xact_lock(" + request.userId() + ")");
        existing = findByUserAndIdempotency(request.userId(), request.idempotencyKey());
        if (existing != null) {
            if (!existing.requestHash().equals(request.requestHash())) {
                throw new IllegalArgumentException("idempotency key was reused with a different withdrawal request");
            }
            return new CreateResult(existing, false);
        }
        BigDecimal dailyTotal = jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(usdt_value), 0)
                  FROM gateway_wallet_withdrawals
                 WHERE user_id = ?
                   AND created_at >= date_trunc('day', now())
                   AND status NOT IN ('REJECTED', 'REFUNDED')
                """, BigDecimal.class, request.userId());
        if (dailyTotal != null && dailyTotal.add(request.usdtValue()).compareTo(request.dailyLimitUsdt()) > 0) {
            throw new IllegalArgumentException("daily withdrawal limit exceeded");
        }
        UUID id = UUID.randomUUID();
        String status = request.requiresApproval() ? "PENDING_APPROVAL" : "PROCESSING";
        Instant now = Instant.now();
        jdbcTemplate.update("""
                INSERT INTO gateway_wallet_withdrawals (
                    withdrawal_id, user_id, idempotency_key, request_sha256, chain, asset_symbol,
                    custody_address_id, to_address, amount, amount_units, usdt_value, external_reference,
                    spot_debit_reference, request_payload, status, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
                """, id, request.userId(), request.idempotencyKey(), request.requestHash(), request.chain(),
                request.assetSymbol(), request.custodyAddressId(), request.toAddress(), request.amount(),
                request.amountUnits(), request.usdtValue(), request.externalReference(),
                request.spotDebitReference(), request.requestPayload(), status, Timestamp.from(now), Timestamp.from(now));
        return new CreateResult(find(id), true);
    }

    public WithdrawalRecord find(UUID withdrawalId) {
        List<WithdrawalRecord> rows = jdbcTemplate.query(selectSql("withdrawal_id = ?"), this::toRecord, withdrawalId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public WithdrawalRecord findByUserAndIdempotency(long userId, String idempotencyKey) {
        List<WithdrawalRecord> rows = jdbcTemplate.query(selectSql("user_id = ? AND idempotency_key = ?"),
                this::toRecord, userId, idempotencyKey);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public WithdrawalRecord findByWalletReference(String walletWithdrawalId, String externalReference) {
        if ((walletWithdrawalId == null || walletWithdrawalId.isBlank())
                && (externalReference == null || externalReference.isBlank())) {
            throw new IllegalArgumentException("withdrawal webhook identifiers are required");
        }
        String predicate;
        Object[] args;
        if (walletWithdrawalId != null && !walletWithdrawalId.isBlank()) {
            if (externalReference != null && !externalReference.isBlank()) {
                predicate = "external_reference = ? AND wallet_withdrawal_id = ?";
                args = new Object[]{externalReference, walletWithdrawalId};
            } else {
                predicate = "wallet_withdrawal_id = ?";
                args = new Object[]{walletWithdrawalId};
            }
        } else {
            predicate = "external_reference = ?";
            args = new Object[]{externalReference};
        }
        List<WithdrawalRecord> rows = jdbcTemplate.query(selectSql(predicate, 2), this::toRecord, args);
        if (rows.size() > 1) {
            throw new IllegalStateException("withdrawal webhook identifiers are ambiguous");
        }
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public List<WithdrawalRecord> listForUser(long userId, String chain, String asset, int limit) {
        if (userId <= 0L) {
            throw new IllegalArgumentException("userId must be positive");
        }
        int safeLimit = Math.max(1, Math.min(limit, 200));
        StringBuilder predicate = new StringBuilder("user_id = ?");
        List<Object> args = new java.util.ArrayList<>();
        args.add(userId);
        if (chain != null && !chain.isBlank()) {
            predicate.append(" AND chain = ?");
            args.add(chain.trim());
        }
        if (asset != null && !asset.isBlank()) {
            predicate.append(" AND asset_symbol = ?");
            args.add(asset.trim().toUpperCase());
        }
        return jdbcTemplate.query(selectSql(predicate.toString(), safeLimit), this::toRecord, args.toArray());
    }

    public List<WithdrawalRecord> list(String status, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 200));
        String predicate = status == null || status.isBlank() ? "TRUE" : "status = ?";
        return status == null || status.isBlank()
                ? jdbcTemplate.query(selectListSql(predicate, safeLimit), this::toRecord)
                : jdbcTemplate.query(selectListSql(predicate, safeLimit), this::toRecord, status.trim().toUpperCase());
    }

    public List<WithdrawalRecord> listPendingFailures(Duration gracePeriod, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 200));
        long seconds = Math.max(1L, gracePeriod.getSeconds());
        return jdbcTemplate.query(selectListSql(
                "status = 'FAILED_PENDING' AND updated_at <= now() - (? * INTERVAL '1 second')", safeLimit),
                this::toRecord, seconds);
    }

    @Transactional
    public WithdrawalRecord recordAdminRetry(UUID id, long adminUserId, String adminUsername, String reason) {
        int updated = jdbcTemplate.update("""
                UPDATE gateway_wallet_withdrawals
                   SET admin_user_id = ?, admin_username = ?, admin_reason = ?, updated_at = now()
                 WHERE withdrawal_id = ? AND status IN ('DEBIT_UNKNOWN', 'BROADCAST_UNKNOWN', 'REFUND_PENDING')
                """, adminUserId, adminUsername, reason, id);
        WithdrawalRecord record = requireRetryable(id, updated, "withdrawal is not retryable in its current state");
        insertAdminAction(id, adminUserId, adminUsername, "RETRY", reason);
        return record;
    }

    @Transactional
    public WithdrawalRecord approve(UUID id, long adminUserId, String adminUsername, String reason) {
        int updated = jdbcTemplate.update("""
                UPDATE gateway_wallet_withdrawals
                   SET status = 'PROCESSING', admin_user_id = ?, admin_username = ?, admin_reason = ?, updated_at = now()
                 WHERE withdrawal_id = ? AND status = 'PENDING_APPROVAL'
                """, adminUserId, adminUsername, reason, id);
        WithdrawalRecord record = requireTransition(id, updated, "withdrawal is not pending approval", "PROCESSING");
        insertAdminAction(id, adminUserId, adminUsername, "APPROVE", reason);
        return record;
    }

    @Transactional
    public WithdrawalRecord reject(UUID id, long adminUserId, String adminUsername, String reason) {
        int updated = jdbcTemplate.update("""
                UPDATE gateway_wallet_withdrawals
                   SET status = 'REJECTED', error_code = 'ADMIN_REJECTED', error_message = ?,
                       admin_user_id = ?, admin_username = ?, admin_reason = ?, updated_at = now()
                 WHERE withdrawal_id = ? AND status = 'PENDING_APPROVAL'
                """, reason, adminUserId, adminUsername, reason, id);
        WithdrawalRecord record = requireTransition(id, updated, "withdrawal is not pending approval", "REJECTED");
        insertAdminAction(id, adminUserId, adminUsername, "REJECT", reason);
        return record;
    }

    public WithdrawalRecord markDebited(UUID id, String reason) {
        int updated = jdbcTemplate.update("""
                UPDATE gateway_wallet_withdrawals
                   SET status = 'DEBITED', error_code = NULL, error_message = NULL, updated_at = now()
                 WHERE withdrawal_id = ? AND status IN ('PROCESSING', 'DEBIT_UNKNOWN')
                """, id);
        return requireConditionalUpdate(id, updated, "cannot transition withdrawal to DEBITED");
    }

    public WithdrawalRecord markDebitUnknown(UUID id, String error) {
        int updated = jdbcTemplate.update("""
                UPDATE gateway_wallet_withdrawals
                   SET status = 'DEBIT_UNKNOWN', error_code = 'SPOT_UNKNOWN', error_message = ?, updated_at = now()
                 WHERE withdrawal_id = ? AND status IN ('PROCESSING', 'DEBIT_UNKNOWN')
                """, error, id);
        return requireConditionalUpdate(id, updated, "cannot transition withdrawal to DEBIT_UNKNOWN");
    }

    public WithdrawalRecord markSubmitted(UUID id, String walletResponse, String walletWithdrawalId) {
        int updated = jdbcTemplate.update("""
                UPDATE gateway_wallet_withdrawals
                   SET status = 'SUBMITTED', wallet_response = ?::jsonb, wallet_withdrawal_id = ?,
                       submitted_at = COALESCE(submitted_at, now()), updated_at = now(), error_code = NULL,
                       error_message = NULL
                 WHERE withdrawal_id = ? AND status IN ('DEBITED', 'BROADCAST_UNKNOWN')
                """, walletResponse == null ? "{}" : walletResponse, walletWithdrawalId, id);
        return requireConditionalUpdate(id, updated, "cannot mark withdrawal submitted");
    }

    public WithdrawalRecord markBroadcastUnknown(UUID id, String walletResponse, String error) {
        return markBroadcastUnknown(id, walletResponse, error, null);
    }

    public WithdrawalRecord markBroadcastUnknown(UUID id, String walletResponse, String error,
                                                 String walletWithdrawalId) {
        int updated = jdbcTemplate.update("""
                UPDATE gateway_wallet_withdrawals
                   SET status = 'BROADCAST_UNKNOWN', wallet_response = ?::jsonb,
                       wallet_withdrawal_id = COALESCE(wallet_withdrawal_id, ?),
                       error_code = 'CUSTODY_UNKNOWN', error_message = ?, updated_at = now()
                 WHERE withdrawal_id = ? AND status IN ('DEBITED', 'SUBMITTED', 'BROADCAST_UNKNOWN')
                """, walletResponse == null ? "{}" : walletResponse, walletWithdrawalId, error, id);
        return requireConditionalUpdate(id, updated, "cannot mark withdrawal broadcast unknown");
    }

    @Transactional
    public WithdrawalRecord markCompleted(UUID id, String walletResponse, String walletWithdrawalId) {
        lockForOutcome(id);
        int updated = jdbcTemplate.update("""
                UPDATE gateway_wallet_withdrawals
                   SET status = 'COMPLETED', wallet_response = ?::jsonb,
                       wallet_withdrawal_id = COALESCE(wallet_withdrawal_id, ?),
                       completed_at = now(), updated_at = now(),
                       error_code = NULL, error_message = NULL
                 WHERE withdrawal_id = ? AND status IN ('SUBMITTED', 'BROADCAST_UNKNOWN', 'FAILED_PENDING')
                """, walletResponse == null ? "{}" : walletResponse, walletWithdrawalId, id);
        return requireTransition(id, updated, "cannot mark withdrawal completed", "COMPLETED");
    }

    @Transactional
    public WithdrawalRecord markFailurePending(UUID id, String walletResponse, String error,
                                               String walletWithdrawalId) {
        lockForOutcome(id);
        int updated = jdbcTemplate.update("""
                UPDATE gateway_wallet_withdrawals
                   SET status = 'FAILED_PENDING', wallet_response = ?::jsonb,
                       wallet_withdrawal_id = COALESCE(wallet_withdrawal_id, ?),
                       error_code = 'CUSTODY_FAILURE_PENDING', error_message = ?, updated_at = now()
                 WHERE withdrawal_id = ? AND status IN ('DEBITED', 'SUBMITTED', 'BROADCAST_UNKNOWN', 'FAILED_PENDING')
                """, walletResponse == null ? "{}" : walletResponse, walletWithdrawalId, error, id);
        return requireTransition(id, updated, "cannot mark withdrawal failure pending", "FAILED_PENDING");
    }

    @Transactional
    public WithdrawalRecord markRefundPending(UUID id, String error) {
        lockForOutcome(id);
        int updated = jdbcTemplate.update("""
                UPDATE gateway_wallet_withdrawals
                   SET status = 'REFUND_PENDING', error_code = 'REFUND_UNKNOWN', error_message = ?, updated_at = now()
                 WHERE withdrawal_id = ? AND status IN ('DEBITED', 'SUBMITTED', 'BROADCAST_UNKNOWN',
                                                        'FAILED_PENDING', 'REFUND_PENDING')
                """, error, id);
        return requireTransition(id, updated, "cannot mark withdrawal refund pending", "REFUND_PENDING");
    }

    @Transactional
    public WithdrawalRecord markRefunded(UUID id, String walletResponse, String reason) {
        lockForOutcome(id);
        int updated = jdbcTemplate.update("""
                UPDATE gateway_wallet_withdrawals
                   SET status = 'REFUNDED', wallet_response = ?::jsonb, error_code = 'REFUNDED',
                       error_message = ?, completed_at = COALESCE(completed_at, now()), updated_at = now()
                 WHERE withdrawal_id = ? AND status IN ('DEBITED', 'SUBMITTED', 'BROADCAST_UNKNOWN',
                                                        'FAILED_PENDING', 'REFUND_PENDING')
                """, walletResponse == null ? "{}" : walletResponse, reason, id);
        return requireTransition(id, updated, "cannot mark withdrawal refunded", "REFUNDED");
    }

    public WithdrawalRecord markRejected(UUID id, String code, String error) {
        int updated = jdbcTemplate.update("""
                UPDATE gateway_wallet_withdrawals
                   SET status = 'REJECTED', error_code = ?, error_message = ?, updated_at = now()
                 WHERE withdrawal_id = ? AND status IN ('PENDING_APPROVAL', 'PROCESSING')
                """, code, error, id);
        return requireConditionalUpdate(id, updated, "cannot mark withdrawal rejected");
    }

    private WithdrawalRecord requireConditionalUpdate(UUID id, int updated, String message) {
        WithdrawalRecord record = find(id);
        if (updated == 0) {
            throw new IllegalStateException(message + "; current status is "
                    + (record == null ? "missing" : record.status()));
        }
        return record;
    }

    private WithdrawalRecord requireTransition(UUID id, int updated, String message, String targetStatus) {
        WithdrawalRecord record = find(id);
        if (updated == 0 && (record == null || !targetStatus.equals(record.status()))) {
            throw new IllegalStateException(message + "; current status is "
                    + (record == null ? "missing" : record.status()));
        }
        return record;
    }

    private WithdrawalRecord requireRetryable(UUID id, int updated, String message) {
        WithdrawalRecord record = find(id);
        if (updated == 0 && (record == null || !RETRYABLE_STATUSES.contains(record.status()))) {
            throw new IllegalStateException(message + "; current status is "
                    + (record == null ? "missing" : record.status()));
        }
        return record;
    }

    private void insertAdminAction(UUID withdrawalId, long adminUserId, String adminUsername,
                                   String action, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("admin action reason is required");
        }
        jdbcTemplate.update("""
                INSERT INTO gateway_wallet_withdrawal_actions
                    (action_id, withdrawal_id, admin_user_id, admin_username, action, reason)
                VALUES (?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), withdrawalId, adminUserId, adminUsername, action, reason.trim());
    }

    public void lockForOutcome(UUID id) {
        Long lockKey = jdbcTemplate.queryForObject(
                "SELECT hashtextextended(?::text, 0)", Long.class, id.toString());
        jdbcTemplate.execute("SELECT pg_advisory_xact_lock(" + lockKey + ")");
    }

    private String selectSql(String predicate) {
        return selectSql(predicate, 1);
    }

    private String selectSql(String predicate, int limit) {
        return """
                SELECT withdrawal_id, user_id, idempotency_key, request_sha256, chain, asset_symbol,
                       custody_address_id, to_address, amount, amount_units, usdt_value, external_reference,
                       spot_debit_reference, request_payload::text, status, wallet_response::text,
                       wallet_withdrawal_id, error_code, error_message, created_at, updated_at,
                       submitted_at, completed_at, admin_user_id, admin_username, admin_reason
                  FROM gateway_wallet_withdrawals
                 WHERE
                """ + predicate + " ORDER BY created_at DESC LIMIT " + limit;
    }

    private String selectListSql(String predicate, int limit) {
        return selectSql(predicate, limit);
    }

    private WithdrawalRecord toRecord(ResultSet rs, int rowNum) throws SQLException {
        return new WithdrawalRecord(
                rs.getObject("withdrawal_id", UUID.class), rs.getLong("user_id"), rs.getString("idempotency_key"),
                rs.getString("request_sha256"), rs.getString("chain"), rs.getString("asset_symbol"),
                rs.getObject("custody_address_id", UUID.class), rs.getString("to_address"), rs.getString("amount"),
                rs.getLong("amount_units"), rs.getBigDecimal("usdt_value"), rs.getString("external_reference"),
                rs.getString("spot_debit_reference"), rs.getString("request_payload"), rs.getString("status"),
                rs.getString("wallet_response"), rs.getString("wallet_withdrawal_id"), rs.getString("error_code"),
                rs.getString("error_message"), instant(rs, "created_at"), instant(rs, "updated_at"),
                nullableInstant(rs, "submitted_at"), nullableInstant(rs, "completed_at"),
                nullableLong(rs, "admin_user_id"), rs.getString("admin_username"), rs.getString("admin_reason"));
    }

    private Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getTimestamp(column).toInstant();
    }

    private Instant nullableInstant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    public record CreateRequest(long userId, String idempotencyKey, String requestHash, String chain,
                                String assetSymbol, UUID custodyAddressId, String toAddress, String amount,
                                long amountUnits, BigDecimal usdtValue, String externalReference,
                                String spotDebitReference, String requestPayload, boolean requiresApproval,
                                BigDecimal dailyLimitUsdt) {
    }

    public record CreateResult(WithdrawalRecord record, boolean created) {
    }

    public record WithdrawalRecord(UUID withdrawalId, long userId, String idempotencyKey, String requestHash,
                                   String chain, String assetSymbol, UUID custodyAddressId, String toAddress,
                                   String amount, long amountUnits, BigDecimal usdtValue, String externalReference,
                                   String spotDebitReference, String requestPayload, String status,
                                   String walletResponse, String walletWithdrawalId, String errorCode,
                                   String errorMessage, Instant createdAt, Instant updatedAt, Instant submittedAt,
                                   Instant completedAt, Long adminUserId, String adminUsername, String adminReason) {
    }
}
