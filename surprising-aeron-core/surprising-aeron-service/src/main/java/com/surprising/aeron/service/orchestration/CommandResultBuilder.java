package com.surprising.aeron.service.orchestration;
import com.surprising.aeron.service.command.order.ResolvedMatchingAdmission;
import com.surprising.aeron.service.command.support.PrimitiveLongView;
import com.surprising.aeron.service.command.support.PrimitiveLongChangeSet;
import static com.surprising.aeron.service.orchestration.TradingCoreRuntime.*;

import com.surprising.aeron.service.matching.CoreMatchingResult;
import com.surprising.aeron.protocol.CoreCommandResultCodec;
import com.surprising.aeron.protocol.CoreOrderStateView;
import com.surprising.aeron.protocol.CoreOrderStateSource;
import com.surprising.aeron.protocol.CoreFundingProgressCodec;
import com.surprising.aeron.protocol.CoreFundingProgressView;
import com.surprising.aeron.protocol.CoreLiquidationProgressCodec;
import com.surprising.aeron.protocol.CoreLiquidationProgressView;
import com.surprising.aeron.protocol.CoreLiquidationBatchResultCodec;
import com.surprising.aeron.protocol.CoreLiquidationBatchResultView;
import com.surprising.aeron.protocol.CoreSettlementProgressCodec;
import com.surprising.aeron.protocol.CoreSettlementProgressView;
import com.surprising.aeron.protocol.CoreRiskScanControlCodec;
import com.surprising.aeron.protocol.CoreRiskScanControlView;
import com.surprising.aeron.service.state.OrderRuntime;
import com.surprising.aeron.service.state.model.CoreOrderState;
import com.surprising.product.api.ProductLine;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreOrderType;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreTimeInForce;
import java.util.UUID;
import java.util.ArrayList;
import java.util.AbstractList;
import java.util.List;
import java.util.RandomAccess;

/** 当前命令的结果与变更 ID；owner 串行复用，在命令完成边界编码。 */
final class CommandResultBuilder {
    /** Empty response payload is immutable and shared by all no-body results. */
    private static final byte[] EMPTY_RESULT = TradingCoreRuntime.EMPTY_RESPONSE_DATA;
    /** 唯一 owner；仅在其线程访问共享交易状态和提交边界。 */
    final TradingCoreRuntime owner;
    private final ResponseArena responseArena;
    /** Current encoded payload storage; arena slices carry an explicit logical length. */
    private byte[] responseStorage = EMPTY_RESULT;
    private int responseOffset;
    private int responseLength;

    CommandResultBuilder(TradingCoreRuntime owner) {
        this.owner = owner;
        this.responseArena = owner == null ? new ResponseArena(1, ResponseArena.DEFAULT_SLOT_BYTES)
                : owner.responseArena;
    }

    /** 当前命令需要返回的订单视图，仅响应边界物化。 */
    List<CoreOrderStateView> commandOrderViews = List.of();

    /** 单订单结果直接借用当前 OrderRuntime，避免每笔响应先物化 CoreOrderStateView。 */
    private OrderRuntime commandSingleOrder;
    private final OrderRuntimeSource commandSingleOrderSource = new OrderRuntimeSource();
    /** Owner-confined source slots for multi-order responses; adapters grow only at a new high-water mark. */
    private final OrderSourceList commandOrderSources = new OrderSourceList(2);

    void clearOrderViews() {
        commandSingleOrder = null;
        commandOrderViews = List.of();
        commandOrderSources.clear();
    }

    /** Start a command with the reusable result workspace in its empty state. */
    void beginCommand() {
        discardResponseStorage();
        clearOrderViews();
        commandChangedUserIds = List.of();
        commandChangedOrderIds = List.of();
        commandTradeCount = 0;
        commandFundingProgress = null;
        commandLiquidationProgress = null;
        commandLiquidationBatchResult = null;
        commandSettlementProgress = null;
        commandRiskScanControl = null;
        commandTriggerOrderView = null;
        resetChangeAccumulators();
    }

