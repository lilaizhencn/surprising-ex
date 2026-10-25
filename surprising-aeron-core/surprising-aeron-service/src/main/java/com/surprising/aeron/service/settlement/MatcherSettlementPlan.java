package com.surprising.aeron.service.settlement;

import com.surprising.aeron.model.CoreOrderStatus;
import com.surprising.aeron.service.account.AccountLaneState;
import com.surprising.aeron.service.core.TradingAccountRuntime;
import com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.aeron.service.order.admission.ResolvedPlaceOrder;
import com.surprising.aeron.service.order.state.OrderRuntime;
import exchange.core2.core.common.MatcherEventType;
import exchange.core2.core.common.MatcherResult.MatcherEvent;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.experimental.Accessors;
import org.eclipse.collections.impl.set.mutable.primitive.LongHashSet;

/**
 * 下单步骤 4.2：Matcher 根据原生成交事实构建受影响订单和账户 Lane 路由；发布后由所属 Lane 校验当前订单并应用。数组、事件和准入引用受在途槽租约约束，不能当成永久快照。
 */
public final class MatcherSettlementPlan {
    /**  本代 taker 或撤单请求是否被拒绝，不能与已计入成交并存。 返回本计划是否要求按拒单或撤单拒绝处理 taker。 */
    @Accessors(fluent = true, chain = false)
    @Getter(value = AccessLevel.PACKAGE)
    private boolean rejectedTaker;
    /**
     * 保存撮合拒单标记并返回本计划；已有成交时禁止标记拒绝，防止用拒单覆盖已发生的成交事实。
     * @param rejected 是否要求本计划按 taker 拒绝处理
     */
    public MatcherSettlementPlan rejectTaker(boolean rejected) {
        if (rejected && tradeCount != 0) throw new IllegalArgumentException("rejected taker cannot contain trades");
        rejectedTaker = rejected;
        return this;
    }

    /**  本计划对应的 Core 复制命令序号。 返回本计划所属 Core 序号。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private long coreSequence;
    /**  主动订单或撤单目标的编号。 返回当前 taker 或撤单目标编号。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private long takerOrderId;
    /**  当前复制命令的发起用户。 返回发起当前命令的账户用户编号。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private long activeUserId;
    /**  由 taker、maker 及相关变化归集的账户 Lane 位图。 返回本计划实际涉及的账户 Lane 位图。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private long requiredLaneMask;
    /** 本计划自有或内部构造接管的原语订单编号数组，只使用 orderCount 个有效槽。 */
    private long[] orderIds;
    /**  订单身份数组当前代的有效项数。 返回本代受影响订单编号的有效数量。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private int orderCount;
    /** 本代原始 Matcher 事件列表引用，包含成交与其他事件。 */
    private List<MatcherEvent> matcherEvents;
    /**  已校验并计入计划的 TRADE 事件数量。 返回本代已经统计的 TRADE 成交事件数。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private int tradeCount;
    /** 本代前置撤单有效数据指向，可能为空哨兵或自有复用数组。 */
    private long[] preCancellationOrderIds;
    /** 前置撤单有效前缀长度，不等于底层保留容量。 */
    private int preCancellationSize;
    /** 计划独占并保留容量的撤单编号数组，写入成功前缀后借给消费者读取。 */
    private long[] preCancellationStorage;
    /** 深度成交时每个 maker 所属 Lane 的链头下标，负一表示没有事件。 */
    private int[] makerLaneHeads;
    /** 每个成交在同一 maker Lane 链中的后继下标，未启用索引时不读取。 */
    private int[] makerLaneNext;
    /** 构建时缓存的主动账户 Lane，深度索引中该 Lane 仍读取完整原事件顺序。 */
    private int takerLaneId = -1;
    /** 本代是否已构建 maker Lane 事件链，复用旧数组时以此开关判定有效性。 */
    private boolean laneEventsIndexed;
    /** 没有前置撤单时共用的空原语数组。 */
    private static final long[] NO_ORDERS = new long[0];
    /**  构建时保留的准入订单值或命令槽引用，消费者遵守该代租约。 返回构建时保留的准入订单引用，借用期受计划租约限制，可能为空。 */
    @Accessors(fluent = true, chain = false)
    @Getter(value = AccessLevel.PACKAGE)
    private OrderRuntime admittedTaker;
    /** 直达事实校验所需的主动订单方向，空表示不走直达订单校验。 */
    private com.surprising.aeron.protocol.CoreOrderSide directTakerSide;
    /** 直达事实校验所需的币对稠密编号。 */
    private int directTakerSymbolId;
    /** 直达事实校验所需的规范币对对象引用。 */
    private CoreInstrument directTakerInstrument;
    /** 本计划构建阶段独占的原语订单去重集合；借用它的构建路径在 finally 清空，短事件路径不使用它。 */
    private final LongHashSet directOrderKeys = new LongHashSet();

