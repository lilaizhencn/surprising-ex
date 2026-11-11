package com.surprising.aeron.service.settlement;

import com.surprising.aeron.model.CoreOrderStatus;
import com.surprising.aeron.model.CoreTriggerOrderState;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.CoreTriggerOrderStatus;
import com.surprising.aeron.service.account.AccountPartition;
import com.surprising.aeron.service.account.AccountLaneCommand;
import com.surprising.aeron.service.account.AccountTransactionRollback;
import com.surprising.aeron.service.account.UserAccount;
import com.surprising.aeron.service.core.TradingAccountRuntime;
import com.surprising.aeron.service.core.change.AccountPartitionCommit;
import com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.aeron.service.order.*;
import com.surprising.aeron.service.order.Order;
import com.surprising.aeron.service.order.OrderCommand.ResolvedPlaceOrder;
import com.surprising.aeron.service.order.OrderCommand.ResultTarget;
import com.surprising.aeron.service.order.OrderFundsReservation;
import com.surprising.aeron.service.position.Position;
import com.surprising.aeron.service.treasury.RuntimeFundsAccumulator;
import com.surprising.aeron.service.treasury.RuntimeTreasuryDelta;
import com.surprising.aeron.service.treasury.TreasuryRuntime;
import exchange.core2.core.common.MatcherEventType;
import exchange.core2.core.common.MatcherResult.MatcherEvent;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.eclipse.collections.impl.map.mutable.primitive.LongLongHashMap;
import org.eclipse.collections.impl.set.mutable.primitive.LongHashSet;

/**
 * 下单步骤 4.2–4.6 的可复用结算事件。Owner 准备命令输入，Matcher 整理并发布事实，各账户 Lane 写自己的变化和完成位，系统账本交回财务完成位，Owner 收集后有序提交。readyForLane 只要求结果已发布，ready 还等待发布方交出租约，complete 再核对全部路由账户及已登记财务工作；事件不是永久不可变快照，清理前还须交出 changes。
 * 构造约定：创建空事件壳，命令输入、批量槽和完成标记由后续准备入口填写。
 */
