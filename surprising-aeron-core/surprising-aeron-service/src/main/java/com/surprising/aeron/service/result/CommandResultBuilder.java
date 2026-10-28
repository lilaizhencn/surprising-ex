package com.surprising.aeron.service.result;

import com.surprising.aeron.protocol.CoreCommandResultCodec;
import com.surprising.aeron.protocol.CoreFundingProgressCodec;
import com.surprising.aeron.protocol.CoreFundingProgressView;
import com.surprising.aeron.protocol.CoreLiquidationBatchResultCodec;
import com.surprising.aeron.protocol.CoreLiquidationBatchResultView;
import com.surprising.aeron.protocol.CoreLiquidationProgressCodec;
import com.surprising.aeron.protocol.CoreLiquidationProgressView;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreOrderStateSource;
import com.surprising.aeron.protocol.CoreOrderType;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreRiskScanControlCodec;
import com.surprising.aeron.protocol.CoreRiskScanControlView;
import com.surprising.aeron.protocol.CoreSettlementProgressCodec;
import com.surprising.aeron.protocol.CoreSettlementProgressView;
import com.surprising.aeron.protocol.CoreTimeInForce;
import com.surprising.aeron.service.core.TradingCoreRuntime;
import com.surprising.aeron.service.matcher.DeterministicExchangeCoreAdapter.CancellationOrder;
import com.surprising.aeron.service.matcher.MatchingResult;
import com.surprising.aeron.service.order.CommandSlot;
import com.surprising.aeron.service.order.command.ResolvedMatchingAdmission;
import com.surprising.aeron.service.order.state.OrderRuntime;
import com.surprising.aeron.support.collection.PrimitiveLongChangeSet;
import com.surprising.aeron.support.collection.PrimitiveLongView;
import com.surprising.product.api.ProductLine;
import java.util.AbstractList;
import java.util.List;
import java.util.RandomAccess;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import static com.surprising.aeron.service.core.TradingCoreRuntime.*;

/**
 * 交易步骤 5–6：Owner 串行复用的当前命令结果工作区，收集变更 ID、借用可编码订单源，并在提交边界选择响应内容。它不执行撮合或资金计算；响应数组可能来自共享池，移交后不能由下一命令提前归还。
 */
public final class CommandResultBuilder {
    /** 所有无内容响应共用的空字节数组。 */
    private static final byte[] EMPTY_RESULT = TradingCoreRuntime.EMPTY_RESPONSE_DATA;
    /** 所属交易运行时，负责命令作用域和状态读取边界。 */
    final TradingCoreRuntime owner;
    /** 当前响应编码共用的固定槽池。 */
    private final ResponseArena responseArena;
    /** 当前工作区仍持有的响应底层数组，移交后清为空。 */
    private byte[] responseStorage = EMPTY_RESULT;
    /** 当前工作区响应有效切片起始偏移。 */
    private int responseOffset;
    /** 当前工作区响应有效字节数。 */
    private int responseLength;

    /**
     * 关联所属运行时的响应池；传空 Owner 仅创建一个小响应池用于有限的独立使用，需要 Owner 的业务方法仍不能在空 Owner 下调用。
     * @param owner 所属运行时；空值只支持不依赖 Owner 的有限辅助操作
     */
    public CommandResultBuilder(TradingCoreRuntime owner) {
        this.owner = owner;
        this.responseArena = owner == null ? new ResponseArena(1, ResponseArena.DEFAULT_SLOT_BYTES)
                : owner.responseArena();
    }

    /** 当前单订单编码直接借用的可空运行订单。 */
    private OrderRuntime commandSingleOrder;
    /** 单订单编码使用的复用协议源适配器。 */
    private final OrderRuntimeSource commandSingleOrderSource = new OrderRuntimeSource();
    /** 多订单编码使用的复用源列表，容量按实际需要增长。 */
    private final OrderSourceList commandOrderSources = new OrderSourceList(2);

    /**
     * 清当前单订单引用及多订单源列表，不释放权威订单，也不修改账户状态。
     */
    public void clearOrderViews() {
        commandSingleOrder = null;
        commandOrderSources.clear();
    }

