package com.surprising.trading.trigger.service;

import com.surprising.trading.trigger.config.TriggerProperties;
import com.surprising.trading.trigger.model.TriggerOutboxRecord;
import com.surprising.trading.trigger.repository.TriggerOrderOutboxRepository;
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

/** Publishes committed trigger-order status snapshots with at-least-once delivery. */
@Service
public class TriggerOrderOutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(TriggerOrderOutboxPublisher.class);
    private static final Duration MINIMUM_CLAIM_LEASE = Duration.ofSeconds(30);
    private static final Duration CLAIM_LEASE_BUFFER = Duration.ofSeconds(5);

    private final TriggerProperties properties;
    private final TriggerOrderOutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ExecutorService publishExecutor;
    private final int maxInFlight;
    private final AtomicBoolean publishing = new AtomicBoolean(false);

    public TriggerOrderOutboxPublisher(TriggerProperties properties,
                                       TriggerOrderOutboxRepository outboxRepository,
                                       @Qualifier("triggerKafkaTemplate") KafkaTemplate<String, String> kafkaTemplate) {
        this.properties = properties;
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.maxInFlight = Math.max(1, properties.getOutbox().getMaxInFlight());
        this.publishExecutor = Executors.newFixedThreadPool(maxInFlight, threadFactory());
    }

    @Scheduled(fixedDelayString = "${surprising.trading.trigger.outbox.publish-delay-ms:200}")
    public void publishPending() {
        if (!publishing.compareAndSet(false, true)) {
            return;
        }
        try {
            int remaining = Math.max(1, properties.getOutbox().getBatchSize());
            while (remaining > 0) {
                Instant now = Instant.now();
                var rows = outboxRepository.claimPending(remaining, now.plus(claimLease(remaining)), now)
                        .stream()
                        .sorted(Comparator.comparing(TriggerOutboxRecord::topic)
                                .thenComparing(TriggerOutboxRecord::eventKey)
                                .thenComparingLong(TriggerOutboxRecord::id))
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

    @Scheduled(fixedDelayString = "${surprising.trading.trigger.outbox.cleanup-delay-ms:60000}")
    public void cleanupPublished() {
        int batchSize = Math.max(1, properties.getOutbox().getCleanupBatchSize());
        int totalDeleted = 0;
        Instant cutoff = Instant.now().minus(properties.getOutbox().getRetention());
        for (int batch = 0; batch < Math.max(1, properties.getOutbox().getCleanupMaxBatches()); batch++) {
            int deleted = outboxRepository.deletePublishedBefore(cutoff, batchSize);
            totalDeleted += deleted;
            if (deleted < batchSize) {
                break;
            }
        }
        if (totalDeleted > 0) {
            log.info("Deleted {} published trigger-order outbox rows", totalDeleted);
        }
    }

    @PreDestroy
    public void shutdown() {
        publishExecutor.shutdownNow();
    }

    private void publishConcurrent(List<TriggerOutboxRecord> rows) {
        ExecutorCompletionService<PublishResult> completionService =
                new ExecutorCompletionService<>(publishExecutor);
        int next = 0;
        int active = 0;
        while (next < rows.size() && active < maxInFlight) {
            TriggerOutboxRecord row = rows.get(next++);
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
                log.warn("Interrupted while publishing trigger-order outbox batch");
                return;
            } catch (Exception ex) {
                log.error("Unexpected trigger-order outbox task failure: {}", ex.getMessage(), ex);
            }
            active--;
            if (next < rows.size()) {
                TriggerOutboxRecord row = rows.get(next++);
                completionService.submit(() -> publish(row));
                active++;
            }
        }

        if (!publishedIds.isEmpty()) {
            try {
                outboxRepository.markPublished(publishedIds, Instant.now());
            } catch (Exception ex) {
                log.error("Failed to batch mark {} trigger-order outbox events published: {}",
                        publishedIds.size(), ex.getMessage(), ex);
                return;
            }
        }
        failures.forEach(result -> markFailed(result.row(), result.error()));
    }

    private PublishResult publish(TriggerOutboxRecord row) {
        try {
            kafkaTemplate.send(row.topic(), row.eventKey(), row.payload())
                    .get(properties.getOutbox().getSendTimeout().toMillis(), TimeUnit.MILLISECONDS);
            return new PublishResult(row, null);
        } catch (Exception ex) {
            return new PublishResult(row, ex);
        }
    }

    private void markFailed(TriggerOutboxRecord row, Exception ex) {
        log.warn("Failed to publish trigger order outbox id={} topic={}: {}",
                row.id(), row.topic(), ex.getMessage());
        try {
            outboxRepository.markFailed(row.id(), ex.getMessage(), Instant.now());
        } catch (Exception markEx) {
            log.error("Failed to mark trigger order outbox id={} after publish failure: {}",
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
            Thread thread = new Thread(task, "trigger-outbox-publisher-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private record PublishResult(TriggerOutboxRecord row, Exception error) {
    }
}
