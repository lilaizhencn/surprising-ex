package com.surprising.adl.provider.service;

import com.surprising.adl.provider.config.AdlProperties;
import com.surprising.risk.api.model.RiskPositionUpdatedEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class AdlRiskPositionConsumer {
    private final ObjectMapper objectMapper; private final RedisAdlCandidateIndex index; private final AdlProperties properties;
    public AdlRiskPositionConsumer(ObjectMapper objectMapper, RedisAdlCandidateIndex index, AdlProperties properties) {
        this.objectMapper=objectMapper; this.index=index; this.properties=properties;
    }
    @KafkaListener(topics = "#{__listener.topic()}", groupId = "#{__listener.groupId()}",
            containerFactory = "riskKafkaListenerContainerFactory")
    public void onRiskPosition(ConsumerRecord<String,String> record) throws Exception {
        index.synchronize(objectMapper.readValue(record.value(), RiskPositionUpdatedEvent.class));
    }
    public String topic() { return properties.getKafka().getPositionRiskEventsTopic(); }
    public String groupId() { return properties.getKafka().getGroupId(); }
}
