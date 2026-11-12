package com.surprising.aeron.service.settlement;

import com.surprising.aeron.model.CoreOrderStatus;
import com.surprising.aeron.model.CoreTriggerOrderState;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.CoreTriggerOrderStatus;
import com.surprising.aeron.service.account.AccountPartition;
import com.surprising.aeron.service.account.AccountLaneCommand;
import com.surprising.aeron.service.account.AccountTransactionRollback;
import com.surprising.aeron.service.account.UserAccount;
import com.surprising.aeron.service.core.AccountBook;
import com.surprising.aeron.service.core.change.AccountPartitionCommit;
import com.surprising.aeron.service.core.identity.IdIndex;
import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.aeron.service.order.*;
import com.surprising.aeron.service.order.Order;
import com.surprising.aeron.service.order.OrderCommand.ResolvedPlaceOrder;
import com.surprising.aeron.service.order.OrderCommand.ResultTarget;
import com.surprising.aeron.service.order.OrderFundsReservation;
import com.surprising.aeron.service.position.Position;
import com.surprising.aeron.service.treasury.FundsTotal;
import com.surprising.aeron.service.treasury.SystemFundsChange;
import com.surprising.aeron.service.treasury.SystemFunds;
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

/** 下单步骤 4.2–4.6 的可复用结算事件；ready 还等待发布方交出租约，清理前须交出 changes。 */
@NoArgsConstructor
public final class TradeSettlement implements
        AccountLaneCommand,
        com.surprising.aeron.service.matcher.OrderMatchingResult {
    /** 原 Matcher 传输占用的释放边界，事件复用现有上下文回调，不新增逐命令任务或状态副本。 */
    public interface MatcherCompletionRoute {
        /** 由事件发布方通知原 Matcher 传输上下文释放本分片提交占用，仍须由事件发布租约保护后续 Lane 投递。 */
        void releaseMatcherSubmission(int matcherShard);
    }

    private static final SystemFundsChange EMPTY_TREASURY_DELTA = new SystemFundsChange(1);
    /** 不同 Lane 完成位之间相隔十六个 long，降低并发写相邻位置的竞争。 */
    private static final int CACHE_LINE_LONGS = 16;
    /** 完成数组的 VarHandle，Lane release 发布、Owner 及账本 acquire 读取。 */
    private static final VarHandle LONGS = MethodHandles.arrayElementVarHandle(long[].class);

    /** 本段账户最终提交序号，顺序批次中间项可为零。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private long commitSequence;
    @Accessors(fluent = true, chain = false)
    @Getter
    private long requiredLaneMask;
    @Accessors(fluent = true, chain = false)
    @Getter
    private AccountBook accountBook;
    @Accessors(fluent = true, chain = false)
    @Getter
    private IdIndex identities;
    private SystemFundsChange[] touchedLaneSystemFundsChanges;
    /** 当前代实体、余额及资金变化缓冲，完成后由 Owner 接管并解除引用。 */
    private Changes changes;
    private boolean systemFundsTrades;
    private long[] completedLanes;
    private SystemFundsSettlement systemFundsSettlement;
    /** 本事件独占的计划及命令结果存储，不借用共享 Owner 暂存数组。 */
    private BatchStorage batchStorage;
    /** Matcher 已通过 volatile 发布结果或故障，Lane 据此允许消费。 */
    private volatile boolean directPublished;
    /** 必须消费本事件票据的 Lane 位图，固定多 Lane 预约可含本代无业务变化的 Lane。返回必须消费本事件票据的路由 Lane 位图，可能包含本代已无业务变化的预约 Lane。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private long routedLaneMask;
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
        com.surprising.aeron.service.order.OrderCommand.ReplaceOrderCheck replacement;
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
        boolean orderCheckRequired;
        OrderCommand.ResolvedPlaceOrder orderCheckOrder;
        long orderCheckUserId;
        long orderCheckOrderId;
        volatile boolean orderCheckResolved;
        boolean orderCheckAccepted;
        int orderCheckResultCode;
        OrderCommand.ResultTarget resultTarget;
    }

    public boolean dispatched() { return state.dispatched; }
    public long queuedLaneMask() { return state.queuedLaneMask; }
    public int directShard() { return state.directShard; }
    public long directLastMatcherSequence() { return state.directLastMatcherSequence; }
    /** 本事件独占复用的批量计划、准入引用、原生结果槽及币对资产列，容量与本代有效前缀分开管理。 */
    public static final class BatchStorage {
        /** Owner 准备阶段的币对编号到首个元数据槽映射，负一表示没有缓存，回收时清空。 */
        public final org.agrona.collections.Int2IntHashMap metadataSlots =
                new org.agrona.collections.Int2IntHashMap(8, 0.65f, -1, false);
        public final TradeSettlement.Plan[] plans;
        public final CoreInstrument[] instruments;
        /** 各有效项持有的准入订单值或命令槽引用，普通不可变准入路径可以为空。 */
        public final Order[] admittedOrders;
        public final long[] cancellationOrderIds;
        public final exchange.core2.core.common.MatcherResult[] nativeResults;
        public exchange.core2.core.common.MatcherResult replacementCancellation;
        /** 本事件独占复用的新单命令槽，仍须遵守事件代次租约。 */
        public Order replacementOrder;
        public final int[] baseAssetIds, quoteAssetIds, settleAssetIds;
        private int activeSize;
        /** 按所需容量预创建计划和各元数据列。 */
        BatchStorage(int size) {
            plans = new TradeSettlement.Plan[size];
            for (int i = 0; i < size; i++) plans[i] = new TradeSettlement.Plan();
            instruments = new CoreInstrument[size];
            admittedOrders = new Order[size];
            cancellationOrderIds = new long[size];
            nativeResults = new exchange.core2.core.common.MatcherResult[size];
            baseAssetIds = new int[size]; quoteAssetIds = new int[size]; settleAssetIds = new int[size];
        }
        /** 设置本次准备会使用的有效前缀，必须为正且不超过已有计划容量。 */
        void activate(int size) {
            if (size <= 0 || size > plans.length) throw new IllegalArgumentException("invalid active batch size");
            activeSize = size;
        }
        /** 只清理本代有效前缀中的计划引用、币对、订单和撤单编号，并重置原生命令槽。 */
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
    /** 在当前结果尚未发布且下标有效时借用事件独占的原生命令结果槽，首次使用按需创建。消费者结束前不得改作另一条命令。 */
    public exchange.core2.core.common.MatcherResult nativeResultSlot(int index) {
        if (batchStorage == null || index < 0 || index >= state.batchPlanCount || directPublished)
            throw new IllegalStateException("native result slot is not writable");
        var result = batchStorage.nativeResults[index];
        if (result == null) batchStorage.nativeResults[index] = result = exchange.core2.core.common.MatcherResult.commandSlot();
        return result;
    }

    /** 在结果尚未发布时借用本事件专属的替换撤单原生命令槽，首次使用按需创建。 */
    public exchange.core2.core.common.MatcherResult replacementCancellationSlot() {
        if (batchStorage == null || directPublished)
            throw new IllegalStateException("replacement cancellation is not writable");
        if (batchStorage.replacementCancellation == null)
            batchStorage.replacementCancellation = exchange.core2.core.common.MatcherResult.commandSlot();
        return batchStorage.replacementCancellation;
    }

    /** 事件尚未激活时准备批量存储，容量不足才重建。 */
    public BatchStorage batchStorage(int size) {
        if (state.batchPlanCount != 0) throw new IllegalStateException("settlement event is still active");
        if (size <= 0) throw new IllegalArgumentException("settlement batch must not be empty");
        if (batchStorage == null || batchStorage.plans.length < size)
            batchStorage = new BatchStorage(size);
        batchStorage.activate(size);
        return batchStorage;
    }

    /** 准备一代直达事件，默认以同一 Core 序号作为最终 Lane 提交序号，委托完整入口登记租约输入。 */
    public void prepareDirect(long sequence, long laneMask, long timestamp, long position,
                       java.util.UUID commandId, int shard, AccountBook accountBook,
                       IdIndex identities, int count, java.util.List<Long> cancellations,
                       OrderCommand.ResultTarget target) {
        prepareDirect(sequence, sequence, laneMask, timestamp, position, commandId, shard,
                accountBook, identities, count, cancellations, target);
    }

    /** 检查序号、时间、路由及有效数量，重置本代准入和发布状态，保存命令证据并借用变化缓冲。顺序批次可用零提交序号先应用本项事实，整批终态再统一推进水位。 */
    public void prepareDirect(long coreSequence, long commitSequence, long laneMask, long timestamp, long position,
                       java.util.UUID commandId, int shard, AccountBook accountBook,
                       IdIndex identities, int count, java.util.List<Long> cancellations,
                       OrderCommand.ResultTarget target) {
        if (coreSequence <= 0 || commitSequence < 0 || laneMask == 0 || timestamp < 0 || position < 0 || commandId == null
                || count <= 0 || batchStorage == null || count > batchStorage.plans.length)
            throw new IllegalArgumentException("invalid direct settlement reservation");
        state.cancellation = false; directPublished = false; state.matcherPublicationDeferred = false;
        state.dispatched = false; state.queuedLaneMask = 0;
        state.expandLaneRoute = Long.bitCount(laneMask) == 1;
        state.directFailure = null;
        resetOrderCheck();
        state.directCoreSequence = coreSequence;
        this.commitSequence = commitSequence; routedLaneMask = requiredLaneMask = laneMask;
        state.commitTimestamp = timestamp; state.commitClusterPosition = position;
        state.directCommandId = commandId;
        state.directShard = shard;
        state.authorizedCancellations = java.util.Objects.requireNonNull(cancellations);
        this.accountBook = accountBook; this.identities = identities; state.resultTarget = target;
        state.batchPlanCount = count;
        changes = accountBook.acquireMatcherSettlementChanges(laneMask);
        changes.directPositionIdentities = true;
        state.collected = false;
        resetCompletions(accountBook.topology().accountLaneCount());
    }

    /** Owner 在预约 Matcher 发布前选定撤单业务及用户，后续 Lane 只应用 Matcher 接受的撤单。 */
    public void cancellation(long userId) {
        if (state.batchPlanCount == 0 || state.dispatched || directPublished || userId <= 0)
            throw new IllegalStateException("invalid direct cancellation preparation");
        state.cancellation = true;
        state.cancellationUserId = userId;
    }

    /** 在单项事件发布前保存源触发单和业务触发时间，后续按 Matcher 结果生成成功或失败终态。 */
    public void triggerCompletion(com.surprising.aeron.model.CoreTriggerOrderState trigger,
                                  long timestamp) {
        if (state.dispatched || directPublished || state.batchPlanCount != 1 || trigger == null
                || timestamp < 0 || state.sourceTrigger != null)
            throw new IllegalStateException("invalid direct trigger preparation");
        state.sourceTrigger = trigger;
        state.triggeredAt = timestamp;
    }

    /** 在单项事件预约发布前保存改单准入输入及新预留资产编号，供所属 Lane 原位执行。 */
    public void replacement(com.surprising.aeron.service.order.OrderCommand.ReplaceOrderCheck orderCheck, int assetId) {
        if (state.dispatched || directPublished || state.batchPlanCount != 1 || orderCheck == null)
            throw new IllegalStateException("invalid direct replacement preparation");
        state.replacement = orderCheck;
        state.replacementAssetId = assetId;
    }

    /** 仅允许单项事件借用首项准入订单引用，普通不可变准入输入路径可能没有此运行时值。 */
    public Order admittedOrder() {
        if (state.batchPlanCount != 1) throw new IllegalStateException("single direct order is required");
        return batchStorage.admittedOrders[0];
    }

    /** 检查当前事件是否有正数有效计划项，不表示结果已经发布或账户已经完成。 */
    public boolean direct() { return state.batchPlanCount != 0; }
    /** 返回直达 Matcher 证据和 Lane 合并使用的 Core 序号，可能不同于最终提交序号。 */
    public long coreSequence() { return state.directCoreSequence; }
    /** 单 Matcher 发布方为实际路由中的 Lane 登记一次投递，拒绝越界、未预约、非路由和重复投递。 */
    public void markLaneQueued(int laneId) {
        if (state.batchPlanCount == 0 || !state.dispatched || laneId < 0 || laneId >= Long.SIZE)
            throw new IllegalStateException("invalid direct settlement Lane queue");
        long bit = 1L << laneId;
        if ((routedLaneMask & bit) == 0 || (state.queuedLaneMask & bit) != 0) {
            throw new IllegalStateException("direct settlement Lane queue is outside its route");
        }
        state.queuedLaneMask |= bit;
    }
    /** 供 Owner 检查结果已发布且 Matcher 已结束发布过程。 */
    public boolean ready() { return directPublished && !state.matcherPublicationDeferred; }

    /** 账户只等待结果发布即可消费。其他 Lane 的投递可能仍在继续，事件仍受发布租约保护。 */
    public boolean readyForLane() {
        return directPublished;
    }

    /** 把准入回执编码转换为业务名称，尚为 NONE 时按当前接口约定返回 SUCCESS。不能据此判断回执已到达。 */
    public String orderCheckResultCode() {
        return state.orderCheckResultCode == CoreResultCode.NONE.wireCode()
                ? "SUCCESS" : CoreResultCode.fromWireCode(state.orderCheckResultCode).name();
    }

    /** 未要求普通下单准入时视为接受，否则返回已复制的准入接受标记。调用方仍须遵守回执发布顺序。 */
    public boolean orderCheckAccepted() { return !state.orderCheckRequired || state.orderCheckAccepted; }

    /** 在已登记普通下单准入路由的事件中保存不可变订单输入与用户，预留回执须匹配该订单编号。 */
    public void orderCheckOrder(OrderCommand.ResolvedPlaceOrder order, long userId) {
        if (state.batchPlanCount == 0 || !state.orderCheckRequired || order == null || userId <= 0) {
            throw new IllegalStateException("invalid place admission order");
        }
        state.orderCheckOrder = order;
        state.orderCheckUserId = userId;
        state.orderCheckOrderId = order.orderId();
    }

    /** Matcher 校验 Lane 固定收据槽的订单身份，只保存接受标记和结果码，最后通过 volatile 标记发布回执。同代不允许重复发布。 */
    public void orderCheckReceipt(long reservationId, boolean accepted, int resultCode) {
        if (state.batchPlanCount == 0 || !state.orderCheckRequired || state.orderCheckResolved || reservationId <= 0
                || reservationId != state.orderCheckOrderId
                || resultCode < 0) {
            throw new IllegalStateException("invalid place admission receipt");
        }
        state.orderCheckAccepted = accepted;
        state.orderCheckResultCode = resultCode;
        state.orderCheckResolved = true;
    }

    /** 检查原生结果、合成拒绝码或执行故障是否已准备。 */
    public boolean resultPrepared() {
        return state.directNativeResult != null
                || state.directSyntheticResultCode != null || state.directFailure != null;
    }

    /** 已预约且未发布时取得发布租约，阻止 Owner 在 Matcher 仍持有事件期间回收。 */
    public void beginMatcherPublication() {
        if (state.batchPlanCount == 0 || !state.dispatched || directPublished || state.matcherPublicationDeferred) {
            throw new IllegalStateException("invalid matcher publication start");
        }
        state.matcherPublicationDeferred = true;
    }

    /** 结束当前发布过程：已有结果或故障则继续通知 Lane 并交出租约，否则只解除延后标记。 */
    public void completeMatcherPublication() {
        if (state.batchPlanCount == 0 || !state.matcherPublicationDeferred) return;
        if (resultPrepared()) notifyDirectPublication();
        else state.matcherPublicationDeferred = false;
    }

    /** 校验单项结果的命令、序号、分片及订单证据，按撤单、普通准入或已有订单构建计划。 */
    public void publishDirectNativeResult(exchange.core2.core.common.MatcherResult result,
            long coreSequence, long commandIdMostSignificantBits, long commandIdLeastSignificantBits,
            long orderId, long matcherSequence, int matcherShard) {
        requireProof(state.dispatched && !directPublished && state.batchPlanCount == 1 && result != null,
                coreSequence, commandIdMostSignificantBits, commandIdLeastSignificantBits, matcherShard,
                matcherSequence > 0, "direct native matcher result proof is inconsistent");
        try {
            Order admitted = batchStorage.admittedOrders[0];
            if (state.cancellation) {
                if (batchStorage.cancellationOrderIds[0] != orderId)
                    throw new IllegalStateException("direct cancellation order is inconsistent");
                batchStorage.plans[0].buildDirectNativeCancellation(state.directCoreSequence, state.cancellationUserId,
                        orderId, result, accountBook);
            } else if (state.orderCheckRequired && state.orderCheckAccepted) {
                if (state.orderCheckOrder == null || state.orderCheckOrder.orderId() != orderId)
                    throw new IllegalStateException("direct admission order is inconsistent");
                batchStorage.plans[0].buildDirectNative(state.directCoreSequence, state.orderCheckUserId, state.orderCheckOrder,
                        batchStorage.instruments[0], result, accountBook);
            } else {
                if (admitted == null || admitted.orderId() != orderId)
                    throw new IllegalStateException("direct matcher order is inconsistent");
                batchStorage.plans[0].buildDirectNative(state.directCoreSequence, admitted,
                        batchStorage.instruments[0], result, accountBook);
            }
            state.directNativeResult = result;
            state.directMatcherSequence = matcherSequence;
            state.directLastMatcherSequence = matcherSequence;
            state.directNativeItemCount = 1;
            state.directNativeAccepted = nativeAccepted(result);
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
        } catch (Throwable failure) { failDirect(failure); throw failure; }
    }

    /** 严格按项下标和递增 Matcher 序号保存批量原生事实，首项承载已成功前置撤单及汇总接口的首项结果。 */
    public void publishDirectNativeBatchItem(exchange.core2.core.common.MatcherResult result,
            int index, long coreSequence,
            long commandIdMostSignificantBits, long commandIdLeastSignificantBits,
            long orderId, long matcherSequence, int matcherShard) {
        requireProof(state.dispatched && !directPublished && result != null && state.batchPlanCount > 0
                        && index == state.directNativeItemCount && index >= 0 && index < state.batchPlanCount,
                coreSequence, commandIdMostSignificantBits, commandIdLeastSignificantBits, matcherShard,
                matcherSequence > state.directLastMatcherSequence, "direct native matcher batch proof is inconsistent");
        try {
            Order admitted = batchStorage.admittedOrders[index];
            if (state.cancellation ? batchStorage.cancellationOrderIds[index] != orderId
                    : admitted == null || admitted.orderId() != orderId)
                throw new IllegalStateException("direct matcher batch order is inconsistent");
            if (state.cancellation) {
                batchStorage.plans[index].buildDirectNativeCancellation(state.directCoreSequence, state.cancellationUserId,
                        orderId, result, accountBook);
            } else {
                batchStorage.plans[index].buildDirectNative(state.directCoreSequence, admitted,
                        batchStorage.instruments[index], result, accountBook);
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
        } catch (Throwable failure) { failDirect(failure); throw failure; }
    }

    /** 关联原单撤单与新单结果，区分新单接受、post-only 拒绝和其他部分变化。 */
    public void publishDirectNativeReplacement(
            exchange.core2.core.common.MatcherResult cancelResult,
            exchange.core2.core.common.MatcherResult result,
            long coreSequence, long commandIdMostSignificantBits, long commandIdLeastSignificantBits,
            long orderId, long matcherSequence, int matcherShard) {
        requireProof(state.dispatched && !directPublished && state.replacement != null && state.batchPlanCount == 1
                        && cancelResult != null && result != null,
                coreSequence, commandIdMostSignificantBits, commandIdLeastSignificantBits, matcherShard,
                matcherSequence > 0 && state.replacement != null && state.replacement.resolved().orderId() == orderId,
                "direct native replacement proof is inconsistent");
        try {
            boolean cancellationAccepted = nativeAccepted(cancelResult);
            boolean placementAccepted = cancellationAccepted && nativeAccepted(result);
            boolean businessRejection = cancellationAccepted && !placementAccepted
                    && result.resultCode() == exchange.core2.core.common.cmd.CommandResultCode.MATCHING_POST_ONLY_FAILED;
            Order admitted = batchStorage.admittedOrders[0];
            if (admitted == null) throw new IllegalStateException("replacement order is missing");
            batchStorage.plans[0].buildDirectNative(state.directCoreSequence, admitted,
                    batchStorage.instruments[0], result, accountBook);
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
        } catch (Throwable failure) { failDirect(failure); throw failure; }
    }

    /** 为普通下单准入拒绝校验命令证据并构造无原生成交的拒绝计划，使已登记账户路径能够交回回执，不创建订单后值。 */
    public void publishDirectRejection(String resultCode, long coreSequence,
            long commandIdMostSignificantBits, long commandIdLeastSignificantBits,
            long orderId, long matcherSequence, int matcherShard) {
        requireProof(state.batchPlanCount != 0 && state.dispatched && !directPublished && state.orderCheckRequired
                        && !state.orderCheckAccepted && resultCode != null && !resultCode.isBlank(),
                coreSequence, commandIdMostSignificantBits, commandIdLeastSignificantBits, matcherShard,
                orderId == state.orderCheckOrderId && matcherSequence > 0, "direct matcher rejection proof is inconsistent");
        try {
            batchStorage.plans[0].prepareRejectedDirect(
                    state.directCoreSequence, state.orderCheckUserId, state.orderCheckOrderId, routedLaneMask);
            state.directSyntheticResultCode = resultCode;
            state.directMatcherSequence = state.directLastMatcherSequence = matcherSequence;
            state.directNativeAccepted = false;
            state.directNativeItemCount = 1;
            finishDirectPublication();
        } catch (Throwable failure) { failDirect(failure); throw failure; }
    }

    /** 先查发布状态，再核对命令序号、命令编号和分片，最后查各入口自身的订单与序号证据。 */
    private void requireProof(boolean publishable, long coreSequence,
                              long commandIdMostSignificantBits, long commandIdLeastSignificantBits,
                              int matcherShard, boolean itemEvidence, String message) {
        if (!publishable || coreSequence != state.directCoreSequence || state.directCommandId == null
                || commandIdMostSignificantBits != state.directCommandId.getMostSignificantBits()
                || commandIdLeastSignificantBits != state.directCommandId.getLeastSignificantBits()
                || matcherShard != state.directShard || !itemEvidence) {
            throw new IllegalStateException(message);
        }
    }

    private static boolean nativeAccepted(exchange.core2.core.common.MatcherResult result) {
        return result.resultCode() == exchange.core2.core.common.cmd.CommandResultCode.SUCCESS
                || result.resultCode() == exchange.core2.core.common.cmd.CommandResultCode.ACCEPTED;
    }

    /** 准入回执已复制且接受后，将 Owner 已授权撤单与 Lane 新授权撤单合并去重并按编号排序，保存为本代只读授权列表。 */
    public void addOrderCheckCancellations(java.util.List<Long> orderIds) {
        if (state.batchPlanCount == 0 || directPublished || !state.orderCheckResolved || !state.orderCheckAccepted)
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
        long[] sorted = combined.toArray();
        java.util.Arrays.sort(sorted);
        state.authorizedCancellations = com.surprising.aeron.support.collection.ImmutableLongArrayList.takeOwnership(sorted);
    }

    /** 准入成功后供 Matcher 借用合并后的授权撤单列表，未满足回执条件时拒绝读取。 */
    public java.util.List<Long> orderCheckCancellations() {
        if (state.batchPlanCount == 0 || !state.orderCheckResolved || !state.orderCheckAccepted)
            throw new IllegalStateException("place admission cancellations are not ready");
        return state.authorizedCancellations;
    }

    /** 结果发布并确认无故障后，向 Owner 返回最终授权列表以核对成功前缀。 */
    public java.util.List<Long> completedPreMatchingCancellations() {
        requireDirectResult();
        return state.authorizedCancellations;
    }

    private static long cancellationIdAt(java.util.List<Long> orderIds, int index) {
        return orderIds instanceof com.surprising.aeron.support.collection.ImmutableLongArrayList primitive
                ? primitive.valueAt(index) : orderIds.get(index);
    }

    /** 结果发布前记录一项已经接受的授权撤单，必要时扩容自有数组。这里检查授权但不另行去重，调用方保证记录顺序。 */
    public void recordDirectPreCancellation(long orderId) {
        boolean authorized = state.authorizedCancellations instanceof com.surprising.aeron.support.collection.ImmutableLongArrayList primitive
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

    /** 检查是否保存了原生结果引用，合成准入拒绝没有原生结果。 */
    public boolean hasDirectNativeResult() { return state.directNativeResult != null; }
    /** 返回本代结果证据所属 Matcher 分片。 */
    public int directMatcherShard() { return state.directShard; }
    private void finishDirectPublication() {
        long actualLanes = 0; int orders = 0;
        for (int index = 0; index < state.batchPlanCount; index++) {
            actualLanes |= batchStorage.plans[index].requiredLaneMask();
            orders = Math.addExact(orders, batchStorage.plans[index].orderCount());
        }
        // 固定多 Lane 路由的既有票据也必须消费。
        if (actualLanes == 0
                || !state.expandLaneRoute && (actualLanes & ~routedLaneMask) != 0
                || state.expandLaneRoute && (routedLaneMask & ~actualLanes) != 0)
            throw new IllegalStateException("matcher result escaped its admitted Lane dependency scope");
        if (state.expandLaneRoute) routedLaneMask = actualLanes;
        requiredLaneMask = actualLanes;
        systemFundsTrades = hasTrade(batchStorage.plans, state.batchPlanCount);
        prepareSystemFundsChanges(actualLanes, systemFundsTrades);
        changes.ensureOrderCapacity(orders, actualLanes);
        if (!state.matcherPublicationDeferred) notifyDirectPublication();
    }

    /** 为已预约且未发布的活动事件保留执行异常。 */
    public void failDirect(Throwable failure) {
        if (state.batchPlanCount == 0 || directPublished) return;
        if (!state.dispatched) throw new IllegalStateException("Matcher publication was not reserved");
        state.directFailure = java.util.Objects.requireNonNull(failure);
        if (!state.matcherPublicationDeferred) notifyDirectPublication();
    }

    /** 下单步骤 4.3：持有发布租约时释放原 Matcher 提交占用，再发布结果并完成各 Lane 投递，最后解除租约。 */
    private void notifyDirectPublication() {
        AccountBook completionRuntime = accountBook;
        // 队列中的已发布事实一旦可见，Lane 就能开始消费。
        // 发布方释放传输占用并完成全部入队前，Owner 仍不能回收事件。
        state.matcherPublicationDeferred = true;
        releaseMatcherCompletionBeforeSignal();
        directPublished = true;
        completionRuntime.publishMatcherOwned(this);
        state.matcherPublicationDeferred = false;
    }

    /** 由 Matcher 管线保存已有传输上下文的释放回调与分片，避免每命令另建回调。同代不重复登记，发布后不能修改。 */
    public void matcherCompletionRoute(MatcherCompletionRoute route, int matcherShard) {
        if (state.batchPlanCount == 0 || directPublished || state.matcherCompletionRoute != null || matcherShard < 0) {
            throw new IllegalStateException("invalid matcher completion release callback");
        }
        state.matcherCompletionRoute = java.util.Objects.requireNonNull(route);
        state.matcherCompletionShard = matcherShard;
    }

    /** 先把释放回调及分片移到局部值并清除事件引用，再通知传输上下文释放占用，后续 Lane 投递仍受发布租约保护。 */
    private void releaseMatcherCompletionBeforeSignal() {
        MatcherCompletionRoute route = state.matcherCompletionRoute;
        int shard = state.matcherCompletionShard;
        state.matcherCompletionRoute = null;
        state.matcherCompletionShard = -1;
        if (route != null) route.releaseMatcherSubmission(shard);
    }

    /** 检查已发布且无执行故障后，以本事件自身提供 OrderMatchingResult 视图，视图只在事件租约内有效。 */
    public com.surprising.aeron.service.matcher.OrderMatchingResult directResult() {
        requireDirectResult();
        return this;
    }
    /** 检查结果已发布、没有直达故障且持有原生结果或合成拒绝码。 */
    public boolean directResultAvailable() {
        return directPublished && state.directFailure == null
                && hasEventOwnedResult();
    }
    /** 作为 OrderMatchingResult 接口返回已保存接受标记。 */
    @Override public boolean accepted() {
        return state.directNativeAccepted;
    }
    /** 返回合成准入拒绝码或原生结果码名称，调用方须先确认可读结果。 */
    @Override public String resultCode() {
        if (state.directSyntheticResultCode != null) return state.directSyntheticResultCode;
        return state.directNativeResult.resultCode().name();
    }
    /** 直达接口不另建逐单撤单对象列表，始终返回空列表。 */
    @Override public java.util.List<com.surprising.aeron.service.matcher.OrderCancelResult> cancellations() {
        return java.util.List.of();
    }
    /** 按部分变化和接受标记分类：post-only 拒绝保留已知前缀，其他未能确认的部分变化视为致命分歧。 */
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
    /** 为证据接口返回本代 Core 序号。 */
    @Override public long nativeCoreSequence() {
        return state.directCoreSequence;
    }
    /** 为证据接口返回本代首项 Matcher 序号。 */
    @Override public long nativeMatcherSequence() {
        return state.directMatcherSequence;
    }
    /** 为证据接口返回本代 Matcher 分片。 */
    @Override public int nativeMatcherShardId() {
        return state.directShard;
    }
    /** 直接读取本代命令 UUID 的高低位进行比较，调用方须在有效事件代次内使用。 */
    @Override public boolean nativeMatches(long most, long least) {
        return state.directCommandId == null ? most == 0 && least == 0
                : state.directCommandId.getMostSignificantBits() == most
                && state.directCommandId.getLeastSignificantBits() == least;
    }
    /** 合成准入拒绝返回空事件列表，其他结果借用原生事件列表。 */
    @Override public java.util.List<exchange.core2.core.common.MatcherResult.MatcherEvent> matcherEvents() {
        if (state.directSyntheticResultCode != null) return java.util.List.of();
        return state.directNativeResult.events();
    }
    private boolean hasEventOwnedResult() {
        return state.directNativeResult != null || state.directSyntheticResultCode != null;
    }
    /** 如有执行故障，以其为原因抛出，防止当成普通业务拒绝。 */
    private void requireDirectResult() {
        if (state.batchPlanCount == 0 || !directPublished) throw new IllegalStateException("direct result is not published");
        if (state.directFailure != null) throw new IllegalStateException("direct matcher failed", state.directFailure);
    }

    /** 校验普通下单准入 Lane，并为活动事件设置一次需要准入回执的边界。 */
    public void orderCheckRoute(int laneId) {
        if (state.batchPlanCount == 0 || state.orderCheckRequired || laneId < 0) {
            throw new IllegalStateException("invalid place admission route");
        }
        state.orderCheckRequired = true;
    }

    /** Owner 在交给 Matcher 前预约逻辑派发状态，仅设置预约标记，不接触任何 Lane 队列。 */
    public void reserveMatcherPublication() {
        if (state.batchPlanCount == 0 || state.dispatched || directPublished) {
            throw new IllegalStateException("invalid Matcher publication reservation");
        }
        state.dispatched = true;
    }

    /** 仅在事件完成且 changes 已交出后清理命令、准入、结果及槽引用，保留批量数组和财务槽容量。下一代 prepareDirect 再重置完成位及 Owner 缓存，不能把此方法当等待在途消费者。 */
    public void clear() {
        if (!complete() || changes != null) {
            throw new IllegalStateException("cannot recycle an incomplete matcher settlement");
        }
        if (systemFundsSettlement != null) systemFundsSettlement.clear();
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
        resetOrderCheck();
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
        accountBook = null;
        identities = null;
        systemFundsTrades = false;
        state.collected = false;
    }

    static Order requireOpen(AccountPartition lane, long orderId) {
        return requireOpen(lane.order(orderId), orderId);
    }

    static Order requireOpen(Order order, long orderId) {
        if (order == null || order.canceled() || order.remainingQuantitySteps() == 0)
            throw new IllegalStateException("runtime matched order is not open: " + orderId);
        return order;
    }

    /** 已终态的订单释放剩余预留，确有释放才推进用户修订。 */
    static void releaseCanceledReservation(AccountBook accountBook, AccountPartition lane, Order order) {
        if (order == null || !order.canceled()) return;
        long releaseUnits = accountBook.releaseTerminalReservationInLane(lane, order, null);
        if (releaseUnits > 0) accountBook.advanceUserRevision(lane, order.userId());
    }

    private void resetOrderCheck() {
        state.orderCheckRequired = false;
        state.orderCheckOrder = null;
        state.orderCheckUserId = state.orderCheckOrderId = 0;
        state.orderCheckResolved = false;
        state.orderCheckAccepted = false;
        state.orderCheckResultCode = CoreResultCode.NONE.wireCode();
    }

    /** 下单步骤 4.4：所属 Lane 应用本代各计划、整理终态并发布完成位。 */
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
        // 准入拒绝时只整理完成回执， 不执行正常成交资金计算，让 Owner 能退休已提交的事件。
        if (state.orderCheckRequired && !state.orderCheckResolved) {
            throw new IllegalStateException("place admission receipt is missing");
        }
        if (state.orderCheckRequired && !state.orderCheckAccepted) {
            changes().prepareLaneTerminal(laneId, identities, lane, state.directCoreSequence);
            publishCompletion(lane);
            return;
        }
        accountBook.enterMatcherSettlementScope(lane, changes());
        try {
            SystemFundsChange delta = !systemFundsTrades
                    ? EMPTY_TREASURY_DELTA : touchedLaneSystemFundsChanges[laneSlot(laneId)];
            for (int index = 0; index < state.batchPlanCount; index++) {
                TradeSettlement.Plan batchPlan = batchStorage.plans[index];
                if ((batchPlan.requiredLaneMask() & laneMask) == 0) continue;
                applyPlan(lane, laneId, batchPlan, batchStorage.instruments[index],
                        batchStorage.baseAssetIds[index], batchStorage.quoteAssetIds[index],
                        batchStorage.settleAssetIds[index], delta,
                        batchStorage.admittedOrders[index]);
                if (commitSequence != 0 || state.cancellation) {
                    accountBook.stampMatcherOrders(
                            lane, batchPlan, state.commitTimestamp, state.commitClusterPosition);
                }
            }
            changes.prepareLaneTerminal(laneId, identities, lane, state.directCoreSequence);
            if (state.resultTarget != null
                    && laneId == accountBook.topology().accountLaneId(plan().activeUserId())) {
                OrderCommand.ResultTarget.capture(state.resultTarget, changes.laneDeltas[laneId], identities, lane);
            }
            if (commitSequence != 0) {
                lane.applied(state.directShard, commitSequence);
                lane.committed(state.directShard, commitSequence);
                accountBook.publishLaneCommittedSequence(lane);
            }
        } finally {
            accountBook.exitMatcherSettlementScope(lane, changes);
        }
        // 每个 Lane 独立发布自己的完成位，Owner 核对事件整体完成边界， 避免所有账户线程竞争写同一个原子 OR 位图。
        publishCompletion(lane);
    }

    /** Lane 记录操作并以 release 发布自己独立的完成标记，拒绝重复完成。 */
    private void publishCompletion(AccountPartition lane) {
        int laneId = lane.laneId();
        AccountBook completionRuntime = accountBook;
        lane.recordSettlementOperation();
        int completionOffset = laneId * CACHE_LINE_LONGS;
        if ((long) LONGS.getAcquire(completedLanes, completionOffset) != 0) {
            throw new IllegalStateException("account lane completed the same matcher fact twice");
        }
        LONGS.setRelease(completedLanes, completionOffset, 1L);
        completionRuntime.publishDirectMatcherSettlementReady(laneId);
    }

    private void applyPlan(AccountPartition lane, int laneId, TradeSettlement.Plan value,
                           CoreInstrument valueInstrument, int valueBaseAssetId,
                           int valueQuoteAssetId, int valueSettleAssetId, SystemFundsChange delta,
                           Order orderCheckVersion) {
        if (state.cancellation) {
            if (!value.rejectedTaker())
                accountBook.cancelOrderInLane(lane, value.activeUserId(), value.takerOrderId(),
                        state.commitTimestamp, state.commitClusterPosition);
            return;
        }
        if (state.replacement != null) {
            applyReplacement(lane, value);
            if (!accepted()) return;
        }
        value.validateDirectLane(lane, accountBook, identities, valueInstrument);
        for (int index = 0; state.replacement == null && index < value.preCancellationCount(); index++) {
            long orderId = value.preCancellationOrderId(index);
            Order order = lane.order(orderId);
            if (order != null && order.status() == CoreOrderStatus.OPEN) {
                accountBook.cancelOrderInLane(lane, order.userId(), order, -1, -1);
            }
        }
        if (value.rejectedTaker()) {
            if (lane.laneId() == accountBook.accountLaneId(value.activeUserId()))
                accountBook.rejectOrderInLane(lane, value.activeUserId(), value.takerOrderId(), state.commitTimestamp, state.commitClusterPosition);
        } else if (valueInstrument.contractType().productLine().isDerivative()) {
            DerivativeTradeSettlementService.applyLane(value.takerOrderId(), value, lane, laneId,
                    accountBook, identities, valueInstrument, valueSettleAssetId, delta, state.commitTimestamp, state.commitClusterPosition);
        } else {
            SpotTradeSettlementService.applyLane(value.takerOrderId(), value, lane, laneId,
                    accountBook, valueInstrument, valueBaseAssetId, valueQuoteAssetId, delta, state.commitTimestamp, state.commitClusterPosition);
        }
        accountBook.completeMatcherPendingReservations(lane, value, orderCheckVersion);
        publishAdmittedState(lane, value, orderCheckVersion);
        var trigger = value.completedTrigger();
        if (trigger != null && lane.laneId() == accountBook.accountLaneId(trigger.userId())) accountBook.putTriggerOrder(lane, trigger);
    }

    private void publishAdmittedState(
            AccountPartition lane, TradeSettlement.Plan value, Order orderCheckVersion) {
        Order admitted = orderCheckVersion == null ? value.admittedTaker() : orderCheckVersion;
        boolean eventOrderCheck = state.orderCheckRequired && state.orderCheckAccepted && state.orderCheckOrder != null;
        long userId = admitted != null ? admitted.userId()
                : eventOrderCheck ? state.orderCheckUserId : value.activeUserId();
        long orderId = admitted != null ? admitted.orderId()
                : eventOrderCheck ? state.orderCheckOrderId : value.takerOrderId();
        if ((admitted == null && !eventOrderCheck)
                || lane.laneId() != accountBook.accountLaneId(userId)) return;
        accountBook.publishUser(lane, userId, lane.user(userId));
        accountBook.publishOrder(lane, orderId, lane.order(orderId));
        accountBook.publishReservation(lane, orderId, lane.reservation(orderId));
    }

    private void applyReplacement(AccountPartition lane, TradeSettlement.Plan value) {
        if (lane.laneId() != accountBook.accountLaneId(state.replacement.userId())) return;
        long userId = state.replacement.userId();
        Order original = lane.order(state.replacement.originalOrderId());
        UserAccount user = lane.user(userId);
        if (original == null || original.revision() != state.replacement.originalOrderRevision()
                || user == null || user.revision() != state.replacement.userRevision())
            throw new IllegalStateException("replace admission changed before Lane execution: order="
                    + (original == null ? "missing" : original.revision()) + '/'
                    + state.replacement.originalOrderRevision() + " user="
                    + (user == null ? "missing" : user.revision()) + '/' + state.replacement.userRevision());
        for (int index = 0; index < value.preCancellationCount(); index++)
            accountBook.cancelOrderInLane(lane, userId, value.preCancellationOrderId(index),
                    state.commitTimestamp, state.commitClusterPosition);
        if (!accepted()) return;
        Order prepared = batchStorage.admittedOrders[0];
        if (lane.order(state.replacement.originalOrderId()).status() != CoreOrderStatus.CANCELED)
            throw new IllegalStateException("accepted replacement did not cancel original order");
        long clientKey = IdIndex.clientKeyValue(
                identities.prepareClientKeyInLane(lane, userId, prepared.clientOrderId()));
        accountBook.accountRollback().captureBalanceBefore(lane, userId, state.replacementAssetId);
        accountBook.placeOrderInLane(lane, userId, state.replacement.resolved(), state.directCommandId,
                state.replacement.requiredReservationUnits(), clientKey, prepared.symbolId(), state.replacementAssetId,
                state.directCoreSequence, prepared, state.commitTimestamp, state.commitClusterPosition);
        accountBook.publishUser(lane, userId, lane.user(userId));
        accountBook.publishOrder(lane, prepared.orderId(), lane.order(prepared.orderId()));
        accountBook.publishReservation(lane, prepared.orderId(), lane.reservation(prepared.orderId()));
        accountBook.accountRollback().captureBalanceAfter(lane, userId, state.replacementAssetId);
    }

    /** 按实际参与 Lane 的紧凑顺序保留财务差额槽，容量不足才扩展。 */
    private void prepareSystemFundsChanges(long laneMask, boolean trades) {
        if (trades) {
            int touchedLaneCount = Long.bitCount(laneMask);
            int capacity = touchedLaneSystemFundsChanges == null ? 0 : touchedLaneSystemFundsChanges.length;
            if (capacity < touchedLaneCount) {
                touchedLaneSystemFundsChanges = capacity == 0
                        ? new SystemFundsChange[touchedLaneCount]
                        : java.util.Arrays.copyOf(touchedLaneSystemFundsChanges, touchedLaneCount);
                for (int index = capacity; index < touchedLaneCount; index++) {
                    touchedLaneSystemFundsChanges[index] = new SystemFundsChange();
                }
            }
        }
        if (touchedLaneSystemFundsChanges != null)
            for (SystemFundsChange delta : touchedLaneSystemFundsChanges) delta.clear();
    }

    private static boolean hasTrade(TradeSettlement.Plan[] plans, int planCount) {
        for (int index = 0; index < planCount; index++) if (plans[index].tradeCount() != 0) return true;
        return false;
    }

    /** Owner 仅在发布结束后以 acquire 读取尚未观察完成的路由 Lane 标记，并缓存已完成位，不回退本代观察结果。 */
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

    /** 事件就绪且没有独立账户线程时，按路由推进尚未完成的 Lane。不在此代替已启动的账户工作线程执行。 */
    public void ensureLanesExecuted() {
        if (!ready() || accountBook == null || accountBook.accountLanesStarted() || !state.dispatched) return;
        long remaining = routedLaneMask() & ~completedLaneMask();
        while (remaining != 0) {
            int laneId = Long.numberOfTrailingZeros(remaining);
            remaining &= remaining - 1;
            if ((state.queuedLaneMask & (1L << laneId)) == 0) {
                markLaneQueued(laneId);
            }
            execute(accountBook.accountLane(laneId));
        }
    }

    /** Owner 等齐账户回执后统一推进已登记的财务结算。 */
    public boolean complete() {
        if (state.ownerObservedComplete) return true;
        if (!ready()) return false;
        ensureLanesExecuted();
        if (completedLaneMask() != routedLaneMask()) return false;
        if (hasLedgerSettlement() && !systemFundsSettlement.completed)
            systemFundsSettlement.apply(accountBook.systemFunds());
        state.ownerObservedComplete = true;
        return true;
    }

    /** 借用首个有效结算计划，空代次由带下标入口拒绝。 */
    public TradeSettlement.Plan plan() { return plan(0); }
    /** 返回本代有效计划数，不是批量存储容量。 */
    public int planCount() { return state.batchPlanCount; }
    /** 校验下标位于本代有效项前缀后借用对应计划，不暴露旧容量尾部。 */
    public TradeSettlement.Plan plan(int index) {
        if (index < 0 || index >= planCount()) throw new IndexOutOfBoundsException(index);
        return batchStorage.plans[index];
    }
    /** 借用本代保留的结算变化缓冲，已交出或未准备时失败。 */
    public TradeSettlement.Changes changes() {
        if (changes == null) throw new IllegalStateException("matcher settlement changes are unavailable");
        return changes;
    }

    /** 检查当前事件是否仍持有变化缓冲。 */
    public boolean hasChanges() { return changes != null; }
    /** 把变化缓冲引用交给收集方并从事件解除，不等待消费者。 */
    public TradeSettlement.Changes takeChanges() {
        TradeSettlement.Changes value = changes();
        changes = null;
        return value;
    }

    /** 财务结算完成后一次性收集账户事实。 */
    public void markCollected() {
        if (!hasLedgerSettlement() || !complete())
            throw new IllegalStateException("matcher financial settlement is incomplete");
        if (state.collected) throw new IllegalStateException("matcher settlement event was already collected");
        state.collected = true;
    }

    private SystemFundsChange mergeSystemFundsChanges() {
        if (!systemFundsTrades) return EMPTY_TREASURY_DELTA;
        SystemFundsChange aggregate = null;
        int touchedLaneCount = Long.bitCount(requiredLaneMask);
        for (int index = 0; index < touchedLaneCount; index++) {
            SystemFundsChange delta = touchedLaneSystemFundsChanges[index];
            if (aggregate == null) aggregate = delta;
            else aggregate.merge(delta);
        }
        if (aggregate == null) throw new IllegalStateException("matcher settlement event has no lane delta");
        return aggregate;
    }

    /** 登记本代财务结算并尝试推进。账户回执未齐时由 complete 继续，禁止重复登记。 */
    public void submitSystemFundsSettlement() {
        if (accountBook == null || changes == null || state.collected)
            throw new IllegalStateException("Ledger settlement requires retained isolated account facts");
        if (systemFundsSettlement == null) systemFundsSettlement = new SystemFundsSettlement();
        if (systemFundsSettlement.queued) throw new IllegalStateException("Treasury settlement was already queued");
        state.ownerObservedComplete = false;
        systemFundsSettlement.queued = true;
        complete();
    }

    /** 返回财务工作是否已登记成功，包括本地无成交与就地完成路径。 */
    public boolean hasLedgerSettlement() { return systemFundsSettlement != null && systemFundsSettlement.queued; }

    /** 本事件复用的财务槽，Owner 等账户完成后应用差额并核对资金。 */
    private final class SystemFundsSettlement {
        private final FundsTotal funds = new FundsTotal();
        /** 本代财务工作已成功登记，等待 Owner 在账户完成后推进。 */
        private boolean queued;
        private boolean completed;

        /** 全部账户回执到齐后由 Owner 核对资金。 */
        public void apply(SystemFunds systemFunds) {
            if (completed || (systemFundsTrades && systemFunds.hasChangedValues()))
                throw new IllegalStateException("invalid Ledger financial scope");
            if (systemFundsTrades) mergeSystemFundsChanges().apply(systemFunds);
            changes.appendFundsDelta(requiredLaneMask, funds);
            if (systemFundsTrades) systemFunds.appendFundsDelta(funds);
            funds.requireConserved(false);
            funds.clear();
            if (systemFundsTrades) {
                accountBook.applyLedgerSettlementPositions(changes, requiredLaneMask, identities);
                systemFunds.clearChangedKeys();
            }
            completed = true;
        }

        /** 确认已登记账本工作完成且资金临时缓冲为空后，重置本槽标记，保留容量。 */
        void clear() {
            if (queued && !completed) throw new IllegalStateException("Ledger still owns this settlement");
            if (!funds.isEmpty()) throw new IllegalStateException("Ledger funds scratch was not cleared");
            queued = completed = false;
        }
    }

    private int laneSlot(int laneId) {
        long laneBit = 1L << laneId;
        if ((requiredLaneMask & laneBit) == 0) {
            throw new IllegalStateException("matcher fact has no treasury slot for lane " + laneId);
        }
        return Long.bitCount(requiredLaneMask & (laneBit - 1));
    }

    /** 清空 Owner 观察缓存和完成缓存，按 Lane 数准备带间隔的完成数组。 */
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

/** 下单步骤 4.2：Matcher 根据原生成交事实构建受影响订单和账户 Lane 路由。数组、事件和准入引用受在途槽租约约束，不能当成永久快照。 */
public static final class Plan {
    /** 本代 taker 或撤单请求是否被拒绝，不能与已计入成交并存。 */
    @Accessors(fluent = true, chain = false)
    @Getter(value = AccessLevel.PACKAGE)
    private boolean rejectedTaker;
    /** 保存撮合拒单标记并返回本计划。已有成交时禁止标记拒绝，防止用拒单覆盖已发生的成交事实。 */
    public Plan rejectTaker(boolean rejected) {
        if (rejected && tradeCount != 0) throw new IllegalArgumentException("rejected taker cannot contain trades");
        rejectedTaker = rejected;
        return this;
    }

    @Accessors(fluent = true, chain = false)
    @Getter
    private long coreSequence;
    @Accessors(fluent = true, chain = false)
    @Getter
    private long takerOrderId;
    @Accessors(fluent = true, chain = false)
    @Getter
    private long activeUserId;
    @Accessors(fluent = true, chain = false)
    @Getter
    private long requiredLaneMask;
    private long[] orderIds;
    @Accessors(fluent = true, chain = false)
    @Getter
    private int orderCount;
    private List<MatcherEvent> matcherEvents;
    @Accessors(fluent = true, chain = false)
    @Getter
    private int tradeCount;
    private long[] preCancellationOrderIds;
    private int preCancellationSize;
    private long[] preCancellationStorage;
    private int[] makerLaneHeads;
    private int[] makerLaneNext;
    /** 构建时缓存的主动账户 Lane，深度索引中该 Lane 仍读取完整原事件顺序。 */
    private int takerLaneId = -1;
    private boolean laneEventsIndexed;
    private static final long[] NO_ORDERS = new long[0];
    /** 构建时保留的准入订单值或命令槽引用，消费者遵守该代租约。返回构建时保留的准入订单引用，借用期受计划租约限制，可能为空。 */
    @Accessors(fluent = true, chain = false)
    @Getter(value = AccessLevel.PACKAGE)
    private Order admittedTaker;
    private com.surprising.aeron.protocol.CoreOrderSide directTakerSide;
    private int directTakerSymbolId;
    private CoreInstrument directTakerInstrument;
    private final LongHashSet directOrderKeys = new LongHashSet();

    /** 创建可复用计划，先保留八个订单编号槽，事件和前置撤单使用空值。 */
    public Plan() { orderIds = new long[8]; preCancellationOrderIds = NO_ORDERS; matcherEvents = List.of(); }

    /** 清空本代事件、触发终态、订单引用、有效计数和直达校验字段。 */
    public void clearReferences() {
        matcherEvents = List.of(); completedTrigger = null; preCancellationOrderIds = NO_ORDERS;
        preCancellationSize = 0;
        rejectedTaker = false; laneEventsIndexed = false; orderCount = tradeCount = 0;
        admittedTaker = null; takerLaneId = -1;
        directTakerSide = null;
        directTakerSymbolId = 0;
        directTakerInstrument = null;
    }

    /** 由已准入运行时订单及原生 MatcherResult 构建直达计划，按订单剩余量校验成交。 */
    void buildDirectNative(long sequence, Order taker, CoreInstrument instrument,
                           exchange.core2.core.common.MatcherResult result, AccountBook accountBook) {
        if (taker == null || instrument == null || result == null || instrument != taker.instrument())
            throw new IllegalArgumentException("invalid direct native matcher fact");
        buildDirectNative(sequence, taker.orderId(), taker.userId(), taker.side(), taker.symbolId(),
                taker.instrument(), taker.remainingQuantitySteps(), taker, result, accountBook);
    }

    /** 由普通下单不可变准入值构建原生事实，使用完整准入数量，保留无需跨线程读取账户的校验字段。 */
    void buildDirectNative(long sequence, long userId, OrderCommand.ResolvedPlaceOrder taker, CoreInstrument instrument,
                           exchange.core2.core.common.MatcherResult result, AccountBook accountBook) {
        if (userId <= 0 || taker == null || instrument == null || result == null
                || instrument != taker.instrument())
            throw new IllegalArgumentException("invalid direct native matcher fact");
        buildDirectNative(sequence, taker.orderId(), userId, taker.side(), taker.symbolId(),
                taker.instrument(), taker.quantitySteps(), null, result, accountBook);
    }

    private void buildDirectNative(long sequence, long orderId, long userId,
                                   com.surprising.aeron.protocol.CoreOrderSide side, int symbolId,
                                   CoreInstrument takerInstrument, long remainingQuantity, Order taker,
                                   exchange.core2.core.common.MatcherResult result, AccountBook accountBook) {
        clearReferences();
        coreSequence = sequence; admittedTaker = taker;
        directTakerSide = side; directTakerSymbolId = symbolId; directTakerInstrument = takerInstrument;
        takerOrderId = orderId; activeUserId = userId;
        takerLaneId = accountBook.topology().accountLaneId(activeUserId);
        requiredLaneMask = accountBook.topology().accountLaneMask(activeUserId);
        matcherEvents = result.events();
        int eventCount = matcherEvents.size();
        if (eventCount == 0) {
            orderIds[0] = takerOrderId;
            orderCount = 1;
            rejectTaker(!nativeAccepted(result));
            return;
        }
        int capacity = Math.addExact(eventCount, 1);
        if (orderIds.length < capacity) orderIds = new long[Math.max(capacity, orderIds.length * 2)];
        if (eventCount <= 4) {
            orderIds[0] = takerOrderId;
            int count = 1;
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
                requiredLaneMask |= accountBook.topology().accountLaneMask(event.matchedOrderUid());
                tradeCount++;
            }
            orderCount = count;
            rejectTaker(!nativeAccepted(result));
            return;
        }
        LongHashSet keys = directOrderKeys;
        try {
            orderCount = addUnique(keys, orderIds, 0, takerOrderId);
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
                requiredLaneMask |= accountBook.topology().accountLaneMask(event.matchedOrderUid());
                tradeCount++;
            }
            rejectTaker(!nativeAccepted(result));
            indexLaneEvents(accountBook);
        } finally { keys.clear(); }
    }

    private static int addUniqueLinear(long[] values, int size, long value) {
        for (int i = 0; i < size; i++) {
            if (values[i] == value) return size;
        }
        values[size] = value;
        return size + 1;
    }

    private static int addUnique(LongHashSet unique, long[] values, int size, long value) {
        if (!unique.add(value)) return size;
        values[size] = value;
        return size + 1;
    }

    /** 检查原生撤单不夹带成交，重置并保存目标用户路由和接受标记。 */
    void buildDirectNativeCancellation(long sequence, long userId, long orderId,
                                       exchange.core2.core.common.MatcherResult result,
                                       AccountBook accountBook) {
        if (orderId <= 0 || result == null) throw new IllegalArgumentException("invalid direct cancellation fact");
        for (int index = 0; index < result.events().size(); index++) {
            MatcherEvent event = result.events().get(index);
            if (event == null || event.eventType() == MatcherEventType.TRADE)
                throw new IllegalStateException("cancel result contains a trade");
        }
        clearReferences();
        boolean accepted = nativeAccepted(result);
        coreSequence = sequence; takerOrderId = orderId; activeUserId = userId;
        takerLaneId = accountBook.topology().accountLaneId(activeUserId);
        rejectedTaker = !accepted;
        requiredLaneMask = accountBook.topology().accountLaneMask(activeUserId);
        orderCount = accepted ? 1 : 0;
        if (orderCount != 0) orderIds[0] = orderId;
    }

    /** 为没有原生结果的准入拒绝只保留身份与路由，让已登记的账户路径能够交回完成回执。 */
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

    /** 原地移除本计划编号前缀中的未成功下出的 taker，保留其他已发生变化的订单编号及原有顺序。 */
    void omitUnplacedOrder() {
        int count = 0;
        for (int index = 0; index < orderCount; index++)
            if (orderIds[index] != takerOrderId) orderIds[count++] = orderIds[index];
        orderCount = count;
    }

    /** 所属 Lane 以当前权威订单校验直达成交身份及 maker 单次剩余量，必要时保留持仓身份。深度成交的非 taker Lane 只沿自己的 maker 链遍历，不跨线程读其他账户。 */
    void validateDirectLane(AccountPartition lane, AccountBook accountBook,
                            IdIndex identities, CoreInstrument instrument) {
        if (directTakerSide == null) return;
        if (accountBook.topology().accountLaneId(activeUserId) == lane.laneId()) {
            Order taker = lane.order(takerOrderId);
            requireDirectOrder(taker, activeUserId, directTakerSide);
            if (tradeCount != 0) accountBook.retainDirectPositionIdentity(lane, identities, instrument, taker);
        }
        for (int index = firstMatcherEvent(lane.laneId()); index >= 0;
                index = nextMatcherEvent(index, lane.laneId())) {
            MatcherEvent event = matcherEvents.get(index);
            if (event.eventType() != MatcherEventType.TRADE
                    || !matcherEventTouchesLane(index, lane.laneId(), accountBook)
                    || accountBook.topology().accountLaneId(event.matchedOrderUid()) != lane.laneId()) continue;
            Order maker = lane.order(event.matchedOrderId());
            requireDirectOrder(maker, event.matchedOrderUid(), directTakerSide
                    == com.surprising.aeron.protocol.CoreOrderSide.BUY
                    ? com.surprising.aeron.protocol.CoreOrderSide.SELL
                    : com.surprising.aeron.protocol.CoreOrderSide.BUY);
            if (maker.remainingQuantitySteps() < event.size())
                throw new IllegalStateException("fill exceeds Lane maker remaining quantity");
            accountBook.retainDirectPositionIdentity(lane, identities, instrument, maker);
        }
    }

    private void requireDirectOrder(Order order, long userId,
                                    com.surprising.aeron.protocol.CoreOrderSide side) {
        if (order == null || order.status() != CoreOrderStatus.OPEN || order.userId() != userId
                || order.symbolId() != directTakerSymbolId || order.side() != side
                || order.instrument() != directTakerInstrument)
            throw new IllegalStateException("matcher fact does not match Lane-owned order");
    }
    @Accessors(fluent = true, chain = false)
    @Getter
    private com.surprising.aeron.model.CoreTriggerOrderState completedTrigger;

    /** 把触发终态附到同一结算事实：用户必须匹配，拒单要求失败状态且无子订单，非拒单要求子订单编号匹配。 */
    public Plan completeTrigger(com.surprising.aeron.model.CoreTriggerOrderState value) {
        if (value == null || value.userId() != activeUserId || (rejectedTaker
                ? value.status() != com.surprising.aeron.protocol.CoreTriggerOrderStatus.TRIGGER_FAILED
                        || value.placedOrderId() != 0
                : value.placedOrderId() != takerOrderId) || completedTrigger != null)
            throw new IllegalArgumentException("invalid settlement trigger completion");
        completedTrigger = value;
        return this;
    }

    /** 直接读取原语订单编号。 */
    public long orderId(int index) { return orderIds[index]; }
    /** 返回本代 Matcher 事件总数，包括非成交事件。 */
    public int matcherEventCount() { return matcherEvents.size(); }
    /** 按列表下标读取本代原始 Matcher 事件引用。 */
    public MatcherEvent matcherEvent(int index) { return matcherEvents.get(index); }
    /** 将已经接受的替换原单撤单保存为唯一前置撤单项。 */
    void acceptedReplacementCancellation(long orderId) {
        if (orderId <= 0) throw new IllegalArgumentException("replacement cancellation is required");
        ensurePreCancellationCapacity(1);
        preCancellationStorage[0] = orderId;
        preCancellationOrderIds = preCancellationStorage;
        preCancellationSize = 1;
    }

    /** 把调用方已经确认成功的撤单前缀追加到现有自有缓冲，不覆盖已有替换撤单。 */
    void acceptedPreCancellations(long[] orderIds, int count) {
        if (count < 0 || count != 0 && (orderIds == null || count > orderIds.length))
            throw new IllegalArgumentException("accepted cancellations are invalid");
        if (count == 0) return;
        ensurePreCancellationCapacity(preCancellationSize + count);
        System.arraycopy(orderIds, 0, preCancellationStorage, preCancellationSize, count);
        preCancellationSize += count;
        preCancellationOrderIds = preCancellationStorage;
    }

    private void ensurePreCancellationCapacity(int required) {
        if (required <= 0) return;
        if (preCancellationStorage == null) {
            preCancellationStorage = new long[Math.max(4, required)];
        } else if (preCancellationStorage.length < required) {
            preCancellationStorage = java.util.Arrays.copyOf(preCancellationStorage,
                    Math.max(required, preCancellationStorage.length * 2));
        }
    }
    /** 返回本代前置撤单有效项数。 */
    public int preCancellationCount() { return preCancellationSize; }
    /** 读取前置撤单原语编号，调用方保证下标小于有效项数。 */
    long preCancellationOrderId(int index) { return preCancellationOrderIds[index]; }
    /** 事件至少八个且涉及多个 Lane 时，逆序串出各 maker Lane 的事件链，保持原成交顺序。taker Lane 继续顺序读取全部原事件。 */
    private Plan indexLaneEvents(AccountBook accountBook) {
        // 深度成交时各 maker Lane 只沿自己的事件链读取， taker Lane 仍保留原事件顺序，不复制成交事件。
        if (matcherEvents.size() < 8 || Long.bitCount(requiredLaneMask) < 2) return this;
        laneEventsIndexed = true;
        if (makerLaneHeads == null || makerLaneHeads.length != accountBook.topology().accountLaneCount())
            makerLaneHeads = new int[accountBook.topology().accountLaneCount()];
        if (makerLaneNext == null || makerLaneNext.length < matcherEvents.size())
            makerLaneNext = new int[matcherEvents.size()];
        java.util.Arrays.fill(makerLaneHeads, -1);
        for (int index = matcherEvents.size() - 1; index >= 0; index--) {
            MatcherEvent event = matcherEvents.get(index);
            if (event.eventType() != MatcherEventType.TRADE) continue;
            int lane = accountBook.topology().accountLaneId(event.matchedOrderUid());
            makerLaneNext[index] = makerLaneHeads[lane];
            makerLaneHeads[lane] = index;
        }
        return this;
    }

    /** 返回当前 Lane 应开始读取的事件下标。 */
    int firstMatcherEvent(int laneId) {
        if (laneEventsIndexed && laneId != takerLaneId) return makerLaneHeads[laneId];
        return matcherEvents.isEmpty() ? -1 : 0;
    }

    /** 沿当前 Lane 的 maker 链或原事件顺序取得下一项，负一表示本 Lane 遍历结束。 */
    int nextMatcherEvent(int index, int laneId) {
        if (laneEventsIndexed && laneId != takerLaneId) return makerLaneNext[index];
        return index + 1 < matcherEvents.size() ? index + 1 : -1;
    }

    /** 判断当前成交是否涉及指定 Lane。 */
    public boolean matcherEventTouchesLane(int index, int laneId, AccountBook accountBook) {
        if (laneEventsIndexed && laneId != takerLaneId) return true;
        MatcherEvent event = matcherEvents.get(index);
        return takerLaneId == laneId
                || accountBook.topology().accountLaneId(event.matchedOrderUid()) == laneId;
    }
}

/** 下单步骤 4.4 与 4.6 的结算变化缓冲。完成交接后供财务核对和 Owner 收集。权威账户仍在 AccountPartition，缓冲只在当前事件租约内有效，清空保留容量。 */
public static final class Changes {
    private static final AccountPartitionCommit.Delta EMPTY_LANE_DELTA = new AccountPartitionCommit.Delta();
    /** 未激活 Lane 共用的空余额捕获哨兵，不可写入业务变化。 */
    private static final AccountTransactionRollback.BalancePatches EMPTY_BALANCE_PATCHES = new AccountTransactionRollback.BalancePatches();
    /** 未激活 Lane 共用的空资金增量哨兵，不可累加金额。 */
    private static final FundsTotal EMPTY_FUNDS_DELTA = new FundsTotal();
    /** 未激活 Lane 共用的空用户修订哨兵，不可写入用户增量。 */
    private static final LongLongHashMap EMPTY_USER_REVISIONS = new LongLongHashMap();
    /** Owner 收集时据此释放已发布身份。返回本代是否采用直达持仓身份交接，供 Owner 选择身份释放方式。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    boolean directPositionIdentities;
    @Accessors(fluent = true, chain = false)
    @Getter
    @Setter
    boolean inUse;
    @Accessors(fluent = true, chain = false)
    @Getter
    final int ringIndex;
    /** 由持有租约的调用方直接设置参与位图。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    @Setter
    long activeLaneMask;
    final AccountPartitionCommit.Delta[] laneDeltas;
    /** 按 Lane 保存的余额前后值，供回滚及资金差额核对。 */
    public final AccountTransactionRollback.BalancePatches[] balancePatches;
    final FundsTotal[] laneFundsDeltas;
    final LongLongHashMap[] userRevisionDeltas;

    /** 按账户 Lane 数创建缓冲入口，默认不关联固定环槽。 */
    public Changes(int laneCount) {
        this(laneCount, -1);
    }

    /** 保存环槽编号并创建各 Lane 的缓冲列，未参与的 Lane 先共用只读空哨兵，真正使用时再分配独立缓冲。 */
    public Changes(int laneCount, int ringIndex) {
        this.ringIndex = ringIndex;
        laneDeltas = new AccountPartitionCommit.Delta[laneCount];
        balancePatches = new AccountTransactionRollback.BalancePatches[laneCount];
        laneFundsDeltas = new FundsTotal[laneCount];
        userRevisionDeltas = new LongLongHashMap[laneCount];
        java.util.Arrays.fill(laneDeltas, EMPTY_LANE_DELTA);
        java.util.Arrays.fill(balancePatches, EMPTY_BALANCE_PATCHES);
        java.util.Arrays.fill(laneFundsDeltas, EMPTY_FUNDS_DELTA);
        java.util.Arrays.fill(userRevisionDeltas, EMPTY_USER_REVISIONS);
    }

    /** 为位图内的 Lane 把空哨兵换成独立可写缓冲。 */
    public void ensureActiveLanes(long laneMask) {
        while (laneMask != 0) {
            int laneId = Long.numberOfTrailingZeros(laneMask);
            laneMask &= laneMask - 1;
            if (laneDeltas[laneId] == EMPTY_LANE_DELTA) laneDeltas[laneId] = new AccountPartitionCommit.Delta();
            if (balancePatches[laneId] == EMPTY_BALANCE_PATCHES) balancePatches[laneId] = new AccountTransactionRollback.BalancePatches();
            if (laneFundsDeltas[laneId] == EMPTY_FUNDS_DELTA) laneFundsDeltas[laneId] = new FundsTotal();
            if (userRevisionDeltas[laneId] == EMPTY_USER_REVISIONS) userRevisionDeltas[laneId] = new LongLongHashMap();
        }
    }

    /** 把用户在本代结算中的修订增量累加到所属 Lane 缓冲。 */
    public void addUserRevision(int laneId, long userId, long count) {
        userRevisionDeltas[laneId].addToValue(userId, count);
    }

    /** 在所属 Lane 应用已聚合的用户修订增量并写入发布变化。 */
    void applyUserRevisions(int laneId, AccountPartition lane) {
        LongLongHashMap deltas = userRevisionDeltas[laneId];
        if (deltas.isEmpty()) return;
        lane.applyUserRevisions(deltas, laneDeltas[laneId]);
        deltas.clear();
    }

    /** 为指定 Lane 提前准备准入与终态槽容量。 */
    public void ensureOrderCheckCapacity(int laneId, int expectedOrders) {
        if (expectedOrders <= 0) return;
        ensureActiveLanes(1L << laneId);
        laneDeltas[laneId].ensureOrderCheckCapacity(expectedOrders);
        laneDeltas[laneId].ensureTerminalCapacity(expectedOrders);
    }

    /** 按本次实际路由登记参与 Lane，并为这些 Lane 提前准备订单及终态槽容量。 */
    public void ensureOrderCapacity(int expectedOrders, long laneMask) {
        if (expectedOrders <= 0) return;
        activeLaneMask |= laneMask;
        ensureActiveLanes(laneMask);
        while (laneMask != 0) {
            int laneId = Long.numberOfTrailingZeros(laneMask);
            laneMask &= laneMask - 1;
            laneDeltas[laneId].ensureOrderCapacity(expectedOrders);
            laneDeltas[laneId].ensureTerminalCapacity(expectedOrders);
        }
    }

    /** 下单步骤 4.4 的账户收尾：应用修订、记录终态，预留耗尽后移除可退休订单并释放客户身份。 */
    public void prepareLaneTerminal(int laneId, IdIndex identities, AccountPartition lane, long sequence) {
        applyUserRevisions(laneId, lane);
        AccountPartitionCommit.Delta changes = laneDeltas[laneId];
        for (int index = 0; index < changes.orders().size(); index++) {
            long orderId = changes.orders().keyAt(index);
            Order order = changes.orders().valueAt(index);
            if (order == null || !order.status().terminal()) continue;
            changes.recordTerminalOrder(order);
            OrderFundsReservation reservation = lane.reservation(orderId);
            if (reservation != null && reservation.reservedUnits() != 0) continue;
            if (reservation != null) {
                lane.removeReservationValue(orderId);
                lane.removeReservationId(order.userId(), orderId);
                changes.removeReservationRoute(orderId);
            }
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
            Position position = changes.positions().valueAt(positionIndex);
            if (position != null && position.signedQuantitySteps() == 0) {
                LongHashSet ids = lane.triggerIds(position.userId());
                if (ids != null && !ids.isEmpty()) {
                    String instrumentId = identities.instrumentId(position.symbolId());
                    var iterator = ids.longIterator();
                    while (iterator.hasNext()) {
                        long id = iterator.next();
                        CoreTriggerOrderState trigger = lane.triggerOrder(id);
                        if (trigger == null || trigger.status() != CoreTriggerOrderStatus.PENDING
                                || trigger.positionSide() != position.positionSide()
                                || trigger.marginMode() != position.marginMode()
                                || !instrumentId.equals(trigger.instrumentId())) continue;
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
        AccountBook.prepareBalanceFundsDelta(balancePatches[laneId], laneFundsDeltas[laneId]);
    }

    /** 从指定 Lane 已捕获的余额前后值准备准入资金增量，不在这里执行完整订单终态清理。 */
    public void prepareOrderCheckLane(int laneId) {
        AccountBook.prepareBalanceFundsDelta(balancePatches[laneId], laneFundsDeltas[laneId]);
    }

    /** 遍历全部已经分配的余额缓冲，把前值合并到目标。 */
    public void copyBalanceBeforeTo(AccountTransactionRollback.BalancePatches target) {
        if (target == null) throw new IllegalArgumentException("balance capture target is required");
        for (int laneId = 0; laneId < balancePatches.length; laneId++) {
            AccountTransactionRollback.BalancePatches source = balancePatches[laneId];
            if (source == EMPTY_BALANCE_PATCHES) continue;
            target.mergeBefore(source);
        }
    }

    /** 按调用方给出的 Lane 位图，把各 Lane 已准备的资金增量加入目标累加器，不清空源。 */
    public void appendFundsDelta(long laneMask, FundsTotal target) {
        if (target == null) throw new IllegalArgumentException("funds accumulator is required");
        long lanes = laneMask;
        while (lanes != 0) {
            int laneId = Long.numberOfTrailingZeros(lanes);
            lanes &= lanes - 1;
            target.add(laneFundsDeltas[laneId]);
        }
    }

    /** 把参与 Lane 的待退休订单交回对应账户的回收缓存。 */
    public void returnRetiredOrders(AccountBook accountBook) {
        long lanes = activeLaneMask;
        while (lanes != 0) {
            int laneId = Long.numberOfTrailingZeros(lanes); lanes &= lanes - 1;
            laneDeltas[laneId].returnRetiredOrders(accountBook.accountLane(laneId));
        }
    }

    /** 清除参与 Lane 的修订、变化、余额与资金缓冲并重置参与位图和持仓身份标记。 */
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

    /** 借用指定 Lane 的实体与终态变化缓冲，不复制内容。 */
    public AccountPartitionCommit.Delta laneDelta(int index) { return laneDeltas[index]; }

    /** 借用指定 Lane 的余额前后值缓冲，不复制内容。 */
    public AccountTransactionRollback.BalancePatches balancePatch(int index) { return balancePatches[index]; }
    /** 返回按拓扑保留的余额缓冲列数，不表示本代活跃 Lane 数。 */
    public int balancePatchCount() { return balancePatches.length; }
}

/** 资金分录的所有者与子账类别。 */
public static final class Posting {
    private Posting() {
    }

    /** 本条资金变化归谁：普通用户、做市账户、系统财库或系统外部。 */
    public enum OwnerKind {
        USER,
        MAKER,
        /** 系统财库的资金归属，所有者编号为零。 */
        TREASURY,
        EXTERNAL;

    }

    /** 按用途区分资金变化的子账类别。 */
    public enum Subledger {
        AVAILABLE,
        LOCKED,
        RESERVATION,
        POSITION_MARGIN,
        FEE,
        INSURANCE,
        LIQUIDATION_FEE,
        FUNDING_RESIDUAL,
        /** 金额舍入剩余差额的变动。 */
        ROUNDING_RESIDUAL,
        CLEARING_PNL,
        DEFICIT,
        /** 外部调入或调出系统的资金变化，只配外部所有者。 */
        EXTERNAL_ADJUSTMENT;

    }
}
}
