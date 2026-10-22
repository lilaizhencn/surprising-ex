package com.surprising.aeron.service.matcher;

import com.surprising.aeron.config.CoreConfiguration;
import com.surprising.aeron.config.CoreRuntimeConfiguration;
import com.surprising.aeron.config.LaneTopology;
import com.surprising.aeron.exception.FatalMatchingDivergenceException;
import com.surprising.aeron.model.CoreOrderState;
import com.surprising.aeron.model.TradingCoreState;
import com.surprising.aeron.protocol.CoreBookLevelView;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.aeron.service.order.admission.ResolvedPlaceOrder;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;
import org.eclipse.collections.impl.set.mutable.primitive.LongHashSet;

/**
 * Matcher 与 exchange-core 同步订单簿之间的接入边界。按 instrumentId 路由到独立分片，转换下单、撤单、改单并绑定命令证据；成交事实交给账户结算，余额和持仓不由这里计算。分片调用由所属 Matcher 线程串行执行，冷态启动、对账、哈希和快照需要调用方先建立相应静止边界；名称中的 Async 多数只包装同步结果，不自动切换线程。
 */
public final class DeterministicExchangeCoreAdapter implements AutoCloseable {

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
    /** 快照号到正在进行操作的并发表，同号请求共享底层 Future，完成后移除。 */
    private final ConcurrentHashMap<Long, SnapshotOperation> snapshotOperations = new ConcurrentHashMap<>();
    /**  启动配置或恢复快照确定的账户与 Matcher 分片拓扑。 返回本适配器启动或恢复时确定的线程分片拓扑。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private final LaneTopology topology;
    /** 只保存首个故障的跨线程标记，后续命令和完成结果据此停止。 */
    private final AtomicReference<Throwable> matcherFailure = new AtomicReference<>();
    /** 区分控制、业务和原生分片序号的证据账本，不是资金账本。 */
    private final MatcherEvidenceLedger matcherEvidence;
    /** 接收真正持久化动作的函数，默认立即执行，测试可控制执行与完成时机。 */
    private final Function<Supplier<CompletableFuture<CommandResultCode>>, CompletableFuture<CommandResultCode>>
            snapshotPersistence;
    /** 延迟恢复动作，整体激活完成后清空。 */
    private Runnable deferredActivation;
    /** 全体分片激活完成标记，用 volatile 向其他线程发布。 */
    private volatile boolean activationComplete;
    /** 已完成创建或恢复核对的分片计数，多分片最后一个负责发布全局激活完成。 */
    private final AtomicInteger activatedShardCount = new AtomicInteger();
    /** 等待分片恢复的 Matcher 快照，空白启动时为空。 */
    private MatcherSnapshot restoredSnapshot;
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
    public DeterministicExchangeCoreAdapter() {
        this(true);
    }

    /**
     * 创建空白适配器，可立即创建全部订单簿，也可留给后续分片激活。
     * @param startImmediately 是否在构造时同步激活全部引擎
     */
    public DeterministicExchangeCoreAdapter(boolean startImmediately) {
        this(CoreConfiguration.standaloneLaneTopology(), startImmediately);
    }

    /**
     * 使用账户运行时共用的显式拓扑创建空白 Matcher，不再自行读取配置。
     * @param topology 启动入口绑定并校验的不可变交易拓扑
     * @param startImmediately 是否立即创建引擎，假时留给后续激活
     */
    public DeterministicExchangeCoreAdapter(LaneTopology topology, boolean startImmediately) {
        this(topology, startImmediately, CoreRuntimeConfiguration.standalone());
    }

    public DeterministicExchangeCoreAdapter(LaneTopology topology, boolean startImmediately,
            CoreRuntimeConfiguration configuration) {
        this(topology, startImmediately, /* 实际持久化动作在调用线程直接执行。 */ submission -> submission.get(), configuration);
    }

    /**
     * 立即创建空白订单簿，并注入快照执行函数，便于测试控制真实持久化动作的完成时机。
     * @param snapshotPersistence 接收真实持久化动作并返回完成 Future 的非空函数
     */
    DeterministicExchangeCoreAdapter(
            Function<Supplier<CompletableFuture<CommandResultCode>>, CompletableFuture<CommandResultCode>>
                    snapshotPersistence) {
        this(CoreConfiguration.standaloneLaneTopology(), true, snapshotPersistence, CoreRuntimeConfiguration.standalone());
    }

