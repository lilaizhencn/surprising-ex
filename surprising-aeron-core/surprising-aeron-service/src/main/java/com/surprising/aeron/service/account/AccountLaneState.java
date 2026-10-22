package com.surprising.aeron.service.account;

import com.surprising.aeron.model.CoreLeverageKey;
import com.surprising.aeron.model.CoreOrderStatus;
import com.surprising.aeron.model.CoreTriggerOrderState;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.service.core.TradingAccountRuntime;
import com.surprising.aeron.service.core.change.LaneCommitDelta;
import com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.order.admission.AdmissionOrderIndex;
import com.surprising.aeron.service.order.admission.AdmissionSummary;
import com.surprising.aeron.service.order.admission.ResolvedPlaceOrder;
import com.surprising.aeron.service.order.state.OrderRuntime;
import com.surprising.aeron.service.order.state.ReservationRuntime;
import com.surprising.aeron.service.position.PositionRuntime;
import com.surprising.aeron.service.risk.LiquidationRuntime;
import com.surprising.aeron.service.risk.RiskLaneProcessor;
import com.surprising.aeron.service.risk.RiskLiquidationBatch;
import com.surprising.aeron.service.settlement.MatcherSettlementChanges;
import com.surprising.aeron.service.settlement.RuntimeDerivativeFillCalculator;
import com.surprising.aeron.service.settlement.RuntimeDerivativeMatchProcessor;
import com.surprising.aeron.service.settlement.RuntimeSpotMatchProcessor;
import com.surprising.aeron.service.snapshot.state.AccountLaneSnapshot;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Map;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.agrona.collections.Int2ObjectHashMap;
import org.agrona.collections.Long2LongHashMap;
import org.agrona.collections.Long2ObjectHashMap;
import org.eclipse.collections.impl.list.mutable.primitive.LongArrayList;
import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.LongIntHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.LongLongHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap;
import org.eclipse.collections.impl.set.mutable.primitive.LongHashSet;

/**
 * 一个账户 Lane 的权威数据与派生索引。工作线程或受控交接后的持有者串行修改用户、余额、订单、预留和持仓；冷业务表也属于同一 Lane。部分入口会核对或认领线程，许多直接读写方法只依赖调用方已进入命令或查询保护范围，返回的表和缓冲并非线程安全副本。
 */
public final class AccountLaneState {
    // 仅指标查询使用原子不透明读；不增加Lane热路径发布、锁或账户副本。
    /** 监控读取 Lane 修订号的句柄，各指标只保证单字段读取，不形成账户快照。 */
    private static final java.lang.invoke.VarHandle METRIC_REVISION = metricField("revision");
    /** 监控不透明读取已应用水位的句柄。 */
    private static final java.lang.invoke.VarHandle METRIC_APPLIED = metricField("appliedSequence");
    /** 监控不透明读取已提交水位的句柄。 */
    private static final java.lang.invoke.VarHandle METRIC_COMMITTED = metricField("committedSequence");
    /** 监控读取命令累计次数的句柄。 */
    private static final java.lang.invoke.VarHandle METRIC_COMMANDS = metricField("commandOperations");
    /** 监控读取 Matcher 结算累计次数的句柄。 */
    private static final java.lang.invoke.VarHandle METRIC_SETTLEMENTS = metricField("matcherSettlementOperations");

    /**
     * 为指标字段取得不透明原子读取句柄；字段不存在时让类初始化失败，避免发布错误指标。
     * @param name 要以不透明方式读取的 long 指标字段名称
     */
    private static java.lang.invoke.VarHandle metricField(String name) {
        try { return java.lang.invoke.MethodHandles.lookup().findVarHandle(AccountLaneState.class, name, long.class); }
        catch (/* 反射查找句柄失败的原因，转为类初始化错误暴露。 */ ReflectiveOperationException failure) { throw new ExceptionInInitializerError(failure); }
    }

    /**  创建时固定的账户分片编号，用于路由与监控。 返回创建时固定的 Lane 编号，不申请线程所有权。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private final int laneId;
    /**  创建时记录的 Lane 工作队列容量。 返回配置的队列容量，供调度和监控使用。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private final int queueCapacity;
    /**
     *  当前准入作用域首次创建用户成员集合时使用的容量提示，默认 2，批量结束后由外层恢复。
     * 返回当前准入用于首次分配用户成员集合的容量提示。
     * 直接设置准入容量提示；批量作用域负责在结束时恢复原值。
     * @param admissionIndexCapacity 当前批量准入希望预先容纳的成员数量
     */
    @Accessors(fluent = true, chain = false)
    @Getter
    @Setter
    int admissionIndexCapacity = 2;
    /** 登记属于本 Lane 的用户编号集合，和 users 值表分别维护。 */
    private final LongHashSet userIds;
    /** 用户编号到 Lane 权威用户元数据的表。 */
    final LongObjectHashMap<UserRuntime> users;
    /** 用户编号到资产余额表的权威数据，最内层资产键对应可变余额单元。 */
    final LongObjectHashMap<IntObjectHashMap<BalanceRuntime>> balances;
    // 后移删除避免订单频繁增删留下删除标记，进而触发整表重新分配。
    /** 订单编号到 Lane 权威订单的表，采用后移删除避免删除标记累积触发整表重建。 */
    final Long2ObjectHashMap<OrderRuntime> orders;
    // 用户活跃成员由 Lane 拥有，后移删除使持续更换的订单编号不积累删除标记。
    /** 按用户维护非终态订单编号集合，所属 Lane 更新；普通订单周转保留空容器。 */
    final LongObjectHashMap<org.agrona.collections.LongHashSet> activeOrderIdsByUser;
    /** 按订单编号保存的资金预留权威表，采用后移删除。 */
    final Long2ObjectHashMap<ReservationRuntime> reservations;
    /** 按用户维护已有预留编号集合，和预留值表配套更新。 */
    final LongObjectHashMap<org.agrona.collections.LongHashSet> reservationIdsByUser;
    /** 运行时持仓键到持仓权威记录的表，可能保留零仓位记录。 */
    final LongObjectHashMap<PositionRuntime> positions;
    /** 用户到持仓键的成员索引，便于只遍历该用户持仓。 */
    final LongObjectHashMap<LongHashSet> positionKeysByUser;
    /**
     *  本逻辑 Lane 复用的风险候选输出，包含同步跨多个 Lane 的调用，不能依赖线程本地共享一个批次。
     * 返回本逻辑 Lane 复用的风险候选输出缓冲。
     * 安装本 Lane 的风险候选输出缓冲，后续扫描可原位复用。
     * @param riskLiquidationBatch 本 Lane 要保留并复用的风险候选批次缓冲
     */
    @Accessors(fluent = true, chain = false)
    @Getter
    @Setter
    RiskLiquidationBatch riskLiquidationBatch;
    /** 按稠密合约和用户分组的开仓持仓键有序原语数组，供分页风险扫描使用。 */
    final IntObjectHashMap<LongObjectHashMap<LongArrayList>> positionKeysBySymbolAndUser
            = new IntObjectHashMap<>();
    /** 每个用户的客户身份键到订单编号映射，不直接以客户号文本作为热路径键。 */
    final LongObjectHashMap<Long2LongHashMap> clientOrderIndex;
    /**  本 Lane 拥有的客户号身份行、引用计数及可复用空行。 返回本 Lane 拥有的客户订单身份字典，不创建跨线程身份副本。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    final LaneClientIdentities clientIdentities = new LaneClientIdentities(this);
    /**  订单编号到所有客户身份别名的反向索引，便于终态逐项清理。 返回订单编号到客户别名的 Lane 内反向索引，终态清理据此逐项解除关联。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    final OrderClientKeyIndex clientKeysByOrderId = new OrderClientKeyIndex();
    /** 同一 Lane 持有的风险、强平、杠杆、条件单和划转等冷业务状态。 */
    final LaneColdState cold = new LaneColdState();
    /**  本 Lane 复用的杠杆读取结果槽。 返回可复用的杠杆查找槽，后续查找可能覆盖其中结果。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    final TradingAccountRuntime.LeverageLookup leverageLookup = new TradingAccountRuntime.LeverageLookup();
    /**  本 Lane 复用的运行时身份查找工作槽。 返回本 Lane 复用的身份查找结果槽；调用方遵守所属线程或有效读取边界，不能长期保留可变结果。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    final RuntimeIdentityRegistry.LookupScratch identityLookupScratch = new RuntimeIdentityRegistry.LookupScratch();
    /** 仅遍历用户修订时借用的命令收据，结束后清空，禁止嵌套借用。 */
    private LaneCommitDelta userRevisionChanges;
    /** 常驻的用户修订回调，使用当前借用的命令收据收集结果。 */
    private final org.eclipse.collections.api.block.procedure.primitive.LongLongProcedure advanceUserRevision =
            this::advanceUserRevision;
    /** 仅终态释放客户身份时借用的字典，遍历结束后清空。 */
    private RuntimeIdentityRegistry releasingClientIdentities;
    /** 当前客户身份释放作用域的用户编号。 */
    private long releasingClientUser;
    /** 常驻的客户别名释放回调，避免每个终态订单新建捕获对象。 */
    private final org.eclipse.collections.api.block.procedure.primitive.LongProcedure releaseClientIdentity =
            this::releaseClientIdentity;
    /** 仅解绑客户号映射时借用的用户映射表。 */
    private Long2LongHashMap unlinkingClientOrders;
    /** 当前解绑作用域要移除的订单编号。 */
    private long unlinkingOrderId;
    /** 常驻的映射解绑回调，删除前核对映射仍指向目标订单。 */
    private final org.eclipse.collections.api.block.procedure.primitive.LongProcedure unlinkClientOrder =
            this::unlinkClientOrder;
    /**  本 Lane 复用的衍生品成交结算工作对象。 返回本 Lane 复用的衍生品结算累加器，避免每次填充重新创建工作对象。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    final RuntimeDerivativeMatchProcessor.DerivativeSettlementAccumulator derivativeSettlementAccumulator =
            new RuntimeDerivativeMatchProcessor.DerivativeSettlementAccumulator();
    /**  本 Lane 复用的现货成交结算工作对象。 返回本 Lane 复用的现货结算累加器。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    final RuntimeSpotMatchProcessor.SpotSettlementAccumulator spotSettlementAccumulator =
            new RuntimeSpotMatchProcessor.SpotSettlementAccumulator();
    /**  本 Lane 复用的单持仓风险计算工作缓冲。 返回本 Lane 风险估值使用的持仓工作缓冲，不是可供外部保存的风险快照。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    final RiskLaneProcessor.PositionRiskScratch positionRiskScratch = new RiskLaneProcessor.PositionRiskScratch();
    /**  本 Lane 复用的单笔成交计算游标。 返回单笔成交计算复用的游标，结果在后续使用时可被覆盖。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    final RuntimeDerivativeFillCalculator.FillCursor singleFillCursor = new RuntimeDerivativeFillCalculator.FillCursor();

    /**  对外可见的队列历史深度峰值，写入按现有串行调用约定执行。 返回最近发布的队列深度峰值，监控读取不会申请账户所有权。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private volatile int queueHighWaterMark;

    /**
     * 在串行写入约定下保留见过的最大队列深度并返回它；这里只用 volatile，不是支持多写者竞争的原子取最大值。
     * @param depth 调用方观察到的当前队列深度
     */
    public int updateQueueHighWaterMark(int depth) {
        // 这次读取的旧队列深度峰值。
        int current = queueHighWaterMark;
        if (depth > current) {
            queueHighWaterMark = depth;
            return depth;
        }
        return current;
    }

    /**
     * 按触发编号写入条件记录并维护用户成员索引；替换为另一用户时先移除旧用户关联，返回旧记录。
     * @param value 要按触发编号保存的条件记录
     */
    public CoreTriggerOrderState putTrigger(CoreTriggerOrderState value) {
        // 同触发编号原有的条件记录，用于判断用户归属是否改变。
        CoreTriggerOrderState previous = cold.triggerOrders.put(value.triggerOrderId(), value);
        if (previous != null && previous.userId() != value.userId()) unindexTrigger(previous);
        cold.triggerIdsByUser.getIfAbsentPut(value.userId(), LongHashSet::new).add(value.triggerOrderId());
        return previous;
    }

    /**
     * 删除条件记录并解除对应用户关联，返回原记录；调用方负责满足 Lane 写入约定。
     * @param id 要移除的触发单编号
     */
    public CoreTriggerOrderState removeTrigger(long id) {
        // 刚移除的条件记录，用于同步删除用户关联。
        CoreTriggerOrderState previous = cold.triggerOrders.remove(id);
        if (previous != null) unindexTrigger(previous);
        return previous;
    }

