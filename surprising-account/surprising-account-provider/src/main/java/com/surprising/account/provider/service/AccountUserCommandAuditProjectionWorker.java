package com.surprising.account.provider.service;

import com.surprising.account.api.model.AccountCommandStatus;
import com.surprising.account.api.model.AccountUserCommand;
import com.surprising.account.provider.model.AccountCommandTerminalResult;
import com.surprising.account.provider.repository.AccountCommandRepository;
import com.surprising.account.provider.config.AccountProperties;
import com.surprising.eventstore.UserPartitionCommandLane;
import com.surprising.eventstore.UserPartitionEvent;
import com.surprising.eventstore.UserPartitionKey;
import com.surprising.eventstore.UserPartitionResultStore;
import com.surprising.eventstore.UserPartitionWal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * 账户事实流的数据库审计投影器。
 *
 * <p>该组件只登记原始命令和已由本地 reducer 保存的终态，不调用任何余额、持仓或保证金服务。
 * 因此数据库落后或不可用不会影响用户分区事实裁决；事实流和本地状态库才是恢复依据。</p>
 */
@Service
public class AccountUserCommandAuditProjectionWorker {

    private static final Logger log = LoggerFactory.getLogger(AccountUserCommandAuditProjectionWorker.class);

    private final ObjectMapper objectMapper;
    private final AccountProperties properties;
    private final UserPartitionWal wal;
    private final UserPartitionResultStore resultStore;
    private final UserPartitionCommandLane lane;
    private final AccountCommandRepository commandRepository;
    private final AccountLedgerProjectionService ledgerProjectionService;

    public AccountUserCommandAuditProjectionWorker(ObjectMapper objectMapper,
                                                    AccountProperties properties,
                                                    UserPartitionWal wal,
                                                    UserPartitionResultStore resultStore,
                                                    UserPartitionCommandLane lane,
                                                    AccountCommandRepository commandRepository,
                                                    AccountLedgerProjectionService ledgerProjectionService) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.wal = wal;
        this.resultStore = resultStore;
        this.lane = lane;
        this.commandRepository = commandRepository;
        this.ledgerProjectionService = ledgerProjectionService;
    }

    @Scheduled(fixedDelayString = "${surprising.account.wal.projection-delay-ms:25}")
    public void projectAudit() {
        for (UserPartitionKey partition : wal.partitions()) {
            try {
                lane.execute(partition, () -> projectPartition(partition));
            } catch (RuntimeException ex) {
                log.warn("账户审计投影失败 partition={}", partition.value(), ex);
            }
        }
    }

    private Void projectPartition(UserPartitionKey partition) {
        long projected = wal.lastProjectedSequence(partition);
        List<UserPartitionEvent> events = wal.replay(partition);
        for (UserPartitionEvent event : events) {
            AccountUserCommand command = decode(event);
            String serialized = new String(event.payload(), StandardCharsets.UTF_8);
            if (event.sequence() <= projected) {
                continue;
            }
            Optional<AccountCommandTerminalResult> localTerminal = terminal(command.commandId());
            if (event.sequence() != projected + 1L) {
                throw new IllegalStateException("账户审计投影序号断裂 partition=" + partition.value());
            }
            commandRepository.projectCommand(command, serialized, Instant.now());
            if (localTerminal.isEmpty()) {
                // 本地 reducer 尚未写入终态时不推进审计水位，避免数据库只留下永久 PROCESSING。
                return null;
            }
            AccountCommandTerminalResult result = localTerminal.get();
            if (result.status() == AccountCommandStatus.APPLIED) {
                commandRepository.markApplied(command.commandId(), result.resultPayload(), Instant.now());
            } else {
                commandRepository.markRejected(command.commandId(), result.resultPayload(), result.errorCode(),
                        result.errorMessage(), Instant.now());
            }
            wal.markProjected(partition, event.sequence());
            projected = event.sequence();
        }
        projectLedgerPartition(partition, events);
        return null;
    }

    /** 账本使用独立投影水位，避免审计水位推进后每轮重复扫描数据库。 */
    private void projectLedgerPartition(UserPartitionKey partition, List<UserPartitionEvent> events) {
        long projected = wal.lastLedgerProjectedSequence(partition);
        for (UserPartitionEvent event : events) {
            if (event.sequence() <= projected) {
                continue;
            }
            if (event.sequence() != projected + 1L) {
                throw new IllegalStateException("账户账本投影序号断裂 partition=" + partition.value());
            }
            AccountUserCommand command = decode(event);
            Optional<AccountCommandTerminalResult> terminal = terminal(command.commandId());
            if (terminal.isEmpty()) {
                // 终态尚未写入结果库时，账本不能越过当前命令；下一轮继续重试。
                return;
            }
            ledgerProjectionService.project(command, terminal.get(), Instant.now());
            wal.markLedgerProjected(partition, event.sequence());
            projected = event.sequence();
        }
    }

    private Optional<AccountCommandTerminalResult> terminal(String commandId) {
        return resultStore.read(commandId).map(bytes -> {
            try {
                return objectMapper.readValue(new String(bytes, StandardCharsets.UTF_8),
                        AccountCommandTerminalResult.class);
            } catch (Exception ex) {
                throw new IllegalStateException("账户命令结果损坏 commandId=" + commandId, ex);
            }
        });
    }

    private AccountUserCommand decode(UserPartitionEvent event) {
        try {
            return objectMapper.readValue(new String(event.payload(), StandardCharsets.UTF_8),
                    AccountUserCommand.class);
        } catch (Exception ex) {
            throw new AccountCommandPoisonPillException("账户审计命令无法解析 commandId=" + event.eventId(), ex);
        }
    }
}
