package com.surprising.aeron.service.order;

import com.surprising.aeron.service.settlement.TradeSettlementPlan;
import com.surprising.aeron.exception.CoreStateRejectedException;
import com.surprising.aeron.model.AssetBalance;
import com.surprising.aeron.model.CoreOrderStatus;
import com.surprising.aeron.protocol.AdjustPositionMarginCommand;
import com.surprising.aeron.protocol.AmendOrderBatchCommand;
import com.surprising.aeron.protocol.AmendOrderCommand;
import com.surprising.aeron.protocol.CancelOrderBatchCommand;
import com.surprising.aeron.protocol.CancelOrderCommand;
import com.surprising.aeron.protocol.CommandFingerprint;
import com.surprising.aeron.protocol.CoreAlgoOrderCommand;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreOrderType;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.CoreTriggerOrderStateView;
import com.surprising.aeron.protocol.ExecuteLiquidationBatchCommand;
import com.surprising.aeron.protocol.ExecuteLiquidationCommand;
import com.surprising.aeron.protocol.PlaceOrderBatchCommand;
import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.protocol.ReplaceOrderCommand;
import com.surprising.aeron.protocol.ReservationKind;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.protocol.SettleInstrumentCommand;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.aeron.protocol.TradingOrderBatchCodec;
import com.surprising.aeron.protocol.TransferFundsCommand;
import com.surprising.aeron.protocol.UpdateLeverageCommand;
import com.surprising.aeron.protocol.UpdatePositionModeCommand;
import com.surprising.aeron.service.account.AccountCommandService;
import com.surprising.aeron.service.account.AccountLaneCommand;
import com.surprising.aeron.service.account.AccountPartition;
import com.surprising.aeron.service.adl.AdlService;
import com.surprising.aeron.service.core.AccountBook;
import com.surprising.aeron.service.core.TradingCore;
import com.surprising.aeron.service.core.change.AccountPartitionCommit;
import com.surprising.aeron.service.core.identity.IdIndex;
import com.surprising.aeron.service.fee.FeePolicyBook;
import com.surprising.aeron.service.funding.FundingSettlementService;
import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.aeron.service.liquidation.LiquidationService;
import com.surprising.aeron.service.matcher.OrderBookEngine.CancellationOrder;
import com.surprising.aeron.service.matcher.OrderMatchingResult;
import com.surprising.aeron.service.position.Position;
import com.surprising.aeron.service.result.CommandResultBuilder;
import com.surprising.aeron.service.result.ResponseSlotPool;
import com.surprising.aeron.service.risk.RiskScanner;
import com.surprising.aeron.service.settlement.TradeSettlement;
import com.surprising.aeron.service.settlement.ContractExpirySettlementService;
import com.surprising.aeron.service.treasury.SystemFunds;
import com.surprising.aeron.service.trigger.command.TriggerOrderCommands;
import com.surprising.aeron.support.collection.ImmutableLongArrayList;
import com.surprising.aeron.support.collection.PrimitiveLongChangeSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.IntFunction;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.Accessors;

