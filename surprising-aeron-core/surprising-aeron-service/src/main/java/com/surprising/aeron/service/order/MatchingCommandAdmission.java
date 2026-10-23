package com.surprising.aeron.service.order;

import com.surprising.aeron.exception.CoreStateRejectedException;
import com.surprising.aeron.protocol.CommandFingerprint;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.service.core.TradingCoreRuntime;
import com.surprising.aeron.service.order.admission.CoreOrderDecisionResolver;
import com.surprising.aeron.service.order.command.DecodedMatchingCommand;
import com.surprising.aeron.service.order.command.ResolvedMatchingAdmission;
import com.surprising.aeron.service.order.state.OrderRuntime;
import com.surprising.aeron.service.product.ProductTradingRulesRegistry;
import com.surprising.aeron.support.collection.PrimitiveLongChangeSet;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;
import org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap;

/**
 * 交易步骤二：在 Owner 上解码并检查订单身份、路由和生命周期冲突，派发账户准入后登记待撮合命令。普通下单、撤单、改单与强平共用这道入口，资金准入可异步完成，Matcher 仍受准入门闩约束；登记成功返回待处理响应，终态由后面的有序提交负责。
 * 构造约定：保存所属运行时；生命周期索引由 initializeLifecycleScopes 另行初始化，不在构造器内建立。
 * 参数 owner：所属交易 Owner 运行时
 */
@RequiredArgsConstructor
public final class MatchingCommandAdmission {
    /** 唯一所属交易 Owner，业务推进及共享编排状态由其串行访问。 */
    final TradingCoreRuntime owner;

    /** 按在途 Core 序号保留的强平或结算范围；普通订单不在此另存一份范围。 */
    LongObjectHashMap<List<LifecycleScope>> pendingLifecycleScopes;

    /** 当前已冻结而尚未正式登记的触发子单，不是权威订单表。 */
    final List<QueuedTriggerMatching> queuedMatching = new ArrayList<>();

    /** 本轮批次已检查的用户原语列，与品种列同下标。 */
    private long[] batchScopeUsers = new long[8];
    /** 本轮批次已检查的品种列，与用户列组成冲突键。 */
    private String[] batchScopeSymbols = new String[8];
    /** 本轮范围缓冲的有效项数，每次批次校验前归零。 */
    private int batchScopeCount;
    /** 本轮批次校验收集的受影响用户去重缓冲。 */
    private final PrimitiveLongChangeSet batchChangedUsers = new PrimitiveLongChangeSet();
    /** 本轮撤单预算内选择的受影响订单去重缓冲。 */
    private final PrimitiveLongChangeSet batchChangedOrders = new PrimitiveLongChangeSet();

    /** 触发业务释放账户访问权前保存的冻结子单输入，只存活到正式登记命令槽，不拥有账户或订单事实。 构造约定：创建待绑定的触发子单暂存对象，初始引用为空。 */
    @NoArgsConstructor
    static final class QueuedTriggerMatching {
        /**  等待登记的触发子单原命令引用。 借用待正式登记的原命令。 */
        @Accessors(fluent = true, chain = false)
        @Getter
        private CoreMessage command;
        /**  账户已经冻结的子订单引用。 借用已经冻结的子订单，不重新做资金准入。 */
        @Accessors(fluent = true, chain = false)
        @Getter
        private OrderRuntime order;
        /**  产生该子单的源触发单引用。 借用产生该子订单的源触发单。 */
        @Accessors(fluent = true, chain = false)
        @Getter
        private com.surprising.aeron.model.CoreTriggerOrderState trigger;

        /**
         * 直接保存本条触发子单输入，不校验非空或复制订单。
         * @param command 当前原始 Core 命令
         * @param order 已冻结的触发子订单
         * @param trigger 产生子订单的源触发单
         */
        public QueuedTriggerMatching(CoreMessage command, OrderRuntime order,
                com.surprising.aeron.model.CoreTriggerOrderState trigger) {
            set(command, order, trigger);
        }

        /**
         * 绑定当前触发子单的命令、冻结订单和源触发单引用，不创建第二份订单索引。
         * @param command 当前原始 Core 命令
         * @param order 已冻结的触发子订单
         * @param trigger 产生子订单的源触发单
         */
        public void set(CoreMessage command, OrderRuntime order,
                com.surprising.aeron.model.CoreTriggerOrderState trigger) {
            this.command = command;
            this.order = order;
            this.trigger = trigger;
        }

        /**
         * 解除本条子单的三个引用，供下轮复用。
         */
        public void clear() {
            this.command = null;
            this.order = null;
            this.trigger = null;
        }

    }

    /** 前六十四条暂存输入可复用的固定对象数组，超出后仍可另行分配。 */
    private final QueuedTriggerMatching[] queuedMatchingPool = new QueuedTriggerMatching[64];
    /** 本轮池中已经借用的前缀长度，列表清理后归零。 */
    private int queuedPoolIndex;

    /**
     * 优先按游标借用前六十四个可复用暂存对象，超过池长则新建；绑定后追加到待登记列表。池长不是触发子单数量上限。
     * @param command 当前原始 Core 命令
     * @param order 已冻结的触发子订单
     * @param trigger 产生子订单的源触发单
     */
    public QueuedTriggerMatching borrowQueuedMatching(CoreMessage command, OrderRuntime order,
            com.surprising.aeron.model.CoreTriggerOrderState trigger) {
        // 本次借到的触发子单暂存对象
        QueuedTriggerMatching entry;
        if (queuedPoolIndex < queuedMatchingPool.length) {
            entry = queuedMatchingPool[queuedPoolIndex];
            if (entry == null) {
                entry = new QueuedTriggerMatching();
                queuedMatchingPool[queuedPoolIndex] = entry;
            }
            queuedPoolIndex++;
            entry.set(command, order, trigger);
        } else {
            entry = new QueuedTriggerMatching(command, order, trigger);
        }
        queuedMatching.add(entry);
        return entry;
    }

    /**
     * 逐项清除非空暂存对象引用，清列表并把池游标归零；保留池容量。
     */
    public void clearQueuedMatching() {
        for (/* 当前列表或范围缓冲下标 */ int i = 0; i < queuedMatching.size(); i++) {
            // 当前待清理的可空触发子单暂存对象
            QueuedTriggerMatching q = queuedMatching.get(i);
            if (q != null) q.clear();
        }
        queuedMatching.clear();
        queuedPoolIndex = 0;
    }

