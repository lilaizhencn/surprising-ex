package com.surprising.funding.provider.task;

import com.surprising.funding.provider.service.FundingAccountCommandResultService;
import com.surprising.funding.provider.service.FundingAccountCommandWalService;
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
    private final FundingAccountCommandWalService commandWalService;

    public FundingMaintenanceTask(FundingService fundingService,
                                  FundingAccountCommandResultService commandResultService,
                                  FundingAccountCommandWalService commandWalService) {
        this.fundingService = fundingService;
        this.commandResultService = commandResultService;
        this.commandWalService = commandWalService;
    }

    @Scheduled(fixedDelayString = "${surprising.funding.calculation.publish-delay-ms:1000}")
    public void publishRates() {
        fundingService.publishRates();
    }

    @Scheduled(fixedDelayString = "${surprising.funding.settlement.settle-delay-ms:1000}")
    public void settleDueRates() {
        fundingService.settleDueRates();
    }

    /** 恢复本地资金费结算事实中尚未追加到账户 WAL 的幂等命令。 */
    @Scheduled(fixedDelayString = "${surprising.funding.settlement.command-recovery-delay-ms:1000}")
    public void recoverAccountCommands() {
        fundingService.recoverPendingCommands();
    }

    @Scheduled(fixedDelayString = "${surprising.funding.settlement.reconcile-delay-ms:1000}")
    public void reconcileAccountCommands() {
        commandResultService.reconcileTerminalCommands();
    }

    @Scheduled(fixedDelayString = "${surprising.funding.account-command.publish-delay-ms:25}")
    public void publishAccountCommands() {
        commandWalService.publishPending();
    }
}
