package com.surprising.aeron.service.orchestration;
import com.surprising.aeron.service.command.order.DecodedMatchingCommand;
import com.surprising.aeron.service.command.order.OrderBatchKind;
import com.surprising.aeron.service.command.support.PrimitiveLongChangeSet;
import com.surprising.aeron.service.matching.CoreMatchingResult;
import com.surprising.aeron.service.matching.MatchingResult;
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
import java.util.ArrayList;
import java.util.List;

/** 批量命令上下文；owner 准备，matcher/Lane 按完成边界交接，终态回收后复用。 */
final class OrderBatchPending implements com.surprising.aeron.service.state.LaneOrderResultTarget, com.surprising.aeron.service.state.PlaceBatchIntentSource, com.surprising.aeron.service.state.SettlementBatchInput, TradingOrderBatchCodec.ResultSource, com.surprising.aeron.protocol.CoreOrderStateSource {
    public com.surprising.aeron.protocol.PlaceOrderCommand intent(int index) {
        return (com.surprising.aeron.protocol.PlaceOrderCommand) items.get(index).command;
    }
    public com.surprising.aeron.service.state.PlaceBatchIntentSource.Decision decision(int index) { return preparedDecisions[index]; }
    /** 每项只引用本批按币对共享的上下文，Lane 接管后填充准入输出。 */
    final com.surprising.aeron.service.state.PlaceBatchIntentSource.Decision[] preparedDecisions;
    public int resultCount() { return items.size(); }
    public long resultOrderId(int index) { return items.get(index).orderId(); }
    public long resultOriginalOrderId(int index) { return items.get(index).originalOrderId(); }
    public void resultOrder(int index, OrderRuntime order, String symbol) {
        var item = items.get(index);
        item.resultOrder = order;
        item.resultOrderSymbol = symbol;
        item.laneResultPrepared = true;
    }
    @Override public boolean captureUnchangedResults() {
        // Every batch result is resolved by its owning Lane. Terminal CANCEL/AMEND items still
        // omit the retired after-image because capture() only uses the live Lane map unless the
        // target explicitly opts into terminal images; unchanged/rejected items are resolved from
        // that same Lane instead of forcing the Owner to query its publication map.
        return true;
    }
    @Override public boolean resultPrepared(int index) {
        return items.get(index).laneResultPrepared;
    }
    @Override public void matcherResult(int index, boolean accepted) {
        int itemIndex = kind == OrderBatchKind.CANCEL ? cancellationChunkStart + index : index;
        OrderBatchItem item = items.get(itemIndex);
        item.status = accepted ? ResponseStatus.APPLIED : ResponseStatus.REJECTED;
        item.resultCode = accepted ? CoreResultCode.NONE : CoreResultCode.MATCHING_REJECTED;
    }
    /** 所属用户 Lane 编码的不可变响应；事件完成回执发布后才允许 Owner 读取。 */
    byte[] preparedResponse;
    int preparedResponseLength;
    private final ResponseArena responseArena;
    private ResponseArena.Slot preparedResponseSlot;
    /** Owner-only ordering links; never read by Matcher/Lane and detached before pool reuse. */
    OrderBatchPending previousBatch, nextBatch;

    @Override public void prepareResponse() {
        // 部分拒单、顺序改单可能没有本次 Lane 结果，仍需提交点补齐其查询语义。
        for (OrderBatchItem item : items) if (!item.laneResultPrepared || item.status == null) return;
        if (preparedResponseSlot != null) {
            responseArena.release(preparedResponseSlot);
            preparedResponseSlot = null;
        }
        if (responseArena == null) {
            preparedResponse = TradingOrderBatchCodec.encodeResultSource(this);
            preparedResponseLength = preparedResponse.length;
        } else {
            int length = TradingOrderBatchCodec.encodedResultSourceLength(this);
            preparedResponseSlot = responseArena.acquireExactSlot(length);
            preparedResponse = preparedResponseSlot.storage;
            preparedResponseLength = TradingOrderBatchCodec.encodeResultSourceInto(
                    this, preparedResponse, 0);
        }
        responseItem = null;
    }

    void transferPreparedResponseOwnership() {
        preparedResponseSlot = null;
    }

    boolean hasPreparedResponseSlot() {
        return preparedResponseSlot != null;
    }

