package com.surprising.aeron.service.matching;

import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreBookLevelView;
import com.surprising.aeron.service.exception.FatalMatchingDivergenceException;
import com.surprising.aeron.service.state.instrument.CoreInstrument;
import com.surprising.aeron.service.state.ResolvedPlaceOrder;
import com.surprising.aeron.service.state.model.CoreOrderState;
import com.surprising.aeron.service.state.TradingCoreState;
import com.surprising.aeron.service.state.LaneTopology;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.function.Function;
import org.eclipse.collections.impl.set.mutable.primitive.LongHashSet;

public final class DeterministicExchangeCoreAdapter implements AutoCloseable {

    /** Rare failed native cancellation prefix; never allocated for successful trading. */
    @jdk.jfr.Name("surprising.MatcherPrefixFailure")
    @jdk.jfr.Category("Surprising Core")
    @jdk.jfr.StackTrace(false)
    static final class MatcherPrefixFailure extends jdk.jfr.Event {
        private static final jdk.jfr.EventType TYPE = jdk.jfr.EventType.getEventType(MatcherPrefixFailure.class);
        public long coreSequence, commandIdHigh, commandIdLow, logicalOrderId, canceledOrderId, userId;
        public int matcherShard, symbolId;
        public String nativeResultCode;
    }

    private SynchronousMatchingEngine[] engines;
    private final InMemorySerializationProcessor serializationProcessor =
            new InMemorySerializationProcessor();
    private final Map<String, Integer> symbols = new ConcurrentHashMap<>();
    private final Map<Integer, String> symbolNames = new ConcurrentHashMap<>();
    private final Set<Integer> registeredSymbols = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<Long, SnapshotOperation> snapshotOperations = new ConcurrentHashMap<>();
    private final LaneTopology topology;
    private final AtomicReference<Throwable> matcherFailure = new AtomicReference<>();
    private final MatcherEvidenceLedger matcherEvidence;
    private final Function<Supplier<CompletableFuture<CommandResultCode>>, CompletableFuture<CommandResultCode>>
            snapshotPersistence;
    private Runnable deferredActivation;
    private boolean activationComplete;
    private final AtomicInteger activatedShardCount = new AtomicInteger();
    private MatcherSnapshot restoredSnapshot;
    private List<CoreOrderState> restoredActiveOrders = List.of();
    private long restoredCoreSequence;
    private MatcherShardSnapshot[] restoredShardHashes;
    private static final class MatcherCommandScope {
        long aeronTimestamp;
        boolean active;
        final LongHashSet users = new LongHashSet();
        final exchange.core2.core.common.MatcherResult prefixResult = exchange.core2.core.common.MatcherResult.commandSlot();
    }

    /** Each activated shard owns its command scratch and user registry. */
    private MatcherCommandScope[] shardCommandScopes;
    private Thread[] shardThreads;
    private final Thread standaloneThread = Thread.currentThread();
    private final MatcherCommandScope standaloneCommandScope = new MatcherCommandScope();

    private void initializeCommandScopes() {
        shardCommandScopes = new MatcherCommandScope[topology.matchingEngineCount()];
        shardThreads = new Thread[shardCommandScopes.length];
        for (int i = 0; i < shardCommandScopes.length; i++) shardCommandScopes[i] = new MatcherCommandScope();
    }

