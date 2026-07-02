package com.surprising.trading.matching.service;

import com.surprising.trading.matching.config.MatchingProperties;
import com.surprising.trading.matching.model.StoredOutboxRecord;
import com.surprising.trading.matching.repository.MatchingOutboxRepository;
import java.time.Duration;
import java.time.Instant;
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

    public MatchingOutboxPublisher(MatchingProperties properties,
                                   MatchingOutboxRepository outboxRepository,
                                   KafkaTemplate<String, String> kafkaTemplate) {
        this.properties = properties;
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
    }

    @Scheduled(fixedDelayString = "${surprising.trading.matching.outbox.publish-delay-ms:100}")
    public void publishPending() {
        int remaining = properties.getOutbox().getBatchSize();
        while (remaining > 0) {
            Instant now = Instant.now();
            var rows = outboxRepository.claimPending(remaining, now.plus(CLAIM_LEASE), now);
            if (rows.isEmpty()) {
                return;
            }
            for (var row : rows) {
                publish(row);
            }
            remaining -= rows.size();
        }
    }

    private void publish(StoredOutboxRecord row) {
        try {
            kafkaTemplate.send(row.topic(), row.eventKey(), row.payload())
                    .get(properties.getOutbox().getSendTimeout().toMillis(), TimeUnit.MILLISECONDS);
            outboxRepository.markPublished(row.id(), Instant.now());
        } catch (Exception ex) {
            log.warn("Failed to publish matching outbox id={} topic={}: {}",
                    row.id(), row.topic(), ex.getMessage());
            try {
                outboxRepository.markFailed(row.id(), ex.getMessage(), Instant.now());
            } catch (Exception markEx) {
                log.error("Failed to mark matching outbox id={} after publish failure: {}",
                        row.id(), markEx.getMessage(), markEx);
            }
        }
    }
}
