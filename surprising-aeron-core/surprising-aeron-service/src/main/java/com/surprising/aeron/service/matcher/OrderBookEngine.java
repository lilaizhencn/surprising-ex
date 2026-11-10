package com.surprising.aeron.service.matcher;

import com.surprising.aeron.service.order.*;

import com.surprising.product.api.ProductLine;
import exchange.core2.core.ExchangeApi;
import exchange.core2.core.common.cmd.OrderCommand;
import exchange.core2.core.processors.journaling.ISerializationProcessor;
import exchange.core2.core.processors.journaling.SnapshotDescriptor;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.NavigableMap;
import java.util.function.Function;
import net.openhft.chronicle.bytes.BytesIn;
import net.openhft.chronicle.bytes.WriteBytesMarshallable;
import com.surprising.aeron.service.matcher.OrderBookSnapshot.ShardProgress;


import com.surprising.aeron.config.CoreConfiguration;
import com.surprising.aeron.config.CoreRuntimeConfiguration;
import com.surprising.aeron.config.LaneTopology;
import com.surprising.aeron.exception.FatalMatchingDivergenceException;
import com.surprising.aeron.model.CoreOrderState;
import com.surprising.aeron.model.TradingCoreState;
import com.surprising.aeron.protocol.CoreBookLevelView;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.service.order.OrderCommand.ResolvedPlaceOrder;
import exchange.core2.core.SynchronousMatchingEngine;
import exchange.core2.core.common.CoreSymbolSpecification;
import exchange.core2.core.common.CoreWaitStrategy;
import exchange.core2.core.common.L2MarketData;
import exchange.core2.core.common.OrderAction;
import exchange.core2.core.common.OrderType;
import exchange.core2.core.common.SymbolType;
import exchange.core2.core.common.cmd.CommandResultCode;
import exchange.core2.core.common.config.ExchangeConfiguration;
import exchange.core2.core.common.config.InitialStateConfiguration;
import exchange.core2.core.common.config.OrdersProcessingConfiguration;
import exchange.core2.core.common.config.PerformanceConfiguration;
import exchange.core2.core.common.config.SerializationConfiguration;
import exchange.core2.core.processors.journaling.InMemorySerializationProcessor;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import lombok.Getter;
import lombok.experimental.Accessors;
import org.eclipse.collections.impl.set.mutable.primitive.LongHashSet;

/**
 * Matcher 与 exchange-core 同步订单簿之间的接入边界。按 instrumentId 路由到独立分片，转换下单、撤单、改单并绑定命令证据；成交事实交给账户结算，余额和持仓不由这里计算。分片调用由所属 Matcher 线程串行执行，冷态启动、对账、哈希和快照需要调用方先建立相应静止边界；名称中的 Async 多数只包装同步结果，不自动切换线程。
 */
public final class OrderBookEngine implements AutoCloseable {

    /**
     * 仅为原生撤单前缀失败记录的 JFR 事件。启用事件时才分配，成功交易路径不创建它。
     */
    @jdk.jfr.Name("surprising.MatcherPrefixFailure")
    @jdk.jfr.Category("Surprising Core")
    @jdk.jfr.StackTrace(false)
    static final class MatcherPrefixFailure extends jdk.jfr.Event {
        /** JFR 事件类型缓存，只有启用失败事件时才创建记录对象。 */
        private static final jdk.jfr.EventType TYPE = jdk.jfr.EventType.getEventType(MatcherPrefixFailure.class);
        /**
         * coreSequence：逻辑 Core 命令序号。
         * commandIdHigh：命令 UUID 高六十四位。
         * commandIdLow：命令 UUID 低六十四位。
         * logicalOrderId：外层逻辑命令关联的订单号，可与实际被撤订单不同。
         * canceledOrderId：失败原生撤单对应的真实订单号。
         * userId：失败原生撤单携带的用户号。
         */
        public long coreSequence, commandIdHigh, commandIdLow, logicalOrderId, canceledOrderId, userId;
        /**
         * matcherShard：失败发生的 Matcher 证据分片号。
         * symbolId：失败原生结果携带的品种编号。
         */
        public int matcherShard, symbolId;
        /** 失败原生结果码文本。 */
        public String nativeResultCode;
    }

    private final CoreRuntimeConfiguration startupConfiguration;
    /** 按全局 Matcher 分片号排列的同步引擎数组，激活创建，关闭后清空。 */
    private SynchronousMatchingEngine[] engines;
    /** 收集与导入快照模块字节的共享内存存储，导出后按快照号删除。 */
    private final InMemorySerializationProcessor serializationProcessor =
            new InMemorySerializationProcessor();
    /** 品种字符串到永久数字编号的并发路由表，条目存在不表示原生已注册。 */
    private final Map<String, Integer> symbols = new ConcurrentHashMap<>();
    /** 永久编号到原字符串的并发反向表，用于拒绝同编号字符串冲突。 */
    private final Map<Integer, String> symbolNames = new ConcurrentHashMap<>();
    /**  启动配置或恢复快照确定的账户与 Matcher 分片拓扑。 返回本适配器启动或恢复时确定的线程分片拓扑。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private final LaneTopology topology;
    /** 只保存首个故障的跨线程标记，后续命令和完成结果据此停止。 */
    private final AtomicReference<Throwable> matcherFailure = new AtomicReference<>();
    /** 区分控制、业务和原生分片序号的证据账本，不是资金账本。 */
    private final MatcherEvidenceLedger matcherEvidence;
    /** 延迟恢复动作，整体激活完成后清空。 */
    private Runnable deferredActivation;
    /** 全体分片激活完成标记，用 volatile 向其他线程发布。 */
    private volatile boolean activationComplete;
    /** 已完成创建或恢复核对的分片计数，多分片最后一个负责发布全局激活完成。 */
    private final AtomicInteger activatedShardCount = new AtomicInteger();
    /** 等待分片恢复的 Matcher 快照，空白启动时为空。 */
    private OrderBookSnapshot restoredSnapshot;
    /** 恢复时固定的活动订单列表，供各分片对账使用，不作为后续实时订单权威。 */
    private List<CoreOrderState> restoredActiveOrders = List.of();
    /** 恢复点的 Core 序号，用于定位快照分歧。 */
    private long restoredCoreSequence;
    /** 恢复时逐分片写入的哈希结果，最后一个分片汇总校验。 */
    private MatcherShardSnapshot[] restoredShardHashes;
    /**
     * 当前 Matcher 写者独占的命令作用域：保存本次业务时间、嵌套标记、用户和品种注册集合，并复用临时前缀结果槽；不是跨线程共享的订单状态。
     */
    private static final class MatcherCommandScope {
        /** 当前同步命令的 Cluster 时间，命令结束时清零。 */
        long aeronTimestamp;
        /** 当前上下文是否已有命令执行，用于拒绝嵌套。 */
        boolean active;
        /** 当前上下文写入的已见用户集合，只在快照边界物化为协议 Set。 */
        final LongHashSet users = new LongHashSet();
        /** 只由当前 Matcher 写者使用的原生注册完成集合，回放也使用该集合。 */
        final org.eclipse.collections.impl.set.mutable.primitive.IntHashSet registeredSymbols =
                new org.eclipse.collections.impl.set.mutable.primitive.IntHashSet();
        /** 当前写者复用的控制撤单临时原生槽，下一次调用会覆盖。 */
        final exchange.core2.core.common.MatcherResult prefixResult = exchange.core2.core.common.MatcherResult.commandSlot();
    }

    /** 每个激活分片独占的命令临时状态与用户登记集合。 */
    private MatcherCommandScope[] shardCommandScopes;
    /** 分片激活时登记的实际线程，用于普通线程身份查找。 */
    private Thread[] shardThreads;
    /** 构造适配器的线程，允许冷态独立调用。 */
    private final Thread standaloneThread = Thread.currentThread();
    /** 创建线程使用的独立命令临时状态与用户集合。 */
    private final MatcherCommandScope standaloneCommandScope = new MatcherCommandScope();

    /**
     * 按当前拓扑创建每个分片独占的用户/注册集合和临时撤单槽，线程登记表同时清空；不在这里启动线程或订单簿。
     */
    private void initializeCommandScopes() {
        shardCommandScopes = new MatcherCommandScope[topology.matchingEngineCount()];
        shardThreads = new Thread[shardCommandScopes.length];
        for (/* 当前遍历数组或模块列表的下标。 */ int i = 0; i < shardCommandScopes.length; i++) shardCommandScopes[i] = new MatcherCommandScope();
    }

    /**
     * 优先按 MatcherThread 携带的合法分片号取上下文，其次接受创建适配器的线程，再按激活时登记的线程查找；其他线程直接拒绝。它不逐次核对 MatcherThread 是否就是登记实例。
     */
    private MatcherCommandScope currentCommandScope() {
        // 当前调用线程，用于选择独占的命令上下文。
        Thread current = Thread.currentThread();
        if (current instanceof com.surprising.aeron.bootstrap.matcher.MatcherThread /* 携带 Matcher 分片号的工作线程。 */ mt) {
            // 当前需要操作或汇总的 Matcher 分片号。
            int shardId = mt.shardId();
            if (shardId >= 0 && shardId < shardCommandScopes.length) {
                return shardCommandScopes[shardId];
            }
        }
        if (current == standaloneThread) return standaloneCommandScope;
        // 当前已登记分片线程数组的局部引用。
        Thread[] threads = shardThreads;
        if (threads != null) {
            for (/* 当前遍历数组或模块列表的下标。 */ int i = 0; i < threads.length; i++) {
                if (threads[i] == current) return shardCommandScopes[i];
            }
        }
        throw new IllegalStateException("matcher command must run on its owning shard thread");
    }

    /**
     * 在当前允许调用的线程上下文中登记用户，供冷态快照与哈希边界汇总；不是原生风险引擎开户。
     * @param userId 请求或对账订单所属用户号
     */
    private void recordUser(long userId) {
        currentCommandScope().users.add(userId);
    }

    /**
     * 把独立上下文与各分片的用户集合汇总为排序去重的协议 Set；本方法不锁住其他分片，调用方保证读取边界。
     */
    private Set<Long> snapshotUsers() {
        // 仅在快照边界建立的排序去重用户集合，汇总独立上下文和所有分片。
        Set<Long> snapshot = new java.util.TreeSet<>();
        standaloneCommandScope.users.forEach((/* 当前用户集合里的一个用户编号，加入排序去重的冷态快照。 */ long userId) -> snapshot.add(userId));
        for (MatcherCommandScope /* 本次冷态汇总正在读取的一个分片独占上下文，调用方已保证读取边界。 */ scope : shardCommandScopes) {
            scope.users.forEach((/* 当前用户集合里的一个用户编号，加入排序去重的冷态快照。 */ long userId) -> snapshot.add(userId));
        }
        return snapshot;
    }

    /**
     * 通过统一配置入口绑定独立运行的拓扑，立即在当前线程创建全套同步订单簿。
     */
    public OrderBookEngine() {
        this(true);
    }

    /**
     * 创建空白适配器，可立即创建全部订单簿，也可留给后续分片激活。
     * @param startImmediately 是否在构造时同步激活全部引擎
     */
    public OrderBookEngine(boolean startImmediately) {
        this(CoreConfiguration.standaloneLaneTopology(), startImmediately);
    }

    /**
     * 使用账户运行时共用的显式拓扑创建空白 Matcher，不再自行读取配置。
     * @param topology 启动入口绑定并校验的不可变交易拓扑
     * @param startImmediately 是否立即创建引擎，假时留给后续激活
     */
    public OrderBookEngine(LaneTopology topology, boolean startImmediately) {
        this(topology, startImmediately, CoreRuntimeConfiguration.standalone());
    }

    public OrderBookEngine(LaneTopology topology, boolean startImmediately,
            CoreRuntimeConfiguration configuration) {
        this.startupConfiguration = java.util.Objects.requireNonNull(configuration);
        this.topology = java.util.Objects.requireNonNull(topology, "topology");
        initializeCommandScopes();
        this.matcherEvidence = new MatcherEvidenceLedger(topology);
        if (startImmediately) {
            start();
            activationComplete = true;
        }
    }

    /**
     * 按 Core 快照元数据恢复，默认立即激活并核对订单簿。
     * @param state 对应恢复或快照点的 Core 状态，具体读取边界见方法说明
     * @param activeOrders 同一冻结点的 Core 活动订单序列
     * @param coreSequence 当前逻辑命令或冻结点的 Core 序号
     * @param snapshot 已有 Matcher 快照；启动路径为空时表示空白引擎
     */
    public OrderBookEngine(
            TradingCoreState state,
            Iterable<CoreOrderState> activeOrders,
            long coreSequence,
            OrderBookSnapshot snapshot) {
        this(state, activeOrders, coreSequence, snapshot, true);
    }

    /**
     * 从非空 Core 状态计算预期业务哈希，再交给完整恢复构造器；state 为空时传零，最终校验负责拒绝。
     * @param state 对应恢复或快照点的 Core 状态，具体读取边界见方法说明
     * @param activeOrders 同一冻结点的 Core 活动订单序列
     * @param coreSequence 当前逻辑命令或冻结点的 Core 序号
     * @param snapshot 已有 Matcher 快照；启动路径为空时表示空白引擎
     * @param startImmediately 是否在构造时同步激活全部引擎
     */
    public OrderBookEngine(
            TradingCoreState state,
            Iterable<CoreOrderState> activeOrders,
            long coreSequence,
            OrderBookSnapshot snapshot,
            boolean startImmediately) {
        this(state, activeOrders, coreSequence, snapshot, startImmediately,
                state == null ? 0 : state.businessStateHash());
    }

    /**
     * 先校验 Core 与 Matcher 快照元数据，导入模块字节和路由/用户表，复制活动订单并保存待激活动作；可立即执行恢复。这里只恢复路由登记，不把它当成原生品种注册证据。恢复异常停止已建引擎并包装为致命分歧。
     * @param state 对应恢复或快照点的 Core 状态，具体读取边界见方法说明
     * @param activeOrders 同一冻结点的 Core 活动订单序列
     * @param coreSequence 当前逻辑命令或冻结点的 Core 序号
     * @param snapshot 已有 Matcher 快照；启动路径为空时表示空白引擎
     * @param startImmediately 是否在构造时同步激活全部引擎
     * @param expectedCoreBusinessStateHash 调用方提供的预期 Core 业务哈希
     */
    public OrderBookEngine(
            TradingCoreState state,
            Iterable<CoreOrderState> activeOrders,
            long coreSequence,
            OrderBookSnapshot snapshot,
            boolean startImmediately,
            long expectedCoreBusinessStateHash) {
        this(state, activeOrders, coreSequence, snapshot, startImmediately,
                expectedCoreBusinessStateHash, CoreRuntimeConfiguration.standalone());
    }