    /**
     * 先归还本工作区尚未移交的响应字节，再清当前订单与进度结果、变更列表及 primitive 收集集合，为新命令建立空工作区。
     */
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

    /** 当前命令公开结果用户列表，物化时绑定当前 primitive 集合。 */
    public List<Long> commandChangedUserIds;

    /** 当前命令公开结果订单列表，物化时绑定当前 primitive 集合。 */
    public List<Long> commandChangedOrderIds;

    /**
     *  当前作用域收集的 primitive 用户 ID 集合，挂起时可随命令槽交接。
     * 借出当前命令的 primitive 用户 ID 集合，所有权仍随 Owner 命令作用域。
     * 把当前命令 primitive 用户集合引用替换为指定集合，供挂起及恢复作用域交接；不复制内容。
     * @param changedUserIds 接手续步时要使用的用户 primitive 集合
     */
    @Accessors(fluent = true, chain = false)
    @Getter
    @Setter
    PrimitiveLongChangeSet changedUserIds = new PrimitiveLongChangeSet();

    /**
     *  当前作用域收集的 primitive 订单 ID 集合。
     * 借出当前命令的 primitive 订单 ID 集合，不创建副本。
     * 交接当前命令 primitive 订单集合引用，不复制或清空被交入集合。
     * @param changedOrderIds 接手续步时要使用的订单 primitive 集合
     */
    @Accessors(fluent = true, chain = false)
    @Getter
    @Setter
    PrimitiveLongChangeSet changedOrderIds = new PrimitiveLongChangeSet();
    /** 当前命令用户键是否已由 Lane 结算准确提供。 */
    private boolean laneCommitIdsSeeded;

    /** 绑定当前用户集合的只读 List 门面，不独立保存用户 ID。 */
    private final MutableLongListView directChangedUsers = new MutableLongListView();
    /** 绑定当前订单集合的只读 List 门面，不独立保存订单 ID。 */
    private final MutableLongListView directChangedOrders = new MutableLongListView();

    /**
     *  当前命令已记录的成交数。
     * 读取当前命令累计成交数。
     * 写入当前命令累计成交数，本入口直接赋值，不增加校验或交易动作。
     * @param commandTradeCount 当前命令的成交数，新值直接覆盖
     */
    @Accessors(fluent = true, chain = false)
    @Getter
    @Setter
    long commandTradeCount;

    /** 当前资金费命令预设的分页进度响应。 */
    public CoreFundingProgressView commandFundingProgress;

    /** 当前强平命令预设的执行进度响应。 */
    public CoreLiquidationProgressView commandLiquidationProgress;

    /** 当前批量强平命令预设的逐项结果。 */
    public CoreLiquidationBatchResultView commandLiquidationBatchResult;

    /** 当前到期结算命令预设的分页进度响应。 */
    public CoreSettlementProgressView commandSettlementProgress;

    /** 当前风险扫描控制命令预设响应，选择响应时优先处理。 */
    public CoreRiskScanControlView commandRiskScanControl;

    /** 当前触发单命令预设的条件记录响应列表。 */
    public java.util.List<com.surprising.aeron.protocol.CoreTriggerOrderStateView> commandTriggerOrderViews;

    /**
     * 先让 Owner 接续当前命令的变更集合，再登记一个变化用户 ID。
     * @param userId 本次需要收集的变化用户 ID
     */
    public void markUserChanged(long userId) {
        owner.seedChangeAccumulators();
        changedUserIds.add(userId);
    }

    /**
     * 先让 Owner 接续当前命令的变更集合，再登记一个变化订单 ID。
     * @param orderId 本次需要收集或编码的订单 ID
     */
    public void markOrderChanged(long orderId) {
        owner.seedChangeAccumulators();
        changedOrderIds.add(orderId);
    }

    /**
     * 接续已有变更集合后添加用户 ID，并把公开结果列表置空等待重新绑定；本方法不清已有 primitive 集合，不表示只保留这一个 ID。
     * @param userId 本次需要收集的变化用户 ID
     */
    public void setSingleChangedUser(long userId) {
        owner.seedChangeAccumulators();
        changedUserIds.add(userId);
        commandChangedUserIds = List.of();
    }

