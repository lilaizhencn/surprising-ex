package com.surprising.trading.matching.service;

import com.surprising.instrument.api.InstrumentEventKeys;
import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import com.surprising.instrument.api.model.InstrumentEvent;
import com.surprising.trading.matching.config.MatchingProperties;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * 撮合服务消费 Instrument 增量事件并更新本地快照。
 */
@Service
public class InstrumentSnapshotConsumer {

    private final ObjectMapper objectMapper;
    private final MatchingProperties properties;
    private final InstrumentSnapshotCache snapshotCache;

    public InstrumentSnapshotConsumer(ObjectMapper objectMapper,
                                      MatchingProperties properties,
                                      InstrumentSnapshotCache snapshotCache) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.snapshotCache = snapshotCache;
    }

    @KafkaListener(
            topics = "surprising.instrument.events.v1",
            groupId = "#{__listener.groupId()}",
            containerFactory = "matchingInstrumentSnapshotKafkaListenerContainerFactory")
    public void onInstrumentEvent(ConsumerRecord<String, String> record) {
        try {
            InstrumentEvent event = objectMapper.readValue(record.value(), InstrumentEvent.class);
            if (!InstrumentEventKeys.matches(record.key(), event)
                    || event.resolvedProductLine() != properties.getKafka().getProductLine()) {
                throw new IllegalArgumentException("Instrument 事件产品线或 key 不匹配");
            }
            if (!snapshotCache.apply(event)) {
                throw new IllegalArgumentException("Instrument 事件快照无效");
            }
        } catch (Exception ex) {
            throw new IllegalStateException("撮合合约快照更新失败", ex);
        }
    }

    public String groupId() {
        return "surprising-" + properties.getKafka().getProductLine().topicSegment()
                + "-matching-instrument-snapshot-v1";
    }
}
