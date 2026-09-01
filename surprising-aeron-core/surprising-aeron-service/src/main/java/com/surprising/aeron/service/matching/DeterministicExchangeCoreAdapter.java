package com.surprising.aeron.service.matching;

import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreBookLevelView;
import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.protocol.ReservationKind;
import com.surprising.aeron.service.state.CoreInstrumentState;
import com.surprising.aeron.service.state.CoreOrderState;
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
import java.util.HashSet;
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

public final class DeterministicExchangeCoreAdapter implements AutoCloseable {

    private SynchronousMatchingEngine engine;
    private final InMemorySerializationProcessor serializationProcessor =
            new InMemorySerializationProcessor();
    private final Map<String, Integer> symbols = new HashMap<>();
    private final Map<Integer, String> symbolNames = new HashMap<>();
    private final Set<Long> users = new HashSet<>();
    private final ConcurrentHashMap<Long, SnapshotOperation> snapshotOperations = new ConcurrentHashMap<>();
    private final LaneTopology topology;
    private final AtomicReference<Throwable> matcherFailure = new AtomicReference<>();
    private final MatcherEvidenceLedger matcherEvidence;
    private final AtomicInteger dispatchInFlight = new AtomicInteger();
    private final AtomicInteger dispatchHighWaterMark = new AtomicInteger();
    private final Function<Supplier<CompletableFuture<CommandResultCode>>, CompletableFuture<CommandResultCode>>
            snapshotPersistence;
    private Runnable deferredActivation;
    private boolean activationComplete;
    private boolean synchronousDispatchObserved;
    private long activeAeronTimestamp;
    private boolean matchingCommandActive;

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
            this.matcherEvidence = new MatcherEvidenceLedger(
                    topology, 0, snapshot.matcherShardProgress());
            serializationProcessor.importSnapshot(snapshot.modules());
            snapshot.symbols().forEach((symbol, symbolId) -> {
                String previous = symbolNames.put(symbolId, symbol);
                if (previous != null && !previous.equals(symbol)) {
                    throw new IllegalArgumentException("matcher symbol registry collision");
                }
                symbols.put(symbol, symbolId);
            });
            users.addAll(snapshot.users());
            List<CoreOrderState> restoredOrders = new ArrayList<>();
            activeOrders.forEach(restoredOrders::add);
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

    private void activateRestoredMatcher(TradingCoreState state, Iterable<CoreOrderState> activeOrders,
                                         long coreSequence, MatcherSnapshot snapshot) {
        start(snapshot);
        reconcileOpenOrdersAsync(activeOrders, coreSequence, snapshot.snapshotId(), "matcher restore").join();
        StateHashes restoredHashes = currentStateHashesAsync().join();
        if (restoredHashes.engineHash() != snapshot.engineStateHash()
                || restoredHashes.bookHash() != snapshot.bookStateHash()) {
            throw new FatalMatchingDivergenceException("matcher restore", coreSequence,
                    snapshot.snapshotId(), "restored exchange-core state hash mismatch"
                            + " (expectedEngine=" + snapshot.engineStateHash()
                            + ", actualEngine=" + restoredHashes.engineHash()
                            + ", expectedBook=" + snapshot.bookStateHash()
                            + ", actualBook=" + restoredHashes.bookHash() + ')');
        }
    }

    public CompletableFuture<CoreMatchingResult> placeAsync(long userId, CoreMatchingOrder command) {
        return placeUnlanedAsync(userId, command);
    }

    public CoreMatchingResult place(long userId, CoreMatchingOrder command) {
        int symbolId = ensureSymbol(command.symbol());
        users.add(userId);
        return directMatcher(() -> engine.place(
                activeAeronTimestamp, command.orderId(), 0,
                command.matchingPriceTicks(), command.matchingPriceTicks(), command.quantitySteps(),
                command.side() == CoreOrderSide.BUY ? OrderAction.BID : OrderAction.ASK,
                orderType(command), symbolId, userId));
    }

