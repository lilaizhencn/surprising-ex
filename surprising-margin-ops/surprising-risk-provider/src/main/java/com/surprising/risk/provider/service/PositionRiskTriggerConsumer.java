package com.surprising.risk.provider.service;

import com.surprising.account.api.model.PositionUpdatedEvent;
import com.surprising.account.api.cache.PositionSnapshotCache;
import com.surprising.risk.provider.config.RiskProperties;
import com.surprising.product.api.ProductLine;
import java.util.ArrayList;
import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class PositionRiskTriggerConsumer {

    private static final Logger log = LoggerFactory.getLogger(PositionRiskTriggerConsumer.class);

    private final ObjectMapper objectMapper;
    private final RiskService riskService;
    private final RiskProperties properties;
    private final PositionSnapshotCache snapshotCache;

    public PositionRiskTriggerConsumer(ObjectMapper objectMapper, RiskService riskService) {
        this(objectMapper, riskService, new RiskProperties(),
                new PositionSnapshotCache(ProductLine.LINEAR_PERPETUAL));
    }

    public PositionRiskTriggerConsumer(ObjectMapper objectMapper, RiskService riskService, RiskProperties properties) {
        this(objectMapper, riskService, properties,
                new PositionSnapshotCache(properties.getKafka().getProductLine()));
    }

    @Autowired
    public PositionRiskTriggerConsumer(ObjectMapper objectMapper,
                                       RiskService riskService,
                                       RiskProperties properties,
                                       PositionSnapshotCache snapshotCache) {
        this.objectMapper = objectMapper;
        this.riskService = riskService;
        this.properties = properties;
        this.snapshotCache = snapshotCache;
    }

    /**
     * 账户持仓事件按可靠 Kafka 批次消费。RiskService 扫描前按风险组和精确持仓合并每个批次，
     * Kafka 重试与数据库租约共同保证至少一次处理语义。
     */
    @KafkaListener(
            topics = "#{__listener.positionEventsTopic()}",
            groupId = "#{__listener.groupId()}",
            containerFactory = "riskKafkaListenerContainerFactory")
    public void onPositionUpdated(List<ConsumerRecord<String, String>> records) {
        try {
            if (records == null || records.isEmpty()) {
                return;
            }
            List<PositionUpdatedEvent> events = new ArrayList<>(records.size());
            for (ConsumerRecord<String, String> record : records) {
                PositionUpdatedEvent event = objectMapper.readValue(record.value(), PositionUpdatedEvent.class);
                requireCurrentProductTopic(record.topic());
                requireUserPartitionKey(record.key(), event);
                events.add(event);
            }
            riskService.scanPositionUpdates(events);
            for (PositionUpdatedEvent event : events) {
                PositionSnapshotCache.ApplyResult result = snapshotCache.apply(event);
                if (result == PositionSnapshotCache.ApplyResult.PRODUCT_LINE_MISMATCH) {
                    throw new IllegalArgumentException("风险持仓 JVM 快照产品线不一致");
                }
            }
        } catch (Exception ex) {
            int recordCount = records == null ? 0 : records.size();
            log.error("Failed to process position risk trigger batch records={}: {}",
                    recordCount, ex.getMessage(), ex);
            throw new IllegalStateException("failed to process position risk trigger batch", ex);
        }
    }

    public String positionEventsTopic() {
        return properties.getKafka().getPositionEventsTopic();
    }

    public String groupId() {
        return properties.getKafka().getGroupId();
    }

    private void requireCurrentProductTopic(String topic) {
        RiskProperties.Kafka kafka = properties.getKafka();
        if (!kafka.isProductTopicsEnabled()) {
            return;
        }
        String expectedTopic = kafka.getPositionEventsTopic();
        if (!expectedTopic.equals(topic)) {
            throw new ProductTopicMismatchException("position update topic must match current product line: expected="
                    + expectedTopic + " actual=" + topic);
        }
    }

    private void requireUserPartitionKey(String key, PositionUpdatedEvent event) {
        if (event.productLine() != properties.getKafka().getProductLine()) {
            throw new ProductTopicMismatchException("position update product line must match current risk provider");
        }
        if (!event.partitionKey().equals(key)) {
            throw new IllegalArgumentException("position update Kafka key must be " + event.partitionKey());
        }
    }

    private static final class ProductTopicMismatchException extends RuntimeException {
        private ProductTopicMismatchException(String message) {
            super(message);
        }
    }
}
