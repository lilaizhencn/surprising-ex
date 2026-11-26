package com.surprising.aeron.service.order;

import com.surprising.aeron.service.position.Position;

import com.surprising.aeron.exception.CoreStateRejectedException;
import com.surprising.aeron.exception.FatalMatchingDivergenceException;
import com.surprising.aeron.protocol.AmendOrderBatchCommand;
import com.surprising.aeron.protocol.AmendOrderCommand;
import com.surprising.aeron.protocol.CancelOrderBatchCommand;
import com.surprising.aeron.protocol.CancelOrderCommand;
import com.surprising.aeron.protocol.CommandFingerprint;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.PlaceOrderBatchCommand;
import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.protocol.TradingOrderBatchCodec;
import com.surprising.aeron.service.account.AccountPartition;
import com.surprising.aeron.service.account.AccountLaneCommand;
import com.surprising.aeron.service.account.AccountTransactionRollback;
import com.surprising.aeron.service.account.UserAccount;
import com.surprising.aeron.service.core.AccountBook;
import com.surprising.aeron.service.core.TradingCore;
import com.surprising.aeron.service.core.identity.IdIndex;
import com.surprising.aeron.service.matcher.OrderCancelResult;
import com.surprising.aeron.service.matcher.OrderMatchingResult;
import com.surprising.aeron.service.result.OrderSource;
import com.surprising.aeron.service.result.ResponseSlotPool;
import com.surprising.aeron.service.settlement.TradeSettlement;
import com.surprising.aeron.support.collection.PrimitiveLongChangeSet;
import com.surprising.product.api.ProductLine;
import exchange.core2.core.common.MatcherEventType;
import exchange.core2.core.common.MatcherResult.MatcherEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.IntFunction;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.experimental.Accessors;


public final class PendingOrderBatch implements OrderResultTarget, TradingOrderBatchCodec.ResultSource {
    public record Decision(com.surprising.aeron.service.order.OrderCheckService.Context context,
            long openInterestSteps, int baseAssetId, int quoteAssetId, int settleAssetId) { }
    /** 按批内下标读取 PLACE 输入。 */
    public com.surprising.aeron.protocol.PlaceOrderCommand intent(int index) {
        return (com.surprising.aeron.protocol.PlaceOrderCommand) items.get(index).command;
    }
    /** 借用该项已经准备的产品判断输入，账户准入前由 Owner 填写。 */
    public Decision decision(int index) { return preparedDecisions[index]; }
    final Decision[] preparedDecisions;

    /** 按有效下标取得本项可复用的解析订单槽，首次使用才创建。 */
    public ResolvedPlaceOrder resolvedOrderSlot(int index) {
        var slot = preparedOrders[index];
        if (slot == null) preparedOrders[index] = slot = new ResolvedPlaceOrder();
        return slot;
    }

    /** 把账户准入订单捕获到对应项的固定副本，返回该份可借用回执，整批退休前保留。 */
    public Order captureAdmittedOrder(int index, Order order) {
        OrderBatchItem item = items.get(index);
        item.captureAdmittedOrder(order);
        return item.admittedOrder;
    }
    /** 返回需要生成 Lane 结果的批内项数。 */
    public int resultCount() { return items.size(); }
    /** 返回指定项的结果订单编号。 */
    public long resultOrderId(int index) { return items.get(index).orderId(); }
    /** 返回指定项的改单原订单编号。 */
    public long resultOriginalOrderId(int index) { return items.get(index).originalOrderId(); }
    /** 捕获本项可空的写后订单并保存品种名称，最后标记该项结果已准备。 */
    public void resultOrder(int index, Order order, String instrumentId) {
        var item = items.get(index);
        item.captureResultOrder(order);
        item.resultOrderSymbol = instrumentId;
        item.laneResultPrepared = true;
    }
    /** 要求所属 Lane 也收集未变化或拒绝项，避免 Owner 另查发布表。 */
    @Override public boolean captureUnchangedResults() {
        // 各项由所属账户提供结果，未变化或拒绝项也如此，避免 Owner 再查发布表。
        return true;
    }
    /** 返回指定项是否已经取得所属 Lane 的结果。 */
    @Override public boolean resultPrepared(int index) {
        return items.get(index).laneResultPrepared;
    }
    /** 按 Matcher 接受与否填写单项状态。 */
    @Override public void matcherResult(int index, boolean accepted) {
        int itemIndex = kind == OrderBatchKind.CANCEL ? cancellationChunkStart + index : index;
        OrderBatchItem item = items.get(itemIndex);
        item.status = accepted ? ResponseStatus.APPLIED : ResponseStatus.REJECTED;
        item.resultCode = accepted ? CoreResultCode.NONE : CoreResultCode.MATCHING_REJECTED;
    }
    @Accessors(fluent = true, chain = false)
    @Getter(onMethod_ = {@Override})
    byte[] preparedResponse;
    @Accessors(fluent = true, chain = false)
    @Getter(onMethod_ = {@Override})
    int preparedResponseLength;
    private final ResponseSlotPool responseSlotPool;
    /** 本批尚未移交或归还的可空响应槽租约。 */
    private ResponseSlotPool.Slot preparedResponseSlot;
    /** previousBatch：仅由 Owner 访问的前一批排序链接，入池前由外层摘除。nextBatch：仅由 Owner 访问的后一批排序链接，Matcher 和账户不读取。 */
    PendingOrderBatch previousBatch, nextBatch;

