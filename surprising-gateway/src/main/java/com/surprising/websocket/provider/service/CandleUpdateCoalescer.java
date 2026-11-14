package com.surprising.websocket.provider.service;

import com.surprising.websocket.provider.model.TimedPayload;

import com.surprising.candlestick.api.model.CandleStatus;
import com.surprising.candlestick.api.model.CandleUpdatedEvent;
import com.surprising.product.api.ProductLine;
import com.surprising.websocket.api.model.SubscriptionTopic;
import com.surprising.websocket.api.model.WsChannel;
import com.surprising.websocket.provider.config.WebSocketProperties;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

@Service
public class CandleUpdateCoalescer {

    private final SubscriptionRegistry registry;
    private final WebSocketProperties properties;
    private final TaskScheduler taskScheduler;
    private final ScheduledExecutorService ownedScheduler;
    private final Map<SubscriptionTopic, TimedPayload> latestPartial = new ConcurrentHashMap<>();
    private final Set<SubscriptionTopic> scheduled = ConcurrentHashMap.newKeySet();

    public CandleUpdateCoalescer(SubscriptionRegistry registry, WebSocketProperties properties) {
        this.registry = registry;
        this.properties = properties;
        this.taskScheduler = null;
        this.ownedScheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().name("ws-candle-coalescer-", 0).factory());
    }

    @Autowired
    public CandleUpdateCoalescer(SubscriptionRegistry registry,
                                 WebSocketProperties properties,
                                 @Qualifier("taskScheduler") TaskScheduler taskScheduler) {
        this.registry = registry;
        this.properties = properties;
        this.taskScheduler = taskScheduler;
        this.ownedScheduler = null;
    }

    public void publish(CandleUpdatedEvent event) {
        publish(event, null);
    }

    public void publish(CandleUpdatedEvent event, ProductLine productLine) {
        SubscriptionTopic topic = new SubscriptionTopic(
                WsChannel.CANDLES, event.instrumentId(), event.period(), null, productLine);
        if (event.status() == CandleStatus.CLOSED) {
            latestPartial.remove(topic);
            registry.publish(topic, event, event.eventTime());
            return;
        }
        latestPartial.put(topic, new TimedPayload(event, event.eventTime()));
        if (scheduled.add(topic)) {
            scheduleFlush(topic);
        }
    }

    private void flush(SubscriptionTopic topic) {
        try {
            var event = latestPartial.remove(topic);
            if (event != null) {
                registry.publishTimedBatch(topic, java.util.List.of(event));
            }
        } finally {
            scheduled.remove(topic);
            if (latestPartial.containsKey(topic) && scheduled.add(topic)) {
                scheduleFlush(topic);
            }
        }
    }

    private void scheduleFlush(SubscriptionTopic topic) {
        long delay = delayMillis();
        if (taskScheduler != null) {
            taskScheduler.schedule(() -> flush(topic), Instant.now().plusMillis(delay));
        } else if (ownedScheduler != null) {
            ownedScheduler.schedule(() -> flush(topic), delay, TimeUnit.MILLISECONDS);
        }
    }

    private long delayMillis() {
        return Math.max(0L, properties.getFanout().getCandlePartialCoalesceWindow().toMillis());
    }

    @PreDestroy
    public void stop() {
        if (ownedScheduler != null) {
            ownedScheduler.shutdownNow();
        }
    }
}
