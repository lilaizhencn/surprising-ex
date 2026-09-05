package com.surprising.price.index.service;

import com.surprising.price.api.model.IndexPriceEvent;
import com.surprising.price.api.model.PriceEventType;
import com.surprising.price.api.model.PricePublishedEvent;
import com.surprising.price.index.config.IndexPriceProperties;
import java.util.ArrayList;
import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** 异步持久化指数价格审计数据，实时价格消费者不读取这些表。 */
@Component
public class IndexPriceAuditConsumer {

    private static final Logger log = LoggerFactory.getLogger(IndexPriceAuditConsumer.class);

    private final ObjectMapper objectMapper;
    private final IndexPriceAuditService auditService;
    private final IndexPriceProperties properties;

    public IndexPriceAuditConsumer(ObjectMapper objectMapper,
                                   IndexPriceAuditService auditService,
                                   IndexPriceProperties properties) {
        this.objectMapper = objectMapper;
        this.auditService = auditService;
        this.properties = properties;
    }

    @KafkaListener(
            topics = "#{__listener.priceEventsTopic()}",
            groupId = "#{__listener.groupId()}",
            containerFactory = "indexAuditKafkaListenerContainerFactory")
    public void onAudit(List<ConsumerRecord<String, String>> records) {
        try {
            List<IndexPriceEvent> events = new ArrayList<>(records.size());
            for (ConsumerRecord<String, String> record : records) {
                PricePublishedEvent publication = objectMapper.readValue(record.value(), PricePublishedEvent.class);
                if (publication.eventType() != PriceEventType.INDEX_PRICE) {
                    continue;
                }
                IndexPriceEvent event = publication.indexPrice();
                if (record.key() == null || !record.key().equals(publication.symbol())
                        || !record.key().equals(event.symbol())) {
                    throw new IllegalArgumentException("index price audit Kafka key must match payload symbol");
                }
                events.add(event);
            }
            auditService.saveBatch(events);
        } catch (Exception ex) {
            log.error("Failed to persist index-price audit batch size={}: {}", records.size(), ex.getMessage(), ex);
            throw new IllegalStateException("failed to persist index price audit", ex);
        }
    }

    public String priceEventsTopic() {
        return properties.getKafka().getPriceEventsTopic();
    }

    public String groupId() {
        return properties.getKafka().getGroupId() + "-audit-writer";
    }
}
