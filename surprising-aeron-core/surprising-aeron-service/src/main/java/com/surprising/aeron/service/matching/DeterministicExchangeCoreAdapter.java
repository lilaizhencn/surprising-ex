package com.surprising.aeron.service.matching;

import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreBookLevelView;
import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.protocol.ReservationKind;
import com.surprising.aeron.service.state.CoreInstrumentState;
import com.surprising.aeron.service.state.CoreOrderState;
import com.surprising.aeron.service.state.CoreOrderStatus;
import com.surprising.aeron.service.state.TradingCoreState;
import exchange.core2.core.ExchangeApi;
import exchange.core2.core.ExchangeCore;
import exchange.core2.core.common.CoreSymbolSpecification;
import exchange.core2.core.common.CoreWaitStrategy;
import exchange.core2.core.common.L2MarketData;
import exchange.core2.core.common.MatcherEventType;
import exchange.core2.core.common.OrderAction;
import exchange.core2.core.common.OrderType;
import exchange.core2.core.common.SymbolType;
import exchange.core2.core.common.api.ApiAddUser;
import exchange.core2.core.common.api.ApiCancelOrder;
import exchange.core2.core.common.api.ApiPlaceOrder;
import exchange.core2.core.common.api.ApiMoveOrder;
import exchange.core2.core.common.api.binary.BatchAddSymbolsCommand;
import exchange.core2.core.common.cmd.CommandResultCode;
import exchange.core2.core.common.config.ExchangeConfiguration;
import exchange.core2.core.common.config.InitialStateConfiguration;
import exchange.core2.core.common.config.OrdersProcessingConfiguration;
import exchange.core2.core.common.config.PerformanceConfiguration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.function.Function;

public final class DeterministicExchangeCoreAdapter implements AutoCloseable {

    private static final int MAX_QUERY_DEPTH = 1_000;

    private ExchangeCore core;
    private ExchangeApi api;
    private final Map<String, Integer> symbols = new ConcurrentHashMap<>();
    private final Map<Integer, String> symbolNames = new ConcurrentHashMap<>();
    private final Set<Long> users = ConcurrentHashMap.newKeySet();
    private final Map<String, CompletableFuture<Integer>> symbolRegistrations = new ConcurrentHashMap<>();
    private final Map<Long, CompletableFuture<Void>> userRegistrations = new ConcurrentHashMap<>();
    private final AtomicReference<CompletableFuture<Void>> submissionTail =
            new AtomicReference<>(CompletableFuture.completedFuture(null));
    private final AtomicReference<CompletableFuture<Void>> matchingSubmissionTail =
            new AtomicReference<>(CompletableFuture.completedFuture(null));

    public DeterministicExchangeCoreAdapter() {
        start();
    }

    public CompletableFuture<CoreMatchingResult> placeAsync(long userId, PlaceOrderCommand command) {
        return enqueueMatching(advance -> ensureSymbolAsync(command.symbol())
                .thenCombine(ensureUserAsync(userId), (symbolId, ignored) -> symbolId)
                .thenCompose(symbolId -> {
                    CompletableFuture<exchange.core2.core.common.cmd.OrderCommand> submitted = api.submitCommandAsyncFullResponse(ApiPlaceOrder.builder()
                        .orderId(command.orderId())
                        .uid(userId)
                        .symbol(symbolId)
                        .action(command.side() == CoreOrderSide.BUY ? OrderAction.BID : OrderAction.ASK)
                        .orderType(orderType(command))
                        .price(command.matchingPriceTicks())
                        .reservePrice(command.side() == CoreOrderSide.BUY ? Long.MAX_VALUE : command.matchingPriceTicks())
                        .size(command.quantitySteps())
                        .build());
                    advance.run();
                    return submitted;
                }))
                .thenApply(DeterministicExchangeCoreAdapter::matchingResult);
    }

    private static CoreMatchingResult matchingResult(exchange.core2.core.common.cmd.OrderCommand response) {
        List<CoreMatch> matches = new ArrayList<>();
        response.processMatcherEvents(event -> {
            if (event.eventType == MatcherEventType.TRADE) {
                matches.add(new CoreMatch(event.matchedOrderId, event.matchedOrderUid, event.price, event.size,
                        event.matchedOrderCompleted, event.activeOrderCompleted));
            }
        });
        return new CoreMatchingResult(response.resultCode == CommandResultCode.SUCCESS,
                response.resultCode.name(), matches);
    }

