package com.surprising.liquidation.provider.task;

import com.surprising.liquidation.provider.service.LiquidationCandidateQueueProcessor;
import com.surprising.liquidation.provider.service.LiquidationService;
import com.surprising.liquidation.provider.service.TradingOutboxPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 强平模块定时任务入口，只负责调用强平服务层。
 */
@Component
public class LiquidationMaintenanceTask {

    private final LiquidationCandidateQueueProcessor candidateQueueProcessor;
    private final LiquidationService liquidationService;
    private final TradingOutboxPublisher outboxPublisher;

    public LiquidationMaintenanceTask(LiquidationCandidateQueueProcessor candidateQueueProcessor,
                                      LiquidationService liquidationService,
                                      TradingOutboxPublisher outboxPublisher) {
        this.candidateQueueProcessor = candidateQueueProcessor;
        this.liquidationService = liquidationService;
        this.outboxPublisher = outboxPublisher;
    }

    @Scheduled(fixedDelayString = "${surprising.liquidation.redis-index.recovery-delay-ms:1000}")
    public void recoverCandidates() {
        candidateQueueProcessor.recoverDurableCandidates();
    }

    @Scheduled(fixedDelayString = "${surprising.liquidation.settlement-reconcile-delay-ms:50}")
    public void finalizeSettlements() {
        liquidationService.finalizeSettledCandidates();
    }

    @Scheduled(fixedDelayString = "${surprising.liquidation.outbox.publish-delay-ms:100}")
    public void publishOutbox() {
        outboxPublisher.publishPending();
    }

    @Scheduled(fixedDelayString = "${surprising.liquidation.outbox.cleanup-delay-ms:60000}")
    public void cleanupOutbox() {
        outboxPublisher.cleanupPublished();
    }
}