    /** 当前命令返回的用户 ID 集合；完成边界生成。 */
    List<Long> commandChangedUserIds;

    /** 当前命令返回的订单 ID 集合；完成边界生成。 */
    List<Long> commandChangedOrderIds;

    /** 当前命令变化的用户 ID，保持 primitive 收集直到返回边界。 */
    PrimitiveLongChangeSet changedUserIds = new PrimitiveLongChangeSet();

    /** 当前命令变化的订单 ID，保持 primitive 收集直到返回边界。 */
    PrimitiveLongChangeSet changedOrderIds = new PrimitiveLongChangeSet();
    /** Lane settlement already supplied the exact primitive user keys for this command. */
    private boolean laneDeltaIdsSeeded;

    /** 复用批量订单响应的去重集合和临时视图缓冲；List.copyOf 在边界创建稳定结果。 */
    private final PrimitiveLongChangeSet commandViewOrderIds = new PrimitiveLongChangeSet();
    private final ArrayList<CoreOrderStateView> commandViewBuffer = new ArrayList<>();
    /** Direct control commands stay on the owner until their response is encoded. Reuse views
     * over the owner workspace instead of copying both sets into a fresh long[] per command. */
    private final MutableLongListView directChangedUsers = new MutableLongListView();
    private final MutableLongListView directChangedOrders = new MutableLongListView();

    /** 当前命令产生的成交数。 */
    long commandTradeCount;

    /** 当前资金费命令的续作进度。 */
    CoreFundingProgressView commandFundingProgress;

    /** 当前清算命令的续作进度。 */
    CoreLiquidationProgressView commandLiquidationProgress;

    /** 当前批量清算的逐项结果。 */
    CoreLiquidationBatchResultView commandLiquidationBatchResult;

    /** 当前到期结算命令的续作进度。 */
    CoreSettlementProgressView commandSettlementProgress;

    /** 当前风险扫描控制命令的返回状态。 */
    CoreRiskScanControlView commandRiskScanControl;

    /** 当前触发单命令返回的终态视图。 */
    com.surprising.aeron.protocol.CoreTriggerOrderStateView commandTriggerOrderView;

    void markUserChanged(long userId) {
        owner.seedChangeAccumulators();
        changedUserIds.add(userId);
    }

    void markOrderChanged(long orderId) {
        owner.seedChangeAccumulators();
        changedOrderIds.add(orderId);
    }

    /** Replace a single changed id without allocating a boxed Long or a temporary List. */
    void setSingleChangedUser(long userId) {
        owner.seedChangeAccumulators();
        changedUserIds.add(userId);
        commandChangedUserIds = List.of();
    }

    /** Replace a single changed id without allocating a boxed Long or a temporary List. */
    void setSingleChangedOrder(long orderId) {
        owner.seedChangeAccumulators();
        changedOrderIds.add(orderId);
        commandChangedOrderIds = List.of();
    }

    void beginChangedUsers() {
        owner.seedChangeAccumulators();
        commandChangedUserIds = List.of();
    }

    void addChangedUser(long userId) {
        changedUserIds.add(userId);
    }

    void addChangedUsersFromOrders(Iterable<? extends CoreOrderState> orders) {
        owner.seedChangeAccumulators();
        commandChangedUserIds = List.of();
        if (orders == null) return;
        for (CoreOrderState order : orders) if (order != null) changedUserIds.add(order.userId());
    }

    void addChangedUsersFromFundingPayments(
            Iterable<? extends com.surprising.aeron.protocol.CoreFundingPaymentView> payments) {
        owner.seedChangeAccumulators();
        commandChangedUserIds = List.of();
        if (payments == null) return;
        for (var payment : payments) if (payment != null) changedUserIds.add(payment.userId());
    }

    void resetChangeAccumulators() {
        owner.commandFundsAccumulator.clear();
        changedUserIds.clear();
        changedOrderIds.clear();
        laneDeltaIdsSeeded = false;
    }

    void markLaneDeltaIdsSeeded() { laneDeltaIdsSeeded = true; }