    private static OrderType orderType(PlaceOrderCommand command) {
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
        if (orders == null || orders.isEmpty()) {
            return CompletableFuture.completedFuture(new CoreMatchingResult(true, "SUCCESS", List.of()));
        }
        List<CompletableFuture<CoreMatchingResult>> futures = orders.stream()
                .map(order -> cancelAsync(order.userId(), order.orderId(), order.symbol())).toList();
        CompletableFuture<List<CoreMatchingResult>> combined = CompletableFuture.completedFuture(new ArrayList<>());
        for (CompletableFuture<CoreMatchingResult> future : futures) {
            combined = combined.thenCombine(future, (results, result) -> {
                List<CoreMatchingResult> next = new ArrayList<>(results);
                next.add(result);
                return next;
            });
        }
        return combined.thenApply(results -> results.stream().filter(result -> !result.accepted()).findFirst()
                .orElse(new CoreMatchingResult(true, "SUCCESS", List.of())));
    }

    private CompletableFuture<CoreMatchingResult> cancelAsync(long userId, long orderId, String symbol) {
        return enqueueMatching(advance -> ensureSymbolAsync(symbol).thenCompose(symbolId -> ensureUserAsync(userId)
                .thenCompose(ignored -> {
                    CompletableFuture<CommandResultCode> submitted = api.submitCommandAsync(ApiCancelOrder.builder()
                            .orderId(orderId).uid(userId).symbol(symbolId).build());
                    advance.run();
                    return submitted;
                })))
                .thenApply(resultCode -> new CoreMatchingResult(resultCode == CommandResultCode.SUCCESS,
                        resultCode.name(), List.of()));
    }

    public CompletableFuture<CoreMatchingResult> cancelAsyncForContinuation(long userId, long orderId,
                                                                               String symbol) {
        return cancelAsync(userId, orderId, symbol);
    }

    public CompletableFuture<CoreMatchingResult> replaceOrderAsync(long userId, long orderId, String symbol,
                                                                    PlaceOrderCommand replacement) {
        return enqueueMatching(advance -> ensureSymbolAsync(symbol).thenCompose(symbolId -> ensureUserAsync(userId)
                .thenCompose(ignored -> {
                    CompletableFuture<CommandResultCode> cancel = api.submitCommandAsync(ApiCancelOrder.builder()
                            .orderId(orderId).uid(userId).symbol(symbolId).build());
                    return cancel.thenCompose(cancelResult -> {
                        if (cancelResult != CommandResultCode.SUCCESS) {
                            advance.run();
                            return CompletableFuture.completedFuture(new CoreMatchingResult(false,
                                    cancelResult.name(), List.of()));
                        }
                        return ensureSymbolAsync(replacement.symbol()).thenCompose(replacementSymbolId -> {
                            CompletableFuture<exchange.core2.core.common.cmd.OrderCommand> place =
                                    api.submitCommandAsyncFullResponse(ApiPlaceOrder.builder()
                                            .orderId(replacement.orderId()).uid(userId).symbol(replacementSymbolId)
                                            .action(replacement.side() == CoreOrderSide.BUY ? OrderAction.BID : OrderAction.ASK)
                                            .orderType(orderType(replacement)).price(replacement.matchingPriceTicks())
                                            .reservePrice(replacement.side() == CoreOrderSide.BUY
                                                    ? Long.MAX_VALUE : replacement.matchingPriceTicks())
                                            .size(replacement.quantitySteps()).build());
                            advance.run();
                            return place.thenApply(DeterministicExchangeCoreAdapter::matchingResult);
                        });
                    });
                })));
    }

    public CompletableFuture<CoreMatchingResult> replaceAsync(long userId, long orderId, String symbol,
                                                               long newPriceTicks) {
        return enqueueMatching(advance -> ensureSymbolAsync(symbol).thenCompose(symbolId -> ensureUserAsync(userId)
                .thenCompose(ignored -> {
                    CompletableFuture<exchange.core2.core.common.cmd.OrderCommand> submitted = api.submitCommandAsyncFullResponse(ApiMoveOrder.builder()
                            .orderId(orderId).uid(userId).symbol(symbolId).newPrice(newPriceTicks).build());
                    advance.run();
                    return submitted;
                })))
                .thenApply(DeterministicExchangeCoreAdapter::matchingResult);
    }

