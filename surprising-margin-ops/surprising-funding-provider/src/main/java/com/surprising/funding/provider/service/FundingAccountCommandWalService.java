package com.surprising.funding.provider.service;

import com.surprising.account.api.model.AccountUserCommand;
import com.surprising.eventstore.UserPartitionEvent;
import com.surprising.eventstore.UserPartitionKey;
import com.surprising.eventstore.UserPartitionStateStore;
import com.surprising.eventstore.UserPartitionWal;
import com.surprising.funding.provider.config.FundingProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.kafka.common.KafkaException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * 资金费账户命令的本地事实队列。
 *
 * <p>资金费支付记录提交成功后先追加用户分区 WAL，再异步发布账户 Kafka 命令。发布游标也
 * 同步写入 RocksDB；进程在发送和游标提交之间崩溃只会产生重复消息，账户 WAL 入口按命令
 * ID 幂等，不能重复扣款。</p>
 */
@Service
public class FundingAccountCommandWalService {

    private final ObjectMapper objectMapper;
    private final FundingProperties properties;
    private final UserPartitionWal wal;
    private final UserPartitionStateStore publishState;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final AtomicBoolean publishing = new AtomicBoolean();

    public FundingAccountCommandWalService(ObjectMapper objectMapper,
                                           FundingProperties properties,
                                           UserPartitionWal wal,
                                           UserPartitionStateStore publishState,
                                           KafkaTemplate<String, Object> kafkaTemplate) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.wal = wal;
        this.publishState = publishState;
        this.kafkaTemplate = kafkaTemplate;
    }

    public void append(java.util.List<AccountUserCommand> commands) {
        if (commands == null || commands.isEmpty()) {
            return;
        }
        for (AccountUserCommand command : commands) {
            append(command);
        }
    }

    public void append(AccountUserCommand command) {
        if (command == null || command.productLine() != properties.getKafka().getProductLine()) {
            throw new IllegalArgumentException("资金费账户命令产品线不匹配");
        }
        String serialized = objectMapper.writeValueAsString(command);
        UserPartitionKey partition = new UserPartitionKey(command.productLine(), command.userId());
        wal.append(partition, command.commandId(), command.commandType().name(),
                serialized.getBytes(StandardCharsets.UTF_8), fingerprint(serialized), command.occurredAt());
    }

    @Scheduled(fixedDelayString = "${surprising.funding.account-command.publish-delay-ms:25}")
    public void publishPending() {
        if (!publishing.compareAndSet(false, true)) {
            return;
        }
        try {
            for (UserPartitionKey partition : wal.partitions()) {
                publishPartition(partition);
            }
        } finally {
            publishing.set(false);
        }
    }

    private void publishPartition(UserPartitionKey partition) {
        long applied = publishState.lastAppliedSequence(partition);
        for (UserPartitionEvent event : wal.replay(partition)) {
            if (event.sequence() <= applied) {
                continue;
            }
            if (event.sequence() != applied + 1L) {
                throw new IllegalStateException("资金费账户命令 WAL 序号断裂: " + partition.value());
            }
            AccountUserCommand command = decode(event, partition);
            try {
                kafkaTemplate.send(properties.getKafka().getUserCommandsTopic(), command.partitionKey(),
                        new String(event.payload(), StandardCharsets.UTF_8))
                        .get(3L, TimeUnit.SECONDS);
            } catch (Exception ex) {
                throw new KafkaException("资金费账户命令发布失败 commandId=" + command.commandId(), ex);
            }
            if (publishState.read(partition).isEmpty()) {
                publishState.initialize(partition, event.payload());
            }
            publishState.apply(partition, event.sequence(), event.payload());
            applied = event.sequence();
        }
    }

    private AccountUserCommand decode(UserPartitionEvent event, UserPartitionKey partition) {
        try {
            AccountUserCommand command = objectMapper.readValue(
                    new String(event.payload(), StandardCharsets.UTF_8), AccountUserCommand.class);
            if (!command.commandId().equals(event.eventId())
                    || command.productLine() != partition.productLine()
                    || command.userId() != partition.userId()
                    || !command.partitionKey().equals(partition.value())) {
                throw new IllegalStateException("资金费账户命令 WAL 元数据不一致");
            }
            return command;
        } catch (Exception ex) {
            throw new IllegalStateException("资金费账户命令 WAL 无法解析: " + event.eventId(), ex);
        }
    }

    private String fingerprint(String payload) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }
}
