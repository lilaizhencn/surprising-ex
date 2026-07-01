package com.surprising.account.provider.service;

import com.surprising.account.provider.config.AccountProperties;
import com.surprising.account.provider.repository.AccountOutboxRepository;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Drains account outbox rows with row-level locking so multiple account nodes can publish safely.
 *
 * <p>Crashes can still produce duplicate Kafka sends between send and mark-published, so consumers
 * must treat event id/trade id as idempotency keys.</p>
 */
@Service
public class AccountOutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(AccountOutboxPublisher.class);

    private final AccountProperties properties;
    private final AccountOutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    public AccountOutboxPublisher(AccountProperties properties,
                                  AccountOutboxRepository outboxRepository,
                                  KafkaTemplate<String, String> kafkaTemplate) {
        this.properties = properties;
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
    }

    @Transactional
    @Scheduled(fixedDelayString = "${surprising.account.outbox.publish-delay-ms:200}")
    public void publishPending() {
        for (var row : outboxRepository.lockPending(properties.getOutbox().getBatchSize())) {
            try {
                kafkaTemplate.send(row.topic(), row.eventKey(), row.payload())
                        .get(properties.getOutbox().getSendTimeout().toMillis(), TimeUnit.MILLISECONDS);
                outboxRepository.markPublished(row.id(), Instant.now());
            } catch (Exception ex) {
                log.warn("failed to publish account outbox id={} topic={}: {}",
                        row.id(), row.topic(), ex.getMessage());
                outboxRepository.markFailed(row.id(), ex.getMessage(), Instant.now());
            }
        }
    }
}
