package com.surprising.aeron.service.matching;

import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.service.state.CoreBookOrder;
import com.surprising.aeron.service.state.CoreBookState;
import exchange.core2.core.ExchangeApi;
import exchange.core2.core.ExchangeCore;
import exchange.core2.core.common.CoreSymbolSpecification;
import exchange.core2.core.common.CoreWaitStrategy;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public final class DeterministicExchangeCoreAdapter implements AutoCloseable {

    private ExchangeCore core;
    private ExchangeApi api;
    private final Map<String, Integer> symbols = new HashMap<>();
    private final Set<Long> users = new HashSet<>();

    public DeterministicExchangeCoreAdapter() {
        start();
    }

    public CoreMatchingResult place(long userId, PlaceOrderCommand command) {
        int symbolId = ensureSymbol(command.symbol());
        ensureUser(userId);
        if (command.postOnly() && wouldTakeLiquidity(symbolId, command)) {
            return new CoreMatchingResult(false, "POST_ONLY_WOULD_TAKE", List.of());
        }
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

    private boolean wouldTakeLiquidity(int symbolId, PlaceOrderCommand command) {
        var book = api.requestOrderBookAsync(symbolId, 1).join();
        if (command.side() == CoreOrderSide.BUY) {
            return book.askSize > 0 && book.askPrices[0] <= command.matchingPriceTicks();
        }
        return book.bidSize > 0 && book.bidPrices[0] >= command.matchingPriceTicks();
    }

    private static OrderType orderType(PlaceOrderCommand command) {
        if (command.orderType() == com.surprising.aeron.protocol.CoreOrderType.MARKET) {
            return command.timeInForce() == com.surprising.aeron.protocol.CoreTimeInForce.FOK
                    ? OrderType.FOK : OrderType.IOC;
        }
        return switch (command.timeInForce()) {
            case IOC -> OrderType.IOC;
            case FOK -> OrderType.FOK;
            case GTC, GTX -> OrderType.GTC;
        };
    }

    public CoreMatchingResult cancel(long userId, long orderId, String symbol) {
        Integer symbolId = symbols.get(symbol);
        if (symbolId == null) {
            return new CoreMatchingResult(false, "UNKNOWN_SYMBOL", List.of());
        }
        ensureUser(userId);
        var response = api.submitCommandAsyncFullResponse(ApiCancelOrder.builder()
                .orderId(orderId).uid(userId).symbol(symbolId).build()).join();
        return new CoreMatchingResult(response.resultCode == CommandResultCode.SUCCESS,
                response.resultCode.name(), List.of());
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

    public void rebuild(CoreBookState bookState) {
        stop();
        symbols.clear();
        users.clear();
        start();
        for (CoreBookOrder order : bookState.recoveryOrder()) {
            PlaceOrderCommand command = new PlaceOrderCommand(order.orderId(), order.symbol(), 1,
                    "BASE", "QUOTE", "QUOTE", order.side(), order.priceTicks(),
                    order.remainingQuantitySteps(), false,
                    com.surprising.aeron.protocol.ReservationKind.SPOT_ASSET,
                    order.side() == CoreOrderSide.BUY ? "QUOTE" : "BASE", 1);
            CoreMatchingResult result = place(order.userId(), command);
            if (!result.accepted() || !result.matches().isEmpty()) {
                throw new IllegalStateException("book recovery crossed or rejected orderId=" + order.orderId());
            }
        }
    }

    private void start() {
        ExchangeConfiguration configuration = ExchangeConfiguration.defaultBuilder()
                .ordersProcessingCfg(OrdersProcessingConfiguration.builder()
                        .riskProcessingMode(OrdersProcessingConfiguration.RiskProcessingMode.NO_RISK_PROCESSING)
                        .marginTradingMode(OrdersProcessingConfiguration.MarginTradingMode.MARGIN_TRADING_DISABLED)
                        .build())
                .performanceCfg(PerformanceConfiguration.latencyPerformanceBuilder()
                        .matchingEnginesNum(1).riskEnginesNum(1).waitStrategy(CoreWaitStrategy.BLOCKING).build())
                .initStateCfg(InitialStateConfiguration.cleanStart("aeron-authoritative-book"))
                .build();
        core = ExchangeCore.builder().exchangeConfiguration(configuration).resultsConsumer((command, sequence) -> {
        }).build();
        core.startup();
        api = core.getApi();
    }

    private int ensureSymbol(String symbol) {
        return symbols.computeIfAbsent(symbol, value -> {
            int symbolId = stableId("SYMBOL:" + value);
            CoreSymbolSpecification specification = CoreSymbolSpecification.builder()
                    .symbolId(symbolId).type(SymbolType.CURRENCY_EXCHANGE_PAIR)
                    .baseCurrency(stableId("BASE:" + value)).quoteCurrency(stableId("QUOTE:" + value))
                    .baseScaleK(1).quoteScaleK(1).makerFee(0).takerFee(0).marginBuy(0).marginSell(0).build();
            CommandResultCode result = api.submitBinaryDataAsync(new BatchAddSymbolsCommand(specification)).join();
            if (result != CommandResultCode.SUCCESS) {
                throw new IllegalStateException("failed to add exchange-core symbol " + value + ": " + result);
            }
            return symbolId;
        });
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
