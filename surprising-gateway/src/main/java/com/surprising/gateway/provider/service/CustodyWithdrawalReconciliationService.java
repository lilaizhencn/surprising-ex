package com.surprising.gateway.provider.service;

import com.surprising.gateway.provider.client.CustodyWalletClient;

import com.surprising.gateway.provider.model.CustodyWithdrawalModels;
import com.surprising.gateway.provider.repository.CustodyWithdrawalRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
@org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.NEVER)
public class CustodyWithdrawalReconciliationService {


    private final CustodyWithdrawalRepository repository;
    private final CustodyWithdrawalStateService states;
    private final CustodyWalletClient walletClient;
    private final CustodyWithdrawalRefundService refundService;
    private final ObjectMapper objectMapper;

    public CustodyWithdrawalReconciliationService(CustodyWithdrawalRepository repository,
                                    CustodyWithdrawalStateService states,
                                                  CustodyWalletClient walletClient,
                                                  CustodyWithdrawalRefundService refundService,
                                                  ObjectMapper objectMapper) {
        this.repository = repository;
        this.states = states;
        this.walletClient = walletClient;
        this.refundService = refundService;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
    }

    public void reconcile(CustodyWithdrawalModels.WithdrawalRecord record) {
        if (record.externalReference() == null || record.externalReference().isBlank()) {
            return;
        }
        List<Map<String, Object>> matches = walletClient.withdrawalsByExternalReference(
                record.externalReference(),record.chain(),record.assetSymbol(),2);
        if (matches == null || matches.size() != 1 || !record.externalReference().equals(matches.getFirst().get("externalReference"))
                || !matchesRecord(record,matches.getFirst())) return;
        Map<String, Object> walletRecord = matches.getFirst();
        String walletWithdrawalId = stringValue(walletRecord.get("withdrawalId"),
                stringValue(walletRecord.get("id"), record.walletWithdrawalId()));
        String status = stringValue(walletRecord.get("status"), "").toUpperCase(Locale.ROOT);
        String response = json(walletRecord);
        if ("CONFIRMED".equals(status)) {
            states.markCompleted(record.withdrawalId(), response, walletWithdrawalId);
        } else if (Set.of("FAILED", "REJECTED", "CANCELLED").contains(status) && Boolean.TRUE.equals(walletRecord.get("refundable"))) {
            refundService.refund(record, "custody wallet withdrawal failed", "custody wallet withdrawal failed", json(Map.of("kind","PRINCIPAL_RELEASED","receipt",walletRecord)));
        } else if (!Set.of("COMPLETED", "REFUNDED", "REJECTED", "FAILED_PENDING").contains(record.status())) {
            states.markSubmitted(record.withdrawalId(), response, walletWithdrawalId);
        }
    }

    private boolean matchesRecord(CustodyWithdrawalModels.WithdrawalRecord record,
                                  Map<String, Object> walletRecord) {
        String walletWithdrawalId = stringValue(walletRecord.get("withdrawalId"),
                stringValue(walletRecord.get("id"), null));
        if (walletWithdrawalId == null) {
            return false;
        }
        if (record.walletWithdrawalId() != null
                && !record.walletWithdrawalId().equals(walletWithdrawalId)) {
            return false;
        }
        String chain = stringValue(walletRecord.get("chain"), stringValue(walletRecord.get("chainId"), null));
        String asset = stringValue(walletRecord.get("assetSymbol"), stringValue(walletRecord.get("asset"), null));
        String amount = stringValue(walletRecord.get("amount"),
                stringValue(walletRecord.get("withdrawalAmount"), null));
        String toAddress = stringValue(walletRecord.get("toAddress"), null);
        String subject = stringValue(walletRecord.get("subject"), null);
        if (toAddress == null || !record.toAddress().equals(toAddress)
                || !record.fundingSource().equals(walletRecord.get("fundingSource"))
                || !("user:" + record.userId()).equals(subject)
                || chain == null || asset == null || amount == null
                || !record.chain().equalsIgnoreCase(chain)
                || !record.assetSymbol().equalsIgnoreCase(asset)) {
            return false;
        }
        try {
            return new BigDecimal(amount).compareTo(new BigDecimal(record.fees().receivedAmount())) == 0;
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
