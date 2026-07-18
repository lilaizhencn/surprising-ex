package com.surprising.account.provider.service;

import com.surprising.account.api.model.PositionCacheEvent;
import com.surprising.account.provider.config.AccountProperties;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Best-effort low-latency Redis accelerator.
 *
 * <p>PostgreSQL remains authoritative. The bounded queue coalesces newer snapshots for a hot position and uses
 * revisions to prevent stale asynchronous writes. Overflow marks the product-line cache unavailable so the
 * coordinator rebuilds it from PostgreSQL instead of serving a silently stale snapshot.</p>
 */
@Component
public class PositionCacheAccelerationWorker {

    private static final Logger log = LoggerFactory.getLogger(PositionCacheAccelerationWorker.class);

    private final RedisPositionCache cache;
    private final PositionCacheMetrics metrics;
    private final ArrayBlockingQueue<PositionKey> queue;
    private final ConcurrentMap<PositionKey, PositionCacheEvent> pending = new ConcurrentHashMap<>();
    private final java.util.Set<PositionKey> scheduled = ConcurrentHashMap.newKeySet();
    private final List<Thread> workers;
    private final AtomicLong dropped = new AtomicLong();
    private volatile boolean running = true;

    public PositionCacheAccelerationWorker(RedisPositionCache cache,
                                           PositionCacheMetrics metrics,
                                           AccountProperties properties) {
        this.cache = cache;
        this.metrics = metrics;
        int capacity = properties.getPositionCache().getAcceleratorQueueCapacity();
        int threadCount = properties.getPositionCache().getAcceleratorThreads();
        this.queue = new ArrayBlockingQueue<>(capacity);
        this.workers = new ArrayList<>(threadCount);
        AtomicInteger sequence = new AtomicInteger();
        for (int i = 0; i < threadCount; i++) {
            Thread worker = new Thread(this::workerLoop,
                    "position-cache-accelerator-" + sequence.incrementAndGet());
            worker.setDaemon(true);
            worker.start();
            workers.add(worker);
        }
    }

    public void submitAll(List<PositionCacheEvent> events) {
        if (events == null || events.isEmpty()) {
            return;
        }
        events.forEach(this::submit);
    }

    public void submit(PositionCacheEvent event) {
        if (event == null || !running) {
            return;
        }
        metrics.recordAcceleratorSubmitted();
        PositionKey key = PositionKey.from(event);
        AtomicBoolean coalesced = new AtomicBoolean();
        pending.compute(key, (ignored, current) -> {
            if (current != null) {
                coalesced.set(true);
            }
            return current == null || event.revision() > current.revision() ? event : current;
        });
        if (coalesced.get()) {
            metrics.recordAcceleratorCoalesced();
        }
        schedule(key);
    }

    private void schedule(PositionKey key) {
        if (!scheduled.add(key)) {
            return;
        }
        if (queue.offer(key)) {
            return;
        }
        scheduled.remove(key);
        pending.remove(key);
        metrics.recordAcceleratorDropped();
        cache.markNotReady(key.productLine());
        long droppedCount = dropped.incrementAndGet();
        if (droppedCount == 1L || droppedCount % 1_000L == 0L) {
            log.warn("Position-cache accelerator queue is full; dropped={} PostgreSQL rebuild required",
                    droppedCount);
        }
    }

    private void workerLoop() {
        while (running) {
            try {
                process(queue.take());
            } catch (InterruptedException ex) {
                if (!running) {
                    Thread.currentThread().interrupt();
                    return;
                }
            } catch (RuntimeException ex) {
                log.error("Unexpected position-cache accelerator worker failure: {}", ex.getMessage(), ex);
            }
        }
    }

    private void process(PositionKey key) {
        PositionCacheEvent event = pending.remove(key);
        try {
            if (event != null) {
                cache.apply(event, false);
            }
        } catch (RuntimeException ex) {
            cache.markNotReady(key.productLine());
            log.warn("Position-cache acceleration failed line={} user={} symbol={} mode={} side={}: {}",
                    key.productLine(), key.userId(), key.symbol(), key.marginMode(), key.positionSide(),
                    ex.getMessage());
        } finally {
            scheduled.remove(key);
            if (pending.containsKey(key)) {
                schedule(key);
            }
        }
    }

    @PreDestroy
    public void shutdown() {
        running = false;
        workers.forEach(Thread::interrupt);
        queue.clear();
        pending.clear();
        scheduled.clear();
    }

    private record PositionKey(
            com.surprising.product.api.ProductLine productLine,
            long userId,
            String symbol,
            com.surprising.trading.api.model.MarginMode marginMode,
            com.surprising.trading.api.model.PositionSide positionSide) {

        private static PositionKey from(PositionCacheEvent event) {
            return new PositionKey(event.productLine(), event.userId(), event.symbol(),
                    event.marginMode(), event.positionSide());
        }
    }
}
