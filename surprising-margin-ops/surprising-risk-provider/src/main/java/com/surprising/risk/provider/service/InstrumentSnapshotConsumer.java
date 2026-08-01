package com.surprising.risk.provider.service;

import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import com.surprising.instrument.api.cache.InstrumentSnapshotSupport;
import com.surprising.product.api.ProductTopicNames;
import com.surprising.risk.provider.config.RiskProperties;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * 风险服务消费 Instrument 增量事件并更新本地快照。
 */
@Service("riskInstrumentSnapshotConsumer")
public class InstrumentSnapshotConsumer {

    private final ObjectMapper objectMapper;
    private final RiskProperties properties;
    private final InstrumentSnapshotCache snapshotCache;

    public InstrumentSnapshotConsumer(ObjectMapper objectMapper,
                                      RiskProperties properties,
                                      @org.springframework.beans.factory.annotation.Qualifier("riskInstrumentSnapshotCache")
                                      InstrumentSnapshotCache snapshotCache) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.snapshotCache = snapshotCache;
    }

    @KafkaListener(
            topics = "#{__listener.topic()}",
            groupId = "#{__listener.groupId()}",
            containerFactory = "riskInstrumentSnapshotKafkaListenerContainerFactory")
    public void onInstrumentEvent(ConsumerRecord<String, String> record) {
        InstrumentSnapshotSupport.consume(objectMapper, record, snapshotCache,
                properties.getKafka().getProductLine(), "风险服务");
    }

    public String groupId() {
        return properties.getKafka().getProductLine().topicSegment()
                + "-risk-instrument-snapshot-v1";
    }

    public String topic() {
        return ProductTopicNames.INSTRUMENT_EVENTS_TOPIC;
    }
}