    void materializeChangeAccumulators() {
        owner.seedChangeAccumulators();
        // A matcher settlement has already copied its LaneDelta user keys into the primitive
        // set. Scanning the Owner-wide changed-user index again only duplicates probes and can
        // accidentally include an earlier command's users while a commit is suspended.
        if (!laneDeltaIdsSeeded) owner.runtimeState.acceptChangedUserIds(changedUserIds::add);
        commandChangedUserIds = changedUserIds.toImmutableList();
        commandChangedOrderIds = changedOrderIds.toImmutableList();
    }

    void materializeDirectChangeAccumulators() {
        owner.seedChangeAccumulators();
        owner.runtimeState.acceptChangedUserIds(changedUserIds::add);
        directChangedUsers.bind(changedUserIds);
        directChangedOrders.bind(changedOrderIds);
        commandChangedUserIds = directChangedUsers;
        commandChangedOrderIds = directChangedOrders;
    }

    void materializeCommandOrderViews(CommandSlot pending) {
        switch (pending.operation()) {
            case PLACE -> {
                materializeResponseOrder(pending, pending.decodedCommand().placeOrder().orderId());
                return;
            }
            case CANCEL -> {
                materializeResponseOrder(pending, pending.decodedCommand().cancelOrder().orderId());
                return;
            }
            case REPLACE, AMEND -> {
                ResolvedMatchingAdmission admission = owner.requireMatchingAdmission(pending);
                materializeResponseOrders(pending, admission.originalOrderId(), admission.command().orderId());
                return;
            }
            case TRIGGER -> {
                materializeResponseOrder(pending, pending.settlementPlan().takerOrderId());
                return;
            }
            default -> { }
        }
        // Most batch and liquidation responses have no pre-existing DTO views.  Feed the
        // reusable runtime sources directly to the protocol encoder instead of creating a
        // CoreOrderStateView, a temporary ArrayList and a List.copyOf for every command.
        if (commandOrderViews.isEmpty()) {
            commandSingleOrder = null;
            commandOrderSources.clear();
            for (int index = 0; index < commandChangedOrderIds.size(); index++) {
                long orderId = primitiveOrderId(commandChangedOrderIds, index);
                OrderRuntime order = owner.responseOrder(orderId);
                if (order != null) commandOrderSources.add(order, owner.runtimeOrderSymbol(order));
            }
            return;
        }
        PrimitiveLongChangeSet orderIds = commandViewOrderIds;
        orderIds.clear();
        java.util.ArrayList<CoreOrderStateView> views = commandViewBuffer;
        views.clear();
        int expectedSize = commandOrderViews.size() + commandChangedOrderIds.size();
        views.ensureCapacity(expectedSize);
        for (CoreOrderStateView view : commandOrderViews) {
            if (orderIds.add(view.orderId())) views.add(view);
        }
        for (int orderIndex = 0; orderIndex < commandChangedOrderIds.size(); orderIndex++) {
            long orderId = primitiveOrderId(commandChangedOrderIds, orderIndex);
            var order = owner.responseOrder(orderId);
            if (order == null) continue;
            CoreOrderStateView view = owner.orderView(order);
            if (orderIds.add(orderId)) {
                views.add(view);
                continue;
            }
            for (int index = 0; index < views.size(); index++) {
                if (views.get(index).orderId() == orderId) {
                    views.set(index, view);
                    break;
                }
            }
        }
        commandOrderViews = List.copyOf(views);
        commandSingleOrder = null;
    }

    private static long primitiveOrderId(List<Long> orderIds, int index) {
        if (orderIds instanceof PrimitiveLongView primitive) return primitive.primitiveValueAt(index);
        return orderIds.get(index);
    }

    void materializeResponseOrders(long... orderIds) {
        commandOrderSources.clear();
        if (orderIds.length == 1) {
            materializeResponseOrder(orderIds[0]);
            return;
        }
        if (orderIds.length > 1) {
            commandSingleOrder = null;
            commandOrderViews = List.of();
            for (long orderId : orderIds) {
                OrderRuntime order = owner.responseOrder(orderId);
                if (order != null) commandOrderSources.add(order, owner.runtimeOrderSymbol(order));
            }
            return;
        }
        commandOrderViews = List.of();
        commandSingleOrder = null;
    }

