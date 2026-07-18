package com.surprising.liquidation.provider.service;

import com.surprising.liquidation.provider.config.LiquidationProperties;
import com.surprising.liquidation.provider.model.TradingOutboxRecord;
import com.surprising.liquidation.provider.repository.LiquidationOrderRepository;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class TradingOutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(TradingOutboxPublisher.class);
    private static final Duration MINIMUM_CLAIM_LEASE = Duration.ofSeconds(30);
    private static final Duration CLAIM_LEASE_BUFFER = Duration.ofSeconds(5);

    private final LiquidationProperties properties;
    private final LiquidationOrderRepository orderRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ExecutorService publishExecutor;
    private final int maxInFlight;
    private final AtomicBoolean publishing = new AtomicBoolean(false);

    public TradingOutboxPublisher(LiquidationProperties properties,
                                  LiquidationOrderRepository orderRepository,
                                  @Qualifier("liquidationKafkaTemplate") KafkaTemplate<String, String> kafkaTemplate) {
        this.properties = properties;
        this.orderRepository = orderRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.maxInFlight = Math.max(1, properties.getOutbox().getMaxInFlight());
        this.publishExecutor = Executors.newFixedThreadPool(maxInFlight, threadFactory());
    }

    @Scheduled(fixedDelayString = "${surprising.liquidation.outbox.publish-delay-ms:100}")
    public void publishPending() {
        if (!publishing.compareAndSet(false, true)) {
            return;
        }
        try {
            int remaining = Math.max(1, properties.getOutbox().getBatchSize());
            while (remaining > 0) {
                Instant now = Instant.now();
                var rows = orderRepository.claimPending(remaining, now.plus(claimLease(remaining)), now)
                        .stream()
                        .sorted(Comparator.comparing(TradingOutboxRecord::topic)
                                .thenComparing(TradingOutboxRecord::eventKey)
                                .thenComparingLong(TradingOutboxRecord::id))
                        .toList();
                if (rows.isEmpty()) {
                    return;
                }
                publishConcurrent(rows);
                remaining -= rows.size();
            }
        } finally {
            publishing.set(false);
        }
    }

    @Scheduled(fixedDelayString = "${surprising.liquidation.outbox.cleanup-delay-ms:60000}")
    public void cleanupPublished() {
        int batchSize = Math.max(1, properties.getOutbox().getCleanupBatchSize());
        int totalDeleted = 0;
        Instant cutoff = Instant.now().minus(properties.getOutbox().getRetention());
        for (int batch = 0; batch < Math.max(1, properties.getOutbox().getCleanupMaxBatches()); batch++) {
            int deleted = orderRepository.deletePublishedBefore(cutoff, batchSize);
            totalDeleted += deleted;
            if (deleted < batchSize) {
                break;
            }
        }
        if (totalDeleted > 0) {
            log.info("Deleted {} published liquidation outbox rows", totalDeleted);
        }
    }

    @PreDestroy
    public void shutdown() {
        publishExecutor.shutdownNow();
    }

    private void publishConcurrent(List<TradingOutboxRecord> rows) {
        ExecutorCompletionService<PublishResult> completionService =
                new ExecutorCompletionService<>(publishExecutor);
        int next = 0;
        int active = 0;
        while (next < rows.size() && active < maxInFlight) {
            TradingOutboxRecord row = rows.get(next++);
            completionService.submit(() -> publish(row));
            active++;
        }

        List<Long> publishedIds = new ArrayList<>(rows.size());
        List<PublishResult> failures = new ArrayList<>();
        while (active > 0) {
            try {
                Future<PublishResult> future = completionService.take();
                PublishResult result = future.get();
                if (result.error() == null) {
                    publishedIds.add(result.row().id());
                } else {
                    failures.add(result);
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                log.warn("Interrupted while publishing liquidation outbox batch");
                return;
            } catch (Exception ex) {
                log.error("Unexpected liquidation outbox task failure: {}", ex.getMessage(), ex);
            }
            active--;
            if (next < rows.size()) {
                TradingOutboxRecord row = rows.get(next++);
                completionService.submit(() -> publish(row));
                active++;
            }
        }

        if (!publishedIds.isEmpty()) {
            try {
                orderRepository.markPublished(publishedIds, Instant.now());
            } catch (Exception ex) {
                log.error("Failed to batch mark {} liquidation outbox events published: {}",
                        publishedIds.size(), ex.getMessage(), ex);
                return;
            }
        }
        failures.forEach(result -> markFailed(result.row(), result.error()));
    }

    private PublishResult publish(TradingOutboxRecord row) {
        try {
            kafkaTemplate.send(row.topic(), row.eventKey(), row.payload())
                    .get(properties.getOutbox().getSendTimeout().toMillis(), TimeUnit.MILLISECONDS);
            return new PublishResult(row, null);
        } catch (Exception ex) {
            return new PublishResult(row, ex);
        }
    }

    private void markFailed(TradingOutboxRecord row, Exception ex) {
        log.warn("Failed to publish liquidation trading outbox id={} topic={}: {}",
                row.id(), row.topic(), ex.getMessage());
        try {
            orderRepository.markFailed(row.id(), ex.getMessage(), Instant.now());
        } catch (Exception markEx) {
            log.error("Failed to mark liquidation outbox id={} after publish failure: {}",
                    row.id(), markEx.getMessage(), markEx);
        }
    }

    private Duration claimLease(int claimedLimit) {
        int sendRounds = (Math.max(1, claimedLimit) + maxInFlight - 1) / maxInFlight;
        Duration budget = properties.getOutbox().getSendTimeout().multipliedBy(sendRounds)
                .plus(CLAIM_LEASE_BUFFER);
        return budget.compareTo(MINIMUM_CLAIM_LEASE) < 0 ? MINIMUM_CLAIM_LEASE : budget;
    }

    private ThreadFactory threadFactory() {
        AtomicInteger counter = new AtomicInteger();
        return task -> {
            Thread thread = new Thread(task, "liquidation-outbox-publisher-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private record PublishResult(TradingOutboxRecord row, Exception error) {
    }
}