    public CompletableFuture<Integer> orderBooksStateHashAsync() {
        return enqueueMatching(advance -> {
            CompletableFuture<Integer> result = api.processReport(
                            new exchange.core2.core.common.api.reports.StateHashReportQuery(), 0)
                    .thenApply(report -> report.getHashCodes().entrySet().stream()
                            .filter(entry -> entry.getKey().submodule
                                    == exchange.core2.core.common.api.reports.StateHashReportResult.SubmoduleType.MATCHING_ORDER_BOOKS)
                            .mapToInt(Map.Entry::getValue)
                            .reduce(0, (left, right) -> left * 31 + right));
            advance.run();
            return result;
        });
    }

    public CompletableFuture<List<CoreBookLevelView>> orderBookLevelsAsync(String requestedSymbol, int depth) {
        return enqueueMatching(advance -> {
            String symbolFilter = requestedSymbol == null ? "" : requestedSymbol;
            int boundedDepth = Math.min(Math.max(depth, 1), MAX_QUERY_DEPTH);
            List<Map.Entry<String, Integer>> entries = symbols.entrySet().stream()
                    .filter(entry -> symbolFilter.isEmpty() || entry.getKey().equals(symbolFilter))
                    .sorted(Map.Entry.comparingByKey()).toList();
            CompletableFuture<List<CoreBookLevelView>> result = CompletableFuture.completedFuture(new ArrayList<>());
            for (Map.Entry<String, Integer> entry : entries) {
                result = result.thenCombine(api.requestOrderBookAsync(entry.getValue(), boundedDepth), (levels, book) -> {
                    List<CoreBookLevelView> next = new ArrayList<>(levels);
                    for (int index = 0; index < book.askSize; index++) {
                        next.add(new CoreBookLevelView(entry.getKey(), CoreOrderSide.SELL, book.askPrices[index],
                                book.askVolumes[index], book.askOrders[index]));
                    }
                    for (int index = 0; index < book.bidSize; index++) {
                        next.add(new CoreBookLevelView(entry.getKey(), CoreOrderSide.BUY, book.bidPrices[index],
                                book.bidVolumes[index], book.bidOrders[index]));
                    }
                    return next;
                });
            }
            CompletableFuture<List<CoreBookLevelView>> sorted = result.thenApply(levels -> {
                levels.sort(Comparator.comparing(CoreBookLevelView::symbol)
                        .thenComparing(CoreBookLevelView::side)
                        .thenComparingLong(CoreBookLevelView::priceTicks));
                return List.copyOf(levels);
            });
            advance.run();
            return sorted;
        });
    }

    public CompletableFuture<Void> rebuildAsync(TradingCoreState state) {
        return rebuildAsync(state, Set.of());
    }

