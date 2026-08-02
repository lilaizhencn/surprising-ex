package com.surprising.trading.matching.task;

import com.surprising.trading.matching.service.KafkaOrderBookDepthPublisher;
import com.surprising.trading.matching.service.KafkaPublicTradePublisher;
import com.surprising.trading.matching.service.MatchingLocalOutboxPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 撮合模块定时任务入口，只负责调用对应服务方法。
 */
@Component
public class MatchingMaintenanceTask {

    private final KafkaOrderBookDepthPublisher depthPublisher;
    private final KafkaPublicTradePublisher tradePublisher;
    private final MatchingLocalOutboxPublisher outboxPublisher;

    public MatchingMaintenanceTask(KafkaOrderBookDepthPublisher depthPublisher,
                                   KafkaPublicTradePublisher tradePublisher,
                                   MatchingLocalOutboxPublisher outboxPublisher) {
        this.depthPublisher = depthPublisher;
        this.tradePublisher = tradePublisher;
        this.outboxPublisher = outboxPublisher;
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
}