    /**
     * 接续已有变更集合后添加订单 ID，并清公开结果列表引用；不在这里清已有 primitive 订单集合。
     * @param orderId 本次需要收集或编码的订单 ID
     */
    public void setSingleChangedOrder(long orderId) {
        owner.seedChangeAccumulators();
        changedOrderIds.add(orderId);
        commandChangedOrderIds = List.of();
    }

    /**
     * 接续当前命令变更集合并把结果列表置空，保留 primitive 已收集内容等待继续追加。
     */
    public void beginChangedUsers() {
        owner.seedChangeAccumulators();
        commandChangedUserIds = List.of();
    }

    /**
     * 直接把用户 ID 追加到当前 primitive 集合，本入口不重新向 Owner 接续作用域。
     * @param userId 本次需要收集的变化用户 ID
     */
    public void addChangedUser(long userId) {
        changedUserIds.add(userId);
    }

    /**
     * 接续当前集合，从非空撤单条目收集用户 ID；空输入或空条目跳过，结果列表先置空待后续绑定。
     * @param orders 可空撤单订单序列，空条目跳过
     */
    public void addChangedUsersFromOrders(Iterable<CancellationOrder> orders) {
        owner.seedChangeAccumulators();
        commandChangedUserIds = List.of();
        if (orders == null) return;
        for (var /* 本轮借用的现存订单或撤单条目，空项按当前入口规则跳过 */ order : orders) if (order != null) changedUserIds.add(order.userId());
    }

    /**
     * 接续当前集合，从非空资金费记录收集用户 ID；空输入和空记录跳过。
     * @param payments 可空资金费记录序列，空条目跳过
     */
    public void addChangedUsersFromFundingPayments(
            Iterable<? extends com.surprising.aeron.protocol.CoreFundingPaymentView> payments) {
        owner.seedChangeAccumulators();
        commandChangedUserIds = List.of();
        if (payments == null) return;
        for (var /* 当前资金费记录，用它收集变化用户 ID */ payment : payments) if (payment != null) changedUserIds.add(payment.userId());
    }

    /**
     * 账本尚未启动时清冷阶段资金工作区，再清当前用户和订单 ID 集合及 Lane 已供给标记；需要有效 Owner 和运行时。
     */
    public void resetChangeAccumulators() {
        changedUserIds.clear();
        changedOrderIds.clear();
        laneCommitIdsSeeded = false;
    }

    /**
     * 标记 Lane 结算已经提供当前命令的准确用户变更键，后续普通结果物化不再额外扫描 Owner 变化索引。
     */
    public void markLaneCommitIdsSeeded() { laneCommitIdsSeeded = true; }

    /**
     * 接续当前集合；只有尚未由 Lane 提供准确键时才补 Owner 用户变更，再把用户和订单结果绑定为 primitive 集合的只读视图，不创建另一份 ID 列表。
     */
    public void materializeChangeAccumulators() {
        owner.seedChangeAccumulators();
        // Lane 结算若已交出当前命令的准确用户键，就不再重复收集 Owner 的整体变化索引。
        // 否则提交挂起期间可能把较早命令的用户混入当前结果。
        if (!laneCommitIdsSeeded) owner.runtimeState().acceptChangedUserIds(changedUserIds::add);
        directChangedUsers.bind(changedUserIds);
        directChangedOrders.bind(changedOrderIds);
        commandChangedUserIds = directChangedUsers;
        commandChangedOrderIds = directChangedOrders;
    }

    /**
     * 直接控制命令接续集合并补入 Owner 用户变化，然后重新绑定两组只读视图；此入口不跳过 Owner 变化收集。
     */
    public void materializeDirectChangeAccumulators() {
        owner.seedChangeAccumulators();
        owner.runtimeState().acceptChangedUserIds(changedUserIds::add);
        directChangedUsers.bind(changedUserIds);
        directChangedOrders.bind(changedOrderIds);
        commandChangedUserIds = directChangedUsers;
        commandChangedOrderIds = directChangedOrders;
    }

