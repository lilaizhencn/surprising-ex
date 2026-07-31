package com.surprising.account.provider.service;

import com.surprising.account.provider.config.AccountProperties;
import com.surprising.instrument.api.InstrumentEventKeys;
import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import com.surprising.instrument.api.model.InstrumentEvent;
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
            topics = "surprising.instrument.events.v1",
            groupId = "#{__listener.groupId()}",
            containerFactory = "accountInstrumentLifecycleKafkaListenerContainerFactory")
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
            throw new IllegalStateException("账户合约快照更新失败", ex);
        }
    }

    public String groupId() {
        return properties.getKafka().getProductLine().topicSegment()
                + "-account-instrument-snapshot-v1";
    }
}
