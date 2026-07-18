package com.surprising.trading.order.service;

import com.surprising.trading.order.config.TradingOrderProperties;
import com.surprising.trading.order.model.OutboxRecord;
import com.surprising.trading.order.repository.OutboxRepository;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    @Scheduled(fixedDelayString = "${surprising.trading.order.outbox.publish-delay-ms:200}")
    public void publishPending() {
        if (!publishing.compareAndSet(false, true)) {
            return;
        }
        try {
            int remaining = properties.getOutbox().getBatchSize();
            while (remaining > 0) {
                Instant now = Instant.now();
                var rows = outboxRepository.claimPending(remaining, now.plus(claimLease(remaining)), now)
                        .stream()
                        .sorted(Comparator.comparing(OutboxRecord::topic)
                                .thenComparing(OutboxRecord::eventKey)
                                .thenComparingLong(OutboxRecord::id))
                        .toList();
                if (rows.isEmpty()) {
                    return;
                }
                if (properties.getOutbox().isAsyncEnabled()) {
                    publishConcurrent(rows);
                } else {
                    publishSequential(rows);
                }
                remaining -= rows.size();
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

    private void publishSequential(List<OutboxRecord> rows) {
        for (var row : rows) {
            Exception failure = publishToKafka(row);
            if (failure == null) {
                outboxRepository.markPublished(row.id(), Instant.now());
            } else {
                markFailed(row, failure);
            }
        }
    }

    private Duration claimLease(int claimedLimit) {
        int batchSize = Math.max(1, claimedLimit);
        int sendRounds = properties.getOutbox().isAsyncEnabled()
                ? (batchSize + maxInFlight - 1) / maxInFlight
                : batchSize;
        Duration budget = properties.getOutbox().getSendTimeout().multipliedBy(sendRounds)
                .plus(CLAIM_LEASE_BUFFER);
        return budget.compareTo(MINIMUM_CLAIM_LEASE) < 0 ? MINIMUM_CLAIM_LEASE : budget;
    }

    private void publishConcurrent(List<OutboxRecord> rows) {
        Map<OutboxKey, List<OutboxRecord>> groups = groupByTopicKey(rows);
        ExecutorCompletionService<GroupPublishResult> completionService =
                new ExecutorCompletionService<>(publishExecutor);
        var iterator = groups.values().iterator();
        int submitted = 0;
        int completed = 0;
        while (submitted < maxInFlight && iterator.hasNext()) {
            List<OutboxRecord> group = iterator.next();
            completionService.submit(() -> publishGroup(group));
            submitted++;
        }

        List<Long> publishedIds = new ArrayList<>(rows.size());
        List<FailedPublish> failures = new ArrayList<>();
        while (completed < submitted) {
            GroupPublishResult result;
            try {
                Future<GroupPublishResult> future = completionService.take();
                result = future.get();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                log.warn("Interrupted while publishing trading outbox batch");
                return;
            } catch (Exception ex) {
                log.error("Unexpected trading outbox publish task failure: {}", ex.getMessage(), ex);
                completed++;
                if (iterator.hasNext()) {
                    List<OutboxRecord> group = iterator.next();
                    completionService.submit(() -> publishGroup(group));
                    submitted++;
                }
                continue;
            }
            publishedIds.addAll(result.publishedIds());
            failures.addAll(result.failures());
            completed++;
            if (iterator.hasNext()) {
                List<OutboxRecord> group = iterator.next();
                completionService.submit(() -> publishGroup(group));
                submitted++;
            }
        }

        if (!publishedIds.isEmpty()) {
            try {
                outboxRepository.markPublished(publishedIds, Instant.now());
            } catch (Exception ex) {
                log.error("Failed to batch mark {} trading outbox events published: {}",
                        publishedIds.size(), ex.getMessage(), ex);
                return;
            }
        }
        for (FailedPublish failure : failures) {
            markFailed(failure.row(), failure.error());
        }
    }

    private GroupPublishResult publishGroup(List<OutboxRecord> group) {
        List<Long> publishedIds = new ArrayList<>(group.size());
        List<FailedPublish> failures = new ArrayList<>(1);
        for (OutboxRecord row : group) {
            Exception failure = publishToKafka(row);
            if (failure == null) {
                publishedIds.add(row.id());
            } else {
                failures.add(new FailedPublish(row, failure));
                break;
            }
        }
        return new GroupPublishResult(publishedIds, failures);
    }

    private Exception publishToKafka(OutboxRecord row) {
        // Delivery is at least once; downstream consumers must dedupe by commandId/orderId or eventId.
        try {
            kafkaTemplate.send(row.topic(), row.eventKey(), row.payload())
                    .get(properties.getOutbox().getSendTimeout().toMillis(), TimeUnit.MILLISECONDS);
            return null;
        } catch (Exception ex) {
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

    private Map<OutboxKey, List<OutboxRecord>> groupByTopicKey(List<OutboxRecord> rows) {
        Map<OutboxKey, List<OutboxRecord>> groups = new LinkedHashMap<>();
        for (OutboxRecord row : rows) {
            groups.computeIfAbsent(new OutboxKey(row.topic(), row.eventKey()), ignored -> new ArrayList<>())
                    .add(row);
        }
        return groups;
    }

    private ThreadFactory threadFactory() {
        AtomicInteger counter = new AtomicInteger();
        return task -> {
            Thread thread = new Thread(task, "trading-outbox-publisher-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private record OutboxKey(String topic, String eventKey) {
    }

    private record GroupPublishResult(List<Long> publishedIds, List<FailedPublish> failures) {
    }

    private record FailedPublish(OutboxRecord row, Exception error) {
    }
}
