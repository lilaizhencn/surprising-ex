package com.surprising.insurance.provider.task;

import com.surprising.insurance.provider.service.InsuranceService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 保险基金模块定时任务入口，只负责调用保险基金服务层。
 */
@Component
public class InsuranceMaintenanceTask {

    private final InsuranceService insuranceService;
    public InsuranceMaintenanceTask(InsuranceService insuranceService) {
        this.insuranceService = insuranceService;
    }

    @Scheduled(fixedDelayString = "${surprising.insurance.coverage.scan-delay-ms:1000}")
    public void coverDeficits() {
        insuranceService.coverDeficits();
    }
}
