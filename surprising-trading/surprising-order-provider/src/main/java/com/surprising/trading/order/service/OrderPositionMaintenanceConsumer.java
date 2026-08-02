package com.surprising.trading.order.service;

import com.surprising.account.api.model.PositionUpdatedEvent;
import com.surprising.trading.order.config.TradingOrderProperties;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * 只减仓订单维护由订单模块负责。账户模块只发布持久化仓位状态，不更新交易订单表。
 */
@Service
public class OrderPositionMaintenanceConsumer {

    private final ObjectMapper objectMapper;
    private final TradingOrderProperties properties;
    private final OrderService orderService;
    private final OrderMarginSnapshotCache marginSnapshotCache;

    public OrderPositionMaintenanceConsumer(ObjectMapper objectMapper,
                                            TradingOrderProperties properties,
                                            OrderService orderService) {
        this(objectMapper, properties, orderService, null);
    }

    @Autowired
    public OrderPositionMaintenanceConsumer(ObjectMapper objectMapper,
                                            TradingOrderProperties properties,
                                            OrderService orderService,
                                            OrderMarginSnapshotCache marginSnapshotCache) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.orderService = orderService;
        this.marginSnapshotCache = marginSnapshotCache;
    }

    @KafkaListener(
            topics = "#{__listener.topic()}",
            groupId = "#{__listener.groupId()}",
            containerFactory = "orderOpenViewKafkaListenerContainerFactory")
    public void onPositionUpdated(List<ConsumerRecord<String, String>> records) {
        try {
            if (records == null || records.isEmpty()) {
                return;
            }
            List<PositionUpdatedEvent> events = new ArrayList<>(records.size());
            for (ConsumerRecord<String, String> record : records) {
                if (!topic().equals(record.topic())) {
                    throw new IllegalArgumentException("unexpected position event topic " + record.topic());
                }
                PositionUpdatedEvent event = objectMapper.readValue(record.value(), PositionUpdatedEvent.class);
                if (event.productLine() != properties.getKafka().getProductLine()) {
                    throw new IllegalArgumentException("position event product line mismatch");
                }
                if (!event.partitionKey().equals(record.key())) {
                    throw new IllegalArgumentException("position event key must be " + event.partitionKey());
                }
                events.add(event);
            }
            for (PositionUpdatedEvent event : events) {
                if (marginSnapshotCache != null) {
                    OrderMarginSnapshotCache.ApplyResult result = marginSnapshotCache.applyPosition(event);
                    if (result == OrderMarginSnapshotCache.ApplyResult.CONFLICT) {
                        marginSnapshotCache.markNotReady(event.productLine());
                        throw new IllegalStateException("持仓 JVM 快照同一修订号出现不同状态，暂停产品线消费");
                    }
                }
                orderService.onPositionUpdated(event);
            }
        } catch (Exception ex) {
            throw new IllegalStateException("failed to maintain reduce-only orders from position event batch", ex);
        }
    }

    public String topic() {
        return properties.getKafka().getPositionEventsTopic();
    }

    public String groupId() {
        return properties.getKafka().getPositionMaintenanceGroupId();
    }
}
