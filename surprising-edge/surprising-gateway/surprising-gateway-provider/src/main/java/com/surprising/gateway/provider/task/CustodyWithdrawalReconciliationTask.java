package com.surprising.gateway.provider.task;

import com.surprising.gateway.provider.service.CustodyWithdrawalService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class CustodyWithdrawalReconciliationTask {

    private final CustodyWithdrawalService withdrawalService;

    public CustodyWithdrawalReconciliationTask(CustodyWithdrawalService withdrawalService) {
        this.withdrawalService = withdrawalService;
    }

    @Scheduled(fixedDelayString = "${surprising.gateway.withdrawal.failure-reconciliation-delay:30s}")
    public void reconcileFailedWithdrawals() {
        withdrawalService.reconcileFailedWithdrawals();
    }
}
