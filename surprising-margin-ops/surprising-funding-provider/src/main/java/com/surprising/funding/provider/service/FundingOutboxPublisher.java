package com.surprising.funding.provider.service;

import com.surprising.funding.provider.config.FundingProperties;
import com.surprising.funding.provider.repository.FundingOutboxRepository;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FundingOutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(FundingOutboxPublisher.class);

    private final FundingProperties properties;
    private final FundingOutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    public FundingOutboxPublisher(FundingProperties properties,
                                  FundingOutboxRepository outboxRepository,
                                  @Qualifier("fundingKafkaTemplate") KafkaTemplate<String, String> kafkaTemplate) {
        this.properties = properties;
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
    }

    @Transactional
    @Scheduled(fixedDelayString = "${surprising.funding.outbox.publish-delay-ms:200}")
    public void publishPending() {
        for (var row : outboxRepository.lockPending(properties.getOutbox().getBatchSize())) {
            try {
                kafkaTemplate.send(row.topic(), row.eventKey(), row.payload())
                        .get(properties.getOutbox().getSendTimeout().toMillis(), TimeUnit.MILLISECONDS);
                outboxRepository.markPublished(row.id(), Instant.now());
            } catch (Exception ex) {
                log.warn("failed to publish funding outbox id={} topic={}: {}",
                        row.id(), row.topic(), ex.getMessage());
                outboxRepository.markFailed(row.id(), ex.getMessage(), Instant.now());
            }
        }
    }
}
