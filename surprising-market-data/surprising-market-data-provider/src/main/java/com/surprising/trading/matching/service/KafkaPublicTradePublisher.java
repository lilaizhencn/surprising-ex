package com.surprising.trading.matching.service;

import com.surprising.trading.api.model.PublicTradeEvent;
import com.surprising.trading.matching.config.MatchingProperties;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * 将公共成交批量发送到 Kafka，不接入资金事务或数据库 Outbox。
 * 每个交易对使用独立的有界 FIFO，事件不会合并。
 */
@Service
    public class KafkaPublicTradePublisher implements PublicTradePublisher {

    static final int BATCH_SIZE = 2_000;
    static final int MAX_QUEUED_PER_SYMBOL = 10_000;
    static final int MAX_IN_FLIGHT = 4_096;

    private static final Logger log = LoggerFactory.getLogger(KafkaPublicTradePublisher.class);

    private final ObjectMapper objectMapper;
    private final MatchingProperties properties;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final Executor dispatchExecutor = ForkJoinPool.commonPool();
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
            @Qualifier("matchingMarketDataKafkaTemplate") KafkaTemplate<String, String> kafkaTemplate) {
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
            if (!symbolQueue.sending && !symbolQueue.active) {
                symbolQueue.active = true;
                readySymbols.offer(symbol);
            }
        }
    }

    public void publishPending() {
        int remaining = Math.min(BATCH_SIZE, availableInFlight());
        int attempts = 0;
        while (remaining > 0 && attempts++ < BATCH_SIZE) {
            String symbol = readySymbols.poll();
            if (symbol == null) {
                return;
            }
            SymbolQueue symbolQueue = symbolQueues.get(symbol);
            if (symbolQueue == null) {
                continue;
            }
            if (dispatch(symbol, symbolQueue)) {
                remaining--;
            }
        }
    }

    public PublisherStats stats() {
        return new PublisherStats(offered.get(), dropped.get(), sent.get(), sendFailed.get(),
                queued.get(), inFlight.get(), symbolQueues.size());
    }

    /**
     * 每个交易对只允许一个发送中的事件，确保失败重试不会越过已经发送的后续成交。
     * 不同交易对仍然可以通过全局 in-flight 上限并行发送。
     */
    private boolean dispatch(String symbol, SymbolQueue symbolQueue) {
        if (!tryAcquireInFlight()) {
            synchronized (symbolQueue) {
                activateIfNeeded(symbol, symbolQueue);
            }
            return false;
        }
        PublicTradeEvent event;
        synchronized (symbolQueue) {
            if (symbolQueue.sending || symbolQueue.events.isEmpty()) {
                inFlight.decrementAndGet();
                if (symbolQueue.events.isEmpty()) {
                    symbolQueue.active = false;
                }
                return false;
            }
            event = symbolQueue.events.removeFirst();
            queued.decrementAndGet();
            symbolQueue.sending = true;
            symbolQueue.active = false;
        }
        send(symbol, symbolQueue, event);
        return true;
    }

    private int availableInFlight() {
        return Math.max(0, MAX_IN_FLIGHT - inFlight.get());
    }

    private boolean tryAcquireInFlight() {
        while (true) {
            int current = inFlight.get();
            if (current >= MAX_IN_FLIGHT) {
                return false;
            }
            if (inFlight.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    private void send(String symbol, SymbolQueue symbolQueue, PublicTradeEvent event) {
        try {
            String payload = objectMapper.writeValueAsString(event);
            kafkaTemplate.send(properties.getKafka().getMatchTradesTopic(), symbol, payload)
                    .whenComplete((ignored, error) -> {
                        inFlight.decrementAndGet();
                        synchronized (symbolQueue) {
                            symbolQueue.sending = false;
                            if (error != null) {
                                retryLocked(symbol, symbolQueue, event);
                            }
                        }
                        if (error == null) {
                            sent.incrementAndGet();
                        } else {
                            recordFailure(symbol, error);
                        }
                        dispatchNextAsync(symbol, symbolQueue);
                    });
        } catch (Exception error) {
            inFlight.decrementAndGet();
            synchronized (symbolQueue) {
                symbolQueue.sending = false;
                retryLocked(symbol, symbolQueue, event);
            }
            recordFailure(symbol, error);
            dispatchNextAsync(symbol, symbolQueue);
        }
    }

    /** 发送失败时将成交放回该交易对队首，避免临时 Kafka 元数据故障造成永久丢失。 */
    private void retryLocked(String symbol, SymbolQueue symbolQueue, PublicTradeEvent event) {
        if (!symbolQueue.events.isEmpty() && symbolQueue.events.peekFirst() == event) {
            return;
        }
        symbolQueue.events.addFirst(event);
        queued.incrementAndGet();
        while (symbolQueue.events.size() > MAX_QUEUED_PER_SYMBOL) {
            symbolQueue.events.removeLast();
            queued.decrementAndGet();
            dropped.incrementAndGet();
        }
    }

    /** 当前事件完成后立即发送同交易对的下一个事件，避免等待下一轮定时任务。 */
    private void dispatchNext(String symbol, SymbolQueue symbolQueue) {
        synchronized (symbolQueue) {
            if (symbolQueue.sending || symbolQueue.events.isEmpty()) {
                symbolQueue.active = false;
                return;
            }
        }
        dispatch(symbol, symbolQueue);
    }

    /** 使用异步跳板，避免测试替身或本地 Kafka 立即完成时递归发送耗尽调用栈。 */
    private void dispatchNextAsync(String symbol, SymbolQueue symbolQueue) {
        dispatchExecutor.execute(() -> dispatchNext(symbol, symbolQueue));
    }

    private void activateIfNeeded(String symbol, SymbolQueue symbolQueue) {
        if (!symbolQueue.sending && !symbolQueue.active && !symbolQueue.events.isEmpty()) {
            symbolQueue.active = true;
            readySymbols.offer(symbol);
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
        private boolean sending;
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