    /**
     * 接收拓扑、创建上下文和证据账本、保存非空快照函数；要求立即启动时同步构造全部引擎并标记已激活。
     * @param topology 启动入口绑定并校验的不可变交易拓扑
     * @param startImmediately 是否在构造时同步激活全部引擎
     * @param snapshotPersistence 接收真实持久化动作并返回完成 Future 的非空函数
     */
    private DeterministicExchangeCoreAdapter(
            LaneTopology topology,
            boolean startImmediately,
            Function<Supplier<CompletableFuture<CommandResultCode>>, CompletableFuture<CommandResultCode>>
                    snapshotPersistence, CoreRuntimeConfiguration configuration) {
        this.startupConfiguration = java.util.Objects.requireNonNull(configuration);
        this.topology = java.util.Objects.requireNonNull(topology, "topology");
        initializeCommandScopes();
        this.matcherEvidence = new MatcherEvidenceLedger(topology);
        this.snapshotPersistence = java.util.Objects.requireNonNull(snapshotPersistence, "snapshotPersistence");
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
    public DeterministicExchangeCoreAdapter(
            TradingCoreState state,
            Iterable<CoreOrderState> activeOrders,
            long coreSequence,
            MatcherSnapshot snapshot) {
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
    public DeterministicExchangeCoreAdapter(
            TradingCoreState state,
            Iterable<CoreOrderState> activeOrders,
            long coreSequence,
            MatcherSnapshot snapshot,
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
    public DeterministicExchangeCoreAdapter(
            TradingCoreState state,
            Iterable<CoreOrderState> activeOrders,
            long coreSequence,
            MatcherSnapshot snapshot,
            boolean startImmediately,
            long expectedCoreBusinessStateHash) {
        this(state, activeOrders, coreSequence, snapshot, startImmediately,
                expectedCoreBusinessStateHash, CoreRuntimeConfiguration.standalone());
    }

    public DeterministicExchangeCoreAdapter(TradingCoreState state, Iterable<CoreOrderState> activeOrders,
            long coreSequence, MatcherSnapshot snapshot, boolean startImmediately,
            long expectedCoreBusinessStateHash, CoreRuntimeConfiguration configuration) {
        this.startupConfiguration = java.util.Objects.requireNonNull(configuration);
        this.snapshotPersistence = /* 注入函数收到的真实持久化动作，默认立即调用。 */ submission -> submission.get();
        if (snapshot == null || activeOrders == null) {
            throw new IllegalArgumentException("matcher snapshot is required");
        }
        try {
            snapshot.verifyCoreState(state, coreSequence, expectedCoreBusinessStateHash);
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
                    state, restoredOrders, coreSequence, snapshot);
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
            // 当前冷态读取或汇总出的引擎与订单簿哈希。
            StateHashes hashes = stateHashes(shardId);
            restoredShardHashes[shardId] = new MatcherShardSnapshot(
                    shardId, hashes.engineHash(), hashes.bookHash());
        }
        if (activatedShardCount.incrementAndGet() == topology.matchingEngineCount()) {
            if (restoredSnapshot != null) {
                // 当前冷态读取或汇总出的引擎与订单簿哈希。
                StateHashes hashes = aggregateStateHashes(List.of(restoredShardHashes));
                if (hashes.engineHash() != restoredSnapshot.engineStateHash()) {
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
     * 同步加载全部原生快照，逐单对账并比对汇总引擎哈希；state 参数在此方法体内不再读取，Core 元数据已在构造阶段检查。
     * @param state 对应恢复或快照点的 Core 状态，具体读取边界见方法说明
     * @param activeOrders 同一冻结点的 Core 活动订单序列
     * @param coreSequence 当前逻辑命令或冻结点的 Core 序号
     * @param snapshot 已有 Matcher 快照；启动路径为空时表示空白引擎
     */
    private void activateRestoredMatcher(TradingCoreState state, Iterable<CoreOrderState> activeOrders,
                                         long coreSequence, MatcherSnapshot snapshot) {
        start(snapshot);
        reconcileOpenOrdersAsync(activeOrders, coreSequence, snapshot.snapshotId(), "matcher restore").join();
        // 全部恢复订单对账后的汇总哈希，用来与快照比较。
        StateHashes restoredHashes = currentStateHashesAsync().join();
        if (restoredHashes.engineHash() != snapshot.engineStateHash()) {
            throw new FatalMatchingDivergenceException("matcher restore", coreSequence,
                    snapshot.snapshotId(), "restored exchange-core state hash mismatch"
                            + " (expectedEngine=" + snapshot.engineStateHash()
                            + ", actualEngine=" + restoredHashes.engineHash() + ')');
        }
    }

    /**
     * 按独立撮合输入注册品种、记用户、计算市价保护边界，再把买卖方向与订单类型转换后写入调用方提供的原生槽。使用当前命令的 Cluster 时间，不处理资金。
     * @param userId 请求或对账订单所属用户号
     * @param command 只读撮合输入，必须遵守其冻结及使用期限
     * @param target 调用方独占且尚未发布的原生结果槽或结算事件，具体类型决定用途
     */
    private exchange.core2.core.common.MatcherResult placeNative(long userId, CoreMatchingOrder command, exchange.core2.core.common.MatcherResult target) {
        // 当前品种的永久原生编号。
        int symbolId = ensureSymbol(command.instrumentId());
        recordUser(userId);
        // 市价保护收紧后的原生撮合价格，限价单保留原价。
        long price = protectedMarketPrice(symbolId, command.side(), command.orderType(),
                command.matchingPriceTicks(), command.maxSlippagePpm());
        return engine(symbolId).placeInto(target,
                currentCommandScope().aeronTimestamp, command.orderId(), 0,
                price, command.matchingPriceTicks(), command.quantitySteps(),
                command.side() == CoreOrderSide.BUY ? OrderAction.BID : OrderAction.ASK,
                orderType(command), symbolId, userId);
    }

    /**
     * 直接读取已完成准入的订单输入，按产品线滑点规则计算市价边界并填入原生槽，避免重建 CoreMatchingOrder。
     * @param userId 请求或对账订单所属用户号
     * @param command 只读撮合输入，必须遵守其冻结及使用期限
     * @param target 调用方独占且尚未发布的原生结果槽或结算事件，具体类型决定用途
     */
    private exchange.core2.core.common.MatcherResult placeNative(long userId, ResolvedPlaceOrder command, exchange.core2.core.common.MatcherResult target) {
        // 当前品种的永久原生编号。
        int symbolId = ensureSymbol(command.instrument().instrumentIdValue());
        recordUser(userId);
        // 市价保护收紧后的原生撮合价格，限价单保留原价。
        long price = protectedMarketPrice(symbolId, command.side(), command.orderType(),
                command.matchingPriceTicks(), CoreMatchingOrder.slippagePpm(command.instrument().contractType().productLine()));
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
            com.surprising.aeron.service.order.state.OrderRuntime command, exchange.core2.core.common.MatcherResult target) {
        // 当前品种的永久原生编号。
        int symbolId = ensureSymbol(command.instrument().instrumentIdValue());
        recordUser(userId);
        // 市价保护收紧后的原生撮合价格，限价单保留原价。
        long price = protectedMarketPrice(symbolId, command.side(), command.orderType(),
                command.matchingPriceTicks(), CoreMatchingOrder.slippagePpm(command.productLine()));
        return engine(symbolId).placeInto(target,
                currentCommandScope().aeronTimestamp, command.orderId(), 0,
                price, command.matchingPriceTicks(), command.remainingQuantitySteps(),
                command.side() == CoreOrderSide.BUY ? OrderAction.BID : OrderAction.ASK,
                orderType(command.orderType(), command.timeInForce()), symbolId, userId);
    }

    /**
     * 要求 UUID 非空，拆成两个 long 后进入冻结准入下单入口。
     * @param shardId 目标全局 Matcher 分片号
     * @param coreSequence 当前逻辑命令或冻结点的 Core 序号
     * @param commandId 逻辑命令 UUID
     * @param aeronTimestamp Cluster 提供的确定性业务时间戳
     * @param userId 请求或对账订单所属用户号
     * @param command 只读撮合输入，必须遵守其冻结及使用期限
     * @param target 调用方独占且尚未发布的原生结果槽或结算事件，具体类型决定用途
     */
    public exchange.core2.core.common.MatcherResult placeAdmittedDirect(
            int shardId, long coreSequence, java.util.UUID commandId, long aeronTimestamp,
            long userId, com.surprising.aeron.service.order.state.OrderRuntime command,
            com.surprising.aeron.service.settlement.MatcherSettlementEvent target) {
        if (commandId == null) throw new IllegalArgumentException("invalid direct matcher command");
        return placeAdmittedDirect(shardId, coreSequence, commandId.getMostSignificantBits(),
                commandId.getLeastSignificantBits(), aeronTimestamp, userId, command, target);
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
            long userId, com.surprising.aeron.service.order.state.OrderRuntime command,
            com.surprising.aeron.service.settlement.MatcherSettlementEvent target) {
        if (shardId < 0 || shardId >= topology.matchingEngineCount() || command == null || target == null)
            throw new IllegalArgumentException("invalid direct matcher command");
        command.requireFrozenMatcherInput(userId);
        validateCommandEvidence(coreSequence, commandIdMsb, commandIdLsb, command.orderId(), aeronTimestamp);
        // 当前线程可使用的独占命令上下文。
        MatcherCommandScope scope = beginSynchronousCommand(aeronTimestamp);
        try {
            return publishNativeMatcherResult(shardId, coreSequence, commandIdMsb, commandIdLsb, command.orderId(),
                    aeronTimestamp, placeNativeAdmitted(userId, command, target.nativeResultSlot(0)), target);
        } catch (/* 当前捕获的运行异常，按所在入口停止、记故障或完成失败结果。 */ RuntimeException exception) {
            matcherFailure.compareAndSet(null, exception);
            throw exception;
        } finally {
            scope.aeronTimestamp = 0;
            scope.active = false;
        }
    }

    /**
     * 把非空 UUID 拆为两个 long，转入已准入订单的直接下单入口。
     * @param shardId 目标全局 Matcher 分片号
     * @param coreSequence 当前逻辑命令或冻结点的 Core 序号
     * @param commandId 逻辑命令 UUID
     * @param aeronTimestamp Cluster 提供的确定性业务时间戳
     * @param userId 请求或对账订单所属用户号
     * @param command 只读撮合输入，必须遵守其冻结及使用期限
     * @param target 调用方独占且尚未发布的原生结果槽或结算事件，具体类型决定用途
     */
    public exchange.core2.core.common.MatcherResult placeDirect(
            int shardId, long coreSequence, java.util.UUID commandId, long aeronTimestamp,
            long userId, ResolvedPlaceOrder command,
            com.surprising.aeron.service.settlement.MatcherSettlementEvent target) {
        if (commandId == null) throw new IllegalArgumentException("invalid direct matcher command");
        return placeDirect(shardId, coreSequence, commandId.getMostSignificantBits(),
                commandId.getLeastSignificantBits(), aeronTimestamp, userId, command, target);
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
            long userId, ResolvedPlaceOrder command,
            com.surprising.aeron.service.settlement.MatcherSettlementEvent target) {
        if (shardId < 0 || shardId >= topology.matchingEngineCount() || command == null || target == null)
            throw new IllegalArgumentException("invalid direct matcher command");
        validateCommandEvidence(coreSequence, commandIdMsb, commandIdLsb, command.orderId(), aeronTimestamp);
        // 当前线程可使用的独占命令上下文。
        MatcherCommandScope scope = beginSynchronousCommand(aeronTimestamp);
        try {
            return publishNativeMatcherResult(shardId, coreSequence, commandIdMsb, commandIdLsb, command.orderId(),
                    aeronTimestamp, placeNative(userId, command, target.nativeResultSlot(0)), target);
        } catch (/* 当前捕获的运行异常，按所在入口停止、记故障或完成失败结果。 */ RuntimeException exception) {
            matcherFailure.compareAndSet(null, exception);
            throw exception;
        } finally {
            scope.aeronTimestamp = 0;
            scope.active = false;
        }
    }

    /**
     * 把非空 UUID 拆为两个 long，转入独立撮合输入的直接下单入口。
     * @param shardId 目标全局 Matcher 分片号
     * @param coreSequence 当前逻辑命令或冻结点的 Core 序号
     * @param commandId 逻辑命令 UUID
     * @param aeronTimestamp Cluster 提供的确定性业务时间戳
     * @param userId 请求或对账订单所属用户号
     * @param command 只读撮合输入，必须遵守其冻结及使用期限
     * @param target 调用方独占且尚未发布的原生结果槽或结算事件，具体类型决定用途
     */
    public exchange.core2.core.common.MatcherResult placeDirect(
            int shardId, long coreSequence, java.util.UUID commandId, long aeronTimestamp,
            long userId, CoreMatchingOrder command,
            com.surprising.aeron.service.settlement.MatcherSettlementEvent target) {
        if (commandId == null) throw new IllegalArgumentException("invalid direct matcher command");
        return placeDirect(shardId, coreSequence, commandId.getMostSignificantBits(),
                commandId.getLeastSignificantBits(), aeronTimestamp, userId, command, target);
    }

    /**
     * 检查分片与独立撮合输入，进入同步作用域后撮合并发布证据；捕获运行异常并清理作用域，不在这里提交账户状态。
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
            long userId, CoreMatchingOrder command,
            com.surprising.aeron.service.settlement.MatcherSettlementEvent target) {
        if (shardId < 0 || shardId >= topology.matchingEngineCount() || command == null || target == null)
            throw new IllegalArgumentException("invalid direct matcher command");
        validateCommandEvidence(coreSequence, commandIdMsb, commandIdLsb, command.orderId(), aeronTimestamp);
        // 当前线程可使用的独占命令上下文。
        MatcherCommandScope scope = beginSynchronousCommand(aeronTimestamp);
        try {
            return publishNativeMatcherResult(shardId, coreSequence, commandIdMsb, commandIdLsb, command.orderId(),
                    aeronTimestamp, placeNative(userId, command, target.nativeResultSlot(0)), target);
        } catch (/* 当前捕获的运行异常，按所在入口停止、记故障或完成失败结果。 */ RuntimeException exception) {
            matcherFailure.compareAndSet(null, exception);
            throw exception;
        } finally {
            scope.aeronTimestamp = 0;
            scope.active = false;
        }
    }

    /**
     * 把非空 UUID 拆开，转入带原生结果槽的直接撤单入口。
     * @param shardId 目标全局 Matcher 分片号
     * @param coreSequence 当前逻辑命令或冻结点的 Core 序号
     * @param commandId 逻辑命令 UUID
     * @param orderId 当前命令关联的订单号，证据校验允许零
     * @param aeronTimestamp Cluster 提供的确定性业务时间戳
     * @param userId 请求或对账订单所属用户号
     * @param instrumentId 外部永久数字品种字符串
     * @param target 调用方独占且尚未发布的原生结果槽或结算事件，具体类型决定用途
     */
    public exchange.core2.core.common.MatcherResult cancelDirect(
            int shardId, long coreSequence, java.util.UUID commandId, long orderId,
            long aeronTimestamp, long userId, String instrumentId,
            com.surprising.aeron.service.settlement.MatcherSettlementEvent target) {
        if (commandId == null) throw new IllegalArgumentException("invalid direct matcher cancellation");
        return cancelDirect(shardId, coreSequence, commandId.getMostSignificantBits(),
                commandId.getLeastSignificantBits(), orderId, aeronTimestamp, userId, instrumentId, target);
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
            long aeronTimestamp, long userId, String instrumentId,
            com.surprising.aeron.service.settlement.MatcherSettlementEvent target) {
        if (shardId < 0 || shardId >= topology.matchingEngineCount()
                || instrumentId == null || instrumentId.isBlank() || target == null)
            throw new IllegalArgumentException("invalid direct matcher cancellation");
        validateCommandEvidence(coreSequence, commandIdMsb, commandIdLsb, orderId, aeronTimestamp);
        // 当前线程可使用的独占命令上下文。
        MatcherCommandScope scope = beginSynchronousCommand(aeronTimestamp);
        try {
            return publishNativeMatcherResult(shardId, coreSequence, commandIdMsb, commandIdLsb, orderId,
                    aeronTimestamp, cancelNative(userId, orderId, instrumentId, target.nativeResultSlot(0)), target);
        } catch (/* 当前捕获的运行异常，按所在入口停止、记故障或完成失败结果。 */ RuntimeException exception) {
            matcherFailure.compareAndSet(null, exception);
            throw exception;
        } finally {
            scope.aeronTimestamp = 0;
            scope.active = false;
        }
    }

    /**
     * 把非空 UUID 拆开，转入先撤旧单再下新单的直接改单入口。
     * @param shardId 目标全局 Matcher 分片号
     * @param coreSequence 当前逻辑命令或冻结点的 Core 序号
     * @param commandId 逻辑命令 UUID
     * @param orderId 当前命令关联的订单号，证据校验允许零
     * @param aeronTimestamp Cluster 提供的确定性业务时间戳
     * @param userId 请求或对账订单所属用户号
     * @param originalOrderId 需要先撤掉的旧订单号
     * @param instrumentId 外部永久数字品种字符串
     * @param replacement 旧单撤销接受后才提交的新单准入输入
     * @param target 调用方独占且尚未发布的原生结果槽或结算事件，具体类型决定用途
     */
    public exchange.core2.core.common.MatcherResult replaceDirect(
            int shardId, long coreSequence, java.util.UUID commandId, long orderId,
            long aeronTimestamp, long userId, long originalOrderId, String instrumentId,
            ResolvedPlaceOrder replacement,
            com.surprising.aeron.service.settlement.MatcherSettlementEvent target) {
        if (commandId == null) throw new IllegalArgumentException("invalid direct matcher replacement");
        return replaceDirect(shardId, coreSequence, commandId.getMostSignificantBits(),
                commandId.getLeastSignificantBits(), orderId, aeronTimestamp, userId, originalOrderId,
                instrumentId, replacement, target);
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
            long aeronTimestamp, long userId, long originalOrderId, String instrumentId,
            ResolvedPlaceOrder replacement,
            com.surprising.aeron.service.settlement.MatcherSettlementEvent target) {
        if (shardId < 0 || shardId >= topology.matchingEngineCount()
                || instrumentId == null || instrumentId.isBlank() || replacement == null || target == null)
            throw new IllegalArgumentException("invalid direct matcher replacement");
        validateCommandEvidence(coreSequence, commandIdMsb, commandIdLsb, orderId, aeronTimestamp);
        // 当前线程可使用的独占命令上下文。
        MatcherCommandScope scope = beginSynchronousCommand(aeronTimestamp);
        try {
            // 先撤旧单产生的原生结果，占用独立槽避免新单覆盖。
            exchange.core2.core.common.MatcherResult cancellation =
                    cancelNative(userId, originalOrderId, instrumentId, target.replacementCancellationSlot());
            // 撤旧单接受后才产生的新单结果；旧单未接受时直接复用撤单结果。
            exchange.core2.core.common.MatcherResult result = nativeAccepted(cancellation)
                    ? placeNative(userId, replacement, target.nativeResultSlot(0)) : cancellation;
            // 根据原生结果品种计算的真实引擎分片，负一表示没有有效品种。
            int nativeShard = result.symbol() <= 0 ? shardId : topology.matcherShardId(result.symbol());
            if (nativeShard != shardId)
                throw new IllegalStateException("native matcher replacement crossed its evidence partition");
            return matcherEvidence.publishReplacement(coreSequence, commandIdMsb, commandIdLsb, orderId, aeronTimestamp,
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
            long userId, ResolvedPlaceOrder command,
            com.surprising.aeron.service.settlement.MatcherSettlementEvent target) {
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
            return matcherEvidence.publishBatchItem(coreSequence, commandId, command.orderId(),
                    aeronTimestamp, matcherEvidence.nextSequence(shardId), shardId, nativeShard,
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
            com.surprising.aeron.service.settlement.MatcherSettlementEvent target) {
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
            return matcherEvidence.publishBatchItem(coreSequence, commandId, orderId,
                    aeronTimestamp, matcherEvidence.nextSequence(shardId), shardId, nativeShard,
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
     * 要求 UUID 非空，拆分后进入原生结果的证据发布方法。
     * @param shardId 目标全局 Matcher 分片号
     * @param coreSequence 当前逻辑命令或冻结点的 Core 序号
     * @param commandId 逻辑命令 UUID
     * @param orderId 当前命令关联的订单号，证据校验允许零
     * @param aeronTimestamp Cluster 提供的确定性业务时间戳
     * @param result 当前原生或普通撮合结果，方法签名决定具体类型
     * @param target 调用方独占且尚未发布的原生结果槽或结算事件，具体类型决定用途
     */
    private exchange.core2.core.common.MatcherResult publishNativeMatcherResult(
            int shardId, long coreSequence, java.util.UUID commandId, long orderId,
            long aeronTimestamp, exchange.core2.core.common.MatcherResult result,
            com.surprising.aeron.service.settlement.MatcherSettlementEvent target) {
        if (commandId == null) throw new IllegalArgumentException("invalid direct matcher command");
        return publishNativeMatcherResult(shardId, coreSequence, commandId.getMostSignificantBits(),
                commandId.getLeastSignificantBits(), orderId, aeronTimestamp, result, target);
    }

    /**
     * 拒绝已有故障后的完成结果，核对有效原生分片与业务分片相符，再分配业务证据序号并写入目标事件；这里不执行账户结算或直接推进 Core 提交。
     * @param shardId 目标全局 Matcher 分片号
     * @param coreSequence 当前逻辑命令或冻结点的 Core 序号
     * @param commandIdMsb 逻辑命令 UUID 高六十四位
     * @param commandIdLsb 逻辑命令 UUID 低六十四位
     * @param orderId 当前命令关联的订单号，证据校验允许零
     * @param aeronTimestamp Cluster 提供的确定性业务时间戳
     * @param result 当前原生或普通撮合结果，方法签名决定具体类型
     * @param target 调用方独占且尚未发布的原生结果槽或结算事件，具体类型决定用途
     */
    private exchange.core2.core.common.MatcherResult publishNativeMatcherResult(
            int shardId, long coreSequence, long commandIdMsb, long commandIdLsb, long orderId,
            long aeronTimestamp, exchange.core2.core.common.MatcherResult result,
            com.surprising.aeron.service.settlement.MatcherSettlementEvent target) {
        // 先前记录的首个 Matcher 故障，存在时禁止发布后续完成结果。
        Throwable poison = matcherFailure.get();
        if (poison != null)
            throw new IllegalStateException("matcher completion discarded after fatal divergence", poison);
        // 根据原生结果品种计算的真实引擎分片，负一表示没有有效品种。
        int nativeShard = result.symbol() <= 0 ? -1 : topology.matcherShardId(result.symbol());
        if (nativeShard >= 0 && nativeShard != shardId)
            throw new IllegalStateException("native matcher result crossed its evidence partition");
        return matcherEvidence.publishNative(coreSequence, commandIdMsb, commandIdLsb, orderId, aeronTimestamp,
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
            long aeronTimestamp, long userId, long orderId, String instrumentId) {
        if (instrumentId == null || instrumentId.isBlank())
            throw new IllegalArgumentException("invalid direct matcher cancellation prefix");
        // 当前线程可使用的独占命令上下文。
        MatcherCommandScope scope = beginSynchronousCommand(aeronTimestamp);
        try {
            return cancelNative(userId, orderId, instrumentId, scope.prefixResult);
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
     * @param aeronTimestamp Cluster 提供的确定性业务时间戳
     * @param result 当前原生或普通撮合结果，方法签名决定具体类型
     * @param target 调用方独占且尚未发布的原生结果槽或结算事件，具体类型决定用途
     */
    public exchange.core2.core.common.MatcherResult publishDirectPrefixFailure(
            int shardId, long coreSequence, java.util.UUID commandId, long logicalOrderId,
            long aeronTimestamp, exchange.core2.core.common.MatcherResult result,
            com.surprising.aeron.service.settlement.MatcherSettlementEvent target) {
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
        return publishNativeMatcherResult(shardId, coreSequence, commandId, logicalOrderId,
                aeronTimestamp, retained, target);
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
            com.surprising.aeron.service.settlement.MatcherSettlementEvent target) {
        if (shardId < 0 || shardId >= topology.matchingEngineCount()
                || resultCode == null || resultCode.isBlank() || target == null)
            throw new IllegalArgumentException("invalid rejected direct matcher command");
        validateCommandEvidence(coreSequence, commandId, orderId, aeronTimestamp);
        // 先前记录的首个 Matcher 故障，存在时禁止发布后续完成结果。
        Throwable poison = matcherFailure.get();
        if (poison != null)
            throw new IllegalStateException("matcher completion discarded after fatal divergence", poison);
        matcherEvidence.publishRejection(coreSequence, commandId, orderId,
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
     * 拒绝空结果及故障后的回执，核对非控制业务分片，再绑定 Core 身份及原生/业务序号；绑定后遇到致命结果分类登记故障，仍返回已绑定事实。
     * @param coreSequence 当前逻辑命令或冻结点的 Core 序号
     * @param commandId 逻辑命令 UUID
     * @param orderId 当前命令关联的订单号，证据校验允许零
     * @param aeronTimestamp Cluster 提供的确定性业务时间戳
     * @param sequence 预先分配的证据分片业务序号
     * @param matcherShardId 业务证据分片号，负一表示控制分片
     * @param result 当前原生或普通撮合结果，方法签名决定具体类型
     */
    private CoreMatchingResult bindMatcherEvidence(
            long coreSequence,
            java.util.UUID commandId,
            long orderId,
            long aeronTimestamp,
            long sequence,
            int matcherShardId,
            CoreMatchingResult result) {
        if (result == null) throw new IllegalStateException("matcher command returned no result");
        // 绑定前观察到的首个故障，阻止继续发布证据。
        Throwable matcherPoison = matcherFailure.get();
        if (matcherPoison != null) {
            throw new IllegalStateException("matcher completion discarded after fatal divergence", matcherPoison);
        }
        // 根据原生结果品种计算的真实引擎分片，负一表示没有有效品种。
        int nativeShard = matcherShardId(result);
        if (matcherShardId >= 0 && nativeShard >= 0 && nativeShard != matcherShardId)
            throw new IllegalStateException("native matcher result crossed its evidence partition");
        // 控制证据可串联不同订单簿，native sequence 仍必须在原生引擎自己的序列内严格递增。
        CoreMatchingResult bound = matcherEvidence.bind(coreSequence, commandId, orderId,
                aeronTimestamp, sequence,
                matcherShardId, nativeShard, result);
        poisonIfFatal(bound);
        return bound;
    }

    /**
     * 只有结果分类是 FATAL_DIVERGENCE 才记录首个异常，防止随后继续接受完成事实；不覆盖更早故障。
     * @param result 当前原生或普通撮合结果，方法签名决定具体类型
     */
    private void poisonIfFatal(CoreMatchingResult result) {
        if (result.outcome() == MatchingResult.Outcome.FATAL_DIVERGENCE) {
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

    /**
     * 从独立撮合输入取订单类型和有效期，交给统一原生类型转换。
     * @param command 只读撮合输入，必须遵守其冻结及使用期限
     */
    private static OrderType orderType(CoreMatchingOrder command) {
        return orderType(command.orderType(), command.timeInForce());
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
    private CoreMatchingResult cancelRoutedBatch(List<CancellationOrder> orders) {
        // 本次控制撤单项数，最多允许 1024。
        int size = orders.size();
        if (size > 1_024) {
            return new CoreMatchingResult(false, "LIFECYCLE_BATCH_TOO_LARGE");
        }
        if (size == 1) {
            // 当前遍历或读取到的订单，Core 与原生类型由所在循环声明区分。
            CancellationOrder order = orders.get(0);
            // 当前请求的结果值，签名或局部类型区分原生结果、结果码及盘口封装。
            CoreMatchingResult result;
            try {
                result = cancelControlNative(order.userId(), order.orderId(), order.instrumentId());
            } catch (/* 当前捕获或共享操作返回的异常，所在方法决定登记、转发或停止等待。 */ RuntimeException failure) {
                matcherFailure.compareAndSet(null, failure);
                throw failure;
            }
            return aggregateRoutedCancellationResults(orders, List.of(result));
        }
        // 本次按请求顺序保存的撤单结果，首个拒绝后用未提交项补齐。
        List<CoreMatchingResult> results = new ArrayList<>(size);
        for (/* 当前批次项或盘口档位的下标。 */ int index = 0; index < size; index++) {
            // 当前遍历或读取到的订单，Core 与原生类型由所在循环声明区分。
            CancellationOrder order = orders.get(index);
            // 当前请求的结果值，签名或局部类型区分原生结果、结果码及盘口封装。
            CoreMatchingResult result;
            try {
                result = cancelControlNative(order.userId(), order.orderId(), order.instrumentId());
            } catch (/* 当前捕获或共享操作返回的异常，所在方法决定登记、转发或停止等待。 */ RuntimeException failure) {
                matcherFailure.compareAndSet(null, failure);
                throw failure;
            }
            results.add(result);
            if (!result.accepted()) {
                while (results.size() < size) {
                    results.add(notSubmitted());
                }
                break;
            }
        }
        return aggregateRoutedCancellationResults(orders, results);
    }

    /**
     * 要求请求与结果等长且各结果非空，生成逐项撤单回执并统计首个失败之前的成功数，按原顺序组合事件；部分前缀成功而整体拒绝时明确登记状态变化，不重做撤单。
     * @param requested 按原顺序排列的控制撤单请求
     * @param results 与每个请求一一对应的撤单结果，未执行项需用占位结果
     */
    private CoreMatchingResult aggregateRoutedCancellationResults(
            List<CancellationOrder> requested, List<CoreMatchingResult> results) {
        if (requested == null || results == null || requested.size() != results.size())
            throw new IllegalArgumentException("cancellation aggregation size mismatch");
        // 用于对外说明每笔订单撤单是否接受的结果列表。
        List<CoreCancellationResult> cancellations = new ArrayList<>(requested.size());
        // 首个未接受结果之前已成功撤单的连续数量。
        int successfulPrefix = 0;
        // 按结果顺序找到的首个未接受回执，用于整体结果码与成功前缀边界。
        CoreMatchingResult failure = null;
        for (/* 当前批次项或盘口档位的下标。 */ int index = 0; index < results.size(); index++) {
            // 当前顺序位置的非空撤单回执，用来生成逐项结果并确定成功前缀。
            CoreMatchingResult result = java.util.Objects.requireNonNull(results.get(index), "cancellation result");
            cancellations.add(new CoreCancellationResult(requested.get(index).orderId(), result.accepted(), result.resultCode()));
            if (failure == null && result.accepted()) successfulPrefix++;
            else if (failure == null) failure = result;
        }
        // 是否所有撤单项都接受，空列表也没有失败。
        boolean accepted = failure == null;
        return new CoreMatchingResult(accepted, accepted ? "SUCCESS" : failure.resultCode(), cancellations,
                successfulPrefix, !accepted && successfulPrefix != 0,
                0, 0, 0, 0, 0, 0, 0, -1,
                null, CoreMatchingResult.concatenateEvents(results),
                new exchange.core2.core.common.MatcherResult.MarketData(List.of(), List.of(), 0, 0));
    }

    /**
     * 执行一条原生控制撤单，先登记真实订单簿分片的原生序号，再包装普通结果，业务控制序号留给后续聚合绑定。
     * @param userId 请求或对账订单所属用户号
     * @param orderId 当前命令关联的订单号，证据校验允许零
     * @param instrumentId 外部永久数字品种字符串
     */
    private CoreMatchingResult cancelControlNative(long userId, long orderId, String instrumentId) {
        // 当前控制撤单的原生结果，先按真实引擎分片登记原生序号。
        var result = cancelNative(userId, orderId, instrumentId);
        matcherEvidence.recordControlNative(topology.matcherShardId(result.symbol()), result.sequence());
        return CoreMatchingResult.fromNative(result);
    }

    /**
     * 确保品种注册、登记用户后同步撤单，返回原生新结果，时间取自当前命令上下文。
     * @param userId 请求或对账订单所属用户号
     * @param orderId 当前命令关联的订单号，证据校验允许零
     * @param instrumentId 外部永久数字品种字符串
     */
    private exchange.core2.core.common.MatcherResult cancelNative(long userId, long orderId, String instrumentId) {
        // 当前品种的永久原生编号。
        int symbolId = ensureSymbol(instrumentId);
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
        // 当前品种的永久原生编号。
        int symbolId = ensureSymbol(instrumentId);
        recordUser(userId);
        return engine(symbolId).cancelInto(target, currentCommandScope().aeronTimestamp, orderId, symbolId, userId);
    }

    /**
     * 在当前命令作用域按既定顺序执行控制撤单并绑定控制分片负一的业务证据；运行异常记录故障，已执行前缀不回滚。
     * @param coreSequence 当前逻辑命令或冻结点的 Core 序号
     * @param commandId 逻辑命令 UUID
     * @param logicalOrderId 外层控制命令关联的逻辑订单号
     * @param timestamp Cluster 提供的确定性业务时间戳
     * @param orders 按调用方确定顺序排列的控制撤单请求
     */
    public CoreMatchingResult cancelControlOrders(long coreSequence, java.util.UUID commandId,
            long logicalOrderId, long timestamp, List<CancellationOrder> orders) {
        validateCommandEvidence(coreSequence, commandId, logicalOrderId, timestamp);
        java.util.Objects.requireNonNull(orders, "control cancellations");
        // 当前线程可使用的独占命令上下文。
        MatcherCommandScope scope = beginSynchronousCommand(timestamp);
        try {
            return bindMatcherEvidence(coreSequence, commandId, logicalOrderId, timestamp,
                    matcherEvidence.nextSequence(-1), -1, cancelRoutedBatch(orders));
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
    public CoreMatchingResult cancelControlOrder(long timestamp, long userId, long orderId, String instrumentId) {
        if (timestamp < 0 || userId <= 0 || orderId <= 0)
            throw new IllegalArgumentException("invalid control cancellation");
        // 当前线程可使用的独占命令上下文。
        MatcherCommandScope scope = beginSynchronousCommand(timestamp);
        try {
            return cancelControlNative(userId, orderId, instrumentId);
        } catch (/* 当前捕获或共享操作返回的异常，所在方法决定登记、转发或停止等待。 */ RuntimeException failure) {
            matcherFailure.compareAndSet(null, failure);
            throw failure;
        } finally { scope.aeronTimestamp = 0; scope.active = false; }
    }

    /**
     * 仅聚合已经在原生分片执行过的撤单结果，绑定一次控制业务证据，不重新操作任何订单簿。
     * @param coreSequence 当前逻辑命令或冻结点的 Core 序号
     * @param commandId 逻辑命令 UUID
     * @param logicalOrderId 外层控制命令关联的逻辑订单号
     * @param timestamp Cluster 提供的确定性业务时间戳
     * @param orders 按调用方确定顺序排列的控制撤单请求
     * @param results 与每个请求一一对应的撤单结果，未执行项需用占位结果
     */
    public CoreMatchingResult completeControlCancellations(long coreSequence, java.util.UUID commandId,
            long logicalOrderId, long timestamp, List<CancellationOrder> orders, List<CoreMatchingResult> results) {
        validateCommandEvidence(coreSequence, commandId, logicalOrderId, timestamp);
        return bindMatcherEvidence(coreSequence, commandId, logicalOrderId, timestamp,
                matcherEvidence.nextSequence(-1), -1, aggregateRoutedCancellationResults(orders, results));
    }

    /**
     * 在当前线程读取并汇总各分片哈希，用 Future 返回订单簿哈希；不会自己向工作线程投递读取任务。
     */
    public CompletableFuture<Integer> orderBooksStateHashAsync() {
        return currentStateHashesAsync().thenApply(StateHashes::bookHash);
    }

    /**
     * 由所属分片线程先逐单对账并取哈希，再通过注入函数执行该分片原生快照并 join；只有持久化成功才返回分片哈希，join 本身没有设置超时。
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
        // 当前冷态读取或汇总出的引擎与订单簿哈希。
        StateHashes hashes = stateHashes(shardId);
        // 当前分片快照执行完成后的原生状态码，只有 SUCCESS 才返回哈希。
        CommandResultCode result = snapshotPersistence.apply(() -> CompletableFuture.completedFuture(
                engines[shardId].storeSnapshot(snapshotId, coreSequence, coreSequence)
                        ? CommandResultCode.SUCCESS
                        : CommandResultCode.STATE_PERSIST_MATCHING_ENGINE_FAILED)).join();
        if (result != CommandResultCode.SUCCESS) {
            throw new FatalMatchingDivergenceException("matcher snapshot", coreSequence, snapshotId,
                    "exchange-core persist failed for shard " + shardId + ": " + result);
        }
        return new MatcherShardSnapshot(shardId, hashes.engineHash(), hashes.bookHash());
    }

    /**
     * 校验分片范围后同步取该分片的引擎和订单簿哈希，返回带分片号的冷态结果。
     * @param shardId 目标全局 Matcher 分片号
     */
    public MatcherShardSnapshot stateHashShard(int shardId) {
        if (shardId < 0 || shardId >= topology.matchingEngineCount()) {
            throw new IllegalArgumentException("matcher shard is outside configured topology");
        }
        // 当前冷态读取或汇总出的引擎与订单簿哈希。
        StateHashes hashes = stateHashes(shardId);
        return new MatcherShardSnapshot(shardId, hashes.engineHash(), hashes.bookHash());
    }

    /**
     * 按输入分片顺序汇总哈希并返回订单簿部分，多分片输入要求分片号与列表位置一致。
     * @param shards 各分片结果列表，顺序与完整性要求见方法说明
     */
    public int aggregateBookHash(List<MatcherShardSnapshot> shards) {
        return aggregateStateHashes(shards).bookHash();
    }

    /**
     * 要求分片结果数量完整，汇总哈希、导出已写入的模块和证据进度、物化用户集合并构造快照；无论构造是否成功都删除该编号的内存模块。对各分片的冻结与存储已由调用方完成。
     * @param snapshotId 正的快照编号
     * @param coreSequence 当前逻辑命令或冻结点的 Core 序号
     * @param businessStateHash 冻结点的 Core 业务哈希
     * @param state 对应恢复或快照点的 Core 状态，具体读取边界见方法说明
     * @param shardSnapshots 已完成相同冻结点捕获的分片哈希列表
     */
    public MatcherSnapshot finishShardedSnapshot(
            long snapshotId, long coreSequence, long businessStateHash, TradingCoreState state,
            List<MatcherShardSnapshot> shardSnapshots) {
        if (state == null || shardSnapshots == null || shardSnapshots.size() != topology.matchingEngineCount()) {
            throw new IllegalArgumentException("incomplete matcher shard snapshot set");
        }
        // 当前冷态读取或汇总出的引擎与订单簿哈希。
        StateHashes hashes = aggregateStateHashes(shardSnapshots);
        try {
            // 从当前快照号导出的原生模块字节，构造后删除内存存储副本。
            List<InMemorySerializationProcessor.SerializedModule> modules =
                    serializationProcessor.exportSnapshot(snapshotId);
            // 导出模块中最大的原生持久化序号。
            long matcherSequence = maxMatcherSequence(modules);
            // 在冷态边界汇总并排序的已见用户集合。
            Set<Long> userSnapshot = snapshotUsers();
            return new MatcherSnapshot(state.productLine(), topology, snapshotId, coreSequence, matcherSequence,
                    matcherEvidence.snapshot(), businessStateHash, hashes.engineHash(),
                    symbols, userSnapshot, modules);
        } finally {
            serializationProcessor.removeSnapshot(snapshotId);
        }
    }

    /**
     * 校验快照参数，按快照号合并正在进行的操作，同号重试须有相同 Core 序号和业务哈希；每次返回独立且拒绝 cancel 的视图，底层持久化只启动一次，结束后移除登记。
     * @param snapshotId 正的快照编号
     * @param coreSequence 当前逻辑命令或冻结点的 Core 序号
     * @param businessStateHash 冻结点的 Core 业务哈希
     * @param state 对应恢复或快照点的 Core 状态，具体读取边界见方法说明
     * @param activeOrders 同一冻结点的 Core 活动订单序列
     */
    public CompletableFuture<MatcherSnapshot> snapshotAsync(
            long snapshotId,
            long coreSequence,
            long businessStateHash,
            TradingCoreState state,
            Iterable<CoreOrderState> activeOrders) {
        if (snapshotId <= 0 || coreSequence < 0 || businessStateHash == 0
                || state == null || activeOrders == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("invalid matcher snapshot request"));
        }
        // 当前正在共享的快照操作，或等待结束的在途操作。
        SnapshotOperation operation = snapshotOperations.get(snapshotId);
        if (operation == null) {
            // 尝试成为同号在途操作所有者的新快照记录。
            SnapshotOperation candidate = new SnapshotOperation(coreSequence, businessStateHash);
            operation = snapshotOperations.putIfAbsent(snapshotId, candidate);
            if (operation == null) {
                operation = candidate;
                startSnapshotOperation(operation, snapshotId, coreSequence, businessStateHash, state, activeOrders);
            }
        }
        if (operation.coreSequence != coreSequence || operation.businessStateHash != businessStateHash) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("snapshot retry does not match in-flight core state"));
        }
        return operation.view();
    }

    /**
     * 依次完成活动订单对账、状态哈希、注入的持久化和模块导出，构造快照后清掉模块；成功或失败都完成共享 Future 并移除在途登记。同步启动异常直接失败，外层视图取消不取消这条流水。
     * @param operation 对账操作名称或正在进行的快照对象，具体类型见签名
     * @param snapshotId 正的快照编号
     * @param coreSequence 当前逻辑命令或冻结点的 Core 序号
     * @param businessStateHash 冻结点的 Core 业务哈希
     * @param state 对应恢复或快照点的 Core 状态，具体读取边界见方法说明
     * @param activeOrders 同一冻结点的 Core 活动订单序列
     */
    private void startSnapshotOperation(
            SnapshotOperation operation,
            long snapshotId,
            long coreSequence,
            long businessStateHash,
            TradingCoreState state,
            Iterable<CoreOrderState> activeOrders) {
        // 真实快照流水的最终 Future，最终结果转发到共享完成对象。
        CompletableFuture<MatcherSnapshot> pipeline;
        try {
            pipeline = reconcileOpenOrdersAsync(activeOrders, coreSequence, snapshotId,
                    "matcher snapshot").thenCompose(/* 此回调不读取的上一步结果或按现有约定忽略的异常，具体含义见所在语句。 */ ignored -> currentStateHashesAsync()).thenCompose(/* 当前冷态读取或汇总出的引擎与订单簿哈希。 */ hashes ->
                    snapshotPersistence.apply(() -> CompletableFuture.completedFuture(
                                    storeMatcherShards(snapshotId, coreSequence)
                                            ? CommandResultCode.SUCCESS
                                            : CommandResultCode.STATE_PERSIST_MATCHING_ENGINE_FAILED))
                            .thenApply(/* 全部原生分片快照的持久化状态码，非 SUCCESS 使共享操作失败。 */ result -> {
                                if (result != CommandResultCode.SUCCESS) {
                                    throw new FatalMatchingDivergenceException("matcher snapshot", coreSequence,
                                            snapshotId, "exchange-core persist failed: " + result);
                                }
                                try {
                                    // 从当前快照号导出的原生模块字节，构造后删除内存存储副本。
                                    List<InMemorySerializationProcessor.SerializedModule> modules =
                                            serializationProcessor.exportSnapshot(snapshotId);
                                    // 导出模块中最大的原生持久化序号。
                                    long matcherSequence = maxMatcherSequence(modules);
                                    // 在冷态边界汇总并排序的已见用户集合。
                                    Set<Long> userSnapshot = snapshotUsers();
                                    return new MatcherSnapshot(state.productLine(), topology,
                                            snapshotId, coreSequence, matcherSequence,
                                            matcherEvidence.snapshot(), businessStateHash,
                                            hashes.engineHash(),
                                            symbols, userSnapshot, modules);
                                } finally {
                                    serializationProcessor.removeSnapshot(snapshotId);
                                }
                            }));
        } catch (/* 当前捕获的运行异常，按所在入口停止、记故障或完成失败结果。 */ RuntimeException exception) {
            operation.result.completeExceptionally(exception);
            snapshotOperations.remove(snapshotId, operation);
            return;
        }
        pipeline.whenComplete((/* 当前快照值或冷态汇总容器，具体类型见声明。 */ snapshot, /* 当前捕获或共享操作返回的异常，所在方法决定登记、转发或停止等待。 */ failure) -> {
            if (failure == null) {
                operation.result.complete(snapshot);
            } else {
                operation.result.completeExceptionally(failure);
            }
            snapshotOperations.remove(snapshotId, operation);
        });
    }

    /**
     * 同步构建 Core OPEN 订单与所有原生活动订单的精确投影，拒绝终态、未登记品种、重复订单号和字段不一致，再用完成/失败 Future 返回；不自动切线程。
     * @param activeOrders 同一冻结点的 Core 活动订单序列
     * @param coreSequence 当前逻辑命令或冻结点的 Core 序号
     * @param snapshotId 正的快照编号
     * @param operation 对账操作名称或正在进行的快照对象，具体类型见签名
     */
    private CompletableFuture<Void> reconcileOpenOrdersAsync(
            Iterable<CoreOrderState> activeOrders,
            long coreSequence,
            long snapshotId,
            String operation) {
        try {
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
            return CompletableFuture.completedFuture(null);
        } catch (/* 当前捕获或共享操作返回的异常，所在方法决定登记、转发或停止等待。 */ RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
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
     * 在调用线程逐分片读取哈希并汇总，捕获运行异常包装失败 Future；调用方负责让各原生引擎处在允许读取的边界。
     */
    private CompletableFuture<StateHashes> currentStateHashesAsync() {
        try {
            // 按分片号收集的当前哈希结果。
            ArrayList<MatcherShardSnapshot> snapshots = new ArrayList<>(engines.length);
            for (/* 当前需要操作或汇总的 Matcher 分片号。 */ int shardId = 0; shardId < engines.length; shardId++) {
                // 当前冷态读取或汇总出的引擎与订单簿哈希。
                StateHashes hashes = stateHashes(shardId);
                snapshots.add(new MatcherShardSnapshot(shardId, hashes.engineHash(), hashes.bookHash()));
            }
            return CompletableFuture.completedFuture(aggregateStateHashes(snapshots));
        } catch (/* 当前捕获或共享操作返回的异常，所在方法决定登记、转发或停止等待。 */ RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    /**
     * 按原生报告的迭代顺序折叠子模块类型、编号和数值为引擎哈希，订单簿子模块另算 bookHash；int 溢出按 Java 哈希运算保留，不是资金数值或密码摘要。
     * @param shardId 目标全局 Matcher 分片号
     */
    private StateHashes stateHashes(int shardId) {
        // 原生引擎当前各子模块状态哈希报告。
        var report = engines[shardId].stateHashReport();
        // 顺序折叠出的引擎哈希，int 自然溢出属于哈希算法。
        int engineHash = 1;
        // 只折叠订单簿子模块得到的 int 哈希。
        int bookHash = 0;
        for (Map.Entry<exchange.core2.core.common.api.reports.StateHashReportResult.SubmoduleKey,
                Integer> /* 当前子模块哈希或品种路由条目，具体类型见循环声明。 */ entry : report.getHashCodes().entrySet()) {
            engineHash = engineHash * 31 + entry.getKey().submodule.code;
            engineHash = engineHash * 31 + entry.getKey().moduleId;
            engineHash = engineHash * 31 + entry.getValue();
            if (entry.getKey().submodule
                    == exchange.core2.core.common.api.reports.StateHashReportResult.SubmoduleType
                    .MATCHING_ORDER_BOOKS) bookHash = bookHash * 31 + entry.getValue();
        }
        return new StateHashes(engineHash, bookHash);
    }

    /**
     * 单分片直接返回其两种哈希；多分片要求列表下标就是分片号再顺序折叠。单分片分支不额外核对该条的分片号，调用方保证集合完整。
     * @param shards 各分片结果列表，顺序与完整性要求见方法说明
     */
    private static StateHashes aggregateStateHashes(List<MatcherShardSnapshot> shards) {
        if (shards.size() == 1) return new StateHashes(shards.getFirst().engineHash(), shards.getFirst().bookHash());
        // 顺序折叠出的引擎哈希，int 自然溢出属于哈希算法。
        int engineHash = 1;
        // 只折叠订单簿子模块得到的 int 哈希。
        int bookHash = 0;
        for (/* 当前需要操作或汇总的 Matcher 分片号。 */ int shardId = 0; shardId < shards.size(); shardId++) {
            // 当前参与汇总的分片哈希或启动盘口结果。
            MatcherShardSnapshot shard = shards.get(shardId);
            if (shard.shardId() != shardId) throw new IllegalArgumentException("matcher shards are not ordered");
            engineHash = (engineHash * 31 + shardId) * 31 + shard.engineHash();
            bookHash = (bookHash * 31 + shardId) * 31 + shard.bookHash();
        }
        return new StateHashes(engineHash, bookHash);
    }

    /**
     * 校验深度 1 至 100 并规范品种字符串，未登记返回空列表；已登记则在调用线程读订单簿并包装 Future，转换结果排序为买卖侧再价格升序。
     * @param requestedSymbol 待查询的品种字符串，会 trim 并按 ROOT 转为大写
     * @param depth 每侧期望读取的盘口深度，范围 1 至 100
     */
    public CompletableFuture<List<CoreBookLevelView>> orderBookLevelsAsync(String requestedSymbol, int depth) {
        if (requestedSymbol == null || requestedSymbol.isBlank() || depth < 1 || depth > 100) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("invalid single-instrumentId book query"));
        }
        // 当前正在登记、规范化或查询的品种字符串。
        String instrumentId = requestedSymbol.trim().toUpperCase(java.util.Locale.ROOT);
        // 当前品种的永久原生编号。
        Integer symbolId = symbols.get(instrumentId);
        if (symbolId == null) return CompletableFuture.completedFuture(List.of());
        return CompletableFuture.completedFuture(bookLevels(instrumentId, engine(symbolId).orderBook(symbolId, depth)));
    }

    /**
     * 当前线程先读取状态哈希，再按品种名排序读取各盘口并合并；这里的已完成 Future 和 allOf 不会自动建立跨线程屏障，调用方先保证分片读取边界。返回品种清单含无档位品种。
     * @param depth 每侧期望读取的盘口深度，范围 1 至 100
     */
    public CompletableFuture<BookBootstrapSnapshot> orderBookBootstrapAsync(int depth) {
        if (depth < 1 || depth > 100) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("invalid bootstrap book depth"));
        }
        return currentStateHashesAsync().thenCompose(/* 当前同步哈希读取的完成值，此回调不读取它，不表示自动跨线程屏障。 */ barrierComplete -> {
            // 当前需要读取盘口的路由条目副本，随后按品种字符串排序。
            List<Map.Entry<String, Integer>> entries = new ArrayList<>(symbols.entrySet());
            entries.sort(Map.Entry.comparingByKey());
            // 需保留在启动视图中的有序品种清单，包括无档位的品种。
            List<String> entrySymbols = new ArrayList<>(entries.size());
            for (Map.Entry<String, Integer> /* 当前子模块哈希或品种路由条目，具体类型见循环声明。 */ entry : entries) {
                entrySymbols.add(entry.getKey());
            }
            // 当前线程同步取值后包装的各品种盘口 Future。
            List<CompletableFuture<BookResult>> requests = new ArrayList<>(entries.size());
            for (Map.Entry<String, Integer> /* 当前子模块哈希或品种路由条目，具体类型见循环声明。 */ entry : entries) {
                requests.add(CompletableFuture.completedFuture(
                        new BookResult(entry.getKey(), engine(entry.getValue()).orderBook(entry.getValue(), depth))));
            }
            return CompletableFuture.allOf(requests.toArray(CompletableFuture[]::new)).thenApply(/* 此回调不读取的上一步结果或按现有约定忽略的异常，具体含义见所在语句。 */ ignored -> {
                // 精确加法累计的实际买卖档位总数，用于分配结果列表。
                int expectedLevels = 0;
                for (CompletableFuture<BookResult> /* 已同步读取完成的一个品种盘口 Future。 */ request : requests) {
                    // 一个已经完成的品种盘口封装，用于累计档位或收集协议视图。
                    BookResult result = request.getNow(null);
                    expectedLevels = Math.addExact(expectedLevels,
                            Math.addExact(result.book().askSize, result.book().bidSize));
                }
                // 本次转换出的协议盘口档位，返回前按既定规则排序。
                List<CoreBookLevelView> levels = new ArrayList<>(expectedLevels);
                for (CompletableFuture<BookResult> /* 已同步读取完成的一个品种盘口 Future。 */ request : requests) {
                    // 一个已经完成的品种盘口封装，用于累计档位或收集协议视图。
                    BookResult result = request.getNow(null);
                    levels.addAll(bookLevels(result.instrumentId(), result.book()));
                }
                levels.sort(Comparator.comparing(CoreBookLevelView::instrumentId)
                        .thenComparing(CoreBookLevelView::side)
                        .thenComparingLong(CoreBookLevelView::priceTicks));
                return new BookBootstrapSnapshot(entrySymbols, levels);
            });
        });
    }

    /**
     * 在当前分片线程筛选路由表，按品种名读取本分片各盘口并转换为启动视图，深度限制 1 至 100；不在这里等待其他分片。
     * @param shardId 目标全局 Matcher 分片号
     * @param depth 每侧期望读取的盘口深度，范围 1 至 100
     */
    public BookBootstrapSnapshot orderBookBootstrapShard(int shardId, int depth) {
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
        return new BookBootstrapSnapshot(entrySymbols, levels);
    }

    /**
     * 要求结果数等于拓扑分片数，拼接品种与档位后统一排序；输入不携带分片身份，本方法不另验证重复分片或重复品种。
     * @param shards 各分片结果列表，顺序与完整性要求见方法说明
     */
    public BookBootstrapSnapshot mergeBookBootstrapShards(List<BookBootstrapSnapshot> shards) {
        if (shards == null || shards.size() != topology.matchingEngineCount()) {
            throw new IllegalArgumentException("incomplete matcher bootstrap shards");
        }
        // 汇总各分片品种清单的临时列表，最终按字符串排序。
        ArrayList<String> mergedSymbols = new ArrayList<>();
        // 汇总各分片盘口档位的临时列表，最终按品种、方向和价格排序。
        ArrayList<CoreBookLevelView> mergedLevels = new ArrayList<>();
        for (BookBootstrapSnapshot /* 当前参与汇总的分片哈希或启动盘口结果。 */ shard : shards) {
            mergedSymbols.addAll(shard.symbols());
            mergedLevels.addAll(shard.levels());
        }
        mergedSymbols.sort(String::compareTo);
        mergedLevels.sort(Comparator.comparing(CoreBookLevelView::instrumentId)
                .thenComparing(CoreBookLevelView::side)
                .thenComparingLong(CoreBookLevelView::priceTicks));
        return new BookBootstrapSnapshot(mergedSymbols, mergedLevels);
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
     * 把一次同步盘口读取与品种字符串配在一起的冷态临时值。
     * @param instrumentId 外部永久数字品种字符串
     * @param book 当前同步读取的原生盘口数据
     */
    private record BookResult(String instrumentId, L2MarketData book) {
    }

    /**
     * 要求品种非空，只读取 instrumentId 交给同步注册包装；其他合约或费率参数不直接配置原生订单簿。空参数直接抛异常，其余注册异常由下层包装为失败 Future。
     * @param instrument 含永久品种编号的业务品种对象
     */
    public CompletableFuture<Integer> ensureInstrumentAsync(CoreInstrument instrument) {
        if (instrument == null) {
            throw new IllegalArgumentException("instrument is required");
        }
        return ensureSymbolAsync(instrument.instrumentId());
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
    private void start(MatcherSnapshot snapshot) {
        engines = new SynchronousMatchingEngine[topology.matchingEngineCount()];
        for (/* 当前需要操作或汇总的 Matcher 分片号。 */ int shardId = 0; shardId < engines.length; shardId++) startShard(shardId, snapshot);
        activatedShardCount.set(engines.length);
    }

    /**
     * 在当前线程创建一个 MATCHING_ONLY 同步引擎，原生每个实例只有一个 Matcher，账户风险和原生日志关闭；从快照或空白启动，并用分片序列化包装器将本地模块号映射为全局分片号。
     * @param shardId 目标全局 Matcher 分片号
     * @param snapshot 已有 Matcher 快照；启动路径为空时表示空白引擎
     */
    private void startShard(int shardId, MatcherSnapshot snapshot) {
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
                        .riskEnginesNum(topology.riskEngineCount())
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
     * 从普通回执的原生结果取得品种并计算实际分片，没有原生品种时返回负一。
     * @param result 当前原生或普通撮合结果，方法签名决定具体类型
     */
    private int matcherShardId(CoreMatchingResult result) {
        // 当前品种的永久原生编号。
        int symbolId = result.nativeMatcherResult() == null ? 0 : result.nativeMatcherResult().symbol();
        return symbolId <= 0 ? -1 : topology.matcherShardId(symbolId);
    }

    /**
     * 在当前线程同步确保原生品种已注册，用完成或失败 Future 包装，不另外调度。
     * @param instrumentId 外部永久数字品种字符串
     */
    private CompletableFuture<Integer> ensureSymbolAsync(String instrumentId) {
        try {
            return CompletableFuture.completedFuture(ensureSymbol(instrumentId));
        } catch (/* 当前捕获或共享操作返回的异常，所在方法决定登记、转发或停止等待。 */ RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
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
     * 按数组顺序同步存储全部分片，首个返回 false 就停止；此前已写入的模块不在这里回滚。
     * @param snapshotId 正的快照编号
     * @param coreSequence 当前逻辑命令或冻结点的 Core 序号
     */
    private boolean storeMatcherShards(long snapshotId, long coreSequence) {
        for (SynchronousMatchingEngine /* 当前遍历或关闭的同步引擎实例。 */ matcher : engines) {
            if (!matcher.storeSnapshot(snapshotId, coreSequence, coreSequence)) return false;
        }
        return true;
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
     * 同一次读取或汇总得到的引擎哈希与订单簿哈希，都是 int 校验值。
     * @param engineHash 原生子模块报告折叠出的 int 引擎哈希
     * @param bookHash 订单簿部分折叠出的 int 哈希
     */
    private record StateHashes(int engineHash, int bookHash) {
    }

    /**
     * 一个分片已完成冷态快照或读取后的哈希结果，不携带模块字节。
     * @param shardId 目标全局 Matcher 分片号
     * @param engineHash 原生子模块报告折叠出的 int 引擎哈希
     * @param bookHash 订单簿部分折叠出的 int 哈希
     */
    public record MatcherShardSnapshot(int shardId, int engineHash, int bookHash) {
        /**
         * 构造时只要求分片号非负，哈希值允许任意 int，拓扑上界由使用处检查。
         */
        public MatcherShardSnapshot {
            if (shardId < 0) throw new IllegalArgumentException("matcher shard id must be non-negative");
        }
    }

    /** 按快照号共享的在途操作，保存重试校验身份和唯一底层完成对象；结束后从并发表移除。 构造约定：登记同一在途快照号对应的 Core 序号和业务哈希，共享底层完成 Future。 参数 coreSequence：当前逻辑命令或冻结点的 Core 序号 参数 businessStateHash：冻结点的 Core 业务哈希 */
    @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
    private static final class SnapshotOperation {
        /** 该在途快照开始时的 Core 序号，用来核对重试身份。 */
        private final long coreSequence;
        /** 该在途快照的 Core 业务哈希，用来核对重试是否仍指同一状态。 */
        private final long businessStateHash;
        /** 所有同号请求共用的快照底层 Future，外层视图不传播 cancel。 */
        private final CompletableFuture<MatcherSnapshot> result = new CompletableFuture<>();

        /**
         * 创建独立的不可取消 Future，将共享底层成功值或异常转发给该视图；调用方在视图上自行完成也不会改变底层结果。
         */
        private CompletableFuture<MatcherSnapshot> view() {
            // 当前请求独立的不可取消外层 Future，共享结果只向它单向转发。
            NonCancellableFuture<MatcherSnapshot> view = new NonCancellableFuture<>();
            result.whenComplete((/* 当前快照值或冷态汇总容器，具体类型见声明。 */ snapshot, /* 当前捕获或共享操作返回的异常，所在方法决定登记、转发或停止等待。 */ failure) -> {
                if (failure == null) {
                    view.complete(snapshot);
                } else {
                    view.completeExceptionally(failure);
                }
            });
            return view;
        }
    }

    /**
     * 阻止外层 cancel 传播的完成视图。它只覆盖 cancel，并未禁止调用方手动 complete、超时完成或强制改写自己的视图。
     * @param <T> 外层只读取消语义视图的结果类型
     */
    private static final class NonCancellableFuture<T> extends CompletableFuture<T> {
        /**
         * 始终拒绝取消，忽略中断意图参数；持久化继续完成并由共享结果通知其他视图。
         * @param mayInterruptIfRunning 调用方的中断意图，本实现忽略且始终拒绝取消
         */
        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return false;
        }
    }

    /**
     * 有引擎时先有限等待在途快照，再依次读取非故障引擎哈希并关闭，汇总关闭异常后清空数组并抛出首个异常；哈希读取与关闭共用 try，前者抛出会跳过该实例 close，不承诺所有资源必已关闭。
     */
    private void stop() {
        if (engines != null) {
            awaitSnapshotOperations();
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
     * 逐个在途操作最多等待三十秒；中断恢复中断标记后返回，执行失败或超时也立即结束全部等待，不是全局固定三十秒截止。
     */
    private void awaitSnapshotOperations() {
        for (SnapshotOperation /* 当前正在共享的快照操作，或等待结束的在途操作。 */ operation : snapshotOperations.values()) {
            try {
                operation.result.get(30, TimeUnit.SECONDS);
            } catch (/* 等待快照被中断，恢复当前线程中断标记后结束等待。 */ InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            } catch (/* 此回调不读取的上一步结果或按现有约定忽略的异常，具体含义见所在语句。 */ ExecutionException | TimeoutException ignored) {
                return;
            }
        }
    }

    /**
     * 执行整体停止流程，可能传播快照等待后出现的引擎关闭异常。
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
    public record CancellationOrder(long orderId, long userId, String instrumentId) {
        /**
         * 检查订单号和用户号为正，品种字符串非空白；不在这里解析或规范品种编号。
         */
        public CancellationOrder {
            if (orderId <= 0 || userId <= 0 || instrumentId == null || instrumentId.isBlank()) {
                throw new IllegalArgumentException("invalid cancellation order");
            }
        }
    }

    /**
     * 创建未提交撤单的占位结果，明确它被跳过而不是原生引擎拒绝。
     */
    private static CoreMatchingResult notSubmitted() {
        return new CoreMatchingResult(false, "NOT_SUBMITTED");
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
}
