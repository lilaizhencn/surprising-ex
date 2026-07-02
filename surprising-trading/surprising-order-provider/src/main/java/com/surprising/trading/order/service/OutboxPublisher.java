package com.surprising.trading.order.service;

import com.surprising.trading.order.config.TradingOrderProperties;
import com.surprising.trading.order.model.OutboxRecord;
import com.surprising.trading.order.repository.OutboxRepository;
import java.util.Comparator;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private static final Duration CLAIM_LEASE = Duration.ofSeconds(30);

    private final TradingOrderProperties properties;
    private final OutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final AtomicBoolean publishing = new AtomicBoolean(false);

    public OutboxPublisher(TradingOrderProperties properties,
                           OutboxRepository outboxRepository,
                           KafkaTemplate<String, String> kafkaTemplate) {
        this.properties = properties;
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
    }

    @Scheduled(fixedDelayString = "${surprising.trading.order.outbox.publish-delay-ms:200}")
    public void publishPending() {
        if (!publishing.compareAndSet(false, true)) {
            return;
        }
        try {
            int remaining = properties.getOutbox().getBatchSize();
            while (remaining > 0) {
                Instant now = Instant.now();
                var rows = outboxRepository.claimPending(remaining, now.plus(CLAIM_LEASE), now)
                        .stream()
                        .sorted(Comparator.comparing(OutboxRecord::topic)
                                .thenComparing(OutboxRecord::eventKey)
                                .thenComparingLong(OutboxRecord::id))
                        .toList();
                if (rows.isEmpty()) {
                    return;
                }
                for (var row : rows) {
                    publish(row);
                }
                remaining -= rows.size();
            }
        } finally {
            publishing.set(false);
        }
    }

    private void publish(OutboxRecord row) {
        // Delivery is at least once; downstream consumers must dedupe by commandId/orderId or eventId.
        try {
            kafkaTemplate.send(row.topic(), row.eventKey(), row.payload())
                    .get(properties.getOutbox().getSendTimeout().toMillis(), TimeUnit.MILLISECONDS);
            outboxRepository.markPublished(row.id(), Instant.now());
        } catch (Exception ex) {
            log.warn("Failed to publish trading outbox id={} topic={}: {}",
                    row.id(), row.topic(), ex.getMessage());
            try {
                outboxRepository.markFailed(row.id(), ex.getMessage(), Instant.now());
            } catch (Exception markEx) {
                log.error("Failed to mark trading outbox id={} after publish failure: {}",
                        row.id(), markEx.getMessage(), markEx);
            }
        }
    }
}