    public CompletableFuture<Void> rebuildAsync(TradingCoreState state, Set<Long> excludedOrderIds) {
        stop();
        symbols.clear();
        symbolNames.clear();
        users.clear();
        start();
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (CoreInstrumentState instrument : state.instruments().values().stream()
                .sorted(java.util.Comparator.comparing(CoreInstrumentState::symbol)).toList()) {
            chain = chain.thenCompose(ignored -> ensureInstrumentAsync(instrument).thenApply(symbolId -> null));
        }
        for (Long userId : state.users().keySet().stream().sorted().toList()) {
            chain = chain.thenCompose(ignored -> ensureUserAsync(userId));
        }
        long activeOrderCount = state.orders().values().stream()
                .filter(order -> order.status() == CoreOrderStatus.OPEN)
                .count();
        if (activeOrderCount != state.bookState().openOrders().size()) {
            throw new IllegalStateException("book recovery active order count mismatch");
        }
        List<PlaceRequest> requests = state.bookState().priorityOrder().stream()
                .map(state::order)
                .filter(order -> order != null && order.status() == CoreOrderStatus.OPEN
                        && (excludedOrderIds == null || !excludedOrderIds.contains(order.orderId())))
                .map(order -> {
            CoreInstrumentState instrument = state.instruments().get(order.symbol());
            if (instrument == null) {
                throw new IllegalStateException("book recovery instrument is missing symbol=" + order.symbol());
            }
            boolean spot = instrument.contractType() == com.surprising.instrument.api.model.ContractType.SPOT;
            PlaceOrderCommand command = new PlaceOrderCommand(order.orderId(), order.symbol(), order.instrumentVersion(),
                    instrument.baseAsset(), instrument.quoteAsset(), instrument.settleAsset(), order.side(),
                    order.priceTicks(), order.remainingQuantitySteps(), order.reduceOnly(), order.marginMode(),
                    order.positionSide(), spot ? ReservationKind.SPOT_ASSET : ReservationKind.DERIVATIVE_MARGIN,
                    spot && order.side() == CoreOrderSide.BUY ? instrument.quoteAsset()
                            : spot ? instrument.baseAsset() : instrument.settleAsset(), 0,
                    order.orderType(), order.timeInForce(), order.priceTicks(), order.postOnly(),
                    order.clientOrderId(), order.makerFeeRatePpm(), order.takerFeeRatePpm());
            return new PlaceRequest(order.userId(), command);
        }).toList();
        for (PlaceRequest request : requests) {
            chain = chain.thenCompose(ignored -> placeAsync(request.userId(), request.command()).thenAccept(result -> {
                if (!result.accepted() || !result.matches().isEmpty()) {
                    throw new IllegalStateException("book recovery crossed or rejected orderId="
                            + request.command().orderId());
                }
            }));
        }
        return chain;
    }

    public CompletableFuture<Integer> ensureInstrumentAsync(CoreInstrumentState instrument) {
        if (instrument == null) {
            throw new IllegalArgumentException("instrument is required");
        }
        return ensureSymbolAsync(instrument.symbol());
    }

    private void start() {
        ExchangeConfiguration configuration = ExchangeConfiguration.defaultBuilder()
                .ordersProcessingCfg(OrdersProcessingConfiguration.builder()
                        .riskProcessingMode(OrdersProcessingConfiguration.RiskProcessingMode.MATCHING_ONLY)
                        .marginTradingMode(OrdersProcessingConfiguration.MarginTradingMode.MARGIN_TRADING_DISABLED)
                        .build())
                .performanceCfg(PerformanceConfiguration.latencyPerformanceBuilder()
                        .matchingEnginesNum(1).riskEnginesNum(1).waitStrategy(CoreWaitStrategy.BUSY_SPIN).build())
                .initStateCfg(InitialStateConfiguration.cleanStart("aeron-authoritative-book"))
                .build();
        core = ExchangeCore.builder().exchangeConfiguration(configuration).resultsConsumer((command, sequence) -> {
        }).build();
        core.startup();
        api = core.getApi();
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

    public record PlaceRequest(long userId, PlaceOrderCommand command) {
        public PlaceRequest {
            if (userId <= 0 || command == null) throw new IllegalArgumentException("invalid place request");
        }
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

    private void stop() {
        if (core != null) {
            core.shutdown(5, TimeUnit.SECONDS);
            core = null;
            api = null;
            symbolRegistrations.clear();
            userRegistrations.clear();
            submissionTail.set(CompletableFuture.completedFuture(null));
            matchingSubmissionTail.set(CompletableFuture.completedFuture(null));
        }
    }

    private <T> CompletableFuture<T> enqueueMatching(
            Function<Runnable, CompletableFuture<T>> submission) {
        CompletableFuture<Void> gate = new CompletableFuture<>();
        CompletableFuture<Void> previous = reserve(matchingSubmissionTail, gate);
        return previous.thenCompose(ignored -> {
            CompletableFuture<T> result;
            try {
                result = submission.apply(() -> gate.complete(null));
            } catch (RuntimeException exception) {
                gate.complete(null);
                throw exception;
            }
            return result.whenComplete((value, failure) -> gate.complete(null));
        });
    }

    private <T> CompletableFuture<T> submitOrdered(Supplier<CompletableFuture<T>> submission) {
        CompletableFuture<Void> gate = new CompletableFuture<>();
        CompletableFuture<Void> previous = reserve(submissionTail, gate);
        return previous.thenCompose(ignored -> {
            CompletableFuture<T> result;
            try {
                result = submission.get();
                gate.complete(null);
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
}
