package com.surprising.trading.order.task;

import com.surprising.trading.order.service.AlgoOrderService;
import com.surprising.trading.order.service.CancelAllAfterService;
import com.surprising.trading.order.service.OrderScheduleIndexCoordinator;
import com.surprising.trading.order.service.OrderLocalStateCoordinator;
import com.surprising.trading.order.service.OrderStateProjectionWorker;
import com.surprising.trading.order.service.OrderUserStateService;
import com.surprising.trading.order.service.OpenInterestSnapshotInitializer;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 订单模块定时任务入口，只负责调用订单服务层。
 */
@Component
public class OrderMaintenanceTask {

    private final OrderLocalStateCoordinator localStateCoordinator;
    private final OrderScheduleIndexCoordinator scheduleIndexCoordinator;
    private final AlgoOrderService algoOrderService;
    private final CancelAllAfterService cancelAllAfterService;
    private final OrderStateProjectionWorker stateProjectionWorker;
    private final OrderUserStateService userStateService;
    private final OpenInterestSnapshotInitializer openInterestSnapshotInitializer;

    public OrderMaintenanceTask(OrderLocalStateCoordinator localStateCoordinator,
                                OrderScheduleIndexCoordinator scheduleIndexCoordinator,
                                AlgoOrderService algoOrderService,
                                CancelAllAfterService cancelAllAfterService,
                                OrderStateProjectionWorker stateProjectionWorker,
                                OrderUserStateService userStateService,
                                OpenInterestSnapshotInitializer openInterestSnapshotInitializer) {
        this.localStateCoordinator = localStateCoordinator;
        this.scheduleIndexCoordinator = scheduleIndexCoordinator;
        this.algoOrderService = algoOrderService;
        this.cancelAllAfterService = cancelAllAfterService;
        this.stateProjectionWorker = stateProjectionWorker;
        this.userStateService = userStateService;
        this.openInterestSnapshotInitializer = openInterestSnapshotInitializer;
    }

    @Scheduled(fixedDelayString = "${surprising.trading.order.redis-index.reconcile-delay-ms:10000}")
    public void reconcileLocalState() {
        localStateCoordinator.reconcile();
    }

    @Scheduled(fixedDelayString = "${surprising.trading.order.redis-index.reconcile-delay-ms:10000}")
    public void reconcileScheduleIndex() {
        scheduleIndexCoordinator.reconcile();
    }

    @Scheduled(fixedDelayString = "${surprising.trading.order.algo.scan-delay-ms:250}")
    public void scanAlgoOrders() {
        algoOrderService.scanDueAlgoOrders();
    }

    @Scheduled(fixedDelayString = "${surprising.trading.order.cancel-all-after.scan-delay-ms:250}")
    public void scanCancelAllTimers() {
        cancelAllAfterService.scanDueTimers();
    }

    @Scheduled(fixedDelayString = "${surprising.trading.order.wal.projection-delay-ms:25}")
    public void projectState() {
        stateProjectionWorker.projectPending();
    }

    @Scheduled(fixedDelayString = "${surprising.trading.order.wal.worker-delay-ms:25}")
    public void applyUserState() {
        userStateService.applyPending();
    }

    @Scheduled(fixedDelayString = "${surprising.trading.order.open-interest.snapshot-retry-delay-ms:1000}")
    public void refreshOpenInterestSnapshot() {
        openInterestSnapshotInitializer.refreshIfNotReady();
    }

}
