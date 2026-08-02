package com.surprising.account.provider.service;

import com.surprising.account.api.model.AccountCommandResultEvent;
import com.surprising.account.api.model.AccountCommandStatus;
import com.surprising.account.api.model.AccountUserCommand;
import com.surprising.account.provider.config.AccountProperties;
import com.surprising.account.provider.model.AccountCommandTerminalResult;
import com.surprising.eventstore.UserPartitionCommandLane;
import com.surprising.eventstore.UserPartitionEvent;
import com.surprising.eventstore.UserPartitionKey;
import com.surprising.eventstore.UserPartitionResultStore;
import com.surprising.eventstore.UserPartitionStateStore;
import com.surprising.eventstore.UserPartitionWal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.common.KafkaException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * 用户账户事实流的唯一状态执行器。
 *
 * <p>它只从本地 WAL 顺序读取命令，由 reducer 在本地状态库中裁决。命令终态先同步写入本地
 * 结果库，再提交账户状态序号，最后发布快照和 Kafka 结果事件；进程在任意位置崩溃都可以
 * 依据结果库重算并补齐状态或重发事件。依赖未完成、序号断裂或快照缺失时分区停止推进。</p>
 */
@Service
public class AccountUserStateCommandWorker {

    private static final Logger log = LoggerFactory.getLogger(AccountUserStateCommandWorker.class);

    private final ObjectMapper objectMapper;
    private final AccountProperties properties;
    private final UserPartitionWal wal;
    private final UserPartitionStateStore stateStore;
    private final UserPartitionResultStore resultStore;
    private final UserPartitionCommandLane lane;
    private final AccountUserStateReducer reducer;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final Set<String> publishedCommands = ConcurrentHashMap.newKeySet();

