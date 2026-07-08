package com.surprising.trading.matching.service;

import com.surprising.trading.matching.config.MatchingProperties;
import com.surprising.trading.matching.model.StoredOutboxRecord;
import com.surprising.trading.matching.repository.MatchingOutboxRepository;
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
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class MatchingOutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(MatchingOutboxPublisher.class);
    private static final Duration CLAIM_LEASE = Duration.ofSeconds(30);

    private final MatchingProperties properties;
    private final MatchingOutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ExecutorService publishExecutor;
    private final int maxInFlight;
    private final AtomicBoolean publishing = new AtomicBoolean(false);

    public MatchingOutboxPublisher(MatchingProperties properties,
                                   MatchingOutboxRepository outboxRepository,
                                   KafkaTemplate<String, String> kafkaTemplate) {
        this.properties = properties;
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.maxInFlight = Math.max(1, properties.getOutbox().getMaxInFlight());
        this.publishExecutor = Executors.newFixedThreadPool(this.maxInFlight, threadFactory());
    }

    @Scheduled(fixedDelayString = "${surprising.trading.matching.outbox.publish-delay-ms:100}")
    public void publishPending() {
        if (!publishing.compareAndSet(false, true)) {
            return;
        }
        try {
            int remaining = properties.getOutbox().getBatchSize();
            while (remaining > 0) {
                Instant now = Instant.now();
                var rows = outboxRepository.claimPending(remaining, now.plus(CLAIM_LEASE), now)
                        .stream()
                        .sorted(Comparator.comparing(StoredOutboxRecord::topic)
                                .thenComparing(StoredOutboxRecord::eventKey)
                                .thenComparingLong(StoredOutboxRecord::id))
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

    @PreDestroy
    public void shutdown() {
        publishExecutor.shutdownNow();
    }

    private void publishSequential(List<StoredOutboxRecord> rows) {
        for (var row : rows) {
            Exception failure = publishToKafka(row);
            if (failure == null) {
                outboxRepository.markPublished(row.id(), Instant.now());
            } else {
                markFailed(row, failure);
            }
        }
    }

    private void publishConcurrent(List<StoredOutboxRecord> rows) {
        Map<OutboxKey, List<StoredOutboxRecord>> groups = groupByTopicKey(rows);
        ExecutorCompletionService<GroupPublishResult> completionService =
                new ExecutorCompletionService<>(publishExecutor);
        var iterator = groups.values().iterator();
        int submitted = 0;
        int completed = 0;
        while (submitted < maxInFlight && iterator.hasNext()) {
            List<StoredOutboxRecord> group = iterator.next();
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
                log.warn("Interrupted while publishing matching outbox batch");
                return;
            } catch (Exception ex) {
                log.error("Unexpected matching outbox publish task failure: {}", ex.getMessage(), ex);
                completed++;
                if (iterator.hasNext()) {
                    List<StoredOutboxRecord> group = iterator.next();
                    completionService.submit(() -> publishGroup(group));
                    submitted++;
                }
                continue;
            }
            publishedIds.addAll(result.publishedIds());
            failures.addAll(result.failures());
            completed++;
            if (iterator.hasNext()) {
                List<StoredOutboxRecord> group = iterator.next();
                completionService.submit(() -> publishGroup(group));
                submitted++;
            }
        }

        if (!publishedIds.isEmpty()) {
            try {
                outboxRepository.markPublished(publishedIds, Instant.now());
            } catch (Exception ex) {
                log.error("Failed to batch mark {} matching outbox events published: {}",
                        publishedIds.size(), ex.getMessage(), ex);
                return;
            }
        }
        for (FailedPublish failure : failures) {
            markFailed(failure.row(), failure.error());
        }
    }

    private GroupPublishResult publishGroup(List<StoredOutboxRecord> group) {
        List<Long> publishedIds = new ArrayList<>(group.size());
        List<FailedPublish> failures = new ArrayList<>(1);
        for (StoredOutboxRecord row : group) {
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

    private Exception publishToKafka(StoredOutboxRecord row) {
        try {
            kafkaTemplate.send(row.topic(), row.eventKey(), row.payload())
                    .get(properties.getOutbox().getSendTimeout().toMillis(), TimeUnit.MILLISECONDS);
            return null;
        } catch (Exception ex) {
            return ex;
        }
    }

    private void markFailed(StoredOutboxRecord row, Exception ex) {
        log.warn("Failed to publish matching outbox id={} topic={}: {}",
                row.id(), row.topic(), ex.getMessage());
        try {
            outboxRepository.markFailed(row.id(), ex.getMessage(), Instant.now());
        } catch (Exception markEx) {
            log.error("Failed to mark matching outbox id={} after publish failure: {}",
                    row.id(), markEx.getMessage(), markEx);
        }
    }

    private Map<OutboxKey, List<StoredOutboxRecord>> groupByTopicKey(List<StoredOutboxRecord> rows) {
        Map<OutboxKey, List<StoredOutboxRecord>> groups = new LinkedHashMap<>();
        for (StoredOutboxRecord row : rows) {
            groups.computeIfAbsent(new OutboxKey(row.topic(), row.eventKey()), ignored -> new ArrayList<>())
                    .add(row);
        }
        return groups;
    }

    private ThreadFactory threadFactory() {
        AtomicInteger counter = new AtomicInteger();
        return task -> {
            Thread thread = new Thread(task, "matching-outbox-publisher-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private record OutboxKey(String topic, String eventKey) {
    }

    private record GroupPublishResult(List<Long> publishedIds, List<FailedPublish> failures) {
    }

    private record FailedPublish(StoredOutboxRecord row, Exception error) {
    }
}
