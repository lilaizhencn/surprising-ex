package com.surprising.account.provider.service;

import com.surprising.account.provider.config.AccountProperties;
import com.surprising.eventstore.UserStateChangelog;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class AccountStateChangelogConsumer {

    private final ObjectMapper objectMapper;
    private final AccountProperties properties;
    private final AccountUserStateReducer reducer;

    public AccountStateChangelogConsumer(ObjectMapper objectMapper,
                                         AccountProperties properties,
                                         AccountUserStateReducer reducer) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.reducer = reducer;
    }

    @KafkaListener(
            topics = "#{__listener.topic()}",
            groupId = "#{__listener.groupId()}",
            containerFactory = "accountStateProjectionKafkaListenerContainerFactory")
    public void onChangelog(ConsumerRecord<String, String> record) {
        try {
            if (!topic().equals(record.topic())) {
                throw new IllegalArgumentException("账户状态 changelog Topic 不匹配");
            }
            UserStateChangelog changelog = objectMapper.readValue(record.value(), UserStateChangelog.class);
            if (changelog.productLine() != properties.getKafka().getProductLine()
                    || !changelog.partitionKey().equals(record.key())) {
                throw new IllegalArgumentException("账户状态 changelog 产品线或 Kafka key 不匹配");
            }
            reducer.restoreChangelog(changelog);
        } catch (Exception ex) {
            throw new IllegalStateException("账户状态 changelog 恢复失败", ex);
        }
    }

    public String topic() {
        return properties.getKafka().getUserStateChangelogTopic();
    }

    public String groupId() {
        return properties.getKafka().getProductLine().topicSegment()
                + "-account-state-changelog-v1-" + properties.getKafka().getClientId();
    }
}
