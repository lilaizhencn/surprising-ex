package com.surprising.aeron.service.state.settlement;

import com.surprising.aeron.service.command.ImmutableLongArrayList;
import com.surprising.aeron.service.matching.CoreCancellationResult;
import com.surprising.aeron.service.matching.MatchingResult;
import com.surprising.aeron.service.state.TradingRuntimeState;
import com.surprising.aeron.service.state.account.AccountLaneState;
import com.surprising.aeron.service.state.admission.ResolvedPlaceOrder;
import com.surprising.aeron.service.state.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.state.instrument.CoreInstrument;
import com.surprising.aeron.service.state.model.CoreOrderStatus;
import com.surprising.aeron.service.state.order.OrderRuntime;
import exchange.core2.core.common.MatcherEventType;
import exchange.core2.core.common.MatcherResult.MatcherEvent;
import java.util.AbstractList;
import java.util.List;
import java.util.RandomAccess;
import org.eclipse.collections.impl.map.mutable.primitive.LongLongHashMap;
import org.eclipse.collections.impl.set.mutable.primitive.LongHashSet;

/**
 * 下单步骤 4.2：把一项撮合结果整理成成交事件、受影响订单和账户 Lane 路由。直达入口由 Matcher 根据输入事实构建，通用入口沿既有 Owner 读取边界校验账户；发布后由所属 Lane 消费。数组、只读视图和准入引用都受事件或在途槽租约约束，不能当成永久快照。
 */
public final class MatcherSettlementPlan {
    /** 本代 taker 或撤单请求是否被拒绝，不能与已计入成交并存。 */
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
    /**
     * 返回本计划是否要求按拒单或撤单拒绝处理 taker。
     */
    boolean rejectedTaker() { return rejectedTaker; }