    /** 只有全部项均有 Lane 结果和状态才重编码，未齐直接返回。先归还旧租约并清响应引用，再借响应槽编码。 */
    @Override public void prepareResponse() {
        for (OrderBatchItem item : items) if (!item.laneResultPrepared || item.status == null) return;
        if (preparedResponseSlot != null) {
            responseSlotPool.release(preparedResponseSlot);
            preparedResponseSlot = null;
        }
        preparedResponse = null;
        preparedResponseLength = 0;
        try {
            int length = TradingOrderBatchCodec.encodedResultSourceLength(this);
            preparedResponseSlot = responseSlotPool.acquireSlot(length);
            int encodedLength = TradingOrderBatchCodec.encodeResultSourceInto(
                    this, preparedResponseSlot.storage(), 0);
            preparedResponse = preparedResponseSlot.storage();
            preparedResponseLength = encodedLength;
        } catch (RuntimeException | Error failure) {
            if (preparedResponseSlot != null) responseSlotPool.release(preparedResponseSlot);
            preparedResponseSlot = null;
            throw failure;
        } finally {
            responseOrder.clear();
        }
    }

    /** 解除本批对响应槽租约的持有，由终态响应接管。字节引用保留到清批，不能再由本批归还同一槽。 */
    void transferPreparedResponseOwnership() {
        preparedResponseSlot = null;
    }

    /** 返回本批是否还持有需要释放或移交的响应槽租约。 */
    boolean hasPreparedResponseSlot() {
        return preparedResponseSlot != null;
    }

    /** 返回批内实际项数，供结果编码器读取。 */
    public int size() { return items.size(); }
    /** 返回指定项的结果订单编号，改单为新编号。 */
    public long orderId(int index) { return items.get(index).orderId; }
    /** 返回指定项的原单编号，非改单通常为零。 */
    public long originalOrderId(int index) { return items.get(index).originalOrderId; }
    /** 返回指定项的替换后编号，非改单通常为零。 */
    public long replacementOrderId(int index) { return items.get(index).replacementOrderId; }
    /** 返回指定项已决定的响应状态。 */
    public ResponseStatus status(int index) { return items.get(index).status; }
    /** 返回指定项的确定性结果码。 */
    public CoreResultCode resultCode(int index) { return items.get(index).resultCode; }
    private final OrderSource responseOrder = new OrderSource();

    public com.surprising.aeron.protocol.CoreOrderStateSource order(int index) {
        OrderBatchItem item = items.get(index);
        responseOrder.set(item.resultOrder, item.resultOrderSymbol);
        return item.resultOrder == null ? null : responseOrder;
    }

    /** 返回指定项已统计的成交事件数，编码器用它计算所需长度。 */
    public int executionCount(int index) { return items.get(index).executionCount; }
    /** 只编码指定项中的成交事件，按主动单、被动单、双方用户、价格和数量的固定顺序写入。 */
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

