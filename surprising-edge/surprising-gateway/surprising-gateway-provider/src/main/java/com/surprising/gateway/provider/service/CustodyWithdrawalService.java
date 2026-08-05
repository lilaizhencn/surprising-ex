package com.surprising.gateway.provider.service;

import com.surprising.gateway.provider.config.GatewayProperties;
import com.surprising.gateway.provider.repository.CustodyWithdrawalRepository;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class CustodyWithdrawalService {

    private static final Logger LOGGER = LoggerFactory.getLogger(CustodyWithdrawalService.class);

    private final GatewayProperties properties;
    private final CustodyWithdrawalRepository repository;
    private final CustodyWalletClient walletClient;
    private final SpotAccountClient spotAccountClient;
    private final WithdrawalValuationClient valuationClient;
    private final CustodyWithdrawalRefundService refundService;
    private final CustodyWithdrawalReconciliationService reconciliationService;
    private final ObjectMapper objectMapper;

    public CustodyWithdrawalService(GatewayProperties properties,
                                    CustodyWithdrawalRepository repository,
                                    CustodyWalletClient walletClient,
                                    SpotAccountClient spotAccountClient,
                                    WithdrawalValuationClient valuationClient,
                                    CustodyWithdrawalRefundService refundService,
                                    CustodyWithdrawalReconciliationService reconciliationService,
                                    ObjectMapper objectMapper) {
        this.properties = properties;
        this.repository = repository;
        this.walletClient = walletClient;
        this.spotAccountClient = spotAccountClient;
        this.valuationClient = valuationClient;
        this.refundService = refundService;
        this.reconciliationService = reconciliationService;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
    }

    public WithdrawalResponse submit(long userId, String idempotencyKey, WithdrawalRequest request) {
        validateInput(userId, idempotencyKey, request);
        String normalizedKey = idempotencyKey.trim();
        long amountUnits = walletClient.amountUnits(request.assetSymbol(), request.amount());
        BigDecimal amount = new BigDecimal(request.amount().trim());
        BigDecimal usdtValue = valuationClient.toUsdt(request.assetSymbol(), amount);
        String spotReference = "custody-wallet-withdrawal:" + normalizedKey;
        String externalReference = "custody-wallet-withdrawal:"
                + sha256(userId + ":" + normalizedKey).substring(0, 32);
        String payload = payload(request, externalReference);
        CustodyWithdrawalRepository.CreateResult result = repository.createOrGet(
                new CustodyWithdrawalRepository.CreateRequest(
                        userId, normalizedKey, sha256(canonical(request, amountUnits, usdtValue)),
                        request.chain().trim(), request.assetSymbol().trim().toUpperCase(), request.custodyAddressId(),
                        request.toAddress().trim(), request.amount().trim(), amountUnits, usdtValue,
                        externalReference, spotReference, payload,
                        usdtValue.compareTo(properties.getWithdrawal().getSingleApprovalThresholdUsdt()) >= 0,
                        properties.getWithdrawal().getDailyLimitUsdt()));
        CustodyWithdrawalRepository.WithdrawalRecord record = result.record();
        if ("PENDING_APPROVAL".equals(record.status()) || terminal(record.status())) {
            return response(record);
        }

        return continueSubmission(record);
    }

    public WithdrawalResponse approve(UUID withdrawalId, long adminUserId, String adminUsername, String reason) {
        CustodyWithdrawalRepository.WithdrawalRecord record = repository.approve(
                withdrawalId, adminUserId, adminUsername, reason);
        return continueSubmission(record);
    }

    public WithdrawalResponse reject(UUID withdrawalId, long adminUserId, String adminUsername, String reason) {
        return response(repository.reject(withdrawalId, adminUserId, adminUsername, reason));
    }

    public WithdrawalResponse retry(UUID withdrawalId, long adminUserId, String adminUsername, String reason) {
        return continueSubmission(repository.recordAdminRetry(withdrawalId, adminUserId, adminUsername, reason));
    }

    public List<Map<String, Object>> adminList(String status, int limit) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (CustodyWithdrawalRepository.WithdrawalRecord record : repository.list(status, limit)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("withdrawalId", record.withdrawalId());
            row.put("userId", record.userId());
            row.put("idempotencyKey", record.idempotencyKey());
            row.put("chain", record.chain());
            row.put("assetSymbol", record.assetSymbol());
            row.put("custodyAddressId", record.custodyAddressId());
            row.put("toAddress", record.toAddress());
            row.put("amount", record.amount());
            row.put("amountUnits", record.amountUnits());
            row.put("usdtValue", record.usdtValue());
            row.put("externalReference", record.externalReference());
            row.put("status", record.status());
            row.put("walletResponse", record.walletResponse());
            row.put("walletWithdrawalId", record.walletWithdrawalId());
            row.put("errorCode", record.errorCode());
            row.put("errorMessage", record.errorMessage());
            row.put("createdAt", record.createdAt());
            row.put("updatedAt", record.updatedAt());
            row.put("submittedAt", record.submittedAt());
            row.put("completedAt", record.completedAt());
            row.put("adminUserId", record.adminUserId());
            row.put("adminUsername", record.adminUsername());
            row.put("adminReason", record.adminReason());
            rows.add(row);
        }
        return rows;
    }

    public java.util.List<Map<String, Object>> history(long userId, String chain, String asset, int limit) {
        java.util.List<Map<String, Object>> rows = new java.util.ArrayList<>();
        boolean custodyUnavailable = false;
        try {
            for (Map<String, Object> item : walletClient.withdrawals(userId, chain, asset, limit)) {
                rows.add(new LinkedHashMap<>(item));
            }
        } catch (IllegalStateException ex) {
            custodyUnavailable = true;
        }
        for (CustodyWithdrawalRepository.WithdrawalRecord record
                : repository.listForUser(userId, chain, asset, limit)) {
            Map<String, Object> matched = null;
            if (record.walletWithdrawalId() != null) {
                for (Map<String, Object> row : rows) {
                    String walletId = stringValue(row.get("withdrawalId"), stringValue(row.get("id"), null));
                    if (record.walletWithdrawalId().equals(walletId)) {
                        matched = row;
                        break;
                    }
                }
            }
            if (matched == null) {
                matched = new LinkedHashMap<>();
                rows.add(matched);
            }
            matched.put("id", record.walletWithdrawalId() == null
                    ? record.withdrawalId().toString() : record.walletWithdrawalId());
            matched.put("gatewayWithdrawalId", record.withdrawalId().toString());
            matched.put("status", record.status());
            matched.put("chain", record.chain());
            matched.put("asset", record.assetSymbol());
            matched.put("amount", record.amount());
            matched.put("toAddress", record.toAddress());
            matched.put("externalReference", record.externalReference());
            matched.put("usdtValue", record.usdtValue());
            matched.put("errorMessage", record.errorMessage());
            matched.put("custodyWalletUnavailable", custodyUnavailable);
            matched.put("createdAt", record.createdAt());
            matched.put("updatedAt", record.updatedAt());
        }
        return rows;
    }

    public void reconcileFailedWithdrawals() {
        for (CustodyWithdrawalRepository.WithdrawalRecord record
                : repository.listPendingFailures(properties.getWithdrawal().getFailureReconciliationDelay(), 50)) {
            try {
                reconciliationService.reconcile(record);
            } catch (RuntimeException ex) {
                LOGGER.warn("custody withdrawal failure reconciliation remains pending: {}", record.withdrawalId(), ex);
            }
        }
    }

    private WithdrawalResponse continueSubmission(CustodyWithdrawalRepository.WithdrawalRecord record) {
        if (record == null) {
            throw new IllegalStateException("withdrawal intent does not exist");
        }
        if ("REFUND_PENDING".equals(record.status())) {
            refund(record, "custody wallet withdrawal refund retry", "custody wallet withdrawal refund retry");
            return response(repository.find(record.withdrawalId()));
        }
        if (terminal(record.status()) || "FAILED_PENDING".equals(record.status())) {
            return response(record);
        }

        if (!"DEBITED".equals(record.status()) && !"SUBMITTED".equals(record.status())
                && !"BROADCAST_UNKNOWN".equals(record.status())) {
            debit(record);
            record = repository.markDebited(record.withdrawalId(), "custody wallet withdrawal");
        }
        if ("SUBMITTED".equals(record.status())) {
            return response(record);
        }
        Map<String, Object> walletResponse;
        try {
            walletResponse = walletClient.createWithdrawal(record.userId(),
                    withdrawalPayload(record), custodyIdempotencyKey(record));
        } catch (CustodyWalletClient.CustodyWalletRejectedException ex) {
            refund(record, "custody wallet withdrawal rejected refund", "custody wallet rejected withdrawal");
            throw new WithdrawalRejectedException("custody wallet rejected withdrawal; funds were released", ex);
        } catch (RuntimeException ex) {
            try {
                repository.markBroadcastUnknown(record.withdrawalId(), "{}", message(ex));
            } catch (IllegalStateException stateEx) {
                CustodyWithdrawalRepository.WithdrawalRecord current = repository.find(record.withdrawalId());
                if (current != null && submissionOutcomeAdvanced(current.status())) {
                    return response(current);
                }
                throw stateEx;
            }
            throw new WithdrawalUnknownException("custody wallet withdrawal status is unknown", ex);
        }
        try {
            record = repository.markSubmitted(record.withdrawalId(), json(walletResponse),
                    stringValue(walletResponse.get("withdrawalId"), stringValue(walletResponse.get("id"), null)));
            return response(record);
        } catch (IllegalStateException ex) {
            CustodyWithdrawalRepository.WithdrawalRecord current = repository.find(record.withdrawalId());
            if (current != null && submissionOutcomeAdvanced(current.status())) {
                return response(current);
            }
            throw ex;
        }
    }

    private boolean submissionOutcomeAdvanced(String status) {
        return "SUBMITTED".equals(status) || "COMPLETED".equals(status)
                || "FAILED_PENDING".equals(status) || "REFUND_PENDING".equals(status)
                || "REFUNDED".equals(status);
    }

    private boolean lateBroadcastUnknownCanBeIgnored(String status) {
        return "SUBMITTED".equals(status) || "COMPLETED".equals(status)
                || "REFUNDED".equals(status) || "REJECTED".equals(status);
    }

    private boolean terminalWebhookIsIdempotent(String status, String eventType) {
        return ("COMPLETED".equals(status) && "WITHDRAWAL.CONFIRMED".equals(eventType))
                || ("REFUNDED".equals(status) && "WITHDRAWAL.FAILED".equals(eventType));
    }

    @Transactional
    public void handleWebhook(String eventType, Map<String, Object> event) {
        Map<String, Object> data = mapValue(event.get("data"));
        String walletWithdrawalId = stringValue(data.get("withdrawalId"), null);
        String externalReference = stringValue(data.get("externalReference"), null);
        CustodyWithdrawalRepository.WithdrawalRecord record = repository.findByWalletReference(
                walletWithdrawalId, externalReference);
        if (record == null) {
            throw new IllegalArgumentException("withdrawal webhook does not match a local withdrawal");
        }
        String normalizedType = eventType.toUpperCase(Locale.ROOT);
        validateWebhookData(record, data);
        if ("COMPLETED".equals(record.status()) || "REFUNDED".equals(record.status())
                || "REJECTED".equals(record.status())) {
            if (terminalWebhookIsIdempotent(record.status(), normalizedType)) {
                return;
            }
            throw new IllegalStateException("withdrawal webhook conflicts with terminal local status");
        }
        String response = json(event);
        switch (normalizedType) {
            case "WITHDRAWAL.CREATED", "WITHDRAWAL.BROADCAST" -> repository.markSubmitted(
                    record.withdrawalId(), response, walletWithdrawalId);
            case "WITHDRAWAL.BROADCAST_UNKNOWN" -> {
                try {
                    repository.markBroadcastUnknown(record.withdrawalId(), response,
                            "custody wallet broadcast status is unknown", walletWithdrawalId);
                } catch (IllegalStateException ex) {
                    CustodyWithdrawalRepository.WithdrawalRecord current = repository.find(record.withdrawalId());
                    if (current == null || !lateBroadcastUnknownCanBeIgnored(current.status())) {
                        throw ex;
                    }
                }
            }
            case "WITHDRAWAL.CONFIRMED" -> repository.markCompleted(
                    record.withdrawalId(), response, walletWithdrawalId);
            case "WITHDRAWAL.FAILED" -> repository.markFailurePending(
                    record.withdrawalId(), response, "custody wallet withdrawal failed", walletWithdrawalId);
            default -> throw new IllegalArgumentException("unsupported withdrawal webhook event type");
        }
    }

    private void debit(CustodyWithdrawalRepository.WithdrawalRecord record) {
        try {
            spotAccountClient.adjustBalance(record.userId(), record.assetSymbol(), -record.amountUnits(),
                    record.spotDebitReference(), "custody wallet withdrawal");
        } catch (SpotAccountClient.SpotAccountRejectedException ex) {
            repository.markRejected(record.withdrawalId(), "SPOT_REJECTED", message(ex));
            throw new WithdrawalRejectedException("spot account rejected withdrawal", ex);
        } catch (RuntimeException ex) {
            repository.markDebitUnknown(record.withdrawalId(), message(ex));
            throw new WithdrawalUnknownException("spot account debit status is unknown", ex);
        }
    }

    private void refund(CustodyWithdrawalRepository.WithdrawalRecord record,
                        String spotReason,
                        String stateReason) {
        try {
            refundService.refund(record, spotReason, stateReason);
        } catch (RuntimeException ex) {
            repository.markRefundPending(record.withdrawalId(), message(ex));
            throw new WithdrawalUnknownException("withdrawal refund status is unknown", ex);
        }
    }

    private Map<String, Object> withdrawalPayload(CustodyWithdrawalRepository.WithdrawalRecord record) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("custodyAddressId", record.custodyAddressId());
        payload.put("chain", record.chain());
        payload.put("assetSymbol", record.assetSymbol());
        payload.put("toAddress", record.toAddress());
        payload.put("amount", record.amount());
        payload.put("externalReference", record.externalReference() == null || record.externalReference().isBlank()
                ? record.spotDebitReference() : record.externalReference());
        payload.put("confirmed", true);
        return payload;
    }

    private String payload(WithdrawalRequest request, String externalReference) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("custodyAddressId", request.custodyAddressId());
        payload.put("chain", request.chain());
        payload.put("assetSymbol", request.assetSymbol());
        payload.put("toAddress", request.toAddress());
        payload.put("amount", request.amount());
        payload.put("externalReference", externalReference);
        if (request.externalReference() != null && !request.externalReference().isBlank()) {
            payload.put("clientExternalReference", request.externalReference().trim());
        }
        payload.put("confirmed", true);
        return json(payload);
    }

    private String canonical(WithdrawalRequest request, long amountUnits, BigDecimal usdtValue) {
        return String.join("|", request.custodyAddressId().toString(), request.chain().trim(),
                request.assetSymbol().trim().toUpperCase(), request.toAddress().trim(), request.amount().trim(),
                Long.toString(amountUnits), usdtValue.toPlainString(),
                request.externalReference() == null ? "" : request.externalReference().trim());
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException ex) {
            throw new IllegalStateException("withdrawal payload cannot be serialized", ex);
        }
    }

    private Map<String, Object> mapValue(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("withdrawal webhook data is invalid");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> converted = (Map<String, Object>) map;
        return converted;
    }

    private String stringValue(Object value, String fallback) {
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
    }

    private void validateWebhookData(CustodyWithdrawalRepository.WithdrawalRecord record,
                                     Map<String, Object> data) {
        String asset = stringValue(data.get("assetSymbol"), stringValue(data.get("asset"), null));
        if (asset == null || !record.assetSymbol().equalsIgnoreCase(asset)) {
            throw new IllegalArgumentException("withdrawal webhook asset does not match local intent");
        }
        String chain = stringValue(data.get("chain"), stringValue(data.get("chainId"), null));
        if (chain == null || !record.chain().equalsIgnoreCase(chain)) {
            throw new IllegalArgumentException("withdrawal webhook chain does not match local intent");
        }
        String amount = stringValue(data.get("amount"), stringValue(data.get("withdrawalAmount"), null));
        if (amount == null) {
            throw new IllegalArgumentException("withdrawal webhook amount does not match local intent");
        }
        try {
            if (new BigDecimal(amount).compareTo(new BigDecimal(record.amount())) != 0) {
                throw new IllegalArgumentException("withdrawal webhook amount does not match local intent");
            }
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("withdrawal webhook amount is invalid", ex);
        }
    }

    private boolean terminal(String status) {
        return "SUBMITTED".equals(status) || "COMPLETED".equals(status)
                || "REFUNDED".equals(status) || "REJECTED".equals(status);
    }

    private CustodyWithdrawalRepository.WithdrawalRecord requireRecord(UUID withdrawalId) {
        CustodyWithdrawalRepository.WithdrawalRecord record = repository.find(withdrawalId);
        if (record == null) {
            throw new IllegalArgumentException("withdrawal intent does not exist");
        }
        return record;
    }

    private void validateInput(long userId, String idempotencyKey, WithdrawalRequest request) {
        if (userId <= 0L || idempotencyKey == null || !idempotencyKey.matches("[A-Za-z0-9._:-]{8,128}")
                || request == null || request.custodyAddressId() == null || request.chain() == null
                || request.chain().isBlank() || request.assetSymbol() == null || request.assetSymbol().isBlank()
                || request.toAddress() == null || request.toAddress().isBlank() || request.amount() == null
                || request.amount().isBlank() || request.chain().length() > 32 || request.assetSymbol().length() > 32
                || request.toAddress().length() > 160 || request.amount().length() > 120
                || (request.externalReference() != null && request.externalReference().length() > 160)) {
            throw new IllegalArgumentException("withdrawal request is invalid");
        }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private String message(Throwable throwable) {
        return throwable.getMessage() == null ? throwable.getClass().getSimpleName() : throwable.getMessage();
    }

    private String custodyIdempotencyKey(CustodyWithdrawalRepository.WithdrawalRecord record) {
        if (record.externalReference() != null && !record.externalReference().isBlank()) {
            return record.externalReference();
        }
        return "custody-wallet-idempotency:" + sha256(record.userId() + ":" + record.idempotencyKey());
    }

    private WithdrawalResponse response(CustodyWithdrawalRepository.WithdrawalRecord record) {
        return new WithdrawalResponse(record.withdrawalId(), record.status(), record.walletWithdrawalId(),
                record.usdtValue(), record.createdAt(), record.updatedAt());
    }

    public record WithdrawalRequest(UUID custodyAddressId, String chain, String assetSymbol, String toAddress,
                                    String amount, String externalReference) {
    }

    public record WithdrawalResponse(UUID withdrawalId, String status, String walletWithdrawalId,
                                     BigDecimal usdtValue, Instant createdAt, Instant updatedAt) {
    }

    public static class WithdrawalRejectedException extends IllegalStateException {
        public WithdrawalRejectedException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static class WithdrawalUnknownException extends IllegalStateException {
        public WithdrawalUnknownException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