    public int settlementCount() { return deferredSettlementCount; }
    public long settlementOrderId(int index) { return items.get(deferredSettlementItemIndexes[index]).orderId; }
    public OrderRuntime settlementOrder(int index) {
        return items.get(deferredSettlementItemIndexes[index]).admittedOrder;
    }
    public long settlementLaneMask(int index) { return items.get(deferredSettlementItemIndexes[index]).settlementLaneMask; }
    public MatchingResult settlementResult(int index) {
        return items.get(deferredSettlementItemIndexes[index]).matchingResult;
    }

    void deferSettlement(int itemIndex, long laneMask) {
        if (itemIndex < 0 || itemIndex >= items.size() || laneMask == 0
                || deferredSettlementCount == deferredSettlementItemIndexes.length) {
            throw new IllegalStateException("invalid deferred settlement item");
        }
        OrderBatchItem item = items.get(itemIndex);
        if (item.matchingResult == null || item.settlementLaneMask != 0)
            throw new IllegalStateException("deferred settlement result is missing or duplicated");
        item.settlementLaneMask = laneMask;
        deferredSettlementItemIndexes[deferredSettlementCount++] = itemIndex;
    }
    public int size() { return items.size(); }
    public long orderId(int index) { return items.get(index).orderId; }
    public long originalOrderId(int index) { return items.get(index).originalOrderId; }
    public long replacementOrderId(int index) { return items.get(index).replacementOrderId; }
    public ResponseStatus status(int index) { return items.get(index).status; }
    public CoreResultCode resultCode(int index) { return items.get(index).resultCode; }
    /** 仅编码器当前调用持有的复用游标，不跨线程或逃逸到响应缓存。 */
    private OrderBatchItem responseItem;
    public com.surprising.aeron.protocol.CoreOrderStateSource order(int index) {
        responseItem = items.get(index);
        return responseItem.resultOrder == null ? null : this;
    }
    public long orderId() { return responseItem.resultOrder.orderId(); }
    public com.surprising.product.api.ProductLine productLine() { return responseItem.resultOrder.productLine(); }
    public long userId() { return responseItem.resultOrder.userId(); }
    public String symbol() { return responseItem.resultOrderSymbol; }
    public com.surprising.aeron.protocol.CoreOrderSide side() { return responseItem.resultOrder.side(); }
    public long priceTicks() { return responseItem.resultOrder.priceTicks(); }
    public long quantitySteps() { return responseItem.resultOrder.quantitySteps(); }
    public long executedQuantitySteps() { return responseItem.resultOrder.executedQuantitySteps(); }
    public long remainingQuantitySteps() { return responseItem.resultOrder.remainingQuantitySteps(); }
    public boolean reduceOnly() { return responseItem.resultOrder.reduceOnly(); }
    public com.surprising.aeron.protocol.CoreMarginMode marginMode() { return responseItem.resultOrder.marginMode(); }
    public com.surprising.aeron.protocol.CorePositionSide positionSide() { return responseItem.resultOrder.positionSide(); }
    public com.surprising.aeron.protocol.CoreOrderType orderType() { return responseItem.resultOrder.orderType(); }
    public com.surprising.aeron.protocol.CoreTimeInForce timeInForce() { return responseItem.resultOrder.timeInForce(); }
    public boolean postOnly() { return responseItem.resultOrder.postOnly(); }
    public String clientOrderId() { return responseItem.resultOrder.clientOrderId(); }
    public java.util.UUID commandId() { return responseItem.resultOrder.commandId(); }
    public long makerFeeRatePpm() { return responseItem.resultOrder.makerFeeRatePpm(); }
    public long takerFeeRatePpm() { return responseItem.resultOrder.takerFeeRatePpm(); }
    public long cumulativeFeeUnits() { return responseItem.resultOrder.cumulativeFeeUnits(); }
    public long createdAtEpochMillis() { return responseItem.resultOrder.createdAtEpochMillis(); }
    public long updatedAtEpochMillis() { return responseItem.resultOrder.updatedAtEpochMillis(); }
    public long clusterPosition() { return responseItem.resultOrder.clusterPosition(); }
    public String status() { return responseItem.resultOrder.status().name(); }
    public long revision() { return responseItem.resultOrder.revision(); }