    /**
     * 创建可复用计划，先保留八个订单编号槽，事件和前置撤单使用空值。
     */
    public MatcherSettlementPlan() { orderIds = new long[8]; preCancellationOrderIds = NO_ORDERS; matcherEvents = List.of(); }

    /**
     * 清空本代事件、触发终态、订单引用、有效计数和直达校验字段；保留数组容量，旧原语槽不再属于有效前缀。
     */
    public void clearReferences() {
        matcherEvents = List.of(); completedTrigger = null; preCancellationOrderIds = NO_ORDERS;
        preCancellationSize = 0;
        rejectedTaker = false; laneEventsIndexed = false; orderCount = tradeCount = 0;
        admittedTaker = null; takerLaneId = -1;
        directTakerSide = null;
        directTakerSymbolId = 0;
        directTakerInstrument = null;
    }

    /**
     * 由已准入运行时订单及原生 MatcherResult 构建直达计划，按订单剩余量校验成交；命令序号等外层证据由事件边界核对。
     * @param sequence 当前事实的 Core 序号
     * @param taker 已准入的运行时主动订单引用，或对应重载的不可变已解析准入值
     * @param instrument 当前事实的规范币对对象，必须与订单输入一致
     * @param result 当前构建入口已关联本代命令的撮合结果，原生重载由外层事件核验证据
     * @param runtime 提供拓扑、允许读取的订单视图及构建暂存缓冲的交易运行时
     */
    void buildDirectNative(long sequence, OrderRuntime taker, CoreInstrument instrument,
                           exchange.core2.core.common.MatcherResult result, TradingAccountRuntime runtime) {
        if (taker == null || instrument == null || result == null || instrument != taker.instrument())
            throw new IllegalArgumentException("invalid direct native matcher fact");
        buildDirectNative(sequence, taker.orderId(), taker.userId(), taker.side(), taker.symbolId(),
                taker.instrument(), taker.remainingQuantitySteps(), taker, result, runtime);
    }

    /**
     * 由普通下单不可变准入值构建原生事实，使用完整准入数量，保留无需跨线程读取账户的校验字段。
     * @param sequence 当前事实的 Core 序号
     * @param userId 当前主动、撤单或持仓身份所属用户
     * @param taker 已准入的运行时主动订单引用，或对应重载的不可变已解析准入值
     * @param instrument 当前事实的规范币对对象，必须与订单输入一致
     * @param result 当前构建入口已关联本代命令的撮合结果，原生重载由外层事件核验证据
     * @param runtime 提供拓扑、允许读取的订单视图及构建暂存缓冲的交易运行时
     */
    void buildDirectNative(long sequence, long userId, ResolvedPlaceOrder taker, CoreInstrument instrument,
                           exchange.core2.core.common.MatcherResult result, TradingAccountRuntime runtime) {
        if (userId <= 0 || taker == null || instrument == null || result == null
                || instrument != taker.instrument())
            throw new IllegalArgumentException("invalid direct native matcher fact");
        buildDirectNative(sequence, taker.orderId(), userId, taker.side(), taker.symbolId(),
                taker.instrument(), taker.quantitySteps(), null, result, runtime);
    }