    /**
     * 从原用户的条件编号集合移除一条记录，空集合一并删除；集合缺失表示索引已经不一致。
     * @param value 需要移除用户关联的原条件记录
     */
    private void unindexTrigger(CoreTriggerOrderState value) {
        // 原用户的触发编号集合，缺失表示权威值与成员索引不一致。
        LongHashSet ids = cold.triggerIdsByUser.get(value.userId());
        if (ids == null) throw new IllegalStateException("trigger user index is missing");
        ids.remove(value.triggerOrderId());
        if (ids.isEmpty()) cold.triggerIdsByUser.remove(value.userId());
    }
    // 有效命令序号为正，零表示缺失；准入和结算频繁周转时采用后移删除，保留原表容量。
    /** 订单到待完成 Core 序号的索引，正数为有效序号，零为缺失，删除时保留原后移表。 */
    final Long2LongHashMap pendingReservationSequences;
    /** 各用户仍待完成的预留条数聚合。 */
    private final LongIntHashMap pendingReservationCountsByUser;
    /** 按资产分组的用户待完成预留金额，归零只移除用户项，资产表保留容量。 */
    private final IntObjectHashMap<LongLongHashMap> pendingReservedUnitsByAsset = new IntObjectHashMap<>();
    /** 整个 Lane 仍待完成的预留条数。 */
    private int totalPendingReservations;
    /**
     *  本 Lane 新建客户身份的累计次数，完成收据由 Owner 汇入字典版本。
     * 返回 Lane 已记录的客户身份新建累计数。
     * 直接设置客户身份新建累计数，供恢复及受控更新使用；不自动汇入 Owner 字典版本。
     * @param clientIdentityAllocations 恢复或更新后的客户身份累计分配次数
     */
    @Accessors(fluent = true, chain = false)
    @Getter
    @Setter
    long clientIdentityAllocations;
    /** 该 Lane 已应用的修订计数，和单个用户或订单修订号不同。 */
    private long revision;
    /** 该 Lane 已应用的 Core 命令水位。 */
    private long appliedSequence;
    /** 该 Lane 已完成提交的 Core 命令水位。 */
    private long committedSequence;
    /** 由所属写入方累计的命令处理次数。 */
    private long commandOperations;
    /** 由所属写入方累计的 Matcher 结算次数。 */
    private long matcherSettlementOperations;
    /** 最近重建或恢复的业务状态哈希缓存，不随每次字段写入自动重算。 */
    private long localStateHash = 0xcbf29ce484222325L;
    /** 最近重建或恢复的余额哈希缓存，只包含用户、资产及可用冻结金额。 */
    private long localFundsHash = 0xcbf29ce484222325L;
    /** 本 Lane 唯一复用的准入汇总视图，每次查询前选择目标稠密合约编号。 */
    private final LaneAdmissionOrderIndex admissionOrderIndex = new LaneAdmissionOrderIndex();
    /**
     *  是否处于命令访问作用域的标记，本身不提供锁或跨线程发布。
     * 返回当前是否处于 Lane 命令作用域，不在此建立线程可见性或自动取得所有权。
     * 直接记录 Lane 命令作用域开关，真实进入和离开顺序由调用方维护。
     * @param commandScopeActive 是否标记为处于命令作用域
     */
    @Accessors(fluent = true, chain = false)
    @Getter
    @Setter
    boolean commandScopeActive;
    /**
     *  是否处于查询访问作用域的标记，不能代替实际读取屏障。
     * 返回已记录的查询作用域开关，不代替实际读取屏障检查。
     * 直接记录查询作用域开关，本身不暂停工作线程或建立读取屏障。
     * @param queryScopeActive 是否标记为处于查询作用域
     */
    @Accessors(fluent = true, chain = false)
    @Getter
    @Setter
    boolean queryScopeActive;
    /**
     *  当前命令借用的 Matcher 结算变化收集器。
     * 返回当前借用的 Matcher 结算变化收集器。
     * 绑定或解除当前结算变化收集器，不在此发布命令完成。
     * @param matcherSettlementChanges 当前结算变化收集器，null 表示解除借用
     */
    @Accessors(fluent = true, chain = false)
    @Getter
    @Setter
    MatcherSettlementChanges matcherSettlementChanges;

    /**
     * 创建一个 Lane 的账户状态和派生索引，检查编号与队列容量，并计算初始空状态哈希；此时不启动工作线程。
     * @param laneId 本 Lane 编号，范围为 0 到 63
     * @param queueCapacity 本 Lane 工作队列的正数容量
     */
    public AccountLaneState(int laneId, int queueCapacity) {
        this(laneId, queueCapacity,
                com.surprising.aeron.config.CoreRuntimeConfiguration.standalone().laneInitialEntities());
    }

    public AccountLaneState(int laneId, int queueCapacity, int initialEntityCapacity) {
        if (initialEntityCapacity < 16) throw new IllegalArgumentException("initial entity capacity must be at least 16");
        this.userIds = new LongHashSet(initialEntityCapacity);
        this.users = new LongObjectHashMap<>(initialEntityCapacity);
        this.balances = new LongObjectHashMap<>(initialEntityCapacity);
        this.orders = new Long2ObjectHashMap<>(initialEntityCapacity, 0.65f, false);
        this.activeOrderIdsByUser = new LongObjectHashMap<>(initialEntityCapacity);
        this.reservations = new Long2ObjectHashMap<>(initialEntityCapacity, 0.65f, false);
        this.reservationIdsByUser = new LongObjectHashMap<>(initialEntityCapacity);
        this.positions = new LongObjectHashMap<>(initialEntityCapacity);
        this.positionKeysByUser = new LongObjectHashMap<>(initialEntityCapacity);
        this.clientOrderIndex = new LongObjectHashMap<>(initialEntityCapacity);
        this.pendingReservationSequences = new Long2LongHashMap(initialEntityCapacity, 0.65f, 0, false);
        this.pendingReservationCountsByUser = new LongIntHashMap(initialEntityCapacity);
        if (laneId < 0 || laneId >= Long.SIZE || queueCapacity <= 0) {
            throw new IllegalArgumentException("invalid account lane");
        }
        this.laneId = laneId;
        this.queueCapacity = queueCapacity;
        this.localStateHash = computeStateHash();
        this.localFundsHash = computeFundsHash();
    }

    /** Owner 归还已脱离业务表订单的 SPSC 队列，Lane 等最终退休水位后才复用，容量为 4096。 */
    private final org.agrona.concurrent.OneToOneConcurrentArrayQueue<OrderRuntime> retiredOrders =
            new org.agrona.concurrent.OneToOneConcurrentArrayQueue<>(4_096);
    /** 已清除旧引用且跨过退休水位的本 Lane 订单复用池，最多缓存 4096 个对象。 */
    private final java.util.ArrayDeque<OrderRuntime> reusableOrders = new java.util.ArrayDeque<>();

    /**
     * 接收 Owner 交回的已脱离业务表的订单，交给单生产者单消费者回收队列；队列满时放弃缓存该对象，不丢弃业务命令。
     * @param order 已经脱离业务表、由 Owner 交回的订单对象
     */
    public void returnRetiredOrder(OrderRuntime order) {
        // 回收缓存有界，满时只放弃缓存已经脱离业务表的对象，不丢业务工作。
        retiredOrders.offer(order);
    }

    /**
     * 在所属线程收取已跨过最终退休水位的订单，清除旧引用后放入有界复用池，再准备本次订单；未过退休水位的对象继续等待。
     * @param product 订单所属产品线
     * @param userId 本次处理的用户编号，路由已由外层选择到本 Lane
     * @param intent 已经完成规则解析的下单意图
     * @param commandId 创建订单的命令幂等编号
     * @param symbolId 身份字典中的稠密合约编号
     * @param timestamp 创建命令携带的 Core 时间戳
     * @param position 创建命令的 Cluster 日志位置
     * @param retiredOwnerSequence Owner 已经完成最终命令退休的水位，用来判断旧订单是否可复用
     */
    public OrderRuntime preparedOrder(com.surprising.product.api.ProductLine product, long userId, ResolvedPlaceOrder intent,
            java.util.UUID commandId, int symbolId, long timestamp, long position, long retiredOwnerSequence) {
        assertOwner();
        // 回收队列头部的旧订单；只有退休水位已满足才可取出复用。
        OrderRuntime ready;
        while ((ready = retiredOrders.peek()) != null && ready.retiredAfterSequence() <= retiredOwnerSequence) {
            retiredOrders.poll();
            ready.clearRetiredReferences();
            if (reusableOrders.size() < 4_096) reusableOrders.addLast(ready);
        }
        return OrderRuntime.preparePooled(reusableOrders.pollLast(), product, userId, intent, commandId, symbolId, timestamp, position);
    }

    /**  当前实际拥有 Lane 状态的线程，受控交接期间允许为空。 返回当前绑定线程，供模块内交接所有权时核对；不在此自动绑定调用方。 */
    @Accessors(fluent = true, chain = false)
    @Getter(value = AccessLevel.PACKAGE)
    private volatile Thread owner;
    /** 用于从未绑定状态原子认领线程的 VarHandle，普通业务读写不通过它加锁。 */
    private static final VarHandle OWNER;
    /** 初始化线程认领句柄；反射失败直接阻止本类启动。 */
    static {
        try { OWNER = MethodHandles.lookup().findVarHandle(AccountLaneState.class, "owner", Thread.class); }
        catch (/* 反射查找句柄失败的原因，转为类初始化错误暴露。 */ ReflectiveOperationException failure) { throw new ExceptionInInitializerError(failure); }
    }

    /**
     * 让当前线程取得尚未绑定的 Lane，或核对自己已经是持有者。
     */
    public void bindOwner() { assertOwner(); }

    /**
     * 借用当前命令收据，逐用户应用结算带来的修订增量并写入收据；禁止嵌套遍历，结束后释放借用。
     * @param revisions 用户编号到本次结算应增加的修订次数
     * @param changes 借用到本次遍历结束的命令变化收据
     */
    public void applyUserRevisions(LongLongHashMap revisions, LaneCommitDelta changes) {
        assertOwner();
        if (userRevisionChanges != null) throw new IllegalStateException("nested Lane user revision traversal");
        userRevisionChanges = java.util.Objects.requireNonNull(changes, "user revision receipt");
        try { revisions.forEachKeyValue(advanceUserRevision); }
        finally { userRevisionChanges = null; }
    }

    /**
     * 用精确加法增加已有用户修订号，把 Lane 权威用户值写回用户表和当前命令收据。
     * @param userId 本次处理的用户编号，路由已由外层选择到本 Lane
     * @param count 该用户本次结算增加的修订次数
     */
    private void advanceUserRevision(long userId, long count) {
        // 本 Lane 已有的用户权威元数据。
        UserRuntime current = users.get(userId);
        if (current == null)
            throw new IllegalStateException("runtime user disappeared during matcher settlement: " + userId);
        // 精确增加结算次数后得到的新用户修订号。
        long revision = Math.addExact(current.revision(), count);
        // 原位更新或转换为 Lane 权威形式后的用户值。
        UserRuntime advanced = current.laneValue().withState(revision, current.positionMode());
        users.put(userId, advanced);
        userRevisionChanges.putUser(userId, advanced);
    }

    /**
     * 借用身份字典及用户编号，遍历该订单的全部客户别名并释放引用；本方法不删除订单反向索引。
     * @param identities 用于释放客户订单身份引用的运行时字典
     * @param orderId 本次处理的订单编号
     * @param userId 本次处理的用户编号，路由已由外层选择到本 Lane
     */
    public void releaseOrderClientIdentities(RuntimeIdentityRegistry identities, long orderId, long userId) {
        assertOwner();
        if (releasingClientIdentities != null)
            throw new IllegalStateException("nested terminal client identity release");
        releasingClientIdentities = java.util.Objects.requireNonNull(identities);
        releasingClientUser = userId;
        try { clientKeysByOrderId.forEach(orderId, releaseClientIdentity); }
        finally { releasingClientIdentities = null; releasingClientUser = 0; }
    }

    /**
     * 使用当前终态释放作用域的字典与用户，释放一个客户身份引用。
     * @param clientKey 当前订单引用的客户身份整数键
     */
    private void releaseClientIdentity(long clientKey) {
        releasingClientIdentities.releaseClientKeyInLane(this, releasingClientUser, clientKey);
    }

    /**
     * 仅移除仍指向该订单的用户客户号映射，再删除订单到别名的反向记录；避免删掉已被新订单接管的映射。
     * @param userId 本次处理的用户编号，路由已由外层选择到本 Lane
     * @param orderId 本次处理的订单编号
     */
    public void removeClientOrdersForOrder(long userId, long orderId) {
        assertOwner();
        if (unlinkingClientOrders != null) throw new IllegalStateException("nested client order unlink");
        // 当前用户客户键到订单编号的映射，遍历作用域临时借用它。
        Long2LongHashMap ordersByClient = clientOrderIndex.get(userId);
        if (ordersByClient != null) {
            unlinkingClientOrders = ordersByClient;
            unlinkingOrderId = orderId;
            try { clientKeysByOrderId.forEach(orderId, unlinkClientOrder); }
            finally { unlinkingClientOrders = null; unlinkingOrderId = 0; }
        }
        clientKeysByOrderId.remove(orderId);
    }

    /**
     * 核对当前客户键仍映射到待清理订单后再删除，保护已经改指向的客户号。
     * @param clientKey 当前订单引用的客户身份整数键
     */
    private void unlinkClientOrder(long clientKey) {
        if (unlinkingClientOrders.get(clientKey) == unlinkingOrderId) unlinkingClientOrders.remove(clientKey);
    }

    /**
     * 先核对当前所有者，再把所有权交给尚未启动的工作线程，避免线程启动后才暴露未绑定窗口。
     * @param worker 尚处于 NEW 状态的实际 Lane 工作线程
     */
    public void bindWorker(Thread worker) {
        assertOwner();
        if (worker == null || worker.getState() != Thread.State.NEW)
            throw new IllegalArgumentException("Account Lane worker must not have started");
        owner = worker;
    }

    /**
     * 由当前所有者清空绑定，允许后续受控交接取得 Lane；排空工作和互斥交接仍由调用方保证。
     */
    public void releaseOwnerForHandoff() {
        assertOwner();
        owner = null;
    }