    public int executionCount(int index) { return items.get(index).executionCount; }
    public void writeExecutions(int index, java.nio.ByteBuffer output) {
        OrderBatchItem item = items.get(index);
        for (int eventIndex = 0; eventIndex < item.executionEvents.size(); eventIndex++) {
            MatcherEvent event = item.executionEvents.get(eventIndex);
            if (event.eventType() != MatcherEventType.TRADE) continue;
            output.putLong(item.orderId).putLong(event.matchedOrderId())
                    .putLong(item.executionTakerUserId).putLong(event.matchedOrderUid())
                    .putLong(event.price()).putLong(event.size());
        }
    }

    /** 本批动作类型：下单、撤单或改单。 */
    OrderBatchKind kind;
    /** 已解码输入及命令内稳定路由，提交后释放。 */
    DecodedMatchingCommand decodedCommand;
    /** 本批逐项命令及结果，终态完成后统一清空。 */
    final ArrayList<OrderBatchItem> items;
    /** 随批上下文复用的结果槽，生命周期覆盖所有 matcher/Lane 引用。 */
    private final OrderBatchItem[] itemSlots;
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
    CommandSlot.Operation operation;
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
    /** 待结算项在 items 中的索引；订单、Lane mask 和结果均由 OrderBatchItem 唯一持有。 */
    final int[] deferredSettlementItemIndexes;
    int deferredSettlementCount;
    /** 本批准入分配的客户单号，失败时按逆序回滚；数组槽位跨批复用，避免逐项 record 分配。 */
    final long[] preparedClientUsers;
    final String[] preparedClientIds;
    final com.surprising.aeron.service.state.RuntimeIdentityRegistry.PreparedClientKey[] preparedClientKeys;
    int preparedClientKeyCount;
    /** 本批已收集的国库变化，随最终提交合并。 */
    com.surprising.aeron.service.state.RuntimeTreasuryDelta treasuryDelta;
    /** 下一项待处理批量命令的位置，随批内执行前进，复用时归零。 */
    int nextIndex;
    /** 撮合结果接收时累计，终态编码不重复汇总。 */
    long tradeCount;
    /** 当前跨分片撤单切片的起止位置。 */
    int cancellationChunkStart;
    int cancellationChunkEnd;
    /** 本批关联的全局命令序号。 */
    long sequence;
    /** 当前批量项撮合前必须先完成的撤单集合。 */
    List<Long> currentPreMatchingCancellationOrderIds = List.of();
    /** 批量提交的唯一阶段：等待、预派发或有序提交，禁止重复取得派发权。 */
    private byte commitState;
    /** 批处理生命周期位图；用一个 primitive 状态取代多个相互独立的布尔字段。 */
    int lifecycleFlags;
    private static final int ADMISSION_COLLECTED = 1;
    private static final int MATCHING_APPLIED = 1 << 1;
    private static final int PIPELINE_REGISTERED = 1 << 2;
    private static final int LANE_COMMIT_COMPLETED = 1 << 3;
    private static final int SETTLEMENTS_COLLECTED = 1 << 4;
    private static final int CANCELLATIONS_COLLECTED = 1 << 5;
    private static final int FINISHING = 1 << 6;
    private static final int LANE_CONTEXT_INITIALIZED = 1 << 7;
    /** 本批已取得执行上下文；与其它生命周期位统一存放，避免独立布尔状态漂移。 */
    private static final int ACTIVATED = 1 << 8;
    /** 本批是否满足并采用批量并行准入路径。 */
    boolean pipelined;
    /** 本批是否必须逐项准入，以保持批内资金或订单依赖。 */
    boolean sequentialAdmission;
    /** Matcher 写入的批量结果；固定槽位在批上下文内复用，完成交接后 Owner 才读取。 */
    final CoreMatchingResult[] pipelinedMatchingResults;
    int pipelinedMatchingResultCount;
    /** matcher 报告的批量失败，随完成通知交给 owner。 */
    Throwable pipelinedMatchingFailure;
    /** 本批派发到各 Lane 的结算事件；收集完成前不得复用。 */
    com.surprising.aeron.service.state.MatcherSettlementEvent settlementEvent;
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
    /** 本批准入生命周期旗标；用 primitive 数组避免每项创建中间身份对象。 */
    final boolean[] preparedLifecycleSettled;
    final boolean[] preparedFundingInProgress;
    /** 本批预备的 SymbolIds 缓冲；派发后必须等待完成交接才能清空或复用。 */
    final int[] preparedSymbolIds;
    /** 本批预备的 AssetIds 缓冲；派发后必须等待完成交接才能清空或复用。 */
    final int[] preparedAssetIds;
    /** 本批预备的 AdmittedOrders 缓冲；派发后必须等待完成交接才能清空或复用。 */
    final OrderRuntime[] preparedAdmittedOrders;
    /** 本批预备的 Symbols 缓冲；派发后必须等待完成交接才能清空或复用。 */
    final ArrayList<String> preparedSymbols;
    /** 与 preparedSymbols 对齐的批内准入上下文；批次最多几十项，线性查找避免 Map 节点。 */
    final com.surprising.aeron.service.state.PlaceBatchIntentSource.Decision[] preparedContextDecisions;

