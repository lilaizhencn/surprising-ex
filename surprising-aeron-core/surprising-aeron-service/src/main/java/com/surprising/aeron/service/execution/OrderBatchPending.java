package com.surprising.aeron.service.execution;

import com.surprising.aeron.service.matching.CoreMatchingResult;
import com.surprising.aeron.protocol.CoreOrderStateView;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.protocol.TradingOrderBatchCodec;
import com.surprising.aeron.service.state.RuntimeIdentityRegistry;
import com.surprising.aeron.service.state.RuntimeProjectionPoint;
import com.surprising.aeron.service.state.OrderRuntime;
import com.surprising.aeron.service.state.ResolvedPlaceOrder;
import com.surprising.aeron.service.state.PlaceBatchAdmissionEvent;
import com.surprising.aeron.service.state.RuntimeTreasuryDelta;
import exchange.core2.core.common.MatcherEventType;
import exchange.core2.core.common.MatcherResult.MatcherEvent;
import com.surprising.aeron.service.matching.CoreCancellationResult;
import com.surprising.aeron.service.matching.CoreMatchingOrder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** 批量命令上下文；owner 准备，matcher/Lane 按完成边界交接，终态回收后复用。 */
final class OrderBatchPending implements TradingOrderBatchCodec.ResultSource {
    public int size() { return items.size(); }
    public long orderId(int index) { return items.get(index).orderId; }
    public long originalOrderId(int index) { return items.get(index).originalOrderId; }
    public long replacementOrderId(int index) { return items.get(index).replacementOrderId; }
    public ResponseStatus status(int index) { return items.get(index).status; }
    public CoreResultCode resultCode(int index) { return items.get(index).resultCode; }
    public com.surprising.aeron.protocol.CoreOrderStateView order(int index) { return items.get(index).resultOrder; }
    public int executionCount(int index) { return items.get(index).executionCount; }
    public void writeExecutions(int index, java.nio.ByteBuffer output) {
        OrderBatchItem item = items.get(index);
        for (MatcherEvent event : item.executionEvents) {
            if (event.eventType() != MatcherEventType.TRADE) continue;
            output.putLong(item.orderId).putLong(event.matchedOrderId())
                    .putLong(item.executionTakerUserId).putLong(event.matchedOrderUid())
                    .putLong(event.price()).putLong(event.size());
        }
    }