@NoArgsConstructor(access = AccessLevel.PACKAGE)
public final class OrderCommand implements TradeSettlement.MatcherCompletionRoute {
    long coreSequence;
    boolean claimed;
    @Accessors(fluent = true, chain = false)
    @Getter
    PendingOrderBatch orderBatch;
    @Accessors(fluent = true, chain = false)
    @Getter
    private Operation operation;
    /** 本次完整 Core 命令引用，Owner 初始化后供固定任务读取。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private CoreMessage command;
    /** 本次命令的确定性指纹。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private CommandFingerprint fingerprint;
    @Accessors(fluent = true, chain = false)
    @Getter
    private DecodedOrderCommand decodedCommand;
    /** 跨分片控制撤单已开始的 Owner 标记，防止重复派发。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    @Setter
    boolean crossShardCancellationStarted;
    @Accessors(fluent = true, chain = false)
    @Getter
    boolean clusterIndependent;
    /** 只为实时推送保留的命令独占订单引用或副本，具体租约由发布路径约束。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    @Setter
    Order realtimeTakerOrder;
    private final OrderCheckState orderCheckState = new OrderCheckState();
    private final MatchingTasks matchingTasks = new MatchingTasks(this);
    private final SettlementState settlementState = new SettlementState();
    private final CommitControlState commitControl = new CommitControlState();

    static final class OrderCheckState {
        List<Long> preMatchingCancellationOrderIds = List.of();
        ReplaceOrderCheck orderCheck;
        PlaceOrderCheckEvent placeOrderCheck;
        boolean placeOrderCheckOwnerCollected;
        ResolvedPlaceOrder realtimeResolvedTakerOrder;
        Order triggerOrderCheckOrder;
        com.surprising.aeron.model.CoreTriggerOrderState sourceTrigger;
        ReplaceOrderCheckEvent replacementOrderCheck;
        long deferredClusterTimestamp;
        long deferredClusterPosition;
        TradingCore.SourceKey deferredSourceKey;
    }

    static final class MatchingTasks {
        static final byte MATCHING_ADMITTED = 0;
        static final byte MATCHING_DEFERRED = 1;
        static final byte MATCHING_SUBMITTED = 2;
        static final byte MATCHER_DONE = 3;
        static final byte LANES_DONE = 4;
        static final byte COMMITTED = 5;
        byte matchingLifecycle;
        int cachedMatcherShard = -1;
        volatile int submittedMatcherShard = -1;
        volatile OrderMatchingResult.Control completedMatchingResult;
        OrderMatchingResult matchingResult;
        CoreResultCode matchingRejection;
        List<CancellationOrder> matcherPreCancellations = List.of();
        final AfterCheckMatchCallback orderCheckMatching;
        final ControlMatchCallback controlMatching;
        final OrderMatchCallback orderMatching;

        MatchingTasks(OrderCommand ownerSlot) {
            this.orderCheckMatching = new AfterCheckMatchCallback(ownerSlot);
            this.controlMatching = new ControlMatchCallback(ownerSlot);
            this.orderMatching = new OrderMatchCallback(ownerSlot);
        }
    }

    static final class SettlementState {
        TradeSettlement settlementContinuation;
        TradeSettlementPlan settlementPlan;
        long expectedLaneMask;
        long completedLaneMask;
        boolean ledgerDispatchClosed;
        boolean pipelinedSettlementCounted;
        final LaneResultTarget laneResultTarget = new LaneResultTarget();
    }

    static final class CommitControlState {
        long commitFenceTimestamp;
        long commitFenceClusterPosition;
        boolean commitFenceEstablished;
        java.util.function.BooleanSupplier controlWork;
        boolean controlPending;
        boolean controlAccountsCommitted;
        com.surprising.aeron.service.core.change.AccountPartitionCommit commitEvent;
        byte[] resultData;
        PrimitiveLongChangeSet commitChangedUserIds = new PrimitiveLongChangeSet();
        PrimitiveLongChangeSet commitChangedOrderIds = new PrimitiveLongChangeSet();
        boolean commitContextActive;
        boolean commitSnapshotDirty;
        com.surprising.aeron.service.liquidation.LiquidationService.ExecutionWork reusableLiquidationWork;
        com.surprising.aeron.service.liquidation.LiquidationService.ExecutionCheck liquidationCheck;
        ContractExpirySettlementService.SettlementWork reusableSettlementWork;
        final SettlementCallback settlementControl = new SettlementCallback();
        final LiquidationCallback liquidationExecutionControl = new LiquidationCallback();
    }

    public List<Long> preMatchingCancellationOrderIds() { return orderCheckState.preMatchingCancellationOrderIds; }
    public boolean ledgerDispatchClosed() { return settlementState.ledgerDispatchClosed; }
    public void ledgerDispatchClosed(boolean ledgerDispatchClosed) { settlementState.ledgerDispatchClosed = ledgerDispatchClosed; }
    int cachedMatcherShard() { return matchingTasks.cachedMatcherShard; }
    public java.util.function.BooleanSupplier controlWork() { return commitControl.controlWork; }
    public void controlWork(java.util.function.BooleanSupplier controlWork) { commitControl.controlWork = controlWork; }
    public com.surprising.aeron.service.core.change.AccountPartitionCommit commitEvent() { return commitControl.commitEvent; }
    public void commitEvent(com.surprising.aeron.service.core.change.AccountPartitionCommit commitEvent) { commitControl.commitEvent = commitEvent; }
    public boolean controlPending() { return commitControl.controlPending; }
    public void controlPending(boolean controlPending) { commitControl.controlPending = controlPending; }
    public boolean controlAccountsCommitted() { return commitControl.controlAccountsCommitted; }
    public void controlAccountsCommitted(boolean controlAccountsCommitted) { commitControl.controlAccountsCommitted = controlAccountsCommitted; }
    public byte[] resultData() { return commitControl.resultData; }
    public void resultData(byte[] resultData) { commitControl.resultData = resultData; }
    public long expectedLaneMask() { return settlementState.expectedLaneMask; }
    public long completedLaneMask() { return settlementState.completedLaneMask; }
    public OrderMatchingResult matchingResult() { return matchingTasks.matchingResult; }
    public int submittedMatcherShard() { return matchingTasks.submittedMatcherShard; }

    /** 非批量命令按操作选择最多两个需要收集结果的订单号，再绑定 Lane 响应目标。 */
    void prepareLaneResultTarget(ResponseSlotPool responseSlotPool) {
        if (orderBatch != null) return;
        long first = 0, second = 0;
        switch (operation) {
            case PLACE -> first = decodedCommand.placeOrder().orderId();
            case CANCEL -> first = decodedCommand.cancelOrder().orderId();
            case REPLACE, AMEND -> {
                ReplaceOrderCheck value = orderCheckState.orderCheck;
                if (value != null) {
                    first = value.originalOrderId();
                    second = value.command().orderId();
                }
            }
            case TRIGGER -> {
                if (orderCheckState.triggerOrderCheckOrder != null) first = orderCheckState.triggerOrderCheckOrder.orderId();
            }
            default -> { }
        }
        settlementState.laneResultTarget.bind(first, second, responseSlotPool);
    }

    /** 目标已绑定正订单号时返回 Lane 写入接口，否则为空。 */
    public com.surprising.aeron.service.order.OrderResultTarget laneResultTarget() {
        return settlementState.laneResultTarget.active() ? settlementState.laneResultTarget : null;
    }

    /** 从本槽已捕获的结果副本中按订单号查找，不回读 Lane 订单表。 */
    public Order laneResultOrder(long orderId) { return settlementState.laneResultTarget.order(orderId); }
    /** 从已捕获结果中取品种字符串，未绑定或未捕获时可为空。 */
    public String laneResultSymbol(long orderId) { return settlementState.laneResultTarget.instrumentId(orderId); }
    /** 返回 Lane 是否已经完成响应字节准备。 */
    public boolean laneResultPrepared() { return settlementState.laneResultTarget.prepared(); }
    /** 返回尚归当前目标持有的响应缓冲引用，不复制数组。 */
    public byte[] lanePreparedResponse() { return settlementState.laneResultTarget.preparedResponse(); }
    /** 返回当前响应有效长度，可能小于租用数组容量。 */
    public int lanePreparedResponseLength() { return settlementState.laneResultTarget.preparedResponseLength(); }
    /** 把响应租约从本槽目标移交出去，只清本目标租约引用，不归还 槽池 或复制字节。 */
    public void transferLaneResponseOwnership() { settlementState.laneResultTarget.transferResponseOwnership(); }

    /** 准备槽内固定的准入等待任务，Matcher 先等 Lane 收据再决定拒绝、先撤或调用原下单任务，避免每次分配捕获 lambda。 */
    java.util.function.Supplier<?> gateOrderCheck(TradingCore owner,
            PlaceOrderCheckEvent orderCheck, int matcherShard,
            com.surprising.aeron.service.settlement.TradeSettlement settlement,
            java.util.function.Supplier<?> original) {
        matchingTasks.orderCheckMatching.prepare(owner, orderCheck, matcherShard, settlement, original);
        return matchingTasks.orderCheckMatching;
    }

    /** 准备本槽普通下单任务，只接受一种已准入订单表示并保留结算事件租约。 */
    java.util.function.Supplier<?> preparePlaceMatching(TradingCore owner,
            int matcherShard, long userId,
            ResolvedPlaceOrder resolvedOrder,
            TradeSettlement direct) {
        matchingTasks.orderMatching.preparePlace(owner, matcherShard, userId, resolvedOrder, null, direct);
        return matchingTasks.orderMatching;
    }

    /** 准备顺序批量中已冻结的 Order 输入，Matcher 直接消费该项租约，不重建独立撮合 DTO。 */
    java.util.function.Supplier<?> prepareAdmittedPlaceMatching(TradingCore owner,
            int matcherShard, long userId, Order admittedOrder, TradeSettlement direct) {
        matchingTasks.orderMatching.preparePlace(owner, matcherShard, userId, null, admittedOrder, direct);
        return matchingTasks.orderMatching;
    }

