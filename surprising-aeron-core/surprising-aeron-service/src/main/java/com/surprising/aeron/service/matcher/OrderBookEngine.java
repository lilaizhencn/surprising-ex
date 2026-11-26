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
import com.surprising.aeron.service.snapshot.state.TradingCoreState;
import com.surprising.aeron.protocol.CoreBookLevelView;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.service.order.ResolvedPlaceOrder;
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

/** Matcher 与 exchange-core 同步订单簿之间的接入边界。分片调用由所属 Matcher 线程串行执行，冷态启动、对账、哈希和快照需要调用方先建立相应静止边界。名称中的 Async 多数只包装同步结果，不自动切换线程。 */
public final class OrderBookEngine implements AutoCloseable {

    /** 仅为原生撤单前缀失败记录的 JFR 事件。 */
    @jdk.jfr.Name("surprising.MatcherPrefixFailure")
    @jdk.jfr.Category("Surprising Core")
    @jdk.jfr.StackTrace(false)
    static final class MatcherPrefixFailure extends jdk.jfr.Event {
        private static final jdk.jfr.EventType TYPE = jdk.jfr.EventType.getEventType(MatcherPrefixFailure.class);
        public long coreSequence, commandIdHigh, commandIdLow, logicalOrderId, canceledOrderId, userId;
        public int matcherShard, symbolId;
        public String nativeResultCode;
    }

    private final CoreRuntimeConfiguration startupConfiguration;
    SynchronousMatchingEngine[] engines;
    /** 收集与导入快照模块字节的共享内存存储，导出后按快照号删除。 */
    final InMemorySerializationProcessor serializationProcessor =
            new InMemorySerializationProcessor();
    /** 品种字符串到永久数字编号的并发路由表，条目存在不表示原生已注册。 */
    final Map<String, Integer> symbols = new ConcurrentHashMap<>();
    /** 永久编号到原字符串的并发反向表，用于拒绝同编号字符串冲突。 */
    private final Map<Integer, String> symbolNames = new ConcurrentHashMap<>();
    /** 启动配置或恢复快照确定的账户与 Matcher 分片拓扑。返回本适配器启动或恢复时确定的线程分片拓扑。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private final LaneTopology topology;
    /** 只保存首个故障的跨线程标记，后续命令和完成结果据此停止。 */
    private final AtomicReference<Throwable> matcherFailure = new AtomicReference<>();
    final MatcherEvidenceLedger matcherEvidence;
    private final OrderBookSnapshotSupport snapshotSupport = new OrderBookSnapshotSupport(this);
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
    /** 当前 Matcher 写者独占的命令作用域：保存本次业务时间、嵌套标记、用户和品种注册集合，并复用临时前缀结果槽。不是跨线程共享的订单状态。 */
    private static final class MatcherCommandScope {
        long aeronTimestamp;
        boolean active;
        final LongHashSet users = new LongHashSet();
        final org.eclipse.collections.impl.set.mutable.primitive.IntHashSet registeredSymbols =
                new org.eclipse.collections.impl.set.mutable.primitive.IntHashSet();
        final exchange.core2.core.common.MatcherResult prefixResult = exchange.core2.core.common.MatcherResult.commandSlot();
    }

    private MatcherCommandScope[] shardCommandScopes;
    /** 分片激活时登记的实际线程，用于普通线程身份查找。 */
    private Thread[] shardThreads;
    /** 构造适配器的线程，允许冷态独立调用。 */
    private final Thread standaloneThread = Thread.currentThread();
    /** 创建线程使用的独立命令临时状态与用户集合。 */
    private final MatcherCommandScope standaloneCommandScope = new MatcherCommandScope();

    /** 按当前拓扑创建每个分片独占的用户/注册集合和临时撤单槽，线程登记表同时清空。不在这里启动线程或订单簿。 */
    private void initializeCommandScopes() {
        shardCommandScopes = new MatcherCommandScope[topology.matchingEngineCount()];
        shardThreads = new Thread[shardCommandScopes.length];
        for (int i = 0; i < shardCommandScopes.length; i++) shardCommandScopes[i] = new MatcherCommandScope();
    }

    /** 优先按 MatcherThread 携带的合法分片号取上下文，其次接受创建适配器的线程，再按激活时登记的线程查找。其他线程直接拒绝。它不逐次核对 MatcherThread 是否就是登记实例。 */
    private MatcherCommandScope currentCommandScope() {
        Thread current = Thread.currentThread();
        if (current instanceof com.surprising.aeron.support.concurrent.MatcherThread mt) {
            int shardId = mt.shardId();
            if (shardId >= 0 && shardId < shardCommandScopes.length) {
                return shardCommandScopes[shardId];
            }
        }
        if (current == standaloneThread) return standaloneCommandScope;
        Thread[] threads = shardThreads;
        if (threads != null) {
            for (int i = 0; i < threads.length; i++) {
                if (threads[i] == current) return shardCommandScopes[i];
            }
        }
        throw new IllegalStateException("matcher command must run on its owning shard thread");
    }

    /** 在当前允许调用的线程上下文中登记用户，供冷态快照与哈希边界汇总。 */
    private void recordUser(long userId) {
        currentCommandScope().users.add(userId);
    }

