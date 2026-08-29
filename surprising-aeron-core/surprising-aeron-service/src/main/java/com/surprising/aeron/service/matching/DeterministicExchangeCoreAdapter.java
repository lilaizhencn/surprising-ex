package com.surprising.aeron.service.matching;

import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreBookLevelView;
import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.protocol.ReservationKind;
import com.surprising.aeron.service.state.CoreInstrumentState;
import com.surprising.aeron.service.state.CoreOrderState;
import com.surprising.aeron.service.state.TradingCoreState;
import com.surprising.aeron.service.state.LaneTopology;
import exchange.core2.core.ExchangeApi;
import exchange.core2.core.ExchangeCore;
import exchange.core2.core.common.CoreSymbolSpecification;
import exchange.core2.core.common.L2MarketData;
import exchange.core2.core.common.OrderAction;
import exchange.core2.core.common.OrderType;
import exchange.core2.core.common.SymbolType;
import exchange.core2.core.common.api.ApiAddUser;
import exchange.core2.core.common.api.ApiCancelOrder;
import exchange.core2.core.common.api.ApiPlaceOrder;
import exchange.core2.core.common.api.ApiMoveOrder;
import exchange.core2.core.common.api.ApiPersistState;
import exchange.core2.core.common.api.binary.BatchAddSymbolsCommand;
import exchange.core2.core.common.api.reports.OpenOrdersReportQuery;
import exchange.core2.core.common.api.reports.OpenOrdersReportResult;
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

public final class DeterministicExchangeCoreAdapter implements AutoCloseable {

