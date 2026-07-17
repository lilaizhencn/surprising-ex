package com.surprising.price.mark.service;

import com.surprising.price.api.model.MarkPricePublishedEvent;
import com.surprising.price.mark.config.MarkPriceProperties;
import com.surprising.price.mark.model.MarkPriceAuditRecord;
import com.surprising.price.mark.repository.MarkPriceRepository;
import java.util.ArrayList;
import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Persists the audit stream asynchronously; no real-time consumer depends on this write. */
@Component
public class MarkPriceAuditConsumer {

    private static final Logger log = LoggerFactory.getLogger(MarkPriceAuditConsumer.class);

    private final ObjectMapper objectMapper;
    private final MarkPriceRepository repository;
    private final MarkPriceProperties properties;

    public MarkPriceAuditConsumer(ObjectMapper objectMapper,
                                  MarkPriceRepository repository,
                                  MarkPriceProperties properties) {
        this.objectMapper = objectMapper;
        this.repository = repository;
        this.properties = properties;
    }

    @KafkaListener(
            topics = "#{__listener.markPriceTopic()}",
            groupId = "#{__listener.groupId()}",
            containerFactory = "markAuditKafkaListenerContainerFactory")
    public void onAudit(List<ConsumerRecord<String, String>> records) {
        try {
            List<MarkPriceAuditRecord> auditRecords = new ArrayList<>(records.size());
            for (ConsumerRecord<String, String> record : records) {
                MarkPricePublishedEvent event = objectMapper.readValue(record.value(), MarkPricePublishedEvent.class);
                if (event.result() == null || record.key() == null
                        || !record.key().equals(event.result().symbol())) {
                    throw new IllegalArgumentException("mark price audit Kafka key must match payload symbol");
                }
                auditRecords.add(new MarkPriceAuditRecord(event, record.value()));
            }
            repository.saveBatch(auditRecords);
        } catch (Exception ex) {
            log.error("Failed to persist mark price audit batch size={}: {}", records.size(), ex.getMessage(), ex);
            throw new IllegalStateException("failed to persist mark price audit", ex);
        }
    }

    public String markPriceTopic() {
        return properties.markPriceTopic();
    }

    public String groupId() {
        return properties.getKafka().getGroupId() + "-audit-writer";
    }
}