    public AccountUserStateCommandWorker(ObjectMapper objectMapper,
                                         AccountProperties properties,
                                         UserPartitionWal wal,
                                         UserPartitionStateStore stateStore,
                                         UserPartitionResultStore resultStore,
                                         UserPartitionCommandLane lane,
                                         AccountUserStateReducer reducer,
                                         KafkaTemplate<String, String> kafkaTemplate) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.wal = wal;
        this.stateStore = stateStore;
        this.resultStore = resultStore;
        this.lane = lane;
        this.reducer = reducer;
        this.kafkaTemplate = kafkaTemplate;
    }

    @Scheduled(fixedDelayString = "${surprising.account.wal.projection-delay-ms:25}")
    public void applyPending() {
        for (UserPartitionKey partition : wal.partitions()) {
            try {
                lane.execute(partition, () -> applyPartition(partition));
            } catch (RuntimeException ex) {
                // 单个用户故障必须停在原序号，不能让其他用户或后续资金事件越过它。
                log.warn("账户事实流分区执行失败 partition={}", partition.value(), ex);
            }
        }
    }

    private Void applyPartition(UserPartitionKey partition) {
        ensureInitialized(partition);
        long applied = stateStore.lastAppliedSequence(partition);
        List<UserPartitionEvent> events = wal.replay(partition);
        for (UserPartitionEvent event : events) {
            AccountUserCommand command = decode(event, partition);
            AccountCommandTerminalResult existing = readResult(command.commandId()).orElse(null);
            if (event.sequence() <= applied) {
                if (existing == null) {
                    // 旧版本可能在结果落盘前提交了状态，不能凭空生成终态继续运行，必须人工核对。
                    throw new IllegalStateException("账户状态已提交但命令终态缺失 commandId="
                            + command.commandId() + " sequence=" + event.sequence());
                }
                publishStateSnapshot(reducer.state(partition)
                        .orElseThrow(() -> new AccountStateUnavailableException(
                                "账户状态快照不存在: " + partition.value()))
                        .snapshot());
                publishOnce(event.sequence(), command, existing);
                continue;
            }
            long expected = applied + 1L;
            if (event.sequence() != expected) {
                throw new IllegalStateException("账户事实流序号断裂 partition=" + partition.value()
                        + " expected=" + expected + " actual=" + event.sequence());
            }
            if (existing != null) {
                long persistedSequence = stateStore.lastAppliedSequence(partition);
                if (persistedSequence > event.sequence()) {
                    throw new IllegalStateException("账户状态序号领先于命令结果 commandId="
                            + command.commandId() + " stateSequence=" + persistedSequence
                            + " eventSequence=" + event.sequence());
                }
                if (persistedSequence < event.sequence()) {
                    // 结果已落盘但状态尚未提交，重算只用于校验，不能相信旧进程留下的任意结果。
                    AccountUserStateReducer.Reduction recovery = reduce(command, event.sequence());
                    AccountCommandTerminalResult recomputed = toTerminal(recovery);
                    if (!existing.equals(recomputed)) {
                        throw new IllegalStateException("账户命令终态重算不一致 commandId="
                                + command.commandId());
                    }
                    reducer.commit(command, event.sequence(), recovery);
                }
                publishStateSnapshot(reducer.state(partition)
                        .orElseThrow(() -> new AccountStateUnavailableException(
                                "账户状态快照不存在: " + partition.value()))
                        .snapshot());
                publishOnce(event.sequence(), command, existing);
                applied = event.sequence();
                continue;
            }

            AccountUserStateReducer.Reduction reduction;
            AccountCommandTerminalResult dependency = dependencyResult(command);
            if (dependency == null && command.dependsOnCommandId() != null) {
                // 依赖命令尚未落盘时，本分区不能越过当前命令。
                break;
            } else if (dependency != null && dependency.status() == AccountCommandStatus.REJECTED) {
                reduction = reducer.rejectWithoutCommit(command, event.sequence(), "DEPENDENCY_REJECTED",
                        "依赖账户命令已拒绝");
            } else {
                reduction = reduce(command, event.sequence());
            }
            AccountCommandTerminalResult terminal = toTerminal(reduction);
            // 先保存终态再提交余额和持仓，崩溃后可以重算并补交状态，不会出现不可恢复的中间窗。
            resultStore.put(command.commandId(), serialize(terminal));
            reducer.commit(command, event.sequence(), reduction);
            publishStateSnapshot(reducer.state(partition)
                    .orElseThrow(() -> new AccountStateUnavailableException(
                            "账户状态快照不存在: " + partition.value()))
                    .snapshot());
            publishOnce(event.sequence(), command, terminal);
            applied = event.sequence();
        }
        return null;
    }

    private AccountUserStateReducer.Reduction reduce(AccountUserCommand command, long sequence) {
        AccountUserStateReducer.Reduction reduction = reducer.reduce(command, sequence);
        if (reduction.status() == AccountUserStateReducer.ApplyStatus.UNSUPPORTED) {
            // 资金命令尚未有本地 reducer 时必须停住用户分区，不能把未执行伪装成拒绝并越过序号。
            throw new IllegalStateException("账户本地 reducer 尚未支持命令 commandId="
                    + command.commandId() + " type=" + command.commandType()
                    + " code=" + reduction.errorCode());
        }
        return reduction;
    }

    private void ensureInitialized(UserPartitionKey partition) {
        if (stateStore.read(partition).isPresent()) {
            return;
        }
        // 账户命令执行器不允许在热路径读取数据库。用户必须先通过账户内部快照初始化入口
        // 写入本地 reducer；缺失快照时停住该用户分区，等待恢复或初始化事件，而不是继续扣款。
        throw new AccountStateUnavailableException("账户 JVM 快照尚未初始化: " + partition.value());
    }

    private AccountUserCommand decode(UserPartitionEvent event, UserPartitionKey partition) {
        try {
            AccountUserCommand command = objectMapper.readValue(
                    new String(event.payload(), StandardCharsets.UTF_8), AccountUserCommand.class);
            if (!command.commandId().equals(event.eventId())
                    || !command.commandType().name().equals(event.eventType())
                    || command.productLine() != partition.productLine()
                    || command.userId() != partition.userId()) {
                throw new AccountCommandPoisonPillException("账户事实流事件元数据不一致");
            }
            return command;
        } catch (AccountCommandPoisonPillException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new AccountCommandPoisonPillException("账户事实流命令无法解析", ex);
        }
    }

    private AccountCommandTerminalResult dependencyResult(AccountUserCommand command) {
        if (command.dependsOnCommandId() == null) {
            return null;
        }
        return readResult(command.dependsOnCommandId()).orElse(null);
    }

    private Optional<AccountCommandTerminalResult> readResult(String commandId) {
        return resultStore.read(commandId).map(bytes -> {
            try {
                return objectMapper.readValue(new String(bytes, StandardCharsets.UTF_8),
                        AccountCommandTerminalResult.class);
            } catch (Exception ex) {
                throw new IllegalStateException("本地账户命令结果损坏 commandId=" + commandId, ex);
            }
        });
    }

    private AccountCommandTerminalResult toTerminal(AccountUserStateReducer.Reduction reduction) {
        AccountCommandStatus status = switch (reduction.status()) {
            case APPLIED, ALREADY_APPLIED -> AccountCommandStatus.APPLIED;
            case REJECTED -> AccountCommandStatus.REJECTED;
            case UNSUPPORTED -> throw new IllegalStateException("unsupported reducer result");
        };
        return new AccountCommandTerminalResult(status, reduction.resultPayload(), reduction.errorCode(),
                reduction.errorMessage());
    }

    private byte[] serialize(AccountCommandTerminalResult result) {
        return objectMapper.writeValueAsString(result).getBytes(StandardCharsets.UTF_8);
    }

    private void publishOnce(long sequence,
                             AccountUserCommand command,
                             AccountCommandTerminalResult result) {
        if (!publishedCommands.add(command.commandId())) {
            return;
        }
        AccountCommandResultEvent event = new AccountCommandResultEvent(
                sequence, command.commandId(), command.productLine(), command.userId(), command.commandType(),
                result.status(), command.source(), command.sourceReference(), result.resultPayload(),
                result.errorCode(), result.errorMessage(), Instant.now(), command.traceId());
        try {
            kafkaTemplate.send(properties.getKafka().getCommandResultsTopic(), command.partitionKey(),
                    objectMapper.writeValueAsString(event)).get(3L, TimeUnit.SECONDS);
        } catch (Exception ex) {
            publishedCommands.remove(command.commandId());
            throw new KafkaException("账户命令结果发布失败 commandId=" + command.commandId(), ex);
        }
    }

    /**
     * 状态快照必须先于账户命令结果发布，其他模块才能按同一修订号更新 JVM 缓存。
     * 发送失败时不保存终态结果，下一轮会按相同 eventId 重试；消费者按修订号幂等。
     */
    private void publishStateSnapshot(com.surprising.account.api.model.PerpetualAccountStateUpdatedEvent snapshot) {
        try {
            kafkaTemplate.send(properties.getKafka().getAccountStateEventsTopic(), snapshot.partitionKey(),
                    objectMapper.writeValueAsString(snapshot)).get(3L, TimeUnit.SECONDS);
        } catch (Exception ex) {
            throw new KafkaException("账户状态快照发布失败 userId=" + snapshot.userId()
                    + " revision=" + snapshot.accountRevision(), ex);
        }
    }
}