    @Accessors(fluent = true, chain = false)
    @Getter
    OrderBatchKind kind;
    DecodedOrderCommand decodedCommand;
    /** 各线程只能在约定交接边界访问对应输入或结果。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    final ArrayList<OrderBatchItem> items;
    private final OrderBatchItem[] itemSlots;
    long clusterTimestamp;
    long clusterPosition;
    /** 批次种类唯一确定撮合操作，不另存第二份类别。 */
    OrderCommand.Operation operation() {
        return switch (kind) {
            case PLACE -> OrderCommand.Operation.PLACE;
            case CANCEL -> OrderCommand.Operation.CANCEL;
            case AMEND -> OrderCommand.Operation.AMEND;
        };
    }
    final PrimitiveLongChangeSet changedUserIds;
    final PrimitiveLongChangeSet runtimeChangedOrderIds;
    @Accessors(fluent = true, chain = false)
    @Setter
    int nextIndex;
    long tradeCount;
    int cancellationChunkStart;
    int cancellationChunkEnd;
    long sequence;
    /** 当前子项撮合前必须先完成的撤单编号列表。 */
    List<Long> currentPreMatchingCancellationOrderIds = List.of();
    /** 等待、已预派发或有序提交的独占阶段，防止重复派发。 */
    private byte commitState;
    int lifecycleFlags;
    private static final int ADMISSION_COLLECTED = 1;
    private static final int MATCHING_APPLIED = 1 << 1;
    private static final int PIPELINE_REGISTERED = 1 << 2;
    private static final int LANE_COMMIT_COMPLETED = 1 << 3;
    private static final int SETTLEMENTS_COLLECTED = 1 << 4;
    private static final int FINISHING = 1 << 6;
    private static final int LANE_CONTEXT_INITIALIZED = 1 << 7;
    private static final int ACTIVATED = 1 << 8;
    @Accessors(fluent = true, chain = false)
    @Getter
    boolean pipelined;
    /** 本批是否必须按项串行准入，以保留资金及订单依赖。 */
    boolean sequentialOrderCheck;
    /** Matcher 执行流水批次时保存的失败，交 Owner 决定恢复处理。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    Throwable pipelinedMatchingFailure;
    @Accessors(fluent = true, chain = false)
    @Getter
    com.surprising.aeron.service.settlement.TradeSettlement settlementEvent;
    /** 整批下单账户准入事件，Owner 收集后回收。借用整批账户准入事件，Owner 收集回收后为空。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    PlaceBatchCheckEvent placeBatchOrderCheckEvent;
    /** 本批有效项的解析下单输入列，派发后不能提前清理。 */
    final ResolvedPlaceOrder[] preparedOrders;
    final long[] preparedClientKeyValues;
    final Order[] preparedAdmittedOrders;
    long[] preparedCancellationOrderIds;
    final ArrayList<String> preparedSymbols;
    final Decision[] preparedContextDecisions;

    long observedSettlementLaneMask;
    long actualLaneMask;
    // 顺序路径同一时刻只有一个子项等待账户发布，直接持有其事件，不复制执行状态。
    /** 当前唯一等待账户发布的顺序子项或撤单片段事件，不另复制一份执行状态。借用当前顺序子项或撤单片段的结算事件。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    com.surprising.aeron.service.settlement.TradeSettlement itemSettlementEvent;
    /** 当前顺序改单已经收集的解析准入。借用当前顺序改单的可空解析准入。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    com.surprising.aeron.service.order.ReplaceOrderCheck replacementOrderCheck;
    /** 当前顺序改单仍需保留的账户准入事件。 */
    com.surprising.aeron.service.order.ReplaceOrderCheckEvent replacementOrderCheckEvent;
    /** 当前顺序下单的账户预留续步。借用当前顺序下单的可空账户预留续步。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    java.util.function.BooleanSupplier itemOrderCheck;
    long itemOrderCheckRevision;
    @Accessors(fluent = true, chain = false)
    @Getter
    com.surprising.aeron.service.core.change.AccountPartitionCommit laneCommitEvent;
    @Accessors(fluent = true, chain = false)
    @Getter
    @Setter
    OrderMatchingResult lastMatchingResult;
    final com.surprising.aeron.service.matcher.OrderMatchingResult.Control noNativeCommandResult =
            new com.surprising.aeron.service.matcher.OrderMatchingResult.Control(true, "NO_NATIVE_COMMAND");
    /** 顺序下单共用的账户预留工作，上一项完成后才能准备下一项。 */
    final BatchPlaceReservation placeReservation = new BatchPlaceReservation();
    final OrderBatchProcessor.PipelinedPlaceBatchSubmission pipelinedPlaceBatchSubmission =
            new OrderBatchProcessor.PipelinedPlaceBatchSubmission();
    final OrderBatchProcessor.CancelBatchChunkSubmission cancelBatchChunkSubmission =
            new OrderBatchProcessor.CancelBatchChunkSubmission();

