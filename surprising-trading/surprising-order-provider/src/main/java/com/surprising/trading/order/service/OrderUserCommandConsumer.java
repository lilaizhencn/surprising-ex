package com.surprising.trading.order.service;

import com.surprising.trading.api.model.OrderUserCommand;
import com.surprising.trading.api.model.OrderUserCommandResult;
import com.surprising.trading.order.config.TradingOrderProperties;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.KafkaException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * 订单用户命令的单写入消费者。
 *
 * <p>Kafka 按 {@code productLine:userId} 分区，同一用户在集群中只由当前分区所有者进入
 * 本地 lane。命令处理完成后再发布结果；发布失败会重试同一命令，结果库和 WAL 共同保证
 * 不重复冻结资金或推进成交量。</p>
 */
@Service
public class OrderUserCommandConsumer {

    private final ObjectMapper objectMapper;
    private final TradingOrderProperties properties;
    private final OrderUserStateService stateService;
    private final KafkaTemplate<String, String> kafkaTemplate;

    public OrderUserCommandConsumer(ObjectMapper objectMapper,
                                    TradingOrderProperties properties,
                                    OrderUserStateService stateService,
                                    KafkaTemplate<String, String> kafkaTemplate) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.stateService = stateService;
        this.kafkaTemplate = kafkaTemplate;
    }

    @KafkaListener(
            topics = "#{__listener.topic()}",
            groupId = "#{__listener.groupId()}",
            containerFactory = "orderUserCommandKafkaListenerContainerFactory")
    public void onCommands(List<ConsumerRecord<String, String>> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        for (ConsumerRecord<String, String> record : records) {
            OrderUserCommand command = decode(record);
            OrderUserCommandResult result = stateService.executeUserCommand(command);
            publishResult(result);
        }
    }

    private OrderUserCommand decode(ConsumerRecord<String, String> record) {
        try {
            if (!topic().equals(record.topic())) {
                throw new IllegalArgumentException("订单用户命令 Topic 不匹配");
            }
            OrderUserCommand command = objectMapper.readValue(record.value(), OrderUserCommand.class);
            if (command.productLine() != properties.getKafka().getProductLine()
                    || !command.partitionKey().equals(record.key())) {
                throw new IllegalArgumentException("订单用户命令产品线或 Kafka key 不匹配");
            }
            return command;
        } catch (Exception ex) {
            throw new IllegalStateException("订单用户命令无法解析", ex);
        }
    }

    private void publishResult(OrderUserCommandResult result) {
        try {
            kafkaTemplate.send(properties.getKafka().getOrderUserCommandResultsTopic(), result.partitionKey(),
                    objectMapper.writeValueAsString(result)).get(
                    properties.getEventPublish().getSendTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception ex) {
            throw new KafkaException("订单用户命令结果发送失败: " + result.commandId(), ex);
        }
    }

    public String topic() {
        return properties.getKafka().getOrderUserCommandsTopic();
    }

    public String groupId() {
        return properties.getKafka().getOrderUserCommandGroupId();
    }
}
