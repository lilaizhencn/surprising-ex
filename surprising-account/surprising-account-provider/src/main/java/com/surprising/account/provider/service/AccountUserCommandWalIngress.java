package com.surprising.account.provider.service;

import com.surprising.account.api.model.AccountUserCommand;
import com.surprising.eventstore.UserPartitionKey;
import com.surprising.eventstore.UserPartitionCommandLane;
import com.surprising.eventstore.UserPartitionWal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.HexFormat;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 账户命令的唯一事实入口。
 *
 * <p>Kafka 消费线程只负责校验后的命令写入同步 WAL。只有 WAL 成功落盘，调用方才允许提交 Kafka
 * offset；余额、持仓和账本由独立投影器按用户分区顺序处理。</p>
 */
@Service
public class AccountUserCommandWalIngress {

    private final UserPartitionWal wal;
    private final UserPartitionCommandLane lane;

    public AccountUserCommandWalIngress(UserPartitionWal wal) {
        this.wal = wal;
        this.lane = null;
    }

    @Autowired
    public AccountUserCommandWalIngress(UserPartitionWal wal, UserPartitionCommandLane lane) {
        this.wal = wal;
        this.lane = lane;
    }

    public List<AppendOutcome> append(List<CommandEnvelope> envelopes) {
        if (envelopes == null || envelopes.isEmpty()) {
            return List.of();
        }
        Map<UserPartitionKey, List<IndexedEnvelope>> byPartition = new LinkedHashMap<>();
        for (int index = 0; index < envelopes.size(); index++) {
            CommandEnvelope envelope = envelopes.get(index);
            validateEnvelope(envelope);
            UserPartitionKey partition = new UserPartitionKey(envelope.command().productLine(), envelope.command().userId());
            byPartition.computeIfAbsent(partition, ignored -> new ArrayList<>())
                    .add(new IndexedEnvelope(index, envelope));
        }
        List<AppendOutcome> outcomes = new ArrayList<>(envelopes.size());
        for (int index = 0; index < envelopes.size(); index++) {
            outcomes.add(null);
        }
        for (Map.Entry<UserPartitionKey, List<IndexedEnvelope>> entry : byPartition.entrySet()) {
            List<IndexedEnvelope> values = entry.getValue();
            appendPartition(entry.getKey(), values);
            for (IndexedEnvelope value : values) {
                outcomes.set(value.index(), AppendOutcome.DURABLE);
            }
        }
        return List.copyOf(outcomes);
    }

    private void appendPartition(UserPartitionKey partition, List<IndexedEnvelope> values) {
        Runnable append = () -> {
            boolean duplicateCommand = values.stream().map(value -> value.envelope().command().commandId())
                    .distinct().count() != values.size();
            if (duplicateCommand) {
                for (IndexedEnvelope value : values) {
                    UserPartitionWal.AppendRequest request = toRequest(value.envelope());
                    wal.append(partition, request.eventId(), request.eventType(), request.payload(),
                            request.fingerprint(), request.occurredAt());
                }
            } else {
                List<UserPartitionWal.AppendRequest> requests = values.stream()
                        .map(value -> toRequest(value.envelope()))
                        .toList();
                wal.appendBatch(partition, requests);
            }
        };
        if (lane == null) {
            append.run();
        } else {
            lane.runAsOwner(partition, () -> {
                append.run();
                return null;
            });
        }
    }

    private UserPartitionWal.AppendRequest toRequest(CommandEnvelope envelope) {
        AccountUserCommand command = envelope.command();
        return new UserPartitionWal.AppendRequest(command.commandId(), command.commandType().name(),
                envelope.serializedEnvelope().getBytes(StandardCharsets.UTF_8), fingerprint(command),
                command.occurredAt());
    }

    private void validateEnvelope(CommandEnvelope envelope) {
        if (envelope == null || envelope.command() == null
                || envelope.serializedEnvelope() == null || envelope.serializedEnvelope().isBlank()) {
            throw new AccountCommandPoisonPillException("invalid account command batch envelope");
        }
    }

    /**
     * 命令编号是客户端重试的幂等键，发生时间和链路追踪号不是业务意图的一部分。
     * 网关在 503/超时后重试时允许这两个字段变化，否则同一笔资金命令会被 WAL
     * 错误识别为幂等冲突并阻塞该用户分区后续所有命令。
     */
    private String fingerprint(AccountUserCommand command) {
        try {
            String canonical = String.join("\u0000",
                    Integer.toString(command.schemaVersion()),
                    command.commandId(),
                    command.productLine().name(),
                    Long.toString(command.userId()),
                    command.commandType().name(),
                    command.source(),
                    command.sourceReference(),
                    command.dependsOnCommandId() == null ? "" : command.dependsOnCommandId(),
                    command.payload());
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }

    /** Kafka 命令已经同步写入本地事实流后的结果。 */
    public enum AppendOutcome {
        DURABLE
    }

    /** 账户 Kafka 消费批次中的原始命令和固定序列化内容。 */
    public record CommandEnvelope(AccountUserCommand command, String serializedEnvelope) {
    }

    private record IndexedEnvelope(int index, CommandEnvelope envelope) {
    }
}