    /**
     * Lane 已准备结果时清普通订单引用，后续直接采用 Lane 编码；其余按下单、撤单、改单、触发路径确定订单，通用路径按变更订单 ID 顺序借取仍存在的订单。
     * @param pending 当前撮合命令槽，包含操作、准入及可能已编码的 Lane 响应
     */
    public void materializeCommandOrderViews(CommandSlot pending) {
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
                // 改单已解析准入，给出原订单与新订单身份
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
        commandSingleOrder = null;
        commandOrderSources.clear();
        for (/* 当前变化订单或有效适配器的遍历下标 */ int index = 0; index < commandChangedOrderIds.size(); index++) {
            // 当前需要尝试读取并加入响应的订单 ID
            long orderId = primitiveOrderId(commandChangedOrderIds, index);
            // 本轮借用的现存订单或撤单条目，空项按当前入口规则跳过
            OrderRuntime order = owner.runtimeOrder(orderId);
            if (order != null) commandOrderSources.add(order, owner.identities().instrumentId(order.symbolId()));
        }
    }

    /**
     * 列表支持 PrimitiveLongView 时直接读 long，否则按普通 List 取值解箱。
     * @param orderIds 按指定顺序准备响应的订单 ID 列表或可变参数
     * @param index 当前有效列表范围内的元素下标
     */
    private static long primitiveOrderId(List<Long> orderIds, int index) {
        if (orderIds instanceof PrimitiveLongView /* 支持直接 long 读取的列表视图，避免普通 List 返回值装箱 */ primitive) return primitive.primitiveValueAt(index);
        return orderIds.get(index);
    }

    /**
     * 清多订单源列表，再按 ID 借用一个可空订单作为当前单订单响应源。
     * @param orderId 本次需要收集或编码的订单 ID
     */
    void materializeResponseOrder(long orderId) {
        commandOrderSources.clear();
        // 本轮借用的现存订单或撤单条目，空项按当前入口规则跳过
        OrderRuntime order = owner.runtimeOrder(orderId);
        commandSingleOrder = order;
    }

    /**
     * 命令槽已有 Lane 结果时从槽中取订单，否则使用 Owner 当前可读响应订单；不在此创建新的账户状态。
     * @param pending 当前撮合命令槽，包含操作、准入及可能已编码的 Lane 响应
     * @param orderId 本次需要收集或编码的订单 ID
     */
    private OrderRuntime responseOrder(CommandSlot pending, long orderId) {
        if (pending != null && pending.laneResultPrepared()) {
            return pending.laneResultOrder(orderId);
        }
        return owner.runtimeOrder(orderId);
    }

    /**
     * 与订单来源保持一致：Lane 结果从槽中取币对文本，其余从 Owner 运行时解析。
     * @param pending 当前撮合命令槽，包含操作、准入及可能已编码的 Lane 响应
     * @param orderId 本次需要收集或编码的订单 ID
     * @param order 当前有效状态读取边界中借用的订单
     */
    private String responseOrderSymbol(CommandSlot pending, long orderId, OrderRuntime order) {
        if (pending != null && pending.laneResultPrepared()) {
            return pending.laneResultSymbol(orderId);
        }
        return owner.identities().instrumentId(order.symbolId());
    }

    /**
     * 按命令槽实际来源借取单订单，清多订单源列表，供原编码边界使用。
     * @param pending 当前撮合命令槽，包含操作、准入及可能已编码的 Lane 响应
     * @param orderId 本次需要收集或编码的订单 ID
     */
    private void materializeResponseOrder(CommandSlot pending, long orderId) {
        commandOrderSources.clear();
        commandSingleOrder = responseOrder(pending, orderId);
    }