    private ExchangeCore core;
    private ExchangeApi api;
    private final InMemorySerializationProcessor serializationProcessor =
            new InMemorySerializationProcessor();
    private final Map<String, Integer> symbols = new ConcurrentHashMap<>();
    private final Map<Integer, String> symbolNames = new ConcurrentHashMap<>();
    private final Set<Long> users = ConcurrentHashMap.newKeySet();
    private final Map<String, CompletableFuture<Integer>> symbolRegistrations = new ConcurrentHashMap<>();
    private final Map<Long, CompletableFuture<Void>> userRegistrations = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, SnapshotOperation> snapshotOperations = new ConcurrentHashMap<>();
    private final AtomicReference<CompletableFuture<Void>> submissionTail =
            new AtomicReference<>(CompletableFuture.completedFuture(null));
    private final LaneTopology topology;
    private final AtomicReference<Throwable> matcherFailure = new AtomicReference<>();
    private final MatcherEvidenceLedger matcherEvidence;
    private final AtomicInteger dispatchInFlight = new AtomicInteger();
    private final AtomicInteger dispatchHighWaterMark = new AtomicInteger();
    private final Function<Supplier<CompletableFuture<CommandResultCode>>, CompletableFuture<CommandResultCode>>
            snapshotPersistence;

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
        }
    }

    public DeterministicExchangeCoreAdapter(
            TradingCoreState state,
            Iterable<CoreOrderState> activeOrders,
            long coreSequence,
            MatcherSnapshot snapshot) {
        this.snapshotPersistence = submission -> submission.get();
        if (snapshot == null || activeOrders == null) {
            throw new IllegalArgumentException("matcher snapshot is required");
        }
        try {
            snapshot.verifyCoreState(state, coreSequence);
            this.topology = snapshot.topology();
            this.matcherEvidence = new MatcherEvidenceLedger(
                    topology, snapshot.matcherSequence(), snapshot.matcherShardProgress());
            serializationProcessor.importSnapshot(snapshot.modules());
            snapshot.symbols().forEach((symbol, symbolId) -> {
                String previous = symbolNames.put(symbolId, symbol);
                if (previous != null && !previous.equals(symbol)) {
                    throw new IllegalArgumentException("matcher symbol registry collision");
                }
                symbols.put(symbol, symbolId);
            });
            users.addAll(snapshot.users());
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
        } catch (RuntimeException exception) {
            stop();
            Throwable cause = unwrap(exception);
            if (cause instanceof FatalMatchingDivergenceException divergence) throw divergence;
            throw new FatalMatchingDivergenceException("matcher restore", coreSequence,
                    snapshot.snapshotId(), "native snapshot validation failed", cause);
        }
    }

    public CompletableFuture<CoreMatchingResult> placeAsync(long userId, CoreMatchingOrder command) {
        return placeUnlanedAsync(userId, command);
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
        CompletableFuture<?>[] futures = new CompletableFuture<?>[symbolFutures.size() + 1];
        futures[0] = ensureUserAsync(userId);
        for (int index = 0; index < symbolFutures.size(); index++) {
            futures[index + 1] = symbolFutures.get(index);
        }
        return CompletableFuture.allOf(futures);
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
            submitted = command.get();
        } catch (RuntimeException exception) {
            matcherFailure.compareAndSet(null, exception);
            return CompletableFuture.failedFuture(exception);
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
        CoreMatchingResult bound = matcherEvidence.bind(coreSequence, commandId, orderId,
                instrumentVersion, aeronTimestamp, sequence,
                controlShard ? -1 : matcherShardId(result), result);
        if ("EXCHANGE_CORE_FAILURE".equals(bound.resultCode())
                || "MATCHING_TIMEOUT".equals(bound.resultCode())
                || !bound.accepted() && bound.matcherStateChanged()) {
            matcherFailure.compareAndSet(null,
                    new IllegalStateException("fatal matcher result: " + bound.resultCode()));
        }
        return bound;
    }

    private CompletableFuture<CoreMatchingResult> placeUnlanedAsync(long userId, CoreMatchingOrder command) {
        return ensureSymbolAsync(command.symbol())
                .thenCombine(ensureUserAsync(userId), (symbolId, ignored) -> symbolId)
                .thenCompose(symbolId -> {
                    return api.submitCommandAsyncMatcherResult(ApiPlaceOrder.builder()
                        .orderId(command.orderId())
                        .uid(userId)
                        .symbol(symbolId)
                        .action(command.side() == CoreOrderSide.BUY ? OrderAction.BID : OrderAction.ASK)
                        .orderType(orderType(command))
                        .price(command.matchingPriceTicks())
                        .reservePrice(command.matchingPriceTicks())
                        .size(command.quantitySteps()).build());
                })
                .thenApply(DeterministicExchangeCoreAdapter::matchingResult);
    }

    private static CoreMatchingResult matchingResult(exchange.core2.core.common.MatcherResult response) {
        return CoreMatchingResult.fromNative(response);
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
                new CoreMatchingResult.NativeCommand(0, "", 0, 0, nativeSequence, 0, 0),
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
                new CoreMatchingResult.NativeCommand(0, "", 0, 0, nativeSequence, 0, 0),
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
        return ensureSymbolAsync(symbol).thenCompose(symbolId -> ensureUserAsync(userId)
                .thenCompose(ignored -> {
                    return api.submitCommandAsyncMatcherResult(ApiCancelOrder.builder()
                            .orderId(orderId).uid(userId).symbol(symbolId).build());
                }))
                .thenApply(DeterministicExchangeCoreAdapter::matchingResult);
    }

    public CompletableFuture<CoreMatchingResult> cancelAsyncForContinuation(long userId, long orderId,
                                                                               String symbol) {
        return cancelAsync(userId, orderId, symbol);
    }

    public CompletableFuture<CoreMatchingResult> replaceOrderAsync(long userId, long orderId, String symbol,
                                                                    CoreMatchingOrder replacement) {
        return ensureSymbolAsync(symbol).thenCompose(symbolId -> ensureUserAsync(userId)
                .thenCompose(ignored -> {
                    CompletableFuture<exchange.core2.core.common.MatcherResult> cancel =
                            api.submitCommandAsyncMatcherResult(ApiCancelOrder.builder()
                            .orderId(orderId).uid(userId).symbol(symbolId).build());
                    return cancel.thenCompose(cancelResponse -> {
                        CoreMatchingResult cancelResult = matchingResult(cancelResponse);
                        if (!cancelResult.accepted()) {
                            return CompletableFuture.completedFuture(cancelResult);
                        }
                        return ensureSymbolAsync(replacement.symbol()).thenCompose(replacementSymbolId -> {
                            CompletableFuture<exchange.core2.core.common.MatcherResult> place =
                                    api.submitCommandAsyncMatcherResult(ApiPlaceOrder.builder()
                                            .orderId(replacement.orderId()).uid(userId).symbol(replacementSymbolId)
                                            .action(replacement.side() == CoreOrderSide.BUY ? OrderAction.BID : OrderAction.ASK)
                                            .orderType(orderType(replacement)).price(replacement.matchingPriceTicks())
                                            .reservePrice(replacement.matchingPriceTicks())
                                            .size(replacement.quantitySteps()).build());
                            return place.thenApply(response -> {
                                CoreMatchingResult result = matchingResult(response);
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
                    });
                }));
    }

    public CompletableFuture<CoreMatchingResult> replaceAsync(long userId, long orderId, String symbol,
                                                               long newPriceTicks) {
        return ensureSymbolAsync(symbol).thenCompose(symbolId -> ensureUserAsync(userId)
                .thenCompose(ignored -> {
                    CompletableFuture<exchange.core2.core.common.MatcherResult> submitted = api.submitCommandAsyncMatcherResult(ApiMoveOrder.builder()
                            .orderId(orderId).uid(userId).symbol(symbolId).newPrice(newPriceTicks).build());
                    return submitted;
                }))
                .thenApply(DeterministicExchangeCoreAdapter::matchingResult);
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
                    snapshotPersistence.apply(() -> api.submitCommandAsync(
                                    ApiPersistState.builder().dumpId(snapshotId).build()))
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
        return api.processReport(new OpenOrdersReportQuery(), 0).thenAccept(report -> {
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
            for (OpenOrdersReportResult.OpenOrder order : report.getOrders()) {
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
        });
    }

    private CompletableFuture<StateHashes> currentStateHashesAsync() {
        return api.processReport(new exchange.core2.core.common.api.reports.StateHashReportQuery(), 0)
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
        return api.requestOrderBookAsync(symbolId, depth).thenApply(book -> bookLevels(symbol, book));
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
                requests.add(api.requestOrderBookAsync(entry.getValue(), depth)
                        .thenApply(book -> new BookResult(entry.getKey(), book)));
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
                        .msgsInGroupLimit(Math.max(1,
                                Integer.getInteger("surprising.aeron.matcher-group-size", 256)))
                        .maxGroupDurationNs(Math.max(1,
                                Integer.getInteger("surprising.aeron.matcher-group-nanos", 10_000)))
                        .waitStrategy(MatcherRuntimeConfiguration.waitStrategy()).build())
                .initStateCfg(initialState)
                .serializationCfg(SerializationConfiguration.builder()
                        .enableJournaling(false)
                        .serializationProcessorFactory(ignored -> serializationProcessor)
                        .build())
                .build();
        core = ExchangeCore.builder().exchangeConfiguration(configuration).resultsConsumer((command, sequence) -> {
        }).build();
        core.startup();
        api = core.getApi();
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
        Integer existing = symbols.get(symbol);
        if (existing != null) return CompletableFuture.completedFuture(existing);
        return symbolRegistrations.computeIfAbsent(symbol, key -> {
            int symbolId = stableSymbolId(key);
            CoreSymbolSpecification specification = CoreSymbolSpecification.builder()
                    .symbolId(symbolId).type(SymbolType.CURRENCY_EXCHANGE_PAIR)
                    .baseCurrency(stableId("BASE:" + key)).quoteCurrency(stableId("QUOTE:" + key))
                    .baseScaleK(1).quoteScaleK(1).makerFee(0).takerFee(0).marginBuy(0).marginSell(0).build();
            return submitOrdered(() -> api.submitBinaryDataAsync(new BatchAddSymbolsCommand(specification))).thenApply(result -> {
                if (result != CommandResultCode.SUCCESS
                        && result != CommandResultCode.SYMBOL_MGMT_SYMBOL_ALREADY_EXISTS) {
                    throw new IllegalStateException("failed to add exchange-core symbol " + key + ": " + result);
                }
                symbols.put(key, symbolId);
                symbolNames.put(symbolId, key);
                return symbolId;
            });
        });
    }

    private CompletableFuture<Void> ensureUserAsync(long userId) {
        if (users.contains(userId)) return CompletableFuture.completedFuture(null);
        return userRegistrations.computeIfAbsent(userId, key ->
                submitOrdered(() -> api.submitCommandAsync(ApiAddUser.builder().uid(key).build())).thenApply(result -> {
                    if (result != CommandResultCode.SUCCESS
                            && result != CommandResultCode.USER_MGMT_USER_ALREADY_EXISTS) {
                        throw new IllegalStateException("failed to add exchange-core user " + key + ": " + result);
                    }
                    users.add(key);
                    return null;
                }));
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
        if (core != null) {
            awaitSnapshotOperations();
            if (api != null) {
                submitOrdered(() -> api.processReport(
                        new exchange.core2.core.common.api.reports.StateHashReportQuery(), 0)).join();
            }
            core.shutdown(30, TimeUnit.SECONDS);
            core = null;
            api = null;
            symbolRegistrations.clear();
            userRegistrations.clear();
            submissionTail.set(CompletableFuture.completedFuture(null));
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

    private <T> CompletableFuture<T> submitOrdered(Supplier<CompletableFuture<T>> submission) {
        CompletableFuture<Void> gate = new CompletableFuture<>();
        CompletableFuture<Void> previous = reserve(submissionTail, gate);
        return previous.thenCompose(ignored -> {
            CompletableFuture<T> result;
            try {
                result = submission.get();
                result.whenComplete((value, failure) -> gate.complete(null));
            } catch (RuntimeException exception) {
                gate.complete(null);
                throw exception;
            }
            return result;
        });
    }

    private static CompletableFuture<Void> reserve(
            AtomicReference<CompletableFuture<Void>> tail, CompletableFuture<Void> gate) {
        CompletableFuture<Void> previous;
        do {
            previous = tail.get();
        } while (!tail.compareAndSet(previous, gate));
        return previous;
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
