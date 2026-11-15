package com.surprising.websocket.provider.service;

import com.surprising.account.provider.service.AccountOpenInterestSnapshotService;
import com.surprising.websocket.api.model.SubscriptionTopic;
import com.surprising.websocket.api.model.WsChannel;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** Public read boundary: one authoritative Core query per tick, shared by all local subscribers. */
@Component
public class OpenInterestPublisher {
    private record PublishedOpenInterest(long revision, long openInterestSteps) {}

    private final SubscriptionRegistry registry;
    private final com.surprising.gateway.provider.product.GatewayProductServices products;
    private final Map<SubscriptionTopic, PublishedOpenInterest> lastPublished = new ConcurrentHashMap<>();

    public OpenInterestPublisher(SubscriptionRegistry registry, com.surprising.gateway.provider.product.GatewayProductServices products) {
        this.registry = registry;
        this.products = products;
    }

    public void publish() {
        List<SubscriptionTopic> allTopics = registry.topics(WsChannel.OPEN_INTEREST);
        if (allTopics.isEmpty()) {
            lastPublished.clear();
            return;
        }
        lastPublished.keySet().retainAll(allTopics);
        for (var product : products.enabled()) publish(product, allTopics);
    }

    private void publish(com.surprising.product.api.ProductLine product, List<SubscriptionTopic> allTopics) {
        List<SubscriptionTopic> topics = null;
        for (SubscriptionTopic topic : allTopics) {
            if (topic.productLine() == product) {
                if (topics == null) topics = new java.util.ArrayList<>(4);
                topics.add(topic);
            }
        }
        if (topics == null) return;
        try {
            var snapshot = products.service(product, AccountOpenInterestSnapshotService.class).snapshot(product);
            for (var topic : topics) {
                long longSteps = 0, shortSteps = 0;
                for (var row : snapshot.shards()) {
                    if (!row.instrumentId().equals(topic.instrumentId())) continue;
                    longSteps = Math.addExact(longSteps, row.longQuantitySteps());
                    shortSteps = Math.addExact(shortSteps, row.shortQuantitySteps());
                }
                long openInterestSteps = Math.max(longSteps, shortSteps);
                var current = new PublishedOpenInterest(snapshot.snapshotRevision(), openInterestSteps);
                if (current.equals(lastPublished.get(topic))) {
                    continue;
                }
                lastPublished.put(topic, current);
                registry.publish(topic, Map.of("instrumentId", topic.instrumentId(), "status", "READY",
                        "openInterestSteps", Long.toString(openInterestSteps),
                        "sequence", Long.toString(snapshot.snapshotRevision())), snapshot.snapshotAt());
            }
        } catch (RuntimeException failure) {
            // Missing/unavailable Core state is never displayed as zero open interest.
            for (var topic : topics) {
                lastPublished.remove(topic);
                registry.publish(topic,
                        Map.of("instrumentId", topic.instrumentId(), "status", "UNAVAILABLE"), Instant.now());
            }
        }
    }
}
