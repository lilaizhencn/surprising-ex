package com.surprising.price.mark.service;

import com.surprising.price.api.model.MarkPricePublishedEvent;
import com.surprising.price.api.model.PriceEventType;
import com.surprising.price.api.model.PricePublishedEvent;
import com.surprising.price.mark.config.MarkPriceProperties;
import com.surprising.price.mark.model.MarkPriceAuditRecord;
import com.surprising.price.mark.repository.MarkPriceTickRepository;
import java.util.ArrayList;
import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** 异步持久化审计流，实时消费链路不依赖该写入结果。 */
@Component
public class MarkPriceAuditConsumer {

    private static final Logger log = LoggerFactory.getLogger(MarkPriceAuditConsumer.class);

    private final ObjectMapper objectMapper;
    private final MarkPriceTickRepository tickRepository;
    private final MarkPriceProperties properties;

    public MarkPriceAuditConsumer(ObjectMapper objectMapper,
                                  MarkPriceTickRepository tickRepository,
                                  MarkPriceProperties properties) {
        this.objectMapper = objectMapper;
        this.tickRepository = tickRepository;
        this.properties = properties;
    }

    @KafkaListener(
            topics = "#{__listener.priceEventsTopic()}",
            groupId = "#{__listener.groupId()}",
            containerFactory = "markAuditKafkaListenerContainerFactory")
    public void onAudit(List<ConsumerRecord<String, String>> records) {
        List<MarkPriceAuditRecord> auditRecords = new ArrayList<>(records.size());
        for (ConsumerRecord<String, String> record : records) {
            try {
                PricePublishedEvent publication = objectMapper.readValue(record.value(), PricePublishedEvent.class);
                if (publication.eventType() != PriceEventType.MARK_PRICE) {
                    continue;
                }
                MarkPricePublishedEvent event = publication.markPrice();
                if (event == null || event.result() == null || record.key() == null
                        || !record.key().equals(publication.symbol())
                        || !record.key().equals(event.result().symbol())) {
                    throw new IllegalArgumentException("mark price audit Kafka key must match payload symbol");
                }
                auditRecords.add(new MarkPriceAuditRecord(event, record.value()));
            } catch (Exception ex) {
                // 审计存储属于异步链路。永久损坏的消息不能阻塞后续有效记录，
                // 也不能让该 Kafka 分区无限重试。
                log.warn("Discarding invalid mark price audit topic={} partition={} offset={}: {}",
                        record.topic(), record.partition(), record.offset(), ex.getMessage(), ex);
            }
        }
        if (!auditRecords.isEmpty()) {
            tickRepository.saveBatch(auditRecords);
        }
    }

    public String priceEventsTopic() {
        return properties.priceEventsTopic();
    }

    public String groupId() {
        return properties.getKafka().getGroupId() + "-audit-writer";
    }
}