    Set<Long> snapshotUsers() {
        Set<Long> snapshot = new java.util.TreeSet<>();
        standaloneCommandScope.users.forEach((long userId) -> snapshot.add(userId));
        for (MatcherCommandScope scope : shardCommandScopes) {
            scope.users.forEach((long userId) -> snapshot.add(userId));
        }
        return snapshot;
    }

    /** 通过统一配置入口绑定独立运行的拓扑，立即在当前线程创建全套同步订单簿。 */
    public OrderBookEngine() {
        this(true);
    }

    /** 创建空白适配器，可立即创建全部订单簿，也可留给后续分片激活。 */
    public OrderBookEngine(boolean startImmediately) {
        this(CoreConfiguration.standaloneLaneTopology(), startImmediately);
    }

    /** 使用账户运行时共用的显式拓扑创建空白 Matcher，不再自行读取配置。 */
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

    /** 按 Core 快照元数据恢复，默认立即激活并核对订单簿。 */
    public OrderBookEngine(
            TradingCoreState state,
            Iterable<CoreOrderState> activeOrders,
            long coreSequence,
            OrderBookSnapshot snapshot) {
        this(state, activeOrders, coreSequence, snapshot, true);
    }

    /** 从非空 Core 状态计算预期业务哈希，再交给完整恢复构造器。 */
    public OrderBookEngine(
            TradingCoreState state,
            Iterable<CoreOrderState> activeOrders,
            long coreSequence,
            OrderBookSnapshot snapshot,
            boolean startImmediately) {
        this(state, activeOrders, coreSequence, snapshot, startImmediately,
                state == null ? 0 : state.businessStateHash());
    }

