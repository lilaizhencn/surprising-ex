package com.surprising.aeron.service.order.admission;

import com.surprising.aeron.bootstrap.account.SettlementLaneWorker;
import com.surprising.aeron.exception.CoreStateRejectedException;
import com.surprising.aeron.service.account.AccountLaneState;
import com.surprising.aeron.service.account.LaneBalancePatches;
import com.surprising.aeron.service.account.UserRuntime;
import com.surprising.aeron.service.core.TradingAccountRuntime;
import com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.order.state.OrderRuntime;
import com.surprising.aeron.service.settlement.MatcherSettlementChanges;
import java.util.UUID;
import lombok.Getter;
import lombok.experimental.Accessors;

/**
 * 同一用户整批 PLACE 的单向账户准入事件。所属 Lane 按项解析、检查并冻结，保留已成功前缀；可预期失败回退该前缀和客户身份，成功则交出原变更缓冲与获准订单租约，不复制账户镜像。
 */
public final class PlaceBatchAdmissionEvent implements SettlementLaneWorker.Command {
    /** 整批下单沿用的 Core 命令序号。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private long coreSequence;
    /**
     * timestamp：整批订单写入使用的集群业务时间。
     * position：整批命令的集群日志位置。
     */
    private long timestamp, position;
    /** 本批唯一所属用户编号，所有项由同一账户 Lane 准入。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private long userId;
    /** 本批业务命令 UUID，各项订单和冻结沿用该命令归属。 */
    private UUID commandId;
    /** Owner 交接的稳定批次输入及原命令槽入口，Lane 完成前不得复用。 */
    private PlaceBatchIntentSource source;
    /** 调用方借出的逐项解析结果数组，Lane 按下标写入已决策订单输入。 */
    private ResolvedPlaceOrder[] orders;
    /** 与批项对齐的实际客户身份键，失败回退后逐项清零。 */
    private long[] clientKeys;
    /**  原批次槽保存的逐项获准订单租约数组，后续撮合继续使用。 借用本批获准订单租约数组，调用方须遵守原批次生命周期，只使用有效项前缀。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private OrderRuntime[] admittedOrders;
    /** 本批本次有效项数量，只处理借用数组的这一前缀。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private int itemCount;
    /** 已经成功写入订单和冻结的前缀项数，失败时只回退此前缀。 */
    private int admittedCount;
    /** 本批唯一账户 Lane 编号，必须与用户拓扑一致。 */
    private int laneId;
    /** 本批固定的 Matcher 分片，账户准入完成后通知该分片的续步。 */
    private int matcherShard;
    /** 提供原账户写入、批次回退和通知入口的所属运行时。 */
    private TradingAccountRuntime runtime;
    /** 本批原有结算变更缓冲，结果消费时转交一次，不复制第二份账户变化。 */
    private MatcherSettlementChanges changes;
    /** 当前产品身份表，客户键在所属 Lane 准备并在失败时回退。 */
    private RuntimeIdentityRegistry identities;
    /** 本批开始前用户元数据的命令槽前值，供本批失败回退，不是长期账户镜像。 */
    private UserRuntime userBefore;
    /** 保存用户前值的可复用命令槽，回收时清内容但保留存储供下一批使用。 */
    private UserRuntime userBeforeSlot;
    /** 本批准入的业务拒绝、算术或参数错误，已有成功前缀先回退再发布。 */
    private RuntimeException rejection;
    /** Lane 写完结果和拒绝信息后发布的完成位，读取结果及回收前必须观察它。 */
    private volatile boolean completed;

