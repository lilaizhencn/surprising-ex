package com.surprising.trading.matching.service;

import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import com.surprising.instrument.api.cache.InstrumentSnapshotSupport;
import com.surprising.product.api.ProductTopicNames;
import com.surprising.trading.matching.config.MatchingProperties;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * 撮合服务消费 Instrument 增量事件并更新本地快照。
 */
@Service("matchingInstrumentSnapshotConsumer")
public class InstrumentSnapshotConsumer {

    private final ObjectMapper objectMapper;
    private final MatchingProperties properties;
    private final InstrumentSnapshotCache snapshotCache;
    private final ExchangeCoreEngine exchangeCoreEngine;

    public InstrumentSnapshotConsumer(ObjectMapper objectMapper,
                                      MatchingProperties properties,
                                      @Qualifier("matchingInstrumentSnapshotCache") InstrumentSnapshotCache snapshotCache,
                                      ExchangeCoreEngine exchangeCoreEngine) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.snapshotCache = snapshotCache;
        this.exchangeCoreEngine = exchangeCoreEngine;
    }

    @KafkaListener(
            topics = "#{__listener.topic()}",
            groupId = "#{__listener.groupId()}",
            containerFactory = "matchingInstrumentSnapshotKafkaListenerContainerFactory")
    public void onInstrumentEvent(ConsumerRecord<String, String> record) {
        try {
            InstrumentSnapshotSupport.consume(objectMapper, record, snapshotCache,
                    properties.getKafka().getProductLine(), "撮合服务");
            exchangeCoreEngine.refreshSymbols();
        } catch (Exception ex) {
            throw new IllegalStateException("撮合合约快照更新失败", ex);
        }
    }

    public String groupId() {
        return "surprising-" + properties.getKafka().getProductLine().topicSegment()
                + "-matching-instrument-snapshot-v1";
    }

    public String topic() {
        return ProductTopicNames.INSTRUMENT_EVENTS_TOPIC;
    }
}