    /** 先校验 Core 与 Matcher 快照元数据，导入模块字节和路由/用户表，复制活动订单并保存待激活动作。可立即执行恢复。这里只恢复路由登记，不把它当成原生品种注册证据。恢复异常停止已建引擎并包装为致命分歧。 */
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
            snapshot.symbols().forEach((instrumentId, symbolId) -> {
                String previous = symbolNames.put(symbolId, instrumentId);
                if (previous != null && !previous.equals(instrumentId)) {
                    throw new IllegalArgumentException("matcher instrumentId registry collision");
                }
                symbols.put(instrumentId, symbolId);
            });
            // 路由表也含尚未创建订单簿的 instrumentId，不能作为 native 注册完成的证据。
            // 恢复后的首次使用仍经 ensureSymbol。
            // native 对已有订单簿的注册是幂等操作。
            snapshot.users().forEach(standaloneCommandScope.users::add);
            List<CoreOrderState> restoredOrders = new ArrayList<>();
            activeOrders.forEach(restoredOrders::add);
            restoredSnapshot = snapshot;
            restoredActiveOrders = List.copyOf(restoredOrders);
            restoredCoreSequence = coreSequence;
            deferredActivation = () -> activateRestoredMatcher(
                    restoredOrders, coreSequence, snapshot);
            if (startImmediately) activate();
        } catch (RuntimeException exception) {
            stop();
            Throwable cause = unwrap(exception);
            if (cause instanceof FatalMatchingDivergenceException divergence) throw divergence;
            throw new FatalMatchingDivergenceException("matcher restore", coreSequence,
                    snapshot.snapshotId(), "native snapshot validation failed", cause);
        }
    }

    /** 在当前线程一次性创建或恢复全部订单簿，成功才清掉延迟动作并标记激活。 */
    public synchronized void activate() {
        if (activationComplete) return;
        try {
            Runnable activation = deferredActivation;
            if (activation == null) start(); else activation.run();
            deferredActivation = null;
            activationComplete = true;
        } catch (RuntimeException exception) {
            stop();
            Throwable cause = unwrap(exception);
            if (cause instanceof FatalMatchingDivergenceException divergence) throw divergence;
            throw exception;
        }
    }

    /** 返回全体分片是否已完成激活的可见标记，不是单个分片就绪状态。 */
    public boolean activated() { return activationComplete; }

    /** 由所属线程激活指定分片，登记线程并构造一个独立同步引擎。恢复时核对该分片活动订单和哈希，最后一个分片再比对全局引擎哈希并发布全体就绪。单分片走整体激活分支，此方法不负责创建工作线程。 */
    public void activateShard(int shardId) {
        if (shardId < 0 || shardId >= topology.matchingEngineCount()) {
            throw new IllegalArgumentException("matcher shard is outside configured topology");
        }
        shardThreads[shardId] = Thread.currentThread();
        if (topology.matchingEngineCount() == 1) {
            synchronized (this) {
                if (activationComplete) return;
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
            snapshotSupport.reconcileOpenOrdersShard(restoredActiveOrders, shardId, restoredCoreSequence,
                    restoredSnapshot.snapshotId(), "matcher restore");
            int engineHash = snapshotSupport.engineStateHash(shardId);
            restoredShardHashes[shardId] = new MatcherShardSnapshot(
                    shardId, engineHash);
        }
        if (activatedShardCount.incrementAndGet() == topology.matchingEngineCount()) {
            if (restoredSnapshot != null) {
                int engineHash = OrderBookSnapshotSupport.aggregateEngineStateHash(List.of(restoredShardHashes));
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

    private void activateRestoredMatcher(Iterable<CoreOrderState> activeOrders,
                                         long coreSequence, OrderBookSnapshot snapshot) {
        start(snapshot);
        snapshotSupport.reconcileOpenOrders(activeOrders, coreSequence, snapshot.snapshotId(), "matcher restore");
        int restoredHash = snapshotSupport.currentEngineStateHash();
        if (restoredHash != snapshot.engineStateHash()) {
            throw new FatalMatchingDivergenceException("matcher restore", coreSequence,
                    snapshot.snapshotId(), "restored exchange-core state hash mismatch"
                            + " (expectedEngine=" + snapshot.engineStateHash()
                            + ", actualEngine=" + restoredHash + ')');
        }
    }

    private exchange.core2.core.common.MatcherResult placeNative(long userId, com.surprising.aeron.service.order.ResolvedPlaceOrder command, exchange.core2.core.common.MatcherResult target) {
        int symbolId = ensureSymbol(command.instrument().instrumentIdValue());
        recordUser(userId);
        long price = protectedMarketPrice(symbolId, command.side(), command.orderType(),
                command.matchingPriceTicks(), slippagePpm(command.instrument().contractType().productLine()));
        return engine(symbolId).placeInto(target,
                currentCommandScope().aeronTimestamp, command.orderId(), 0,
                price, command.matchingPriceTicks(), command.quantitySteps(),
                command.side() == CoreOrderSide.BUY ? OrderAction.BID : OrderAction.ASK,
                orderType(command.orderType(), command.timeInForce()), symbolId, userId);
    }

    private exchange.core2.core.common.MatcherResult placeNativeAdmitted(long userId,
            com.surprising.aeron.service.order.Order command, exchange.core2.core.common.MatcherResult target) {
        int symbolId = ensureSymbol(command.instrument().instrumentIdValue());
        recordUser(userId);
        long price = protectedMarketPrice(symbolId, command.side(), command.orderType(),
                command.matchingPriceTicks(), slippagePpm(command.productLine()));
        return engine(symbolId).placeInto(target,
                currentCommandScope().aeronTimestamp, command.orderId(), 0,
                price, command.matchingPriceTicks(), command.remainingQuantitySteps(),
                command.side() == CoreOrderSide.BUY ? OrderAction.BID : OrderAction.ASK,
                orderType(command.orderType(), command.timeInForce()), symbolId, userId);
    }

    /** 检查分片、冻结输入身份和命令证据后开启串行命令，撮合并发布到结算事件。 */
    public exchange.core2.core.common.MatcherResult placeAdmittedDirect(
            int shardId, long coreSequence, long commandIdMsb, long commandIdLsb, long aeronTimestamp,
            long userId, com.surprising.aeron.service.order.Order command,
            com.surprising.aeron.service.settlement.TradeSettlement target) {
        if (shardId < 0 || shardId >= topology.matchingEngineCount() || command == null || target == null)
            throw new IllegalArgumentException("invalid direct matcher command");
        command.requireFrozenMatcherInput(userId);
        validateCommandEvidence(coreSequence, commandIdMsb != 0 || commandIdLsb != 0, command.orderId(), aeronTimestamp);
        MatcherCommandScope scope = beginSynchronousCommand(aeronTimestamp);
        try {
            return publishNativeMatcherResult(shardId, coreSequence, commandIdMsb, commandIdLsb, command.orderId(), placeNativeAdmitted(userId, command, target.nativeResultSlot(0)), target);
        } catch (RuntimeException exception) {
            throw poison(exception);
        } finally {
            endSynchronousCommand(scope);
        }
    }

    /** 检查分片和准入输入后同步下单，将原生结果与证据写入复用结算事件。 */
    public exchange.core2.core.common.MatcherResult placeDirect(
            int shardId, long coreSequence, long commandIdMsb, long commandIdLsb, long aeronTimestamp,
            long userId, com.surprising.aeron.service.order.ResolvedPlaceOrder command,
            com.surprising.aeron.service.settlement.TradeSettlement target) {
        if (shardId < 0 || shardId >= topology.matchingEngineCount() || command == null || target == null)
            throw new IllegalArgumentException("invalid direct matcher command");
        validateCommandEvidence(coreSequence, commandIdMsb != 0 || commandIdLsb != 0, command.orderId(), aeronTimestamp);
        MatcherCommandScope scope = beginSynchronousCommand(aeronTimestamp);
        try {
            return publishNativeMatcherResult(shardId, coreSequence, commandIdMsb, commandIdLsb, command.orderId(), placeNative(userId, command, target.nativeResultSlot(0)), target);
        } catch (RuntimeException exception) {
            throw poison(exception);
        } finally {
            endSynchronousCommand(scope);
        }
    }

    /** 检查分片、品种和命令证据，同步撤单并发布结果。 */
    public exchange.core2.core.common.MatcherResult cancelDirect(
            int shardId, long coreSequence, long commandIdMsb, long commandIdLsb, long orderId,
            long aeronTimestamp, long userId, int symbolId,
            com.surprising.aeron.service.settlement.TradeSettlement target) {
        if (shardId < 0 || shardId >= topology.matchingEngineCount()
                || symbolId <= 0 || target == null)
            throw new IllegalArgumentException("invalid direct matcher cancellation");
        validateCommandEvidence(coreSequence, commandIdMsb != 0 || commandIdLsb != 0, orderId, aeronTimestamp);
        MatcherCommandScope scope = beginSynchronousCommand(aeronTimestamp);
        try {
            return publishNativeMatcherResult(shardId, coreSequence, commandIdMsb, commandIdLsb, orderId, cancelNative(userId, orderId, symbolId, target.nativeResultSlot(0)), target);
        } catch (RuntimeException exception) {
            throw poison(exception);
        } finally {
            endSynchronousCommand(scope);
        }
    }

    /** 先写独立的旧单撤单槽，只有撤单接受才下新单，再发布两段事实及最终结果。新单被拒也不会回滚已执行撤单。 */
    public exchange.core2.core.common.MatcherResult replaceDirect(
            int shardId, long coreSequence, long commandIdMsb, long commandIdLsb, long orderId,
            long aeronTimestamp, long userId, long originalOrderId, int symbolId,
            com.surprising.aeron.service.order.ResolvedPlaceOrder replacement,
            com.surprising.aeron.service.settlement.TradeSettlement target) {
        if (shardId < 0 || shardId >= topology.matchingEngineCount()
                || symbolId <= 0 || replacement == null || target == null
                || replacement.instrument().instrumentIdValue() != symbolId)
            throw new IllegalArgumentException("invalid direct matcher replacement");
        validateCommandEvidence(coreSequence, commandIdMsb != 0 || commandIdLsb != 0, orderId, aeronTimestamp);
        MatcherCommandScope scope = beginSynchronousCommand(aeronTimestamp);
        try {
            // 先撤旧单产生的原生结果，占用独立槽避免新单覆盖。
            exchange.core2.core.common.MatcherResult cancellation =
                    cancelNative(userId, originalOrderId, symbolId, target.replacementCancellationSlot());
            exchange.core2.core.common.MatcherResult result = nativeAccepted(cancellation)
                    ? placeNative(userId, replacement, target.nativeResultSlot(0)) : cancellation;
            int nativeShard = result.symbol() <= 0 ? shardId : topology.matcherShardId(result.symbol());
            if (nativeShard != shardId)
                throw new IllegalStateException("native matcher replacement crossed its evidence partition");
            return matcherEvidence.publishReplacement(coreSequence, commandIdMsb, commandIdLsb, orderId,
                    matcherEvidence.nextSequence(shardId), shardId, nativeShard,
                    cancellation, result, target);
        } catch (RuntimeException exception) {
            throw poison(exception);
        } finally {
            endSynchronousCommand(scope);
        }
    }

    /** 执行同一批次中的一个已准入下单项，写指定原生槽并推进本分片证据序号。 */
    public exchange.core2.core.common.MatcherResult placeDirectBatchItem(
            int shardId, int index, long coreSequence, java.util.UUID commandId, long aeronTimestamp,
            long userId, com.surprising.aeron.service.order.ResolvedPlaceOrder command,
            com.surprising.aeron.service.settlement.TradeSettlement target) {
        if (shardId < 0 || shardId >= topology.matchingEngineCount() || index < 0
                || command == null || target == null)
            throw new IllegalArgumentException("invalid direct matcher batch item");
        validateCommandEvidence(coreSequence, commandId != null, command.orderId(), aeronTimestamp);
        MatcherCommandScope scope = beginSynchronousCommand(aeronTimestamp);
        try {
            exchange.core2.core.common.MatcherResult result = placeNative(userId, command, target.nativeResultSlot(index));
            int nativeShard = result.symbol() <= 0 ? -1 : topology.matcherShardId(result.symbol());
            if (nativeShard >= 0 && nativeShard != shardId)
                throw new IllegalStateException("native matcher batch crossed its evidence partition");
            return matcherEvidence.publishBatchItem(coreSequence, commandId.getMostSignificantBits(), commandId.getLeastSignificantBits(), command.orderId(), matcherEvidence.nextSequence(shardId), shardId, nativeShard,
                    result, index, target);
        } catch (RuntimeException exception) {
            throw poison(exception);
        } finally {
            endSynchronousCommand(scope);
        }
    }

    /** 执行批次中的一个撤单项，核对原生与证据分片一致后发布到指定结算槽。 */
    public exchange.core2.core.common.MatcherResult cancelDirectBatchItem(
            int shardId, int index, long coreSequence, java.util.UUID commandId, long orderId,
            long aeronTimestamp, long userId, String instrumentId,
            com.surprising.aeron.service.settlement.TradeSettlement target) {
        if (shardId < 0 || shardId >= topology.matchingEngineCount() || index < 0
                || instrumentId == null || instrumentId.isBlank() || target == null)
            throw new IllegalArgumentException("invalid direct matcher cancellation item");
        validateCommandEvidence(coreSequence, commandId != null, orderId, aeronTimestamp);
        MatcherCommandScope scope = beginSynchronousCommand(aeronTimestamp);
        try {
            exchange.core2.core.common.MatcherResult result = cancelNative(userId, orderId, instrumentId, target.nativeResultSlot(index));
            int nativeShard = result.symbol() <= 0 ? shardId : topology.matcherShardId(result.symbol());
            if (nativeShard != shardId)
                throw new IllegalStateException("native matcher cancellation crossed its evidence partition");
            return matcherEvidence.publishBatchItem(coreSequence, commandId.getMostSignificantBits(), commandId.getLeastSignificantBits(), orderId, matcherEvidence.nextSequence(shardId), shardId, nativeShard,
                    result, index, target);
        } catch (RuntimeException exception) {
            throw poison(exception);
        } finally {
            endSynchronousCommand(scope);
        }
    }

    private static boolean nativeAccepted(exchange.core2.core.common.MatcherResult result) {
        return result.resultCode() == CommandResultCode.SUCCESS
                || result.resultCode() == CommandResultCode.ACCEPTED;
    }

    private exchange.core2.core.common.MatcherResult publishNativeMatcherResult(
            int shardId, long coreSequence, long commandIdMsb, long commandIdLsb, long orderId, exchange.core2.core.common.MatcherResult result,
            com.surprising.aeron.service.settlement.TradeSettlement target) {
        Throwable poison = matcherFailure.get();
        if (poison != null)
            throw new IllegalStateException("matcher completion discarded after fatal divergence", poison);
        int nativeShard = result.symbol() <= 0 ? -1 : topology.matcherShardId(result.symbol());
        if (nativeShard >= 0 && nativeShard != shardId)
            throw new IllegalStateException("native matcher result crossed its evidence partition");
        return matcherEvidence.publishNative(coreSequence, commandIdMsb, commandIdLsb, orderId,
                matcherEvidence.nextSequence(shardId), shardId, nativeShard, result, target);
    }

    /** 在当前线程的临时槽里执行一条撤单前缀，finally 退出作用域。 */
    public exchange.core2.core.common.MatcherResult cancelDirectPrefix(
            long aeronTimestamp, long userId, long orderId, int symbolId) {
        if (symbolId <= 0)
            throw new IllegalArgumentException("invalid direct matcher cancellation prefix");
        MatcherCommandScope scope = beginSynchronousCommand(aeronTimestamp);
        try {
            return cancelNative(userId, orderId, symbolId, scope.prefixResult);
        } finally {
            endSynchronousCommand(scope);
        }
    }

    /** 可选记录 JFR 失败证据，把临时原生结果捕获到结算槽后按逻辑命令发布。防止后续复用临时槽覆盖需保留的失败事实。 */
    public exchange.core2.core.common.MatcherResult publishDirectPrefixFailure(
            int shardId, long coreSequence, java.util.UUID commandId, long logicalOrderId, exchange.core2.core.common.MatcherResult result,
            com.surprising.aeron.service.settlement.TradeSettlement target) {
        if (commandId == null) throw new IllegalArgumentException("invalid direct matcher command");
        if (MatcherPrefixFailure.TYPE.isEnabled()) {
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
        var retained = target.nativeResultSlot(0);
        retained.capture(result);
        return publishNativeMatcherResult(shardId, coreSequence, commandId.getMostSignificantBits(),
                commandId.getLeastSignificantBits(), logicalOrderId, retained, target);
    }

    /** 发布未进入订单簿的准入拒单证据，仍推进分片业务序号。 */
    public void rejectedPlaceDirect(
            int shardId, long coreSequence, java.util.UUID commandId, long orderId,
            long aeronTimestamp, String resultCode,
            com.surprising.aeron.service.settlement.TradeSettlement target) {
        if (shardId < 0 || shardId >= topology.matchingEngineCount()
                || resultCode == null || resultCode.isBlank() || target == null)
            throw new IllegalArgumentException("invalid rejected direct matcher command");
        validateCommandEvidence(coreSequence, commandId != null, orderId, aeronTimestamp);
        Throwable poison = matcherFailure.get();
        if (poison != null)
            throw new IllegalStateException("matcher completion discarded after fatal divergence", poison);
        matcherEvidence.publishRejection(coreSequence, commandId.getMostSignificantBits(), commandId.getLeastSignificantBits(), orderId,
                matcherEvidence.nextSequence(shardId), shardId, resultCode, target);
    }

    /** 校验用户和品种列表，在当前允许调用的上下文逐个确保原生品种已注册，最后登记用户。 */
    public void prepareOrderRoutes(long userId, Iterable<String> symbols) {
        if (userId <= 0 || symbols == null) {
            throw new IllegalArgumentException("invalid matcher route preparation");
        }
        for (String instrumentId : symbols) {
            if (instrumentId == null || instrumentId.isBlank()) {
                throw new IllegalArgumentException("invalid matcher route preparation instrumentId");
            }
            ensureSymbol(instrumentId);
        }
        recordUser(userId);
    }

    /** 检查 Core 序号为正、命令编号存在、订单号及时间非负。 */
    private static void validateCommandEvidence(long coreSequence, boolean commandIdPresent,
            long orderId, long aeronTimestamp) {
        if (coreSequence <= 0 || !commandIdPresent || orderId < 0 || aeronTimestamp < 0)
            throw new IllegalArgumentException("invalid matcher command evidence");
    }

    /** 先拒绝已有故障，再取得当前线程上下文并禁止嵌套调用，保存业务时间且标记活跃。调用方必须在 finally 清掉两个字段。 */
    private MatcherCommandScope beginSynchronousCommand(long aeronTimestamp) {
        Throwable failure = matcherFailure.get();
        if (failure != null) throw new IllegalStateException("matcher is poisoned by an earlier command", failure);
        MatcherCommandScope scope = currentCommandScope();
        if (scope.active) throw new IllegalStateException("nested synchronous matcher command");
        scope.active = true;
        scope.aeronTimestamp = aeronTimestamp;
        return scope;
    }

    private static void endSynchronousCommand(MatcherCommandScope scope) {
        scope.aeronTimestamp = 0;
        scope.active = false;
    }

    /** 记录首个撮合故障，后续命令一律拒绝；返回原异常供调用方重新抛出。 */
    private RuntimeException poison(RuntimeException failure) {
        matcherFailure.compareAndSet(null, failure);
        return failure;
    }

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

    /** 只有结果分类是 FATAL_DIVERGENCE 才记录首个异常，防止随后继续接受完成事实。 */
    private void poisonIfFatal(OrderMatchingResult.Control result) {
        if (result.outcome() == OrderMatchingResult.Outcome.FATAL_DIVERGENCE) {
            matcherFailure.compareAndSet(null,
                    new IllegalStateException("fatal matcher result: " + result.resultCode()));
        }
    }

    /** 让 Owner 将非空故障说明登记为首个 Matcher 故障，不在此线程继续尝试修复订单簿。 */
    public void poisonFromOwner(String detail) {
        if (detail == null || detail.isBlank()) throw new IllegalArgumentException("matcher poison detail is required");
        matcherFailure.compareAndSet(null, new IllegalStateException(detail));
    }

    /** 仅为启用滑点的市价单计算保护价：读对手一档价格，向内取整滑点距离后与原界限收紧；对手侧为空时保留原界限。 */
    private long protectedMarketPrice(int symbolId, CoreOrderSide side,
            com.surprising.aeron.protocol.CoreOrderType type, long originalBound, long slippagePpm) {
        if (type != com.surprising.aeron.protocol.CoreOrderType.MARKET || slippagePpm == 0) return originalBound;
        boolean buy = side == CoreOrderSide.BUY;
        L2MarketData top = engine(symbolId).orderBook(symbolId, 1);
        if (buy ? top.askSize == 0 : top.bidSize == 0) return originalBound;
        long best = buy ? top.askPrices[0] : top.bidPrices[0];
        long distance = Math.addExact(Math.multiplyExact(best / 1_000_000L, slippagePpm),
                (best % 1_000_000L) * slippagePpm / 1_000_000L);
        return buy ? Math.min(originalBound, Math.addExact(best, distance))
                : Math.max(originalBound, Math.subtractExact(best, distance));
    }

    private static long slippagePpm(ProductLine product) {
        return product == ProductLine.LINEAR_PERPETUAL || product == ProductLine.LINEAR_DELIVERY ? 100 : 0;
    }

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

    /** 按给定顺序撤单，超过 1024 项直接拒绝。 */
    private OrderMatchingResult.Control cancelRoutedBatch(List<CancellationOrder> orders) {
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
                boolean accepted = nativeAccepted(result);
                cancellations.add(new OrderCancelResult(order.orderId(), accepted));
                OrderMatchingResult.Control.appendEvents(events, result.events());
                if (accepted) successfulPrefix++;
                else failureCode = result.resultCode().name();
            } catch (RuntimeException failure) {
                throw poison(failure);
            }
        }
        return new OrderMatchingResult.Control(failureCode == null, failureCode == null ? "SUCCESS" : failureCode,
                cancellations, failureCode != null && successfulPrefix != 0,
                java.util.Collections.unmodifiableList(events));
    }

    /** 要求请求与结果等长且各结果非空，生成逐项撤单回执并统计首个失败之前的成功数，按原顺序组合事件。 */
    private OrderMatchingResult.Control aggregateRoutedCancellationResults(
            List<CancellationOrder> requested, List<OrderMatchingResult.Control> results) {
        if (requested == null || results == null || requested.size() != results.size())
            throw new IllegalArgumentException("cancellation aggregation size mismatch");
        List<OrderCancelResult> cancellations = new ArrayList<>(requested.size());
        int successfulPrefix = 0;
        OrderMatchingResult.Control failure = null;
        for (int index = 0; index < results.size(); index++) {
            OrderMatchingResult.Control result = java.util.Objects.requireNonNull(results.get(index), "cancellation result");
            cancellations.add(new OrderCancelResult(requested.get(index).orderId(), result.accepted()));
            if (failure == null && result.accepted()) successfulPrefix++;
            else if (failure == null) failure = result;
        }
        boolean accepted = failure == null;
        return new OrderMatchingResult.Control(accepted, accepted ? "SUCCESS" : failure.resultCode(), cancellations,
                !accepted && successfulPrefix != 0, OrderMatchingResult.Control.concatenateEvents(results));
    }

    private OrderMatchingResult.Control cancelControlNative(long userId, long orderId, int symbolId) {
        var result = cancelNative(userId, orderId, symbolId);
        matcherEvidence.recordControlNative(topology.matcherShardId(result.symbol()), result.sequence());
        return OrderMatchingResult.Control.fromNative(result);
    }

    private exchange.core2.core.common.MatcherResult cancelNative(long userId, long orderId, int symbolId) {
        symbolId = ensureSymbol(symbolId);
        recordUser(userId);
        return engine(symbolId).cancel(currentCommandScope().aeronTimestamp, orderId, symbolId, userId);
    }

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

    /** 在当前命令作用域按既定顺序执行控制撤单并绑定控制分片负一的业务证据。运行异常记录故障，已执行前缀不回滚。 */
    public OrderMatchingResult.Control cancelControlOrders(long coreSequence, java.util.UUID commandId,
            long timestamp, List<CancellationOrder> orders) {
        validateCommandEvidence(coreSequence, commandId != null, 0, timestamp);
        java.util.Objects.requireNonNull(orders, "control cancellations");
        MatcherCommandScope scope = beginSynchronousCommand(timestamp);
        try {
            return bindControlEvidence(coreSequence, commandId,
                    matcherEvidence.nextSequence(-1), cancelRoutedBatch(orders));
        } catch (RuntimeException failure) {
            throw poison(failure);
        } finally { endSynchronousCommand(scope); }
    }

    /** 由目标分片线程执行一条控制撤单，只记录原生序号。Owner 后续汇总控制事实。 */
    public OrderMatchingResult.Control cancelControlOrder(long timestamp, long userId, long orderId, int symbolId) {
        if (timestamp < 0 || userId <= 0 || orderId <= 0)
            throw new IllegalArgumentException("invalid control cancellation");
        MatcherCommandScope scope = beginSynchronousCommand(timestamp);
        try {
            return cancelControlNative(userId, orderId, symbolId);
        } catch (RuntimeException failure) {
            throw poison(failure);
        } finally { endSynchronousCommand(scope); }
    }

    /** 仅聚合已经在原生分片执行过的撤单结果，绑定一次控制业务证据，不重新操作任何订单簿。 */
    public OrderMatchingResult.Control completeControlCancellations(long coreSequence, java.util.UUID commandId,
            long timestamp, List<CancellationOrder> orders, List<OrderMatchingResult.Control> results) {
        validateCommandEvidence(coreSequence, commandId != null, 0, timestamp);
        return bindControlEvidence(coreSequence, commandId,
                matcherEvidence.nextSequence(-1), aggregateRoutedCancellationResults(orders, results));
    }

    /** 由所属分片线程先逐单对账并取哈希，再同步持久化该分片原生快照。 */
    public MatcherShardSnapshot captureShardSnapshot(
            int shardId, long snapshotId, long coreSequence, Iterable<CoreOrderState> activeOrders) {
        return snapshotSupport.captureShardSnapshot(shardId, snapshotId, coreSequence, activeOrders);
    }

    /** 要求分片结果数量完整，汇总哈希、导出已写入的模块和证据进度、物化用户集合并构造快照。 */
    public OrderBookSnapshot finishShardedSnapshot(
            long snapshotId, long coreSequence, long businessStateHash, TradingCoreState state,
            List<MatcherShardSnapshot> shardSnapshots) {
        return snapshotSupport.finishShardedSnapshot(snapshotId, coreSequence, businessStateHash, state, shardSnapshots);
    }

    /** 全部分片捕获结束后释放失败快照的原生模块。 */
    public void discardSnapshot(long snapshotId) {
        serializationProcessor.removeSnapshot(snapshotId);
    }

    /** 校验深度 1 至 100 并规范品种字符串，未登记返回空列表。已登记则在调用线程读订单簿，转换结果排序为买卖侧再价格升序。 */
    public List<CoreBookLevelView> orderBookLevels(String requestedSymbol, int depth) {
        return snapshotSupport.orderBookLevels(requestedSymbol, depth);
    }

    /** 在当前分片线程筛选路由表，按品种名读取本分片各盘口并转换为启动视图，深度限制 1 至 100。 */
    public OrderBookDepthSnapshot orderBookBootstrapShard(int shardId, int depth) {
        return snapshotSupport.orderBookBootstrapShard(shardId, depth);
    }

    /** 要求结果数等于拓扑分片数，拼接品种与档位后统一排序。 */
    public OrderBookDepthSnapshot mergeBookBootstrapShards(List<OrderBookDepthSnapshot> shards) {
        return snapshotSupport.mergeBookBootstrapShards(shards);
    }

    /** 以空白初始状态同步创建全部引擎，不启动独立工作线程。 */
    private void start() {
        start(null);
    }

    /** 重新分配全体分片引擎数组，逐个在当前线程构造空白或恢复的订单簿，最后登记已创建数量。 */
    private void start(OrderBookSnapshot snapshot) {
        engines = new SynchronousMatchingEngine[topology.matchingEngineCount()];
        for (int shardId = 0; shardId < engines.length; shardId++) startShard(shardId, snapshot);
        activatedShardCount.set(engines.length);
    }

    /** 在当前线程创建一个 MATCHING_ONLY 同步引擎，原生每个实例只有一个 Matcher，账户风险和原生日志关闭。 */
    private void startShard(int shardId, OrderBookSnapshot snapshot) {
        shardThreads[shardId] = Thread.currentThread();
        InitialStateConfiguration initialState = snapshot == null
                ? InitialStateConfiguration.cleanStart("aeron-authoritative-book")
                : InitialStateConfiguration.fromSnapshotOnly(
                        "aeron-authoritative-book", snapshot.snapshotId(), 0);
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
                        .serializationProcessorFactory(ignored ->
                                new ShardSerializationProcessor(serializationProcessor, shardId))
                        .build())
                .build();
        engines[shardId] = new SynchronousMatchingEngine(configuration);
    }

    /** 校验品种字符串并预留稳定路由编号，返回目标 Matcher 分片。 */
    public int matcherShardId(String instrumentId) {
        if (instrumentId == null || instrumentId.isBlank()) throw new IllegalArgumentException("matcher instrumentId is required");
        return topology.matcherShardId(reserveSymbolId(instrumentId));
    }
    /** 把外部数字品种字符串解析成永久编号，再走当前线程的原生品种注册路径。 */
    private int ensureSymbol(String instrumentId) {
        return ensureSymbol(permanentInstrumentId(instrumentId));
    }

    /** 先查本线程上下文的注册集合，未注册则建立数字字符串路由并向所属引擎注册零费率、单位缩放的撮合品种。 */
    private int ensureSymbol(int symbolId) {
        if (symbolId <= 0) throw new IllegalArgumentException("positive instrumentId is required");
        var registeredSymbols = currentCommandScope().registeredSymbols;
        if (registeredSymbols.contains(symbolId)) return symbolId;
        String instrumentId = Integer.toString(symbolId);
        registerSymbolId(instrumentId);
        CoreSymbolSpecification specification = CoreSymbolSpecification.builder()
                .symbolId(symbolId).type(SymbolType.CURRENCY_EXCHANGE_PAIR)
                .baseCurrency(stableId("BASE:" + instrumentId)).quoteCurrency(stableId("QUOTE:" + instrumentId))
                .baseScaleK(1).quoteScaleK(1).makerFee(0).takerFee(0).marginBuy(0).marginSell(0).build();
        CommandResultCode result = engine(symbolId).registerSymbol(specification);
        if (result != CommandResultCode.SUCCESS
                && result != CommandResultCode.SYMBOL_MGMT_SYMBOL_ALREADY_EXISTS) {
            throw new IllegalStateException("failed to add exchange-core instrumentId " + instrumentId + ": " + result);
        }
        registeredSymbols.add(symbolId);
        return symbolId;
    }

    private int permanentInstrumentId(String instrumentId) {
        return com.surprising.product.api.utils.InstrumentIds.parse(instrumentId);
    }

    /** 已有字符串路由直接读取并发字典，新路由进入双向表冲突检查。 */
    private int reserveSymbolId(String instrumentId) {
        Integer existing = symbols.get(instrumentId);
        return existing != null ? existing : registerSymbolId(instrumentId);
    }

    /** 没有全局 synchronized 块，依赖并发表原子 putIfAbsent。 */
    private int registerSymbolId(String instrumentId) {
        Integer existing = symbols.get(instrumentId);
        if (existing != null) return existing;
        int symbolId = permanentInstrumentId(instrumentId);
        String collision = symbolNames.putIfAbsent(symbolId, instrumentId);
        if (collision != null && !collision.equals(instrumentId)) {
            throw new IllegalStateException("stable matcher instrumentId id collision: " + instrumentId + '/' + collision);
        }
        symbols.putIfAbsent(instrumentId, symbolId);
        return symbolId;
    }

    /** 此方法不再单独检查该元素已激活或调用线程。 */
    SynchronousMatchingEngine engine(int symbolId) {
        if (engines == null) throw new IllegalStateException("matcher engines are not active");
        return engines[topology.matcherShardId(symbolId)];
    }

    private static int stableId(String value) {
        int hash = 0x811c9dc5;
        for (int index = 0; index < value.length(); index++) {
            hash ^= value.charAt(index);
            hash *= 0x01000193;
        }
        return hash & 0x7fffffff;
    }

    /** 一个分片已完成冷态快照或读取后的哈希结果，不携带模块字节。 */
    public record MatcherShardSnapshot(int shardId, int engineHash) {
        public MatcherShardSnapshot {
            if (shardId < 0) throw new IllegalArgumentException("matcher shard id must be non-negative");
        }
    }

    private void stop() {
        if (engines != null) {
            RuntimeException failure = null;
            for (SynchronousMatchingEngine matcher : engines) {
                try {
                    if (matcherFailure.get() == null) matcher.stateHashReport();
                    matcher.close();
                } catch (RuntimeException closeFailure) {
                    if (failure == null) failure = closeFailure;
                    else failure.addSuppressed(closeFailure);
                }
            }
            engines = null;
            if (failure != null) throw failure;
        }
    }

    /** 只关闭指定已存在的引擎，未启动时直接返回。 */
    public void closeShard(int shardId) {
        SynchronousMatchingEngine[] active = engines;
        if (active == null) return;
        if (shardId < 0 || shardId >= active.length) {
            throw new IllegalArgumentException("matcher shard is outside configured topology");
        }
        SynchronousMatchingEngine matcher = active[shardId];
        if (matcher == null) return;
        if (matcherFailure.get() == null) matcher.stateHashReport();
        matcher.close();
        active[shardId] = null;
    }

    /** 执行整体停止流程，传播引擎关闭异常。 */
    @Override
    public void close() {
        stop();
    }

    private static Throwable unwrap(Throwable failure) {
        return failure instanceof java.util.concurrent.CompletionException && failure.getCause() != null
                ? failure.getCause() : failure;
    }

    /** 按确定性顺序执行控制撤单需要的最小身份，不携带账户余额或撤单执行状态。 */
    public record CancellationOrder(long orderId, long userId, int symbolId) {
        public CancellationOrder(long orderId, long userId, String instrumentId) {
            this(orderId, userId, com.surprising.product.api.utils.InstrumentIds.parse(instrumentId));
        }
        public CancellationOrder {
            if (orderId <= 0 || userId <= 0 || symbolId <= 0) {
                throw new IllegalArgumentException("invalid cancellation order");
            }
        }
    }
}
