package com.surprising.websocket.provider.service;

import com.surprising.account.provider.service.AccountOpenInterestSnapshotService;
import com.surprising.websocket.api.model.WsChannel;
import com.surprising.websocket.provider.config.WebSocketProperties;
import java.time.Instant;
import java.util.Map;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Public read boundary: one authoritative Core query per tick, shared by all local subscribers. */
@Component
public class OpenInterestPublisher {
    private final SubscriptionRegistry registry;
    private final AccountOpenInterestSnapshotService snapshots;
    private final WebSocketProperties properties;

    public OpenInterestPublisher(SubscriptionRegistry registry, AccountOpenInterestSnapshotService snapshots,
                                 WebSocketProperties properties) {
        this.registry = registry;
        this.snapshots = snapshots;
        this.properties = properties;
    }

    @Scheduled(fixedDelay = 1000)
    public void publish() {
        var product = properties.getKafka().getProductLine();
        var topics = registry.topics(WsChannel.OPEN_INTEREST).stream()
                .filter(topic -> topic.productLine() == product).toList();
        if (topics.isEmpty()) return;
        try {
            var snapshot = snapshots.snapshot(product);
            for (var topic : topics) {
                long longSteps = 0, shortSteps = 0;
                for (var row : snapshot.shards()) {
                    if (!row.symbol().equals(topic.symbol())) continue;
                    longSteps = Math.addExact(longSteps, row.longQuantitySteps());
                    shortSteps = Math.addExact(shortSteps, row.shortQuantitySteps());
                }
                registry.publish(topic, Map.of("symbol", topic.symbol(), "status", "READY",
                        "openInterestSteps", Long.toString(Math.max(longSteps, shortSteps)),
                        "sequence", Long.toString(snapshot.snapshotRevision())), snapshot.snapshotAt());
            }
        } catch (RuntimeException failure) {
            // Missing/unavailable Core state is never displayed as zero open interest.
            for (var topic : topics) registry.publish(topic,
                    Map.of("symbol", topic.symbol(), "status", "UNAVAILABLE"), Instant.now());
        }
    }
}
