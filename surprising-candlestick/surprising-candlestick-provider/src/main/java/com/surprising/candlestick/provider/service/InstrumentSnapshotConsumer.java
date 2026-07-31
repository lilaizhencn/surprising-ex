package com.surprising.candlestick.provider.service;

import com.surprising.candlestick.provider.config.CandlestickProperties;
import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import com.surprising.instrument.api.cache.InstrumentSnapshotSupport;
import com.surprising.instrument.api.model.InstrumentEvent;
import com.surprising.product.api.ProductTopicNames;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * K 线服务消费 Instrument 增量事件并刷新 symbol 和精度快照。
 */
@Service
public class InstrumentSnapshotConsumer {

    private final ObjectMapper objectMapper;
    private final CandlestickProperties properties;
    private final InstrumentSnapshotCache snapshotCache;
    private final SymbolRegistryService symbolRegistryService;

    public InstrumentSnapshotConsumer(ObjectMapper objectMapper,
                                      CandlestickProperties properties,
                                      InstrumentSnapshotCache snapshotCache,
                                      SymbolRegistryService symbolRegistryService) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.snapshotCache = snapshotCache;
        this.symbolRegistryService = symbolRegistryService;
    }

    @KafkaListener(
            topics = "#{__listener.topic()}",
            groupId = "#{__listener.groupId()}",
            containerFactory = "candlestickInstrumentSnapshotKafkaListenerContainerFactory")
    public void onInstrumentEvent(ConsumerRecord<String, String> record) {
        try {
            InstrumentEvent event = objectMapper.readValue(record.value(), InstrumentEvent.class);
            InstrumentSnapshotSupport.apply(snapshotCache, record.key(), event,
                    properties.getKafka().getProductLine(), "K 线服务");
            symbolRegistryService.refresh();
        } catch (Exception ex) {
            throw new IllegalStateException("K 线合约快照更新失败", ex);
        }
    }

    public String topic() {
        return ProductTopicNames.INSTRUMENT_EVENTS_TOPIC;
    }

    public String groupId() {
        return properties.getKafka().getInstrumentSnapshotGroupId();
    }
}
