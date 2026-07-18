package com.surprising.account.provider.service;

import com.surprising.account.api.model.AccountUserCommand;
import com.surprising.account.provider.config.AccountProperties;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class AccountUserCommandConsumer {

    private final ObjectMapper objectMapper;
    private final AccountUserCommandProcessor processor;
    private final AccountProperties properties;
    private final AccountCommandMetrics metrics;

    public AccountUserCommandConsumer(ObjectMapper objectMapper,
                                      AccountUserCommandProcessor processor,
                                      AccountProperties properties,
                                      AccountCommandMetrics metrics) {
        this.objectMapper = objectMapper;
        this.processor = processor;
        this.properties = properties;
        this.metrics = metrics;
    }

    @KafkaListener(
            topics = "#{__listener.topic()}",
            groupId = "#{__listener.groupId()}",
            containerFactory = "accountUserCommandKafkaListenerContainerFactory")
    public void onCommand(ConsumerRecord<String, String> record) {
        long startedAtNanos = System.nanoTime();
        AccountUserCommand command;
        try {
            command = objectMapper.readValue(record.value(), AccountUserCommand.class);
        } catch (Exception ex) {
            metrics.recordFailure(null, startedAtNanos);
            throw new AccountCommandPoisonPillException("invalid account user command envelope", ex);
        }
        try {
            if (!topic().equals(record.topic())) {
                throw new AccountCommandPoisonPillException("account command arrived on unexpected topic "
                        + record.topic());
            }
            if (command.productLine() != properties.getKafka().getProductLine()) {
                throw new AccountCommandPoisonPillException("account command product line does not match provider");
            }
            if (!command.partitionKey().equals(record.key())) {
                throw new AccountCommandPoisonPillException("account command Kafka key must be "
                        + command.partitionKey());
            }
            var outcome = processor.process(command, record.value());
            metrics.record(outcome, command.occurredAt(), startedAtNanos);
        } catch (RuntimeException ex) {
            metrics.recordFailure(command.occurredAt(), startedAtNanos);
            throw ex;
        }
    }

    public String topic() {
        return properties.getKafka().getUserCommandsTopic();
    }

    public String groupId() {
        return properties.getKafka().getUserCommandGroupId();
    }
}