    public OrderBookEngine(TradingCoreState state, Iterable<CoreOrderState> activeOrders,
            long coreSequence, OrderBookSnapshot snapshot, boolean startImmediately,
            long expectedCoreBusinessStateHash, CoreRuntimeConfiguration configuration) {
        this.startupConfiguration = java.util.Objects.requireNonNull(configuration);
        if (snapshot == null || activeOrders == null) {
            throw new IllegalArgumentException("matcher snapshot is required");
        }
        try {
            if (state == null) throw new IllegalStateException("Core snapshot state is missing");
            snapshot.verifyCoreManifest(state.productLine(), coreSequence, expectedCoreBusinessStateHash);
            this.topology = snapshot.topology();
            initializeCommandScopes();
            this.matcherEvidence = new MatcherEvidenceLedger(
                    topology, 0, snapshot.matcherShardProgress());
            serializationProcessor.importSnapshot(snapshot.modules());
            snapshot.symbols().forEach((/* 当前正在登记、规范化或查询的品种字符串。 */ instrumentId, /* 当前品种的永久原生编号。 */ symbolId) -> {
                // 恢复反向路由时原有的品种字符串，用来发现同编号冲突。
                String previous = symbolNames.put(symbolId, instrumentId);
                if (previous != null && !previous.equals(instrumentId)) {
                    throw new IllegalArgumentException("matcher instrumentId registry collision");
                }
                symbols.put(instrumentId, symbolId);
            });
            // 路由表也含尚未创建订单簿的 instrumentId，不能作为 native 注册完成的证据。
            // 恢复后的首次使用仍经 ensureSymbol；native 对已有订单簿的注册是幂等操作。
            snapshot.users().forEach(standaloneCommandScope.users::add);
            // 从活动订单迭代器复制出的恢复对账列表。
            List<CoreOrderState> restoredOrders = new ArrayList<>();
            activeOrders.forEach(restoredOrders::add);
            restoredSnapshot = snapshot;
            restoredActiveOrders = List.copyOf(restoredOrders);
            restoredCoreSequence = coreSequence;
            deferredActivation = () -> activateRestoredMatcher(
                    restoredOrders, coreSequence, snapshot);
            if (startImmediately) activate();
        } catch (/* 当前捕获的运行异常，按所在入口停止、记故障或完成失败结果。 */ RuntimeException exception) {
            stop();
            // 仅拆一层 CompletionException 后的恢复或激活异常。
            Throwable cause = unwrap(exception);
            if (cause instanceof FatalMatchingDivergenceException /* 已经是致命撮合分歧的异常，直接保留原类型抛出。 */ divergence) throw divergence;
            throw new FatalMatchingDivergenceException("matcher restore", coreSequence,
                    snapshot.snapshotId(), "native snapshot validation failed", cause);
        }
    }

    /**
     * 在当前线程一次性创建或恢复全部订单簿，成功才清掉延迟动作并标记激活；运行异常时停止引擎，保留原有致命分歧类型后抛出。
     */
    public synchronized void activate() {
        if (activationComplete) return;
        try {
            // 当前待执行的延迟恢复动作，空值表示空白启动。
            Runnable activation = deferredActivation;
            if (activation == null) start(); else activation.run();
            deferredActivation = null;
            activationComplete = true;
        } catch (/* 当前捕获的运行异常，按所在入口停止、记故障或完成失败结果。 */ RuntimeException exception) {
            stop();
            // 仅拆一层 CompletionException 后的恢复或激活异常。
            Throwable cause = unwrap(exception);
            if (cause instanceof FatalMatchingDivergenceException /* 已经是致命撮合分歧的异常，直接保留原类型抛出。 */ divergence) throw divergence;
            throw exception;
        }
    }

    /**
     * 返回全体分片是否已完成激活的可见标记，不是单个分片就绪状态。
     */
    public boolean activated() { return activationComplete; }

    /**
     * 由所属线程激活指定分片，登记线程并构造一个独立同步引擎；恢复时核对该分片活动订单和哈希，最后一个分片再比对全局引擎哈希并发布全体就绪。单分片走整体激活分支，此方法不负责创建工作线程。
     * @param shardId 目标全局 Matcher 分片号
     */
    public void activateShard(int shardId) {
        if (shardId < 0 || shardId >= topology.matchingEngineCount()) {
            throw new IllegalArgumentException("matcher shard is outside configured topology");
        }
        shardThreads[shardId] = Thread.currentThread();
        if (topology.matchingEngineCount() == 1) {
            synchronized (this) {
                if (activationComplete) return;
                // 当前待执行的延迟恢复动作，空值表示空白启动。
                Runnable activation = deferredActivation;
                if (activation == null) start(); else activation.run();
                deferredActivation = null;
                activationComplete = true;
                return;
            }
        }
        synchronized (this) {
            if (activationComplete) return;
            if (engines == null) engines = new SynchronousMatchingEngine[topology.matchingEngineCount()];
            if (restoredShardHashes == null) restoredShardHashes = new MatcherShardSnapshot[engines.length];
            if (engines[shardId] != null) return;
        }
        startShard(shardId, restoredSnapshot);
        if (restoredSnapshot != null) {
            reconcileOpenOrdersShard(restoredActiveOrders, shardId, restoredCoreSequence,
                    restoredSnapshot.snapshotId(), "matcher restore");
            // 当前冷态读取或汇总出的引擎哈希。
            int engineHash = engineStateHash(shardId);
            restoredShardHashes[shardId] = new MatcherShardSnapshot(
                    shardId, engineHash);
        }
        if (activatedShardCount.incrementAndGet() == topology.matchingEngineCount()) {
            if (restoredSnapshot != null) {
                // 当前冷态读取或汇总出的引擎哈希。
                int engineHash = aggregateEngineStateHash(List.of(restoredShardHashes));
                if (engineHash != restoredSnapshot.engineStateHash()) {
                    throw new FatalMatchingDivergenceException("matcher restore", restoredCoreSequence,
                            restoredSnapshot.snapshotId(), "restored exchange-core state hash mismatch");
                }
            }
            synchronized (this) {
                deferredActivation = null;
                activationComplete = true;
            }
        }
    }

    /**
     * 同步加载全部原生快照，逐单对账并比对汇总引擎哈希；Core 元数据已在构造阶段检查。
     * @param activeOrders 同一冻结点的 Core 活动订单序列
     * @param coreSequence 当前逻辑命令或冻结点的 Core 序号
     * @param snapshot 已有 Matcher 快照；启动路径为空时表示空白引擎
     */
    private void activateRestoredMatcher(Iterable<CoreOrderState> activeOrders,
                                         long coreSequence, OrderBookSnapshot snapshot) {
        start(snapshot);
        reconcileOpenOrders(activeOrders, coreSequence, snapshot.snapshotId(), "matcher restore");
        // 全部恢复订单对账后的汇总哈希，用来与快照比较。
        int restoredHash = currentEngineStateHash();
        if (restoredHash != snapshot.engineStateHash()) {
            throw new FatalMatchingDivergenceException("matcher restore", coreSequence,
                    snapshot.snapshotId(), "restored exchange-core state hash mismatch"
                            + " (expectedEngine=" + snapshot.engineStateHash()
                            + ", actualEngine=" + restoredHash + ')');
        }
    }

    /**
     * 直接读取已完成准入的订单输入，按产品线滑点规则计算市价边界并填入原生槽。
     * @param userId 请求或对账订单所属用户号
     * @param command 只读撮合输入，必须遵守其冻结及使用期限
     * @param target 调用方独占且尚未发布的原生结果槽或结算事件，具体类型决定用途
     */
    private exchange.core2.core.common.MatcherResult placeNative(long userId, com.surprising.aeron.service.order.OrderCommand.ResolvedPlaceOrder command, exchange.core2.core.common.MatcherResult target) {
        // 当前品种的永久原生编号。
        int symbolId = ensureSymbol(command.instrument().instrumentIdValue());
        recordUser(userId);
        // 市价保护收紧后的原生撮合价格，限价单保留原价。
        long price = protectedMarketPrice(symbolId, command.side(), command.orderType(),
                command.matchingPriceTicks(), slippagePpm(command.instrument().contractType().productLine()));
        return engine(symbolId).placeInto(target,
                currentCommandScope().aeronTimestamp, command.orderId(), 0,
                price, command.matchingPriceTicks(), command.quantitySteps(),
                command.side() == CoreOrderSide.BUY ? OrderAction.BID : OrderAction.ASK,
                orderType(command.orderType(), command.timeInForce()), symbolId, userId);
    }

    /**
     * 从冻结的批量准入输入读取剩余数量等字段，转换后直接写原生槽；冻结身份检查由外层入口先执行。
     * @param userId 请求或对账订单所属用户号
     * @param command 只读撮合输入，必须遵守其冻结及使用期限
     * @param target 调用方独占且尚未发布的原生结果槽或结算事件，具体类型决定用途
     */
    private exchange.core2.core.common.MatcherResult placeNativeAdmitted(long userId,
            com.surprising.aeron.service.order.Order command, exchange.core2.core.common.MatcherResult target) {
        // 当前品种的永久原生编号。
        int symbolId = ensureSymbol(command.instrument().instrumentIdValue());
        recordUser(userId);
        // 市价保护收紧后的原生撮合价格，限价单保留原价。
        long price = protectedMarketPrice(symbolId, command.side(), command.orderType(),
                command.matchingPriceTicks(), slippagePpm(command.productLine()));
        return engine(symbolId).placeInto(target,
                currentCommandScope().aeronTimestamp, command.orderId(), 0,
                price, command.matchingPriceTicks(), command.remainingQuantitySteps(),
                command.side() == CoreOrderSide.BUY ? OrderAction.BID : OrderAction.ASK,
                orderType(command.orderType(), command.timeInForce()), symbolId, userId);
    }

    /**
     * 检查分片、冻结输入身份和命令证据后开启串行命令，撮合并发布到结算事件；运行异常记为全局首个故障，finally 释放命令作用域。
     * @param shardId 目标全局 Matcher 分片号
     * @param coreSequence 当前逻辑命令或冻结点的 Core 序号
     * @param commandIdMsb 逻辑命令 UUID 高六十四位
     * @param commandIdLsb 逻辑命令 UUID 低六十四位
     * @param aeronTimestamp Cluster 提供的确定性业务时间戳
     * @param userId 请求或对账订单所属用户号
     * @param command 只读撮合输入，必须遵守其冻结及使用期限
     * @param target 调用方独占且尚未发布的原生结果槽或结算事件，具体类型决定用途
     */
    public exchange.core2.core.common.MatcherResult placeAdmittedDirect(
            int shardId, long coreSequence, long commandIdMsb, long commandIdLsb, long aeronTimestamp,
            long userId, com.surprising.aeron.service.order.Order command,
            com.surprising.aeron.service.settlement.TradeSettlement target) {
        if (shardId < 0 || shardId >= topology.matchingEngineCount() || command == null || target == null)
            throw new IllegalArgumentException("invalid direct matcher command");
        command.requireFrozenMatcherInput(userId);
        validateCommandEvidence(coreSequence, commandIdMsb, commandIdLsb, command.orderId(), aeronTimestamp);
        // 当前线程可使用的独占命令上下文。
        MatcherCommandScope scope = beginSynchronousCommand(aeronTimestamp);
        try {
            return publishNativeMatcherResult(shardId, coreSequence, commandIdMsb, commandIdLsb, command.orderId(), placeNativeAdmitted(userId, command, target.nativeResultSlot(0)), target);
        } catch (/* 当前捕获的运行异常，按所在入口停止、记故障或完成失败结果。 */ RuntimeException exception) {
            matcherFailure.compareAndSet(null, exception);
            throw exception;
        } finally {
            scope.aeronTimestamp = 0;
            scope.active = false;
        }
    }

    /**
     * 检查分片和准入输入后同步下单，将原生结果与证据写入复用结算事件；异常置故障标记，成功与失败均退出命令作用域。
     * @param shardId 目标全局 Matcher 分片号
     * @param coreSequence 当前逻辑命令或冻结点的 Core 序号
     * @param commandIdMsb 逻辑命令 UUID 高六十四位
     * @param commandIdLsb 逻辑命令 UUID 低六十四位
     * @param aeronTimestamp Cluster 提供的确定性业务时间戳
     * @param userId 请求或对账订单所属用户号
     * @param command 只读撮合输入，必须遵守其冻结及使用期限
     * @param target 调用方独占且尚未发布的原生结果槽或结算事件，具体类型决定用途
     */
    public exchange.core2.core.common.MatcherResult placeDirect(
            int shardId, long coreSequence, long commandIdMsb, long commandIdLsb, long aeronTimestamp,
            long userId, com.surprising.aeron.service.order.OrderCommand.ResolvedPlaceOrder command,
            com.surprising.aeron.service.settlement.TradeSettlement target) {
        if (shardId < 0 || shardId >= topology.matchingEngineCount() || command == null || target == null)
            throw new IllegalArgumentException("invalid direct matcher command");
        validateCommandEvidence(coreSequence, commandIdMsb, commandIdLsb, command.orderId(), aeronTimestamp);
        // 当前线程可使用的独占命令上下文。
        MatcherCommandScope scope = beginSynchronousCommand(aeronTimestamp);
        try {
            return publishNativeMatcherResult(shardId, coreSequence, commandIdMsb, commandIdLsb, command.orderId(), placeNative(userId, command, target.nativeResultSlot(0)), target);
        } catch (/* 当前捕获的运行异常，按所在入口停止、记故障或完成失败结果。 */ RuntimeException exception) {
            matcherFailure.compareAndSet(null, exception);
            throw exception;
        } finally {
            scope.aeronTimestamp = 0;
            scope.active = false;
        }
    }

    /**
     * 检查分片、品种和命令证据，同步撤单并发布结果；运行异常记录首个故障，最后清理命令作用域。
     * @param shardId 目标全局 Matcher 分片号
     * @param coreSequence 当前逻辑命令或冻结点的 Core 序号
     * @param commandIdMsb 逻辑命令 UUID 高六十四位
     * @param commandIdLsb 逻辑命令 UUID 低六十四位
     * @param orderId 当前命令关联的订单号，证据校验允许零
     * @param aeronTimestamp Cluster 提供的确定性业务时间戳
     * @param userId 请求或对账订单所属用户号
     * @param instrumentId 外部永久数字品种字符串
     * @param target 调用方独占且尚未发布的原生结果槽或结算事件，具体类型决定用途
     */
    public exchange.core2.core.common.MatcherResult cancelDirect(
            int shardId, long coreSequence, long commandIdMsb, long commandIdLsb, long orderId,
            long aeronTimestamp, long userId, int symbolId,
            com.surprising.aeron.service.settlement.TradeSettlement target) {
        if (shardId < 0 || shardId >= topology.matchingEngineCount()
                || symbolId <= 0 || target == null)
            throw new IllegalArgumentException("invalid direct matcher cancellation");
        validateCommandEvidence(coreSequence, commandIdMsb, commandIdLsb, orderId, aeronTimestamp);
        // 当前线程可使用的独占命令上下文。
        MatcherCommandScope scope = beginSynchronousCommand(aeronTimestamp);
        try {
            return publishNativeMatcherResult(shardId, coreSequence, commandIdMsb, commandIdLsb, orderId, cancelNative(userId, orderId, symbolId, target.nativeResultSlot(0)), target);
        } catch (/* 当前捕获的运行异常，按所在入口停止、记故障或完成失败结果。 */ RuntimeException exception) {
            matcherFailure.compareAndSet(null, exception);
            throw exception;
        } finally {
            scope.aeronTimestamp = 0;
            scope.active = false;
        }
    }

