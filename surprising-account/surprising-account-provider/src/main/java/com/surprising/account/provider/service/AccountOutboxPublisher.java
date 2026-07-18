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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeoutException;
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
    private static final Duration MINIMUM_CLAIM_LEASE = Duration.ofSeconds(30);
    private static final Duration CLAIM_LEASE_BUFFER = Duration.ofSeconds(5);

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
                var rows = outboxRepository.claimPending(remaining, now.plus(claimLease(remaining)), now)
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

    @Scheduled(fixedDelayString = "${surprising.account.outbox.cleanup-delay-ms:60000}")
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
            log.info("Deleted {} published account outbox rows", totalDeleted);
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

    private Duration claimLease(int claimedLimit) {
        int batchSize = Math.max(1, claimedLimit);
        int workPerKey = Math.max(1, Math.min(batchSize, properties.getOutbox().getMaxRowsPerKey()));
        int sendRounds = properties.getOutbox().isAsyncEnabled() ? workPerKey : batchSize;
        Duration budget = properties.getOutbox().getSendTimeout().multipliedBy(sendRounds)
                .plus(CLAIM_LEASE_BUFFER);
        return budget.compareTo(MINIMUM_CLAIM_LEASE) < 0 ? MINIMUM_CLAIM_LEASE : budget;
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
        int windowSize = requiresStrictAckOrder(group)
                ? 1
                : Math.max(1, properties.getOutbox().getSendWindowSize());
        for (int offset = 0; offset < group.size(); offset += windowSize) {
            int end = Math.min(group.size(), offset + windowSize);
            List<PendingPublish> pending = new ArrayList<>(end - offset);
            FailedPublish synchronousFailure = null;
            for (int index = offset; index < end; index++) {
                AccountOutboxRecord row = group.get(index);
                try {
                    pending.add(new PendingPublish(
                            row, kafkaTemplate.send(row.topic(), row.eventKey(), row.payload())));
                } catch (Exception ex) {
                    synchronousFailure = new FailedPublish(row, ex);
                    break;
                }
            }

            FailedPublish asynchronousFailure = awaitContinuousSuccessPrefix(pending, publishedIds);
            if (asynchronousFailure != null) {
                failures.add(asynchronousFailure);
                break;
            }
            if (synchronousFailure != null) {
                failures.add(synchronousFailure);
                break;
            }
        }
        return new GroupPublishResult(publishedIds, failures);
    }

    private FailedPublish awaitContinuousSuccessPrefix(List<PendingPublish> pending,
                                                       List<Long> publishedIds) {
        if (pending.isEmpty()) {
            return null;
        }
        try {
            CompletableFuture.allOf(pending.stream()
                            .map(PendingPublish::future)
                            .toArray(CompletableFuture[]::new))
                    .get(properties.getOutbox().getSendTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return new FailedPublish(firstUnconfirmed(pending), ex);
        } catch (ExecutionException | TimeoutException ignored) {
            // Inspect futures in submission order below and only confirm the continuous success prefix.
        }

        for (PendingPublish publish : pending) {
            if (!publish.future().isDone()) {
                return new FailedPublish(publish.row(),
                        new TimeoutException("timed out waiting for Kafka send acknowledgement"));
            }
            try {
                publish.future().join();
                publishedIds.add(publish.row().id());
            } catch (CompletionException | CancellationException ex) {
                return new FailedPublish(publish.row(), unwrap(ex));
            }
        }
        return null;
    }

    private AccountOutboxRecord firstUnconfirmed(List<PendingPublish> pending) {
        return pending.stream()
                .filter(publish -> !publish.future().isDone() || publish.future().isCompletedExceptionally())
                .map(PendingPublish::row)
                .findFirst()
                .orElse(pending.get(0).row());
    }

    private Exception unwrap(Exception exception) {
        Throwable current = exception;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current instanceof Exception result ? result : new IllegalStateException(current);
    }

    private boolean requiresStrictAckOrder(List<AccountOutboxRecord> group) {
        return !group.isEmpty()
                && properties.getKafka().getUserCommandsTopic().equals(group.get(0).topic());
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

    private record PendingPublish(AccountOutboxRecord row,
                                  CompletableFuture<?> future) {
    }

    private record FailedPublish(AccountOutboxRecord row, Exception error) {
    }
}
