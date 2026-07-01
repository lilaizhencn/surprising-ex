package com.surprising.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.account.api.model.BalanceResponse;
import com.surprising.account.api.model.PositionResponse;
import com.surprising.account.provider.model.BalanceSettlementState;
import com.surprising.account.provider.model.ContractSpec;
import com.surprising.account.provider.model.PositionState;
import com.surprising.account.provider.repository.AccountRepository;
import com.surprising.account.provider.service.AccountService;
import com.surprising.account.provider.service.MarginTransferMath;
import com.surprising.account.provider.service.PnlSettlementMath;
import com.surprising.account.provider.service.PositionCalculator;
import com.surprising.instrument.api.math.PerpetualContractMath;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.liquidation.api.model.LiquidationOrderResponse;
import com.surprising.liquidation.api.model.LiquidationOrderStatus;
import com.surprising.liquidation.provider.config.LiquidationProperties;
import com.surprising.liquidation.provider.model.ClaimedCandidate;
import com.surprising.liquidation.provider.model.LiquidationCloseState;
import com.surprising.liquidation.provider.model.LiquidationSizingInput;
import com.surprising.liquidation.provider.repository.LiquidationOrderRepository;
import com.surprising.liquidation.provider.repository.LiquidationRepository;
import com.surprising.liquidation.provider.repository.LiquidationSequenceRepository;
import com.surprising.liquidation.provider.service.LiquidationService;
import com.surprising.liquidation.provider.service.LiquidationSizingPolicy;
import com.surprising.risk.api.model.LiquidationCandidateEvent;
import com.surprising.risk.api.model.LiquidationCandidateQueryResponse;
import com.surprising.risk.api.model.LiquidationCandidateResponse;
import com.surprising.risk.api.model.LiquidationCandidateStatus;
import com.surprising.risk.api.model.RiskAccountSnapshotResponse;
import com.surprising.risk.api.model.RiskPositionQueryResponse;
import com.surprising.risk.api.model.RiskPositionSnapshotResponse;
import com.surprising.risk.api.model.RiskStatus;
import com.surprising.risk.provider.config.RiskProperties;
import com.surprising.risk.provider.model.CalculatedPositionRisk;
import com.surprising.risk.provider.model.RiskGroupKey;
import com.surprising.risk.provider.repository.RiskOutboxRepository;
import com.surprising.risk.provider.repository.RiskRepository;
import com.surprising.risk.provider.repository.RiskSequenceRepository;
import com.surprising.risk.provider.service.RiskService;
import com.surprising.trading.api.model.MatchResultEvent;
import com.surprising.trading.api.model.MatchTradeEvent;
import com.surprising.trading.api.model.CancelOrderRequest;
import com.surprising.trading.api.model.OrderCommandEvent;
import com.surprising.trading.api.model.OrderCommandType;
import com.surprising.trading.api.model.OrderEvent;
import com.surprising.trading.api.model.OrderEventType;
import com.surprising.trading.api.model.OrderQueryResponse;
import com.surprising.trading.api.model.OrderResponse;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.OrderStatus;
import com.surprising.trading.api.model.OrderType;
import com.surprising.trading.api.model.PlaceOrderRequest;
import com.surprising.trading.api.model.TimeInForce;
import com.surprising.trading.matching.config.MatchingProperties;
import com.surprising.trading.matching.model.InstrumentSymbol;
import com.surprising.trading.matching.model.MatchingSymbol;
import com.surprising.trading.matching.model.RecoveredOrderBookOrder;
import com.surprising.trading.matching.repository.MatchingOrderBookRecoveryRepository;
import com.surprising.trading.matching.repository.MatchingOutboxRepository;
import com.surprising.trading.matching.repository.MatchingProtectionRepository;
import com.surprising.trading.matching.repository.MatchingResultRepository;
import com.surprising.trading.matching.repository.MatchingSequenceRepository;
import com.surprising.trading.matching.repository.MatchingSymbolRepository;
import com.surprising.trading.matching.service.ExchangeCoreEngine;
import com.surprising.trading.matching.service.MatchingService;
import com.surprising.trading.order.config.TradingOrderProperties;
import com.surprising.trading.order.model.InstrumentRule;
import com.surprising.trading.order.model.MarginRequirement;
import com.surprising.trading.order.model.OrderRecord;
import com.surprising.trading.order.model.OutboxRecord;
import com.surprising.trading.order.model.ReduceOnlyPosition;
import com.surprising.trading.order.repository.OrderMarginRepository;
import com.surprising.trading.order.repository.OrderRepository;
import com.surprising.trading.order.repository.OutboxRepository;
import com.surprising.trading.order.service.OrderMarginMath;
import com.surprising.trading.order.service.OrderService;
import com.surprising.trading.order.service.OrderValidator;
import com.surprising.trading.order.service.ReduceOnlyValidator;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import tools.jackson.databind.ObjectMapper;

class PerpetualTradingChainIntegrationTest {

    private static final String SYMBOL = "BTC-USDT";
    private static final String SETTLE = "USDT";
    private static final long VERSION = 1L;
    private static final long NOTIONAL_MULTIPLIER = 100L;
    private static final long PRICE_TICK_UNITS = 1L;
    private static final long SETTLE_SCALE_UNITS = 1L;
    private static final long INITIAL_MARGIN_RATE_PPM = 10_000L;

    @Test
    void userInitiatedOpenAndReduceOnlyCloseFlowReachesAccountState() {
        try (Harness harness = new Harness()) {
            harness.state.setBalance(1001L, 100_000L);
            harness.state.setBalance(2002L, 100_000L);
            harness.state.setBalance(3003L, 100_000L);

            harness.place(1001L, "maker-sell-100", OrderSide.SELL, OrderType.LIMIT,
                    TimeInForce.GTC, 100L, 6L, false);
            harness.place(2002L, "taker-buy-100", OrderSide.BUY, OrderType.LIMIT,
                    TimeInForce.IOC, 100L, 6L, false);
            harness.processNewOrderCommands();

            assertThat(harness.state.position(2002L)).isEqualTo(new PositionState(6L, VERSION, 100L, 0L));
            assertThat(harness.state.position(1001L)).isEqualTo(new PositionState(-6L, VERSION, 100L, 0L));

            harness.place(3003L, "close-liquidity-bid", OrderSide.BUY, OrderType.LIMIT,
                    TimeInForce.GTC, 110L, 6L, false);
            harness.processNewOrderCommands();
            OrderResponse close = harness.place(2002L, "manual-reduce-only-close", OrderSide.SELL,
                    OrderType.LIMIT, TimeInForce.IOC, 110L, 6L, true);
            harness.processNewOrderCommands();

            assertThat(close.reduceOnly()).isTrue();
            assertThat(harness.state.position(2002L)).isEqualTo(new PositionState(0L, 0L, 0L, 6_000L));
            assertThat(harness.state.balance(2002L).availableUnits).isEqualTo(106_000L);
            assertThat(harness.state.balance(2002L).lockedUnits).isZero();
            assertThat(harness.state.balance(2002L).deficitUnits).isZero();
        }
    }

