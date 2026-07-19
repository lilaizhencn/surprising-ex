package com.surprising.trading.order.service;

import com.surprising.trading.order.config.TradingOrderProperties;
import com.surprising.trading.order.model.OutboxRecord;
import com.surprising.trading.order.repository.OutboxRepository;
import com.surprising.trading.order.repository.OutboxRepository.OutboxStreamBatch;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private static final Duration MINIMUM_CLAIM_LEASE = Duration.ofSeconds(30);
    private static final Duration CLAIM_LEASE_BUFFER = Duration.ofSeconds(5);

    private final TradingOrderProperties properties;
    private final OutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ExecutorService publishExecutor;
    private final int maxInFlight;
    private final AtomicBoolean publishing = new AtomicBoolean(false);

    public OutboxPublisher(TradingOrderProperties properties,
                           OutboxRepository outboxRepository,
                           @Qualifier("orderKafkaTemplate") KafkaTemplate<String, String> kafkaTemplate) {
        this.properties = properties;
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.maxInFlight = Math.max(1, properties.getOutbox().getMaxInFlight());
        this.publishExecutor = Executors.newFixedThreadPool(this.maxInFlight, threadFactory());
    }

    @Scheduled(fixedDelayString = "${surprising.trading.order.outbox.publish-delay-ms:20}")
    public void publishPending() {
        if (!publishing.compareAndSet(false, true)) {
            return;
        }
        try {
            int remaining = properties.getOutbox().getBatchSize();
            while (remaining > 0) {
                Instant now = Instant.now();
                var batches = outboxRepository.claimPendingBatches(
                        remaining, now.plus(claimLease(remaining)), now);
                if (batches.isEmpty()) {
                    return;
                }
                if (properties.getOutbox().isAsyncEnabled()) {
                    publishConcurrent(batches);
                } else {
                    publishSequential(batches);
                }
                remaining -= batches.stream().mapToInt(batch -> batch.rows().size()).sum();
            }
        } finally {
            publishing.set(false);
        }
    }

    @Scheduled(fixedDelayString = "${surprising.trading.order.outbox.cleanup-delay-ms:60000}")
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
            log.info("Deleted {} published order outbox rows", totalDeleted);
        }
    }

    @PreDestroy
    public void shutdown() {
        publishExecutor.shutdownNow();
    }

    private void publishSequential(List<OutboxStreamBatch> batches) {
        List<GroupPublishResult> results = new ArrayList<>(batches.size());
        for (OutboxStreamBatch batch : batches) {
            results.add(publishGroup(batch));
        }
        completePublish(results);
    }

    private Duration claimLease(int claimedLimit) {
        Duration budget = properties.getOutbox().isAsyncEnabled()
                ? properties.getOutbox().getSendTimeout().plus(CLAIM_LEASE_BUFFER)
                : properties.getOutbox().getSendTimeout().multipliedBy(Math.max(1, claimedLimit))
                        .plus(CLAIM_LEASE_BUFFER);
        return budget.compareTo(MINIMUM_CLAIM_LEASE) < 0 ? MINIMUM_CLAIM_LEASE : budget;
    }

    private void publishConcurrent(List<OutboxStreamBatch> batches) {
        ExecutorCompletionService<QueuedGroup> completionService =
                new ExecutorCompletionService<>(publishExecutor);
        for (OutboxStreamBatch batch : batches) {
            completionService.submit(() -> queueGroup(batch));
        }

        List<QueuedGroup> queuedGroups = new ArrayList<>(batches.size());
        for (int completed = 0; completed < batches.size(); completed++) {
            try {
                queuedGroups.add(completionService.take().get());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                log.warn("Interrupted while publishing trading outbox batch");
                return;
            } catch (Exception ex) {
                log.error("Unexpected trading outbox publish task failure: {}", ex.getMessage(), ex);
            }
        }
        awaitBatch(queuedGroups.stream().flatMap(group -> group.sends().stream()).toList());
        List<GroupPublishResult> results = queuedGroups.stream().map(this::inspectGroup).toList();
        completePublish(results);
    }

    private void completePublish(List<GroupPublishResult> results) {
        List<Long> publishedIds = results.stream().flatMap(result -> result.publishedIds().stream()).toList();
        if (!publishedIds.isEmpty()) {
            try {
                outboxRepository.markPublished(publishedIds, Instant.now());
            } catch (Exception ex) {
                log.error("Failed to batch mark {} trading outbox events published: {}",
                        publishedIds.size(), ex.getMessage(), ex);
                return;
            }
        }
        List<FailedPublish> failures = results.stream().flatMap(result -> result.failures().stream()).toList();
        for (FailedPublish failure : failures) {
            markFailed(failure.row(), failure.error());
        }
        List<Long> pendingTailIds = results.stream().flatMap(result -> result.pendingTailIds().stream()).toList();
        if (!pendingTailIds.isEmpty()) {
            try {
                outboxRepository.releasePending(pendingTailIds, Instant.now());
            } catch (Exception ex) {
                log.error("Failed to release {} unattempted trading outbox event leases: {}",
                        pendingTailIds.size(), ex.getMessage(), ex);
            }
        }
    }

    private GroupPublishResult publishGroup(OutboxStreamBatch batch) {
        QueuedGroup group = queueGroup(batch);
        awaitBatch(group.sends());
        return inspectGroup(group);
    }

    private QueuedGroup queueGroup(OutboxStreamBatch batch) {
        List<OutboxRecord> rows = batch.rows();
        List<CompletableFuture<?>> sends = new ArrayList<>(rows.size());
        Exception synchronousFailure = null;
        for (int index = 0; index < rows.size(); index++) {
            OutboxRecord row = rows.get(index);
            try {
                sends.add(kafkaTemplate.send(row.topic(), row.eventKey(), row.payload()));
            } catch (Exception ex) {
                synchronousFailure = ex;
                break;
            }
        }
        return new QueuedGroup(rows, sends, synchronousFailure);
    }

    private GroupPublishResult inspectGroup(QueuedGroup group) {
        List<OutboxRecord> rows = group.rows();
        List<CompletableFuture<?>> sends = group.sends();
        Exception synchronousFailure = group.synchronousFailure();

        int firstFailure = -1;
        Exception failure = null;
        for (int index = 0; index < sends.size(); index++) {
            Exception sendFailure = completionFailure(sends.get(index));
            if (sendFailure != null) {
                firstFailure = index;
                failure = sendFailure;
                break;
            }
        }
        if (firstFailure < 0 && synchronousFailure != null) {
            firstFailure = sends.size();
            failure = synchronousFailure;
        }
        if (firstFailure < 0) {
            return new GroupPublishResult(rows.stream().map(OutboxRecord::id).toList(), List.of(), List.of());
        }

        List<Long> publishedIds = rows.subList(0, firstFailure).stream().map(OutboxRecord::id).toList();
        List<Long> pendingTailIds = rows.subList(firstFailure + 1, rows.size()).stream()
                .map(OutboxRecord::id)
                .toList();
        return new GroupPublishResult(
                publishedIds,
                List.of(new FailedPublish(rows.get(firstFailure), failure)),
                pendingTailIds);
    }

    private void awaitBatch(List<CompletableFuture<?>> sends) {
        if (sends.isEmpty()) {
            return;
        }
        try {
            CompletableFuture.allOf(sends.toArray(CompletableFuture[]::new))
                    .get(properties.getOutbox().getSendTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (Exception ignored) {
            // Inspect every future below so the first failed stream row remains the retry boundary.
        }
    }

    private Exception completionFailure(CompletableFuture<?> send) {
        if (!send.isDone()) {
            return new TimeoutException("Kafka outbox batch send timed out");
        }
        try {
            send.join();
            return null;
        } catch (CompletionException ex) {
            return ex.getCause() instanceof Exception cause ? cause : ex;
        } catch (CancellationException ex) {
            return ex;
        }
    }

    private void markFailed(OutboxRecord row, Exception ex) {
        log.warn("Failed to publish trading outbox id={} topic={}: {}",
                row.id(), row.topic(), ex.getMessage());
        try {
            outboxRepository.markFailed(row.id(), ex.getMessage(), Instant.now());
        } catch (Exception markEx) {
            log.error("Failed to mark trading outbox id={} after publish failure: {}",
                    row.id(), markEx.getMessage(), markEx);
        }
    }

    private ThreadFactory threadFactory() {
        AtomicInteger counter = new AtomicInteger();
        return task -> {
            Thread thread = new Thread(task, "trading-outbox-publisher-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private record GroupPublishResult(
            List<Long> publishedIds,
            List<FailedPublish> failures,
            List<Long> pendingTailIds) {
    }

    private record QueuedGroup(
            List<OutboxRecord> rows,
            List<CompletableFuture<?>> sends,
            Exception synchronousFailure) {
    }

    private record FailedPublish(OutboxRecord row, Exception error) {
    }
}
