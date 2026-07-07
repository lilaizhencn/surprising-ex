package com.surprising.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.account.api.model.AccountType;
import com.surprising.account.api.model.BalanceResponse;
import com.surprising.account.api.model.LiquidationFeeSettledEvent;
import com.surprising.account.api.model.PositionMarginAdjustmentRequest;
import com.surprising.account.api.model.PositionMarginAdjustmentResponse;
import com.surprising.account.api.model.PositionMarginResponse;
import com.surprising.account.api.model.PositionResponse;
import com.surprising.account.api.model.PositionUpdatedEvent;
import com.surprising.account.provider.config.AccountProperties;
import com.surprising.account.provider.model.BalanceSettlementState;
import com.surprising.account.provider.model.ContractSpec;
import com.surprising.account.provider.model.LiquidationFeeContext;
import com.surprising.account.provider.model.LiquidationFeeSettlement;
import com.surprising.account.provider.model.OrderFeeSnapshot;
import com.surprising.account.provider.model.PositionState;
import com.surprising.account.provider.model.SpotInstrumentSpec;
import com.surprising.account.provider.repository.AccountOutboxRepository;
import com.surprising.account.provider.repository.AccountRepository;
import com.surprising.account.provider.service.AccountService;
import com.surprising.account.provider.service.MarginTransferMath;
import com.surprising.account.provider.service.PnlSettlementMath;
import com.surprising.account.provider.service.PositionCalculator;
import com.surprising.instrument.api.math.PerpetualContractMath;
import com.surprising.instrument.api.model.ContractSettlementMethod;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.instrument.api.model.DeliverySettlementEvent;
import com.surprising.instrument.api.model.InstrumentResponse;
import com.surprising.instrument.api.model.InstrumentStatus;
import com.surprising.instrument.api.model.InstrumentType;
import com.surprising.instrument.api.model.OptionExerciseEvent;
import com.surprising.instrument.api.model.OptionExerciseStyle;
import com.surprising.instrument.api.model.OptionType;
import com.surprising.liquidation.api.model.LiquidationOrderResponse;
import com.surprising.liquidation.api.model.LiquidationOrderStatus;
import com.surprising.liquidation.provider.config.LiquidationProperties;
import com.surprising.liquidation.provider.model.ClaimedCandidate;
import com.surprising.liquidation.provider.model.LiquidationCloseState;
import com.surprising.liquidation.provider.model.LiquidationPricingDecision;
import com.surprising.liquidation.provider.model.LiquidationPricingInput;
import com.surprising.liquidation.provider.model.LiquidationSizingInput;
import com.surprising.liquidation.provider.repository.LiquidationOrderRepository;
import com.surprising.liquidation.provider.repository.LiquidationRepository;
import com.surprising.liquidation.provider.repository.LiquidationSequenceRepository;
import com.surprising.liquidation.provider.service.LiquidationService;
import com.surprising.liquidation.provider.service.LiquidationPriceCalculator;
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
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
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
import com.surprising.trading.api.model.PositionMode;
import com.surprising.trading.api.model.PositionSide;
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
import com.surprising.trading.order.model.SpotReservationRequirement;
import com.surprising.trading.order.repository.OrderFeeRepository;
import com.surprising.trading.order.repository.OrderMarginRepository;
import com.surprising.trading.order.repository.OrderRepository;
import com.surprising.trading.order.repository.OutboxRepository;
import com.surprising.trading.order.repository.SpotOrderReservationRepository;
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
    void spotLimitTradeMovesProductBalancesWithoutPerpetualPositions() {
        try (Harness harness = Harness.spot()) {
            harness.state.setSpotBalance(1001L, "BTC", 10L);
            harness.state.setSpotBalance(2002L, "USDT", 100_000L);

            OrderResponse maker = harness.place(1001L, "spot-maker-sell-100", OrderSide.SELL,
                    OrderType.LIMIT, TimeInForce.GTC, 100L, 2L, false);
            OrderResponse taker = harness.place(2002L, "spot-taker-buy-100", OrderSide.BUY,
                    OrderType.LIMIT, TimeInForce.IOC, 100L, 2L, false);
            harness.processNewOrderCommands();

            assertThat(harness.orderRepository.findByOrderId(maker.orderId()).orElseThrow().status())
                    .isEqualTo(OrderStatus.FILLED);
            assertThat(harness.orderRepository.findByOrderId(taker.orderId()).orElseThrow().status())
                    .isEqualTo(OrderStatus.FILLED);
            assertThat(harness.state.spotBalance(1001L, "BTC").availableUnits).isEqualTo(8L);
            assertThat(harness.state.spotBalance(1001L, "BTC").lockedUnits).isZero();
            assertThat(harness.state.spotBalance(1001L, "USDT").availableUnits).isEqualTo(20_000L);
            assertThat(harness.state.spotBalance(2002L, "USDT").availableUnits).isEqualTo(80_000L);
            assertThat(harness.state.spotBalance(2002L, "USDT").lockedUnits).isZero();
            assertThat(harness.state.spotBalance(2002L, "BTC").availableUnits).isEqualTo(2L);
            assertThat(harness.state.position(1001L)).isEqualTo(new PositionState(0L, 0L, 0L, 0L));
            assertThat(harness.state.position(2002L)).isEqualTo(new PositionState(0L, 0L, 0L, 0L));
            assertThat(harness.accountOutbox.positionEvents).isEmpty();
        }
    }

    @Test
    void linearDeliveryTradeSettlesCashAtExpiryAndIsIdempotent() {
        try (Harness harness = Harness.linearDelivery()) {
            harness.state.setBalance(1001L, 30_000L);
            harness.state.setBalance(2002L, 30_000L);

            harness.place(1001L, "delivery-maker-sell-100", OrderSide.SELL, OrderType.LIMIT,
                    TimeInForce.GTC, 100L, 4L, false);
            harness.place(2002L, "delivery-taker-buy-100", OrderSide.BUY, OrderType.LIMIT,
                    TimeInForce.IOC, 100L, 4L, false);
            harness.processNewOrderCommands();

            assertThat(harness.state.position(2002L)).isEqualTo(new PositionState(4L, VERSION, 100L, 0L));
            assertThat(harness.state.position(1001L)).isEqualTo(new PositionState(-4L, VERSION, 100L, 0L));
            assertThat(harness.state.positionMargin(2002L)).isEqualTo(400L);
            assertThat(harness.state.positionMargin(1001L)).isEqualTo(400L);

            harness.state.markPriceTicks = 120L;
            assertThat(harness.settleDelivery(VERSION + 1)).isEqualTo(2);
            assertThat(harness.settleDelivery(VERSION + 1)).isZero();

            assertThat(harness.state.position(2002L)).isEqualTo(new PositionState(0L, 0L, 0L, 8_000L));
            assertThat(harness.state.position(1001L)).isEqualTo(new PositionState(0L, 0L, 0L, -8_000L));
            assertBalance(harness.state.balance(2002L), 38_000L, 0L, 0L);
            assertBalance(harness.state.balance(1001L), 22_000L, 0L, 0L);
        }
    }

    @Test
    void optionPremiumTradeThenEuropeanExerciseMovesCashOnce() {
        try (Harness harness = Harness.option()) {
            harness.state.setBalance(1001L, 10_000L);
            harness.state.setBalance(2002L, 10_000L);

            harness.place(2002L, "option-maker-sell-5", OrderSide.SELL, OrderType.LIMIT,
                    TimeInForce.GTC, 5L, 2L, false);
            harness.place(1001L, "option-taker-buy-5", OrderSide.BUY, OrderType.LIMIT,
                    TimeInForce.IOC, 5L, 2L, false);
            harness.processNewOrderCommands();

            assertThat(harness.state.position(1001L)).isEqualTo(new PositionState(2L, VERSION, 5L, 0L));
            assertThat(harness.state.position(2002L)).isEqualTo(new PositionState(-2L, VERSION, 5L, 0L));
            assertBalance(harness.state.balance(1001L), 9_000L, 0L, 0L);
            assertBalance(harness.state.balance(2002L), 9_900L, 1_100L, 0L);
            assertThat(harness.state.positionMargin(1001L)).isZero();
            assertThat(harness.state.positionMargin(2002L)).isEqualTo(1_100L);

            harness.state.underlyingMarkPriceUnits = 120L;
            assertThat(harness.exerciseCallOption(VERSION + 1, 100L)).isEqualTo(2);
            assertThat(harness.exerciseCallOption(VERSION + 1, 100L)).isZero();

            assertThat(harness.state.position(1001L)).isEqualTo(new PositionState(0L, 0L, 0L, 3_000L));
            assertThat(harness.state.position(2002L)).isEqualTo(new PositionState(0L, 0L, 0L, -3_000L));
            assertBalance(harness.state.balance(1001L), 13_000L, 0L, 0L);
            assertBalance(harness.state.balance(2002L), 7_000L, 0L, 0L);
        }
    }

    @Test
    void outOfMoneyCallOptionExerciseReleasesSellerMarginWithoutCashPayoff() {
        try (Harness harness = Harness.option()) {
            harness.state.setBalance(1001L, 10_000L);
            harness.state.setBalance(2002L, 10_000L);

            harness.place(2002L, "option-otm-maker-sell-5", OrderSide.SELL, OrderType.LIMIT,
                    TimeInForce.GTC, 5L, 2L, false);
            harness.place(1001L, "option-otm-taker-buy-5", OrderSide.BUY, OrderType.LIMIT,
                    TimeInForce.IOC, 5L, 2L, false);
            harness.processNewOrderCommands();

            assertBalance(harness.state.balance(1001L), 9_000L, 0L, 0L);
            assertBalance(harness.state.balance(2002L), 9_900L, 1_100L, 0L);

            harness.state.underlyingMarkPriceUnits = 90L;
            assertThat(harness.exerciseCallOption(VERSION + 1, 100L)).isEqualTo(2);
            assertThat(harness.exerciseCallOption(VERSION + 1, 100L)).isZero();

            assertThat(harness.state.position(1001L)).isEqualTo(new PositionState(0L, 0L, 0L, -1_000L));
            assertThat(harness.state.position(2002L)).isEqualTo(new PositionState(0L, 0L, 0L, 1_000L));
            assertBalance(harness.state.balance(1001L), 9_000L, 0L, 0L);
            assertBalance(harness.state.balance(2002L), 11_000L, 0L, 0L);
        }
    }

    @Test
    void putOptionPremiumTradeThenEuropeanExerciseMovesCashOnce() {
        try (Harness harness = Harness.option()) {
            harness.state.setBalance(1001L, 10_000L);
            harness.state.setBalance(2002L, 10_000L);

            harness.place(2002L, "option-put-maker-sell-5", OrderSide.SELL, OrderType.LIMIT,
                    TimeInForce.GTC, 5L, 2L, false);
            harness.place(1001L, "option-put-taker-buy-5", OrderSide.BUY, OrderType.LIMIT,
                    TimeInForce.IOC, 5L, 2L, false);
            harness.processNewOrderCommands();

            harness.state.underlyingMarkPriceUnits = 70L;
            assertThat(harness.exercisePutOption(VERSION + 1, 100L)).isEqualTo(2);
            assertThat(harness.exercisePutOption(VERSION + 1, 100L)).isZero();

            assertThat(harness.state.position(1001L)).isEqualTo(new PositionState(0L, 0L, 0L, 5_000L));
            assertThat(harness.state.position(2002L)).isEqualTo(new PositionState(0L, 0L, 0L, -5_000L));
            assertBalance(harness.state.balance(1001L), 15_000L, 0L, 0L);
            assertBalance(harness.state.balance(2002L), 5_000L, 0L, 0L);
        }
    }

    private static void assertBalance(BalanceState actual,
                                      long availableUnits,
                                      long lockedUnits,
                                      long deficitUnits) {
        assertThat(actual.availableUnits).isEqualTo(availableUnits);
        assertThat(actual.lockedUnits).isEqualTo(lockedUnits);
        assertThat(actual.deficitUnits).isEqualTo(deficitUnits);
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
    void isolatedMarginOpenAndManualMarginAdjustmentStayScopedToIsolatedPosition() {
        try (Harness harness = new Harness()) {
            harness.state.setBalance(1101L, 100_000L);
            harness.state.setBalance(2202L, 100_000L);

            harness.place(1101L, "iso-maker-sell", OrderSide.SELL, OrderType.LIMIT,
                    TimeInForce.GTC, 100L, 10L, MarginMode.CROSS, false);
            harness.place(2202L, "iso-user-open-long", OrderSide.BUY, OrderType.LIMIT,
                    TimeInForce.IOC, 100L, 10L, MarginMode.ISOLATED, false);
            harness.processNewOrderCommands();

            assertThat(harness.state.position(2202L, MarginMode.CROSS))
                    .isEqualTo(new PositionState(0L, 0L, 0L, 0L));
            assertThat(harness.state.position(2202L, MarginMode.ISOLATED))
                    .isEqualTo(new PositionState(10L, VERSION, 100L, 0L));
            assertThat(harness.state.positionMargin(2202L, MarginMode.ISOLATED)).isEqualTo(1_000L);
            assertThat(harness.accountService.positionMargin(2202L, SYMBOL, "ISOLATED").marginUnits())
                    .isEqualTo(1_000L);
            assertThat(harness.state.balance(2202L).availableUnits).isEqualTo(99_000L);
            assertThat(harness.state.balance(2202L).lockedUnits).isEqualTo(1_000L);

            PositionMarginAdjustmentResponse added = harness.accountService.adjustPositionMargin(
                    new PositionMarginAdjustmentRequest(2202L, SYMBOL, MarginMode.ISOLATED,
                            500L, "iso-margin-add-1", "ADD_POSITION_MARGIN"));
            assertThat(added.positionMarginUnits()).isEqualTo(1_500L);
            assertThat(harness.state.balance(2202L).availableUnits).isEqualTo(98_500L);
            assertThat(harness.state.balance(2202L).lockedUnits).isEqualTo(1_500L);

            PositionMarginAdjustmentResponse removed = harness.accountService.adjustPositionMargin(
                    new PositionMarginAdjustmentRequest(2202L, SYMBOL, MarginMode.ISOLATED,
                            -200L, "iso-margin-remove-1", "REMOVE_POSITION_MARGIN"));
            assertThat(removed.positionMarginUnits()).isEqualTo(1_300L);
            assertThat(harness.state.positionMargin(2202L, MarginMode.ISOLATED)).isEqualTo(1_300L);
            assertThat(harness.state.balance(2202L).availableUnits).isEqualTo(98_700L);
            assertThat(harness.state.balance(2202L).lockedUnits).isEqualTo(1_300L);
            assertThat(harness.accountOutbox.positionEvents)
                    .anySatisfy(event -> {
                        assertThat(event.userId()).isEqualTo(2202L);
                        assertThat(event.marginMode()).isEqualTo(MarginMode.ISOLATED);
                        assertThat(event.tradeId()).isZero();
                    });
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

            harness.state.markPriceTicks = 99L;
            harness.place(3303L, "liquidation-bid", OrderSide.BUY, OrderType.LIMIT,
                    TimeInForce.GTC, 99L, 6L, false);
            harness.processNewOrderCommands();

            harness.riskService.scan();
            LiquidationCandidateEvent candidate = harness.riskOutbox.lastCandidate();
            assertThat(candidate.userId()).isEqualTo(2202L);
            assertThat(candidate.instrumentVersion()).isEqualTo(VERSION);
            assertThat(candidate.signedQuantitySteps()).isEqualTo(6L);
            assertThat(candidate.markPriceTicks()).isEqualTo(99L);

            harness.liquidationService.processCandidate(candidate);
            OrderCommandEvent liquidationOrder = harness.liquidationOrderRepository.commands.get(0);
            assertThat(liquidationOrder.side()).isEqualTo(OrderSide.SELL);
            assertThat(liquidationOrder.orderType()).isEqualTo(OrderType.MARKET);
            assertThat(liquidationOrder.timeInForce()).isEqualTo(TimeInForce.IOC);
            assertThat(liquidationOrder.reduceOnly()).isTrue();
            assertThat(liquidationOrder.quantitySteps()).isEqualTo(6L);

            harness.processCommand(liquidationOrder);

            assertThat(harness.state.position(2202L)).isEqualTo(new PositionState(0L, 0L, 0L, -600L));
            assertThat(harness.state.balance(2202L).availableUnits).isEqualTo(221L);
            assertThat(harness.state.balance(2202L).lockedUnits).isZero();
            assertThat(harness.state.balance(2202L).deficitUnits).isZero();
            assertThat(harness.accountOutbox.liquidationFeeEvents).singleElement()
                    .satisfies(event -> {
                        assertThat(event.userId()).isEqualTo(2202L);
                        assertThat(event.asset()).isEqualTo(SETTLE);
                        assertThat(event.amountUnits()).isEqualTo(179L);
                        assertThat(event.feeRatePpm()).isEqualTo(3_000L);
                        assertThat(event.marginMode()).isEqualTo(MarginMode.CROSS);
                    });
            assertThat(harness.liquidationRepository.status(candidate.candidateId())).isEqualTo("COMPLETED");
            assertThat(harness.liquidationRepository.orders).singleElement()
                    .satisfies(order -> {
                        assertThat(order.status()).isEqualTo(LiquidationOrderStatus.FILLED);
                        assertThat(order.liquidationFeeRatePpm()).isEqualTo(3_000L);
                    });
        }
    }

    @Test
    void liquidationPreemptionIgnoresReduceOnlyOrdersFromOtherProductLines() {
        try (Harness harness = new Harness()) {
            harness.state.setBalance(1101L, 1_000L);
            harness.state.setBalance(2202L, 1_000L);

            harness.place(1101L, "risk-maker-sell", OrderSide.SELL, OrderType.LIMIT,
                    TimeInForce.GTC, 100L, 6L, false);
            harness.place(2202L, "risk-user-open-long", OrderSide.BUY, OrderType.LIMIT,
                    TimeInForce.IOC, 100L, 6L, false);
            harness.processNewOrderCommands();
            harness.state.markPriceTicks = 99L;

            harness.riskService.scan();
            LiquidationCandidateEvent candidate = harness.riskOutbox.lastCandidate();
            long foreignOrderId = 90_001L;
            Instant now = Instant.parse("2026-07-01T00:00:00Z");
            harness.state.orders.put(foreignOrderId, new OrderRecord(foreignOrderId, ProductLine.OPTION,
                    2202L, "option-reduce-only-close", SYMBOL, VERSION, OrderSide.SELL, OrderType.LIMIT,
                    TimeInForce.GTC, 99L, 6L, 0L, 6L, MarginMode.CROSS, PositionSide.NET,
                    0L, 0L, true, false, OrderStatus.ACCEPTED, null, now, now));

            harness.liquidationService.processCandidate(candidate);

            assertThat(harness.liquidationOrderRepository.commands).singleElement()
                    .satisfies(command -> {
                        assertThat(command.commandType()).isEqualTo(OrderCommandType.PLACE);
                        assertThat(command.reduceOnly()).isTrue();
                        assertThat(command.side()).isEqualTo(OrderSide.SELL);
                    });
            assertThat(harness.state.orders.get(foreignOrderId).productLine()).isEqualTo(ProductLine.OPTION);
            assertThat(harness.state.orders.get(foreignOrderId).status()).isEqualTo(OrderStatus.ACCEPTED);
        }
    }

    private static final class Harness implements AutoCloseable {
        private final SharedState state;
        private final ObjectMapper objectMapper = new ObjectMapper();
        private final FakeOrderRepository orderRepository;
        private final FakeOrderFeeRepository orderFeeRepository;
        private final FakeOrderOutboxRepository orderOutbox;
        private final FakeMatchingResultRepository matchingResultRepository;
        private final FakeAccountOutboxRepository accountOutbox;
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
            SharedState state = new SharedState(ContractType.INVERSE_PERPETUAL, "BTC",
                    10_000_000_000L, 100_000_000L, 100_000_000L, 100_000L);
            state.markPriceTicks = 50_000L;
            return new Harness(0L, state);
        }

        private static Harness spot() {
            SharedState state = SharedState.spot();
            state.markPriceTicks = 100L;
            return new Harness(0L, state);
        }

        private static Harness linearDelivery() {
            SharedState state = SharedState.delivery(ContractType.LINEAR_DELIVERY, "USDT",
                    NOTIONAL_MULTIPLIER, PRICE_TICK_UNITS, SETTLE_SCALE_UNITS, INITIAL_MARGIN_RATE_PPM);
            state.markPriceTicks = 100L;
            return new Harness(0L, state);
        }

        private static Harness option() {
            SharedState state = SharedState.option();
            state.markPriceTicks = 5L;
            state.underlyingMarkPriceUnits = 120L;
            return new Harness(0L, state);
        }

        private Harness(long marketMaxSlippagePpm, SharedState state) {
            this.state = state;
            orderRepository = new FakeOrderRepository(state);
            orderFeeRepository = new FakeOrderFeeRepository(state);
            orderOutbox = new FakeOrderOutboxRepository(state);
            matchingResultRepository = new FakeMatchingResultRepository(state);
            accountOutbox = new FakeAccountOutboxRepository(state);
            riskOutbox = new FakeRiskOutboxRepository(objectMapper);
            liquidationRepository = new FakeLiquidationRepository(state);
            liquidationOrderRepository = new FakeLiquidationOrderRepository(state);

            InstrumentRule rule = new InstrumentRule(SYMBOL, VERSION, "TRADING", state.instrumentType,
                    state.contractType, state.baseAsset, state.quoteAsset, state.settleAsset,
                    Set.of(OrderType.LIMIT.name(), OrderType.MARKET.name()),
                    Set.of(TimeInForce.GTC.name(), TimeInForce.IOC.name(), TimeInForce.FOK.name(),
                            TimeInForce.GTX.name()),
                    true, true, state.instrumentType != InstrumentType.SPOT, state.quantityStepUnits,
                    1L, 1_000_000L, 1L, Long.MAX_VALUE / 4, state.notionalMultiplierUnits,
                    100_000_000L, state.initialMarginRatePpm);
            TradingOrderProperties orderProperties = new TradingOrderProperties();
            orderProperties.getKafka().setProductLine(state.productLine());
            orderProperties.getKafka().setProductTopicsEnabled(true);
            orderProperties.getRisk().setMarketMaxSlippagePpm(marketMaxSlippagePpm);
            OrderValidator orderValidator = new OrderValidator(symbol -> SYMBOL.equals(symbol)
                    ? Optional.of(rule)
                    : Optional.empty(), orderProperties,
                    (symbol, instrumentVersion, maxAgeMs) -> OptionalLong.of(state.markPriceTicks));
            ReduceOnlyValidator reduceOnlyValidator = new ReduceOnlyValidator(new FakeReduceOnlyLookup(state),
                    orderProperties);
            FakeOrderMarginRepository marginRepository = new FakeOrderMarginRepository(state);
            SpotOrderReservationRepository spotReservationRepository = state.instrumentType == InstrumentType.SPOT
                    ? new FakeSpotOrderReservationRepository(state)
                    : null;
            orderService = new OrderService(objectMapper, orderProperties, orderValidator,
                    reduceOnlyValidator, orderRepository, orderFeeRepository, marginRepository,
                    spotReservationRepository, orderOutbox);

            MatchingProperties matchingProperties = new MatchingProperties();
            matchingProperties.getKafka().setProductLine(state.productLine());
            matchingProperties.getKafka().setProductTopicsEnabled(true);
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

            AccountProperties accountProperties = new AccountProperties();
            accountProperties.getKafka().setProductLine(state.productLine());
            accountProperties.getKafka().setProductTopicsEnabled(true);
            accountService = new AccountService(new FakeAccountRepository(state), new PositionCalculator(),
                    null, accountProperties, accountOutbox);
            RiskProperties riskProperties = new RiskProperties();
            riskProperties.getKafka().setProductLine(state.productLine());
            riskProperties.getKafka().setProductTopicsEnabled(true);
            riskService = new RiskService(objectMapper, riskProperties, new FakeRiskRepository(state),
                    new FakeRiskSequenceRepository(state), riskOutbox, transactionManager());
            liquidationService = new LiquidationService(objectMapper, new LiquidationProperties(),
                    liquidationRepository, liquidationOrderRepository, new FakeLiquidationSequenceRepository(state),
                    new LiquidationSizingPolicy(), new LiquidationPriceCalculator());
        }

        private OrderResponse place(long userId,
                                    String clientOrderId,
                                    OrderSide side,
                                    OrderType orderType,
                                    TimeInForce timeInForce,
                                    long priceTicks,
                                    long quantitySteps,
                                    boolean reduceOnly) {
            return place(userId, clientOrderId, side, orderType, timeInForce, priceTicks, quantitySteps,
                    MarginMode.CROSS, reduceOnly);
        }

        private OrderResponse place(long userId,
                                    String clientOrderId,
                                    OrderSide side,
                                    OrderType orderType,
                                    TimeInForce timeInForce,
                                    long priceTicks,
                                    long quantitySteps,
                                    MarginMode marginMode,
                                    boolean reduceOnly) {
            return orderService.place(new PlaceOrderRequest(userId, clientOrderId, SYMBOL, side, orderType,
                    timeInForce, priceTicks, quantitySteps, marginMode, reduceOnly, false));
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
            int resultOffset = matchingResultRepository.results.size();
            int tradeOffset = matchingResultRepository.trades.size();
            matchingService.process(command);
            for (MatchResultEvent result : matchingResultRepository.results.subList(resultOffset,
                    matchingResultRepository.results.size())) {
                liquidationService.processMatchResult(result);
            }
            for (MatchTradeEvent trade : matchingResultRepository.trades.subList(tradeOffset,
                    matchingResultRepository.trades.size())) {
                accountService.processTrade(trade);
            }
        }

        private int settleDelivery(long eventVersion) {
            return accountService.processDeliverySettlement(new DeliverySettlementEvent(
                    SYMBOL, eventVersion, state.contractType, Instant.now(), Instant.now(),
                    ContractSettlementMethod.CASH, InstrumentStatus.CLOSED, Instant.now(),
                    instrument(eventVersion, InstrumentStatus.CLOSED)));
        }

        private int exerciseCallOption(long eventVersion, long strikePriceUnits) {
            return exerciseOption(eventVersion, strikePriceUnits, OptionType.CALL);
        }

        private int exercisePutOption(long eventVersion, long strikePriceUnits) {
            return exerciseOption(eventVersion, strikePriceUnits, OptionType.PUT);
        }

        private int exerciseOption(long eventVersion, long strikePriceUnits, OptionType optionType) {
            return accountService.processOptionExercise(new OptionExerciseEvent(
                    SYMBOL, eventVersion, SYMBOL, strikePriceUnits, optionType, OptionExerciseStyle.EUROPEAN,
                    Instant.now(), Instant.now(), ContractSettlementMethod.CASH, InstrumentStatus.CLOSED,
                    Instant.now(), optionInstrument(eventVersion, strikePriceUnits, optionType,
                            InstrumentStatus.CLOSED)));
        }

        private InstrumentResponse optionInstrument(long version, long strikePriceUnits, InstrumentStatus status) {
            return optionInstrument(version, strikePriceUnits, OptionType.CALL, status);
        }

        private InstrumentResponse optionInstrument(long version,
                                                    long strikePriceUnits,
                                                    OptionType optionType,
                                                    InstrumentStatus status) {
            return instrument(version, status, SYMBOL, strikePriceUnits, optionType);
        }

        private InstrumentResponse instrument(InstrumentStatus status) {
            return instrument(VERSION, status, null, null, null);
        }

        private InstrumentResponse instrument(long version, InstrumentStatus status) {
            return instrument(version, status, null, null, null);
        }

        private InstrumentResponse instrument(long version,
                                              InstrumentStatus status,
                                              String underlyingSymbol,
                                              Long strikePriceUnits,
                                              OptionType optionType) {
            return new InstrumentResponse(
                    SYMBOL,
                    version,
                    state.instrumentType,
                    state.contractType,
                    state.baseAsset,
                    state.quoteAsset,
                    state.settleAsset,
                    1_000_000L,
                    state.settleAsset,
                    state.priceTickUnits,
                    state.quantityStepUnits,
                    1L,
                    1_000_000L,
                    1L,
                    Long.MAX_VALUE / 4,
                    state.notionalMultiplierUnits,
                    1,
                    3,
                    List.of(OrderType.LIMIT.name(), OrderType.MARKET.name()),
                    List.of(TimeInForce.GTC.name(), TimeInForce.IOC.name()),
                    true,
                    state.instrumentType != InstrumentType.SPOT,
                    state.instrumentType != InstrumentType.SPOT,
                    100_000_000L,
                    state.initialMarginRatePpm,
                    state.maintenanceMarginRatePpm,
                    state.makerFeeRatePpm,
                    state.takerFeeRatePpm,
                    Long.MAX_VALUE / 4,
                    300_000L,
                    Long.MAX_VALUE / 8,
                    state.contractType.isPerpetual() ? 8 : 0,
                    0L,
                    0L,
                    0L,
                    Long.MAX_VALUE / 8,
                    2,
                    Instant.now(),
                    Instant.now(),
                    underlyingSymbol,
                    strikePriceUnits,
                    optionType,
                    optionType == null ? null : OptionExerciseStyle.EUROPEAN,
                    state.contractType.isDelivery() || state.contractType.isOption()
                            ? ContractSettlementMethod.CASH
                            : null,
                    status,
                    Instant.now(),
                    Instant.now(),
                    Instant.now(),
                    List.of(),
                    List.of());
        }

        @Override
        public void close() {
            engine.stop();
        }
    }

    private static final class SharedState {
        private final InstrumentType instrumentType;
        private final ContractType contractType;
        private final String baseAsset;
        private final String quoteAsset;
        private final String settleAsset;
        private final long quantityStepUnits;
        private final long notionalMultiplierUnits;
        private final long priceTickUnits;
        private final long settleScaleUnits;
        private final long initialMarginRatePpm;
        private final long maintenanceMarginRatePpm;
        private final long makerFeeRatePpm = 0L;
        private final long takerFeeRatePpm = 0L;
        private final Map<String, Long> sequences = new HashMap<>();
        private final Map<Long, OrderRecord> orders = new LinkedHashMap<>();
        private final Map<UserAssetKey, BalanceState> balances = new HashMap<>();
        private final Map<UserAssetKey, BalanceState> spotBalances = new HashMap<>();
        private final Map<PositionKey, PositionState> positions = new HashMap<>();
        private final Map<PositionKey, Long> positionMargins = new HashMap<>();
        private final Map<Long, MarginReservationState> reservations = new HashMap<>();
        private final Map<Long, SpotReservationState> spotReservations = new HashMap<>();
        private final Map<Long, LiquidationOrderResponse> liquidationOrders = new LinkedHashMap<>();
        private final Map<String, PositionMarginAdjustmentResponse> positionMarginAdjustmentReferences = new HashMap<>();
        private final Set<String> liquidationFeeReferences = new HashSet<>();
        private final Set<String> lifecycleReferences = new HashSet<>();
        private final Set<Long> processedTrades = new HashSet<>();
        private long markPriceTicks = 100L;
        private long underlyingMarkPriceUnits = 100L;

        private SharedState(ContractType contractType,
                            String settleAsset,
                            long notionalMultiplierUnits,
                            long priceTickUnits,
                            long settleScaleUnits,
                            long initialMarginRatePpm) {
            this(InstrumentType.PERPETUAL, contractType, "BTC", "USDT", settleAsset, 1L,
                    notionalMultiplierUnits, priceTickUnits, settleScaleUnits, initialMarginRatePpm);
        }

        private static SharedState spot() {
            return new SharedState(InstrumentType.SPOT, ContractType.SPOT, "BTC", "USDT", "USDT",
                    1L, 100L, 1L, 1L, 0L);
        }

        private static SharedState delivery(ContractType contractType,
                                            String settleAsset,
                                            long notionalMultiplierUnits,
                                            long priceTickUnits,
                                            long settleScaleUnits,
                                            long initialMarginRatePpm) {
            return new SharedState(InstrumentType.DELIVERY, contractType, "BTC", "USDT", settleAsset, 1L,
                    notionalMultiplierUnits, priceTickUnits, settleScaleUnits, initialMarginRatePpm);
        }

        private static SharedState option() {
            return new SharedState(InstrumentType.OPTION, ContractType.VANILLA_OPTION, "BTC", "USDT", "USDT",
                    1L, 100L, 1L, 1L, 100_000L);
        }

        private SharedState(InstrumentType instrumentType,
                            ContractType contractType,
                            String baseAsset,
                            String quoteAsset,
                            String settleAsset,
                            long quantityStepUnits,
                            long notionalMultiplierUnits,
                            long priceTickUnits,
                            long settleScaleUnits,
                            long initialMarginRatePpm) {
            this.instrumentType = instrumentType;
            this.contractType = contractType;
            this.baseAsset = baseAsset;
            this.quoteAsset = quoteAsset;
            this.settleAsset = settleAsset;
            this.quantityStepUnits = quantityStepUnits;
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

        private void setSpotBalance(long userId, String asset, long availableUnits) {
            spotBalances.put(new UserAssetKey(userId, asset), new BalanceState(availableUnits, 0L, 0L));
        }

        private BalanceState balance(long userId) {
            return balances.computeIfAbsent(new UserAssetKey(userId, settleAsset),
                    ignored -> new BalanceState(0L, 0L, 0L));
        }

        private BalanceState spotBalance(long userId, String asset) {
            return spotBalances.computeIfAbsent(new UserAssetKey(userId, asset),
                    ignored -> new BalanceState(0L, 0L, 0L));
        }

        private String productAccountType() {
            return productLine().accountTypeCode();
        }

        private ProductLine productLine() {
            return contractType.productLine();
        }

        private PositionState position(long userId) {
            return position(userId, MarginMode.CROSS);
        }

        private PositionState position(long userId, MarginMode marginMode) {
            return position(userId, marginMode, PositionSide.NET);
        }

        private PositionState position(long userId, MarginMode marginMode, PositionSide positionSide) {
            return positions.getOrDefault(new PositionKey(userId, SYMBOL, MarginMode.defaultIfNull(marginMode),
                            PositionSide.defaultIfNull(positionSide)),
                    new PositionState(0L, 0L, 0L, 0L));
        }

        private void putPosition(long userId, PositionState state) {
            putPosition(userId, MarginMode.CROSS, state);
        }

        private void putPosition(long userId, MarginMode marginMode, PositionState state) {
            putPosition(userId, marginMode, PositionSide.NET, state);
        }

        private void putPosition(long userId, MarginMode marginMode, PositionSide positionSide, PositionState state) {
            positions.put(new PositionKey(userId, SYMBOL, MarginMode.defaultIfNull(marginMode),
                    PositionSide.defaultIfNull(positionSide)), state);
        }

        private long positionMargin(long userId) {
            return positionMargin(userId, MarginMode.CROSS);
        }

        private long positionMargin(long userId, MarginMode marginMode) {
            return positionMargin(userId, marginMode, PositionSide.NET);
        }

        private long positionMargin(long userId, MarginMode marginMode, PositionSide positionSide) {
            return positionMargins.getOrDefault(new PositionKey(userId, SYMBOL, MarginMode.defaultIfNull(marginMode),
                            PositionSide.defaultIfNull(positionSide)),
                    0L);
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
            if (order.clientOrderId() != null
                    && findByClientOrderId(order.productLine(), order.userId(), order.clientOrderId()).isPresent()) {
                return false;
            }
            state.orders.put(order.orderId(), order);
            return true;
        }

        @Override
        public void lockUserPositionMode(long userId) {
        }

        @Override
        public void lockUserPositionMode(ProductLine productLine, long userId) {
        }

        @Override
        public PositionMode positionMode(long userId) {
            return PositionMode.ONE_WAY;
        }

        @Override
        public PositionMode positionMode(ProductLine productLine, long userId) {
            return PositionMode.ONE_WAY;
        }

        @Override
        public void lockUserSymbolMarginScope(long userId, String symbol) {
        }

        @Override
        public void lockUserSymbolMarginScope(ProductLine productLine, long userId, String symbol) {
            assertThat(productLine).isEqualTo(state.productLine());
        }

        @Override
        public boolean hasActiveMarginModeConflict(long userId, String symbol, MarginMode marginMode) {
            return hasActiveMarginModeConflict(state.productLine(), userId, symbol, marginMode);
        }

        @Override
        public boolean hasActiveMarginModeConflict(ProductLine productLine,
                                                   long userId,
                                                   String symbol,
                                                   MarginMode marginMode) {
            assertThat(productLine).isEqualTo(state.productLine());
            MarginMode normalizedMarginMode = MarginMode.defaultIfNull(marginMode);
            boolean conflictingPosition = state.positions.entrySet().stream()
                    .anyMatch(entry -> entry.getKey().userId() == userId
                            && entry.getKey().symbol().equals(symbol)
                            && entry.getKey().marginMode() != normalizedMarginMode
                            && entry.getValue().signedQuantitySteps() != 0L);
            if (conflictingPosition) {
                return true;
            }
            return state.orders.values().stream()
                    .filter(order -> order.productLine() == productLine)
                    .filter(order -> order.userId() == userId)
                    .filter(order -> order.symbol().equals(symbol))
                    .filter(order -> order.marginMode() != normalizedMarginMode)
                    .filter(order -> order.status() == OrderStatus.ACCEPTED
                            || order.status() == OrderStatus.PARTIALLY_FILLED
                            || order.status() == OrderStatus.CANCEL_REQUESTED)
                    .anyMatch(order -> order.remainingQuantitySteps() > 0L);
        }

        @Override
        public Optional<ReduceOnlyPosition> lockedPosition(ProductLine productLine,
                                                           long userId,
                                                           String symbol,
                                                           MarginMode marginMode,
                                                           PositionSide positionSide) {
            assertThat(productLine).isEqualTo(state.productLine());
            PositionState position = state.position(userId, marginMode, positionSide);
            return position.signedQuantitySteps() == 0L
                    ? Optional.empty()
                    : Optional.of(new ReduceOnlyPosition(position.signedQuantitySteps(), position.instrumentVersion()));
        }

        @Override
        public void insertEvent(OrderEvent event) {
        }

        @Override
        public void reject(long orderId, String rejectReason, Instant now) {
            OrderRecord order = state.orders.get(orderId);
            if (order == null || order.status() != OrderStatus.ACCEPTED || order.executedQuantitySteps() != 0L) {
                throw new IllegalStateException("failed to reject order " + orderId);
            }
            state.orders.put(orderId, new OrderRecord(order.orderId(), order.userId(), order.clientOrderId(),
                    order.symbol(), order.instrumentVersion(), order.side(), order.orderType(), order.timeInForce(),
                    order.priceTicks(), order.quantitySteps(), order.executedQuantitySteps(), 0L,
                    order.marginMode(), order.positionSide(), order.makerFeeRatePpm(), order.takerFeeRatePpm(),
                    order.reduceOnly(), order.postOnly(), OrderStatus.REJECTED, rejectReason, order.createdAt(), now));
        }

        @Override
        public Optional<OrderRecord> findByOrderId(long orderId) {
            return Optional.ofNullable(state.orders.get(orderId));
        }

        @Override
        public boolean orderMatchesContractType(long orderId, String contractType) {
            OrderRecord order = state.orders.get(orderId);
            if (order == null || contractType == null) {
                return true;
            }
            return order.productLine() == ProductLine.requireContractTypeCode(contractType);
        }

        @Override
        public Optional<OrderRecord> findByClientOrderId(long userId, String clientOrderId) {
            return state.orders.values().stream()
                    .filter(order -> order.userId() == userId && clientOrderId.equals(order.clientOrderId()))
                    .findFirst();
        }

        @Override
        public Optional<OrderRecord> findByClientOrderId(ProductLine productLine, long userId, String clientOrderId) {
            return state.orders.values().stream()
                    .filter(order -> order.productLine() == productLine)
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
                    order.remainingQuantitySteps(), order.marginMode(), order.positionSide(), order.makerFeeRatePpm(),
                    order.takerFeeRatePpm(), order.reduceOnly(), order.postOnly(),
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

    private static final class FakeOrderFeeRepository extends OrderFeeRepository {
        private final SharedState state;

        private FakeOrderFeeRepository(SharedState state) {
            super(null);
            this.state = state;
        }

        @Override
        public Optional<com.surprising.trading.order.model.OrderFeeSnapshot> snapshot(long userId,
                                                                                      String symbol,
                                                                                      long instrumentVersion,
                                                                                      Instant now) {
            return Optional.of(new com.surprising.trading.order.model.OrderFeeSnapshot(
                    state.makerFeeRatePpm, state.takerFeeRatePpm, "INSTRUMENT"));
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
                                                       long userId,
                                                       MarginMode marginMode,
                                                       OrderSide side,
                                                       OrderType orderType,
                                                       long priceTicks,
                                                       long quantitySteps,
                                                       long marketMaxSlippagePpm,
                                                       long marketMaxMarkAgeMs) {
            return requirement(symbol, instrumentVersion, userId, marginMode, PositionSide.NET, side, orderType,
                    priceTicks, quantitySteps, marketMaxSlippagePpm, marketMaxMarkAgeMs);
        }

        @Override
        public Optional<MarginRequirement> requirement(String symbol,
                                                       long instrumentVersion,
                                                       long userId,
                                                       MarginMode marginMode,
                                                       PositionSide positionSide,
                                                       OrderSide side,
                                                       OrderType orderType,
                                                       long priceTicks,
                                                       long quantitySteps,
                                                       long marketMaxSlippagePpm,
                                                       long marketMaxMarkAgeMs) {
            assertThat(symbol).isEqualTo(SYMBOL);
            assertThat(instrumentVersion).isEqualTo(VERSION);
            assertThat(userId).isPositive();
            lastQuantitySteps = quantitySteps;
            long marginUnits = OrderMarginMath.initialMarginUnits(state.contractType, side, orderType,
                    priceTicks, quantitySteps, state.markPriceTicks, marketMaxSlippagePpm, state.notionalMultiplierUnits,
                    state.priceTickUnits, state.settleScaleUnits, state.initialMarginRatePpm);
            return Optional.of(new MarginRequirement(state.productAccountType(), state.settleAsset, marginUnits));
        }

        public boolean reserve(long userId,
                               String asset,
                               long orderId,
                               String symbol,
                               MarginMode marginMode,
                               long amountUnits,
                               Instant now) {
            return reserve(userId, asset, orderId, symbol, marginMode, PositionSide.NET, amountUnits, now);
        }

        public boolean reserve(long userId,
                               String asset,
                               long orderId,
                               String symbol,
                               MarginMode marginMode,
                               PositionSide positionSide,
                               long amountUnits,
                               Instant now) {
            BalanceState balance = state.balance(userId);
            if (balance.availableUnits < amountUnits) {
                return false;
            }
            balance.availableUnits -= amountUnits;
            balance.lockedUnits += amountUnits;
            state.reservations.put(orderId, new MarginReservationState(userId, asset, orderId, symbol,
                    MarginMode.defaultIfNull(marginMode), PositionSide.defaultIfNull(positionSide), amountUnits,
                    0L, 0L, lastQuantitySteps, false));
            return true;
        }

        @Override
        public boolean reserve(long userId,
                               String accountType,
                               String asset,
                               long orderId,
                               String symbol,
                               MarginMode marginMode,
                               long amountUnits,
                               Instant now) {
            assertThat(accountType).isEqualTo(state.productAccountType());
            return reserve(userId, accountType, asset, orderId, symbol, marginMode, PositionSide.NET, amountUnits, now);
        }

        @Override
        public boolean reserve(long userId,
                               String accountType,
                               String asset,
                               long orderId,
                               String symbol,
                               MarginMode marginMode,
                               PositionSide positionSide,
                               long amountUnits,
                               Instant now) {
            assertThat(accountType).isEqualTo(state.productAccountType());
            return reserve(userId, asset, orderId, symbol, marginMode, positionSide, amountUnits, now);
        }
    }

    private static final class FakeSpotOrderReservationRepository extends SpotOrderReservationRepository {
        private final SharedState state;

        private FakeSpotOrderReservationRepository(SharedState state) {
            super(null, null);
            this.state = state;
        }

        @Override
        public Optional<SpotReservationRequirement> requirement(String symbol,
                                                                long instrumentVersion,
                                                                OrderSide side,
                                                                OrderType orderType,
                                                                long priceTicks,
                                                                long quantitySteps,
                                                                long marketMaxSlippagePpm,
                                                                long marketMaxMarkAgeMs,
                                                                com.surprising.trading.order.model.OrderFeeSnapshot feeSnapshot) {
            assertThat(symbol).isEqualTo(SYMBOL);
            assertThat(instrumentVersion).isEqualTo(VERSION);
            if (side == OrderSide.SELL) {
                return Optional.of(new SpotReservationRequirement(state.baseAsset,
                        Math.multiplyExact(quantitySteps, state.quantityStepUnits)));
            }
            long notionalUnits = Math.multiplyExact(Math.multiplyExact(priceTicks, quantitySteps),
                    state.notionalMultiplierUnits);
            long feeRatePpm = Math.max(0L, Math.max(feeSnapshot.makerFeeRatePpm(), feeSnapshot.takerFeeRatePpm()));
            long feeUnits = feeRatePpm == 0L
                    ? 0L
                    : (Math.multiplyExact(notionalUnits, feeRatePpm) + 999_999L) / 1_000_000L;
            return Optional.of(new SpotReservationRequirement(state.quoteAsset,
                    Math.addExact(notionalUnits, feeUnits)));
        }

        @Override
        public boolean reserve(long userId,
                               String asset,
                               long orderId,
                               String symbol,
                               OrderSide side,
                               long amountUnits,
                               Instant now) {
            BalanceState balance = state.spotBalance(userId, asset);
            if (balance.availableUnits < amountUnits) {
                return false;
            }
            balance.availableUnits = Math.subtractExact(balance.availableUnits, amountUnits);
            balance.lockedUnits = Math.addExact(balance.lockedUnits, amountUnits);
            state.spotReservations.put(orderId, new SpotReservationState(userId, asset, side, amountUnits));
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

        public Optional<ReduceOnlyPosition> lockedPosition(long userId, String symbol) {
            return lockedPosition(userId, symbol, MarginMode.CROSS);
        }

        @Override
        public Optional<ReduceOnlyPosition> lockedPosition(long userId, String symbol, MarginMode marginMode) {
            return lockedPosition(state.productLine(), userId, symbol, marginMode, PositionSide.NET);
        }

        @Override
        public Optional<ReduceOnlyPosition> lockedPosition(ProductLine productLine,
                                                           long userId,
                                                           String symbol,
                                                           MarginMode marginMode,
                                                           PositionSide positionSide) {
            assertThat(productLine).isEqualTo(state.productLine());
            PositionState position = state.position(userId, marginMode, positionSide);
            return position.signedQuantitySteps() == 0
                    ? Optional.empty()
                    : Optional.of(new ReduceOnlyPosition(position.signedQuantitySteps(), position.instrumentVersion()));
        }

        public long lockedOpenReduceOnlySteps(long userId,
                                              String symbol,
                                              long instrumentVersion,
                                              OrderSide closeSide) {
            return lockedOpenReduceOnlySteps(userId, symbol, MarginMode.CROSS, instrumentVersion, closeSide);
        }

        @Override
        public long lockedOpenReduceOnlySteps(long userId,
                                              String symbol,
                                              MarginMode marginMode,
                                              long instrumentVersion,
                                              OrderSide closeSide) {
            return lockedOpenReduceOnlySteps(state.productLine(), userId, symbol, marginMode, instrumentVersion,
                    PositionSide.NET, closeSide);
        }

        @Override
        public long lockedOpenReduceOnlySteps(ProductLine productLine,
                                              long userId,
                                              String symbol,
                                              MarginMode marginMode,
                                              long instrumentVersion,
                                              PositionSide positionSide,
                                              OrderSide closeSide) {
            assertThat(productLine).isEqualTo(state.productLine());
            return state.orders.values().stream()
                    .filter(order -> order.productLine() == productLine)
                    .filter(order -> order.userId() == userId)
                    .filter(order -> order.symbol().equals(symbol))
                    .filter(order -> order.marginMode() == MarginMode.defaultIfNull(marginMode))
                    .filter(order -> order.positionSide() == PositionSide.defaultIfNull(positionSide))
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
        public boolean orderExists(long orderId) {
            return state.orders.containsKey(orderId);
        }

        @Override
        public long orderInstrumentVersion(long orderId) {
            return Optional.ofNullable(state.orders.get(orderId))
                    .map(OrderRecord::instrumentVersion)
                    .orElseThrow(() -> new IllegalStateException("missing order " + orderId));
        }

        @Override
        public MarginMode orderMarginMode(long orderId) {
            return Optional.ofNullable(state.orders.get(orderId))
                    .map(OrderRecord::marginMode)
                    .orElseThrow(() -> new IllegalStateException("missing order " + orderId));
        }

        @Override
        public PositionSide orderPositionSide(long orderId) {
            return Optional.ofNullable(state.orders.get(orderId))
                    .map(OrderRecord::positionSide)
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
                    remaining, order.marginMode(), order.positionSide(), order.makerFeeRatePpm(), order.takerFeeRatePpm(),
                    order.reduceOnly(), order.postOnly(), result.orderStatus(), order.rejectReason(),
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
                    remaining, order.marginMode(), order.positionSide(), order.makerFeeRatePpm(), order.takerFeeRatePpm(),
                    order.reduceOnly(), order.postOnly(), status, order.rejectReason(),
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
        public InstrumentType instrumentType(String symbol, long instrumentVersion) {
            return state.instrumentType;
        }

        @Override
        public SpotInstrumentSpec spotInstrumentSpec(String symbol, long instrumentVersion) {
            return new SpotInstrumentSpec(VERSION, state.baseAsset, state.quoteAsset,
                    state.quantityStepUnits, state.notionalMultiplierUnits);
        }

        @Override
        public OrderFeeSnapshot orderFeeSnapshot(long orderId, long userId, String symbol) {
            OrderRecord order = state.orders.get(orderId);
            if (order == null || order.userId() != userId || !symbol.equals(order.symbol())) {
                throw new IllegalStateException("missing order fee snapshot " + orderId);
            }
            return new OrderFeeSnapshot(order.makerFeeRatePpm(), order.takerFeeRatePpm());
        }

        @Override
        public Optional<LiquidationFeeContext> liquidationFeeContext(long orderId, long userId, String symbol) {
            LiquidationOrderResponse order = state.liquidationOrders.get(orderId);
            if (order == null || order.userId() != userId || !symbol.equals(order.symbol())) {
                return Optional.empty();
            }
            if (order.status() != LiquidationOrderStatus.SUBMITTED
                    && order.status() != LiquidationOrderStatus.PARTIALLY_FILLED
                    && order.status() != LiquidationOrderStatus.FILLED) {
                return Optional.empty();
            }
            return Optional.of(new LiquidationFeeContext(order.liquidationOrderId(), order.candidateId(),
                    order.liquidationFeeRatePpm()));
        }

        @Override
        public boolean markTradeProcessing(long tradeId, String symbol) {
            return state.processedTrades.add(tradeId);
        }

        @Override
        public boolean markTradeProcessing(ProductLine productLine, long tradeId, String symbol) {
            assertThat(productLine).isEqualTo(state.productLine());
            return state.processedTrades.add(tradeId);
        }

        @Override
        public long settlementMarkPriceTicks(String symbol,
                                             long instrumentVersion,
                                             Instant settlementTime,
                                             Duration priceWindow) {
            assertThat(symbol).isEqualTo(SYMBOL);
            assertThat(instrumentVersion).isEqualTo(VERSION);
            return state.markPriceTicks;
        }

        @Override
        public long settlementMarkPriceUnits(String symbol, Instant settlementTime, Duration priceWindow) {
            assertThat(symbol).isEqualTo(SYMBOL);
            return state.underlyingMarkPriceUnits;
        }

        @Override
        public List<PositionResponse> openPositionsForSettlement(String symbol, long instrumentVersion) {
            assertThat(symbol).isEqualTo(SYMBOL);
            assertThat(instrumentVersion).isEqualTo(VERSION);
            return state.positions.entrySet().stream()
                    .filter(entry -> entry.getKey().symbol().equals(symbol))
                    .filter(entry -> entry.getValue().instrumentVersion() == instrumentVersion)
                    .filter(entry -> entry.getValue().signedQuantitySteps() != 0L)
                    .map(entry -> new PositionResponse(entry.getKey().userId(), symbol,
                            entry.getValue().instrumentVersion(), entry.getKey().marginMode(),
                            entry.getKey().positionSide(), entry.getValue().signedQuantitySteps(),
                            entry.getValue().entryPriceTicks(), entry.getValue().realizedPnlUnits(), Instant.now()))
                    .sorted(Comparator.comparingLong(PositionResponse::userId))
                    .toList();
        }

        @Override
        public List<PositionResponse> openPositionsForSettlement(ProductLine productLine, String symbol) {
            assertThat(productLine).isEqualTo(state.productLine());
            assertThat(symbol).isEqualTo(SYMBOL);
            return state.positions.entrySet().stream()
                    .filter(entry -> entry.getKey().symbol().equals(symbol))
                    .filter(entry -> entry.getValue().signedQuantitySteps() != 0L)
                    .map(entry -> new PositionResponse(entry.getKey().userId(), symbol,
                            entry.getValue().instrumentVersion(), entry.getKey().marginMode(),
                            entry.getKey().positionSide(), entry.getValue().signedQuantitySteps(),
                            entry.getValue().entryPriceTicks(), entry.getValue().realizedPnlUnits(), Instant.now()))
                    .sorted(Comparator.comparingLong(PositionResponse::userId))
                    .toList();
        }

        @Override
        public PositionState lockPosition(long userId, String symbol, MarginMode marginMode) {
            return state.position(userId, marginMode);
        }

        @Override
        public PositionState lockPosition(ProductLine productLine,
                                          long userId,
                                          String symbol,
                                          MarginMode marginMode,
                                          PositionSide positionSide) {
            assertThat(productLine).isEqualTo(state.productLine());
            return state.position(userId, marginMode, positionSide);
        }

        @Override
        public PositionState lockPosition(long userId, String symbol, MarginMode marginMode, PositionSide positionSide) {
            return state.position(userId, marginMode, positionSide);
        }

        @Override
        public Optional<PositionResponse> position(long userId, String symbol, MarginMode marginMode) {
            return position(userId, symbol, marginMode, PositionSide.NET);
        }

        @Override
        public Optional<PositionResponse> position(long userId,
                                                   String symbol,
                                                   MarginMode marginMode,
                                                   PositionSide positionSide) {
            MarginMode normalizedMarginMode = MarginMode.defaultIfNull(marginMode);
            PositionSide normalizedPositionSide = PositionSide.defaultIfNull(positionSide);
            PositionState position = state.position(userId, normalizedMarginMode, normalizedPositionSide);
            return position.signedQuantitySteps() == 0
                    ? Optional.empty()
                    : Optional.of(new PositionResponse(userId, symbol, position.instrumentVersion(),
                    normalizedMarginMode, normalizedPositionSide, position.signedQuantitySteps(), position.entryPriceTicks(),
                    position.realizedPnlUnits(), Instant.now()));
        }

        @Override
        public Optional<PositionResponse> position(ProductLine productLine,
                                                   long userId,
                                                   String symbol,
                                                   MarginMode marginMode,
                                                   PositionSide positionSide) {
            assertThat(productLine).isEqualTo(state.productLine());
            return position(userId, symbol, marginMode, positionSide);
        }

        @Override
        public Optional<PositionMarginResponse> positionMargin(long userId, String symbol, MarginMode marginMode) {
            return positionMargin(userId, symbol, marginMode, PositionSide.NET);
        }

        @Override
        public Optional<PositionMarginResponse> positionMargin(long userId,
                                                               String symbol,
                                                               MarginMode marginMode,
                                                               PositionSide positionSide) {
            MarginMode normalizedMarginMode = MarginMode.defaultIfNull(marginMode);
            PositionSide normalizedPositionSide = PositionSide.defaultIfNull(positionSide);
            return Optional.of(new PositionMarginResponse(userId, symbol, state.settleAsset, normalizedMarginMode,
                    normalizedPositionSide, state.positionMargin(userId, normalizedMarginMode, normalizedPositionSide),
                    Instant.now()));
        }

        @Override
        public Optional<PositionMarginResponse> positionMargin(ProductLine productLine,
                                                               long userId,
                                                               String symbol,
                                                               MarginMode marginMode,
                                                               PositionSide positionSide) {
            assertThat(productLine).isEqualTo(state.productLine());
            return positionMargin(userId, symbol, marginMode, positionSide);
        }

        @Override
        public PositionMarginAdjustmentResponse adjustIsolatedPositionMargin(long userId,
                                                                             String symbol,
                                                                             long amountUnits,
                                                                             String referenceId,
                                                                             String reason,
                                                                             Duration maxRiskSnapshotAge,
                                                                             long removalBufferPpm) {
            return adjustIsolatedPositionMargin(userId, symbol, PositionSide.NET, amountUnits, referenceId, reason,
                    maxRiskSnapshotAge, removalBufferPpm);
        }

        @Override
        public PositionMarginAdjustmentResponse adjustIsolatedPositionMargin(ProductLine productLine,
                                                                             long userId,
                                                                             String symbol,
                                                                             long amountUnits,
                                                                             String referenceId,
                                                                             String reason,
                                                                             Duration maxRiskSnapshotAge,
                                                                             long removalBufferPpm) {
            assertThat(productLine).isEqualTo(state.productLine());
            return adjustIsolatedPositionMargin(userId, symbol, PositionSide.NET, amountUnits, referenceId, reason,
                    maxRiskSnapshotAge, removalBufferPpm);
        }

        @Override
        public PositionMarginAdjustmentResponse adjustIsolatedPositionMargin(ProductLine productLine,
                                                                             long userId,
                                                                             String symbol,
                                                                             PositionSide positionSide,
                                                                             long amountUnits,
                                                                             String referenceId,
                                                                             String reason,
                                                                             Duration maxRiskSnapshotAge,
                                                                             long removalBufferPpm) {
            assertThat(productLine).isEqualTo(state.productLine());
            return adjustIsolatedPositionMargin(userId, symbol, positionSide, amountUnits, referenceId, reason,
                    maxRiskSnapshotAge, removalBufferPpm);
        }

        @Override
        public PositionMarginAdjustmentResponse adjustIsolatedPositionMargin(long userId,
                                                                             String symbol,
                                                                             PositionSide positionSide,
                                                                             long amountUnits,
                                                                             String referenceId,
                                                                             String reason,
                                                                             Duration maxRiskSnapshotAge,
                                                                             long removalBufferPpm) {
            PositionSide normalizedPositionSide = PositionSide.defaultIfNull(positionSide);
            String key = userId + ":" + symbol + ":" + referenceId;
            PositionMarginAdjustmentResponse existing = state.positionMarginAdjustmentReferences.get(key);
            if (existing != null) {
                return existing;
            }
            PositionState position = state.position(userId, MarginMode.ISOLATED, normalizedPositionSide);
            if (position.signedQuantitySteps() == 0) {
                throw new IllegalStateException("open isolated position not found");
            }
            PositionKey positionKey = new PositionKey(userId, symbol, MarginMode.ISOLATED, normalizedPositionSide);
            BalanceState balance = state.balance(userId);
            long currentMargin = state.positionMargins.getOrDefault(positionKey, 0L);
            if (amountUnits > 0) {
                assertThat(balance.availableUnits).isGreaterThanOrEqualTo(amountUnits);
                balance.availableUnits = Math.subtractExact(balance.availableUnits, amountUnits);
                balance.lockedUnits = Math.addExact(balance.lockedUnits, amountUnits);
                currentMargin = Math.addExact(currentMargin, amountUnits);
            } else {
                long removeUnits = Math.absExact(amountUnits);
                assertThat(currentMargin).isGreaterThanOrEqualTo(removeUnits);
                currentMargin = Math.subtractExact(currentMargin, removeUnits);
                balance.availableUnits = Math.addExact(balance.availableUnits, removeUnits);
                balance.lockedUnits = Math.subtractExact(balance.lockedUnits, removeUnits);
            }
            if (currentMargin == 0L) {
                state.positionMargins.remove(positionKey);
            } else {
                state.positionMargins.put(positionKey, currentMargin);
            }
            PositionMarginAdjustmentResponse response = new PositionMarginAdjustmentResponse(userId, symbol,
                    state.settleAsset, MarginMode.ISOLATED, normalizedPositionSide, amountUnits, currentMargin,
                    balance.availableUnits, balance.lockedUnits,
                    balance.availableUnits + balance.lockedUnits - balance.deficitUnits, referenceId, Instant.now());
            state.positionMarginAdjustmentReferences.put(key, response);
            return response;
        }

        @Override
        public void consumeOrderMargin(long orderId,
                                       long userId,
                                       String symbol,
                                       MarginMode marginMode,
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
            state.positionMargins.merge(new PositionKey(userId, symbol, MarginMode.defaultIfNull(marginMode),
                    reservation.positionSide), actualMarginUnits, Long::sum);
            if (excess > 0) {
                reservation.releasedUnits += excess;
                releaseBalanceLock(userId, excess);
            }
        }

        @Override
        public void consumeOrderMargin(ProductLine productLine,
                                       long orderId,
                                       long userId,
                                       String symbol,
                                       MarginMode marginMode,
                                       long openSteps,
                                       long actualMarginUnits,
                                       boolean sweepRemainder,
                                       Instant now) {
            assertThat(productLine).isEqualTo(state.productLine());
            consumeOrderMargin(orderId, userId, symbol, marginMode, openSteps, actualMarginUnits, sweepRemainder, now);
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
                                          MarginMode marginMode,
                                          long closeSteps,
                                          long positionAbsSteps,
                                          Instant now) {
            releasePositionMargin(userId, symbol, marginMode, closeSteps, PositionSide.NET, positionAbsSteps, now);
        }

        @Override
        public void releasePositionMargin(long userId,
                                          String symbol,
                                          MarginMode marginMode,
                                          long closeSteps,
                                          PositionSide positionSide,
                                          long positionAbsSteps,
                                          Instant now) {
            PositionKey key = new PositionKey(userId, symbol, MarginMode.defaultIfNull(marginMode),
                    PositionSide.defaultIfNull(positionSide));
            long currentMargin = state.positionMargins.getOrDefault(key, 0L);
            long amount = MarginTransferMath.positionMarginReleaseAmount(currentMargin, closeSteps, positionAbsSteps);
            if (amount <= 0) {
                return;
            }
            state.positionMargins.put(key, currentMargin - amount);
            releaseBalanceLock(userId, amount);
        }

        @Override
        public void releasePositionMargin(ProductLine productLine,
                                          long userId,
                                          String symbol,
                                          MarginMode marginMode,
                                          long closeSteps,
                                          PositionSide positionSide,
                                          long positionAbsSteps,
                                          Instant now) {
            assertThat(productLine).isEqualTo(state.productLine());
            releasePositionMargin(userId, symbol, marginMode, closeSteps, positionSide, positionAbsSteps, now);
        }

        @Override
        public void settleRealizedPnl(long userId,
                                      String asset,
                                      long orderId,
                                      long tradeId,
                                      long realizedPnlDeltaUnits,
                                      Instant now) {
            settleRealizedPnl(userId, asset, orderId, tradeId, SYMBOL, MarginMode.CROSS, realizedPnlDeltaUnits, now);
        }

        @Override
        public void settleRealizedPnl(long userId,
                                      String asset,
                                      long orderId,
                                      long tradeId,
                                      String symbol,
                                      MarginMode marginMode,
                                      long realizedPnlDeltaUnits,
                                      Instant now) {
            applyBalanceSettlement(userId, symbol, marginMode, realizedPnlDeltaUnits);
        }

        @Override
        public void settleRealizedPnl(AccountType accountType,
                                      long userId,
                                      String asset,
                                      long orderId,
                                      long tradeId,
                                      String symbol,
                                      MarginMode marginMode,
                                      long realizedPnlDeltaUnits,
                                      Instant now) {
            assertThat(accountType).isEqualTo(expectedAccountType());
            settleRealizedPnl(userId, asset, orderId, tradeId, symbol, marginMode, realizedPnlDeltaUnits, now);
        }

        @Override
        public boolean settleLifecyclePnl(AccountType accountType,
                                          long userId,
                                          String asset,
                                          String referenceType,
                                          String referenceId,
                                          String reason,
                                          String symbol,
                                          MarginMode marginMode,
                                          long realizedPnlDeltaUnits,
                                          Instant now) {
            assertThat(accountType).isEqualTo(expectedAccountType());
            assertThat(asset).isEqualTo(state.settleAsset);
            assertThat(referenceType).isIn("DELIVERY_SETTLEMENT", "OPTION_EXERCISE");
            assertThat(reason).isEqualTo(referenceType);
            if (!state.lifecycleReferences.add(referenceId)) {
                return false;
            }
            applyBalanceSettlement(userId, symbol, marginMode, realizedPnlDeltaUnits);
            return true;
        }

        @Override
        public void settleOptionPremium(AccountType accountType,
                                        OrderSide side,
                                        long userId,
                                        String asset,
                                        long orderId,
                                        long tradeId,
                                        String symbol,
                                        MarginMode marginMode,
                                        long premiumUnits,
                                        boolean orderCompleted,
                                        Instant now) {
            assertThat(accountType).isEqualTo(AccountType.OPTION);
            assertThat(asset).isEqualTo(state.settleAsset);
            assertThat(symbol).isEqualTo(SYMBOL);
            if (side == OrderSide.BUY) {
                MarginReservationState reservation = state.reservations.get(orderId);
                assertThat(reservation).isNotNull();
                long unreleased = reservation.reservedUnits - reservation.releasedUnits - reservation.positionMarginUnits;
                assertThat(unreleased).isGreaterThanOrEqualTo(premiumUnits);
                BalanceState balance = state.balance(userId);
                assertThat(balance.lockedUnits).isGreaterThanOrEqualTo(premiumUnits);
                balance.lockedUnits = Math.subtractExact(balance.lockedUnits, premiumUnits);
                reservation.releasedUnits = Math.addExact(reservation.releasedUnits, premiumUnits);
                long remaining = reservation.reservedUnits - reservation.releasedUnits - reservation.positionMarginUnits;
                if (orderCompleted && remaining > 0L) {
                    reservation.releasedUnits = Math.addExact(reservation.releasedUnits, remaining);
                    releaseBalanceLock(userId, remaining);
                }
                return;
            }
            BalanceState balance = state.balance(userId);
            balance.availableUnits = Math.addExact(balance.availableUnits, premiumUnits);
        }

        @Override
        public void settleTradeFee(long userId,
                                   String asset,
                                   long orderId,
                                   long tradeId,
                                   long feeDeltaUnits,
                                   String reason,
                                   long feeRatePpm,
                                   String symbol,
                                   Instant now) {
            settleTradeFee(userId, asset, orderId, tradeId, feeDeltaUnits, reason, feeRatePpm, symbol,
                    MarginMode.CROSS, now);
        }

        @Override
        public void settleTradeFee(long userId,
                                   String asset,
                                   long orderId,
                                   long tradeId,
                                   long feeDeltaUnits,
                                   String reason,
                                   long feeRatePpm,
                                   String symbol,
                                   MarginMode marginMode,
                                   Instant now) {
            applyBalanceSettlement(userId, symbol, marginMode, feeDeltaUnits);
        }

        @Override
        public void settleTradeFee(AccountType accountType,
                                   long userId,
                                   String asset,
                                   long orderId,
                                   long tradeId,
                                   long feeDeltaUnits,
                                   String reason,
                                   long feeRatePpm,
                                   String symbol,
                                   MarginMode marginMode,
                                   Instant now) {
            assertThat(accountType).isEqualTo(expectedAccountType());
            settleTradeFee(userId, asset, orderId, tradeId, feeDeltaUnits, reason, feeRatePpm, symbol,
                    marginMode, now);
        }

        @Override
        public void settleSpotTradeSide(long userId,
                                        long orderId,
                                        long tradeId,
                                        String symbol,
                                        OrderSide side,
                                        long priceTicks,
                                        long quantitySteps,
                                        SpotInstrumentSpec spec,
                                        long feeRatePpm,
                                        String feeReason,
                                        boolean orderCompleted,
                                        Instant now) {
            SpotReservationState reservation = state.spotReservations.get(orderId);
            assertThat(reservation).isNotNull();
            assertThat(reservation.userId).isEqualTo(userId);
            assertThat(reservation.side).isEqualTo(side);
            long baseUnits = Math.multiplyExact(quantitySteps, spec.quantityStepUnits());
            long quoteUnits = Math.multiplyExact(Math.multiplyExact(priceTicks, quantitySteps),
                    spec.notionalMultiplierUnits());
            long feeUnits = spotFeeUnits(quoteUnits, feeRatePpm);
            long positiveFeeUnits = feeRatePpm > 0 ? feeUnits : 0L;
            long settledUnits = side == OrderSide.BUY
                    ? Math.addExact(quoteUnits, positiveFeeUnits)
                    : baseUnits;
            long remainingUnits = Math.subtractExact(reservation.reservedUnits,
                    Math.addExact(reservation.settledUnits, reservation.releasedUnits));
            assertThat(remainingUnits).isGreaterThanOrEqualTo(settledUnits);
            long releaseUnits = orderCompleted ? Math.subtractExact(remainingUnits, settledUnits) : 0L;
            if (side == OrderSide.BUY) {
                BalanceState quote = state.spotBalance(userId, spec.quoteAsset());
                quote.lockedUnits = Math.subtractExact(quote.lockedUnits, quoteUnits);
                if (positiveFeeUnits > 0) {
                    quote.lockedUnits = Math.subtractExact(quote.lockedUnits, positiveFeeUnits);
                } else if (feeUnits > 0) {
                    quote.availableUnits = Math.addExact(quote.availableUnits, feeUnits);
                }
                state.spotBalance(userId, spec.baseAsset()).availableUnits =
                        Math.addExact(state.spotBalance(userId, spec.baseAsset()).availableUnits, baseUnits);
                releaseSpotLock(userId, spec.quoteAsset(), releaseUnits);
            } else {
                BalanceState base = state.spotBalance(userId, spec.baseAsset());
                base.lockedUnits = Math.subtractExact(base.lockedUnits, baseUnits);
                BalanceState quote = state.spotBalance(userId, spec.quoteAsset());
                quote.availableUnits = Math.addExact(quote.availableUnits, quoteUnits);
                if (positiveFeeUnits > 0) {
                    quote.availableUnits = Math.subtractExact(quote.availableUnits, positiveFeeUnits);
                } else if (feeUnits > 0) {
                    quote.availableUnits = Math.addExact(quote.availableUnits, feeUnits);
                }
                releaseSpotLock(userId, spec.baseAsset(), releaseUnits);
            }
            reservation.settledUnits = Math.addExact(reservation.settledUnits, settledUnits);
            reservation.releasedUnits = Math.addExact(reservation.releasedUnits, releaseUnits);
        }

        @Override
        public Optional<LiquidationFeeSettlement> settleLiquidationFee(long userId,
                                                                      String asset,
                                                                      long orderId,
                                                                      long tradeId,
                                                                      String symbol,
                                                                      MarginMode marginMode,
                                                                      long requestedFeeUnits,
                                                                      LiquidationFeeContext context,
                                                                      Instant now) {
            if (requestedFeeUnits <= 0 || context == null || context.feeRatePpm() <= 0) {
                return Optional.empty();
            }
            String referenceId = tradeId + ":" + orderId;
            if (state.liquidationFeeReferences.contains(referenceId)) {
                return Optional.empty();
            }
            BalanceState current = state.balance(userId);
            MarginMode normalizedMarginMode = MarginMode.defaultIfNull(marginMode);
            long maxLockedDebit = matchingPositionMargins(userId, symbol, normalizedMarginMode).stream()
                    .mapToLong(Map.Entry::getValue)
                    .reduce(0L, Math::addExact);
            long availableInput = normalizedMarginMode == MarginMode.ISOLATED ? 0L : current.availableUnits;
            long collectibleUnits = Math.min(requestedFeeUnits, Math.addExact(availableInput, maxLockedDebit));
            if (collectibleUnits <= 0) {
                return Optional.empty();
            }
            BalanceSettlementState next = PnlSettlementMath.apply(availableInput, current.lockedUnits,
                    current.deficitUnits, Math.negateExact(collectibleUnits), maxLockedDebit);
            if (normalizedMarginMode == MarginMode.ISOLATED) {
                next = new BalanceSettlementState(current.availableUnits, next.lockedUnits(), next.deficitUnits());
            }
            assertThat(next.deficitUnits()).isEqualTo(current.deficitUnits);
            long lockedDebit = Math.subtractExact(current.lockedUnits, next.lockedUnits());
            if (lockedDebit > 0) {
                reducePositionMargins(userId, symbol, normalizedMarginMode, lockedDebit);
            }
            current.availableUnits = next.availableUnits();
            current.lockedUnits = next.lockedUnits();
            current.deficitUnits = next.deficitUnits();
            state.liquidationFeeReferences.add(referenceId);
            return Optional.of(new LiquidationFeeSettlement(context.liquidationOrderId(), context.candidateId(),
                    collectibleUnits, context.feeRatePpm()));
        }

        @Override
        public Optional<LiquidationFeeSettlement> settleLiquidationFee(AccountType accountType,
                                                                       long userId,
                                                                       String asset,
                                                                       long orderId,
                                                                       long tradeId,
                                                                       String symbol,
                                                                       MarginMode marginMode,
                                                                       long requestedFeeUnits,
                                                                       LiquidationFeeContext context,
                                                                       Instant now) {
            assertThat(accountType).isEqualTo(expectedAccountType());
            return settleLiquidationFee(userId, asset, orderId, tradeId, symbol, marginMode, requestedFeeUnits,
                    context, now);
        }

        private void applyBalanceSettlement(long userId, String symbol, MarginMode marginMode, long amountUnits) {
            BalanceState current = state.balance(userId);
            MarginMode normalizedMarginMode = MarginMode.defaultIfNull(marginMode);
            long maxLockedDebit = amountUnits < 0
                    ? matchingPositionMargins(userId, symbol, normalizedMarginMode).stream()
                    .mapToLong(Map.Entry::getValue)
                    .reduce(0L, Math::addExact)
                    : current.lockedUnits;
            long availableInput = amountUnits < 0 && normalizedMarginMode == MarginMode.ISOLATED
                    ? 0L
                    : current.availableUnits;
            BalanceSettlementState next = PnlSettlementMath.apply(availableInput, current.lockedUnits,
                    current.deficitUnits, amountUnits, maxLockedDebit);
            if (amountUnits < 0 && normalizedMarginMode == MarginMode.ISOLATED) {
                next = new BalanceSettlementState(current.availableUnits, next.lockedUnits(), next.deficitUnits());
            }
            long lockedDebit = Math.subtractExact(current.lockedUnits, next.lockedUnits());
            if (lockedDebit > 0) {
                reducePositionMargins(userId, symbol, normalizedMarginMode, lockedDebit);
            }
            current.availableUnits = next.availableUnits();
            current.lockedUnits = next.lockedUnits();
            current.deficitUnits = next.deficitUnits();
        }

        private List<Map.Entry<PositionKey, Long>> matchingPositionMargins(long userId,
                                                                           String symbol,
                                                                           MarginMode marginMode) {
            return state.positionMargins.entrySet().stream()
                    .filter(entry -> entry.getKey().userId() == userId)
                    .filter(entry -> entry.getKey().marginMode() == marginMode)
                    .filter(entry -> marginMode == MarginMode.CROSS || entry.getKey().symbol().equals(symbol))
                    .filter(entry -> entry.getValue() > 0)
                    .sorted(Map.Entry.comparingByKey(Comparator
                            .comparing(PositionKey::symbol)
                            .thenComparing(PositionKey::marginMode)
                            .thenComparing(PositionKey::positionSide)))
                    .toList();
        }

        private void reducePositionMargins(long userId, String symbol, MarginMode marginMode, long amountUnits) {
            long remaining = amountUnits;
            for (Map.Entry<PositionKey, Long> entry : matchingPositionMargins(userId, symbol, marginMode)) {
                if (remaining <= 0) {
                    break;
                }
                long debit = Math.min(entry.getValue(), remaining);
                long nextMargin = Math.subtractExact(entry.getValue(), debit);
                if (nextMargin == 0L) {
                    state.positionMargins.remove(entry.getKey());
                } else {
                    state.positionMargins.put(entry.getKey(), nextMargin);
                }
                remaining = Math.subtractExact(remaining, debit);
            }
            assertThat(remaining).isZero();
        }

        @Override
        public PositionResponse updatePosition(long userId,
                                               String symbol,
                                               MarginMode marginMode,
                                               PositionState next,
                                               Instant now) {
            return updatePosition(userId, symbol, marginMode, PositionSide.NET, next, now);
        }

        @Override
        public PositionResponse updatePosition(long userId,
                                               String symbol,
                                               MarginMode marginMode,
                                               PositionSide positionSide,
                                               PositionState next,
                                               Instant now) {
            PositionSide normalizedPositionSide = PositionSide.defaultIfNull(positionSide);
            state.putPosition(userId, marginMode, normalizedPositionSide, next);
            return new PositionResponse(userId, symbol, next.instrumentVersion(), MarginMode.defaultIfNull(marginMode),
                    normalizedPositionSide, next.signedQuantitySteps(), next.entryPriceTicks(),
                    next.realizedPnlUnits(), now);
        }

        @Override
        public PositionResponse updatePosition(long userId,
                                               String symbol,
                                               MarginMode marginMode,
                                               PositionState next,
                                               long previousSignedQuantitySteps,
                                               Instant now) {
            return updatePosition(userId, symbol, marginMode, next, now);
        }

        @Override
        public PositionResponse updatePosition(long userId,
                                               String symbol,
                                               MarginMode marginMode,
                                               PositionSide positionSide,
                                               PositionState next,
                                               long previousSignedQuantitySteps,
                                               Instant now) {
            return updatePosition(userId, symbol, marginMode, positionSide, next, now);
        }

        @Override
        public PositionResponse updatePosition(ProductLine productLine,
                                               long userId,
                                               String symbol,
                                               MarginMode marginMode,
                                               PositionSide positionSide,
                                               PositionState next,
                                               long previousSignedQuantitySteps,
                                               Instant now) {
            assertThat(productLine).isEqualTo(state.productLine());
            return updatePosition(userId, symbol, marginMode, positionSide, next, previousSignedQuantitySteps, now);
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

        private void releaseSpotLock(long userId, String asset, long amount) {
            if (amount <= 0) {
                return;
            }
            BalanceState balance = state.spotBalance(userId, asset);
            assertThat(balance.lockedUnits).isGreaterThanOrEqualTo(amount);
            balance.lockedUnits = Math.subtractExact(balance.lockedUnits, amount);
            balance.availableUnits = Math.addExact(balance.availableUnits, amount);
        }

        private long spotFeeUnits(long quoteUnits, long feeRatePpm) {
            if (feeRatePpm == 0L) {
                return 0L;
            }
            long numerator = Math.multiplyExact(quoteUnits, Math.absExact(feeRatePpm));
            return (numerator + 999_999L) / 1_000_000L;
        }

        private AccountType expectedAccountType() {
            return AccountType.valueOf(state.productAccountType());
        }
    }

    private static final class FakeAccountOutboxRepository extends AccountOutboxRepository {
        private final SharedState state;
        private final List<PositionUpdatedEvent> positionEvents = new ArrayList<>();
        private final List<LiquidationFeeSettledEvent> liquidationFeeEvents = new ArrayList<>();

        private FakeAccountOutboxRepository(SharedState state) {
            super(null, null, null);
            this.state = state;
        }

        @Override
        public PositionUpdatedEvent enqueuePositionUpdated(String topic,
                                                           long tradeId,
                                                           PositionResponse position,
                                                           Instant now,
                                                           String traceId) {
            PositionUpdatedEvent event = new PositionUpdatedEvent(state.next("account-position-event"), tradeId,
                    position.userId(), position.symbol(), position.instrumentVersion(), position.marginMode(),
                    position.positionSide(), position.signedQuantitySteps(), position.entryPriceTicks(),
                    position.realizedPnlUnits(), now, traceId);
            positionEvents.add(event);
            return event;
        }

        @Override
        public LiquidationFeeSettledEvent enqueueLiquidationFeeSettled(String topic,
                                                                       long tradeId,
                                                                       long orderId,
                                                                       long liquidationOrderId,
                                                                       long candidateId,
                                                                       long userId,
                                                                       String symbol,
                                                                       MarginMode marginMode,
                                                                       String accountType,
                                                                       String asset,
                                                                       long amountUnits,
                                                                       long feeRatePpm,
                                                                       Instant now,
                                                                       String traceId) {
            LiquidationFeeSettledEvent event = new LiquidationFeeSettledEvent(
                    state.next("account-liquidation-fee-event"), tradeId, orderId, liquidationOrderId, candidateId,
                    userId, symbol, marginMode, accountType, asset, amountUnits, feeRatePpm, now, traceId);
            liquidationFeeEvents.add(event);
            return event;
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
                        return new CalculatedPositionRisk(entry.getKey().userId(), SYMBOL,
                                entry.getKey().marginMode(), entry.getKey().positionSide(),
                                position.instrumentVersion(), state.settleAsset, signed, position.entryPriceTicks(),
                                state.markPriceTicks, notional, pnl, maintenance,
                                state.positionMargin(entry.getKey().userId(), entry.getKey().marginMode(),
                                        entry.getKey().positionSide()));
                    })
                    .toList();
        }

        @Override
        public List<RiskGroupKey> riskGroups(Duration maxMarkAge, RiskGroupKey after, int limit) {
            return calculatePositions(maxMarkAge).stream()
                    .map(position -> new RiskGroupKey(position.userId(), position.settleAsset()))
                    .distinct()
                    .sorted(Comparator.comparingLong(RiskGroupKey::userId)
                            .thenComparing(RiskGroupKey::accountType)
                            .thenComparing(RiskGroupKey::settleAsset))
                    .filter(key -> after == null || key.userId() > after.userId()
                            || (key.userId() == after.userId()
                            && key.accountType().compareTo(after.accountType()) > 0)
                            || (key.userId() == after.userId()
                            && key.accountType().equals(after.accountType())
                            && key.settleAsset().compareTo(after.settleAsset()) > 0))
                    .limit(limit)
                    .toList();
        }

        @Override
        public List<CalculatedPositionRisk> calculatePositions(RiskGroupKey key, Duration maxMarkAge) {
            return calculatePositions(maxMarkAge).stream()
                    .filter(position -> position.userId() == key.userId()
                            && position.settleAsset().equals(key.settleAsset()))
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
        public long walletBalanceUnits(long userId, String accountType, String settleAsset) {
            return walletBalanceUnits(userId, settleAsset);
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
            return createLiquidationCandidate(account, position, positionStatus, positionMarginRatioPpm,
                    account.equityUnits(), candidateId, now);
        }

        @Override
        public long createLiquidationCandidate(RiskAccountSnapshotResponse account,
                                               CalculatedPositionRisk position,
                                               RiskStatus positionStatus,
                                               long positionMarginRatioPpm,
                                               long equityUnits,
                                               long candidateId,
                                               Instant now) {
            boolean exists = candidates.values().stream()
                    .anyMatch(candidate -> candidate.userId() == position.userId()
                            && candidate.symbol().equals(position.symbol())
                            && candidate.marginMode() == position.marginMode()
                            && (candidate.status() == LiquidationCandidateStatus.NEW
                            || candidate.status() == LiquidationCandidateStatus.PROCESSING));
            if (exists) {
                return 0L;
            }
            LiquidationCandidateResponse candidate = new LiquidationCandidateResponse(candidateId,
                    account.snapshotId(), position.userId(), position.symbol(), position.marginMode(),
                    position.positionSide(), position.instrumentVersion(), position.settleAsset(),
                    position.signedQuantitySteps(), position.markPriceTicks(), equityUnits, position.maintenanceMarginUnits(),
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
        public Optional<RiskAccountSnapshotResponse> latestAccount(long userId, String accountType, String settleAsset) {
            return latestAccount(userId, settleAsset);
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
            long equity = equity(2202L, position);
            long maintenance = maintenance(position);
            return Optional.of(new ClaimedCandidate(candidateId, 1L, 2202L, SYMBOL, MarginMode.CROSS,
                    PositionSide.NET, VERSION, state.settleAsset, position.signedQuantitySteps(),
                    state.markPriceTicks, equity, maintenance, Long.MAX_VALUE));
        }

        @Override
        public RiskStatus latestRiskStatus(long userId, String settleAsset, java.time.Duration maxSnapshotAge) {
            return RiskStatus.LIQUIDATION;
        }

        @Override
        public RiskStatus latestRiskStatus(long userId,
                                           String accountType,
                                           String settleAsset,
                                           java.time.Duration maxSnapshotAge) {
            return RiskStatus.LIQUIDATION;
        }

        @Override
        public RiskStatus latestRiskStatus(long userId,
                                           String symbol,
                                           MarginMode marginMode,
                                           long instrumentVersion,
                                           java.time.Duration maxSnapshotAge) {
            return RiskStatus.LIQUIDATION;
        }

        @Override
        public RiskStatus latestRiskStatus(long userId,
                                           String symbol,
                                           MarginMode marginMode,
                                           PositionSide positionSide,
                                           long instrumentVersion,
                                           java.time.Duration maxSnapshotAge) {
            return RiskStatus.LIQUIDATION;
        }

        @Override
        public Optional<LiquidationCloseState> lockCloseState(long userId, String symbol, long instrumentVersion) {
            return lockCloseState(userId, symbol, MarginMode.CROSS, instrumentVersion);
        }

        @Override
        public Optional<LiquidationCloseState> lockCloseState(long userId,
                                                             String symbol,
                                                             MarginMode marginMode,
                                                             long instrumentVersion) {
            return Optional.of(new LiquidationCloseState(state.position(userId).signedQuantitySteps()));
        }

        @Override
        public Optional<LiquidationCloseState> lockCloseState(long userId,
                                                             String symbol,
                                                             MarginMode marginMode,
                                                             PositionSide positionSide,
                                                             long instrumentVersion) {
            return Optional.of(new LiquidationCloseState(
                    state.position(userId, marginMode, positionSide).signedQuantitySteps()));
        }

        @Override
        public long lockOpenReduceOnlySteps(long userId, String symbol, long instrumentVersion, OrderSide closeSide) {
            return lockOpenReduceOnlySteps(userId, symbol, MarginMode.CROSS, instrumentVersion, closeSide);
        }

        @Override
        public long lockOpenReduceOnlySteps(long userId,
                                            String symbol,
                                            MarginMode marginMode,
                                            long instrumentVersion,
                                            OrderSide closeSide) {
            return 0L;
        }

        @Override
        public long lockOpenReduceOnlySteps(long userId,
                                            String symbol,
                                            MarginMode marginMode,
                                            PositionSide positionSide,
                                            long instrumentVersion,
                                            OrderSide closeSide) {
            return 0L;
        }

        @Override
        public Optional<LiquidationSizingInput> sizingInput(long userId,
                                                           String symbol,
                                                           long instrumentVersion,
                                                           long availableCloseSteps) {
            return sizingInput(userId, symbol, MarginMode.CROSS, instrumentVersion, availableCloseSteps);
        }

        @Override
        public Optional<LiquidationSizingInput> sizingInput(long userId,
                                                           String symbol,
                                                           MarginMode marginMode,
                                                           long instrumentVersion,
                                                           long availableCloseSteps) {
            return sizingInput(userId, symbol, marginMode, PositionSide.NET, instrumentVersion, availableCloseSteps);
        }

        @Override
        public Optional<LiquidationSizingInput> sizingInput(long userId,
                                                           String symbol,
                                                           MarginMode marginMode,
                                                           PositionSide positionSide,
                                                           long instrumentVersion,
                                                           long availableCloseSteps) {
            PositionState position = state.position(userId, marginMode, positionSide);
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
        public Optional<LiquidationPricingInput> latestPricingInput(long userId,
                                                                    String symbol,
                                                                    MarginMode marginMode,
                                                                    long instrumentVersion,
                                                                    java.time.Duration maxSnapshotAge) {
            return latestPricingInput(userId, symbol, marginMode, PositionSide.NET, instrumentVersion, maxSnapshotAge);
        }

        @Override
        public Optional<LiquidationPricingInput> latestPricingInput(long userId,
                                                                    String symbol,
                                                                    MarginMode marginMode,
                                                                    PositionSide positionSide,
                                                                    long instrumentVersion,
                                                                    java.time.Duration maxSnapshotAge) {
            PositionState position = state.position(userId, marginMode, positionSide);
            if (position.signedQuantitySteps() == 0) {
                return Optional.empty();
            }
            return Optional.of(new LiquidationPricingInput(state.contractType, position.signedQuantitySteps(),
                    state.markPriceTicks, equity(userId, position), maintenance(position),
                    state.notionalMultiplierUnits, state.priceTickUnits, state.settleScaleUnits));
        }

        private long equity(long userId, PositionState position) {
            BalanceState balance = state.balance(userId);
            long wallet = balance.availableUnits + balance.lockedUnits - balance.deficitUnits;
            long unrealized = position.signedQuantitySteps() == 0
                    ? 0L
                    : PerpetualContractMath.unrealizedPnlUnits(state.contractType, position.signedQuantitySteps(),
                    position.entryPriceTicks(), state.markPriceTicks, state.notionalMultiplierUnits,
                    state.priceTickUnits, state.settleScaleUnits);
            return wallet + unrealized;
        }

        private long maintenance(PositionState position) {
            if (position.signedQuantitySteps() == 0) {
                return 0L;
            }
            return PerpetualContractMath.maintenanceMarginUnits(state.contractType, position.signedQuantitySteps(),
                    state.markPriceTicks, state.notionalMultiplierUnits, state.priceTickUnits,
                    state.settleScaleUnits, state.maintenanceMarginRatePpm);
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
            return insertLiquidationOrder(liquidationOrderId, candidateId, orderId, userId, symbol, MarginMode.CROSS,
                    side, quantitySteps, status, reason, now);
        }

        @Override
        public boolean insertLiquidationOrder(long liquidationOrderId,
                                              long candidateId,
                                              long orderId,
                                              long userId,
                                              String symbol,
                                              MarginMode marginMode,
                                              OrderSide side,
                                              long quantitySteps,
                                              LiquidationOrderStatus status,
                                              String reason,
                                              LiquidationPricingDecision pricing,
                                              Instant now) {
            return insertLiquidationOrder(liquidationOrderId, candidateId, orderId, userId, symbol, marginMode,
                    PositionSide.NET, side, quantitySteps, status, reason, pricing, now);
        }

        @Override
        public boolean insertLiquidationOrder(long liquidationOrderId,
                                              long candidateId,
                                              long orderId,
                                              long userId,
                                              String symbol,
                                              MarginMode marginMode,
                                              PositionSide positionSide,
                                              OrderSide side,
                                              long quantitySteps,
                                              LiquidationOrderStatus status,
                                              String reason,
                                              LiquidationPricingDecision pricing,
                                              Instant now) {
            LiquidationPricingDecision auditPricing = pricing == null ? LiquidationPricingDecision.empty() : pricing;
            LiquidationOrderResponse order = new LiquidationOrderResponse(liquidationOrderId, candidateId,
                    orderId, userId, symbol, marginMode, PositionSide.defaultIfNull(positionSide), side, quantitySteps,
                    auditPricing.bankruptcyPriceTicks(), auditPricing.takeoverPriceTicks(),
                    auditPricing.liquidationFeeRatePpm(), auditPricing.liquidationFeeUnits(), status, reason, now);
            orders.add(order);
            state.liquidationOrders.put(orderId, order);
            return true;
        }

        @Override
        public Optional<Long> updateOrderLifecycle(long orderId,
                                                   LiquidationOrderStatus orderStatus,
                                                   String candidateStatus) {
            for (int i = 0; i < orders.size(); i++) {
                LiquidationOrderResponse order = orders.get(i);
                if (order.orderId() == orderId
                        && (order.status() == LiquidationOrderStatus.SUBMITTED
                        || order.status() == LiquidationOrderStatus.PARTIALLY_FILLED)) {
                    LiquidationOrderResponse updated = new LiquidationOrderResponse(
                            order.liquidationOrderId(), order.candidateId(),
                            order.orderId(), order.userId(), order.symbol(), order.marginMode(), order.positionSide(),
                            order.side(), order.quantitySteps(), order.bankruptcyPriceTicks(), order.takeoverPriceTicks(),
                            order.liquidationFeeRatePpm(), order.liquidationFeeUnits(), orderStatus,
                            order.reason(), order.createdAt());
                    orders.set(i, updated);
                    state.liquidationOrders.put(order.orderId(), updated);
                    if ("PROCESSING".equals(statuses.get(order.candidateId()))) {
                        statuses.put(order.candidateId(), candidateStatus);
                    }
                    return Optional.of(order.candidateId());
                }
            }
            return Optional.empty();
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
            return cancelOpenReduceOnlyCloseOrders(userId, symbol, MarginMode.CROSS, instrumentVersion, closeSide,
                    now, serializer);
        }

        @Override
        public int cancelOpenReduceOnlyCloseOrders(long userId,
                                                   String symbol,
                                                   MarginMode marginMode,
                                                   long instrumentVersion,
                                                   OrderSide closeSide,
                                                   Instant now,
                                                   Function<Object, String> serializer) {
            return cancelOpenReduceOnlyCloseOrders(userId, symbol, marginMode, PositionSide.NET, instrumentVersion,
                    closeSide, now, serializer);
        }

        @Override
        public int cancelOpenReduceOnlyCloseOrders(long userId,
                                                   String symbol,
                                                   MarginMode marginMode,
                                                   PositionSide positionSide,
                                                   long instrumentVersion,
                                                   OrderSide closeSide,
                                                   Instant now,
                                                   Function<Object, String> serializer) {
            PositionSide normalizedPositionSide = PositionSide.defaultIfNull(positionSide);
            List<OrderRecord> openReduceOnlyOrders = state.orders.values().stream()
                    .filter(order -> order.productLine() == state.productLine())
                    .filter(order -> order.userId() == userId)
                    .filter(order -> symbol.equals(order.symbol()))
                    .filter(order -> order.marginMode() == MarginMode.defaultIfNull(marginMode))
                    .filter(order -> order.positionSide() == normalizedPositionSide)
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
                    state.orders.put(order.orderId(), new OrderRecord(order.orderId(), order.productLine(),
                            order.userId(),
                            order.clientOrderId(), order.symbol(), order.instrumentVersion(), order.side(),
                            order.orderType(), order.timeInForce(), order.priceTicks(), order.quantitySteps(),
                            order.executedQuantitySteps(), order.remainingQuantitySteps(), order.marginMode(),
                            order.positionSide(), order.makerFeeRatePpm(), order.takerFeeRatePpm(),
                            order.reduceOnly(), order.postOnly(), OrderStatus.CANCEL_REQUESTED,
                            "LIQUIDATION_PREEMPTED_REDUCE_ONLY", order.createdAt(), now));
                }
                commands.add(new OrderCommandEvent(OrderCommandType.CANCEL, state.next("trading-command"),
                        order.orderId(), order.userId(), order.clientOrderId(), order.symbol(),
                        order.instrumentVersion(), order.side(), order.orderType(), order.timeInForce(),
                        order.priceTicks(), order.quantitySteps(), order.marginMode(), order.positionSide(),
                        order.reduceOnly(), order.postOnly(), now, null));
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
            return createReduceOnlyMarketOrder(candidateId, userId, symbol, MarginMode.CROSS, instrumentVersion, side,
                    quantitySteps, now, serializer);
        }

        @Override
        public OrderCommandEvent createReduceOnlyMarketOrder(long candidateId,
                                                             long userId,
                                                             String symbol,
                                                             MarginMode marginMode,
                                                             long instrumentVersion,
                                                             OrderSide side,
                                                             long quantitySteps,
                                                             Instant now,
                                                             Function<Object, String> serializer) {
            return createReduceOnlyMarketOrder(candidateId, userId, symbol, marginMode, PositionSide.NET,
                    instrumentVersion, side, quantitySteps, now, serializer);
        }

        @Override
        public OrderCommandEvent createReduceOnlyMarketOrder(long candidateId,
                                                             long userId,
                                                             String symbol,
                                                             MarginMode marginMode,
                                                             PositionSide positionSide,
                                                             long instrumentVersion,
                                                             OrderSide side,
                                                             long quantitySteps,
                                                             Instant now,
                                                             Function<Object, String> serializer) {
            long orderId = state.next("trading-order");
            long commandId = state.next("trading-command");
            OrderRecord order = new OrderRecord(orderId, state.productLine(), userId, "LIQ-" + candidateId, symbol,
                    instrumentVersion,
                    side, OrderType.MARKET, TimeInForce.IOC, 0L, quantitySteps, 0L, quantitySteps,
                    MarginMode.defaultIfNull(marginMode), PositionSide.defaultIfNull(positionSide),
                    state.makerFeeRatePpm, state.takerFeeRatePpm,
                    true, false, OrderStatus.ACCEPTED, null, now, now);
            state.orders.put(orderId, order);
            OrderCommandEvent command = new OrderCommandEvent(OrderCommandType.PLACE, commandId, orderId, userId,
                    order.clientOrderId(), symbol, instrumentVersion, side, OrderType.MARKET, TimeInForce.IOC,
                    0L, quantitySteps, order.marginMode(), order.positionSide(), true, false, now, null);
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

    private record PositionKey(long userId, String symbol, MarginMode marginMode, PositionSide positionSide) {
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
        private final MarginMode marginMode;
        private final PositionSide positionSide;
        private final long reservedUnits;
        private long releasedUnits;
        private long positionMarginUnits;
        private final long orderQuantitySteps;
        private final boolean reduceOnly;

        private MarginReservationState(long userId,
                                       String asset,
                                       long orderId,
                                       String symbol,
                                       MarginMode marginMode,
                                       PositionSide positionSide,
                                       long reservedUnits,
                                       long releasedUnits,
                                       long positionMarginUnits,
                                       long orderQuantitySteps,
                                       boolean reduceOnly) {
            this.userId = userId;
            this.asset = asset;
            this.orderId = orderId;
            this.symbol = symbol;
            this.marginMode = MarginMode.defaultIfNull(marginMode);
            this.positionSide = PositionSide.defaultIfNull(positionSide);
            this.reservedUnits = reservedUnits;
            this.releasedUnits = releasedUnits;
            this.positionMarginUnits = positionMarginUnits;
            this.orderQuantitySteps = orderQuantitySteps;
            this.reduceOnly = reduceOnly;
        }
    }

    private static final class SpotReservationState {
        private final long userId;
        private final String asset;
        private final OrderSide side;
        private final long reservedUnits;
        private long settledUnits;
        private long releasedUnits;

        private SpotReservationState(long userId, String asset, OrderSide side, long reservedUnits) {
            this.userId = userId;
            this.asset = asset;
            this.side = side;
            this.reservedUnits = reservedUnits;
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
