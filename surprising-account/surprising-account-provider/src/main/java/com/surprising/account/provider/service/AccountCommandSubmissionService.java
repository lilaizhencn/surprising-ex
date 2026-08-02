package com.surprising.account.provider.service;

import com.surprising.account.api.model.AccountUserCommand;
import com.surprising.account.provider.config.AccountProperties;
import java.util.concurrent.TimeUnit;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class AccountCommandSubmissionService {

    private final ObjectMapper objectMapper;
    private final AccountProperties properties;
    private final KafkaTemplate<String, String> kafkaTemplate;

    public AccountCommandSubmissionService(ObjectMapper objectMapper,
                                           AccountProperties properties,
                                           KafkaTemplate<String, String> kafkaTemplate) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.kafkaTemplate = kafkaTemplate;
    }

    /** HTTP 命令直接发送到 Kafka；账户消费者成功写入同步 WAL 后才提交 offset。 */
    public void submit(AccountUserCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("account command is required");
        }
        String serialized = objectMapper.writeValueAsString(command);
        try {
            kafkaTemplate.send(properties.getKafka().getUserCommandsTopic(), command.partitionKey(), serialized)
                    .get(properties.getCommandWait().getTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception ex) {
            throw new IllegalStateException("账户命令未能写入 Kafka: " + command.commandId(), ex);
        }
    }
}