    /**
     * 按命令槽实际来源处理两个订单及其币对标识；只在两者都存在时建立双源列表，单个存在时使用单订单引用。
     * @param pending 当前撮合命令槽，包含操作、准入及可能已编码的 Lane 响应
     * @param firstOrderId 原单或双订单响应中的第一个 ID
     * @param secondOrderId 新单或双订单响应中的第二个 ID
     */
    private void materializeResponseOrders(CommandSlot pending, long firstOrderId, long secondOrderId) {
        commandOrderSources.clear();
        // 按第一个 ID 查到的可空订单
        OrderRuntime first = responseOrder(pending, firstOrderId);
        // 按第二个 ID 查到的可空订单
        OrderRuntime second = responseOrder(pending, secondOrderId);
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

    /**
     * 不提供撮合槽和结果时选择当前已有特殊响应；普通订单编码条件不齐则返回空内容。
     */
    public byte[] commandResultData() {
        return commandResultData(null, null);
    }

    /**
     * 按风险控制、资金费、强平进度、批量强平、到期、触发的固定优先级选择第一个预设结果；否则采用 Lane 已编码切片或在撮合信息齐全时编码订单。Lane 标记已准备却无字节时报错，普通订单编码的 IllegalArgumentException 则清为空响应。
     * @param pending 当前撮合命令槽，包含操作、准入及可能已编码的 Lane 响应
     * @param matchingResult 普通订单编码分支的存在性条件，当前实现不读取其内部字段
     */
    public byte[] commandResultData(
            CommandSlot pending,
            MatchingResult matchingResult) {
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
            // 当前命令槽中 Lane 已准备的可空响应底层数组
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
                // 编码器计算的当前有效响应字节数
                int length = CoreCommandResultCodec.encodedSingleOrderLength(commandSingleOrderSource);
                // 向共享响应池申请的实际编码目标数组
                byte[] destination = prepareResponseStorage(length);
                responseLength = CoreCommandResultCodec.encodeSingleOrderInto(
                        commandSingleOrderSource, destination, responseOffset);
                return destination;
            }
            if (!commandOrderSources.isEmpty()) {
                // 编码器计算的当前有效响应字节数
                int length = CoreCommandResultCodec.encodedLength(commandOrderSources, List.of());
                // 向共享响应池申请的实际编码目标数组
                byte[] destination = prepareResponseStorage(length);
                responseLength = CoreCommandResultCodec.encodeInto(
                        commandOrderSources, List.of(), destination, responseOffset);
                return destination;
            }
            return setResponse(EMPTY_RESULT);
        } catch (/* 普通订单编码出现的参数异常，当前实现将响应清为空内容 */ IllegalArgumentException exception) {
            return setResponse(EMPTY_RESULT);
        }
    }

    /**
     * 归还旧工作区响应后直接接入整个新数组，空引用规范为空数组；不复制新字节，也不在此增加响应池引用。
     * @param response 外层已准备好并交给本工作区的数组
     */
    private byte[] setResponse(byte[] response) {
        discardResponseStorage();
        responseStorage = response == null ? EMPTY_RESULT : response;
        responseOffset = 0;
        responseLength = responseStorage.length;
        return responseStorage;
    }

    /**
     * 先归还旧响应，空数组引用或零长度直接规范为空；其余校验有效切片后原样接入，不复制和增加引用。
     * @param response 外层已准备好并交给本工作区的数组
     * @param offset 当前有效响应切片起点
     * @param length 当前有效编码长度或申请的数组长度
     */
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

    /**
     * 归还旧工作区响应，设置有效长度及零偏移，再向共享池申请当前编码目标数组。
     * @param length 当前有效编码长度或申请的数组长度
     */
    private byte[] prepareResponseStorage(int length) {
        discardResponseStorage();
        responseOffset = 0;
        responseLength = length;
        responseStorage = responseArena.acquire(length);
        return responseStorage;
    }

    /**
     * 归还当前尚未移交响应的一份发送引用，再把工作区恢复为空；独立数组不参加池计数。
     */
    private void discardResponseStorage() {
        if (responseStorage != EMPTY_RESULT) responseArena.release(responseStorage);
        responseStorage = EMPTY_RESULT;
        responseOffset = 0;
        responseLength = 0;
    }

    /**
     * 外层已把响应交给终态或账本后，清本工作区引用和有效范围，不调用 release，避免下一命令提前归还已移交字节。
     */
    public void transferResponseOwnership() {
        responseStorage = EMPTY_RESULT;
        responseOffset = 0;
        responseLength = 0;
    }

    /**
     * 读取当前响应有效切片偏移，移交后工作区恢复为零。
     */
    public int responseDataOffset() { return responseOffset; }
    /**
     * 读取当前响应有效字节数，不是底层数组容量。
     */
    public int responseDataLength() { return responseLength; }

    /**
     * Owner 当前提交作用域使用的可增长只读列表门面，底层订单源适配器按历史最高用量复用；清列表只释放引用，编码器不能长期保留这些源。
     */
    private static final class OrderSourceList extends AbstractList<CoreOrderStateSource> implements RandomAccess {
        /** 按当前有效顺序保存的复用订单源适配器数组。 */
        private OrderRuntimeSource[] values;
        /**  当前多订单源列表的有效数量。 返回当前有效源数，不返回预留数组容量。 */
        @Accessors(fluent = true, chain = false)
        @Getter(onMethod_ = {@Override})
        private int size;

        /**
         * 建立指定初始容量的源适配器数组，适配器在实际追加时才按需创建。
         * @param initialCapacity 多订单源数组的初始容量
         */
        private OrderSourceList(int initialCapacity) { values = new OrderRuntimeSource[initialCapacity]; }

        /**
         * 容量不足时翻倍，复用当前位置的订单源适配器并绑定订单及币对；只追加借用视图，不复制权威订单。
         * @param order 当前有效状态读取边界中借用的订单
         * @param instrumentId 与该订单配对的协议币对文本
         */
        private void add(OrderRuntime order, String instrumentId) {
            if (size == values.length) {
                values = java.util.Arrays.copyOf(values, Math.multiplyExact(values.length, 2));
            }
            // 当前位置复用或新建的订单源适配器，只借用本次订单
            OrderRuntimeSource source = values[size];
            if (source == null) values[size] = source = new OrderRuntimeSource();
            source.set(order, instrumentId);
            size++;
        }

        /**
         * 清本次已用适配器里的订单引用并把有效数量归零，保留数组和适配器供下次复用。
         */
        @Override public void clear() {
            for (/* 当前变化订单或有效适配器的遍历下标 */ int index = 0; index < size; index++) values[index].clear();
            size = 0;
        }

        /**
         * 检查当前有效范围后借出对应源适配器。
         * @param index 当前有效列表范围内的元素下标
         */
        @Override public CoreOrderStateSource get(int index) {
            if (index < 0 || index >= size) throw new IndexOutOfBoundsException(index);
            return values[index];
        }

    }

    /**
     * 直接绑定 primitive 变更集合的 List 只读门面，提交挂起后由原槽接手集合，恢复时重新绑定；不能跨命令把门面当作独立 ID 快照。
     */
    private static final class MutableLongListView extends AbstractList<Long> implements RandomAccess, PrimitiveLongView {
        /** 只读门面当前绑定的 primitive 变更集合，未绑定时为空。 */
        private PrimitiveLongChangeSet values;

        /**
         * 把只读列表门面绑定到当前 primitive 集合，列表本身不另存 ID。
         * @param values 要绑定的当前 primitive 变更集合
         */
        void bind(PrimitiveLongChangeSet values) { this.values = values; }

        /**
         * 按下标取当前 long 并按对象列表契约装箱，热路径可使用 primitiveValueAt。
         * @param index 当前有效列表范围内的元素下标
         */
        @Override public Long get(int index) { return values.valueAt(index); }
        /**
         * 返回当前绑定集合数量，尚未绑定时为零。
         */
        @Override public int size() { return values == null ? 0 : values.size(); }
        /**
         * 通过同一绑定集合返回 primitive 元素数。
         */
        @Override public int primitiveSize() { return size(); }
        /**
         * 按下标直接返回 long，避免经对象列表返回值装箱。
         * @param index 当前有效列表范围内的元素下标
         */
        @Override public long primitiveValueAt(int index) { return values.valueAt(index); }
    }

}
