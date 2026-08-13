package com.surprising.trading.matching.service;

import com.surprising.trading.matching.config.MatchingProperties;
import com.surprising.trading.matching.store.MatchingLocalStateStore;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
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
        int shardCount = Math.max(1, properties.getEngine().getBookShards());
        for (int shardId = 0; shardId < shardCount; shardId++) {
            long publishingEpoch = stateStore.assignmentEpoch();
            List<MatchingLocalStateStore.LocalOutboxRecord> records = stateStore.pendingOutbox(shardId,
                    Math.max(1, properties.getOutbox().getBatchSize()));
            if (records.isEmpty()) {
                continue;
            }
            List<CompletableFuture<?>> sends = new ArrayList<>(records.size());
            try {
                for (MatchingLocalStateStore.LocalOutboxRecord record : records) {
                    sends.add(kafkaTemplate.send(record.topic(), record.eventKey(), record.payload()));
                }
                CompletableFuture.allOf(sends.toArray(CompletableFuture[]::new))
                        .get(properties.getOutbox().getSendTimeout().toMillis(), TimeUnit.MILLISECONDS);
                stateStore.markOutboxPublished(records, publishingEpoch);
            } catch (Exception ex) {
                throw new KafkaException("撮合本地通知批量发布失败 shardId=" + shardId, ex);
            }
        }
    }
}
