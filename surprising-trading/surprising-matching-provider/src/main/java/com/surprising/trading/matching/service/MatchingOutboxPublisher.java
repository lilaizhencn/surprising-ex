package com.surprising.trading.matching.service;

import com.surprising.trading.matching.config.MatchingProperties;
import com.surprising.trading.matching.repository.MatchingOutboxRepository;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class MatchingOutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(MatchingOutboxPublisher.class);

    private final MatchingProperties properties;
    private final MatchingOutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final TransactionTemplate transactionTemplate;

    public MatchingOutboxPublisher(MatchingProperties properties,
                                   MatchingOutboxRepository outboxRepository,
                                   KafkaTemplate<String, String> kafkaTemplate,
                                   PlatformTransactionManager transactionManager) {
        this.properties = properties;
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Scheduled(fixedDelayString = "${surprising.trading.matching.outbox.publish-delay-ms:100}")
    public void publishPending() {
        int remaining = properties.getOutbox().getBatchSize();
        while (remaining > 0) {
            int limit = remaining;
            Integer published = transactionTemplate.execute(ignored -> drainOnce(limit));
            if (published == null || published == 0) {
                return;
            }
            remaining -= published;
        }
    }

    private int drainOnce(int limit) {
        var rows = outboxRepository.lockPending(limit);
        int processed = 0;
        for (var row : rows) {
            try {
                kafkaTemplate.send(row.topic(), row.eventKey(), row.payload())
                        .get(properties.getOutbox().getSendTimeout().toMillis(), TimeUnit.MILLISECONDS);
                outboxRepository.markPublished(row.id(), Instant.now());
            } catch (Exception ex) {
                log.warn("Failed to publish matching outbox id={} topic={}: {}",
                        row.id(), row.topic(), ex.getMessage());
                outboxRepository.markFailed(row.id(), ex.getMessage(), Instant.now());
            }
            processed++;
        }
        return processed;
    }
}
