package com.surprising.gateway.provider.service;

import com.surprising.gateway.provider.client.CustodyWalletClient;
import com.surprising.gateway.provider.model.CustodyWithdrawalModels;
import com.surprising.gateway.provider.service.CustodyWithdrawalService.WithdrawalRequest;
import com.surprising.gateway.provider.service.CustodyWithdrawalService.WithdrawalResponse;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * 托管提现请求参数校验、规范化哈希、载荷序列化及响应组装辅助。
 */
final class CustodyWithdrawalPayloadSupport {

    private CustodyWithdrawalPayloadSupport() {
    }

    static String rejectionEvidence(ObjectMapper objectMapper, CustodyWalletClient.CustodyWalletRejectedException ex) {
        return json(objectMapper, Map.of("kind", "SUBMISSION_REFUSAL",
                "receipt", objectMapper.readValue(ex.responseBody(), Map.class)));
    }

    static Map<String, Object> withdrawalPayload(CustodyWithdrawalModels.WithdrawalRecord record) {
        return CustodyWithdrawalModels.custodyRequest(record);
    }

    static String payload(ObjectMapper objectMapper, WithdrawalRequest request, String externalReference) {
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
        return json(objectMapper, payload);
    }

    static String canonical(ObjectMapper objectMapper, WithdrawalRequest request) {
        return json(objectMapper, List.of(
                request.chain().trim().toUpperCase(Locale.ROOT),
                request.assetSymbol().trim().toUpperCase(Locale.ROOT),
                request.toAddress().trim(),
                new BigDecimal(request.amount().trim()).stripTrailingZeros().toPlainString(),
                request.externalReference() == null ? "" : request.externalReference().trim(),
                request.custodyAddressId() == null ? "" : request.custodyAddressId().toString(),
                request.networkRevision() == null ? "" : request.networkRevision().toString()));
    }

    static String json(ObjectMapper objectMapper, Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException ex) {
            throw new IllegalStateException("withdrawal payload cannot be serialized", ex);
        }
    }

    static String stringValue(Object value, String fallback) {
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
    }

    static boolean matchesPayload(CustodyWithdrawalModels.WithdrawalRecord record, Map<String, Object> payload) {
        if (payload == null) return false;
        try {
            return record.fundingSource().equals(payload.get("fundingSource"))
                    && ("user:" + record.userId()).equals(payload.get("subject"))
                    && record.chain().equalsIgnoreCase(String.valueOf(payload.get("chain")))
                    && record.assetSymbol().equalsIgnoreCase(
                            stringValue(payload.get("assetSymbol"), stringValue(payload.get("asset"), "")))
                    && record.toAddress().equals(payload.get("toAddress"))
                    && new BigDecimal(String.valueOf(payload.get("amount")))
                            .compareTo(new BigDecimal(record.fees().receivedAmount())) == 0;
        } catch (NumberFormatException invalid) {
            return false;
        }
    }

    static boolean terminal(String status) {
        return "SUBMITTED".equals(status) || "COMPLETED".equals(status)
                || "REFUNDED".equals(status) || "REJECTED".equals(status);
    }

    static void validateInput(long userId, String idempotencyKey, WithdrawalRequest request) {
        if (userId <= 0L || idempotencyKey == null || !idempotencyKey.matches("[A-Za-z0-9._:-]{8,128}")
                || request == null || request.custodyAddressId() != null || request.chain() == null
                || request.chain().isBlank() || request.assetSymbol() == null || request.assetSymbol().isBlank()
                || request.toAddress() == null || request.toAddress().isBlank() || request.amount() == null
                || request.amount().isBlank() || request.chain().length() > 32 || request.assetSymbol().length() > 32
                || request.toAddress().length() > 160 || request.amount().length() > 120
                || (request.externalReference() != null && request.externalReference().length() > 160)) {
            throw new IllegalArgumentException("withdrawal request is invalid");
        }
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    static String message(Throwable throwable) {
        return throwable.getMessage() == null ? throwable.getClass().getSimpleName() : throwable.getMessage();
    }

    static String custodyIdempotencyKey(CustodyWithdrawalModels.WithdrawalRecord record) {
        if (record.externalReference() != null && !record.externalReference().isBlank()) {
            return record.externalReference();
        }
        return "custody-wallet-idempotency:" + sha256(record.userId() + ":" + record.idempotencyKey());
    }

    static WithdrawalResponse response(CustodyWithdrawalModels.WithdrawalRecord record) {
        return new WithdrawalResponse(
                record.withdrawalId(), record.status(), record.walletWithdrawalId(),
                record.usdtValue(), record.createdAt(), record.updatedAt(),
                record.amount(), record.fees().fee(), record.fees().receivedAmount(),
                record.fees().networkRevision());
    }
}
