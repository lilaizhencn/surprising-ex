package com.surprising.account.provider.service;

import com.surprising.account.api.model.AccountUserCommand;
import com.surprising.eventstore.UserPartitionKey;
import com.surprising.eventstore.UserPartitionWal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.HexFormat;
import org.springframework.stereotype.Service;

/**
 * 账户命令的唯一事实入口。
 *
 * <p>Kafka 消费线程只负责校验后的命令写入同步 WAL。只有 WAL 成功落盘，调用方才允许提交 Kafka
 * offset；余额、持仓和账本由独立投影器按用户分区顺序处理。</p>
 */
@Service
public class AccountUserCommandWalIngress {

    private final UserPartitionWal wal;

    public AccountUserCommandWalIngress(UserPartitionWal wal) {
        this.wal = wal;
    }

    public List<AppendOutcome> append(List<CommandEnvelope> envelopes) {
        if (envelopes == null || envelopes.isEmpty()) {
            return List.of();
        }
        return envelopes.stream().map(this::appendOne).toList();
    }

    private AppendOutcome appendOne(CommandEnvelope envelope) {
        if (envelope == null || envelope.command() == null
                || envelope.serializedEnvelope() == null || envelope.serializedEnvelope().isBlank()) {
            throw new AccountCommandPoisonPillException("invalid account command batch envelope");
        }
        AccountUserCommand command = envelope.command();
        UserPartitionKey partition = new UserPartitionKey(command.productLine(), command.userId());
        wal.append(partition, command.commandId(), command.commandType().name(),
                envelope.serializedEnvelope().getBytes(StandardCharsets.UTF_8),
                fingerprint(envelope.serializedEnvelope()), command.occurredAt());
        return AppendOutcome.DURABLE;
    }

    private String fingerprint(String serializedEnvelope) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(serializedEnvelope.getBytes(StandardCharsets.UTF_8)));
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
}