    @Test
    void inversePerpetualOpenAndReduceOnlyCloseSettlesCoinMarginAndPnl() {
        try (Harness harness = Harness.inversePerpetual()) {
            harness.state.setBalance(1001L, 1_000_000L);
            harness.state.setBalance(2002L, 1_000_000L);
            harness.state.setBalance(3003L, 1_000_000L);

            harness.place(1001L, "inverse-maker-sell-50000", OrderSide.SELL, OrderType.LIMIT,
                    TimeInForce.GTC, 50_000L, 1L, false);
            harness.place(2002L, "inverse-taker-buy-50000", OrderSide.BUY, OrderType.LIMIT,
                    TimeInForce.IOC, 50_000L, 1L, false);
            harness.processNewOrderCommands();

            assertThat(harness.state.position(2002L)).isEqualTo(new PositionState(1L, VERSION, 50_000L, 0L));
            assertThat(harness.state.positionMargin(2002L)).isEqualTo(20_000L);
            assertThat(harness.state.balance(2002L).availableUnits).isEqualTo(980_000L);
            assertThat(harness.state.balance(2002L).lockedUnits).isEqualTo(20_000L);

            harness.place(3003L, "inverse-close-bid-60000", OrderSide.BUY, OrderType.LIMIT,
                    TimeInForce.GTC, 60_000L, 1L, false);
            OrderResponse close = harness.place(2002L, "inverse-reduce-only-close", OrderSide.SELL,
                    OrderType.LIMIT, TimeInForce.IOC, 60_000L, 1L, true);
            harness.processNewOrderCommands();

            assertThat(close.reduceOnly()).isTrue();
            assertThat(harness.state.position(2002L)).isEqualTo(new PositionState(0L, 0L, 0L, 33_333L));
            assertThat(harness.state.positionMargin(2002L)).isZero();
            assertThat(harness.state.balance(2002L).availableUnits).isEqualTo(1_033_333L);
            assertThat(harness.state.balance(2002L).lockedUnits).isZero();
            assertThat(harness.state.balance(2002L).deficitUnits).isZero();
        }
    }

    @Test
    void userCancelOpenOrderRemovesExchangeCoreOrderAndReleasesReservedMargin() {
        try (Harness harness = new Harness()) {
            harness.state.setBalance(1001L, 10_000L);

            OrderResponse placed = harness.place(1001L, "resting-bid-cancel", OrderSide.BUY, OrderType.LIMIT,
                    TimeInForce.GTC, 100L, 5L, false);
            harness.processNewOrderCommands();

            assertThat(placed.status()).isEqualTo(OrderStatus.ACCEPTED);
            assertThat(harness.state.balance(1001L).availableUnits).isEqualTo(9_500L);
            assertThat(harness.state.balance(1001L).lockedUnits).isEqualTo(500L);
            assertThat(harness.state.position(1001L)).isEqualTo(new PositionState(0L, 0L, 0L, 0L));

            OrderResponse cancelRequested = harness.cancel(1001L, placed.orderId());
            harness.processNewOrderCommands();

            assertThat(cancelRequested.status()).isEqualTo(OrderStatus.CANCEL_REQUESTED);
            OrderRecord canceled = harness.orderRepository.findByOrderId(placed.orderId()).orElseThrow();
            assertThat(canceled.status()).isEqualTo(OrderStatus.CANCELED);
            assertThat(canceled.remainingQuantitySteps()).isZero();
            assertThat(harness.orderRepository.openOrders(1001L, SYMBOL, 100)).isEmpty();
            assertThat(harness.state.balance(1001L).availableUnits).isEqualTo(10_000L);
            assertThat(harness.state.balance(1001L).lockedUnits).isZero();
            assertThat(harness.state.position(1001L)).isEqualTo(new PositionState(0L, 0L, 0L, 0L));
        }
    }

    @Test
    void cancelAfterPartialFillReleasesOnlyUnusedOrderMargin() {
        try (Harness harness = new Harness()) {
            harness.state.setBalance(1001L, 10_000L);
            harness.state.setBalance(2002L, 10_000L);

            OrderResponse restingBid = harness.place(1001L, "partial-bid-cancel", OrderSide.BUY, OrderType.LIMIT,
                    TimeInForce.GTC, 100L, 10L, false);
            harness.processNewOrderCommands();
            harness.place(2002L, "partial-sell-taker", OrderSide.SELL, OrderType.LIMIT,
                    TimeInForce.IOC, 100L, 4L, false);
            harness.processNewOrderCommands();

            OrderRecord partiallyFilled = harness.orderRepository.findByOrderId(restingBid.orderId()).orElseThrow();
            assertThat(partiallyFilled.status()).isEqualTo(OrderStatus.PARTIALLY_FILLED);
            assertThat(partiallyFilled.executedQuantitySteps()).isEqualTo(4L);
            assertThat(partiallyFilled.remainingQuantitySteps()).isEqualTo(6L);
            assertThat(harness.state.position(1001L)).isEqualTo(new PositionState(4L, VERSION, 100L, 0L));
            assertThat(harness.state.positionMargin(1001L)).isEqualTo(400L);
            assertThat(harness.state.balance(1001L).availableUnits).isEqualTo(9_000L);
            assertThat(harness.state.balance(1001L).lockedUnits).isEqualTo(1_000L);

            OrderResponse cancelRequested = harness.cancel(1001L, restingBid.orderId());
            harness.processNewOrderCommands();

            assertThat(cancelRequested.status()).isEqualTo(OrderStatus.CANCEL_REQUESTED);
            OrderRecord canceled = harness.orderRepository.findByOrderId(restingBid.orderId()).orElseThrow();
            assertThat(canceled.status()).isEqualTo(OrderStatus.CANCELED);
            assertThat(canceled.executedQuantitySteps()).isEqualTo(4L);
            assertThat(canceled.remainingQuantitySteps()).isZero();
            assertThat(harness.state.position(1001L)).isEqualTo(new PositionState(4L, VERSION, 100L, 0L));
            assertThat(harness.state.positionMargin(1001L)).isEqualTo(400L);
            assertThat(harness.state.balance(1001L).availableUnits).isEqualTo(9_600L);
            assertThat(harness.state.balance(1001L).lockedUnits).isEqualTo(400L);
        }
    }

    @Test
    void marketOrderReleasesProtectionPriceExcessMarginAfterFill() {
        try (Harness harness = new Harness(100_000L)) {
            harness.state.setBalance(1001L, 100_000L);
            harness.state.setBalance(2002L, 100_000L);
            harness.state.markPriceTicks = 100L;

            harness.place(1001L, "market-maker-sell-100", OrderSide.SELL, OrderType.LIMIT,
                    TimeInForce.GTC, 100L, 6L, false);
            OrderResponse taker = harness.place(2002L, "market-buy-protected-110", OrderSide.BUY,
                    OrderType.MARKET, TimeInForce.IOC, 0L, 6L, false);
            harness.processNewOrderCommands();

            OrderRecord takerOrder = harness.orderRepository.findByOrderId(taker.orderId()).orElseThrow();
            assertThat(takerOrder.status()).isEqualTo(OrderStatus.FILLED);
            assertThat(harness.state.position(2002L)).isEqualTo(new PositionState(6L, VERSION, 100L, 0L));
            assertThat(harness.state.positionMargin(2002L)).isEqualTo(600L);
            assertThat(harness.state.reservations.get(taker.orderId()).reservedUnits).isEqualTo(660L);
            assertThat(harness.state.reservations.get(taker.orderId()).releasedUnits).isEqualTo(60L);
            assertThat(harness.state.reservations.get(taker.orderId()).positionMarginUnits).isEqualTo(600L);
            assertThat(harness.state.balance(2002L).availableUnits).isEqualTo(99_400L);
            assertThat(harness.state.balance(2002L).lockedUnits).isEqualTo(600L);
        }
    }