    /** 本批动作类型：下单、撤单或改单。 */
    OrderBatchKind kind;
    /** 本批逐项命令及结果，终态完成后统一清空。 */
    final ArrayList<OrderBatchItem> items;
    /** 本批开始前的完整提交点，用于失败恢复判断。 */
    RuntimeProjectionPoint beforeProjection;
    /** 本批开始前的状态 revision，限定回滚范围。 */
    long runtimeCheckpoint;
    /** 本批新增持仓身份的回滚起点。 */
    long positionIdentityCheckpoint;
    /** 命令对应的确定性集群时间。 */
    long clusterTimestamp;
    /** 命令对应的复制日志位置。 */
    long clusterPosition;
    /** 本次派发的业务操作，在对应执行完成之前保留。 */
    PendingMatching.Operation operation;
    /** 当前命令变化的用户 ID，保持 primitive 收集直到返回边界。 */
    final PrimitiveLongChangeSet changedUserIds;
    /** 当前命令变化的订单 ID，保持 primitive 收集直到返回边界。 */
    final PrimitiveLongChangeSet changedOrderIds;
    /** 本批实际改变运行时状态的订单，用于最终序号标记。 */
    final PrimitiveLongChangeSet runtimeChangedOrderIds;
    /** 当前批量项的变化 ID 临时集合，每项处理前清空。 */
    final PrimitiveLongChangeSet itemChangedOrderIds;
    /** 需要在安全结算阶段撤销的订单 ID。 */
    final PrimitiveLongChangeSet deferredCancellationOrderIds;
    /** 本批保留的撮合结果，终态清理时释放。 */
    final List<com.surprising.aeron.service.matching.CoreMatchingResult> matchingResults;
    /** 等待统一派发结算的订单 ID，按批量项顺序排列。 */
    final org.eclipse.collections.impl.list.mutable.primitive.LongArrayList
            deferredSettlementOrderIds = new org.eclipse.collections.impl.list.mutable.primitive.LongArrayList();
    /** 各待结算订单必须完成的账户 Lane 位图。 */
    final org.eclipse.collections.impl.list.mutable.primitive.LongArrayList
            deferredSettlementExpectedLaneMasks =
            new org.eclipse.collections.impl.list.mutable.primitive.LongArrayList();
    /** 与待结算订单一一对应的不可变撮合结果。 */
    final List<com.surprising.aeron.service.matching.CoreMatchingResult>
            deferredSettlementMatchingResults;
    /** 本批准入分配的客户单号，失败时按逆序回滚。 */
    final List<PreparedClientAllocation> preparedClientKeys;
    /** 本批已收集的国库变化，随最终提交合并。 */
    com.surprising.aeron.service.state.RuntimeTreasuryDelta treasuryDelta;
    /** 下一项待处理批量命令的位置，随批内执行前进，复用时归零。 */
    int nextIndex;
    /** 当前跨分片撤单切片的结束位置。 */
    int cancellationChunkEnd;
    /** 本批关联的全局命令序号。 */
    long sequence;
    /** 当前批量项撮合前必须先完成的撤单集合。 */
    List<Long> currentPreMatchingCancellationOrderIds = List.of();
    /** 本批是否已完成执行准备，避免重复建立准入上下文。 */
    boolean started;
    /** 批量提交的唯一阶段：等待、预派发或有序提交，禁止重复取得派发权。 */
    CommitStage commitStage = CommitStage.WAITING;
    /** Lane 准入结果是否已经收集完成。 */
    boolean admissionCollected;
    /** 本批撮合结果是否已核验并转成待结算变化。 */
    boolean matchingApplied;
    /** 本批币对是否已登记到在途依赖索引。 */
    boolean pipelineRegistered;
    /** 本批需要的最终 Lane 提交是否已完成。 */
    boolean laneCommitCompleted;
    /** 本批是否满足并采用批量并行准入路径。 */
    boolean pipelined;
    /** 本批是否必须逐项准入，以保持批内资金或订单依赖。 */
    boolean sequentialAdmission;
    /** matcher 写入的批量结果；完成交接后 owner 才读取。 */
    final List<com.surprising.aeron.service.matching.CoreMatchingResult> pipelinedMatchingResults;
    /** matcher 报告的批量失败，随完成通知交给 owner。 */
    Throwable pipelinedMatchingFailure;
    /** 本批派发到各 Lane 的结算事件；收集完成前不得复用。 */
    com.surprising.aeron.service.state.MatcherSettlementEvent[] settlementEvents;
    /** 本批撤单事件，完成通知后才能收集并回收。 */
    com.surprising.aeron.service.state.LaneCancelEvent cancelEvent;
    /** 本批准入事件；Lane 完成后由 owner 收集。 */
    PlaceBatchAdmissionEvent placeBatchAdmissionEvent;
    /** 本批预备的 Orders 缓冲；派发后必须等待完成交接才能清空或复用。 */
    final ResolvedPlaceOrder[] preparedOrders;
    /** 本批预备的 ClientKeyValues 缓冲；派发后必须等待完成交接才能清空或复用。 */
    final com.surprising.aeron.service.state.RuntimeIdentityRegistry.PreparedClientKey[]
            preparedClientKeyValues;
    /** 本批预备的 OpenInterestSteps 缓冲；派发后必须等待完成交接才能清空或复用。 */
    final long[] preparedOpenInterestSteps;
    /** 本批预备的 AdmissionIdentities 缓冲；派发后必须等待完成交接才能清空或复用。 */
    final com.surprising.aeron.service.state.RuntimeOrderAdmission.AdmissionIdentity[]
            preparedAdmissionIdentities;
    /** 本批预备的 SymbolIds 缓冲；派发后必须等待完成交接才能清空或复用。 */
    final int[] preparedSymbolIds;
    /** 本批预备的 AssetIds 缓冲；派发后必须等待完成交接才能清空或复用。 */
    final int[] preparedAssetIds;
    /** 本批预备的 MatchingOrders 缓冲；派发后必须等待完成交接才能清空或复用。 */
    final CoreMatchingOrder[] preparedMatchingOrders;
    /** 本批预备的 AdmittedOrders 缓冲；派发后必须等待完成交接才能清空或复用。 */
    final OrderRuntime[] preparedAdmittedOrders;
    /** 本批预备的 AdmittedReservations 缓冲；派发后必须等待完成交接才能清空或复用。 */
    final com.surprising.aeron.service.state.ReservationRuntime[] preparedAdmittedReservations;
    /** 本批预备的 Symbols 缓冲；派发后必须等待完成交接才能清空或复用。 */
    final ArrayList<String> preparedSymbols;
    /** 本批预备的 SymbolSet 缓冲；派发后必须等待完成交接才能清空或复用。 */
    final HashSet<String> preparedSymbolSet;
    /** 本批结算事件是否已收集，防止重复应用结果。 */
    boolean settlementsCollected;
    /** 本批撤单事件是否已收集，防止重复释放预留。 */
    boolean cancellationsCollected;
    /** 是否已进入终态收集范围，保证只激活一次资金上下文。 */
    boolean finishing;
    /** 该批的 Lane 完成要求是否已经登记。 */
    boolean laneContextInitialized;
    /** 本批实际涉及的账户 Lane，用于和预期范围核对。 */
    long actualLaneMask;
    /** 当前批最近完成的撮合结果，用于延续序号证据。 */
    com.surprising.aeron.service.matching.CoreMatchingResult lastMatchingResult;
    /** 本批准入订单增量索引，供后续批量项检查前面项目产生的订单依赖。 */
    BatchAdmissionOrderIndex admissionOrderIndex;