    /**
     * 先写独立的旧单撤单槽，只有撤单接受才下新单，再发布两段事实及最终结果；新单被拒也不会回滚已执行撤单。检查最终原生分片与证据分片一致，异常记故障并退出作用域。
     * @param shardId 目标全局 Matcher 分片号
     * @param coreSequence 当前逻辑命令或冻结点的 Core 序号
     * @param commandIdMsb 逻辑命令 UUID 高六十四位
     * @param commandIdLsb 逻辑命令 UUID 低六十四位
     * @param orderId 当前命令关联的订单号，证据校验允许零
     * @param aeronTimestamp Cluster 提供的确定性业务时间戳
     * @param userId 请求或对账订单所属用户号
     * @param originalOrderId 需要先撤掉的旧订单号
     * @param instrumentId 外部永久数字品种字符串
     * @param replacement 旧单撤销接受后才提交的新单准入输入
     * @param target 调用方独占且尚未发布的原生结果槽或结算事件，具体类型决定用途
     */
    public exchange.core2.core.common.MatcherResult replaceDirect(
            int shardId, long coreSequence, long commandIdMsb, long commandIdLsb, long orderId,
            long aeronTimestamp, long userId, long originalOrderId, int symbolId,
            com.surprising.aeron.service.order.OrderCommand.ResolvedPlaceOrder replacement,
            com.surprising.aeron.service.settlement.TradeSettlement target) {
        if (shardId < 0 || shardId >= topology.matchingEngineCount()
                || symbolId <= 0 || replacement == null || target == null
                || replacement.instrument().instrumentIdValue() != symbolId)
            throw new IllegalArgumentException("invalid direct matcher replacement");
        validateCommandEvidence(coreSequence, commandIdMsb, commandIdLsb, orderId, aeronTimestamp);
        // 当前线程可使用的独占命令上下文。
        MatcherCommandScope scope = beginSynchronousCommand(aeronTimestamp);
        try {
            // 先撤旧单产生的原生结果，占用独立槽避免新单覆盖。
            exchange.core2.core.common.MatcherResult cancellation =
                    cancelNative(userId, originalOrderId, symbolId, target.replacementCancellationSlot());
            // 撤旧单接受后才产生的新单结果；旧单未接受时直接复用撤单结果。
            exchange.core2.core.common.MatcherResult result = nativeAccepted(cancellation)
                    ? placeNative(userId, replacement, target.nativeResultSlot(0)) : cancellation;
            // 根据原生结果品种计算的真实引擎分片，负一表示没有有效品种。
            int nativeShard = result.symbol() <= 0 ? shardId : topology.matcherShardId(result.symbol());
            if (nativeShard != shardId)
                throw new IllegalStateException("native matcher replacement crossed its evidence partition");
            return matcherEvidence.publishReplacement(coreSequence, commandIdMsb, commandIdLsb, orderId,
                    matcherEvidence.nextSequence(shardId), shardId, nativeShard,
                    cancellation, result, target);
        } catch (/* 当前捕获的运行异常，按所在入口停止、记故障或完成失败结果。 */ RuntimeException exception) {
            matcherFailure.compareAndSet(null, exception);
            throw exception;
        } finally {
            scope.aeronTimestamp = 0;
            scope.active = false;
        }
    }

    /**
     * 执行同一批次中的一个已准入下单项，写指定原生槽并推进本分片证据序号；只检查下标非负，槽容量由目标对象约束，此方法不负责整批账户完成。
     * @param shardId 目标全局 Matcher 分片号
     * @param index 当前批次项在目标原生槽中的下标
     * @param coreSequence 当前逻辑命令或冻结点的 Core 序号
     * @param commandId 逻辑命令 UUID
     * @param aeronTimestamp Cluster 提供的确定性业务时间戳
     * @param userId 请求或对账订单所属用户号
     * @param command 只读撮合输入，必须遵守其冻结及使用期限
     * @param target 调用方独占且尚未发布的原生结果槽或结算事件，具体类型决定用途
     */
    public exchange.core2.core.common.MatcherResult placeDirectBatchItem(
            int shardId, int index, long coreSequence, java.util.UUID commandId, long aeronTimestamp,
            long userId, com.surprising.aeron.service.order.OrderCommand.ResolvedPlaceOrder command,
            com.surprising.aeron.service.settlement.TradeSettlement target) {
        if (shardId < 0 || shardId >= topology.matchingEngineCount() || index < 0
                || command == null || target == null)
            throw new IllegalArgumentException("invalid direct matcher batch item");
        validateCommandEvidence(coreSequence, commandId, command.orderId(), aeronTimestamp);
        // 当前线程可使用的独占命令上下文。
        MatcherCommandScope scope = beginSynchronousCommand(aeronTimestamp);
        try {
            // 本批下单项写入指定复用槽后的原生结果。
            exchange.core2.core.common.MatcherResult result = placeNative(userId, command, target.nativeResultSlot(index));
            // 根据原生结果品种计算的真实引擎分片，负一表示没有有效品种。
            int nativeShard = result.symbol() <= 0 ? -1 : topology.matcherShardId(result.symbol());
            if (nativeShard >= 0 && nativeShard != shardId)
                throw new IllegalStateException("native matcher batch crossed its evidence partition");
            return matcherEvidence.publishBatchItem(coreSequence, commandId.getMostSignificantBits(), commandId.getLeastSignificantBits(), command.orderId(), matcherEvidence.nextSequence(shardId), shardId, nativeShard,
                    result, index, target);
        } catch (/* 当前捕获的运行异常，按所在入口停止、记故障或完成失败结果。 */ RuntimeException exception) {
            matcherFailure.compareAndSet(null, exception);
            throw exception;
        } finally {
            scope.aeronTimestamp = 0;
            scope.active = false;
        }
    }

    /**
     * 执行批次中的一个撤单项，核对原生与证据分片一致后发布到指定结算槽；任一运行异常记为故障并清理作用域。
     * @param shardId 目标全局 Matcher 分片号
     * @param index 当前批次项在目标原生槽中的下标
     * @param coreSequence 当前逻辑命令或冻结点的 Core 序号
     * @param commandId 逻辑命令 UUID
     * @param orderId 当前命令关联的订单号，证据校验允许零
     * @param aeronTimestamp Cluster 提供的确定性业务时间戳
     * @param userId 请求或对账订单所属用户号
     * @param instrumentId 外部永久数字品种字符串
     * @param target 调用方独占且尚未发布的原生结果槽或结算事件，具体类型决定用途
     */
    public exchange.core2.core.common.MatcherResult cancelDirectBatchItem(
            int shardId, int index, long coreSequence, java.util.UUID commandId, long orderId,
            long aeronTimestamp, long userId, String instrumentId,
            com.surprising.aeron.service.settlement.TradeSettlement target) {
        if (shardId < 0 || shardId >= topology.matchingEngineCount() || index < 0
                || instrumentId == null || instrumentId.isBlank() || target == null)
            throw new IllegalArgumentException("invalid direct matcher cancellation item");
        validateCommandEvidence(coreSequence, commandId, orderId, aeronTimestamp);
        // 当前线程可使用的独占命令上下文。
        MatcherCommandScope scope = beginSynchronousCommand(aeronTimestamp);
        try {
            // 本批撤单项写入指定复用槽后的原生结果。
            exchange.core2.core.common.MatcherResult result = cancelNative(userId, orderId, instrumentId, target.nativeResultSlot(index));
            // 根据原生结果品种计算的真实引擎分片，负一表示没有有效品种。
            int nativeShard = result.symbol() <= 0 ? shardId : topology.matcherShardId(result.symbol());
            if (nativeShard != shardId)
                throw new IllegalStateException("native matcher cancellation crossed its evidence partition");
            return matcherEvidence.publishBatchItem(coreSequence, commandId.getMostSignificantBits(), commandId.getLeastSignificantBits(), orderId, matcherEvidence.nextSequence(shardId), shardId, nativeShard,
                    result, index, target);
        } catch (/* 当前捕获的运行异常，按所在入口停止、记故障或完成失败结果。 */ RuntimeException exception) {
            matcherFailure.compareAndSet(null, exception);
            throw exception;
        } finally {
            scope.aeronTimestamp = 0;
            scope.active = false;
        }
    }

    /**
     * 只有原生 SUCCESS 或 ACCEPTED 算接受，不把任意非异常结果都视为成功。
     * @param result 当前原生或普通撮合结果，方法签名决定具体类型
     */
    private static boolean nativeAccepted(exchange.core2.core.common.MatcherResult result) {
        return result.resultCode() == CommandResultCode.SUCCESS
                || result.resultCode() == CommandResultCode.ACCEPTED;
    }

    /**
     * 拒绝已有故障后的完成结果，核对有效原生分片与业务分片相符，再分配业务证据序号并写入目标事件；这里不执行账户结算或直接推进 Core 提交。
     * @param shardId 目标全局 Matcher 分片号
     * @param coreSequence 当前逻辑命令或冻结点的 Core 序号
     * @param commandIdMsb 逻辑命令 UUID 高六十四位
     * @param commandIdLsb 逻辑命令 UUID 低六十四位
     * @param orderId 当前命令关联的订单号，证据校验允许零
     * @param result 当前原生或普通撮合结果，方法签名决定具体类型
     * @param target 调用方独占且尚未发布的原生结果槽或结算事件，具体类型决定用途
     */
    private exchange.core2.core.common.MatcherResult publishNativeMatcherResult(
            int shardId, long coreSequence, long commandIdMsb, long commandIdLsb, long orderId, exchange.core2.core.common.MatcherResult result,
            com.surprising.aeron.service.settlement.TradeSettlement target) {
        // 先前记录的首个 Matcher 故障，存在时禁止发布后续完成结果。
        Throwable poison = matcherFailure.get();
        if (poison != null)
            throw new IllegalStateException("matcher completion discarded after fatal divergence", poison);
        // 根据原生结果品种计算的真实引擎分片，负一表示没有有效品种。
        int nativeShard = result.symbol() <= 0 ? -1 : topology.matcherShardId(result.symbol());
        if (nativeShard >= 0 && nativeShard != shardId)
            throw new IllegalStateException("native matcher result crossed its evidence partition");
        return matcherEvidence.publishNative(coreSequence, commandIdMsb, commandIdLsb, orderId,
                matcherEvidence.nextSequence(shardId), shardId, nativeShard, result, target);
    }

    /**
     * 在当前线程的临时槽里执行一条撤单前缀，finally 退出作用域；此处不分配业务证据序号，也不自行捕获运行异常置故障，返回槽在下次复用前消费。
     * @param aeronTimestamp Cluster 提供的确定性业务时间戳
     * @param userId 请求或对账订单所属用户号
     * @param orderId 当前命令关联的订单号，证据校验允许零
     * @param instrumentId 外部永久数字品种字符串
     */
    public exchange.core2.core.common.MatcherResult cancelDirectPrefix(
            long aeronTimestamp, long userId, long orderId, int symbolId) {
        if (symbolId <= 0)
            throw new IllegalArgumentException("invalid direct matcher cancellation prefix");
        // 当前线程可使用的独占命令上下文。
        MatcherCommandScope scope = beginSynchronousCommand(aeronTimestamp);
        try {
            return cancelNative(userId, orderId, symbolId, scope.prefixResult);
        } finally {
            scope.aeronTimestamp = 0;
            scope.active = false;
        }
    }

    /**
     * 可选记录 JFR 失败证据，把临时原生结果捕获到结算槽后按逻辑命令发布；防止后续复用临时槽覆盖需保留的失败事实。
     * @param shardId 目标全局 Matcher 分片号
     * @param coreSequence 当前逻辑命令或冻结点的 Core 序号
     * @param commandId 逻辑命令 UUID
     * @param logicalOrderId 外层控制命令关联的逻辑订单号
     * @param result 当前原生或普通撮合结果，方法签名决定具体类型
     * @param target 调用方独占且尚未发布的原生结果槽或结算事件，具体类型决定用途
     */
    public exchange.core2.core.common.MatcherResult publishDirectPrefixFailure(
            int shardId, long coreSequence, java.util.UUID commandId, long logicalOrderId, exchange.core2.core.common.MatcherResult result,
            com.surprising.aeron.service.settlement.TradeSettlement target) {
        if (commandId == null) throw new IllegalArgumentException("invalid direct matcher command");
        if (MatcherPrefixFailure.TYPE.isEnabled()) {
            // 仅在 JFR 启用时创建的失败事件，记录逻辑身份与原生撤单结果。
            var failure = new MatcherPrefixFailure();
            failure.coreSequence = coreSequence;
            failure.commandIdHigh = commandId.getMostSignificantBits();
            failure.commandIdLow = commandId.getLeastSignificantBits();
            failure.logicalOrderId = logicalOrderId;
            failure.canceledOrderId = result.orderId();
            failure.userId = result.uid();
            failure.matcherShard = shardId;
            failure.symbolId = result.symbol();
            failure.nativeResultCode = result.resultCode().name();
            failure.commit();
        }
        // 结算事件自己持有的原生结果槽，捕获临时结果后按租约消费。
        var retained = target.nativeResultSlot(0);
        retained.capture(result);
        return publishNativeMatcherResult(shardId, coreSequence, commandId.getMostSignificantBits(),
                commandId.getLeastSignificantBits(), logicalOrderId, retained, target);
    }

    /**
     * 发布未进入订单簿的准入拒单证据，仍推进分片业务序号；检查参数和已有故障，不开启原生命令作用域，也不使用时间戳写原生结果。
     * @param shardId 目标全局 Matcher 分片号
     * @param coreSequence 当前逻辑命令或冻结点的 Core 序号
     * @param commandId 逻辑命令 UUID
     * @param orderId 当前命令关联的订单号，证据校验允许零
     * @param aeronTimestamp Cluster 提供的确定性业务时间戳
     * @param resultCode 拒单原因文本
     * @param target 调用方独占且尚未发布的原生结果槽或结算事件，具体类型决定用途
     */
    public void rejectedPlaceDirect(
            int shardId, long coreSequence, java.util.UUID commandId, long orderId,
            long aeronTimestamp, String resultCode,
            com.surprising.aeron.service.settlement.TradeSettlement target) {
        if (shardId < 0 || shardId >= topology.matchingEngineCount()
                || resultCode == null || resultCode.isBlank() || target == null)
            throw new IllegalArgumentException("invalid rejected direct matcher command");
        validateCommandEvidence(coreSequence, commandId, orderId, aeronTimestamp);
        // 先前记录的首个 Matcher 故障，存在时禁止发布后续完成结果。
        Throwable poison = matcherFailure.get();
        if (poison != null)
            throw new IllegalStateException("matcher completion discarded after fatal divergence", poison);
        matcherEvidence.publishRejection(coreSequence, commandId.getMostSignificantBits(), commandId.getLeastSignificantBits(), orderId,
                matcherEvidence.nextSequence(shardId), shardId, resultCode, target);
    }