    /** 双订单改单响应的无-varargs快路径，避免为两个 ID 创建临时 long[]。 */
    void materializeResponseOrders(long firstOrderId, long secondOrderId) {
        commandOrderSources.clear();
        OrderRuntime first = owner.responseOrder(firstOrderId);
        OrderRuntime second = owner.responseOrder(secondOrderId);
        if (first == null) {
            if (second == null) {
                commandSingleOrder = null;
                commandOrderViews = List.of();
            } else {
                commandSingleOrder = second;
                commandOrderViews = List.of();
            }
        } else if (second == null) {
            commandSingleOrder = first;
            commandOrderViews = List.of();
        } else {
            commandSingleOrder = null;
            commandOrderViews = List.of();
            commandOrderSources.add(first, owner.runtimeOrderSymbol(first));
            commandOrderSources.add(second, owner.runtimeOrderSymbol(second));
        }
    }

    void materializeResponseOrder(long orderId) {
        commandOrderSources.clear();
        OrderRuntime order = owner.responseOrder(orderId);
        commandSingleOrder = order;
        commandOrderViews = List.of();
    }

    private OrderRuntime responseOrder(CommandSlot pending, long orderId) {
        if (pending != null && pending.laneResultPrepared()) {
            OrderRuntime laneOrder = pending.laneResultOrder(orderId);
            if (laneOrder != null) return laneOrder;
        }
        return owner.responseOrder(orderId);
    }

    private String responseOrderSymbol(CommandSlot pending, long orderId, OrderRuntime order) {
        if (pending != null && pending.laneResultPrepared()) {
            String symbol = pending.laneResultSymbol(orderId);
            if (symbol != null) return symbol;
        }
        return owner.runtimeOrderSymbol(order);
    }

    private void materializeResponseOrder(CommandSlot pending, long orderId) {
        commandOrderSources.clear();
        commandSingleOrder = responseOrder(pending, orderId);
        commandOrderViews = List.of();
    }

    private void materializeResponseOrders(CommandSlot pending, long firstOrderId, long secondOrderId) {
        commandOrderSources.clear();
        OrderRuntime first = responseOrder(pending, firstOrderId);
        OrderRuntime second = responseOrder(pending, secondOrderId);
        if (first == null) {
            if (second == null) {
                commandSingleOrder = null;
                commandOrderViews = List.of();
            } else {
                commandSingleOrder = second;
                commandOrderViews = List.of();
            }
        } else if (second == null) {
            commandSingleOrder = first;
            commandOrderViews = List.of();
        } else {
            commandSingleOrder = null;
            commandOrderViews = List.of();
            commandOrderSources.add(first, responseOrderSymbol(pending, firstOrderId, first));
            commandOrderSources.add(second, responseOrderSymbol(pending, secondOrderId, second));
        }
    }

    byte[] commandResultData() {
        return commandResultData(null, null);
    }

