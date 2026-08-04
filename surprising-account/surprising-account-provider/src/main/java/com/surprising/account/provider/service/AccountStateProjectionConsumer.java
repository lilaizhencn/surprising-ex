package com.surprising.account.provider.service;

import com.surprising.account.api.model.PerpetualAccountStateUpdatedEvent;
import com.surprising.account.provider.config.AccountProperties;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * 账户状态数据库异步投影消费者。
 *
 * <p>本消费者使用独立 consumer group，不与本地 reducer 初始化或其他 JVM 快照消费者抢占
 * 消息。消费失败会停在当前 Kafka offset，直到投影事务成功，不能跳过资金状态。</p>
 */
@Service
public class AccountStateProjectionConsumer {

    private final ObjectMapper objectMapper;
    private final AccountProperties properties;
    private final AccountStateProjectionService projectionService;

    public AccountStateProjectionConsumer(ObjectMapper objectMapper,
                                          AccountProperties properties,
                                          AccountStateProjectionService projectionService) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.projectionService = projectionService;
    }

    @KafkaListener(
            topics = "#{__listener.topic()}",
            groupId = "#{__listener.groupId()}",
            containerFactory = "accountStateProjectionKafkaListenerContainerFactory")
    public void onSnapshot(ConsumerRecord<String, String> record) {
        try {
            if (!topic().equals(record.topic())) {
                throw new IllegalArgumentException("账户状态投影 Topic 不匹配");
            }
            PerpetualAccountStateUpdatedEvent event = objectMapper.readValue(
                    record.value(), PerpetualAccountStateUpdatedEvent.class);
            if (event.productLine() != properties.getKafka().getProductLine()
                    || !event.partitionKey().equals(record.key())) {
                throw new IllegalArgumentException("账户状态投影产品线或 Kafka key 不匹配");
            }
            projectionService.project(event);
        } catch (Exception ex) {
            throw new IllegalStateException("账户状态数据库投影失败", ex);
        }
    }

    public String topic() {
        return properties.getKafka().getAccountStateEventsTopic();
    }

    public String groupId() {
        return properties.getKafka().getProductLine().topicSegment()
                + "-account-state-projection-v1";
    }
}
