package com.surprising.adl.provider.service;

import com.surprising.adl.provider.config.AdlProperties;
import com.surprising.risk.api.model.RiskPositionUpdatedEvent;
import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class AdlRiskPositionConsumer {
    private static final Logger log = LoggerFactory.getLogger(AdlRiskPositionConsumer.class);

    private final ObjectMapper objectMapper; private final RedisAdlCandidateIndex index; private final AdlProperties properties;
    public AdlRiskPositionConsumer(ObjectMapper objectMapper, RedisAdlCandidateIndex index, AdlProperties properties) {
        this.objectMapper=objectMapper; this.index=index; this.properties=properties;
    }
    @KafkaListener(topics = "#{__listener.topic()}", groupId = "#{__listener.groupId()}",
            containerFactory = "riskKafkaListenerContainerFactory")
    public void onRiskPosition(List<ConsumerRecord<String, String>> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        for (ConsumerRecord<String, String> record : records) {
            if (!topic().equals(record.topic())) {
                throw new IllegalArgumentException("unexpected ADL risk event topic: " + record.topic());
            }
            RiskPositionUpdatedEvent event;
            try {
                event = objectMapper.readValue(record.value(), RiskPositionUpdatedEvent.class);
            } catch (Exception ex) {
                // Redis ADL 索引是可重建的加速结构。格式错误的历史消息不能阻塞分区，
                // 也不能延迟其后的有效风险更新。
                log.warn("Discarding invalid ADL risk event topic={} partition={} offset={}: {}", record.topic(),
                        record.partition(), record.offset(), ex.getMessage());
                continue;
            }
            if (event.productLine() != properties.getKafka().getProductLine()) {
                continue;
            }
            index.synchronize(event);
        }
    }
    public String topic() { return properties.getKafka().getPositionRiskEventsTopic(); }
    public String groupId() { return properties.getKafka().getGroupId(); }
}
