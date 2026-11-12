package com.surprising.aeron.service.result;

import com.surprising.aeron.service.order.*;

import com.surprising.aeron.protocol.CoreCommandResultCodec;
import com.surprising.aeron.protocol.CoreFundingProgressCodec;
import com.surprising.aeron.protocol.CoreFundingProgressView;
import com.surprising.aeron.protocol.CoreLiquidationBatchResultCodec;
import com.surprising.aeron.protocol.CoreLiquidationBatchResultView;
import com.surprising.aeron.protocol.CoreLiquidationProgressCodec;
import com.surprising.aeron.protocol.CoreLiquidationProgressView;
import com.surprising.aeron.protocol.CoreOrderStateSource;
import com.surprising.aeron.protocol.CoreRiskScanControlCodec;
import com.surprising.aeron.protocol.CoreRiskScanControlView;
import com.surprising.aeron.protocol.CoreSettlementProgressCodec;
import com.surprising.aeron.protocol.CoreSettlementProgressView;
import com.surprising.aeron.service.core.TradingCore;
import com.surprising.aeron.service.matcher.OrderBookEngine.CancellationOrder;
import com.surprising.aeron.service.matcher.OrderMatchingResult;
import com.surprising.aeron.service.order.OrderCommand;
import com.surprising.aeron.service.order.OrderCommand.ReplaceOrderCheck;
import com.surprising.aeron.service.order.Order;
import com.surprising.aeron.support.collection.PrimitiveLongChangeSet;
import com.surprising.aeron.support.collection.PrimitiveLongView;
import java.util.AbstractList;
import java.util.List;
import java.util.RandomAccess;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import static com.surprising.aeron.service.core.TradingCore.*;

/** 交易步骤 5–6：Owner 串行复用的当前命令结果工作区，收集变更 ID、借用可编码订单源，并在提交边界选择响应内容。响应数组可能来自共享池，移交后不能由下一命令提前归还。 */
public final class CommandResultBuilder {
    private static final byte[] EMPTY_RESULT = TradingCore.EMPTY_RESPONSE_DATA;
    final TradingCore owner;
    private final ResponseSlotPool responseSlotPool;
    private byte[] responseStorage = EMPTY_RESULT;
    private int responseOffset;
    private int responseLength;

    /** 关联所属运行时的响应池。传空 Owner 仅创建一个小响应池用于有限的独立使用，需要 Owner 的业务方法仍不能在空 Owner 下调用。 */
    public CommandResultBuilder(TradingCore owner) {
        this.owner = owner;
        this.responseSlotPool = owner == null ? new ResponseSlotPool(1, ResponseSlotPool.DEFAULT_SLOT_BYTES)
                : owner.responseSlotPool();
    }

    private Order commandSingleOrder;
    private final OrderSource commandSingleOrderSource = new OrderSource();
    private final OrderSourceList commandOrderSources = new OrderSourceList(2);

    /** 清当前单订单引用及多订单源列表，不释放权威订单，也不修改账户状态。 */
    public void clearOrderViews() {
        commandSingleOrder = null;
        commandOrderSources.clear();
    }

    /** 先归还本工作区尚未移交的响应字节，再清当前订单与进度结果、变更列表及 primitive 收集集合，为新命令建立空工作区。 */
    public void beginCommand() {
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
        commandTriggerOrderViews = null;
        resetChangeAccumulators();
    }

    public List<Long> commandChangedUserIds;

    public List<Long> commandChangedOrderIds;

    /** 借出当前命令的 primitive 用户 ID 集合，所有权仍随 Owner 命令作用域。把当前命令 primitive 用户集合引用替换为指定集合，供挂起及恢复作用域交接。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    @Setter
    PrimitiveLongChangeSet changedUserIds = new PrimitiveLongChangeSet();

    @Accessors(fluent = true, chain = false)
    @Getter
    @Setter
    PrimitiveLongChangeSet changedOrderIds = new PrimitiveLongChangeSet();
    private boolean laneCommitIdsSeeded;

    private final MutableLongListView directChangedUsers = new MutableLongListView();
    private final MutableLongListView directChangedOrders = new MutableLongListView();

    @Accessors(fluent = true, chain = false)
    @Getter
    @Setter
    long commandTradeCount;

    public CoreFundingProgressView commandFundingProgress;

    public CoreLiquidationProgressView commandLiquidationProgress;

    public CoreLiquidationBatchResultView commandLiquidationBatchResult;

    public CoreSettlementProgressView commandSettlementProgress;

    public CoreRiskScanControlView commandRiskScanControl;

    public java.util.List<com.surprising.aeron.protocol.CoreTriggerOrderStateView> commandTriggerOrderViews;

    /** 先让 Owner 接续当前命令的变更集合，再登记一个变化用户 ID。 */
    public void markUserChanged(long userId) {
        owner.seedChangeAccumulators();
        changedUserIds.add(userId);
    }