    /** 本批实际涉及的账户 Lane，用于和预期范围核对。 */
    long actualLaneMask;
    /** 当前批最近完成的撮合结果，用于延续序号证据。 */
    // At most one sequential item is awaiting account publication; no copied item state.
    com.surprising.aeron.service.state.MatcherSettlementEvent itemSettlementEvent;
    com.surprising.aeron.service.command.order.ResolvedMatchingAdmission replacementAdmission;
    java.util.function.BooleanSupplier itemAdmission;
    long itemAdmissionRevision;
    com.surprising.aeron.service.state.LaneCommitEvent laneCommitEvent;
    MatchingResult lastMatchingResult;
    /** 本批准入订单增量索引，供后续批量项检查前面项目产生的订单依赖。 */
    BatchAdmissionOrderIndex admissionOrderIndex;

    private static final byte COMMIT_WAITING = 0;
    private static final byte COMMIT_PREDISPATCHED = 1;
    private static final byte COMMITTING = 2;

    boolean canPredispatch() { return commitState == COMMIT_WAITING; }
    boolean commitStarted() { return commitState == COMMITTING; }

    void markPredispatched() {
        if (!canPredispatch()) throw new IllegalStateException("batch Lane work already owned");
        commitState = COMMIT_PREDISPATCHED;
    }

    void beginCommit() {
        if (commitStarted()) throw new IllegalStateException("batch commit already started");
        commitState = COMMITTING;
    }

    private boolean has(int bit) { return (lifecycleFlags & bit) != 0; }
    private void set(int bit, boolean value) {
        if (value) lifecycleFlags |= bit; else lifecycleFlags &= ~bit;
    }
    boolean admissionCollected() { return has(ADMISSION_COLLECTED); }
    void admissionCollected(boolean value) { set(ADMISSION_COLLECTED, value); }
    boolean matchingApplied() { return has(MATCHING_APPLIED); }
    void matchingApplied(boolean value) { set(MATCHING_APPLIED, value); }
    boolean pipelineRegistered() { return has(PIPELINE_REGISTERED); }
    void pipelineRegistered(boolean value) { set(PIPELINE_REGISTERED, value); }
    boolean laneCommitCompleted() { return has(LANE_COMMIT_COMPLETED); }
    void laneCommitCompleted(boolean value) { set(LANE_COMMIT_COMPLETED, value); }
    boolean settlementsCollected() { return has(SETTLEMENTS_COLLECTED); }
    void settlementsCollected(boolean value) { set(SETTLEMENTS_COLLECTED, value); }
    boolean cancellationsCollected() { return has(CANCELLATIONS_COLLECTED); }
    void cancellationsCollected(boolean value) { set(CANCELLATIONS_COLLECTED, value); }
    boolean finishing() { return has(FINISHING); }
    void finishing(boolean value) { set(FINISHING, value); }
    boolean laneContextInitialized() { return has(LANE_CONTEXT_INITIALIZED); }
    void laneContextInitialized(boolean value) { set(LANE_CONTEXT_INITIALIZED, value); }
    boolean activated() { return has(ACTIVATED); }
    void activated(boolean value) { set(ACTIVATED, value); }

    OrderBatchPending(int requestedCapacity) {
        this(requestedCapacity, null);
    }

