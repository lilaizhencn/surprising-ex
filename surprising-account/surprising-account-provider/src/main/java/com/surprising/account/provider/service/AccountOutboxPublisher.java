package com.surprising.account.provider.service;

import com.surprising.account.provider.config.AccountProperties;
import com.surprising.account.provider.model.AccountOutboxRecord;
import com.surprising.account.provider.repository.AccountOutboxRepository;
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

/**
 * Drains account outbox rows with row-level locking so multiple account nodes can publish safely.
 *
 * <p>Crashes can still produce duplicate Kafka sends between send and mark-published, so consumers
 * must treat event id/trade id as idempotency keys.</p>
 */
@Service
public class AccountOutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(AccountOutboxPublisher.class);
    private static final Duration CLAIM_LEASE = Duration.ofSeconds(30);

    private final AccountProperties properties;
    private final AccountOutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ExecutorService publishExecutor;
    private final int maxInFlight;
    private final AtomicBoolean publishing = new AtomicBoolean(false);

    public AccountOutboxPublisher(AccountProperties properties,
                                  AccountOutboxRepository outboxRepository,
                                  KafkaTemplate<String, String> kafkaTemplate) {
        this.properties = properties;
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.maxInFlight = Math.max(1, properties.getOutbox().getMaxInFlight());
        this.publishExecutor = Executors.newFixedThreadPool(this.maxInFlight, threadFactory());
    }

    @Scheduled(fixedDelayString = "${surprising.account.outbox.publish-delay-ms:200}")
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
                        .sorted(Comparator.comparing(AccountOutboxRecord::topic)
                                .thenComparing(AccountOutboxRecord::eventKey)
                                .thenComparingLong(AccountOutboxRecord::id))
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

    private void publishSequential(List<AccountOutboxRecord> rows) {
        for (AccountOutboxRecord row : rows) {
            Exception failure = publishToKafka(row);
            if (failure == null) {
                outboxRepository.markPublished(row.id(), Instant.now());
            } else {
                markFailed(row, failure);
            }
        }
    }

    private void publishConcurrent(List<AccountOutboxRecord> rows) {
        Map<OutboxKey, List<AccountOutboxRecord>> groups = groupByTopicKey(rows);
        ExecutorCompletionService<GroupPublishResult> completionService =
                new ExecutorCompletionService<>(publishExecutor);
        var iterator = groups.values().iterator();
        int submitted = 0;
        int completed = 0;
        while (submitted < maxInFlight && iterator.hasNext()) {
            List<AccountOutboxRecord> group = iterator.next();
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
                log.warn("Interrupted while publishing account outbox batch");
                return;
            } catch (Exception ex) {
                log.error("Unexpected account outbox publish task failure: {}", ex.getMessage(), ex);
                completed++;
                if (iterator.hasNext()) {
                    List<AccountOutboxRecord> group = iterator.next();
                    completionService.submit(() -> publishGroup(group));
                    submitted++;
                }
                continue;
            }
            publishedIds.addAll(result.publishedIds());
            failures.addAll(result.failures());
            completed++;
            if (iterator.hasNext()) {
                List<AccountOutboxRecord> group = iterator.next();
                completionService.submit(() -> publishGroup(group));
                submitted++;
            }
        }

        if (!publishedIds.isEmpty()) {
            try {
                outboxRepository.markPublished(publishedIds, Instant.now());
            } catch (Exception ex) {
                log.error("Failed to batch mark {} account outbox events published: {}",
                        publishedIds.size(), ex.getMessage(), ex);
                return;
            }
        }
        for (FailedPublish failure : failures) {
            markFailed(failure.row(), failure.error());
        }
    }

    private GroupPublishResult publishGroup(List<AccountOutboxRecord> group) {
        List<Long> publishedIds = new ArrayList<>(group.size());
        List<FailedPublish> failures = new ArrayList<>(1);
        for (AccountOutboxRecord row : group) {
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

    private Exception publishToKafka(AccountOutboxRecord row) {
        try {
            kafkaTemplate.send(row.topic(), row.eventKey(), row.payload())
                    .get(properties.getOutbox().getSendTimeout().toMillis(), TimeUnit.MILLISECONDS);
            return null;
        } catch (Exception ex) {
            return ex;
        }
    }

    private void markFailed(AccountOutboxRecord row, Exception ex) {
        log.warn("failed to publish account outbox id={} topic={}: {}",
                row.id(), row.topic(), ex.getMessage());
        try {
            outboxRepository.markFailed(row.id(), ex.getMessage(), Instant.now());
        } catch (Exception markEx) {
            log.error("Failed to mark account outbox id={} after publish failure: {}",
                    row.id(), markEx.getMessage(), markEx);
        }
    }

    private Map<OutboxKey, List<AccountOutboxRecord>> groupByTopicKey(List<AccountOutboxRecord> rows) {
        Map<OutboxKey, List<AccountOutboxRecord>> groups = new LinkedHashMap<>();
        for (AccountOutboxRecord row : rows) {
            groups.computeIfAbsent(new OutboxKey(row.topic(), row.eventKey()), ignored -> new ArrayList<>())
                    .add(row);
        }
        return groups;
    }

    private ThreadFactory threadFactory() {
        AtomicInteger counter = new AtomicInteger();
        return task -> {
            Thread thread = new Thread(task, "account-outbox-publisher-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private record OutboxKey(String topic, String eventKey) {
    }

    private record GroupPublishResult(List<Long> publishedIds, List<FailedPublish> failures) {
    }

    private record FailedPublish(AccountOutboxRecord row, Exception error) {
    }
}