    /**
     * 按原生成交事件构建当前计划；零事件只记 taker 和接受标记，至多四个事件用线性去重，更多事件借用集合并按需建立 Lane 索引，始终校验 taker 累计成交不超量。
     * @param sequence 当前事实的 Core 序号
     * @param orderId 当前主动订单、撤单目标或待检查订单的原语编号
     * @param userId 当前主动、撤单或持仓身份所属用户
     * @param side 主动订单或待核对订单的预期买卖方向
     * @param symbolId 主动订单的币对稠密编号
     * @param takerInstrument 主动订单输入中保存的规范币对对象
     * @param remainingQuantity 本代主动订单可供累计成交的数量上限
     * @param taker 已准入的运行时主动订单引用，或对应重载的不可变已解析准入值
     * @param result 当前构建入口已关联本代命令的撮合结果，原生重载由外层事件核验证据
     * @param runtime 提供拓扑、允许读取的订单视图及构建暂存缓冲的交易运行时
     */
    private void buildDirectNative(long sequence, long orderId, long userId,
                                   com.surprising.aeron.protocol.CoreOrderSide side, int symbolId,
                                   CoreInstrument takerInstrument, long remainingQuantity, OrderRuntime taker,
                                   exchange.core2.core.common.MatcherResult result, TradingAccountRuntime runtime) {
        clearReferences();
        coreSequence = sequence; admittedTaker = taker;
        directTakerSide = side; directTakerSymbolId = symbolId; directTakerInstrument = takerInstrument;
        takerOrderId = orderId; activeUserId = userId;
        takerLaneId = runtime.topology().accountLaneId(activeUserId);
        requiredLaneMask = runtime.topology().accountLaneMask(activeUserId);
        matcherEvents = result.events();
        // 原生结果的全部事件数量，用于选择短前缀或集合去重路径。
        int eventCount = matcherEvents.size();
        if (eventCount == 0) {
            orderIds[0] = takerOrderId;
            orderCount = 1;
            rejectTaker(!nativeAccepted(result));
            return;
        }
        // 容纳本代主动订单、maker 及撤单身份所需的数组容量。
        int capacity = Math.addExact(eventCount, 1);
        if (orderIds.length < capacity) orderIds = new long[Math.max(capacity, orderIds.length * 2)];
        if (eventCount <= 4) {
            orderIds[0] = takerOrderId;
            // 本轮筛选或线性去重后保留的有效项数。
            int count = 1;
            // 主动订单按已遍历成交逐项扣减后的待成交数量。
            long remaining = remainingQuantity;
            for (/* 当前遍历的原始撮合事件、撤单结果或订单前缀下标。 */ int index = 0; index < eventCount; index++) {
                // 当前原始 Matcher 事件，TRADE 才参与成交数量与 maker 路由。
                MatcherEvent event = matcherEvents.get(index);
                if (event == null) throw new IllegalArgumentException("missing matcher event");
                if (event.eventType() != MatcherEventType.TRADE) continue;
                if (event.price() <= 0 || event.size() <= 0 || event.matchedOrderId() <= 0
                        || event.matchedOrderUid() <= 0 || event.matchedOrderUid() == activeUserId)
                    throw new IllegalStateException("invalid direct fill identity or quantity");
                remaining = Math.subtractExact(remaining, event.size());
                if (remaining < 0) throw new IllegalStateException("fill exceeds admitted taker quantity");
                count = addUniqueLinear(orderIds, count, event.matchedOrderId());
                requiredLaneMask |= runtime.topology().accountLaneMask(event.matchedOrderUid());
                tradeCount++;
            }
            orderCount = count;
            rejectTaker(!nativeAccepted(result));
            return;
        }
        // 本计划独占复用的原语订单去重集合，构建结束即清空。
        LongHashSet keys = directOrderKeys;
        try {
            orderCount = addUnique(keys, orderIds, 0, takerOrderId);
            // 主动订单按已遍历成交逐项扣减后的待成交数量。
            long remaining = remainingQuantity;
            for (/* 当前遍历的原始撮合事件、撤单结果或订单前缀下标。 */ int index = 0; index < eventCount; index++) {
                // 当前原始 Matcher 事件，TRADE 才参与成交数量与 maker 路由。
                MatcherEvent event = matcherEvents.get(index);
                if (event == null) throw new IllegalArgumentException("missing matcher event");
                if (event.eventType() != MatcherEventType.TRADE) continue;
                if (event.price() <= 0 || event.size() <= 0 || event.matchedOrderId() <= 0
                        || event.matchedOrderUid() <= 0 || event.matchedOrderUid() == activeUserId)
                    throw new IllegalStateException("invalid direct fill identity or quantity");
                remaining = Math.subtractExact(remaining, event.size());
                if (remaining < 0) throw new IllegalStateException("fill exceeds admitted taker quantity");
                orderCount = addUnique(keys, orderIds, orderCount, event.matchedOrderId());
                requiredLaneMask |= runtime.topology().accountLaneMask(event.matchedOrderUid());
                tradeCount++;
            }
            rejectTaker(!nativeAccepted(result));
            indexLaneEvents(runtime);
        } finally { keys.clear(); }
    }

