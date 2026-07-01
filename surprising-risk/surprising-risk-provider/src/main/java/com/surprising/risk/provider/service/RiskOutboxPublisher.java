package com.surprising.risk.provider.service;

import com.surprising.risk.provider.config.RiskProperties;
import com.surprising.risk.provider.repository.RiskOutboxRepository;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RiskOutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(RiskOutboxPublisher.class);

    private final RiskProperties properties;
    private final RiskOutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    public RiskOutboxPublisher(RiskProperties properties,
                               RiskOutboxRepository outboxRepository,
                               KafkaTemplate<String, String> kafkaTemplate) {
        this.properties = properties;
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
    }

    @Transactional
    @Scheduled(fixedDelayString = "${surprising.risk.outbox.publish-delay-ms:200}")
    public void publishPending() {
        var rows = outboxRepository.lockPending(properties.getOutbox().getBatchSize());
        for (var row : rows) {
            try {
                kafkaTemplate.send(row.topic(), row.eventKey(), row.payload())
                        .get(properties.getOutbox().getSendTimeout().toMillis(), TimeUnit.MILLISECONDS);
                outboxRepository.markPublished(row.id(), Instant.now());
            } catch (Exception ex) {
                log.warn("Failed to publish risk outbox id={} topic={}: {}",
                        row.id(), row.topic(), ex.getMessage());
                outboxRepository.markFailed(row.id(), ex.getMessage(), Instant.now());
            }
        }
    }
}
