package com.surprising.account.provider.task;

import com.surprising.account.provider.service.AccountCommandResultWaiter;
import com.surprising.account.provider.service.PositionCacheCoordinator;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 账户模块定时任务入口，只负责按计划调用服务层。
 */
@Component
public class AccountMaintenanceTask {

    private final AccountCommandResultWaiter commandResultWaiter;
    private final PositionCacheCoordinator positionCacheCoordinator;

    public AccountMaintenanceTask(AccountCommandResultWaiter commandResultWaiter,
                                  PositionCacheCoordinator positionCacheCoordinator) {
        this.commandResultWaiter = commandResultWaiter;
        this.positionCacheCoordinator = positionCacheCoordinator;
    }

    @Scheduled(fixedDelayString = "${surprising.account.command-wait.poll-delay-ms:20}")
    public void completeTerminalCommands() {
        commandResultWaiter.completeTerminalCommands();
    }

    @Scheduled(fixedDelayString = "${surprising.account.position-cache.reconcile-delay-ms:10000}")
    public void reconcilePositionCache() {
        positionCacheCoordinator.reconcile();
    }
}
