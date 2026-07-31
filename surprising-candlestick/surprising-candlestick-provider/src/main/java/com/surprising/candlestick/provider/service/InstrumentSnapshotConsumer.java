package com.surprising.candlestick.provider.service;

import com.surprising.candlestick.provider.config.CandlestickProperties;
import com.surprising.instrument.api.InstrumentEventKeys;
import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import com.surprising.instrument.api.model.InstrumentEvent;
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
            if (!InstrumentEventKeys.matches(record.key(), event)
                    || event.resolvedProductLine() != properties.getKafka().getProductLine()
                    || !snapshotCache.apply(event)) {
                throw new IllegalArgumentException("Instrument 事件产品线、key 或快照不匹配");
            }
            symbolRegistryService.refresh();
        } catch (Exception ex) {
            throw new IllegalStateException("K 线合约快照更新失败", ex);
        }
    }

    public String topic() {
        return properties.getKafka().getInstrumentEventsTopic();
    }

    public String groupId() {
        return properties.getKafka().getInstrumentSnapshotGroupId();
    }
}