    /**
     * 未绑定时用 CAS 认领当前线程；已绑定时只允许原持有者继续，其他线程直接失败。
     */
    public void assertOwner() {
        // 本次尝试访问 Lane 的实际线程。
        Thread current = Thread.currentThread();
        // 进入检查时观察到的绑定线程，空值才尝试 CAS 认领。
        Thread bound = owner;
        if (bound == null && OWNER.compareAndSet(this, null, current)) return;
        if (owner != current)
            throw new IllegalStateException("account lane " + laneId + " is bound to another thread");
    }

    /**
     * 累加 Lane 处理的命令次数；调用方保证所属线程串行写入，不在这里额外加锁。
     */
    public void recordCommandOperation() {
        commandOperations++;
    }

    /**
     * 累加 Lane 应用 Matcher 结算的次数；这里只记录数量，不采集结算延迟。
     */
    public void recordSettlementOperation() {
        matcherSettlementOperations++;
    }

    /** 初始化每类操作的零计数数组，当前监控只额外填入命令和结算次数。 */
    private static final long[] EMPTY_METRIC_OPERATIONS = new long[com.surprising.aeron.protocol.CoreLaneMetricsView.OPERATION_TYPE_COUNT];

    /**
     * 不读取账户表地编码监控计数；各字段允许独立滞后，把观察到的应用水位抬到不小于提交水位，不代表一致业务快照。
     * @param encoder 接收 Lane 计数的监控编码器
     * @param depth 调用方观察到的当前队列深度
     * @param capacity 要写入监控帧的队列容量
     * @param highWater 要写入监控帧的队列历史深度峰值
     */
    public void writeMetrics(com.surprising.aeron.protocol.CoreLaneMetricsCodec.Encoder encoder,
                      int depth, int capacity, int highWater) {
        // 不透明读取到的提交水位，各字段可能独立滞后。
        long committed = (long) METRIC_COMMITTED.getOpaque(this);
        // 观察到的应用水位与提交水位的较大值，防止监控帧显示应用落后于提交。
        long applied = Math.max(committed, (long) METRIC_APPLIED.getOpaque(this));
        encoder.writeLane(laneId, (long) METRIC_REVISION.getOpaque(this), applied, committed,
                depth, capacity, highWater, 0, 0);
        encoder.writeOperations(laneId, EMPTY_METRIC_OPERATIONS, EMPTY_METRIC_OPERATIONS, EMPTY_METRIC_OPERATIONS, EMPTY_METRIC_OPERATIONS);
        // 不透明读取到的累计命令次数。
        long commands = (long) METRIC_COMMANDS.getOpaque(this);
        if (commands != 0) {
            encoder.addOperation(laneId, 0, commands, 0, 0, 0);
        }
        // 不透明读取到的累计 Matcher 结算次数。
        long settlements = (long) METRIC_SETTLEMENTS.getOpaque(this);
        if (settlements != 0) {
            encoder.addOperation(laneId, 1, settlements, 0, 0, 0);
        }
    }

    /**
     * 核对所属线程后返回 Lane 应用修订次数。
     */
    public long revision() { assertOwner(); return revision; }
    /**
     * 核对所属线程后返回该 Lane 已应用的 Core 命令水位。
     */
    public long appliedSequence() { assertOwner(); return appliedSequence; }
    /**
     * 核对所属线程后返回该 Lane 已提交的 Core 命令水位。
     */
    public long committedSequence() { assertOwner(); return committedSequence; }
    /**
     * 返回最近一次重建或恢复的业务状态哈希；不会在读取时遍历状态表。
     */
    public long localStateHash() { assertOwner(); return localStateHash; }
    /**
     * 返回最近一次重建或恢复的余额哈希；本方法不重新计算余额。
     */
    public long localFundsHash() { assertOwner(); return localFundsHash; }
    /**
     * 检查用户编号是否登记在本 Lane 的成员集合中，不根据用户表是否有值作判断。
     * @param userId 本次处理的用户编号，路由已由外层选择到本 Lane
     */
    public boolean owns(long userId) { assertOwner(); return userIds.contains(userId); }
    /**
     * 返回登记的用户成员数，不等同于直接读取 users 表大小。
     */
    public int userCount() { assertOwner(); return userIds.size(); }

    /**
     * 核对所属线程并登记正数用户编号；重复登记不增加成员数，不在此创建用户余额。
     * @param userId 本次处理的用户编号，路由已由外层选择到本 Lane
     */
    public void registerUser(long userId) {
        assertOwner();
        if (userId <= 0) throw new IllegalArgumentException("userId must be positive");
        userIds.add(userId);
    }

    /**
     * 移除用户成员资格，并尝试释放已经空闲的准入汇总表；其他账户表与实体的清理由调用方另行完成。
     * @param userId 本次处理的用户编号，路由已由外层选择到本 Lane
     */
    public void removeUser(long userId) {
        assertOwner();
        userIds.remove(userId);
        admissionOrderIndex.removeUnusedUser(userId);
    }

    /**
     * 把已有预留登记为尚待 Matcher 完成的准入，先计算用户条数、总条数和资产金额，再记录命令序号及聚合值。
     * @param orderId 本次处理的订单编号
     * @param coreSequence 本次推进或核对的 Core 命令序号
     */
    public void markPendingReservation(long orderId, long coreSequence) {
        assertOwner();
        if (orderId <= 0 || coreSequence <= committedSequence) {
            throw new IllegalArgumentException("invalid pending reservation identity");
        }
        if (pendingReservationSequences.containsKey(orderId)) {
            throw new IllegalStateException("reservation is already pending");
        }
        // 本订单当前的预留权威记录，聚合金额以其剩余预留为准。
        ReservationRuntime reservation = reservations.get(orderId);
        if (reservation == null) throw new IllegalStateException("pending reservation is missing");
        // 精确增加一条后的用户待完成预留数，先计算再写入。
        int nextUserCount = Math.addExact(pendingReservationCountsByUser.get(reservation.userId()), 1);
        // 精确增加一条后的 Lane 待完成预留总数。
        int nextTotal = Math.addExact(totalPendingReservations, 1);
        // 当前用户当前资产已经聚合的待完成预留金额。
        long previousUnits = pendingReservedUnits(reservation.userId(), reservation.assetId());
        // 加上本预留剩余金额后的资产聚合值。
        long nextUnits = Math.addExact(previousUnits, reservation.reservedUnits());
        pendingReservationSequences.put(orderId, coreSequence);
        pendingReservationCountsByUser.put(reservation.userId(), nextUserCount);
        setPendingReservedUnits(reservation.userId(), reservation.assetId(), nextUnits);
        totalPendingReservations = nextTotal;
    }

    /**
     * 核对当前预留与命令序号后解除待完成标记，扣减条数和金额聚合；不删除预留权威记录或直接解冻余额。
     * @param orderId 本次处理的订单编号
     * @param coreSequence 本次推进或核对的 Core 命令序号
     */
    public void completePendingReservation(long orderId, long coreSequence) {
        pendingReservationCompletion(orderId, coreSequence, true);
    }

    /**
     * 执行与完成路径相同的序号、预留存在性检查及归零计算，但不写入聚合或移除标记。
     * @param orderId 本次处理的订单编号
     * @param coreSequence 本次推进或核对的 Core 命令序号
     */
    public void requirePendingReservationCompletion(long orderId, long coreSequence) {
        pendingReservationCompletion(orderId, coreSequence, false);
    }

    /**
     * 核对序号及预留存在性，按零下限计算扣减后的条数和金额；apply 为 false 时只走检查，计数不足在这里截为零而非抛错。
     * @param orderId 本次处理的订单编号
     * @param coreSequence 本次推进或核对的 Core 命令序号
     * @param apply true 表示实际解除待完成标记，false 表示只检查和计算
     */
    private void pendingReservationCompletion(long orderId, long coreSequence, boolean apply) {
        assertOwner();
        // 该订单待完成索引中的序号，必须和本次完成命令相同。
        long pendingSequence = pendingReservationSequences.get(orderId);
        if (pendingSequence != coreSequence) {
            throw new IllegalStateException("pending reservation sequence mismatch");
        }
        // 本订单当前的预留权威记录，聚合金额以其剩余预留为准。
        ReservationRuntime reservation = reservations.get(orderId);
        if (reservation == null) throw new IllegalStateException("pending reservation is missing");
        // 本用户完成前的待完成预留条数。
        int userCount = pendingReservationCountsByUser.get(reservation.userId());
        // 扣一条并以零为下限的用户条数，本算法不会因原计数不足而报错。
        int nextUserCount = Math.max(0, userCount - 1);
        // 扣一条并以零为下限的 Lane 总条数。
        int nextTotal = Math.max(0, totalPendingReservations - 1);
        // 当前用户当前资产已经聚合的待完成预留金额。
        long previousUnits = pendingReservedUnits(reservation.userId(), reservation.assetId());
        // 扣除当前预留金额并以零为下限的资产合计。
        long nextUnits = Math.max(0L, previousUnits - reservation.reservedUnits());
        if (!apply) return;
        pendingReservationSequences.remove(orderId);
        if (nextUserCount == 0) pendingReservationCountsByUser.removeKey(reservation.userId());
        else pendingReservationCountsByUser.put(reservation.userId(), nextUserCount);
        setPendingReservedUnits(reservation.userId(), reservation.assetId(), nextUnits);
        totalPendingReservations = nextTotal;
    }

    /**
     * 仅在旧预留仍待完成时调整聚合金额，禁止改变订单和用户；同资产替换或跨资产替换都先完成精确算术，保持条数与序号不变。
     * @param previous 原来仍待完成的预留记录
     * @param replacement 同订单、同用户的新预留，资产和剩余金额可以改变
     */
    public void replacePendingReservation(ReservationRuntime previous, ReservationRuntime replacement) {
        assertOwner();
        if (!pendingReservationSequences.containsKey(previous.orderId())) return;
        if (previous.orderId() != replacement.orderId() || previous.userId() != replacement.userId()) {
            throw new IllegalStateException("pending reservation owner cannot change");
        }
        // 旧预留所属资产目前的待完成金额聚合。
        long currentUnits = pendingReservedUnits(previous.userId(), previous.assetId());
        // 精确减去旧预留后的资产合计，小于零时拒绝替换。
        long remainingUnits = Math.subtractExact(currentUnits, previous.reservedUnits());
        if (remainingUnits < 0) throw new IllegalStateException("pending reservation counters are inconsistent");
        if (previous.assetId() == replacement.assetId()) {
            // 同资产替换时加入新预留后的聚合金额。
            long nextUnits = Math.addExact(remainingUnits, replacement.reservedUnits());
            setPendingReservedUnits(previous.userId(), previous.assetId(), nextUnits);
        } else {
            // 跨资产替换时，新资产原合计加上新预留的结果，先算完再写两边。
            long replacementUnits = Math.addExact(
                    pendingReservedUnits(previous.userId(), replacement.assetId()), replacement.reservedUnits());
            // 两项资产的新合计均先完成算术检查，再写入任一边，回滚替换也走同一顺序。
            setPendingReservedUnits(previous.userId(), previous.assetId(), remainingUnits);
            setPendingReservedUnits(previous.userId(), replacement.assetId(), replacementUnits);
        }
    }

    /**
     * 检查订单是否还带有待完成准入标记。
     * @param orderId 本次处理的订单编号
     */
    public boolean pendingReservation(long orderId) {
        assertOwner();
        return pendingReservationSequences.containsKey(orderId);
    }

    /**
     * 取得订单的待完成 Core 序号，未登记时返回零。
     * @param orderId 本次处理的订单编号
     */
    public long pendingReservationSequence(long orderId) {
        assertOwner();
        return pendingReservationSequences.get(orderId);
    }

    /**
     * 预留对象已经原位修改后，用调用方保留的旧金额修正待完成聚合；未带待完成标记的订单不处理。
     * @param reservation 已经原位修改的预留权威对象
     * @param previousReservedUnits 该预留在本次原位修改前的剩余预留金额
     */
    public void updatePendingReservationInPlace(ReservationRuntime reservation, long previousReservedUnits) {
        assertOwner();
        if (reservation == null || previousReservedUnits < 0) throw new IllegalArgumentException("invalid reservation update");
        if (!pendingReservationSequences.containsKey(reservation.orderId())) return;
        // 本用户本资产尚未修正的待完成聚合金额。
        long current = pendingReservedUnits(reservation.userId(), reservation.assetId());
        // 减旧预留再加当前预留后的聚合值。
        long next = Math.addExact(Math.subtractExact(current, previousReservedUnits), reservation.reservedUnits());
        if (next < 0) throw new IllegalStateException("pending reservation counters are inconsistent");
        setPendingReservedUnits(reservation.userId(), reservation.assetId(), next);
    }

    /**
     * 返回用户在指定资产的待完成预留合计；资产表或用户项缺失时返回零，调用方负责读取边界。
     * @param userId 本次处理的用户编号，路由已由外层选择到本 Lane
     * @param assetId 身份字典中的资产整数编号
     */
    public long pendingReservedUnits(long userId, int assetId) {
        // 此资产下的用户待完成金额表，未创建时按零处理。
        LongLongHashMap unitsByUser = pendingReservedUnitsByAsset.get(assetId);
        return unitsByUser == null ? 0 : unitsByUser.get(userId);
    }

    /**
     * 写入单个资产下的用户待完成金额；零值只移除用户项，保留资产容器供后续复用。
     * @param userId 本次处理的用户编号，路由已由外层选择到本 Lane
     * @param assetId 身份字典中的资产整数编号
     * @param units 此用户此资产更新后的待完成预留合计，零值删除用户项
     */
    private void setPendingReservedUnits(long userId, int assetId, long units) {
        // 此资产复用的用户金额表，归零只删用户项。
        LongLongHashMap users = pendingReservedUnitsByAsset.get(assetId);
        if (units == 0) {
            if (users != null) users.removeKey(userId);
        } else {
            if (users == null) {
                users = new LongLongHashMap();
                pendingReservedUnitsByAsset.put(assetId, users);
            }
            users.put(userId, units);
        }
    }

