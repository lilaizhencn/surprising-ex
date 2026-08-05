package com.surprising.gateway.provider.service;

import com.surprising.gateway.provider.repository.CustodyWithdrawalRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
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

    private static final int PAGE_SIZE = 20;
    private static final int MAX_PAGES = 100;

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
        List<Map<String, Object>> matches = new ArrayList<>();
        boolean complete = false;
        int offset = 0;
        for (int page = 0; page < MAX_PAGES; page++) {
            List<Map<String, Object>> rows = offset == 0
                    ? walletClient.withdrawalsByExternalReference(
                            record.externalReference(), record.chain(), record.assetSymbol(), PAGE_SIZE)
                    : walletClient.withdrawalsByExternalReference(
                            record.externalReference(), record.chain(), record.assetSymbol(), PAGE_SIZE, offset);
            if (rows == null) {
                return;
            }
            matches.addAll(rows.stream()
                    .filter(row -> record.externalReference().equals(
                            stringValue(row.get("externalReference"), stringValue(row.get("external_reference"), null))))
                    .toList());
            if (matches.size() > 1) {
                return;
            }
            if (rows.size() < PAGE_SIZE) {
                complete = true;
                break;
            }
            offset += rows.size();
        }
        if (!complete || matches.size() != 1 || !matchesRecord(record, matches.getFirst())) {
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

    private boolean matchesRecord(CustodyWithdrawalRepository.WithdrawalRecord record,
                                  Map<String, Object> walletRecord) {
        String walletWithdrawalId = stringValue(walletRecord.get("withdrawalId"),
                stringValue(walletRecord.get("id"), null));
        if (record.walletWithdrawalId() != null
                && !record.walletWithdrawalId().equals(walletWithdrawalId)) {
            return false;
        }
        String chain = stringValue(walletRecord.get("chain"), stringValue(walletRecord.get("chainId"), null));
        String asset = stringValue(walletRecord.get("assetSymbol"), stringValue(walletRecord.get("asset"), null));
        String amount = stringValue(walletRecord.get("amount"),
                stringValue(walletRecord.get("withdrawalAmount"), null));
        if (chain == null || asset == null || amount == null
                || !record.chain().equalsIgnoreCase(chain)
                || !record.assetSymbol().equalsIgnoreCase(asset)) {
            return false;
        }
        try {
            return new BigDecimal(amount).compareTo(new BigDecimal(record.amount())) == 0;
        } catch (NumberFormatException ex) {
            return false;
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
