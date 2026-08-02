package com.surprising.account.provider.service;

import com.surprising.account.api.model.AccountUserCommand;
import com.surprising.eventstore.UserPartitionEvent;
import com.surprising.eventstore.UserPartitionCommandLane;
import com.surprising.eventstore.UserPartitionKey;
import com.surprising.eventstore.UserPartitionWal;
import com.surprising.account.provider.config.AccountProperties;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * 按用户分区顺序把 WAL 事实投影到现有账户状态和审计表。
 *
 * <p>投影成功返回后才推进 WAL 水位。数据库事务回滚、进程重启或依赖尚未完成时，事件保持未投影状态，
 * 下一轮从同一序号重试，不会跳过事件，也不会因为重复执行而绕过账户命令幂等校验。</p>
 */
@Service
public class AccountUserCommandWalProjectionWorker {

    private static final Logger log = LoggerFactory.getLogger(AccountUserCommandWalProjectionWorker.class);

    private final ObjectMapper objectMapper;
    private final AccountProperties properties;
    private final UserPartitionWal wal;
    private final UserPartitionCommandLane lane;
    private final AccountUserCommandProcessor processor;

    public AccountUserCommandWalProjectionWorker(ObjectMapper objectMapper,
                                                  AccountProperties properties,
                                                  UserPartitionWal wal,
                                                  UserPartitionCommandLane lane,
                                                  AccountUserCommandProcessor processor) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.wal = wal;
        this.lane = lane;
        this.processor = processor;
    }

    @Scheduled(fixedDelayString = "${surprising.account.wal.projection-delay-ms:25}")
    public void projectPending() {
        for (UserPartitionKey partition : wal.partitions()) {
            try {
                lane.execute(partition, () -> projectPartition(partition));
            } catch (RuntimeException ex) {
                // 单个用户分区故障不能阻塞其他用户；当前分区不推进水位，下一轮继续重试。
                log.warn("账户 WAL 分区投影失败 partition={}", partition.value(), ex);
            }
        }
    }

    private Void projectPartition(UserPartitionKey partition) {
        long projected = wal.lastProjectedSequence(partition);
        int processed = 0;
        List<UserPartitionEvent> events = wal.replay(partition);
        for (UserPartitionEvent event : events) {
            if (event.sequence() <= projected) {
                continue;
            }
            long expected = projected + 1L;
            if (event.sequence() != expected) {
                throw new IllegalStateException("账户 WAL 分区序列不连续 partition=" + partition.value()
                        + " expected=" + expected + " actual=" + event.sequence());
            }
            String serialized = new String(event.payload(), StandardCharsets.UTF_8);
            AccountUserCommand command = decode(event, partition, serialized);
            AccountUserCommandProcessor.ProcessingOutcome outcome = processor.processBatch(List.of(
                    new AccountUserCommandProcessor.CommandEnvelope(command, serialized))).getFirst();
            if (outcome == AccountUserCommandProcessor.ProcessingOutcome.WAITING_DEPENDENCY) {
                // 依赖事件未完成时必须停在当前序号，避免同一用户的后续资金事件越过它。
                break;
            }
            if (outcome == AccountUserCommandProcessor.ProcessingOutcome.DURABLE) {
                throw new IllegalStateException("账户投影器收到未执行的 DURABLE 结果 commandId="
                        + command.commandId());
            }
            wal.markProjected(partition, event.sequence());
            projected = event.sequence();
            processed++;
            if (processed >= properties.getWal().getProjectionBatchSize()) {
                break;
            }
        }
        return null;
    }

    private AccountUserCommand decode(UserPartitionEvent event,
                                      UserPartitionKey partition,
                                      String serialized) {
        try {
            AccountUserCommand command = objectMapper.readValue(serialized, AccountUserCommand.class);
            if (command.productLine() != partition.productLine()
                    || command.userId() != partition.userId()
                    || !command.commandType().name().equals(event.eventType())
                    || !command.commandId().equals(event.eventId())) {
                throw new AccountCommandPoisonPillException(
                        "账户 WAL 事件与用户分区元数据不一致 commandId=" + event.eventId());
            }
            return command;
        } catch (AccountCommandPoisonPillException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new AccountCommandPoisonPillException(
                    "账户 WAL 事件无法反序列化 commandId=" + event.eventId(), ex);
        }
    }
}