    /**
     * 返回某用户仍待完成的预留条数，用户没有记录时为零。
     * @param userId 本次处理的用户编号，路由已由外层选择到本 Lane
     */
    public int pendingReservationCount(long userId) {
        assertOwner();
        return pendingReservationCountsByUser.get(userId);
    }

    /**
     * 返回本 Lane 全部待完成预留条数。
     */
    public int pendingReservationCount() {
        assertOwner();
        return totalPendingReservations;
    }

    /**
     * 检查是否还有待完成预留，用于命令和快照边界判断。
     */
    boolean hasPendingReservations() {
        assertOwner();
        return totalPendingReservations != 0;
    }

    /**
     * 由账户所有者从自己的活跃订单选出会自成交的反向订单，与平仓容量撤单合并去重；新增自成交撤单时按编号排序，否则原样返回已有列表。
     * @param userId 本次处理的用户编号，路由已由外层选择到本 Lane
     * @param symbolId 身份字典中的稠密合约编号
     * @param side 新订单的买卖方向，自成交候选须为反向订单
     * @param matchingPrice 新订单送入 Matcher 的保护价或限价，以价格档位表示
     * @param excludedOrderId 改单时需要跳过的原订单编号
     * @param newOrderId 本次新订单编号，不能被选作前置撤单
     * @param closeCapacity 已有的平仓容量冲突撤单列表，无新增自成交冲突时原样返回
     */
    public java.util.List<Long> preMatchingCancellations(long userId, int symbolId,
            com.surprising.aeron.protocol.CoreOrderSide side, long matchingPrice,
            long excludedOrderId, long newOrderId, java.util.List<Long> closeCapacity) {
        assertOwner();
        // 该用户活跃订单编号集合，避免遍历其他用户订单。
        var active = activeOrderIdsByUser.get(userId);
        if (active == null || active.isEmpty()) return closeCapacity;
        // 发现自成交候选后才创建的去重集合，先合入已有平仓容量撤单。
        LongHashSet cancellations = null;
        // 该用户活跃订单集合的原语迭代器。
        var iterator = active.iterator();
        while (iterator.hasNext()) {
            // 当前候选旧订单编号，原订单、新订单和待完成准入都要跳过。
            long id = iterator.nextValue();
            if (id == excludedOrderId || id == newOrderId || pendingReservation(id)) continue;
            // 当前编号在 Lane 权威表中对应的订单记录。
            OrderRuntime order = orders.get(id);
            if (order == null || order.symbolId() != symbolId || order.side() == side) continue;
            // 该反向订单价格是否会与新订单匹配价交叉。
            boolean crosses = side == com.surprising.aeron.protocol.CoreOrderSide.BUY
                    ? matchingPrice >= order.priceTicks() : matchingPrice <= order.priceTicks();
            if (crosses) {
                if (cancellations == null) {
                    cancellations = new LongHashSet();
                    for (long /* 已有平仓容量冲突列表中需要保留的撤单编号。 */ closeId : closeCapacity) cancellations.add(closeId);
                }
                cancellations.add(id);
            }
        }
        if (cancellations == null) return closeCapacity;
        // 合并去重后交给 Matcher 的撤单编号数组，按升序排列。
        long[] ids = cancellations.toArray();
        java.util.Arrays.sort(ids);
        return com.surprising.aeron.support.collection.ImmutableLongArrayList.takeOwnership(ids);
    }

    /**
     * 选择本次准入的稠密合约编号并返回复用索引视图；下一次选择会改变该视图，不应跨调用保存。
     * @param symbolId 身份字典中的稠密合约编号
     */
    public AdmissionOrderIndex admissionOrderIndex(int symbolId) {
        assertOwner();
        if (symbolId < 0) throw new IllegalArgumentException("invalid admission instrumentId");
        admissionOrderIndex.symbolId = symbolId;
        return admissionOrderIndex;
    }

    /**
     * 把订单转换为 Lane 权威值后按编号存入，同时维护用户活跃成员和准入聚合索引。
     * @param order 本次参与索引更新的订单记录
     */
    public void putOrder(OrderRuntime order) {
        assertOwner();
        if (order == null) throw new IllegalArgumentException("order is required");
        order = order.laneValue();
        // 同订单编号写入前的旧权威值，用于调整索引。
        OrderRuntime previous = orders.put(order.orderId(), order);
        replaceActiveOrder(previous, order);
        admissionOrderIndex.replace(previous, order);
    }

    /**
     * 删除订单权威记录并同步移除活跃成员和准入聚合；本方法不负责预留、余额或客户身份清理。
     * @param orderId 本次处理的订单编号
     */
    public OrderRuntime removeOrder(long orderId) {
        assertOwner();
        // 被移除的原订单，用其原分组清理成员和准入统计。
        OrderRuntime previous = orders.remove(orderId);
        replaceActiveOrder(previous, null);
        admissionOrderIndex.replace(previous, null);
        return previous;
    }

    /**
     * 原位应用成交数量、手续费、状态与版本，再用旧活跃标记和旧剩余量修正两个索引，不创建中间订单副本。
     * @param orderId 本次处理的订单编号
     * @param executed 更新后的累计已成交数量步数，与剩余量之和应等于原始数量
     * @param remaining 成交后的剩余数量步数
     * @param feeUnits 本次新增手续费整数金额
     * @param nextStatus 更新后订单状态
     * @param nextRevision 更新后订单修订号
     * @param commitTimestamp 本次结果对应的 Core 提交时间
     * @param commitPosition 本次结果对应的 Cluster 日志位置
     */
    public OrderRuntime updateOrderInPlace(long orderId, long executed, long remaining, long feeUnits,
                                    CoreOrderStatus nextStatus, long nextRevision,
                                    long commitTimestamp, long commitPosition) {
        assertOwner();
        // 当前编号在 Lane 权威表中对应的订单记录。
        OrderRuntime order = orders.get(orderId);
        if (order == null) throw new IllegalArgumentException("runtime order is not registered: " + orderId);
        // 订单写入前是否为非终态，原位修改后无法再从同一对象读取旧状态。
        boolean previousActive = active(order);
        // 原位成交前的剩余数量，更新准入汇总时作为扣减基数。
        long previousRemaining = order.remainingQuantitySteps();
        order.applyFillInPlace(executed, remaining, feeUnits, nextStatus, nextRevision,
                commitTimestamp, commitPosition);
        if (previousActive && !active(order)) removeActiveOrder(order.userId(), order.orderId());
        else if (!previousActive && active(order)) addActiveOrder(order.userId(), order.orderId());
        admissionOrderIndex.updateInPlace(order, previousActive, previousRemaining);
        return order;
    }

    /**
     * 原位修改订单状态及提交元数据，再同步活跃成员和准入汇总；入口自身不调用 assertOwner，调用方须已持有 Lane。
     * @param orderId 本次处理的订单编号
     * @param nextStatus 更新后订单状态
     * @param nextRevision 更新后订单修订号
     * @param commitTimestamp 本次结果对应的 Core 提交时间
     * @param commitPosition 本次结果对应的 Cluster 日志位置
     */
    public OrderRuntime updateOrderStatusInPlace(long orderId, CoreOrderStatus nextStatus, long nextRevision,
                                          long commitTimestamp, long commitPosition) {
        // 当前编号在 Lane 权威表中对应的订单记录。
        OrderRuntime order = orders.get(orderId);
        if (order == null) throw new IllegalArgumentException("runtime order is not registered: " + orderId);
        // 订单写入前是否为非终态，原位修改后无法再从同一对象读取旧状态。
        boolean previousActive = active(order);
        order.applyStatusInPlace(nextStatus, nextRevision, commitTimestamp, commitPosition);
        if (previousActive && !active(order)) removeActiveOrder(order.userId(), order.orderId());
        else if (!previousActive && active(order)) addActiveOrder(order.userId(), order.orderId());
        admissionOrderIndex.updateInPlace(order, previousActive, order.remainingQuantitySteps());
        return order;
    }

    /**
     * 遍历该用户活跃订单，精确累加指定合约、持仓腿、方向和保证金模式的只减仓剩余量。
     * @param userId 本次处理的用户编号，路由已由外层选择到本 Lane
     * @param symbolId 身份字典中的稠密合约编号
     * @param positionSide 要选择的净仓、多仓或空仓持仓腿
     * @param side 要选择的买入或卖出方向
     * @param marginMode 要选择的全仓或逐仓保证金模式
     */
    public long openReduceOnlyQuantity(long userId, int symbolId, CorePositionSide positionSide,
                                CoreOrderSide side, CoreMarginMode marginMode) {
        assertOwner();
        // 当前用户的活跃订单编号集合，不复制订单值。
        var orderIds = activeOrderIdsByUser.get(userId);
        if (orderIds == null) return 0;
        // 已匹配合约、方向、持仓腿及保证金模式的只减仓剩余量累积值。
        long total = 0;
        // 该用户活跃订单编号的原语迭代器。
        var iterator = orderIds.iterator();
        while (iterator.hasNext()) {
            // 当前需要检查是否属于目标只减仓分组的订单编号。
            long orderId = iterator.nextValue();
            // 当前编号在 Lane 权威表中对应的订单记录。
            OrderRuntime order = orders.get(orderId);
            if (order != null && order.symbolId() == symbolId && order.reduceOnly()
                    && order.positionSide() == positionSide && order.side() == side
                    && order.marginMode() == marginMode) {
                total = Math.addExact(total, order.remainingQuantitySteps());
            }
        }
        return total;
    }

    /**
     * 根据新旧订单是否非终态维护用户成员；同用户且两边都活跃时成员不变，数量变化由准入汇总另行处理。
     * @param previous 替换或移除之前的订单记录，可由对应路径传入 null
     * @param replacement 替换后的订单记录，移除路径可以传入 null
     */
    private void replaceActiveOrder(OrderRuntime previous, OrderRuntime replacement) {
        // 同用户订单在非终态内改数量或状态，不改变活跃成员资格。
        if (active(previous) && active(replacement) && previous.userId() == replacement.userId()) return;
        if (active(previous)) removeActiveOrder(previous.userId(), previous.orderId());
        if (active(replacement)) addActiveOrder(replacement.userId(), replacement.orderId());
    }

    /**
     * 把订单加入用户活跃编号集合；首次分配使用本次准入容量提示，后续复用同一容器。
     * @param userId 本次处理的用户编号，路由已由外层选择到本 Lane
     * @param orderId 本次处理的订单编号
     */
    private void addActiveOrder(long userId, long orderId) {
        // 当前用户的活跃订单编号集合，不复制订单值。
        var orderIds = activeOrderIdsByUser.get(userId);
        if (orderIds == null) {
            orderIds = new org.agrona.collections.LongHashSet(admissionIndexCapacity, 0.65f, true);
            activeOrderIdsByUser.put(userId, orderIds);
        }
        orderIds.add(orderId);
    }

    /**
     * 要求活跃成员确实存在后移除编号；即使集合变空也保留容器，用户移除路径负责最终清理。
     * @param userId 本次处理的用户编号，路由已由外层选择到本 Lane
     * @param orderId 本次处理的订单编号
     */
    private void removeActiveOrder(long userId, long orderId) {
        // 当前用户的活跃订单编号集合，不复制订单值。
        var orderIds = activeOrderIdsByUser.get(userId);
        if (orderIds == null || !orderIds.remove(orderId)) {
            throw new IllegalStateException("active order index is missing");
        }
        // 用户仍存在时保留空原语容器，终态订单接续新订单时可以复用已清空的容量。
    }

    /**
     * 把预留订单编号加入用户成员集合，按准入容量提示首次创建集合。
     * @param userId 本次处理的用户编号，路由已由外层选择到本 Lane
     * @param orderId 本次处理的订单编号
     */
    public void addReservationId(long userId, long orderId) {
        // 此用户复用的预留编号成员集合。
        var ids = reservationIdsByUser.get(userId);
        if (ids == null) {
            ids = new org.agrona.collections.LongHashSet(admissionIndexCapacity, 0.65f, true);
            reservationIdsByUser.put(userId, ids);
        }
        ids.add(orderId);
    }

    /**
     * 从用户预留成员集合移除编号，缺失时不报错，空容器继续保留供该用户复用。
     * @param userId 本次处理的用户编号，路由已由外层选择到本 Lane
     * @param orderId 本次处理的订单编号
     */
    public void removeReservationId(long userId, long orderId) {
        // 此用户已有的预留编号集合，缺失时无需处理。
        var ids = reservationIdsByUser.get(userId);
        if (ids != null) ids.remove(orderId);
        // 与活跃订单成员一样，保留此用户已有的空容器。
    }

    /**
     * 仅把非空且状态非终态的订单视为活跃，不另外检查剩余数量。
     * @param order 待检查的订单，null 直接视为不活跃
     */
    private static boolean active(OrderRuntime order) {
        return order != null && !order.status().terminal();
    }

    /**
     * 从活跃订单增量维护准入需要的数量和模式条数，避免逐次扫描订单；视图中的目标合约和返回槽都由本 Lane 复用。
     */
    private final class LaneAdmissionOrderIndex implements AdmissionOrderIndex {
        /** 用户到稠密合约准入汇总的索引，和订单权威表按同一更新边界维护。 */
        private final Long2ObjectHashMap<Int2ObjectHashMap<AdmissionAggregate>> summariesByUser =
                new Long2ObjectHashMap<>();
        /** 已脱离用户合约索引的空汇总单元复用池，不带用户身份，最多保存 256 个。 */
        private final java.util.ArrayDeque<AdmissionAggregate> emptyAggregates = new java.util.ArrayDeque<>();
        /** 每次 inspect 覆盖的准入统计返回槽，调用方不能保存为长期快照。 */
        private final AdmissionSummary summary = new AdmissionSummary();
        /** 本次复用准入视图选择的稠密合约编号。 */
        private int symbolId;

