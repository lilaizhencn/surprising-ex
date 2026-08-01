package com.surprising.trading.order.service;

import com.surprising.account.api.model.OpenInterestShardUpdatedEvent;
import com.surprising.trading.order.config.TradingOrderProperties;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** 消费账户发布的未平仓量分片绝对值，更新订单模块本地快照。 */
@Service
public class OpenInterestSnapshotConsumer {

    private final ObjectMapper objectMapper;
    private final TradingOrderProperties properties;
    private final OpenInterestSnapshotCache cache;

    public OpenInterestSnapshotConsumer(ObjectMapper objectMapper,
                                        TradingOrderProperties properties,
                                        OpenInterestSnapshotCache cache) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.cache = cache;
    }

    @KafkaListener(
            topics = "#{__listener.topic()}",
            groupId = "#{__listener.groupId()}",
            autoStartup = "#{__listener.enabled()}",
            containerFactory = "orderInstrumentLifecycleKafkaListenerContainerFactory")
    public void onOpenInterestEvent(ConsumerRecord<String, String> record) {
        if (!topic().equals(record.topic())) {
            throw new IllegalArgumentException("未平仓量 Topic 不匹配: " + record.topic());
        }
        try {
            OpenInterestShardUpdatedEvent event = objectMapper.readValue(
                    record.value(), OpenInterestShardUpdatedEvent.class);
            if (event.productLine() != properties.getKafka().getProductLine()) {
                throw new IllegalArgumentException("未平仓量事件产品线不匹配: " + event.productLine());
            }
            if (!event.partitionKey().equals(record.key())) {
                throw new IllegalArgumentException("未平仓量事件分区键不匹配");
            }
            cache.apply(event);
        } catch (Exception ex) {
            throw new IllegalArgumentException("未平仓量快照事件解析失败", ex);
        }
    }

    public String topic() {
        return properties.getKafka().getOpenInterestEventsTopic();
    }

    public String groupId() {
        return properties.getKafka().getOpenInterestSnapshotGroupId();
    }

    public boolean enabled() {
        return properties.getKafka().getProductLine().isDerivative();
    }
}
