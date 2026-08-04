package com.surprising.account.provider.task;

import com.surprising.account.provider.service.AccountCommandResultWaiter;
import com.surprising.account.provider.service.AccountUserCommandAuditProjectionWorker;
import com.surprising.account.provider.service.AccountUserStateCommandWorker;
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
    private final AccountUserStateCommandWorker stateCommandWorker;
    private final AccountUserCommandAuditProjectionWorker auditProjectionWorker;

    public AccountMaintenanceTask(AccountCommandResultWaiter commandResultWaiter,
                                  PositionCacheCoordinator positionCacheCoordinator,
                                  AccountUserStateCommandWorker stateCommandWorker,
                                  AccountUserCommandAuditProjectionWorker auditProjectionWorker) {
        this.commandResultWaiter = commandResultWaiter;
        this.positionCacheCoordinator = positionCacheCoordinator;
        this.stateCommandWorker = stateCommandWorker;
        this.auditProjectionWorker = auditProjectionWorker;
    }

    @Scheduled(fixedDelayString = "${surprising.account.command-wait.poll-delay-ms:20}")
    public void completeTerminalCommands() {
        commandResultWaiter.completeTerminalCommands();
    }

    @Scheduled(fixedDelayString = "${surprising.account.position-cache.reconcile-delay-ms:10000}")
    public void reconcilePositionCache() {
        // 只从本地 JVM 快照重放 Redis，不允许定时任务访问数据库恢复持仓。
        positionCacheCoordinator.reconcile();
    }

    @Scheduled(fixedDelayString = "${surprising.account.wal.projection-delay-ms:25}")
    public void applyPendingAccountCommands() {
        stateCommandWorker.applyPending();
    }

    @Scheduled(fixedDelayString = "${surprising.account.wal.projection-delay-ms:25}")
    public void projectAccountAudit() {
        auditProjectionWorker.projectAudit();
    }
}