    /**
     * 在短原语前缀中线性查重，新编号追加到末尾并返回新有效长度；调用方预先保证容量。
     * @param values 预先保证容量的原语订单编号数组
     * @param size 数组当前有效编号数量
     * @param value 要附加的订单编号，或对应入口的触发单终态值
     */
    private static int addUniqueLinear(long[] values, int size, long value) {
        for (/* 线性检查短订单前缀的当前下标。 */ int i = 0; i < size; i++) {
            if (values[i] == value) return size;
        }
        values[size] = value;
        return size + 1;
    }

    /**
     * 借用给定集合检查编号是否首次出现，新编号追加到原语有效前缀，容量由构建方保证。
     * @param unique 当前构建边界独占的原语订单去重集合
     * @param values 预先保证容量的原语订单编号数组
     * @param size 数组当前有效编号数量
     * @param value 要附加的订单编号，或对应入口的触发单终态值
     */
    private static int addUnique(LongHashSet unique, long[] values, int size, long value) {
        if (!unique.add(value)) return size;
        values[size] = value;
        return size + 1;
    }

    /**
     * 检查原生撤单不夹带成交，重置并保存目标用户路由和接受标记；接受时记录一个订单身份，拒绝时有效身份数为零。
     * @param sequence 当前事实的 Core 序号
     * @param userId 当前主动、撤单或持仓身份所属用户
     * @param orderId 当前主动订单、撤单目标或待检查订单的原语编号
     * @param result 当前构建入口已关联本代命令的撮合结果，原生重载由外层事件核验证据
     * @param runtime 提供拓扑、允许读取的订单视图及构建暂存缓冲的交易运行时
     */
    void buildDirectNativeCancellation(long sequence, long userId, long orderId,
                                       exchange.core2.core.common.MatcherResult result,
                                       TradingAccountRuntime runtime) {
        if (orderId <= 0 || result == null) throw new IllegalArgumentException("invalid direct cancellation fact");
        for (/* 当前遍历的原始撮合事件、撤单结果或订单前缀下标。 */ int index = 0; index < result.events().size(); index++) {
            // 当前原始 Matcher 事件，TRADE 才参与成交数量与 maker 路由。
            MatcherEvent event = result.events().get(index);
            if (event == null || event.eventType() == MatcherEventType.TRADE)
                throw new IllegalStateException("cancel result contains a trade");
        }
        clearReferences();
        // 原生撤单结果是否为 SUCCESS 或 ACCEPTED。
        boolean accepted = nativeAccepted(result);
        coreSequence = sequence; takerOrderId = orderId; activeUserId = userId;
        takerLaneId = runtime.topology().accountLaneId(activeUserId);
        rejectedTaker = !accepted;
        requiredLaneMask = runtime.topology().accountLaneMask(activeUserId);
        orderCount = accepted ? 1 : 0;
        if (orderCount != 0) orderIds[0] = orderId;
    }

    /**
     * 仅把原生 SUCCESS 或 ACCEPTED 视为 Matcher 接受。
     * @param result 当前构建入口已关联本代命令的撮合结果，原生重载由外层事件核验证据
     */
    private static boolean nativeAccepted(exchange.core2.core.common.MatcherResult result) {
        return result.resultCode() == exchange.core2.core.common.cmd.CommandResultCode.SUCCESS
                || result.resultCode() == exchange.core2.core.common.cmd.CommandResultCode.ACCEPTED;
    }

