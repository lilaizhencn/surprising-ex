package com.surprising.gateway.provider.service;

import com.surprising.gateway.provider.client.CustodyWalletClient;
import com.surprising.gateway.provider.client.SpotAccountClient;
import com.surprising.gateway.provider.model.CustodyWithdrawalModels;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** A positive Core adjustment requires persisted evidence that this exact principal cannot still be paid. */
@Service
@org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.NEVER)
public class CustodyWithdrawalRefundService {
    private final CustodyWithdrawalStateService states;
    private final SpotAccountClient spotAccountClient;
    private final ObjectMapper mapper;

    public CustodyWithdrawalRefundService(CustodyWithdrawalStateService states, SpotAccountClient spotAccountClient,
                                          ObjectMapper mapper) {
        this.states=states; this.spotAccountClient=spotAccountClient; this.mapper=mapper;
    }

    public void refund(CustodyWithdrawalModels.WithdrawalRecord record,String spotReason,String stateReason) {
        refund(record,spotReason,stateReason,null);
    }

    public void refund(CustodyWithdrawalModels.WithdrawalRecord record,String spotReason,String stateReason,String walletResponse) {
        if (Set.of("REFUNDED","COMPLETED","REJECTED").contains(record.status())) return;
        String evidence=walletResponse==null ? record.walletResponse() : walletResponse;
        if (!authorized(record,evidence)) throw new IllegalStateException("withdrawal refund has no matching permanent refusal or principal-release receipt");
        var pending=states.markRefundPending(record.withdrawalId(),stateReason,evidence);
        if (pending==null || !"REFUND_PENDING".equals(pending.status())) return;
        spotAccountClient.adjustBalance(pending.userId(),pending.assetSymbol(),pending.amountUnits(),
                pending.spotDebitReference()+":refund",spotReason);
        states.markRefunded(pending.withdrawalId(),pending.walletResponse(),stateReason);
    }

    private boolean authorized(CustodyWithdrawalModels.WithdrawalRecord record,String json) {
        if (json==null) return false;
        try {
            var evidence=mapper.readValue(json,Map.class);
            if (!(evidence.get("receipt") instanceof Map<?,?> receipt)) return false;
            if ("SUBMISSION_REFUSAL".equals(evidence.get("kind"))) {
                return "REJECTED".equals(receipt.get("status"))
                        && record.externalReference().equals(receipt.get("idempotencyKey"))
                        && record.externalReference().equals(receipt.get("externalReference"))
                        && CustodyWalletClient.withdrawalRequestHash(CustodyWithdrawalModels.custodyRequest(record)).equals(receipt.get("requestHash"));
            }
            if (!"PRINCIPAL_RELEASED".equals(evidence.get("kind"))) return false;
            String id=String.valueOf(receipt.get("id"));
            return Boolean.TRUE.equals(receipt.get("refundable"))
                    && Set.of("FAILED","REJECTED","CANCELLED").contains(receipt.get("status"))
                    && !id.isBlank() && !"null".equals(id)
                    && (record.walletWithdrawalId()==null || record.walletWithdrawalId().equals(id))
                    && record.externalReference().equals(receipt.get("externalReference"))
                    && record.fundingSource().equals(receipt.get("fundingSource"))
                    && ("user:"+record.userId()).equals(receipt.get("subject"))
                    && record.toAddress().equals(receipt.get("toAddress"))
                    && record.chain().equals(receipt.get("chain"))
                    && record.assetSymbol().equals(receipt.get("assetSymbol") == null ? receipt.get("asset") : receipt.get("assetSymbol"))
                    && new BigDecimal(String.valueOf(receipt.get("amount"))).compareTo(new BigDecimal(record.fees().receivedAmount()))==0;
        } catch (RuntimeException invalid) { return false; }
    }
}