    /**
     * 逐个把冻结触发子单登记为新的 Core 序号，保存源触发及固定提交边界，再提交 Matcher；正常结束后清列表和池游标。中途异常不提供整批原子撤销。
     * @param clusterTimestamp 固定使用的 Cluster 业务时间
     * @param clusterPosition 固定使用的复制日志位置
     */
    public void appendQueuedMatching(long clusterTimestamp, long clusterPosition) {
        if (queuedMatching.isEmpty()) return;
        for (/* 当前列表或范围缓冲下标 */ int i = 0; i < queuedMatching.size(); i++) {
            // 当前准备正式登记的可空触发子单暂存对象
            QueuedTriggerMatching queued = queuedMatching.get(i);
            if (queued == null) continue;
            // 当前原命令或按操作取出的解码请求，具体类型见声明
            CoreMessage command = queued.command();
            // 本条命令新占或延后保留的 Core 序号
            long sequence = owner.nextAppliedSequence();
            // 当前新借、恢复或参与范围比较的命令槽
            CommandSlot pending = newPendingMatching(sequence, CommandSlot.Operation.TRIGGER, command);
            pending.triggerAdmission(queued.order());
            pending.sourceTrigger(queued.trigger());
            pending.establishCommitFence(clusterTimestamp, clusterPosition);
            owner.matchingFlow.putPendingMatching(pending);
            registerPendingLifecycle(pending);
            owner.recordAppliedSequence(sequence, null, 0L);
            owner.matchingFlow.submitMatching(pending);
            queued.clear();
        }
        queuedMatching.clear();
        queuedPoolIndex = 0;
    }

    /**
     * 从原命令计算指纹，再借用新的待命令槽。
     * @param sequence 当前原命令或新借槽的完整 Core 序号
     * @param operation 当前撮合操作类别
     * @param command 当前原始 Core 命令
     */
    CommandSlot newPendingMatching(long sequence, CommandSlot.Operation operation,
                                   CoreMessage command) {
        return newPendingMatching(sequence, operation, command, owner.fingerprints().of(command));
    }

    /**
     * 使用已有指纹并解码输入，按空前置撤单、空资金增量和空准入借槽。
     * @param sequence 当前原命令或新借槽的完整 Core 序号
     * @param operation 当前撮合操作类别
     * @param command 当前原始 Core 命令
     * @param fingerprint 原命令完整指纹
     */
    CommandSlot newPendingMatching(long sequence, CommandSlot.Operation operation,
                                   CoreMessage command, CommandFingerprint fingerprint) {
        return owner.pendingMatching().acquire(sequence, operation, command, fingerprint, List.of(),
                com.surprising.aeron.service.treasury.RuntimeFundsDelta.empty(),
                owner.decodeMatchingCommand(command), null);
    }

    /**
     * 复用已经解码的输入借槽，前置撤单及资金增量先为空。
     * @param sequence 当前原命令或新借槽的完整 Core 序号
     * @param operation 当前撮合操作类别
     * @param command 当前原始 Core 命令
     * @param fingerprint 原命令完整指纹
     * @param decodedCommand 已经解码的类型化命令输入
     */
    CommandSlot newPendingMatching(long sequence, CommandSlot.Operation operation,
                                   CoreMessage command, CommandFingerprint fingerprint,
                                   DecodedMatchingCommand decodedCommand) {
        return owner.pendingMatching().acquire(sequence, operation, command, fingerprint, List.of(),
                com.surprising.aeron.service.treasury.RuntimeFundsDelta.empty(), decodedCommand, null);
    }

    /**
     * 把已有解码、准入、前置撤单与运行时冷路径资金增量交给命令槽，避免重复解码和搬运。
     * @param sequence 当前原命令或新借槽的完整 Core 序号
     * @param operation 当前撮合操作类别
     * @param command 当前原始 Core 命令
     * @param fingerprint 原命令完整指纹
     * @param preMatchingCancellations 本轮已确定要先执行的撤单编号
     * @param decodedCommand 已经解码的类型化命令输入
     * @param admission 已解析的可空准入结果
     */
    CommandSlot newPendingMatching(long sequence, CommandSlot.Operation operation,
                                   CoreMessage command, CommandFingerprint fingerprint,
                                   List<Long> preMatchingCancellations,
                                   DecodedMatchingCommand decodedCommand,
                                   ResolvedMatchingAdmission admission) {
        return owner.pendingMatching().acquire(sequence, operation, command, fingerprint, preMatchingCancellations,
                owner.runtimeState().coldCommandFunds(),
                decodedCommand, admission);
    }

    /**
     * 确定撮合操作；已有未完成批次且不允许 Cluster 管线准入时先延后，否则立即进入本条命令准入。
     * @param message 当前完整 Core 消息
     * @param clusterTimestamp 固定使用的 Cluster 业务时间
     * @param clusterPosition 固定使用的复制日志位置
     * @param sourceKey 当前已确认的来源键
     * @param fingerprint 原命令完整指纹
     */
    public CoreResponse beginMatching(CoreMessage message, long clusterTimestamp, long clusterPosition,
                                       TradingCoreRuntime.SourceKey sourceKey, CommandFingerprint fingerprint) {
        // 由消息类型确定的撮合操作类别
        CommandSlot.Operation operation = matchingOperation(message.header().messageType());
        if (owner.batches().hasPendingBatches() && !owner.clusterPipelineAdmission()) {
            return deferMatching(message, clusterTimestamp, clusterPosition, sourceKey, operation, fingerprint);
        }
        return prepareMatching(message, clusterTimestamp, clusterPosition, sourceKey, operation, fingerprint, null);
    }

    /**
     * 把支持的下单、撤单、改单、强平及结算消息映射到既有槽操作；其他消息直接拒绝映射。
     * @param messageType 原消息头内的消息类别
     */
    static CommandSlot.Operation matchingOperation(CoreMessageType messageType) {
        return switch (messageType) {
            case PLACE_ORDER, PLACE_ORDER_BATCH -> CommandSlot.Operation.PLACE;
            case CANCEL_ORDER, CANCEL_ORDER_BATCH -> CommandSlot.Operation.CANCEL;
            case REPLACE_ORDER -> CommandSlot.Operation.REPLACE;
            case AMEND_ORDER, AMEND_ORDER_BATCH -> CommandSlot.Operation.AMEND;
            case EXECUTE_LIQUIDATION -> CommandSlot.Operation.LIQUIDATION;
            case EXECUTE_LIQUIDATION_BATCH -> CommandSlot.Operation.LIQUIDATION_BATCH;
            case SETTLE_INSTRUMENT -> CommandSlot.Operation.SETTLEMENT;
            default -> throw new IllegalArgumentException("not a matching command");
        };
    }

