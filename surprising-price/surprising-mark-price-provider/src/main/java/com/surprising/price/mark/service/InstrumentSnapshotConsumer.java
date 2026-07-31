package com.surprising.price.mark.service;

import com.surprising.instrument.api.InstrumentEventKeys;
import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import com.surprising.instrument.api.model.InstrumentEvent;
import com.surprising.price.mark.config.MarkPriceProperties;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * 标记价格服务消费 Instrument 增量事件并更新本地快照。
 */
@Service
public class InstrumentSnapshotConsumer {

    private final ObjectMapper objectMapper;
    private final MarkPriceProperties properties;
    private final InstrumentSnapshotCache snapshotCache;

    public InstrumentSnapshotConsumer(ObjectMapper objectMapper,
                                      MarkPriceProperties properties,
                                      InstrumentSnapshotCache snapshotCache) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.snapshotCache = snapshotCache;
    }

    @KafkaListener(
            topics = "#{__listener.topic()}",
            groupId = "#{__listener.groupId()}",
            containerFactory = "instrumentSnapshotKafkaListenerContainerFactory")
    public void onInstrumentEvent(ConsumerRecord<String, String> record) {
        try {
            InstrumentEvent event = objectMapper.readValue(record.value(), InstrumentEvent.class);
            if (!InstrumentEventKeys.matches(record.key(), event)
                    || event.resolvedProductLine() != properties.getKafka().getProductLine()
                    || !snapshotCache.apply(event)) {
                throw new IllegalArgumentException("Instrument 事件产品线、key 或快照不匹配");
            }
        } catch (Exception ex) {
            throw new IllegalStateException("标记价格合约快照更新失败", ex);
        }
    }

    public String topic() {
        return properties.instrumentEventsTopic();
    }

    public String groupId() {
        return properties.getKafka().getInstrumentSnapshotGroupId();
    }
}
