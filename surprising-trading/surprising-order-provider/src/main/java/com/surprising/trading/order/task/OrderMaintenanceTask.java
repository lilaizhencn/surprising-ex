package com.surprising.trading.order.task;

import com.surprising.trading.order.service.AlgoOrderService;
import com.surprising.trading.order.service.CancelAllAfterService;
import com.surprising.trading.order.service.OpenOrderViewCoordinator;
import com.surprising.trading.order.service.OrderAccountCommandResultReconciler;
import com.surprising.trading.order.service.OrderScheduleIndexCoordinator;
import com.surprising.trading.order.service.OutboxPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 订单模块定时任务入口，只负责调用订单服务层。
 */
@Component
public class OrderMaintenanceTask {

    private final OrderAccountCommandResultReconciler commandResultReconciler;
    private final OpenOrderViewCoordinator openOrderViewCoordinator;
    private final OrderScheduleIndexCoordinator scheduleIndexCoordinator;
    private final AlgoOrderService algoOrderService;
    private final CancelAllAfterService cancelAllAfterService;
    private final OutboxPublisher outboxPublisher;

    public OrderMaintenanceTask(OrderAccountCommandResultReconciler commandResultReconciler,
                                OpenOrderViewCoordinator openOrderViewCoordinator,
                                OrderScheduleIndexCoordinator scheduleIndexCoordinator,
                                AlgoOrderService algoOrderService,
                                CancelAllAfterService cancelAllAfterService,
                                OutboxPublisher outboxPublisher) {
        this.commandResultReconciler = commandResultReconciler;
        this.openOrderViewCoordinator = openOrderViewCoordinator;
        this.scheduleIndexCoordinator = scheduleIndexCoordinator;
        this.algoOrderService = algoOrderService;
        this.cancelAllAfterService = cancelAllAfterService;
        this.outboxPublisher = outboxPublisher;
    }

    @Scheduled(fixedDelayString = "${surprising.trading.order.account-result-reconcile-delay-ms:1000}")
    public void reconcileAccountCommands() {
        commandResultReconciler.reconcile();
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

    @Scheduled(fixedDelayString = "${surprising.trading.order.outbox.publish-delay-ms:20}")
    public void publishOutbox() {
        outboxPublisher.publishPending();
    }

    @Scheduled(fixedDelayString = "${surprising.trading.order.outbox.cleanup-delay-ms:60000}")
    public void cleanupOutbox() {
        outboxPublisher.cleanupPublished();
    }
}
