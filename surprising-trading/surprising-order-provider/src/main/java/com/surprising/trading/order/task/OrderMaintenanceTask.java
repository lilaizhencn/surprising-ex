package com.surprising.trading.order.task;

import com.surprising.trading.order.service.AlgoOrderService;
import com.surprising.trading.order.service.CancelAllAfterService;
import com.surprising.trading.order.service.OrderScheduleIndexCoordinator;
import com.surprising.trading.order.service.OrderLocalStateCoordinator;
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

    public OrderMaintenanceTask(OrderLocalStateCoordinator localStateCoordinator,
                                OrderScheduleIndexCoordinator scheduleIndexCoordinator,
                                AlgoOrderService algoOrderService,
                                CancelAllAfterService cancelAllAfterService) {
        this.localStateCoordinator = localStateCoordinator;
        this.scheduleIndexCoordinator = scheduleIndexCoordinator;
        this.algoOrderService = algoOrderService;
        this.cancelAllAfterService = cancelAllAfterService;
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

}