    public CompletableFuture<Void> prepareOrderRoutesAsync(long userId, Iterable<String> symbols) {
        if (userId <= 0 || symbols == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("invalid matcher route preparation"));
        }
        List<CompletableFuture<Integer>> symbolFutures = new ArrayList<>();
        for (String symbol : symbols) {
            if (symbol == null || symbol.isBlank()) {
                return CompletableFuture.failedFuture(
                        new IllegalArgumentException("invalid matcher route preparation symbol"));
            }
            symbolFutures.add(ensureSymbolAsync(symbol));
        }
        users.add(userId);
        CompletableFuture<?>[] futures = new CompletableFuture<?>[symbolFutures.size()];
        for (int index = 0; index < symbolFutures.size(); index++) {
            futures[index] = symbolFutures.get(index);
        }
        return CompletableFuture.allOf(futures);
    }

    public void prepareOrderRoutes(long userId, Iterable<String> symbols) {
        if (userId <= 0 || symbols == null) {
            throw new IllegalArgumentException("invalid matcher route preparation");
        }
        for (String symbol : symbols) {
            if (symbol == null || symbol.isBlank()) {
                throw new IllegalArgumentException("invalid matcher route preparation symbol");
            }
            ensureSymbol(symbol);
        }
        users.add(userId);
    }

    public CompletableFuture<CoreMatchingResult> executeWithEvidence(
            long coreSequence,
            java.util.UUID commandId,
            long orderId,
            long instrumentVersion,
            long aeronTimestamp,
            Supplier<CompletableFuture<CoreMatchingResult>> command) {
        return executeWithEvidence(coreSequence, commandId, orderId, instrumentVersion,
                aeronTimestamp, false, command);
    }

    public CompletableFuture<CoreMatchingResult> executeControlWithEvidence(
            long coreSequence,
            java.util.UUID commandId,
            long orderId,
            long instrumentVersion,
            long aeronTimestamp,
            Supplier<CompletableFuture<CoreMatchingResult>> command) {
        return executeWithEvidence(coreSequence, commandId, orderId, instrumentVersion,
                aeronTimestamp, true, command);
    }

    public CoreMatchingResult executeWithEvidenceSync(
            long coreSequence,
            java.util.UUID commandId,
            long orderId,
            long instrumentVersion,
            long aeronTimestamp,
            Supplier<CoreMatchingResult> command) {
        return executeWithEvidenceSync(coreSequence, commandId, orderId, instrumentVersion,
                aeronTimestamp, false, command);
    }

    public CoreMatchingResult executeControlWithEvidenceSync(
            long coreSequence,
            java.util.UUID commandId,
            long orderId,
            long instrumentVersion,
            long aeronTimestamp,
            Supplier<CoreMatchingResult> command) {
        return executeWithEvidenceSync(coreSequence, commandId, orderId, instrumentVersion,
                aeronTimestamp, true, command);
    }

    private CoreMatchingResult executeWithEvidenceSync(
            long coreSequence,
            java.util.UUID commandId,
            long orderId,
            long instrumentVersion,
            long aeronTimestamp,
            boolean controlShard,
            Supplier<CoreMatchingResult> command) {
        if (coreSequence <= 0 || commandId == null || orderId < 0 || instrumentVersion < 0
                || aeronTimestamp < 0 || command == null) {
            throw new IllegalArgumentException("invalid matcher command evidence");
        }
        Throwable failure = matcherFailure.get();
        if (failure != null) throw new IllegalStateException("matcher is poisoned by an earlier command", failure);
        if (matchingCommandActive) throw new IllegalStateException("nested synchronous matcher command");
        long sequence = matcherEvidence.nextSequence();
        if (!synchronousDispatchObserved) {
            synchronousDispatchObserved = true;
            dispatchHighWaterMark.accumulateAndGet(1, Math::max);
        }
        try {
            matchingCommandActive = true;
            activeAeronTimestamp = aeronTimestamp;
            CoreMatchingResult result = command.get();
            return bindMatcherEvidence(coreSequence, commandId, orderId, instrumentVersion,
                    aeronTimestamp, sequence, controlShard, result);
        } catch (RuntimeException exception) {
            matcherFailure.compareAndSet(null, exception);
            throw exception;
        } finally {
            activeAeronTimestamp = 0;
            matchingCommandActive = false;
        }
    }

    private CompletableFuture<CoreMatchingResult> executeWithEvidence(
            long coreSequence,
            java.util.UUID commandId,
            long orderId,
            long instrumentVersion,
            long aeronTimestamp,
            boolean controlShard,
            Supplier<CompletableFuture<CoreMatchingResult>> command) {
        if (coreSequence <= 0 || commandId == null || orderId < 0 || instrumentVersion < 0
                || aeronTimestamp < 0 || command == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("invalid matcher command evidence"));
        }
        Throwable failure = matcherFailure.get();
        if (failure != null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("matcher is poisoned by an earlier command", failure));
        }
        int depth = dispatchInFlight.incrementAndGet();
        if (depth > topology.matcherWindowSize()) {
            dispatchInFlight.decrementAndGet();
            IllegalStateException exhausted = new IllegalStateException("matcher dispatch window is exhausted");
            matcherFailure.compareAndSet(null, exhausted);
            return CompletableFuture.failedFuture(exhausted);
        }
        dispatchHighWaterMark.accumulateAndGet(depth, Math::max);
        CompletableFuture<CoreMatchingResult> pipeline = executeWithEvidenceNow(
                coreSequence, commandId, orderId, instrumentVersion, aeronTimestamp, controlShard, command);
        NonCancellableFuture<CoreMatchingResult> view = new NonCancellableFuture<>();
        pipeline.whenComplete((result, completionFailure) -> {
            dispatchInFlight.decrementAndGet();
            if (completionFailure == null) view.complete(result);
            else {
                matcherFailure.compareAndSet(null, unwrap(completionFailure));
                view.completeExceptionally(completionFailure);
            }
        });
        return view;
    }

    private CompletableFuture<CoreMatchingResult> executeWithEvidenceNow(
            long coreSequence,
            java.util.UUID commandId,
            long orderId,
            long instrumentVersion,
            long aeronTimestamp,
            boolean controlShard,
            Supplier<CompletableFuture<CoreMatchingResult>> command) {
        long sequence = matcherEvidence.nextSequence();
        CompletableFuture<CoreMatchingResult> submitted;
        try {
            if (matchingCommandActive) {
                throw new IllegalStateException("nested synchronous matcher command");
            }
            matchingCommandActive = true;
            activeAeronTimestamp = aeronTimestamp;
            submitted = command.get();
        } catch (RuntimeException exception) {
            matcherFailure.compareAndSet(null, exception);
            return CompletableFuture.failedFuture(exception);
        } finally {
            activeAeronTimestamp = 0;
            matchingCommandActive = false;
        }
        if (submitted == null) {
            IllegalStateException failure = new IllegalStateException("matcher command returned no future");
            matcherFailure.compareAndSet(null, failure);
            return CompletableFuture.failedFuture(failure);
        }
        return submitted.thenApply(result -> bindMatcherEvidence(coreSequence, commandId, orderId,
                instrumentVersion, aeronTimestamp, sequence, controlShard, result));
    }

    private CoreMatchingResult bindMatcherEvidence(
            long coreSequence,
            java.util.UUID commandId,
            long orderId,
            long instrumentVersion,
            long aeronTimestamp,
            long sequence,
            boolean controlShard,
            CoreMatchingResult result) {
        if (result == null) throw new IllegalStateException("matcher command returned no result");
        Throwable matcherPoison = matcherFailure.get();
        if (matcherPoison != null) {
            throw new IllegalStateException("matcher completion discarded after fatal divergence", matcherPoison);
        }
        CoreMatchingResult bound = matcherEvidence.bind(coreSequence, commandId, orderId,
                instrumentVersion, aeronTimestamp, sequence,
                controlShard ? -1 : matcherShardId(result), result);
        if (bound.outcome() == CoreMatchingResult.Outcome.FATAL_DIVERGENCE) {
            matcherFailure.compareAndSet(null,
                    new IllegalStateException("fatal matcher result: " + bound.resultCode()));
        }
        return bound;
    }

    public void poisonFromOwner(String detail) {
        if (detail == null || detail.isBlank()) throw new IllegalArgumentException("matcher poison detail is required");
        matcherFailure.compareAndSet(null, new IllegalStateException(detail));
    }

    private CompletableFuture<CoreMatchingResult> placeUnlanedAsync(long userId, CoreMatchingOrder command) {
        Integer symbolId = symbols.get(command.symbol());
        users.add(userId);
        if (symbolId != null) return submitDirectPlace(userId, symbolId, command);
        return ensureSymbolAsync(command.symbol())
                .thenCompose(registered -> submitDirectPlace(userId, registered, command));
    }

    private CompletableFuture<CoreMatchingResult> submitDirectPlace(
            long userId, int symbolId, CoreMatchingOrder command) {
        return completedMatcher(() -> engine.place(
                activeAeronTimestamp, command.orderId(), 0,
                command.matchingPriceTicks(), command.matchingPriceTicks(), command.quantitySteps(),
                command.side() == CoreOrderSide.BUY ? OrderAction.BID : OrderAction.ASK,
                orderType(command), symbolId, userId));
    }

    private static OrderType orderType(CoreMatchingOrder command) {
        if (command.orderType() == com.surprising.aeron.protocol.CoreOrderType.MARKET) {
            return command.timeInForce() == com.surprising.aeron.protocol.CoreTimeInForce.FOK
                    ? OrderType.FOK : OrderType.IOC;
        }
        return switch (command.timeInForce()) {
            case IOC -> OrderType.IOC;
            case FOK -> OrderType.FOK;
            case GTC -> OrderType.GTC;
            case GTX -> OrderType.GTX;
        };
    }

    public CompletableFuture<CoreMatchingResult> cancelBatchAsync(List<CoreOrderState> orders) {
        List<CoreOrderState> requested = orders == null ? List.of() : List.copyOf(orders);
        return cancelBatchOrderedAsync(requested).thenApply(outcome -> aggregateCancellationResult(requested, outcome));
    }

    public CoreMatchingResult cancelBatch(List<CoreOrderState> orders) {
        List<CoreOrderState> requested = orders == null ? List.of() : List.copyOf(orders);
        if (requested.size() > 1_024) {
            return aggregateCancellationResult(requested, CancelBatchOutcome.rejected(requested.size(),
                    new CoreMatchingResult(false, "LIFECYCLE_BATCH_TOO_LARGE")));
        }
        return aggregateCancellationResult(requested,
                cancelBatchOrdered(requested,
                        order -> cancel(order.userId(), order.orderId(), order.symbol())));
    }

    public CompletableFuture<CoreMatchingResult> executeAfterCancellations(
            List<CancellationOrder> orders,
            Supplier<CompletableFuture<CoreMatchingResult>> submission) {
        List<CancellationOrder> requested = orders == null ? List.of() : List.copyOf(orders);
        if (requested.isEmpty()) return submission.get();
        return cancelBatchOrderedAsync(requested,
                order -> cancelAsync(order.userId(), order.orderId(), order.symbol())).thenCompose(outcome -> {
            CoreMatchingResult cancellations = aggregateRuntimeCancellationResult(requested, outcome);
            if (!cancellations.accepted()) return CompletableFuture.completedFuture(cancellations);
            return submission.get().thenApply(result -> combineCancellationPrefix(cancellations, result));
        });
    }

    public CoreMatchingResult executeAfterCancellationsSync(
            List<CancellationOrder> orders,
            Supplier<CoreMatchingResult> submission) {
        List<CancellationOrder> requested = orders == null ? List.of() : List.copyOf(orders);
        if (requested.isEmpty()) return submission.get();
        CancelBatchOutcome outcome = cancelBatchOrdered(
                requested, order -> cancel(order.userId(), order.orderId(), order.symbol()));
        CoreMatchingResult cancellations = aggregateRuntimeCancellationResult(requested, outcome);
        if (!cancellations.accepted()) return cancellations;
        return combineCancellationPrefix(cancellations, submission.get());
    }

    private static CoreMatchingResult aggregateRuntimeCancellationResult(
            List<CancellationOrder> requested,
            CancelBatchOutcome outcome) {
        List<CoreCancellationResult> cancellations = new ArrayList<>(requested.size());
        for (int index = 0; index < requested.size(); index++) {
            CoreMatchingResult result = outcome.results().get(index);
            cancellations.add(new CoreCancellationResult(requested.get(index).orderId(), result.accepted(),
                    result.resultCode()));
        }
        return aggregateCancellationOutcomes(cancellations, outcome);
    }

    private static CoreMatchingResult combineCancellationPrefix(
            CoreMatchingResult cancellations,
            CoreMatchingResult result) {
        if (result == null) throw new IllegalStateException("matcher command returned no result");
        List<CoreCancellationResult> combinedCancellations = new ArrayList<>(
                cancellations.cancellations().size() + result.cancellations().size());
        combinedCancellations.addAll(cancellations.cancellations());
        combinedCancellations.addAll(result.cancellations());
        List<exchange.core2.core.common.MatcherResult.MatcherEvent> events =
                CoreMatchingResult.concatenateEvents(cancellations.matcherEvents(), result.matcherEvents());
        long nativeSequence = Math.max(cancellations.nativeCommand().nativeSequence(),
                result.nativeCommand().nativeSequence());
        return new CoreMatchingResult(result.accepted(), result.resultCode(), combinedCancellations,
                Math.addExact(cancellations.successfulPrefixCount(), result.successfulPrefixCount()),
                cancellations.matcherStateChanged() || result.matcherStateChanged()
                        || !cancellations.cancellations().isEmpty(),
                new CoreMatchingResult.NativeCommand(0, 0, 0, 0, 0, nativeSequence, 0, 0, -1),
                new CoreMatchingResult.MatcherPrefix(0, 0), result.nativeMatcherResult(), events,
                result.marketData());
    }

    private static CoreMatchingResult aggregateCancellationResult(
            List<CoreOrderState> requested,
            CancelBatchOutcome outcome) {
        List<CoreCancellationResult> cancellations = new ArrayList<>(requested.size());
        for (int index = 0; index < requested.size(); index++) {
            CoreMatchingResult result = outcome.results().get(index);
            cancellations.add(new CoreCancellationResult(requested.get(index).orderId(), result.accepted(),
                    result.resultCode()));
        }
        return aggregateCancellationOutcomes(cancellations, outcome);
    }

    private static CoreMatchingResult aggregateCancellationOutcomes(
            List<CoreCancellationResult> cancellations,
            CancelBatchOutcome outcome) {
        List<exchange.core2.core.common.MatcherResult.MatcherEvent> events =
                CoreMatchingResult.concatenateEvents(outcome.results());
        long nativeSequence = outcome.results().stream()
                .mapToLong(result -> result.nativeCommand().nativeSequence()).max().orElse(0);
        CoreMatchingResult failure = outcome.failedResult();
        boolean accepted = outcome.exception() == null && failure == null;
        String resultCode = outcome.exception() != null ? "EXCHANGE_CORE_FAILURE"
                : failure == null ? "SUCCESS" : failure.resultCode();
        return new CoreMatchingResult(accepted, resultCode, cancellations,
                outcome.successfulPrefix().size(), !accepted && !outcome.successfulPrefix().isEmpty(),
                new CoreMatchingResult.NativeCommand(0, 0, 0, 0, 0, nativeSequence, 0, 0, -1),
                new CoreMatchingResult.MatcherPrefix(0, 0), null, events,
                new exchange.core2.core.common.MatcherResult.MarketData(List.of(), List.of(), 0, 0));
    }

    public CompletableFuture<CancelBatchOutcome> cancelBatchOrderedAsync(List<CoreOrderState> orders) {
        if (orders != null && orders.size() > 1_024) {
            return CompletableFuture.completedFuture(CancelBatchOutcome.rejected(orders.size(),
                    new CoreMatchingResult(false, "LIFECYCLE_BATCH_TOO_LARGE")));
        }
        return cancelBatchOrderedAsync(orders == null ? List.of() : orders,
                order -> cancelAsync(order.userId(), order.orderId(), order.symbol()));
    }

    private CompletableFuture<CoreMatchingResult> cancelAsync(long userId, long orderId, String symbol) {
        Integer symbolId = symbols.get(symbol);
        users.add(userId);
        if (symbolId != null) return submitDirectCancel(userId, orderId, symbolId);
        return ensureSymbolAsync(symbol)
                .thenCompose(registered -> submitDirectCancel(userId, orderId, registered));
    }

    private CompletableFuture<CoreMatchingResult> submitDirectCancel(
            long userId, long orderId, int symbolId) {
        return completedMatcher(() -> engine.cancel(activeAeronTimestamp, orderId, symbolId, userId));
    }

    public CompletableFuture<CoreMatchingResult> cancelAsyncForContinuation(long userId, long orderId,
                                                                               String symbol) {
        return cancelAsync(userId, orderId, symbol);
    }

    public CoreMatchingResult cancelForContinuation(long userId, long orderId, String symbol) {
        return cancel(userId, orderId, symbol);
    }

    private CoreMatchingResult cancel(long userId, long orderId, String symbol) {
        int symbolId = ensureSymbol(symbol);
        users.add(userId);
        return directMatcher(() -> engine.cancel(activeAeronTimestamp, orderId, symbolId, userId));
    }

    public CompletableFuture<CoreMatchingResult> replaceOrderAsync(long userId, long orderId, String symbol,
                                                                    CoreMatchingOrder replacement) {
        users.add(userId);
        return ensureSymbolAsync(symbol).thenCompose(symbolId ->
                    submitDirectCancel(userId, orderId, symbolId).thenCompose(cancelResult -> {
                        if (!cancelResult.accepted()) {
                            return CompletableFuture.completedFuture(cancelResult);
                        }
                        return ensureSymbolAsync(replacement.symbol()).thenCompose(replacementSymbolId -> {
                            return submitDirectPlace(userId, replacementSymbolId, replacement)
                                    .thenApply(result -> {
                                List<CoreCancellationResult> cancellations = List.of(
                                        new CoreCancellationResult(orderId, true, cancelResult.resultCode()));
                                List<exchange.core2.core.common.MatcherResult.MatcherEvent> events =
                                        CoreMatchingResult.concatenateEvents(
                                                cancelResult.matcherEvents(), result.matcherEvents());
                                return new CoreMatchingResult(result.accepted(), result.resultCode(),
                                        cancellations, 1, !result.accepted(),
                                        result.nativeCommand(), new CoreMatchingResult.MatcherPrefix(0, 0),
                                        result.nativeMatcherResult(), events, result.marketData());
                            });
                        });
                    }));
    }

    public CoreMatchingResult replaceOrder(long userId, long orderId, String symbol,
                                           CoreMatchingOrder replacement) {
        users.add(userId);
        CoreMatchingResult cancelled = cancel(userId, orderId, symbol);
        if (!cancelled.accepted()) return cancelled;
        CoreMatchingResult placed = place(userId, replacement);
        List<CoreCancellationResult> cancellations = List.of(
                new CoreCancellationResult(orderId, true, cancelled.resultCode()));
        List<exchange.core2.core.common.MatcherResult.MatcherEvent> events =
                CoreMatchingResult.concatenateEvents(cancelled.matcherEvents(), placed.matcherEvents());
        return new CoreMatchingResult(placed.accepted(), placed.resultCode(), cancellations, 1,
                !placed.accepted(), placed.nativeCommand(), new CoreMatchingResult.MatcherPrefix(0, 0),
                placed.nativeMatcherResult(), events, placed.marketData());
    }

    public CompletableFuture<CoreMatchingResult> replaceAsync(long userId, long orderId, String symbol,
                                                               long newPriceTicks) {
        Integer symbolId = symbols.get(symbol);
        users.add(userId);
        if (symbolId != null) return submitDirectMove(userId, orderId, symbolId, newPriceTicks);
        return ensureSymbolAsync(symbol)
                .thenCompose(registered -> submitDirectMove(userId, orderId, registered, newPriceTicks));
    }

    private CompletableFuture<CoreMatchingResult> submitDirectMove(
            long userId, long orderId, int symbolId, long newPriceTicks) {
        return completedMatcher(() -> engine.move(activeAeronTimestamp, newPriceTicks, orderId, symbolId, userId));
    }

    public CompletableFuture<Integer> orderBooksStateHashAsync() {
        return currentStateHashesAsync().thenApply(StateHashes::bookHash);
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
                                    engine.storeSnapshot(snapshotId, coreSequence, coreSequence)
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
                                    long matcherSequence = modules.stream()
                                            .mapToLong(InMemorySerializationProcessor.SerializedModule::sequence)
                                            .max().orElseThrow();
                                    return new MatcherSnapshot(state.productLine(), MatcherSnapshot.CORE_SHARD_ID,
                                            MatcherSnapshot.ROUTE_VERSION, topology, snapshotId, coreSequence, matcherSequence,
                                            matcherEvidence.snapshot(), businessStateHash,
                                            hashes.engineHash(), hashes.bookHash(),
                                            MatcherSnapshot.symbolRegistryHash(symbols),
                                            topology.symbolRouteHash(symbols),
                                            MatcherSnapshot.userRegistryHash(users),
                                            MatcherSnapshot.instrumentRegistryHash(state),
                                            MatcherSnapshot.activeOrderHash(state), MatcherSnapshot.FORK_GIT_SHA,
                                            MatcherSnapshot.ARTIFACT_SHA256, MatcherSnapshot.matcherConfigHash(topology),
                                            symbols, users, modules);
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
                if (order.status() != com.surprising.aeron.service.state.CoreOrderStatus.OPEN) {
                    throw new FatalMatchingDivergenceException(operation, coreSequence, snapshotId,
                            "active-order index contains a terminal order");
                }
                Integer symbolId = symbols.get(order.symbol());
                if (symbolId == null) {
                    throw new FatalMatchingDivergenceException(operation, coreSequence, snapshotId,
                            "Core open order references an unregistered matcher symbol");
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
            for (SynchronousMatchingEngine.OpenOrder order : engine.openOrders()) {
                ReconciledOrder reconciled = new ReconciledOrder(order.symbolId(), order.orderId(), order.uid(),
                        order.action(), order.price(), order.size(), order.filled(), order.reserveBidPrice());
                if (actual.put(order.orderId(), reconciled) != null) {
                    throw new FatalMatchingDivergenceException(operation, coreSequence, snapshotId,
                            "exchange-core returned duplicate open order ID " + order.orderId());
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

    private CompletableFuture<StateHashes> currentStateHashesAsync() {
        return CompletableFuture.completedFuture(engine.stateHashReport())
                .thenApply(report -> {
                    int engineHash = 1;
                    int bookHash = 0;
                    for (Map.Entry<exchange.core2.core.common.api.reports.StateHashReportResult.SubmoduleKey,
                            Integer> entry : report.getHashCodes().entrySet()) {
                        engineHash = engineHash * 31 + entry.getKey().submodule.code;
                        engineHash = engineHash * 31 + entry.getKey().moduleId;
                        engineHash = engineHash * 31 + entry.getValue();
                        if (entry.getKey().submodule
                                == exchange.core2.core.common.api.reports.StateHashReportResult.SubmoduleType
                                .MATCHING_ORDER_BOOKS) {
                            bookHash = bookHash * 31 + entry.getValue();
                        }
                    }
                    return new StateHashes(engineHash, bookHash);
                });
    }

    public CompletableFuture<List<CoreBookLevelView>> orderBookLevelsAsync(String requestedSymbol, int depth) {
        if (requestedSymbol == null || requestedSymbol.isBlank() || depth < 1 || depth > 100) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("invalid single-symbol book query"));
        }
        String symbol = requestedSymbol.trim().toUpperCase(java.util.Locale.ROOT);
        Integer symbolId = symbols.get(symbol);
        if (symbolId == null) return CompletableFuture.completedFuture(List.of());
        return CompletableFuture.completedFuture(bookLevels(symbol, engine.orderBook(symbolId, depth)));
    }

    public CompletableFuture<BookBootstrapSnapshot> orderBookBootstrapAsync(int depth) {
        if (depth < 1 || depth > 100) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("invalid bootstrap book depth"));
        }
        return currentStateHashesAsync().thenCompose(barrierComplete -> {
            List<Map.Entry<String, Integer>> entries = symbols.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey()).toList();
            List<CompletableFuture<BookResult>> requests = new ArrayList<>(entries.size());
            for (Map.Entry<String, Integer> entry : entries) {
                requests.add(CompletableFuture.completedFuture(
                        new BookResult(entry.getKey(), engine.orderBook(entry.getValue(), depth))));
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
                    levels.addAll(bookLevels(result.symbol(), result.book()));
                }
                levels.sort(Comparator.comparing(CoreBookLevelView::symbol)
                        .thenComparing(CoreBookLevelView::side)
                        .thenComparingLong(CoreBookLevelView::priceTicks));
                return new BookBootstrapSnapshot(entries.stream().map(Map.Entry::getKey).toList(), levels);
            });
        });
    }

    private static List<CoreBookLevelView> bookLevels(String symbol, L2MarketData book) {
        List<CoreBookLevelView> levels = new ArrayList<>(Math.addExact(book.askSize, book.bidSize));
        for (int index = 0; index < book.askSize; index++) {
            levels.add(new CoreBookLevelView(symbol, CoreOrderSide.SELL, book.askPrices[index],
                    book.askVolumes[index], book.askOrders[index]));
        }
        for (int index = 0; index < book.bidSize; index++) {
            levels.add(new CoreBookLevelView(symbol, CoreOrderSide.BUY, book.bidPrices[index],
                    book.bidVolumes[index], book.bidOrders[index]));
        }
        levels.sort(Comparator.comparing(CoreBookLevelView::side)
                .thenComparingLong(CoreBookLevelView::priceTicks));
        return levels;
    }

    private record BookResult(String symbol, L2MarketData book) {
    }

    public CompletableFuture<Integer> ensureInstrumentAsync(CoreInstrumentState instrument) {
        if (instrument == null) {
            throw new IllegalArgumentException("instrument is required");
        }
        return ensureSymbolAsync(instrument.symbol());
    }

    private void start() {
        start(null);
    }

    private void start(MatcherSnapshot snapshot) {
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
                        .matchingEnginesNum(topology.matchingEngineCount())
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
                        .serializationProcessorFactory(ignored -> serializationProcessor)
                        .build())
                .build();
        engine = new SynchronousMatchingEngine(configuration);
    }

    public LaneTopology topology() { return topology; }
    public int dispatchDepth() { return dispatchInFlight.get(); }
    public int dispatchCapacity() { return topology.matcherWindowSize(); }
    public int dispatchHighWaterMark() { return dispatchHighWaterMark.get(); }

    private int matcherShardId(CoreMatchingResult result) {
        int symbolId = result.nativeMatcherResult() == null ? 0 : result.nativeMatcherResult().symbol();
        return symbolId <= 0 ? -1 : topology.matcherShardId(symbolId);
    }

    private CompletableFuture<Integer> ensureSymbolAsync(String symbol) {
        try {
            return CompletableFuture.completedFuture(ensureSymbol(symbol));
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    private int ensureSymbol(String symbol) {
        Integer existing = symbols.get(symbol);
        if (existing != null) return existing;
        int symbolId = stableSymbolId(symbol);
        CoreSymbolSpecification specification = CoreSymbolSpecification.builder()
                .symbolId(symbolId).type(SymbolType.CURRENCY_EXCHANGE_PAIR)
                .baseCurrency(stableId("BASE:" + symbol)).quoteCurrency(stableId("QUOTE:" + symbol))
                .baseScaleK(1).quoteScaleK(1).makerFee(0).takerFee(0).marginBuy(0).marginSell(0).build();
        CommandResultCode result = engine.registerSymbol(specification);
        if (result != CommandResultCode.SUCCESS
                && result != CommandResultCode.SYMBOL_MGMT_SYMBOL_ALREADY_EXISTS) {
            throw new IllegalStateException("failed to add exchange-core symbol " + symbol + ": " + result);
        }
        symbols.put(symbol, symbolId);
        symbolNames.put(symbolId, symbol);
        return symbolId;
    }

    private CompletableFuture<CoreMatchingResult> completedMatcher(
            java.util.function.Supplier<exchange.core2.core.common.MatcherResult> submission) {
        try {
            return CompletableFuture.completedFuture(CoreMatchingResult.fromNative(submission.get()));
        } catch (Throwable failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    private CoreMatchingResult directMatcher(
            java.util.function.Supplier<exchange.core2.core.common.MatcherResult> submission) {
        return CoreMatchingResult.fromNative(submission.get());
    }

    private static <T> CancelBatchOutcome cancelBatchOrdered(
            List<T> orders, Function<T, CoreMatchingResult> cancellation) {
        List<CoreMatchingResult> results = new ArrayList<>(
                java.util.Collections.nCopies(orders.size(), notSubmitted()));
        List<CoreMatchingResult> successfulPrefix = new ArrayList<>();
        CoreMatchingResult failed = null;
        Throwable exception = null;
        for (int index = 0; index < orders.size(); index++) {
            try {
                CoreMatchingResult result = cancellation.apply(orders.get(index));
                results.set(index, result);
                if (!result.accepted()) {
                    failed = result;
                    break;
                }
                successfulPrefix.add(result);
            } catch (RuntimeException failure) {
                exception = failure;
                break;
            }
        }
        return new CancelBatchOutcome(results, successfulPrefix, failed, exception);
    }

    private int stableSymbolId(String symbol) {
        int symbolId = stableId("SYMBOL:" + symbol);
        while (symbolId == 0 || (symbolNames.containsKey(symbolId) && !symbol.equals(symbolNames.get(symbolId)))) {
            symbolId = symbolId == Integer.MAX_VALUE ? 1 : symbolId + 1;
        }
        return symbolId;
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
        if (engine != null) {
            awaitSnapshotOperations();
            if (matcherFailure.get() == null) engine.stateHashReport();
            engine.close();
            engine = null;
        }
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

    public record CancelBatchOutcome(List<CoreMatchingResult> results,
                                     List<CoreMatchingResult> successfulPrefix,
                                     CoreMatchingResult failedResult,
                                     Throwable exception) {

        public CancelBatchOutcome {
            results = List.copyOf(results);
            successfulPrefix = List.copyOf(successfulPrefix);
        }

        private static CancelBatchOutcome rejected(int size, CoreMatchingResult failure) {
            List<CoreMatchingResult> results = new ArrayList<>(java.util.Collections.nCopies(size, notSubmitted()));
            if (!results.isEmpty()) results.set(0, failure);
            return new CancelBatchOutcome(results, List.of(), failure, null);
        }
    }

    static <T> CompletableFuture<CancelBatchOutcome> cancelBatchOrderedAsync(
            List<T> orders,
            Function<T, CompletableFuture<CoreMatchingResult>> cancellation) {
        List<CoreMatchingResult> results = new ArrayList<>(
                java.util.Collections.nCopies(orders.size(), notSubmitted()));
        List<CoreMatchingResult> successfulPrefix = new ArrayList<>();
        CompletableFuture<BatchProgress> chain = CompletableFuture.completedFuture(new BatchProgress(null, null));
        for (int index = 0; index < orders.size(); index++) {
            int resultIndex = index;
            chain = chain.thenCompose(progress -> {
                if (progress.failedResult() != null || progress.exception() != null) {
                    return CompletableFuture.completedFuture(progress);
                }
                CompletableFuture<CoreMatchingResult> future;
                try {
                    future = cancellation.apply(orders.get(resultIndex));
                } catch (RuntimeException exception) {
                    return CompletableFuture.completedFuture(new BatchProgress(null, exception));
                }
                return future.handle((result, exception) -> {
                    if (exception != null) return new BatchProgress(null, unwrap(exception));
                    results.set(resultIndex, result);
                    if (!result.accepted()) return new BatchProgress(result, null);
                    successfulPrefix.add(result);
                    return progress;
                });
            });
        }
        return chain.thenApply(progress -> new CancelBatchOutcome(results, successfulPrefix,
                progress.failedResult(), progress.exception()));
    }

    private static Throwable unwrap(Throwable failure) {
        return failure instanceof java.util.concurrent.CompletionException && failure.getCause() != null
                ? failure.getCause() : failure;
    }

    public record CancellationOrder(long orderId, long userId, String symbol) {
        public CancellationOrder {
            if (orderId <= 0 || userId <= 0 || symbol == null || symbol.isBlank()) {
                throw new IllegalArgumentException("invalid cancellation order");
            }
        }
    }

    private static CoreMatchingResult notSubmitted() {
        return new CoreMatchingResult(false, "NOT_SUBMITTED");
    }

    private record BatchProgress(CoreMatchingResult failedResult, Throwable exception) {
    }
}
