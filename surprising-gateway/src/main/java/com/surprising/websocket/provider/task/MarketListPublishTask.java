package com.surprising.websocket.provider.task;

import com.surprising.websocket.provider.service.MarketListAggregator;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** One list event per product line, and only when a folded price changed. */
@Component
public final class MarketListPublishTask {
    private final MarketListAggregator aggregator;

    public MarketListPublishTask(MarketListAggregator aggregator) {
        this.aggregator = aggregator;
    }

    @Scheduled(fixedDelay = 1000)
    public void publish() {
        aggregator.publish();
    }
}
