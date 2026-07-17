package com.surprising.trading.trigger.service;

import com.surprising.trading.trigger.config.TriggerProperties;
import com.surprising.trading.trigger.model.TriggerOutboxRecord;
import com.surprising.trading.trigger.repository.TriggerOrderOutboxRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
    private final AtomicBoolean publishing = new AtomicBoolean(false);

    public TriggerOrderOutboxPublisher(TriggerProperties properties,
                                       TriggerOrderOutboxRepository outboxRepository,
                                       @Qualifier("triggerKafkaTemplate") KafkaTemplate<String, String> kafkaTemplate) {
        this.properties = properties;
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
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
                var rows = outboxRepository.claimPending(remaining, now.plus(claimLease(remaining)), now);
                if (rows.isEmpty()) {
                    return;
                }
                for (TriggerOutboxRecord row : rows) {
                    publish(row);
                }
                remaining -= rows.size();
            }
        } finally {
            publishing.set(false);
        }
    }

    private void publish(TriggerOutboxRecord row) {
        try {
            kafkaTemplate.send(row.topic(), row.eventKey(), row.payload())
                    .get(properties.getOutbox().getSendTimeout().toMillis(), TimeUnit.MILLISECONDS);
            outboxRepository.markPublished(row.id(), Instant.now());
        } catch (Exception ex) {
            log.warn("Failed to publish trigger order outbox id={} topic={}: {}",
                    row.id(), row.topic(), ex.getMessage());
            try {
                outboxRepository.markFailed(row.id(), ex.getMessage(), Instant.now());
            } catch (Exception markEx) {
                log.error("Failed to mark trigger order outbox id={} after publish failure: {}",
                        row.id(), markEx.getMessage(), markEx);
            }
        }
    }

    private Duration claimLease(int claimedLimit) {
        Duration budget = properties.getOutbox().getSendTimeout().multipliedBy(Math.max(1, claimedLimit))
                .plus(CLAIM_LEASE_BUFFER);
        return budget.compareTo(MINIMUM_CLAIM_LEASE) < 0 ? MINIMUM_CLAIM_LEASE : budget;
    }
}
