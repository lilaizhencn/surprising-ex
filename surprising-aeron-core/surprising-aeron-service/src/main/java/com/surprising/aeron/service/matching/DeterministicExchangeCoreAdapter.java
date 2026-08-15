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
import exchange.core2.core.common.api.reports.StateHashReportQuery;
import exchange.core2.core.common.api.reports.StateHashReportResult;
import exchange.core2.core.common.cmd.CommandResultCode;
import exchange.core2.core.common.config.ExchangeConfiguration;
import exchange.core2.core.common.config.InitialStateConfiguration;
import exchange.core2.core.common.config.OrdersProcessingConfiguration;
import exchange.core2.core.common.config.PerformanceConfiguration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public final class DeterministicExchangeCoreAdapter implements AutoCloseable {

    private static final int MAX_QUERY_DEPTH = 1_000;

    private ExchangeCore core;
    private ExchangeApi api;
    private final Map<String, Integer> symbols = new HashMap<>();
    private final Map<Integer, String> symbolNames = new HashMap<>();
    private final Set<Long> users = new HashSet<>();

    public DeterministicExchangeCoreAdapter() {
        start();
    }

    public CoreMatchingResult place(long userId, PlaceOrderCommand command) {
        int symbolId = ensureSymbol(command.symbol());
        ensureUser(userId);
        var response = api.submitCommandAsyncFullResponse(ApiPlaceOrder.builder()
                .orderId(command.orderId())
                .uid(userId)
                .symbol(symbolId)
                .action(command.side() == CoreOrderSide.BUY ? OrderAction.BID : OrderAction.ASK)
                .orderType(orderType(command))
                .price(command.matchingPriceTicks())
                .reservePrice(command.side() == CoreOrderSide.BUY ? Long.MAX_VALUE : command.matchingPriceTicks())
                .size(command.quantitySteps())
                .build()).join();
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

    public CoreMatchingResult cancel(long userId, long orderId, String symbol) {
        return cancelAsync(userId, orderId, symbol).join();
    }

    public List<CoreMatchingResult> cancelBatch(List<CoreOrderState> orders) {
        if (orders == null || orders.isEmpty()) {
            return List.of();
        }
        List<CompletableFuture<CoreMatchingResult>> futures = new ArrayList<>(orders.size());
        for (CoreOrderState order : orders) {
            futures.add(cancelAsync(order.userId(), order.orderId(), order.symbol()));
        }
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
        return futures.stream().map(CompletableFuture::join).toList();
    }

    private CompletableFuture<CoreMatchingResult> cancelAsync(long userId, long orderId, String symbol) {
        Integer symbolId = symbols.get(symbol);
        if (symbolId == null) {
            return CompletableFuture.completedFuture(
                    new CoreMatchingResult(false, "UNKNOWN_SYMBOL", List.of()));
        }
        ensureUser(userId);
        return api.submitCommandAsync(ApiCancelOrder.builder()
                        .orderId(orderId).uid(userId).symbol(symbolId).build())
                .thenApply(resultCode -> new CoreMatchingResult(resultCode == CommandResultCode.SUCCESS,
                        resultCode.name(), List.of()));
    }

    public CoreMatchingResult replace(long userId, long orderId, String symbol, long newPriceTicks) {
        Integer symbolId = symbols.get(symbol);
        if (symbolId == null) {
            return new CoreMatchingResult(false, "UNKNOWN_SYMBOL", List.of());
        }
        ensureUser(userId);
        var response = api.submitCommandAsyncFullResponse(ApiMoveOrder.builder()
                .orderId(orderId).uid(userId).symbol(symbolId).newPrice(newPriceTicks).build()).join();
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

    public int orderBooksStateHash() {
        var result = api.processReport(new StateHashReportQuery(), 0).join();
        return result.getHashCodes().entrySet().stream()
                .filter(entry -> entry.getKey().submodule
                        == StateHashReportResult.SubmoduleType.MATCHING_ORDER_BOOKS)
                .mapToInt(Map.Entry::getValue)
                .reduce(0, (left, right) -> left * 31 + right);
    }

    public List<CoreBookLevelView> orderBookLevels() {
        List<CoreBookLevelView> levels = new ArrayList<>();
        symbols.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            L2MarketData book = api.requestOrderBookAsync(entry.getValue(), MAX_QUERY_DEPTH).join();
            for (int index = 0; index < book.askSize; index++) {
                levels.add(new CoreBookLevelView(entry.getKey(), CoreOrderSide.SELL, book.askPrices[index],
                        book.askVolumes[index], book.askOrders[index]));
            }
            for (int index = 0; index < book.bidSize; index++) {
                levels.add(new CoreBookLevelView(entry.getKey(), CoreOrderSide.BUY, book.bidPrices[index],
                        book.bidVolumes[index], book.bidOrders[index]));
            }
        });
        levels.sort(Comparator.comparing(CoreBookLevelView::symbol)
                .thenComparing(CoreBookLevelView::side)
                .thenComparingLong(CoreBookLevelView::priceTicks));
        return List.copyOf(levels);
    }

    public void rebuild(TradingCoreState state) {
        stop();
        symbols.clear();
        symbolNames.clear();
        users.clear();
        start();
        state.instruments().values().forEach(this::ensureInstrument);
        state.bookState().priorityOrder().stream()
                .map(state::order)
                .filter(order -> order != null && order.status() == CoreOrderStatus.OPEN)
                .forEach(order -> {
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
            CoreMatchingResult result = place(order.userId(), command);
            if (!result.accepted() || !result.matches().isEmpty()) {
                throw new IllegalStateException("book recovery crossed or rejected orderId=" + order.orderId());
            }
                });
    }

    public void ensureInstrument(CoreInstrumentState instrument) {
        if (instrument == null) {
            throw new IllegalArgumentException("instrument is required");
        }
        ensureSymbol(instrument.symbol(), instrument);
    }

    private void start() {
        ExchangeConfiguration configuration = ExchangeConfiguration.defaultBuilder()
                .ordersProcessingCfg(OrdersProcessingConfiguration.builder()
                        .riskProcessingMode(OrdersProcessingConfiguration.RiskProcessingMode.NO_RISK_PROCESSING)
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

    private int ensureSymbol(String symbol) {
        return ensureSymbol(symbol, null);
    }

    private int ensureSymbol(String symbol, CoreInstrumentState instrument) {
        Integer existing = symbols.get(symbol);
        if (existing != null) {
            return existing;
        }
        int symbolId = stableSymbolId(symbol);
        CoreSymbolSpecification specification = CoreSymbolSpecification.builder()
                .symbolId(symbolId).type(symbolType(instrument))
                .baseCurrency(stableId("BASE:" + symbol)).quoteCurrency(stableId("QUOTE:" + symbol))
                .baseScaleK(1).quoteScaleK(1).makerFee(0).takerFee(0).marginBuy(0).marginSell(0).build();
        CommandResultCode result = api.submitBinaryDataAsync(new BatchAddSymbolsCommand(specification)).join();
        if (result != CommandResultCode.SUCCESS) {
            throw new IllegalStateException("failed to add exchange-core symbol " + symbol + ": " + result);
        }
        symbols.put(symbol, symbolId);
        symbolNames.put(symbolId, symbol);
        return symbolId;
    }

    private static SymbolType symbolType(CoreInstrumentState instrument) {
        if (instrument == null || instrument.contractType()
                == com.surprising.instrument.api.model.ContractType.SPOT) {
            return SymbolType.CURRENCY_EXCHANGE_PAIR;
        }
        if (instrument.contractType()
                == com.surprising.instrument.api.model.ContractType.VANILLA_OPTION) {
            return SymbolType.OPTION;
        }
        return SymbolType.FUTURES_CONTRACT;
    }

    private int stableSymbolId(String symbol) {
        int symbolId = stableId("SYMBOL:" + symbol);
        while (symbolId == 0 || (symbolNames.containsKey(symbolId) && !symbol.equals(symbolNames.get(symbolId)))) {
            symbolId = symbolId == Integer.MAX_VALUE ? 1 : symbolId + 1;
        }
        return symbolId;
    }

    private void ensureUser(long userId) {
        if (users.add(userId)) {
            CommandResultCode result = api.submitCommandAsync(ApiAddUser.builder().uid(userId).build()).join();
            if (result != CommandResultCode.SUCCESS) {
                users.remove(userId);
                throw new IllegalStateException("failed to add exchange-core user " + userId + ": " + result);
            }
        }
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
        }
    }

    @Override
    public void close() {
        stop();
    }
}
