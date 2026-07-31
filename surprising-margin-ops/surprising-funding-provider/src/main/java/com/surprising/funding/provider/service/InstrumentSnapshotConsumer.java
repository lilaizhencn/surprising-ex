package com.surprising.funding.provider.service;

import com.surprising.funding.provider.config.FundingProperties;
import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import com.surprising.instrument.api.cache.InstrumentSnapshotSupport;
import com.surprising.instrument.api.model.InstrumentEvent;
import com.surprising.product.api.ProductTopicNames;
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
            InstrumentSnapshotSupport.apply(snapshotCache, record.key(), event,
                    properties.getKafka().getProductLine(), "资金费服务");
        } catch (Exception ex) {
            throw new IllegalStateException("资金费合约快照更新失败", ex);
        }
    }

    public String topic() {
        return ProductTopicNames.INSTRUMENT_EVENTS_TOPIC;
    }

    public String groupId() {
        return properties.getKafka().getInstrumentSnapshotGroupId();
    }
}
