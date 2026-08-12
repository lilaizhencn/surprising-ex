package com.surprising.gateway.provider.service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ProductTransferRepository implements ProductTransferStore {

    private static final String SELECT = """
            SELECT transfer_id, user_id, idempotency_key, request_fingerprint, source_account_type,
                   target_account_type, asset, amount_units, reference_id, reason, status, error_code,
                   error_message, created_at, updated_at, completed_at
              FROM gateway_product_transfers
            """;

    private final JdbcTemplate jdbcTemplate;

    public ProductTransferRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public ProductTransferState createOrGet(ProductTransferCreateRequest request) {
        List<ProductTransferState> inserted = jdbcTemplate.query("""
                INSERT INTO gateway_product_transfers (
                    transfer_id, user_id, idempotency_key, request_fingerprint, source_account_type,
                    target_account_type, asset, amount_units, reference_id, reason, status, created_at, updated_at)
                VALUES (nextval('gateway_product_transfer_seq'), ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', now(), now())
                ON CONFLICT (user_id, idempotency_key) DO NOTHING RETURNING
                    transfer_id, user_id, idempotency_key, request_fingerprint, source_account_type,
                    target_account_type, asset, amount_units, reference_id, reason, status, error_code,
                    error_message, created_at, updated_at, completed_at
                """,
                this::map, request.userId(), request.idempotencyKey(), request.requestFingerprint(),
                request.sourceAccountType(), request.targetAccountType(), request.asset(), request.amountUnits(),
                request.referenceId(), request.reason());
        if (!inserted.isEmpty()) {
            ProductTransferState created = inserted.getFirst();
            insertEvent(created, null, created.status());
            return created;
        }
        return jdbcTemplate.query(SELECT + " WHERE user_id = ? AND idempotency_key = ?",
                this::map, request.userId(), request.idempotencyKey()).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("product transfer row was not created or found"));
    }

    @Override
    public ProductTransferState lock(long transferId) {
        return jdbcTemplate.query(SELECT + " WHERE transfer_id = ?", this::map, transferId)
                .stream().findFirst().orElse(null);
    }

    @Override
    @Transactional
    public ProductTransferState update(ProductTransferState previous, ProductTransferState next) {
        int updated = jdbcTemplate.update("""
                UPDATE gateway_product_transfers
                   SET status = ?, error_code = ?, error_message = ?, updated_at = ?, completed_at = ?
                 WHERE transfer_id = ? AND status = ?
                """, next.status().name(), next.errorCode(), next.errorMessage(),
                Timestamp.from(next.updatedAt()), timestamp(next.completedAt()), next.transferId(),
                previous.status().name());
        if (updated == 1) {
            insertEvent(next, previous.status(), next.status());
            return next;
        }
        return lock(next.transferId());
    }

    @Override
    public List<ProductTransferState> recoverable(int limit) {
        return jdbcTemplate.query(SELECT + " WHERE status IN ('PENDING', 'SOURCE_DEBIT_UNKNOWN', "
                        + "'SOURCE_DEBITED', 'TARGET_CREDIT_UNKNOWN', 'COMPENSATION_REQUIRED') "
                        + "AND updated_at < now() - interval '1 second' "
                        + "ORDER BY updated_at, transfer_id LIMIT ?",
                this::map, limit);
    }

    private ProductTransferState map(ResultSet rs, int rowNum) throws SQLException {
        return new ProductTransferState(rs.getLong("transfer_id"), rs.getLong("user_id"),
                rs.getString("idempotency_key"), rs.getString("request_fingerprint"),
                rs.getString("source_account_type"), rs.getString("target_account_type"), rs.getString("asset"),
                rs.getLong("amount_units"), rs.getString("reference_id"), rs.getString("reason"),
                ProductTransferStatus.valueOf(rs.getString("status")), rs.getString("error_code"),
                rs.getString("error_message"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(), timestamp(rs.getTimestamp("completed_at")));
    }

    private void insertEvent(ProductTransferState state, ProductTransferStatus from, ProductTransferStatus to) {
        jdbcTemplate.update("""
                INSERT INTO gateway_product_transfer_events
                    (event_id, transfer_id, from_status, to_status, error_code, error_message, created_at)
                VALUES (nextval('gateway_product_transfer_event_seq'), ?, ?, ?, ?, ?, now())
                """, state.transferId(), from == null ? null : from.name(), to.name(), state.errorCode(),
                state.errorMessage());
    }

    private Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private Instant timestamp(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
