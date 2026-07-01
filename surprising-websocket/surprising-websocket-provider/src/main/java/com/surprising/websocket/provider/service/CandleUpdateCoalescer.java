package com.surprising.websocket.provider.service;

import com.surprising.candlestick.api.model.CandleStatus;
import com.surprising.candlestick.api.model.CandleUpdatedEvent;
import com.surprising.websocket.api.model.SubscriptionTopic;
import com.surprising.websocket.api.model.WsChannel;
import com.surprising.websocket.provider.config.WebSocketProperties;
import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;

@Service
public class CandleUpdateCoalescer {

    private final SubscriptionRegistry registry;
    private final WebSocketProperties properties;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            Thread.ofVirtual().name("ws-candle-coalescer-", 0).factory());
    private final Map<SubscriptionTopic, CandleUpdatedEvent> latestPartial = new ConcurrentHashMap<>();
    private final Set<SubscriptionTopic> scheduled = ConcurrentHashMap.newKeySet();

    public CandleUpdateCoalescer(SubscriptionRegistry registry, WebSocketProperties properties) {
        this.registry = registry;
        this.properties = properties;
    }

    public void publish(CandleUpdatedEvent event) {
        SubscriptionTopic topic = new SubscriptionTopic(WsChannel.CANDLES, event.symbol(), event.period(), null);
        if (event.status() == CandleStatus.CLOSED) {
            latestPartial.remove(topic);
            registry.publish(topic, event, event.eventTime());
            return;
        }
        latestPartial.put(topic, event);
        if (scheduled.add(topic)) {
            scheduler.schedule(() -> flush(topic), delayMillis(), TimeUnit.MILLISECONDS);
        }
    }

    private void flush(SubscriptionTopic topic) {
        try {
            CandleUpdatedEvent event = latestPartial.remove(topic);
            if (event != null) {
                registry.publish(topic, event, event.eventTime());
            }
        } finally {
            scheduled.remove(topic);
            if (latestPartial.containsKey(topic) && scheduled.add(topic)) {
                scheduler.schedule(() -> flush(topic), delayMillis(), TimeUnit.MILLISECONDS);
            }
        }
    }

    private long delayMillis() {
        return Math.max(0L, properties.getFanout().getCandlePartialCoalesceWindow().toMillis());
    }

    @PreDestroy
    public void stop() {
        scheduler.shutdownNow();
    }
}