    /**
     * 校验用户和品种列表，在当前允许调用的上下文逐个确保原生品种已注册，最后登记用户；不是只往路由 Map 里预填编号。
     * @param userId 请求或对账订单所属用户号
     * @param symbols 需要在当前线程确保注册的品种字符串序列
     */
    public void prepareOrderRoutes(long userId, Iterable<String> symbols) {
        if (userId <= 0 || symbols == null) {
            throw new IllegalArgumentException("invalid matcher route preparation");
        }
        for (String /* 当前正在登记、规范化或查询的品种字符串。 */ instrumentId : symbols) {
            if (instrumentId == null || instrumentId.isBlank()) {
                throw new IllegalArgumentException("invalid matcher route preparation instrumentId");
            }
            ensureSymbol(instrumentId);
        }
        recordUser(userId);
    }

    /**
     * 检查 Core 序号为正、UUID 非空、订单号及时间非负；UUID 对象即使全零也不会在此重载被拒绝。
     * @param coreSequence 当前逻辑命令或冻结点的 Core 序号
     * @param commandId 逻辑命令 UUID
     * @param orderId 当前命令关联的订单号，证据校验允许零
     * @param aeronTimestamp Cluster 提供的确定性业务时间戳
     */
    private static void validateCommandEvidence(long coreSequence, java.util.UUID commandId,
            long orderId, long aeronTimestamp) {
        if (coreSequence <= 0 || commandId == null || orderId < 0
                || aeronTimestamp < 0)
            throw new IllegalArgumentException("invalid matcher command evidence");
    }

    /**
     * 检查 Core 序号为正、UUID 两半不能同时为零、订单号及时间非负；不核对命令是否已经执行。
     * @param coreSequence 当前逻辑命令或冻结点的 Core 序号
     * @param commandIdMsb 逻辑命令 UUID 高六十四位
     * @param commandIdLsb 逻辑命令 UUID 低六十四位
     * @param orderId 当前命令关联的订单号，证据校验允许零
     * @param aeronTimestamp Cluster 提供的确定性业务时间戳
     */
    private static void validateCommandEvidence(long coreSequence, long commandIdMsb, long commandIdLsb,
            long orderId, long aeronTimestamp) {
        if (coreSequence <= 0 || (commandIdMsb == 0 && commandIdLsb == 0) || orderId < 0
                || aeronTimestamp < 0)
            throw new IllegalArgumentException("invalid matcher command evidence");
    }

    /**
     * 先拒绝已有故障，再取得当前线程上下文并禁止嵌套调用，保存业务时间且标记活跃；调用方必须在 finally 清掉两个字段。
     * @param aeronTimestamp Cluster 提供的确定性业务时间戳
     */
    private MatcherCommandScope beginSynchronousCommand(long aeronTimestamp) {
        // 当前捕获或共享操作返回的异常，所在方法决定登记、转发或停止等待。
        Throwable failure = matcherFailure.get();
        if (failure != null) throw new IllegalStateException("matcher is poisoned by an earlier command", failure);
        // 当前线程可使用的独占命令上下文。
        MatcherCommandScope scope = currentCommandScope();
        if (scope.active) throw new IllegalStateException("nested synchronous matcher command");
        scope.active = true;
        scope.aeronTimestamp = aeronTimestamp;
        return scope;
    }

    /**
     * 拒绝空结果及故障后的回执，绑定已聚合控制命令的业务证据；致命结果仍登记故障后返回原事实。
     * @param coreSequence 当前控制命令的 Core 序号
     * @param commandId 原命令 UUID
     * @param sequence 控制分片已经分配的业务证据序号
     * @param result 已聚合的控制撤单结果
     */
    private OrderMatchingResult.Control bindControlEvidence(long coreSequence, java.util.UUID commandId,
                                                   long sequence, OrderMatchingResult.Control result) {
        if (result == null) throw new IllegalStateException("matcher command returned no result");
        Throwable matcherPoison = matcherFailure.get();
        if (matcherPoison != null) {
            throw new IllegalStateException("matcher completion discarded after fatal divergence", matcherPoison);
        }
        OrderMatchingResult.Control bound = matcherEvidence.bindControl(coreSequence, commandId, sequence, result);
        poisonIfFatal(bound);
        return bound;
    }

    /**
     * 只有结果分类是 FATAL_DIVERGENCE 才记录首个异常，防止随后继续接受完成事实；不覆盖更早故障。
     * @param result 当前原生或普通撮合结果，方法签名决定具体类型
     */
    private void poisonIfFatal(OrderMatchingResult.Control result) {
        if (result.outcome() == OrderMatchingResult.Outcome.FATAL_DIVERGENCE) {
            matcherFailure.compareAndSet(null,
                    new IllegalStateException("fatal matcher result: " + result.resultCode()));
        }
    }

    /**
     * 让 Owner 将非空故障说明登记为首个 Matcher 故障，不在此线程继续尝试修复订单簿。
     * @param detail Owner 提供的非空白故障说明
     */
    public void poisonFromOwner(String detail) {
        if (detail == null || detail.isBlank()) throw new IllegalArgumentException("matcher poison detail is required");
        matcherFailure.compareAndSet(null, new IllegalStateException(detail));
    }

    /**
     * 冷态初始化反射字段，撮合时只读取同线程订单簿的对手最优价，避免为读一档分配完整行情；读取失败按当前约定返回负一。
     */
    private static final class DirectTopPriceReader {
        /** 同步引擎内部 router 的反射字段，初始化失败时可能为空。 */
        private static final java.lang.reflect.Field ROUTER_FIELD;
        /** 直接订单簿最优卖单的反射字段，供买单读取对手价格。 */
        private static final java.lang.reflect.Field BEST_ASK_FIELD;
        /** 直接订单簿最优买单的反射字段，供卖单读取对手价格。 */
        private static final java.lang.reflect.Field BEST_BID_FIELD;
        /** 反射字段是否全部初始化成功，不保证每次读取必能取得订单。 */
        private static final boolean AVAILABLE;

        // 尝试一次性准备内部字段；任一反射步骤失败则标记不可用，由外层选择既有盘口读取路径。
        static {
            // 反射字段是否全部成功准备，静态初始化最后保存到可用标记。
            boolean available = false;
            // 临时解析出的同步引擎 router 反射字段。
            java.lang.reflect.Field routerField = null;
            // 临时解析出的直接订单簿最优卖单反射字段。
            java.lang.reflect.Field bestAskField = null;
            // 临时解析出的直接订单簿最优买单反射字段。
            java.lang.reflect.Field bestBidField = null;
            try {
                routerField = exchange.core2.core.SynchronousMatchingEngine.class.getDeclaredField("router");
                routerField.setAccessible(true);
                // 按固定名称加载的直接订单簿实现类型。
                Class<?> directBookClass = Class.forName("exchange.core2.core.orderbook.OrderBookDirectImpl");
                bestAskField = directBookClass.getDeclaredField("bestAskOrder");
                bestAskField.setAccessible(true);
                bestBidField = directBookClass.getDeclaredField("bestBidOrder");
                bestBidField.setAccessible(true);
                available = true;
            } catch (/* 此回调不读取的上一步结果或按现有约定忽略的异常，具体含义见所在语句。 */ Throwable ignored) {
            }
            ROUTER_FIELD = routerField;
            BEST_ASK_FIELD = bestAskField;
            BEST_BID_FIELD = bestBidField;
            AVAILABLE = available;
        }

        /**
         * 反射直接读取目标订单簿的对手侧最优订单价格；字段不可用、没有订单簿或最优单、类型不符以及读取异常都返回负一，由外层决定使用原界限或查询盘口。
         * @param engine 需要读取对手最优价的同步引擎
         * @param symbolId 永久原生品种编号
         * @param isBuy 是否为买单，买单读取卖一，卖单读取买一
         */
        static long readBestPrice(exchange.core2.core.SynchronousMatchingEngine engine, int symbolId, boolean isBuy) {
            if (!AVAILABLE || engine == null) return -1L;
            try {
                // 从当前引擎反射取出的订单簿路由对象。
                exchange.core2.core.processors.MatchingEngineRouter router =
                        (exchange.core2.core.processors.MatchingEngineRouter) ROUTER_FIELD.get(engine);
                if (router == null) return -1L;
                // 当前品种的原生订单簿。
                exchange.core2.core.orderbook.IOrderBook book = router.getOrderBooks().get(symbolId);
                if (book == null) return -1L;
                // 按买卖方向读出的对手侧最优订单引用。
                Object directOrder = isBuy ? BEST_ASK_FIELD.get(book) : BEST_BID_FIELD.get(book);
                if (directOrder == null) return -1L;
                if (directOrder instanceof exchange.core2.core.common.IOrder /* 当前遍历或读取到的订单，Core 与原生类型由所在循环声明区分。 */ order) {
                    return order.getPrice();
                }
                return -1L;
            } catch (/* 反射读取失败，按当前约定返回负一而不在这里抛出。 */ Throwable e) {
                return -1L;
            }
        }
    }

    /**
     * 仅为启用滑点的市价单计算保护价：在当前 Matcher 线程读对手最优价，向内取整滑点距离后与原界限收紧。只有反射初始化不可用才查询一档盘口；可用但读不到价格时保留原界限，算术精确操作溢出会抛异常。
     * @param symbolId 永久原生品种编号
     * @param side 订单买卖方向
     * @param type 协议订单类型
     * @param originalBound 准入时已有的市价撮合价格界限
     * @param slippagePpm 以百万分比表示的最优价滑点距离
     */
    private long protectedMarketPrice(int symbolId, CoreOrderSide side,
            com.surprising.aeron.protocol.CoreOrderType type, long originalBound, long slippagePpm) {
        if (type != com.surprising.aeron.protocol.CoreOrderType.MARKET || slippagePpm == 0) return originalBound;
        // 是否为买单，决定读取卖一及使用上限方向。
        boolean buy = side == CoreOrderSide.BUY;
        // 对手侧最优价，无法读取时使用原界限。
        long best = DirectTopPriceReader.readBestPrice(engine(symbolId), symbolId, buy);
        if (best <= 0) {
            if (!DirectTopPriceReader.AVAILABLE) {
                // 仅反射初始化不可用时读取的一档原生盘口。
                L2MarketData top = engine(symbolId).orderBook(symbolId, 1);
                if (buy ? top.askSize == 0 : top.bidSize == 0) return originalBound;
                best = buy ? top.askPrices[0] : top.bidPrices[0];
            } else {
                return originalBound;
            }
        }
        // 滑点价差向最优价一侧取整；向外取整可能多出一个完整 tick。
        // 按百万分比向内取整的价差，用于收紧市价保护界限。
        long distance = Math.addExact(Math.multiplyExact(best / 1_000_000L, slippagePpm),
                (best % 1_000_000L) * slippagePpm / 1_000_000L);
        return buy ? Math.min(originalBound, Math.addExact(best, distance))
                : Math.max(originalBound, Math.subtractExact(best, distance));
    }

    /** U 本位永续和交割的市价单滑点上限为 100 ppm，其他产品沿用零值规则。 */
    private static long slippagePpm(ProductLine product) {
        return product == ProductLine.LINEAR_PERPETUAL || product == ProductLine.LINEAR_DELIVERY ? 100 : 0;
    }

    /**
     * 市价单映射为 FOK 或 IOC；限价单按 IOC、FOK、GTC、GTX 原样对应到原生类型。
     * @param orderType 协议订单类型
     * @param timeInForce 订单有效期策略
     */
    private static OrderType orderType(com.surprising.aeron.protocol.CoreOrderType orderType,
                                       com.surprising.aeron.protocol.CoreTimeInForce timeInForce) {
        if (orderType == com.surprising.aeron.protocol.CoreOrderType.MARKET) {
            return timeInForce == com.surprising.aeron.protocol.CoreTimeInForce.FOK
                    ? OrderType.FOK : OrderType.IOC;
        }
        return switch (timeInForce) {
            case IOC -> OrderType.IOC;
            case FOK -> OrderType.FOK;
            case GTC -> OrderType.GTC;
            case GTX -> OrderType.GTX;
        };
    }

    /**
     * 按给定顺序撤单，超过 1024 项直接拒绝；首个业务拒绝后余项记 NOT_SUBMITTED，已成功前缀保留。运行异常登记故障后直接抛出，不会伪造一份完整聚合回执。
     * @param orders 按调用方确定顺序排列的控制撤单请求
     */
    private OrderMatchingResult.Control cancelRoutedBatch(List<CancellationOrder> orders) {
        // 本次控制撤单项数，最多允许 1024。
        int size = orders.size();
        if (size > 1_024) {
            return new OrderMatchingResult.Control(false, "LIFECYCLE_BATCH_TOO_LARGE");
        }
        List<OrderCancelResult> cancellations = new ArrayList<>(size);
        List<exchange.core2.core.common.MatcherResult.MatcherEvent> events = new ArrayList<>(size);
        String failureCode = null;
        int successfulPrefix = 0;
        for (CancellationOrder order : orders) {
            if (failureCode != null) {
                cancellations.add(new OrderCancelResult(order.orderId(), false));
                continue;
            }
            try {
                var result = cancelNative(order.userId(), order.orderId(), order.symbolId());
                matcherEvidence.recordControlNative(topology.matcherShardId(result.symbol()), result.sequence());
                boolean accepted = result.resultCode() == CommandResultCode.SUCCESS
                        || result.resultCode() == CommandResultCode.ACCEPTED;
                cancellations.add(new OrderCancelResult(order.orderId(), accepted));
                OrderMatchingResult.Control.appendEvents(events, result.events());
                if (accepted) successfulPrefix++;
                else failureCode = result.resultCode().name();
            } catch (RuntimeException failure) {
                matcherFailure.compareAndSet(null, failure);
                throw failure;
            }
        }
        return new OrderMatchingResult.Control(failureCode == null, failureCode == null ? "SUCCESS" : failureCode,
                cancellations, failureCode != null && successfulPrefix != 0,
                java.util.Collections.unmodifiableList(events));
    }