    private static final byte COMMIT_WAITING = 0;
    private static final byte COMMIT_PREDISPATCHED = 1;
    private static final byte COMMITTING = 2;

    /** 仅在尚未取得预派发或有序提交权时返回真。 */
    boolean canPredispatch() { return commitState == COMMIT_WAITING; }
    /** 返回是否已经进入本批有序提交阶段。 */
    public boolean commitStarted() { return commitState == COMMITTING; }

    /** 要求仍在等待阶段，再标为已预派发，防止重复取得账户工作派发权。 */
    void markPredispatched() {
        if (!canPredispatch()) throw new IllegalStateException("batch Lane work already owned");
        commitState = COMMIT_PREDISPATCHED;
    }

    /** 拒绝重复开始有序提交，将等待或预派发阶段转为提交阶段。 */
    void beginCommit() {
        if (commitStarted()) throw new IllegalStateException("batch commit already started");
        commitState = COMMITTING;
    }

    private boolean has(int bit) { return (lifecycleFlags & bit) != 0; }
    private void set(int bit, boolean value) {
        if (value) lifecycleFlags |= bit; else lifecycleFlags &= ~bit;
    }
    /** 读取生命周期位，说明Owner 是否已经登记整批准入的身份分配。 */
    boolean orderCheckCollected() { return has(ADMISSION_COLLECTED); }
    /** 只更新生命周期位，记录Owner 是否已经登记整批准入的身份分配。 */
    void orderCheckCollected(boolean value) { set(ADMISSION_COLLECTED, value); }
    /** 读取生命周期位，说明当前批次全部撮合输入是否已经应用。 */
    public boolean matchingApplied() { return has(MATCHING_APPLIED); }
    /** 只更新生命周期位，记录当前批次全部撮合输入是否已经应用。 */
    public void matchingApplied(boolean value) { set(MATCHING_APPLIED, value); }
    /** 读取生命周期位，说明是否已在品种依赖表登记本批。 */
    boolean pipelineRegistered() { return has(PIPELINE_REGISTERED); }
    /** 只更新生命周期位，记录是否已在品种依赖表登记本批。 */
    void pipelineRegistered(boolean value) { set(PIPELINE_REGISTERED, value); }
    /** 读取生命周期位，说明本批最终账户提交水位是否已经完成。 */
    boolean laneCommitCompleted() { return has(LANE_COMMIT_COMPLETED); }
    /** 只更新生命周期位，记录本批最终账户提交水位是否已经完成。 */
    void laneCommitCompleted(boolean value) { set(LANE_COMMIT_COMPLETED, value); }
    /** 读取生命周期位，说明整批结算事件是否已经收集。 */
    boolean settlementsCollected() { return has(SETTLEMENTS_COLLECTED); }
    /** 只更新生命周期位，记录整批结算事件是否已经收集。 */
    void settlementsCollected(boolean value) { set(SETTLEMENTS_COLLECTED, value); }
    /** 读取生命周期位，说明是否已进入整批最终收尾。 */
    boolean finishing() { return has(FINISHING); }
    /** 只更新生命周期位，记录是否已进入整批最终收尾。 */
    void finishing(boolean value) { set(FINISHING, value); }
    /** 读取生命周期位，说明是否已经为最终提交建立完整账户完成位图。 */
    boolean laneContextInitialized() { return has(LANE_CONTEXT_INITIALIZED); }
    /** 只更新生命周期位，记录是否已经为最终提交建立完整账户完成位图。 */
    void laneContextInitialized(boolean value) { set(LANE_CONTEXT_INITIALIZED, value); }
    /** 读取生命周期位，说明是否已经取得本批执行上下文。 */
    public boolean activated() { return has(ACTIVATED); }
    /** 只更新生命周期位，记录是否已经取得本批执行上下文。 */
    public void activated(boolean value) { set(ACTIVATED, value); }

