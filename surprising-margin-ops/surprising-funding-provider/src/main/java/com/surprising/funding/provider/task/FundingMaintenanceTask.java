package com.surprising.funding.provider.task;

import com.surprising.funding.provider.service.FundingAccountCommandResultService;
import com.surprising.funding.provider.service.FundingService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 资金费模块定时任务入口，只负责调用资金费服务层。
 */
@Component
public class FundingMaintenanceTask {

    private final FundingService fundingService;
    private final FundingAccountCommandResultService commandResultService;

    public FundingMaintenanceTask(FundingService fundingService,
                                  FundingAccountCommandResultService commandResultService) {
        this.fundingService = fundingService;
        this.commandResultService = commandResultService;
    }

    @Scheduled(fixedDelayString = "${surprising.funding.calculation.publish-delay-ms:1000}")
    public void publishRates() {
        fundingService.publishRates();
    }

    @Scheduled(fixedDelayString = "${surprising.funding.settlement.settle-delay-ms:1000}")
    public void settleDueRates() {
        fundingService.settleDueRates();
    }

    @Scheduled(fixedDelayString = "${surprising.funding.settlement.reconcile-delay-ms:1000}")
    public void reconcileAccountCommands() {
        commandResultService.reconcileTerminalCommands();
    }
}