    /**
     * 校验有效项数、借用数组、命令和拓扑范围，绑定本批稳定输入及原变更缓冲，重置成功前缀和结果标记。
     * @param coreSequence 整批下单沿用的 Core 命令序号
     * @param userId 本批唯一所属用户编号，所有项由同一账户 Lane 准入
     * @param commandId 本批业务命令 UUID，各项订单和冻结沿用该命令归属
     * @param orders 调用方借出的逐项解析结果数组，Lane 按下标写入已决策订单输入
     * @param clientKeys 与批项对齐的实际客户身份键，失败回退后逐项清零
     * @param admittedOrders 原批次槽保存的逐项获准订单租约数组，后续撮合继续使用
     * @param itemCount 本批本次有效项数量，只处理借用数组的这一前缀
     * @param laneId 本批唯一账户 Lane 编号，必须与用户拓扑一致
     * @param matcherShard 本批固定的 Matcher 分片，账户准入完成后通知该分片的续步
     * @param runtime 提供原账户写入、批次回退和通知入口的所属运行时
     * @param changes 本批原有结算变更缓冲，结果消费时转交一次，不复制第二份账户变化
     * @param identities 当前产品身份表，客户键在所属 Lane 准备并在失败时回退
     * @param source Owner 交接的稳定批次输入及原命令槽入口，Lane 完成前不得复用
     * @param timestamp 整批订单写入使用的集群业务时间
     * @param position 整批命令的集群日志位置
     */
    public PlaceBatchAdmissionEvent prepare(
            long coreSequence, long userId, UUID commandId, ResolvedPlaceOrder[] orders,
            long[] clientKeys,
            OrderRuntime[] admittedOrders,
            int itemCount, int laneId, int matcherShard,
            TradingAccountRuntime runtime, MatcherSettlementChanges changes, RuntimeIdentityRegistry identities, PlaceBatchIntentSource source, long timestamp, long position) {
        if (timestamp < 0 || position < 0 || coreSequence <= 0 || userId <= 0 || commandId == null || orders == null
                || source == null || identities == null || clientKeys == null || admittedOrders == null || itemCount <= 0
                || itemCount > orders.length || itemCount > clientKeys.length
                || itemCount > admittedOrders.length
                || laneId < 0 || matcherShard < 0
                || runtime == null || matcherShard >= runtime.topology().matchingEngineCount() || changes == null) {
            throw new IllegalArgumentException("invalid place batch admission event");
        }
        this.coreSequence = coreSequence;
        this.timestamp = timestamp;
        this.position = position;
        this.userId = userId;
        this.commandId = commandId;
        this.orders = orders;
        this.source = source;
        this.clientKeys = clientKeys;
        this.admittedOrders = admittedOrders;
        this.itemCount = itemCount;
        this.laneId = laneId;
        this.matcherShard = matcherShard;
        this.runtime = runtime;
        this.changes = changes;
        this.identities = identities;
        admittedCount = 0;
        rejection = null;
        completed = false;
        return this;
    }

