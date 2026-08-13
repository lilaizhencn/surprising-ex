package com.surprising.account.provider.service;

import com.surprising.account.api.model.AccountCommandResultEvent;
import com.surprising.account.api.model.AccountCommandStatus;
import com.surprising.account.api.model.AccountUserCommand;
import com.surprising.account.api.model.AccountUserCommandType;
import com.surprising.account.api.model.ExpiringPositionSettlementAccountCommand;
import com.surprising.account.api.model.PositionMarginAdjustmentRequest;
import com.surprising.account.api.model.PositionUpdatedEvent;
import com.surprising.account.api.model.TradeSideSettlementCommand;
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
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.common.KafkaException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
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

    public void applyPending() {
        for (UserPartitionKey partition : wal.partitions()) {
            try {
                applyPendingPartition(partition);
            } catch (RuntimeException ex) {
                // 恢复任务只记录故障并保留该分区位点，不能影响其他用户继续恢复。
                log.warn("账户事实流恢复任务暂停分区={}", partition.value(), ex);
            }
        }
    }

    /**
     * 命令消费者在确认 Kafka offset 前调用的同步执行入口。
     *
     * <p>定时任务只负责恢复进程崩溃后已经落盘但尚未完成的事实；新命令不能先提交
     * offset 再等待定时任务，否则节点故障时可能只剩 Kafka 已提交位点而没有可迁移的
     * 本地状态。</p>
     */
    public void applyPendingPartition(UserPartitionKey partition) {
        if (partition == null) {
            throw new IllegalArgumentException("账户事实流分区不能为空");
        }
        try {
            if (lane.isOwnerThread(partition)) {
                applyPartition(partition);
            } else {
                lane.execute(partition, () -> applyPartition(partition));
            }
        } catch (RuntimeException ex) {
            // 单个用户故障必须停在原序号，不能让其他用户或后续资金事件越过它。
            log.warn("账户事实流分区执行失败 partition={}", partition.value(), ex);
            throw ex;
        }
    }

    private Void applyPartition(UserPartitionKey partition) {
        long applied = stateStore.lastAppliedSequence(partition);
        List<UserPartitionEvent> events = wal.replay(partition);
        initializeFirstCommandPartition(partition, events);
        ensureInitialized(partition);
        for (UserPartitionEvent event : events) {
            AccountUserCommand command = decode(event, partition);
            AccountCommandTerminalResult existing = readResult(partition, command.commandId()).orElse(null);
            if (event.sequence() <= applied) {
                if (existing == null) {
                    // 旧版本可能在结果落盘前提交了状态，不能凭空生成终态继续运行，必须人工核对。
                    throw new IllegalStateException("账户状态已提交但命令终态缺失 commandId="
                            + command.commandId() + " sequence=" + event.sequence());
                }
                if (!publishedCommands.contains(command.commandId())) {
                    publishStateSnapshot(reducer.state(partition)
                            .orElseThrow(() -> new AccountStateUnavailableException(
                                    "账户状态快照不存在: " + partition.value()))
                            .snapshot());
                }
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
                    AccountUserStateReducer.Reduction recovery = reduceWithDependency(partition, command,
                            event.sequence());
                    if (recovery == null) {
                        throw new IllegalStateException("账户命令依赖结果缺失，无法恢复 commandId="
                                + command.commandId());
                    }
                    AccountUserReducerState before = reducer.state(partition)
                            .orElseThrow(() -> new AccountStateUnavailableException(
                                    "账户状态快照不存在: " + partition.value()));
                    AccountCommandTerminalResult recomputed = toTerminal(command, before, recovery);
                    if (!terminalEquivalent(existing, recomputed)) {
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

            AccountUserReducerState before = reducer.state(partition)
                    .orElseThrow(() -> new AccountStateUnavailableException(
                            "账户状态快照不存在: " + partition.value()));
            AccountUserStateReducer.Reduction reduction = reduceWithDependency(partition, command, event.sequence());
            if (reduction == null) {
                // 依赖命令尚未落盘时，本分区不能越过当前命令。
                break;
            }
            AccountCommandTerminalResult terminal = toTerminal(command, before, reduction);
            // 先保存终态再提交余额和持仓，崩溃后可以重算并补交状态，不会出现不可恢复的中间窗。
            resultStore.put(partition, command.commandId(), serialize(terminal));
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

    boolean terminalEquivalent(AccountCommandTerminalResult left, AccountCommandTerminalResult right) {
        if (left == right) {
            return true;
        }
        if (left == null || right == null
                || left.status() != right.status()
                || !Objects.equals(left.errorCode(), right.errorCode())
                || !Objects.equals(left.errorMessage(), right.errorMessage())
                || !Objects.equals(left.ledgerDeltas(), right.ledgerDeltas())) {
            return false;
        }
        if (Objects.equals(left.resultPayload(), right.resultPayload())) {
            return true;
        }
        if (left.resultPayload() == null || right.resultPayload() == null) {
            return false;
        }
        try {
            return objectMapper.readTree(left.resultPayload()).equals(objectMapper.readTree(right.resultPayload()));
        } catch (Exception ex) {
            return false;
        }
    }

    /**
     * 新用户首条命令可以是订单预占或正式资金入账。两者都从零余额状态开始：预占会得到明确的
     * 余额不足终态，资金调整则由 reducer 按正式命令生成余额、修订号和流水。其他命令仍必须
     * 等待内部 RPC/Kafka 快照初始化，不能用零状态掩盖已有充值、持仓或转账数据。
     */
    private void initializeFirstCommandPartition(UserPartitionKey partition,
                                                 List<UserPartitionEvent> events) {
        if (stateStore.read(partition).isPresent() || events == null || events.isEmpty()) {
            return;
        }
        AccountUserCommand first = decode(events.get(0), partition);
        if (first.commandType() == AccountUserCommandType.ORDER_RESERVE
                || first.commandType() == AccountUserCommandType.BALANCE_ADJUST
                || first.commandType() == AccountUserCommandType.PRODUCT_BALANCE_ADJUST) {
            reducer.initializeEmpty(partition);
        }
    }

    private AccountUserStateReducer.Reduction reduce(AccountUserCommand command, long sequence) {
        AccountUserStateReducer.Reduction reduction;
        try {
            reduction = reducer.reduce(command, sequence);
        } catch (AccountCommandPoisonPillException ex) {
            return reducer.rejectWithoutCommit(command, sequence, "INVALID_COMMAND_PAYLOAD", ex.getMessage());
        }
        if (reduction.status() == AccountUserStateReducer.ApplyStatus.UNSUPPORTED) {
            // 资金命令尚未有本地 reducer 时必须停住用户分区，不能把未执行伪装成拒绝并越过序号。
            throw new IllegalStateException("账户本地 reducer 尚未支持命令 commandId="
                    + command.commandId() + " type=" + command.commandType()
                    + " code=" + reduction.errorCode());
        }
        return reduction;
    }

    /**
     * 统一处理命令依赖，正常执行和崩溃恢复必须得到完全相同的下一状态。
     * 依赖结果尚未落盘时返回 null，让当前用户分区停在原序号。
     */
    private AccountUserStateReducer.Reduction reduceWithDependency(UserPartitionKey partition,
                                                                    AccountUserCommand command,
                                                                    long sequence) {
        AccountCommandTerminalResult dependency = dependencyResult(partition, command);
        if (dependency == null && command.dependsOnCommandId() != null) {
            return null;
        }
        if (dependency != null && dependency.status() == AccountCommandStatus.REJECTED) {
            return reducer.rejectWithoutCommit(command, sequence, "DEPENDENCY_REJECTED",
                    "依赖账户命令已拒绝");
        }
        return reduce(command, sequence);
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

    private AccountCommandTerminalResult dependencyResult(UserPartitionKey partition,
                                                           AccountUserCommand command) {
        if (command.dependsOnCommandId() == null) {
            return null;
        }
        // 依赖命令必须属于同一个用户分区；不能因为全局结果库中存在同名编号而跨用户裁决资金。
        if (wal.readEvent(partition, command.dependsOnCommandId()).isEmpty()) {
            return null;
        }
        return readResult(partition, command.dependsOnCommandId()).orElse(null);
    }

    private Optional<AccountCommandTerminalResult> readResult(UserPartitionKey partition, String commandId) {
        return resultStore.read(partition, commandId).map(bytes -> {
            try {
                return objectMapper.readValue(new String(bytes, StandardCharsets.UTF_8),
                        AccountCommandTerminalResult.class);
            } catch (Exception ex) {
                throw new IllegalStateException("本地账户命令结果损坏 commandId=" + commandId, ex);
            }
        });
    }

    private AccountCommandTerminalResult toTerminal(AccountUserCommand command,
                                                    AccountUserReducerState before,
                                                    AccountUserStateReducer.Reduction reduction) {
        AccountCommandStatus status = switch (reduction.status()) {
            case APPLIED, ALREADY_APPLIED -> AccountCommandStatus.APPLIED;
            case REJECTED -> AccountCommandStatus.REJECTED;
            case UNSUPPORTED -> throw new IllegalStateException("unsupported reducer result");
        };
        return new AccountCommandTerminalResult(status, reduction.resultPayload(), reduction.errorCode(),
                reduction.errorMessage(), ledgerDeltas(command, before, reduction));
    }

    /**
     * 从 reducer 的前后完整快照计算净权益变更，作为数据库账本的异步输入。
     *
     * <p>这里只读取已经在本地单写者中计算好的状态，不重新执行资金规则，也不查询数据库。
     * 预占、释放和持仓保证金在可用/锁定之间移动但不改变净权益，因此不产生普通净变更行；
     * 逐仓保证金调整则保留一条以可用余额为基准的审计行。</p>
     */
    private List<AccountCommandTerminalResult.LedgerDelta> ledgerDeltas(
            AccountUserCommand command,
            AccountUserReducerState before,
            AccountUserStateReducer.Reduction reduction) {
        if (reduction.status() != AccountUserStateReducer.ApplyStatus.APPLIED
                || reduction.nextState() == null) {
            return List.of();
        }
        if (command.commandType() == AccountUserCommandType.TRADE_SIDE_SETTLE) {
            return tradeLedgerDeltas(command, reduction);
        }
        String referenceType = ledgerReferenceType(command);
        String referenceId = ledgerReferenceId(command);
        String symbol = null;
        java.util.Map<String, Long> beforeEquity = equity(before.snapshot());
        java.util.Map<String, Long> afterEquity = equity(reduction.nextState().snapshot());
        java.util.Map<String, Long> beforeAvailable = available(before.snapshot());
        java.util.Map<String, Long> afterAvailable = available(reduction.nextState().snapshot());
        if (command.commandType() == AccountUserCommandType.POSITION_MARGIN_ADJUST) {
            try {
                PositionMarginAdjustmentRequest request = objectMapper.readValue(
                        command.payload(), PositionMarginAdjustmentRequest.class);
                symbol = request.symbol();
            } catch (Exception ex) {
                throw new AccountCommandPoisonPillException("逐仓保证金审计负载无法解析", ex);
            }
        }
        java.util.Set<String> assets = new java.util.TreeSet<>();
        assets.addAll(beforeEquity.keySet());
        assets.addAll(afterEquity.keySet());
        List<AccountCommandTerminalResult.LedgerDelta> deltas = new java.util.ArrayList<>();
        for (String asset : assets) {
            long amount = Math.subtractExact(afterEquity.getOrDefault(asset, 0L),
                    beforeEquity.getOrDefault(asset, 0L));
            long balanceAfter = afterEquity.getOrDefault(asset, 0L);
            if (command.commandType() == AccountUserCommandType.POSITION_MARGIN_ADJUST) {
                amount = Math.subtractExact(afterAvailable.getOrDefault(asset, 0L),
                        beforeAvailable.getOrDefault(asset, 0L));
                balanceAfter = afterAvailable.getOrDefault(asset, 0L);
            }
            if (amount != 0L) {
                deltas.add(new AccountCommandTerminalResult.LedgerDelta(asset, amount, balanceAfter,
                        referenceType, referenceId, command.commandType().name(), symbol));
            }
        }
        return List.copyOf(deltas);
    }

    /**
     * 成交结果已经由 reducer 按确定性规则计算完成，这里只拆分账本明细，不重新计算资金。
     * 这样数据库账本可以分别审计成交本金、已实现盈亏、权利金和手续费，同时保持数据库不参与在线裁决。
     */
    private List<AccountCommandTerminalResult.LedgerDelta> tradeLedgerDeltas(
            AccountUserCommand command,
            AccountUserStateReducer.Reduction reduction) {
        JsonNode detail = readResultPayload(reduction.resultPayload(), "成交账本明细");
        if (detail.path("duplicate").asBoolean(false)) {
            return List.of();
        }
        long tradeId = requiredLong(detail, "tradeId");
        long orderId = requiredLong(detail, "orderId");
        String referenceId = tradeId + ":" + orderId;
        String symbol = requiredText(detail, "symbol");
        java.util.Map<String, Long> afterEquity = equity(reduction.nextState().snapshot());
        List<AccountCommandTerminalResult.LedgerDelta> deltas = new java.util.ArrayList<>();
        if (command.productLine() == com.surprising.product.api.ProductLine.SPOT) {
            String baseAsset = requiredText(detail, "baseAsset");
            String quoteAsset = requiredText(detail, "quoteAsset");
            addLedgerDelta(deltas, baseAsset, detail.path("baseUnits").asLong(),
                    afterEquity, "SPOT_TRADE", referenceId, "SPOT_TRADE", symbol);
            addLedgerDelta(deltas, quoteAsset, detail.path("quotePrincipalUnits").asLong(),
                    afterEquity, "SPOT_TRADE", referenceId, "SPOT_TRADE", symbol);
            addLedgerDelta(deltas, quoteAsset, detail.path("feeUnits").asLong(),
                    afterEquity, "TRADE_FEE", referenceId, "TRADE_FEE", symbol);
            return List.copyOf(deltas);
        }
        String settleAsset = requiredText(detail, "settleAsset");
        addLedgerDelta(deltas, settleAsset, detail.path("realizedPnlUnits").asLong(),
                afterEquity, "TRADE_PNL", referenceId, "TRADE_PNL", symbol);
        if (command.productLine() == com.surprising.product.api.ProductLine.OPTION) {
            addLedgerDelta(deltas, settleAsset, detail.path("premiumUnits").asLong(),
                    afterEquity, "OPTION_PREMIUM", referenceId, "OPTION_PREMIUM", symbol);
        }
        addLedgerDelta(deltas, settleAsset, detail.path("feeUnits").asLong(),
                afterEquity, "TRADE_FEE", referenceId, "TRADE_FEE", symbol);
        return List.copyOf(deltas);
    }

    private void addLedgerDelta(List<AccountCommandTerminalResult.LedgerDelta> deltas,
                                String asset,
                                long amount,
                                java.util.Map<String, Long> afterEquity,
                                String referenceType,
                                String referenceId,
                                String reason,
                                String symbol) {
        if (amount == 0L) {
            return;
        }
        deltas.add(new AccountCommandTerminalResult.LedgerDelta(asset, amount,
                afterEquity.getOrDefault(asset, 0L), referenceType, referenceId, reason, symbol));
    }

    private JsonNode readResultPayload(String payload, String description) {
        if (payload == null || payload.isBlank()) {
            throw new AccountCommandPoisonPillException(description + "为空");
        }
        try {
            return objectMapper.readTree(payload);
        } catch (Exception ex) {
            throw new AccountCommandPoisonPillException(description + "无法解析", ex);
        }
    }

    private long requiredLong(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isNumber() || value.asLong() <= 0L) {
            throw new AccountCommandPoisonPillException("成交账本明细缺少有效" + field);
        }
        return value.asLong();
    }

    private String requiredText(JsonNode node, String field) {
        String value = node.path(field).asString(null);
        if (value == null || value.isBlank()) {
            throw new AccountCommandPoisonPillException("成交账本明细缺少有效" + field);
        }
        return value;
    }

    /** 生命周期流水使用稳定业务引用，便于同一事件重放时按合约、版本和持仓边界幂等。 */
    private String ledgerReferenceId(AccountUserCommand command) {
        if (command.commandType() != AccountUserCommandType.DELIVERY_SETTLE
                && command.commandType() != AccountUserCommandType.OPTION_EXERCISE) {
            return command.commandId();
        }
        try {
            ExpiringPositionSettlementAccountCommand request = objectMapper.readValue(
                    command.payload(), ExpiringPositionSettlementAccountCommand.class);
            return command.commandType().name() + ":" + request.symbol() + ":" + request.instrumentVersion()
                    + ":" + command.userId() + ":" + request.marginMode().name() + ":"
                    + request.positionSide().name();
        } catch (Exception ex) {
            throw new AccountCommandPoisonPillException("生命周期账本引用无法解析", ex);
        }
    }

    private String ledgerReferenceType(AccountUserCommand command) {
        return switch (command.commandType()) {
            case BALANCE_ADJUST -> "BALANCE_ADJUSTMENT";
            case PRODUCT_BALANCE_ADJUST -> "PRODUCT_BALANCE_ADJUSTMENT";
            case FUNDING_SETTLE -> "FUNDING";
            case TRADE_SIDE_SETTLE -> command.productLine() == com.surprising.product.api.ProductLine.OPTION
                    ? "OPTION_PREMIUM" : "TRADE_SETTLEMENT";
            case POSITION_MARGIN_ADJUST -> "POSITION_MARGIN_ADJUSTMENT";
            case DELIVERY_SETTLE -> "DELIVERY_SETTLEMENT";
            case OPTION_EXERCISE -> "OPTION_EXERCISE";
            case ADL_DEFICIT_RESERVE, ADL_TARGET_SETTLE, ADL_DEFICIT_FINALIZE, ADL_DEFICIT_RELEASE -> "ADL";
            case INSURANCE_DEFICIT_RESERVE, INSURANCE_DEFICIT_FINALIZE, INSURANCE_DEFICIT_RELEASE -> "INSURANCE";
            default -> "ACCOUNT_COMMAND";
        };
    }

    /**
     * 账本余额使用净权益，而不是余额表的毛余额。账户出现穿仓时，亏空必须在同一条
     * 业务流水的 balanceAfter 中体现，否则异步账本无法和本地事实流完成守恒核对。
     */
    private java.util.Map<String, Long> equity(
            com.surprising.account.api.model.PerpetualAccountStateUpdatedEvent snapshot) {
        java.util.Map<String, Long> values = new java.util.HashMap<>();
        for (var balance : snapshot.balances()) {
            long gross = Math.addExact(balance.availableUnits(), balance.lockedUnits());
            long deficit = snapshot.deficits().stream()
                    .filter(value -> value.asset().equalsIgnoreCase(balance.asset()))
                    .mapToLong(com.surprising.account.api.model.PerpetualAccountStateUpdatedEvent.Deficit::deficitUnits)
                    .reduce(0L, Math::addExact);
            values.put(balance.asset(), Math.subtractExact(gross, deficit));
        }
        // 亏空资产可能在余额表中没有行，仍要把净权益变化投影到账本。
        for (var deficit : snapshot.deficits()) {
            values.putIfAbsent(deficit.asset(), Math.negateExact(deficit.deficitUnits()));
        }
        return values;
    }

    private java.util.Map<String, Long> available(
            com.surprising.account.api.model.PerpetualAccountStateUpdatedEvent snapshot) {
        java.util.Map<String, Long> values = new java.util.HashMap<>();
        for (var balance : snapshot.balances()) {
            values.put(balance.asset(), balance.availableUnits());
        }
        return values;
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
                result.errorCode(), result.errorMessage(), command.occurredAt(), command.traceId());
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
     * 发布失败时保留本地终态和状态序号，下一轮会按相同 eventId 重试；消费者按修订号幂等。
     */
    private void publishStateSnapshot(com.surprising.account.api.model.PerpetualAccountStateUpdatedEvent snapshot) {
        try {
            kafkaTemplate.send(properties.getKafka().getAccountStateEventsTopic(), snapshot.partitionKey(),
                    objectMapper.writeValueAsString(snapshot)).get(3L, TimeUnit.SECONDS);
            publishPositionSnapshots(snapshot);
        } catch (Exception ex) {
            throw new KafkaException("账户状态快照发布失败 userId=" + snapshot.userId()
                    + " revision=" + snapshot.accountRevision(), ex);
        }
    }

    /**
     * 将已恢复的 canonical 快照重新广播给下游缓存。
     *
     * <p>仅由受限的内部恢复入口调用，不会修改账户余额、修订号或 WAL；下游以账户修订号
     * 幂等处理同一份快照。</p>
     */
    public void publishStateSnapshotForRecovery(
            com.surprising.account.api.model.PerpetualAccountStateUpdatedEvent snapshot) {
        if (snapshot == null) {
            throw new IllegalArgumentException("账户状态恢复快照不能为空");
        }
        publishStateSnapshot(snapshot);
    }

    /**
     * 从同一份账户 canonical 快照派生持仓事件。
     *
     * <p>持仓事件与账户状态快照共用用户分区 key 和账户修订号，不能再从数据库拼装。状态
     * 快照发布成功后才发布持仓事件；任一步失败都会保留本地结果并在下一轮按相同内容重发，
     * 下游按修订号幂等。</p>
     */
    private void publishPositionSnapshots(
            com.surprising.account.api.model.PerpetualAccountStateUpdatedEvent snapshot) throws Exception {
        for (com.surprising.account.api.model.PerpetualAccountStateUpdatedEvent.Position position
                : snapshot.positions()) {
            if (position.instrumentVersion() <= 0L) {
                // 尚未有过有效合约版本的空仓不需要向持仓读模型发布事件。
                continue;
            }
            var margin = snapshot.positionMargins().stream()
                    .filter(value -> value.symbol().equalsIgnoreCase(position.symbol()))
                    .filter(value -> value.marginMode() == position.marginMode())
                    .filter(value -> value.positionSide() == position.positionSide())
                    .findFirst();
            String marginAsset = margin.map(value -> value.asset())
                    .orElseGet(() -> reducer.settleAsset(snapshot.productLine(), position.symbol(),
                            position.instrumentVersion()));
            PositionUpdatedEvent event = new PositionUpdatedEvent(
                    PositionUpdatedEvent.CURRENT_SCHEMA_VERSION,
                    snapshot.eventId(),
                    0L,
                    snapshot.productLine(),
                    snapshot.accountRevision(),
                    snapshot.userId(),
                    position.symbol(),
                    position.instrumentVersion(),
                    position.marginMode(),
                    position.positionSide(),
                    position.signedQuantitySteps(),
                    position.entryPriceTicks(),
                    position.entryValueTicks(),
                    position.realizedPnlUnits(),
                    marginAsset,
                    margin.map(value -> value.marginUnits()).orElse(0L),
                    position.updatedAt(),
                    position.updatedAt(),
                    snapshot.eventTime(),
                    snapshot.traceId());
            kafkaTemplate.send(properties.getKafka().getPositionEventsTopic(), event.partitionKey(),
                    objectMapper.writeValueAsString(event)).get(3L, TimeUnit.SECONDS);
        }
    }
}
