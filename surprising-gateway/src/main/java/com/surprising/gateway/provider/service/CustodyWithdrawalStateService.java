package com.surprising.gateway.provider.service;

import com.surprising.gateway.provider.model.CustodyWithdrawalModels.CreateRequest;
import com.surprising.gateway.provider.model.CustodyWithdrawalModels.CreateResult;
import com.surprising.gateway.provider.model.CustodyWithdrawalModels.WithdrawalRecord;
import com.surprising.gateway.provider.repository.CustodyWithdrawalRepository;
import com.surprising.gateway.provider.repository.CustodyWithdrawalEventRepository;
import com.surprising.gateway.provider.repository.CustodyWithdrawalActionRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 提现意图、幂等/额度、状态流转和必需动作/事件的事务入口，不承担钱包网络调用。 */
@Service
public class CustodyWithdrawalStateService {
    private static final Set<String> RETRYABLE_STATUSES = Set.of("DEBIT_UNKNOWN", "BROADCAST_UNKNOWN", "REFUND_PENDING");
    private final CustodyWithdrawalRepository repository;
    private final CustodyWithdrawalEventRepository events;
    private final CustodyWithdrawalActionRepository actions;
    public CustodyWithdrawalStateService(CustodyWithdrawalRepository repository,
                                        CustodyWithdrawalEventRepository events,
                                        CustodyWithdrawalActionRepository actions) {
        this.repository = repository;
        this.events = events;
        this.actions = actions;
    }

    @Transactional
    public CreateResult createOrGet(CreateRequest request) {
        WithdrawalRecord existing = repository.findByUserAndIdempotency(request.userId(), request.idempotencyKey());
        if (existing != null) {
            if (!existing.requestHash().equals(request.requestHash())) {
                throw new IllegalArgumentException("idempotency key was reused with a different withdrawal request");
            }
            return new CreateResult(existing, false);
        }
        repository.lockUserQuota(request.userId());
        existing = repository.findByUserAndIdempotency(request.userId(), request.idempotencyKey());
        if (existing != null) {
            if (!existing.requestHash().equals(request.requestHash())) {
                throw new IllegalArgumentException("idempotency key was reused with a different withdrawal request");
            }
            return new CreateResult(existing, false);
        }
        BigDecimal dailyTotal = repository.dailyTotal(request.userId());
        if (dailyTotal != null && dailyTotal.add(request.usdtValue()).compareTo(request.dailyLimitUsdt()) > 0) {
            throw new IllegalArgumentException("daily withdrawal limit exceeded");
        }
        UUID id = UUID.randomUUID();
        String status = request.requiresApproval() ? "PENDING_APPROVAL" : "PROCESSING";
        Instant now = Instant.now();
        repository.insertIntent(id, request, status, now);
        WithdrawalRecord created = repository.find(id);
        insertTransitionEvent(id, "INTENT_CREATED", "USER", null, status, null,
                request.requestPayload(), "withdrawal intent created");
        return new CreateResult(created, true);
    }

    @Transactional
    public void recordWebhookObservation(UUID id, String walletWithdrawalId, String payload, String reason) {
        recordWebhookObservation(id, walletWithdrawalId, payload, reason, null);
    }

    @Transactional
    public void recordWebhookObservation(UUID id, String walletWithdrawalId, String payload, String reason,
                                         String providerEventId) {
        WithdrawalRecord record = repository.find(id);
        if (record == null) {
            throw new IllegalStateException("withdrawal intent does not exist");
        }
        insertTransitionEvent(id, "WEBHOOK_IDEMPOTENT", "CUSTODY_WALLET", record.status(), record.status(),
                walletWithdrawalId, providerEventId, payload, reason);
    }

    @Transactional
    public boolean recordLateBroadcastUnknownObservation(UUID id, String walletWithdrawalId, String payload,
                                                         String reason, String providerEventId) {
        WithdrawalRecord record = lockAndValidateWalletReference(id, walletWithdrawalId);
        if (!Set.of("SUBMITTED", "COMPLETED", "REFUNDED", "REJECTED").contains(record.status())) {
            return false;
        }
        insertTransitionEvent(id, "WEBHOOK_IDEMPOTENT", "CUSTODY_WALLET", record.status(), record.status(),
                walletWithdrawalId, providerEventId, payload, reason);
        return true;
    }

