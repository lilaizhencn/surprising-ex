package com.surprising.websocket.provider.service;

import com.surprising.account.provider.service.AccountOpenInterestSnapshotService;
import com.surprising.websocket.api.model.WsChannel;
import java.time.Instant;
import java.util.Map;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Public read boundary: one authoritative Core query per tick, shared by all local subscribers. */
@Component
public class OpenInterestPublisher {
    private final SubscriptionRegistry registry;
    private final com.surprising.gateway.provider.product.GatewayProductServices products;

    public OpenInterestPublisher(SubscriptionRegistry registry, com.surprising.gateway.provider.product.GatewayProductServices products) {
        this.registry = registry;
        this.products = products;
    }

    @Scheduled(fixedDelay = 1000)
    public void publish() {
        for (var product : products.enabled()) publish(product);
    }

    private void publish(com.surprising.product.api.ProductLine product) {
        var topics = registry.topics(WsChannel.OPEN_INTEREST).stream()
                .filter(topic -> topic.productLine() == product).toList();
        if (topics.isEmpty()) return;
        try {
            var snapshot = products.service(product, AccountOpenInterestSnapshotService.class).snapshot(product);
            for (var topic : topics) {
                long longSteps = 0, shortSteps = 0;
                for (var row : snapshot.shards()) {
                    if (!row.instrumentId().equals(topic.instrumentId())) continue;
                    longSteps = Math.addExact(longSteps, row.longQuantitySteps());
                    shortSteps = Math.addExact(shortSteps, row.shortQuantitySteps());
                }
                registry.publish(topic, Map.of("instrumentId", topic.instrumentId(), "status", "READY",
                        "openInterestSteps", Long.toString(Math.max(longSteps, shortSteps)),
                        "sequence", Long.toString(snapshot.snapshotRevision())), snapshot.snapshotAt());
            }
        } catch (RuntimeException failure) {
            // Missing/unavailable Core state is never displayed as zero open interest.
            for (var topic : topics) registry.publish(topic,
                    Map.of("instrumentId", topic.instrumentId(), "status", "UNAVAILABLE"), Instant.now());
        }
    }
}