    /**
     * 初次解码或恢复延后输入，先检查生命周期，再建立事实作用域与回滚检查点，按操作派发账户准入或检查路由。已知准入错误按现有边界回滚并拒绝，不能跨在途账户命令回滚；成功则登记槽、保存收据并释放 Owner 访问权后提交 Matcher，返回待处理状态。
     * @param message 当前完整 Core 消息
     * @param clusterTimestamp 固定使用的 Cluster 业务时间
     * @param clusterPosition 固定使用的复制日志位置
     * @param sourceKey 当前已确认的来源键
     * @param operation 当前撮合操作类别
     * @param fingerprint 原命令完整指纹
     * @param deferredPending 已占原序号的延后命令槽，初次准入为空
     */
    CoreResponse prepareMatching(CoreMessage message, long clusterTimestamp, long clusterPosition,
                                         TradingCoreRuntime.SourceKey sourceKey, CommandSlot.Operation operation,
                                         CommandFingerprint fingerprint, CommandSlot deferredPending) {
        // 初次解码或从延后槽取回的类型化输入
        DecodedMatchingCommand decodedCommand;
        try {
            decodedCommand = deferredPending == null
                    ? owner.decodeMatchingCommand(message) : deferredPending.decodedCommand();
        } catch (/* 解码阶段的已知拒绝、算术或参数异常 */ CoreStateRejectedException rejection) {
            return deferredPending == null
                    ? owner.admissionRejected(CoreResultCode.fromRejectionCode(rejection.code())) : null;
        } catch (/* 解码阶段的已知拒绝、算术或参数异常 */ ArithmeticException | IllegalArgumentException rejection) {
            return deferredPending == null
                    ? owner.admissionRejected(rejection instanceof ArithmeticException
                            ? CoreResultCode.ARITHMETIC_OVERFLOW : CoreResultCode.INVALID_COMMAND) : null;
        }
        // 延后项沿用旧指纹，初次项使用本次输入指纹
        CommandFingerprint effectiveFingerprint = deferredPending == null
                ? fingerprint : deferredPending.fingerprint();
        owner.activateFactContext(message, effectiveFingerprint);
        try {
            rejectLifecycleOverlap(message, operation, decodedCommand);
        } catch (/* 当前准入或盖戳过程中抛出的错误，按所在边界拒绝、回滚或继续传播 */ CoreStateRejectedException exception) {
            // 本轮已决定的拒绝响应，由事实作用域收尾后返回
            CoreResponse response = rejectMatching(message, sourceKey, effectiveFingerprint,
                    CoreResultCode.fromRejectionCode(exception.code()), deferredPending);
            return owner.finishFactContext(response);
        } catch (/* 当前准入或盖戳过程中抛出的错误，按所在边界拒绝、回滚或继续传播 */ ArithmeticException | IllegalArgumentException exception) {
            // 本轮已决定的拒绝响应，由事实作用域收尾后返回
            CoreResponse response = rejectMatching(message, sourceKey, effectiveFingerprint,
                    exception instanceof ArithmeticException
                    ? CoreResultCode.ARITHMETIC_OVERFLOW : CoreResultCode.INVALID_COMMAND, deferredPending);
            return owner.finishFactContext(response);
        }
        // 本条命令新占或延后保留的 Core 序号
        long sequence = deferredPending == null
                ? Math.incrementExact(owner.appliedCommandCount()) : deferredPending.sequence();
        // 开始业务准入前的账户回滚检查点
        long runtimeCommandCheckpoint = owner.runtimeState().commandRevisionCheckpoint();
        // 开始业务准入前的持仓身份回滚检查点
        long positionIdentityCheckpoint = owner.identities().positionCheckpoint();
        // 当前已能确定的先撤订单列表，未就绪准入可暂为空
        List<Long> preMatchingCancellations = List.of();
        // 当前可空的解析准入输入
        ResolvedMatchingAdmission admission = null;
        // 账户下单准入事件，可能尚未完成
        com.surprising.aeron.service.order.admission.PlaceAdmissionEvent placeAdmission = null;
        // 账户替换或改单准入事件，可能尚未完成
        com.surprising.aeron.service.order.admission.ReplacementAdmissionEvent replacementAdmission = null;
        owner.resultBuilder.beginCommand();
        owner.commits.publication.begin();
        try {
            switch (operation) {
                case PLACE -> {
                    // 当前原命令或按操作取出的解码请求，具体类型见声明
                    var command = decodedCommand.placeOrder();
                    owner.requireOrderIdentityAvailable(message.header().userId(), command);
                    placeAdmission = owner.dispatchPlaceAdmission(message.header().userId(), command,
                            message.header().commandId(), sequence, clusterTimestamp, clusterPosition);
                }
                case CANCEL -> validatePendingCancel(message, decodedCommand);
                case REPLACE -> replacementAdmission = dispatchReplacementAdmission(message, decodedCommand, false, sequence);
                case AMEND -> replacementAdmission = dispatchReplacementAdmission(message, decodedCommand, true, sequence);
                case TRIGGER -> throw new IllegalStateException("trigger matching requires its Lane admission");
                case LIQUIDATION -> validatePendingLiquidation(decodedCommand);
                case LIQUIDATION_BATCH -> validatePendingLiquidationBatch(decodedCommand);
                case SETTLEMENT -> validatePendingSettlement(decodedCommand);
            }
            if (replacementAdmission == null)
                preMatchingCancellations = preMatchingCloseCapacityCancellations(
                        operation, message, decodedCommand, placeAdmission);
        } catch (/* 当前准入或盖戳过程中抛出的错误，按所在边界拒绝、回滚或继续传播 */ CoreStateRejectedException exception) {
            if (owner.commits.publication.dirty()) {
                if (!owner.pendingMatching().isEmpty()) {
                    throw new IllegalStateException("cannot roll back across an in-flight lane command", exception);
                }
                owner.rollbackCommandState(runtimeCommandCheckpoint, positionIdentityCheckpoint, sequence);
            }
            else owner.commits.publication.abort();
            // 本轮已决定的拒绝响应，由事实作用域收尾后返回
            CoreResponse response = rejectMatching(message, sourceKey, effectiveFingerprint,
                    CoreResultCode.fromRejectionCode(exception.code()), deferredPending);
            return owner.finishFactContext(response);
        } catch (/* 当前准入或盖戳过程中抛出的错误，按所在边界拒绝、回滚或继续传播 */ ArithmeticException | IllegalArgumentException exception) {
            if (owner.commits.publication.dirty()) {
                if (!owner.pendingMatching().isEmpty()) {
                    throw new IllegalStateException("cannot roll back across an in-flight lane command", exception);
                }
                owner.rollbackCommandState(runtimeCommandCheckpoint, positionIdentityCheckpoint, sequence);
            }
            else owner.commits.publication.abort();
            // 本轮已决定的拒绝响应，由事实作用域收尾后返回
            CoreResponse response = rejectMatching(message, sourceKey, effectiveFingerprint,
                    exception instanceof ArithmeticException
                    ? CoreResultCode.ARITHMETIC_OVERFLOW : CoreResultCode.INVALID_COMMAND, deferredPending);
            return owner.finishFactContext(response);
        }
        // 准入后是否已有正式变化，临时投影不算在内
        boolean tradingStateChanged = owner.commits.publication.dirty()
                && !owner.commits.publication.provisionalOnly();
        if (tradingStateChanged) {
            try {
                owner.stampOrderChangesRuntime(clusterTimestamp, clusterPosition, owner.resultBuilder.commandChangedOrderIds);
            } catch (/* 当前准入或盖戳过程中抛出的错误，按所在边界拒绝、回滚或继续传播 */ RuntimeException exception) {
                owner.rollbackCommandState(runtimeCommandCheckpoint, positionIdentityCheckpoint, sequence);
                throw exception;
            }
        }
        owner.commits.publication.complete();
        // 当前新借、恢复或参与范围比较的命令槽
        CommandSlot pending = deferredPending == null
                ? newPendingMatching(sequence, operation, message, effectiveFingerprint,
                        preMatchingCancellations, decodedCommand, admission)
                : deferredPending.withPreMatchingCancellations(preMatchingCancellations)
                        .withAdmission(admission);
        pending.establishCommitFence(clusterTimestamp, clusterPosition);
        if (deferredPending == null) {
            owner.matchingFlow.putPendingMatching(pending);
        } else {
            owner.pendingMatching().put(pending);
            pending.activateDeferredMatching();
        }
        if (placeAdmission != null) pending.placeAdmission(placeAdmission);
        if (replacementAdmission != null) pending.replacementAdmission(replacementAdmission);
        registerPendingLifecycle(pending);
        if (deferredPending == null) {
            owner.recordAppliedSequence(sequence, sourceKey, message.header().sourceSequence());
        }
        // 当前准入已结束；推进其他序号的异步续接前先释放 owner 的事实上下文。
        owner.clearFactContext();
        owner.runtimeState().releaseOwnerLaneAccess();
        // 撮合输入已经固定，可立即交接；账户 Lane 同时完成资金预留。
        // Matcher 仍须等准入门闩通过，有序提交再收齐准入及结算事实。
        owner.matchingFlow.submitMatching(pending);
        return message.matchingPendingResponse(com.surprising.aeron.protocol.CoreResultCode.MATCHING_PENDING, sequence);
    }

