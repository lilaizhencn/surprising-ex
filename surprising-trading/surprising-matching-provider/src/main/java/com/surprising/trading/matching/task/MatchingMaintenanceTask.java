package com.surprising.trading.matching.task;

import com.surprising.trading.matching.service.ExchangeCoreEngine;
import com.surprising.trading.matching.service.KafkaOrderBookDepthPublisher;
import com.surprising.trading.matching.service.KafkaPublicTradePublisher;
import com.surprising.trading.matching.service.MatchingOutboxPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 撮合模块定时任务入口，只负责调用对应服务方法。
 */
@Component
public class MatchingMaintenanceTask {

    private final ExchangeCoreEngine exchangeCoreEngine;
    private final KafkaOrderBookDepthPublisher depthPublisher;
    private final KafkaPublicTradePublisher tradePublisher;
    private final MatchingOutboxPublisher outboxPublisher;

    public MatchingMaintenanceTask(ExchangeCoreEngine exchangeCoreEngine,
                                   KafkaOrderBookDepthPublisher depthPublisher,
                                   KafkaPublicTradePublisher tradePublisher,
                                   MatchingOutboxPublisher outboxPublisher) {
        this.exchangeCoreEngine = exchangeCoreEngine;
        this.depthPublisher = depthPublisher;
        this.tradePublisher = tradePublisher;
        this.outboxPublisher = outboxPublisher;
    }

    @Scheduled(fixedDelayString = "${surprising.trading.matching.engine.initial-symbol-refresh-delay-ms:30000}")
    public void refreshSymbols() {
        exchangeCoreEngine.refreshSymbols();
    }

    @Scheduled(fixedDelayString = "${surprising.trading.matching.market-data.publish-delay-ms:5}")
    public void publishDepth() {
        depthPublisher.publishPending();
    }

    @Scheduled(fixedDelay = 50L)
    public void publishTrades() {
        tradePublisher.publishPending();
    }

    @Scheduled(fixedDelayString = "${surprising.trading.matching.outbox.publish-delay-ms:20}")
    public void publishOutbox() {
        outboxPublisher.publishPending();
    }

    @Scheduled(fixedDelayString = "${surprising.trading.matching.outbox.cleanup-delay-ms:60000}")
    public void cleanupOutbox() {
        outboxPublisher.cleanupPublished();
    }
}