    /**
     * 为没有原生结果的准入拒绝只保留身份与路由，让已登记的账户路径能够交回完成回执。
     * @param sequence 当前事实的 Core 序号
     * @param userId 当前主动、撤单或持仓身份所属用户
     * @param orderId 当前主动订单、撤单目标或待检查订单的原语编号
     * @param laneMask 本拒绝事实仍需交回完成位的账户 Lane 位图
     */
    void prepareRejectedDirect(long sequence, long userId, long orderId, long laneMask) {
        if (sequence <= 0 || userId <= 0 || orderId <= 0 || laneMask == 0)
            throw new IllegalArgumentException("invalid rejected direct matcher fact");
        clearReferences();
        coreSequence = sequence;
        takerOrderId = orderId;
        activeUserId = userId;
        requiredLaneMask = laneMask;
        rejectedTaker = true;
        orderCount = tradeCount = 0;
    }

    /**
     * 原地移除本计划编号前缀中的未成功下出的 taker，保留其他已发生变化的订单编号及原有顺序。
     */
    void omitUnplacedOrder() {
        // 本轮筛选或线性去重后保留的有效项数。
        int count = 0;
        for (/* 当前遍历的原始撮合事件、撤单结果或订单前缀下标。 */ int index = 0; index < orderCount; index++)
            if (orderIds[index] != takerOrderId) orderIds[count++] = orderIds[index];
        orderCount = count;
    }

    /**
     * 所属 Lane 以当前权威订单校验直达成交身份及 maker 单次剩余量，必要时保留持仓身份；深度成交的非 taker Lane 只沿自己的 maker 链遍历，不跨线程读其他账户。
     * @param lane 当前线程持有的所属账户 Lane
     * @param runtime 提供拓扑、允许读取的订单视图及构建暂存缓冲的交易运行时
     * @param identities 合约名称与运行时持仓身份字典
     * @param instrument 当前事实的规范币对对象，必须与订单输入一致
     */
    void validateDirectLane(AccountLaneState lane, TradingAccountRuntime runtime,
                            RuntimeIdentityRegistry identities, CoreInstrument instrument) {
        if (directTakerSide == null) return;
        if (runtime.topology().accountLaneId(activeUserId) == lane.laneId()) {
            // 当前构建或 Lane 校验使用的主动订单引用，已解析输入路径可以为空。
            OrderRuntime taker = lane.order(takerOrderId);
            requireDirectOrder(taker, activeUserId, directTakerSide);
            if (tradeCount != 0) runtime.retainDirectPositionIdentity(lane, identities, instrument, taker);
        }
        // 深度成交计划已经按 maker 所属 Lane 建好事件链，非 taker Lane 只校验自己的 maker。
        // taker Lane 仍按原事件顺序读取，因为它也可能同时持有本 Lane 的 maker。
        for (/* 当前遍历的原始撮合事件、撤单结果或订单前缀下标。 */ int index = firstMatcherEvent(lane.laneId()); index >= 0;
                index = nextMatcherEvent(index, lane.laneId())) {
            // 当前原始 Matcher 事件，TRADE 才参与成交数量与 maker 路由。
            MatcherEvent event = matcherEvents.get(index);
            if (event.eventType() != MatcherEventType.TRADE
                    || !matcherEventTouchesLane(index, lane.laneId(), runtime)
                    || runtime.topology().accountLaneId(event.matchedOrderUid()) != lane.laneId()) continue;
            // 当前成交在允许读取边界内取得的对手订单引用。
            OrderRuntime maker = lane.order(event.matchedOrderId());
            requireDirectOrder(maker, event.matchedOrderUid(), directTakerSide
                    == com.surprising.aeron.protocol.CoreOrderSide.BUY
                    ? com.surprising.aeron.protocol.CoreOrderSide.SELL
                    : com.surprising.aeron.protocol.CoreOrderSide.BUY);
            if (maker.remainingQuantitySteps() < event.size())
                throw new IllegalStateException("fill exceeds Lane maker remaining quantity");
            runtime.retainDirectPositionIdentity(lane, identities, instrument, maker);
        }
    }

