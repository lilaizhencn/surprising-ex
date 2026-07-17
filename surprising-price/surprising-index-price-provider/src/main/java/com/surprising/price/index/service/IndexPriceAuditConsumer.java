package com.surprising.price.index.service;

import com.surprising.price.api.model.IndexPriceEvent;
import com.surprising.price.index.config.IndexPriceProperties;
import com.surprising.price.index.repository.IndexPriceRepository;
import java.util.ArrayList;
import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Persists index audit data asynchronously; no real-time price consumer reads these tables. */
@Component
public class IndexPriceAuditConsumer {

    private static final Logger log = LoggerFactory.getLogger(IndexPriceAuditConsumer.class);

    private final ObjectMapper objectMapper;
    private final IndexPriceRepository repository;
    private final IndexPriceProperties properties;

    public IndexPriceAuditConsumer(ObjectMapper objectMapper,
                                   IndexPriceRepository repository,
                                   IndexPriceProperties properties) {
        this.objectMapper = objectMapper;
        this.repository = repository;
        this.properties = properties;
    }

    @KafkaListener(
            topics = "#{__listener.indexPriceTopic()}",
            groupId = "#{__listener.groupId()}",
            containerFactory = "indexAuditKafkaListenerContainerFactory")
    public void onAudit(List<ConsumerRecord<String, String>> records) {
        try {
            List<IndexPriceEvent> events = new ArrayList<>(records.size());
            for (ConsumerRecord<String, String> record : records) {
                IndexPriceEvent event = objectMapper.readValue(record.value(), IndexPriceEvent.class);
                if (record.key() == null || !record.key().equals(event.symbol())) {
                    throw new IllegalArgumentException("index price audit Kafka key must match payload symbol");
                }
                events.add(event);
            }
            repository.saveBatch(events);
        } catch (Exception ex) {
            log.error("Failed to persist index-price audit batch size={}: {}", records.size(), ex.getMessage(), ex);
            throw new IllegalStateException("failed to persist index price audit", ex);
        }
    }

    public String indexPriceTopic() {
        return properties.getKafka().getIndexPriceTopic();
    }

    public String groupId() {
        return properties.getKafka().getGroupId() + "-audit-writer";
    }
}
