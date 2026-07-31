package com.surprising.funding.provider.service;

import com.surprising.funding.provider.config.FundingProperties;
import com.surprising.instrument.api.InstrumentEventKeys;
import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import com.surprising.instrument.api.model.InstrumentEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** 资金费服务消费合约增量事件并原子更新本地快照。 */
@Service
public class InstrumentSnapshotConsumer {

    private final ObjectMapper objectMapper;
    private final FundingProperties properties;
    private final InstrumentSnapshotCache snapshotCache;

    public InstrumentSnapshotConsumer(ObjectMapper objectMapper,
                                      FundingProperties properties,
                                      InstrumentSnapshotCache snapshotCache) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.snapshotCache = snapshotCache;
    }

    @KafkaListener(topics = "#{__listener.topic()}", groupId = "#{__listener.groupId()}",
            containerFactory = "fundingInstrumentSnapshotKafkaListenerContainerFactory")
    public void onInstrumentEvent(ConsumerRecord<String, String> record) {
        try {
            InstrumentEvent event = objectMapper.readValue(record.value(), InstrumentEvent.class);
            if (!InstrumentEventKeys.matches(record.key(), event)
                    || event.resolvedProductLine() != properties.getKafka().getProductLine()
                    || !snapshotCache.apply(event)) {
                throw new IllegalArgumentException("资金费合约事件产品线、key 或快照不匹配");
            }
        } catch (Exception ex) {
            throw new IllegalStateException("资金费合约快照更新失败", ex);
        }
    }

    public String topic() {
        return properties.getKafka().getInstrumentEventsTopic();
    }

    public String groupId() {
        return properties.getKafka().getInstrumentSnapshotGroupId();
    }
}