    /**
     * 要求 Lane 当前订单为开放状态，且用户、方向、币对编号及规范币对对象都与直达输入一致。
     * @param order 当前需校验或准备持仓身份的订单引用
     * @param userId 当前主动、撤单或持仓身份所属用户
     * @param side 主动订单或待核对订单的预期买卖方向
     */
    private void requireDirectOrder(OrderRuntime order, long userId,
                                    com.surprising.aeron.protocol.CoreOrderSide side) {
        if (order == null || order.status() != CoreOrderStatus.OPEN || order.userId() != userId
                || order.symbolId() != directTakerSymbolId || order.side() != side
                || order.instrument() != directTakerInstrument)
            throw new IllegalStateException("matcher fact does not match Lane-owned order");
    }
    /**  本代要由 taker Lane 写入的触发单终态，普通命令为空。 返回本代要由 taker Lane 写入的触发单终态，普通订单返回空。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private com.surprising.aeron.model.CoreTriggerOrderState completedTrigger;

    /**
     * 把触发终态附到同一结算事实：用户必须匹配，拒单要求失败状态且无子订单，非拒单要求子订单编号匹配；同代只允许设置一次。
     * @param value 与当前用户及子订单结果对应的触发单终态，只允许设置一次
     */
    public MatcherSettlementPlan completeTrigger(com.surprising.aeron.model.CoreTriggerOrderState value) {
        if (value == null || value.userId() != activeUserId || (rejectedTaker
                ? value.status() != com.surprising.aeron.protocol.CoreTriggerOrderStatus.TRIGGER_FAILED
                        || value.placedOrderId() != 0
                : value.placedOrderId() != takerOrderId) || completedTrigger != null)
            throw new IllegalArgumentException("invalid settlement trigger completion");
        completedTrigger = value;
        return this;
    }

    /**
     * 直接读取原语订单编号；调用方保证下标在 orderCount 内，本方法不另检查业务有效前缀。
     * @param index 当前代有效订单、事件或前置撤单下标
     */
    public long orderId(int index) { return orderIds[index]; }
    /**
     * 返回本代 Matcher 事件总数，包括非成交事件。
     */
    public int matcherEventCount() { return matcherEvents.size(); }
    /**
     * 按列表下标读取本代原始 Matcher 事件引用。
     * @param index 当前代有效订单、事件或前置撤单下标
     */
    public MatcherEvent matcherEvent(int index) { return matcherEvents.get(index); }
    /**
     * 将已经接受的替换原单撤单保存为唯一前置撤单项；之后可追加普通前置撤单。
     * @param orderId 当前主动订单、撤单目标或待检查订单的原语编号
     */
    void acceptedReplacementCancellation(long orderId) {
        if (orderId <= 0) throw new IllegalArgumentException("replacement cancellation is required");
        ensurePreCancellationCapacity(1);
        preCancellationStorage[0] = orderId;
        preCancellationOrderIds = preCancellationStorage;
        preCancellationSize = 1;
    }

    /**
     * 把调用方已经确认成功的撤单前缀追加到现有自有缓冲，不覆盖已有替换撤单；数量为零保持原状态。
     * @param orderIds 受影响订单编号数组，复制或接管方式以具体入口为准
     * @param count 输入数组中已经确认接受的撤单前缀数量
     */
    void acceptedPreCancellations(long[] orderIds, int count) {
        if (count < 0 || count != 0 && (orderIds == null || count > orderIds.length))
            throw new IllegalArgumentException("accepted cancellations are invalid");
        if (count == 0) return;
        ensurePreCancellationCapacity(preCancellationSize + count);
        System.arraycopy(orderIds, 0, preCancellationStorage, preCancellationSize, count);
        preCancellationSize += count;
        preCancellationOrderIds = preCancellationStorage;
    }

