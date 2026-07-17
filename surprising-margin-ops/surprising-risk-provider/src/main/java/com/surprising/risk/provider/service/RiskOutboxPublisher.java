package com.surprising.risk.provider.service;

import com.surprising.risk.provider.config.RiskProperties;
import com.surprising.risk.provider.model.RiskOutboxRecord;
import com.surprising.risk.provider.repository.RiskOutboxRepository;
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
public class RiskOutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(RiskOutboxPublisher.class);
    private static final Duration MINIMUM_CLAIM_LEASE = Duration.ofSeconds(30);
    private static final Duration CLAIM_LEASE_BUFFER = Duration.ofSeconds(5);

    private final RiskProperties properties;
    private final RiskOutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ExecutorService publishExecutor;
    private final int maxInFlight;
    private final AtomicBoolean publishing = new AtomicBoolean(false);

    public RiskOutboxPublisher(RiskProperties properties,
                               RiskOutboxRepository outboxRepository,
                               @Qualifier("riskKafkaTemplate") KafkaTemplate<String, String> kafkaTemplate) {
        this.properties = properties;
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.maxInFlight = Math.max(1, properties.getOutbox().getMaxInFlight());
        this.publishExecutor = Executors.newFixedThreadPool(this.maxInFlight, threadFactory());
    }

    @Scheduled(fixedDelayString = "${surprising.risk.outbox.publish-delay-ms:200}")
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
                        .sorted(Comparator.comparing(RiskOutboxRecord::topic)
                                .thenComparing(RiskOutboxRecord::eventKey)
                                .thenComparingLong(RiskOutboxRecord::id))
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

    @Scheduled(fixedDelayString = "${surprising.risk.outbox.cleanup-delay-ms:60000}")
    public void cleanupPublished() {
        int deleted = outboxRepository.deletePublishedBefore(Instant.now().minus(properties.getOutbox().getRetention()),
                properties.getOutbox().getCleanupBatchSize());
        if (deleted > 0) {
            log.info("Deleted {} published risk outbox rows", deleted);
        }
    }

    @PreDestroy
    public void shutdown() {
        publishExecutor.shutdownNow();
    }

    private void publishSequential(List<RiskOutboxRecord> rows) {
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
        int workPerKey = Math.max(1, Math.min(batchSize, properties.getOutbox().getMaxRowsPerKey()));
        int sendRounds = properties.getOutbox().isAsyncEnabled() ? workPerKey : batchSize;
        Duration budget = properties.getOutbox().getSendTimeout().multipliedBy(sendRounds)
                .plus(CLAIM_LEASE_BUFFER);
        return budget.compareTo(MINIMUM_CLAIM_LEASE) < 0 ? MINIMUM_CLAIM_LEASE : budget;
    }

    private void publishConcurrent(List<RiskOutboxRecord> rows) {
        Map<OutboxKey, List<RiskOutboxRecord>> groups = groupByTopicKey(rows);
        ExecutorCompletionService<GroupPublishResult> completionService =
                new ExecutorCompletionService<>(publishExecutor);
        var iterator = groups.values().iterator();
        int submitted = 0;
        int completed = 0;
        while (submitted < maxInFlight && iterator.hasNext()) {
            List<RiskOutboxRecord> group = iterator.next();
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
                log.warn("Interrupted while publishing risk outbox batch");
                return;
            } catch (Exception ex) {
                log.error("Unexpected risk outbox publish task failure: {}", ex.getMessage(), ex);
                completed++;
                if (iterator.hasNext()) {
                    List<RiskOutboxRecord> group = iterator.next();
                    completionService.submit(() -> publishGroup(group));
                    submitted++;
                }
                continue;
            }
            publishedIds.addAll(result.publishedIds());
            failures.addAll(result.failures());
            completed++;
            if (iterator.hasNext()) {
                List<RiskOutboxRecord> group = iterator.next();
                completionService.submit(() -> publishGroup(group));
                submitted++;
            }
        }

        if (!publishedIds.isEmpty()) {
            try {
                outboxRepository.markPublished(publishedIds, Instant.now());
            } catch (Exception ex) {
                log.error("Failed to batch mark {} risk outbox events published: {}",
                        publishedIds.size(), ex.getMessage(), ex);
                return;
            }
        }
        for (FailedPublish failure : failures) {
            markFailed(failure.row(), failure.error());
        }
    }

    private GroupPublishResult publishGroup(List<RiskOutboxRecord> group) {
        List<Long> publishedIds = new ArrayList<>(group.size());
        List<FailedPublish> failures = new ArrayList<>(1);
        for (RiskOutboxRecord row : group) {
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

    private Exception publishToKafka(RiskOutboxRecord row) {
        try {
            kafkaTemplate.send(row.topic(), row.eventKey(), row.payload())
                    .get(properties.getOutbox().getSendTimeout().toMillis(), TimeUnit.MILLISECONDS);
            return null;
        } catch (Exception ex) {
            return ex;
        }
    }

    private void markFailed(RiskOutboxRecord row, Exception ex) {
        log.warn("Failed to publish risk outbox id={} topic={}: {}",
                row.id(), row.topic(), ex.getMessage());
        try {
            outboxRepository.markFailed(row.id(), ex.getMessage(), Instant.now());
        } catch (Exception markEx) {
            log.error("Failed to mark risk outbox id={} after publish failure: {}",
                    row.id(), markEx.getMessage(), markEx);
        }
    }

    private Map<OutboxKey, List<RiskOutboxRecord>> groupByTopicKey(List<RiskOutboxRecord> rows) {
        Map<OutboxKey, List<RiskOutboxRecord>> groups = new LinkedHashMap<>();
        for (RiskOutboxRecord row : rows) {
            groups.computeIfAbsent(new OutboxKey(row.topic(), row.eventKey()), ignored -> new ArrayList<>())
                    .add(row);
        }
        return groups;
    }

    private ThreadFactory threadFactory() {
        AtomicInteger counter = new AtomicInteger();
        return task -> {
            Thread thread = new Thread(task, "risk-outbox-publisher-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private record OutboxKey(String topic, String eventKey) {
    }

    private record GroupPublishResult(List<Long> publishedIds, List<FailedPublish> failures) {
    }

    private record FailedPublish(RiskOutboxRecord row, Exception error) {
    }
}