    /** 保存撤单身份及直达结算目标到本槽固定任务，不在 Owner 准备阶段撤订单簿。 */
    java.util.function.Supplier<?> prepareCancelMatching(TradingCore owner,
            int matcherShard, long orderId, long userId, String instrumentId, TradeSettlement direct) {
        matchingTasks.orderMatching.prepareCancel(owner, matcherShard, orderId, userId, instrumentId, direct);
        return matchingTasks.orderMatching;
    }

    /** 保存旧单号、替换准入和直达结算目标，执行时先处理共同的前置撤单，再由适配器执行撤旧下新。 */
    java.util.function.Supplier<?> prepareReplaceMatching(TradingCore owner,
            int matcherShard, long orderId, long userId,
            long originalOrderId, String instrumentId, ResolvedPlaceOrder replacement,
            TradeSettlement direct) {
        matchingTasks.orderMatching.prepareReplace(owner, matcherShard, orderId, userId,
                originalOrderId, instrumentId, replacement, direct);
        return matchingTasks.orderMatching;
    }

    /** 保留非空前置撤单列表引用，不复制或在这里执行。 */
    void matcherPreCancellations(List<CancellationOrder> orders) {
        matchingTasks.matcherPreCancellations = Objects.requireNonNull(orders);
    }

    /** 准备按既定顺序执行控制撤单的固定任务，要求 Owner 和列表非空，返回本槽同一个 Supplier。 */
    java.util.function.Supplier<?> prepareControlMatching(TradingCore owner,
            List<CancellationOrder> orders) {
        matchingTasks.controlMatching.prepare(owner, orders);
        return matchingTasks.controlMatching;
    }

    /** 按当前列表顺序执行原生前置撤单，接受时记入直达事件。 */
    exchange.core2.core.common.MatcherResult applyMatcherPreCancellations(
            TradingCore owner, int shard, long logicalOrderId, TradeSettlement target) {
        for (int index = 0; index < matchingTasks.matcherPreCancellations.size(); index++) {
            var cancellation = matchingTasks.matcherPreCancellations.get(index);
            var result = owner.matchingAdapter().cancelDirectPrefix(command.header().submittedAtEpochMillis(),
                    cancellation.userId(), cancellation.orderId(), cancellation.symbolId());
            boolean accepted = result.resultCode() == exchange.core2.core.common.cmd.CommandResultCode.SUCCESS
                    || result.resultCode() == exchange.core2.core.common.cmd.CommandResultCode.ACCEPTED;
            if (accepted) target.recordDirectPreCancellation(cancellation.orderId());
            else return owner.matchingAdapter().publishDirectPrefixFailure(shard, coreSequence,
                    command.header().commandId(), logicalOrderId, result, target);
        }
        return null;
    }

    /** 当前还没有控制续步时绑定非空 BooleanSupplier，之后由 Owner 轮询其完成状态。 */
    public void deferControl(java.util.function.BooleanSupplier work) {
        if (commitControl.controlWork != null) throw new IllegalStateException("command already has pending work");
        commitControl.controlWork = Objects.requireNonNull(work);
    }

    /** 返回本槽保留的可复用到期结算工作对象，尚未创建时可为空。 */
    public com.surprising.aeron.service.settlement.ContractExpirySettlementService.SettlementWork settlementWork() {
        return commitControl.reusableSettlementWork;
    }

    /** 保存非空可复用结算工作对象，回收时清内部引用而保留对象。 */
    public void settlementWork(com.surprising.aeron.service.settlement.ContractExpirySettlementService.SettlementWork work) {
        commitControl.reusableSettlementWork = Objects.requireNonNull(work);
    }

    /** 要求尚无待控制工作，准备固定到期结算轮询器并把它设为当前 controlWork。 */
    public void deferSettlementControl(OrderCommitService coordinator,
            com.surprising.aeron.service.settlement.ContractExpirySettlementService.SettlementWork work) {
        if (commitControl.controlWork != null) throw new IllegalStateException("command already has pending work");
        commitControl.settlementControl.prepare(coordinator, work);
        commitControl.controlWork = commitControl.settlementControl;
    }

    /** 懒创建并返回本槽可复用的强平校验工作。 */
    public com.surprising.aeron.service.liquidation.LiquidationService.ExecutionCheck liquidationCheck() {
        if (commitControl.liquidationCheck == null)
            commitControl.liquidationCheck = new com.surprising.aeron.service.liquidation.LiquidationService.ExecutionCheck();
        return commitControl.liquidationCheck;
    }

    /** 返回已保留的强平执行工作，未设置时为空。 */
    public com.surprising.aeron.service.liquidation.LiquidationService.ExecutionWork liquidationWork() {
        return commitControl.reusableLiquidationWork;
    }

    /** 保存非空强平执行工作，后续命令复用同一工作对象。 */
    public void liquidationWork(com.surprising.aeron.service.liquidation.LiquidationService.ExecutionWork work) {
        commitControl.reusableLiquidationWork = Objects.requireNonNull(work);
    }

    /** 当前无待控制工作时，绑定强平执行工作及最终进度，由固定 Owner 回调轮询，不在此重新执行资金业务。 */
    public void deferLiquidationExecutionControl(OrderCommitService coordinator,
            com.surprising.aeron.service.liquidation.LiquidationService.ExecutionWork work,
            com.surprising.aeron.protocol.CoreLiquidationProgressView progress) {
        if (commitControl.controlWork != null) throw new IllegalStateException("command already has pending work");
        commitControl.liquidationExecutionControl.prepare(coordinator, work, progress);
        commitControl.controlWork = commitControl.liquidationExecutionControl;
    }