    byte[] commandResultData(
            CommandSlot pending,
            com.surprising.aeron.service.matching.CoreMatchingResult matchingResult) {
        if (commandRiskScanControl != null) {
            return setResponse(CoreRiskScanControlCodec.encodeView(commandRiskScanControl));
        }
        if (commandFundingProgress != null) {
            return setResponse(CoreFundingProgressCodec.encode(commandFundingProgress));
        }
        if (commandLiquidationProgress != null) {
            return setResponse(CoreLiquidationProgressCodec.encode(commandLiquidationProgress));
        }
        if (commandLiquidationBatchResult != null) {
            return setResponse(CoreLiquidationBatchResultCodec.encode(commandLiquidationBatchResult));
        }
        if (commandSettlementProgress != null) {
            return setResponse(CoreSettlementProgressCodec.encode(commandSettlementProgress));
        }
        if (commandTriggerOrderView != null) {
            return setResponse(com.surprising.aeron.protocol.CoreTriggerOrderCodec.encodeSingle(commandTriggerOrderView));
        }
        if (pending != null) {
            byte[] prepared = pending.lanePreparedResponse();
            if (prepared != null) return setResponse(prepared);
        }
        if (commandSingleOrder == null && commandOrderViews.isEmpty() && commandOrderSources.isEmpty()) {
            return setResponse(EMPTY_RESULT);
        }
        if (pending == null || matchingResult == null) {
            return setResponse(EMPTY_RESULT);
        }
        long matcherPrefixBefore = matchingResult.matcherPrefixBefore();
        long matcherPrefixAfter = matchingResult.matcherPrefixAfter();
        long nativeOrderId = matchingResult.nativeOrderId();
        long nativeInstrumentChangeId = matchingResult.nativeInstrumentChangeId();
        long nativeMatcherSequence = matchingResult.nativeMatcherSequence();
        if (matchingResult.nativeCoreSequence() != pending.sequence()
                || !matchingResult.nativeMatches(pending.command().header().commandId())
                || nativeOrderId <= 0 || nativeInstrumentChangeId <= 0
                || nativeMatcherSequence <= 0 || matcherPrefixBefore == 0 || matcherPrefixAfter == 0) {
            return setResponse(EMPTY_RESULT);
        }
        try {
            if (commandSingleOrder != null) {
                commandSingleOrderSource.set(commandSingleOrder, owner.runtimeOrderSymbol(commandSingleOrder));
                int length = CoreCommandResultCodec.encodedSingleOrderLength(commandSingleOrderSource);
                byte[] destination = prepareResponseStorage(length);
                responseLength = CoreCommandResultCodec.encodeSingleOrderInto(
                        pending.sequence(), pending.command().header().commandId(),
                        nativeOrderId, nativeInstrumentChangeId, nativeMatcherSequence,
                        matcherPrefixBefore, matcherPrefixAfter, commandSingleOrderSource,
                        destination, responseOffset);
                return destination;
            }
            if (commandOrderViews.size() == 1) {
                int length = CoreCommandResultCodec.encodedSingleOrderLength(commandOrderViews.get(0));
                byte[] destination = prepareResponseStorage(length);
                responseLength = CoreCommandResultCodec.encodeSingleOrderInto(
                        pending.sequence(), pending.command().header().commandId(),
                        nativeOrderId, nativeInstrumentChangeId, nativeMatcherSequence,
                        matcherPrefixBefore, matcherPrefixAfter, commandOrderViews.get(0),
                        destination, responseOffset);
                return destination;
            }
            if (!commandOrderSources.isEmpty()) {
                int length = CoreCommandResultCodec.encodedLength(commandOrderSources, List.of());
                byte[] destination = prepareResponseStorage(length);
                responseLength = CoreCommandResultCodec.encodeInto(
                        pending.sequence(), pending.command().header().commandId(),
                        nativeOrderId, nativeInstrumentChangeId, nativeMatcherSequence,
                        matcherPrefixBefore, matcherPrefixAfter, commandOrderSources, List.of(),
                        destination, responseOffset);
                return destination;
            }
            int length = CoreCommandResultCodec.encodedLength(commandOrderViews, List.of());
            byte[] destination = prepareResponseStorage(length);
            responseLength = CoreCommandResultCodec.encodeInto(
                    pending.sequence(), pending.command().header().commandId(),
                    nativeOrderId, nativeInstrumentChangeId, nativeMatcherSequence,
                    matcherPrefixBefore, matcherPrefixAfter, commandOrderViews, List.of(),
                    destination, responseOffset);
            return destination;
        } catch (IllegalArgumentException exception) {
            return setResponse(EMPTY_RESULT);
        }
    }

    private byte[] setResponse(byte[] response) {
        discardResponseStorage();
        responseStorage = response == null ? EMPTY_RESULT : response;
        responseOffset = 0;
        responseLength = responseStorage.length;
        return responseStorage;
    }

    private byte[] prepareResponseStorage(int length) {
        discardResponseStorage();
        responseOffset = 0;
        responseLength = length;
        responseStorage = responseArena.acquire(length);
        return responseStorage;
    }

