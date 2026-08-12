package com.surprising.adl.provider.task;

import com.surprising.adl.provider.service.AdlExecutionReconciler;
import com.surprising.adl.provider.service.AdlRedisIndexCoordinator;
import com.surprising.adl.provider.service.AdlService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * ADL 模块定时任务入口，只负责调用 ADL 服务层。
 */
@Component
public class AdlMaintenanceTask {

    private final AdlService adlService;
    private final AdlExecutionReconciler executionReconciler;
    private final AdlRedisIndexCoordinator indexCoordinator;

    public AdlMaintenanceTask(AdlService adlService,
                              AdlExecutionReconciler executionReconciler,
                              AdlRedisIndexCoordinator indexCoordinator) {
        this.adlService = adlService;
        this.executionReconciler = executionReconciler;
        this.indexCoordinator = indexCoordinator;
    }

    @Scheduled(fixedDelayString = "${surprising.adl.scanner.scan-delay-ms:1000}")
    public void processResidualDeficits() {
        adlService.processResidualDeficits();
    }

    @Scheduled(fixedDelayString = "${surprising.adl.scanner.reconcile-delay-ms:200}")
    public void reconcileExecutions() {
        executionReconciler.reconcile();
    }

    @Scheduled(fixedDelayString = "${surprising.adl.redis-index.reconcile-delay-ms:10000}")
    public void rebuildIndex() {
        indexCoordinator.rebuild();
    }
}