    /** 要求正序号、命令类消息和必需输入非空，清旧生命周期后绑定命令、指纹、解码及先撤列表，重置本次提交边界和业务阶段。 */
    OrderCommand initialize(long sequence, Operation operation, CoreMessage command,
                               CommandFingerprint fingerprint, List<Long> preMatchingCancellationOrderIds,
                               DecodedOrderCommand decodedCommand, ReplaceOrderCheck orderCheck) {
        if (sequence <= 0 || operation == null || command == null || preMatchingCancellationOrderIds == null
                || fingerprint == null || decodedCommand == null
                || command.header().kind() != com.surprising.aeron.protocol.WireMessageKind.COMMAND) {
            throw new IllegalArgumentException("invalid pending matching request");
        }
        if (!command.header().hasCommandId()) throw new NullPointerException("commandId");
        clear();
        this.coreSequence = sequence;
        claimed = true;
        orderBatch = null;
        this.operation = operation;
        this.command = command;
        this.fingerprint = fingerprint;
        orderCheckState.preMatchingCancellationOrderIds = retainCancellationIds(preMatchingCancellationOrderIds);
        this.decodedCommand = decodedCommand;
        orderCheckState.orderCheck = orderCheck;
        commitControl.commitFenceTimestamp = 0;
        commitControl.commitFenceClusterPosition = 0;
        commitControl.commitFenceEstablished = false;
        settlementState.settlementContinuation = null;
        clearPlaceReferences();
        orderCheckState.replacementOrderCheck = null;
        settlementState.settlementPlan = null;
        orderCheckState.triggerOrderCheckOrder = null;
        orderCheckState.sourceTrigger = null;
        matchingTasks.matchingLifecycle = MatchingTasks.MATCHING_ADMITTED;
        clearDeferredMatching();
        crossShardCancellationStarted = false;
        clusterIndependent = false;
        settlementState.ledgerDispatchClosed = false;
        realtimeTakerOrder = null;
        settlementState.pipelinedSettlementCounted = false;
        matchingTasks.cachedMatcherShard = -1;
        return this;
    }

    /** 更新先撤订单号列表并返回同一槽，复用不可变 primitive 列表，其余按规则复制。 */
    OrderCommand withPreMatchingCancellations(List<Long> orderIds) {
        orderCheckState.preMatchingCancellationOrderIds = retainCancellationIds(orderIds);
        return this;
    }

    /** 不复制 primitive 不可变存储。 */
    private static List<Long> retainCancellationIds(List<Long> orderIds) {
        if (orderIds == null || orderIds.isEmpty()) return List.of();
        if (orderIds instanceof ImmutableLongArrayList) {
            return orderIds;
        }
        return List.copyOf(orderIds);
    }

    /** 替换可空的解析准入结果并返回同一槽，不重做准入或读取账户。 */
    OrderCommand withOrderCheck(ReplaceOrderCheck nextOrderCheck) {
        orderCheckState.orderCheck = nextOrderCheck;
        return this;
    }

    /** 要求时间与日志位置非负，只在首次保存提交边界。 */
    public void establishCommitFence(long clusterTimestamp, long clusterPosition) {
        if (clusterTimestamp < 0 || clusterPosition < 0) {
            throw new IllegalArgumentException("matching commit fence cannot be negative");
        }
        if (commitControl.commitFenceEstablished) return;
        commitControl.commitFenceTimestamp = clusterTimestamp;
        commitControl.commitFenceClusterPosition = clusterPosition;
        commitControl.commitFenceEstablished = true;
    }

    /** 返回保留的 Core 序号，槽已回收后也可能仍保留旧值。 */
    public long sequence() { return coreSequence; }
    /** 返回可空的改撤等已解析准入信息。 */
    public ReplaceOrderCheck matchingOrderCheck() { return orderCheckState.orderCheck; }
    /** 返回替换操作绑定的 Lane 准入事件。 */
    com.surprising.aeron.service.order.ReplaceOrderCheckEvent replacementOrderCheck() { return orderCheckState.replacementOrderCheck; }
    /** 只允许 AMEND/REPLACE 首次绑定非空替换准入事件，重复或操作不符拒绝。 */
    void replacementOrderCheck(com.surprising.aeron.service.order.ReplaceOrderCheckEvent event) {
        if (event == null || orderCheckState.replacementOrderCheck != null || operation != Operation.AMEND && operation != Operation.REPLACE)
            throw new IllegalStateException("invalid replacement admissionState.admission slot");
        orderCheckState.replacementOrderCheck = event;
    }
    /** 取走替换准入事件并清该引用，把后续使用责任交给调用方。 */
    com.surprising.aeron.service.order.ReplaceOrderCheckEvent takeReplaceOrderCheck() {
        var value = orderCheckState.replacementOrderCheck; orderCheckState.replacementOrderCheck = null; return value;
    }
    /** 返回此命令保留的可空结算事件。 */
    public com.surprising.aeron.service.settlement.TradeSettlement settlementEvent() {
        return settlementState.settlementContinuation;
    }
    /** 取出并断开结算事件引用，不在此完成结算或回收事件。 */
    public com.surprising.aeron.service.settlement.TradeSettlement takeSettlementEvent() {
        var value = settlementEvent();
        settlementState.settlementContinuation = null;
        return value;
    }
    /** 返回与当前事件配套的结算计划引用。 */
    public com.surprising.aeron.service.settlement.TradeSettlementPlan settlementPlan() { return settlementState.settlementPlan; }
    /** 返回普通下单的 Lane 准入事件。 */
    public PlaceOrderCheckEvent placeOrderCheck() {
        return orderCheckState.placeOrderCheck;
    }
    /** 返回是否仍保留结算事件引用。 */
    public boolean hasLaneContinuation() {
        return settlementState.settlementContinuation != null;
    }
    /** 有结算事件且事件自身已完成时返回真，没有事件时返回假。 */
    public boolean laneContinuationComplete() {
        if (!hasLaneContinuation()) return false;
        return settlementState.settlementContinuation.complete();
    }
    /** 取出普通下单准入事件并清引用。 */
    PlaceOrderCheckEvent takePlaceOrderCheck() {
        PlaceOrderCheckEvent value = orderCheckState.placeOrderCheck;
        orderCheckState.placeOrderCheck = null;
        return value;
    }
    /** 只允许 PLACE 首次绑定非空准入事件，其他操作或重复绑定拒绝。 */
    public void placeOrderCheck(PlaceOrderCheckEvent event) {
        if (event == null || orderCheckState.placeOrderCheck != null || operation != Operation.PLACE) {
            throw new IllegalStateException("invalid place admissionState.admission continuation");
        }
        orderCheckState.placeOrderCheck = event;
    }
    /** 返回 Owner 是否已收集本次下单准入收据。 */
    boolean placeOrderCheckOwnerCollected() { return orderCheckState.placeOrderCheckOwnerCollected; }
    /** 标记下单准入已由 Owner 收集，不在此重新读 Lane。 */
    void markPlaceOrderCheckOwnerCollected() { orderCheckState.placeOrderCheckOwnerCollected = true; }
    /** 返回为实时推送保留的准入订单引用。 */
    public ResolvedPlaceOrder realtimeResolvedTakerOrder() { return orderCheckState.realtimeResolvedTakerOrder; }
    /** 保存可空实时准入订单引用，不复制对象。 */
    public void realtimeResolvedTakerOrder(ResolvedPlaceOrder order) { orderCheckState.realtimeResolvedTakerOrder = order; }
    /** 返回触发续步已经冻结的源触发单收据，避免再回读 Lane 客户端索引。 */
    public com.surprising.aeron.model.CoreTriggerOrderState sourceTrigger() { return orderCheckState.sourceTrigger; }
    /** 首次绑定非空源触发单收据，重复绑定立即拒绝。 */
    public void sourceTrigger(com.surprising.aeron.model.CoreTriggerOrderState value) {
        if (value == null || orderCheckState.sourceTrigger != null) throw new IllegalStateException("trigger receipt already bound");
        orderCheckState.sourceTrigger = value;
    }
    /** 返回触发子单已经完成准入的订单引用。 */
    public Order triggerOrderCheckOrder() { return orderCheckState.triggerOrderCheckOrder; }
    /** 只允许 TRIGGER 且尚无准入收据时保存冻结订单，Matcher 直接消费同一租约。 */
    void triggerOrderCheck(Order order) {
        if (operation != Operation.TRIGGER || order == null || orderCheckState.triggerOrderCheckOrder != null)
            throw new IllegalStateException("invalid trigger admissionState.admission");
        orderCheckState.triggerOrderCheckOrder = order;

    }
    /** 按阶段标签判断是否已到提交 Matcher 或更后阶段，不探查实际传输队列。 */
    public boolean isMatchingSubmitted() { return matchingTasks.matchingLifecycle >= MatchingTasks.MATCHING_SUBMITTED; }