    /** 容量至少取一，要求显式响应分配器，分配本批各列和项槽。 */
    public PendingOrderBatch(int requestedCapacity, ResponseSlotPool responseSlotPool) {
        int capacity = Math.max(1, requestedCapacity);
        this.responseSlotPool = java.util.Objects.requireNonNull(responseSlotPool, "response slot pool");
        items = new ArrayList<>(capacity);
        itemSlots = new OrderBatchItem[capacity];
        changedUserIds = new PrimitiveLongChangeSet(capacity * 2);
        runtimeChangedOrderIds = new PrimitiveLongChangeSet(capacity * 2);
        preparedOrders = new ResolvedPlaceOrder[capacity];
        preparedDecisions = new Decision[capacity];
        preparedClientKeyValues = new long[capacity];
        preparedAdmittedOrders = new Order[capacity];
        preparedCancellationOrderIds = new long[preparedAdmittedOrders.length];
        preparedSymbols = new ArrayList<>(capacity);
        preparedContextDecisions =
                new Decision[capacity];
    }

    /** 绑定非空批次种类及原日志时间位置。 */
    PendingOrderBatch initialize(OrderBatchKind kind, long clusterTimestamp,
                                         long clusterPosition) {
        this.kind = java.util.Objects.requireNonNull(kind, "batch kind");
        this.clusterTimestamp = clusterTimestamp;
        this.clusterPosition = clusterPosition;
        return this;
    }

    /** 返回当前预分配的项容量，不表示已经装入的项数。 */
    int capacity() {
        return preparedOrders.length;
    }

    /** 清空当前品种前缀的共享判断引用和品种列表。仅在准入交出前或整批退休后使用，不能清仍被账户借用的输入。 */
    void clearPreparedDecisionCache() {
        java.util.Arrays.fill(preparedContextDecisions, 0, preparedSymbols.size(), null);
        preparedSymbols.clear();
    }

    /** 使用当前项数作为下标，首次创建或复用该位置项槽，再追加到列表。容量由调用方预先保证，旧项必须已清理。 */
    public void addItem(long orderId, long originalOrderId, long replacementOrderId, Object command) {
        int index = items.size();
        OrderBatchItem item = itemSlots[index];
        if (item == null) itemSlots[index] = item = new OrderBatchItem(orderId, originalOrderId, replacementOrderId, command);
        else item.initialize(orderId, originalOrderId, replacementOrderId, command);
        items.add(item);
    }

    /** 在所有借用结束后清本批输入、结果、有效前缀和等待标记，归还仍由本批持有的响应槽，保留数组及工作对象容量。 */
    void clear() {
        int preparedCount = items.size();
        for (OrderBatchItem item : items) item.clear();
        items.clear();
        decodedCommand = null;
        changedUserIds.clear();
        runtimeChangedOrderIds.clear();

        nextIndex = 0;
        tradeCount = 0;
        responseOrder.clear();
        if (preparedResponseSlot != null) responseSlotPool.release(preparedResponseSlot);
        preparedResponseSlot = null;
        preparedResponse = null;
        preparedResponseLength = 0;
        cancellationChunkStart = cancellationChunkEnd = 0;
        sequence = 0;
        currentPreMatchingCancellationOrderIds = List.of();
        commitState = COMMIT_WAITING;
        pipelined = false;
        sequentialOrderCheck = false;
        pipelinedMatchingFailure = null;
        settlementEvent = null;
        placeBatchOrderCheckEvent = null;
        for (int index = 0; index < preparedCount; index++) {
            if (preparedOrders[index] != null) preparedOrders[index].clear();
        }
        java.util.Arrays.fill(preparedOrders, 0, preparedCount, null);
        java.util.Arrays.fill(preparedDecisions, 0, preparedCount, null);
        java.util.Arrays.fill(preparedClientKeyValues, 0, preparedCount, 0);
        java.util.Arrays.fill(preparedAdmittedOrders, 0, preparedCount, null);
        clearPreparedDecisionCache();
        lifecycleFlags = 0;
        observedSettlementLaneMask = 0;
        actualLaneMask = 0;
        lastMatchingResult = null;
        noNativeCommandResult.clearCoreSequence();
        itemSettlementEvent = null;
        replacementOrderCheck = null;
        replacementOrderCheckEvent = null;
        itemOrderCheck = null;
        itemOrderCheckRevision = 0;
        laneCommitEvent = null;
        placeReservation.clear();
        pipelinedPlaceBatchSubmission.clear();
        cancelBatchChunkSubmission.clear();
        kind = null;
        clusterTimestamp = 0;
        clusterPosition = 0;
    }

