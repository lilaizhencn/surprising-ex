package com.surprising.trading.order.task;

import com.surprising.trading.order.service.AlgoOrderService;
import com.surprising.trading.order.service.CancelAllAfterService;
import com.surprising.trading.order.service.OpenOrderViewCoordinator;
import com.surprising.trading.order.service.OrderScheduleIndexCoordinator;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 订单模块定时任务入口，只负责调用订单服务层。
 */
@Component
public class OrderMaintenanceTask {

    private final OpenOrderViewCoordinator openOrderViewCoordinator;
    private final OrderScheduleIndexCoordinator scheduleIndexCoordinator;
    private final AlgoOrderService algoOrderService;
    private final CancelAllAfterService cancelAllAfterService;

    public OrderMaintenanceTask(OpenOrderViewCoordinator openOrderViewCoordinator,
                                OrderScheduleIndexCoordinator scheduleIndexCoordinator,
                                AlgoOrderService algoOrderService,
                                CancelAllAfterService cancelAllAfterService) {
        this.openOrderViewCoordinator = openOrderViewCoordinator;
        this.scheduleIndexCoordinator = scheduleIndexCoordinator;
        this.algoOrderService = algoOrderService;
        this.cancelAllAfterService = cancelAllAfterService;
    }

    @Scheduled(fixedDelayString = "${surprising.trading.order.redis-index.reconcile-delay-ms:10000}")
    public void reconcileOpenOrderView() {
        openOrderViewCoordinator.reconcile();
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
