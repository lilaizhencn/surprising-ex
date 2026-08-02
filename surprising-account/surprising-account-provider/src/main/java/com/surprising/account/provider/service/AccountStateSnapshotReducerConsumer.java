package com.surprising.account.provider.service;

import com.surprising.account.api.model.PerpetualAccountStateUpdatedEvent;
import com.surprising.account.provider.config.AccountProperties;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * 消费账户完整快照，初始化本账户进程的用户 reducer 状态。
 *
 * <p>快照只用于启动恢复和明确初始化，不参与命令热路径的数据库回查；后续用户命令由 WAL
 * 顺序事件推进状态。</p>
 */
@Service
@ConditionalOnExpression("'${surprising.account.kafka.product-line:LINEAR_PERPETUAL}' == 'LINEAR_PERPETUAL'")
public class AccountStateSnapshotReducerConsumer {

    private final ObjectMapper objectMapper;
    private final AccountProperties properties;
    private final AccountUserStateReducer reducer;

    public AccountStateSnapshotReducerConsumer(ObjectMapper objectMapper,
                                               AccountProperties properties,
                                               AccountUserStateReducer reducer) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.reducer = reducer;
    }

    @KafkaListener(
            topics = "#{__listener.topic()}",
            groupId = "#{__listener.groupId()}",
            containerFactory = "accountKafkaListenerContainerFactory")
    public void onSnapshot(ConsumerRecord<String, String> record) {
        try {
            if (!topic().equals(record.topic())) {
                throw new IllegalArgumentException("账户 reducer 快照 Topic 不匹配");
            }
            PerpetualAccountStateUpdatedEvent event = objectMapper.readValue(
                    record.value(), PerpetualAccountStateUpdatedEvent.class);
            if (event.productLine() != properties.getKafka().getProductLine()
                    || !event.partitionKey().equals(record.key())) {
                throw new IllegalArgumentException("账户 reducer 快照产品线或 Kafka key 不匹配");
            }
            reducer.initialize(event);
        } catch (Exception ex) {
            throw new IllegalStateException("账户 reducer 快照初始化失败", ex);
        }
    }

    public String topic() {
        return properties.getKafka().getAccountStateEventsTopic();
    }

    public String groupId() {
        return properties.getKafka().getProductLine().topicSegment()
                + "-account-reducer-state-v1";
    }
}
