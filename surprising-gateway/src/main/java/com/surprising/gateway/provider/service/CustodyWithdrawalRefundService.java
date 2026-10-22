package com.surprising.gateway.provider.service;

import com.surprising.gateway.provider.client.SpotAccountClient;
import com.surprising.gateway.provider.model.CustodyWithdrawalModels;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CustodyWithdrawalRefundService {

    private final CustodyWithdrawalStateService states;
    private final SpotAccountClient spotAccountClient;

    public CustodyWithdrawalRefundService(CustodyWithdrawalStateService states,
                                          SpotAccountClient spotAccountClient) {
        this.states = states;
        this.spotAccountClient = spotAccountClient;
    }

    @Transactional
    public void refund(CustodyWithdrawalModels.WithdrawalRecord record,
                       String spotReason,
                       String stateReason) {
        refund(record, spotReason, stateReason, null);
    }

    @Transactional
    public void refund(CustodyWithdrawalModels.WithdrawalRecord record,
                       String spotReason,
                       String stateReason,
                       String walletResponse) {
        if ("REFUNDED".equals(record.status()) || "COMPLETED".equals(record.status())
                || "REJECTED".equals(record.status())) {
            return;
        }
        CustodyWithdrawalModels.WithdrawalRecord pending = walletResponse == null
                ? states.markRefundPending(record.withdrawalId(), stateReason)
                : states.markRefundPending(record.withdrawalId(), stateReason, walletResponse);
        if (pending == null || !"REFUND_PENDING".equals(pending.status())) {
            return;
        }
        spotAccountClient.adjustBalance(pending.userId(), pending.assetSymbol(), pending.amountUnits(),
                pending.spotDebitReference() + ":refund", spotReason);
        states.markRefunded(pending.withdrawalId(), pending.walletResponse(), stateReason);
    }
}
