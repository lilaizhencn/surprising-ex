package com.surprising.gateway.provider.service;

import com.surprising.gateway.provider.repository.CustodyWithdrawalRepository;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class CustodyWithdrawalReconciliationService {

    private final CustodyWithdrawalRepository repository;
    private final CustodyWalletClient walletClient;
    private final CustodyWithdrawalRefundService refundService;
    private final ObjectMapper objectMapper;

    public CustodyWithdrawalReconciliationService(CustodyWithdrawalRepository repository,
                                                  CustodyWalletClient walletClient,
                                                  CustodyWithdrawalRefundService refundService,
                                                  ObjectMapper objectMapper) {
        this.repository = repository;
        this.walletClient = walletClient;
        this.refundService = refundService;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
    }

    @Transactional
    public void reconcile(CustodyWithdrawalRepository.WithdrawalRecord record) {
        if (record.externalReference() == null || record.externalReference().isBlank()) {
            return;
        }
        repository.lockForOutcome(record.withdrawalId());
        List<Map<String, Object>> matches = walletClient.withdrawalsByExternalReference(
                        record.externalReference(), record.chain(), record.assetSymbol(), 20).stream()
                .filter(row -> record.externalReference().equals(
                        stringValue(row.get("externalReference"), stringValue(row.get("external_reference"), null))))
                .filter(row -> record.walletWithdrawalId() == null
                        || record.walletWithdrawalId().equals(
                                stringValue(row.get("withdrawalId"), stringValue(row.get("id"), null))))
                .toList();
        if (matches.size() != 1) {
            return;
        }
        Map<String, Object> walletRecord = matches.getFirst();
        String walletWithdrawalId = stringValue(walletRecord.get("withdrawalId"),
                stringValue(walletRecord.get("id"), record.walletWithdrawalId()));
        String status = stringValue(walletRecord.get("status"), "").toUpperCase(Locale.ROOT);
        String response = json(walletRecord);
        if ("CONFIRMED".equals(status)) {
            repository.markCompleted(record.withdrawalId(), response, walletWithdrawalId);
        } else if (Set.of("FAILED", "REJECTED", "CANCELLED").contains(status)) {
            refundService.refund(record, "custody wallet withdrawal failed", "custody wallet withdrawal failed");
        }
    }

    private String stringValue(Object value, String fallback) {
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException ex) {
            throw new IllegalStateException("withdrawal reconciliation response cannot be serialized", ex);
        }
    }
}