        /**
         * 用外层选择的稠密合约编号读取准入汇总，把数量和冲突模式条数写入同一个复用返回槽；接口的文本合约参数不参与查找。
         * @param userId 本次处理的用户编号，路由已由外层选择到本 Lane
         * @param instrumentId 接口保留的文本合约编号；本实现使用外层已选择的 symbolId，不读取此参数
         * @param positionSide 要选择的净仓、多仓或空仓持仓腿
         * @param side 要选择的买入或卖出方向
         * @param conflictingMarginMode 需要统计是否冲突的保证金模式
         */
        @Override
        public AdmissionSummary inspect(
                long userId, String instrumentId, CorePositionSide positionSide,
                CoreOrderSide side, CoreMarginMode conflictingMarginMode) {
            // 当前用户按稠密合约分组的准入统计表。
            Int2ObjectHashMap<AdmissionAggregate> bySymbol = summariesByUser.get(userId);
            // 本用户本合约的准入汇总单元；移除路径缺失时表示索引不一致。
            AdmissionAggregate aggregate = bySymbol == null ? null : bySymbol.get(symbolId);
            return aggregate == null ? summary.set(0, 0, 0)
                    : summary.set(aggregate.pending(positionSide, side), aggregate.reduceOnly(side),
                    aggregate.marginMode(positionSide, conflictingMarginMode));
        }

        /**
         * 订单原位更新后，若仍活跃只替换数量；活跃状态改变时按旧量移除或按新量加入统计。
         * @param order 本次参与索引更新的订单记录
         * @param previousActive 修改前订单是否非终态
         * @param previousRemaining 修改前订单的剩余数量步数
         */
        private void updateInPlace(OrderRuntime order, boolean previousActive, long previousRemaining) {
            // 原位更新后的订单是否仍为非终态。
            boolean nextActive = active(order);
            if (previousActive && nextActive) {
                // 当前用户按稠密合约分组的准入统计表。
                var bySymbol = summariesByUser.get(order.userId());
                // 本用户本合约的准入汇总单元；移除路径缺失时表示索引不一致。
                var aggregate = bySymbol == null ? null : bySymbol.get(order.symbolId());
                if (aggregate == null) throw new IllegalStateException("admission order index is missing");
                aggregate.replaceQuantity(previousRemaining, order.remainingQuantitySteps(), order);
                return;
            }
            if (previousActive) update(order, -1, previousRemaining);
            if (nextActive) update(order, 1, order.remainingQuantitySteps());
        }

        /**
         * 分组身份完全一致且两边活跃时只更新数量；其他变化先移除旧订单统计，再加入新订单统计。
         * @param previous 替换或移除之前的订单记录，可由对应路径传入 null
         * @param replacement 替换后的订单记录，移除路径可以传入 null
         */
        private void replace(OrderRuntime previous, OrderRuntime replacement) {
            if (active(previous) && active(replacement)
                    && previous.userId() == replacement.userId() && previous.symbolId() == replacement.symbolId()
                    && previous.side() == replacement.side() && previous.positionSide() == replacement.positionSide()
                    && previous.marginMode() == replacement.marginMode() && previous.reduceOnly() == replacement.reduceOnly()) {
                // 当前用户按稠密合约分组的准入统计表。
                var bySymbol = summariesByUser.get(previous.userId());
                // 本用户本合约的准入汇总单元；移除路径缺失时表示索引不一致。
                var aggregate = bySymbol == null ? null : bySymbol.get(previous.symbolId());
                if (aggregate == null) throw new IllegalStateException("admission order index is missing");
                aggregate.replaceQuantity(previous, replacement);
                return;
            }
            if (active(previous)) update(previous, -1);
            if (active(replacement)) update(replacement, 1);
        }

        /**
         * 用订单当前剩余量执行加入或移除准入统计。
         * @param order 本次参与索引更新的订单记录
         * @param direction 加入订单时为 1，移除订单时为 -1
         */
        private void update(OrderRuntime order, int direction) {
            update(order, direction, order.remainingQuantitySteps());
        }

        /**
         * 按用户与合约找到汇总单元，加入时可复用空单元；最后一单移除后回收零单元，并按用户是否仍存在决定保留空表。
         * @param order 本次参与索引更新的订单记录
         * @param direction 加入订单时为 1，移除订单时为 -1
         * @param remainingQuantity 要加入或移除的订单剩余数量步数
         */
        private void update(OrderRuntime order, int direction, long remainingQuantity) {
            // 当前用户按稠密合约分组的准入统计表。
            Int2ObjectHashMap<AdmissionAggregate> bySymbol = summariesByUser.get(order.userId());
            // 本用户本合约的准入汇总单元；移除路径缺失时表示索引不一致。
            AdmissionAggregate aggregate = bySymbol == null ? null : bySymbol.get(order.symbolId());
            if (aggregate == null) {
                if (direction < 0) throw new IllegalStateException("admission order index is missing");
                aggregate = emptyAggregates.pollLast();
                if (aggregate == null) aggregate = new AdmissionAggregate();
                aggregate.update(order, direction, remainingQuantity);
                bySymbol = bySymbol == null ? new Int2ObjectHashMap<>(8, 0.65f, false) : bySymbol;
                bySymbol.put(order.symbolId(), aggregate);
                summariesByUser.put(order.userId(), bySymbol);
                return;
            }
            aggregate.update(order, direction, remainingQuantity);
            if (aggregate.empty()) {
                bySymbol.remove(order.symbolId());
                if (emptyAggregates.size() < 256) emptyAggregates.addLast(aggregate);
                removeUnusedUser(order.userId());
            }
        }

        /**
         * 只有用户已不在成员集合且其合约汇总表为空时才删除用户表，保留仍在使用的成员或订单分组。
         * @param userId 本次处理的用户编号，路由已由外层选择到本 Lane
         */
        private void removeUnusedUser(long userId) {
            // 当前用户按稠密合约分组的准入统计表。
            var bySymbol = summariesByUser.get(userId);
            // 用户还存在时保留空表；若用户先被移除，则由最后一笔订单清理时释放该表。
            if (bySymbol != null && bySymbol.isEmpty() && !userIds.contains(userId)) summariesByUser.remove(userId);
        }

    }

    /**
     * 单个用户、单个合约的活跃订单统计。普通剩余量按持仓腿及方向分组，只减仓量按买卖方向分组，保证金模式保存条数而不是金额。
     */
    private static final class AdmissionAggregate {
        /** 净仓腿非只减仓买单的剩余数量合计。 */
        private long netBuy;
        /** 净仓腿非只减仓卖单的剩余数量合计。 */
        private long netSell;
        /** 多仓腿非只减仓买单的剩余数量合计。 */
        private long longBuy;
        /** 多仓腿非只减仓卖单的剩余数量合计。 */
        private long longSell;
        /** 空仓腿非只减仓买单的剩余数量合计。 */
        private long shortBuy;
        /** 空仓腿非只减仓卖单的剩余数量合计。 */
        private long shortSell;
        /** 全部持仓腿只减仓买单的剩余数量合计。 */
        private long reduceBuy;
        /** 全部持仓腿只减仓卖单的剩余数量合计。 */
        private long reduceSell;
        /** 净仓腿全仓模式的活跃订单条数。 */
        private int netCross;
        /** 净仓腿逐仓模式的活跃订单条数。 */
        private int netIsolated;
        /** 多仓腿全仓模式的活跃订单条数。 */
        private int longCross;
        /** 多仓腿逐仓模式的活跃订单条数。 */
        private int longIsolated;
        /** 空仓腿全仓模式的活跃订单条数。 */
        private int shortCross;
        /** 空仓腿逐仓模式的活跃订单条数。 */
        private int shortIsolated;
        /** 此用户此合约下的活跃订单总条数，归零后允许回收汇总单元。 */
        private int orders;

        /**
         * 从新旧同分组订单取剩余量，按原订单分组替换汇总中的数量，不改变订单条数或保证金模式条数。
         * @param previous 替换或移除之前的订单记录，可由对应路径传入 null
         * @param replacement 替换后的订单记录，移除路径可以传入 null
         */
        void replaceQuantity(OrderRuntime previous, OrderRuntime replacement) {
            replaceQuantity(previous.remainingQuantitySteps(), replacement.remainingQuantitySteps(), previous);
        }

        /**
         * 要求分组订单存在，精确减去旧数量并加上新数量，拒绝数量下溢后才写入对应分组。
         * @param previousRemaining 修改前订单的剩余数量步数
         * @param nextRemaining 修改后订单的剩余数量步数
         * @param grouping 提供方向、持仓腿和只减仓标记的订单，分组必须与原统计一致
         */
        private void replaceQuantity(long previousRemaining, long nextRemaining, OrderRuntime grouping) {
            // 原位成交更新不改变分组；调用方用能说明原分组的订单提供方向和持仓腿。
            // 分组订单的买卖方向，供选择数量统计格。
            CoreOrderSide side = grouping == null ? null : grouping.side();
            // 分组订单的持仓腿，普通订单数量按此细分。
            CorePositionSide positionSide = grouping == null ? null : grouping.positionSide();
            // 此订单是否只减仓，只减仓量使用单独的方向统计。
            boolean reduce = grouping != null && grouping.reduceOnly();
            if (grouping == null) throw new IllegalStateException("in-place admission grouping is missing");
            // 更新前该数量分组的合计剩余量。
            long current = reduce ? reduceOnly(side) : pending(positionSide, side);
            // 从分组合计精确扣掉原订单数量后的其他订单数量。
            long remaining = Math.subtractExact(current, previousRemaining);
            if (remaining < 0) throw new IllegalStateException("admission order index underflow");
            // 再加入新订单剩余量后的分组合计。
            long next = Math.addExact(remaining, nextRemaining);
            if (reduce) assignReduceOnly(side, next);
            else assignPending(positionSide, side, next);
        }

        /**
         * 先计算并检查分组数量、保证金模式条数和总订单数，再统一写入统计；只减仓数量按方向汇总，普通量按持仓腿及方向汇总。
         * @param order 本次参与索引更新的订单记录
         * @param direction 加入订单时为 1，移除订单时为 -1
         * @param remainingQuantity 要加入或移除的订单剩余数量步数
         */
        private void update(OrderRuntime order, int direction, long remainingQuantity) {
            // 加入时为正剩余量，移除时精确取负得到的数量变化。
            long quantityDelta = direction > 0 ? remainingQuantity : Math.negateExact(remainingQuantity);
            // 当前订单对应的只减仓或普通方向分组的旧数量。
            long currentQuantity = order.reduceOnly()
                    ? reduceOnly(order.side())
                    : pending(order.positionSide(), order.side());
            // 加入本次变化后的分组数量，写入前必须非负。
            long nextQuantity = Math.addExact(currentQuantity, quantityDelta);
            // 该持仓腿及保证金模式更新后的订单条数，写入前必须非负。
            int nextMargin = Math.addExact(
                    marginMode(order.positionSide(), order.marginMode()), direction);
            // 当前用户合约更新后的总订单条数，归零可释放汇总单元。
            int nextOrders = Math.addExact(orders, direction);
            if (nextQuantity < 0 || nextMargin < 0 || nextOrders < 0) {
                throw new IllegalStateException("admission order index underflow");
            }
            if (order.reduceOnly()) assignReduceOnly(order.side(), nextQuantity);
            else assignPending(order.positionSide(), order.side(), nextQuantity);
            assignMarginMode(order.positionSide(), order.marginMode(), nextMargin);
            orders = nextOrders;
        }

        /**
         * 读取非只减仓订单在指定持仓腿和买卖方向的剩余数量合计。
         * @param positionSide 要选择的净仓、多仓或空仓持仓腿
         * @param side 要选择的买入或卖出方向
         */
        long pending(CorePositionSide positionSide, CoreOrderSide side) {
            return switch (positionSide) {
                case NET -> side == CoreOrderSide.BUY ? netBuy : netSell;
                case LONG -> side == CoreOrderSide.BUY ? longBuy : longSell;
                case SHORT -> side == CoreOrderSide.BUY ? shortBuy : shortSell;
            };
        }

        /**
         * 读取只减仓订单在指定买卖方向的剩余数量合计，不再按持仓腿细分。
         * @param side 要选择的买入或卖出方向
         */
        long reduceOnly(CoreOrderSide side) {
            return side == CoreOrderSide.BUY ? reduceBuy : reduceSell;
        }

        /**
         * 读取指定持仓腿和保证金模式的活跃订单条数，包含普通单及只减仓单。
         * @param positionSide 要选择的净仓、多仓或空仓持仓腿
         * @param marginMode 要选择的全仓或逐仓保证金模式
         */
        int marginMode(CorePositionSide positionSide, CoreMarginMode marginMode) {
            return switch (positionSide) {
                case NET -> marginMode == CoreMarginMode.CROSS ? netCross : netIsolated;
                case LONG -> marginMode == CoreMarginMode.CROSS ? longCross : longIsolated;
                case SHORT -> marginMode == CoreMarginMode.CROSS ? shortCross : shortIsolated;
            };
        }

        /**
         * 用活跃订单总条数判断汇总单元是否空闲。
         */
        boolean empty() { return orders == 0; }