    private void discardResponseStorage() {
        if (responseStorage != EMPTY_RESULT) responseArena.release(responseStorage);
        responseStorage = EMPTY_RESULT;
        responseOffset = 0;
        responseLength = 0;
    }

    /** The ledger takes over the current stable storage after a successful store. */
    void transferResponseOwnership() {
        responseStorage = EMPTY_RESULT;
        responseOffset = 0;
        responseLength = 0;
    }

    int responseDataOffset() { return responseOffset; }
    int responseDataLength() { return responseLength; }

    /** Fixed-size owner list backed by reusable OrderRuntime source adapters. */
    private static final class OrderSourceList extends AbstractList<CoreOrderStateSource> implements RandomAccess {
        private OrderRuntimeSource[] values;
        private int size;

        private OrderSourceList(int initialCapacity) { values = new OrderRuntimeSource[initialCapacity]; }

        private void add(OrderRuntime order, String symbol) {
            if (size == values.length) {
                values = java.util.Arrays.copyOf(values, Math.multiplyExact(values.length, 2));
            }
            OrderRuntimeSource source = values[size];
            if (source == null) values[size] = source = new OrderRuntimeSource();
            source.set(order, symbol);
            size++;
        }

        @Override public void clear() {
            for (int index = 0; index < size; index++) values[index].clear();
            size = 0;
        }

        @Override public CoreOrderStateSource get(int index) {
            if (index < 0 || index >= size) throw new IndexOutOfBoundsException(index);
            return values[index];
        }

        @Override public int size() { return size; }
    }

    /** Reusable source adapter; the protocol encoder never retains it. */
    private static final class OrderRuntimeSource implements CoreOrderStateSource {
        private OrderRuntime order;
        private String symbol;

        void set(OrderRuntime order, String symbol) {
            this.order = order;
            this.symbol = symbol;
        }

        void clear() {
            order = null;
            symbol = null;
        }

        public long orderId() { return order.orderId(); }
        public ProductLine productLine() { return order.productLine(); }
        public long userId() { return order.userId(); }
        public String symbol() { return symbol; }
        public long instrumentChangeId() { return order.instrumentChangeId(); }
        public CoreOrderSide side() { return order.side(); }
        public long priceTicks() { return order.priceTicks(); }
        public long quantitySteps() { return order.quantitySteps(); }
        public long executedQuantitySteps() { return order.executedQuantitySteps(); }
        public long remainingQuantitySteps() { return order.remainingQuantitySteps(); }
        public boolean reduceOnly() { return order.reduceOnly(); }
        public CoreMarginMode marginMode() { return order.marginMode(); }
        public CorePositionSide positionSide() { return order.positionSide(); }
        public CoreOrderType orderType() { return order.orderType(); }
        public CoreTimeInForce timeInForce() { return order.timeInForce(); }
        public boolean postOnly() { return order.postOnly(); }
        public String clientOrderId() { return order.clientOrderId(); }
        public UUID commandId() { return order.commandId(); }
        public long makerFeeRatePpm() { return order.makerFeeRatePpm(); }
        public long takerFeeRatePpm() { return order.takerFeeRatePpm(); }
        public long cumulativeFeeUnits() { return order.cumulativeFeeUnits(); }
        public long createdAtEpochMillis() { return order.createdAtEpochMillis(); }
        public long updatedAtEpochMillis() { return order.updatedAtEpochMillis(); }
        public long clusterPosition() { return order.clusterPosition(); }
        public String status() { return order.status().name(); }
        public long revision() { return order.revision(); }
    }

    /** Owner-confined List facade; the direct command path never retains it after the callback. */
    private static final class MutableLongListView extends AbstractList<Long> implements RandomAccess {
        private PrimitiveLongChangeSet values;

        void bind(PrimitiveLongChangeSet values) { this.values = values; }

        @Override public Long get(int index) { return values.valueAt(index); }
        @Override public int size() { return values == null ? 0 : values.size(); }
    }
}