    /**
     * 先解码输入并分配 Core 序号，再登记为延后项，保存原时间、日志位置及来源；实际业务准入留到依赖允许后执行。
     * @param message 当前完整 Core 消息
     * @param clusterTimestamp 固定使用的 Cluster 业务时间
     * @param clusterPosition 固定使用的复制日志位置
     * @param sourceKey 当前已确认的来源键
     * @param operation 当前撮合操作类别
     * @param fingerprint 原命令完整指纹
     */
    CoreResponse deferMatching(CoreMessage message, long clusterTimestamp, long clusterPosition,
                                       TradingCoreRuntime.SourceKey sourceKey, CommandSlot.Operation operation,
                                       CommandFingerprint fingerprint) {
        // 初次解码或从延后槽取回的类型化输入
        DecodedMatchingCommand decodedCommand;
        try {
            decodedCommand = owner.decodeMatchingCommand(message);
        } catch (/* 解码阶段的已知拒绝、算术或参数异常 */ CoreStateRejectedException rejection) {
            return owner.admissionRejected(CoreResultCode.fromRejectionCode(rejection.code()));
        } catch (/* 解码阶段的已知拒绝、算术或参数异常 */ ArithmeticException | IllegalArgumentException rejection) {
            return owner.admissionRejected(rejection instanceof ArithmeticException
                    ? CoreResultCode.ARITHMETIC_OVERFLOW : CoreResultCode.INVALID_COMMAND);
        }
        // 本条命令新占或延后保留的 Core 序号
        long sequence = owner.nextAppliedSequence();
        // 当前新借、恢复或参与范围比较的命令槽
        CommandSlot pending = newPendingMatching(sequence, operation, message, fingerprint, decodedCommand);
        owner.matchingFlow.putPendingMatching(pending);
        pending.deferMatching(clusterTimestamp, clusterPosition, sourceKey);
        owner.recordAppliedSequence(sequence, sourceKey, message.header().sourceSequence());
        return message.matchingPendingResponse(com.surprising.aeron.protocol.CoreResultCode.MATCHING_PENDING, sequence);
    }

    /**
     * 延后项使用原序号保存拒绝并退休；新命令交撮合流按当前队列顺序登记拒绝。
     * @param message 当前完整 Core 消息
     * @param sourceKey 当前已确认的来源键
     * @param effectiveFingerprint 应绑定终态的原命令完整指纹
     * @param resultCode 需要保存的拒绝原因
     * @param deferredPending 已占原序号的延后命令槽，初次准入为空
     */
    private CoreResponse rejectMatching(CoreMessage message, TradingCoreRuntime.SourceKey sourceKey,
                                        CommandFingerprint effectiveFingerprint, CoreResultCode resultCode,
                                        CommandSlot deferredPending) {
        return deferredPending != null
                ? recordRejectedDeferredMatching(deferredPending, resultCode)
                : owner.matchingFlow.recordRejectedMatching(message, sourceKey, effectiveFingerprint, resultCode);
    }

    /**
     * 核对待项可提交，保存其原序号的幂等拒绝结果，移除待项并返回终态；提交水位由移除路径刷新。
     * @param pending 当前仍在槽池生命周期内的命令
     * @param resultCode 需要保存的拒绝原因
     */
    CoreResponse recordRejectedDeferredMatching(CommandSlot pending, CoreResultCode resultCode) {
        owner.matchingFlow.commitMatchingSequence(pending.sequence());
        owner.resultLedger().storeOwnedResult(pending.command().header().commandId(), pending.fingerprint(),
                ResponseStatus.REJECTED, resultCode, pending.sequence(),
                TradingCoreRuntime.EMPTY_RESPONSE_DATA);
        owner.matchingFlow.removePendingMatching(pending.sequence());
        return new CoreResponse(ResponseStatus.REJECTED, ResponseStatus.REJECTED, resultCode,
                pending.sequence(), TradingCoreRuntime.EMPTY_RESPONSE_DATA);
    }

