package com.surprising.instrument.provider.service;

import com.surprising.instrument.provider.config.InstrumentProperties;
import com.surprising.instrument.provider.model.InstrumentOutboxRecord;
import com.surprising.instrument.provider.repository.InstrumentOutboxRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
public class InstrumentOutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(InstrumentOutboxPublisher.class);
    private static final Duration MINIMUM_LEASE = Duration.ofSeconds(30);

    private final InstrumentProperties properties;
    private final InstrumentOutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final AtomicBoolean publishing = new AtomicBoolean(false);

    public InstrumentOutboxPublisher(InstrumentProperties properties,
                                     InstrumentOutboxRepository outboxRepository,
                                     KafkaTemplate<String, String> kafkaTemplate) {
        this.properties = properties;
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
    }

    public void publishPending() {
        if (!publishing.compareAndSet(false, true)) {
            return;
        }
        try {
            Instant now = Instant.now();
            Duration timeout = properties.getOutbox().getSendTimeout();
            Duration lease = timeout.plusSeconds(5);
            if (lease.compareTo(MINIMUM_LEASE) < 0) {
                lease = MINIMUM_LEASE;
            }
            var rows = outboxRepository.claimPending(
                    Math.max(1, properties.getOutbox().getBatchSize()), now.plus(lease), now);
            for (InstrumentOutboxRecord row : rows) {
                publish(row, timeout);
            }
        } finally {
            publishing.set(false);
        }
    }

    public void cleanupPublished() {
        int batchSize = Math.max(1, properties.getOutbox().getCleanupBatchSize());
        Instant cutoff = Instant.now().minus(properties.getOutbox().getRetention());
        int total = 0;
        for (int index = 0; index < Math.max(1, properties.getOutbox().getCleanupMaxBatches()); index++) {
            int deleted = outboxRepository.deletePublishedBefore(cutoff, batchSize);
            total += deleted;
            if (deleted < batchSize) {
                break;
            }
        }
        if (total > 0) {
            log.info("已清理 {} 条 instrument outbox 历史事件", total);
        }
    }

    private void publish(InstrumentOutboxRecord row, Duration timeout) {
        try {
            kafkaTemplate.send(row.topic(), row.eventKey(), row.payload())
                    .get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            outboxRepository.markPublished(row.id(), Instant.now());
        } catch (Exception ex) {
            log.warn("instrument outbox 发布失败 id={} topic={}: {}",
                    row.id(), row.topic(), ex.getMessage());
            outboxRepository.markFailed(row.id(), ex.getMessage(), Instant.now());
        }
    }
}