    /** 把紧凑阶段值转成可读诊断名，未知值返回 UNKNOWN。 */
    public String matchingLifecycleName() {
        return switch (matchingTasks.matchingLifecycle) {
            case MatchingTasks.MATCHING_ADMITTED -> "ADMITTED";
            case MatchingTasks.MATCHING_DEFERRED -> "DEFERRED";
            case MatchingTasks.MATCHING_SUBMITTED -> "SUBMITTED";
            case MatchingTasks.MATCHER_DONE -> "MatchingTasks.MATCHER_DONE";
            case MatchingTasks.LANES_DONE -> "MatchingTasks.LANES_DONE";
            case MatchingTasks.COMMITTED -> "MatchingTasks.COMMITTED";
            default -> "UNKNOWN";
        };
    }
    /** 登记首次 Matcher 提交。若结果阶段已先发布则保持更后阶段，否则重复提交拒绝。 */
    public void matchingSubmitted() {
        // 跨分片控制结果可能先于提交游标释放而可见。
        if (matchingTasks.matchingLifecycle >= MatchingTasks.MATCHER_DONE) return;
        if (isMatchingSubmitted()) throw new IllegalStateException("matching command was submitted twice");
        matchingTasks.matchingLifecycle = MatchingTasks.MATCHING_SUBMITTED;
    }

    /** 要求事件和计划非空且尚无事件续步，再绑定二者，账户应用由后续流程执行。 */
    public void settlement(com.surprising.aeron.service.settlement.TradeSettlement event,
                    com.surprising.aeron.service.settlement.TradeSettlementPlan plan) {
        if (event == null || plan == null || hasLaneContinuation()) {
            throw new IllegalStateException("invalid matcher settlement continuation");
        }
        settlementState.settlementContinuation = event;
        settlementState.settlementPlan = plan;
    }

    /** 清普通业务引用、子上下文、资金缓冲与 Core 序号，并清可复用准入订单内容。 */
    void clearReferences() {
        coreSequence = 0;
        orderBatch = null;
        operation = null;
        command = null;
        fingerprint = null;
        orderCheckState.preMatchingCancellationOrderIds = List.of();
        decodedCommand = null;
        orderCheckState.orderCheck = null;
        settlementState.settlementContinuation = null;
        clearPlaceReferences();
        orderCheckState.replacementOrderCheck = null;
        settlementState.settlementPlan = null;
        orderCheckState.triggerOrderCheckOrder = null;
        orderCheckState.sourceTrigger = null;
        clearLiquidationReferences();
        realtimeTakerOrder = null;
        matchingTasks.matchingLifecycle = MatchingTasks.MATCHING_ADMITTED;
        clearDeferredMatching();
        matchingTasks.cachedMatcherShard = -1;
    }

    /** 只允许从 ADMITTED 转为 DEFERRED，保存日志时间、位置和非空来源键，等待已有批量边界先完成。 */
    void deferMatching(long clusterTimestamp, long clusterPosition, TradingCore.SourceKey sourceKey) {
        if (matchingTasks.matchingLifecycle != MatchingTasks.MATCHING_ADMITTED || sourceKey == null) {
            throw new IllegalStateException("invalid deferred matching transition");
        }
        orderCheckState.deferredClusterTimestamp = clusterTimestamp;
        orderCheckState.deferredClusterPosition = clusterPosition;
        orderCheckState.deferredSourceKey = sourceKey;
        matchingTasks.matchingLifecycle = MatchingTasks.MATCHING_DEFERRED;
    }
    /** 只允许从 DEFERRED 回到 ADMITTED，同时清延后元数据，再由外层重新提交。 */
    void activateDeferredMatching() {
        if (matchingTasks.matchingLifecycle != MatchingTasks.MATCHING_DEFERRED) {
            throw new IllegalStateException("matching command is not deferred");
        }
        matchingTasks.matchingLifecycle = MatchingTasks.MATCHING_ADMITTED;
        clearDeferredMatching();
    }
    /** 返回当前阶段是否恰好为延后撮合。 */
    boolean deferredMatching() { return matchingTasks.matchingLifecycle == MatchingTasks.MATCHING_DEFERRED; }
    /** 直接标记 COMMITTED，调用方先完成业务提交条件，本方法不重新核验。 */
    void committed() {
        matchingTasks.matchingLifecycle = MatchingTasks.COMMITTED;
    }
    /** 要求仍处于延后阶段后返回原 Cluster 时间。 */
    long deferredClusterTimestamp() {
        if (!deferredMatching()) throw new IllegalStateException("matching command is not deferred");
        return orderCheckState.deferredClusterTimestamp;
    }
    /** 要求仍处于延后阶段后返回原日志位置。 */
    long deferredClusterPosition() {
        if (!deferredMatching()) throw new IllegalStateException("matching command is not deferred");
        return orderCheckState.deferredClusterPosition;
    }
    /** 要求延后阶段后返回原来源键。 */
    TradingCore.SourceKey deferredSourceKey() {
        if (!deferredMatching()) throw new IllegalStateException("matching command is not deferred");
        return orderCheckState.deferredSourceKey;
    }