    OrderBatchPending(int requestedCapacity, ResponseArena responseArena) {
        int capacity = Math.max(1, requestedCapacity);
        this.responseArena = responseArena;
        items = new ArrayList<>(capacity);
        itemSlots = new OrderBatchItem[capacity];
        changedUserIds = new PrimitiveLongChangeSet(capacity * 2);
        changedOrderIds = new PrimitiveLongChangeSet(capacity * 2);
        runtimeChangedOrderIds = new PrimitiveLongChangeSet(capacity * 2);
        itemChangedOrderIds = new PrimitiveLongChangeSet(capacity * 2);
        deferredCancellationOrderIds = new PrimitiveLongChangeSet(capacity);
        deferredSettlementItemIndexes = new int[capacity];
        pipelinedMatchingResults = new CoreMatchingResult[capacity];
        preparedClientUsers = new long[capacity];
        preparedClientIds = new String[capacity];
        preparedClientKeys = new com.surprising.aeron.service.state.RuntimeIdentityRegistry.PreparedClientKey[capacity];
        preparedOrders = new ResolvedPlaceOrder[capacity];
        preparedDecisions = new com.surprising.aeron.service.state.PlaceBatchIntentSource.Decision[capacity];
        preparedClientKeyValues =
                new com.surprising.aeron.service.state.RuntimeIdentityRegistry.PreparedClientKey[capacity];
        preparedOpenInterestSteps = new long[capacity];
        preparedLifecycleSettled = new boolean[capacity];
        preparedFundingInProgress = new boolean[capacity];
        preparedSymbolIds = new int[capacity];
        preparedAssetIds = new int[capacity];
        preparedAdmittedOrders = new OrderRuntime[capacity];
        preparedSymbols = new ArrayList<>(capacity);
        preparedContextDecisions =
                new com.surprising.aeron.service.state.PlaceBatchIntentSource.Decision[capacity];
    }

    OrderBatchPending initialize(OrderBatchKind kind, long clusterTimestamp,
                                         long clusterPosition, CommandSlot.Operation operation) {
        this.kind = java.util.Objects.requireNonNull(kind, "batch kind");
        this.clusterTimestamp = clusterTimestamp;
        this.clusterPosition = clusterPosition;
        this.operation = java.util.Objects.requireNonNull(operation, "batch operation");
        return this;
    }

    int capacity() {
        return preparedOrders.length;
    }

    void addItem(long orderId, long originalOrderId, long replacementOrderId, Object command) {
        int index = items.size();
        OrderBatchItem item = itemSlots[index];
        if (item == null) itemSlots[index] = item = new OrderBatchItem(orderId, originalOrderId, replacementOrderId, command);
        else item.initialize(orderId, originalOrderId, replacementOrderId, command);
        items.add(item);
    }

