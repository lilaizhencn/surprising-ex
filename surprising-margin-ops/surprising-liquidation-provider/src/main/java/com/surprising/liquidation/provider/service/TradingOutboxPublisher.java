package com.surprising.liquidation.provider.service;

import com.surprising.liquidation.provider.config.LiquidationProperties;
import com.surprising.liquidation.provider.repository.LiquidationOrderRepository;
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

@Service
public class TradingOutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(TradingOutboxPublisher.class);
    private static final Duration CLAIM_LEASE = Duration.ofSeconds(30);

    private final LiquidationProperties properties;
    private final LiquidationOrderRepository orderRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final AtomicBoolean publishing = new AtomicBoolean(false);

    public TradingOutboxPublisher(LiquidationProperties properties,
                                  LiquidationOrderRepository orderRepository,
                                  @Qualifier("liquidationKafkaTemplate") KafkaTemplate<String, String> kafkaTemplate) {
        this.properties = properties;
        this.orderRepository = orderRepository;
        this.kafkaTemplate = kafkaTemplate;
    }

    @Scheduled(fixedDelayString = "${surprising.liquidation.outbox.publish-delay-ms:100}")
    public void publishPending() {
        if (!publishing.compareAndSet(false, true)) {
            return;
        }
        try {
            Instant now = Instant.now();
            var rows = orderRepository.claimPending(properties.getOutbox().getBatchSize(), now.plus(CLAIM_LEASE), now);
            for (var row : rows) {
                try {
                    kafkaTemplate.send(row.topic(), row.eventKey(), row.payload())
                            .get(properties.getOutbox().getSendTimeout().toMillis(), TimeUnit.MILLISECONDS);
                    orderRepository.markPublished(row.id(), Instant.now());
                } catch (Exception ex) {
                    log.warn("Failed to publish liquidation trading outbox id={} topic={}: {}",
                            row.id(), row.topic(), ex.getMessage());
                    orderRepository.markFailed(row.id(), ex.getMessage(), Instant.now());
                }
            }
        } finally {
            publishing.set(false);
        }
    }
}