    /**
     * 在唯一所属 Lane 捕获用户前值，按项解析决策、准备身份、检查风险及容量并冻结；需有序撤单的平仓冲突拒绝整批准入，其他可预期失败同样回退已获准前缀，最后发布结果。
     * @param lane 执行本批准入的账户 Lane，必须与已绑定 Lane 和用户拓扑一致
     */
    public void execute(AccountLaneState lane) {
        if (lane.laneId() != laneId || laneId != runtime.topology().accountLaneId(userId)) {
            throw new IllegalStateException("place batch admission reached the wrong Account Lane");
        }
        // 本批执行前的用户元数据，存在时捕获到原命令槽作为回退前值。
        UserRuntime currentUser = lane.user(userId);
        userBefore = currentUser == null ? null
                : (userBeforeSlot = currentUser.copyIntoCommandSlot(userBeforeSlot));
        try {
            runtime.enterMatcherSettlementScope(lane, changes);
            lane.admissionIndexCapacity(Math.max(2, itemCount));
            try {
                for (/* 当前处理的批次项下标，所有输入、客户键和获准订单数组按同一下标对齐。 */ int index = 0; index < itemCount; index++) {
                    // 当前项已准备的币对市场输入、OI 和资产路由。
                    var decision = source.decision(index);
                    // 写入该项原命令槽的解析订单，后续检查和冻结都使用同一输入。
                    ResolvedPlaceOrder order = RuntimeOrderAdmission.resolveInto(
                            source.resolvedOrderSlot(index), decision.context(), userId, source.intent(index));
                    orders[index] = order;
                    // 当前项币对的运行时稠密编号。
                    int symbolId = order.symbolId();
                    // 衍生品结算资产或现货买卖方向对应的报价、基础资产编号。
                    int assetId = runtime.productLine().isDerivative() ? decision.settleAssetId()
                            : order.side() == com.surprising.aeron.protocol.CoreOrderSide.BUY
                            ? decision.quoteAssetId() : decision.baseAssetId();
                    // 为当前项客户端标识准备并取出的实际身份键，保留到对齐数组供回退。
                    long key = RuntimeIdentityRegistry.clientKeyValue(
                            identities.prepareClientKeyInLane(lane, userId, order.clientOrderId()));
                    clientKeys[index] = key;
                    // 当前用户、币对和持仓腿的已有持仓身份。
                    long positionKey = identities.findPositionKeyValueInLane(
                            lane, userId, order.instrument(), order.positionSide());
                    if (runtime.productLine().isDerivative() && positionKey != 0) {
                        // 当前项对应已有持仓，逆向订单可能与已有只减仓委托争用平仓容量。
                        var position = lane.position(positionKey);
                        if (position != null && position.signedQuantitySteps() != 0
                                && (position.signedQuantitySteps() > 0) != (order.side() == com.surprising.aeron.protocol.CoreOrderSide.BUY)) {
                            // 当前方向的待成交量及只减仓占量汇总，用来识别必须走顺序撤单的冲突。
                            var summary = lane.admissionOrderIndex(symbolId).inspect(userId, order.instrumentId(),
                                    order.positionSide(), order.side(), order.marginMode());
                            if (summary.reduceOnlyQuantity() > 0 && Math.addExact(Math.addExact(summary.pendingQuantity(), summary.reduceOnlyQuantity()), order.quantitySteps())
                                    > Math.absExact(position.signedQuantitySteps()))
                                throw new CoreStateRejectedException("CLOSE_CAPACITY_REQUIRES_ORDERED_ADMISSION", "close commitments require ordered cancellation");
                        }
                    }
                    // 当前项通过统一账户准入后算出的所需冻结金额。
                    long requiredReservation = RuntimeOrderAdmission.requiredReservationPrepared(
                            runtime, lane, userId, order, decision.openInterestSteps(),
                            lane.admissionOrderIndex(symbolId), key, positionKey,
                            decision.context().lifecycleSettled(), decision.context().fundingInProgress());
                    runtime.placeOrderInLane(lane, userId, order, commandId,
                            requiredReservation, clientKeys[index], symbolId, assetId,
                            coreSequence, null, timestamp, position);
                    // Lane 刚写入的获准订单，交给原批次槽保存后续撮合租约。
                    OrderRuntime live = lane.order(order.orderId());
                    admittedOrders[index] = source.captureAdmittedOrder(index, live);
                    admittedCount++;
                }
                changes.prepareAdmissionLane(laneId);
            } finally {
                lane.admissionIndexCapacity(2);
                runtime.exitMatcherSettlementScope(lane, changes);
            }
        } catch (/* 本批出现的业务拒绝、溢出或非法参数，先回退已成功前缀及身份再保存。 */ CoreStateRejectedException | ArithmeticException | IllegalArgumentException failure) {
            runtime.rollbackPlaceBatchAdmissionInLane(
                    lane, userId, coreSequence, orders, clientKeys, admittedCount, userBefore);
            for (/* 失败回退时遍历所有已准备客户身份的项下标，回退后清零对应键。 */ int i = 0; i < itemCount; i++) {
                if (orders[i] != null) identities.rollbackClientKeyInLane(lane, userId, orders[i].clientOrderId(), clientKeys[i]);
                clientKeys[i] = 0;
            }
            admittedCount = 0;
            rejection = failure;
        }
        // 完成发布前取得的通知目标，用于唤醒原 Matcher 分片续步。
        TradingAccountRuntime completionRuntime = runtime;
        lane.recordCommandOperation();
        completed = true;
        completionRuntime.publishAccountAdmissionReadyShard(matcherShard);
    }