    enum CommitStage { WAITING, PREDISPATCHED, COMMITTING }

    boolean canPredispatch() { return commitStage == CommitStage.WAITING; }
    boolean commitStarted() { return commitStage == CommitStage.COMMITTING; }

    void markPredispatched() {
        if (!canPredispatch()) throw new IllegalStateException("batch Lane work already owned");
        commitStage = CommitStage.PREDISPATCHED;
    }

    void beginCommit() {
        if (commitStarted()) throw new IllegalStateException("batch commit already started");
        commitStage = CommitStage.COMMITTING;
    }

    OrderBatchPending(int requestedCapacity) {
        int capacity = Math.max(1, requestedCapacity);
        items = new ArrayList<>(capacity);
        changedUserIds = new PrimitiveLongChangeSet(capacity * 2);
        changedOrderIds = new PrimitiveLongChangeSet(capacity * 2);
        runtimeChangedOrderIds = new PrimitiveLongChangeSet(capacity * 2);
        itemChangedOrderIds = new PrimitiveLongChangeSet(capacity * 2);
        deferredCancellationOrderIds = new PrimitiveLongChangeSet(capacity);
        matchingResults = new ArrayList<>(capacity);
        deferredSettlementMatchingResults = new ArrayList<>(capacity);
        pipelinedMatchingResults = new ArrayList<>(capacity);
        preparedClientKeys = new ArrayList<>(capacity);
        preparedOrders = new ResolvedPlaceOrder[capacity];
        preparedClientKeyValues =
                new com.surprising.aeron.service.state.RuntimeIdentityRegistry.PreparedClientKey[capacity];
        preparedOpenInterestSteps = new long[capacity];
        preparedAdmissionIdentities =
                new com.surprising.aeron.service.state.RuntimeOrderAdmission.AdmissionIdentity[capacity];
        preparedSymbolIds = new int[capacity];
        preparedAssetIds = new int[capacity];
        preparedMatchingOrders = new CoreMatchingOrder[capacity];
        preparedAdmittedOrders = new OrderRuntime[capacity];
        preparedAdmittedReservations =
                new com.surprising.aeron.service.state.ReservationRuntime[capacity];
        preparedSymbols = new ArrayList<>(capacity);
        preparedSymbolSet = new HashSet<>(capacity);
    }

    OrderBatchPending initialize(OrderBatchKind kind, long clusterTimestamp,
                                         long clusterPosition, PendingMatching.Operation operation) {
        this.kind = java.util.Objects.requireNonNull(kind, "batch kind");
        this.clusterTimestamp = clusterTimestamp;
        this.clusterPosition = clusterPosition;
        this.operation = java.util.Objects.requireNonNull(operation, "batch operation");
        return this;
    }

    int capacity() {
        return preparedOrders.length;
    }