    /**
     * 从活动订单路由检查原单存在且归请求用户，记下受影响用户和订单，不在此直接读写账户 Lane 订单。
     * @param message 当前完整 Core 消息
     * @param decodedCommand 已经解码的类型化命令输入
     */
    void validatePendingCancel(CoreMessage message, DecodedMatchingCommand decodedCommand) {
        // 当前原命令或按操作取出的解码请求，具体类型见声明
        var command = decodedCommand.cancelOrder();
        // 当前原单路由或本页撤单身份，具体类型见声明
        var order = owner.activeOrderIndex().activeOrderRoute(command.orderId());
        if (order == null) throw new CoreStateRejectedException("ORDER_NOT_FOUND", "order does not exist");
        if (order.userId() != message.header().userId()) {
            throw new CoreStateRejectedException("ORDER_OWNER_MISMATCH", "order belongs to another user");
        }
        owner.resultBuilder.setSingleChangedUser(message.header().userId());
        owner.resultBuilder.setSingleChangedOrder(command.orderId());
    }

    /**
     * 找原单并核对归属；AMEND 沿原合约，REPLACE 使用新单合约。准备产品判断输入，记变化身份后向所属账户派发替换准入。
     * @param message 当前完整 Core 消息
     * @param decodedCommand 已经解码的类型化命令输入
     * @param amend 是否为原合约上的可选字段修改，否则为 REPLACE 新单
     * @param sequence 当前原命令或新借槽的完整 Core 序号
     */
    com.surprising.aeron.service.order.admission.ReplacementAdmissionEvent dispatchReplacementAdmission(
            CoreMessage message, DecodedMatchingCommand decodedCommand, boolean amend, long sequence) {
        // 从 AMEND 或 REPLACE 请求取出的原单编号
        long originalOrderId = amend ? decodedCommand.amendOrder().originalOrderId()
                : decodedCommand.replaceOrder().originalOrderId();
        // 当前原单路由或本页撤单身份，具体类型见声明
        var order = owner.activeOrderIndex().activeOrderRoute(originalOrderId);
        if (order == null) throw new CoreStateRejectedException("ORDER_NOT_FOUND", "order does not exist");
        if (order.userId() != message.header().userId()) {
            throw new CoreStateRejectedException("ORDER_OWNER_MISMATCH", "order belongs to another user");
        }
        // 本次替换准入需要校验的新合约或原合约
        String instrumentId = amend ? order.instrumentId()
                : decodedCommand.replaceOrder().replacement().instrumentId();
        // 按当前业务时间和产品规则准备的订单判断输入
        var decision = CoreOrderDecisionResolver.context(owner.runtimeState(), owner.identities(),
                message.header().userId(), instrumentId, owner.currentClusterTimestamp());
        owner.resultBuilder.setSingleChangedUser(message.header().userId());
        owner.resultBuilder.setSingleChangedOrder(originalOrderId);
        return owner.runtimeState().dispatchReplacementAdmission(sequence, message.header().userId(), originalOrderId,
                amend ? decodedCommand.amendOrder() : null, amend ? null : decodedCommand.replaceOrder(),
                decision,
                owner.matchingAdapter().matcherShardId(instrumentId), owner.identities());
    }

    /**
     * 只有 PLACE 才读取准入的先撤单列表，收据未完成暂给空表；非 PLACE 直接为空，消息和解码参数在此实现中不读取。
     * @param operation 当前撮合操作类别
     * @param message 沿用入口签名的原消息，此方法不读取
     * @param decodedCommand 沿用入口签名的解码输入，此方法不读取
     * @param placeAdmission 普通下单的账户准入收据
     */
    List<Long> preMatchingCloseCapacityCancellations(CommandSlot.Operation operation,
            CoreMessage message, DecodedMatchingCommand decodedCommand,
            com.surprising.aeron.service.order.admission.PlaceAdmissionEvent placeAdmission) {
        if (operation != CommandSlot.Operation.PLACE) return List.of();
        if (placeAdmission == null) throw new IllegalStateException("place matching requires its Lane admission");
        return placeAdmission.complete() ? placeAdmission.closeCapacityCancellations() : List.of();
    }

    /**
     * 要求强平计划仍有路由，登记该用户及本页活动订单；完整账户执行资格由后续 Lane 校验。
     * @param decodedCommand 已经解码的类型化命令输入
     */
    void validatePendingLiquidation(DecodedMatchingCommand decodedCommand) {
        // 当前原命令或按操作取出的解码请求，具体类型见声明
        var command = decodedCommand.liquidation();
        // 当前强平计划的活动路由身份
        var route = owner.liquidationIndex().route(command.liquidationId());
        if (route == null) throw new CoreStateRejectedException("LIQUIDATION_NOT_FOUND", "liquidation plan does not exist");
        owner.resultBuilder.setSingleChangedUser(route.userId());
        owner.resultBuilder.commandChangedOrderIds = owner.activeOrderIndex().page(route.userId(), route.instrumentId(),
                command.cursorOrderId(), command.maxOrders()).orderIds();
    }