    @Test
    void marketSellOrderReservesUpperBoundMarginBeforeOpeningShort() {
        try (Harness harness = new Harness(100_000L)) {
            harness.state.setBalance(1001L, 100_000L);
            harness.state.setBalance(2002L, 100_000L);
            harness.state.markPriceTicks = 100L;

            harness.place(1001L, "high-bid-liquidity", OrderSide.BUY, OrderType.LIMIT,
                    TimeInForce.GTC, 110L, 6L, false);
            OrderResponse taker = harness.place(2002L, "market-sell-protected-90", OrderSide.SELL,
                    OrderType.MARKET, TimeInForce.IOC, 0L, 6L, false);
            harness.processNewOrderCommands();

            OrderRecord takerOrder = harness.orderRepository.findByOrderId(taker.orderId()).orElseThrow();
            assertThat(takerOrder.status()).isEqualTo(OrderStatus.FILLED);
            assertThat(harness.state.position(2002L)).isEqualTo(new PositionState(-6L, VERSION, 110L, 0L));
            assertThat(harness.state.positionMargin(2002L)).isEqualTo(660L);
            assertThat(harness.state.reservations.get(taker.orderId()).reservedUnits).isEqualTo(660L);
            assertThat(harness.state.reservations.get(taker.orderId()).releasedUnits).isZero();
            assertThat(harness.state.reservations.get(taker.orderId()).positionMarginUnits).isEqualTo(660L);
            assertThat(harness.state.balance(2002L).availableUnits).isEqualTo(99_340L);
            assertThat(harness.state.balance(2002L).lockedUnits).isEqualTo(660L);
        }
    }

    @Test
    void riskLiquidationFlowCreatesReduceOnlyOrderAndSettlesMatchedClose() {
        try (Harness harness = new Harness()) {
            harness.state.setBalance(1101L, 1_000L);
            harness.state.setBalance(2202L, 1_000L);
            harness.state.setBalance(3303L, 1_000L);

            harness.place(1101L, "risk-maker-sell", OrderSide.SELL, OrderType.LIMIT,
                    TimeInForce.GTC, 100L, 6L, false);
            harness.place(2202L, "risk-user-open-long", OrderSide.BUY, OrderType.LIMIT,
                    TimeInForce.IOC, 100L, 6L, false);
            harness.processNewOrderCommands();
            assertThat(harness.state.position(2202L)).isEqualTo(new PositionState(6L, VERSION, 100L, 0L));

            harness.state.markPriceTicks = 50L;
            harness.place(3303L, "liquidation-bid", OrderSide.BUY, OrderType.LIMIT,
                    TimeInForce.GTC, 50L, 6L, false);
            harness.processNewOrderCommands();

            harness.riskService.scan();
            LiquidationCandidateEvent candidate = harness.riskOutbox.lastCandidate();
            assertThat(candidate.userId()).isEqualTo(2202L);
            assertThat(candidate.instrumentVersion()).isEqualTo(VERSION);
            assertThat(candidate.signedQuantitySteps()).isEqualTo(6L);
            assertThat(candidate.markPriceTicks()).isEqualTo(50L);

            harness.liquidationService.processCandidate(candidate);
            OrderCommandEvent liquidationOrder = harness.liquidationOrderRepository.commands.get(0);
            assertThat(liquidationOrder.side()).isEqualTo(OrderSide.SELL);
            assertThat(liquidationOrder.orderType()).isEqualTo(OrderType.MARKET);
            assertThat(liquidationOrder.timeInForce()).isEqualTo(TimeInForce.IOC);
            assertThat(liquidationOrder.reduceOnly()).isTrue();
            assertThat(liquidationOrder.quantitySteps()).isEqualTo(6L);

            harness.processCommand(liquidationOrder);

            assertThat(harness.state.position(2202L)).isEqualTo(new PositionState(0L, 0L, 0L, -30_000L));
            assertThat(harness.state.balance(2202L).availableUnits).isZero();
            assertThat(harness.state.balance(2202L).lockedUnits).isZero();
            assertThat(harness.state.balance(2202L).deficitUnits).isEqualTo(29_000L);
            assertThat(harness.liquidationRepository.status(candidate.candidateId())).isEqualTo("COMPLETED");
            assertThat(harness.liquidationRepository.orders).singleElement()
                    .extracting(LiquidationOrderResponse::status)
                    .isEqualTo(LiquidationOrderStatus.SUBMITTED);
        }
    }

    private static final class Harness implements AutoCloseable {
        private final SharedState state;
        private final ObjectMapper objectMapper = new ObjectMapper();
        private final FakeOrderRepository orderRepository;
        private final FakeOrderOutboxRepository orderOutbox;
        private final FakeMatchingResultRepository matchingResultRepository;
        private final FakeRiskOutboxRepository riskOutbox;
        private final FakeLiquidationRepository liquidationRepository;
        private final FakeLiquidationOrderRepository liquidationOrderRepository;
        private final ExchangeCoreEngine engine;
        private final OrderService orderService;
        private final MatchingService matchingService;
        private final AccountService accountService;
        private final RiskService riskService;
        private final LiquidationService liquidationService;
        private int processedOrderOutbox;

        private Harness() {
            this(0L);
        }

        private Harness(long marketMaxSlippagePpm) {
            this(marketMaxSlippagePpm, new SharedState(ContractType.LINEAR_PERPETUAL, SETTLE, NOTIONAL_MULTIPLIER,
                    PRICE_TICK_UNITS, SETTLE_SCALE_UNITS, INITIAL_MARGIN_RATE_PPM));
        }

        private static Harness inversePerpetual() {
            return new Harness(0L, new SharedState(ContractType.INVERSE_PERPETUAL, "BTC",
                    10_000_000_000L, 100_000_000L, 100_000_000L, 100_000L));
        }

        private Harness(long marketMaxSlippagePpm, SharedState state) {
            this.state = state;
            orderRepository = new FakeOrderRepository(state);
            orderOutbox = new FakeOrderOutboxRepository(state);
            matchingResultRepository = new FakeMatchingResultRepository(state);
            riskOutbox = new FakeRiskOutboxRepository(objectMapper);
            liquidationRepository = new FakeLiquidationRepository(state);
            liquidationOrderRepository = new FakeLiquidationOrderRepository(state);

            InstrumentRule rule = new InstrumentRule(SYMBOL, VERSION, "TRADING", state.contractType,
                    Set.of(OrderType.LIMIT.name(), OrderType.MARKET.name()),
                    Set.of(TimeInForce.GTC.name(), TimeInForce.IOC.name(), TimeInForce.FOK.name(),
                            TimeInForce.GTX.name()),
                    true, true, true, 1L, 1_000_000L, 1L, Long.MAX_VALUE / 4, state.notionalMultiplierUnits);
            TradingOrderProperties orderProperties = new TradingOrderProperties();
            orderProperties.getRisk().setMarketMaxSlippagePpm(marketMaxSlippagePpm);
            OrderValidator orderValidator = new OrderValidator(symbol -> SYMBOL.equals(symbol)
                    ? Optional.of(rule)
                    : Optional.empty(), orderProperties,
                    (symbol, instrumentVersion, maxAgeMs) -> OptionalLong.of(state.markPriceTicks));
            ReduceOnlyValidator reduceOnlyValidator = new ReduceOnlyValidator(new FakeReduceOnlyLookup(state));
            FakeOrderMarginRepository marginRepository = new FakeOrderMarginRepository(state);
            orderService = new OrderService(objectMapper, orderProperties, orderValidator,
                    reduceOnlyValidator, orderRepository, marginRepository, orderOutbox);

            MatchingProperties matchingProperties = new MatchingProperties();
            matchingProperties.getEngine().setExchangeId("integration-" + System.nanoTime());
            matchingProperties.getRecovery().setOpenOrderBookRestoreEnabled(false);
            matchingProperties.getProtection().setSelfTradePreventionEnabled(false);
            matchingProperties.getProtection().setMarketMaxSlippagePpm(marketMaxSlippagePpm);
            engine = new ExchangeCoreEngine(matchingProperties, new FakeMatchingSymbolRepository(state),
                    new FakeRecoveryRepository());
            matchingService = new MatchingService(objectMapper, matchingProperties, engine,
                    new FakeMatchingProtectionRepository(state), new FakeMatchingSequenceRepository(state),
                    matchingResultRepository, new FakeMatchingOutboxRepository(state));
            engine.start();

            accountService = new AccountService(new FakeAccountRepository(state), new PositionCalculator());
            riskService = new RiskService(objectMapper, new RiskProperties(), new FakeRiskRepository(state),
                    new FakeRiskSequenceRepository(state), riskOutbox, transactionManager());
            liquidationService = new LiquidationService(objectMapper, new LiquidationProperties(),
                    liquidationRepository, liquidationOrderRepository, new FakeLiquidationSequenceRepository(state),
                    new LiquidationSizingPolicy());
        }