    /** 先让 Owner 接续当前命令的变更集合，再登记一个变化订单 ID。 */
    public void markOrderChanged(long orderId) {
        owner.seedChangeAccumulators();
        changedOrderIds.add(orderId);
    }

    /** 接续已有变更集合后添加用户 ID，并把公开结果列表置空等待重新绑定。 */
    public void setSingleChangedUser(long userId) {
        owner.seedChangeAccumulators();
        changedUserIds.add(userId);
        commandChangedUserIds = List.of();
    }

    /** 接续已有变更集合后添加订单 ID，并清公开结果列表引用。 */
    public void setSingleChangedOrder(long orderId) {
        owner.seedChangeAccumulators();
        changedOrderIds.add(orderId);
        commandChangedOrderIds = List.of();
    }

    /** 接续当前命令变更集合并把结果列表置空，保留 primitive 已收集内容等待继续追加。 */
    public void beginChangedUsers() {
        owner.seedChangeAccumulators();
        commandChangedUserIds = List.of();
    }

    /** 直接把用户 ID 追加到当前 primitive 集合，本入口不重新向 Owner 接续作用域。 */
    public void addChangedUser(long userId) {
        changedUserIds.add(userId);
    }

    public void addLifecycleChanges(Iterable<CancellationOrder> orders) {
        owner.seedChangeAccumulators();
        commandChangedOrderIds = List.of();
        commandChangedUserIds = List.of();
        for (CancellationOrder order : orders) {
            changedOrderIds.add(order.orderId());
            changedUserIds.add(order.userId());
        }
    }

    /** 接续当前集合，从非空资金费记录收集用户 ID。 */
    public void addChangedUsersFromFundingPayments(
            Iterable<? extends com.surprising.aeron.protocol.CoreFundingPaymentView> payments) {
        owner.seedChangeAccumulators();
        commandChangedUserIds = List.of();
        if (payments == null) return;
        for (var payment : payments) if (payment != null) changedUserIds.add(payment.userId());
    }

    /** 账本尚未启动时清冷阶段资金工作区，再清当前用户和订单 ID 集合及 Lane 已供给标记。需要有效 Owner 和运行时。 */
    public void resetChangeAccumulators() {
        changedUserIds.clear();
        changedOrderIds.clear();
        laneCommitIdsSeeded = false;
    }

    /** 标记 Lane 结算已经提供当前命令的准确用户变更键，后续普通结果物化不再额外扫描 Owner 变化索引。 */
    public void markLaneCommitIdsSeeded() { laneCommitIdsSeeded = true; }

    /** 接续当前集合。只有尚未由 Lane 提供准确键时才补 Owner 用户变更，再把用户和订单结果绑定为 primitive 集合的只读视图，不创建另一份 ID 列表。 */
    public void materializeChangeAccumulators() {
        owner.seedChangeAccumulators();
        // Lane 结算若已交出当前命令的准确用户键，就不再重复收集 Owner 的整体变化索引。
        // 否则提交挂起期间可能把较早命令的用户混入当前结果。
        if (!laneCommitIdsSeeded) owner.accountBook().acceptChangedUserIds(changedUserIds::add);
        directChangedUsers.bind(changedUserIds);
        directChangedOrders.bind(changedOrderIds);
        commandChangedUserIds = directChangedUsers;
        commandChangedOrderIds = directChangedOrders;
    }

    /** 直接控制命令接续集合并补入 Owner 用户变化，然后重新绑定两组只读视图。此入口不跳过 Owner 变化收集。 */
    public void materializeDirectChangeAccumulators() {
        owner.seedChangeAccumulators();
        owner.accountBook().acceptChangedUserIds(changedUserIds::add);
        directChangedUsers.bind(changedUserIds);
        directChangedOrders.bind(changedOrderIds);
        commandChangedUserIds = directChangedUsers;
        commandChangedOrderIds = directChangedOrders;
    }

