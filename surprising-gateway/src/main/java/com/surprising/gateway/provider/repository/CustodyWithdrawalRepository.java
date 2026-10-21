package com.surprising.gateway.provider.repository;

import com.surprising.gateway.provider.model.CustodyWithdrawalModels.CreateRequest;
import com.surprising.gateway.provider.model.CustodyWithdrawalModels.WithdrawalRecord;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 只访问gateway_wallet_withdrawals；状态规则、动作审计和事件原子提交由StateService聚合。 */
@Repository
public class CustodyWithdrawalRepository {
    private final JdbcTemplate jdbcTemplate;

    public CustodyWithdrawalRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public BigDecimal dailyTotal(long userId) {
        return jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(usdt_value), 0)
                  FROM gateway_wallet_withdrawals
                 WHERE user_id = ?
                   AND created_at >= date_trunc('day', now())
                   AND status NOT IN ('REJECTED', 'REFUNDED')
                """, BigDecimal.class, userId);
    }

    public void insertIntent(UUID id, CreateRequest request, String status, Instant now) {
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

    public int bindWalletId(UUID id, String walletWithdrawalId) {
        return jdbcTemplate.update("""
                            UPDATE gateway_wallet_withdrawals
                               SET wallet_withdrawal_id = ?, updated_at = now()
                             WHERE withdrawal_id = ? AND wallet_withdrawal_id IS NULL
                            """, walletWithdrawalId, id);
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

    public int recordAdminRetryRow(UUID id, long adminUserId, String adminUsername, String reason) {
        return jdbcTemplate.update("""
                UPDATE gateway_wallet_withdrawals
                   SET admin_user_id = ?, admin_username = ?, admin_reason = ?, updated_at = now()
                 WHERE withdrawal_id = ? AND status IN ('DEBIT_UNKNOWN', 'BROADCAST_UNKNOWN', 'REFUND_PENDING')
                """, adminUserId, adminUsername, reason, id);
    }

    public int approveRow(UUID id, long adminUserId, String adminUsername, String reason) {
        return jdbcTemplate.update("""
                UPDATE gateway_wallet_withdrawals
                   SET status = 'PROCESSING', admin_user_id = ?, admin_username = ?, admin_reason = ?, updated_at = now()
                 WHERE withdrawal_id = ? AND status = 'PENDING_APPROVAL'
                """, adminUserId, adminUsername, reason, id);
    }

    public int rejectRow(UUID id, long adminUserId, String adminUsername, String reason) {
        return jdbcTemplate.update("""
                UPDATE gateway_wallet_withdrawals
                   SET status = 'REJECTED', error_code = 'ADMIN_REJECTED', error_message = ?,
                       admin_user_id = ?, admin_username = ?, admin_reason = ?, updated_at = now()
                 WHERE withdrawal_id = ? AND status = 'PENDING_APPROVAL'
                """, reason, adminUserId, adminUsername, reason, id);
    }

    public int markDebitedRow(UUID id) {
        return jdbcTemplate.update("""
                UPDATE gateway_wallet_withdrawals
                   SET status = 'DEBITED', error_code = NULL, error_message = NULL, updated_at = now()
                 WHERE withdrawal_id = ? AND status IN ('PROCESSING', 'DEBIT_UNKNOWN', 'DEBITED')
                """, id);
    }

    public int markDebitUnknownRow(UUID id, String error) {
        return jdbcTemplate.update("""
                UPDATE gateway_wallet_withdrawals
                   SET status = 'DEBIT_UNKNOWN', error_code = 'SPOT_UNKNOWN', error_message = ?, updated_at = now()
                 WHERE withdrawal_id = ? AND status IN ('PROCESSING', 'DEBIT_UNKNOWN')
                """, error, id);
    }

    public int markSubmittedRow(UUID id, String walletResponse, String walletWithdrawalId) {
        return jdbcTemplate.update("""
                    UPDATE gateway_wallet_withdrawals
                       SET status = 'SUBMITTED', wallet_response = COALESCE(wallet_response, ?::jsonb),
                           wallet_withdrawal_id = COALESCE(wallet_withdrawal_id, ?),
                           submitted_at = COALESCE(submitted_at, now()), updated_at = now(), error_code = NULL,
                           error_message = NULL
                     WHERE withdrawal_id = ? AND status IN ('DEBITED', 'BROADCAST_UNKNOWN', 'SUBMITTED')
                    """, walletResponse == null ? "{}" : walletResponse, walletWithdrawalId, id);
    }

    public int markBroadcastUnknownRow(UUID id, String walletResponse, String error, String walletWithdrawalId) {
        return jdbcTemplate.update("""
                    UPDATE gateway_wallet_withdrawals
                       SET status = 'BROADCAST_UNKNOWN', wallet_response = ?::jsonb,
                           wallet_withdrawal_id = COALESCE(wallet_withdrawal_id, ?),
                           error_code = 'CUSTODY_UNKNOWN', error_message = ?, updated_at = now()
                     WHERE withdrawal_id = ? AND status IN ('DEBITED', 'BROADCAST_UNKNOWN')
                    """, walletResponse == null ? "{}" : walletResponse, walletWithdrawalId, error, id);
    }

    public int markCompletedRow(UUID id, String walletResponse, String walletWithdrawalId) {
        return jdbcTemplate.update("""
                    UPDATE gateway_wallet_withdrawals
                       SET status = 'COMPLETED', wallet_response = ?::jsonb,
                           wallet_withdrawal_id = COALESCE(wallet_withdrawal_id, ?),
                           completed_at = now(), updated_at = now(),
                           error_code = NULL, error_message = NULL
                     WHERE withdrawal_id = ? AND status IN ('DEBITED', 'SUBMITTED', 'BROADCAST_UNKNOWN', 'FAILED_PENDING')
                    """, walletResponse == null ? "{}" : walletResponse, walletWithdrawalId, id);
    }

    public int markFailurePendingRow(UUID id, String walletResponse, String error, String walletWithdrawalId) {
        return jdbcTemplate.update("""
                    UPDATE gateway_wallet_withdrawals
                       SET status = 'FAILED_PENDING', wallet_response = ?::jsonb,
                           wallet_withdrawal_id = COALESCE(wallet_withdrawal_id, ?),
                           error_code = 'CUSTODY_FAILURE_PENDING', error_message = ?, updated_at = now()
                     WHERE withdrawal_id = ? AND status IN ('DEBITED', 'SUBMITTED', 'BROADCAST_UNKNOWN', 'FAILED_PENDING')
                    """, walletResponse == null ? "{}" : walletResponse, walletWithdrawalId, error, id);
    }

    public int markRefundPendingRow(UUID id, String error, String walletResponse) {
        return jdbcTemplate.update("""
                   UPDATE gateway_wallet_withdrawals
                   SET status = 'REFUND_PENDING', wallet_response = COALESCE(?::jsonb, wallet_response),
                       error_code = 'REFUND_UNKNOWN', error_message = ?, updated_at = now()
                 WHERE withdrawal_id = ? AND status IN ('DEBITED', 'SUBMITTED', 'BROADCAST_UNKNOWN',
                                                        'FAILED_PENDING', 'REFUND_PENDING')
                """, walletResponse, error, id);
    }

    public int markRefundedRow(UUID id, String walletResponse, String reason) {
        return jdbcTemplate.update("""
                UPDATE gateway_wallet_withdrawals
                   SET status = 'REFUNDED', wallet_response = ?::jsonb, error_code = 'REFUNDED',
                       error_message = ?, completed_at = COALESCE(completed_at, now()), updated_at = now()
                 WHERE withdrawal_id = ? AND status IN ('DEBITED', 'SUBMITTED', 'BROADCAST_UNKNOWN',
                                                        'FAILED_PENDING', 'REFUND_PENDING')
                """, walletResponse == null ? "{}" : walletResponse, reason, id);
    }

    public int markRejectedRow(UUID id, String code, String error) {
        return jdbcTemplate.update("""
                UPDATE gateway_wallet_withdrawals
                   SET status = 'REJECTED', error_code = ?, error_message = ?, updated_at = now()
                 WHERE withdrawal_id = ? AND status IN ('PENDING_APPROVAL', 'PROCESSING', 'REJECTED')
                """, code, error, id);
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

    public List<WithdrawalRecord> findByExternalReference(String externalReference) {
        return jdbcTemplate.query(selectSql("external_reference = ?", 2), this::toRecord, externalReference);
    }

    public List<WithdrawalRecord> findByWalletId(String walletWithdrawalId) {
        return jdbcTemplate.query(selectSql("wallet_withdrawal_id = ?", 2), this::toRecord, walletWithdrawalId);
    }
    /** 用户日额度锁；必须由Service事务持有到意图与事件一并提交。 */
    public void lockUserQuota(long userId) {
        jdbcTemplate.execute(connection -> {
            var statement = connection.prepareStatement("SELECT pg_advisory_xact_lock(?)");
            statement.setLong(1, userId);
            return statement;
        }, (org.springframework.jdbc.core.PreparedStatementCallback<Void>) statement -> {
            statement.execute();
            return null;
        });
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
}
