package com.surprising.trading.matching.service;

import com.surprising.trading.api.model.PublicTradeEvent;
import com.surprising.trading.matching.config.MatchingProperties;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * Batches public trades for Kafka without joining the financial transaction or database outbox.
 * Each symbol owns an independent bounded FIFO; events are never coalesced.
 */
@Service
public class KafkaPublicTradePublisher implements PublicTradePublisher {

    static final int BATCH_SIZE = 2_000;
    static final int MAX_PER_SYMBOL_PER_BATCH = 256;
    static final int MAX_QUEUED_PER_SYMBOL = 10_000;
    static final int MAX_IN_FLIGHT = 4_096;

    private static final Logger log = LoggerFactory.getLogger(KafkaPublicTradePublisher.class);

    private final ObjectMapper objectMapper;
    private final MatchingProperties properties;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ConcurrentMap<String, SymbolQueue> symbolQueues = new ConcurrentHashMap<>();
    private final Queue<String> readySymbols = new ConcurrentLinkedQueue<>();
    private final AtomicInteger queued = new AtomicInteger();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicLong offered = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong sent = new AtomicLong();
    private final AtomicLong sendFailed = new AtomicLong();

    public KafkaPublicTradePublisher(
            ObjectMapper objectMapper,
            MatchingProperties properties,
            @Qualifier("matchingPublicTradeKafkaTemplate") KafkaTemplate<String, String> kafkaTemplate) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.kafkaTemplate = kafkaTemplate;
    }

    @Override
    public void offer(PublicTradeEvent event) {
        if (event == null) {
            return;
        }
        String symbol = event.symbol();
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("public trade symbol is required");
        }
        offered.incrementAndGet();
        SymbolQueue symbolQueue = symbolQueues.computeIfAbsent(symbol, ignored -> new SymbolQueue());
        synchronized (symbolQueue) {
            symbolQueue.events.addLast(event);
            queued.incrementAndGet();
            while (symbolQueue.events.size() > MAX_QUEUED_PER_SYMBOL) {
                symbolQueue.events.removeFirst();
                queued.decrementAndGet();
                dropped.incrementAndGet();
            }
            if (!symbolQueue.active) {
                symbolQueue.active = true;
                readySymbols.offer(symbol);
            }
        }
    }

    @Scheduled(fixedDelay = 50L)
    public void publishPending() {
        int remaining = Math.min(BATCH_SIZE, availableInFlight());
        while (remaining > 0) {
            String symbol = readySymbols.poll();
            if (symbol == null) {
                return;
            }
            SymbolQueue symbolQueue = symbolQueues.get(symbol);
            if (symbolQueue == null) {
                continue;
            }
            List<PublicTradeEvent> batch = drain(symbol, symbolQueue, remaining);
            if (batch.isEmpty()) {
                continue;
            }
            remaining -= batch.size();
            batch.forEach(this::send);
        }
    }

    public PublisherStats stats() {
        return new PublisherStats(offered.get(), dropped.get(), sent.get(), sendFailed.get(),
                queued.get(), inFlight.get(), symbolQueues.size());
    }

    private List<PublicTradeEvent> drain(String symbol, SymbolQueue symbolQueue, int remaining) {
        int limit = Math.min(remaining, MAX_PER_SYMBOL_PER_BATCH);
        List<PublicTradeEvent> batch = new ArrayList<>(limit);
        synchronized (symbolQueue) {
            while (batch.size() < limit && !symbolQueue.events.isEmpty()) {
                batch.add(symbolQueue.events.removeFirst());
                queued.decrementAndGet();
            }
            if (symbolQueue.events.isEmpty()) {
                symbolQueue.active = false;
            } else {
                readySymbols.offer(symbol);
            }
        }
        return batch;
    }

    private int availableInFlight() {
        return Math.max(0, MAX_IN_FLIGHT - inFlight.get());
    }

    private void send(PublicTradeEvent event) {
        inFlight.incrementAndGet();
        try {
            String payload = objectMapper.writeValueAsString(event);
            kafkaTemplate.send(properties.getKafka().getMatchTradesTopic(), event.symbol(), payload)
                    .whenComplete((ignored, error) -> {
                        inFlight.decrementAndGet();
                        if (error == null) {
                            sent.incrementAndGet();
                        } else {
                            recordFailure(event.symbol(), error);
                        }
                    });
        } catch (Exception error) {
            inFlight.decrementAndGet();
            recordFailure(event.symbol(), error);
        }
    }

    private void recordFailure(String symbol, Throwable error) {
        long failures = sendFailed.incrementAndGet();
        if ((failures & (failures - 1L)) == 0L) {
            log.warn("Failed to publish public trade symbol={} failures={}: {}",
                    symbol, failures, error.getMessage());
        }
    }

    private static final class SymbolQueue {
        private final ArrayDeque<PublicTradeEvent> events = new ArrayDeque<>();
        private boolean active;
    }

    public record PublisherStats(long offered,
                                 long dropped,
                                 long sent,
                                 long sendFailed,
                                 int queued,
                                 int inFlight,
                                 int symbols) {
    }
}