    void clear() {
        int preparedCount = items.size();
        for (OrderBatchItem item : items) item.clear();
        items.clear();
        decodedCommand = null;
        beforeProjection = null;
        runtimeCheckpoint = 0;
        positionIdentityCheckpoint = 0;
        changedUserIds.clear();
        changedOrderIds.clear();
        runtimeChangedOrderIds.clear();
        itemChangedOrderIds.clear();
        deferredCancellationOrderIds.clear();
        java.util.Arrays.fill(deferredSettlementItemIndexes, 0, deferredSettlementCount, 0);
        deferredSettlementCount = 0;
        java.util.Arrays.fill(preparedClientIds, 0, preparedClientKeyCount, null);
        java.util.Arrays.fill(preparedClientKeys, 0, preparedClientKeyCount, null);
        preparedClientKeyCount = 0;
        if (treasuryDelta != null) treasuryDelta.clear();

        nextIndex = 0;
        tradeCount = 0;
        responseItem = null;
        if (preparedResponseSlot != null) responseArena.release(preparedResponseSlot);
        preparedResponseSlot = null;
        preparedResponse = null;
        preparedResponseLength = 0;
        cancellationChunkStart = cancellationChunkEnd = 0;
        sequence = 0;
        currentPreMatchingCancellationOrderIds = List.of();
        commitState = COMMIT_WAITING;
        pipelined = false;
        sequentialAdmission = false;
        java.util.Arrays.fill(pipelinedMatchingResults, 0, pipelinedMatchingResultCount, null);
        pipelinedMatchingResultCount = 0;
        pipelinedMatchingFailure = null;
        settlementEvent = null;
        cancelEvent = null;
        placeBatchAdmissionEvent = null;
        java.util.Arrays.fill(preparedOrders, 0, preparedCount, null);
        java.util.Arrays.fill(preparedDecisions, 0, preparedCount, null);
        java.util.Arrays.fill(preparedLifecycleSettled, 0, preparedCount, false);
        java.util.Arrays.fill(preparedFundingInProgress, 0, preparedCount, false);
        java.util.Arrays.fill(preparedClientKeyValues, 0, preparedCount, null);
        java.util.Arrays.fill(preparedAdmittedOrders, 0, preparedCount, null);
        java.util.Arrays.fill(preparedContextDecisions, 0, preparedSymbols.size(), null);
        preparedSymbols.clear();
        lifecycleFlags = 0;
        actualLaneMask = 0;
        lastMatchingResult = null;
        itemSettlementEvent = null;
        replacementAdmission = null;
        itemAdmission = null;
        itemAdmissionRevision = 0;
        laneCommitEvent = null;
        if (admissionOrderIndex != null) admissionOrderIndex.clear();
        kind = null;
        clusterTimestamp = 0;
        clusterPosition = 0;
        operation = null;
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
            if (preparedClientKeyCount >= preparedClientKeys.length) {
                throw new IllegalStateException("prepared client key capacity is exhausted");
            }
            preparedClientUsers[preparedClientKeyCount] = userId;
            preparedClientIds[preparedClientKeyCount] = clientOrderId;
            preparedClientKeys[preparedClientKeyCount++] = prepared;
        }
    }

    void rollbackPreparedClientKeys(
            com.surprising.aeron.service.state.RuntimeIdentityRegistry identities) {
        for (int index = preparedClientKeyCount - 1; index >= 0; index--) {
            identities.rollbackPreparedClientKey(preparedClientUsers[index], preparedClientIds[index],
                    preparedClientKeys[index]);
        }
        java.util.Arrays.fill(preparedClientIds, 0, preparedClientKeyCount, null);
        java.util.Arrays.fill(preparedClientKeys, 0, preparedClientKeyCount, null);
        preparedClientKeyCount = 0;
    }

    boolean hasPendingLaneWork() {
        return laneCommitEvent != null || cancelEvent != null && !cancellationsCollected()
                || settlementEvent != null && !settlementsCollected();
    }

    boolean laneWorkComplete() {
        if (itemSettlementEvent != null && !itemSettlementEvent.complete()) return false;
        if (laneCommitEvent != null && !laneCommitEvent.complete()) return false;
        if (cancelEvent != null && !cancellationsCollected() && !cancelEvent.complete()) return false;
        if (settlementEvent != null && !settlementsCollected() && !settlementEvent.complete()) return false;
        return true;
    }

    void collectChangedOrderIds(
            OrderBatchItem item,
            long takerUserId,
            MatchingResult matchingResult) {
        changedUserIds.add(takerUserId);
        itemChangedOrderIds.clear();
        itemChangedOrderIds.add(item.orderId());
        if (item.originalOrderId() > 0) itemChangedOrderIds.add(item.originalOrderId());
        if (item.replacementOrderId() > 0) itemChangedOrderIds.add(item.replacementOrderId());
        var matcherEvents = matchingResult.matcherEvents();
        for (int index = 0; index < matcherEvents.size(); index++) {
            MatcherEvent event = matcherEvents.get(index);
            if (event.eventType() == MatcherEventType.TRADE) {
                changedUserIds.add(event.matchedOrderUid());
                itemChangedOrderIds.add(event.matchedOrderId());
            }
        }
        var cancellations = matchingResult.cancellations();
        for (int index = 0; index < cancellations.size(); index++) {
            CoreCancellationResult cancellation = cancellations.get(index);
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

    void collectChangedOrderIds(OrderBatchItem item, long takerUserId,
                                exchange.core2.core.common.MatcherResult result) {
        changedUserIds.add(takerUserId);
        itemChangedOrderIds.clear();
        itemChangedOrderIds.add(item.orderId());
        if (item.originalOrderId() > 0) itemChangedOrderIds.add(item.originalOrderId());
        if (item.replacementOrderId() > 0) itemChangedOrderIds.add(item.replacementOrderId());
        for (int index = 0; index < result.events().size(); index++) {
            MatcherEvent event = result.events().get(index);
            if (event.eventType() == MatcherEventType.TRADE) {
                changedUserIds.add(event.matchedOrderUid());
                itemChangedOrderIds.add(event.matchedOrderId());
            }
        }
        boolean accepted = result.resultCode() == exchange.core2.core.common.cmd.CommandResultCode.SUCCESS
                || result.resultCode() == exchange.core2.core.common.cmd.CommandResultCode.ACCEPTED;
        if (kind == OrderBatchKind.PLACE || accepted) {
            for (int index = 0; index < itemChangedOrderIds.size(); index++)
                runtimeChangedOrderIds.add(itemChangedOrderIds.valueAt(index));
        }
        changedOrderIds.addAll(itemChangedOrderIds);
    }
}