    /** 要求分片非负后保存，不在此检查拓扑上界。 */
    void cachedMatcherShard(int shard) {
        if (shard < 0) throw new IllegalArgumentException("matcher shard cannot be negative");
        matchingTasks.cachedMatcherShard = shard;
    }

    /** 标记本命令计入流水结算统计，重复调用只是再次置真。 */
    void countPipelinedSettlement() { settlementState.pipelinedSettlementCounted = true; }
    /** 读出并清掉已统计标记，供外层仅对已计数命令做一次扣减。 */
    public boolean takePipelinedSettlementCounted() {
        boolean value = settlementState.pipelinedSettlementCounted;
        settlementState.pipelinedSettlementCounted = false;
        return value;
    }
    /** 要求边界已建立后返回固定业务时间。 */
    public long commitFenceTimestamp() {
        if (!commitControl.commitFenceEstablished) throw new IllegalStateException("matching commit fence is not established");
        return commitControl.commitFenceTimestamp;
    }
    /** 要求边界已建立后返回固定日志位置。 */
    public long commitFenceClusterPosition() {
        if (!commitControl.commitFenceEstablished) throw new IllegalStateException("matching commit fence is not established");
        return commitControl.commitFenceClusterPosition;
    }

    /** 命令槽支持的业务动作类别，不混用为产品线或 Matcher 结果类型。 */
    public enum Operation {
        /** 普通下单。 */
        PLACE,
        /** 普通撤单。 */
        CANCEL,
        /** 撤旧下新的订单替换。 */
        REPLACE,
        /** 改单请求，经准入后按替换路径撮合。 */
        AMEND,
        /** 触发条件达到后的子单。 */
        TRIGGER,
        /** 单笔强平执行控制。 */
        LIQUIDATION,
        /** 强平批次执行控制。 */
        LIQUIDATION_BATCH,
        /** 交割或行权等到期结算控制。 */
        SETTLEMENT
    }

    /** 要求槽仍被占用、分片非负、没有现有路由或未收集结果，再由 Owner 绑定提交分片。 */
    public void claimMatcherSubmission(int shardId) {
        if (!claimed || shardId < 0 || matchingTasks.submittedMatcherShard != -1 || matchingTasks.completedMatchingResult != null)
            throw new IllegalStateException("matcher command token is already routed or inactive");
        matchingTasks.submittedMatcherShard = shardId;
    }

    /** 要求释放分片与当前路由相同，再置为负一。普通发布由 Matcher 在写可见结果之前调用。 */
    @Override
    public void releaseMatcherSubmission(int shardId) {
        if (matchingTasks.submittedMatcherShard != shardId) {
            throw new IllegalStateException("matcher completion belongs to another shard");
        }
        matchingTasks.submittedMatcherShard = -1;
    }

    /** 只有 claimed 时返回 Core 序号，否则返回零，避免内部链接把空闲槽当作有效命令。 */
    long coreSequence() { return claimed ? coreSequence : 0; }
    /** 要求非空结果匹配本槽 Core 序号且没有未收集结果，先释放 Matcher 路由再用 volatile 结果字段发布。后续 Owner 可直接读取，不能当作多写者 CAS 容器。 */
    public void publishMatcherResult(int shardId, OrderMatchingResult.Control result) {
        if (result == null || result.nativeCoreSequence() != coreSequence
                || matchingTasks.completedMatchingResult != null) {
            throw new IllegalStateException("invalid matcher result publication");
        }
        releaseMatcherSubmission(shardId);
        matchingTasks.completedMatchingResult = result;
    }

    /** 校验结果与 Core 序号，只在当前完成字段为空时保存。 */
    void publishMatchingCompletion(OrderMatchingResult.Control result) {
        if (result == null || result.nativeCoreSequence() != coreSequence) {
            throw new IllegalStateException("invalid matching completion");
        }
        if (matchingTasks.completedMatchingResult == null) matchingTasks.completedMatchingResult = result;
    }

    /** 取出可见普通结果，只有确实读到非空时才清字段，避免空探查擦掉随后到来的发布。 */
    public OrderMatchingResult.Control takeMatchingCompletion() {
        OrderMatchingResult.Control result = matchingTasks.completedMatchingResult;
        // 空探查不写回 null，避免擦掉随后到来的 Matcher 发布。
        if (result != null) matchingTasks.completedMatchingResult = null;
        return result;
    }

    /** 只探查当前普通完成引用，不消费。 */
    public OrderMatchingResult.Control matchingCompletion() { return matchingTasks.completedMatchingResult; }
    /** 返回是否有普通 Matcher 完成引用。 */
    public boolean hasMatchingCompletion() { return matchingTasks.completedMatchingResult != null; }
    /** 当前没有挂起上下文时，把 Owner 结果构建器的 primitive 用户/订单变更缓冲与本槽空缓冲交换，并转交资金累加器及快照标记，供续步让出执行后恢复。 */
    public void suspendCommitContext(CommandResultBuilder resultBuilder,
                              boolean snapshotDirty) {
        if (resultBuilder == null || commitControl.commitContextActive) {
            throw new IllegalStateException("invalid suspended sequence commit context");
        }
        PrimitiveLongChangeSet reusableUserIds = commitControl.commitChangedUserIds;
        PrimitiveLongChangeSet reusableOrderIds = commitControl.commitChangedOrderIds;
        commitControl.commitChangedUserIds = resultBuilder.changedUserIds();
        commitControl.commitChangedOrderIds = resultBuilder.changedOrderIds();
        resultBuilder.changedUserIds(reusableUserIds);
        resultBuilder.changedOrderIds(reusableOrderIds);
        commitControl.commitSnapshotDirty = snapshotDirty;
        commitControl.commitContextActive = true;
    }

    /** 要求已挂起上下文，把保存的 primitive 变更缓冲交回结果构建器，并收回构建器现有缓冲。 */
    public void restoreCommitContext(CommandResultBuilder resultBuilder) {
        requireCommit();
        if (resultBuilder == null) throw new IllegalStateException("result builder is required");
        PrimitiveLongChangeSet committedUserIds = commitControl.commitChangedUserIds;
        PrimitiveLongChangeSet committedOrderIds = commitControl.commitChangedOrderIds;
        commitControl.commitChangedUserIds = resultBuilder.changedUserIds();
        commitControl.commitChangedOrderIds = resultBuilder.changedOrderIds();
        resultBuilder.changedUserIds(committedUserIds);
        resultBuilder.changedOrderIds(committedOrderIds);
    }