    /**
     * 要求请求与结果等长且各结果非空，生成逐项撤单回执并统计首个失败之前的成功数，按原顺序组合事件；部分前缀成功而整体拒绝时明确登记状态变化，不重做撤单。
     * @param requested 按原顺序排列的控制撤单请求
     * @param results 与每个请求一一对应的撤单结果，未执行项需用占位结果
     */
    private OrderMatchingResult.Control aggregateRoutedCancellationResults(
            List<CancellationOrder> requested, List<OrderMatchingResult.Control> results) {
        if (requested == null || results == null || requested.size() != results.size())
            throw new IllegalArgumentException("cancellation aggregation size mismatch");
        // 用于对外说明每笔订单撤单是否接受的结果列表。
        List<OrderCancelResult> cancellations = new ArrayList<>(requested.size());
        // 首个未接受结果之前已成功撤单的连续数量。
        int successfulPrefix = 0;
        // 按结果顺序找到的首个未接受回执，用于整体结果码与成功前缀边界。
        OrderMatchingResult.Control failure = null;
        for (/* 当前批次项或盘口档位的下标。 */ int index = 0; index < results.size(); index++) {
            // 当前顺序位置的非空撤单回执，用来生成逐项结果并确定成功前缀。
            OrderMatchingResult.Control result = java.util.Objects.requireNonNull(results.get(index), "cancellation result");
            cancellations.add(new OrderCancelResult(requested.get(index).orderId(), result.accepted()));
            if (failure == null && result.accepted()) successfulPrefix++;
            else if (failure == null) failure = result;
        }
        // 是否所有撤单项都接受，空列表也没有失败。
        boolean accepted = failure == null;
        return new OrderMatchingResult.Control(accepted, accepted ? "SUCCESS" : failure.resultCode(), cancellations,
                !accepted && successfulPrefix != 0, OrderMatchingResult.Control.concatenateEvents(results));
    }

    /**
     * 执行一条原生控制撤单，先登记真实订单簿分片的原生序号，再包装普通结果，业务控制序号留给后续聚合绑定。
     * @param userId 请求或对账订单所属用户号
     * @param orderId 当前命令关联的订单号，证据校验允许零
     * @param instrumentId 外部永久数字品种字符串
     */
    private OrderMatchingResult.Control cancelControlNative(long userId, long orderId, int symbolId) {
        // 当前控制撤单的原生结果，先按真实引擎分片登记原生序号。
        var result = cancelNative(userId, orderId, symbolId);
        matcherEvidence.recordControlNative(topology.matcherShardId(result.symbol()), result.sequence());
        return OrderMatchingResult.Control.fromNative(result);
    }

    /**
     * 确保品种注册、登记用户后同步撤单，返回原生新结果，时间取自当前命令上下文。
     * @param userId 请求或对账订单所属用户号
     * @param orderId 当前命令关联的订单号，证据校验允许零
     * @param instrumentId 外部永久数字品种字符串
     */
    private exchange.core2.core.common.MatcherResult cancelNative(long userId, long orderId, int symbolId) {
        symbolId = ensureSymbol(symbolId);
        recordUser(userId);
        return engine(symbolId).cancel(currentCommandScope().aeronTimestamp, orderId, symbolId, userId);
    }

    /**
     * 确保品种注册并记用户，将同步撤单结果直接填入目标原生槽。
     * @param userId 请求或对账订单所属用户号
     * @param orderId 当前命令关联的订单号，证据校验允许零
     * @param instrumentId 外部永久数字品种字符串
     * @param target 调用方独占且尚未发布的原生结果槽或结算事件，具体类型决定用途
     */
    private exchange.core2.core.common.MatcherResult cancelNative(long userId, long orderId, String instrumentId,
            exchange.core2.core.common.MatcherResult target) {
        return cancelNative(userId, orderId, permanentInstrumentId(instrumentId), target);
    }

    private exchange.core2.core.common.MatcherResult cancelNative(long userId, long orderId, int symbolId,
            exchange.core2.core.common.MatcherResult target) {
        symbolId = ensureSymbol(symbolId);
        recordUser(userId);
        return engine(symbolId).cancelInto(target, currentCommandScope().aeronTimestamp, orderId, symbolId, userId);
    }

    /**
     * 在当前命令作用域按既定顺序执行控制撤单并绑定控制分片负一的业务证据；运行异常记录故障，已执行前缀不回滚。
     * @param coreSequence 当前逻辑命令或冻结点的 Core 序号
     * @param commandId 逻辑命令 UUID
     * @param timestamp Cluster 提供的确定性业务时间戳
     * @param orders 按调用方确定顺序排列的控制撤单请求
     */
    public OrderMatchingResult.Control cancelControlOrders(long coreSequence, java.util.UUID commandId,
            long timestamp, List<CancellationOrder> orders) {
        validateCommandEvidence(coreSequence, commandId, 0, timestamp);
        java.util.Objects.requireNonNull(orders, "control cancellations");
        // 当前线程可使用的独占命令上下文。
        MatcherCommandScope scope = beginSynchronousCommand(timestamp);
        try {
            return bindControlEvidence(coreSequence, commandId,
                    matcherEvidence.nextSequence(-1), cancelRoutedBatch(orders));
        } catch (/* 当前捕获或共享操作返回的异常，所在方法决定登记、转发或停止等待。 */ RuntimeException failure) {
            matcherFailure.compareAndSet(null, failure);
            throw failure;
        } finally { scope.aeronTimestamp = 0; scope.active = false; }
    }

    /**
     * 由目标分片线程执行一条控制撤单，只记录原生序号；Owner 后续汇总控制事实。校验时间、用户和订单，异常记故障，finally 清理作用域。
     * @param timestamp Cluster 提供的确定性业务时间戳
     * @param userId 请求或对账订单所属用户号
     * @param orderId 当前命令关联的订单号，证据校验允许零
     * @param instrumentId 外部永久数字品种字符串
     */
    public OrderMatchingResult.Control cancelControlOrder(long timestamp, long userId, long orderId, int symbolId) {
        if (timestamp < 0 || userId <= 0 || orderId <= 0)
            throw new IllegalArgumentException("invalid control cancellation");
        // 当前线程可使用的独占命令上下文。
        MatcherCommandScope scope = beginSynchronousCommand(timestamp);
        try {
            return cancelControlNative(userId, orderId, symbolId);
        } catch (/* 当前捕获或共享操作返回的异常，所在方法决定登记、转发或停止等待。 */ RuntimeException failure) {
            matcherFailure.compareAndSet(null, failure);
            throw failure;
        } finally { scope.aeronTimestamp = 0; scope.active = false; }
    }

    /**
     * 仅聚合已经在原生分片执行过的撤单结果，绑定一次控制业务证据，不重新操作任何订单簿。
     * @param coreSequence 当前逻辑命令或冻结点的 Core 序号
     * @param commandId 逻辑命令 UUID
     * @param timestamp Cluster 提供的确定性业务时间戳
     * @param orders 按调用方确定顺序排列的控制撤单请求
     * @param results 与每个请求一一对应的撤单结果，未执行项需用占位结果
     */
    public OrderMatchingResult.Control completeControlCancellations(long coreSequence, java.util.UUID commandId,
            long timestamp, List<CancellationOrder> orders, List<OrderMatchingResult.Control> results) {
        validateCommandEvidence(coreSequence, commandId, 0, timestamp);
        return bindControlEvidence(coreSequence, commandId,
                matcherEvidence.nextSequence(-1), aggregateRoutedCancellationResults(orders, results));
    }

    /**
     * 由所属分片线程先逐单对账并取哈希，再同步持久化该分片原生快照；只有成功才返回分片哈希。
     * @param shardId 目标全局 Matcher 分片号
     * @param snapshotId 正的快照编号
     * @param coreSequence 当前逻辑命令或冻结点的 Core 序号
     * @param activeOrders 同一冻结点的 Core 活动订单序列
     */
    public MatcherShardSnapshot captureShardSnapshot(
            int shardId, long snapshotId, long coreSequence, Iterable<CoreOrderState> activeOrders) {
        if (shardId < 0 || shardId >= topology.matchingEngineCount() || snapshotId <= 0
                || coreSequence < 0 || activeOrders == null) {
            throw new IllegalArgumentException("invalid matcher shard snapshot request");
        }
        reconcileOpenOrdersShard(activeOrders, shardId, coreSequence, snapshotId, "matcher snapshot");
        // 当前冷态读取或汇总出的引擎哈希。
        int engineHash = engineStateHash(shardId);
        if (!engines[shardId].storeSnapshot(snapshotId, coreSequence, coreSequence)) {
            throw new FatalMatchingDivergenceException("matcher snapshot", coreSequence, snapshotId,
                    "exchange-core persist failed for shard " + shardId);
        }
        return new MatcherShardSnapshot(shardId, engineHash);
    }

    /**
     * 要求分片结果数量完整，汇总哈希、导出已写入的模块和证据进度、物化用户集合并构造快照；无论构造是否成功都删除该编号的内存模块。对各分片的冻结与存储已由调用方完成。
     * @param snapshotId 正的快照编号
     * @param coreSequence 当前逻辑命令或冻结点的 Core 序号
     * @param businessStateHash 冻结点的 Core 业务哈希
     * @param state 对应恢复或快照点的 Core 状态，具体读取边界见方法说明
     * @param shardSnapshots 已完成相同冻结点捕获的分片哈希列表
     */
    public OrderBookSnapshot finishShardedSnapshot(
            long snapshotId, long coreSequence, long businessStateHash, TradingCoreState state,
            List<MatcherShardSnapshot> shardSnapshots) {
        if (snapshotId <= 0 || coreSequence < 0 || businessStateHash == 0 || state == null
                || shardSnapshots == null || shardSnapshots.size() != topology.matchingEngineCount()) {
            throw new IllegalArgumentException("incomplete matcher shard snapshot set");
        }
        // 当前冷态读取或汇总出的引擎哈希。
        int engineHash = aggregateEngineStateHash(shardSnapshots);
        try {
            // 从当前快照号导出的原生模块字节，构造后删除内存存储副本。
            List<InMemorySerializationProcessor.SerializedModule> modules =
                    serializationProcessor.exportSnapshot(snapshotId);
            // 导出模块中最大的原生持久化序号。
            long matcherSequence = maxMatcherSequence(modules);
            // 在冷态边界汇总并排序的已见用户集合。
            Set<Long> userSnapshot = snapshotUsers();
            return new OrderBookSnapshot(state.productLine(), topology, snapshotId, coreSequence, matcherSequence,
                    matcherEvidence.snapshot(), businessStateHash, engineHash,
                    symbols, userSnapshot, modules);
        } finally {
            serializationProcessor.removeSnapshot(snapshotId);
        }
    }

    /** 全部分片捕获结束后释放失败快照的原生模块。 */
    public void discardSnapshot(long snapshotId) {
        serializationProcessor.removeSnapshot(snapshotId);
    }

    /**
     * 同步构建 Core OPEN 订单与所有原生活动订单的精确投影，拒绝终态、未登记品种、重复订单号和字段不一致，不匹配时直接抛出故障；不自动切线程。
     * @param activeOrders 同一冻结点的 Core 活动订单序列
     * @param coreSequence 当前逻辑命令或冻结点的 Core 序号
     * @param snapshotId 正的快照编号
     * @param operation 对账操作名称或正在进行的快照对象，具体类型见签名
     */
    private void reconcileOpenOrders(
            Iterable<CoreOrderState> activeOrders,
            long coreSequence,
            long snapshotId,
            String operation) {
        // 从冻结 Core 活动订单建立的精确投影，以订单号去重。
        Map<Long, ReconciledOrder> expected = new HashMap<>();
        for (CoreOrderState /* 当前遍历或读取到的订单，Core 与原生类型由所在循环声明区分。 */ order : activeOrders) {
            if (order.status() != com.surprising.aeron.model.CoreOrderStatus.OPEN) {
                throw new FatalMatchingDivergenceException(operation, coreSequence, snapshotId,
                        "active-order index contains a terminal order");
            }
            // 当前品种的永久原生编号。
            Integer symbolId = symbols.get(order.instrumentId());
            if (symbolId == null) {
                throw new FatalMatchingDivergenceException(operation, coreSequence, snapshotId,
                        "Core open order references an unregistered matcher instrumentId");
            }
            if (expected.put(order.orderId(), new ReconciledOrder(symbolId, order.orderId(), order.userId(),
                    order.side() == CoreOrderSide.BUY ? OrderAction.BID : OrderAction.ASK,
                    order.matchingPriceTicks(), order.quantitySteps(), order.executedQuantitySteps(),
                    order.matchingPriceTicks())) != null) {
                throw new FatalMatchingDivergenceException(operation, coreSequence, snapshotId,
                        "active-order index contains duplicate order ID " + order.orderId());
            }
        }
        // 从原生订单簿活动订单建立的精确投影，以订单号去重。
        Map<Long, ReconciledOrder> actual = new HashMap<>();
        for (SynchronousMatchingEngine /* 当前遍历或关闭的同步引擎实例。 */ matcher : engines) {
            for (SynchronousMatchingEngine.OpenOrder /* 当前遍历或读取到的订单，Core 与原生类型由所在循环声明区分。 */ order : matcher.openOrders()) {
                // 把一笔原生活动订单投影为可逐字段比较的值。
                ReconciledOrder reconciled = new ReconciledOrder(order.symbolId(), order.orderId(), order.uid(),
                        order.action(), order.price(), order.size(), order.filled(), order.reserveBidPrice());
                if (actual.put(order.orderId(), reconciled) != null) {
                    throw new FatalMatchingDivergenceException(operation, coreSequence, snapshotId,
                            "exchange-core returned duplicate open order ID " + order.orderId());
                }
            }
        }
        if (!expected.equals(actual)) {
            throw new FatalMatchingDivergenceException(operation, coreSequence, snapshotId,
                    "Core OPEN orders do not exactly match exchange-core open orders"
                            + " (expected=" + expected.size() + ", actual=" + actual.size() + ')');
        }
    }