        private OrderResponse place(long userId,
                                    String clientOrderId,
                                    OrderSide side,
                                    OrderType orderType,
                                    TimeInForce timeInForce,
                                    long priceTicks,
                                    long quantitySteps,
                                    boolean reduceOnly) {
            return orderService.place(new PlaceOrderRequest(userId, clientOrderId, SYMBOL, side, orderType,
                    timeInForce, priceTicks, quantitySteps, reduceOnly, false));
        }

        private OrderResponse cancel(long userId, long orderId) {
            return orderService.cancel(new CancelOrderRequest(userId, orderId));
        }

        private void processNewOrderCommands() {
            List<OutboxEnvelope> pending = orderOutbox.records.subList(processedOrderOutbox,
                    orderOutbox.records.size());
            processedOrderOutbox = orderOutbox.records.size();
            for (OutboxEnvelope record : pending) {
                if (record.payloadType() == OrderCommandEvent.class) {
                    processCommand(objectMapper.readValue(record.payload(), OrderCommandEvent.class));
                }
            }
        }

        private void processCommand(OrderCommandEvent command) {
            int tradeOffset = matchingResultRepository.trades.size();
            matchingService.process(command);
            for (MatchTradeEvent trade : matchingResultRepository.trades.subList(tradeOffset,
                    matchingResultRepository.trades.size())) {
                accountService.processTrade(trade);
            }
        }

        @Override
        public void close() {
            engine.stop();
        }
    }

    private static final class SharedState {
        private final ContractType contractType;
        private final String settleAsset;
        private final long notionalMultiplierUnits;
        private final long priceTickUnits;
        private final long settleScaleUnits;
        private final long initialMarginRatePpm;
        private final long maintenanceMarginRatePpm;
        private final Map<String, Long> sequences = new HashMap<>();
        private final Map<Long, OrderRecord> orders = new LinkedHashMap<>();
        private final Map<UserAssetKey, BalanceState> balances = new HashMap<>();
        private final Map<PositionKey, PositionState> positions = new HashMap<>();
        private final Map<PositionKey, Long> positionMargins = new HashMap<>();
        private final Map<Long, MarginReservationState> reservations = new HashMap<>();
        private final Set<Long> processedTrades = new HashSet<>();
        private long markPriceTicks = 100L;

        private SharedState(ContractType contractType,
                            String settleAsset,
                            long notionalMultiplierUnits,
                            long priceTickUnits,
                            long settleScaleUnits,
                            long initialMarginRatePpm) {
            this.contractType = contractType;
            this.settleAsset = settleAsset;
            this.notionalMultiplierUnits = notionalMultiplierUnits;
            this.priceTickUnits = priceTickUnits;
            this.settleScaleUnits = settleScaleUnits;
            this.initialMarginRatePpm = initialMarginRatePpm;
            this.maintenanceMarginRatePpm = 10_000L;
        }

        private long next(String sequenceName) {
            long value = sequences.getOrDefault(sequenceName, 0L) + 1L;
            sequences.put(sequenceName, value);
            return value;
        }

        private void setBalance(long userId, long availableUnits) {
            balances.put(new UserAssetKey(userId, settleAsset), new BalanceState(availableUnits, 0L, 0L));
        }

        private BalanceState balance(long userId) {
            return balances.computeIfAbsent(new UserAssetKey(userId, settleAsset),
                    ignored -> new BalanceState(0L, 0L, 0L));
        }

        private PositionState position(long userId) {
            return positions.getOrDefault(new PositionKey(userId, SYMBOL), new PositionState(0L, 0L, 0L, 0L));
        }

        private void putPosition(long userId, PositionState state) {
            positions.put(new PositionKey(userId, SYMBOL), state);
        }

        private long positionMargin(long userId) {
            return positionMargins.getOrDefault(new PositionKey(userId, SYMBOL), 0L);
        }
    }

    private static final class FakeOrderRepository extends OrderRepository {
        private final SharedState state;

        private FakeOrderRepository(SharedState state) {
            super(null);
            this.state = state;
        }

        @Override
        public long nextSequence(String sequenceName) {
            return state.next("trading-" + sequenceName);
        }

        @Override
        public boolean insert(OrderRecord order) {
            if (state.orders.containsKey(order.orderId())) {
                return false;
            }
            if (order.clientOrderId() != null && findByClientOrderId(order.userId(), order.clientOrderId()).isPresent()) {
                return false;
            }
            state.orders.put(order.orderId(), order);
            return true;
        }

        @Override
        public void insertEvent(OrderEvent event) {
        }

        @Override
        public Optional<OrderRecord> findByOrderId(long orderId) {
            return Optional.ofNullable(state.orders.get(orderId));
        }

        @Override
        public Optional<OrderRecord> findByClientOrderId(long userId, String clientOrderId) {
            return state.orders.values().stream()
                    .filter(order -> order.userId() == userId && clientOrderId.equals(order.clientOrderId()))
                    .findFirst();
        }

        @Override
        public boolean requestCancel(long orderId, Instant now) {
            OrderRecord order = state.orders.get(orderId);
            if (order == null
                    || (order.status() != OrderStatus.ACCEPTED && order.status() != OrderStatus.PARTIALLY_FILLED)) {
                return false;
            }
            state.orders.put(orderId, new OrderRecord(order.orderId(), order.userId(), order.clientOrderId(),
                    order.symbol(), order.instrumentVersion(), order.side(), order.orderType(), order.timeInForce(),
                    order.priceTicks(), order.quantitySteps(), order.executedQuantitySteps(),
                    order.remainingQuantitySteps(), order.reduceOnly(), order.postOnly(),
                    OrderStatus.CANCEL_REQUESTED, order.rejectReason(), order.createdAt(), now));
            return true;
        }