    /** 本计划对应的 Core 复制命令序号。 */
    private long coreSequence;
    /** 主动订单或撤单目标的编号。 */
    private long takerOrderId;
    /** 当前复制命令的发起用户。 */
    private long activeUserId;
    /** 由 taker、maker 及相关变化归集的账户 Lane 位图。 */
    private long requiredLaneMask;
    /** 本计划自有或内部构造接管的原语订单编号数组，只使用 orderCount 个有效槽。 */
    private long[] orderIds;
    /** 订单身份数组当前代的有效项数。 */
    private int orderCount;
    /** 本代原始 Matcher 事件列表引用，包含成交与其他事件。 */
    private List<MatcherEvent> matcherEvents;
    /** 已校验并计入计划的 TRADE 事件数量。 */
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
    /** 构建入口复用的初始订单身份参数数组，不作为对外发布的事实视图。 */
    private long[] initialOrderScratch;
    /** 没有前置撤单时共用的空原语数组。 */
    private static final long[] NO_ORDERS = new long[0];
    /** 直达构建入口保存的 taker 引用，按本计划租约保留并在 clearReferences 释放。 */
    private OrderRuntime directTaker;
    /** 构建时保留的准入订单值或命令槽引用，消费者遵守该代租约。 */
    private OrderRuntime admittedTaker;
    /** 尚无 Owner 订单后值时使用的不可变已解析准入输入。 */
    private ResolvedPlaceOrder resolvedTaker;
    /** 直达事实校验所需的主动订单方向，空表示不走直达订单校验。 */
    private com.surprising.aeron.protocol.CoreOrderSide directTakerSide;
    /** 直达事实校验所需的币对稠密编号。 */
    private int directTakerSymbolId;
    /** 直达事实校验所需的规范币对对象引用。 */
    private CoreInstrument directTakerInstrument;
    /** 本计划构建阶段独占的原语订单去重集合；借用它的构建路径在 finally 清空，短事件路径不使用它。 */
    private final LongHashSet directOrderKeys = new LongHashSet();
    /** 绑定本计划的只读订单编号视图，跟随当前代变化而非独立快照。 */
    private final List<Long> orderIdView = new OrderIdView(this);

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
        directTaker = null; admittedTaker = null; resolvedTaker = null; takerLaneId = -1;
        directTakerSide = null;
        directTakerSymbolId = 0;
        directTakerInstrument = null;
    }

    /**
     * 核对已准入订单、规范币对及 MatchingResult 的 Core 序号和 taker 编号，再按订单剩余量构建直达事实，不读取其他线程账户表。
     * @param sequence 当前事实的 Core 序号
     * @param taker 已准入的运行时主动订单引用，或对应重载的不可变已解析准入值
     * @param instrument 当前事实的规范币对对象，必须与订单输入一致
     * @param result 当前构建入口已关联本代命令的撮合结果，原生重载由外层事件核验证据
     * @param runtime 提供拓扑、允许读取的订单视图及构建暂存缓冲的交易运行时
     */
    void buildDirect(long sequence, OrderRuntime taker, CoreInstrument instrument,
                     MatchingResult result, TradingRuntimeState runtime) {
        if (taker == null || instrument == null || result == null
                || result.nativeCoreSequence() != sequence
                || result.nativeOrderId() != taker.orderId()
                || instrument != taker.instrument())
            throw new IllegalArgumentException("invalid direct matcher fact");
        buildDirect(sequence, taker.orderId(), taker.userId(), taker.side(), taker.symbolId(),
                taker.instrument(), taker.remainingQuantitySteps(), taker, instrument, result, runtime);
    }

    /**
     * 用普通下单的不可变准入输入核对结果证据，以准入数量构建直达事实，不创建脱离 Lane 的运行时订单副本。
     * @param sequence 当前事实的 Core 序号
     * @param userId 当前主动、撤单或持仓身份所属用户
     * @param taker 已准入的运行时主动订单引用，或对应重载的不可变已解析准入值
     * @param instrument 当前事实的规范币对对象，必须与订单输入一致
     * @param result 当前构建入口已关联本代命令的撮合结果，原生重载由外层事件核验证据
     * @param runtime 提供拓扑、允许读取的订单视图及构建暂存缓冲的交易运行时
     */
    void buildDirect(long sequence, long userId, ResolvedPlaceOrder taker, CoreInstrument instrument,
                     MatchingResult result, TradingRuntimeState runtime) {
        if (userId <= 0 || taker == null || instrument == null || result == null
                || result.nativeCoreSequence() != sequence
                || result.nativeOrderId() != taker.orderId()
                || instrument != taker.instrument())
            throw new IllegalArgumentException("invalid direct matcher fact");
        buildDirect(sequence, taker.orderId(), userId, taker.side(), taker.symbolId(),
                taker.instrument(), taker.quantitySteps(), null, instrument, result, runtime);
    }

    /**
     * 重置本代计划，校验成交价格、数量和 maker 身份，累计扣减 taker 剩余量，合并订单身份及 Lane 路由；记录已成功的前置撤单并准备深度成交索引，构建临时去重集合在 finally 清空。
     * @param sequence 当前事实的 Core 序号
     * @param orderId 当前主动订单、撤单目标或待检查订单的原语编号
     * @param userId 当前主动、撤单或持仓身份所属用户
     * @param side 主动订单或待核对订单的预期买卖方向
     * @param symbolId 主动订单的币对稠密编号
     * @param takerInstrument 主动订单输入中保存的规范币对对象
     * @param remainingQuantity 本代主动订单可供累计成交的数量上限
     * @param taker 已准入的运行时主动订单引用，或对应重载的不可变已解析准入值
     * @param instrument 当前事实的规范币对对象，必须与订单输入一致
     * @param result 当前构建入口已关联本代命令的撮合结果，原生重载由外层事件核验证据
     * @param runtime 提供拓扑、允许读取的订单视图及构建暂存缓冲的交易运行时
     */
    private void buildDirect(long sequence, long orderId, long userId,
                             com.surprising.aeron.protocol.CoreOrderSide side, int symbolId,
                             CoreInstrument takerInstrument, long remainingQuantity, OrderRuntime taker,
                             CoreInstrument instrument, MatchingResult result,
                             TradingRuntimeState runtime) {
        clearReferences();
        coreSequence = sequence; directTaker = taker; admittedTaker = taker;
        directTakerSide = side;
        directTakerSymbolId = symbolId;
        directTakerInstrument = takerInstrument;
        takerOrderId = orderId; activeUserId = userId;
        takerLaneId = runtime.topology().accountLaneId(activeUserId);
        requiredLaneMask = runtime.topology().accountLaneMask(activeUserId);
        matcherEvents = result.matcherEvents();
        // 容纳本代主动订单、maker 及撤单身份所需的数组容量。
        int capacity = Math.addExact(matcherEvents.size(), Math.addExact(result.cancellations().size(), 1));
        if (orderIds.length < capacity) orderIds = new long[Math.max(capacity, orderIds.length * 2)];
        // 本计划独占复用的原语订单去重集合，构建结束即清空。
        LongHashSet keys = directOrderKeys;
        try {
            orderCount = addUnique(keys, orderIds, 0, takerOrderId);
            // 主动订单按已遍历成交逐项扣减后的待成交数量。
            long remaining = remainingQuantity;
            for (/* 当前遍历的原始撮合事件、撤单结果或订单前缀下标。 */ int index = 0; index < matcherEvents.size(); index++) {
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
            // 前置撤单归本次提交账户，已在准入时授权；这里只把实际接受的编号计入变化。
            // 当前结果携带的撤单项列表。
            var cancellations = result.cancellations();
            for (/* 当前遍历的原始撮合事件、撤单结果或订单前缀下标。 */ int index = 0; index < cancellations.size(); index++) {
                // 当前检查的单笔撤单结果。
                var cancellation = cancellations.get(index);
                if (cancellation.accepted())
                    orderCount = addUnique(keys, orderIds, orderCount, cancellation.orderId());
            }
            rejectTaker(!result.accepted());
            indexLaneEvents(runtime);
        } finally { keys.clear(); }
    }

    /**
     * 校验单笔撤单的序号与订单证据，拒绝夹带成交或其他撤单；撤单被接受时再检查用户归属并登记订单身份，只需用户所属 Lane。
     * @param sequence 当前事实的 Core 序号
     * @param userId 当前主动、撤单或持仓身份所属用户
     * @param order 当前需校验或准备持仓身份的订单引用
     * @param result 当前构建入口已关联本代命令的撮合结果，原生重载由外层事件核验证据
     * @param runtime 提供拓扑、允许读取的订单视图及构建暂存缓冲的交易运行时
     */
    void buildDirectCancellation(long sequence, long userId, OrderRuntime order, MatchingResult result,
                                 TradingRuntimeState runtime) {
        if (order == null || result == null || result.nativeCoreSequence() != sequence
                || result.nativeOrderId() != order.orderId())
            throw new IllegalArgumentException("invalid direct cancellation fact");
        // 当前构建或校验入口借用的原始撮合事件列表。
        var matcherEvents = result.matcherEvents();
        for (/* 当前遍历的原始撮合事件、撤单结果或订单前缀下标。 */ int index = 0; index < matcherEvents.size(); index++) {
            // 当前原始 Matcher 事件，TRADE 才参与成交数量与 maker 路由。
            MatcherEvent event = matcherEvents.get(index);
            if (event == null || event.eventType() == MatcherEventType.TRADE)
                throw new IllegalStateException("cancel result contains a trade");
        }
        if (!result.cancellations().isEmpty())
            throw new IllegalStateException("single cancel contains unrelated cancellations");
        clearReferences();
        if (result.accepted() && order.userId() != userId)
            throw new IllegalStateException("matcher canceled another account order");
        coreSequence = sequence;
        takerOrderId = order.orderId();
        activeUserId = userId;
        takerLaneId = runtime.topology().accountLaneId(activeUserId);
        rejectedTaker = !result.accepted();
        requiredLaneMask = runtime.topology().accountLaneMask(activeUserId);
        orderCount = result.accepted() ? 1 : 0;
        if (orderCount != 0) orderIds[0] = order.orderId();
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
                           exchange.core2.core.common.MatcherResult result, TradingRuntimeState runtime) {
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
                           exchange.core2.core.common.MatcherResult result, TradingRuntimeState runtime) {
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
                                   exchange.core2.core.common.MatcherResult result, TradingRuntimeState runtime) {
        clearReferences();
        coreSequence = sequence; directTaker = taker; admittedTaker = taker;
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
     * 检查原生撤单不夹带成交，重置并保存目标用户路由和接受标记；接受时记录一个订单身份，拒绝时有效身份数为零。
     * @param sequence 当前事实的 Core 序号
     * @param userId 当前主动、撤单或持仓身份所属用户
     * @param orderId 当前主动订单、撤单目标或待检查订单的原语编号
     * @param result 当前构建入口已关联本代命令的撮合结果，原生重载由外层事件核验证据
     * @param runtime 提供拓扑、允许读取的订单视图及构建暂存缓冲的交易运行时
     */
    void buildDirectNativeCancellation(long sequence, long userId, long orderId,
                                       exchange.core2.core.common.MatcherResult result,
                                       TradingRuntimeState runtime) {
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
     * 为已经确认的准入拒绝保留序号、用户、订单与路由及传入事件引用，不创建订单后值；成交数和订单有效数均置零。
     * @param sequence 当前事实的 Core 序号
     * @param userId 当前主动、撤单或持仓身份所属用户
     * @param orderId 当前主动订单、撤单目标或待检查订单的原语编号
     * @param laneMask 本拒绝事实仍需交回完成位的账户 Lane 位图
     * @param result 当前构建入口已关联本代命令的撮合结果，原生重载由外层事件核验证据
     */
    void prepareRejectedDirect(long sequence, long userId, long orderId, long laneMask,
                               MatchingResult result) {
        if (sequence <= 0 || userId <= 0 || orderId <= 0 || laneMask == 0 || result == null) {
            throw new IllegalArgumentException("invalid rejected direct matcher fact");
        }
        clearReferences();
        coreSequence = sequence;
        takerOrderId = orderId;
        activeUserId = userId;
        requiredLaneMask = laneMask;
        rejectedTaker = true;
        matcherEvents = result.matcherEvents();
        orderCount = 0;
        tradeCount = 0;
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
    void validateDirectLane(AccountLaneState lane, TradingRuntimeState runtime,
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
    /** 本代要由 taker Lane 写入的触发单终态，普通命令为空。 */
    private com.surprising.aeron.service.state.model.CoreTriggerOrderState completedTrigger;

    /**
     * 把触发终态附到同一结算事实：用户必须匹配，拒单要求失败状态且无子订单，非拒单要求子订单编号匹配；同代只允许设置一次。
     * @param value 与当前用户及子订单结果对应的触发单终态，只允许设置一次
     */
    public MatcherSettlementPlan completeTrigger(com.surprising.aeron.service.state.model.CoreTriggerOrderState value) {
        if (value == null || value.userId() != activeUserId || (rejectedTaker
                ? value.status() != com.surprising.aeron.protocol.CoreTriggerOrderStatus.TRIGGER_FAILED
                        || value.placedOrderId() != 0
                : value.placedOrderId() != takerOrderId) || completedTrigger != null)
            throw new IllegalArgumentException("invalid settlement trigger completion");
        completedTrigger = value;
        return this;
    }
    /**
     * 返回本代要由 taker Lane 写入的触发单终态，普通订单返回空。
     */
    public com.surprising.aeron.service.state.model.CoreTriggerOrderState completedTrigger() { return completedTrigger; }

    /**
     * 从已有原语事实列创建计划，默认没有前置撤单；内部入口接管传入数组与事件引用。
     * @param coreSequence 当前事实对应的 Core 序号
     * @param takerOrderId 主动订单或该计划的撤单目标编号
     * @param activeUserId 发起本代命令的账户用户
     * @param requiredLaneMask 当前计划涉及的账户 Lane 位图
     * @param orderIds 受影响订单编号数组，复制或接管方式以具体入口为准
     * @param orderCount 传入原语订单数组的有效前缀长度
     * @param matcherEvents 传入的原始撮合事件列表引用
     * @param tradeCount 已统计的成交事件数量
     */
    private MatcherSettlementPlan(long coreSequence, long takerOrderId, long activeUserId,
                                  long requiredLaneMask, long[] orderIds, int orderCount,
                                  List<MatcherEvent> matcherEvents, int tradeCount) {
        this(coreSequence, takerOrderId, activeUserId, requiredLaneMask, orderIds, orderCount,
                matcherEvents, tradeCount, NO_ORDERS);
    }

    /**
     * 保存调用方已准备的事实列和有效数量，不在构造器中重新校验成交或复制输入数组。
     * @param coreSequence 当前事实对应的 Core 序号
     * @param takerOrderId 主动订单或该计划的撤单目标编号
     * @param activeUserId 发起本代命令的账户用户
     * @param requiredLaneMask 当前计划涉及的账户 Lane 位图
     * @param orderIds 受影响订单编号数组，复制或接管方式以具体入口为准
     * @param orderCount 传入原语订单数组的有效前缀长度
     * @param matcherEvents 传入的原始撮合事件列表引用
     * @param tradeCount 已统计的成交事件数量
     * @param preCancellationOrderIds 内部构造接管的前置撤单编号数组
     */
    private MatcherSettlementPlan(long coreSequence, long takerOrderId, long activeUserId,
                                  long requiredLaneMask, long[] orderIds, int orderCount,
                                  List<MatcherEvent> matcherEvents, int tradeCount,
                                  long[] preCancellationOrderIds) {
        this.coreSequence = coreSequence;
        this.takerOrderId = takerOrderId;
        this.activeUserId = activeUserId;
        this.requiredLaneMask = requiredLaneMask;
        this.orderIds = orderIds;
        this.orderCount = orderCount;
        this.matcherEvents = matcherEvents;
        this.tradeCount = tradeCount;
        this.preCancellationOrderIds = preCancellationOrderIds;
        this.preCancellationSize = preCancellationOrderIds.length;
    }

    /**
     * Owner 构建当前批次时独占的校验暂存。累计跨项剩余量、终态身份和最近 maker 引用，批次结束清空，不发布成第二份订单状态。
     */
    static final class BatchValidationScratch {
        /** 当前批次按订单累计扣减后的剩余量校验表，不是权威订单状态。 */
        private final LongLongHashMap remainingByOrderId = new LongLongHashMap();
        /** 本批已用尽、即时完成或不再允许后续成交的订单编号集合。 */
        private final LongHashSet terminalOrderIds = new LongHashSet();
        /** 本批最近一次读取的 maker 引用，仅复用相邻重复 maker，clear 解除引用。 */
        private OrderRuntime lastMaker;

        /**
         * 复用最近一个 maker 的开放订单引用，相邻成交切换编号时才重新读取；只在当前批次构建调用边界内使用。
         * @param runtime 提供拓扑、允许读取的订单视图及构建暂存缓冲的交易运行时
         * @param id 当前要读取或复用最近引用的 maker 订单编号
         */
        OrderRuntime maker(TradingRuntimeState runtime, long id) {
            if (lastMaker == null || lastMaker.orderId() != id) lastMaker = requireOpen(runtime, id);
            return lastMaker;
        }

        /**
         * 清空累计剩余量、已终态订单集合和最近 maker 引用，开始新批次前调用。
         */
        void clear() {
            remainingByOrderId.clear();
            terminalOrderIds.clear();
            lastMaker = null;
        }
    }

    /**
     * 从初始订单编号和 MatchingResult 构建新计划，拒绝空编号数组，其余校验交给完整构建入口。
     * @param coreSequence 当前事实对应的 Core 序号
     * @param takerOrderId 主动订单或该计划的撤单目标编号
     * @param activeUserId 发起本代命令的账户用户
     * @param initialOrderIds 构建前已知的订单编号，非正数项在通用入口跳过
     * @param result 当前构建入口已关联本代命令的撮合结果，原生重载由外层事件核验证据
     * @param runtime 提供拓扑、允许读取的订单视图及构建暂存缓冲的交易运行时
     * @param identities 合约名称与运行时持仓身份字典
     */
    public static MatcherSettlementPlan build(long coreSequence, long takerOrderId, long activeUserId,
                                               long[] initialOrderIds, MatchingResult result,
                                               TradingRuntimeState runtime, RuntimeIdentityRegistry identities) {
        if (initialOrderIds == null) throw new IllegalArgumentException("initial orders are required");
        return build(coreSequence, takerOrderId, activeUserId, initialOrderIds, result, runtime, identities,
                null, null, null, null, null);
    }

    /**
     * 复用目标计划的两个初始身份槽，按通用构建入口校验结果；目标的上一代必须已完成消费。
     * @param target 可独占重建的计划槽，上一代消费者必须已经结束
     * @param sequence 当前事实的 Core 序号
     * @param takerOrderId 主动订单或该计划的撤单目标编号
     * @param userId 当前主动、撤单或持仓身份所属用户
     * @param firstOrderId 首个已知关联订单编号
     * @param secondOrderId 第二个已知关联订单编号
     * @param result 当前构建入口已关联本代命令的撮合结果，原生重载由外层事件核验证据
     * @param runtime 提供拓扑、允许读取的订单视图及构建暂存缓冲的交易运行时
     * @param identities 合约名称与运行时持仓身份字典
     */
    public static MatcherSettlementPlan buildInto(MatcherSettlementPlan target,
            long sequence, long takerOrderId, long userId, long firstOrderId, long secondOrderId,
            MatchingResult result, TradingRuntimeState runtime, RuntimeIdentityRegistry identities) {
        if (target == null) throw new IllegalArgumentException("settlement target is required");
        if (target.initialOrderScratch == null) target.initialOrderScratch = new long[2];
        target.initialOrderScratch[0] = firstOrderId;
        target.initialOrderScratch[1] = secondOrderId;
        return build(sequence, takerOrderId, userId, target.initialOrderScratch, result, runtime, identities,
                null, null, null, null, target);
    }

    /**
     * 使用准入回执中的订单引用构建目标计划，避免尚未发布的账户后值必须先进入 Owner 查询视图。
     * @param target 可独占重建的计划槽，上一代消费者必须已经结束
     * @param sequence 当前事实的 Core 序号
     * @param taker 已准入的运行时主动订单引用，或对应重载的不可变已解析准入值
     * @param firstOrderId 首个已知关联订单编号
     * @param secondOrderId 第二个已知关联订单编号
     * @param result 当前构建入口已关联本代命令的撮合结果，原生重载由外层事件核验证据
     * @param runtime 提供拓扑、允许读取的订单视图及构建暂存缓冲的交易运行时
     * @param identities 合约名称与运行时持仓身份字典
     */
    public static MatcherSettlementPlan buildAdmittedInto(MatcherSettlementPlan target,
            long sequence, OrderRuntime taker, long firstOrderId, long secondOrderId,
            MatchingResult result, TradingRuntimeState runtime, RuntimeIdentityRegistry identities) {
        if (target == null || taker == null) throw new IllegalArgumentException("admitted taker is required");
        if (target.initialOrderScratch == null) target.initialOrderScratch = new long[2];
        target.initialOrderScratch[0] = firstOrderId;
        target.initialOrderScratch[1] = secondOrderId;
        return build(sequence, taker.orderId(), taker.userId(), target.initialOrderScratch, result,
                runtime, identities, taker, null, null, null, target);
    }

    /**
     * 使用不可变已解析准入输入和规范币对构建目标计划，不复制 Lane 权威运行时订单。
     * @param target 可独占重建的计划槽，上一代消费者必须已经结束
     * @param sequence 当前事实的 Core 序号
     * @param userId 当前主动、撤单或持仓身份所属用户
     * @param taker 已准入的运行时主动订单引用，或对应重载的不可变已解析准入值
     * @param firstOrderId 首个已知关联订单编号
     * @param secondOrderId 第二个已知关联订单编号
     * @param result 当前构建入口已关联本代命令的撮合结果，原生重载由外层事件核验证据
     * @param runtime 提供拓扑、允许读取的订单视图及构建暂存缓冲的交易运行时
     * @param identities 合约名称与运行时持仓身份字典
     */
    public static MatcherSettlementPlan buildResolvedInto(MatcherSettlementPlan target,
            long sequence, long userId, ResolvedPlaceOrder taker,
            long firstOrderId, long secondOrderId, MatchingResult result,
            TradingRuntimeState runtime, RuntimeIdentityRegistry identities) {
        if (target == null || userId <= 0 || taker == null)
            throw new IllegalArgumentException("resolved admitted taker is required");
        if (target.initialOrderScratch == null) target.initialOrderScratch = new long[2];
        target.initialOrderScratch[0] = firstOrderId;
        target.initialOrderScratch[1] = secondOrderId;
        return build(sequence, taker.orderId(), userId, target.initialOrderScratch, result,
                runtime, identities, null, taker, taker.instrument(), null, target);
    }

    /**
     * 复用目标的一项初始身份缓冲构建批次单项；有效身份按通用入口处理，不为参数传递临时创建单元素数组。
     * @param target 可独占重建的计划槽，上一代消费者必须已经结束
     * @param sequence 当前事实的 Core 序号
     * @param takerOrderId 主动订单或该计划的撤单目标编号
     * @param userId 当前主动、撤单或持仓身份所属用户
     * @param result 当前构建入口已关联本代命令的撮合结果，原生重载由外层事件核验证据
     * @param runtime 提供拓扑、允许读取的订单视图及构建暂存缓冲的交易运行时
     * @param identities 合约名称与运行时持仓身份字典
     */
    public static MatcherSettlementPlan buildSingleInto(MatcherSettlementPlan target,
            long sequence, long takerOrderId, long userId,
            MatchingResult result, TradingRuntimeState runtime, RuntimeIdentityRegistry identities) {
        if (target == null) throw new IllegalArgumentException("settlement target is required");
        if (target.initialOrderScratch == null || target.initialOrderScratch.length < 1)
            target.initialOrderScratch = new long[1];
        target.initialOrderScratch[0] = takerOrderId;
        return build(sequence, takerOrderId, userId, target.initialOrderScratch, result, runtime, identities,
                null, null, null, null, target);
    }

    /**
     * 复用目标创建没有成交的两订单计划，路由仅为当前用户，第二个编号作为 taker；清除上一代引用后保存两个有效身份。
     * @param target 可独占重建的计划槽，上一代消费者必须已经结束
     * @param sequence 当前事实的 Core 序号
     * @param userId 当前主动、撤单或持仓身份所属用户
     * @param firstOrderId 首个已知关联订单编号
     * @param secondOrderId 第二个已知关联订单编号
     * @param runtime 提供拓扑、允许读取的订单视图及构建暂存缓冲的交易运行时
     */
    public static MatcherSettlementPlan emptyInto(MatcherSettlementPlan target, long sequence,
            long userId, long firstOrderId, long secondOrderId, TradingRuntimeState runtime) {
        if (target == null || sequence <= 0 || userId <= 0 || firstOrderId <= 0 || secondOrderId <= 0
                || runtime == null) throw new IllegalArgumentException("invalid empty settlement target");
        target.clearReferences();
        target.coreSequence = sequence;
        target.activeUserId = userId;
        target.takerOrderId = secondOrderId;
        target.requiredLaneMask = runtime.topology().accountLaneMask(userId);
        target.orderIds[0] = firstOrderId;
        target.orderIds[1] = secondOrderId;
        target.orderCount = 2;
        return target;
    }

    /**
     * 在一遍成交遍历中构建当前批次项并校验跨项累计数量，共用本批剩余量和终态校验状态。
     * @param sequence 当前事实的 Core 序号
     * @param taker 已准入的运行时主动订单引用，或对应重载的不可变已解析准入值
     * @param instrument 当前事实的规范币对对象，必须与订单输入一致
     * @param result 当前构建入口已关联本代命令的撮合结果，原生重载由外层事件核验证据
     * @param runtime 提供拓扑、允许读取的订单视图及构建暂存缓冲的交易运行时
     * @param identities 合约名称与运行时持仓身份字典
     * @param scratch 仅在当前批次构建调用边界内使用的累计数量及终态校验暂存
     * @param target 可独占重建的计划槽，上一代消费者必须已经结束
     */
    static MatcherSettlementPlan buildBatchItem(long sequence, OrderRuntime taker, CoreInstrument instrument,
                                                MatchingResult result, TradingRuntimeState runtime,
                                                RuntimeIdentityRegistry identities, BatchValidationScratch scratch, MatcherSettlementPlan target) {
        return build(sequence, taker.orderId(), taker.userId(), null, result, runtime, identities,
                taker, null, instrument, scratch, target);
    }

    /**
     * 通用构建入口：选择已准入或可读取的开放 taker，逐成交核对 maker、累计剩余量与持仓身份，归集变化订单和 Lane；批次保留跨项数量，单项临时数量表在 finally 清空，最后填充或创建计划并建立事件索引。
     * @param coreSequence 当前事实对应的 Core 序号
     * @param takerOrderId 主动订单或该计划的撤单目标编号
     * @param activeUserId 发起本代命令的账户用户
     * @param initialOrderIds 构建前已知的订单编号，非正数项在通用入口跳过
     * @param result 当前构建入口已关联本代命令的撮合结果，原生重载由外层事件核验证据
     * @param runtime 提供拓扑、允许读取的订单视图及构建暂存缓冲的交易运行时
     * @param identities 合约名称与运行时持仓身份字典
     * @param preparedTaker 调用方提供的已准入运行时订单引用，可为空
     * @param resolvedTaker 调用方提供的不可变已解析准入值，可为空
     * @param preparedInstrument 调用方已解析的规范币对，为空时由允许的运行时视图读取
     * @param batch 当前批次共享的累计数量校验状态，普通单项为空
     * @param target 可独占重建的计划槽，上一代消费者必须已经结束
     */
    private static MatcherSettlementPlan build(long coreSequence, long takerOrderId, long activeUserId,
                                               long[] initialOrderIds, MatchingResult result,
                                               TradingRuntimeState runtime, RuntimeIdentityRegistry identities,
                                               OrderRuntime preparedTaker, ResolvedPlaceOrder resolvedTaker,
                                               CoreInstrument preparedInstrument,
                                               BatchValidationScratch batch, MatcherSettlementPlan target) {
        if (coreSequence <= 0 || takerOrderId <= 0 || activeUserId <= 0 || (initialOrderIds == null && batch == null)
                || result == null || runtime == null || identities == null
                || result.nativeCoreSequence() != coreSequence) {
            throw new IllegalArgumentException("invalid matcher settlement plan input");
        }
        // 当前构建或 Lane 校验使用的主动订单引用，已解析输入路径可以为空。
        OrderRuntime taker = preparedTaker == null && resolvedTaker == null
                ? requireOpen(runtime, takerOrderId) : preparedTaker;
        if (taker != null && taker.status() != CoreOrderStatus.OPEN
                || batch != null && batch.terminalOrderIds.contains(takerOrderId))
            throw new IllegalStateException("runtime matched order is not open: " + takerOrderId);
        // 本次构建使用的规范币对对象。
        CoreInstrument instrument = preparedInstrument == null
                ? runtime.instrument(identities.instrumentId(taker.symbolId())) : preparedInstrument;
        // 主动订单的币对稠密编号，用于核对 maker。
        int takerSymbolId = taker == null ? resolvedTaker.symbolId() : taker.symbolId();
        // 主动订单在本次构建起点的剩余量或完整准入数量。
        long takerRemainingQuantity = taker == null ? resolvedTaker.quantitySteps() : taker.remainingQuantitySteps();
        // 主动订单的买卖方向。
        var takerSide = taker == null ? resolvedTaker.side() : taker.side();
        // 主动订单的持仓方向，供衍生品身份准备。
        var takerPositionSide = taker == null ? resolvedTaker.positionSide() : taker.positionSide();
        // 主动订单的有效期策略，用于判断批次内是否终态。
        var takerTimeInForce = taker == null ? resolvedTaker.timeInForce() : taker.timeInForce();
        // 主动订单的订单类型，市价单在本项后不再继续成交。
        var takerOrderType = taker == null ? resolvedTaker.orderType() : taker.orderType();
        if (instrument == null || instrument != (taker == null ? resolvedTaker.instrument() : taker.instrument())) {
            throw new IllegalStateException("runtime match instrument is missing");
        }
        // 本次订单变化身份数组按已知事件和初始编号估算的容量。
        int expectedChanges = Math.max(2, result.matcherEvents().size() + result.cancellations().size()
                + (initialOrderIds == null ? 1 : initialOrderIds.length) + 1);
        // 本次新建或目标计划保留的原语订单编号数组。
        long[] orders;
        if (target == null) orders = new long[expectedChanges];
        else {
            if (target.orderIds.length < expectedChanges)
                target.orderIds = new long[Math.max(expectedChanges, target.orderIds.length * 2)];
            orders = target.orderIds;
        }
        // 本次构建已写入的去重订单身份数。
        int orderCount = 0;
        // 通用构建入口借用的运行时原语订单去重暂存。
        LongHashSet uniqueOrders = runtime.matcherSettlementOrderScratch();
        if (initialOrderIds == null) orderCount = addUnique(uniqueOrders, orders, orderCount, takerOrderId);
        else for (long /* 本次加入身份前缀或核对撤单授权的原语订单编号。 */ orderId : initialOrderIds) {
            if (orderId > 0) orderCount = addUnique(uniqueOrders, orders, orderCount, orderId);
        }
        // 已经归集的实际参与账户 Lane 位图。
        long laneMask = runtime.topology().accountLaneMask(activeUserId);
        // 构建时确定的主动账户 Lane 编号。
        int builtTakerLaneId = runtime.topology().accountLaneId(activeUserId);
        // 单个事件不会重复访问 maker，无批次时无需累计剩余量表。
        // 批次或多个事件是否需要累计剩余量校验表。
        boolean cumulativeValidation = batch != null || result.matcherEvents().size() > 1;
        // 逐订单累计剩余量暂存，单事件无批次时可以为空。
        LongLongHashMap remainingByOrderId = !cumulativeValidation ? null : batch == null
                ? runtime.matcherSettlementRemainingScratch() : batch.remainingByOrderId;
        if (batch == null && remainingByOrderId != null) remainingByOrderId.clear();
        if (remainingByOrderId == null || !remainingByOrderId.containsKey(takerOrderId)) {
            if (remainingByOrderId != null) remainingByOrderId.put(takerOrderId, takerRemainingQuantity);
        }
        // 本轮已经校验的 TRADE 事件数。
        int tradeCount = 0;
        try {
            // 当前构建或校验入口借用的原始撮合事件列表。
            var matcherEvents = result.matcherEvents();
            for (/* 当前遍历的原始撮合事件、撤单结果或订单前缀下标。 */ int index = 0; index < matcherEvents.size(); index++) {
                // 当前原始 Matcher 事件，TRADE 才参与成交数量与 maker 路由。
                MatcherEvent event = matcherEvents.get(index);
                if (event == null) throw new IllegalArgumentException("runtime match is required");
                if (event.eventType() != MatcherEventType.TRADE) continue;
                if (tradeCount == 0) preparePositionIdentity(runtime, identities, instrument,
                        activeUserId, takerPositionSide);
                if (event.price() <= 0 || event.size() <= 0) {
                    throw new IllegalArgumentException("invalid runtime match price or quantity");
                }
                // 当前成交在允许读取边界内取得的对手订单引用。
                OrderRuntime maker = batch == null ? requireOpen(runtime, event.matchedOrderId())
                        : batch.maker(runtime, event.matchedOrderId());
                if (batch != null && batch.terminalOrderIds.contains(maker.orderId())
                        || maker.userId() != event.matchedOrderUid() || maker.symbolId() != takerSymbolId
                        || maker.side() == takerSide || maker.userId() == activeUserId) {
                    throw new IllegalStateException("runtime match does not match authoritative orders");
                }
                // 扣除当前成交后主动订单的累计剩余量。
                long takerRemaining = Math.subtractExact(remainingByOrderId == null
                        ? takerRemainingQuantity : remainingByOrderId.get(takerOrderId), event.size());
                // 当前 maker 是否已经出现在累计剩余量表中。
                boolean knownMaker = remainingByOrderId != null && remainingByOrderId.containsKey(maker.orderId());
                // 当前成交前 maker 的累计剩余量，首次出现时取订单权威值。
                long makerBefore = knownMaker ? remainingByOrderId.get(maker.orderId()) : maker.remainingQuantitySteps();
                // 扣除当前成交后 maker 的累计剩余量。
                long makerRemaining = Math.subtractExact(makerBefore, event.size());
                if (takerRemaining < 0 || makerRemaining < 0) {
                    throw new IllegalStateException("fill exceeds runtime order remaining quantity");
                }
                if (remainingByOrderId != null) {
                    remainingByOrderId.put(takerOrderId, takerRemaining);
                    remainingByOrderId.put(maker.orderId(), makerRemaining);
                }
                if (batch != null && makerRemaining == 0) batch.terminalOrderIds.add(maker.orderId());
                if (!knownMaker)
                    preparePositionIdentity(runtime, identities, instrument, maker);
                orderCount = addUnique(uniqueOrders, orders, orderCount, maker.orderId());
                // 当前 maker 用户所属的账户 Lane 编号。
                int makerLane = runtime.topology().accountLaneId(maker.userId());
                tradeCount++;
                laneMask |= 1L << makerLane;
            }
            if (batch != null && (remainingByOrderId.get(takerOrderId) == 0 || takerTimeInForce.immediate()
                    || takerOrderType == com.surprising.aeron.protocol.CoreOrderType.MARKET))
                batch.terminalOrderIds.add(takerOrderId);
        } finally {
            if (batch == null && remainingByOrderId != null) remainingByOrderId.clear();
        }
        // 当前结果携带的撤单项列表。
        var cancellations = result.cancellations();
        for (/* 当前遍历的原始撮合事件、撤单结果或订单前缀下标。 */ int index = 0; index < cancellations.size(); index++) {
            // 当前检查的单笔撤单结果。
            var cancellation = cancellations.get(index);
            // 当前撤单结果在运行时可读取的订单，或 requireOpen 取得的待校验值。
            OrderRuntime order = runtime.order(cancellation.orderId());
            if (order != null) {
                orderCount = addUnique(uniqueOrders, orders, orderCount, order.orderId());
                laneMask |= runtime.topology().accountLaneMask(order.userId());
            }
        }
        // 本次新建或正在复用填充的结算计划。
        MatcherSettlementPlan plan = target == null
                ? new MatcherSettlementPlan(coreSequence, takerOrderId, activeUserId, laneMask, orders, orderCount, result.matcherEvents(), tradeCount)
                : target;
        plan.coreSequence = coreSequence; plan.takerOrderId = takerOrderId; plan.activeUserId = activeUserId;
        plan.admittedTaker = taker;
        plan.resolvedTaker = resolvedTaker;
        plan.directTaker = null;
        plan.directTakerSide = null;
        plan.directTakerSymbolId = 0;
        plan.directTakerInstrument = null;
        plan.takerLaneId = builtTakerLaneId;
        plan.requiredLaneMask = laneMask; plan.orderIds = orders; plan.orderCount = orderCount;
        plan.matcherEvents = result.matcherEvents(); plan.tradeCount = tradeCount;
        plan.preCancellationOrderIds = NO_ORDERS; plan.preCancellationSize = 0;
        plan.completedTrigger = null; plan.rejectedTaker = false;
        plan.laneEventsIndexed = false;
        return plan.indexLaneEvents(runtime);
    }

    /**
     * 创建没有成交的计划，复制调用方订单编号数组，按当前用户确定路由；数组为空时 taker 编号为零。
     * @param coreSequence 当前事实对应的 Core 序号
     * @param activeUserId 发起本代命令的账户用户
     * @param orderIds 受影响订单编号数组，复制或接管方式以具体入口为准
     * @param runtime 提供拓扑、允许读取的订单视图及构建暂存缓冲的交易运行时
     */
    public static MatcherSettlementPlan empty(long coreSequence, long activeUserId, long[] orderIds,
                                               TradingRuntimeState runtime) {
        if (coreSequence <= 0 || activeUserId <= 0 || orderIds == null || runtime == null) {
            throw new IllegalArgumentException("invalid empty matcher settlement plan input");
        }
        return new MatcherSettlementPlan(coreSequence,
                orderIds.length == 0 ? 0 : orderIds[orderIds.length - 1], activeUserId,
                runtime.topology().accountLaneMask(activeUserId), orderIds.clone(), orderIds.length,
                List.of(), 0);
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
     * 绑定所属计划的只读订单编号视图，同时提供原语读取接口；有效范围随当前计划代次变化，不持有独立快照。
     */
    private static final class OrderIdView extends AbstractList<Long>
            implements RandomAccess, com.surprising.aeron.service.command.support.PrimitiveLongView {
        /** 只读视图借用的所属计划，不复制其订单数组。 */
        private final MatcherSettlementPlan plan;

        /**
         * 只保存所属计划引用，读取时跟随该计划当前代的有效编号前缀。
         * @param plan 此只读视图借用的所属计划
         */
        private OrderIdView(MatcherSettlementPlan plan) { this.plan = plan; }

        /**
         * 校验当前有效范围后返回装箱订单编号；本视图不复制编号，计划复用后旧代内容失效。
         * @param index 当前代有效订单、事件或前置撤单下标
         */
        @Override
        public Long get(int index) {
            if (index < 0 || index >= plan.orderCount) throw new IndexOutOfBoundsException(index);
            return plan.orderIds[index];
        }

        /**
         * 返回所属计划当前代有效订单编号数。
         */
        @Override
        public int size() { return plan.orderCount; }

        /**
         * 为原语视图接口返回当前代有效订单编号数。
         */
        @Override
        public int primitiveSize() { return plan.orderCount; }

        /**
         * 校验有效下标后直接读取原语订单编号，避免装箱。
         * @param index 当前代有效订单、事件或前置撤单下标
         */
        @Override
        public long primitiveValueAt(int index) {
            if (index < 0 || index >= plan.orderCount) throw new IndexOutOfBoundsException(index);
            return plan.orderIds[index];
        }
    }

    /**
     * 根据已知订单的用户与持仓方向，委托产品感知入口准备持仓身份。
     * @param runtime 提供拓扑、允许读取的订单视图及构建暂存缓冲的交易运行时
     * @param identities 合约名称与运行时持仓身份字典
     * @param instrument 当前事实的规范币对对象，必须与订单输入一致
     * @param order 当前需校验或准备持仓身份的订单引用
     */
    private static void preparePositionIdentity(TradingRuntimeState runtime, RuntimeIdentityRegistry identities,
                                                CoreInstrument instrument, OrderRuntime order) {
        preparePositionIdentity(runtime, identities, instrument, order.userId(), order.positionSide());
    }

    /**
     * 只有衍生品才解析或建立用户、合约与持仓方向的运行时持仓键，现货直接返回。
     * @param runtime 提供拓扑、允许读取的订单视图及构建暂存缓冲的交易运行时
     * @param identities 合约名称与运行时持仓身份字典
     * @param instrument 当前事实的规范币对对象，必须与订单输入一致
     * @param userId 当前主动、撤单或持仓身份所属用户
     * @param positionSide 用户在该合约上的持仓方向
     */
    private static void preparePositionIdentity(TradingRuntimeState runtime, RuntimeIdentityRegistry identities,
                                                CoreInstrument instrument, long userId,
                                                com.surprising.aeron.protocol.CorePositionSide positionSide) {
        if (!runtime.productLine().isDerivative()) return;
        identities.positionKey(userId, instrument, positionSide);
    }

    /**
     * 从当前允许读取的运行时订单视图取得开放订单；缺失或已终态时报出订单和成交进度诊断。
     * @param runtime 提供拓扑、允许读取的订单视图及构建暂存缓冲的交易运行时
     * @param orderId 当前主动订单、撤单目标或待检查订单的原语编号
     */
    private static OrderRuntime requireOpen(TradingRuntimeState runtime, long orderId) {
        // 当前撤单结果在运行时可读取的订单，或 requireOpen 取得的待校验值。
        OrderRuntime order = runtime.order(orderId);
        if (order == null || order.status() != CoreOrderStatus.OPEN) {
            throw new IllegalStateException("runtime matched order is not open: " + orderId
                    + (order == null ? " missing" : " status=" + order.status()
                    + " executed=" + order.executedQuantitySteps()
                    + " remaining=" + order.remainingQuantitySteps()
                    + " revision=" + order.revision()));
        }
        return order;
    }

    /**
     * 返回本计划所属 Core 序号。
     */
    public long coreSequence() { return coreSequence; }
    /**
     * 返回当前 taker 或撤单目标编号。
     */
    public long takerOrderId() { return takerOrderId; }
    /**
     * 返回发起当前命令的账户用户编号。
     */
    public long activeUserId() { return activeUserId; }
    /**
     * 返回构建时保留的准入订单引用，借用期受计划租约限制，可能为空。
     */
    OrderRuntime admittedTaker() { return admittedTaker; }
    /**
     * 返回普通下单等入口保存的不可变准入输入，可能为空。
     */
    ResolvedPlaceOrder resolvedTaker() { return resolvedTaker; }
    /**
     * 返回本计划实际涉及的账户 Lane 位图。
     */
    public long requiredLaneMask() { return requiredLaneMask; }
    /**
     * 返回本代受影响订单编号的有效数量。
     */
    public int orderCount() { return orderCount; }

    /**
     * 借用计划自带的只读订单编号视图，不复制数组；视图随当前计划代次变化，不能跨租约保留。
     */
    public List<Long> orderIdList() { return orderIdView; }
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
     * 返回本代已经统计的 TRADE 成交事件数。
     */
    public int tradeCount() { return tradeCount; }
    /**
     * 把调用方整个原语撤单数组复制到计划自有缓冲，空数组清除本代撤单前缀；这里不重新验证撤单是否成功。
     * @param orderIds 受影响订单编号数组，复制或接管方式以具体入口为准
     */
    public MatcherSettlementPlan preCancellations(long[] orderIds) {
        if (orderIds == null) throw new IllegalArgumentException("pre-cancellation ids are required");
        if (orderIds.length == 0) {
            preCancellationOrderIds = NO_ORDERS;
            preCancellationSize = 0;
            return this;
        }
        ensurePreCancellationCapacity(orderIds.length);
        System.arraycopy(orderIds, 0, preCancellationStorage, 0, orderIds.length);
        preCancellationOrderIds = preCancellationStorage;
        preCancellationSize = orderIds.length;
        return this;
    }

    /**
     * 按结果顺序保存已接受且已授权的前置撤单，默认不额外允许替换原单编号。
     * @param cancellations Matcher 返回的逐订单撤单结果列表
     * @param authorizedOrderIds 准入期已经授权 Matcher 撤销的订单编号列表
     */
    void preCancellationsFromResult(List<CoreCancellationResult> cancellations,
                                     List<Long> authorizedOrderIds) {
        preCancellationsFromResult(cancellations, authorizedOrderIds, 0);
    }

    /**
     * 忽略拒绝撤单，对每个成功项核对授权列表或替换原单编号，再写入计划自有有效前缀；出现未授权成功撤单即失败。
     * @param cancellations Matcher 返回的逐订单撤单结果列表
     * @param authorizedOrderIds 准入期已经授权 Matcher 撤销的订单编号列表
     * @param replacedOrderId 额外允许撤销的替换原单编号，零表示没有该特例
     */
    void preCancellationsFromResult(List<CoreCancellationResult> cancellations,
                                   List<Long> authorizedOrderIds, long replacedOrderId) {
        if (cancellations == null || authorizedOrderIds == null) {
            throw new IllegalArgumentException("cancellation inputs are required");
        }
        // 本轮筛选或线性去重后保留的有效项数。
        int count = 0;
        // 授权列表是否支持直接读取原语编号的专用实现。
        boolean primitiveAuthorized = authorizedOrderIds instanceof com.surprising.aeron.service.command.ImmutableLongArrayList;
        for (/* 当前遍历的原始撮合事件、撤单结果或订单前缀下标。 */ int index = 0; index < cancellations.size(); index++) {
            // 当前检查的单笔撤单结果。
            CoreCancellationResult cancellation = cancellations.get(index);
            if (!cancellation.accepted()) continue;
            // 本次加入身份前缀或核对撤单授权的原语订单编号。
            long orderId = cancellation.orderId();
            // 当前成功撤单是否在准入授权列表内。
            boolean authorized = primitiveAuthorized
                    ? ((com.surprising.aeron.service.command.ImmutableLongArrayList) authorizedOrderIds)
                    .containsLong(orderId)
                    : authorizedOrderIds.contains(orderId);
            if (!authorized && orderId != replacedOrderId) {
                throw new IllegalStateException("direct matcher cancelled an unauthorized order");
            }
            ensurePreCancellationCapacity(count + 1);
            preCancellationStorage[count++] = orderId;
        }
        preCancellationOrderIds = count == 0 ? NO_ORDERS : preCancellationStorage;
        preCancellationSize = count;
    }

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
     * 按准入期望编号的顺序筛出实际接受的撤单，原语列表避免装箱，普通列表跳过空编号；每个期望项最多写入一次。
     * @param expectedOrderIds 按准入顺序排列的预期前置撤单编号
     * @param cancellations Matcher 返回的逐订单撤单结果列表
     */
    public void preCancellationsFromExpected(List<Long> expectedOrderIds,
                                             List<CoreCancellationResult> cancellations) {
        if (expectedOrderIds == null || cancellations == null) {
            throw new IllegalArgumentException("cancellation inputs are required");
        }
        if (expectedOrderIds.isEmpty()) {
            preCancellationOrderIds = NO_ORDERS;
            preCancellationSize = 0;
            return;
        }
        // 本轮筛选或线性去重后保留的有效项数。
        int count = 0;
        if (expectedOrderIds instanceof com.surprising.aeron.service.command.ImmutableLongArrayList /* 已经识别为专用原语列表的期望撤单编号视图。 */ primitive) {
            for (/* 当前按准入顺序遍历的期望撤单下标。 */ int expectedIndex = 0; expectedIndex < primitive.size(); expectedIndex++) {
                // 本次加入身份前缀或核对撤单授权的原语订单编号。
                long orderId = primitive.valueAt(expectedIndex);
                for (/* 当前为期望编号查找的实际撤单结果下标。 */ int cancellationIndex = 0; cancellationIndex < cancellations.size(); cancellationIndex++) {
                    // 当前检查的单笔撤单结果。
                    CoreCancellationResult cancellation = cancellations.get(cancellationIndex);
                    if (cancellation.accepted() && cancellation.orderId() == orderId) {
                        ensurePreCancellationCapacity(count + 1);
                        preCancellationStorage[count++] = orderId;
                        break;
                    }
                }
            }
        } else {
            for (/* 当前按准入顺序遍历的期望撤单下标。 */ int expectedIndex = 0; expectedIndex < expectedOrderIds.size(); expectedIndex++) {
                // 普通列表中取出的可空期望编号，空项跳过。
                Long expectedOrderId = expectedOrderIds.get(expectedIndex);
                if (expectedOrderId == null) continue;
                // 本次加入身份前缀或核对撤单授权的原语订单编号。
                long orderId = expectedOrderId;
                for (/* 当前为期望编号查找的实际撤单结果下标。 */ int cancellationIndex = 0; cancellationIndex < cancellations.size(); cancellationIndex++) {
                    // 当前检查的单笔撤单结果。
                    CoreCancellationResult cancellation = cancellations.get(cancellationIndex);
                    if (cancellation.accepted() && cancellation.orderId() == orderId) {
                        ensurePreCancellationCapacity(count + 1);
                        preCancellationStorage[count++] = orderId;
                        break;
                    }
                }
            }
        }
        preCancellationOrderIds = count == 0 ? NO_ORDERS : preCancellationStorage;
        preCancellationSize = count;
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
    private MatcherSettlementPlan indexLaneEvents(TradingRuntimeState runtime) {
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
    public boolean matcherEventTouchesLane(int index, int laneId, TradingRuntimeState runtime) {
        // 启用深度索引时，非 taker 调用方已经沿当前 maker Lane 的事件链传入下标，
        // 因此可直接判定相关；其他遍历方式仍按账户拓扑判断。
        if (laneEventsIndexed && laneId != takerLaneId) return true;
        // 当前原始 Matcher 事件，TRADE 才参与成交数量与 maker 路由。
        MatcherEvent event = matcherEvents.get(index);
        return takerLaneId == laneId
                || runtime.topology().accountLaneId(event.matchedOrderUid()) == laneId;
    }
}