    void clear() {
        items.clear();
        beforeProjection = null;
        runtimeCheckpoint = 0;
        positionIdentityCheckpoint = 0;
        changedUserIds.clear();
        changedOrderIds.clear();
        runtimeChangedOrderIds.clear();
        itemChangedOrderIds.clear();
        deferredCancellationOrderIds.clear();
        matchingResults.clear();
        deferredSettlementOrderIds.clear();
        deferredSettlementExpectedLaneMasks.clear();
        deferredSettlementMatchingResults.clear();
        preparedClientKeys.clear();
        if (treasuryDelta != null) treasuryDelta.clear();
        treasuryDelta = null;
        nextIndex = 0;
        cancellationChunkEnd = 0;
        sequence = 0;
        currentPreMatchingCancellationOrderIds = List.of();
        started = false;
        commitStage = CommitStage.WAITING;
        admissionCollected = false;
        matchingApplied = false;
        pipelineRegistered = false;
        laneCommitCompleted = false;
        pipelined = false;
        sequentialAdmission = false;
        pipelinedMatchingResults.clear();
        pipelinedMatchingFailure = null;
        settlementEvents = null;
        cancelEvent = null;
        placeBatchAdmissionEvent = null;
        java.util.Arrays.fill(preparedOrders, null);
        java.util.Arrays.fill(preparedAdmissionIdentities, null);
        java.util.Arrays.fill(preparedClientKeyValues, null);
        java.util.Arrays.fill(preparedMatchingOrders, null);
        java.util.Arrays.fill(preparedAdmittedOrders, null);
        java.util.Arrays.fill(preparedAdmittedReservations, null);
        preparedSymbols.clear();
        preparedSymbolSet.clear();
        settlementsCollected = false;
        cancellationsCollected = false;
        finishing = false;
        laneContextInitialized = false;
        actualLaneMask = 0;
        lastMatchingResult = null;
        if (admissionOrderIndex != null) admissionOrderIndex.clear();
        kind = null;
        clusterTimestamp = 0;
        clusterPosition = 0;
        operation = null;
    }

    void retainMatchingResult(
            com.surprising.aeron.service.matching.CoreMatchingResult result) {
        if (result == null || result.nativeCommand().coreSequence() != sequence) {
            throw new IllegalArgumentException("invalid order batch matcher result");
        }
        matchingResults.add(result);
    }

    void mergeTreasuryDelta(
            com.surprising.aeron.service.state.RuntimeTreasuryDelta delta) {
        if (delta == null) throw new IllegalArgumentException("order batch Treasury delta is required");
        if (treasuryDelta == null) {
            treasuryDelta = new com.surprising.aeron.service.state.RuntimeTreasuryDelta(
                    com.surprising.aeron.service.state.RuntimeTreasuryDelta.ORDER_BATCH_CAPACITY);
        }
        treasuryDelta.merge(delta);
    }

    void retainPreparedClientKey(
            long userId, String clientOrderId,
            com.surprising.aeron.service.state.RuntimeIdentityRegistry.PreparedClientKey prepared) {
        if (prepared != null && prepared.allocated()) {
            preparedClientKeys.add(new PreparedClientAllocation(userId, clientOrderId, prepared));
        }
    }

    void rollbackPreparedClientKeys(
            com.surprising.aeron.service.state.RuntimeIdentityRegistry identities) {
        for (int index = preparedClientKeys.size() - 1; index >= 0; index--) {
            PreparedClientAllocation allocation = preparedClientKeys.get(index);
            identities.rollbackPreparedClientKey(allocation.userId(), allocation.clientOrderId(),
                    allocation.prepared());
        }
        preparedClientKeys.clear();
    }

    boolean hasPendingLaneWork() {
        return cancelEvent != null && !cancellationsCollected
                || settlementEvents != null && !settlementsCollected;
    }

    boolean laneWorkComplete() {
        if (cancelEvent != null && !cancellationsCollected && !cancelEvent.complete()) return false;
        if (settlementEvents != null && !settlementsCollected) {
            for (var event : settlementEvents) {
                if (event == null || !event.complete()) return false;
            }
        }
        return true;
    }

    void collectChangedOrderIds(
            OrderBatchItem item,
            com.surprising.aeron.service.matching.CoreMatchingResult matchingResult) {
        itemChangedOrderIds.clear();
        itemChangedOrderIds.add(item.orderId());
        if (item.originalOrderId() > 0) itemChangedOrderIds.add(item.originalOrderId());
        if (item.replacementOrderId() > 0) itemChangedOrderIds.add(item.replacementOrderId());
        for (MatcherEvent event : matchingResult.matcherEvents()) {
            if (event.eventType() == MatcherEventType.TRADE) {
                itemChangedOrderIds.add(event.matchedOrderId());
            }
        }
        for (CoreCancellationResult cancellation : matchingResult.cancellations()) {
            if (cancellation.accepted()) {
                itemChangedOrderIds.add(cancellation.orderId());
                runtimeChangedOrderIds.add(cancellation.orderId());
            }
        }
        if (kind == OrderBatchKind.PLACE || matchingResult.accepted()) {
            for (int index = 0; index < itemChangedOrderIds.size(); index++) {
                runtimeChangedOrderIds.add(itemChangedOrderIds.valueAt(index));
            }
        }
        changedOrderIds.addAll(itemChangedOrderIds);
    }
}