        /**
         * 把非只减仓剩余量写入对应持仓腿和方向的统计格。
         * @param positionSide 要选择的净仓、多仓或空仓持仓腿
         * @param side 要选择的买入或卖出方向
         * @param quantity 该分组更新后的剩余数量合计
         */
        private void assignPending(CorePositionSide positionSide, CoreOrderSide side, long quantity) {
            switch (positionSide) {
                case NET -> { if (side == CoreOrderSide.BUY) netBuy = quantity; else netSell = quantity; }
                case LONG -> { if (side == CoreOrderSide.BUY) longBuy = quantity; else longSell = quantity; }
                case SHORT -> { if (side == CoreOrderSide.BUY) shortBuy = quantity; else shortSell = quantity; }
            }
        }

        /**
         * 把只减仓剩余量写入买或卖方向的统计格。
         * @param side 要选择的买入或卖出方向
         * @param quantity 该分组更新后的剩余数量合计
         */
        private void assignReduceOnly(CoreOrderSide side, long quantity) {
            if (side == CoreOrderSide.BUY) reduceBuy = quantity;
            else reduceSell = quantity;
        }

        /**
         * 写入指定持仓腿及保证金模式的活跃订单条数。
         * @param positionSide 要选择的净仓、多仓或空仓持仓腿
         * @param marginMode 要选择的全仓或逐仓保证金模式
         * @param count 此持仓腿与保证金模式更新后的活跃订单条数
         */
        private void assignMarginMode(CorePositionSide positionSide, CoreMarginMode marginMode, int count) {
            switch (positionSide) {
                case NET -> { if (marginMode == CoreMarginMode.CROSS) netCross = count; else netIsolated = count; }
                case LONG -> { if (marginMode == CoreMarginMode.CROSS) longCross = count; else longIsolated = count; }
                case SHORT -> { if (marginMode == CoreMarginMode.CROSS) shortCross = count; else shortIsolated = count; }
            }
        }
    }

    /**
     * 要求 Core 水位严格前进后写入已应用序号，并精确增加 Lane 修订号；提交水位由后续完成边界推进。
     * @param coreSequence 本次推进或核对的 Core 命令序号
     */
    public void applied(long coreSequence) {
        assertOwner();
        requireApplySequence(coreSequence);
        appliedSequence = coreSequence;
        revision = Math.incrementExact(revision);
    }

    /**
     * 要求新 Core 序号大于该 Lane 已应用水位，拒绝重复或倒序应用。
     * @param coreSequence 本次推进或核对的 Core 命令序号
     */
    void requireApplySequence(long coreSequence) {
        assertOwner();
        if (coreSequence <= appliedSequence) {
            throw new IllegalStateException("account lane apply is out of order: lane=" + laneId
                    + ", incoming=" + coreSequence + ", applied=" + appliedSequence
                    + ", committed=" + committedSequence);
        }
    }

    /**
     * 检查提交范围后推进提交水位，允许重复确认当前提交序号。
     * @param coreSequence 本次推进或核对的 Core 命令序号
     */
    public void committed(long coreSequence) {
        assertOwner();
        requireCommit(coreSequence);
        committedSequence = coreSequence;
    }

    /**
     * 要求提交水位不倒退且不超过已应用水位。
     * @param coreSequence 本次推进或核对的 Core 命令序号
     */
    void requireCommit(long coreSequence) {
        assertOwner();
        if (coreSequence < committedSequence || coreSequence > appliedSequence) {
            throw new IllegalStateException("account lane commit is out of order");
        }
    }

    /**
     * 确认没有未提交工作且读取水位不倒退，再把应用和提交水位一起推进到读取边界。
     * @param coreSequence 本次推进或核对的 Core 命令序号
     */
    public void readFence(long coreSequence) {
        assertOwner();
        requireReadFence(coreSequence);
        advanceReadFence(coreSequence);
    }

    /**
     * 检查当前应用与提交水位一致，并且请求的读取边界不早于当前提交水位。
     * @param coreSequence 本次推进或核对的 Core 命令序号
     */
    public void requireReadFence(long coreSequence) {
        assertOwner();
        if (coreSequence < committedSequence || appliedSequence != committedSequence) {
            throw new IllegalStateException("account lane read fence crossed uncommitted work");
        }
    }

    /**
     * 直接把应用与提交水位设为读取边界，不增加修订号；调用方应先检查边界条件。
     * @param coreSequence 本次推进或核对的 Core 命令序号
     */
    public void advanceReadFence(long coreSequence) {
        assertOwner();
        appliedSequence = coreSequence;
        committedSequence = coreSequence;
    }

    /**
     * 恢复 Lane 进度、已存哈希和用户成员，并清空待完成预留索引；不在此恢复订单、余额或持仓实体表。
     * @param revision 快照保存的 Lane 修订号
     * @param appliedSequence 快照保存的已应用 Core 水位
     * @param committedSequence 快照保存的已提交 Core 水位
     * @param localStateHash 快照保存的非零业务状态哈希
     * @param localFundsHash 快照保存的非零余额哈希
     * @param restoredUsers 快照恢复出的用户成员集合
     */
    public void restore(long revision, long appliedSequence, long committedSequence,
                 long localStateHash, long localFundsHash, LongHashSet restoredUsers) {
        assertOwner();
        if (revision < 0 || appliedSequence < committedSequence || committedSequence < 0
                || localStateHash == 0 || localFundsHash == 0 || restoredUsers == null) {
            throw new IllegalArgumentException("invalid account lane snapshot");
        }
        this.revision = revision;
        this.appliedSequence = appliedSequence;
        this.committedSequence = committedSequence;
        this.localStateHash = localStateHash;
        this.localFundsHash = localFundsHash;
        userIds.clear();
        userIds.addAll(restoredUsers);
        pendingReservationSequences.clear();
        pendingReservationCountsByUser.clear();
        pendingReservedUnitsByAsset.clear();
        totalPendingReservations = 0;
    }

    /**
     * 核对所属线程后复制用户成员集合，避免调用方直接修改原成员表。
     */
    LongHashSet userIdsSnapshot() {
        assertOwner();
        return new LongHashSet(userIds);
    }

    /**
     * 确认没有未完成提交或预留后对齐水位，按用户编号排序，构造仅含 Lane 元数据与用户成员的快照记录。
     * @param fenceSequence 完整恢复或快照流程选定的已完成命令边界
     */
    public AccountLaneSnapshot snapshot(long fenceSequence) {
        assertOwner();
        requireSnapshot(fenceSequence);
        appliedSequence = fenceSequence;
        committedSequence = fenceSequence;
        // 快照独立保存的升序用户编号列表，不携带完整账户实体。
        java.util.List<Long> users = new java.util.ArrayList<>(userIds.size());
        userIds.forEach(users::add);
        users.sort(Long::compare);
        return new AccountLaneSnapshot(laneId, revision, appliedSequence, committedSequence,
                localStateHash, localFundsHash, users);
    }

    /**
     * 要求待完成预留为空、应用水位等于提交水位且快照边界不倒退，避免未完成业务跨越快照。
     * @param fenceSequence 完整恢复或快照流程选定的已完成命令边界
     */
    public void requireSnapshot(long fenceSequence) {
        assertOwner();
        if (!pendingReservationSequences.isEmpty()) {
            throw new IllegalStateException("pending reservation cannot cross snapshot fence");
        }
        if (fenceSequence < committedSequence || appliedSequence != committedSequence) {
            throw new IllegalStateException("account lane snapshot crossed an incomplete commit");
        }
    }

    /**
     * 检查快照属于本 Lane，把用户列表转换为成员集合后恢复元数据；业务实体由完整恢复流程另行加载。
     * @param snapshot 属于本 Lane 的元数据与用户成员快照
     */
    public void restore(AccountLaneSnapshot snapshot) {
        assertOwner();
        if (snapshot == null || snapshot.laneId() != laneId) {
            throw new IllegalArgumentException("account lane snapshot route mismatch");
        }
        // 由元数据快照用户列表构造的成员集合。
        LongHashSet restoredUsers = new LongHashSet();
        snapshot.userIds().forEach(restoredUsers::add);
        restore(snapshot.revision(), snapshot.appliedSequence(), snapshot.committedSequence(),
                snapshot.localStateHash(), snapshot.localFundsHash(), restoredUsers);
    }

    /**
     * 保存结算次数、累计纳秒与最大纳秒的值记录；本类当前 writeMetrics 只输出操作次数，并不读取此记录。
     * @param operations 结算操作次数
     * @param totalLatencyNanos 累计结算耗时，单位为纳秒
     * @param maxLatencyNanos 观察到的最大结算耗时，单位为纳秒
     */
    record MatcherSettlementMetrics(long operations, long totalLatencyNanos, long maxLatencyNanos) {
    }

    /**
     * 把一个 long 从低字节到高字节混入哈希，乘法按 long 自然溢出，不用于金额运算。
     * @param hash 混入当前值之前的哈希累积值
     * @param value 按八个字节混入的 long 值
     */
    private static long mix(long hash, long value) {
        // 逐字节混入 long 值的哈希累积值，溢出沿用 long 哈希语义。
        long mixed = hash;
        for (/* 当前取出的字节在 long 中的位偏移，从零逐次增加八。 */ int shift = 0; shift < Long.SIZE; shift += Byte.SIZE) {
            mixed ^= (value >>> shift) & 0xff;
            mixed *= 0x100000001b3L;
        }
        return mixed;
    }

    /**
     * 汇总用户、订单、预留、持仓及冷业务记录的无序哈希；各表遍历顺序不影响加和结果，不在这里混入余额表。
     */
    private long computeStateHash() {
        // 已混入 Lane 编号及各项分组结果的哈希累积值。
        long hash = mix(0xcbf29ce484222325L, laneId);
        // 各用户子哈希的无序加和，表遍历先后不影响结果。
        long userSum = 0;
        // 当前用户编号集合或余额表用户键的原语迭代器。
        var userIterator = userIds.longIterator();
        while (userIterator.hasNext()) {
            // 本次加入哈希的用户编号。
            long userId = userIterator.next();
            userSum += mix(mix(0x9e3779b97f4a7c15L, userId), mixValue(0, users.get(userId)));
        }
        hash = mix(hash, userSum);
        hash = mix(hash, mixMapUnordered(orders));
        hash = mix(hash, mixMapUnordered(reservations));
        hash = mix(hash, mixMapUnordered(positions));
        hash = mix(hash, mixPrimitiveMapUnordered(cold.liquidations));
        hash = mix(hash, mixPrimitiveMapUnordered(cold.riskSnapshots));
        // 各杠杆业务键与设置值的无序哈希加和。
        long leverageSum = 0;
        for (Map.Entry<CoreLeverageKey, Long> /* 当前需要混入业务键和杠杆值的一项设置。 */ entry : cold.leverages.entrySet()) {
            leverageSum += mix(mixValue(0, entry.getKey()), entry.getValue());
        }
        hash = mix(hash, leverageSum);
        hash = mix(hash, mixPrimitiveMapUnordered(cold.algoOrders));
        hash = mix(hash, mixPrimitiveMapUnordered(cold.triggerOrders));
        return hash == 0 ? 1 : hash;
    }

    /**
     * 按每项的键和值计算哈希再相加，避免 primitive 表的内部遍历顺序影响结果。
     * @param <T> 表中业务值的类型，混合时使用其值哈希
     * @param values 要计算无序键值哈希的原语键表
     */
    private <T> long mixPrimitiveMapUnordered(LongObjectHashMap<T> values) {
        // 各原语键值项哈希的无序累积和。
        long sum = 0;
        // 当前原语键对象表的键迭代器。
        var iterator = values.keySet().longIterator();
        while (iterator.hasNext()) {
            // 当前加入无序项哈希的原语 long 键。
            long key = iterator.next();
            sum += mix(mix(0x9e3779b97f4a7c15L, key), mixValue(0, values.get(key)));
        }
        return sum;
    }

    /**
     * 按用户与资产混入可用和冻结金额，构造不依赖表遍历顺序的余额哈希；不扫描订单或单独解释冻结来源。
     */
    private long computeFundsHash() {
        // 已混入 Lane 编号及各项分组结果的哈希累积值。
        long hash = mix(0xcbf29ce484222325L, laneId);
        // 各用户子哈希的无序加和，表遍历先后不影响结果。
        long userSum = 0;
        // 当前用户编号集合或余额表用户键的原语迭代器。
        var userIterator = balances.keySet().longIterator();
        while (userIterator.hasNext()) {
            // 本次加入哈希的用户编号。
            long userId = userIterator.next();
            // 此用户各资产余额子哈希的无序加和。
            long assetSum = 0;
            // 当前用户权威资产余额表，只在所属线程重建哈希时读取。
            IntObjectHashMap<BalanceRuntime> userBalances = balances.get(userId);
            if (userBalances != null) {
                // 当前用户资产键的原语迭代器。
                var assetIterator = userBalances.keySet().intIterator();
                while (assetIterator.hasNext()) {
                    // 当前混入余额哈希的资产整数编号。
                    int assetId = assetIterator.next();
                    // 当前资产的权威可用与冻结余额单元。
                    BalanceRuntime balance = userBalances.get(assetId);
                    // 混入资产编号、可用金额和冻结金额后的余额项哈希。
                    long itemHash = mix(mix(mix(0x9e3779b97f4a7c15L, assetId), balance.availableUnits()), balance.lockedUnits());
                    assetSum += itemHash;
                }
            }
            userSum += mix(mix(0x9e3779b97f4a7c15L, userId), assetSum);
        }
        hash = mix(hash, userSum);
        return hash == 0 ? 1 : hash;
    }

