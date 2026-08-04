package com.surprising.trading.matching.service;

import com.surprising.trading.matching.config.MatchingProperties;
import com.surprising.trading.matching.store.MatchingLocalStateStore;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.common.KafkaException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

/** 撮合本地通知队列发布器；数据库不参与重试和顺序控制。 */
@Service
public class MatchingLocalOutboxPublisher {

    private final MatchingProperties properties;
    private final MatchingLocalStateStore stateStore;
    private final KafkaTemplate<String, String> kafkaTemplate;

    public MatchingLocalOutboxPublisher(MatchingProperties properties,
                                        MatchingLocalStateStore stateStore,
                                        @Qualifier("matchingKafkaTemplate") KafkaTemplate<String, String> kafkaTemplate) {
        this.properties = properties;
        this.stateStore = stateStore;
        this.kafkaTemplate = kafkaTemplate;
    }

    public void publishPending() {
        List<MatchingLocalStateStore.LocalOutboxRecord> records = stateStore.pendingOutbox(
                Math.max(1, properties.getOutbox().getBatchSize()));
        for (MatchingLocalStateStore.LocalOutboxRecord record : records) {
            try {
                kafkaTemplate.send(record.topic(), record.eventKey(), record.payload())
                        .get(properties.getOutbox().getSendTimeout().toMillis(), TimeUnit.MILLISECONDS);
                stateStore.markOutboxPublished(record.sequence());
            } catch (Exception ex) {
                throw new KafkaException("撮合本地通知发布失败 sequence=" + record.sequence()
                        + " topic=" + record.topic(), ex);
            }
        }
    }
}
