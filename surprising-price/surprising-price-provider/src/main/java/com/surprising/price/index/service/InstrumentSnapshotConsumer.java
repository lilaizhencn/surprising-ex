package com.surprising.price.index.service;

import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import com.surprising.instrument.api.cache.InstrumentSnapshotSupport;
import com.surprising.product.api.ProductTopicNames;
import com.surprising.price.index.config.IndexPriceProperties;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * 指数价格服务消费 Instrument 增量事件并刷新本地配置快照。
 */
@Service("indexInstrumentSnapshotConsumer")
public class InstrumentSnapshotConsumer {

    private final ObjectMapper objectMapper;
    private final IndexPriceProperties properties;
    private final InstrumentSnapshotCache snapshotCache;
    private final IndexInstrumentConfigService configService;

    public InstrumentSnapshotConsumer(ObjectMapper objectMapper,
                                      IndexPriceProperties properties,
                                      @Qualifier("indexInstrumentSnapshotCache") InstrumentSnapshotCache snapshotCache,
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
            InstrumentSnapshotSupport.consume(objectMapper, record, snapshotCache,
                    properties.getKafka().getProductLine(), "指数价格服务");
            configService.refresh();
        } catch (Exception ex) {
            throw new IllegalStateException("指数价格合约快照更新失败", ex);
        }
    }

    public String topic() {
        return ProductTopicNames.INSTRUMENT_EVENTS_TOPIC;
    }

    public String groupId() {
        return properties.getKafka().getInstrumentSnapshotGroupId();
    }
}