        @Override
        public List<OrderRecord> openOrders(long userId, String symbol, int limit) {
            return state.orders.values().stream()
                    .filter(order -> order.userId() == userId)
                    .filter(order -> symbol == null || symbol.equals(order.symbol()))
                    .filter(order -> order.status() == OrderStatus.ACCEPTED
                            || order.status() == OrderStatus.PARTIALLY_FILLED
                            || order.status() == OrderStatus.CANCEL_REQUESTED)
                    .sorted(Comparator.comparing(OrderRecord::createdAt).reversed())
                    .limit(limit)
                    .toList();
        }
    }

    private static final class FakeOrderMarginRepository extends OrderMarginRepository {
        private final SharedState state;
        private long lastQuantitySteps;

        private FakeOrderMarginRepository(SharedState state) {
            super(null, null);
            this.state = state;
        }

        @Override
        public Optional<MarginRequirement> requirement(String symbol,
                                                       long instrumentVersion,
                                                       OrderSide side,
                                                       OrderType orderType,
                                                       long priceTicks,
                                                       long quantitySteps,
                                                       long marketMaxSlippagePpm,
                                                       long marketMaxMarkAgeMs) {
            assertThat(symbol).isEqualTo(SYMBOL);
            assertThat(instrumentVersion).isEqualTo(VERSION);
            lastQuantitySteps = quantitySteps;
            long marginUnits = OrderMarginMath.initialMarginUnits(state.contractType, side, orderType,
                    priceTicks, quantitySteps, state.markPriceTicks, marketMaxSlippagePpm, state.notionalMultiplierUnits,
                    state.priceTickUnits, state.settleScaleUnits, state.initialMarginRatePpm);
            return Optional.of(new MarginRequirement(state.settleAsset, marginUnits));
        }

        @Override
        public boolean reserve(long userId,
                               String asset,
                               long orderId,
                               String symbol,
                               long amountUnits,
                               Instant now) {
            BalanceState balance = state.balance(userId);
            if (balance.availableUnits < amountUnits) {
                return false;
            }
            balance.availableUnits -= amountUnits;
            balance.lockedUnits += amountUnits;
            state.reservations.put(orderId, new MarginReservationState(userId, asset, orderId, symbol,
                    amountUnits, 0L, 0L, lastQuantitySteps, false));
            return true;
        }
    }

    private static final class FakeOrderOutboxRepository extends OutboxRepository {
        private final SharedState state;
        private final List<OutboxEnvelope> records = new ArrayList<>();

        private FakeOrderOutboxRepository(SharedState state) {
            super(null, null);
            this.state = state;
        }

        @Override
        public long enqueue(String aggregateType,
                            long aggregateId,
                            String topic,
                            String eventKey,
                            String eventType,
                            String payload,
                            Instant now) {
            long id = state.next("trading-outbox");
            Class<?> payloadType = switch (aggregateType) {
                case "ORDER" -> "PLACE".equals(eventType) || "CANCEL".equals(eventType)
                        ? OrderCommandEvent.class
                        : OrderEvent.class;
                default -> String.class;
            };
            records.add(new OutboxEnvelope(id, topic, eventKey, eventType, payload, payloadType));
            return id;
        }

        @Override
        public List<OutboxRecord> lockPending(int limit) {
            return List.of();
        }
    }

    private static final class FakeReduceOnlyLookup implements com.surprising.trading.order.model.ReduceOnlyPositionLookup {
        private final SharedState state;

        private FakeReduceOnlyLookup(SharedState state) {
            this.state = state;
        }

        @Override
        public Optional<ReduceOnlyPosition> lockedPosition(long userId, String symbol) {
            PositionState position = state.position(userId);
            return position.signedQuantitySteps() == 0
                    ? Optional.empty()
                    : Optional.of(new ReduceOnlyPosition(position.signedQuantitySteps(), position.instrumentVersion()));
        }

        @Override
        public long lockedOpenReduceOnlySteps(long userId,
                                              String symbol,
                                              long instrumentVersion,
                                              OrderSide closeSide) {
            return state.orders.values().stream()
                    .filter(order -> order.userId() == userId)
                    .filter(order -> order.symbol().equals(symbol))
                    .filter(order -> order.instrumentVersion() == instrumentVersion)
                    .filter(order -> order.reduceOnly() && order.side() == closeSide)
                    .filter(order -> order.status() == OrderStatus.ACCEPTED
                            || order.status() == OrderStatus.PARTIALLY_FILLED
                            || order.status() == OrderStatus.CANCEL_REQUESTED)
                    .mapToLong(OrderRecord::remainingQuantitySteps)
                    .reduce(0L, Math::addExact);
        }
    }

    private static final class FakeMatchingSymbolRepository extends MatchingSymbolRepository {
        private final InstrumentSymbol instrument;
        private final MatchingSymbol matchingSymbol = new MatchingSymbol(SYMBOL, 501, 11, 12);

        private FakeMatchingSymbolRepository(SharedState state) {
            super(null, null);
            this.instrument = new InstrumentSymbol(SYMBOL, "BTC", "USDT", state.settleAsset);
        }

        @Override
        public List<InstrumentSymbol> currentTradingSymbols() {
            return List.of(instrument);
        }

        @Override
        public Optional<InstrumentSymbol> currentTradingSymbol(String symbol) {
            return SYMBOL.equals(symbol) ? Optional.of(instrument) : Optional.empty();
        }

        @Override
        public MatchingSymbol ensureMatchingSymbol(InstrumentSymbol instrument) {
            return matchingSymbol;
        }
    }

    private static final class FakeRecoveryRepository extends MatchingOrderBookRecoveryRepository {
        private FakeRecoveryRepository() {
            super(null);
        }

        @Override
        public List<RecoveredOrderBookOrder> recoverableOpenOrdersAfter(Instant lastCreatedAt,
                                                                        long lastOrderId,
                                                                        int limit) {
            return List.of();
        }
    }

    private static final class FakeMatchingProtectionRepository extends MatchingProtectionRepository {
        private final SharedState state;

        private FakeMatchingProtectionRepository(SharedState state) {
            super(null);
            this.state = state;
        }

        @Override
        public OptionalLong latestMarkPriceTicks(String symbol, long instrumentVersion, Duration maxAge) {
            return OptionalLong.of(state.markPriceTicks);
        }

        @Override
        public boolean wouldSelfTrade(long userId,
                                      String symbol,
                                      long instrumentVersion,
                                      OrderSide side,
                                      long effectivePriceTicks) {
            return false;
        }

        @Override
        public boolean hasOpenOrdersWithDifferentInstrumentVersion(String symbol,
                                                                   long instrumentVersion,
                                                                   long orderId) {
            return false;
        }
    }

    private static final class FakeMatchingSequenceRepository extends MatchingSequenceRepository {
        private final SharedState state;

        private FakeMatchingSequenceRepository(SharedState state) {
            super(null);
            this.state = state;
        }

        @Override
        public long nextSequence(String sequenceName) {
            return state.next("matching-" + sequenceName);
        }
    }

    private static final class FakeMatchingResultRepository extends MatchingResultRepository {
        private final SharedState state;
        private final List<MatchResultEvent> results = new ArrayList<>();
        private final List<MatchTradeEvent> trades = new ArrayList<>();

        private FakeMatchingResultRepository(SharedState state) {
            super(null, null);
            this.state = state;
        }

        @Override
        public boolean commandResultExists(long commandId) {
            return results.stream().anyMatch(result -> result.commandId() == commandId);
        }

        @Override
        public long orderInstrumentVersion(long orderId) {
            return Optional.ofNullable(state.orders.get(orderId))
                    .map(OrderRecord::instrumentVersion)
                    .orElseThrow(() -> new IllegalStateException("missing order " + orderId));
        }