    /**
     * 由所属线程完整重建业务状态和余额哈希缓存；这是显式遍历入口，读取哈希本身不触发重建。
     */
    public void rebuildLocalHashes() {
        assertOwner();
        localStateHash = computeStateHash();
        localFundsHash = computeFundsHash();
    }

    /**
     * 遍历 Agrona long 键对象表，按每项键值哈希加和，保持与表内顺序无关。
     * @param values 要计算无序键值哈希的原语键表
     */
    private long mixMapUnordered(Long2ObjectHashMap<?> values) {
        // 各原语键值项哈希的无序累积和。
        long sum = 0;
        // 当前原语键对象表的键迭代器。
        var iterator = values.keySet().iterator();
        while (iterator.hasNext()) {
            // 当前加入无序项哈希的原语 long 键。
            long key = iterator.nextLong();
            sum += mix(mix(0x9e3779b97f4a7c15L, key), mixValue(0, values.get(key)));
        }
        return sum;
    }

    /**
     * 把 Eclipse long 键对象表交给同一无序加和算法处理。
     * @param <T> 表中业务值的类型，混合时使用其值哈希
     * @param values 要计算无序键值哈希的原语键表
     */
    private <T> long mixMapUnordered(LongObjectHashMap<T> values) {
        return mixPrimitiveMapUnordered(values);
    }

    /**
     * 空值混入零，文本逐字符混入长度和字符字节，其他对象混入无符号扩展的 hashCode；这是校验哈希而非密码学摘要。
     * @param hash 混入当前值之前的哈希累积值
     * @param value 要混入的文本或业务对象，允许 null
     */
    private static long mixValue(long hash, Object value) {
        if (value == null) return mix(hash, 0);
        if (value instanceof CharSequence /* 按字符内容参与哈希的文本值，不能只依赖对象身份。 */ seq) {
            // 先混入文本长度，再逐字符更新的哈希值。
            long mixed = mix(hash, seq.length());
            for (/* 当前字符在文本中的位置。 */ int index = 0; index < seq.length(); index++) {
                // 当前 UTF-16 字符，ASCII 混低字节，非 ASCII 还混高字节。
                char character = seq.charAt(index);
                mixed ^= (character & 0xff);
                if (character > 0x7f) {
                    mixed ^= (character >>> 8);
                }
                mixed *= 0x100000001b3L;
            }
            return mixed;
        }
        return mix(hash, Integer.toUnsignedLong(value.hashCode()));
    }

    /**
     * 按订单编号读取预留权威值，缺失返回 null。
     * @param key 预留对应的订单编号
     */
    public ReservationRuntime reservation(long key) { return reservations.get(key); }
    /**
     * 只移除并返回预留值；待完成标记、用户成员和余额须由调用方配套处理。
     * @param key 预留对应的订单编号
     */
    public ReservationRuntime removeReservationValue(long key) { return reservations.remove(key); }
    /**
     * 按用户编号读取 Lane 权威用户值，缺失返回 null。
     * @param key 用户编号
     */
    public UserRuntime user(long key) { return users.get(key); }
    /**
     * 直接写入并返回旧用户值，不自动登记用户成员或复制成 Lane 权威值。
     * @param key 用户编号
     * @param value 要存入的用户值
     */
    public UserRuntime storeUser(long key, UserRuntime value) { return users.put(key, value); }
    /**
     * 按订单编号读取权威记录，可能返回仍保留的终态订单。
     * @param key 订单编号
     */
    public OrderRuntime order(long key) { return orders.get(key); }
    /**
     * 遍历 users 权威表的用户键，区别于独立的用户成员集合；回调不得破坏正在遍历的容器。
     * @param consumer 在当前 Lane 访问边界内消费遍历项的回调，不应修改正在遍历的容器
     */
    public void forEachUserId(org.eclipse.collections.api.block.procedure.primitive.LongProcedure consumer) { users.forEachKey(consumer); }
    /**
     * 按运行时持仓键读取持仓记录，不额外判断仓位是否为零。
     * @param key 身份字典计算的持仓键
     */
    public com.surprising.aeron.service.position.PositionRuntime position(long key) { return positions.get(key); }
    /**
     * 按业务杠杆键读取设置，缺失返回 null。
     * @param key 通常为 CoreLeverageKey 的查找键
     */
    public Long leverage(Object key) { return cold.leverages.get(key); }
    /**
     * 按强平编号读取当前 Lane 的强平记录。
     * @param key 强平编号
     */
    public com.surprising.aeron.service.risk.LiquidationRuntime liquidation(long key) { return cold.liquidations.get(key); }
    /**
     * 在本 Lane 强平表上判断是否有记录满足条件，调用方须保持有效读取边界。
     * @param predicate 在当前读取边界内判断业务记录是否符合要求的条件
     */
    public boolean anyLiquidation(org.eclipse.collections.api.block.predicate.Predicate<? super com.surprising.aeron.service.risk.LiquidationRuntime> predicate) { return cold.liquidations.anySatisfy(predicate); }
    /**
     * 返回直接关联强平表的值视图，不复制容器，不能跨越 Lane 后续写入长期遍历。
     */
    public Iterable<com.surprising.aeron.service.risk.LiquidationRuntime> liquidationValuesForRead() { return cold.liquidations.values(); }
    /**
     * 按持仓键读取风险估值记录。
     * @param key 对应持仓的运行时键
     */
    public com.surprising.aeron.service.risk.RiskSnapshotRuntime riskSnapshot(long key) { return cold.riskSnapshots.get(key); }
    /**
     * 返回持仓记录条数，包括表中保留的零仓位记录；不是净持仓数量或未平仓量。
     */
    public int positionCount() { return positions.size(); }
    /**
     * 返回直接关联持仓表的值视图，调用方在读取保护范围内消费。
     */
    public Iterable<com.surprising.aeron.service.position.PositionRuntime> positionValuesForRead() { return positions.values(); }
    /**
     * 直接写入并返回旧杠杆设置，用户键索引由调用方同步维护。
     * @param key 用户、合约和保证金模式组成的杠杆键
     * @param value 要保存的杠杆值
     */
    public Long storeLeverage(CoreLeverageKey key, Long value) { return cold.leverages.put(key, value); }
    /**
     * 按算法单编号读取权威记录。
     * @param key 算法单编号
     */
    public com.surprising.aeron.model.CoreAlgoOrderState algoOrder(long key) { return cold.algoOrders.get(key); }
    /**
     * 在算法单权威表上判断是否有值满足条件，不构造额外订单列表。
     * @param predicate 在当前读取边界内判断业务记录是否符合要求的条件
     */
    public boolean anyAlgoOrder(org.eclipse.collections.api.block.predicate.Predicate<? super com.surprising.aeron.model.CoreAlgoOrderState> predicate) { return cold.algoOrders.anySatisfy(predicate); }
    /**
     * 直接写入并返回旧算法单值，不在这里修改 Owner 派生调度索引。
     * @param key 算法单编号
     * @param value 要保存的算法单记录
     */
    public com.surprising.aeron.model.CoreAlgoOrderState storeAlgoOrder(long key, com.surprising.aeron.model.CoreAlgoOrderState value) { return cold.algoOrders.put(key, value); }
    /**
     * 只移除并返回算法单值，调度索引清理由外层负责。
     * @param key 算法单编号
     */
    public com.surprising.aeron.model.CoreAlgoOrderState removeAlgoOrderValue(long key) { return cold.algoOrders.remove(key); }
    /**
     * 按触发单编号读取权威记录。
     * @param key 触发单编号
     */
    public com.surprising.aeron.model.CoreTriggerOrderState triggerOrder(long key) { return cold.triggerOrders.get(key); }
    /**
     * 遍历本 Lane 算法单编号与记录，沿用当前容器顺序，不保证业务排序。
     * @param consumer 在当前 Lane 访问边界内消费遍历项的回调，不应修改正在遍历的容器
     */
    public void forEachAlgoOrder(org.eclipse.collections.api.block.procedure.primitive.LongObjectProcedure<? super com.surprising.aeron.model.CoreAlgoOrderState> consumer) { cold.algoOrders.forEachKeyValue(consumer); }
    /**
     * 遍历本 Lane 触发单编号与记录；这是现有表的直接遍历，不提供跨线程快照。
     * @param consumer 在当前 Lane 访问边界内消费遍历项的回调，不应修改正在遍历的容器
     */
    public void forEachTriggerOrder(org.eclipse.collections.api.block.procedure.primitive.LongObjectProcedure<? super com.surprising.aeron.model.CoreTriggerOrderState> consumer) { cold.triggerOrders.forEachKeyValue(consumer); }
    /**
     * 按划转编号读取本 Lane 的待完成划转状态。
     * @param key 划转编号
     */
    public com.surprising.aeron.service.account.TransferRuntime pendingTransfer(long key) { return cold.pendingTransfers.get(key); }
    /**
     * 遍历本 Lane 待完成划转编号与状态，调用方须持有有效读取或写入作用域。
     * @param consumer 在当前 Lane 访问边界内消费遍历项的回调，不应修改正在遍历的容器
     */
    public void forEachPendingTransfer(org.eclipse.collections.api.block.procedure.primitive.LongObjectProcedure<? super com.surprising.aeron.service.account.TransferRuntime> consumer) { cold.pendingTransfers.forEachKeyValue(consumer); }
    /**
     * 仅清空待完成划转表，不在此冲正余额或系统账本。
     */
    public void clearPendingTransferValues() { cold.pendingTransfers.clear(); }
    /**
     * 直接写入并返回旧划转状态，不在此处理余额变化。
     * @param key 划转编号
     * @param value 要保存的待完成划转记录
     */
    public com.surprising.aeron.service.account.TransferRuntime storePendingTransfer(long key, com.surprising.aeron.service.account.TransferRuntime value) { return cold.pendingTransfers.put(key, value); }
    /**
     * 检查待完成划转表是否包含编号。
     * @param key 划转编号
     */
    public boolean hasPendingTransfer(long key) { return cold.pendingTransfers.containsKey(key); }
    /**
     * 只移除并返回待完成划转状态，不冲正余额。
     * @param key 划转编号
     */
    public com.surprising.aeron.service.account.TransferRuntime removePendingTransferValue(long key) { return cold.pendingTransfers.remove(key); }
    /**
     * 仅清空杠杆值表，用户到杠杆键的索引由调用方配套处理。
     */
    public void clearLeverageValues() { cold.leverages.clear(); }
    /**
     * 仅清空算法单权威值表，派生索引由外层恢复或清理流程同步处理。
     */
    public void clearAlgoOrderValues() { cold.algoOrders.clear(); }
    /**
     * 仅清空触发单权威表，不隐式清空用户触发编号索引。
     */
    public void clearTriggerOrderValues() { cold.triggerOrders.clear(); }
    /**
     * 只移除并返回用户权威值，不清理成员、余额、订单或身份索引。
     * @param key 用户编号
     */
    public UserRuntime removeUserValue(long key) { return users.remove(key); }
    /**
     * 直接写入并返回旧预留值；待完成金额及用户成员索引由外层同步维护。
     * @param key 预留对应的订单编号
     * @param value 要保存的预留记录
     */
    public ReservationRuntime storeReservation(long key, ReservationRuntime value) { return reservations.put(key, value); }
    /**
     * 直接写入并返回旧持仓值，不自动维护用户或合约持仓索引。
     * @param key 运行时持仓键
     * @param value 要保存的持仓记录
     */
    public com.surprising.aeron.service.position.PositionRuntime storePosition(long key, com.surprising.aeron.service.position.PositionRuntime value) { return positions.put(key, value); }
    /**
     * 直接写入并返回旧强平值，活跃强平路由索引由调用方维护。
     * @param key 强平编号
     * @param value 要保存的强平记录
     */
    public com.surprising.aeron.service.risk.LiquidationRuntime storeLiquidation(long key, com.surprising.aeron.service.risk.LiquidationRuntime value) { return cold.liquidations.put(key, value); }
    /**
     * 直接写入并返回旧风险估值，不自行重算账户权益。
     * @param key 对应持仓的运行时键
     * @param value 要保存的风险估值记录
     */
    public com.surprising.aeron.service.risk.RiskSnapshotRuntime storeRiskSnapshot(long key, com.surprising.aeron.service.risk.RiskSnapshotRuntime value) { return cold.riskSnapshots.put(key, value); }
    /**
     * 只移除并返回强平记录，不自动释放活跃强平索引。
     * @param key 强平编号
     */
    public com.surprising.aeron.service.risk.LiquidationRuntime removeLiquidationValue(long key) { return cold.liquidations.remove(key); }
    /**
     * 只移除并返回持仓值，不自动移除用户或合约成员索引。
     * @param key 运行时持仓键
     */
    public com.surprising.aeron.service.position.PositionRuntime removePositionValue(long key) { return positions.remove(key); }
    /**
     * 只移除并返回风险估值记录。
     * @param key 对应持仓的运行时键
     */
    public com.surprising.aeron.service.risk.RiskSnapshotRuntime removeRiskSnapshotValue(long key) { return cold.riskSnapshots.remove(key); }