@NoArgsConstructor
public final class TradeSettlement implements
        AccountLaneCommand,
        com.surprising.aeron.service.matcher.OrderMatchingResult {
    /**
     * 原 Matcher 传输占用的释放边界，事件复用现有上下文回调，不新增逐命令任务或状态副本。
     */
    public interface MatcherCompletionRoute {
        /**
         * 由事件发布方通知原 Matcher 传输上下文释放本分片提交占用，仍须由事件发布租约保护后续 Lane 投递。
         */
        void releaseMatcherSubmission(int matcherShard);
    }

    /** 无财务差额时共用的空返回值或只读输入，不向其中累加真实成交金额。 */
    private static final RuntimeTreasuryDelta EMPTY_TREASURY_DELTA = new RuntimeTreasuryDelta(1);
    /** 不同 Lane 完成位之间相隔十六个 long，降低并发写相邻位置的竞争。 */
    private static final int CACHE_LINE_LONGS = 16;
    /** 完成数组的 VarHandle，Lane release 发布、Owner 及账本 acquire 读取。 */
    private static final VarHandle LONGS = MethodHandles.arrayElementVarHandle(long[].class);

    /**  本段账户最终提交序号，顺序批次中间项可为零。 返回本段账户处理完成后需要推进的 Lane 提交序号，零表示本段不推进最终水位。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private long commitSequence;
    /**  当前事实真正需要应用账户业务变化的 Lane 位图。 返回本代事实实际需要应用业务变化的账户 Lane 位图。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private long requiredLaneMask;
    /**  当前代所属运行时，事件归还前解除引用。 借用事件关联的所属交易运行时，回收后引用为空。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private TradingAccountRuntime runtime;
    /**  当前代解析资产、币对及持仓身份的运行时字典。 借用当前代运行时身份字典，事件回收后引用为空。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private RuntimeIdentityRegistry identities;
    /** 按实际参与业务 Lane 紧凑排列的财务差额槽，各 Lane 只写自己的槽。 */
    private RuntimeTreasuryDelta[] touchedLaneTreasuryDeltas;
    /** 当前代实体、余额及资金变化缓冲，完成后由 Owner 接管并解除引用。 */
    private Changes changes;
    /** 本代有效计划中是否至少有一个成交，用于选择财务处理路径。 */
    private boolean treasuryTrades;
    /** 带固定间隔的各路由 Lane 完成标记，每 Lane 单写自己的有效位置。 */
    private long[] completedLanes;
    /** 本事件保留并复用的唯一财务任务槽，系统资金权威仍在原 Treasury。 */
    private TreasurySettlement treasurySettlement;
    /** 本事件独占的计划及命令结果存储，不借用共享 Owner 暂存数组。 */
    private BatchStorage batchStorage;
    /** Matcher 已通过 volatile 发布结果或故障，Lane 据此允许消费。 */
    private volatile boolean directPublished;
    /**  必须消费本事件票据的 Lane 位图，固定多 Lane 预约可含本代无业务变化的 Lane。 返回必须消费本事件票据的路由 Lane 位图，可能包含本代已无业务变化的预约 Lane。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private long routedLaneMask;
    /** 本代命令输入、准入回执、原生撮合证据与完成观察状态。 */
    private final ExecutionState state = new ExecutionState();

    static final class ExecutionState {
        long commitTimestamp;
        long commitClusterPosition;
        boolean collected;
        long observedCompletedLaneMask;
        boolean ownerObservedComplete;
        int batchPlanCount;
        boolean cancellation;
        long cancellationUserId;
        com.surprising.aeron.service.order.OrderCommand.ReplacementAdmission replacement;
        int replacementAssetId;
        com.surprising.aeron.model.CoreTriggerOrderState sourceTrigger;
        long triggeredAt;
        long directCoreSequence;
        boolean dispatched;
        long queuedLaneMask;
        boolean expandLaneRoute;
        volatile boolean matcherPublicationDeferred;
        Throwable directFailure;
        java.util.UUID directCommandId;
        int directShard;
        java.util.List<Long> authorizedCancellations = java.util.List.of();
        exchange.core2.core.common.MatcherResult directNativeResult;
        long directMatcherSequence;
        long directLastMatcherSequence;
        int directNativeItemCount;
        long[] directAcceptedCancellations;
        int directAcceptedCancellationCount;
        boolean directNativeAccepted;
        boolean directNativeStateChanged;
        String directSyntheticResultCode;
        MatcherCompletionRoute matcherCompletionRoute;
        int matcherCompletionShard;
        boolean admissionRequired;
        OrderCommand.ResolvedPlaceOrder admissionOrder;
        long admissionUserId;
        long admissionOrderId;
        volatile boolean admissionResolved;
        boolean admissionAccepted;
        int admissionResultCode;
        OrderCommand.ResultTarget resultTarget;
    }

    public boolean dispatched() { return state.dispatched; }
    public long queuedLaneMask() { return state.queuedLaneMask; }
    public int directShard() { return state.directShard; }
    public long directLastMatcherSequence() { return state.directLastMatcherSequence; }
    /**
     * 本事件独占复用的批量计划、准入引用、原生结果槽及币对资产列，容量与本代有效前缀分开管理。
     */
    public static final class BatchStorage {
        /** Owner 准备阶段的币对编号到首个元数据槽映射，负一表示没有缓存，回收时清空。 */
        public final org.agrona.collections.Int2IntHashMap metadataSlots =
                new org.agrona.collections.Int2IntHashMap(8, 0.65f, -1, false);
        /** 按容量预创建并由事件独占复用的结算计划列，只读取本代有效前缀。 */
        public final TradeSettlement.Plan[] plans;
        /** 每有效项对应的规范币对引用，撤单等路径可以不填写。 */
        public final CoreInstrument[] instruments;
        /** 各有效项持有的准入订单值或命令槽引用，普通不可变准入路径可以为空。 */
        public final Order[] admittedOrders;
        /** 直接单笔或批量撤单目标的原语编号列。 */
        public final long[] cancellationOrderIds;
        /** 各项按需创建的原生结果命令槽，Matcher 在结果发布前填写，回收时清空内容但保留对象。 */
        public final exchange.core2.core.common.MatcherResult[] nativeResults;
        /** 本事件独占复用的替换原单撤单结果槽。 */
        public exchange.core2.core.common.MatcherResult replacementCancellation;
        /** 本事件独占复用的新单命令槽，仍须遵守事件代次租约。 */
        public Order replacementOrder;
        /**
         * baseAssetIds：各有效项预解析的基础资产编号列，不依赖容量尾部旧值。
         * quoteAssetIds：各有效项预解析的计价资产编号列。
         * settleAssetIds：各有效项预解析的结算资产编号列。
         */
        public final int[] baseAssetIds, quoteAssetIds, settleAssetIds;
        /** 本次准备使用的存储有效前缀，清理按此前缀释放引用。 */
        private int activeSize;
        /**
         * 按所需容量预创建计划和各元数据列；原生结果命令槽按需建立，不为每一项提前创建。
         */
        BatchStorage(int size) {
            plans = new TradeSettlement.Plan[size];
            for (int i = 0; i < size; i++) plans[i] = new TradeSettlement.Plan();
            instruments = new CoreInstrument[size];
            admittedOrders = new Order[size];
            cancellationOrderIds = new long[size];
            nativeResults = new exchange.core2.core.common.MatcherResult[size];
            baseAssetIds = new int[size]; quoteAssetIds = new int[size]; settleAssetIds = new int[size];
        }
        /**
         * 设置本次准备会使用的有效前缀，必须为正且不超过已有计划容量。
         */
        void activate(int size) {
            if (size <= 0 || size > plans.length) throw new IllegalArgumentException("invalid active batch size");
            activeSize = size;
        }
        /**
         * 只清理本代有效前缀中的计划引用、币对、订单和撤单编号，并重置原生命令槽；保留槽对象和原语数组容量，资产列旧尾部不再有效。
         */
        void clear() {
            metadataSlots.clear();
            for (int index = 0; index < activeSize; index++) {
                plans[index].clearReferences();
                instruments[index] = null;
                admittedOrders[index] = null;
                cancellationOrderIds[index] = 0;
                if (nativeResults[index] != null) nativeResults[index].clearCommandSlot();
            }
            if (replacementCancellation != null) replacementCancellation.clearCommandSlot();
            if (replacementOrder != null) replacementOrder.clearCommandSlot();
            activeSize = 0;
        }
    }
    /**
     * 在当前结果尚未发布且下标有效时借用事件独占的原生命令结果槽，首次使用按需创建；消费者结束前不得改作另一条命令。
     */
    public exchange.core2.core.common.MatcherResult nativeResultSlot(int index) {
        if (batchStorage == null || index < 0 || index >= state.batchPlanCount || directPublished)
            throw new IllegalStateException("native result slot is not writable");
        var result = batchStorage.nativeResults[index];
        if (result == null) batchStorage.nativeResults[index] = result = exchange.core2.core.common.MatcherResult.commandSlot();
        return result;
    }

    /**
     * 在结果尚未发布时借用本事件专属的替换撤单原生命令槽，首次使用按需创建。
     */
    public exchange.core2.core.common.MatcherResult replacementCancellationSlot() {
        if (batchStorage == null || directPublished)
            throw new IllegalStateException("replacement cancellation is not writable");
        if (batchStorage.replacementCancellation == null)
            batchStorage.replacementCancellation = exchange.core2.core.common.MatcherResult.commandSlot();
        return batchStorage.replacementCancellation;
    }

    /**
     * 事件尚未激活时准备批量存储，容量不足才重建；设置本次有效前缀，调用方仍须确保没有旧代消费者。
     */
    public BatchStorage batchStorage(int size) {
        if (state.batchPlanCount != 0) throw new IllegalStateException("settlement event is still active");
        if (size <= 0) throw new IllegalArgumentException("settlement batch must not be empty");
        if (batchStorage == null || batchStorage.plans.length < size)
            batchStorage = new BatchStorage(size);
        batchStorage.activate(size);
        return batchStorage;
    }

    /**
     * 准备一代直达事件，默认以同一 Core 序号作为最终 Lane 提交序号，委托完整入口登记租约输入。
     * @param cancellations 准入已授权的前置撤单编号，只读保留到本代事件结束
     * @param target 在事件租约内接收最终订单值的命令目标，可为空
     */
    public void prepareDirect(long sequence, long laneMask, long timestamp, long position,
                       java.util.UUID commandId, int shard, TradingAccountRuntime runtime,
                       RuntimeIdentityRegistry identities, int count, java.util.List<Long> cancellations,
                       OrderCommand.ResultTarget target) {
        prepareDirect(sequence, sequence, laneMask, timestamp, position, commandId, shard,
                runtime, identities, count, cancellations, target);
    }

    /**
     * 检查序号、时间、路由及有效数量，重置本代准入和发布状态，保存命令证据并借用变化缓冲。顺序批次可用零提交序号先应用本项事实，整批终态再统一推进水位；这里不投递 Lane 队列。
     * @param commitSequence 最终账户提交序号，中间批次项可用零表示本段不推进水位
     * @param cancellations 准入已授权的前置撤单编号，只读保留到本代事件结束
     * @param target 在事件租约内接收最终订单值的命令目标，可为空
     */
    public void prepareDirect(long coreSequence, long commitSequence, long laneMask, long timestamp, long position,
                       java.util.UUID commandId, int shard, TradingAccountRuntime runtime,
                       RuntimeIdentityRegistry identities, int count, java.util.List<Long> cancellations,
                       OrderCommand.ResultTarget target) {
        if (coreSequence <= 0 || commitSequence < 0 || laneMask == 0 || timestamp < 0 || position < 0 || commandId == null
                || count <= 0 || batchStorage == null || count > batchStorage.plans.length)
            throw new IllegalArgumentException("invalid direct settlement reservation");
        state.cancellation = false; directPublished = false; state.matcherPublicationDeferred = false;
        state.dispatched = false; state.queuedLaneMask = 0;
        state.expandLaneRoute = Long.bitCount(laneMask) == 1;
        state.directFailure = null;
        state.admissionRequired = false;
        state.admissionOrder = null;
        state.admissionUserId = state.admissionOrderId = 0;
        state.admissionResolved = false;
        state.admissionAccepted = false;
        state.admissionResultCode = CoreResultCode.NONE.wireCode();
        state.directCoreSequence = coreSequence;
        this.commitSequence = commitSequence; routedLaneMask = requiredLaneMask = laneMask;
        state.commitTimestamp = timestamp; state.commitClusterPosition = position;
        state.directCommandId = commandId;
        state.directShard = shard;
        state.authorizedCancellations = java.util.Objects.requireNonNull(cancellations);
        this.runtime = runtime; this.identities = identities; state.resultTarget = target;
        state.batchPlanCount = count;
        changes = runtime.acquireMatcherSettlementChanges(laneMask);
        changes.directPositionIdentities = true;
        state.collected = false;
        resetCompletions(runtime.topology().accountLaneCount());
        // 直达事件通过自己带间隔的完成数组交回回执，
        // 不再登记物化计划路径使用的逐 Lane 通知预期。
    }

    /**
     * Owner 在预约 Matcher 发布前选定撤单业务及用户，后续 Lane 只应用 Matcher 接受的撤单。
     */
    public void cancellation(long userId) {
        if (state.batchPlanCount == 0 || state.dispatched || directPublished || userId <= 0)
            throw new IllegalStateException("invalid direct cancellation preparation");
        state.cancellation = true;
        state.cancellationUserId = userId;
    }

    /**
     * 在单项事件发布前保存源触发单和业务触发时间，后续按 Matcher 结果生成成功或失败终态；每代只可准备一次。
     */
    public void triggerCompletion(com.surprising.aeron.model.CoreTriggerOrderState trigger,
                                  long timestamp) {
        if (state.dispatched || directPublished || state.batchPlanCount != 1 || trigger == null
                || timestamp < 0 || state.sourceTrigger != null)
            throw new IllegalStateException("invalid direct trigger preparation");
        state.sourceTrigger = trigger;
        state.triggeredAt = timestamp;
    }

    /**
     * 在单项事件预约发布前保存改单准入输入及新预留资产编号，供所属 Lane 原位执行。
     */
    public void replacement(com.surprising.aeron.service.order.OrderCommand.ReplacementAdmission admission, int assetId) {
        if (state.dispatched || directPublished || state.batchPlanCount != 1 || admission == null)
            throw new IllegalStateException("invalid direct replacement preparation");
        state.replacement = admission;
        state.replacementAssetId = assetId;
    }

    /**
     * 仅允许单项事件借用首项准入订单引用，普通不可变准入输入路径可能没有此运行时值。
     */
    public Order admittedOrder() {
        if (state.batchPlanCount != 1) throw new IllegalStateException("single direct order is required");
        return batchStorage.admittedOrders[0];
    }

    /**
     * 检查当前事件是否有正数有效计划项，不表示结果已经发布或账户已经完成。
     */
    public boolean direct() { return state.batchPlanCount != 0; }
    /**
     * 返回直达 Matcher 证据和 Lane 合并使用的 Core 序号，可能不同于最终提交序号。
     */
    public long coreSequence() { return state.directCoreSequence; }
    /**
     * 单 Matcher 发布方为实际路由中的 Lane 登记一次投递，拒绝越界、未预约、非路由和重复投递。
     */
    public void markLaneQueued(int laneId) {
        if (state.batchPlanCount == 0 || !state.dispatched || laneId < 0 || laneId >= Long.SIZE)
            throw new IllegalStateException("invalid direct settlement Lane queue");
        long bit = 1L << laneId;
        if ((routedLaneMask & bit) == 0 || (state.queuedLaneMask & bit) != 0) {
            throw new IllegalStateException("direct settlement Lane queue is outside its route");
        }
        state.queuedLaneMask |= bit;
    }
    /**
     * 供 Owner 检查结果已发布且 Matcher 已结束发布过程；尚不表示账户或财务工作完成。
     */
    public boolean ready() { return directPublished && !state.matcherPublicationDeferred; }

    /**
     * 账户只等待结果发布即可消费；其他 Lane 的投递可能仍在继续，事件仍受发布租约保护。
     */
    public boolean readyForLane() {
        return directPublished;
    }

    /**
     * 把准入回执编码转换为业务名称，尚为 NONE 时按当前接口约定返回 SUCCESS；不能据此判断回执已到达。
     */
    public String admissionResultCode() {
        return state.admissionResultCode == CoreResultCode.NONE.wireCode()
                ? "SUCCESS" : CoreResultCode.fromWireCode(state.admissionResultCode).name();
    }

    /**
     * 未要求普通下单准入时视为接受，否则返回已复制的准入接受标记；调用方仍须遵守回执发布顺序。
     */
    public boolean admissionAccepted() { return !state.admissionRequired || state.admissionAccepted; }

    /**
     * 在已登记普通下单准入路由的事件中保存不可变订单输入与用户，预留回执须匹配该订单编号。
     * @param order 普通下单已经解析的不可变准入订单输入
     */
    public void admissionOrder(OrderCommand.ResolvedPlaceOrder order, long userId) {
        if (state.batchPlanCount == 0 || !state.admissionRequired || order == null || userId <= 0) {
            throw new IllegalStateException("invalid place admission order");
        }
        state.admissionOrder = order;
        state.admissionUserId = userId;
        state.admissionOrderId = order.orderId();
    }

    /**
     * Matcher 校验 Lane 固定收据槽的订单身份，只保存接受标记和结果码，最后通过 volatile 标记发布回执；同代不允许重复发布。
     * @param reservationId 准入回执中的预留编号，必须等于本代准入订单编号
     * @param resultCode 准入编码或合成拒绝业务码，类型及约束以对应入口为准
     */
    public void admissionReceipt(long reservationId, boolean accepted, int resultCode) {
        if (state.batchPlanCount == 0 || !state.admissionRequired || state.admissionResolved || reservationId <= 0
                || reservationId != state.admissionOrderId
                || resultCode < 0) {
            throw new IllegalStateException("invalid place admission receipt");
        }
        state.admissionAccepted = accepted;
        state.admissionResultCode = resultCode;
        state.admissionResolved = true;
    }

    /**
     * 检查原生结果、合成拒绝码或执行故障是否已准备；包含故障的准备状态不等于成功或允许 Lane 消费。
     */
    public boolean resultPrepared() {
        return state.directNativeResult != null
                || state.directSyntheticResultCode != null || state.directFailure != null;
    }

    /**
     * 已预约且未发布时取得发布租约，阻止 Owner 在 Matcher 仍持有事件期间回收。
     */
    public void beginMatcherPublication() {
        if (state.batchPlanCount == 0 || !state.dispatched || directPublished || state.matcherPublicationDeferred) {
            throw new IllegalStateException("invalid matcher publication start");
        }
        state.matcherPublicationDeferred = true;
    }

    /**
     * 结束当前发布过程：已有结果或故障则继续通知 Lane 并交出租约，否则只解除延后标记；非活动或未延后的事件直接返回。
     */
    public void completeMatcherPublication() {
        if (state.batchPlanCount == 0 || !state.matcherPublicationDeferred) return;
        if (resultPrepared()) notifyDirectPublication();
        else state.matcherPublicationDeferred = false;
    }

    /**
     * 校验单项结果的命令、序号、分片及订单证据，按撤单、普通准入或已有订单构建计划；保留已成功前置撤单，触发单补终态，最后准备实际路由和发布。构建异常登记为直达故障后继续抛出。
     * @param result 当前需要保存的原生 Matcher 结果，借用有效期受事件租约限制
     */
    public void publishDirectNativeResult(exchange.core2.core.common.MatcherResult result,
            long coreSequence, long commandIdMostSignificantBits, long commandIdLeastSignificantBits,
            long orderId, long matcherSequence, int matcherShard) {
        if (!state.dispatched || directPublished || state.batchPlanCount != 1 || result == null
                || coreSequence != state.directCoreSequence || state.directCommandId == null
                || commandIdMostSignificantBits != state.directCommandId.getMostSignificantBits()
                || commandIdLeastSignificantBits != state.directCommandId.getLeastSignificantBits()
                || matcherShard != state.directShard || matcherSequence <= 0) {
            throw new IllegalStateException("direct native matcher result proof is inconsistent");
        }
        try {
            // 命令或计划保留的准入订单引用，普通不可变准入路径可以为空。
            Order admitted = batchStorage.admittedOrders[0];
            if (state.cancellation) {
                if (batchStorage.cancellationOrderIds[0] != orderId)
                    throw new IllegalStateException("direct cancellation order is inconsistent");
                batchStorage.plans[0].buildDirectNativeCancellation(state.directCoreSequence, state.cancellationUserId,
                        orderId, result, runtime);
            } else if (state.admissionRequired && state.admissionAccepted) {
                if (state.admissionOrder == null || state.admissionOrder.orderId() != orderId)
                    throw new IllegalStateException("direct admission order is inconsistent");
                batchStorage.plans[0].buildDirectNative(state.directCoreSequence, state.admissionUserId, state.admissionOrder,
                        batchStorage.instruments[0], result, runtime);
            } else {
                if (admitted == null || admitted.orderId() != orderId)
                    throw new IllegalStateException("direct matcher order is inconsistent");
                batchStorage.plans[0].buildDirectNative(state.directCoreSequence, admitted,
                        batchStorage.instruments[0], result, runtime);
            }
            state.directNativeResult = result;
            state.directMatcherSequence = matcherSequence;
            state.directLastMatcherSequence = matcherSequence;
            state.directNativeItemCount = 1;
            state.directNativeAccepted = result.resultCode() == exchange.core2.core.common.cmd.CommandResultCode.SUCCESS
                    || result.resultCode() == exchange.core2.core.common.cmd.CommandResultCode.ACCEPTED;
            // 新单拒绝也可能已有成功撤单前缀，
            // 保留部分变化结果，提交时仍核对 Lane 最终授权。
            state.directNativeStateChanged = state.directAcceptedCancellationCount != 0 && !state.directNativeAccepted;
            if (state.sourceTrigger != null) {
                batchStorage.plans[0].completeTrigger(state.directNativeAccepted
                        ? state.sourceTrigger.toMatchedCompletion(
                                batchStorage.admittedOrders[0].orderId(), state.triggeredAt)
                        : state.sourceTrigger.toRejectedCompletion(
                                result.resultCode().name(), state.triggeredAt));
            }
            batchStorage.plans[0].acceptedPreCancellations(
                    state.directAcceptedCancellations, state.directAcceptedCancellationCount);
            finishDirectPublication();
        } catch (/* 构建或保存原生结果时捕获的执行异常，登记到本事件后继续抛出。 */ Throwable failure) { failDirect(failure); throw failure; }
    }

    /**
     * 严格按项下标和递增 Matcher 序号保存批量原生事实，首项承载已成功前置撤单及汇总接口的首项结果；每项记录接受标记，全部有效项齐备后才结束发布准备。
     * @param result 当前需要保存的原生 Matcher 结果，借用有效期受事件租约限制
     */
    public void publishDirectNativeBatchItem(exchange.core2.core.common.MatcherResult result,
            int index, long coreSequence,
            long commandIdMostSignificantBits, long commandIdLeastSignificantBits,
            long orderId, long matcherSequence, int matcherShard) {
        if (!state.dispatched || directPublished || result == null || state.batchPlanCount <= 0
                || index != state.directNativeItemCount || index < 0 || index >= state.batchPlanCount
                || coreSequence != state.directCoreSequence || state.directCommandId == null
                || commandIdMostSignificantBits != state.directCommandId.getMostSignificantBits()
                || commandIdLeastSignificantBits != state.directCommandId.getLeastSignificantBits()
                || matcherShard != state.directShard || matcherSequence <= state.directLastMatcherSequence) {
            throw new IllegalStateException("direct native matcher batch proof is inconsistent");
        }
        try {
            // 命令或计划保留的准入订单引用，普通不可变准入路径可以为空。
            Order admitted = batchStorage.admittedOrders[index];
            if (state.cancellation ? batchStorage.cancellationOrderIds[index] != orderId
                    : admitted == null || admitted.orderId() != orderId)
                throw new IllegalStateException("direct matcher batch order is inconsistent");
            if (state.cancellation) {
                batchStorage.plans[index].buildDirectNativeCancellation(state.directCoreSequence, state.cancellationUserId,
                        orderId, result, runtime);
            } else {
                batchStorage.plans[index].buildDirectNative(state.directCoreSequence, admitted,
                        batchStorage.instruments[index], result, runtime);
            }
            if (index == 0) {
                batchStorage.plans[index].acceptedPreCancellations(
                        state.directAcceptedCancellations, state.directAcceptedCancellationCount);
                state.directNativeResult = result;
                state.directMatcherSequence = matcherSequence;
                state.directNativeAccepted = nativeAccepted(result);
            }
            if (state.resultTarget != null) state.resultTarget.matcherResult(index, nativeAccepted(result));
            state.directLastMatcherSequence = matcherSequence;
            state.directNativeItemCount++;
            if (state.directNativeItemCount == state.batchPlanCount) finishDirectPublication();
        } catch (/* 构建或保存原生结果时捕获的执行异常，登记到本事件后继续抛出。 */ Throwable failure) { failDirect(failure); throw failure; }
    }

    /**
     * 关联原单撤单与新单结果，区分新单接受、post-only 拒绝和其他部分变化；记录允许保留的原单撤单，未下出的新单从变化身份中移除，随后发布完整结算事实。
     * @param result 当前需要保存的原生 Matcher 结果，借用有效期受事件租约限制
     */
    public void publishDirectNativeReplacement(
            exchange.core2.core.common.MatcherResult cancelResult,
            exchange.core2.core.common.MatcherResult result,
            long coreSequence, long commandIdMostSignificantBits, long commandIdLeastSignificantBits,
            long orderId, long matcherSequence, int matcherShard) {
        if (!state.dispatched || directPublished || state.replacement == null || state.batchPlanCount != 1
                || cancelResult == null || result == null || coreSequence != state.directCoreSequence
                || state.directCommandId == null
                || commandIdMostSignificantBits != state.directCommandId.getMostSignificantBits()
                || commandIdLeastSignificantBits != state.directCommandId.getLeastSignificantBits()
                || matcherShard != state.directShard || matcherSequence <= 0
                || state.replacement.resolved().orderId() != orderId) {
            throw new IllegalStateException("direct native replacement proof is inconsistent");
        }
        try {
            // 替换原单的撤单是否已被 Matcher 接受。
            boolean cancellationAccepted = nativeAccepted(cancelResult);
            // 原单撤单成功且新订单原生结果也被接受的标记。
            boolean placementAccepted = cancellationAccepted && nativeAccepted(result);
            // 原单已撤但新单被 post-only 规则拒绝的已知部分变化。
            boolean businessRejection = cancellationAccepted && !placementAccepted
                    && result.resultCode() == exchange.core2.core.common.cmd.CommandResultCode.MATCHING_POST_ONLY_FAILED;
            // 命令或计划保留的准入订单引用，普通不可变准入路径可以为空。
            Order admitted = batchStorage.admittedOrders[0];
            if (admitted == null) throw new IllegalStateException("replacement order is missing");
            batchStorage.plans[0].buildDirectNative(state.directCoreSequence, admitted,
                    batchStorage.instruments[0], result, runtime);
            if (placementAccepted || businessRejection)
                batchStorage.plans[0].acceptedReplacementCancellation(state.replacement.originalOrderId());
            batchStorage.plans[0].acceptedPreCancellations(
                    state.directAcceptedCancellations, state.directAcceptedCancellationCount);
            if (!placementAccepted) batchStorage.plans[0].omitUnplacedOrder();
            state.directNativeResult = result;
            state.directMatcherSequence = matcherSequence;
            state.directLastMatcherSequence = matcherSequence;
            state.directNativeItemCount = 1;
            state.directNativeAccepted = placementAccepted;
            state.directNativeStateChanged = cancellationAccepted && !placementAccepted;
            finishDirectPublication();
        } catch (/* 构建或保存原生结果时捕获的执行异常，登记到本事件后继续抛出。 */ Throwable failure) { failDirect(failure); throw failure; }
    }

    /**
     * 为普通下单准入拒绝校验命令证据并构造无原生成交的拒绝计划，使已登记账户路径能够交回回执，不创建订单后值。
     * @param resultCode 准入编码或合成拒绝业务码，类型及约束以对应入口为准
     */
    public void publishDirectRejection(String resultCode, long coreSequence,
            long commandIdMostSignificantBits, long commandIdLeastSignificantBits,
            long orderId, long matcherSequence, int matcherShard) {
        if (state.batchPlanCount == 0 || !state.dispatched || directPublished || !state.admissionRequired || state.admissionAccepted
                || resultCode == null || resultCode.isBlank() || coreSequence != state.directCoreSequence
                || state.directCommandId == null
                || commandIdMostSignificantBits != state.directCommandId.getMostSignificantBits()
                || commandIdLeastSignificantBits != state.directCommandId.getLeastSignificantBits()
                || orderId != state.admissionOrderId || matcherSequence <= 0 || matcherShard != state.directShard)
            throw new IllegalStateException("direct matcher rejection proof is inconsistent");
        try {
            batchStorage.plans[0].prepareRejectedDirect(
                    state.directCoreSequence, state.admissionUserId, state.admissionOrderId, routedLaneMask);
            state.directSyntheticResultCode = resultCode;
            state.directMatcherSequence = state.directLastMatcherSequence = matcherSequence;
            state.directNativeAccepted = false;
            state.directNativeItemCount = 1;
            finishDirectPublication();
        } catch (/* 构建或保存原生结果时捕获的执行异常，登记到本事件后继续抛出。 */ Throwable failure) { failDirect(failure); throw failure; }
    }

    /**
     * 仅原生 SUCCESS 与 ACCEPTED 视为接受。
     * @param result 当前需要保存的原生 Matcher 结果，借用有效期受事件租约限制
     */
    private static boolean nativeAccepted(exchange.core2.core.common.MatcherResult result) {
        return result.resultCode() == exchange.core2.core.common.cmd.CommandResultCode.SUCCESS
                || result.resultCode() == exchange.core2.core.common.cmd.CommandResultCode.ACCEPTED;
    }

    /**
     * 准入回执已复制且接受后，将 Owner 已授权撤单与 Lane 新授权撤单合并去重并按编号排序，保存为本代只读授权列表；不表示这些撤单已经成功。
     */
    public void addAdmissionCancellations(java.util.List<Long> orderIds) {
        if (state.batchPlanCount == 0 || directPublished || !state.admissionResolved || !state.admissionAccepted)
            throw new IllegalStateException("place admission cancellations are not ready");
        if (orderIds.isEmpty()) return;
        var combined = new org.eclipse.collections.impl.set.mutable.primitive.LongHashSet();
        for (int index = 0; index < state.authorizedCancellations.size(); index++)
            combined.add(cancellationIdAt(state.authorizedCancellations, index));
        for (int index = 0; index < orderIds.size(); index++) {
            long orderId = cancellationIdAt(orderIds, index);
            if (orderId <= 0) throw new IllegalArgumentException("invalid admission cancellation order");
            combined.add(orderId);
        }
        // 去重后按订单编号排序的自有原语数组，交给只读授权列表接管。
        long[] sorted = combined.toArray();
        java.util.Arrays.sort(sorted);
        state.authorizedCancellations = com.surprising.aeron.support.collection.ImmutableLongArrayList.takeOwnership(sorted);
    }

    /**
     * 准入成功后供 Matcher 借用合并后的授权撤单列表，未满足回执条件时拒绝读取。
     */
    public java.util.List<Long> admissionCancellations() {
        if (state.batchPlanCount == 0 || !state.admissionResolved || !state.admissionAccepted)
            throw new IllegalStateException("place admission cancellations are not ready");
        return state.authorizedCancellations;
    }

    /**
     * 结果发布并确认无故障后，向 Owner 返回最终授权列表以核对成功前缀；返回的是授权全集，不是实际成功项数组。
     */
    public java.util.List<Long> completedPreMatchingCancellations() {
        requireDirectResult();
        return state.authorizedCancellations;
    }

    /**
     * 按下标读取撤单编号，专用原语列表直接读取 long，普通列表走既有装箱接口。
     */
    private static long cancellationIdAt(java.util.List<Long> orderIds, int index) {
        return orderIds instanceof com.surprising.aeron.support.collection.ImmutableLongArrayList /* 已经识别为专用原语只读列表的撤单编号视图。 */ primitive
                ? primitive.valueAt(index) : orderIds.get(index);
    }

    /**
     * 结果发布前记录一项已经接受的授权撤单，必要时扩容自有数组；这里检查授权但不另行去重，调用方保证记录顺序。
     */
    public void recordDirectPreCancellation(long orderId) {
        boolean authorized = state.authorizedCancellations instanceof com.surprising.aeron.support.collection.ImmutableLongArrayList /* 已经识别为专用原语只读列表的撤单编号视图。 */ primitive
                ? primitive.containsLong(orderId) : state.authorizedCancellations.contains(orderId);
        if (state.batchPlanCount == 0 || directPublished || orderId <= 0 || !authorized)
            throw new IllegalStateException("unauthorized direct pre-cancellation");
        if (state.directAcceptedCancellations == null)
            state.directAcceptedCancellations = new long[Math.max(4, state.authorizedCancellations.size())];
        if (state.directAcceptedCancellationCount == state.directAcceptedCancellations.length)
            state.directAcceptedCancellations = java.util.Arrays.copyOf(
                    state.directAcceptedCancellations, state.directAcceptedCancellations.length * 2);
        state.directAcceptedCancellations[state.directAcceptedCancellationCount++] = orderId;
    }

    /**
     * 检查是否保存了原生结果引用，合成准入拒绝没有原生结果。
     */
    public boolean hasDirectNativeResult() { return state.directNativeResult != null; }
    /**
     * 返回本代结果证据所属 Matcher 分片。
     */
    public int directMatcherShard() { return state.directShard; }
    /**
     * 合并各有效计划的实际业务 Lane 与订单数量，核对原依赖范围；单 Lane 初始路由允许扩展，固定多 Lane 路由保留所有票据。准备财务差额及变化容量，未延后时立即交接发布。
     */
    private void finishDirectPublication() {
        // 所有有效计划合并得到的实际业务 Lane 位图。
        long actualLanes = 0; int orders = 0;
        for (int index = 0; index < state.batchPlanCount; index++) {
            actualLanes |= batchStorage.plans[index].requiredLaneMask();
            orders = Math.addExact(orders, batchStorage.plans[index].orderCount());
        }
        // 初始单 Lane 路由保留主动账户，Matcher 构建事实时补充发现的 maker Lane。
        // 各 Lane 从自己的 Matcher 邮箱按序应用；固定多 Lane 路由的既有票据也必须消费。
        if (actualLanes == 0
                || !state.expandLaneRoute && (actualLanes & ~routedLaneMask) != 0
                || state.expandLaneRoute && (routedLaneMask & ~actualLanes) != 0)
            throw new IllegalStateException("matcher result escaped its admitted Lane dependency scope");
        if (state.expandLaneRoute) routedLaneMask = actualLanes;
        requiredLaneMask = actualLanes;
        treasuryTrades = hasTrade(batchStorage.plans, state.batchPlanCount);
        prepareTreasuryDeltas(actualLanes, treasuryTrades);
        changes.ensureOrderCapacity(orders, actualLanes);
        if (!state.matcherPublicationDeferred) notifyDirectPublication();
    }

    /**
     * 为已预约且未发布的活动事件保留执行异常；未延后时继续发布故障事实，非活动或已发布事件不重复处理。
     * @param failure 本代 Matcher 执行或构建失败的非空原因
     */
    public void failDirect(Throwable failure) {
        if (state.batchPlanCount == 0 || directPublished) return;
        if (!state.dispatched) throw new IllegalStateException("Matcher publication was not reserved");
        state.directFailure = java.util.Objects.requireNonNull(failure);
        if (!state.matcherPublicationDeferred) notifyDirectPublication();
    }

    /**
     * 下单步骤 4.3：持有发布租约时释放原 Matcher 提交占用，再发布结果并完成各 Lane 投递，最后解除租约。此后不再读可被回收的事件字段。
     */
    private void notifyDirectPublication() {
        // 发布完成前提前保存的运行时通知目标，完成位发布后只使用此局部引用。
        TradingAccountRuntime completionRuntime = runtime;
        // 队列中的已发布事实一旦可见，Lane 就能开始消费；
        // 发布方释放传输占用并完成全部入队前，Owner 仍不能回收事件。
        state.matcherPublicationDeferred = true;
        releaseMatcherCompletionBeforeSignal();
        directPublished = true;
        completionRuntime.publishMatcherOwned(this);
        state.matcherPublicationDeferred = false;
    }

    /**
     * 由 Matcher 管线保存已有传输上下文的释放回调与分片，避免每命令另建回调；同代不重复登记，发布后不能修改。
     */
    public void matcherCompletionRoute(MatcherCompletionRoute route, int matcherShard) {
        if (state.batchPlanCount == 0 || directPublished || state.matcherCompletionRoute != null || matcherShard < 0) {
            throw new IllegalStateException("invalid matcher completion release callback");
        }
        state.matcherCompletionRoute = java.util.Objects.requireNonNull(route);
        state.matcherCompletionShard = matcherShard;
    }

    /**
     * 先把释放回调及分片移到局部值并清除事件引用，再通知传输上下文释放占用，后续 Lane 投递仍受发布租约保护。
     */
    private void releaseMatcherCompletionBeforeSignal() {
        MatcherCompletionRoute route = state.matcherCompletionRoute;
        // 与本次传输释放回调一起取出的 Matcher 分片编号。
        int shard = state.matcherCompletionShard;
        state.matcherCompletionRoute = null;
        state.matcherCompletionShard = -1;
        if (route != null) route.releaseMatcherSubmission(shard);
    }

    /**
     * 检查已发布且无执行故障后，以本事件自身提供 OrderMatchingResult 视图，视图只在事件租约内有效。
     */
    public com.surprising.aeron.service.matcher.OrderMatchingResult directResult() {
        requireDirectResult();
        return this;
    }
    /**
     * 检查结果已发布、没有直达故障且持有原生结果或合成拒绝码；不要求账户或财务工作已完成。
     */
    public boolean directResultAvailable() {
        return directPublished && state.directFailure == null
                && hasEventOwnedResult();
    }
    /**
     * 作为 OrderMatchingResult 接口返回已保存接受标记；调用方先遵守结果发布边界，批量对应首项。
     */
    @Override public boolean accepted() {
        return state.directNativeAccepted;
    }
    /**
     * 返回合成准入拒绝码或原生结果码名称，调用方须先确认可读结果。
     */
    @Override public String resultCode() {
        if (state.directSyntheticResultCode != null) return state.directSyntheticResultCode;
        return state.directNativeResult.resultCode().name();
    }
    /**
     * 直达接口不另建逐单撤单对象列表，始终返回空列表；成功前缀由计划原语编号和授权事实提供。
     */
    @Override public java.util.List<com.surprising.aeron.service.matcher.OrderCancelResult> cancellations() {
        return java.util.List.of();
    }
    /**
     * 按部分变化和接受标记分类：post-only 拒绝保留已知前缀，其他未能确认的部分变化视为致命分歧；无部分变化时返回已应用或未变化拒绝。
     */
    @Override public com.surprising.aeron.service.matcher.OrderMatchingResult.Outcome outcome() {
        if (state.directNativeStateChanged) {
            return state.directNativeResult != null
                    && state.directNativeResult.resultCode() == exchange.core2.core.common.cmd.CommandResultCode.MATCHING_POST_ONLY_FAILED
                    ? com.surprising.aeron.service.matcher.OrderMatchingResult.Outcome.KNOWN_PREFIX_APPLIED
                    : com.surprising.aeron.service.matcher.OrderMatchingResult.Outcome.FATAL_DIVERGENCE;
        }
        return state.directNativeAccepted
                ? com.surprising.aeron.service.matcher.OrderMatchingResult.Outcome.APPLIED
                : com.surprising.aeron.service.matcher.OrderMatchingResult.Outcome.REJECTED_UNCHANGED;
    }
    /**
     * 为证据接口返回本代 Core 序号。
     */
    @Override public long nativeCoreSequence() {
        return state.directCoreSequence;
    }
    /**
     * 为证据接口返回本代首项 Matcher 序号。
     */
    @Override public long nativeMatcherSequence() {
        return state.directMatcherSequence;
    }
    /**
     * 为证据接口返回本代 Matcher 分片。
     */
    @Override public int nativeMatcherShardId() {
        return state.directShard;
    }
    /**
     * 直接读取本代命令 UUID 的高低位进行比较，调用方须在有效事件代次内使用。
     */
    @Override public boolean nativeMatches(long most, long least) {
        return state.directCommandId == null ? most == 0 && least == 0
                : state.directCommandId.getMostSignificantBits() == most
                && state.directCommandId.getLeastSignificantBits() == least;
    }
    /**
     * 合成准入拒绝返回空事件列表，其他结果借用原生事件列表；不复制事件。
     */
    @Override public java.util.List<exchange.core2.core.common.MatcherResult.MatcherEvent> matcherEvents() {
        if (state.directSyntheticResultCode != null) return java.util.List.of();
        return state.directNativeResult.events();
    }
    /**
     * 检查本事件是否持有原生结果或合成拒绝码，执行故障本身不算可推进证据的结果。
     */
    private boolean hasEventOwnedResult() {
        return state.directNativeResult != null || state.directSyntheticResultCode != null;
    }
    /**
     * 要求存在活动计划且直达结果已发布；如有执行故障，以其为原因抛出，防止当成普通业务拒绝。
     */
    private void requireDirectResult() {
        if (state.batchPlanCount == 0 || !directPublished) throw new IllegalStateException("direct result is not published");
        if (state.directFailure != null) throw new IllegalStateException("direct matcher failed", state.directFailure);
    }

    /**
     * 校验普通下单准入 Lane，并为活动事件设置一次需要准入回执的边界。
     */
    public void admissionRoute(int laneId) {
        if (state.batchPlanCount == 0 || state.admissionRequired || laneId < 0) {
            throw new IllegalStateException("invalid place admission route");
        }
        state.admissionRequired = true;
    }

    /**
     * Owner 在交给 Matcher 前预约逻辑派发状态，仅设置预约标记，不接触任何 Lane 队列。
     */
    public void reserveMatcherPublication() {
        if (state.batchPlanCount == 0 || state.dispatched || directPublished) {
            throw new IllegalStateException("invalid Matcher publication reservation");
        }
        state.dispatched = true;
    }

    /**
     * 仅在事件完成且 changes 已交出后清理命令、准入、结果及槽引用，保留批量数组和财务槽容量；下一代 prepareDirect 再重置完成位及 Owner 缓存，不能把此方法当等待在途消费者。
     */
    public void clear() {
        if (!complete() || changes != null) {
            throw new IllegalStateException("cannot recycle an incomplete matcher settlement");
        }
        if (treasurySettlement != null) treasurySettlement.clear();
        state.cancellation = false; directPublished = false; state.directFailure = null; state.dispatched = false;
        state.queuedLaneMask = 0;
        state.expandLaneRoute = false;
        state.matcherPublicationDeferred = false;
        state.cancellationUserId = 0;
        state.replacement = null;
        state.replacementAssetId = 0;
        state.sourceTrigger = null;
        state.triggeredAt = 0;
        state.matcherCompletionRoute = null;
        state.matcherCompletionShard = -1;
        state.admissionRequired = false;
        state.admissionOrder = null;
        state.admissionUserId = state.admissionOrderId = 0;
        state.admissionResolved = false;
        state.admissionAccepted = false;
        state.admissionResultCode = CoreResultCode.NONE.wireCode();
        state.directCoreSequence = 0;
        state.directCommandId = null;
        state.authorizedCancellations = java.util.List.of();
        state.directNativeResult = null;
        state.directMatcherSequence = state.directLastMatcherSequence = 0;
        state.directNativeItemCount = 0;
        state.directAcceptedCancellationCount = 0;
        state.directNativeAccepted = state.directNativeStateChanged = false;
        state.directSyntheticResultCode = null;
        routedLaneMask = 0;
        state.resultTarget = null;
        if (batchStorage != null) batchStorage.clear();
        state.batchPlanCount = 0;
        runtime = null;
        identities = null;
        treasuryTrades = false;
        state.collected = false;
    }

    /**
     * 下单步骤 4.4：所属 Lane 应用本代各计划、整理终态并发布完成位；仅消费票据而无业务变化的路由直接回执。准入拒绝不走正常成交计算，其他路径按撤单、改单和产品结算处理，非零提交序号才推进账户水位。
     * @param lane 当前线程持有并执行账户工作的所属 Lane
     */
    public void execute(AccountPartition lane) {
        requireDirectResult();
        int laneId = lane.laneId();
        long laneMask = 1L << laneId;
        if ((requiredLaneMask & laneMask) == 0) {
            if ((routedLaneMask & laneMask) != 0) {
                publishCompletion(lane);
                return;
            }
            throw new IllegalStateException("matcher fact was routed to an unrelated account lane");
        }
        // 普通下单事实排在同一 Lane 的准入之后；准入拒绝时只整理完成回执，
        // 不执行正常成交资金计算，让 Owner 能退休已提交的事件。
        if (state.admissionRequired && !state.admissionResolved) {
            throw new IllegalStateException("place admission receipt is missing");
        }
        if (state.admissionRequired && !state.admissionAccepted) {
            changes().prepareLaneTerminal(laneId, identities, lane, state.directCoreSequence);
            publishCompletion(lane);
            return;
        }
        runtime.enterMatcherSettlementScope(lane, changes());
        try {
            RuntimeTreasuryDelta delta = !treasuryTrades
                    ? EMPTY_TREASURY_DELTA : touchedLaneTreasuryDeltas[laneSlot(laneId)];
            for (int index = 0; index < state.batchPlanCount; index++) {
                // 本代有效前缀中当前需要由所属 Lane 应用的计划。
                TradeSettlement.Plan batchPlan = batchStorage.plans[index];
                if ((batchPlan.requiredLaneMask() & laneMask) == 0) continue;
                applyPlan(lane, laneId, batchPlan, batchStorage.instruments[index],
                        batchStorage.baseAssetIds[index], batchStorage.quoteAssetIds[index],
                        batchStorage.settleAssetIds[index], delta,
                        batchStorage.admittedOrders[index]);
                if (commitSequence != 0 || state.cancellation) {
                    runtime.stampMatcherOrders(
                            lane, batchPlan, state.commitTimestamp, state.commitClusterPosition);
                }
            }
            changes.prepareLaneTerminal(laneId, identities, lane, state.directCoreSequence);
            if (state.resultTarget != null
                    && laneId == runtime.topology().accountLaneId(plan().activeUserId())) {
                OrderCommand.ResultTarget.capture(state.resultTarget, changes.laneDeltas[laneId], identities, lane);
            }
            if (commitSequence != 0) {
                lane.applied(state.directShard, commitSequence);
                lane.committed(state.directShard, commitSequence);
                runtime.publishLaneCommittedSequence(lane);
            }
        } finally {
            runtime.exitMatcherSettlementScope(lane, changes);
        }
        // 每个 Lane 独立发布自己的完成位，Owner 核对事件整体完成边界，
        // 避免所有账户线程竞争写同一个原子 OR 位图。
        publishCompletion(lane);
    }

    /**
     * Lane 记录操作并以 release 发布自己独立的完成标记，拒绝重复完成；先保存通知运行时，发布后只用局部值发出就绪通知。
     * @param lane 当前线程持有并执行账户工作的所属 Lane
     */
    private void publishCompletion(AccountPartition lane) {
        int laneId = lane.laneId();
        // 发布完成前提前保存的运行时通知目标，完成位发布后只使用此局部引用。
        TradingAccountRuntime completionRuntime = runtime;
        lane.recordSettlementOperation();
        int completionOffset = laneId * CACHE_LINE_LONGS;
        if ((long) LONGS.getAcquire(completedLanes, completionOffset) != 0) {
            throw new IllegalStateException("account lane completed the same matcher fact twice");
        }
        LONGS.setRelease(completedLanes, completionOffset, 1L);
        completionRuntime.publishDirectMatcherSettlementReady(laneId);
    }

    /**
     * 所属 Lane 先处理直接撤单或改单，再核对本地订单、应用已接受前置撤单、拒单或产品成交；完成待结算预留及准入值发布，最后写本 Lane 负责的触发终态。
     * @param lane 当前线程持有并执行账户工作的所属 Lane
     * @param delta 本 Lane 独占的系统财务差额槽，无成交时使用只读空值
     * @param admissionVersion 命令保存的准入订单值，可为空；最终权威后值从所属 Lane 读取
     */
    private void applyPlan(AccountPartition lane, int laneId, TradeSettlement.Plan value,
                           CoreInstrument valueInstrument, int valueBaseAssetId,
                           int valueQuoteAssetId, int valueSettleAssetId, RuntimeTreasuryDelta delta,
                           Order admissionVersion) {
        if (state.cancellation) {
            if (!value.rejectedTaker())
                runtime.cancelOrderInLane(lane, value.activeUserId(), value.takerOrderId(),
                        state.commitTimestamp, state.commitClusterPosition);
            return;
        }
        if (state.replacement != null) {
            applyReplacement(lane, value);
            if (!accepted()) return;
        }
        value.validateDirectLane(lane, runtime, identities, valueInstrument);
        for (int index = 0; state.replacement == null && index < value.preCancellationCount(); index++) {
            long orderId = value.preCancellationOrderId(index);
            Order order = lane.order(orderId);
            if (order != null && order.status() == CoreOrderStatus.OPEN) {
                runtime.cancelOrderInLane(lane, order.userId(), order, -1, -1);
            }
        }
        if (value.rejectedTaker()) {
            if (lane.laneId() == runtime.accountLaneId(value.activeUserId()))
                runtime.rejectOrderInLane(lane, value.activeUserId(), value.takerOrderId(), state.commitTimestamp, state.commitClusterPosition);
        } else if (valueInstrument.contractType().productLine().isDerivative()) {
            DerivativeTradeSettlementService.applyLane(value.takerOrderId(), value, lane, laneId,
                    runtime, identities, valueInstrument, valueSettleAssetId, delta, state.commitTimestamp, state.commitClusterPosition);
        } else {
            SpotTradeSettlementService.applyLane(value.takerOrderId(), value, lane, laneId,
                    runtime, valueInstrument, valueBaseAssetId, valueQuoteAssetId, delta, state.commitTimestamp, state.commitClusterPosition);
        }
        runtime.completeMatcherPendingReservations(lane, value, admissionVersion);
        publishAdmittedState(lane, value, admissionVersion);
        // 本计划准备的触发单终态，仅其用户所属 Lane 写入。
        var trigger = value.completedTrigger();
        if (trigger != null && lane.laneId() == runtime.accountLaneId(trigger.userId())) runtime.putTriggerOrder(lane, trigger);
    }

    /**
     * 把本代准入用户、订单及预留的 Lane 最终值加入同一变化缓冲，包括没有成交的挂单；只在用户所属 Lane 执行，已有变化键按缓冲规则合并。
     * @param lane 当前线程持有并执行账户工作的所属 Lane
     * @param admissionVersion 命令保存的准入订单值，可为空；最终权威后值从所属 Lane 读取
     */
    private void publishAdmittedState(
            AccountPartition lane, TradeSettlement.Plan value, Order admissionVersion) {
        // 命令或计划保留的准入订单引用，普通不可变准入路径可以为空。
        Order admitted = admissionVersion == null ? value.admittedTaker() : admissionVersion;
        // 本事件是否持有已经接受的普通下单不可变准入输入。
        boolean eventAdmission = state.admissionRequired && state.admissionAccepted && state.admissionOrder != null;
        long userId = admitted != null ? admitted.userId()
                : eventAdmission ? state.admissionUserId : value.activeUserId();
        long orderId = admitted != null ? admitted.orderId()
                : eventAdmission ? state.admissionOrderId : value.takerOrderId();
        if ((admitted == null && !eventAdmission)
                || lane.laneId() != runtime.accountLaneId(userId)) return;
        runtime.publishUser(lane, userId, lane.user(userId));
        runtime.publishOrder(lane, orderId, lane.order(orderId));
        runtime.publishReservation(lane, orderId, lane.reservation(orderId));
    }

    /**
     * 只在改单用户所属 Lane 核对原订单及用户版本，先执行已经接受的前置撤单；新单接受后准备客户身份、捕获余额前后值并放置新订单和预留，拒绝的新单不落账户。
     * @param lane 当前线程持有并执行账户工作的所属 Lane
     */
    private void applyReplacement(AccountPartition lane, TradeSettlement.Plan value) {
        if (lane.laneId() != runtime.accountLaneId(state.replacement.userId())) return;
        long userId = state.replacement.userId();
        // 改单原订单的 Lane 权威值，版本必须仍等于准入时记录。
        Order original = lane.order(state.replacement.originalOrderId());
        // 改单用户的 Lane 权威账户值，版本必须仍与准入输入一致。
        UserAccount user = lane.user(userId);
        if (original == null || original.revision() != state.replacement.originalOrderRevision()
                || user == null || user.revision() != state.replacement.userRevision())
            throw new IllegalStateException("replace admission changed before Lane execution: order="
                    + (original == null ? "missing" : original.revision()) + '/'
                    + state.replacement.originalOrderRevision() + " user="
                    + (user == null ? "missing" : user.revision()) + '/' + state.replacement.userRevision());
        for (int index = 0; index < value.preCancellationCount(); index++)
            runtime.cancelOrderInLane(lane, userId, value.preCancellationOrderId(index),
                    state.commitTimestamp, state.commitClusterPosition);
        if (!accepted()) return;
        // 本事件替换订单命令槽中的新单输入值。
        Order prepared = batchStorage.admittedOrders[0];
        if (lane.order(state.replacement.originalOrderId()).status() != CoreOrderStatus.CANCELED)
            throw new IllegalStateException("accepted replacement did not cancel original order");
        long clientKey = RuntimeIdentityRegistry.clientKeyValue(
                identities.prepareClientKeyInLane(lane, userId, prepared.clientOrderId()));
        runtime.accountRollback().captureBalanceBefore(lane, userId, state.replacementAssetId);
        runtime.placeOrderInLane(lane, userId, state.replacement.resolved(), state.directCommandId,
                state.replacement.requiredReservationUnits(), clientKey, prepared.symbolId(), state.replacementAssetId,
                state.directCoreSequence, prepared, state.commitTimestamp, state.commitClusterPosition);
        runtime.publishUser(lane, userId, lane.user(userId));
        runtime.publishOrder(lane, prepared.orderId(), lane.order(prepared.orderId()));
        runtime.publishReservation(lane, prepared.orderId(), lane.reservation(prepared.orderId()));
        runtime.accountRollback().captureBalanceAfter(lane, userId, state.replacementAssetId);
    }

    /**
     * 按实际参与 Lane 的紧凑顺序保留财务差额槽，容量不足才扩展；每次真实结果确定后清空所有保留槽，无成交复用也清空旧金额。
     */
    private void prepareTreasuryDeltas(long laneMask, boolean trades) {
        if (trades) {
            // 本代实际业务 Lane 数量，决定紧凑财务槽的有效前缀。
            int touchedLaneCount = Long.bitCount(laneMask);
            int capacity = touchedLaneTreasuryDeltas == null ? 0 : touchedLaneTreasuryDeltas.length;
            if (capacity < touchedLaneCount) {
                touchedLaneTreasuryDeltas = capacity == 0
                        ? new RuntimeTreasuryDelta[touchedLaneCount]
                        : java.util.Arrays.copyOf(touchedLaneTreasuryDeltas, touchedLaneCount);
                for (int index = capacity; index < touchedLaneCount; index++) {
                    touchedLaneTreasuryDeltas[index] = new RuntimeTreasuryDelta();
                }
            }
        }
        // 实际事实确定后每代清空一次，包括上一代有成交而本代无成交的复用。
        if (touchedLaneTreasuryDeltas != null)
            for (RuntimeTreasuryDelta delta : touchedLaneTreasuryDeltas) delta.clear();
    }

    /**
     * 只遍历本代有效计划前缀，任一项有成交则返回 true，不读取保留容量中的旧尾部。
     */
    private static boolean hasTrade(TradeSettlement.Plan[] plans, int planCount) {
        for (int index = 0; index < planCount; index++) if (plans[index].tradeCount() != 0) return true;
        return false;
    }

    /**
     * Owner 仅在发布结束后以 acquire 读取尚未观察完成的路由 Lane 标记，并缓存已完成位，不回退本代观察结果。
     */
    public long completedLaneMask() {
        if (!ready()) return 0;
        long remaining = routedLaneMask() & ~state.observedCompletedLaneMask;
        while (remaining != 0) {
            int laneId = Long.numberOfTrailingZeros(remaining);
            long bit = 1L << laneId;
            remaining &= ~bit;
            if ((long) LONGS.getAcquire(completedLanes, laneId * CACHE_LINE_LONGS) != 0)
                state.observedCompletedLaneMask |= bit;
        }
        return state.observedCompletedLaneMask;
    }

    /**
     * 事件就绪且没有独立账户线程时，按路由推进尚未完成的 Lane；不在此代替已启动的账户工作线程执行。
     */
    public void ensureLanesExecuted() {
        if (!ready() || runtime == null || runtime.accountLanesStarted() || !state.dispatched) return;
        long remaining = routedLaneMask() & ~completedLaneMask();
        while (remaining != 0) {
            int laneId = Long.numberOfTrailingZeros(remaining);
            remaining &= remaining - 1;
            if ((state.queuedLaneMask & (1L << laneId)) == 0) {
                markLaneQueued(laneId);
            }
            execute(runtime.accountLane(laneId));
        }
    }

    /** Owner 等齐账户回执后统一推进已登记的财务结算；成功结果只在本代缓存。 */
    public boolean complete() {
        if (state.ownerObservedComplete) return true;
        if (!ready()) return false;
        ensureLanesExecuted();
        if (completedLaneMask() != routedLaneMask()) return false;
        if (hasLedgerSettlement() && !treasurySettlement.completed)
            treasurySettlement.apply(runtime.treasury());
        state.ownerObservedComplete = true;
        return true;
    }

    /**
     * 借用首个有效结算计划，空代次由带下标入口拒绝。
     */
    public TradeSettlement.Plan plan() { return plan(0); }
    /**
     * 返回本代有效计划数，不是批量存储容量。
     */
    public int planCount() { return state.batchPlanCount; }
    /**
     * 校验下标位于本代有效项前缀后借用对应计划，不暴露旧容量尾部。
     */
    public TradeSettlement.Plan plan(int index) {
        if (index < 0 || index >= planCount()) throw new IndexOutOfBoundsException(index);
        return batchStorage.plans[index];
    }
    /**
     * 借用本代保留的结算变化缓冲，已交出或未准备时失败。
     */
    public TradeSettlement.Changes changes() {
        if (changes == null) throw new IllegalStateException("matcher settlement changes are unavailable");
        return changes;
    }

    /**
     * 检查当前事件是否仍持有变化缓冲。
     */
    public boolean hasChanges() { return changes != null; }
    /**
     * 把变化缓冲引用交给收集方并从事件解除，不等待消费者；调用方须先满足既有完成边界。
     */
    public TradeSettlement.Changes takeChanges() {
        // 准备交给收集方并从事件解除的变化缓冲引用。
        TradeSettlement.Changes value = changes();
        changes = null;
        return value;
    }

    /** 财务结算完成后一次性收集账户事实；重复收集或绕过财务阶段均拒绝。 */
    public void markCollected() {
        if (!hasLedgerSettlement() || !complete())
            throw new IllegalStateException("matcher financial settlement is incomplete");
        if (state.collected) throw new IllegalStateException("matcher settlement event was already collected");
        state.collected = true;
    }

    /**
     * 无成交返回共用空差额；有成交只合并当前业务 Lane 对应的紧凑有效槽，以首个槽为结果，不创建副本或读取容量尾部。
     */
    private RuntimeTreasuryDelta mergeTreasuryDeltas() {
        if (!treasuryTrades) return EMPTY_TREASURY_DELTA;
        RuntimeTreasuryDelta aggregate = null;
        // 本代实际业务 Lane 数量，决定紧凑财务槽的有效前缀。
        int touchedLaneCount = Long.bitCount(requiredLaneMask);
        for (int index = 0; index < touchedLaneCount; index++) {
            RuntimeTreasuryDelta delta = touchedLaneTreasuryDeltas[index];
            if (aggregate == null) aggregate = delta;
            else aggregate.merge(delta);
        }
        if (aggregate == null) throw new IllegalStateException("matcher settlement event has no lane delta");
        return aggregate;
    }

    /** 登记本代财务结算并尝试推进；账户回执未齐时由 complete 继续，禁止重复登记。 */
    public void submitTreasurySettlement() {
        if (runtime == null || changes == null || state.collected)
            throw new IllegalStateException("Ledger settlement requires retained isolated account facts");
        if (treasurySettlement == null) treasurySettlement = new TreasurySettlement();
        if (treasurySettlement.queued) throw new IllegalStateException("Treasury settlement was already queued");
        state.ownerObservedComplete = false;
        treasurySettlement.queued = true;
        complete();
    }

    /**
     * 返回财务工作是否已登记成功，包括本地无成交与就地完成路径。
     */
    public boolean hasLedgerSettlement() { return treasurySettlement != null && treasurySettlement.queued; }

    /**
     * 本事件复用的财务槽，Owner 等账户完成后应用差额并核对资金。
     */
    private final class TreasurySettlement {
        /** 本财务槽独占的原语资金核对暂存，完成前校验守恒并清空，不是第二份资金账本。 */
        private final RuntimeFundsAccumulator funds = new RuntimeFundsAccumulator();
        /** 本代财务工作已成功登记，等待 Owner 在账户完成后推进。 */
        private boolean queued;
        /** 本代财务处理已结束的完成标记。 */
        private boolean completed;

        /** 全部账户回执到齐后由 Owner 核对资金；有成交时同时应用财库差额和持仓索引。 */
        public void apply(TreasuryRuntime treasury) {
            if (completed || (treasuryTrades && treasury.hasChangedValues()))
                throw new IllegalStateException("invalid Ledger financial scope");
            if (treasuryTrades) mergeTreasuryDeltas().apply(treasury);
            changes.appendFundsDelta(requiredLaneMask, funds);
            if (treasuryTrades) treasury.appendFundsDelta(funds);
            funds.requireConserved(false);
            funds.clear();
            if (treasuryTrades) {
                runtime.applyLedgerSettlementPositions(changes, requiredLaneMask, identities);
                treasury.clearChangedKeys();
            }
            completed = true;
        }

        /**
         * 确认已登记账本工作完成且资金临时缓冲为空后，重置本槽标记，保留容量。
         */
        void clear() {
            if (queued && !completed) throw new IllegalStateException("Ledger still owns this settlement");
            if (!funds.isEmpty()) throw new IllegalStateException("Ledger funds scratch was not cleared");
            queued = completed = false;
        }
    }

    /**
     * 把实际业务 Lane 编号转换为按参与位从低到高紧凑排列的财务槽下标，非参与 Lane 拒绝。
     */
    private int laneSlot(int laneId) {
        long laneBit = 1L << laneId;
        if ((requiredLaneMask & laneBit) == 0) {
            throw new IllegalStateException("matcher fact has no treasury slot for lane " + laneId);
        }
        return Long.bitCount(requiredLaneMask & (laneBit - 1));
    }


    /**
     * 清空 Owner 观察缓存和完成缓存，按 Lane 数准备带间隔的完成数组；容量相同时只重置各 Lane 的有效完成位置。
     */
    private void resetCompletions(int laneCount) {
        state.observedCompletedLaneMask = 0;
        state.ownerObservedComplete = false;
        int length = Math.multiplyExact(laneCount, CACHE_LINE_LONGS);
        if (completedLanes == null || completedLanes.length != length) {
            completedLanes = new long[length];
        } else {
            for (int laneId = 0; laneId < laneCount; laneId++) {
                completedLanes[laneId * CACHE_LINE_LONGS] = 0L;
            }
        }
    }


/**
 * 下单步骤 4.2：Matcher 根据原生成交事实构建受影响订单和账户 Lane 路由；发布后由所属 Lane 校验当前订单并应用。数组、事件和准入引用受在途槽租约约束，不能当成永久快照。
 */
public static final class Plan {
    /**  本代 taker 或撤单请求是否被拒绝，不能与已计入成交并存。 返回本计划是否要求按拒单或撤单拒绝处理 taker。 */
    @Accessors(fluent = true, chain = false)
    @Getter(value = AccessLevel.PACKAGE)
    private boolean rejectedTaker;
    /**
     * 保存撮合拒单标记并返回本计划；已有成交时禁止标记拒绝，防止用拒单覆盖已发生的成交事实。
     */
    public Plan rejectTaker(boolean rejected) {
        if (rejected && tradeCount != 0) throw new IllegalArgumentException("rejected taker cannot contain trades");
        rejectedTaker = rejected;
        return this;
    }

    /**  本计划对应的 Core 复制命令序号。 返回本计划所属 Core 序号。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private long coreSequence;
    /**  主动订单或撤单目标的编号。 返回当前 taker 或撤单目标编号。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private long takerOrderId;
    /**  当前复制命令的发起用户。 返回发起当前命令的账户用户编号。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private long activeUserId;
    /**  由 taker、maker 及相关变化归集的账户 Lane 位图。 返回本计划实际涉及的账户 Lane 位图。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private long requiredLaneMask;
    /** 本计划自有或内部构造接管的原语订单编号数组，只使用 orderCount 个有效槽。 */
    private long[] orderIds;
    /**  订单身份数组当前代的有效项数。 返回本代受影响订单编号的有效数量。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private int orderCount;
    /** 本代原始 Matcher 事件列表引用，包含成交与其他事件。 */
    private List<MatcherEvent> matcherEvents;
    /**  已校验并计入计划的 TRADE 事件数量。 返回本代已经统计的 TRADE 成交事件数。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private int tradeCount;
    /** 本代前置撤单有效数据指向，可能为空哨兵或自有复用数组。 */
    private long[] preCancellationOrderIds;
    /** 前置撤单有效前缀长度，不等于底层保留容量。 */
    private int preCancellationSize;
    /** 计划独占并保留容量的撤单编号数组，写入成功前缀后借给消费者读取。 */
    private long[] preCancellationStorage;
    /** 深度成交时每个 maker 所属 Lane 的链头下标，负一表示没有事件。 */
    private int[] makerLaneHeads;
    /** 每个成交在同一 maker Lane 链中的后继下标，未启用索引时不读取。 */
    private int[] makerLaneNext;
    /** 构建时缓存的主动账户 Lane，深度索引中该 Lane 仍读取完整原事件顺序。 */
    private int takerLaneId = -1;
    /** 本代是否已构建 maker Lane 事件链，复用旧数组时以此开关判定有效性。 */
    private boolean laneEventsIndexed;
    /** 没有前置撤单时共用的空原语数组。 */
    private static final long[] NO_ORDERS = new long[0];
    /**  构建时保留的准入订单值或命令槽引用，消费者遵守该代租约。 返回构建时保留的准入订单引用，借用期受计划租约限制，可能为空。 */
    @Accessors(fluent = true, chain = false)
    @Getter(value = AccessLevel.PACKAGE)
    private Order admittedTaker;
    /** 直达事实校验所需的主动订单方向，空表示不走直达订单校验。 */
    private com.surprising.aeron.protocol.CoreOrderSide directTakerSide;
    /** 直达事实校验所需的币对稠密编号。 */
    private int directTakerSymbolId;
    /** 直达事实校验所需的规范币对对象引用。 */
    private CoreInstrument directTakerInstrument;
    /** 本计划构建阶段独占的原语订单去重集合；借用它的构建路径在 finally 清空，短事件路径不使用它。 */
    private final LongHashSet directOrderKeys = new LongHashSet();

    /**
     * 创建可复用计划，先保留八个订单编号槽，事件和前置撤单使用空值。
     */
    public Plan() { orderIds = new long[8]; preCancellationOrderIds = NO_ORDERS; matcherEvents = List.of(); }

    /**
     * 清空本代事件、触发终态、订单引用、有效计数和直达校验字段；保留数组容量，旧原语槽不再属于有效前缀。
     */
    public void clearReferences() {
        matcherEvents = List.of(); completedTrigger = null; preCancellationOrderIds = NO_ORDERS;
        preCancellationSize = 0;
        rejectedTaker = false; laneEventsIndexed = false; orderCount = tradeCount = 0;
        admittedTaker = null; takerLaneId = -1;
        directTakerSide = null;
        directTakerSymbolId = 0;
        directTakerInstrument = null;
    }

    /**
     * 由已准入运行时订单及原生 MatcherResult 构建直达计划，按订单剩余量校验成交；命令序号等外层证据由事件边界核对。
     * @param taker 已准入的运行时主动订单引用，或对应重载的不可变已解析准入值
     * @param instrument 当前事实的规范币对对象，必须与订单输入一致
     */
    void buildDirectNative(long sequence, Order taker, CoreInstrument instrument,
                           exchange.core2.core.common.MatcherResult result, TradingAccountRuntime runtime) {
        if (taker == null || instrument == null || result == null || instrument != taker.instrument())
            throw new IllegalArgumentException("invalid direct native matcher fact");
        buildDirectNative(sequence, taker.orderId(), taker.userId(), taker.side(), taker.symbolId(),
                taker.instrument(), taker.remainingQuantitySteps(), taker, result, runtime);
    }

    /**
     * 由普通下单不可变准入值构建原生事实，使用完整准入数量，保留无需跨线程读取账户的校验字段。
     * @param taker 已准入的运行时主动订单引用，或对应重载的不可变已解析准入值
     * @param instrument 当前事实的规范币对对象，必须与订单输入一致
     */
    void buildDirectNative(long sequence, long userId, OrderCommand.ResolvedPlaceOrder taker, CoreInstrument instrument,
                           exchange.core2.core.common.MatcherResult result, TradingAccountRuntime runtime) {
        if (userId <= 0 || taker == null || instrument == null || result == null
                || instrument != taker.instrument())
            throw new IllegalArgumentException("invalid direct native matcher fact");
        buildDirectNative(sequence, taker.orderId(), userId, taker.side(), taker.symbolId(),
                taker.instrument(), taker.quantitySteps(), null, result, runtime);
    }

    /**
     * 按原生成交事件构建当前计划；零事件只记 taker 和接受标记，至多四个事件用线性去重，更多事件借用集合并按需建立 Lane 索引，始终校验 taker 累计成交不超量。
     * @param remainingQuantity 本代主动订单可供累计成交的数量上限
     * @param taker 已准入的运行时主动订单引用，或对应重载的不可变已解析准入值
     */
    private void buildDirectNative(long sequence, long orderId, long userId,
                                   com.surprising.aeron.protocol.CoreOrderSide side, int symbolId,
                                   CoreInstrument takerInstrument, long remainingQuantity, Order taker,
                                   exchange.core2.core.common.MatcherResult result, TradingAccountRuntime runtime) {
        clearReferences();
        coreSequence = sequence; admittedTaker = taker;
        directTakerSide = side; directTakerSymbolId = symbolId; directTakerInstrument = takerInstrument;
        takerOrderId = orderId; activeUserId = userId;
        takerLaneId = runtime.topology().accountLaneId(activeUserId);
        requiredLaneMask = runtime.topology().accountLaneMask(activeUserId);
        matcherEvents = result.events();
        // 原生结果的全部事件数量，用于选择短前缀或集合去重路径。
        int eventCount = matcherEvents.size();
        if (eventCount == 0) {
            orderIds[0] = takerOrderId;
            orderCount = 1;
            rejectTaker(!nativeAccepted(result));
            return;
        }
        // 容纳本代主动订单、maker 及撤单身份所需的数组容量。
        int capacity = Math.addExact(eventCount, 1);
        if (orderIds.length < capacity) orderIds = new long[Math.max(capacity, orderIds.length * 2)];
        if (eventCount <= 4) {
            orderIds[0] = takerOrderId;
            int count = 1;
            // 主动订单按已遍历成交逐项扣减后的待成交数量。
            long remaining = remainingQuantity;
            for (int index = 0; index < eventCount; index++) {
                MatcherEvent event = matcherEvents.get(index);
                if (event == null) throw new IllegalArgumentException("missing matcher event");
                if (event.eventType() != MatcherEventType.TRADE) continue;
                if (event.price() <= 0 || event.size() <= 0 || event.matchedOrderId() <= 0
                        || event.matchedOrderUid() <= 0 || event.matchedOrderUid() == activeUserId)
                    throw new IllegalStateException("invalid direct fill identity or quantity");
                remaining = Math.subtractExact(remaining, event.size());
                if (remaining < 0) throw new IllegalStateException("fill exceeds admitted taker quantity");
                count = addUniqueLinear(orderIds, count, event.matchedOrderId());
                requiredLaneMask |= runtime.topology().accountLaneMask(event.matchedOrderUid());
                tradeCount++;
            }
            orderCount = count;
            rejectTaker(!nativeAccepted(result));
            return;
        }
        // 本计划独占复用的原语订单去重集合，构建结束即清空。
        LongHashSet keys = directOrderKeys;
        try {
            orderCount = addUnique(keys, orderIds, 0, takerOrderId);
            // 主动订单按已遍历成交逐项扣减后的待成交数量。
            long remaining = remainingQuantity;
            for (int index = 0; index < eventCount; index++) {
                MatcherEvent event = matcherEvents.get(index);
                if (event == null) throw new IllegalArgumentException("missing matcher event");
                if (event.eventType() != MatcherEventType.TRADE) continue;
                if (event.price() <= 0 || event.size() <= 0 || event.matchedOrderId() <= 0
                        || event.matchedOrderUid() <= 0 || event.matchedOrderUid() == activeUserId)
                    throw new IllegalStateException("invalid direct fill identity or quantity");
                remaining = Math.subtractExact(remaining, event.size());
                if (remaining < 0) throw new IllegalStateException("fill exceeds admitted taker quantity");
                orderCount = addUnique(keys, orderIds, orderCount, event.matchedOrderId());
                requiredLaneMask |= runtime.topology().accountLaneMask(event.matchedOrderUid());
                tradeCount++;
            }
            rejectTaker(!nativeAccepted(result));
            indexLaneEvents(runtime);
        } finally { keys.clear(); }
    }

    /**
     * 在短原语前缀中线性查重，新编号追加到末尾并返回新有效长度；调用方预先保证容量。
     * @param values 预先保证容量的原语订单编号数组
     */
    private static int addUniqueLinear(long[] values, int size, long value) {
        for (int i = 0; i < size; i++) {
            if (values[i] == value) return size;
        }
        values[size] = value;
        return size + 1;
    }

    /**
     * 借用给定集合检查编号是否首次出现，新编号追加到原语有效前缀，容量由构建方保证。
     * @param values 预先保证容量的原语订单编号数组
     */
    private static int addUnique(LongHashSet unique, long[] values, int size, long value) {
        if (!unique.add(value)) return size;
        values[size] = value;
        return size + 1;
    }

    /**
     * 检查原生撤单不夹带成交，重置并保存目标用户路由和接受标记；接受时记录一个订单身份，拒绝时有效身份数为零。
     */
    void buildDirectNativeCancellation(long sequence, long userId, long orderId,
                                       exchange.core2.core.common.MatcherResult result,
                                       TradingAccountRuntime runtime) {
        if (orderId <= 0 || result == null) throw new IllegalArgumentException("invalid direct cancellation fact");
        for (int index = 0; index < result.events().size(); index++) {
            MatcherEvent event = result.events().get(index);
            if (event == null || event.eventType() == MatcherEventType.TRADE)
                throw new IllegalStateException("cancel result contains a trade");
        }
        clearReferences();
        // 原生撤单结果是否为 SUCCESS 或 ACCEPTED。
        boolean accepted = nativeAccepted(result);
        coreSequence = sequence; takerOrderId = orderId; activeUserId = userId;
        takerLaneId = runtime.topology().accountLaneId(activeUserId);
        rejectedTaker = !accepted;
        requiredLaneMask = runtime.topology().accountLaneMask(activeUserId);
        orderCount = accepted ? 1 : 0;
        if (orderCount != 0) orderIds[0] = orderId;
    }

    /**
     * 仅把原生 SUCCESS 或 ACCEPTED 视为 Matcher 接受。
     */
    private static boolean nativeAccepted(exchange.core2.core.common.MatcherResult result) {
        return result.resultCode() == exchange.core2.core.common.cmd.CommandResultCode.SUCCESS
                || result.resultCode() == exchange.core2.core.common.cmd.CommandResultCode.ACCEPTED;
    }

    /**
     * 为没有原生结果的准入拒绝只保留身份与路由，让已登记的账户路径能够交回完成回执。
     */
    void prepareRejectedDirect(long sequence, long userId, long orderId, long laneMask) {
        if (sequence <= 0 || userId <= 0 || orderId <= 0 || laneMask == 0)
            throw new IllegalArgumentException("invalid rejected direct matcher fact");
        clearReferences();
        coreSequence = sequence;
        takerOrderId = orderId;
        activeUserId = userId;
        requiredLaneMask = laneMask;
        rejectedTaker = true;
        orderCount = tradeCount = 0;
    }

    /**
     * 原地移除本计划编号前缀中的未成功下出的 taker，保留其他已发生变化的订单编号及原有顺序。
     */
    void omitUnplacedOrder() {
        int count = 0;
        for (int index = 0; index < orderCount; index++)
            if (orderIds[index] != takerOrderId) orderIds[count++] = orderIds[index];
        orderCount = count;
    }

    /**
     * 所属 Lane 以当前权威订单校验直达成交身份及 maker 单次剩余量，必要时保留持仓身份；深度成交的非 taker Lane 只沿自己的 maker 链遍历，不跨线程读其他账户。
     * @param lane 当前线程持有的所属账户 Lane
     * @param instrument 当前事实的规范币对对象，必须与订单输入一致
     */
    void validateDirectLane(AccountPartition lane, TradingAccountRuntime runtime,
                            RuntimeIdentityRegistry identities, CoreInstrument instrument) {
        if (directTakerSide == null) return;
        if (runtime.topology().accountLaneId(activeUserId) == lane.laneId()) {
            // 当前构建或 Lane 校验使用的主动订单引用，已解析输入路径可以为空。
            Order taker = lane.order(takerOrderId);
            requireDirectOrder(taker, activeUserId, directTakerSide);
            if (tradeCount != 0) runtime.retainDirectPositionIdentity(lane, identities, instrument, taker);
        }
        // 深度成交计划已经按 maker 所属 Lane 建好事件链，非 taker Lane 只校验自己的 maker。
        // taker Lane 仍按原事件顺序读取，因为它也可能同时持有本 Lane 的 maker。
        for (int index = firstMatcherEvent(lane.laneId()); index >= 0;
                index = nextMatcherEvent(index, lane.laneId())) {
            MatcherEvent event = matcherEvents.get(index);
            if (event.eventType() != MatcherEventType.TRADE
                    || !matcherEventTouchesLane(index, lane.laneId(), runtime)
                    || runtime.topology().accountLaneId(event.matchedOrderUid()) != lane.laneId()) continue;
            Order maker = lane.order(event.matchedOrderId());
            requireDirectOrder(maker, event.matchedOrderUid(), directTakerSide
                    == com.surprising.aeron.protocol.CoreOrderSide.BUY
                    ? com.surprising.aeron.protocol.CoreOrderSide.SELL
                    : com.surprising.aeron.protocol.CoreOrderSide.BUY);
            if (maker.remainingQuantitySteps() < event.size())
                throw new IllegalStateException("fill exceeds Lane maker remaining quantity");
            runtime.retainDirectPositionIdentity(lane, identities, instrument, maker);
        }
    }

    /**
     * 要求 Lane 当前订单为开放状态，且用户、方向、币对编号及规范币对对象都与直达输入一致。
     */
    private void requireDirectOrder(Order order, long userId,
                                    com.surprising.aeron.protocol.CoreOrderSide side) {
        if (order == null || order.status() != CoreOrderStatus.OPEN || order.userId() != userId
                || order.symbolId() != directTakerSymbolId || order.side() != side
                || order.instrument() != directTakerInstrument)
            throw new IllegalStateException("matcher fact does not match Lane-owned order");
    }
    /**  本代要由 taker Lane 写入的触发单终态，普通命令为空。 返回本代要由 taker Lane 写入的触发单终态，普通订单返回空。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private com.surprising.aeron.model.CoreTriggerOrderState completedTrigger;

    /**
     * 把触发终态附到同一结算事实：用户必须匹配，拒单要求失败状态且无子订单，非拒单要求子订单编号匹配；同代只允许设置一次。
     */
    public Plan completeTrigger(com.surprising.aeron.model.CoreTriggerOrderState value) {
        if (value == null || value.userId() != activeUserId || (rejectedTaker
                ? value.status() != com.surprising.aeron.protocol.CoreTriggerOrderStatus.TRIGGER_FAILED
                        || value.placedOrderId() != 0
                : value.placedOrderId() != takerOrderId) || completedTrigger != null)
            throw new IllegalArgumentException("invalid settlement trigger completion");
        completedTrigger = value;
        return this;
    }

    /**
     * 直接读取原语订单编号；调用方保证下标在 orderCount 内，本方法不另检查业务有效前缀。
     */
    public long orderId(int index) { return orderIds[index]; }
    /**
     * 返回本代 Matcher 事件总数，包括非成交事件。
     */
    public int matcherEventCount() { return matcherEvents.size(); }
    /**
     * 按列表下标读取本代原始 Matcher 事件引用。
     */
    public MatcherEvent matcherEvent(int index) { return matcherEvents.get(index); }
    /**
     * 将已经接受的替换原单撤单保存为唯一前置撤单项；之后可追加普通前置撤单。
     */
    void acceptedReplacementCancellation(long orderId) {
        if (orderId <= 0) throw new IllegalArgumentException("replacement cancellation is required");
        ensurePreCancellationCapacity(1);
        preCancellationStorage[0] = orderId;
        preCancellationOrderIds = preCancellationStorage;
        preCancellationSize = 1;
    }

    /**
     * 把调用方已经确认成功的撤单前缀追加到现有自有缓冲，不覆盖已有替换撤单；数量为零保持原状态。
     */
    void acceptedPreCancellations(long[] orderIds, int count) {
        if (count < 0 || count != 0 && (orderIds == null || count > orderIds.length))
            throw new IllegalArgumentException("accepted cancellations are invalid");
        if (count == 0) return;
        ensurePreCancellationCapacity(preCancellationSize + count);
        System.arraycopy(orderIds, 0, preCancellationStorage, preCancellationSize, count);
        preCancellationSize += count;
        preCancellationOrderIds = preCancellationStorage;
    }

    /**
     * 只在需要时创建或扩容计划自有撤单数组，保留已写前缀，非正数请求不处理。
     * @param required 自有撤单存储至少需要容纳的有效项数
     */
    private void ensurePreCancellationCapacity(int required) {
        if (required <= 0) return;
        if (preCancellationStorage == null) {
            preCancellationStorage = new long[Math.max(4, required)];
        } else if (preCancellationStorage.length < required) {
            preCancellationStorage = java.util.Arrays.copyOf(preCancellationStorage,
                    Math.max(required, preCancellationStorage.length * 2));
        }
    }
    /**
     * 返回本代前置撤单有效项数。
     */
    public int preCancellationCount() { return preCancellationSize; }
    /**
     * 读取前置撤单原语编号，调用方保证下标小于有效项数。
     */
    long preCancellationOrderId(int index) { return preCancellationOrderIds[index]; }
    /**
     * 事件至少八个且涉及多个 Lane 时，逆序串出各 maker Lane 的事件链，保持原成交顺序；taker Lane 继续顺序读取全部原事件。
     */
    private Plan indexLaneEvents(TradingAccountRuntime runtime) {
        // 少量事件不建索引；深度成交时各 maker Lane 只沿自己的事件链读取，
        // taker Lane 仍保留原事件顺序，不复制成交事件。
        if (matcherEvents.size() < 8 || Long.bitCount(requiredLaneMask) < 2) return this;
        // 主动账户 Lane 已在构建时缓存，这里只补充 maker 事件链。
        laneEventsIndexed = true;
        if (makerLaneHeads == null || makerLaneHeads.length != runtime.topology().accountLaneCount())
            makerLaneHeads = new int[runtime.topology().accountLaneCount()];
        if (makerLaneNext == null || makerLaneNext.length < matcherEvents.size())
            makerLaneNext = new int[matcherEvents.size()];
        java.util.Arrays.fill(makerLaneHeads, -1);
        for (int index = matcherEvents.size() - 1; index >= 0; index--) {
            MatcherEvent event = matcherEvents.get(index);
            if (event.eventType() != MatcherEventType.TRADE) continue;
            int lane = runtime.topology().accountLaneId(event.matchedOrderUid());
            makerLaneNext[index] = makerLaneHeads[lane];
            makerLaneHeads[lane] = index;
        }
        return this;
    }

    /**
     * 返回当前 Lane 应开始读取的事件下标；非 taker 的深度索引路径读链头，其他情况从原事件首项开始，负一表示结束。
     */
    int firstMatcherEvent(int laneId) {
        if (laneEventsIndexed && laneId != takerLaneId) return makerLaneHeads[laneId];
        return matcherEvents.isEmpty() ? -1 : 0;
    }

    /**
     * 沿当前 Lane 的 maker 链或原事件顺序取得下一项，负一表示本 Lane 遍历结束。
     */
    int nextMatcherEvent(int index, int laneId) {
        if (laneEventsIndexed && laneId != takerLaneId) return makerLaneNext[index];
        return index + 1 < matcherEvents.size() ? index + 1 : -1;
    }

    /**
     * 判断当前成交是否涉及指定 Lane；启用深度索引时非 taker 调用方须沿本 Lane 事件链传入下标，该前提成立即可直接返回 true。
     */
    public boolean matcherEventTouchesLane(int index, int laneId, TradingAccountRuntime runtime) {
        // 启用深度索引时，非 taker 调用方已经沿当前 maker Lane 的事件链传入下标，
        // 因此可直接判定相关；其他遍历方式仍按账户拓扑判断。
        if (laneEventsIndexed && laneId != takerLaneId) return true;
        MatcherEvent event = matcherEvents.get(index);
        return takerLaneId == laneId
                || runtime.topology().accountLaneId(event.matchedOrderUid()) == laneId;
    }
}

/**
 * 下单步骤 4.4 与 4.6 的结算变化缓冲。每个 Lane 写自己的实体变化、余额前后值、资金增量及用户修订次数；完成交接后供财务核对和 Owner 收集。权威账户仍在 AccountPartition，缓冲只在当前事件租约内有效，清空保留容量。
 */
public static final class Changes {
    /** 未激活 Lane 共用的空实体变化哨兵，写入前须替换为独立缓冲。 */
    private static final AccountPartitionCommit.Delta EMPTY_LANE_DELTA = new AccountPartitionCommit.Delta();
    /** 未激活 Lane 共用的空余额捕获哨兵，不可写入业务变化。 */
    private static final AccountTransactionRollback.BalancePatches EMPTY_BALANCE_PATCHES = new AccountTransactionRollback.BalancePatches();
    /** 未激活 Lane 共用的空资金增量哨兵，不可累加金额。 */
    private static final RuntimeFundsAccumulator EMPTY_FUNDS_DELTA = new RuntimeFundsAccumulator();
    /** 未激活 Lane 共用的空用户修订哨兵，不可写入用户增量。 */
    private static final LongLongHashMap EMPTY_USER_REVISIONS = new LongLongHashMap();
    /**  本代是否直接使用持仓身份交接；Owner 收集时据此释放已发布身份。 返回本代是否采用直达持仓身份交接，供 Owner 选择身份释放方式。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    boolean directPositionIdentities;
    /**
     *  由外部环槽或对象池管理的占用标记，clear 不自行重置。
     * 返回外部池或环槽管理的占用标记。
     * 由池或环槽管理方更新占用标记，不在此等待消费者。
     */
    @Accessors(fluent = true, chain = false)
    @Getter
    @Setter
    boolean inUse;
    /**  创建时确定的固定环槽编号，负一表示普通独立缓冲。 返回固定环槽编号，负一表示未关联固定环槽。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    final int ringIndex;
    /**
     *  本代已登记参与的 Lane 位图，路由扩展时补充，清理时只处理这些 Lane。
     * 返回本代登记过的参与 Lane 位图。
     * 由持有租约的调用方直接设置参与位图；这里只保存位图，不分配各 Lane 缓冲。
     */
    @Accessors(fluent = true, chain = false)
    @Getter
    @Setter
    long activeLaneMask;
    /** 按 Lane 保存的实体与终态变化列，完成交接前由所属 Lane 独占写入。 */
    final AccountPartitionCommit.Delta[] laneDeltas;
    /** 按 Lane 保存的余额前后值，供回滚及资金差额核对。 */
    public final AccountTransactionRollback.BalancePatches[] balancePatches;
    /** 由各 Lane 余额前后值准备的资金增量，交接后供账本或未激活路径消费。 */
    final RuntimeFundsAccumulator[] laneFundsDeltas;
    /** 本代每 Lane 按用户聚合的修订次数，收尾应用成功后清空。 */
    final LongLongHashMap[] userRevisionDeltas;

    /**
     * 按账户 Lane 数创建缓冲入口，默认不关联固定环槽。
     */
    public Changes(int laneCount) {
        this(laneCount, -1);
    }

    /**
     * 保存环槽编号并创建各 Lane 的缓冲列，未参与的 Lane 先共用只读空哨兵，真正使用时再分配独立缓冲。
     * @param ringIndex 固定环槽编号，独立缓冲使用负一
     */
    public Changes(int laneCount, int ringIndex) {
        this.ringIndex = ringIndex;
        laneDeltas = new AccountPartitionCommit.Delta[laneCount];
        balancePatches = new AccountTransactionRollback.BalancePatches[laneCount];
        laneFundsDeltas = new RuntimeFundsAccumulator[laneCount];
        userRevisionDeltas = new LongLongHashMap[laneCount];
        java.util.Arrays.fill(laneDeltas, EMPTY_LANE_DELTA);
        java.util.Arrays.fill(balancePatches, EMPTY_BALANCE_PATCHES);
        java.util.Arrays.fill(laneFundsDeltas, EMPTY_FUNDS_DELTA);
        java.util.Arrays.fill(userRevisionDeltas, EMPTY_USER_REVISIONS);
    }

    /**
     * 为位图内的 Lane 把空哨兵换成独立可写缓冲；本方法只准备存储，不自行登记 activeLaneMask。
     */
    public void ensureActiveLanes(long laneMask) {
        while (laneMask != 0) {
            // 位图或数组遍历中当前操作的账户 Lane 编号。
            int laneId = Long.numberOfTrailingZeros(laneMask);
            laneMask &= laneMask - 1;
            if (laneDeltas[laneId] == EMPTY_LANE_DELTA) laneDeltas[laneId] = new AccountPartitionCommit.Delta();
            if (balancePatches[laneId] == EMPTY_BALANCE_PATCHES) balancePatches[laneId] = new AccountTransactionRollback.BalancePatches();
            if (laneFundsDeltas[laneId] == EMPTY_FUNDS_DELTA) laneFundsDeltas[laneId] = new RuntimeFundsAccumulator();
            if (userRevisionDeltas[laneId] == EMPTY_USER_REVISIONS) userRevisionDeltas[laneId] = new LongLongHashMap();
        }
    }

    /**
     * 把用户在本代结算中的修订增量累加到所属 Lane 缓冲；用户权威修订号在收尾时统一应用。
     */
    public void addUserRevision(int laneId, long userId, long count) {
        userRevisionDeltas[laneId].addToValue(userId, count);
    }

    /**
     * 在所属 Lane 应用已聚合的用户修订增量并写入发布变化；只有正常返回后才清空增量，失败时保留原缓冲。
     * @param lane 当前线程持有的对应账户 Lane
     */
    void applyUserRevisions(int laneId, AccountPartition lane) {
        LongLongHashMap deltas = userRevisionDeltas[laneId];
        if (deltas.isEmpty()) return;
        lane.applyUserRevisions(deltas, laneDeltas[laneId]);
        deltas.clear();
    }

    /**
     * 为指定 Lane 提前准备准入与终态槽容量；非正数请求不处理，参与位图仍由调用方登记。
     */
    public void ensureAdmissionCapacity(int laneId, int expectedOrders) {
        if (expectedOrders <= 0) return;
        ensureActiveLanes(1L << laneId);
        laneDeltas[laneId].ensureAdmissionCapacity(expectedOrders);
        laneDeltas[laneId].ensureTerminalCapacity(expectedOrders);
    }

    /**
     * 按本次实际路由登记参与 Lane，并为这些 Lane 提前准备订单及终态槽容量；非正数请求不处理。
     */
    public void ensureOrderCapacity(int expectedOrders, long laneMask) {
        if (expectedOrders <= 0) return;
        activeLaneMask |= laneMask;
        ensureActiveLanes(laneMask);
        while (laneMask != 0) {
            // 位图或数组遍历中当前操作的账户 Lane 编号。
            int laneId = Long.numberOfTrailingZeros(laneMask);
            laneMask &= laneMask - 1;
            laneDeltas[laneId].ensureOrderCapacity(expectedOrders);
            laneDeltas[laneId].ensureTerminalCapacity(expectedOrders);
        }
    }

    /**
     * 下单步骤 4.4 的账户收尾：应用修订、记录终态，预留耗尽后移除可退休订单并释放客户身份；拒单保留查询记录。零持仓同时取消同合约、方向及保证金模式的待触发单，最后准备持仓退休、发布值和余额资金增量。
     * @param lane 当前线程持有的对应账户 Lane
     * @param sequence 本代 Core 序号，用于设置订单再次复用前必须越过的退休边界
     */
    public void prepareLaneTerminal(int laneId, RuntimeIdentityRegistry identities, AccountPartition lane, long sequence) {
        applyUserRevisions(laneId, lane);
        AccountPartitionCommit.Delta changes = laneDeltas[laneId];
        // Owner 收齐回执后按序提交；这里保存终态身份供有限保留窗口使用，
        // 订单和预留金额计数仍以 Lane 中的权威值为准。
        for (int index = 0; index < changes.orders().size(); index++) {
            // 本项变化的原语订单编号。
            long orderId = changes.orders().keyAt(index);
            // 当前变更槽中的订单值，空值或非终态跳过。
            Order order = changes.orders().valueAt(index);
            if (order == null || !order.status().terminal()) continue;
            changes.recordTerminalOrder(order);
            // 本终态订单在权威 Lane 中仍保留的预留，未耗尽时不能移除订单。
            OrderFundsReservation reservation = lane.reservation(orderId);
            if (reservation != null && reservation.reservedUnits() != 0) continue;
            if (reservation != null) {
                lane.removeReservationValue(orderId);
                lane.removeReservationId(order.userId(), orderId);
                changes.removeReservationRoute(orderId);
            }
            // 原生拒单保留已有的 REJECTED 查询记录，但不再持有冻结或 reservation。
            if (order.status() == CoreOrderStatus.REJECTED) continue;
            lane.retireAlgoChild(identities, order, changes);
            Order removed = lane.removeOrder(orderId);
            if (removed != null) {
                changes.removeOrderRoute(orderId);
                if (removed.retireForReuse(sequence)) changes.retiredOrder(removed);
            }
            lane.releaseOrderClientIdentities(identities, orderId, order.userId());
            lane.removeClientOrdersForOrder(order.userId(), orderId);
        }
        for (int positionIndex = 0; positionIndex < changes.positions().size(); positionIndex++) {
            // 本项变化后的持仓值，数量归零时需要清理相关待触发单。
            Position position = changes.positions().valueAt(positionIndex);
            if (position != null && position.signedQuantitySteps() == 0) {
                LongHashSet ids = lane.triggerIds(position.userId());
                if (ids != null && !ids.isEmpty()) {
                    // 零持仓对应的业务合约名称，用于筛选相关触发单。
                    String instrumentId = identities.instrumentId(position.symbolId());
                    // 逐项读取该用户触发单编号的原语迭代器。
                    var iterator = ids.longIterator();
                    while (iterator.hasNext()) {
                        long id = iterator.next();
                        CoreTriggerOrderState trigger = lane.triggerOrder(id);
                        if (trigger == null || trigger.status() != CoreTriggerOrderStatus.PENDING
                                || trigger.positionSide() != position.positionSide()
                                || trigger.marginMode() != position.marginMode()
                                || !instrumentId.equals(trigger.instrumentId())) continue;
                        // 同合约、方向及保证金模式的待触发单取消值。
                        CoreTriggerOrderState canceled = trigger.toPendingCancellation();
                        lane.putTrigger(canceled);
                        changes.putTrigger(id, canceled);
                        changes.closedTriggerCount(Math.incrementExact(changes.closedTriggerCount()));
                    }
                }
            }
        }
        changes.preparePositionRetirement(lane);
        changes.preparePublication();
        TradingAccountRuntime.prepareBalanceFundsDelta(balancePatches[laneId], laneFundsDeltas[laneId]);
    }

    /**
     * 从指定 Lane 已捕获的余额前后值准备准入资金增量，不在这里执行完整订单终态清理。
     */
    public void prepareAdmissionLane(int laneId) {
        TradingAccountRuntime.prepareBalanceFundsDelta(balancePatches[laneId], laneFundsDeltas[laneId]);
    }

    /**
     * 遍历全部已经分配的余额缓冲，把前值合并到目标；跳过空哨兵，不只遍历 activeLaneMask。
     */
    public void copyBalanceBeforeTo(AccountTransactionRollback.BalancePatches target) {
        if (target == null) throw new IllegalArgumentException("balance capture target is required");
        for (int laneId = 0; laneId < balancePatches.length; laneId++) {
            // 已分配的某 Lane 余额前后值缓冲，空哨兵直接跳过。
            AccountTransactionRollback.BalancePatches source = balancePatches[laneId];
            if (source == EMPTY_BALANCE_PATCHES) continue;
            target.mergeBefore(source);
        }
    }

    /**
     * 按调用方给出的 Lane 位图，把各 Lane 已准备的资金增量加入目标累加器，不清空源。
     */
    public void appendFundsDelta(long laneMask, RuntimeFundsAccumulator target) {
        if (target == null) throw new IllegalArgumentException("funds accumulator is required");
        long lanes = laneMask;
        while (lanes != 0) {
            // 位图或数组遍历中当前操作的账户 Lane 编号。
            int laneId = Long.numberOfTrailingZeros(lanes);
            lanes &= lanes - 1;
            target.add(laneFundsDeltas[laneId]);
        }
    }

    /**
     * 把参与 Lane 的待退休订单交回对应账户的回收缓存；对象能否再次初始化仍受退休序号约束。
     */
    public void returnRetiredOrders(TradingAccountRuntime runtime) {
        long lanes = activeLaneMask;
        while (lanes != 0) {
            // 位图或数组遍历中当前操作的账户 Lane 编号。
            int laneId = Long.numberOfTrailingZeros(lanes); lanes &= lanes - 1;
            laneDeltas[laneId].returnRetiredOrders(runtime.accountLane(laneId));
        }
    }

    /**
     * 清除参与 Lane 的修订、变化、余额与资金缓冲并重置参与位图和持仓身份标记；保留容量，不在此修改 inUse 或环槽编号，也不自动归还退休订单，调用方须先完成退休交接。
     */
    public void clear() {
        directPositionIdentities = false;
        long lanes = activeLaneMask;
        while (lanes != 0) {
            int lane = Long.numberOfTrailingZeros(lanes); lanes &= lanes - 1;
            userRevisionDeltas[lane].clear();
            laneDeltas[lane].clear();
            balancePatches[lane].clear();
            laneFundsDeltas[lane].clear();
        }
        activeLaneMask = 0;
    }

    /**
     * 借用指定 Lane 的实体与终态变化缓冲，不复制内容。
     */
    public AccountPartitionCommit.Delta laneDelta(int index) { return laneDeltas[index]; }

    /**
     * 借用指定 Lane 的余额前后值缓冲，不复制内容。
     */
    public AccountTransactionRollback.BalancePatches balancePatch(int index) { return balancePatches[index]; }
    /**
     * 返回按拓扑保留的余额缓冲列数，不表示本代活跃 Lane 数。
     */
    public int balancePatchCount() { return balancePatches.length; }
}

/** 资金分录的所有者与子账类别；真实分录由运行态资金变化保存。 */
public static final class Posting {
    private Posting() {
    }

    /** 本条资金变化归谁：普通用户、做市账户、系统财库或系统外部。 */
    public enum OwnerKind {
        /**
         * 普通用户账户的资金归属。
         */
        USER,
        /**
         * 做市账户的资金归属。
         */
        MAKER,
        /**
         * 系统财库的资金归属，所有者编号为零。
         */
        TREASURY,
        /**
         * 系统外部的资金归属，编号为零且只使用外部调整子账。
         */
        EXTERNAL;

    }

    /** 按用途区分资金变化的子账类别；枚举本身不执行余额更新，也不校验一组分录是否平衡。 */
    public enum Subledger {
        /**
         * 可用余额的变动。
         */
        AVAILABLE,
        /**
         * 冻结余额的变动。
         */
        LOCKED,
        /**
         * 订单资金预留的变动。
         */
        RESERVATION,
        /**
         * 持仓保证金的变动。
         */
        POSITION_MARGIN,
        /**
         * 交易手续费的变动。
         */
        FEE,
        /**
         * 保险基金资金的变动。
         */
        INSURANCE,
        /**
         * 强平费用的变动。
         */
        LIQUIDATION_FEE,
        /**
         * 资金费结算剩余差额的变动。
         */
        FUNDING_RESIDUAL,
        /**
         * 金额舍入剩余差额的变动。
         */
        ROUNDING_RESIDUAL,
        /**
         * 清算盈亏资金的变动。
         */
        CLEARING_PNL,
        /**
         * 亏空资金的变动。
         */
        DEFICIT,
        /**
         * 外部调入或调出系统的资金变化，只配外部所有者。
         */
        EXTERNAL_ADJUSTMENT;

    }
}
}