    /**
     * 只比较属于指定分片的 Core 与原生活动订单，检查终态、重复和字段差异；未登记品种或属于其他分片的 Core 订单在此分支直接跳过，与全分片对账边界不同。
     * @param activeOrders 同一冻结点的 Core 活动订单序列
     * @param shardId 目标全局 Matcher 分片号
     * @param coreSequence 当前逻辑命令或冻结点的 Core 序号
     * @param snapshotId 正的快照编号
     * @param operation 对账操作名称或正在进行的快照对象，具体类型见签名
     */
    private void reconcileOpenOrdersShard(Iterable<CoreOrderState> activeOrders, int shardId,
                                          long coreSequence, long snapshotId, String operation) {
        // 从冻结 Core 活动订单建立的精确投影，以订单号去重。
        Map<Long, ReconciledOrder> expected = new HashMap<>();
        for (CoreOrderState /* 当前遍历或读取到的订单，Core 与原生类型由所在循环声明区分。 */ order : activeOrders) {
            // 当前品种的永久原生编号。
            Integer symbolId = symbols.get(order.instrumentId());
            if (symbolId == null || topology.matcherShardId(symbolId) != shardId) continue;
            if (order.status() != com.surprising.aeron.model.CoreOrderStatus.OPEN) {
                throw new FatalMatchingDivergenceException(operation, coreSequence, snapshotId,
                        "active-order index contains a terminal order");
            }
            // 当前 Core 或原生活动订单的对账投影。
            ReconciledOrder value = new ReconciledOrder(symbolId, order.orderId(), order.userId(),
                    order.side() == CoreOrderSide.BUY ? OrderAction.BID : OrderAction.ASK,
                    order.matchingPriceTicks(), order.quantitySteps(), order.executedQuantitySteps(),
                    order.matchingPriceTicks());
            if (expected.put(order.orderId(), value) != null) {
                throw new FatalMatchingDivergenceException(operation, coreSequence, snapshotId,
                        "active-order index contains duplicate order ID " + order.orderId());
            }
        }
        // 从原生订单簿活动订单建立的精确投影，以订单号去重。
        Map<Long, ReconciledOrder> actual = new HashMap<>();
        for (SynchronousMatchingEngine.OpenOrder /* 当前遍历或读取到的订单，Core 与原生类型由所在循环声明区分。 */ order : engines[shardId].openOrders()) {
            // 当前 Core 或原生活动订单的对账投影。
            ReconciledOrder value = new ReconciledOrder(order.symbolId(), order.orderId(), order.uid(),
                    order.action(), order.price(), order.size(), order.filled(), order.reserveBidPrice());
            if (actual.put(order.orderId(), value) != null) {
                throw new FatalMatchingDivergenceException(operation, coreSequence, snapshotId,
                        "exchange-core returned duplicate open order ID " + order.orderId());
            }
        }
        if (!expected.equals(actual)) {
            throw new FatalMatchingDivergenceException(operation, coreSequence, snapshotId,
                    "Core OPEN orders do not exactly match exchange-core shard " + shardId
                            + " (expected=" + expected.size() + ", actual=" + actual.size() + ')');
        }
    }

    /**
     * 在调用线程按分片号折叠哈希，不创建中间分片快照列表；调用方负责保持原生引擎读取边界。
     */
    private int currentEngineStateHash() {
        if (engines.length == 1) return engineStateHash(0);
        int engineHash = 1;
        for (int shardId = 0; shardId < engines.length; shardId++) {
            engineHash = (engineHash * 31 + shardId) * 31 + engineStateHash(shardId);
        }
        return engineHash;
    }

    /**
     * 按原生报告的迭代顺序折叠子模块类型、编号和数值为引擎哈希；int 溢出按 Java 哈希运算保留，不是资金数值或密码摘要。
     * @param shardId 目标全局 Matcher 分片号
     */
    private int engineStateHash(int shardId) {
        // 原生引擎当前各子模块状态哈希报告。
        var report = engines[shardId].stateHashReport();
        // 顺序折叠出的引擎哈希，int 自然溢出属于哈希算法。
        int engineHash = 1;
        for (Map.Entry<exchange.core2.core.common.api.reports.StateHashReportResult.SubmoduleKey,
                Integer> /* 当前子模块哈希或品种路由条目，具体类型见循环声明。 */ entry : report.getHashCodes().entrySet()) {
            engineHash = engineHash * 31 + entry.getKey().submodule.code;
            engineHash = engineHash * 31 + entry.getKey().moduleId;
            engineHash = engineHash * 31 + entry.getValue();
        }
        return engineHash;
    }

    /**
     * 单分片直接返回引擎哈希；多分片要求列表下标就是分片号再顺序折叠。单分片分支不额外核对该条的分片号，调用方保证集合完整。
     * @param shards 各分片结果列表，顺序与完整性要求见方法说明
     */
    private static int aggregateEngineStateHash(List<MatcherShardSnapshot> shards) {
        if (shards.size() == 1) return shards.getFirst().engineHash();
        // 顺序折叠出的引擎哈希，int 自然溢出属于哈希算法。
        int engineHash = 1;
        for (/* 当前需要操作或汇总的 Matcher 分片号。 */ int shardId = 0; shardId < shards.size(); shardId++) {
            // 当前参与汇总的分片哈希或启动盘口结果。
            MatcherShardSnapshot shard = shards.get(shardId);
            if (shard.shardId() != shardId) throw new IllegalArgumentException("matcher shards are not ordered");
            engineHash = (engineHash * 31 + shardId) * 31 + shard.engineHash();
        }
        return engineHash;
    }

    /**
     * 校验深度 1 至 100 并规范品种字符串，未登记返回空列表；已登记则在调用线程读订单簿，转换结果排序为买卖侧再价格升序。
     * @param requestedSymbol 待查询的品种字符串，会 trim 并按 ROOT 转为大写
     * @param depth 每侧期望读取的盘口深度，范围 1 至 100
     */
    public List<CoreBookLevelView> orderBookLevels(String requestedSymbol, int depth) {
        if (requestedSymbol == null || requestedSymbol.isBlank() || depth < 1 || depth > 100) {
            throw new IllegalArgumentException("invalid single-instrumentId book query");
        }
        // 当前正在登记、规范化或查询的品种字符串。
        String instrumentId = requestedSymbol.trim().toUpperCase(java.util.Locale.ROOT);
        // 当前品种的永久原生编号。
        Integer symbolId = symbols.get(instrumentId);
        if (symbolId == null) return List.of();
        return bookLevels(instrumentId, engine(symbolId).orderBook(symbolId, depth));
    }

    /**
     * 在当前分片线程筛选路由表，按品种名读取本分片各盘口并转换为启动视图，深度限制 1 至 100；不在这里等待其他分片。
     * @param shardId 目标全局 Matcher 分片号
     * @param depth 每侧期望读取的盘口深度，范围 1 至 100
     */
    public OrderBookDepthSnapshot orderBookBootstrapShard(int shardId, int depth) {
        if (shardId < 0 || shardId >= topology.matchingEngineCount() || depth < 1 || depth > 100) {
            throw new IllegalArgumentException("invalid matcher shard bootstrap query");
        }
        // 当前需要读取盘口的路由条目副本，随后按品种字符串排序。
        List<Map.Entry<String, Integer>> entries = new ArrayList<>();
        for (Map.Entry<String, Integer> /* 当前子模块哈希或品种路由条目，具体类型见循环声明。 */ entry : symbols.entrySet()) {
            if (topology.matcherShardId(entry.getValue()) == shardId) {
                entries.add(entry);
            }
        }
        entries.sort(Map.Entry.comparingByKey());
        // 本次转换出的协议盘口档位，返回前按既定规则排序。
        ArrayList<CoreBookLevelView> levels = new ArrayList<>();
        // 需保留在启动视图中的有序品种清单，包括无档位的品种。
        List<String> entrySymbols = new ArrayList<>(entries.size());
        for (Map.Entry<String, Integer> /* 当前子模块哈希或品种路由条目，具体类型见循环声明。 */ entry : entries) {
            entrySymbols.add(entry.getKey());
            levels.addAll(bookLevels(entry.getKey(), engines[shardId].orderBook(entry.getValue(), depth)));
        }
        return new OrderBookDepthSnapshot(entrySymbols, levels);
    }

    /**
     * 要求结果数等于拓扑分片数，拼接品种与档位后统一排序；输入不携带分片身份，本方法不另验证重复分片或重复品种。
     * @param shards 各分片结果列表，顺序与完整性要求见方法说明
     */
    public OrderBookDepthSnapshot mergeBookBootstrapShards(List<OrderBookDepthSnapshot> shards) {
        if (shards == null || shards.size() != topology.matchingEngineCount()) {
            throw new IllegalArgumentException("incomplete matcher bootstrap shards");
        }
        // 汇总各分片品种清单的临时列表，最终按字符串排序。
        ArrayList<String> mergedSymbols = new ArrayList<>();
        // 汇总各分片盘口档位的临时列表，最终按品种、方向和价格排序。
        ArrayList<CoreBookLevelView> mergedLevels = new ArrayList<>();
        for (OrderBookDepthSnapshot /* 当前参与汇总的分片哈希或启动盘口结果。 */ shard : shards) {
            mergedSymbols.addAll(shard.symbols());
            mergedLevels.addAll(shard.levels());
        }
        mergedSymbols.sort(String::compareTo);
        mergedLevels.sort(Comparator.comparing(CoreBookLevelView::instrumentId)
                .thenComparing(CoreBookLevelView::side)
                .thenComparingLong(CoreBookLevelView::priceTicks));
        return new OrderBookDepthSnapshot(mergedSymbols, mergedLevels);
    }

    /**
     * 把原生卖档、买档的价格、数量和订单数转成协议视图，再按买卖侧和价格升序排序；不会把买档单独改成价格降序。
     * @param instrumentId 外部永久数字品种字符串
     * @param book 当前同步读取的原生盘口数据
     */
    private static List<CoreBookLevelView> bookLevels(String instrumentId, L2MarketData book) {
        // 本次转换出的协议盘口档位，返回前按既定规则排序。
        List<CoreBookLevelView> levels = new ArrayList<>(Math.addExact(book.askSize, book.bidSize));
        for (/* 当前批次项或盘口档位的下标。 */ int index = 0; index < book.askSize; index++) {
            levels.add(new CoreBookLevelView(instrumentId, CoreOrderSide.SELL, book.askPrices[index],
                    book.askVolumes[index], book.askOrders[index]));
        }
        for (/* 当前批次项或盘口档位的下标。 */ int index = 0; index < book.bidSize; index++) {
            levels.add(new CoreBookLevelView(instrumentId, CoreOrderSide.BUY, book.bidPrices[index],
                    book.bidVolumes[index], book.bidOrders[index]));
        }
        levels.sort(Comparator.comparing(CoreBookLevelView::side)
                .thenComparingLong(CoreBookLevelView::priceTicks));
        return levels;
    }

    /**
     * 以空白初始状态同步创建全部引擎，不启动独立工作线程。
     */
    private void start() {
        start(null);
    }

    /**
     * 重新分配全体分片引擎数组，逐个在当前线程构造空白或恢复的订单簿，最后登记已创建数量。
     * @param snapshot 已有 Matcher 快照；启动路径为空时表示空白引擎
     */
    private void start(OrderBookSnapshot snapshot) {
        engines = new SynchronousMatchingEngine[topology.matchingEngineCount()];
        for (/* 当前需要操作或汇总的 Matcher 分片号。 */ int shardId = 0; shardId < engines.length; shardId++) startShard(shardId, snapshot);
        activatedShardCount.set(engines.length);
    }

    /**
     * 在当前线程创建一个 MATCHING_ONLY 同步引擎，原生每个实例只有一个 Matcher，账户风险和原生日志关闭；从快照或空白启动，并用分片序列化包装器将本地模块号映射为全局分片号。
     * @param shardId 目标全局 Matcher 分片号
     * @param snapshot 已有 Matcher 快照；启动路径为空时表示空白引擎
     */
    private void startShard(int shardId, OrderBookSnapshot snapshot) {
        shardThreads[shardId] = Thread.currentThread();
        // 空白引擎或指定快照号的原生初始状态配置。
        InitialStateConfiguration initialState = snapshot == null
                ? InitialStateConfiguration.cleanStart("aeron-authoritative-book")
                : InitialStateConfiguration.fromSnapshotOnly(
                        "aeron-authoritative-book", snapshot.snapshotId(), 0);
        // 当前分片的 MATCHING_ONLY 同步引擎配置，不启动独立处理线程。
        ExchangeConfiguration configuration = ExchangeConfiguration.defaultBuilder()
                .ordersProcessingCfg(OrdersProcessingConfiguration.builder()
                        .riskProcessingMode(OrdersProcessingConfiguration.RiskProcessingMode.MATCHING_ONLY)
                        .marginTradingMode(OrdersProcessingConfiguration.MarginTradingMode.MARGIN_TRADING_DISABLED)
                        .build())
                .performanceCfg(PerformanceConfiguration.latencyPerformanceBuilder()
                        .matchingEnginesNum(1)
                        .riskEnginesNum(0)
                        .directMatchingOnlyPipeline(true)
                        .msgsInGroupLimit(startupConfiguration.matcherGroupSize())
                        .maxGroupDurationNs(startupConfiguration.matcherGroupNanos())
                        .waitStrategy(CoreWaitStrategy.BLOCKING).build())
                .initStateCfg(initialState)
                .serializationCfg(SerializationConfiguration.builder()
                        .enableJournaling(false)
                        .serializationProcessorFactory(/* 此回调不读取的上一步结果或按现有约定忽略的异常，具体含义见所在语句。 */ ignored ->
                                new ShardSerializationProcessor(serializationProcessor, shardId))
                        .build())
                .build();
        engines[shardId] = new SynchronousMatchingEngine(configuration);
    }

    /**
     * 校验品种字符串并预留稳定路由编号，返回目标 Matcher 分片；预留路由不等于该分片原生品种已注册。
     * @param instrumentId 外部永久数字品种字符串
     */
    public int matcherShardId(String instrumentId) {
        if (instrumentId == null || instrumentId.isBlank()) throw new IllegalArgumentException("matcher instrumentId is required");
        return topology.matcherShardId(reserveSymbolId(instrumentId));
    }
    /**
     * 把外部数字品种字符串解析成永久编号，再走当前线程的原生品种注册路径。
     * @param instrumentId 外部永久数字品种字符串
     */
    private int ensureSymbol(String instrumentId) {
        return ensureSymbol(permanentInstrumentId(instrumentId));
    }

    /**
     * 先查本线程上下文的注册集合，未注册则建立数字字符串路由并向所属引擎注册零费率、单位缩放的撮合品种；接受首次成功或已存在后才缓存。真实业务费率与资金由账户侧负责。
     * @param symbolId 永久原生品种编号
     */
    private int ensureSymbol(int symbolId) {
        if (symbolId <= 0) throw new IllegalArgumentException("positive instrumentId is required");
        // 当前写者独占的已完成原生注册集合。
        var registeredSymbols = currentCommandScope().registeredSymbols;
        if (registeredSymbols.contains(symbolId)) return symbolId;
        // 当前正在登记、规范化或查询的品种字符串。
        String instrumentId = Integer.toString(symbolId);
        registerSymbolId(instrumentId);
        // 只服务撮合的零费率、单位缩放原生品种规格，资金业务由账户侧处理。
        CoreSymbolSpecification specification = CoreSymbolSpecification.builder()
                .symbolId(symbolId).type(SymbolType.CURRENCY_EXCHANGE_PAIR)
                .baseCurrency(stableId("BASE:" + instrumentId)).quoteCurrency(stableId("QUOTE:" + instrumentId))
                .baseScaleK(1).quoteScaleK(1).makerFee(0).takerFee(0).marginBuy(0).marginSell(0).build();
        // 原生注册品种的返回码，成功或已经存在都可缓存为已注册。
        CommandResultCode result = engine(symbolId).registerSymbol(specification);
        if (result != CommandResultCode.SUCCESS
                && result != CommandResultCode.SYMBOL_MGMT_SYMBOL_ALREADY_EXISTS) {
            throw new IllegalStateException("failed to add exchange-core instrumentId " + instrumentId + ": " + result);
        }
        registeredSymbols.add(symbolId);
        return symbolId;
    }