    /**
     * 核对可选风险续扫预算和游标，逐有效强平动作校验用户、品种、正执行价及生命周期范围，拒绝重复用户加品种；按总撤单预算记录用户和订单变化身份。
     * @param decodedCommand 已经解码的类型化命令输入
     */
    void validatePendingLiquidationBatch(DecodedMatchingCommand decodedCommand) {
        // 当前原命令或按操作取出的解码请求，具体类型见声明
        var command = decodedCommand.liquidationBatch();
        if (command.riskScanContinuation() != null) {
            // 运行时当前风险扫描控制参数
            var control = owner.runtimeState().riskScanControl();
            if (!control.enabled() || command.maxRiskScanUsers() > control.scanBatchSize()) {
                throw new CoreStateRejectedException("INVALID_COMMAND",
                        "risk scan continuation exceeds current control");
            }
            // 当前第一个尚未完成的风险扫描记录
            var scan = owner.runtimeState().firstRiskIncompleteScan();
            // 命令请求继续推进的风险扫描游标
            var continuation = command.riskScanContinuation();
            if (scan == null
                    || !owner.identities().instrumentId(scan.symbolId()).equals(continuation.instrumentId())
                    || scan.priceSequence() != continuation.priceSequence()
                    || scan.lastUserId() != continuation.lastUserId()) {
                throw new CoreStateRejectedException("INVALID_COMMAND", "risk scan cursor does not match state");
            }
        }
        batchScopeCount = 0;
        batchChangedUsers.clear();
        batchChangedOrders.clear();
        // 本批仍可选择的撤单数量
        int remaining = command.maxCancelOrders();
        for (var /* 当前批次强平动作，含用户、品种及撤单游标 */ action : command.actions()) {
            // 现有强平计划的可空路由
            var liquidation = owner.liquidationIndex().route(action.liquidationId());
            if (liquidation == null || !liquidation.executable()) continue;
            if (liquidation.userId() != action.userId() || !liquidation.instrumentId().equals(action.instrumentId())
                    || action.executionPriceTicks() <= 0)
                throw new CoreStateRejectedException("INVALID_COMMAND", "liquidation batch route does not match action");
            // 该有效强平计划对应的品种名称
            String liquidationSymbol = liquidation.instrumentId();
            for (/* 本轮已经登记的用户加品种范围下标 */ int scopeIndex = 0; scopeIndex < batchScopeCount; scopeIndex++) {
                if (batchScopeUsers[scopeIndex] == liquidation.userId()
                        && batchScopeSymbols[scopeIndex].equals(liquidationSymbol)) {
                    throw new CoreStateRejectedException("LIFECYCLE_IN_PROGRESS",
                            "liquidation batch contains overlapping scopes");
                }
            }
            if (batchScopeCount == batchScopeUsers.length) {
                // 重复范围缓冲精确翻倍后的容量
                int capacity = Math.multiplyExact(batchScopeUsers.length, 2);
                batchScopeUsers = java.util.Arrays.copyOf(batchScopeUsers, capacity);
                batchScopeSymbols = java.util.Arrays.copyOf(batchScopeSymbols, capacity);
            }
            batchScopeUsers[batchScopeCount] = liquidation.userId();
            batchScopeSymbols[batchScopeCount++] = liquidationSymbol;
            ensureLifecycleScopeAvailable(new LifecycleScope(false, liquidation.userId(),
                    liquidationSymbol,
                    action.liquidationId(), true, false));
            batchChangedUsers.add(liquidation.userId());
            if (remaining > 0) {
                // 按剩余预算取得的当前强平撤单页
                TradingCoreRuntime.LifecycleOrderChunk chunk = owner.matchingFlow.lifecycleOrders(liquidation.userId(), liquidationSymbol,
                        action.cursorOrderId(), remaining);
                for (var /* 当前原单路由或本页撤单身份，具体类型见声明 */ order : chunk.orders()) batchChangedOrders.add(order.orderId());
                remaining -= chunk.orders().size();
            }
        }
        owner.resultBuilder.commandChangedUserIds = batchChangedUsers.toImmutableList();
        owner.resultBuilder.commandChangedOrderIds = batchChangedOrders.toImmutableList();
    }

    /**
     * 已注册品种先检查产品到期规则，再核结算编号、价格和分页游标；按撤单阶段选择订单及受影响用户，不在这里做持仓结算，缺失品种也不是由本方法直接拒绝。
     * @param decodedCommand 已经解码的类型化命令输入
     */
    void validatePendingSettlement(DecodedMatchingCommand decodedCommand) {
        // 当前原命令或按操作取出的解码请求，具体类型见声明
        var command = decodedCommand.settlement();
        // 待结算或触发所用的可空品种状态
        var instrument = owner.runtimeState().instrument(command.instrumentId());
        if (instrument != null) ProductTradingRulesRegistry.forInstrument(instrument)
                .validateLifecycleSettlement(instrument, command);
        if (instrument != null && !instrument.administrativeSettlement(command)
                && instrument.expiryEpochMillis() > owner.currentClusterTimestamp()) {
            throw new CoreStateRejectedException("INVALID_COMMAND", "instrument has not reached expiry");
        }
        // 当前品种已提交的到期结算进度
        var progress = owner.runtimeLifecycleProgress(command.instrumentId());
        if (progress == null && (command.cursorUserId() != 0 || command.cursorOrderId() != 0)) {
            throw new CoreStateRejectedException("INVALID_COMMAND", "settlement cursor must start at zero");
        }
        if (progress != null && (progress.settlementId() != command.settlementId()
                || progress.settlementPriceTicks() != command.settlementPriceTicks()

                || progress.ordersComplete() != (command.cursorOrderId() == 0)
                || progress.nextCursorOrderId() != command.cursorOrderId()
                || progress.nextCursorUserId() != command.cursorUserId())) {
            throw new CoreStateRejectedException("INVALID_COMMAND", "settlement cursor does not match progress");
        }
        // 该品种在当前撤单游标后的活动订单页
        TradingCoreRuntime.LifecycleOrderChunk orderChunk = owner.matchingFlow.lifecycleOrders(0, command.instrumentId(), command.cursorOrderId(), command.maxOrders());
        // 当前结算是否仍处在撤单阶段
        boolean orderPhase = progress == null || !progress.ordersComplete();
        if (orderPhase && !orderChunk.more()) {
            owner.resultBuilder.commandChangedOrderIds = TradingCoreRuntime.boxedOrderIds(orderChunk.orders());
            owner.resultBuilder.addChangedUsersFromOrders(orderChunk.orders());
        } else if (orderPhase) {
            owner.resultBuilder.commandChangedOrderIds = TradingCoreRuntime.boxedOrderIds(orderChunk.orders());
            owner.resultBuilder.addChangedUsersFromOrders(orderChunk.orders());
        } else {
            owner.resultBuilder.commandChangedOrderIds = List.of();
        }
    }

    /**
     * 单笔强平和到期结算按完整生命周期范围检查，其他操作先按现有用户和路由品种检查；批次强平的全部范围另在批次校验中逐项检查。
     * @param message 当前完整 Core 消息
     * @param operation 当前撮合操作类别
     * @param decodedCommand 已经解码的类型化命令输入
     */
    void rejectLifecycleOverlap(CoreMessage message, CommandSlot.Operation operation,
                                        DecodedMatchingCommand decodedCommand) {
        if (operation == CommandSlot.Operation.LIQUIDATION || operation == CommandSlot.Operation.SETTLEMENT) {
            ensureLifecycleScopeAvailable(lifecycleScope(message, operation, decodedCommand));
        } else {
            ensureLifecycleScopeAvailable(message.header().userId(),
                    matchingSymbol(message, operation, decodedCommand), null);
        }
    }

    /**
     * 把已有生命周期范围展开，交同一冲突检查入口。
     * @param candidate 要检查的完整生命周期范围，普通订单原语入口传空
     */
    void ensureLifecycleScopeAvailable(LifecycleScope candidate) {
        ensureLifecycleScopeAvailable(candidate.userId(), candidate.instrumentId(), candidate);
    }

