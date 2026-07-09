package com.surprising.liquidation.provider.service;

import com.surprising.liquidation.provider.config.LiquidationProperties;
import com.surprising.liquidation.provider.repository.LiquidationOrderRepository;
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
public class TradingOutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(TradingOutboxPublisher.class);

    private final LiquidationProperties properties;
    private final LiquidationOrderRepository orderRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    public TradingOutboxPublisher(LiquidationProperties properties,
                                  LiquidationOrderRepository orderRepository,
                                  @Qualifier("liquidationKafkaTemplate") KafkaTemplate<String, String> kafkaTemplate) {
        this.properties = properties;
        this.orderRepository = orderRepository;
        this.kafkaTemplate = kafkaTemplate;
    }

    @Transactional
    @Scheduled(fixedDelayString = "${surprising.liquidation.outbox.publish-delay-ms:100}")
    public void publishPending() {
        var rows = orderRepository.lockPending(properties.getOutbox().getBatchSize());
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
    }
}