    /** Lane 已准备结果时清普通订单引用，后续直接采用 Lane 编码。其余按下单、撤单、改单、触发路径确定订单，通用路径按变更订单 ID 顺序借取仍存在的订单。 */
    public void materializeCommandOrderViews(OrderCommand pending) {
        if (pending.laneResultPrepared()) {
            clearOrderViews();
            return;
        }
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
                OrderCommand.ReplaceOrderCheck orderCheck = owner.requireMatchingOrderCheck(pending);
                materializeResponseOrders(pending, orderCheck.originalOrderId(), orderCheck.command().orderId());
                return;
            }
            case TRIGGER -> {
                materializeResponseOrder(pending, pending.settlementPlan().takerOrderId());
                return;
            }
            default -> { }
        }
        commandSingleOrder = null;
        commandOrderSources.clear();
        for (int index = 0; index < commandChangedOrderIds.size(); index++) {
            long orderId = primitiveOrderId(commandChangedOrderIds, index);
            Order order = owner.runtimeOrder(orderId);
            if (order != null) commandOrderSources.add(order, owner.identities().instrumentId(order.symbolId()));
        }
    }

    /** 列表支持 PrimitiveLongView 时直接读 long，否则按普通 List 取值解箱。 */
    private static long primitiveOrderId(List<Long> orderIds, int index) {
        if (orderIds instanceof PrimitiveLongView primitive) return primitive.primitiveValueAt(index);
        return orderIds.get(index);
    }

    /** 清多订单源列表，再按 ID 借用一个可空订单作为当前单订单响应源。 */
    void materializeResponseOrder(long orderId) {
        commandOrderSources.clear();
        Order order = owner.runtimeOrder(orderId);
        commandSingleOrder = order;
    }

    /** 命令槽已有 Lane 结果时从槽中取订单，否则使用 Owner 当前可读响应订单。 */
    private Order responseOrder(OrderCommand pending, long orderId) {
        if (pending != null && pending.laneResultPrepared()) {
            return pending.laneResultOrder(orderId);
        }
        return owner.runtimeOrder(orderId);
    }

    /** 与订单来源保持一致：Lane 结果从槽中取币对文本，其余从 Owner 运行时解析。 */
    private String responseOrderSymbol(OrderCommand pending, long orderId, Order order) {
        if (pending != null && pending.laneResultPrepared()) {
            return pending.laneResultSymbol(orderId);
        }
        return owner.identities().instrumentId(order.symbolId());
    }

    private void materializeResponseOrder(OrderCommand pending, long orderId) {
        commandOrderSources.clear();
        commandSingleOrder = responseOrder(pending, orderId);
    }

    private void materializeResponseOrders(OrderCommand pending, long firstOrderId, long secondOrderId) {
        commandOrderSources.clear();
        Order first = responseOrder(pending, firstOrderId);
        Order second = responseOrder(pending, secondOrderId);
        if (first == null) {
            if (second == null) {
                commandSingleOrder = null;
            } else {
                commandSingleOrder = second;
            }
        } else if (second == null) {
            commandSingleOrder = first;
        } else {
            commandSingleOrder = null;
            commandOrderSources.add(first, responseOrderSymbol(pending, firstOrderId, first));
            commandOrderSources.add(second, responseOrderSymbol(pending, secondOrderId, second));
        }
    }

    /** 不提供撮合槽和结果时选择当前已有特殊响应。 */
    public byte[] commandResultData() {
        return commandResultData(null, null);
    }

    /** 按风险控制、资金费、强平进度、批量强平、到期、触发的固定优先级选择第一个预设结果。否则采用 Lane 已编码切片或在撮合信息齐全时编码订单。 */
    public byte[] commandResultData(
            OrderCommand pending,
            OrderMatchingResult matchingResult) {
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
        if (commandTriggerOrderViews != null) {
            return setResponse(com.surprising.aeron.protocol.CoreTriggerOrderCodec.encodeList(commandTriggerOrderViews));
        }
        if (pending != null) {
            byte[] prepared = pending.lanePreparedResponse();
            if (pending.laneResultPrepared()) {
                if (prepared == null) throw new IllegalStateException("Lane response was not encoded");
                return setResponse(prepared, 0, pending.lanePreparedResponseLength());
            }
        }
        if (commandSingleOrder == null && commandOrderSources.isEmpty()) {
            return setResponse(EMPTY_RESULT);
        }
        if (pending == null || matchingResult == null) {
            return setResponse(EMPTY_RESULT);
        }
        try {
            if (commandSingleOrder != null) {
                commandSingleOrderSource.set(commandSingleOrder, owner.identities().instrumentId(commandSingleOrder.symbolId()));
                int length = CoreCommandResultCodec.encodedSingleOrderLength(commandSingleOrderSource);
                byte[] destination = prepareResponseStorage(length);
                responseLength = CoreCommandResultCodec.encodeSingleOrderInto(
                        commandSingleOrderSource, destination, responseOffset);
                return destination;
            }
            if (!commandOrderSources.isEmpty()) {
                int length = CoreCommandResultCodec.encodedLength(commandOrderSources, List.of());
                byte[] destination = prepareResponseStorage(length);
                responseLength = CoreCommandResultCodec.encodeInto(
                        commandOrderSources, List.of(), destination, responseOffset);
                return destination;
            }
            return setResponse(EMPTY_RESULT);
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

    private byte[] setResponse(byte[] response, int offset, int length) {
        discardResponseStorage();
        if (response == null || length == 0) return setResponse(EMPTY_RESULT);
        if (offset < 0 || length < 0 || offset > response.length - length) {
            throw new IllegalArgumentException("invalid prepared Lane response");
        }
        responseStorage = response;
        responseOffset = offset;
        responseLength = length;
        return responseStorage;
    }

    private byte[] prepareResponseStorage(int length) {
        discardResponseStorage();
        responseOffset = 0;
        responseLength = length;
        responseStorage = responseSlotPool.acquire(length);
        return responseStorage;
    }

    /** 归还当前尚未移交响应的一份发送引用，再把工作区恢复为空。 */
    private void discardResponseStorage() {
        if (responseStorage != EMPTY_RESULT) responseSlotPool.release(responseStorage);
        responseStorage = EMPTY_RESULT;
        responseOffset = 0;
        responseLength = 0;
    }

    /** 外层已把响应交给终态或账本后，清本工作区引用和有效范围，不调用 release，避免下一命令提前归还已移交字节。 */
    public void transferResponseOwnership() {
        responseStorage = EMPTY_RESULT;
        responseOffset = 0;
        responseLength = 0;
    }

    /** 读取当前响应有效切片偏移，移交后工作区恢复为零。 */
    public int responseDataOffset() { return responseOffset; }
    /** 读取当前响应有效字节数，不是底层数组容量。 */
    public int responseDataLength() { return responseLength; }

    /** Owner 当前提交作用域使用的可增长只读列表门面，底层订单源适配器按历史最高用量复用。清列表只释放引用，编码器不能长期保留这些源。 */
    private static final class OrderSourceList extends AbstractList<CoreOrderStateSource> implements RandomAccess {
        /** 按当前有效顺序保存的复用订单源适配器数组。 */
        private OrderSource[] values;
        @Accessors(fluent = true, chain = false)
        @Getter(onMethod_ = {@Override})
        private int size;

        private OrderSourceList(int initialCapacity) { values = new OrderSource[initialCapacity]; }

        private void add(Order order, String instrumentId) {
            if (size == values.length) {
                values = java.util.Arrays.copyOf(values, Math.multiplyExact(values.length, 2));
            }
            OrderSource source = values[size];
            if (source == null) values[size] = source = new OrderSource();
            source.set(order, instrumentId);
            size++;
        }

        /** 清本次已用适配器里的订单引用并把有效数量归零，保留数组和适配器供下次复用。 */
        @Override public void clear() {
            for (int index = 0; index < size; index++) values[index].clear();
            size = 0;
        }

        /** 检查当前有效范围后借出对应源适配器。 */
        @Override public CoreOrderStateSource get(int index) {
            if (index < 0 || index >= size) throw new IndexOutOfBoundsException(index);
            return values[index];
        }

    }

    /** 直接绑定 primitive 变更集合的 List 只读门面，提交挂起后由原槽接手集合，恢复时重新绑定。不能跨命令把门面当作独立 ID 快照。 */
    private static final class MutableLongListView extends AbstractList<Long> implements RandomAccess, PrimitiveLongView {
        private PrimitiveLongChangeSet values;

        /** 把只读列表门面绑定到当前 primitive 集合，列表本身不另存 ID。 */
        void bind(PrimitiveLongChangeSet values) { this.values = values; }

        /** 按下标取当前 long 并按对象列表契约装箱，热路径可使用 primitiveValueAt。 */
        @Override public Long get(int index) { return values.valueAt(index); }
        /** 返回当前绑定集合数量，尚未绑定时为零。 */
        @Override public int size() { return values == null ? 0 : values.size(); }
        /** 通过同一绑定集合返回 primitive 元素数。 */
        @Override public int primitiveSize() { return size(); }
        /** 按下标直接返回 long，避免经对象列表返回值装箱。 */
        @Override public long primitiveValueAt(int index) { return values.valueAt(index); }
    }

}