    /**
     * 通过共享 InstrumentIds 契约解析数字字符串为永久正品种编号，不使用本地自增或名称哈希。
     * @param instrumentId 外部永久数字品种字符串
     */
    private int permanentInstrumentId(String instrumentId) {
        return com.surprising.product.api.utils.InstrumentIds.parse(instrumentId);
    }

    /**
     * 已有字符串路由直接读取并发字典，新路由进入双向表冲突检查；只预留映射，不触碰原生订单簿。
     * @param instrumentId 外部永久数字品种字符串
     */
    private int reserveSymbolId(String instrumentId) {
        // 已登记路由直接读并发字典；新路由通过反向表 putIfAbsent 检查永久编号冲突。
        Integer existing = symbols.get(instrumentId);
        return existing != null ? existing : registerSymbolId(instrumentId);
    }

    /**
     * 解析永久品种编号，先用反向表拒绝同编号不同字符串，再按原字符串登记正向路由；没有全局 synchronized 块，依赖并发表原子 putIfAbsent。
     * @param instrumentId 外部永久数字品种字符串
     */
    private int registerSymbolId(String instrumentId) {
        // 并发路由表中已经登记的永久品种编号。
        Integer existing = symbols.get(instrumentId);
        if (existing != null) return existing;
        // 当前品种的永久原生编号。
        int symbolId = permanentInstrumentId(instrumentId);
        // 同一永久编号已登记的原字符串，用来检查冲突。
        String collision = symbolNames.putIfAbsent(symbolId, instrumentId);
        if (collision != null && !collision.equals(instrumentId)) {
            throw new IllegalStateException("stable matcher instrumentId id collision: " + instrumentId + '/' + collision);
        }
        symbols.putIfAbsent(instrumentId, symbolId);
        return symbolId;
    }

    /**
     * 要求引擎数组已建立后按品种路由取分片实例；此方法不再单独检查该元素已激活或调用线程。
     * @param symbolId 永久原生品种编号
     */
    private SynchronousMatchingEngine engine(int symbolId) {
        if (engines == null) throw new IllegalStateException("matcher engines are not active");
        return engines[topology.matcherShardId(symbolId)];
    }

    /**
     * 按字符执行固定的 FNV 风格 int 哈希并清掉符号位，仅给原生占位资产编号使用；不保证无碰撞，也不排除结果为零。
     * @param value 待计算占位资产编号的字符串
     */
    private static int stableId(String value) {
        // 占位资产字符串的固定 int 哈希累加值。
        int hash = 0x811c9dc5;
        for (/* 当前批次项或盘口档位的下标。 */ int index = 0; index < value.length(); index++) {
            hash ^= value.charAt(index);
            hash *= 0x01000193;
        }
        return hash & 0x7fffffff;
    }

    /**
     * Core 与原生活动订单对账时使用的逐字段投影；只比较订单号、用户、品种、方向、价格、总量、已成交量和买单保留界限，不含资金账户或 FIFO 链接。
     * @param symbolId 永久原生品种编号
     * @param orderId 当前命令关联的订单号，证据校验允许零
     * @param userId 请求或对账订单所属用户号
     * @param action 对账订单的原生买卖方向
     * @param price 对账订单的撮合价
     * @param size 原始订单数量步数
     * @param filled 原生已成交数量步数
     * @param reserveBidPrice 原生买单保留界限，对账时取 Core 撮合价
     */
    private record ReconciledOrder(
            int symbolId,
            long orderId,
            long userId,
            OrderAction action,
            long price,
            long size,
            long filled,
            long reserveBidPrice) {
    }

    /**
     * 一个分片已完成冷态快照或读取后的哈希结果，不携带模块字节。
     * @param shardId 目标全局 Matcher 分片号
     * @param engineHash 原生子模块报告折叠出的 int 引擎哈希
     */
    public record MatcherShardSnapshot(int shardId, int engineHash) {
        /**
         * 构造时只要求分片号非负，哈希值允许任意 int，拓扑上界由使用处检查。
         */
        public MatcherShardSnapshot {
            if (shardId < 0) throw new IllegalArgumentException("matcher shard id must be non-negative");
        }
    }

    /**
     * 独立调用者在快照捕获返回后关闭；依次读取非故障引擎哈希并关闭，汇总关闭异常后清空数组并抛出首个异常；哈希读取与关闭共用 try，前者抛出会跳过该实例 close，不承诺所有资源必已关闭。
     */
    private void stop() {
        if (engines != null) {
            // 关闭循环遇到的首个运行异常，后续异常作为 suppressed 附上。
            RuntimeException failure = null;
            for (SynchronousMatchingEngine /* 当前遍历或关闭的同步引擎实例。 */ matcher : engines) {
                try {
                    if (matcherFailure.get() == null) matcher.stateHashReport();
                    matcher.close();
                } catch (/* 关闭流程中本次出现的运行异常，会保留为首个或附加异常。 */ RuntimeException closeFailure) {
                    if (failure == null) failure = closeFailure;
                    else failure.addSuppressed(closeFailure);
                }
            }
            engines = null;
            if (failure != null) throw failure;
        }
    }

    /**
     * 只关闭指定已存在的引擎，未启动时直接返回；无故障时先取哈希，再 close 并清该槽。任一步抛异常会保留该数组元素，调用方负责后续退出。
     * @param shardId 目标全局 Matcher 分片号
     */
    public void closeShard(int shardId) {
        // 当前引擎数组引用，整体未激活或已停止时为空。
        SynchronousMatchingEngine[] active = engines;
        if (active == null) return;
        if (shardId < 0 || shardId >= active.length) {
            throw new IllegalArgumentException("matcher shard is outside configured topology");
        }
        // 当前遍历或关闭的同步引擎实例。
        SynchronousMatchingEngine matcher = active[shardId];
        if (matcher == null) return;
        if (matcherFailure.get() == null) matcher.stateHashReport();
        matcher.close();
        active[shardId] = null;
    }

    /**
     * 执行整体停止流程，传播引擎关闭异常。
     */
    @Override
    public void close() {
        stop();
    }

    /**
     * 仅拆一层带非空 cause 的 CompletionException，其他异常或空 cause 保持原值。
     * @param failure 需要拆开一层 CompletionException 的异常
     */
    private static Throwable unwrap(Throwable failure) {
        return failure instanceof java.util.concurrent.CompletionException && failure.getCause() != null
                ? failure.getCause() : failure;
    }

    /**
     * 按确定性顺序执行控制撤单需要的最小身份，不携带账户余额或撤单执行状态。
     * @param orderId 当前命令关联的订单号，证据校验允许零
     * @param userId 请求或对账订单所属用户号
     * @param instrumentId 外部永久数字品种字符串
     */
    public record CancellationOrder(long orderId, long userId, int symbolId) {
        public CancellationOrder(long orderId, long userId, String instrumentId) {
            this(orderId, userId, com.surprising.product.api.utils.InstrumentIds.parse(instrumentId));
        }
        /**
         * 检查订单号和用户号为正，品种字符串非空白；不在这里解析或规范品种编号。
         */
        public CancellationOrder {
            if (orderId <= 0 || userId <= 0 || symbolId <= 0) {
                throw new IllegalArgumentException("invalid cancellation order");
            }
        }
    }



    /**
     * 从非空模块列表读取最大原生持久化序号；空列表明确抛异常，不据此递增业务证据序号。
     * @param modules 需要读取最大原生持久化序号的非空模块列表
     */
    private static long maxMatcherSequence(List<InMemorySerializationProcessor.SerializedModule> modules) {
        if (modules.isEmpty()) throw new java.util.NoSuchElementException("modules is empty");
        // 已扫描模块中的最大原生持久化序号。
        long max = Long.MIN_VALUE;
        for (/* 当前遍历数组或模块列表的下标。 */ int i = 0; i < modules.size(); i++) {
            // 当前模块保存的原生持久化序号。
            long seq = modules.get(i).sequence();
            if (seq > max) {
                max = seq;
            }
        }
        return max;
    }

    private static final class ShardSerializationProcessor implements ISerializationProcessor {
    /** 真正负责快照存储和日志回放的原序列化处理器。 */
    private final ISerializationProcessor delegate;
    /** 此独立引擎在全局 Matcher 拓扑中的非负分片编号。 */
    private final int shardId;

    /**
     * 要求委托存在且全局分片编号非负；编号上界由组装方拓扑保证，包装器不另检查。
     * @param delegate 原序列化处理器实例
     * @param shardId 全局撮合分片编号
     */
    ShardSerializationProcessor(ISerializationProcessor delegate, int shardId) {
        if (delegate == null || shardId < 0) throw new IllegalArgumentException("invalid shard serializer");
        this.delegate = delegate;
        this.shardId = shardId;
    }

    /**
     * 撮合路由模块必须使用本地实例零，转换为本包装器全局分片号；其他模块类型保留原实例编号。
     * @param type 当前序列化模块类型
     * @param localInstance 原引擎给出的本地模块实例编号，撮合模块必须为零
     */
    private int globalInstance(SerializedModuleType type, int localInstance) {
        if (type == SerializedModuleType.MATCHING_ENGINE_ROUTER) {
            if (localInstance != 0) throw new IllegalStateException("synchronous matcher local module must be zero");
            return shardId;
        }
        return localInstance;
    }

    /**
     * 先映射当前模块实例编号，再把同一快照、水位、时间和写入器交给原处理器，返回委托写入结果。
     * @param snapshotId 本次模块所属快照编号
     * @param sequence 原引擎模块保存的序号水位
     * @param timestampNs 原模块记录时间，纳秒
     * @param type 当前序列化模块类型
     * @param instanceId 待映射的本地模块实例编号
     * @param writer 原模块内容写入函数
     */
    @Override
    public boolean storeData(long snapshotId, long sequence, long timestampNs, SerializedModuleType type,
                             int instanceId, WriteBytesMarshallable writer) {
        return delegate.storeData(snapshotId, sequence, timestampNs, type,
                globalInstance(type, instanceId), writer);
    }

    /**
     * 按同一实例映射规则读取模块，业务对象解析函数原样交给委托。
     * @param <T> 模块载荷恢复出的业务对象类型
     * @param snapshotId 本次模块所属快照编号
     * @param type 当前序列化模块类型
     * @param instanceId 待映射的本地模块实例编号
     * @param reader 从模块字节恢复业务对象的函数
     */
    @Override
    public <T> T loadData(long snapshotId, SerializedModuleType type, int instanceId,
                          Function<BytesIn, T> reader) {
        return delegate.loadData(snapshotId, type, globalInstance(type, instanceId), reader);
    }

    /**
     * 按同一实例映射规则向委托检查模块是否存在，避免检查本地零号造成跨分片混淆。
     * @param snapshotId 本次模块所属快照编号
     * @param type 当前序列化模块类型
     * @param instanceId 待映射的本地模块实例编号
     */
    @Override
    public boolean checkSnapshotExists(long snapshotId, SerializedModuleType type, int instanceId) {
        return delegate.checkSnapshotExists(snapshotId, type, globalInstance(type, instanceId));
    }

    /**
     * 把原命令、日志序号和批次结束标记原样委托，不在此复制命令或重排日志。
     * @param cmd 要写入原引擎日志的命令
     * @param dSeq 原处理器使用的日志序号
     * @param eob 是否本批次最后一条日志命令
     */
    @Override public void writeToJournal(OrderCommand cmd, long dSeq, boolean eob) throws IOException {
        delegate.writeToJournal(cmd, dSeq, eob);
    }
    /**
     * 把原启用序号和引擎 API 交给委托，日志启用边界沿用原处理器。
     * @param afterSeq 交给委托的日志启用序号边界
     * @param api 接收日志回放命令的原引擎 API
     */
    @Override public void enableJournaling(long afterSeq, ExchangeApi api) {
        delegate.enableJournaling(afterSeq, api);
    }
    /**
     * 直接返回委托发现的快照点有序表，不复制或额外固定为只读。
     */
    @Override public NavigableMap<Long, SnapshotDescriptor> findAllSnapshotPoints() {
        return delegate.findAllSnapshotPoints();
    }
    /**
     * 把指定快照及序号范围交给委托分段回放，范围包含规则由原处理器决定。
     * @param snapshotId 本次模块所属快照编号
     * @param seqFrom 分段回放起始序号，边界规则沿用委托
     * @param seqTo 分段回放结束序号，边界规则沿用委托
     * @param api 接收日志回放命令的原引擎 API
     */
    @Override public void replayJournalStep(long snapshotId, long seqFrom, long seqTo, ExchangeApi api) {
        delegate.replayJournalStep(snapshotId, seqFrom, seqTo, api);
    }
    /**
     * 按初始状态配置回放委托日志，并返回其报告的回放序号。
     * @param initialState 全量回放使用的快照及启动状态配置
     * @param api 接收日志回放命令的原引擎 API
     */
    @Override public long replayJournalFull(InitialStateConfiguration initialState, ExchangeApi api) {
        return delegate.replayJournalFull(initialState, api);
    }
    /**
     * 由委托完成全量日志回放后启用日志；保留原接口方法名称和行为。
     * @param initialState 全量回放使用的快照及启动状态配置
     * @param api 接收日志回放命令的原引擎 API
     */
    @Override public void replayJournalFullAndThenEnableJouraling(
            InitialStateConfiguration initialState, ExchangeApi api) {
        delegate.replayJournalFullAndThenEnableJouraling(initialState, api);
    }
}

    static final class MatcherEvidenceLedger {

    /** 每个逻辑分片在序号数组中占用的十六个 long 间隔，只有首项存该分片值。 */
    private static final int CACHE_LINE_LONGS = 16;
    /** 以 acquire/release 访问 long 数组元素的 VarHandle，未使用 CAS 协调同分片多写者。 */
    private static final VarHandle LONGS = MethodHandles.arrayElementVarHandle(long[].class);

    /** 本产品线撮合分片拓扑，用于检查有效分片范围。 */
    private final LaneTopology topology;
    /** 实际撮合分片数加一个控制分片。 */
    private final int shardCount;
    /** 各业务分片已经分配的序号，恢复时至少达到 sequenceFloor。 */
    private final long[] issuedSequences;
    /** 各业务分片已经绑定或直接发布证据的序号，快照导出此数组。 */
    private final long[] shardSequences;
    /** 本轮运行已观察到的各实际原生分片序号，恢复构造不从历史进度填入。 */
    private final long[] shardNativeSequences;