    /** 只检查最终 Lane 提交事件或尚未收集的整批结算事件。 */
    public boolean hasPendingLaneWork() {
        return laneCommitEvent != null
                || settlementEvent != null && !settlementsCollected();
    }

    /** 检查已有单项、最终提交及未收集整批事件是否都完成。 */
    public boolean laneWorkComplete() {
        if (itemSettlementEvent != null && !itemSettlementEvent.complete()) return false;
        if (laneCommitEvent != null && !laneCommitEvent.complete()) return false;
        if (settlementEvent != null && !settlementsCollected() && !settlementEvent.complete()) return false;
        return true;
    }

    /** 登记主动用户及成交 maker。 */
    void collectChangedOrderIds(
            OrderBatchItem item,
            long takerUserId,
            OrderMatchingResult matchingResult) {
        changedUserIds.add(takerUserId);
        boolean recordOrders = kind == OrderBatchKind.PLACE || matchingResult.accepted();
        if (recordOrders) {
            runtimeChangedOrderIds.add(item.orderId());
            if (item.originalOrderId() > 0) runtimeChangedOrderIds.add(item.originalOrderId());
            if (item.replacementOrderId() > 0) runtimeChangedOrderIds.add(item.replacementOrderId());
        }
        var matcherEvents = matchingResult.matcherEvents();
        for (int index = 0; index < matcherEvents.size(); index++) {
            MatcherEvent event = matcherEvents.get(index);
            if (event.eventType() == MatcherEventType.TRADE) {
                changedUserIds.add(event.matchedOrderUid());
                if (recordOrders) runtimeChangedOrderIds.add(event.matchedOrderId());
            }
        }
        var cancellations = matchingResult.cancellations();
        for (int index = 0; index < cancellations.size(); index++) {
            OrderCancelResult cancellation = cancellations.get(index);
            if (cancellation.accepted()) {
                runtimeChangedOrderIds.add(cancellation.orderId());
            }
        }
    }

    /** 返回实际批内项数，不是数组容量。 */
    public int itemCount() { return items.size(); }

    /** 顺序批量下单中一个当前项的准入和冻结任务。Owner 只推进原账本及控制邮箱，Lane 计算并冻结，订单租约保留在原批次槽。任务可复用，但必须等 Lane 释放并由控制调度器发布结果。 */
    public static final class BatchPlaceReservation extends ResolvedPlaceOrder implements IntFunction<Object>, BooleanSupplier {
        private com.surprising.aeron.service.core.AccountBook accountBook;
        private com.surprising.aeron.service.core.identity.IdIndex identities;
        private Decision decision;
        private long sequence, userId, openInterestSteps;
        private UUID commandId;
        private Order admittedOrder;
        private PendingOrderBatch source;
        private int sourceIndex;
        @Accessors(fluent = true, chain = false)
        @Getter
        private List<Long> closeCapacityCancellations = List.of();
        private volatile boolean completed = true;
        private boolean accountDispatched;