    /**
     * 空品种直接结束。生命周期操作检查全部在途命令，普通操作只查已保留生命周期索引；同时拒绝未完成资金费和不兼容结算，核对已提交强平冲突，允许同编号结算按原规则续页。
     * @param userId 操作所属用户，生命周期结算可用零代表全用户
     * @param instrumentId 待检查冲突的品种名称
     * @param candidate 要检查的完整生命周期范围，普通订单原语入口传空
     */
    private void ensureLifecycleScopeAvailable(long userId, String instrumentId, LifecycleScope candidate) {
        if (instrumentId.isBlank()) return;
        // 当前候选是否属于生命周期操作
        boolean lifecycle = candidate != null && candidate.lifecycle();
        // 当前候选是否覆盖整个品种结算
        boolean settlement = candidate != null && candidate.settlement();
        // 当前候选生命周期编号，普通订单用零
        long lifecycleId = candidate == null ? 0 : candidate.lifecycleId();
        // 当前候选是否会改变普通订单
        boolean orderChanging = candidate == null || candidate.orderChanging();
        if (lifecycle) {
            owner.pendingMatching().forEach(/* 当前新借、恢复或参与范围比较的命令槽 */ pending -> {
                if (pendingLifecycleConflicts(candidate, pending)) {
                    throw new CoreStateRejectedException("LIFECYCLE_IN_PROGRESS",
                            "matching lifecycle scope is in progress");
                }
            });
        } else if (!pendingLifecycleScopes.isEmpty()) {
            for (List<LifecycleScope> /* 某个在途生命周期命令已经保留的范围列表 */ scopes : pendingLifecycleScopes.values()) {
                for (/* 当前列表或范围缓冲下标 */ int i = 0; i < scopes.size(); i++) {
                    // 当前被比较的单个生命周期范围
                    LifecycleScope scope = scopes.get(i);
                    if (instrumentId.equals(scope.instrumentId()) && scope.lifecycle()
                            && (settlement || scope.settlement() || userId == scope.userId())) {
                        throw new CoreStateRejectedException("LIFECYCLE_IN_PROGRESS",
                                "matching lifecycle scope is in progress");
                    }
                }
            }
        }
        // 品种名称查到的可空永久编号
        Integer candidateSymbolId = owner.identities().findSymbolId(instrumentId);
        if (candidateSymbolId != null && owner.runtimeState().treasury().fundingProgress(candidateSymbolId) != null) {
            throw new CoreStateRejectedException("LIFECYCLE_IN_PROGRESS", "funding position cut is in progress");
        }
        // 该品种是否已有尚未结束的结算进度
        boolean settlementProgress = candidateSymbolId != null
                && owner.runtimeState().treasury().lifecycleProgress(candidateSymbolId) != null;
        if (settlementProgress
                && orderChanging) {
            throw new CoreStateRejectedException("LIFECYCLE_IN_PROGRESS",
                    "settlement lifecycle is in progress");
        }
        if (lifecycle) {
            // 是否存在需要排除交错的已提交活动强平计划
            boolean liquidationActive = candidateSymbolId != null
                    && hasCommittedLiquidationConflict(
                    settlement ? 0 : userId, candidateSymbolId, lifecycleId);
            // 当前请求是否恰好继续同编号的结算
            boolean settlementContinuation = settlementProgress && settlement
                    && owner.runtimeState().treasury().lifecycleProgress(candidateSymbolId).settlementId()
                    == lifecycleId;
            if (settlementProgress && !settlementContinuation || liquidationActive) {
                throw new CoreStateRejectedException("LIFECYCLE_IN_PROGRESS",
                        "matching lifecycle scope is in progress");
            }
        }
    }

    /**
     * 从已有活动强平派生索引查找同品种、指定用户或全用户的可执行计划，排除当前生命周期编号，不跨线程扫描账户状态。
     * @param userId 操作所属用户，生命周期结算可用零代表全用户
     * @param symbolId 查已提交强平冲突的永久品种编号
     * @param excludedId 应排除的当前生命周期编号
     */
    private boolean hasCommittedLiquidationConflict(long userId, int symbolId, long excludedId) {
        for (long /* 当前已提交活动强平计划编号 */ id : owner.liquidationIndex().activeIds()) {
            if (id == excludedId) continue;
            // 现有强平计划的可空路由
            var liquidation = owner.liquidationIndex().route(id);
            if (liquidation != null && (userId == 0 || liquidation.userId() == userId)
                    && liquidation.instrumentId().equals(owner.identities().instrumentId(symbolId))
                    && liquidation.executable()) return true;
        }
        return false;
    }

    /**
     * 普通待项比较一个范围，批次强平则逐动作比较所有范围，任一冲突即返回真。
     * @param candidate 要检查的完整生命周期范围，普通订单原语入口传空
     * @param pending 当前仍在槽池生命周期内的命令
     */
    boolean pendingLifecycleConflicts(LifecycleScope candidate, CommandSlot pending) {
        if (pending.operation() != CommandSlot.Operation.LIQUIDATION_BATCH) {
            return conflicts(candidate, lifecycleScope(pending));
        }
        // 当前槽内的已解码批次强平请求
        var batch = pending.decodedCommand().liquidationBatch();
        return batch.actions().stream().map(/* 当前批次强平动作，含用户、品种及撤单游标 */ action -> new LifecycleScope(false, action.userId(), action.instrumentId(),
                        action.liquidationId(), true, false)).anyMatch(/* 当前被比较的单个生命周期范围 */ scope -> conflicts(candidate, scope));
    }

    /**
     * 仅为强平和到期结算保留按 Core 序号索引的范围；批次登记全部动作，普通订单不复制成持久范围对象。
     * @param pending 当前仍在槽池生命周期内的命令
     */
    void registerPendingLifecycle(CommandSlot pending) {
        // 某个在途生命周期命令已经保留的范围列表
        List<LifecycleScope> scopes = switch (pending.operation()) {
            case LIQUIDATION, SETTLEMENT -> List.of(lifecycleScope(pending));
            case LIQUIDATION_BATCH -> pending.decodedCommand().liquidationBatch()
                    .actions().stream()
                    .map(/* 当前批次强平动作，含用户、品种及撤单游标 */ action -> new LifecycleScope(false, action.userId(), action.instrumentId(),
                            action.liquidationId(), true, false))
                    .toList();
            default -> List.of();
        };
        if (!scopes.isEmpty()) pendingLifecycleScopes.put(pending.sequence(), scopes);
    }