        @Override
        public boolean saveResult(MatchResultEvent event) {
            results.add(event);
            return true;
        }

        @Override
        public boolean saveTrade(MatchTradeEvent trade) {
            trades.add(trade);
            return true;
        }

        @Override
        public void applyActiveOrderStatus(MatchResultEvent result) {
            OrderRecord order = state.orders.get(result.orderId());
            if (order == null) {
                return;
            }
            long executed = Math.addExact(order.executedQuantitySteps(), result.filledQuantitySteps());
            long remaining = result.orderStatus() == OrderStatus.FILLED
                    || result.orderStatus() == OrderStatus.CANCELED
                    || result.orderStatus() == OrderStatus.REJECTED
                    ? 0L
                    : Math.max(0L, order.quantitySteps() - executed);
            state.orders.put(order.orderId(), new OrderRecord(order.orderId(), order.userId(),
                    order.clientOrderId(), order.symbol(), order.instrumentVersion(), order.side(),
                    order.orderType(), order.timeInForce(), order.priceTicks(), order.quantitySteps(), executed,
                    remaining, order.reduceOnly(), order.postOnly(), result.orderStatus(), order.rejectReason(),
                    order.createdAt(), result.eventTime()));
            if (result.commandType() == OrderCommandType.CANCEL && result.orderStatus() == OrderStatus.CANCELED) {
                releaseUnusedOrderMargin(order.orderId());
            }
        }

        @Override
        public void applyMakerFill(MatchTradeEvent trade) {
            OrderRecord order = state.orders.get(trade.makerOrderId());
            long executed = order.executedQuantitySteps() + trade.quantitySteps();
            long remaining = Math.max(0L, order.remainingQuantitySteps() - trade.quantitySteps());
            OrderStatus status = trade.makerOrderCompleted() ? OrderStatus.FILLED : OrderStatus.PARTIALLY_FILLED;
            state.orders.put(order.orderId(), new OrderRecord(order.orderId(), order.userId(),
                    order.clientOrderId(), order.symbol(), order.instrumentVersion(), order.side(),
                    order.orderType(), order.timeInForce(), order.priceTicks(), order.quantitySteps(), executed,
                    remaining, order.reduceOnly(), order.postOnly(), status, order.rejectReason(),
                    order.createdAt(), trade.eventTime()));
        }

        private void releaseUnusedOrderMargin(long orderId) {
            MarginReservationState reservation = state.reservations.get(orderId);
            if (reservation == null) {
                return;
            }
            long amount = reservation.reservedUnits - reservation.releasedUnits - reservation.positionMarginUnits;
            if (amount <= 0) {
                return;
            }
            reservation.releasedUnits += amount;
            BalanceState balance = state.balance(reservation.userId);
            assertThat(balance.lockedUnits).isGreaterThanOrEqualTo(amount);
            balance.lockedUnits -= amount;
            balance.availableUnits += amount;
        }
    }

    private static final class FakeMatchingOutboxRepository extends MatchingOutboxRepository {
        private FakeMatchingOutboxRepository(SharedState state) {
            super(null, null);
        }

        @Override
        public void enqueue(String aggregateType,
                            long aggregateId,
                            String topic,
                            String eventKey,
                            String eventType,
                            String payload,
                            Instant now) {
        }
    }

    private static final class FakeAccountRepository extends AccountRepository {
        private final SharedState state;

        private FakeAccountRepository(SharedState state) {
            super(null, null);
            this.state = state;
        }

        @Override
        public ContractSpec contractSpec(String symbol, long instrumentVersion) {
            return new ContractSpec(VERSION, state.contractType, state.settleAsset,
                    state.notionalMultiplierUnits, state.priceTickUnits, state.settleScaleUnits,
                    state.initialMarginRatePpm, 0L, 0L);
        }

        @Override
        public boolean markTradeProcessing(long tradeId, String symbol) {
            return state.processedTrades.add(tradeId);
        }

        @Override
        public PositionState lockPosition(long userId, String symbol) {
            return state.position(userId);
        }

        @Override
        public void consumeOrderMargin(long orderId,
                                       long userId,
                                       String symbol,
                                       long openSteps,
                                       long actualMarginUnits,
                                       boolean sweepRemainder,
                                       Instant now) {
            MarginReservationState reservation = state.reservations.get(orderId);
            if (reservation == null || reservation.reduceOnly) {
                return;
            }
            long allocated = MarginTransferMath.orderMarginConsumeAmount(reservation.reservedUnits,
                    reservation.releasedUnits, reservation.positionMarginUnits, reservation.orderQuantitySteps,
                    openSteps, sweepRemainder);
            long excess = MarginTransferMath.excessOrderMarginUnits(allocated, actualMarginUnits);
            reservation.positionMarginUnits += actualMarginUnits;
            state.positionMargins.merge(new PositionKey(userId, symbol), actualMarginUnits, Long::sum);
            if (excess > 0) {
                reservation.releasedUnits += excess;
                releaseBalanceLock(userId, excess);
            }
        }

        @Override
        public void releaseOrderMargin(long orderId,
                                       long userId,
                                       String symbol,
                                       long closeSteps,
                                       boolean sweepRemainder,
                                       Instant now) {
            MarginReservationState reservation = state.reservations.get(orderId);
            if (reservation == null) {
                return;
            }
            long amount = MarginTransferMath.orderMarginReleaseAmount(reservation.reservedUnits,
                    reservation.releasedUnits, reservation.positionMarginUnits, reservation.orderQuantitySteps,
                    closeSteps, sweepRemainder);
            reservation.releasedUnits += amount;
            releaseBalanceLock(userId, amount);
        }

        @Override
        public void releasePositionMargin(long userId,
                                          String symbol,
                                          long closeSteps,
                                          long positionAbsSteps,
                                          Instant now) {
            PositionKey key = new PositionKey(userId, symbol);
            long currentMargin = state.positionMargins.getOrDefault(key, 0L);
            long amount = MarginTransferMath.positionMarginReleaseAmount(currentMargin, closeSteps, positionAbsSteps);
            if (amount <= 0) {
                return;
            }
            state.positionMargins.put(key, currentMargin - amount);
            releaseBalanceLock(userId, amount);
        }

        @Override
        public void settleRealizedPnl(long userId,
                                      String asset,
                                      long orderId,
                                      long tradeId,
                                      long realizedPnlDeltaUnits,
                                      Instant now) {
            BalanceState current = state.balance(userId);
            BalanceSettlementState next = PnlSettlementMath.apply(current.availableUnits, current.lockedUnits,
                    current.deficitUnits, realizedPnlDeltaUnits);
            current.availableUnits = next.availableUnits();
            current.lockedUnits = next.lockedUnits();
            current.deficitUnits = next.deficitUnits();
        }

        @Override
        public void settleTradeFee(long userId,
                                   String asset,
                                   long orderId,
                                   long tradeId,
                                   long feeDeltaUnits,
                                   String reason,
                                   Instant now) {
            BalanceState current = state.balance(userId);
            BalanceSettlementState next = PnlSettlementMath.apply(current.availableUnits, current.lockedUnits,
                    current.deficitUnits, feeDeltaUnits);
            current.availableUnits = next.availableUnits();
            current.lockedUnits = next.lockedUnits();
            current.deficitUnits = next.deficitUnits();
        }

