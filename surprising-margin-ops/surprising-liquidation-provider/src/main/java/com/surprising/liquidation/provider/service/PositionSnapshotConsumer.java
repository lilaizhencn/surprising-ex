package com.surprising.liquidation.provider.service;

import com.surprising.account.api.cache.PositionSnapshotCache;
import com.surprising.account.api.model.PositionUpdatedEvent;
import com.surprising.liquidation.provider.config.LiquidationProperties;
import java.util.ArrayList;
import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * 强平服务只消费账户持仓事件来维护 JVM 快照，不在此处查询账户数据库。
 * 快照更新成功后才确认 Kafka 批次，重复事件由 revision 幂等过滤。
 */
@Service
public class PositionSnapshotConsumer {

    private static final Logger log = LoggerFactory.getLogger(PositionSnapshotConsumer.class);

    private final ObjectMapper objectMapper;
    private final LiquidationProperties properties;
    private final PositionSnapshotCache snapshotCache;

    public PositionSnapshotConsumer(ObjectMapper objectMapper,
                                    LiquidationProperties properties,
                                    @Qualifier("liquidationPositionSnapshot") PositionSnapshotCache snapshotCache) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.snapshotCache = snapshotCache;
    }

    @KafkaListener(
            topics = "#{__listener.topic()}",
            groupId = "#{__listener.groupId()}",
            containerFactory = "liquidationPositionSnapshotKafkaListenerContainerFactory")
    public void onPositionEvents(List<ConsumerRecord<String, String>> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        try {
            List<PositionUpdatedEvent> events = new ArrayList<>(records.size());
            for (ConsumerRecord<String, String> record : records) {
                requireCurrentTopic(record.topic());
                PositionUpdatedEvent event = objectMapper.readValue(record.value(), PositionUpdatedEvent.class);
                requirePartitionKey(record.key(), event);
                events.add(event);
            }
            for (PositionUpdatedEvent event : events) {
                PositionSnapshotCache.ApplyResult result = snapshotCache.apply(event);
                if (result == PositionSnapshotCache.ApplyResult.PRODUCT_LINE_MISMATCH) {
                    throw new IllegalArgumentException("持仓事件产品线与强平服务不一致");
                }
            }
            log.debug("强平持仓 JVM 快照已应用事件数={}", events.size());
        } catch (Exception ex) {
            log.error("强平持仓 JVM 快照消费失败，批次将重试：{}", ex.getMessage(), ex);
            throw new IllegalStateException("强平持仓 JVM 快照消费失败", ex);
        }
    }

    public String topic() {
        return properties.getKafka().getPositionEventsTopic();
    }

    public String groupId() {
        return properties.getKafka().getPositionSnapshotGroupId();
    }

    private void requireCurrentTopic(String topic) {
        if (properties.getKafka().isProductTopicsEnabled() && !topic().equals(topic)) {
            throw new IllegalArgumentException("持仓事件 Topic 与永续强平服务不一致：expected=" + topic()
                    + " actual=" + topic);
        }
    }

    private void requirePartitionKey(String key, PositionUpdatedEvent event) {
        if (event.productLine() != properties.getKafka().getProductLine()) {
            throw new IllegalArgumentException("持仓事件产品线与强平服务不一致");
        }
        if (!event.partitionKey().equals(key)) {
            throw new IllegalArgumentException("持仓事件 Kafka key 必须为 " + event.partitionKey());
        }
    }
}
