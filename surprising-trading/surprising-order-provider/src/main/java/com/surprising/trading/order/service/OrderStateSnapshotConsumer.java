package com.surprising.trading.order.service;

import com.surprising.trading.order.config.TradingOrderProperties;
import com.surprising.trading.order.model.OrderUserStateSnapshot;
import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** 消费按用户键压缩的订单完整快照，恢复分区迁移到当前 JVM 后的本地事实状态。 */
@Service
public class OrderStateSnapshotConsumer {

    private final ObjectMapper objectMapper;
    private final TradingOrderProperties properties;
    private final OrderUserStateService stateService;

    public OrderStateSnapshotConsumer(ObjectMapper objectMapper,
                                      TradingOrderProperties properties,
                                      OrderUserStateService stateService) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.stateService = stateService;
    }

    @KafkaListener(
            topics = "#{__listener.topic()}",
            groupId = "#{__listener.groupId()}",
            containerFactory = "orderStateKafkaListenerContainerFactory")
    public void onSnapshot(List<ConsumerRecord<String, String>> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        try {
            for (ConsumerRecord<String, String> record : records) {
                if (!topic().equals(record.topic())) {
                    throw new IllegalArgumentException("订单完整快照 Topic 不匹配");
                }
                OrderUserStateSnapshot snapshot = objectMapper.readValue(record.value(),
                        OrderUserStateSnapshot.class);
                if (!snapshot.partitionKey().equals(record.key())) {
                    throw new IllegalArgumentException("订单完整快照 Kafka key 必须为 " + snapshot.partitionKey());
                }
                stateService.initializeSnapshot(snapshot);
            }
        } catch (Exception ex) {
            throw new IllegalStateException("订单完整快照初始化失败", ex);
        }
    }

    /** 单条调用仅供单元测试使用，生产监听器始终按批次提交位点。 */
    public void onSnapshot(ConsumerRecord<String, String> record) {
        onSnapshot(List.of(record));
    }

    public String topic() {
        return properties.getKafka().getOrderStateEventsTopic();
    }

    public String groupId() {
        return properties.getKafka().getOrderStateSnapshotGroupId();
    }
}