    private MatcherCommandScope currentCommandScope() {
        Thread current = Thread.currentThread();
        if (current instanceof com.surprising.aeron.service.matcher.MatcherThread mt) {
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

    private void recordUser(long userId) {
        currentCommandScope().users.add(userId);
    }

    /** Snapshot/hash are cold boundaries; materialize the protocol Set only here. */
    private Set<Long> snapshotUsers() {
        Set<Long> snapshot = new java.util.TreeSet<>();
        standaloneCommandScope.users.forEach((long userId) -> snapshot.add(userId));
        for (MatcherCommandScope scope : shardCommandScopes) {
            scope.users.forEach((long userId) -> snapshot.add(userId));
        }
        return snapshot;
    }

    public DeterministicExchangeCoreAdapter() {
        this(true);
    }

    public DeterministicExchangeCoreAdapter(boolean startImmediately) {
        this(startImmediately, submission -> submission.get());
    }

    DeterministicExchangeCoreAdapter(
            Function<Supplier<CompletableFuture<CommandResultCode>>, CompletableFuture<CommandResultCode>>
                    snapshotPersistence) {
        this(true, snapshotPersistence);
    }

    private DeterministicExchangeCoreAdapter(
            boolean startImmediately,
            Function<Supplier<CompletableFuture<CommandResultCode>>, CompletableFuture<CommandResultCode>>
                    snapshotPersistence) {
        this.topology = LaneTopology.configured(Boolean.getBoolean("surprising.aeron.p10-characterization"));
        initializeCommandScopes();
        this.matcherEvidence = new MatcherEvidenceLedger(topology);
        this.snapshotPersistence = java.util.Objects.requireNonNull(snapshotPersistence, "snapshotPersistence");
        if (startImmediately) {
            start();
            activationComplete = true;
        }
    }

    public DeterministicExchangeCoreAdapter(
            TradingCoreState state,
            Iterable<CoreOrderState> activeOrders,
            long coreSequence,
            MatcherSnapshot snapshot) {
        this(state, activeOrders, coreSequence, snapshot, true);
    }

    public DeterministicExchangeCoreAdapter(
            TradingCoreState state,
            Iterable<CoreOrderState> activeOrders,
            long coreSequence,
            MatcherSnapshot snapshot,
            boolean startImmediately) {
        this(state, activeOrders, coreSequence, snapshot, startImmediately,
                state == null ? 0 : state.businessStateHash());
    }

    public DeterministicExchangeCoreAdapter(
            TradingCoreState state,
            Iterable<CoreOrderState> activeOrders,
            long coreSequence,
            MatcherSnapshot snapshot,
            boolean startImmediately,
            long expectedCoreBusinessStateHash) {
        this.snapshotPersistence = submission -> submission.get();
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
            snapshot.symbols().forEach((instrumentId, symbolId) -> {
                String previous = symbolNames.put(symbolId, instrumentId);
                if (previous != null && !previous.equals(instrumentId)) {
                    throw new IllegalArgumentException("matcher instrumentId registry collision");
                }
                symbols.put(instrumentId, symbolId);
            });
            // 路由表也含尚未创建订单簿的 instrumentId，不能作为 native 注册完成的证据。
            // 恢复后的首次使用仍经 ensureSymbol；native 对已有订单簿的注册是幂等操作。
            snapshot.users().forEach(standaloneCommandScope.users::add);
            List<CoreOrderState> restoredOrders = new ArrayList<>();
            activeOrders.forEach(restoredOrders::add);
            restoredSnapshot = snapshot;
            restoredActiveOrders = List.copyOf(restoredOrders);
            restoredCoreSequence = coreSequence;
            deferredActivation = () -> activateRestoredMatcher(
                    state, restoredOrders, coreSequence, snapshot);
            if (startImmediately) activate();
        } catch (RuntimeException exception) {
            stop();
            Throwable cause = unwrap(exception);
            if (cause instanceof FatalMatchingDivergenceException divergence) throw divergence;
            throw new FatalMatchingDivergenceException("matcher restore", coreSequence,
                    snapshot.snapshotId(), "native snapshot validation failed", cause);
        }
    }

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

    public synchronized boolean activated() { return activationComplete; }

    /** Starts one independent synchronous engine on the thread that will own that shard. */
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
            reconcileOpenOrdersShard(restoredActiveOrders, shardId, restoredCoreSequence,
                    restoredSnapshot.snapshotId(), "matcher restore");
            StateHashes hashes = stateHashes(shardId);
            restoredShardHashes[shardId] = new MatcherShardSnapshot(
                    shardId, hashes.engineHash(), hashes.bookHash());
        }
        if (activatedShardCount.incrementAndGet() == topology.matchingEngineCount()) {
            if (restoredSnapshot != null) {
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

    private void activateRestoredMatcher(TradingCoreState state, Iterable<CoreOrderState> activeOrders,
                                         long coreSequence, MatcherSnapshot snapshot) {
        start(snapshot);
        reconcileOpenOrdersAsync(activeOrders, coreSequence, snapshot.snapshotId(), "matcher restore").join();
        StateHashes restoredHashes = currentStateHashesAsync().join();
        if (restoredHashes.engineHash() != snapshot.engineStateHash()) {
            throw new FatalMatchingDivergenceException("matcher restore", coreSequence,
                    snapshot.snapshotId(), "restored exchange-core state hash mismatch"
                            + " (expectedEngine=" + snapshot.engineStateHash()
                            + ", actualEngine=" + restoredHashes.engineHash() + ')');
        }
    }

    private exchange.core2.core.common.MatcherResult placeNative(long userId, CoreMatchingOrder command, exchange.core2.core.common.MatcherResult target) {
        int symbolId = ensureSymbol(command.instrumentId());
        recordUser(userId);
        long price = protectedMarketPrice(symbolId, command.side(), command.orderType(),
                command.matchingPriceTicks(), command.maxSlippagePpm());
        return engine(symbolId).placeInto(target,
                currentCommandScope().aeronTimestamp, command.orderId(), 0,
                price, command.matchingPriceTicks(), command.quantitySteps(),
                command.side() == CoreOrderSide.BUY ? OrderAction.BID : OrderAction.ASK,
                orderType(command), symbolId, userId);
    }

    private exchange.core2.core.common.MatcherResult placeNative(long userId, ResolvedPlaceOrder command, exchange.core2.core.common.MatcherResult target) {
        int symbolId = ensureSymbol(command.instrumentId());
        recordUser(userId);
        long price = protectedMarketPrice(symbolId, command.side(), command.orderType(),
                command.matchingPriceTicks(), CoreMatchingOrder.slippagePpm(command.instrument().contractType().productLine()));
        return engine(symbolId).placeInto(target,
                currentCommandScope().aeronTimestamp, command.orderId(), 0,
                price, command.matchingPriceTicks(), command.quantitySteps(),
                command.side() == CoreOrderSide.BUY ? OrderAction.BID : OrderAction.ASK,
                orderType(command.orderType(), command.timeInForce()), symbolId, userId);
    }

    /** Matcher converts the batch item's frozen admission without constructing a CoreMatchingOrder. */
    private exchange.core2.core.common.MatcherResult placeNativeAdmitted(long userId,
            com.surprising.aeron.service.state.OrderRuntime command, exchange.core2.core.common.MatcherResult target) {
        int symbolId = ensureSymbol(command.instrument().instrumentId());
        recordUser(userId);
        long price = protectedMarketPrice(symbolId, command.side(), command.orderType(),
                command.matchingPriceTicks(), CoreMatchingOrder.slippagePpm(command.productLine()));
        return engine(symbolId).placeInto(target,
                currentCommandScope().aeronTimestamp, command.orderId(), 0,
                price, command.matchingPriceTicks(), command.remainingQuantitySteps(),
                command.side() == CoreOrderSide.BUY ? OrderAction.BID : OrderAction.ASK,
                orderType(command.orderType(), command.timeInForce()), symbolId, userId);
    }

    /** The existing Matcher slot owns this call; Account Lane authorities are rejected before touching the book. */
    public exchange.core2.core.common.MatcherResult placeAdmittedDirect(
            int shardId, long coreSequence, java.util.UUID commandId, long aeronTimestamp,
            long userId, com.surprising.aeron.service.state.OrderRuntime command,
            com.surprising.aeron.service.state.MatcherSettlementEvent target) {
        if (shardId < 0 || shardId >= topology.matchingEngineCount() || command == null || target == null)
            throw new IllegalArgumentException("invalid direct matcher command");
        command.requireFrozenMatcherInput(userId);
        validateCommandEvidence(coreSequence, commandId, command.orderId(), aeronTimestamp);
        MatcherCommandScope scope = beginSynchronousCommand(aeronTimestamp);
        try {
            return publishNativeMatcherResult(shardId, coreSequence, commandId, command.orderId(),
                    aeronTimestamp, placeNativeAdmitted(userId, command, target.nativeResultSlot(0)), target);
        } catch (RuntimeException exception) {
            matcherFailure.compareAndSet(null, exception);
            throw exception;
        } finally {
            scope.aeronTimestamp = 0;
            scope.active = false;
        }
    }

    /** Hot path: publish the native immutable result into the already-owned settlement event. */
    public exchange.core2.core.common.MatcherResult placeDirect(
            int shardId, long coreSequence, java.util.UUID commandId, long aeronTimestamp,
            long userId, ResolvedPlaceOrder command,
            com.surprising.aeron.service.state.MatcherSettlementEvent target) {
        if (shardId < 0 || shardId >= topology.matchingEngineCount() || command == null || target == null)
            throw new IllegalArgumentException("invalid direct matcher command");
        validateCommandEvidence(coreSequence, commandId, command.orderId(), aeronTimestamp);
        MatcherCommandScope scope = beginSynchronousCommand(aeronTimestamp);
        try {
            return publishNativeMatcherResult(shardId, coreSequence, commandId, command.orderId(),
                    aeronTimestamp, placeNative(userId, command, target.nativeResultSlot(0)), target);
        } catch (RuntimeException exception) {
            matcherFailure.compareAndSet(null, exception);
            throw exception;
        } finally {
            scope.aeronTimestamp = 0;
            scope.active = false;
        }
    }

    public exchange.core2.core.common.MatcherResult placeDirect(
            int shardId, long coreSequence, java.util.UUID commandId, long aeronTimestamp,
            long userId, CoreMatchingOrder command,
            com.surprising.aeron.service.state.MatcherSettlementEvent target) {
        if (shardId < 0 || shardId >= topology.matchingEngineCount() || command == null || target == null)
            throw new IllegalArgumentException("invalid direct matcher command");
        validateCommandEvidence(coreSequence, commandId, command.orderId(), aeronTimestamp);
        MatcherCommandScope scope = beginSynchronousCommand(aeronTimestamp);
        try {
            return publishNativeMatcherResult(shardId, coreSequence, commandId, command.orderId(),
                    aeronTimestamp, placeNative(userId, command, target.nativeResultSlot(0)), target);
        } catch (RuntimeException exception) {
            matcherFailure.compareAndSet(null, exception);
            throw exception;
        } finally {
            scope.aeronTimestamp = 0;
            scope.active = false;
        }
    }

    public exchange.core2.core.common.MatcherResult cancelDirect(
            int shardId, long coreSequence, java.util.UUID commandId, long orderId,
            long aeronTimestamp, long userId, String instrumentId,
            com.surprising.aeron.service.state.MatcherSettlementEvent target) {
        if (shardId < 0 || shardId >= topology.matchingEngineCount()
                || instrumentId == null || instrumentId.isBlank() || target == null)
            throw new IllegalArgumentException("invalid direct matcher cancellation");
        validateCommandEvidence(coreSequence, commandId, orderId, aeronTimestamp);
        MatcherCommandScope scope = beginSynchronousCommand(aeronTimestamp);
        try {
            return publishNativeMatcherResult(shardId, coreSequence, commandId, orderId,
                    aeronTimestamp, cancelNative(userId, orderId, instrumentId, target.nativeResultSlot(0)), target);
        } catch (RuntimeException exception) {
            matcherFailure.compareAndSet(null, exception);
            throw exception;
        } finally {
            scope.aeronTimestamp = 0;
            scope.active = false;
        }
    }

    public exchange.core2.core.common.MatcherResult replaceDirect(
            int shardId, long coreSequence, java.util.UUID commandId, long orderId,
            long aeronTimestamp, long userId, long originalOrderId, String instrumentId,
            ResolvedPlaceOrder replacement,
            com.surprising.aeron.service.state.MatcherSettlementEvent target) {
        if (shardId < 0 || shardId >= topology.matchingEngineCount()
                || instrumentId == null || instrumentId.isBlank() || replacement == null || target == null)
            throw new IllegalArgumentException("invalid direct matcher replacement");
        validateCommandEvidence(coreSequence, commandId, orderId, aeronTimestamp);
        MatcherCommandScope scope = beginSynchronousCommand(aeronTimestamp);
        try {
            exchange.core2.core.common.MatcherResult cancellation =
                    cancelNative(userId, originalOrderId, instrumentId, target.replacementCancellationSlot());
            exchange.core2.core.common.MatcherResult result = nativeAccepted(cancellation)
                    ? placeNative(userId, replacement, target.nativeResultSlot(0)) : cancellation;
            int nativeShard = result.symbol() <= 0 ? shardId : topology.matcherShardId(result.symbol());
            if (nativeShard != shardId)
                throw new IllegalStateException("native matcher replacement crossed its evidence partition");
            return matcherEvidence.publishReplacement(coreSequence, commandId, orderId, aeronTimestamp,
                    matcherEvidence.nextSequence(shardId), shardId, nativeShard,
                    cancellation, result, target);
        } catch (RuntimeException exception) {
            matcherFailure.compareAndSet(null, exception);
            throw exception;
        } finally {
            scope.aeronTimestamp = 0;
            scope.active = false;
        }
    }

    public exchange.core2.core.common.MatcherResult placeDirectBatchItem(
            int shardId, int index, long coreSequence, java.util.UUID commandId, long aeronTimestamp,
            long userId, ResolvedPlaceOrder command,
            com.surprising.aeron.service.state.MatcherSettlementEvent target) {
        if (shardId < 0 || shardId >= topology.matchingEngineCount() || index < 0
                || command == null || target == null)
            throw new IllegalArgumentException("invalid direct matcher batch item");
        validateCommandEvidence(coreSequence, commandId, command.orderId(), aeronTimestamp);
        MatcherCommandScope scope = beginSynchronousCommand(aeronTimestamp);
        try {
            exchange.core2.core.common.MatcherResult result = placeNative(userId, command, target.nativeResultSlot(index));
            int nativeShard = result.symbol() <= 0 ? -1 : topology.matcherShardId(result.symbol());
            if (nativeShard >= 0 && nativeShard != shardId)
                throw new IllegalStateException("native matcher batch crossed its evidence partition");
            return matcherEvidence.publishBatchItem(coreSequence, commandId, command.orderId(),
                    aeronTimestamp, matcherEvidence.nextSequence(shardId), shardId, nativeShard,
                    result, index, target);
        } catch (RuntimeException exception) {
            matcherFailure.compareAndSet(null, exception);
            throw exception;
        } finally {
            scope.aeronTimestamp = 0;
            scope.active = false;
        }
    }

    public exchange.core2.core.common.MatcherResult cancelDirectBatchItem(
            int shardId, int index, long coreSequence, java.util.UUID commandId, long orderId,
            long aeronTimestamp, long userId, String instrumentId,
            com.surprising.aeron.service.state.MatcherSettlementEvent target) {
        if (shardId < 0 || shardId >= topology.matchingEngineCount() || index < 0
                || instrumentId == null || instrumentId.isBlank() || target == null)
            throw new IllegalArgumentException("invalid direct matcher cancellation item");
        validateCommandEvidence(coreSequence, commandId, orderId, aeronTimestamp);
        MatcherCommandScope scope = beginSynchronousCommand(aeronTimestamp);
        try {
            exchange.core2.core.common.MatcherResult result = cancelNative(userId, orderId, instrumentId, target.nativeResultSlot(index));
            int nativeShard = result.symbol() <= 0 ? shardId : topology.matcherShardId(result.symbol());
            if (nativeShard != shardId)
                throw new IllegalStateException("native matcher cancellation crossed its evidence partition");
            return matcherEvidence.publishBatchItem(coreSequence, commandId, orderId,
                    aeronTimestamp, matcherEvidence.nextSequence(shardId), shardId, nativeShard,
                    result, index, target);
        } catch (RuntimeException exception) {
            matcherFailure.compareAndSet(null, exception);
            throw exception;
        } finally {
            scope.aeronTimestamp = 0;
            scope.active = false;
        }
    }

    private static boolean nativeAccepted(exchange.core2.core.common.MatcherResult result) {
        return result.resultCode() == CommandResultCode.SUCCESS
                || result.resultCode() == CommandResultCode.ACCEPTED;
    }

    private exchange.core2.core.common.MatcherResult publishNativeMatcherResult(
            int shardId, long coreSequence, java.util.UUID commandId, long orderId,
            long aeronTimestamp, exchange.core2.core.common.MatcherResult result,
            com.surprising.aeron.service.state.MatcherSettlementEvent target) {
        Throwable poison = matcherFailure.get();
        if (poison != null)
            throw new IllegalStateException("matcher completion discarded after fatal divergence", poison);
        int nativeShard = result.symbol() <= 0 ? -1 : topology.matcherShardId(result.symbol());
        if (nativeShard >= 0 && nativeShard != shardId)
            throw new IllegalStateException("native matcher result crossed its evidence partition");
        return matcherEvidence.publishNative(coreSequence, commandId, orderId, aeronTimestamp,
                matcherEvidence.nextSequence(shardId), shardId, nativeShard, result, target);
    }

    public exchange.core2.core.common.MatcherResult cancelDirectPrefix(
            long aeronTimestamp, long userId, long orderId, String instrumentId) {
        if (instrumentId == null || instrumentId.isBlank())
            throw new IllegalArgumentException("invalid direct matcher cancellation prefix");
        MatcherCommandScope scope = beginSynchronousCommand(aeronTimestamp);
        try {
            return cancelNative(userId, orderId, instrumentId, scope.prefixResult);
        } finally {
            scope.aeronTimestamp = 0;
            scope.active = false;
        }
    }

    public exchange.core2.core.common.MatcherResult publishDirectPrefixFailure(
            int shardId, long coreSequence, java.util.UUID commandId, long logicalOrderId,
            long aeronTimestamp, exchange.core2.core.common.MatcherResult result,
            com.surprising.aeron.service.state.MatcherSettlementEvent target) {
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
        return publishNativeMatcherResult(shardId, coreSequence, commandId, logicalOrderId,
                aeronTimestamp, retained, target);
    }

    /** Ordinary Lane admission already owns the resolved order; avoid rebuilding a matcher DTO. */

    public void rejectedPlaceDirect(
            int shardId, long coreSequence, java.util.UUID commandId, long orderId,
            long aeronTimestamp, String resultCode,
            com.surprising.aeron.service.state.MatcherSettlementEvent target) {
        if (shardId < 0 || shardId >= topology.matchingEngineCount()
                || resultCode == null || resultCode.isBlank() || target == null)
            throw new IllegalArgumentException("invalid rejected direct matcher command");
        validateCommandEvidence(coreSequence, commandId, orderId, aeronTimestamp);
        Throwable poison = matcherFailure.get();
        if (poison != null)
            throw new IllegalStateException("matcher completion discarded after fatal divergence", poison);
        matcherEvidence.publishRejection(coreSequence, commandId, orderId,
                matcherEvidence.nextSequence(shardId), shardId, resultCode, target);
    }

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

    private static void validateCommandEvidence(long coreSequence, java.util.UUID commandId,
            long orderId, long aeronTimestamp) {
        if (coreSequence <= 0 || commandId == null || orderId < 0
                || aeronTimestamp < 0)
            throw new IllegalArgumentException("invalid matcher command evidence");
    }

    private MatcherCommandScope beginSynchronousCommand(long aeronTimestamp) {
        Throwable failure = matcherFailure.get();
        if (failure != null) throw new IllegalStateException("matcher is poisoned by an earlier command", failure);
        MatcherCommandScope scope = currentCommandScope();
        if (scope.active) throw new IllegalStateException("nested synchronous matcher command");
        scope.active = true;
        scope.aeronTimestamp = aeronTimestamp;
        return scope;
    }

    private CoreMatchingResult bindMatcherEvidence(
            long coreSequence,
            java.util.UUID commandId,
            long orderId,
            long aeronTimestamp,
            long sequence,
            int matcherShardId,
            CoreMatchingResult result) {
        if (result == null) throw new IllegalStateException("matcher command returned no result");
        Throwable matcherPoison = matcherFailure.get();
        if (matcherPoison != null) {
            throw new IllegalStateException("matcher completion discarded after fatal divergence", matcherPoison);
        }
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

    private void poisonIfFatal(CoreMatchingResult result) {
        if (result.outcome() == MatchingResult.Outcome.FATAL_DIVERGENCE) {
            matcherFailure.compareAndSet(null,
                    new IllegalStateException("fatal matcher result: " + result.resultCode()));
        }
    }

    public void poisonFromOwner(String detail) {
        if (detail == null || detail.isBlank()) throw new IllegalArgumentException("matcher poison detail is required");
        matcherFailure.compareAndSet(null, new IllegalStateException(detail));
    }

    private static final class DirectTopPriceReader {
        private static final java.lang.reflect.Field ROUTER_FIELD;
        private static final java.lang.reflect.Field BEST_ASK_FIELD;
        private static final java.lang.reflect.Field BEST_BID_FIELD;
        private static final boolean AVAILABLE;

        static {
            boolean available = false;
            java.lang.reflect.Field routerField = null;
            java.lang.reflect.Field bestAskField = null;
            java.lang.reflect.Field bestBidField = null;
            try {
                routerField = exchange.core2.core.SynchronousMatchingEngine.class.getDeclaredField("router");
                routerField.setAccessible(true);
                Class<?> directBookClass = Class.forName("exchange.core2.core.orderbook.OrderBookDirectImpl");
                bestAskField = directBookClass.getDeclaredField("bestAskOrder");
                bestAskField.setAccessible(true);
                bestBidField = directBookClass.getDeclaredField("bestBidOrder");
                bestBidField.setAccessible(true);
                available = true;
            } catch (Throwable ignored) {
            }
            ROUTER_FIELD = routerField;
            BEST_ASK_FIELD = bestAskField;
            BEST_BID_FIELD = bestBidField;
            AVAILABLE = available;
        }

        static long readBestPrice(exchange.core2.core.SynchronousMatchingEngine engine, int symbolId, boolean isBuy) {
            if (!AVAILABLE || engine == null) return -1L;
            try {
                exchange.core2.core.processors.MatchingEngineRouter router =
                        (exchange.core2.core.processors.MatchingEngineRouter) ROUTER_FIELD.get(engine);
                if (router == null) return -1L;
                exchange.core2.core.orderbook.IOrderBook book = router.getOrderBooks().get(symbolId);
                if (book == null) return -1L;
                Object directOrder = isBuy ? BEST_ASK_FIELD.get(book) : BEST_BID_FIELD.get(book);
                if (directOrder == null) return -1L;
                if (directOrder instanceof exchange.core2.core.common.IOrder order) {
                    return order.getPrice();
                }
                return -1L;
            } catch (Throwable e) {
                return -1L;
            }
        }
    }

    /** Read and place on the same matcher thread: no other order can consume the quoted top between them. */
    private long protectedMarketPrice(int symbolId, CoreOrderSide side,
            com.surprising.aeron.protocol.CoreOrderType type, long originalBound, long slippagePpm) {
        if (type != com.surprising.aeron.protocol.CoreOrderType.MARKET || slippagePpm == 0) return originalBound;
        boolean buy = side == CoreOrderSide.BUY;
        long best = DirectTopPriceReader.readBestPrice(engine(symbolId), symbolId, buy);
        if (best <= 0) {
            if (!DirectTopPriceReader.AVAILABLE) {
                L2MarketData top = engine(symbolId).orderBook(symbolId, 1);
                if (buy ? top.askSize == 0 : top.bidSize == 0) return originalBound;
                best = buy ? top.askPrices[0] : top.bidPrices[0];
            } else {
                return originalBound;
            }
        }
        // Round toward the best price; outward rounding can exceed 1bp by a whole tick.
        long distance = Math.addExact(Math.multiplyExact(best / 1_000_000L, slippagePpm),
                (best % 1_000_000L) * slippagePpm / 1_000_000L);
        return buy ? Math.min(originalBound, Math.addExact(best, distance))
                : Math.max(originalBound, Math.subtractExact(best, distance));
    }

    private static OrderType orderType(CoreMatchingOrder command) {
        return orderType(command.orderType(), command.timeInForce());
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

    /** 控制命令按确定性顺序撤单；首次拒绝或异常后，余项保持 NOT_SUBMITTED。 */
    private CoreMatchingResult cancelRoutedBatch(List<CancellationOrder> orders) {
        int size = orders.size();
        if (size > 1_024) {
            return new CoreMatchingResult(false, "LIFECYCLE_BATCH_TOO_LARGE");
        }
        if (size == 1) {
            CancellationOrder order = orders.get(0);
            CoreMatchingResult result;
            try {
                result = cancelControlNative(order.userId(), order.orderId(), order.instrumentId());
            } catch (RuntimeException failure) {
                matcherFailure.compareAndSet(null, failure);
                throw failure;
            }
            return aggregateRoutedCancellationResults(orders, List.of(result));
        }
        List<CoreMatchingResult> results = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            CancellationOrder order = orders.get(index);
            CoreMatchingResult result;
            try {
                result = cancelControlNative(order.userId(), order.orderId(), order.instrumentId());
            } catch (RuntimeException failure) {
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

    private CoreMatchingResult aggregateRoutedCancellationResults(
            List<CancellationOrder> requested, List<CoreMatchingResult> results) {
        if (requested == null || results == null || requested.size() != results.size())
            throw new IllegalArgumentException("cancellation aggregation size mismatch");
        List<CoreCancellationResult> cancellations = new ArrayList<>(requested.size());
        int successfulPrefix = 0;
        CoreMatchingResult failure = null;
        for (int index = 0; index < results.size(); index++) {
            CoreMatchingResult result = java.util.Objects.requireNonNull(results.get(index), "cancellation result");
            cancellations.add(new CoreCancellationResult(requested.get(index).orderId(), result.accepted(), result.resultCode()));
            if (failure == null && result.accepted()) successfulPrefix++;
            else if (failure == null) failure = result;
        }
        boolean accepted = failure == null;
        return new CoreMatchingResult(accepted, accepted ? "SUCCESS" : failure.resultCode(), cancellations,
                successfulPrefix, !accepted && successfulPrefix != 0,
                0, 0, 0, 0, 0, 0, 0, -1,
                null, CoreMatchingResult.concatenateEvents(results),
                new exchange.core2.core.common.MatcherResult.MarketData(List.of(), List.of(), 0, 0));
    }

    private CoreMatchingResult cancelControlNative(long userId, long orderId, String instrumentId) {
        var result = cancelNative(userId, orderId, instrumentId);
        matcherEvidence.recordControlNative(topology.matcherShardId(result.symbol()), result.sequence());
        return CoreMatchingResult.fromNative(result);
    }

    private exchange.core2.core.common.MatcherResult cancelNative(long userId, long orderId, String instrumentId) {
        int symbolId = ensureSymbol(instrumentId);
        recordUser(userId);
        return engine(symbolId).cancel(currentCommandScope().aeronTimestamp, orderId, symbolId, userId);
    }

    private exchange.core2.core.common.MatcherResult cancelNative(long userId, long orderId, String instrumentId,
            exchange.core2.core.common.MatcherResult target) {
        int symbolId = ensureSymbol(instrumentId);
        recordUser(userId);
        return engine(symbolId).cancelInto(target, currentCommandScope().aeronTimestamp, orderId, symbolId, userId);
    }

    /** 强平/交割按既定次序撤单，首个失败后停止，并生成控制序列证据。 */
    public CoreMatchingResult cancelControlOrders(long coreSequence, java.util.UUID commandId,
            long logicalOrderId, long timestamp, List<CancellationOrder> orders) {
        validateCommandEvidence(coreSequence, commandId, logicalOrderId, timestamp);
        java.util.Objects.requireNonNull(orders, "control cancellations");
        MatcherCommandScope scope = beginSynchronousCommand(timestamp);
        try {
            return bindMatcherEvidence(coreSequence, commandId, logicalOrderId, timestamp,
                    matcherEvidence.nextSequence(-1), -1, cancelRoutedBatch(orders));
        } catch (RuntimeException failure) {
            matcherFailure.compareAndSet(null, failure);
            throw failure;
        } finally { scope.aeronTimestamp = 0; scope.active = false; }
    }

    /** 跨分片控制命令一次只让该分片 worker 执行当前撤单。 */
    public CoreMatchingResult cancelControlOrder(long timestamp, long userId, long orderId, String instrumentId) {
        if (timestamp < 0 || userId <= 0 || orderId <= 0)
            throw new IllegalArgumentException("invalid control cancellation");
        MatcherCommandScope scope = beginSynchronousCommand(timestamp);
        try {
            return cancelControlNative(userId, orderId, instrumentId);
        } catch (RuntimeException failure) {
            matcherFailure.compareAndSet(null, failure);
            throw failure;
        } finally { scope.aeronTimestamp = 0; scope.active = false; }
    }

    /** Owner 只绑定已经按原生分片消费的控制撤单事实，不再执行撮合业务。 */
    public CoreMatchingResult completeControlCancellations(long coreSequence, java.util.UUID commandId,
            long logicalOrderId, long timestamp, List<CancellationOrder> orders, List<CoreMatchingResult> results) {
        validateCommandEvidence(coreSequence, commandId, logicalOrderId, timestamp);
        return bindMatcherEvidence(coreSequence, commandId, logicalOrderId, timestamp,
                matcherEvidence.nextSequence(-1), -1, aggregateRoutedCancellationResults(orders, results));
    }

    public CompletableFuture<Integer> orderBooksStateHashAsync() {
        return currentStateHashesAsync().thenApply(StateHashes::bookHash);
    }

    public MatcherShardSnapshot captureShardSnapshot(
            int shardId, long snapshotId, long coreSequence, Iterable<CoreOrderState> activeOrders) {
        if (shardId < 0 || shardId >= topology.matchingEngineCount() || snapshotId <= 0
                || coreSequence < 0 || activeOrders == null) {
            throw new IllegalArgumentException("invalid matcher shard snapshot request");
        }
        reconcileOpenOrdersShard(activeOrders, shardId, coreSequence, snapshotId, "matcher snapshot");
        StateHashes hashes = stateHashes(shardId);
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

    public MatcherShardSnapshot stateHashShard(int shardId) {
        if (shardId < 0 || shardId >= topology.matchingEngineCount()) {
            throw new IllegalArgumentException("matcher shard is outside configured topology");
        }
        StateHashes hashes = stateHashes(shardId);
        return new MatcherShardSnapshot(shardId, hashes.engineHash(), hashes.bookHash());
    }

    public int aggregateBookHash(List<MatcherShardSnapshot> shards) {
        return aggregateStateHashes(shards).bookHash();
    }

    public MatcherSnapshot finishShardedSnapshot(
            long snapshotId, long coreSequence, long businessStateHash, TradingCoreState state,
            List<MatcherShardSnapshot> shardSnapshots) {
        if (state == null || shardSnapshots == null || shardSnapshots.size() != topology.matchingEngineCount()) {
            throw new IllegalArgumentException("incomplete matcher shard snapshot set");
        }
        StateHashes hashes = aggregateStateHashes(shardSnapshots);
        try {
            List<InMemorySerializationProcessor.SerializedModule> modules =
                    serializationProcessor.exportSnapshot(snapshotId);
            long matcherSequence = maxMatcherSequence(modules);
            Set<Long> userSnapshot = snapshotUsers();
            return new MatcherSnapshot(state.productLine(), topology, snapshotId, coreSequence, matcherSequence,
                    matcherEvidence.snapshot(), businessStateHash, hashes.engineHash(),
                    symbols, userSnapshot, modules);
        } finally {
            serializationProcessor.removeSnapshot(snapshotId);
        }
    }

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
        SnapshotOperation operation = snapshotOperations.get(snapshotId);
        if (operation == null) {
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

    private void startSnapshotOperation(
            SnapshotOperation operation,
            long snapshotId,
            long coreSequence,
            long businessStateHash,
            TradingCoreState state,
            Iterable<CoreOrderState> activeOrders) {
        CompletableFuture<MatcherSnapshot> pipeline;
        try {
            pipeline = reconcileOpenOrdersAsync(activeOrders, coreSequence, snapshotId,
                    "matcher snapshot").thenCompose(ignored -> currentStateHashesAsync()).thenCompose(hashes ->
                    snapshotPersistence.apply(() -> CompletableFuture.completedFuture(
                                    storeMatcherShards(snapshotId, coreSequence)
                                            ? CommandResultCode.SUCCESS
                                            : CommandResultCode.STATE_PERSIST_MATCHING_ENGINE_FAILED))
                            .thenApply(result -> {
                                if (result != CommandResultCode.SUCCESS) {
                                    throw new FatalMatchingDivergenceException("matcher snapshot", coreSequence,
                                            snapshotId, "exchange-core persist failed: " + result);
                                }
                                try {
                                    List<InMemorySerializationProcessor.SerializedModule> modules =
                                            serializationProcessor.exportSnapshot(snapshotId);
                                    long matcherSequence = maxMatcherSequence(modules);
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
        } catch (RuntimeException exception) {
            operation.result.completeExceptionally(exception);
            snapshotOperations.remove(snapshotId, operation);
            return;
        }
        pipeline.whenComplete((snapshot, failure) -> {
            if (failure == null) {
                operation.result.complete(snapshot);
            } else {
                operation.result.completeExceptionally(failure);
            }
            snapshotOperations.remove(snapshotId, operation);
        });
    }

    private CompletableFuture<Void> reconcileOpenOrdersAsync(
            Iterable<CoreOrderState> activeOrders,
            long coreSequence,
            long snapshotId,
            String operation) {
        try {
            Map<Long, ReconciledOrder> expected = new HashMap<>();
            for (CoreOrderState order : activeOrders) {
                if (order.status() != com.surprising.aeron.service.state.model.CoreOrderStatus.OPEN) {
                    throw new FatalMatchingDivergenceException(operation, coreSequence, snapshotId,
                            "active-order index contains a terminal order");
                }
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
            Map<Long, ReconciledOrder> actual = new HashMap<>();
            for (SynchronousMatchingEngine matcher : engines) {
                for (SynchronousMatchingEngine.OpenOrder order : matcher.openOrders()) {
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
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    private void reconcileOpenOrdersShard(Iterable<CoreOrderState> activeOrders, int shardId,
                                          long coreSequence, long snapshotId, String operation) {
        Map<Long, ReconciledOrder> expected = new HashMap<>();
        for (CoreOrderState order : activeOrders) {
            Integer symbolId = symbols.get(order.instrumentId());
            if (symbolId == null || topology.matcherShardId(symbolId) != shardId) continue;
            if (order.status() != com.surprising.aeron.service.state.model.CoreOrderStatus.OPEN) {
                throw new FatalMatchingDivergenceException(operation, coreSequence, snapshotId,
                        "active-order index contains a terminal order");
            }
            ReconciledOrder value = new ReconciledOrder(symbolId, order.orderId(), order.userId(),
                    order.side() == CoreOrderSide.BUY ? OrderAction.BID : OrderAction.ASK,
                    order.matchingPriceTicks(), order.quantitySteps(), order.executedQuantitySteps(),
                    order.matchingPriceTicks());
            if (expected.put(order.orderId(), value) != null) {
                throw new FatalMatchingDivergenceException(operation, coreSequence, snapshotId,
                        "active-order index contains duplicate order ID " + order.orderId());
            }
        }
        Map<Long, ReconciledOrder> actual = new HashMap<>();
        for (SynchronousMatchingEngine.OpenOrder order : engines[shardId].openOrders()) {
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

    private CompletableFuture<StateHashes> currentStateHashesAsync() {
        try {
            ArrayList<MatcherShardSnapshot> snapshots = new ArrayList<>(engines.length);
            for (int shardId = 0; shardId < engines.length; shardId++) {
                StateHashes hashes = stateHashes(shardId);
                snapshots.add(new MatcherShardSnapshot(shardId, hashes.engineHash(), hashes.bookHash()));
            }
            return CompletableFuture.completedFuture(aggregateStateHashes(snapshots));
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    private StateHashes stateHashes(int shardId) {
        var report = engines[shardId].stateHashReport();
        int engineHash = 1;
        int bookHash = 0;
        for (Map.Entry<exchange.core2.core.common.api.reports.StateHashReportResult.SubmoduleKey,
                Integer> entry : report.getHashCodes().entrySet()) {
            engineHash = engineHash * 31 + entry.getKey().submodule.code;
            engineHash = engineHash * 31 + entry.getKey().moduleId;
            engineHash = engineHash * 31 + entry.getValue();
            if (entry.getKey().submodule
                    == exchange.core2.core.common.api.reports.StateHashReportResult.SubmoduleType
                    .MATCHING_ORDER_BOOKS) bookHash = bookHash * 31 + entry.getValue();
        }
        return new StateHashes(engineHash, bookHash);
    }

    private static StateHashes aggregateStateHashes(List<MatcherShardSnapshot> shards) {
        if (shards.size() == 1) return new StateHashes(shards.getFirst().engineHash(), shards.getFirst().bookHash());
        int engineHash = 1;
        int bookHash = 0;
        for (int shardId = 0; shardId < shards.size(); shardId++) {
            MatcherShardSnapshot shard = shards.get(shardId);
            if (shard.shardId() != shardId) throw new IllegalArgumentException("matcher shards are not ordered");
            engineHash = (engineHash * 31 + shardId) * 31 + shard.engineHash();
            bookHash = (bookHash * 31 + shardId) * 31 + shard.bookHash();
        }
        return new StateHashes(engineHash, bookHash);
    }

    public CompletableFuture<List<CoreBookLevelView>> orderBookLevelsAsync(String requestedSymbol, int depth) {
        if (requestedSymbol == null || requestedSymbol.isBlank() || depth < 1 || depth > 100) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("invalid single-instrumentId book query"));
        }
        String instrumentId = requestedSymbol.trim().toUpperCase(java.util.Locale.ROOT);
        Integer symbolId = symbols.get(instrumentId);
        if (symbolId == null) return CompletableFuture.completedFuture(List.of());
        return CompletableFuture.completedFuture(bookLevels(instrumentId, engine(symbolId).orderBook(symbolId, depth)));
    }

    public CompletableFuture<BookBootstrapSnapshot> orderBookBootstrapAsync(int depth) {
        if (depth < 1 || depth > 100) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("invalid bootstrap book depth"));
        }
        return currentStateHashesAsync().thenCompose(barrierComplete -> {
            List<Map.Entry<String, Integer>> entries = new ArrayList<>(symbols.entrySet());
            entries.sort(Map.Entry.comparingByKey());
            List<String> entrySymbols = new ArrayList<>(entries.size());
            for (Map.Entry<String, Integer> entry : entries) {
                entrySymbols.add(entry.getKey());
            }
            List<CompletableFuture<BookResult>> requests = new ArrayList<>(entries.size());
            for (Map.Entry<String, Integer> entry : entries) {
                requests.add(CompletableFuture.completedFuture(
                        new BookResult(entry.getKey(), engine(entry.getValue()).orderBook(entry.getValue(), depth))));
            }
            return CompletableFuture.allOf(requests.toArray(CompletableFuture[]::new)).thenApply(ignored -> {
                int expectedLevels = 0;
                for (CompletableFuture<BookResult> request : requests) {
                    BookResult result = request.getNow(null);
                    expectedLevels = Math.addExact(expectedLevels,
                            Math.addExact(result.book().askSize, result.book().bidSize));
                }
                List<CoreBookLevelView> levels = new ArrayList<>(expectedLevels);
                for (CompletableFuture<BookResult> request : requests) {
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

    public BookBootstrapSnapshot orderBookBootstrapShard(int shardId, int depth) {
        if (shardId < 0 || shardId >= topology.matchingEngineCount() || depth < 1 || depth > 100) {
            throw new IllegalArgumentException("invalid matcher shard bootstrap query");
        }
        List<Map.Entry<String, Integer>> entries = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : symbols.entrySet()) {
            if (topology.matcherShardId(entry.getValue()) == shardId) {
                entries.add(entry);
            }
        }
        entries.sort(Map.Entry.comparingByKey());
        ArrayList<CoreBookLevelView> levels = new ArrayList<>();
        List<String> entrySymbols = new ArrayList<>(entries.size());
        for (Map.Entry<String, Integer> entry : entries) {
            entrySymbols.add(entry.getKey());
            levels.addAll(bookLevels(entry.getKey(), engines[shardId].orderBook(entry.getValue(), depth)));
        }
        return new BookBootstrapSnapshot(entrySymbols, levels);
    }

    public BookBootstrapSnapshot mergeBookBootstrapShards(List<BookBootstrapSnapshot> shards) {
        if (shards == null || shards.size() != topology.matchingEngineCount()) {
            throw new IllegalArgumentException("incomplete matcher bootstrap shards");
        }
        ArrayList<String> mergedSymbols = new ArrayList<>();
        ArrayList<CoreBookLevelView> mergedLevels = new ArrayList<>();
        for (BookBootstrapSnapshot shard : shards) {
            mergedSymbols.addAll(shard.symbols());
            mergedLevels.addAll(shard.levels());
        }
        mergedSymbols.sort(String::compareTo);
        mergedLevels.sort(Comparator.comparing(CoreBookLevelView::instrumentId)
                .thenComparing(CoreBookLevelView::side)
                .thenComparingLong(CoreBookLevelView::priceTicks));
        return new BookBootstrapSnapshot(mergedSymbols, mergedLevels);
    }

    private static List<CoreBookLevelView> bookLevels(String instrumentId, L2MarketData book) {
        List<CoreBookLevelView> levels = new ArrayList<>(Math.addExact(book.askSize, book.bidSize));
        for (int index = 0; index < book.askSize; index++) {
            levels.add(new CoreBookLevelView(instrumentId, CoreOrderSide.SELL, book.askPrices[index],
                    book.askVolumes[index], book.askOrders[index]));
        }
        for (int index = 0; index < book.bidSize; index++) {
            levels.add(new CoreBookLevelView(instrumentId, CoreOrderSide.BUY, book.bidPrices[index],
                    book.bidVolumes[index], book.bidOrders[index]));
        }
        levels.sort(Comparator.comparing(CoreBookLevelView::side)
                .thenComparingLong(CoreBookLevelView::priceTicks));
        return levels;
    }

    private record BookResult(String instrumentId, L2MarketData book) {
    }

    public CompletableFuture<Integer> ensureInstrumentAsync(CoreInstrument instrument) {
        if (instrument == null) {
            throw new IllegalArgumentException("instrument is required");
        }
        return ensureSymbolAsync(instrument.instrumentId());
    }

    private void start() {
        start(null);
    }

    private void start(MatcherSnapshot snapshot) {
        engines = new SynchronousMatchingEngine[topology.matchingEngineCount()];
        for (int shardId = 0; shardId < engines.length; shardId++) startShard(shardId, snapshot);
        activatedShardCount.set(engines.length);
    }

    private void startShard(int shardId, MatcherSnapshot snapshot) {
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
                        .riskEnginesNum(topology.riskEngineCount())
                        .directMatchingOnlyPipeline(true)
                        .msgsInGroupLimit(Math.max(1,
                                Integer.getInteger("surprising.aeron.matcher-group-size", 256)))
                        .maxGroupDurationNs(Math.max(1,
                                Integer.getInteger("surprising.aeron.matcher-group-nanos", 10_000)))
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

    public LaneTopology topology() { return topology; }
    public int matcherShardId(String instrumentId) {
        if (instrumentId == null || instrumentId.isBlank()) throw new IllegalArgumentException("matcher instrumentId is required");
        return topology.matcherShardId(reserveSymbolId(instrumentId));
    }
    private int matcherShardId(CoreMatchingResult result) {
        int symbolId = result.nativeMatcherResult() == null ? 0 : result.nativeMatcherResult().symbol();
        return symbolId <= 0 ? -1 : topology.matcherShardId(symbolId);
    }

    private CompletableFuture<Integer> ensureSymbolAsync(String instrumentId) {
        try {
            return CompletableFuture.completedFuture(ensureSymbol(instrumentId));
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    private int ensureSymbol(String instrumentId) {
        int symbolId = reserveSymbolId(instrumentId);
        if (registeredSymbols.contains(symbolId)) return symbolId;
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
        return com.surprising.product.api.InstrumentIds.parse(instrumentId);
    }

    private int reserveSymbolId(String instrumentId) {
        // 已注册币对走并发字典只读路径；新币对仍串行检查稳定 ID 冲突。
        Integer existing = symbols.get(instrumentId);
        return existing != null ? existing : registerSymbolId(instrumentId);
    }

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

    private SynchronousMatchingEngine engine(int symbolId) {
        if (engines == null) throw new IllegalStateException("matcher engines are not active");
        return engines[topology.matcherShardId(symbolId)];
    }

    private boolean storeMatcherShards(long snapshotId, long coreSequence) {
        for (SynchronousMatchingEngine matcher : engines) {
            if (!matcher.storeSnapshot(snapshotId, coreSequence, coreSequence)) return false;
        }
        return true;
    }

    private static int stableId(String value) {
        int hash = 0x811c9dc5;
        for (int index = 0; index < value.length(); index++) {
            hash ^= value.charAt(index);
            hash *= 0x01000193;
        }
        return hash & 0x7fffffff;
    }

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

    private record StateHashes(int engineHash, int bookHash) {
    }

    public record MatcherShardSnapshot(int shardId, int engineHash, int bookHash) {
        public MatcherShardSnapshot {
            if (shardId < 0) throw new IllegalArgumentException("matcher shard id must be non-negative");
        }
    }

    private static final class SnapshotOperation {
        private final long coreSequence;
        private final long businessStateHash;
        private final CompletableFuture<MatcherSnapshot> result = new CompletableFuture<>();

        private SnapshotOperation(long coreSequence, long businessStateHash) {
            this.coreSequence = coreSequence;
            this.businessStateHash = businessStateHash;
        }

        private CompletableFuture<MatcherSnapshot> view() {
            NonCancellableFuture<MatcherSnapshot> view = new NonCancellableFuture<>();
            result.whenComplete((snapshot, failure) -> {
                if (failure == null) {
                    view.complete(snapshot);
                } else {
                    view.completeExceptionally(failure);
                }
            });
            return view;
        }
    }

    private static final class NonCancellableFuture<T> extends CompletableFuture<T> {
        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return false;
        }
    }

    private void stop() {
        if (engines != null) {
            awaitSnapshotOperations();
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

    private void awaitSnapshotOperations() {
        for (SnapshotOperation operation : snapshotOperations.values()) {
            try {
                operation.result.get(30, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            } catch (ExecutionException | TimeoutException ignored) {
                return;
            }
        }
    }

    @Override
    public void close() {
        stop();
    }

    private static Throwable unwrap(Throwable failure) {
        return failure instanceof java.util.concurrent.CompletionException && failure.getCause() != null
                ? failure.getCause() : failure;
    }

    public record CancellationOrder(long orderId, long userId, String instrumentId) {
        public CancellationOrder {
            if (orderId <= 0 || userId <= 0 || instrumentId == null || instrumentId.isBlank()) {
                throw new IllegalArgumentException("invalid cancellation order");
            }
        }
    }

    private static CoreMatchingResult notSubmitted() {
        return new CoreMatchingResult(false, "NOT_SUBMITTED");
    }

    private static long maxMatcherSequence(List<InMemorySerializationProcessor.SerializedModule> modules) {
        if (modules.isEmpty()) throw new java.util.NoSuchElementException("modules is empty");
        long max = Long.MIN_VALUE;
        for (int i = 0; i < modules.size(); i++) {
            long seq = modules.get(i).sequence();
            if (seq > max) {
                max = seq;
            }
        }
        return max;
    }
}