    /**
     * 要求 Lane 已完成且变更缓冲已交出，清理借用数组和结果引用，清空但保留用户前值槽以便后续复用。
     */
    public void clear() {
        if (!complete() || changes != null) {
            throw new IllegalStateException("cannot recycle an incomplete place batch admission");
        }
        source = null;
        orders = null;
        clientKeys = null;
        admittedOrders = null;
        userBefore = null;
        if (userBeforeSlot != null) userBeforeSlot.clearCommandSlot();
        runtime = null;
        identities = null;
        rejection = null;
        itemCount = 0;
        admittedCount = 0;
        matcherShard = 0;
    }

    /**
     * 返回 Lane 是否发布了本批准入完成，成功和已回退的业务拒绝都可完成。
     */
    public boolean complete() { return completed; }
    /**
     * 完成后读取本批原始拒绝异常，成功时为 null。
     */
    public RuntimeException rejection() {
        if (!complete()) throw new IllegalStateException("place batch admission is incomplete");
        return rejection;
    }
    /**
     * 完成后读取供本批回退的用户前值，原来没有用户时为 null。
     */
    public UserRuntime userBefore() {
        if (!complete()) throw new IllegalStateException("place batch admission is incomplete");
        return userBefore;
    }
    /**
     * 按有效项下标读取该项获准订单租约，不新建订单副本。
     * @param index 本批有效前缀内的零起始项下标
     */
    OrderRuntime admittedOrder(int index) { return admittedOrders[index]; }
    /**
     * 按有效项下标读取 Lane 已解析的订单决策输入，引用仍受原批次生命周期约束。
     * @param index 本批有效前缀内的零起始项下标
     */
    ResolvedPlaceOrder order(int index) { return orders[index]; }
    /**
     * 完成后一次性交出原结算变更缓冲并清空事件引用，第二次获取或提前读取都报错。
     */
    public MatcherSettlementChanges takeChanges() {
        if (!complete() || changes == null) {
            throw new IllegalStateException("place batch admission changes are unavailable");
        }
        // 本次转交给调用方的原变更缓冲，事件随后不再拥有该引用。
        MatcherSettlementChanges value = changes;
        changes = null;
        return value;
    }

    /**
     * 在事件完成且仍持有变更缓冲时，把本批余额前值交给指定原有补丁缓冲，供后续正确回退使用。
     * @param target 接收本批余额前值的原 LaneBalancePatches 缓冲
     */
    public void copyBalanceBeforeTo(LaneBalancePatches target) {
        if (!complete() || changes == null || target == null) {
            throw new IllegalStateException("place batch admission changes are unavailable");
        }
        changes.copyBalanceBeforeTo(target);
    }
    /**
     * 取出并清空当前变更缓冲，同时设置完成位供清理；调用方负责确保该事件已不会继续由活动 Lane 使用。
     */
    public MatcherSettlementChanges discardChanges() {
        // 中止清理路径取出的变更缓冲，事件清空引用并标记可清理。
        MatcherSettlementChanges value = changes;
        changes = null;
        completed = true;
        return value;
    }

    /**
     * Owner 固定的批量下单输入与命令槽访问边界。
     */
    public interface PlaceBatchIntentSource {
        com.surprising.aeron.protocol.PlaceOrderCommand intent(int index);
        Decision decision(int index);
        ResolvedPlaceOrder resolvedOrderSlot(int index);
        OrderRuntime captureAdmittedOrder(int index, OrderRuntime order);
        record Decision(RuntimeOrderAdmission.Context context, long openInterestSteps,
                        int baseAssetId, int quoteAssetId, int settleAssetId) { }
    }
}