    /**
     * 只在需要时创建或扩容计划自有撤单数组，保留已写前缀，非正数请求不处理。
     * @param required 自有撤单存储至少需要容纳的有效项数
     */
    private void ensurePreCancellationCapacity(int required) {
        if (required <= 0) return;
        if (preCancellationStorage == null) {
            preCancellationStorage = new long[Math.max(4, required)];
        } else if (preCancellationStorage.length < required) {
            preCancellationStorage = java.util.Arrays.copyOf(preCancellationStorage,
                    Math.max(required, preCancellationStorage.length * 2));
        }
    }
    /**
     * 返回本代前置撤单有效项数。
     */
    public int preCancellationCount() { return preCancellationSize; }
    /**
     * 读取前置撤单原语编号，调用方保证下标小于有效项数。
     * @param index 当前代有效订单、事件或前置撤单下标
     */
    long preCancellationOrderId(int index) { return preCancellationOrderIds[index]; }
    /**
     * 事件至少八个且涉及多个 Lane 时，逆序串出各 maker Lane 的事件链，保持原成交顺序；taker Lane 继续顺序读取全部原事件。
     * @param runtime 提供拓扑、允许读取的订单视图及构建暂存缓冲的交易运行时
     */
    private MatcherSettlementPlan indexLaneEvents(TradingAccountRuntime runtime) {
        // 少量事件不建索引；深度成交时各 maker Lane 只沿自己的事件链读取，
        // taker Lane 仍保留原事件顺序，不复制成交事件。
        if (matcherEvents.size() < 8 || Long.bitCount(requiredLaneMask) < 2) return this;
        // 主动账户 Lane 已在构建时缓存，这里只补充 maker 事件链。
        laneEventsIndexed = true;
        if (makerLaneHeads == null || makerLaneHeads.length != runtime.topology().accountLaneCount())
            makerLaneHeads = new int[runtime.topology().accountLaneCount()];
        if (makerLaneNext == null || makerLaneNext.length < matcherEvents.size())
            makerLaneNext = new int[matcherEvents.size()];
        java.util.Arrays.fill(makerLaneHeads, -1);
        for (/* 当前遍历的原始撮合事件、撤单结果或订单前缀下标。 */ int index = matcherEvents.size() - 1; index >= 0; index--) {
            // 当前原始 Matcher 事件，TRADE 才参与成交数量与 maker 路由。
            MatcherEvent event = matcherEvents.get(index);
            if (event.eventType() != MatcherEventType.TRADE) continue;
            // 当前 maker 所属账户 Lane，用于链接其事件索引。
            int lane = runtime.topology().accountLaneId(event.matchedOrderUid());
            makerLaneNext[index] = makerLaneHeads[lane];
            makerLaneHeads[lane] = index;
        }
        return this;
    }

    /**
     * 返回当前 Lane 应开始读取的事件下标；非 taker 的深度索引路径读链头，其他情况从原事件首项开始，负一表示结束。
     * @param laneId 当前要遍历或判断的账户 Lane 编号
     */
    int firstMatcherEvent(int laneId) {
        if (laneEventsIndexed && laneId != takerLaneId) return makerLaneHeads[laneId];
        return matcherEvents.isEmpty() ? -1 : 0;
    }

    /**
     * 沿当前 Lane 的 maker 链或原事件顺序取得下一项，负一表示本 Lane 遍历结束。
     * @param index 当前代有效订单、事件或前置撤单下标
     * @param laneId 当前要遍历或判断的账户 Lane 编号
     */
    int nextMatcherEvent(int index, int laneId) {
        if (laneEventsIndexed && laneId != takerLaneId) return makerLaneNext[index];
        return index + 1 < matcherEvents.size() ? index + 1 : -1;
    }

    /**
     * 判断当前成交是否涉及指定 Lane；启用深度索引时非 taker 调用方须沿本 Lane 事件链传入下标，该前提成立即可直接返回 true。
     * @param index 当前代有效订单、事件或前置撤单下标
     * @param laneId 当前要遍历或判断的账户 Lane 编号
     * @param runtime 提供拓扑、允许读取的订单视图及构建暂存缓冲的交易运行时
     */
    public boolean matcherEventTouchesLane(int index, int laneId, TradingAccountRuntime runtime) {
        // 启用深度索引时，非 taker 调用方已经沿当前 maker Lane 的事件链传入下标，
        // 因此可直接判定相关；其他遍历方式仍按账户拓扑判断。
        if (laneEventsIndexed && laneId != takerLaneId) return true;
        // 当前原始 Matcher 事件，TRADE 才参与成交数量与 maker 路由。
        MatcherEvent event = matcherEvents.get(index);
        return takerLaneId == laneId
                || runtime.topology().accountLaneId(event.matchedOrderUid()) == laneId;
    }
}