    @Transactional
    public WithdrawalRecord findByWalletReference(String walletWithdrawalId, String externalReference) {
        return findByWalletReference(walletWithdrawalId, externalReference, null);
    }

    @Transactional
    public WithdrawalRecord findByWalletReference(String walletWithdrawalId, String externalReference,
                                                  String providerEventId) {
        if ((walletWithdrawalId == null || walletWithdrawalId.isBlank())
                && (externalReference == null || externalReference.isBlank())) {
            throw new IllegalArgumentException("withdrawal webhook identifiers are required");
        }
        if (externalReference != null && !externalReference.isBlank()) {
            List<WithdrawalRecord> rows = repository.findByExternalReference(externalReference);
            if (rows.size() > 1) {
                throw new IllegalStateException("withdrawal webhook external reference is ambiguous");
            }
            if (rows.isEmpty()) {
                return null;
            }
            WithdrawalRecord record = rows.getFirst();
            if (walletWithdrawalId == null || walletWithdrawalId.isBlank()) {
                return record;
            }
            if (record.walletWithdrawalId() != null
                    && !record.walletWithdrawalId().equals(walletWithdrawalId)) {
                throw new IllegalStateException("withdrawal webhook wallet id does not match local intent");
            }
            if (record.walletWithdrawalId() == null) {
                WithdrawalRecord owner = findByWalletWithdrawalId(walletWithdrawalId);
                if (owner != null && !owner.withdrawalId().equals(record.withdrawalId())) {
                    throw new IllegalStateException("withdrawal webhook wallet id is already bound");
                }
                int updated;
                try {
                    updated = repository.bindWalletId(record.withdrawalId(), walletWithdrawalId);
                } catch (DataIntegrityViolationException ex) {
                    throw new IllegalStateException("withdrawal webhook wallet id is already bound", ex);
                }
                if (updated == 0) {
                    WithdrawalRecord current = repository.find(record.withdrawalId());
                    if (current == null) {
                        return null;
                    }
                    if (current.walletWithdrawalId() != null
                            && !current.walletWithdrawalId().equals(walletWithdrawalId)) {
                        throw new IllegalStateException("withdrawal webhook wallet id does not match local intent");
                    }
                }
                if (updated == 1) {
                    insertTransitionEvent(record.withdrawalId(), "WALLET_ID_BOUND", "CUSTODY_WALLET",
                            record.status(), record.status(), walletWithdrawalId, providerEventId, "{}",
                            "wallet withdrawal id correlated by external reference");
                }
                return repository.find(record.withdrawalId());
            }
            return record;
        }
        List<WithdrawalRecord> rows = repository.findByWalletId(walletWithdrawalId);
        if (rows.size() > 1) {
            throw new IllegalStateException("withdrawal webhook identifiers are ambiguous");
        }
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private WithdrawalRecord findByWalletWithdrawalId(String walletWithdrawalId) {
        List<WithdrawalRecord> rows = repository.findByWalletId(walletWithdrawalId);
        if (rows.size() > 1) {
            throw new IllegalStateException("withdrawal webhook wallet id is ambiguous");
        }
        return rows.isEmpty() ? null : rows.getFirst();
    }

    @Transactional
    public WithdrawalRecord recordAdminRetry(UUID id, long adminUserId, String adminUsername, String reason) {
        int updated = repository.recordAdminRetryRow(id, adminUserId, adminUsername, reason);
        WithdrawalRecord record = requireRetryable(id, updated, "withdrawal is not retryable in its current state");
        insertAdminAction(id, adminUserId, adminUsername, "RETRY", reason);
        if (updated == 1) {
            insertTransitionEvent(id, "ADMIN_RETRY", "ADMIN", record.status(), record.status(),
                    record.walletWithdrawalId(), "{}", reason);
        }
        return record;
    }

    @Transactional
    public WithdrawalRecord approve(UUID id, long adminUserId, String adminUsername, String reason) {
        WithdrawalRecord before = repository.find(id);
        int updated = repository.approveRow(id, adminUserId, adminUsername, reason);
        WithdrawalRecord record = requireConditionalUpdate(id, updated, "withdrawal is not pending approval");
        insertAdminAction(id, adminUserId, adminUsername, "APPROVE", reason);
        recordTransitionEvent(id, "ADMIN_APPROVED", "ADMIN", before, record, updated,
                record.walletWithdrawalId(), "{}", reason);
        return record;
    }

    @Transactional
    public WithdrawalRecord reject(UUID id, long adminUserId, String adminUsername, String reason) {
        WithdrawalRecord before = repository.find(id);
        int updated = repository.rejectRow(id, adminUserId, adminUsername, reason);
        WithdrawalRecord record = requireConditionalUpdate(id, updated, "withdrawal is not pending approval");
        insertAdminAction(id, adminUserId, adminUsername, "REJECT", reason);
        recordTransitionEvent(id, "ADMIN_REJECTED", "ADMIN", before, record, updated,
                record.walletWithdrawalId(), "{}", reason);
        return record;
    }

    @Transactional
    public WithdrawalRecord markDebited(UUID id, String reason) {
        repository.lockForOutcome(id);
        WithdrawalRecord before = repository.find(id);
        int updated = repository.markDebitedRow(id);
        WithdrawalRecord record = requireTransition(id, updated, "cannot transition withdrawal to DEBITED", "DEBITED");
        recordTransitionEvent(id, "DEBITED", "SPOT_ACCOUNT", before, record, updated,
                record.walletWithdrawalId(), "{}", reason);
        return record;
    }

    @Transactional
    public WithdrawalRecord markDebitUnknown(UUID id, String error) {
        repository.lockForOutcome(id);
        WithdrawalRecord before = repository.find(id);
        int updated = repository.markDebitUnknownRow(id, error);
        WithdrawalRecord record = requireTransition(id, updated, "cannot transition withdrawal to DEBIT_UNKNOWN", "DEBIT_UNKNOWN");
        recordTransitionEvent(id, "DEBIT_UNKNOWN", "SPOT_ACCOUNT", before, record, updated,
                record == null ? null : record.walletWithdrawalId(), "{}", error);
        return record;
    }

    @Transactional
    public WithdrawalRecord markSubmitted(UUID id, String walletResponse, String walletWithdrawalId) {
        return markSubmitted(id, walletResponse, walletWithdrawalId, null);
    }

    @Transactional
    public WithdrawalRecord markSubmitted(UUID id, String walletResponse, String walletWithdrawalId,
                                          String providerEventId) {
        WithdrawalRecord before = lockAndValidateWalletReference(id, walletWithdrawalId);
        int updated;
        try {
            updated = repository.markSubmittedRow(id, walletResponse, walletWithdrawalId);
        } catch (DataIntegrityViolationException ex) {
            throw new IllegalStateException("wallet withdrawal id is already bound", ex);
        }
        WithdrawalRecord record = requireTransition(id, updated, "cannot mark withdrawal submitted", "SUBMITTED");
        recordTransitionEvent(id, "SUBMITTED", "CUSTODY_WALLET", before, record, updated,
                walletWithdrawalId, walletResponse, null, providerEventId);
        return record;
    }

    @Transactional
    public WithdrawalRecord markBroadcastUnknown(UUID id, String walletResponse, String error) {
        return markBroadcastUnknown(id, walletResponse, error, null);
    }

    @Transactional
    public WithdrawalRecord markBroadcastUnknown(UUID id, String walletResponse, String error,
                                                 String walletWithdrawalId) {
        return markBroadcastUnknown(id, walletResponse, error, walletWithdrawalId, null);
    }

    @Transactional
    public WithdrawalRecord markBroadcastUnknown(UUID id, String walletResponse, String error,
                                                 String walletWithdrawalId, String providerEventId) {
        WithdrawalRecord before = lockAndValidateWalletReference(id, walletWithdrawalId);
        int updated;
        try {
            updated = repository.markBroadcastUnknownRow(id, walletResponse, error, walletWithdrawalId);
        } catch (DataIntegrityViolationException ex) {
            throw new IllegalStateException("wallet withdrawal id is already bound", ex);
        }
        WithdrawalRecord record = requireTransition(id, updated, "cannot mark withdrawal broadcast unknown", "BROADCAST_UNKNOWN");
        recordTransitionEvent(id, "BROADCAST_UNKNOWN", "CUSTODY_WALLET", before, record, updated,
                walletWithdrawalId, walletResponse, error, providerEventId);
        return record;
    }

    @Transactional
    public WithdrawalRecord markCompleted(UUID id, String walletResponse, String walletWithdrawalId) {
        return markCompleted(id, walletResponse, walletWithdrawalId, null);
    }

    @Transactional
    public WithdrawalRecord markCompleted(UUID id, String walletResponse, String walletWithdrawalId,
                                          String providerEventId) {
        WithdrawalRecord before = lockAndValidateWalletReference(id, walletWithdrawalId);
        int updated;
        try {
            updated = repository.markCompletedRow(id, walletResponse, walletWithdrawalId);
        } catch (DataIntegrityViolationException ex) {
            throw new IllegalStateException("wallet withdrawal id is already bound", ex);
        }
        WithdrawalRecord record = requireTransition(id, updated, "cannot mark withdrawal completed", "COMPLETED");
        recordTransitionEvent(id, "COMPLETED", "CUSTODY_WALLET", before, record, updated,
                walletWithdrawalId, walletResponse, null, providerEventId);
        return record;
    }

    @Transactional
    public WithdrawalRecord markFailurePending(UUID id, String walletResponse, String error,
                                               String walletWithdrawalId) {
        return markFailurePending(id, walletResponse, error, walletWithdrawalId, null);
    }

    @Transactional
    public WithdrawalRecord markFailurePending(UUID id, String walletResponse, String error,
                                               String walletWithdrawalId, String providerEventId) {
        WithdrawalRecord before = lockAndValidateWalletReference(id, walletWithdrawalId);
        int updated;
        try {
            updated = repository.markFailurePendingRow(id, walletResponse, error, walletWithdrawalId);
        } catch (DataIntegrityViolationException ex) {
            throw new IllegalStateException("wallet withdrawal id is already bound", ex);
        }
        WithdrawalRecord record = requireTransition(id, updated, "cannot mark withdrawal failure pending", "FAILED_PENDING");
        recordTransitionEvent(id, "FAILED_PENDING", "CUSTODY_WALLET", before, record, updated,
                walletWithdrawalId, walletResponse, error, providerEventId);
        return record;
    }

    @Transactional
    public WithdrawalRecord markRefundPending(UUID id, String error) {
        return markRefundPending(id, error, null);
    }

    @Transactional
    public WithdrawalRecord markRefundPending(UUID id, String error, String walletResponse) {
        repository.lockForOutcome(id);
        WithdrawalRecord before = repository.find(id);
        int updated = repository.markRefundPendingRow(id, error, walletResponse);
        WithdrawalRecord record = requireTransition(id, updated, "cannot mark withdrawal refund pending", "REFUND_PENDING");
        recordTransitionEvent(id, "REFUND_PENDING", "RECONCILIATION", before, record, updated,
                record == null ? null : record.walletWithdrawalId(), walletResponse, error);
        return record;
    }

    @Transactional
    public WithdrawalRecord markRefunded(UUID id, String walletResponse, String reason) {
        repository.lockForOutcome(id);
        WithdrawalRecord before = repository.find(id);
        int updated = repository.markRefundedRow(id, walletResponse, reason);
        WithdrawalRecord record = requireTransition(id, updated, "cannot mark withdrawal refunded", "REFUNDED");
        recordTransitionEvent(id, "REFUNDED", "SPOT_ACCOUNT", before, record, updated,
                record == null ? null : record.walletWithdrawalId(), walletResponse, reason);
        return record;
    }

    @Transactional
    public WithdrawalRecord markRejected(UUID id, String code, String error) {
        repository.lockForOutcome(id);
        WithdrawalRecord before = repository.find(id);
        int updated = repository.markRejectedRow(id, code, error);
        WithdrawalRecord record = requireTransition(id, updated, "cannot mark withdrawal rejected", "REJECTED");
        recordTransitionEvent(id, "REJECTED", "SPOT_ACCOUNT", before, record, updated,
                record == null ? null : record.walletWithdrawalId(), "{}", error);
        return record;
    }

    private WithdrawalRecord requireConditionalUpdate(UUID id, int updated, String message) {
        WithdrawalRecord record = repository.find(id);
        if (updated == 0) {
            throw new IllegalStateException(message + "; current status is "
                    + (record == null ? "missing" : record.status()));
        }
        return record;
    }

    private WithdrawalRecord requireTransition(UUID id, int updated, String message, String targetStatus) {
        WithdrawalRecord record = repository.find(id);
        if (updated == 0 && (record == null || !targetStatus.equals(record.status()))) {
            throw new IllegalStateException(message + "; current status is "
                    + (record == null ? "missing" : record.status()));
        }
        return record;
    }

    private WithdrawalRecord requireRetryable(UUID id, int updated, String message) {
        WithdrawalRecord record = repository.find(id);
        if (updated == 0 && (record == null || !RETRYABLE_STATUSES.contains(record.status()))) {
            throw new IllegalStateException(message + "; current status is "
                    + (record == null ? "missing" : record.status()));
        }
        return record;
    }

    private WithdrawalRecord lockAndValidateWalletReference(UUID id, String walletWithdrawalId) {
        repository.lockForOutcome(id);
        WithdrawalRecord current = repository.find(id);
        if (current == null) {
            throw new IllegalStateException("withdrawal intent does not exist");
        }
        if (walletWithdrawalId != null && !walletWithdrawalId.isBlank()
                && current.walletWithdrawalId() != null
                && !current.walletWithdrawalId().equals(walletWithdrawalId)) {
            throw new IllegalStateException("wallet withdrawal id does not match local intent");
        }
        return current;
    }

    private void recordTransitionEvent(UUID id, String eventType, String source,
                                       WithdrawalRecord before, WithdrawalRecord after, int updated,
                                       String walletWithdrawalId, String payload, String reason) {
        recordTransitionEvent(id, eventType, source, before, after, updated,
                walletWithdrawalId, payload, reason, null);
    }

    private void recordTransitionEvent(UUID id, String eventType, String source,
                                       WithdrawalRecord before, WithdrawalRecord after, int updated,
                                       String walletWithdrawalId, String payload, String reason,
                                       String providerEventId) {
        if (updated == 1 && before != null && after != null) {
            insertTransitionEvent(id, eventType, source, before.status(), after.status(),
                    walletWithdrawalId, providerEventId, payload, reason);
        }
    }

    private void insertTransitionEvent(UUID id, String eventType, String source,
                                       String fromStatus, String toStatus, String walletWithdrawalId,
                                       String payload, String reason) {
        insertTransitionEvent(id, eventType, source, fromStatus, toStatus, walletWithdrawalId, null, payload, reason);
    }

    private void insertTransitionEvent(UUID id, String eventType, String source,
                                       String fromStatus, String toStatus, String walletWithdrawalId,
                                       String providerEventId, String payload, String reason) {
        events.append(id, eventType, source, fromStatus, toStatus, walletWithdrawalId, providerEventId, payload, reason);
    }

    private void insertAdminAction(UUID withdrawalId, long adminUserId, String adminUsername,
                                   String action, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("admin action reason is required");
        }
        actions.append(withdrawalId, adminUserId, adminUsername, action, reason.trim());
    }
}
