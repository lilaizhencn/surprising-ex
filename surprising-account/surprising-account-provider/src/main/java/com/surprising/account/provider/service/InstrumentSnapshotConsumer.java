package com.surprising.account.provider.service;

import com.surprising.account.provider.config.AccountProperties;
import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import com.surprising.instrument.api.cache.InstrumentSnapshotSupport;
import com.surprising.instrument.api.model.InstrumentEvent;
import com.surprising.product.api.ProductTopicNames;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * 账户服务消费 Instrument 增量事件并更新本地快照。
 */
@Service
public class InstrumentSnapshotConsumer {

    private final ObjectMapper objectMapper;
    private final AccountProperties properties;
    private final InstrumentSnapshotCache snapshotCache;

    public InstrumentSnapshotConsumer(ObjectMapper objectMapper,
                                      AccountProperties properties,
                                      InstrumentSnapshotCache snapshotCache) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.snapshotCache = snapshotCache;
    }

    @KafkaListener(
            topics = "#{__listener.topic()}",
            groupId = "#{__listener.groupId()}",
            containerFactory = "accountInstrumentLifecycleKafkaListenerContainerFactory")
    public void onInstrumentEvent(ConsumerRecord<String, String> record) {
        try {
            InstrumentEvent event = objectMapper.readValue(record.value(), InstrumentEvent.class);
            InstrumentSnapshotSupport.apply(snapshotCache, record.key(), event,
                    properties.getKafka().getProductLine(), "账户服务");
        } catch (Exception ex) {
            throw new IllegalStateException("账户合约快照更新失败", ex);
        }
    }

    public String groupId() {
        return properties.getKafka().getProductLine().topicSegment()
                + "-account-instrument-snapshot-v1";
    }

    public String topic() {
        return ProductTopicNames.INSTRUMENT_EVENTS_TOPIC;
    }
}
