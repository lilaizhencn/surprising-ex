package com.surprising.aeron.service.order;

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
    OrderBatchProcessor.PendingBatch orderBatch;
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
    private Decoded decodedCommand;
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
        OrderCommand.PlaceOrderCheckEvent placeOrderCheck;
        boolean placeOrderCheckOwnerCollected;
        OrderCommand.ResolvedPlaceOrder realtimeResolvedTakerOrder;
        Order triggerOrderCheckOrder;
        com.surprising.aeron.model.CoreTriggerOrderState sourceTrigger;
        OrderCommand.ReplaceOrderCheckEvent replacementOrderCheck;
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
        TradeSettlement.Plan settlementPlan;
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
                OrderCommand.ReplaceOrderCheck value = orderCheckState.orderCheck;
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
    public com.surprising.aeron.service.order.OrderCommand.ResultTarget laneResultTarget() {
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
            OrderCommand.PlaceOrderCheckEvent orderCheck, int matcherShard,
            com.surprising.aeron.service.settlement.TradeSettlement settlement,
            java.util.function.Supplier<?> original) {
        matchingTasks.orderCheckMatching.prepare(owner, orderCheck, matcherShard, settlement, original);
        return matchingTasks.orderCheckMatching;
    }

    /** 准备本槽普通下单任务，只接受一种已准入订单表示并保留结算事件租约。 */
    java.util.function.Supplier<?> preparePlaceMatching(TradingCore owner,
            int matcherShard, long userId,
            OrderCommand.ResolvedPlaceOrder resolvedOrder,
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
            long originalOrderId, String instrumentId, OrderCommand.ResolvedPlaceOrder replacement,
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
                               OrderCommand.Decoded decodedCommand, OrderCommand.ReplaceOrderCheck orderCheck) {
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
    OrderCommand withOrderCheck(OrderCommand.ReplaceOrderCheck nextOrderCheck) {
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
    public OrderCommand.ReplaceOrderCheck matchingOrderCheck() { return orderCheckState.orderCheck; }
    /** 返回替换操作绑定的 Lane 准入事件。 */
    com.surprising.aeron.service.order.OrderCommand.ReplaceOrderCheckEvent replacementOrderCheck() { return orderCheckState.replacementOrderCheck; }
    /** 只允许 AMEND/REPLACE 首次绑定非空替换准入事件，重复或操作不符拒绝。 */
    void replacementOrderCheck(com.surprising.aeron.service.order.OrderCommand.ReplaceOrderCheckEvent event) {
        if (event == null || orderCheckState.replacementOrderCheck != null || operation != Operation.AMEND && operation != Operation.REPLACE)
            throw new IllegalStateException("invalid replacement admissionState.admission slot");
        orderCheckState.replacementOrderCheck = event;
    }
    /** 取走替换准入事件并清该引用，把后续使用责任交给调用方。 */
    com.surprising.aeron.service.order.OrderCommand.ReplaceOrderCheckEvent takeReplaceOrderCheck() {
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
    public com.surprising.aeron.service.settlement.TradeSettlement.Plan settlementPlan() { return settlementState.settlementPlan; }
    /** 返回普通下单的 Lane 准入事件。 */
    public OrderCommand.PlaceOrderCheckEvent placeOrderCheck() {
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
    OrderCommand.PlaceOrderCheckEvent takePlaceOrderCheck() {
        OrderCommand.PlaceOrderCheckEvent value = orderCheckState.placeOrderCheck;
        orderCheckState.placeOrderCheck = null;
        return value;
    }
    /** 只允许 PLACE 首次绑定非空准入事件，其他操作或重复绑定拒绝。 */
    public void placeOrderCheck(OrderCommand.PlaceOrderCheckEvent event) {
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
    public OrderCommand.ResolvedPlaceOrder realtimeResolvedTakerOrder() { return orderCheckState.realtimeResolvedTakerOrder; }
    /** 保存可空实时准入订单引用，不复制对象。 */
    public void realtimeResolvedTakerOrder(OrderCommand.ResolvedPlaceOrder order) { orderCheckState.realtimeResolvedTakerOrder = order; }
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
                    com.surprising.aeron.service.settlement.TradeSettlement.Plan plan) {
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
            implements com.surprising.aeron.service.order.OrderCommand.ResultTarget {
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

public static final class Direct {
    /** 创建槽时绑定的唯一所属 Owner，跨命令复用。 */
    private final TradingCore owner;
    @Accessors(fluent = true, chain = false)
    @Getter(value = AccessLevel.PACKAGE)
    private CoreMessage command;
    /** 本轮完整命令指纹，供幂等及终态保留核对。 */
    private CommandFingerprint fingerprint;
    private TradingCore.SourceKey sourceKey;
    @Accessors(fluent = true, chain = false)
    @Getter(value = AccessLevel.PACKAGE)
    private long commitFenceTimestamp;
    private long commitFenceClusterPosition;

    /** 返回是否已有直接命令占用本槽，不能据此判断业务是否成功。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private boolean active;
    /** 该工作开始前的运行时修订，用于变化及回滚判断。 */
    private long beforeRevision;
    /** 命令开始前的账户回滚检查点。返回本命令开始前的账户回滚检查点。 */
    @Accessors(fluent = true, chain = false)
    @Getter(value = AccessLevel.PACKAGE)
    private long checkpoint;
    /** 命令开始前的持仓身份回滚检查点。 */
    private long identityCheckpoint;
    /** 当前业务或回滚续步，返回假时由 Owner 后续轮询推进。 */
    private BooleanSupplier controlWork;
    @Accessors(fluent = true, chain = false)
    @Getter(value = AccessLevel.PACKAGE)
    private ResponseStatus status;
    @Accessors(fluent = true, chain = false)
    @Getter(value = AccessLevel.PACKAGE)
    private CoreResultCode resultCode;
    @Accessors(fluent = true, chain = false)
    @Getter
    @Setter
    private com.surprising.aeron.service.core.change.AccountPartitionCommit commitEvent;
    /** 返回直接命令的收尾材料是否已准备，防止后续轮询重复准备。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private boolean finalizationPrepared;

    private final FundingControlContinuation fundingControl = new FundingControlContinuation();
    private final RiskScanControlContinuation riskScanControl = new RiskScanControlContinuation();
    private final AdlControlContinuation adlControl = new AdlControlContinuation();
    private final LiquidationResolutionControlContinuation liquidationResolutionControl =
            new LiquidationResolutionControlContinuation();
    private final AccountCommandService accountControl = new AccountCommandService();
    private final BooleanSupplier accountControlWork;
    private final TriggerControlContinuation triggerControl = new TriggerControlContinuation();
    private static final BooleanSupplier COMPLETE_CONTROL = () -> true;

    @Accessors(fluent = true, chain = false)
    @Getter
    private FundingSettlementService.FundingWork reusableFundingWork;
    @Accessors(fluent = true, chain = false)
    @Getter
    private AdlService.AdlWork reusableAdlWork;
    private LiquidationService.ResolutionWork reusableResolutionWork;
    private RiskScanner reusableRiskCoordinator;

    public Direct(TradingCore owner) {
        this.owner = Objects.requireNonNull(owner);
        accountControlWork = () -> {
            if (!accountControl.getAsBoolean()) return false;
            if (accountControl.stateChanged()) this.owner.requestCommitPublication();
            return true;
        };
    }

    /** 拒绝覆盖仍占用的槽，保存非空命令、指纹及来源，再记下业务时间、日志位置与回滚检查点。 */
    public void initialize(CoreMessage command, CommandFingerprint fingerprint,
            TradingCore.SourceKey sourceKey, long timestamp, long position,
            long beforeRevision,
            long checkpoint, long identityCheckpoint) {
        if (active) throw new IllegalStateException("direct command slot is occupied");
        this.command = Objects.requireNonNull(command);
        this.fingerprint = Objects.requireNonNull(fingerprint);
        this.sourceKey = Objects.requireNonNull(sourceKey);
        this.beforeRevision = beforeRevision;
        this.checkpoint = checkpoint;
        this.identityCheckpoint = identityCheckpoint;
        commitFenceTimestamp = timestamp;
        commitFenceClusterPosition = position;
        active = true;
    }

    /** 返回是否还有控制续步，也可能是拒单后的回滚续步。 */
    public boolean hasControlWork() { return controlWork != null; }
    /** 保存调用方已经决定的状态和结果码，不在此检查非空或完成发布。 */
    public void result(ResponseStatus status, CoreResultCode resultCode) {
        this.status = status;
        this.resultCode = resultCode;
    }
    /** 记下本条命令的收尾材料已经准备。 */
    public void markFinalizationPrepared() { finalizationPrepared = true; }
    /** 撤销收尾材料就绪标记，允许调用方按现有规则重新准备。 */
    public void clearFinalizationPrepared() { finalizationPrepared = false; }

    /** 只解除本槽对提交事件的引用，事件回收由原调用方负责。 */
    public void clearCommitEvent() { commitEvent = null; }

    /** 要求当前没有续步，再保存非空控制工作，供 Owner 后续轮询。 */
    public void deferControl(BooleanSupplier work) {
        if (controlWork != null) throw new IllegalStateException("command already has pending work");
        controlWork = Objects.requireNonNull(work);
    }

    /** 要求没有旧续步，绑定资金费收集器并保留工作对象供后续命令复用。 */
    public void deferFundingControl(FundingSettlementService.FundingWork work) {
        if (controlWork != null) throw new IllegalStateException("command already has pending work");
        reusableFundingWork = Objects.requireNonNull(work);
        controlWork = fundingControl;
    }

    /** 绑定风险扫描和后续触发扫描所需信息。同一槽不能并存第二条控制续步。 */
    public void deferRiskScanControl(RiskScanner risk, int symbolId,
            String instrumentId, int maxUsers, int pendingBefore, long startedAt, long beforeRevision) {
        if (controlWork != null) throw new IllegalStateException("command already has pending work");
        riskScanControl.prepare(risk, symbolId, instrumentId, maxUsers, pendingBefore, startedAt,
                beforeRevision);
        controlWork = riskScanControl;
    }

    /** 绑定自动减仓续步，并记住原工作对象供后续复用。 */
    public void deferAdlControl(AdlService.AdlWork work) {
        if (controlWork != null) throw new IllegalStateException("command already has pending work");
        reusableAdlWork = Objects.requireNonNull(work);
        controlWork = adlControl;
    }

    /** 绑定强平结果确认续步，并保留原工作对象供后续命令复用。 */
    public void deferLiquidationResolutionControl(LiquidationService.ResolutionWork work) {
        if (controlWork != null) throw new IllegalStateException("command already has pending work");
        reusableResolutionWork = Objects.requireNonNull(work);
        controlWork = liquidationResolutionControl;
    }

    /** 返回上次保存的强平结果确认工作对象。 */
    public LiquidationService.ResolutionWork reusableLiquidationResolutionWork() {
        return reusableResolutionWork;
    }

    /** 首次用所属 Owner 的索引、运行时和身份表创建扫描器，之后重置本轮预算。 */
    public RiskScanner reusableRiskScanCoordinator(int maxUsers) {
        if (reusableRiskCoordinator == null) {
            reusableRiskCoordinator = new RiskScanner(
                    maxUsers, owner.positionUserIndex(), owner.accountBook(), owner.identities());
        } else {
            reusableRiskCoordinator.resetForCommand(maxUsers);
        }
        return reusableRiskCoordinator;
    }

    /** 准备所属用户的余额调整账户任务，并安装固定轮询函数。 */
    public void deferBalanceAdjustment(long userId, String asset, long deltaUnits) {
        if (controlWork != null) throw new IllegalStateException("command already has pending work");
        accountControl.prepareBalanceAdjustment(owner.accountBook(), owner.identities(), userId, asset, deltaUnits);
        controlWork = accountControlWork;
    }

    /** 准备所属用户的转出账户任务，完成和变化判断沿用账户任务自身回执。 */
    public void deferTransferOut(long userId, TransferFundsCommand command) {
        if (controlWork != null) throw new IllegalStateException("command already has pending work");
        accountControl.prepareTransferOut(owner.accountBook(), owner.identities(), userId, command);
        controlWork = accountControlWork;
    }

    /** 准备所属用户的划转完成任务，后续轮询成功且有变化时请求发布。 */
    public void deferTransferComplete(long userId, long transferId) {
        if (controlWork != null) throw new IllegalStateException("command already has pending work");
        accountControl.prepareTransferCompletion(owner.accountBook(), userId, transferId);
        controlWork = accountControlWork;
    }

    /** 准备杠杆变更账户任务，是否发生变化由账户回执判断。 */
    public void deferLeverageChange(long userId, UpdateLeverageCommand command) {
        if (controlWork != null) throw new IllegalStateException("command already has pending work");
        accountControl.prepareLeverageChange(owner.accountBook(), owner.identities(), userId, command);
        controlWork = accountControlWork;
    }

    /** 准备持仓模式变更账户任务，是否发生变化由账户回执判断。 */
    public void deferPositionModeChange(long userId, UpdatePositionModeCommand command) {
        if (controlWork != null) throw new IllegalStateException("command already has pending work");
        accountControl.preparePositionModeChange(owner.accountBook(), userId, command);
        controlWork = accountControlWork;
    }

    /** 准备逐仓保证金调整账户任务，不在 Owner 直接改 Lane 状态。 */
    public void deferPositionMarginAdjustment(long userId,
                                       AdjustPositionMarginCommand command) {
        if (controlWork != null) throw new IllegalStateException("command already has pending work");
        accountControl.preparePositionMarginAdjustment(owner.accountBook(), owner.identities(), userId, command);
        controlWork = accountControlWork;
    }

    /** 把触发单变更参数放入固定槽，只派发给该用户所属 Lane，后续由同一对象收集完成。 */
    public void deferTriggerMutationControl(long userId,
            TriggerOrderCommands.Mutation mutation, long triggerOrderId, long arg1, long arg2,
            long arg3, boolean flag, String text) {
        if (controlWork != null) throw new IllegalStateException("command already has pending work");
        triggerControl.prepareMutation(userId, mutation, triggerOrderId, arg1, arg2, arg3,
                flag, text);
        owner.accountBook().dispatchControlLanes(
                1L << owner.accountBook().topology().accountLaneId(userId), triggerControl);
        controlWork = triggerControl;
    }

    /** 准备触发单写入任务并派发给用户所属 Lane，保留合约和持仓身份。 */
    public void deferTriggerUpsertControl(long userId,
            CoreTriggerOrderStateView trigger, int symbolId, long positionKey,
            boolean instrumentSettled) {
        if (controlWork != null) throw new IllegalStateException("command already has pending work");
        triggerControl.prepareTriggerUpsert(userId, trigger, symbolId, positionKey,
                instrumentSettled);
        owner.accountBook().dispatchControlLanes(
                1L << owner.accountBook().topology().accountLaneId(userId), triggerControl);
        controlWork = triggerControl;
    }

    /** 准备互斥触发单对，共用一次所属账户 Lane 派发，第二条保存在同一续步内。 */
    public void deferTriggerOcoPairControl(long userId,
            CoreTriggerOrderStateView first, CoreTriggerOrderStateView second,
            int symbolId, long positionKey, boolean instrumentSettled) {
        if (controlWork != null) throw new IllegalStateException("command already has pending work");
        triggerControl.prepareTriggerUpsert(userId, first, symbolId, positionKey, instrumentSettled);
        triggerControl.secondTrigger = second;
        owner.accountBook().dispatchControlLanes(
                1L << owner.accountBook().topology().accountLaneId(userId), triggerControl);
        controlWork = triggerControl;
    }

    /** 准备算法单写入任务并派发给用户所属 Lane。 */
    public void deferAlgoUpsertControl(long userId, CoreAlgoOrderCommand algo,
            int symbolId, long clusterTimestamp) {
        if (controlWork != null) throw new IllegalStateException("command already has pending work");
        triggerControl.prepareAlgoUpsert(userId, algo, symbolId, clusterTimestamp);
        owner.accountBook().dispatchControlLanes(
                1L << owner.accountBook().topology().accountLaneId(userId), triggerControl);
        controlWork = triggerControl;
    }

    /** 只允许 Owner 推进已占用槽。若拒绝前已有变化，先分轮完成账户及持仓身份回滚，再交运行时收尾。 */
    public CoreResponse poll() {
        owner.assertOwner();
        if (!active) throw new IllegalStateException("no pending direct command");
        if (status == null) {
            try {
                if (!controlWork.getAsBoolean()) return null;
                status = ResponseStatus.APPLIED;
                resultCode = CoreResultCode.NONE;
            } catch (CoreStateRejectedException failure) {
                status = ResponseStatus.REJECTED;
                resultCode = CoreResultCode.fromRejectionCode(failure.code());
            } catch (ArithmeticException failure) {
                status = ResponseStatus.REJECTED;
                resultCode = CoreResultCode.ARITHMETIC_OVERFLOW;
            } catch (IllegalArgumentException failure) {
                status = ResponseStatus.REJECTED;
                resultCode = CoreResultCode.INVALID_COMMAND;
            }
            controlWork = null;
        }
        if (status != ResponseStatus.APPLIED
                && (owner.commits.publication.dirty()
                || owner.accountBook().revision() != beforeRevision
                || owner.accountBook().hasUncommittedCommandChanges())) {
            if (controlWork == null) {
                owner.commits.publication.clearBatchState();
                controlWork = owner.accountBook().beginCommandRollback(
                        checkpoint, Math.incrementExact(owner.appliedCommandCount()));
            }
            if (!controlWork.getAsBoolean()) return null;
            owner.identities().rollbackPositionKeys(identityCheckpoint);
            controlWork = null;
        }
        return owner.finishDirectCommand(command, commitFenceTimestamp, commitFenceClusterPosition,
                sourceKey, fingerprint, beforeRevision, checkpoint,
                identityCheckpoint, status, resultCode);
    }

    /** 清除本条命令、各固定续步和提交标记，释放本轮引用。 */
    public void clear() {
        riskScanControl.clear();
        accountControl.clear();
        triggerControl.clear();
        active = false;
        command = null;
        fingerprint = null;
        sourceKey = null;
        beforeRevision = checkpoint = identityCheckpoint = 0;
        controlWork = null;
        status = null;
        resultCode = null;
        commitEvent = null;
        finalizationPrepared = false;
        commitFenceTimestamp = commitFenceClusterPosition = 0;
    }

    /** 资金费工作完成后的 Owner 收集步骤，整理进度和受影响用户。 */
    private final class FundingControlContinuation implements BooleanSupplier {
        @Override public boolean getAsBoolean() {
            var work = reusableFundingWork;
            if (!work.poll()) return false;
            var result = work.result();
            owner.requestCommitPublication();
            owner.resultBuilder.commandFundingProgress = result.progress();
            owner.resultBuilder.addChangedUsersFromFundingPayments(result.payments());
            owner.notifyReplayFundingObserver(result);
            return true;
        }
    }

    /** 在同一条风险命令内衔接风险扫描和剩余预算内的触发扫描，不建立新业务队列。 */
    private final class RiskScanControlContinuation implements BooleanSupplier {
        private RiskScanner risk;
        private int symbolId;
        private String instrumentId;
        private int maxUsers;
        private int pendingBefore;
        private long startedAt;
        /** 该工作开始前的运行时修订，用于变化及回滚判断。 */
        private long beforeRevision;
        private BooleanSupplier triggers;

        /** 保存风险续扫输入并清空上一轮触发扫描续步。 */
        void prepare(RiskScanner risk, int symbolId, String instrumentId,
                int maxUsers, int pendingBefore, long startedAt, long beforeRevision) {
            this.risk = risk;
            this.symbolId = symbolId;
            this.instrumentId = Objects.requireNonNull(instrumentId);
            this.maxUsers = maxUsers;
            this.pendingBefore = pendingBefore;
            this.startedAt = startedAt;
            this.beforeRevision = beforeRevision;
            this.triggers = null;
        }
        /** 清除当前风险及触发续步引用和统计输入，保留外层可复用扫描器。 */
        void clear() {
            risk = null; instrumentId = null; triggers = null;
            symbolId = maxUsers = pendingBefore = 0; startedAt = beforeRevision = 0;
        }

        /** 先收齐可空风险扫描工作，有修订变化则请求发布。 */
        @Override public boolean getAsBoolean() {
            if (triggers == null) {
                if (risk != null && !risk.poll()) return false;
                if (owner.accountBook().revision() != beforeRevision) owner.requestCommitPublication();
                int remaining = maxUsers - (risk == null ? 0 : risk.completedWork());
                var completedScan = owner.accountBook().riskScan(symbolId);
                triggers = remaining > 0 && completedScan != null
                        && !completedScan.triggerComplete()
                        ? owner.pendingTriggerScan(instrumentId, remaining) : COMPLETE_CONTROL;
            }
            if (!triggers.getAsBoolean()) return false;
            owner.logRiskScan("continuation", instrumentId, maxUsers, pendingBefore, startedAt);
            return true;
        }
    }

    /** 在 Owner 轮询自动减仓工作完成并请求发布的固定续步。 */
    private final class AdlControlContinuation implements BooleanSupplier {
        @Override public boolean getAsBoolean() {
            var work = reusableAdlWork;
            if (!work.getAsBoolean()) return false;
            owner.requestCommitPublication();
            return true;
        }
    }

    /** 在 Owner 收集强平结果确认工作并请求发布的固定续步。 */
    private final class LiquidationResolutionControlContinuation implements BooleanSupplier {
        @Override public boolean getAsBoolean() {
            var work = reusableResolutionWork;
            if (!work.getAsBoolean()) return false;
            owner.requestCommitPublication();
            return true;
        }
    }

    /** 触发单和算法单直接命令共用的固定槽：账户 Lane 执行变更，Owner 再轮询并收集结果。 */
    private final class TriggerControlContinuation
            implements BooleanSupplier, IntFunction<Object> {
        private static final byte MUTATION = 1;
        private static final byte TRIGGER_UPSERT = 2;
        private static final byte ALGO_UPSERT = 3;
        private byte kind;
        private long userId, triggerOrderId, arg1, arg2, arg3;
        private boolean flag;
        private String text;
        private TriggerOrderCommands.Mutation mutation;
        private CoreTriggerOrderStateView trigger, secondTrigger;
        private CoreAlgoOrderCommand algo;
        private int symbolId;
        private long positionKey;
        private boolean instrumentSettled;

        /** 清除旧参数，保存非空触发单变更种类与各原语输入。 */
        void prepareMutation(long userId,
                TriggerOrderCommands.Mutation mutation, long triggerOrderId, long arg1, long arg2,
                long arg3, boolean flag, String text) {
            clear(); kind = MUTATION; this.userId = userId;
            this.mutation = Objects.requireNonNull(mutation); this.triggerOrderId = triggerOrderId;
            this.arg1 = arg1; this.arg2 = arg2; this.arg3 = arg3; this.flag = flag; this.text = text;
        }
        /** 清除旧参数并保存非空触发单及其账户、合约、持仓身份。 */
        void prepareTriggerUpsert(long userId,
                CoreTriggerOrderStateView trigger, int symbolId, long positionKey,
                boolean instrumentSettled) {
            clear(); kind = TRIGGER_UPSERT;
            this.userId = userId; this.trigger = Objects.requireNonNull(trigger); this.symbolId = symbolId;
            this.positionKey = positionKey; this.instrumentSettled = instrumentSettled;
        }
        /** 清除旧参数并保存非空算法单及其账户、合约身份。 */
        void prepareAlgoUpsert(long userId, CoreAlgoOrderCommand algo,
                int symbolId, long clusterTimestamp) {
            clear(); kind = ALGO_UPSERT;
            this.userId = userId; this.algo = Objects.requireNonNull(algo); this.symbolId = symbolId; this.arg1 = clusterTimestamp;
        }
        /** 清除本轮操作类别、引用与原语参数，固定续步对象本身继续复用。 */
        void clear() {
            kind = 0; mutation = null; trigger = null; secondTrigger = null; algo = null; text = null;
            userId = triggerOrderId = arg1 = arg2 = arg3 = positionKey = 0;
            flag = instrumentSettled = false; symbolId = 0;
        }

        /** 在已选中的账户 Lane 执行触发单变更、单条或互斥对写入、算法单写入。 */
        @Override public Object apply(int ignoredLaneId) {
            var accountBook = owner.accountBook();
            return switch (kind) {
                case MUTATION -> switch (mutation) {
                    case CANCEL -> accountBook.triggerOrders().cancelTriggerOrder(userId, triggerOrderId);
                    case CLAIM -> accountBook.triggerOrders().claimTriggerOrder(triggerOrderId, arg1, arg2, arg3);
                    case COMPLETE -> accountBook.triggerOrders().completeTriggerOrder(triggerOrderId,
                            flag, arg1, text == null ? "" : text, arg2);
                    case TRAILING -> accountBook.triggerOrders().updateTrailingTriggerOrder(triggerOrderId,
                            arg1, arg2, arg3);
                    case EXPIRE -> accountBook.triggerOrders().expireTriggerOrder(triggerOrderId, arg1);
                    case RETRY -> accountBook.triggerOrders().retryTriggerOrder(triggerOrderId, arg1, arg2);
                };
                case TRIGGER_UPSERT -> {
                    if (secondTrigger == null) accountBook.triggerOrders().upsertTriggerOrder(userId, trigger, symbolId,
                            positionKey, instrumentSettled);
                    else accountBook.triggerOrders().placeOcoPair(userId, trigger, secondTrigger,
                            symbolId, positionKey, instrumentSettled);
                    yield secondTrigger == null ? accountBook.triggerOrder(trigger.triggerOrderId()).view()
                            : java.util.List.of(accountBook.triggerOrder(trigger.triggerOrderId()).view(),
                                    accountBook.triggerOrder(secondTrigger.triggerOrderId()).view());
                }
                case ALGO_UPSERT -> {
                    accountBook.upsertAlgoOrder(userId, algo, symbolId, arg1);
                    yield accountBook.algoOrder(algo.algoOrderId());
                }
                default -> throw new IllegalStateException("unknown trigger continuation");
            };
        }

        /** 等账户控制派发完成，读取该用户 Lane 回执。 */
        @Override public boolean getAsBoolean() {
            if (!owner.accountBook().pollControlLanes()) return false;
            Object result = owner.accountBook().controlLaneResult(
                    owner.accountBook().topology().accountLaneId(userId));
            if (kind == MUTATION) {
                if (Boolean.TRUE.equals(result)) owner.requestCommitPublication();
            } else if (kind == TRIGGER_UPSERT) {
                owner.requestCommitPublication();
                if (secondTrigger == null) owner.resultBuilder.commandTriggerOrderViews = java.util.List.of((CoreTriggerOrderStateView) result);
                else {
                    @SuppressWarnings("unchecked") var views = (java.util.List<CoreTriggerOrderStateView>) result;
                    owner.resultBuilder.commandTriggerOrderViews = views;
                }
            } else {
                owner.requestCommitPublication();
            }
            return true;
        }
    }
}

public static final class Decoded {

    private Object value;
    private PlaceOrderCommand placeSlot;
    private PlaceOrderBatchCommand placeBatchSlot;
    private CancelOrderCommand cancelSlot;
    private CancelOrderBatchCommand cancelBatchSlot;
    private AmendOrderCommand amendSlot;
    private AmendOrderBatchCommand amendBatchSlot;
    private ReplaceOrderCommand replaceSlot;
    private boolean commandSlot;
    private String routedSymbol;
    private int routedShard;

    /** 同标的复用本命令作用域缓存的 Matcher 分片，换标的才查适配器。调用方提供非空规范标的和匹配的拓扑，缓存不跨线程同步。 */
    public int matcherShard(com.surprising.aeron.service.matcher.OrderBookEngine adapter, String instrumentId) {
        if (!instrumentId.equals(routedSymbol)) {
            routedShard = adapter.matcherShardId(instrumentId);
            routedSymbol = instrumentId;
        }
        return routedShard;
    }

    /** 普通构造出来的实例不能按窗口槽接口清理或重解码。 */
    private Decoded(Object value) {
        bindValue(value);
    }

    /** 建立可反复解码和清理的空窗口槽，具体协议载体在首次需要时延迟创建。 */
    public static Decoded commandSlot() {
        var result = new Decoded(null);
        result.commandSlot = true;
        return result;
    }

    /** 只允许复用槽操作，先清旧值、路由及各载体，再按消息类型原位解码常用订单类型。 */
    public void decodeInto(CoreMessage message) {
        if (!commandSlot) throw new IllegalStateException("decoded command is not a window slot");
        clearCommandSlot();
        if (message.header().messageType() == com.surprising.aeron.protocol.CoreMessageType.PLACE_ORDER) {
            if (placeSlot == null) placeSlot = PlaceOrderCommand.commandSlot();
            TradingCommandCodec.decodePlaceOrderInto(message.payloadUnsafe(), 0, message.payloadLength(), placeSlot);
            bindValue(placeSlot);
        } else if (message.header().messageType() == com.surprising.aeron.protocol.CoreMessageType.PLACE_ORDER_BATCH) {
            if (placeBatchSlot == null) placeBatchSlot = PlaceOrderBatchCommand.commandSlot();
            TradingOrderBatchCodec.decodePlaceOrderBatchInto(message.payloadUnsafe(), message.payloadLength(), placeBatchSlot);
            bindValue(placeBatchSlot);
        } else if (message.header().messageType() == com.surprising.aeron.protocol.CoreMessageType.CANCEL_ORDER) {
            if (cancelSlot == null) cancelSlot = CancelOrderCommand.commandSlot();
            TradingCommandCodec.decodeCancelOrderInto(message.payloadUnsafe(), 0, message.payloadLength(), cancelSlot);
            bindValue(cancelSlot);
        } else if (message.header().messageType() == com.surprising.aeron.protocol.CoreMessageType.CANCEL_ORDER_BATCH) {
            if (cancelBatchSlot == null) cancelBatchSlot = CancelOrderBatchCommand.commandSlot();
            TradingOrderBatchCodec.decodeCancelOrderBatchInto(message.payloadUnsafe(), message.payloadLength(), cancelBatchSlot);
            bindValue(cancelBatchSlot);
        } else if (message.header().messageType() == com.surprising.aeron.protocol.CoreMessageType.AMEND_ORDER) {
            if (amendSlot == null) amendSlot = AmendOrderCommand.commandSlot();
            TradingCommandCodec.decodeAmendOrderInto(message.payloadUnsafe(), 0, message.payloadLength(), amendSlot);
            bindValue(amendSlot);
        } else if (message.header().messageType() == com.surprising.aeron.protocol.CoreMessageType.AMEND_ORDER_BATCH) {
            if (amendBatchSlot == null) amendBatchSlot = AmendOrderBatchCommand.commandSlot();
            TradingOrderBatchCodec.decodeAmendOrderBatchInto(message.payloadUnsafe(), message.payloadLength(), amendBatchSlot);
            bindValue(amendBatchSlot);
        } else if (message.header().messageType() == com.surprising.aeron.protocol.CoreMessageType.REPLACE_ORDER) {
            if (replaceSlot == null) replaceSlot = ReplaceOrderCommand.commandSlot();
            TradingCommandCodec.decodeReplaceOrderInto(message.payloadUnsafe(), 0, message.payloadLength(), replaceSlot);
            bindValue(replaceSlot);
        } else {
            bindValue(decodeRawPayload(message));
        }
    }

    /** 只允许复用槽清理：解除当前值和路由缓存，清空已创建的各协议载体，但保留载体对象供后续复用。 */
    public void clearCommandSlot() {
        if (!commandSlot) throw new IllegalStateException("cannot clear an immutable decoded command");
        clearValues();
        routedSymbol = null;
        routedShard = 0;
        if (placeSlot != null) placeSlot.clearCommandSlot();
        if (placeBatchSlot != null) placeBatchSlot.clearCommandSlot();
        if (cancelSlot != null) cancelSlot.clearCommandSlot();
        if (cancelBatchSlot != null) cancelBatchSlot.clearCommandSlot();
        if (amendSlot != null) amendSlot.clearCommandSlot();
        if (amendBatchSlot != null) amendBatchSlot.clearCommandSlot();
        if (replaceSlot != null) replaceSlot.clearCommandSlot();
    }

    /** 判断当前 value 是否非空。 */
    public boolean isDecoded() { return value != null; }

    private void clearValues() { value = null; }

    private void bindValue(Object value) { this.value = value; }

    private <T> T required(Class<T> type) {
        if (!type.isInstance(value)) throw new IllegalStateException("decoded matching command type mismatch");
        return type.cast(value);
    }

    /** 创建单次解码持有者，不借用本类的窗口复用槽。 */
    public static Decoded decode(CoreMessage message) {
        return new Decoded(decodeRawPayload(message));
    }

    private static Object decodeRawPayload(CoreMessage message) {
        return switch (message.header().messageType()) {
            case PLACE_ORDER -> TradingCommandCodec.decodePlaceOrderInto(message.payloadUnsafe(), 0, message.payloadLength(), null);
            case PLACE_ORDER_BATCH -> TradingOrderBatchCodec.decodePlaceOrderBatch(message.payloadUnsafe(), message.payloadLength());
            case CANCEL_ORDER -> TradingCommandCodec.decodeCancelOrderInto(message.payloadUnsafe(), 0, message.payloadLength(), null);
            case CANCEL_ORDER_BATCH -> TradingOrderBatchCodec.decodeCancelOrderBatch(message.payloadUnsafe(), message.payloadLength());
            case REPLACE_ORDER -> TradingCommandCodec.decodeReplaceOrderInto(message.payloadUnsafe(), 0, message.payloadLength(), null);
            case AMEND_ORDER -> TradingCommandCodec.decodeAmendOrderInto(message.payloadUnsafe(), 0, message.payloadLength(), null);
            case AMEND_ORDER_BATCH -> TradingOrderBatchCodec.decodeAmendOrderBatch(message.payloadUnsafe(), message.payloadLength());
            case EXECUTE_TRIGGER_ORDER ->
                    com.surprising.aeron.protocol.CoreTriggerOrderCodec.decodeExecute(message.payloadUnsafe());
            case EXECUTE_LIQUIDATION -> TradingCommandCodec.decodeExecuteLiquidation(message.payloadUnsafe());
            case EXECUTE_LIQUIDATION_BATCH ->
                    TradingCommandCodec.decodeExecuteLiquidationBatch(message.payloadUnsafe());
            case SETTLE_INSTRUMENT -> TradingCommandCodec.decodeSettleInstrument(message.payloadUnsafe());
            default -> message;
        };
    }

    /** 核对并借出当前普通下单载体。 */
    public PlaceOrderCommand placeOrder() { return required(PlaceOrderCommand.class); }
    /** 核对并借出当前批量下单载体。 */
    public PlaceOrderBatchCommand placeOrderBatch() { return required(PlaceOrderBatchCommand.class); }
    /** 核对并借出当前普通撤单载体。 */
    public CancelOrderCommand cancelOrder() { return required(CancelOrderCommand.class); }
    /** 核对并借出当前批量撤单载体。 */
    public CancelOrderBatchCommand cancelOrderBatch() { return required(CancelOrderBatchCommand.class); }
    /** 核对并借出当前撤改重下命令载体。 */
    public ReplaceOrderCommand replaceOrder() { return required(ReplaceOrderCommand.class); }
    /** 核对并借出当前普通改单载体。 */
    public AmendOrderCommand amendOrder() { return required(AmendOrderCommand.class); }
    /** 核对并借出当前批量改单载体。 */
    public AmendOrderBatchCommand amendOrderBatch() { return required(AmendOrderBatchCommand.class); }
    /** 核对并借出当前单项强平执行参数。 */
    public ExecuteLiquidationCommand liquidation() { return required(ExecuteLiquidationCommand.class); }
    /** 核对并借出当前批量强平执行参数。 */
    public ExecuteLiquidationBatchCommand liquidationBatch() { return required(ExecuteLiquidationBatchCommand.class); }
    /** 核对并借出当前合约到期结算参数。 */
    public SettleInstrumentCommand settlement() { return required(SettleInstrumentCommand.class); }
    /** 核对并借出触发执行解码器返回的原 long 参数数组。字段顺序沿用现有协议，调用方不得跨槽清理保留引用。 */
    public long[] trigger() { return required(long[].class); }
}

@NoArgsConstructor
public static class ResolvedPlaceOrder {
    @Accessors(fluent = true, chain = false)
    @Getter
    private PlaceOrderCommand intent;
    @Accessors(fluent = true, chain = false)
    @Getter
    private CoreInstrument instrument;
    @Accessors(fluent = true, chain = false)
    @Getter
    private int symbolId;
    private boolean resolved;
    @Accessors(fluent = true, chain = false)
    @Getter
    private long matchingPriceTicks;
    @Accessors(fluent = true, chain = false)
    @Getter
    private long reservationPriceTicks;
    @Accessors(fluent = true, chain = false)
    @Getter
    private long markPriceTicks;
    @Accessors(fluent = true, chain = false)
    @Getter
    private long indexPriceTicks;
    /** 本次决策使用的远期价格刻度，有远期价格时必须同时提供指数价格。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private long forwardPriceTicks;
    @Accessors(fluent = true, chain = false)
    @Getter
    private ReservationKind reservationKind;
    @Accessors(fluent = true, chain = false)
    @Getter
    private String reservationAsset;
    /** Maker 费率，单位为百万分比，负数表示返佣。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private long makerFeeRatePpm;
    /** Taker 费率，单位为百万分比，且不能小于 Maker 费率。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private long takerFeeRatePpm;

    /** 校验并保存完整订单决策输入，包括指数、远期价格及冻结资产与费率。 */
    public ResolvedPlaceOrder(PlaceOrderCommand intent, CoreInstrument instrument, int symbolId,
                              long matchingPriceTicks, long reservationPriceTicks, long markPriceTicks,
                              long indexPriceTicks, long forwardPriceTicks,
                              ReservationKind reservationKind, String reservationAsset,
                              long makerFeeRatePpm, long takerFeeRatePpm) {
        initializeResolvedOrder(intent, instrument, symbolId, matchingPriceTicks, reservationPriceTicks,
                markPriceTicks, indexPriceTicks, forwardPriceTicks, reservationKind, reservationAsset,
                makerFeeRatePpm, takerFeeRatePpm);
    }

    /** 清除本对象保存的意图及全部决策字段，供后续安全复用。 */
    public void clear() {
        clearResolvedOrder();
    }

    /** 只允许初始化一次完整决策。若路由已先绑定，必须保持相同意图、币对对象和稠密编号，再校验价格与费率并发布已决策标记。 */
    public final void initializeResolvedOrder(
            PlaceOrderCommand intent, CoreInstrument instrument, int symbolId,
            long matchingPriceTicks, long reservationPriceTicks, long markPriceTicks,
            long indexPriceTicks, long forwardPriceTicks, ReservationKind reservationKind,
            String reservationAsset, long makerFeeRatePpm, long takerFeeRatePpm) {
        if (resolved || intent == null || instrument == null || symbolId < 0
                || (this.intent != null && (this.intent != intent || this.instrument != instrument || this.symbolId != symbolId))
                || matchingPriceTicks <= 0 || reservationPriceTicks <= 0
                || markPriceTicks <= 0 || indexPriceTicks < 0 || forwardPriceTicks < 0
                || (forwardPriceTicks > 0 && indexPriceTicks == 0)
                || reservationKind == null || reservationAsset == null || reservationAsset.isBlank()
                || makerFeeRatePpm < -1_000_000 || makerFeeRatePpm > 1_000_000
                || takerFeeRatePpm < -1_000_000 || takerFeeRatePpm > 1_000_000
                || makerFeeRatePpm > takerFeeRatePpm) {
            throw new IllegalArgumentException("invalid resolved place order");
        }
        // Owner 在投递前已固定路由身份，Lane 补决策时不能改写它。
        if (this.intent == null) initializeOrderIntent(intent, instrument, symbolId);
        this.matchingPriceTicks = matchingPriceTicks;
        this.reservationPriceTicks = reservationPriceTicks;
        this.markPriceTicks = markPriceTicks;
        this.indexPriceTicks = indexPriceTicks;
        this.forwardPriceTicks = forwardPriceTicks;
        this.reservationKind = reservationKind;
        this.reservationAsset = AssetBalance.normalizeAsset(reservationAsset);
        this.makerFeeRatePpm = makerFeeRatePpm;
        this.takerFeeRatePpm = takerFeeRatePpm;
        resolved = true;
    }

    /** 先绑定订单意图和币对路由，确认两者币对编号一致。后续 Lane 只能补决策字段，不能换掉路由身份。 */
    public final void initializeOrderIntent(PlaceOrderCommand intent, CoreInstrument instrument, int symbolId) {
        if (this.intent != null || intent == null || instrument == null || symbolId < 0
                || !intent.instrumentId().equals(instrument.instrumentId()))
            throw new IllegalArgumentException("invalid order routing input");
        this.intent = intent;
        this.instrument = instrument;
        this.symbolId = symbolId;
    }

    /** 释放意图和币对引用，将价格、费率及身份字段清零，并撤销已决策标记。此时不能再读取依赖 intent 的订单属性。 */
    public final void clearResolvedOrder() {
        intent = null;
        instrument = null;
        symbolId = 0;
        matchingPriceTicks = reservationPriceTicks = markPriceTicks = 0;
        indexPriceTicks = forwardPriceTicks = 0;
        reservationKind = null;
        reservationAsset = null;
        makerFeeRatePpm = takerFeeRatePpm = 0;
        resolved = false;
    }

    /** 返回完整价格和预留决策是否已初始化，仅绑定路由不能算完成。 */
    public final boolean resolvedOrderInitialized() { return resolved; }
    /** 返回原下单意图中的唯一订单编号。 */
    public long orderId() { return intent.orderId(); }
    /** 返回原下单意图中的永久币对编号文本。 */
    public String instrumentId() { return intent.instrumentId(); }
    /** 返回原下单意图的买入或卖出方向。 */
    public com.surprising.aeron.protocol.CoreOrderSide side() { return intent.side(); }
    /** 返回原始请求的限价或现货市价保护边界，不等于所有类型实际使用的撮合价。 */
    public long limitPriceTicks() { return intent.limitPriceTicks(); }
    /** 返回原下单意图的数量步数。 */
    public long quantitySteps() { return intent.quantitySteps(); }
    /** 返回原下单意图是否只允许减少已有持仓。 */
    public boolean reduceOnly() { return intent.reduceOnly(); }
    /** 返回原下单意图的全仓或逐仓模式。 */
    public com.surprising.aeron.protocol.CoreMarginMode marginMode() { return intent.marginMode(); }
    /** 返回原下单意图对应的净持仓或双向持仓腿。 */
    public com.surprising.aeron.protocol.CorePositionSide positionSide() { return intent.positionSide(); }
    /** 返回原下单意图的限价或市价类型。 */
    public com.surprising.aeron.protocol.CoreOrderType orderType() { return intent.orderType(); }
    /** 返回原下单意图的有效期策略，如 GTC 或 IOC。 */
    public com.surprising.aeron.protocol.CoreTimeInForce timeInForce() { return intent.timeInForce(); }
    /** 返回原下单意图是否只允许成为挂单方。 */
    public boolean postOnly() { return intent.postOnly(); }
    public boolean hasIntent() { return intent != null; }
    /** 返回原下单意图的客户端订单标识。 */
    public String clientOrderId() { return intent == null ? "" : intent.clientOrderId(); }
}

/** 单笔下单与改单准入事件共用的 Lane 结果：完成位为 volatile，Owner 读到完成后才读取拒绝和平仓容量撤单。 */
abstract static class OrderCheckEvent extends ResolvedPlaceOrder {
    long openInterestSteps;

    /** Lane 捕获的业务拒绝、算术或参数异常，完成后由 Owner 或 Matcher 读取。 */
    RuntimeException rejection;

    List<Long> closeCapacityCancellations = List.of();

    volatile boolean completed;

    public void bindOpenInterest(long openInterestSteps) {
        this.openInterestSteps = openInterestSteps;
    }

    public long openInterestSteps() {
        return openInterestSteps;
    }

    public boolean complete() {
        return completed;
    }

    public RuntimeException rejection() {
        if (!completed) throw new IllegalStateException("admission is incomplete");
        return rejection;
    }

    public List<Long> closeCapacityCancellations() {
        if (!completed) throw new IllegalStateException("admission is incomplete");
        return rejection == null ? closeCapacityCancellations : List.of();
    }

    void clearBaseOrderCheck() {
        clearResolvedOrder();
        openInterestSteps = 0;
        rejection = null;
        closeCapacityCancellations = List.of();
        completed = false;
    }
}

public static final class PlaceOrderCheckEvent extends OrderCheckEvent implements AccountLaneCommand {
    @Accessors(fluent = true, chain = false)
    @Getter
    private long coreSequence;
    /** position：命令的集群日志位置，用于订单状态和后续确定性提交。 */
    private long timestamp, position;
    /** 下单用户编号，只能由其所属账户 Lane 处理。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private long userId;
    private UUID commandId;
    /** Owner 固定并借用的不可变账本事实，供 Lane 检查资金费及到期结算冲突。 */
    private SystemFunds.SettlementStatus settlementStatus;
    @Accessors(fluent = true, chain = false)
    @Getter
    private int assetId;
    @Accessors(fluent = true, chain = false)
    @Getter
    private int laneId;
    private AccountBook accountBook;
    private IdIndex identities;
    /** Owner 绑定的本命令价格事实，Lane 据此完成价格决策。 */
    private com.surprising.aeron.service.market.MarkPrice decisionMark;
    private FeePolicyBook decisionPolicies;
    /** Owner 固定的决策业务时间，价格鲜度、费率及到期检查使用此值。 */
    private long decisionTimestamp;
    /** Matcher 已读取或明确取消等待的发布位，可见后 Matcher 不得继续访问事件字段。 */
    private volatile boolean matcherConsumed;

    /** Owner 先绑定意图和币对路由，再保存价格、费率表及决策时间引用。 */
    public void prepareDecision(com.surprising.aeron.protocol.PlaceOrderCommand intent,
            com.surprising.aeron.service.instrument.CoreInstrument instrument, int symbolId,
            com.surprising.aeron.service.market.MarkPrice mark, FeePolicyBook policies, long timestamp) {
        if (policies == null || timestamp <= 0) throw new IllegalArgumentException("invalid place decision input");
        initializeOrderIntent(intent, instrument, symbolId);
        decisionMark = mark;
        decisionPolicies = policies;
        decisionTimestamp = timestamp;
    }

    /** 校验已绑定路由并固定命令、用户、资产、目标 Lane 和账本事实，重置回执及消费标记，按产品规则决定是否等待 OI。 */
    public OrderCommand.PlaceOrderCheckEvent prepare(long coreSequence, long userId, UUID commandId,
                                SystemFunds.SettlementStatus settlementStatus,
                                int assetId, int laneId,
                                AccountBook accountBook, IdIndex identities,
                                long timestamp, long position) {
        if (timestamp < 0 || position < 0 || coreSequence <= 0 || userId <= 0
                || intent() == null || commandId == null
                || symbolId() < 0 || assetId < 0 || settlementStatus == null
                || laneId < 0 || accountBook == null) {
            throw new IllegalArgumentException("invalid place admission event");
        }
        this.coreSequence = coreSequence;
        this.timestamp = timestamp;
        this.position = position;
        this.userId = userId;
        this.commandId = commandId;
        openInterestSteps = 0;
        this.settlementStatus = settlementStatus;
        this.identities = identities;
        this.assetId = assetId;
        this.laneId = laneId;
        this.accountBook = accountBook;
        rejection = null;
        closeCapacityCancellations = List.of();
        completed = false;
        matcherConsumed = false;
        return this;
    }

    /** 只有 Lane 已完成且需要的 Matcher 回执已经消费，才清理决策、引用和标记供池化复用。 */
    @Override
    public void clear() {
        if (!complete()) throw new IllegalStateException("cannot recycle an incomplete place admission");
        if (!matcherConsumed) {
            throw new IllegalStateException("cannot recycle a place admission before matcher handoff");
        }
        clearBaseOrderCheck();
        commandId = null;
        settlementStatus = null;
        accountBook = null;
        identities = null;
        decisionMark = null;
        decisionPolicies = null;
        decisionTimestamp = 0;
        matcherConsumed = false;
    }

    /** 在目标 Lane 等账本输入就绪后，补决策、准备客户身份、计算预留和前置撤单，再写订单及冻结。可预期拒绝回退客户身份，最后记录分配计数并发布回执。 */
    public void execute(AccountPartition lane) {
        if (lane.laneId() != accountBook.topology().accountLaneId(userId)) {
            throw new IllegalStateException("place admission reached the wrong Account Lane");
        }
        long preparedClientKey = 0;
        try {
            accountBook.enterLaneCommandScope(lane);
            try {
                boolean lifecycleSettled = settlementStatus.lifecycleSettled();
                boolean fundingInProgress = settlementStatus.fundingInProgress();
                if (!resolvedOrderInitialized())
                    OrderCheckService.resolvePrepared(
                            this, decisionMark, decisionPolicies, userId, decisionTimestamp, lifecycleSettled);
                preparedClientKey = IdIndex.clientKeyValue(
                        identities.prepareClientKeyInLane(lane, userId, clientOrderId()));
                long positionKey = identities.findPositionKeyValueInLane(
                        lane, userId, instrument(), positionSide());
                int symbolId = symbolId();
                long requiredReservation = OrderCheckService.requiredReservationPrepared(
                        accountBook, lane, userId, this, openInterestSteps,
                        preparedClientKey, positionKey,
                        lifecycleSettled, fundingInProgress);
                Position currentPosition = positionKey == 0 ? null : lane.position(positionKey);
                if (instrument().contractType().productLine().isDerivative() && !reduceOnly()
                        && currentPosition != null && currentPosition.signedQuantitySteps() != 0
                        && (currentPosition.signedQuantitySteps() > 0)
                        != (side() == com.surprising.aeron.protocol.CoreOrderSide.BUY)) {
                    closeCapacityCancellations = Position.CloseCapacity.conflictsFor(
                            lane, userId, symbolId, positionKey, positionSide(), side(), orderId(), quantitySteps());
                }
                closeCapacityCancellations = lane.preMatchingCancellations(userId, symbolId,
                        side(), matchingPriceTicks(), orderId(), orderId(), closeCapacityCancellations);
                accountBook.placeOrderInLane(lane, userId, this, commandId,
                        requiredReservation, preparedClientKey, symbolId, assetId, coreSequence, null, timestamp, position);
            } finally {
                accountBook.exitLaneCommandScope(lane);
            }
        } catch (CoreStateRejectedException | ArithmeticException | IllegalArgumentException failure) {
            identities.rollbackClientKeyInLane(lane, userId, clientOrderId(), preparedClientKey);
            rejection = failure;
        }
        AccountBook completionRuntime = accountBook;
        lane.recordCommandOperation();
        completed = true;
        completionRuntime.publishPlaceOrderCheckReady(laneId, coreSequence);
    }

    /** 清理从未正式交给账户 Lane 的已准备事件。Lane 已完成时必须走对应交接路径，不能直接丢弃。 */
    public void discard() {
        if (complete()) throw new IllegalStateException("completed place admission must be collected");
        clearBaseOrderCheck();
        commandId = null;
        settlementStatus = null;
        accountBook = null;
        identities = null;
        decisionMark = null;
        decisionPolicies = null;
        decisionTimestamp = 0;
        coreSequence = 0;
        userId = 0;
        laneId = 0;
        matcherConsumed = false;
    }

    /** Matcher 最多等待三十秒取得 Lane 回执，区分成功、业务拒绝、溢出及非法参数并填入原结算事件。 */
    public void awaitMatcherReceipt(TradeSettlement target) {
        if (target == null) {
            throw new IllegalArgumentException("place admission target is required");
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!completed) {
            if (System.nanoTime() >= deadline) {
                throw new IllegalStateException("timed out waiting for place admission sequence="
                        + coreSequence + " lane=" + laneId);
            }
            Thread.onSpinWait();
            if (Thread.currentThread().isInterrupted()) {
                throw new IllegalStateException("place admission wait was interrupted");
            }
        }
        CoreResultCode resultCode = rejection == null ? CoreResultCode.NONE
                : rejection instanceof CoreStateRejectedException stateRejected
                ? CoreResultCode.fromRejectionCode(stateRejected.code())
                : rejection instanceof ArithmeticException ? CoreResultCode.ARITHMETIC_OVERFLOW
                : CoreResultCode.INVALID_COMMAND;
        target.orderCheckReceipt(orderId(), rejection == null, resultCode.wireCode());
        if (rejection == null) target.addOrderCheckCancellations(closeCapacityCancellations);
        // Owner 可能立即清空池化事件。
        // 消费位可见后 Matcher 只使用已捕获引用，不再读取事件字段。
        // 消费位发布后，Owner 可以回收本事件，不再访问事件字段。
        matcherConsumed = true;
    }

    /** 读取实际消费发布位。 */
    public boolean matcherConsumed() { return matcherConsumed; }

    /** 已完成的准入不再需要进入 Matcher 等待路径时，显式发布消费完成。 */
    public void cancelMatcherWait() {
        if (!complete() || matcherConsumed) {
            throw new IllegalStateException("invalid matcher admission cancellation");
        }
        matcherConsumed = true;
    }

    /** 只有完整决策已完成且准入成功才交出本对象作为订单输入，不能把半成品或拒单当获准订单。 */
    public OrderCommand.ResolvedPlaceOrder resolvedOrder() {
        if (!complete() || !resolvedOrderInitialized() || rejection != null) {
            throw new IllegalStateException("place admission is not accepted");
        }
        return this;
    }

    /** 返回已绑定的路由输入，供 Owner 预构建结算或 Matcher 借用。读取 Lane 补齐的价格和费率前仍必须等准入回执。 */
    public OrderCommand.ResolvedPlaceOrder matchingOrder() {
        if (intent() == null) throw new IllegalStateException("place admission is not prepared");
        return this;
    }
}

public static final class ReplaceOrderCheckEvent extends OrderCheckEvent implements AccountLaneCommand {
    private long sequence, userId, originalOrderId;
    /** Owner 固定的 Matcher 分片，完成通知按此唤醒原撮合续步。 */
    private int matcherShard;
    private AmendOrderCommand amend;
    private ReplaceOrderCommand replace;
    /** Owner 固定的币对决策输入，Lane 读取后写入本事件继承的解析字段。 */
    private OrderCheckService.Context decision;
    private AccountBook accountBook;
    private IdIndex identities;
    private OrderCommand.ReplaceOrderCheck orderCheck;

    /** 要求改单和替换意图恰好提供一种，绑定原订单、用户、配置和 Matcher 分片，重置结果并按产品判断是否等待账本 OI。 */
    public OrderCommand.ReplaceOrderCheckEvent prepare(long sequence, long userId, long originalOrderId,
            AmendOrderCommand amend, ReplaceOrderCommand replace,
            OrderCheckService.Context decision, int matcherShard,
            AccountBook accountBook, IdIndex identities) {
        if (sequence <= 0 || userId <= 0 || originalOrderId <= 0 || (amend == null) == (replace == null)
                || decision == null || accountBook == null || identities == null
                || matcherShard < 0 || matcherShard >= accountBook.topology().matchingEngineCount())
            throw new IllegalArgumentException("invalid replacement admission");
        this.sequence = sequence; this.userId = userId; this.originalOrderId = originalOrderId;
        this.amend = amend; this.replace = replace; this.decision = decision;
        this.matcherShard = matcherShard; openInterestSteps = 0; this.accountBook = accountBook; this.identities = identities;
        orderCheck = null; rejection = null; completed = false; closeCapacityCancellations = List.of();
        return this;
    }

    /** 在原单所属 Lane 核对原单存在、用户、OPEN 状态及可改单类型，解析新意图并排除原单占用计算预留。 */
    @Override public void execute(AccountPartition lane) {
        AccountBook completionRuntime = accountBook;
        int completionShard = matcherShard;
        int completionLane = lane.laneId();
        if (completionLane != accountBook.topology().accountLaneId(userId))
            throw new IllegalStateException("replacement admission crossed Account Lane");
        accountBook.enterLaneCommandScope(lane);
        try {
            Order original = lane.order(originalOrderId);
            if (original == null) throw rejected("ORDER_NOT_FOUND", "order does not exist");
            if (original.userId() != userId) throw rejected("ORDER_OWNER_MISMATCH", "order belongs to another user");
            if (original.status() != CoreOrderStatus.OPEN) throw rejected("INVALID_COMMAND", "order is not replaceable");
            if (amend != null && original.orderType() != CoreOrderType.LIMIT)
                throw rejected("INVALID_COMMAND", "order is not amendable");
            PlaceOrderCommand command = replace == null ? amendCommand(original) : replace.replacement();
            OrderCheckService.resolveInto(this, decision, userId, command);
            long positionKey = identities.findPositionKeyValueInLane(lane, userId, instrument(), positionSide());
            long required = OrderCheckService.requiredReplacementReservationPrepared(
                    accountBook, lane, userId, this, openInterestSteps,
                    original, identities.findClientKeyValueInLane(lane, userId, clientOrderId()), positionKey,
                    decision.lifecycleSettled(), decision.fundingInProgress());
            if (amend != null && accountBook.productLine() == com.surprising.product.api.ProductLine.SPOT) {
                var reservation = lane.reservation(originalOrderId);
                if (reservation == null || !reservationAsset().equals(identities.asset(reservation.assetId())))
                    throw new IllegalStateException("spot amend original reservation is missing or mismatched");
                var balance = accountBook.balance(userId, reservation.assetId());
                if (balance == null || required > Math.addExact(balance.availableUnits(), reservation.reservedUnits()))
                    throw rejected("INSUFFICIENT_AVAILABLE_BALANCE", "available balance is insufficient for amended order");
            }
            Position position = positionKey == 0 ? null : lane.position(positionKey);
            if (instrument().contractType().productLine().isDerivative() && !reduceOnly()
                    && position != null && position.signedQuantitySteps() != 0
                    && (position.signedQuantitySteps() > 0)
                    != (side() == com.surprising.aeron.protocol.CoreOrderSide.BUY)) {
                closeCapacityCancellations = Position.CloseCapacity.conflictsFor(
                        lane, userId, symbolId(), positionKey, positionSide(), side(), originalOrderId, quantitySteps());
            }
            closeCapacityCancellations = lane.preMatchingCancellations(userId, symbolId(),
                    side(), matchingPriceTicks(), originalOrderId, orderId(), closeCapacityCancellations);
            var user = lane.user(userId);
            orderCheck = new OrderCommand.ReplaceOrderCheck(userId, originalOrderId, original.revision(),
                    user == null ? 0 : user.revision(), command, this, required);
        } catch (CoreStateRejectedException | ArithmeticException | IllegalArgumentException failure) {
            rejection = failure;
        } finally { accountBook.exitLaneCommandScope(lane); }
        // 完成位可见后 Owner 可能推进回收或复用。
        // 通知目标和分片必须提前捕获，发布后不再读取事件字段。
        lane.recordCommandOperation();
        completed = true;
        completionRuntime.publishAccountOrderCheckReadyShard(completionShard);
    }

    private PlaceOrderCommand amendCommand(Order original) {
        return new PlaceOrderCommand(amend.replacementOrderId(), decision.instrument().instrumentId(),
                original.side(), amend.hasPriceTicks() ? amend.rawPriceTicks() : original.priceTicks(),
                amend.hasQuantitySteps() ? amend.rawQuantitySteps() : original.remainingQuantitySteps(),
                original.reduceOnly(), original.marginMode(), original.positionSide(), original.orderType(),
                amend.timeInForce() == null ? original.timeInForce() : amend.timeInForce(),
                amend.hasPostOnly() ? amend.isPostOnly() : original.postOnly(),
                amend.newClientOrderId() == null ? "" : amend.newClientOrderId());
    }

    /** 返回本次改单命令的 Core 序号，准入结果和后续撮合沿用此序号。 */
    public long coreSequence() { return sequence; }
    /** 完成后返回新单、原单版本和账户版本组成的准入结果。 */
    public OrderCommand.ReplaceOrderCheck orderCheck() { requireComplete(); return orderCheck; }

    /** 要求 Lane 已完成后清理事件。关联命令仍需先结束对解析输入的使用，不能因看到完成位就提前复用。 */
    @Override public void clear() {
        requireComplete();
        discard();
    }

    /** 清理意图、决策和结果引用并重置标记，供未投递清理或终态回收使用。 */
    public void discard() {
        clearBaseOrderCheck();
        amend = null; replace = null; decision = null; accountBook = null; identities = null;
        orderCheck = null;
    }

    private void requireComplete() {
        if (!completed) throw new IllegalStateException("replacement admission is incomplete");
    }
    private static CoreStateRejectedException rejected(String code, String message) {
        return new CoreStateRejectedException(code, message);
    }
}

public record ReplaceOrderCheck(long userId, long originalOrderId, long originalOrderRevision,
                                 long userRevision, PlaceOrderCommand command,
                                 OrderCommand.ResolvedPlaceOrder resolved,
                                 long requiredReservationUnits) {

    public ReplaceOrderCheck {
        if (userId <= 0 || originalOrderId <= 0 || originalOrderRevision < 0 || userRevision < 0
                || command == null || resolved == null
                || requiredReservationUnits < 0 || command.orderId() != resolved.orderId()) {
            throw new IllegalArgumentException("invalid resolved matching admission");
        }
    }

}

public interface ResultTarget {
    /** 返回本命令需要填充的结果槽数量。 */
    int resultCount();
    /** 返回指定结果槽主要查询的新单或当前订单编号。 */
    long resultOrderId(int index);
    /** 返回指定槽对应的原订单编号，没有改单或替换原单时可为 0。 */
    long resultOriginalOrderId(int index);
    /** 由所属 Lane 把该槽的最终订单及规范币对文本交给原命令捕获，订单已移除时允许为空。 */
    void resultOrder(int index, Order order, String instrumentId);

    /** 默认不额外读取无变化结果。 */
    default boolean captureUnchangedResults() { return false; }

    /** 判断该槽是否已由变化捕获填好，默认 false 让需要补读的实现自行决定。 */
    default boolean resultPrepared(int index) { return false; }

    /** 在最终 Lane 结果捕获后准备原命令响应，必须先于完成回执发布。 */
    void prepareResponse();

    /** 可选地记录当前批次项 Matcher 接受或拒绝结果，默认不额外存状态，原发布流程在最终释放边界前调用。 */
    default void matcherResult(int index, boolean accepted) { }

    /** 响应准备完成后交出后备字节数组，Owner 按原租约消费并结合逻辑长度读取有效前缀。 */
    byte[] preparedResponse();

    /** 返回已准备响应的有效字节长度，可能小于固定后备数组容量。 */
    int preparedResponseLength();

    /** 默认只沿用 Lane 中仍保留的订单。 */
    default boolean includeTerminalAfterImage() { return false; }

    /** 先处理本次变更命中的结果槽，按目标策略选 Lane 当前订单或变化缓冲终态后值，新编号缺失时再取原单。 */
    static void capture(OrderCommand.ResultTarget target, AccountPartitionCommit.Delta changes,
                        IdIndex identities, AccountPartition lane) {
        for (int i = 0; i < target.resultCount(); i++) {
            long id = target.resultOrderId(i);
            long original = target.resultOriginalOrderId(i);
            if (!changes.orders().containsKey(id) && (original == 0 || !changes.orders().containsKey(original))) continue;
            Order order = lane.order(id);
            if (target.includeTerminalAfterImage() && changes.orders().containsKey(id))
                order = changes.orders().get(id);
            if (order == null && original > 0) {
                order = lane.order(original);
                if (target.includeTerminalAfterImage() && changes.orders().containsKey(original))
                    order = changes.orders().get(original);
            }
            target.resultOrder(i, order, order == null ? null : identities.instrumentId(order.symbolId()));
        }
        if (target.captureUnchangedResults()) captureUnchanged(target, identities, lane);
        target.prepareResponse();
    }

    /** 仅补读尚未准备的结果槽，从所属 Lane 查询新编号或原单，不查询全局发布表。 */
    static void captureUnchanged(OrderCommand.ResultTarget target,
                                 IdIndex identities, AccountPartition lane) {
        for (int index = 0; index < target.resultCount(); index++) {
            if (target.resultPrepared(index)) continue;
            Order order = lane.order(target.resultOrderId(index));
            if (order == null) {
                long original = target.resultOriginalOrderId(index);
                if (original > 0) order = lane.order(original);
            }
            target.resultOrder(index, order, order == null ? null : identities.instrumentId(order.symbolId()));
        }
    }

    /** 顺序批次结束后重新读取全部结果槽的 Lane 最终状态，纳入后续项对前面订单的影响，再准备整批响应。 */
    static void captureFinal(OrderCommand.ResultTarget target,
                             IdIndex identities, AccountPartition lane) {
        for (int index = 0; index < target.resultCount(); index++) {
            Order order = lane.order(target.resultOrderId(index));
            long original = target.resultOriginalOrderId(index);
            if (order == null && original > 0) order = lane.order(original);
            target.resultOrder(index, order, order == null ? null : identities.instrumentId(order.symbolId()));
        }
        target.prepareResponse();
    }
}

}