        public void prepare(com.surprising.aeron.service.core.AccountBook accountBook,
                com.surprising.aeron.service.core.identity.IdIndex identities,
                long sequence, long userId, UUID commandId, com.surprising.aeron.protocol.PlaceOrderCommand command,
                Decision decision,
                PendingOrderBatch source, int sourceIndex) {
            if (accountBook == null || identities == null || sequence <= 0 || userId <= 0
                    || commandId == null || command == null || decision == null || source == null || sourceIndex < 0)
                throw new IllegalArgumentException("invalid batch place reservation");
            accountBook.assertOwner();
            if (!completed) throw new IllegalStateException("Account Lane still owns this batch reservation");
            clear();
            this.decision = decision;
            this.source = source;
            this.sourceIndex = sourceIndex;
            initializeOrderIntent(command, decision.context().instrument(), decision.context().symbolId());
            this.accountBook = accountBook;
            this.identities = identities;
            this.sequence = sequence;
            this.userId = userId;
            this.commandId = commandId;
            if (decision.context().instrument().usesOpenInterestForUserLimit() && !command.reduceOnly()) {
                this.openInterestSteps = accountBook.ledgerOrderCheckOpenInterest(decision.context().instrument().instrumentId());
            } else {
                this.openInterestSteps = 0;
            }
            accountDispatched = false;
            completed = false;
        }

        @Override public boolean getAsBoolean() { return pollOrderCheck(); }

        public boolean pollOrderCheck() {
            accountBook.assertOwner();
            if (!accountDispatched) {
                accountBook.dispatchControlLanes(accountBook.topology().accountLaneMask(userId), this);
                accountDispatched = true;
            }
            return accountBook.pollControlLanes();
        }

        @Override public Object apply(int laneId) {
            com.surprising.aeron.service.account.AccountPartition lane = accountBook.currentLaneCommandScope();
            if (lane == null || lane.laneId() != laneId || laneId != accountBook.topology().accountLaneId(userId))
                throw new IllegalStateException("batch place reservation requires its Account Lane");
            long identity = 0;
            try {
                com.surprising.aeron.service.order.OrderCheckService.resolveInto(this, decision.context(), userId, intent());
                int assetId = accountBook.productLine().isDerivative() ? decision.settleAssetId()
                        : side() == com.surprising.aeron.protocol.CoreOrderSide.BUY ? decision.quoteAssetId() : decision.baseAssetId();
                identity = identities.prepareClientKeyInLane(lane, userId, clientOrderId());
                long clientKey = com.surprising.aeron.service.core.identity.IdIndex.clientKeyValue(identity);
                long positionKey = identities.findPositionKeyValueInLane(lane, userId, instrument(), positionSide());
                long required = com.surprising.aeron.service.order.OrderCheckService.requiredReservationPrepared(accountBook, lane, userId, this,
                        openInterestSteps, clientKey, positionKey,
                        decision.context().lifecycleSettled(), decision.context().fundingInProgress());
                com.surprising.aeron.service.position.Position position = positionKey == 0 ? null : lane.position(positionKey);
                if (instrument().contractType().productLine().isDerivative() && !reduceOnly()
                        && position != null && position.signedQuantitySteps() != 0
                        && (position.signedQuantitySteps() > 0) != (side() == com.surprising.aeron.protocol.CoreOrderSide.BUY)) {
                    closeCapacityCancellations = com.surprising.aeron.service.position.Position.CloseCapacity.conflictsFor(lane, userId, symbolId(),
                            positionKey, positionSide(), side(), orderId(), quantitySteps());
                }
                accountBook.placePreparedOrder(userId, this, commandId,
                        required, clientKey, symbolId(), assetId);
                lane.markPendingReservation(orderId(), sequence);
                Order admitted = accountBook.laneDelta(laneId).orders().get(orderId());
                if (admitted == null) throw new IllegalStateException("batch admission order is missing");
                admittedOrder = source.captureAdmittedOrder(sourceIndex, admitted);
                return null;
            } catch (RuntimeException | Error failure) {
                if (identity != 0) identities.rollbackClientKeyInLane(lane, userId, clientOrderId(), identity);
                throw failure;
            } finally {
                completed = true;
            }
        }

        public Order admittedOrder() {
            if (admittedOrder == null) throw new IllegalStateException("batch reservation has not completed");
            return admittedOrder;
        }

        public void clear() {
            if (!completed) return;
            clearResolvedOrder();
            accountBook = null;
            identities = null;
            decision = null;
            commandId = null;
            sequence = userId = openInterestSteps = 0;
            admittedOrder = null;
            source = null;
            sourceIndex = 0;
            closeCapacityCancellations = List.of();
            accountDispatched = false;
        }
    }
}

