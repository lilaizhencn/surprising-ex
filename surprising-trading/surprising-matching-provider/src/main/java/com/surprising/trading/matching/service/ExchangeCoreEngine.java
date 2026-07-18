package com.surprising.trading.matching.service;

import com.surprising.trading.api.model.OrderCommandEvent;
import com.surprising.trading.api.model.OrderCommandType;
import com.surprising.trading.matching.config.MatchingProperties;
import com.surprising.trading.matching.model.InstrumentSymbol;
import com.surprising.trading.matching.model.MatchingSymbol;
import com.surprising.trading.matching.model.RecoveredOrderBookOrder;
import com.surprising.trading.matching.repository.MatchingOrderBookRecoveryRepository;
import com.surprising.trading.matching.repository.MatchingSymbolRepository;
import exchange.core2.core.ExchangeApi;
import exchange.core2.core.ExchangeCore;
import exchange.core2.core.common.CoreSymbolSpecification;
import exchange.core2.core.common.CoreWaitStrategy;
import exchange.core2.core.common.L2MarketData;
import exchange.core2.core.common.SymbolType;
import exchange.core2.core.common.api.ApiAddUser;
import exchange.core2.core.common.api.ApiBinaryDataCommand;
import exchange.core2.core.common.api.ApiCancelOrder;
import exchange.core2.core.common.api.ApiPlaceOrder;
import exchange.core2.core.common.api.binary.BatchAddSymbolsCommand;
import exchange.core2.core.common.cmd.CommandResultCode;
import exchange.core2.core.common.cmd.OrderCommand;
import exchange.core2.core.common.config.ExchangeConfiguration;
import exchange.core2.core.common.config.InitialStateConfiguration;
import exchange.core2.core.common.config.OrdersProcessingConfiguration;
import exchange.core2.core.common.config.PerformanceConfiguration;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class ExchangeCoreEngine {

    private static final Logger log = LoggerFactory.getLogger(ExchangeCoreEngine.class);

    private final MatchingProperties properties;
    private final MatchingSymbolRepository symbolRepository;
    private final MatchingOrderBookRecoveryRepository recoveryRepository;
    private final ConcurrentMap<String, MatchingSymbol> loadedSymbols = new ConcurrentHashMap<>();
    private final Set<Long> createdUsers = ConcurrentHashMap.newKeySet();
    private volatile Set<String> activeSymbols = Set.of();

    private ExchangeCore exchangeCore;
    private ExchangeApi api;

    public ExchangeCoreEngine(MatchingProperties properties,
                              MatchingSymbolRepository symbolRepository,
                              MatchingOrderBookRecoveryRepository recoveryRepository) {
        this.properties = properties;
        this.symbolRepository = symbolRepository;
        this.recoveryRepository = recoveryRepository;
    }

    @PostConstruct
    public void start() {
        ExchangeConfiguration configuration = ExchangeConfiguration.defaultBuilder()
                .ordersProcessingCfg(OrdersProcessingConfiguration.builder()
                        .riskProcessingMode(OrdersProcessingConfiguration.RiskProcessingMode.NO_RISK_PROCESSING)
                        .marginTradingMode(OrdersProcessingConfiguration.MarginTradingMode.MARGIN_TRADING_DISABLED)
                        .build())
                .performanceCfg(PerformanceConfiguration.throughputPerformanceBuilder()
                        .matchingEnginesNum(properties.getEngine().getMatchingEngines())
                        .riskEnginesNum(properties.getEngine().getRiskEngines())
                        .waitStrategy(CoreWaitStrategy.BLOCKING)
                        .build())
                .initStateCfg(InitialStateConfiguration.cleanStart(properties.getEngine().getExchangeId()))
                .build();
        exchangeCore = ExchangeCore.builder()
                .exchangeConfiguration(configuration)
                .resultsConsumer((cmd, seq) -> {
                })
                .build();
        exchangeCore.startup();
        api = exchangeCore.getApi();
        refreshSymbols();
        restoreOpenOrderBook();
    }

    @PreDestroy
    public void stop() {
        if (exchangeCore != null) {
            exchangeCore.shutdown(5, TimeUnit.SECONDS);
        }
    }

    @Scheduled(fixedDelayString = "${surprising.trading.matching.engine.initial-symbol-refresh-delay-ms:30000}")
    public void refreshSymbols() {
        List<InstrumentSymbol> instruments = symbolRepository.currentTradingSymbols();
        Set<String> refreshedActiveSymbols = new HashSet<>(instruments.size());
        for (InstrumentSymbol instrument : instruments) {
            loadSymbol(instrument);
            refreshedActiveSymbols.add(instrument.symbol());
        }
        activeSymbols = Set.copyOf(refreshedActiveSymbols);
    }

    public Optional<MatchingSymbol> ensureSymbol(String symbol) {
        if (symbol == null || !activeSymbols.contains(symbol)) {
            return Optional.empty();
        }
        return Optional.ofNullable(loadedSymbols.get(symbol));
    }

    private MatchingSymbol loadSymbol(InstrumentSymbol instrument) {
        return loadedSymbols.computeIfAbsent(instrument.symbol(), ignored -> {
            MatchingSymbol matchingSymbol = symbolRepository.ensureMatchingSymbol(instrument);
            if (!instrument.symbol().equals(matchingSymbol.symbol())) {
                throw new IllegalStateException("matching symbol mismatch for " + instrument.symbol()
                        + ": " + matchingSymbol.symbol());
            }
            CoreSymbolSpecification spec = CoreSymbolSpecification.builder()
                    .symbolId(matchingSymbol.symbolId())
                    .type(SymbolType.CURRENCY_EXCHANGE_PAIR)
                    .baseCurrency(matchingSymbol.baseCurrencyId())
                    .quoteCurrency(matchingSymbol.quoteCurrencyId())
                    .baseScaleK(1L)
                    .quoteScaleK(1L)
                    .makerFee(0L)
                    .takerFee(0L)
                    .marginBuy(0L)
                    .marginSell(0L)
                    .build();
            CommandResultCode result = api.submitBinaryDataAsync(new BatchAddSymbolsCommand(spec)).join();
            if (result != CommandResultCode.SUCCESS && result != CommandResultCode.SYMBOL_MGMT_SYMBOL_ALREADY_EXISTS) {
                throw new IllegalStateException("exchange-core failed to add symbol "
                        + matchingSymbol.symbol() + ": " + result);
            }
            log.info("Loaded exchange-core symbol={} symbolId={}", matchingSymbol.symbol(), matchingSymbol.symbolId());
            return matchingSymbol;
        });
    }

    public OrderCommand submit(OrderCommandEvent command, MatchingSymbol symbol, long effectivePrice) {
        ensureUser(command.userId());
        if (command.commandType() == OrderCommandType.CANCEL) {
            ApiCancelOrder cancel = ApiCancelOrder.builder()
                    .orderId(command.orderId())
                    .uid(command.userId())
                    .symbol(symbol.symbolId())
                    .build();
            return api.submitCommandAsyncFullResponse(cancel).join();
        }

        ApiPlaceOrder place = ApiPlaceOrder.builder()
                .orderId(command.orderId())
                .uid(command.userId())
                .symbol(symbol.symbolId())
                .action(ExchangeCoreMapper.action(command.side()))
                .orderType(ExchangeCoreMapper.orderType(command.orderType(), command.timeInForce()))
                .price(effectivePrice)
                .reservePrice(effectivePrice)
                .size(command.quantitySteps())
                .userCookie((int) (command.commandId() & 0x7fffffff))
                .build();
        return api.submitCommandAsyncFullResponse(place).join();
    }

    public CommandResultCode restoreOpenOrder(RecoveredOrderBookOrder order, MatchingSymbol symbol) {
        ensureUser(order.userId());
        ApiPlaceOrder place = ApiPlaceOrder.builder()
                .orderId(order.orderId())
                .uid(order.userId())
                .symbol(symbol.symbolId())
                .action(ExchangeCoreMapper.action(order.side()))
                .orderType(exchange.core2.core.common.OrderType.GTC)
                .price(order.priceTicks())
                .reservePrice(order.priceTicks())
                .size(order.remainingQuantitySteps())
                .build();
        OrderCommand response = api.submitCommandAsyncFullResponse(place).join();
        boolean[] matcherEventProduced = new boolean[] {false};
        response.processMatcherEvents(event -> matcherEventProduced[0] = true);
        if (matcherEventProduced[0]) {
            throw new IllegalStateException("restored open orders crossed the book for orderId=" + order.orderId());
        }
        return response.resultCode;
    }

    public boolean wouldTakeLiquidity(OrderCommandEvent command, MatchingSymbol symbol, long effectivePriceTicks) {
        if (!command.postOnly()) {
            return false;
        }
        L2MarketData book = api.requestOrderBookAsync(
                symbol.symbolId(), properties.getEngine().getOrderBookDepthForPostOnly()).join();
        if (command.side() == com.surprising.trading.api.model.OrderSide.BUY) {
            return book.askSize > 0 && book.askPrices[0] <= effectivePriceTicks;
        }
        return book.bidSize > 0 && book.bidPrices[0] >= effectivePriceTicks;
    }

    public L2MarketData requestOrderBook(MatchingSymbol symbol, int depth) {
        return api.requestOrderBookAsync(symbol.symbolId(), Math.max(1, depth)).join();
    }

    private void ensureUser(long userId) {
        if (!createdUsers.add(userId)) {
            return;
        }
        CommandResultCode result = api.submitCommandAsync(ApiAddUser.builder().uid(userId).build()).join();
        if (result != CommandResultCode.SUCCESS && result != CommandResultCode.USER_MGMT_USER_ALREADY_EXISTS) {
            createdUsers.remove(userId);
            throw new IllegalStateException("exchange-core failed to add user " + userId + ": " + result);
        }
    }

    private void restoreOpenOrderBook() {
        if (!properties.getRecovery().isOpenOrderBookRestoreEnabled()) {
            return;
        }
        int batchSize = Math.max(1, properties.getRecovery().getOpenOrderBatchSize());
        Instant lastCreatedAt = Instant.EPOCH;
        long lastOrderId = 0L;
        long restored = 0L;
        while (true) {
            var batch = recoveryRepository.recoverableOpenOrdersAfter(lastCreatedAt, lastOrderId, batchSize);
            if (batch.isEmpty()) {
                break;
            }
            for (RecoveredOrderBookOrder order : batch) {
                MatchingSymbol symbol = ensureSymbol(order.symbol())
                        .orElseThrow(() -> new IllegalStateException("cannot restore order for disabled symbol " + order.symbol()));
                CommandResultCode result = restoreOpenOrder(order, symbol);
                if (result != CommandResultCode.SUCCESS) {
                    throw new IllegalStateException("exchange-core failed to restore orderId="
                            + order.orderId() + ": " + result);
                }
                restored++;
                lastCreatedAt = order.createdAt();
                lastOrderId = order.orderId();
            }
            if (batch.size() < batchSize) {
                break;
            }
        }
        log.info("Restored exchange-core open order book orders={}", restored);
    }
}
