package com.surprising.trading.order.service;

import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import com.surprising.instrument.api.cache.InstrumentSnapshotSupport;
import com.surprising.product.api.ProductTopicNames;
import com.surprising.trading.order.config.TradingOrderProperties;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * 订单服务消费 Instrument 增量事件并原子替换本地快照。
 */
@Service("orderInstrumentSnapshotConsumer")
public class InstrumentSnapshotConsumer {

    private final ObjectMapper objectMapper;
    private final TradingOrderProperties properties;
    private final InstrumentSnapshotCache snapshotCache;

    public InstrumentSnapshotConsumer(ObjectMapper objectMapper,
                                      TradingOrderProperties properties,
                                      @Qualifier("orderInstrumentSnapshotCache") InstrumentSnapshotCache snapshotCache) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.snapshotCache = snapshotCache;
    }

    @KafkaListener(
            topics = "#{__listener.topic()}",
            groupId = "#{__listener.groupId()}",
            containerFactory = "orderInstrumentLifecycleKafkaListenerContainerFactory")
    public void onInstrumentEvent(ConsumerRecord<String, String> record) {
        InstrumentSnapshotSupport.consume(objectMapper, record, snapshotCache,
                properties.getKafka().getProductLine(), "订单服务");
    }

    public String topic() {
        return ProductTopicNames.INSTRUMENT_EVENTS_TOPIC;
    }

    public String groupId() {
        return properties.getKafka().getInstrumentSnapshotGroupId();
    }
}
