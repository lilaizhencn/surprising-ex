package com.surprising.price.index.service;

import com.surprising.instrument.api.InstrumentEventKeys;
import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import com.surprising.instrument.api.model.InstrumentEvent;
import com.surprising.price.index.config.IndexPriceProperties;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * 指数价格服务消费 Instrument 增量事件并刷新本地配置快照。
 */
@Service
public class InstrumentSnapshotConsumer {

    private final ObjectMapper objectMapper;
    private final IndexPriceProperties properties;
    private final InstrumentSnapshotCache snapshotCache;
    private final IndexInstrumentConfigService configService;

    public InstrumentSnapshotConsumer(ObjectMapper objectMapper,
                                      IndexPriceProperties properties,
                                      InstrumentSnapshotCache snapshotCache,
                                      IndexInstrumentConfigService configService) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.snapshotCache = snapshotCache;
        this.configService = configService;
    }

    @KafkaListener(
            topics = "#{__listener.topic()}",
            groupId = "#{__listener.groupId()}",
            containerFactory = "indexInstrumentSnapshotKafkaListenerContainerFactory")
    public void onInstrumentEvent(ConsumerRecord<String, String> record) {
        try {
            InstrumentEvent event = objectMapper.readValue(record.value(), InstrumentEvent.class);
            if (!InstrumentEventKeys.matches(record.key(), event)
                    || event.resolvedProductLine() != properties.getKafka().getProductLine()
                    || !snapshotCache.apply(event)) {
                throw new IllegalArgumentException("Instrument 事件产品线、key 或快照不匹配");
            }
            configService.refresh();
        } catch (Exception ex) {
            throw new IllegalStateException("指数价格合约快照更新失败", ex);
        }
    }

    public String topic() {
        return properties.getKafka().getInstrumentEventsTopic();
    }

    public String groupId() {
        return properties.getKafka().getInstrumentSnapshotGroupId();
    }
}
