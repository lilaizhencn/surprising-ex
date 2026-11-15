package com.surprising.gateway.provider.service;

import com.surprising.gateway.provider.model.CustodyWithdrawalModels;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Validates and applies custody wallet withdrawal webhooks to local withdrawal state.
 */
@Service
public class CustodyWithdrawalWebhookHandler {

    private final CustodyWithdrawalStateService states;
    private final ObjectMapper objectMapper;

    @Autowired
    public CustodyWithdrawalWebhookHandler(CustodyWithdrawalStateService states, ObjectMapper objectMapper) {
        this.states = states;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
    }

    @Transactional
    public void handleWebhook(String eventType, Map<String, Object> event) {
        handleWebhook(null, eventType, event);
    }

    @Transactional
    public void handleWebhook(String providerEventId, String eventType, Map<String, Object> event) {
        Map<String, Object> data = mapValue(event.get("data"));
        String walletWithdrawalId = stringValue(data.get("withdrawalId"), null);
        String externalReference = stringValue(data.get("externalReference"), null);
        if (walletWithdrawalId == null || externalReference == null) {
            throw new IllegalArgumentException("withdrawal webhook wallet id and external reference are required");
        }
        CustodyWithdrawalModels.WithdrawalRecord record = providerEventId == null
                ? states.findByWalletReference(walletWithdrawalId, externalReference)
                : states.findByWalletReference(walletWithdrawalId, externalReference, providerEventId);
        if (record == null) {
            throw new IllegalArgumentException("withdrawal webhook does not match a local withdrawal");
        }
        String normalizedType = eventType.toUpperCase(Locale.ROOT);
        validateWebhookData(record, data);
        if ("COMPLETED".equals(record.status()) || "REFUNDED".equals(record.status())
                || "REJECTED".equals(record.status())) {
            if (terminalWebhookIsIdempotent(record.status(), normalizedType)) {
                if (providerEventId == null) {
                    states.recordWebhookObservation(record.withdrawalId(), walletWithdrawalId, json(event),
                            "idempotent terminal webhook: " + normalizedType);
                } else {
                    states.recordWebhookObservation(record.withdrawalId(), walletWithdrawalId, json(event),
                            "idempotent terminal webhook: " + normalizedType, providerEventId);
                }
                return;
            }
            throw new IllegalStateException("withdrawal webhook conflicts with terminal local status");
        }
        String response = json(event);
        switch (normalizedType) {
            case "WITHDRAWAL.CREATED", "WITHDRAWAL.BROADCAST" -> {
                if (providerEventId == null) {
                    states.markSubmitted(record.withdrawalId(), response, walletWithdrawalId);
                } else {
                    states.markSubmitted(record.withdrawalId(), response, walletWithdrawalId, providerEventId);
                }
            }
            case "WITHDRAWAL.BROADCAST_UNKNOWN" -> {
                boolean observed = states.recordLateBroadcastUnknownObservation(record.withdrawalId(),
                        walletWithdrawalId, response,
                        "late broadcast-unknown webhook ignored after local status advanced", providerEventId);
                if (!observed) {
                    if (providerEventId == null) {
                        states.markBroadcastUnknown(record.withdrawalId(), response,
                                "custody wallet broadcast status is unknown", walletWithdrawalId);
                    } else {
                        states.markBroadcastUnknown(record.withdrawalId(), response,
                                "custody wallet broadcast status is unknown", walletWithdrawalId, providerEventId);
                    }
                }
            }
            case "WITHDRAWAL.CONFIRMED" -> {
                if (providerEventId == null) {
                    states.markCompleted(record.withdrawalId(), response, walletWithdrawalId);
                } else {
                    states.markCompleted(record.withdrawalId(), response, walletWithdrawalId, providerEventId);
                }
            }
            case "WITHDRAWAL.FAILED" -> {
                if (providerEventId == null) {
                    states.markFailurePending(record.withdrawalId(), response,
                            "custody wallet withdrawal failed", walletWithdrawalId);
                } else {
                    states.markFailurePending(record.withdrawalId(), response,
                            "custody wallet withdrawal failed", walletWithdrawalId, providerEventId);
                }
            }
            default -> throw new IllegalArgumentException("unsupported withdrawal webhook event type");
        }
    }

    private boolean terminalWebhookIsIdempotent(String status, String eventType) {
        return ("COMPLETED".equals(status) && "WITHDRAWAL.CONFIRMED".equals(eventType))
                || ("REFUNDED".equals(status) && "WITHDRAWAL.FAILED".equals(eventType));
    }

    private void validateWebhookData(CustodyWithdrawalModels.WithdrawalRecord record,
                                     Map<String, Object> data) {
        if (!record.fundingSource().equals(data.get("fundingSource"))
                || !("user:" + record.userId()).equals(data.get("subject"))) {
            throw new IllegalArgumentException(
                    "withdrawal webhook funding source or beneficiary does not match local intent");
        }
        if (!record.toAddress().equals(data.get("toAddress"))) {
            throw new IllegalArgumentException("withdrawal webhook recipient does not match local intent");
        }
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
            if (new BigDecimal(amount).compareTo(new BigDecimal(record.fees().receivedAmount())) != 0) {
                throw new IllegalArgumentException("withdrawal webhook amount does not match local intent");
            }
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("withdrawal webhook amount is invalid", ex);
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

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException ex) {
            throw new IllegalStateException("withdrawal payload cannot be serialized", ex);
        }
    }
}
