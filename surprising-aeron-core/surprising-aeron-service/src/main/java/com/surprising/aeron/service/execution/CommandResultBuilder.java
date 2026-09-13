package com.surprising.aeron.service.execution;

import static com.surprising.aeron.service.execution.TradingCoreRuntime.*;

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

    CommandResultBuilder(TradingCoreRuntime owner) { this.owner = owner; }

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

    /** 当前命令返回的用户 ID 集合；完成边界生成。 */
    List<Long> commandChangedUserIds;

    /** 当前命令返回的订单 ID 集合；完成边界生成。 */
    List<Long> commandChangedOrderIds;

    /** 当前命令变化的用户 ID，保持 primitive 收集直到返回边界。 */
    final PrimitiveLongChangeSet changedUserIds = new PrimitiveLongChangeSet();

    /** 当前命令变化的订单 ID，保持 primitive 收集直到返回边界。 */
    final PrimitiveLongChangeSet changedOrderIds = new PrimitiveLongChangeSet();

    /** 复用批量订单响应的去重集合和临时视图缓冲；List.copyOf 在边界创建稳定结果。 */
    private final PrimitiveLongChangeSet commandViewOrderIds = new PrimitiveLongChangeSet();
    private final ArrayList<CoreOrderStateView> commandViewBuffer = new ArrayList<>();

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

    void resetChangeAccumulators() {
        owner.commandFundsAccumulator.clear();
        owner.commandExternalAdjustment = false;
        changedUserIds.clear();
        changedOrderIds.clear();
    }

    void materializeChangeAccumulators() {
        owner.seedChangeAccumulators();
        owner.runtimeState.acceptChangedUserIds(changedUserIds::add);
        commandChangedUserIds = changedUserIds.toImmutableList();
        commandChangedOrderIds = changedOrderIds.toImmutableList();
    }

    void materializeCommandOrderViews(PendingMatching pending) {
        switch (pending.operation()) {
            case PLACE -> {
                materializeResponseOrder(pending.decodedCommand().placeOrder().orderId());
                return;
            }
            case CANCEL -> {
                materializeResponseOrder(pending.decodedCommand().cancelOrder().orderId());
                return;
            }
            case REPLACE, AMEND -> {
                ResolvedMatchingAdmission admission = owner.requireMatchingAdmission(pending);
                materializeResponseOrders(admission.originalOrderId(), admission.command().orderId());
                return;
            }
            case TRIGGER -> {
                materializeResponseOrder(pending.settlementPlan().takerOrderId());
                return;
            }
            default -> { }
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
            long orderId = commandChangedOrderIds instanceof ImmutableLongArrayList primitive
                    ? primitive.valueAt(orderIndex) : commandChangedOrderIds.get(orderIndex);
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
        java.util.ArrayList<CoreOrderStateView> views = new java.util.ArrayList<>(orderIds.length);
        for (long orderId : orderIds) {
            OrderRuntime order = owner.responseOrder(orderId);
            if (order != null) views.add(owner.orderView(order));
        }
        commandOrderViews = List.copyOf(views);
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

    byte[] commandResultData() {
        return commandResultData(null, null);
    }

    byte[] commandResultData(
            PendingMatching pending,
            com.surprising.aeron.service.matching.CoreMatchingResult matchingResult) {
        if (commandRiskScanControl != null) {
            return CoreRiskScanControlCodec.encodeView(commandRiskScanControl);
        }
        if (commandFundingProgress != null) {
            return CoreFundingProgressCodec.encode(commandFundingProgress);
        }
        if (commandLiquidationProgress != null) {
            return CoreLiquidationProgressCodec.encode(commandLiquidationProgress);
        }
        if (commandLiquidationBatchResult != null) {
            return CoreLiquidationBatchResultCodec.encode(commandLiquidationBatchResult);
        }
        if (commandSettlementProgress != null) {
            return CoreSettlementProgressCodec.encode(commandSettlementProgress);
        }
        if (commandTriggerOrderView != null) {
            return com.surprising.aeron.protocol.CoreTriggerOrderCodec.encodeList(List.of(commandTriggerOrderView));
        }
        if (commandSingleOrder == null && commandOrderViews.isEmpty() && commandOrderSources.isEmpty()) {
            return EMPTY_RESULT;
        }
        if (pending == null || matchingResult == null) {
            return EMPTY_RESULT;
        }
        var nativeCommand = matchingResult.nativeCommand();
        var matcherPrefix = matchingResult.matcherPrefix();
        if (nativeCommand.coreSequence() != pending.sequence()
                || !nativeCommand.matches(pending.command().header().commandId())
                || nativeCommand.orderId() <= 0 || nativeCommand.instrumentChangeId() <= 0
                || nativeCommand.matcherSequence() <= 0 || !matcherPrefix.bound()) {
            return EMPTY_RESULT;
        }
        try {
            if (commandSingleOrder != null) {
                commandSingleOrderSource.set(commandSingleOrder, owner.runtimeOrderSymbol(commandSingleOrder));
                return CoreCommandResultCodec.encodeSingleOrder(
                        pending.sequence(), pending.command().header().commandId(),
                        nativeCommand.orderId(), nativeCommand.instrumentChangeId(), nativeCommand.matcherSequence(),
                        matcherPrefix.before(), matcherPrefix.after(), commandSingleOrderSource);
            }
            if (commandOrderViews.size() == 1) {
                return CoreCommandResultCodec.encodeSingleOrder(
                        pending.sequence(), pending.command().header().commandId(),
                        nativeCommand.orderId(), nativeCommand.instrumentChangeId(), nativeCommand.matcherSequence(),
                        matcherPrefix.before(), matcherPrefix.after(), commandOrderViews.get(0));
            }
            if (!commandOrderSources.isEmpty()) {
                return CoreCommandResultCodec.encode(
                        pending.sequence(), pending.command().header().commandId(),
                        nativeCommand.orderId(), nativeCommand.instrumentChangeId(), nativeCommand.matcherSequence(),
                        matcherPrefix.before(), matcherPrefix.after(), commandOrderSources, List.of());
            }
            return CoreCommandResultCodec.encode(
                    pending.sequence(), pending.command().header().commandId(),
                    nativeCommand.orderId(), nativeCommand.instrumentChangeId(), nativeCommand.matcherSequence(),
                    matcherPrefix.before(), matcherPrefix.after(), commandOrderViews, List.of());
        } catch (IllegalArgumentException exception) {
            return EMPTY_RESULT;
        }
    }

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
}
