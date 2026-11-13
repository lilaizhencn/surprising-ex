package com.surprising.funding.provider.task;

import com.surprising.funding.provider.service.FundingService;
import org.springframework.stereotype.Component;

/**
 * 资金费模块定时任务入口，只负责调用资金费服务层。
 */
@Component
public class FundingMaintenanceTask {

    private final FundingService fundingService;
    public FundingMaintenanceTask(FundingService fundingService) {
        this.fundingService = fundingService;
    }

    public void publishRates() {
        fundingService.publishRates();
    }

    public void settleDueRates() {
        fundingService.settleDueRates();
    }

}
