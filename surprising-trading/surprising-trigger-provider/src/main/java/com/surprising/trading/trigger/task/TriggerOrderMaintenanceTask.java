package com.surprising.trading.trigger.task;

import com.surprising.trading.trigger.service.MarkPriceTriggerService;
import com.surprising.trading.trigger.service.TriggerOrderIndexCoordinator;
import com.surprising.trading.trigger.service.TriggerOrderOutboxPublisher;
import com.surprising.trading.trigger.service.TriggerOrderService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 条件单模块定时任务入口，只负责调用条件单服务层。
 */
@Component
public class TriggerOrderMaintenanceTask {

    private final TriggerOrderOutboxPublisher outboxPublisher;
    private final MarkPriceTriggerService markPriceTriggerService;
    private final TriggerOrderIndexCoordinator indexCoordinator;
    private final TriggerOrderService triggerOrderService;

    public TriggerOrderMaintenanceTask(TriggerOrderOutboxPublisher outboxPublisher,
                                       MarkPriceTriggerService markPriceTriggerService,
                                       TriggerOrderIndexCoordinator indexCoordinator,
                                       TriggerOrderService triggerOrderService) {
        this.outboxPublisher = outboxPublisher;
        this.markPriceTriggerService = markPriceTriggerService;
        this.indexCoordinator = indexCoordinator;
        this.triggerOrderService = triggerOrderService;
    }

    @Scheduled(fixedDelayString = "${surprising.trading.trigger.outbox.publish-delay-ms:200}")
    public void publishOutbox() {
        outboxPublisher.publishPending();
    }

    @Scheduled(fixedDelayString = "${surprising.trading.trigger.outbox.cleanup-delay-ms:60000}")
    public void cleanupOutbox() {
        outboxPublisher.cleanupPublished();
    }

    @Scheduled(
            fixedRate = MarkPriceTriggerService.SCAN_INTERVAL_MS,
            initialDelay = MarkPriceTriggerService.SCAN_INTERVAL_MS)
    public void scanLatestMarkPrices() {
        markPriceTriggerService.scanLatest();
    }

    @Scheduled(fixedDelayString = "${surprising.trading.trigger.redis-index.reconcile-delay-ms:10000}")
    public void reconcileIndex() {
        indexCoordinator.reconcile();
    }

    @Scheduled(fixedDelayString = "${surprising.trading.trigger.execution.maintenance-delay-ms:1000}")
    public void maintainTriggerOrders() {
        triggerOrderService.maintenance();
    }
}
