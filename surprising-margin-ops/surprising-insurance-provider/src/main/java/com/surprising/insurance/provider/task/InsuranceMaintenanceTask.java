package com.surprising.insurance.provider.task;

import com.surprising.insurance.provider.service.InsuranceCoverageReconciler;
import com.surprising.insurance.provider.service.InsuranceService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 保险基金模块定时任务入口，只负责调用保险基金服务层。
 */
@Component
public class InsuranceMaintenanceTask {

    private final InsuranceService insuranceService;
    private final InsuranceCoverageReconciler coverageReconciler;

    public InsuranceMaintenanceTask(InsuranceService insuranceService,
                                    InsuranceCoverageReconciler coverageReconciler) {
        this.insuranceService = insuranceService;
        this.coverageReconciler = coverageReconciler;
    }

    @Scheduled(fixedDelayString = "${surprising.insurance.coverage.scan-delay-ms:1000}")
    public void coverDeficits() {
        insuranceService.coverDeficits();
    }

    @Scheduled(fixedDelayString = "${surprising.insurance.coverage.reconcile-delay-ms:200}")
    public void reconcileCoverage() {
        coverageReconciler.reconcile();
    }
}