    /**
     * 以零下限和从控制分片到各撮合分片的全零进度创建新证据账本。
     * @param topology 包含撮合分片数量的有效 Lane 拓扑
     */
    MatcherEvidenceLedger(LaneTopology topology) {
        if (topology == null) throw new IllegalArgumentException("matcher topology is required");
        this.topology = topology;
        this.shardCount = topology.matchingEngineCount() + 1;
        int paddedLength = Math.multiplyExact(shardCount, CACHE_LINE_LONGS);
        this.shardSequences = new long[paddedLength];
        this.shardNativeSequences = new long[paddedLength];
        this.issuedSequences = new long[paddedLength];
    }

    /**
     * 按拓扑创建留有间隔的序号数组，恢复每个分片已发布进度，已发号位置取给定下限与原进度较大值；原生序号数组从零开始。输入进度可以不按编号排列，但分片必须齐全且不能重复。
     * @param topology 包含撮合分片数量的有效 Lane 拓扑
     * @param sequenceFloor 仅恢复发号数组使用的非负下限，不直接替换各分片已发布进度
     * @param progress 控制分片及所有撮合分片的完整进度列表
     */
    MatcherEvidenceLedger(LaneTopology topology, long sequenceFloor, List<ShardProgress> progress) {
        if (topology == null || sequenceFloor < 0 || progress == null
                || progress.size() != topology.matchingEngineCount() + 1) {
            throw new IllegalArgumentException("invalid matcher evidence restore");
        }
        this.topology = topology;
        this.shardCount = progress.size();
        // 逻辑分片数乘十六后的数组容量，使用精确乘法检查溢出。
        int paddedLength = Math.multiplyExact(shardCount, CACHE_LINE_LONGS);
        this.shardSequences = new long[paddedLength];
        this.shardNativeSequences = new long[paddedLength];
        this.issuedSequences = new long[paddedLength];
        // 记录哪些逻辑分片已经装入，检测重复或缺失。
        boolean[] restored = new boolean[progress.size()];
        for (ShardProgress /* 当前需要恢复的分片及其已发布序号。 */ shard : progress) {
            // 当前逻辑分片的零起点下标，负一控制分片映射到零。
            int index = index(shard.matcherShardId());
            if (restored[index]) throw new IllegalArgumentException("duplicate matcher shard progress");
            restored[index] = true;
            // 当前逻辑分片在留间隔数组中的实际偏移。
            int offset = offset(index);
            shardSequences[offset] = shard.matcherSequence();
            issuedSequences[offset] = Math.max(sequenceFloor, shard.matcherSequence());
        }
        for (boolean /* 当前分片是否已经从输入进度恢复。 */ present : restored) {
            if (!present) throw new IllegalArgumentException("incomplete matcher shard progress");
        }
    }

    /**
     * 为指定业务分片递增已发号序号并发布，精确加法溢出时报错；发号不等于结果已发布，依赖同一分片单所有者调用，不是多生产者 CAS 发号器。
     * @param matcherShardId 业务证据所属分片，控制命令使用负一
     */
    long nextSequence(int matcherShardId) {
        // 当前逻辑分片在留间隔数组中的实际偏移。
        int offset = offset(index(matcherShardId));
        // 在当前已发号值上精确加一后的新序号。
        long next = Math.incrementExact((long) LONGS.getAcquire(issuedSequences, offset));
        LONGS.setRelease(issuedSequences, offset, next);
        return next;
    }

    /**
     * 按实际订单簿分片严格推进本轮原生序号，用于控制命令中已发生的原生调用；不改变业务证据发布进度。
     * @param shardId 此次实际原生控制调用所属的订单簿分片
     * @param nativeSequence 本次要严格推进的原生 exchange-core 序号
     */
    void recordControlNative(int shardId, long nativeSequence) {
        advanceStrictly(shardNativeSequences, index(shardId), nativeSequence,
                "matcher shard native sequence is not strictly increasing");
    }

    /**
     * 聚合控制撤单已经逐项登记过原生序号；这里只推进控制分片的业务证据，再绑定原命令身份。
     * @param coreSequence 控制命令的 Core 序号
     * @param commandId 原命令非空 UUID
     * @param sequence 要发布的控制业务序号，必须严格大于原已发布值
     * @param result 已聚合的控制结果或合成结果
     */
    OrderMatchingResult.Control bindControl(long coreSequence, java.util.UUID commandId,
                                   long sequence, OrderMatchingResult.Control result) {
        if (commandId == null || result == null) throw new IllegalArgumentException("matcher evidence is required");
        advanceStrictly(shardSequences, index(-1), sequence,
                "matcher shard sequence is not strictly increasing");
        return result.bindEvidenceInPlace(coreSequence, commandId.getMostSignificantBits(),
                commandId.getLeastSignificantBits(), sequence);
    }

    /**
     * 检查结果和目标非空，正原生序号先按实际订单簿分片推进，再推进业务证据分片并向现有结算事件直接发布原生成交或撤单结果。失败不自动回退已写进度。
     * @param coreSequence 该结果对应的 Core 命令序号
     * @param commandIdMsb 原命令 UUID 高六十四位
     * @param commandIdLsb 原命令 UUID 低六十四位
     * @param orderId 该事实关联的订单编号
     * @param sequence 要发布的业务分片证据序号，必须严格大于原已发布值
     * @param matcherShardId 业务证据所属分片，控制命令使用负一
     * @param nativeMatcherShardId 原生结果实际产生的订单簿分片，可与控制业务证据分片不同
     * @param result 要绑定或发布的非空原生结果/普通结果对象
     * @param target 当前命令持有租约的原 TradeSettlement，不新建账户结算状态
     */
    exchange.core2.core.common.MatcherResult publishNative(
            long coreSequence, long commandIdMsb, long commandIdLsb, long orderId,
            long sequence, int matcherShardId, int nativeMatcherShardId,
            exchange.core2.core.common.MatcherResult result,
            com.surprising.aeron.service.settlement.TradeSettlement target) {
        if (result == null || target == null)
            throw new IllegalArgumentException("native matcher publication is required");
        // 本次业务证据分片的数组逻辑下标。
        int matcherIndex = index(matcherShardId);
        // 当前结果携带的原生序号，仅正数进入严格原生进度检查。
        long nativeSequence = result.sequence();
        if (nativeSequence > 0) {
            advanceStrictly(shardNativeSequences, index(nativeMatcherShardId), nativeSequence,
                    "matcher shard native sequence is not strictly increasing");
        }
        advanceStrictly(shardSequences, matcherIndex, sequence,
                "matcher shard sequence is not strictly increasing");
        target.publishDirectNativeResult(result, coreSequence,
                commandIdMsb, commandIdLsb, orderId,
                sequence, matcherShardId);
        return result;
    }

    /**
     * 检查撤旧、放新结果和目标非空，以最终放新结果的原生序号推进实际分片，再推进业务证据并把两结果交给现有事件。不在此单独登记撤旧结果的原生序号，也不读取 aeronTimestamp，后续失败不回退已写序号。
     * @param coreSequence 该结果对应的 Core 命令序号
     * @param commandIdMsb 原命令 UUID 高六十四位
     * @param commandIdLsb 原命令 UUID 低六十四位
     * @param orderId 该事实关联的订单编号
     * @param sequence 要发布的业务分片证据序号，必须严格大于原已发布值
     * @param matcherShardId 业务证据所属分片，控制命令使用负一
     * @param nativeMatcherShardId 原生结果实际产生的订单簿分片，可与控制业务证据分片不同
     * @param cancellation 替换操作中已经发生的撤旧原生结果
     * @param result 要绑定或发布的非空原生结果/普通结果对象
     * @param target 当前命令持有租约的原 TradeSettlement，不新建账户结算状态
     */
    exchange.core2.core.common.MatcherResult publishReplacement(
            long coreSequence, long commandIdMsb, long commandIdLsb, long orderId,
            long sequence, int matcherShardId, int nativeMatcherShardId,
            exchange.core2.core.common.MatcherResult cancellation,
            exchange.core2.core.common.MatcherResult result,
            com.surprising.aeron.service.settlement.TradeSettlement target) {
        if (cancellation == null || result == null || target == null)
            throw new IllegalArgumentException("native matcher replacement publication is required");
        // 当前结果携带的原生序号，仅正数进入严格原生进度检查。
        long nativeSequence = result.sequence();
        if (nativeSequence > 0) {
            advanceStrictly(shardNativeSequences, index(nativeMatcherShardId), nativeSequence,
                    "matcher shard native sequence is not strictly increasing");
        }
        advanceStrictly(shardSequences, index(matcherShardId), sequence,
                "matcher shard sequence is not strictly increasing");
        target.publishDirectNativeReplacement(cancellation, result, coreSequence,
                commandIdMsb, commandIdLsb, orderId,
                sequence, matcherShardId);
        return result;
    }

    /**
     * 检查结果和目标非空，推进正原生序号及业务证据序号，再将结果和批次下标交给现有事件；本方法不额外检查目标批次下标，也不读取 aeronTimestamp，失败不回退已写序号。
     * @param coreSequence 该结果对应的 Core 命令序号
     * @param commandIdMsb 原命令 UUID 高六十四位
     * @param commandIdLsb 原命令 UUID 低六十四位
     * @param orderId 该事实关联的订单编号
     * @param sequence 要发布的业务分片证据序号，必须严格大于原已发布值
     * @param matcherShardId 业务证据所属分片，控制命令使用负一
     * @param nativeMatcherShardId 原生结果实际产生的订单簿分片，可与控制业务证据分片不同
     * @param result 要绑定或发布的非空原生结果/普通结果对象
     * @param index 逻辑数组下标，或 publishBatchItem 中目标批次子项下标
     * @param target 当前命令持有租约的原 TradeSettlement，不新建账户结算状态
     */
    exchange.core2.core.common.MatcherResult publishBatchItem(
            long coreSequence, long commandIdMsb, long commandIdLsb, long orderId,
            long sequence, int matcherShardId, int nativeMatcherShardId,
            exchange.core2.core.common.MatcherResult result, int index,
            com.surprising.aeron.service.settlement.TradeSettlement target) {
        if (result == null || target == null)
            throw new IllegalArgumentException("native matcher batch publication is required");
        // 当前结果携带的原生序号，仅正数进入严格原生进度检查。
        long nativeSequence = result.sequence();
        if (nativeSequence > 0) {
            advanceStrictly(shardNativeSequences, index(nativeMatcherShardId), nativeSequence,
                    "matcher shard native sequence is not strictly increasing");
        }
        advanceStrictly(shardSequences, index(matcherShardId), sequence,
                "matcher shard sequence is not strictly increasing");
        target.publishDirectNativeBatchItem(result, index, coreSequence,
                commandIdMsb, commandIdLsb, orderId,
                sequence, matcherShardId);
        return result;
    }

    /**
     * 要求拒绝码非空且目标存在，只推进业务证据分片，再向现有事件发布拒绝身份；不推进原生序号数组。
     * @param coreSequence 该结果对应的 Core 命令序号
     * @param commandIdMsb 原命令 UUID 高六十四位
     * @param commandIdLsb 原命令 UUID 低六十四位
     * @param orderId 该事实关联的订单编号
     * @param sequence 要发布的业务分片证据序号，必须严格大于原已发布值
     * @param matcherShardId 业务证据所属分片，控制命令使用负一
     * @param resultCode 要保存的非空拒绝结果码
     * @param target 当前命令持有租约的原 TradeSettlement，不新建账户结算状态
     */
    void publishRejection(
            long coreSequence, long commandIdMsb, long commandIdLsb, long orderId,
            long sequence, int matcherShardId, String resultCode,
            com.surprising.aeron.service.settlement.TradeSettlement target) {
        if (resultCode == null || resultCode.isBlank() || target == null)
            throw new IllegalArgumentException("matcher rejection publication is required");
        advanceStrictly(shardSequences, index(matcherShardId), sequence,
                "matcher shard sequence is not strictly increasing");
        target.publishDirectRejection(resultCode, coreSequence,
                commandIdMsb, commandIdLsb, orderId,
                sequence, matcherShardId);
    }

    /**
     * 逐个以 acquire 读取已发布的业务分片进度，按负一控制分片到各撮合分片顺序返回只读列表。跨分片一致切面由调用方原快照栅栏保证，本方法不锁住全部所有者线程。
     */
    List<ShardProgress> snapshot() {
        // 本次快照或初始零进度的列表构建器，返回前固定列表。
        ArrayList<ShardProgress> progress = new ArrayList<>(shardCount);
        for (/* 当前逻辑分片的零起点下标，负一控制分片映射到零。 */ int index = 0; index < shardCount; index++) {
            // 当前逻辑分片在留间隔数组中的实际偏移。
            int offset = offset(index);
            progress.add(new ShardProgress(index - 1,
                    (long) LONGS.getAcquire(shardSequences, offset)));
        }
        return List.copyOf(progress);
    }

    /**
     * 检查分片编号在控制负一到实际撮合分片范围内，再加一转成数组逻辑下标。
     * @param matcherShardId 业务证据所属分片，控制命令使用负一
     */
    private int index(int matcherShardId) {
        if (matcherShardId < -1 || matcherShardId >= topology.matchingEngineCount()) {
            throw new IllegalArgumentException("matcher shard is outside configured topology");
        }
        return matcherShardId + 1;
    }

    /**
     * 在单所有者约定下读取原值，要求新值严格更大，再以 release 发布；不是 CAS 重试，也不替调用方协调两个线程同时写同一分片。
     * @param values 指定分片的原生或业务序号数组
     * @param index 逻辑数组下标，或 publishBatchItem 中目标批次子项下标
     * @param next 本次期望写入的严格递增序号
     * @param message 序号倒退或重复时使用的错误说明
     */
    private static void advanceStrictly(long[] values, int index, long next, String message) {
        // 当前逻辑分片在留间隔数组中的实际偏移。
        int offset = offset(index);
        // 从对应数组以 acquire 取得的已发布原值。
        long previous = (long) LONGS.getAcquire(values, offset);
        if (next <= previous) {
            throw new IllegalStateException(message + " shard=" + (index - 1)
                    + " previous=" + previous + " next=" + next);
        }
        // 每个撮合分片只有一个所有者写线程，以 release 发布即可供原快照栅栏读取；
        // 这里不为每条撮合命令增加 CAS，也不支持两个线程同时写同一分片。
        LONGS.setRelease(values, offset, next);
    }

    /**
     * 把逻辑分片下标乘十六转换为实际数组偏移，给相邻分片写入留间隔。
     * @param index 逻辑数组下标，或 publishBatchItem 中目标批次子项下标
     */
    private static int offset(int index) {
        return index * CACHE_LINE_LONGS;
    }

}

}
