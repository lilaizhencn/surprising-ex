package com.surprising.gateway.provider.service;

import com.surprising.gateway.provider.repository.CustodyWithdrawalRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CustodyWithdrawalRefundService {

    private final CustodyWithdrawalRepository repository;
    private final SpotAccountClient spotAccountClient;

    public CustodyWithdrawalRefundService(CustodyWithdrawalRepository repository,
                                          SpotAccountClient spotAccountClient) {
        this.repository = repository;
        this.spotAccountClient = spotAccountClient;
    }

    @Transactional
    public void refund(CustodyWithdrawalRepository.WithdrawalRecord record,
                       String spotReason,
                       String stateReason) {
        refund(record, spotReason, stateReason, null);
    }

    @Transactional
    public void refund(CustodyWithdrawalRepository.WithdrawalRecord record,
                       String spotReason,
                       String stateReason,
                       String walletResponse) {
        if ("REFUNDED".equals(record.status()) || "COMPLETED".equals(record.status())
                || "REJECTED".equals(record.status())) {
            return;
        }
        CustodyWithdrawalRepository.WithdrawalRecord pending = walletResponse == null
                ? repository.markRefundPending(record.withdrawalId(), stateReason)
                : repository.markRefundPending(record.withdrawalId(), stateReason, walletResponse);
        if (pending == null || !"REFUND_PENDING".equals(pending.status())) {
            return;
        }
        spotAccountClient.adjustBalance(pending.userId(), pending.assetSymbol(), pending.amountUnits(),
                pending.spotDebitReference() + ":refund", spotReason);
        repository.markRefunded(pending.withdrawalId(), pending.walletResponse(), stateReason);
    }
}
