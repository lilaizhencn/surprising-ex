package com.surprising.trading.order.service;

import com.surprising.eventstore.UserStateChangelog;
import com.surprising.eventstore.UserStateChangelogReplay;
import com.surprising.trading.order.config.TradingOrderProperties;
import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class OrderStateChangelogConsumer {

    private final ObjectMapper objectMapper;
    private final TradingOrderProperties properties;
    private final OrderUserStateService stateService;
    private final UserStateChangelogReplay replay = new UserStateChangelogReplay();

    public OrderStateChangelogConsumer(ObjectMapper objectMapper,
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
    public void onChangelog(List<ConsumerRecord<String, String>> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        try {
            for (ConsumerRecord<String, String> record : records) {
                if (!topic().equals(record.topic())) {
                    throw new IllegalArgumentException("订单状态 changelog Topic 不匹配");
                }
                UserStateChangelog changelog = objectMapper.readValue(record.value(), UserStateChangelog.class);
                if (changelog.productLine() != properties.getKafka().getProductLine()
                        || !changelog.partitionKey().equals(record.key())) {
                    throw new IllegalArgumentException("订单状态 changelog 产品线或 Kafka key 不匹配");
                }
                if (replay.observe(changelog) == UserStateChangelogReplay.Decision.APPLY) {
                    stateService.restoreChangelog(changelog);
                }
            }
        } catch (Exception ex) {
            throw new IllegalStateException("订单状态 changelog 恢复失败", ex);
        }
    }

    public void onChangelog(ConsumerRecord<String, String> record) {
        onChangelog(List.of(record));
    }

    public String topic() {
        return properties.getKafka().getUserStateChangelogTopic();
    }

    public String groupId() {
        return properties.getKafka().getProductLine().topicSegment()
                + "-order-state-changelog-v1-" + properties.getKafka().getClientId();
    }
}