    /**
     * 单笔强平从路由取得用户和合约，路由缺失用空范围；结算覆盖该品种全部用户；普通订单只描述用户和品种并标记会改订单。
     * @param message 当前完整 Core 消息
     * @param operation 当前撮合操作类别
     * @param decodedCommand 已经解码的类型化命令输入
     */
    LifecycleScope lifecycleScope(CoreMessage message, CommandSlot.Operation operation,
                                          DecodedMatchingCommand decodedCommand) {
        return switch (operation) {
            case LIQUIDATION -> {
                // 当前原命令或按操作取出的解码请求，具体类型见声明
                var command = decodedCommand.liquidation();
                // 现有强平计划的可空路由
                var liquidation = owner.liquidationIndex().route(command.liquidationId());
                yield liquidation == null ? new LifecycleScope(false, 0, "", 0, true, false)
                        : new LifecycleScope(false, liquidation.userId(), liquidation.instrumentId(),
                                command.liquidationId(), true, false);
            }
            case SETTLEMENT -> new LifecycleScope(true, 0, decodedCommand.settlement().instrumentId(),
                    decodedCommand.settlement().settlementId(), true, false);
            default -> new LifecycleScope(false, message.header().userId(),
                    matchingSymbol(message, operation, decodedCommand), 0, false, true);
        };
    }

    /**
     * 从命令槽生成范围；批次入口仅返回第一项，涉及全部动作的冲突判断须用 pendingLifecycleConflicts。
     * @param pending 当前仍在槽池生命周期内的命令
     */
    LifecycleScope lifecycleScope(CommandSlot pending) {
        if (pending.operation() == CommandSlot.Operation.LIQUIDATION_BATCH) {
            // 当前批次强平动作，含用户、品种及撤单游标
            var action = pending.decodedCommand().liquidationBatch().actions().getFirst();
            return new LifecycleScope(false, action.userId(), action.instrumentId(), action.liquidationId(), true, false);
        }
        return lifecycleScope(pending.command(), pending.operation(), pending.decodedCommand());
    }

    /**
     * 只有同非空品种且至少一方属于生命周期才可能冲突；全品种结算或同用户视为范围相交。
     * @param left 左侧待比较资源范围
     * @param right 右侧待比较资源范围
     */
    static boolean conflicts(LifecycleScope left, LifecycleScope right) {
        if (left.instrumentId().isBlank() || !left.instrumentId().equals(right.instrumentId())
                || (!left.lifecycle() && !right.lifecycle())) return false;
        return left.settlement() || right.settlement() || left.userId() == right.userId();
    }

    /**
     * 用于判断在途操作能否交错的资源范围，只描述用户、品种和生命周期身份，不执行状态修改，也没有额外构造校验。
     * @param settlement 是否为覆盖整个品种的到期结算
     * @param userId 操作所属用户，生命周期结算可用零代表全用户
     * @param instrumentId 待检查冲突的品种名称
     * @param lifecycleId 当前强平或到期结算编号，普通订单为零
     * @param lifecycle 是否属于强平或到期等生命周期操作
     * @param orderChanging 是否属于会改变订单的普通操作
     */
    record LifecycleScope(boolean settlement, long userId, String instrumentId, long lifecycleId,
                                  boolean lifecycle, boolean orderChanging) {
    }

    /**
     * 下单取解码合约，撤改单取原活动订单路由，触发子单取源触发路由并要求品种仍存在；批次取第一项，单笔强平和结算留给专用路径。
     * @param message 当前完整 Core 消息
     * @param operation 当前撮合操作类别
     * @param decodedCommand 已经解码的类型化命令输入
     */
    String matchingSymbol(CoreMessage message, CommandSlot.Operation operation,
                                  DecodedMatchingCommand decodedCommand) {
        return switch (operation) {
            case PLACE -> decodedCommand.placeOrder().instrumentId();
            case CANCEL -> {
                // 当前原命令或按操作取出的解码请求，具体类型见声明
                var command = decodedCommand.cancelOrder();
                // 当前原单路由或本页撤单身份，具体类型见声明
                var order = owner.activeOrderIndex().activeOrderRoute(command.orderId());
                yield order == null ? "" : order.instrumentId();
            }
            case REPLACE, AMEND -> {
                // 替换或改单需要查路由的原订单编号
                long orderId = operation == CommandSlot.Operation.REPLACE
                        ? decodedCommand.replaceOrder().originalOrderId()
                        : decodedCommand.amendOrder().originalOrderId();
                // 当前原单路由或本页撤单身份，具体类型见声明
                var order = owner.activeOrderIndex().activeOrderRoute(orderId);
                yield order == null ? "" : order.instrumentId();
            }
            case TRIGGER -> {
                // 触发子单解码后的原语参数数组，首项用于查源触发路由
                long[] execute = decodedCommand.trigger();
                // 现有源触发单的可空路由
                var trigger = owner.triggerOrderIndex().route(execute[0]);
                if (trigger == null) yield "";
                // 待结算或触发所用的可空品种状态
                var instrument = owner.runtimeState().instrument(trigger.instrumentId());
                if (instrument == null)
                    throw new com.surprising.aeron.exception.CoreStateRejectedException(
                            "INSTRUMENT_NOT_FOUND", "trigger instrument state is missing");
                yield instrument.instrumentId();
            }
            case LIQUIDATION, SETTLEMENT -> "";
            case LIQUIDATION_BATCH -> {
                // 当前槽内的已解码批次强平请求
                var batch = decodedCommand.liquidationBatch();
                yield batch.actions().isEmpty() ? "" : batch.actions().getFirst().instrumentId();
            }
        };
    }

    /**
     * 结算直接取合约，批次强平取第一项，单笔强平取现有计划路由；找不到返回空串。
     * @param pending 当前仍在槽池生命周期内的命令
     */
    String pendingLifecycleSymbol(CommandSlot pending) {
        if (pending.operation() == CommandSlot.Operation.SETTLEMENT) {
            return pending.decodedCommand().settlement().instrumentId();
        }
        if (pending.operation() == CommandSlot.Operation.LIQUIDATION_BATCH) {
            // 当前槽内的已解码批次强平请求
            var batch = pending.decodedCommand().liquidationBatch();
            return batch.actions().isEmpty() ? "" : batch.actions().getFirst().instrumentId();
        }
        // 现有强平计划的可空路由
        var liquidation = owner.liquidationIndex().route(
                pending.decodedCommand().liquidation().liquidationId());
        return liquidation == null ? "" : liquidation.instrumentId();
    }

    /**
     * 返回当前待正式登记的触发子单列表长度。
     */
    public int queuedMatchingCount() { return queuedMatching.size(); }
    /**
     * 新建空生命周期范围索引；会替换原索引，只能在初始化或恢复约定边界调用。
     */
    public void initializeLifecycleScopes() { pendingLifecycleScopes = new LongObjectHashMap<>(); }
    /**
     * 清空已初始化的范围索引，保留容器供后续命令复用。
     */
    public void clearLifecycleScopes() { pendingLifecycleScopes.clear(); }
}
