package com.surprising.trading.order.service;

import com.surprising.account.api.model.AccountCommandResultEvent;
import com.surprising.trading.api.model.OrderUserCommand;
import com.surprising.trading.api.model.OrderUserCommandType;
import com.surprising.trading.api.model.OrderUserCommandResult;
import com.surprising.trading.order.config.TradingOrderProperties;
import com.surprising.eventstore.UserMutation;
import com.surprising.eventstore.UserMutationBatch;
import com.surprising.eventstore.UserPartitionCommandLane;
import com.surprising.eventstore.UserPartitionKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.KafkaException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(OrderUserCommandConsumer.class);

    private final ObjectMapper objectMapper;
    private final TradingOrderProperties properties;
    private final OrderUserStateService stateService;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final UserPartitionCommandLane lane;

    public OrderUserCommandConsumer(ObjectMapper objectMapper,
                                    TradingOrderProperties properties,
                                    OrderUserStateService stateService,
                                    @Qualifier("orderKafkaTemplate") KafkaTemplate<String, String> kafkaTemplate) {
        this(objectMapper, properties, stateService, kafkaTemplate, null);
    }

    @Autowired
    public OrderUserCommandConsumer(ObjectMapper objectMapper,
                                    TradingOrderProperties properties,
                                    OrderUserStateService stateService,
                                    @Qualifier("orderKafkaTemplate") KafkaTemplate<String, String> kafkaTemplate,
                                    UserPartitionCommandLane lane) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.stateService = stateService;
        this.kafkaTemplate = kafkaTemplate;
        this.lane = lane;
    }

    @KafkaListener(
            topics = "#{__listener.topic()}",
            groupId = "#{__listener.groupId()}",
            containerFactory = "orderUserCommandKafkaListenerContainerFactory")
    public void onCommands(List<ConsumerRecord<String, String>> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        List<OrderUserCommand> commands = new ArrayList<>(records.size());
        for (ConsumerRecord<String, String> record : records) {
            try {
                commands.add(decode(record));
            } catch (RuntimeException ex) {
                log.error("订单用户命令解析失败 topic={} partition={} offset={} key={}",
                        record.topic(), record.partition(), record.offset(), record.key(), ex);
                throw ex;
            }
        }
        UserMutationBatch mutationBatch = new UserMutationBatch(commands.stream().map(this::toUserMutation).toList());
        Map<String, ConsumerRecord<String, String>> recordsByCommandId = new java.util.LinkedHashMap<>();
        Map<String, OrderUserCommand> commandsById = new java.util.LinkedHashMap<>();
        for (int index = 0; index < records.size(); index++) {
            recordsByCommandId.put(commands.get(index).commandId(), records.get(index));
            commandsById.put(commands.get(index).commandId(), commands.get(index));
        }
        List<ProcessedCommand> processed = new ArrayList<>(commands.size());
        for (Map.Entry<UserPartitionKey, List<UserMutation>> entry : mutationBatch.byPartition().entrySet()) {
            Runnable processPartition = () -> {
                for (UserMutation mutation : entry.getValue()) {
                    String commandId = mutation.commandId();
                    processCommand(commandsById.get(commandId), recordsByCommandId.get(commandId), processed);
                }
            };
            if (lane == null) {
                processPartition.run();
            } else {
                lane.runAsOwner(entry.getKey(), () -> {
                    processPartition.run();
                    return null;
                });
            }
        }
        for (ProcessedCommand command : processed) {
            publishResult(command.result());
        }
    }

    private void processCommand(OrderUserCommand command,
                                ConsumerRecord<String, String> record,
                                List<ProcessedCommand> processed) {
        try {
            OrderUserCommandResult result = stateService.executeUserCommand(command);
            processed.add(new ProcessedCommand(command.commandId(), result));
        } catch (RuntimeException ex) {
            log.error("订单用户命令处理失败 topic={} partition={} offset={} key={} commandId={}",
                    record.topic(), record.partition(), record.offset(), record.key(), command.commandId(), ex);
            throw ex;
        }
    }

    private UserMutation toUserMutation(OrderUserCommand command) {
        String source = "ORDER";
        String sourceReference = command.commandId();
        String dependency = null;
        if (command.commandType() == OrderUserCommandType.ACCOUNT_RESULT) {
            try {
                AccountCommandResultEvent result = objectMapper.readValue(command.payload(),
                        AccountCommandResultEvent.class);
                source = "ACCOUNT";
                sourceReference = result.sourceReference() == null || result.sourceReference().isBlank()
                        ? command.commandId() : result.sourceReference();
                dependency = result.commandId();
            } catch (Exception ex) {
                throw new IllegalStateException("账户结果 mutation 负载无法解析", ex);
            }
        }
        return new UserMutation(UserMutation.CURRENT_SCHEMA_VERSION, command.commandId(), command.productLine(),
                command.userId(), command.commandType().name(), source, sourceReference, dependency,
                command.payload(), command.occurredAt(), command.traceId());
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
            java.util.concurrent.CompletableFuture<?> send = kafkaTemplate.send(
                    properties.getKafka().getOrderUserCommandResultsTopic(), result.partitionKey(),
                    objectMapper.writeValueAsString(result));
            if (!(kafkaTemplate.isTransactional() && kafkaTemplate.inTransaction())) {
                send.get(properties.getEventPublish().getSendTimeout().toMillis(), TimeUnit.MILLISECONDS);
            }
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

    private record ProcessedCommand(String commandId, OrderUserCommandResult result) {
    }
}
