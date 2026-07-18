package com.surprising.trading.matching.service;

import com.surprising.trading.api.model.OrderBookDepthEvent;
import com.surprising.trading.matching.config.MatchingProperties;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * Sends public depth through a lossy, latest-only path isolated from matching business events.
 *
 * <p>Each symbol owns one slot. A newer snapshot replaces an unpublished older snapshot, so
 * Kafka outages cannot create an unbounded market-data backlog and one hot symbol cannot erase
 * another symbol's latest state.</p>
 */
@Service
public class KafkaOrderBookDepthPublisher implements OrderBookDepthPublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaOrderBookDepthPublisher.class);

    private final ObjectMapper objectMapper;
    private final MatchingProperties properties;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ConcurrentMap<String, SymbolSlot> slots = new ConcurrentHashMap<>();
    private final Queue<String> readySymbols = new ConcurrentLinkedQueue<>();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicLong offered = new AtomicLong();
    private final AtomicLong coalesced = new AtomicLong();
    private final AtomicLong sent = new AtomicLong();
    private final AtomicLong sendFailed = new AtomicLong();

    public KafkaOrderBookDepthPublisher(
            ObjectMapper objectMapper,
            MatchingProperties properties,
            @Qualifier("matchingMarketDataKafkaTemplate") KafkaTemplate<String, String> kafkaTemplate) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.kafkaTemplate = kafkaTemplate;
    }

    @Override
    public void offer(OrderBookDepthEvent event) {
        if (!properties.getMarketData().isEnabled() || event == null) {
            return;
        }
        String symbol = event.symbol();
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("order-book depth symbol is required");
        }
        offered.incrementAndGet();
        SymbolSlot slot = slots.computeIfAbsent(symbol, ignored -> new SymbolSlot());
        if (slot.latest.getAndSet(event) != null) {
            coalesced.incrementAndGet();
        }
        schedule(symbol, slot);
    }

    @Scheduled(fixedDelayString = "${surprising.trading.matching.market-data.publish-delay-ms:5}")
    public void publishPending() {
        if (!properties.getMarketData().isEnabled()) {
            return;
        }
        int published = 0;
        int batchSize = Math.max(1, properties.getMarketData().getBatchSize());
        int maxInFlight = Math.max(1, properties.getMarketData().getMaxInFlight());
        while (published < batchSize && tryAcquire(maxInFlight)) {
            String symbol = readySymbols.poll();
            if (symbol == null) {
                inFlight.decrementAndGet();
                return;
            }
            SymbolSlot slot = slots.get(symbol);
            if (slot == null) {
                inFlight.decrementAndGet();
                continue;
            }
            OrderBookDepthEvent event = slot.latest.getAndSet(null);
            if (event == null) {
                inFlight.decrementAndGet();
                deactivate(symbol, slot);
                continue;
            }
            published++;
            send(symbol, slot, event);
        }
    }

    public PublisherStats stats() {
        return new PublisherStats(offered.get(), coalesced.get(), sent.get(), sendFailed.get(),
                readySymbols.size(), inFlight.get(), slots.size());
    }

    private void send(String symbol, SymbolSlot slot, OrderBookDepthEvent event) {
        try {
            String payload = objectMapper.writeValueAsString(event);
            kafkaTemplate.send(properties.getKafka().getOrderBookDepthTopic(), symbol, payload)
                    .whenComplete((ignored, error) -> {
                        inFlight.decrementAndGet();
                        if (error == null) {
                            sent.incrementAndGet();
                        } else {
                            recordFailure(symbol, error);
                        }
                        rescheduleOrDeactivate(symbol, slot);
                    });
        } catch (Exception error) {
            inFlight.decrementAndGet();
            recordFailure(symbol, error);
            rescheduleOrDeactivate(symbol, slot);
        }
    }

    private boolean tryAcquire(int maxInFlight) {
        while (true) {
            int current = inFlight.get();
            if (current >= maxInFlight) {
                return false;
            }
            if (inFlight.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    private void schedule(String symbol, SymbolSlot slot) {
        if (slot.active.compareAndSet(false, true)) {
            readySymbols.offer(symbol);
        }
    }

    private void rescheduleOrDeactivate(String symbol, SymbolSlot slot) {
        if (slot.latest.get() != null) {
            readySymbols.offer(symbol);
            return;
        }
        deactivate(symbol, slot);
    }

    private void deactivate(String symbol, SymbolSlot slot) {
        slot.active.set(false);
        if (slot.latest.get() != null && slot.active.compareAndSet(false, true)) {
            readySymbols.offer(symbol);
        }
    }

    private void recordFailure(String symbol, Throwable error) {
        long failures = sendFailed.incrementAndGet();
        if ((failures & (failures - 1L)) == 0L) {
            log.warn("Failed to publish latest order-book depth symbol={} failures={}: {}",
                    symbol, failures, error.getMessage());
        }
    }

    private static final class SymbolSlot {
        private final AtomicReference<OrderBookDepthEvent> latest = new AtomicReference<>();
        private final AtomicBoolean active = new AtomicBoolean();
    }

    public record PublisherStats(long offered,
                                 long coalesced,
                                 long sent,
                                 long sendFailed,
                                 int readySymbols,
                                 int inFlight,
                                 int symbols) {
    }
}
