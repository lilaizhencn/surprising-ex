package com.surprising.trading.order.task;

import com.surprising.trading.order.service.AlgoOrderService;
import com.surprising.trading.order.service.CancelAllAfterService;
import com.surprising.trading.order.service.OpenInterestSnapshotInitializer;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 订单模块定时任务入口，只负责调用订单服务层。
 */
@Component
public class OrderMaintenanceTask {

    private final AlgoOrderService algoOrderService;
    private final CancelAllAfterService cancelAllAfterService;
    private final OpenInterestSnapshotInitializer openInterestSnapshotInitializer;

    public OrderMaintenanceTask(AlgoOrderService algoOrderService,
                                CancelAllAfterService cancelAllAfterService,
                                OpenInterestSnapshotInitializer openInterestSnapshotInitializer) {
        this.algoOrderService = algoOrderService;
        this.cancelAllAfterService = cancelAllAfterService;
        this.openInterestSnapshotInitializer = openInterestSnapshotInitializer;
    }

    @Scheduled(fixedDelayString = "${surprising.trading.order.algo.scan-delay-ms:250}")
    public void scanAlgoOrders() {
        algoOrderService.scanDueAlgoOrders();
    }

    @Scheduled(fixedDelayString = "${surprising.trading.order.cancel-all-after.scan-delay-ms:250}")
    public void scanCancelAllTimers() {
        cancelAllAfterService.scanDueTimers();
    }

    @Scheduled(fixedDelayString = "${surprising.trading.order.open-interest.snapshot-retry-delay-ms:1000}")
    public void refreshOpenInterestSnapshot() {
        openInterestSnapshotInitializer.refreshIfNotReady();
    }

}