    /** 要求提交上下文活跃后返回保存的用户变更缓冲。 */
    PrimitiveLongChangeSet commitChangedUserIds() { return requiredCommit(commitControl.commitChangedUserIds); }
    /** 要求提交上下文活跃后返回保存的订单变更缓冲。 */
    PrimitiveLongChangeSet commitChangedOrderIds() { return requiredCommit(commitControl.commitChangedOrderIds); }
    /** 要求上下文活跃后返回是否需要发布快照变化。 */
    public boolean commitSnapshotDirty() { requireCommit(); return commitControl.commitSnapshotDirty; }
    /** 要求上下文活跃，清掉变更集合、资金和快照标记，再标记不再挂起。 */
    public void clearCommitContext() {
        requireCommit();
        commitControl.commitChangedUserIds.clear();
        commitControl.commitChangedOrderIds.clear();
        commitControl.commitSnapshotDirty = false;
        commitControl.commitContextActive = false;
    }

    private void requireCommit() {
        if (!commitControl.commitContextActive) throw new IllegalStateException("sequence commit context is missing");
    }

    private <T> T requiredCommit(T value) {
        requireCommit();
        return value;
    }

    /** 返回是否正在保留挂起提交输入。 */
    public boolean hasCommitContext() { return commitControl.commitContextActive; }

    /** 只允许首次保存非空撮合拒绝原因，不覆盖既有原因。 */
    void rejectMatching(CoreResultCode resultCode) {
        if (resultCode == null || matchingTasks.matchingRejection != null) {
            throw new IllegalStateException("invalid matching rejection");
        }
        matchingTasks.matchingRejection = resultCode;
    }

    /** 返回是否已保存拒绝原因。 */
    public boolean hasMatchingRejection() { return matchingTasks.matchingRejection != null; }

    /** 要求拒绝原因已存在后返回原结果码。 */
    public CoreResultCode matchingRejection() {
        if (matchingTasks.matchingRejection == null) throw new IllegalStateException("matching rejection is missing");
        return matchingTasks.matchingRejection;
    }

    /** 首次把匹配 Core 序号的事实与合法参与 Lane 位集合绑定，阶段置 MATCHER_DONE。 */
    public void result(OrderMatchingResult result, long expectedMask, long validLaneMask) {
        if (result == null || result.nativeCoreSequence() != coreSequence
                || (expectedMask & ~validLaneMask) != 0
                || matchingTasks.matchingResult != null) {
            throw new IllegalStateException("invalid immutable matching result fanout");
        }
        matchingTasks.matchingResult = result;
        settlementState.expectedLaneMask = expectedMask;
        matchingTasks.matchingLifecycle = MatchingTasks.MATCHER_DONE;
    }

    /** 只清待收集的普通完成字段，不清 Lane 扇出结果或整个命令状态。 */
    void resetMatchingContinuation() { matchingTasks.completedMatchingResult = null; }

    /** 要求已经绑定撮合事实且尚未收集任一 Lane，再把合法控制参与位并入预期集合。 */
    public void includeControlLanes(long laneMask, long validLaneMask) {
        if (matchingTasks.matchingResult == null || settlementState.completedLaneMask != 0 || (laneMask & ~validLaneMask) != 0) {
            throw new IllegalStateException("invalid synchronous control lane participants");
        }
        settlementState.expectedLaneMask |= laneMask;
    }

    /** 要求存在事实、完成位是预期子集且无重复位，再合并完成位。零位集合允许传入，真正幂等边界由调用流程遵守。 */
    public void completeLanes(long laneMask) {
        if (matchingTasks.matchingResult == null
                || (laneMask & ~settlementState.expectedLaneMask) != 0
                || (settlementState.completedLaneMask & laneMask) != 0) {
            throw new IllegalStateException("duplicate or unexpected account lane completion"
                    + " laneMask=" + laneMask + " expected=" + settlementState.expectedLaneMask
                    + " completed=" + settlementState.completedLaneMask);
        }
        settlementState.completedLaneMask |= laneMask;
        if (matchingTasks.matchingResult != null && settlementState.completedLaneMask == settlementState.expectedLaneMask) {
            matchingTasks.matchingLifecycle = MatchingTasks.LANES_DONE;
        }
    }

    /** 仅当已绑定事实且完成位等于预期位时为真，不单靠阶段标签。 */
    public boolean complete() {
        return matchingTasks.matchingResult != null && settlementState.completedLaneMask == settlementState.expectedLaneMask;
    }

    /** 解除当前任务借用引用、结果和路由，清提交上下文、Lane 结果目标并把 claimed 置假。 */
    void clear() {
        clearMatchingControlContinuations();
        if (commitControl.reusableSettlementWork != null) commitControl.reusableSettlementWork.clearReferences();
        clearLiquidationReferences();
        commitControl.controlWork = null;
        commitControl.controlPending = false;
        commitControl.controlAccountsCommitted = false;
        commitControl.commitEvent = null;
        commitControl.resultData = null;
        claimed = false;
        settlementState.expectedLaneMask = 0;
        settlementState.completedLaneMask = 0;
        matchingTasks.completedMatchingResult = null;
        matchingTasks.matchingResult = null;
        matchingTasks.submittedMatcherShard = -1;
        commitControl.commitChangedUserIds.clear();
        commitControl.commitChangedOrderIds.clear();
        commitControl.commitSnapshotDirty = false;
        commitControl.commitContextActive = false;
        matchingTasks.matchingRejection = null;
        clearPlaceReferences();
        orderCheckState.replacementOrderCheck = null;
        matchingTasks.matchingLifecycle = MatchingTasks.MATCHING_ADMITTED;
        clearDeferredMatching();
        matchingTasks.cachedMatcherShard = -1;
        settlementState.laneResultTarget.clear();
    }

    private void clearMatchingControlContinuations() {
        matchingTasks.orderCheckMatching.clear();
        commitControl.settlementControl.clear();
        commitControl.liquidationExecutionControl.clear();
        matchingTasks.orderMatching.clear();
        matchingTasks.controlMatching.clear();
        matchingTasks.matcherPreCancellations = List.of();
    }

