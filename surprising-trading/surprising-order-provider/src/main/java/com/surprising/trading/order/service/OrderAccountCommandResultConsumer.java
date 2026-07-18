package com.surprising.trading.order.service;

import com.surprising.account.api.model.AccountCommandResultEvent;
import com.surprising.account.api.model.AccountUserCommand;
import com.surprising.trading.order.config.TradingOrderProperties;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class OrderAccountCommandResultConsumer {

    private final ObjectMapper objectMapper;
    private final TradingOrderProperties properties;
    private final OrderService orderService;

    public OrderAccountCommandResultConsumer(ObjectMapper objectMapper,
                                             TradingOrderProperties properties,
                                             OrderService orderService) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.orderService = orderService;
    }

    @KafkaListener(
            topics = "#{__listener.topic()}",
            groupId = "#{__listener.groupId()}",
            containerFactory = "orderOpenViewKafkaListenerContainerFactory")
    public void onResult(ConsumerRecord<String, String> record) {
        try {
            AccountCommandResultEvent result = objectMapper.readValue(record.value(), AccountCommandResultEvent.class);
            if (!topic().equals(record.topic())) {
                throw new IllegalArgumentException("unexpected account command result topic " + record.topic());
            }
            String expectedKey = AccountUserCommand.partitionKey(result.productLine(), result.userId());
            if (!expectedKey.equals(record.key())) {
                throw new IllegalArgumentException("invalid account command result key");
            }
            if (result.productLine() != properties.getKafka().getProductLine()) {
                throw new IllegalArgumentException("account command result product line mismatch");
            }
            orderService.processAccountCommandResult(result);
        } catch (Exception ex) {
            throw new IllegalStateException("failed to process order account command result", ex);
        }
    }

    public String topic() {
        return properties.getKafka().getAccountCommandResultsTopic();
    }

    public String groupId() {
        return properties.getKafka().getAccountCommandResultsGroupId();
    }
}
