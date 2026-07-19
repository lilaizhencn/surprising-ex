package com.surprising.liquidation.provider.service;

import com.surprising.liquidation.provider.config.LiquidationProperties;
import com.surprising.liquidation.provider.repository.LiquidationRepository;
import com.surprising.risk.api.model.LiquidationCandidateEvent;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class LiquidationCandidateQueueProcessor {

    private static final Logger log = LoggerFactory.getLogger(LiquidationCandidateQueueProcessor.class);

    private final RedisLiquidationCandidateQueue queue;
    private final LiquidationRepository repository;
    private final LiquidationService service;
    private final LiquidationProperties properties;
    private final TaskExecutor taskExecutor;
    private final Semaphore wakeup = new Semaphore(0);
    private volatile boolean running = true;

    public LiquidationCandidateQueueProcessor(RedisLiquidationCandidateQueue queue,
                                              LiquidationRepository repository,
                                              LiquidationService service,
                                              LiquidationProperties properties,
                                              @Qualifier("liquidationCandidateTaskExecutor") TaskExecutor taskExecutor) {
        this.queue = queue;
        this.repository = repository;
        this.service = service;
        this.properties = properties;
        this.taskExecutor = taskExecutor;
    }

    public void enqueue(List<LiquidationCandidateEvent> events) {
        queue.offer(events);
        wakeWorkers();
    }

    @PostConstruct
    public void startWorkers() {
        int workerCount = properties.getRedisIndex().getWorkerCount();
        for (int i = 0; i < workerCount; i++) {
            taskExecutor.execute(this::workerLoop);
        }
    }

    @PreDestroy
    public void stopWorkers() {
        running = false;
    }

    @Scheduled(fixedDelayString = "${surprising.liquidation.redis-index.recovery-delay-ms:1000}")
    public void recoverDurableCandidates() {
        int limit = properties.getRedisIndex().getCandidateBatchSize()
                * properties.getRedisIndex().getWorkerCount();
        List<LiquidationCandidateEvent> candidates = repository.newCandidateEvents(limit);
        if (!candidates.isEmpty()) {
            queue.offer(candidates);
            wakeWorkers();
        }
    }

    private void workerLoop() {
        int batchSize = properties.getRedisIndex().getCandidateBatchSize();
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                Instant now = Instant.now();
                List<LiquidationCandidateEvent> candidates = queue.claim(batchSize,
                        properties.getRedisIndex().getLeaseDuration(), now);
                if (candidates.isEmpty()) {
                    awaitWork();
                    continue;
                }
                processLease(candidates, now);
            } catch (RuntimeException ex) {
                log.warn("liquidation Redis worker iteration failed", ex);
                awaitWork();
            }
        }
    }

    private void awaitWork() {
        try {
            wakeup.tryAcquire(500, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private void wakeWorkers() {
        int missingPermits = properties.getRedisIndex().getWorkerCount() - wakeup.availablePermits();
        if (missingPermits > 0) {
            wakeup.release(missingPermits);
        }
    }

    private void processLease(List<LiquidationCandidateEvent> candidates, Instant now) {
        List<Long> allIds = candidates.stream().map(LiquidationCandidateEvent::candidateId).toList();
        try {
            LiquidationService.CandidateBatchResult result = service.processCandidates(candidates);
            Set<Long> retryIds = new HashSet<>(result.retryCandidateIds());
            List<Long> acknowledged = allIds.stream().filter(id -> !retryIds.contains(id)).toList();
            queue.acknowledge(acknowledged);
            queue.retry(result.retryCandidateIds(), properties.getRedisIndex().getRetryDelay(), now);
        } catch (RuntimeException ex) {
            queue.retry(allIds, properties.getRedisIndex().getRetryDelay(), now);
            log.warn("liquidation candidate batch remains queued size={} firstId={}", candidates.size(),
                    candidates.getFirst().candidateId(), ex);
        }
    }
}