        @Override
        public PositionResponse updatePosition(long userId, String symbol, PositionState next, Instant now) {
            state.putPosition(userId, next);
            return new PositionResponse(userId, symbol, next.instrumentVersion(), next.signedQuantitySteps(),
                    next.entryPriceTicks(), next.realizedPnlUnits(), now);
        }

        @Override
        public Optional<BalanceResponse> balance(long userId, String asset) {
            BalanceState balance = state.balance(userId);
            return Optional.of(new BalanceResponse(userId, asset, balance.availableUnits, balance.lockedUnits,
                    balance.availableUnits + balance.lockedUnits - balance.deficitUnits, Instant.now()));
        }

        private void releaseBalanceLock(long userId, long amount) {
            BalanceState balance = state.balance(userId);
            assertThat(balance.lockedUnits).isGreaterThanOrEqualTo(amount);
            balance.lockedUnits -= amount;
            balance.availableUnits += amount;
        }
    }

    private static final class FakeRiskRepository extends RiskRepository {
        private final SharedState state;
        private final Map<UserAssetKey, RiskAccountSnapshotResponse> accountSnapshots = new HashMap<>();
        private final Map<Long, LiquidationCandidateResponse> candidates = new LinkedHashMap<>();

        private FakeRiskRepository(SharedState state) {
            super(null);
            this.state = state;
        }

        @Override
        public List<CalculatedPositionRisk> calculatePositions(Duration maxMarkAge) {
            return state.positions.entrySet().stream()
                    .filter(entry -> entry.getValue().signedQuantitySteps() != 0)
                    .map(entry -> {
                        PositionState position = entry.getValue();
                        long signed = position.signedQuantitySteps();
                        long notional = PerpetualContractMath.notionalUnits(state.contractType, signed,
                                state.markPriceTicks, state.notionalMultiplierUnits, state.priceTickUnits,
                                state.settleScaleUnits);
                        long pnl = PerpetualContractMath.unrealizedPnlUnits(state.contractType, signed,
                                position.entryPriceTicks(), state.markPriceTicks, state.notionalMultiplierUnits,
                                state.priceTickUnits, state.settleScaleUnits);
                        long maintenance = Math.max(1L, PerpetualContractMath.maintenanceMarginUnits(
                                state.contractType, signed, state.markPriceTicks, state.notionalMultiplierUnits,
                                state.priceTickUnits, state.settleScaleUnits, state.maintenanceMarginRatePpm));
                        return new CalculatedPositionRisk(entry.getKey().userId(), SYMBOL, position.instrumentVersion(),
                                state.settleAsset, signed, position.entryPriceTicks(), state.markPriceTicks,
                                notional, pnl, maintenance);
                    })
                    .toList();
        }

        @Override
        public boolean acquireScanLease(RiskGroupKey key, String ownerId, Duration leaseDuration) {
            return true;
        }

        @Override
        public long walletBalanceUnits(long userId, String settleAsset) {
            BalanceState balance = state.balance(userId);
            return balance.availableUnits + balance.lockedUnits - balance.deficitUnits;
        }

        @Override
        public void saveAccountSnapshot(RiskAccountSnapshotResponse snapshot) {
            accountSnapshots.put(new UserAssetKey(snapshot.userId(), snapshot.settleAsset()), snapshot);
        }

        @Override
        public void savePositionSnapshot(long snapshotId,
                                         CalculatedPositionRisk position,
                                         long marginRatioPpm,
                                         RiskStatus status,
                                         Instant now) {
        }

        @Override
        public long createLiquidationCandidate(RiskAccountSnapshotResponse account,
                                               CalculatedPositionRisk position,
                                               RiskStatus positionStatus,
                                               long positionMarginRatioPpm,
                                               long candidateId,
                                               Instant now) {
            boolean exists = candidates.values().stream()
                    .anyMatch(candidate -> candidate.userId() == position.userId()
                            && candidate.symbol().equals(position.symbol())
                            && (candidate.status() == LiquidationCandidateStatus.NEW
                            || candidate.status() == LiquidationCandidateStatus.PROCESSING));
            if (exists) {
                return 0L;
            }
            LiquidationCandidateResponse candidate = new LiquidationCandidateResponse(candidateId,
                    account.snapshotId(), position.userId(), position.symbol(), position.instrumentVersion(),
                    position.settleAsset(), position.signedQuantitySteps(), position.markPriceTicks(),
                    account.equityUnits(), position.maintenanceMarginUnits(),
                    Math.max(account.marginRatioPpm(), positionMarginRatioPpm),
                    LiquidationCandidateStatus.NEW, now);
            candidates.put(candidateId, candidate);
            return candidateId;
        }

        @Override
        public Optional<LiquidationCandidateResponse> liquidationCandidate(long candidateId) {
            return Optional.ofNullable(candidates.get(candidateId));
        }

        @Override
        public Optional<RiskAccountSnapshotResponse> latestAccount(long userId, String settleAsset) {
            return Optional.ofNullable(accountSnapshots.get(new UserAssetKey(userId, settleAsset)));
        }

        @Override
        public List<RiskPositionSnapshotResponse> latestPositions(long userId) {
            return List.of();
        }

        @Override
        public List<LiquidationCandidateResponse> liquidationCandidates(LiquidationCandidateStatus status, int limit) {
            return candidates.values().stream()
                    .filter(candidate -> candidate.status() == status)
                    .limit(limit)
                    .toList();
        }
    }

    private static final class FakeRiskSequenceRepository extends RiskSequenceRepository {
        private final SharedState state;

        private FakeRiskSequenceRepository(SharedState state) {
            super(null);
            this.state = state;
        }

        @Override
        public long nextSequence(String sequenceName) {
            return state.next("risk-" + sequenceName);
        }
    }

    private static final class FakeRiskOutboxRepository extends RiskOutboxRepository {
        private final ObjectMapper objectMapper;
        private final List<String> payloads = new ArrayList<>();

        private FakeRiskOutboxRepository(ObjectMapper objectMapper) {
            super(null, null);
            this.objectMapper = objectMapper;
        }

        @Override
        public void enqueue(String topic, String eventKey, String eventType, String payload, Instant now) {
            payloads.add(payload);
        }

        private LiquidationCandidateEvent lastCandidate() {
            assertThat(payloads).isNotEmpty();
            return objectMapper.readValue(payloads.get(payloads.size() - 1), LiquidationCandidateEvent.class);
        }
    }

    private static final class FakeLiquidationRepository extends LiquidationRepository {
        private final SharedState state;
        private final Map<Long, String> statuses = new HashMap<>();
        private final List<LiquidationOrderResponse> orders = new ArrayList<>();

        private FakeLiquidationRepository(SharedState state) {
            super(null);
            this.state = state;
        }

        @Override
        public Optional<ClaimedCandidate> claimCandidate(long candidateId) {
            statuses.put(candidateId, "PROCESSING");
            PositionState position = state.position(2202L);
            return Optional.of(new ClaimedCandidate(candidateId, 1L, 2202L, SYMBOL, VERSION, state.settleAsset,
                    position.signedQuantitySteps(), state.markPriceTicks, Long.MAX_VALUE));
        }

        @Override
        public RiskStatus latestRiskStatus(long userId, String settleAsset, java.time.Duration maxSnapshotAge) {
            return RiskStatus.LIQUIDATION;
        }

        @Override
        public Optional<LiquidationCloseState> lockCloseState(long userId, String symbol, long instrumentVersion) {
            return Optional.of(new LiquidationCloseState(state.position(userId).signedQuantitySteps()));
        }