    /**
     * 以原语订单编号和记录遍历订单表，避免把键转换为 Long；不自动排序。
     * @param consumer 在当前 Lane 访问边界内消费遍历项的回调，不应修改正在遍历的容器
     */
    public void forEachOrder(org.agrona.collections.LongObjConsumer<OrderRuntime> consumer) { orders.longForEach(consumer); }
    /**
     * 以原语订单编号和记录遍历预留表，不生成预留快照列表。
     * @param consumer 在当前 Lane 访问边界内消费遍历项的回调，不应修改正在遍历的容器
     */
    public void forEachReservation(org.agrona.collections.LongObjConsumer<ReservationRuntime> consumer) { reservations.longForEach(consumer); }
    /**
     * 检查订单权威表是否包含编号，不等同于检查订单是否仍活跃。
     * @param key 要检查是否仍在权威表中的订单编号
     */
    public boolean hasOrder(long key) { return orders.containsKey(key); }
    /**
     * 用接收 Long 键的 Java 回调遍历订单项，适用于现有冷边界调用；需要原语键时使用 forEachOrder。
     * @param consumer 在当前 Lane 访问边界内消费遍历项的回调，不应修改正在遍历的容器
     */
    public void forEachOrderEntry(java.util.function.BiConsumer<? super Long, ? super OrderRuntime> consumer) { orders.forEach(consumer); }
    /**
     * 直接遍历持仓值，不复制容器，也不额外过滤零仓位。
     * @param consumer 在当前 Lane 访问边界内消费遍历项的回调，不应修改正在遍历的容器
     */
    public void forEachPositionValue(org.eclipse.collections.api.block.procedure.Procedure<? super com.surprising.aeron.service.position.PositionRuntime> consumer) { positions.forEachValue(consumer); }
    /**
     * 直接遍历触发单值，调用方应在有效读取边界内完成消费。
     * @param consumer 在当前 Lane 访问边界内消费遍历项的回调，不应修改正在遍历的容器
     */
    public void forEachTriggerOrderValue(org.eclipse.collections.api.block.procedure.Procedure<? super com.surprising.aeron.model.CoreTriggerOrderState> consumer) { cold.triggerOrders.forEachValue(consumer); }
    /**
     * 检查杠杆值表是否为空，不据此推断用户杠杆键索引也已清空。
     */
    public boolean leverageValuesEmpty() { return cold.leverages.isEmpty(); }

    /**
     * 安装用户资产到余额单元的现有表并返回旧表，不复制余额或登记用户。
     * @param key 用户编号
     * @param value 资产编号到余额权威单元的表
     */
    public IntObjectHashMap<BalanceRuntime> storeUserBalances(long key, IntObjectHashMap<BalanceRuntime> value) { return balances.put(key, value); }
    /**
     * 取得用户实际使用的资产余额表，返回值仍关联权威状态。
     * @param key 用户编号
     */
    public IntObjectHashMap<BalanceRuntime> userBalances(long key) { return balances.get(key); }
    /**
     * 移除并返回用户资产余额表，不在此对余额作账务冲正。
     * @param key 用户编号
     */
    public IntObjectHashMap<BalanceRuntime> removeUserBalances(long key) { return balances.remove(key); }
    /**
     * 返回用户实际使用的预留编号集合，不创建集合副本。
     * @param key 用户编号
     */
    public org.agrona.collections.LongHashSet reservationIds(long key) { return reservationIdsByUser.get(key); }
    /**
     * 移除并返回该用户的预留成员集合，不删除预留值。
     * @param key 用户编号
     */
    public org.agrona.collections.LongHashSet removeReservationIds(long key) { return reservationIdsByUser.remove(key); }
    /**
     * 返回用户实际使用的持仓键集合。
     * @param key 用户编号
     */
    public LongHashSet positionKeys(long key) { return positionKeysByUser.get(key); }
    /**
     * 移除并返回用户持仓键集合，不删除持仓值。
     * @param key 用户编号
     */
    public LongHashSet removePositionKeys(long key) { return positionKeysByUser.remove(key); }
    /**
     * 返回用户活跃订单编号集合，供所属 Lane 直接遍历。
     * @param key 用户编号
     */
    public org.agrona.collections.LongHashSet activeOrderIds(long key) { return activeOrderIdsByUser.get(key); }
    /**
     * 移除并返回用户活跃订单成员集合，不删除订单值。
     * @param key 用户编号
     */
    public org.agrona.collections.LongHashSet removeActiveOrderIds(long key) { return activeOrderIdsByUser.remove(key); }
    /**
     * 安装并返回旧的用户杠杆键集合，不修改杠杆值。
     * @param key 用户编号
     * @param value 该用户对应的业务杠杆键集合
     */
    public java.util.HashSet<CoreLeverageKey> storeLeverageKeys(long key, java.util.HashSet<CoreLeverageKey> value) { return cold.leverageKeysByUser.put(key, value); }
    /**
     * 取得用户实际使用的杠杆键集合。
     * @param key 用户编号
     */
    public java.util.HashSet<CoreLeverageKey> leverageKeys(long key) { return cold.leverageKeysByUser.get(key); }
    /**
     * 清空用户到杠杆键的成员索引，不同时清空杠杆值表。
     */
    public void clearLeverageKeys() { cold.leverageKeysByUser.clear(); }
    /**
     * 移除并返回用户杠杆键集合，不同时移除杠杆设置。
     * @param key 用户编号
     */
    public java.util.HashSet<CoreLeverageKey> removeLeverageKeys(long key) { return cold.leverageKeysByUser.remove(key); }
    /**
     * 安装并返回旧的合约到用户持仓键索引分组。
     * @param key 稠密合约编号
     * @param value 用户编号到有序持仓键数组的分组
     */
    public LongObjectHashMap<LongArrayList> storeSymbolPositionKeys(int key, LongObjectHashMap<LongArrayList> value) { return positionKeysBySymbolAndUser.put(key, value); }
    /**
     * 取得某合约的用户持仓键分组，不复制内部数组。
     * @param key 稠密合约编号
     */
    public LongObjectHashMap<LongArrayList> symbolPositionKeys(int key) { return positionKeysBySymbolAndUser.get(key); }
    /**
     * 移除并返回整个合约持仓索引分组，不删除持仓值。
     * @param key 稠密合约编号
     */
    public LongObjectHashMap<LongArrayList> removeSymbolPositionKeys(int key) { return positionKeysBySymbolAndUser.remove(key); }
    /**
     * 安装并返回旧的用户活跃强平索引，实体强平值不在此写入。
     * @param key 用户编号
     * @param value 按合约及持仓腿键分组的活跃强平编号表
     */
    public IntObjectHashMap<LongObjectHashMap<Long>> storeActiveLiquidations(long key, IntObjectHashMap<LongObjectHashMap<Long>> value) { return cold.activeLiquidationIndex.put(key, value); }
    /**
     * 取得用户实际使用的活跃强平分组表。
     * @param key 用户编号
     */
    public IntObjectHashMap<LongObjectHashMap<Long>> activeLiquidations(long key) { return cold.activeLiquidationIndex.get(key); }
    /**
     * 移除并返回用户活跃强平索引，不删除强平实体记录。
     * @param key 用户编号
     */
    public IntObjectHashMap<LongObjectHashMap<Long>> removeActiveLiquidations(long key) { return cold.activeLiquidationIndex.remove(key); }
    /**
     * 取得用户触发单编号集合，不复制成员。
     * @param key 用户编号
     */
    public LongHashSet triggerIds(long key) { return cold.triggerIdsByUser.get(key); }
    /**
     * 清空用户到触发编号的索引，不同时删除触发单权威值。
     */
    public void clearTriggerIds() { cold.triggerIdsByUser.clear(); }
    /**
     * 安装并返回旧的用户客户键到订单编号映射。
     * @param key 用户编号
     * @param value 客户身份键到订单编号的表
     */
    public Long2LongHashMap storeUserClientOrders(long key, Long2LongHashMap value) { return clientOrderIndex.put(key, value); }
    /**
     * 取得用户实际使用的客户身份键到订单编号映射。
     * @param key 用户编号
     */
    public Long2LongHashMap userClientOrders(long key) { return clientOrderIndex.get(key); }
    /**
     * 移除并返回用户客户号映射，不释放身份字典引用或订单反向索引。
     * @param key 用户编号
     */
    public Long2LongHashMap removeUserClientOrders(long key) { return clientOrderIndex.remove(key); }
    /**
     * 给用户登记一个持仓键，首次出现时创建集合；本入口不写入持仓权威表。
     * @param userId 本次处理的用户编号，路由已由外层选择到本 Lane
     * @param key 要加入该用户成员集合的运行时持仓键
     */
    public void indexPositionKey(long userId, long key) {
        // 此用户实际使用的持仓成员键集合，添加时可创建，移除后为空则删除用户项。
        LongHashSet keys = positionKeysByUser.get(userId);
        if (keys == null) { keys = new LongHashSet(16); positionKeysByUser.put(userId, keys); }
        keys.add(key);
    }
    /**
     * 移除用户持仓键，集合为空时删除用户索引项；持仓值的删除由外层完成。
     * @param userId 本次处理的用户编号，路由已由外层选择到本 Lane
     * @param key 要从该用户成员集合移除的运行时持仓键
     */
    public void unindexPositionKey(long userId, long key) {
        // 此用户实际使用的持仓成员键集合，添加时可创建，移除后为空则删除用户项。
        LongHashSet keys = positionKeysByUser.get(userId);
        if (keys == null) return;
        keys.remove(key);
        if (keys.isEmpty()) positionKeysByUser.remove(userId);
    }

    /**
     * 调用方已持有查询屏障时，逐用户转换为可发布值并复制用户表；不要求查询线程成为 Lane 写入者。
     */
    public LongObjectHashMap<UserRuntime> usersSnapshot() {
        // 用于导出的独立用户表，每项用户值转换为可发布形式。
        LongObjectHashMap<UserRuntime> copy = new LongObjectHashMap<>(users.size());
        users.forEachKeyValue((/* 本次加入哈希的用户编号。 */ userId, /* 用户表遍历到的权威用户值，放入副本前先调用 publicationValue。 */ user) -> copy.put(userId, user.publicationValue()));
        return copy;
    }

    /**
     * 交给运行时复制全部用户及资产余额，返回与权威余额单元分离的导出数据。
     */
    public LongObjectHashMap<IntObjectHashMap<BalanceRuntime>> balancesSnapshot() {
        return TradingAccountRuntime.copyAllBalances(balances);
    }
    /**
     * 复制用户客户号到订单的映射，并转为导出使用的 primitive 表类型。
     */
    public LongObjectHashMap<org.eclipse.collections.impl.map.mutable.primitive.LongLongHashMap> clientOrdersSnapshot() {
        return TradingAccountRuntime.copyClientOrderIndex(clientOrderIndex);
    }
    /**
     * 复制持仓容器，保留原值引用；调用方仍须遵守查询或快照屏障，不把容器复制当作所有值的深复制。
     */
    public LongObjectHashMap<PositionRuntime> positionsSnapshot() { return new LongObjectHashMap<>(positions); }
    /**
     * 复制强平容器，保留原记录引用，供冷边界导出。
     */
    public LongObjectHashMap<LiquidationRuntime> liquidationsSnapshot() { return new LongObjectHashMap<>(cold.liquidations); }
    /**
     * 复制风险记录容器，值引用不再逐个复制。
     */
    public LongObjectHashMap<com.surprising.aeron.service.risk.RiskSnapshotRuntime> riskSnapshotsSnapshot() { return new LongObjectHashMap<>(cold.riskSnapshots); }
    /**
     * 把杠杆值复制到按业务键排序的 TreeMap，排序只发生在导出边界。
     */
    public java.util.TreeMap<CoreLeverageKey, Long> leveragesSnapshot() { return new java.util.TreeMap<>(cold.leverages); }

}

/**
 * 与热订单、余额、预留和持仓分开的冷业务表集合。仍由同一 Lane 线程或受控读取边界持有，不额外引入锁、线程或第二份资金状态。
 */
final class LaneColdState {
    /** 按强平编号保存的 Lane 权威强平记录。 */
    final org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap<LiquidationRuntime> liquidations =
            new org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap<>();
    /** 用户到稠密合约及持仓腿键的活跃强平编号索引。 */
    final org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap<org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap<org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap<Long>>> activeLiquidationIndex =
            new org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap<>();
    /** 运行时持仓键到最近风险估值记录的表。 */
    final org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap<com.surprising.aeron.service.risk.RiskSnapshotRuntime> riskSnapshots =
            new org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap<>();
    /** 按用户、合约和保证金模式的业务键保存杠杆设置。 */
    final java.util.Map<com.surprising.aeron.model.CoreLeverageKey, Long> leverages =
            new java.util.HashMap<>();
    /** 用户到其全部杠杆业务键的成员索引。 */
    final org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap<java.util.HashSet<com.surprising.aeron.model.CoreLeverageKey>> leverageKeysByUser =
            new org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap<>();
    /** 按算法单编号保存的 Lane 权威算法单记录。 */
    final org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap<com.surprising.aeron.model.CoreAlgoOrderState> algoOrders =
            new org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap<>();
    /** 按触发编号保存的 Lane 权威条件记录。 */
    final org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap<com.surprising.aeron.model.CoreTriggerOrderState> triggerOrders =
            new org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap<>();
    /** 用户到其触发单编号集合的成员索引。 */
    final org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap<org.eclipse.collections.impl.set.mutable.primitive.LongHashSet> triggerIdsByUser =
            new org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap<>();
    /** 按划转编号保存的尚待完成划转状态。 */
    final org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap<com.surprising.aeron.service.account.TransferRuntime> pendingTransfers =
            new org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap<>();

    /**
     * 清空本 Lane 的全部冷业务表及其成员索引；不修改热订单、余额、预留和持仓表，也不产生账务冲正。
     */
    void clear() {
        liquidations.clear();
        activeLiquidationIndex.clear();
        riskSnapshots.clear();
        leverages.clear();
        leverageKeysByUser.clear();
        algoOrders.clear();
        triggerOrders.clear();
        triggerIdsByUser.clear();
        pendingTransfers.clear();
    }
}