    /** 普通单笔/替换命令的 Lane 结果目标，最多保留两个身份、可复用订单副本和一个响应租约。 */
    static final class LaneResultTarget
            implements com.surprising.aeron.service.order.OrderResultTarget {
        private final long[] ids = new long[2];
        private final Order[] orders = new Order[2];
        private final Order[] orderCopies = new Order[2];
        private final String[] symbols = new String[2];
        private int count;
        private int capturedMask;
        @Accessors(fluent = true, chain = false)
        @Getter(value = AccessLevel.PACKAGE)
        private boolean prepared;
        private byte[] response;
        private int responseLength;
        private ResponseSlotPool responseSlotPool;
        /** 仍由目标持有的可空 槽池 租约，移交后清空。 */
        private ResponseSlotPool.Slot responseSlot;

        /** 先清上次结果，再保存 槽池。 */
        void bind(long first, long second, ResponseSlotPool responseSlotPool) {
            clear();
            this.responseSlotPool = responseSlotPool;
            if (first <= 0) return;
            ids[0] = first;
            count = 1;
            if (second > 0 && second != first) {
                ids[1] = second;
                count = 2;
            }
        }

        /** 返回是否绑定了至少一个正订单号。 */
        boolean active() { return count != 0; }
        /** 在最多两个已绑定身份中查结果副本，找不到返回空。 */
        Order order(long id) {
            for (int index = 0; index < count; index++) if (ids[index] == id) return orders[index];
            return null;
        }
        /** 在绑定身份中返回捕获时保存的品种字符串，找不到为空。 */
        String instrumentId(long id) {
            for (int index = 0; index < count; index++) if (ids[index] == id) return symbols[index];
            return null;
        }

        /** 返回绑定身份数，不是当前非空订单副本数。 */
        @Override public int resultCount() { return count; }
        /** 要求 Lane 连未发生变化的目标订单也捕获结果，供响应完整描述。 */
        @Override public boolean captureUnchangedResults() { return true; }
        /** 按位判断指定结果项是否已被 Lane 捕获。 */
        @Override public boolean resultPrepared(int index) { return (capturedMask & (1 << index)) != 0; }
        /** 返回指定绑定项的订单号，不做额外下标检查。 */
        @Override public long resultOrderId(int index) { return ids[index]; }
        /** 返回零。 */
        @Override public long resultOriginalOrderId(int index) { return 0; }
        /** 把非空订单复制进本目标持有的可复用副本槽，空订单保持空。保存品种并标已捕获，Owner 后续无需回读 Lane 表。副本在目标清理或复用后不能继续借用。 */
        @Override public void resultOrder(int index, Order order, String instrumentId) {
            orders[index] = order == null ? null : (orderCopies[index] = order.copyIntoCommandSlot(orderCopies[index]));
            symbols[index] = instrumentId;
            capturedMask |= 1 << index;
        }
        /** 要求 Lane 对已终态订单也提供结果副本。 */
        @Override public boolean includeTerminalAfterImage() { return true; }
        /** 归还旧租约后按非空订单副本数选择空响应、单单或多单协议，向 槽池 租用字节并编码，保存有效长度。编码运行异常或 Error 时归还本次已取得的租约再抛出。 */
        @Override public void prepareResponse() {
            if (responseSlot != null) responseSlotPool.release(responseSlot);
            responseSlot = null;
            prepared = false;
            response = null;
            responseLength = 0;

            int validCount = 0;
            for (int i = 0; i < count; i++) {
                if (orders[i] != null) {
                    sources[validCount].set(orders[i], symbols[i]);
                    validCount++;
                }
            }
            if (validCount == 0) {
                response = TradingCore.EMPTY_RESPONSE_DATA;
                prepared = true;
                return;
            }
            try {
                boolean single = validCount == 1;
                var first = sources[0];
                var orderList = single ? java.util.List.of(first) : java.util.List.of(first, sources[1]);
                int length = single
                        ? com.surprising.aeron.protocol.CoreCommandResultCodec.encodedSingleOrderLength(first)
                        : com.surprising.aeron.protocol.CoreCommandResultCodec.encodedLength(orderList, java.util.List.of());
                responseSlot = responseSlotPool.acquireSlot(length);
                int encodedLength = single
                        ? com.surprising.aeron.protocol.CoreCommandResultCodec.encodeSingleOrderInto(first, responseSlot.writer())
                        : com.surprising.aeron.protocol.CoreCommandResultCodec.encodeInto(
                                orderList, java.util.List.of(), responseSlot.storage(), 0);
                responseLength = encodedLength;
                response = responseSlot.storage();
                prepared = true;
            } catch (RuntimeException | Error failure) {
                if (responseSlot != null) responseSlotPool.release(responseSlot);
                responseSlot = null;
                throw failure;
            }
        }

        private final com.surprising.aeron.service.result.OrderSource[] sources = {
                new com.surprising.aeron.service.result.OrderSource(),
                new com.surprising.aeron.service.result.OrderSource()
        };

        /** 返回当前响应存储引用，移交租约后为空。 */
        @Override public byte[] preparedResponse() { return response; }
        /** 返回当前响应有效字节数，移交后为零。 */
        @Override public int preparedResponseLength() { return responseLength; }

        /** 只断开当前响应槽和数组/长度引用，不归还 槽池，prepared 标记保持原值直到 clear 或下一次准备。 */
        void transferResponseOwnership() {
            responseSlot = null;
            response = null;
            responseLength = 0;
        }

        /** 归还仍由目标持有的响应租约，清有效订单和身份、捕获位与响应标记。 */
        void clear() {
            if (responseSlot != null && responseSlotPool != null) {
                responseSlotPool.release(responseSlot);
                responseSlot = null;
            }
            for (int i = 0; i < count; i++) {
                orders[i] = null;
                Order copy = orderCopies[i];
                if (copy != null) copy.clearCommandSlot();
                sources[i].clear();
                symbols[i] = null;
                ids[i] = 0;
            }
            count = 0;
            capturedMask = 0;
            prepared = false;
            response = null;
            responseLength = 0;
        }
    }

    private void clearPlaceReferences() {
        orderCheckState.placeOrderCheck = null;
        orderCheckState.placeOrderCheckOwnerCollected = false;
        orderCheckState.realtimeResolvedTakerOrder = null;
    }

    private void clearLiquidationReferences() {
        if (commitControl.reusableLiquidationWork != null) commitControl.reusableLiquidationWork.clearReferences();
        if (commitControl.liquidationCheck != null) commitControl.liquidationCheck.clearReferences();
    }

    private void clearDeferredMatching() {
        orderCheckState.deferredClusterTimestamp = 0;
        orderCheckState.deferredClusterPosition = 0;
        orderCheckState.deferredSourceKey = null;
    }

}