        @Override
        public long lockOpenReduceOnlySteps(long userId, String symbol, long instrumentVersion, OrderSide closeSide) {
            return 0L;
        }

        @Override
        public Optional<LiquidationSizingInput> sizingInput(long userId,
                                                           String symbol,
                                                           long instrumentVersion,
                                                           long availableCloseSteps) {
            PositionState position = state.position(userId);
            long notionalPerStep = PerpetualContractMath.notionalPerStepUnits(state.contractType,
                    state.markPriceTicks, state.notionalMultiplierUnits, state.priceTickUnits,
                    state.settleScaleUnits);
            long notional = PerpetualContractMath.notionalUnits(state.contractType,
                    position.signedQuantitySteps(), state.markPriceTicks, state.notionalMultiplierUnits,
                    state.priceTickUnits, state.settleScaleUnits);
            return Optional.of(new LiquidationSizingInput(Math.absExact(position.signedQuantitySteps()),
                    availableCloseSteps, notional, notionalPerStep, 0L));
        }

        @Override
        public void markCandidate(long candidateId, String status) {
            statuses.put(candidateId, status);
        }

        @Override
        public boolean insertLiquidationOrder(long liquidationOrderId,
                                              long candidateId,
                                              long orderId,
                                              long userId,
                                              String symbol,
                                              OrderSide side,
                                              long quantitySteps,
                                              LiquidationOrderStatus status,
                                              String reason,
                                              Instant now) {
            orders.add(new LiquidationOrderResponse(liquidationOrderId, candidateId, orderId, userId, symbol,
                    side, quantitySteps, status, reason, now));
            return true;
        }

        private String status(long candidateId) {
            return statuses.get(candidateId);
        }
    }

    private static final class FakeLiquidationOrderRepository extends LiquidationOrderRepository {
        private final SharedState state;
        private final List<OrderCommandEvent> commands = new ArrayList<>();

        private FakeLiquidationOrderRepository(SharedState state) {
            super(null, null, new LiquidationProperties());
            this.state = state;
        }

        @Override
        public int cancelOpenReduceOnlyCloseOrders(long userId,
                                                   String symbol,
                                                   long instrumentVersion,
                                                   OrderSide closeSide,
                                                   Instant now,
                                                   Function<Object, String> serializer) {
            List<OrderRecord> openReduceOnlyOrders = state.orders.values().stream()
                    .filter(order -> order.userId() == userId)
                    .filter(order -> symbol.equals(order.symbol()))
                    .filter(order -> order.instrumentVersion() == instrumentVersion)
                    .filter(order -> order.side() == closeSide)
                    .filter(OrderRecord::reduceOnly)
                    .filter(order -> order.remainingQuantitySteps() > 0)
                    .filter(order -> order.status() == OrderStatus.ACCEPTED
                            || order.status() == OrderStatus.PARTIALLY_FILLED
                            || order.status() == OrderStatus.CANCEL_REQUESTED)
                    .sorted(Comparator.comparing(OrderRecord::createdAt).thenComparing(OrderRecord::orderId))
                    .toList();
            for (OrderRecord order : openReduceOnlyOrders) {
                if (order.status() != OrderStatus.CANCEL_REQUESTED) {
                    state.orders.put(order.orderId(), new OrderRecord(order.orderId(), order.userId(),
                            order.clientOrderId(), order.symbol(), order.instrumentVersion(), order.side(),
                            order.orderType(), order.timeInForce(), order.priceTicks(), order.quantitySteps(),
                            order.executedQuantitySteps(), order.remainingQuantitySteps(), order.reduceOnly(),
                            order.postOnly(), OrderStatus.CANCEL_REQUESTED, "LIQUIDATION_PREEMPTED_REDUCE_ONLY",
                            order.createdAt(), now));
                }
                commands.add(new OrderCommandEvent(OrderCommandType.CANCEL, state.next("trading-command"),
                        order.orderId(), order.userId(), order.clientOrderId(), order.symbol(),
                        order.instrumentVersion(), order.side(), order.orderType(), order.timeInForce(),
                        order.priceTicks(), order.quantitySteps(), order.reduceOnly(), order.postOnly(), now));
            }
            return openReduceOnlyOrders.size();
        }

        @Override
        public OrderCommandEvent createReduceOnlyMarketOrder(long candidateId,
                                                             long userId,
                                                             String symbol,
                                                             long instrumentVersion,
                                                             OrderSide side,
                                                             long quantitySteps,
                                                             Instant now,
                                                             Function<Object, String> serializer) {
            long orderId = state.next("trading-order");
            long commandId = state.next("trading-command");
            OrderRecord order = new OrderRecord(orderId, userId, "LIQ-" + candidateId, symbol, instrumentVersion,
                    side, OrderType.MARKET, TimeInForce.IOC, 0L, quantitySteps, 0L, quantitySteps,
                    true, false, OrderStatus.ACCEPTED, null, now, now);
            state.orders.put(orderId, order);
            OrderCommandEvent command = new OrderCommandEvent(OrderCommandType.PLACE, commandId, orderId, userId,
                    order.clientOrderId(), symbol, instrumentVersion, side, OrderType.MARKET, TimeInForce.IOC,
                    0L, quantitySteps, true, false, now);
            commands.add(command);
            return command;
        }
    }

    private static final class FakeLiquidationSequenceRepository extends LiquidationSequenceRepository {
        private final SharedState state;

        private FakeLiquidationSequenceRepository(SharedState state) {
            super(null);
            this.state = state;
        }

        @Override
        public long nextLiquidationSequence(String sequenceName) {
            return state.next("liquidation-" + sequenceName);
        }
    }

    private record UserAssetKey(long userId, String asset) {
    }

    private record PositionKey(long userId, String symbol) {
    }

    private record OutboxEnvelope(long id,
                                  String topic,
                                  String eventKey,
                                  String eventType,
                                  String payload,
                                  Class<?> payloadType) {
    }

    private static final class BalanceState {
        private long availableUnits;
        private long lockedUnits;
        private long deficitUnits;

        private BalanceState(long availableUnits, long lockedUnits, long deficitUnits) {
            this.availableUnits = availableUnits;
            this.lockedUnits = lockedUnits;
            this.deficitUnits = deficitUnits;
        }
    }

    private static final class MarginReservationState {
        private final long userId;
        private final String asset;
        private final long orderId;
        private final String symbol;
        private final long reservedUnits;
        private long releasedUnits;
        private long positionMarginUnits;
        private final long orderQuantitySteps;
        private final boolean reduceOnly;

        private MarginReservationState(long userId,
                                       String asset,
                                       long orderId,
                                       String symbol,
                                       long reservedUnits,
                                       long releasedUnits,
                                       long positionMarginUnits,
                                       long orderQuantitySteps,
                                       boolean reduceOnly) {
            this.userId = userId;
            this.asset = asset;
            this.orderId = orderId;
            this.symbol = symbol;
            this.reservedUnits = reservedUnits;
            this.releasedUnits = releasedUnits;
            this.positionMarginUnits = positionMarginUnits;
            this.orderQuantitySteps = orderQuantitySteps;
            this.reduceOnly = reduceOnly;
        }
    }

    private static PlatformTransactionManager transactionManager() {
        return new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                return new SimpleTransactionStatus();
            }

            @Override
            public void commit(TransactionStatus status) {
            }

            @Override
            public void rollback(TransactionStatus status) {
            }
        };
    }
}
