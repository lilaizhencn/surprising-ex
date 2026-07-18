package com.surprising.trading.matching.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.account.api.model.AccountUserCommand;
import com.surprising.account.api.model.AccountUserCommandType;
import com.surprising.account.api.model.TradeParticipantRole;
import com.surprising.account.api.model.TradeSideSettlementCommand;
import com.surprising.trading.api.model.MatchResultEvent;
import com.surprising.trading.api.model.MatchTradeEvent;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.OrderBookDepthEvent;
import com.surprising.trading.api.model.OrderBookSnapshotResponse;
import com.surprising.trading.api.model.OrderCommandEvent;
import com.surprising.trading.api.model.OrderCommandType;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.OrderStatus;
import com.surprising.trading.api.model.OrderType;
import com.surprising.trading.api.model.PositionSide;
import com.surprising.trading.api.model.PublicTradeEvent;
import com.surprising.trading.api.model.TimeInForce;
import com.surprising.trading.matching.config.MatchingProperties;
import com.surprising.trading.matching.model.InstrumentSymbol;
import com.surprising.trading.matching.model.MatchedOrderSnapshot;
import com.surprising.trading.matching.model.MatchingSymbol;
import com.surprising.trading.matching.model.RecoveredOrderBookOrder;
import com.surprising.trading.matching.repository.MatchingOrderBookRecoveryRepository;
import com.surprising.trading.matching.repository.MatchingOutboxRepository;
import com.surprising.trading.matching.repository.MatchingProtectionRepository;
import com.surprising.trading.matching.repository.MatchingResultRepository;
import com.surprising.trading.matching.repository.MatchingSequenceRepository;
import com.surprising.trading.matching.repository.MatchingSymbolRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class MatchingServiceTest {

    @Test
    void isolatesPublicTradeFailureWhileCompletingFinancialMatchProcessing() throws Exception {
        MatchingProperties properties = new MatchingProperties();
        properties.getEngine().setExchangeId("matching-service-test");
        properties.getRecovery().setOpenOrderBookRestoreEnabled(false);
        properties.getProtection().setSelfTradePreventionEnabled(false);

        MatchingSymbol matchingSymbol = new MatchingSymbol("BTC-USDT", 301, 11, 12);
        InstrumentSymbol instrument = new InstrumentSymbol("BTC-USDT", "BTC", "USDT", "USDT");
        ExchangeCoreEngine engine = new ExchangeCoreEngine(properties,
                new FakeMatchingSymbolRepository(instrument, matchingSymbol),
                new FakeRecoveryRepository());
        FakeSequenceRepository sequenceRepository = new FakeSequenceRepository();
        FakeResultRepository resultRepository = new FakeResultRepository();
        FakeOutboxRepository outboxRepository = new FakeOutboxRepository();
        FakeDepthPublisher depthPublisher = new FakeDepthPublisher();
        FakePublicTradePublisher tradePublisher = new FakePublicTradePublisher(true);
        MatchingService service = new MatchingService(new ObjectMapper(), properties, engine,
                new FakeProtectionRepository(), sequenceRepository, resultRepository, outboxRepository,
                depthPublisher, tradePublisher);

        try {
            engine.start();

            OrderCommandEvent maker = new OrderCommandEvent(OrderCommandType.PLACE, 501L, 101L, 1001L,
                    "maker-101", "BTC-USDT", 5L, OrderSide.SELL, OrderType.LIMIT, TimeInForce.GTC,
                    100L, 10L, 2L, 5L, false, false, Instant.parse("2026-07-01T00:00:00Z"),
                    "trace-maker-501");
            OrderCommandEvent taker = new OrderCommandEvent(OrderCommandType.PLACE, 502L, 202L, 2002L,
                    "taker-202", "BTC-USDT", 7L, OrderSide.BUY, OrderType.LIMIT, TimeInForce.IOC,
                    100L, 3L, 2L, 5L, false, false, Instant.parse("2026-07-01T00:00:01Z"),
                    "trace-taker-502");

            service.process(maker);
            service.process(taker);
            service.process(taker);

            assertThat(resultRepository.results).hasSize(2);
            assertThat(resultRepository.results.get(0).orderStatus()).isEqualTo(OrderStatus.ACCEPTED);
            assertThat(resultRepository.results.get(0).traceId()).isEqualTo("trace-maker-501");
            assertThat(resultRepository.results.get(1).orderStatus()).isEqualTo(OrderStatus.FILLED);
            assertThat(resultRepository.results.get(1).traceId()).isEqualTo("trace-taker-502");
            assertThat(resultRepository.trades).hasSize(1);

            MatchTradeEvent trade = resultRepository.trades.get(0);
            assertThat(trade.tradeId()).isEqualTo(1L);
            assertThat(trade.commandId()).isEqualTo(502L);
            assertThat(trade.takerOrderId()).isEqualTo(202L);
            assertThat(trade.takerInstrumentVersion()).isEqualTo(7L);
            assertThat(trade.takerUserId()).isEqualTo(2002L);
            assertThat(trade.takerSide()).isEqualTo(OrderSide.BUY);
            assertThat(trade.takerMarginMode()).isEqualTo(MarginMode.CROSS);
            assertThat(trade.takerPositionSide()).isEqualTo(PositionSide.NET);
            assertThat(trade.makerOrderId()).isEqualTo(101L);
            assertThat(trade.makerInstrumentVersion()).isEqualTo(5L);
            assertThat(trade.makerUserId()).isEqualTo(1001L);
            assertThat(trade.makerMarginMode()).isEqualTo(MarginMode.CROSS);
            assertThat(trade.makerPositionSide()).isEqualTo(PositionSide.NET);
            assertThat(trade.takerFeeRatePpm()).isEqualTo(5L);
            assertThat(trade.makerFeeRatePpm()).isEqualTo(2L);
            assertThat(trade.priceTicks()).isEqualTo(100L);
            assertThat(trade.quantitySteps()).isEqualTo(3L);
            assertThat(trade.traceId()).isEqualTo("trace-taker-502");
            assertThat(outboxRepository.records)
                    .extracting(OutboxRecord::aggregateType)
                    .containsExactly("MATCH_RESULT", "ACCOUNT_COMMAND", "ACCOUNT_COMMAND",
                            "ACCOUNT_COMMAND", "MATCH_RESULT");
            AccountUserCommand takerSettlement = new ObjectMapper().readValue(
                    outboxRepository.records.get(1).payload(), AccountUserCommand.class);
            AccountUserCommand makerSettlement = new ObjectMapper().readValue(
                    outboxRepository.records.get(2).payload(), AccountUserCommand.class);
            AccountUserCommand takerRelease = new ObjectMapper().readValue(
                    outboxRepository.records.get(3).payload(), AccountUserCommand.class);
            assertThat(takerSettlement.commandType()).isEqualTo(AccountUserCommandType.TRADE_SIDE_SETTLE);
            assertThat(takerSettlement.partitionKey()).isEqualTo("LINEAR_PERPETUAL:2002");
            assertThat(new ObjectMapper().readValue(
                    takerSettlement.payload(), TradeSideSettlementCommand.class).participantRole())
                    .isEqualTo(TradeParticipantRole.TAKER);
            assertThat(makerSettlement.commandType()).isEqualTo(AccountUserCommandType.TRADE_SIDE_SETTLE);
            assertThat(makerSettlement.partitionKey()).isEqualTo("LINEAR_PERPETUAL:1001");
            assertThat(new ObjectMapper().readValue(
                    makerSettlement.payload(), TradeSideSettlementCommand.class).participantRole())
                    .isEqualTo(TradeParticipantRole.MAKER);
            assertThat(takerRelease.commandType()).isEqualTo(AccountUserCommandType.ORDER_RELEASE);
            assertThat(takerRelease.dependsOnCommandId()).isEqualTo(takerSettlement.commandId());
            assertThat(tradePublisher.events).singleElement().satisfies(publicTrade -> {
                assertThat(publicTrade.tradeId()).isEqualTo("502:1");
                assertThat(publicTrade.sequence()).isEqualTo(502_000_001L);
                assertThat(publicTrade.symbol()).isEqualTo("BTC-USDT");
                assertThat(publicTrade.instrumentVersion()).isEqualTo(7L);
                assertThat(publicTrade.takerSide()).isEqualTo(OrderSide.BUY);
                assertThat(publicTrade.priceTicks()).isEqualTo(100L);
                assertThat(publicTrade.quantitySteps()).isEqualTo(3L);
                assertThat(publicTrade.traceId()).isEqualTo("trace-taker-502");
            });
            assertThat(depthPublisher.events).hasSize(2);
            OrderBookDepthEvent depth = depthPublisher.events.get(1);
            assertThat(depth.symbol()).isEqualTo("BTC-USDT");
            assertThat(depth.sequence()).isEqualTo(2L);
            assertThat(depth.previousSequence()).isEqualTo(1L);
            assertThat(depth.updateType().name()).isEqualTo("SNAPSHOT");
            assertThat(depth.bids()).isEmpty();
            assertThat(depth.asks()).singleElement().satisfies(level -> {
                assertThat(level.priceTicks()).isEqualTo(100L);
                assertThat(level.quantitySteps()).isEqualTo(7L);
                assertThat(level.orderCount()).isEqualTo(1L);
            });

            OrderBookSnapshotResponse snapshot = service.orderBookSnapshot("BTC-USDT", 10);
            assertThat(snapshot.symbol()).isEqualTo("BTC-USDT");
            assertThat(snapshot.sequence()).isEqualTo(2L);
            assertThat(snapshot.depth()).isEqualTo(10);
            assertThat(snapshot.bids()).isEmpty();
            assertThat(snapshot.asks()).singleElement().satisfies(level -> {
                assertThat(level.priceTicks()).isEqualTo(100L);
                assertThat(level.quantitySteps()).isEqualTo(7L);
                assertThat(level.orderCount()).isEqualTo(1L);
            });
        } finally {
            engine.stop();
        }
    }

    @Test
    void skipsCommandWhenOrderIsMissingFromDatabase() throws Exception {
        MatchingProperties properties = new MatchingProperties();
        properties.getEngine().setExchangeId("matching-service-missing-order-test");
        properties.getRecovery().setOpenOrderBookRestoreEnabled(false);
        MatchingSymbol matchingSymbol = new MatchingSymbol("BTC-USDT", 301, 11, 12);
        InstrumentSymbol instrument = new InstrumentSymbol("BTC-USDT", "BTC", "USDT", "USDT");
        ExchangeCoreEngine engine = new ExchangeCoreEngine(properties,
                new FakeMatchingSymbolRepository(instrument, matchingSymbol),
                new FakeRecoveryRepository());
        FakeResultRepository resultRepository = new FakeResultRepository();
        resultRepository.missingOrderIds.add(404L);
        MatchingService service = new MatchingService(new ObjectMapper(), properties, engine,
                new FakeProtectionRepository(), new FakeSequenceRepository(), resultRepository,
                new FakeOutboxRepository());

        service.process(new OrderCommandEvent(OrderCommandType.PLACE, 901L, 404L, 1001L,
                "missing-404", "BTC-USDT", 5L, OrderSide.BUY, OrderType.LIMIT, TimeInForce.GTC,
                100L, 1L, 2L, 5L, false, false, Instant.parse("2026-07-01T00:00:00Z"),
                "trace-missing-901"));

        assertThat(resultRepository.results).isEmpty();
        assertThat(resultRepository.trades).isEmpty();
    }

    @Test
    void emitsHedgePositionSidesFromTakerCommandAndMakerLookup() throws Exception {
        MatchingProperties properties = new MatchingProperties();
        properties.getEngine().setExchangeId("matching-service-hedge-position-side-test");
        properties.getRecovery().setOpenOrderBookRestoreEnabled(false);
        properties.getProtection().setSelfTradePreventionEnabled(false);

        MatchingSymbol matchingSymbol = new MatchingSymbol("BTC-USDT", 301, 11, 12);
        InstrumentSymbol instrument = new InstrumentSymbol("BTC-USDT", "BTC", "USDT", "USDT");
        ExchangeCoreEngine engine = new ExchangeCoreEngine(properties,
                new FakeMatchingSymbolRepository(instrument, matchingSymbol),
                new FakeRecoveryRepository());
        FakeResultRepository resultRepository = new FakeResultRepository();
        MatchingService service = new MatchingService(new ObjectMapper(), properties, engine,
                new FakeProtectionRepository(), new FakeSequenceRepository(), resultRepository,
                new FakeOutboxRepository());

        try {
            engine.start();

            OrderCommandEvent maker = new OrderCommandEvent(OrderCommandType.PLACE, 501L, 101L, 1001L,
                    "maker-short-101", "BTC-USDT", 5L, OrderSide.SELL, OrderType.LIMIT, TimeInForce.GTC,
                    100L, 10L, MarginMode.CROSS, PositionSide.SHORT, 2L, 5L, false, false,
                    Instant.parse("2026-07-01T00:00:00Z"), "trace-maker-short");
            OrderCommandEvent taker = new OrderCommandEvent(OrderCommandType.PLACE, 502L, 202L, 2002L,
                    "taker-long-202", "BTC-USDT", 7L, OrderSide.BUY, OrderType.LIMIT, TimeInForce.IOC,
                    100L, 3L, MarginMode.CROSS, PositionSide.LONG, 2L, 5L, false, false,
                    Instant.parse("2026-07-01T00:00:01Z"), "trace-taker-long");

            service.process(maker);
            resultRepository.orderPositionSides.put(101L, PositionSide.SHORT);
            service.process(taker);

            assertThat(resultRepository.trades).singleElement().satisfies(trade -> {
                assertThat(trade.takerPositionSide()).isEqualTo(PositionSide.LONG);
                assertThat(trade.makerPositionSide()).isEqualTo(PositionSide.SHORT);
            });
        } finally {
            engine.stop();
        }
    }

    @Test
    void rejectsMarketOrderWhenMarkPriceUnavailableAtMatching() {
        MatchingProperties properties = new MatchingProperties();
        properties.getEngine().setExchangeId("matching-service-mark-unavailable-test");
        properties.getRecovery().setOpenOrderBookRestoreEnabled(false);
        properties.getProtection().setSelfTradePreventionEnabled(false);

        MatchingSymbol matchingSymbol = new MatchingSymbol("BTC-USDT", 301, 11, 12);
        InstrumentSymbol instrument = new InstrumentSymbol("BTC-USDT", "BTC", "USDT", "USDT");
        ExchangeCoreEngine engine = new ExchangeCoreEngine(properties,
                new FakeMatchingSymbolRepository(instrument, matchingSymbol),
                new FakeRecoveryRepository());
        FakeResultRepository resultRepository = new FakeResultRepository();
        MatchingService service = new MatchingService(new ObjectMapper(), properties, engine,
                new FakeProtectionRepository(OptionalLong.empty()), new FakeSequenceRepository(), resultRepository,
                new FakeOutboxRepository());

        try {
            engine.start();

            service.process(new OrderCommandEvent(OrderCommandType.PLACE, 501L, 101L, 1001L,
                    "market-101", "BTC-USDT", 5L, OrderSide.BUY, OrderType.MARKET, TimeInForce.IOC,
                    0L, 1L, 2L, 5L, false, false, Instant.parse("2026-07-01T00:00:00Z")));

            assertThat(resultRepository.results).singleElement().satisfies(result -> {
                assertThat(result.orderStatus()).isEqualTo(OrderStatus.REJECTED);
                assertThat(result.resultCode()).isEqualTo("MARK_PRICE_UNAVAILABLE");
            });
            assertThat(resultRepository.trades).isEmpty();
        } finally {
            engine.stop();
        }
    }

    @Test
    void publishesDepthBeforeMatchResultPersistenceAndSkipsDuplicateDatabaseSideEffects() {
        MatchingProperties properties = new MatchingProperties();
        properties.getEngine().setExchangeId("matching-service-result-replay-test");
        properties.getRecovery().setOpenOrderBookRestoreEnabled(false);
        properties.getProtection().setSelfTradePreventionEnabled(false);

        MatchingSymbol matchingSymbol = new MatchingSymbol("BTC-USDT", 301, 11, 12);
        InstrumentSymbol instrument = new InstrumentSymbol("BTC-USDT", "BTC", "USDT", "USDT");
        ExchangeCoreEngine engine = new ExchangeCoreEngine(properties,
                new FakeMatchingSymbolRepository(instrument, matchingSymbol),
                new FakeRecoveryRepository());
        FakeResultRepository resultRepository = new FakeResultRepository();
        resultRepository.rejectNextSaveResult = true;
        FakeOutboxRepository outboxRepository = new FakeOutboxRepository();
        FakeDepthPublisher depthPublisher = new FakeDepthPublisher();
        MatchingService service = new MatchingService(new ObjectMapper(), properties, engine,
                new FakeProtectionRepository(), new FakeSequenceRepository(), resultRepository, outboxRepository,
                depthPublisher, PublicTradePublisher.NOOP);

        try {
            engine.start();

            service.process(new OrderCommandEvent(OrderCommandType.PLACE, 501L, 101L, 1001L,
                    "maker-101", "BTC-USDT", 5L, OrderSide.SELL, OrderType.LIMIT, TimeInForce.GTC,
                    100L, 10L, 2L, 5L, false, false, Instant.parse("2026-07-01T00:00:00Z")));

            assertThat(resultRepository.results).isEmpty();
            assertThat(resultRepository.activeStatusUpdates).isZero();
            assertThat(outboxRepository.records).isEmpty();
            assertThat(depthPublisher.events).singleElement()
                    .satisfies(depth -> assertThat(depth.updateType().name()).isEqualTo("SNAPSHOT"));
        } finally {
            engine.stop();
        }
    }

    @Test
    void keepsPublicTradeIndependentWhenFinancialTradePersistenceIsDuplicate() throws Exception {
        MatchingProperties properties = new MatchingProperties();
        properties.getEngine().setExchangeId("matching-service-trade-replay-test");
        properties.getRecovery().setOpenOrderBookRestoreEnabled(false);
        properties.getProtection().setSelfTradePreventionEnabled(false);

        MatchingSymbol matchingSymbol = new MatchingSymbol("BTC-USDT", 301, 11, 12);
        InstrumentSymbol instrument = new InstrumentSymbol("BTC-USDT", "BTC", "USDT", "USDT");
        ExchangeCoreEngine engine = new ExchangeCoreEngine(properties,
                new FakeMatchingSymbolRepository(instrument, matchingSymbol),
                new FakeRecoveryRepository());
        FakeResultRepository resultRepository = new FakeResultRepository();
        FakeOutboxRepository outboxRepository = new FakeOutboxRepository();
        FakePublicTradePublisher tradePublisher = new FakePublicTradePublisher();
        MatchingService service = new MatchingService(new ObjectMapper(), properties, engine,
                new FakeProtectionRepository(), new FakeSequenceRepository(), resultRepository, outboxRepository,
                OrderBookDepthPublisher.NOOP, tradePublisher);

        try {
            engine.start();

            service.process(new OrderCommandEvent(OrderCommandType.PLACE, 501L, 101L, 1001L,
                    "maker-101", "BTC-USDT", 5L, OrderSide.SELL, OrderType.LIMIT, TimeInForce.GTC,
                    100L, 10L, 2L, 5L, false, false, Instant.parse("2026-07-01T00:00:00Z")));
            resultRepository.rejectNextSaveTrade = true;
            service.process(new OrderCommandEvent(OrderCommandType.PLACE, 502L, 202L, 2002L,
                    "taker-202", "BTC-USDT", 7L, OrderSide.BUY, OrderType.LIMIT, TimeInForce.IOC,
                    100L, 3L, 2L, 5L, false, false, Instant.parse("2026-07-01T00:00:01Z")));

            assertThat(resultRepository.results).hasSize(2);
            assertThat(resultRepository.trades).isEmpty();
            assertThat(resultRepository.makerFillUpdates).isZero();
            assertThat(tradePublisher.events).singleElement()
                    .satisfies(event -> assertThat(event.tradeId()).isEqualTo("502:1"));
            assertThat(outboxRepository.records)
                    .extracting(OutboxRecord::aggregateType)
                    .containsExactly("MATCH_RESULT", "ACCOUNT_COMMAND", "MATCH_RESULT");
        } finally {
            engine.stop();
        }
    }

    @Test
    void cancelCommandsBypassPostOnlyLiquidityProtection() {
        MatchingProperties properties = new MatchingProperties();
        properties.getEngine().setExchangeId("matching-service-cancel-post-only-test");
        properties.getRecovery().setOpenOrderBookRestoreEnabled(false);
        properties.getProtection().setSelfTradePreventionEnabled(false);

        MatchingSymbol matchingSymbol = new MatchingSymbol("BTC-USDT", 301, 11, 12);
        InstrumentSymbol instrument = new InstrumentSymbol("BTC-USDT", "BTC", "USDT", "USDT");
        ExchangeCoreEngine engine = new ExchangeCoreEngine(properties,
                new FakeMatchingSymbolRepository(instrument, matchingSymbol),
                new FakeRecoveryRepository());
        FakeResultRepository resultRepository = new FakeResultRepository();
        MatchingService service = new MatchingService(new ObjectMapper(), properties, engine,
                new FakeProtectionRepository(), new FakeSequenceRepository(), resultRepository,
                new FakeOutboxRepository());

        try {
            engine.start();

            service.process(new OrderCommandEvent(OrderCommandType.PLACE, 501L, 101L, 1001L,
                    "ask-101", "BTC-USDT", 5L, OrderSide.SELL, OrderType.LIMIT, TimeInForce.GTC,
                    90L, 1L, 2L, 5L, false, false, Instant.parse("2026-07-01T00:00:00Z")));
            service.process(new OrderCommandEvent(OrderCommandType.CANCEL, 502L, 202L, 2002L,
                    "post-only-cancel-202", "BTC-USDT", 5L, OrderSide.BUY, OrderType.LIMIT, TimeInForce.GTC,
                    100L, 1L, 2L, 5L, false, true, Instant.parse("2026-07-01T00:00:01Z")));

            MatchResultEvent cancelResult = resultRepository.results.get(1);
            assertThat(cancelResult.commandType()).isEqualTo(OrderCommandType.CANCEL);
            assertThat(cancelResult.resultCode()).isNotEqualTo("POST_ONLY_WOULD_TAKE");
            assertThat(cancelResult.orderStatus()).isEqualTo(OrderStatus.CANCEL_REQUESTED);
        } finally {
            engine.stop();
        }
    }

    @Test
    void marketMakerBypassSkipsSelfTradePreventionCheck() {
        MatchingProperties properties = new MatchingProperties();
        properties.getEngine().setExchangeId("matching-service-mm-stp-bypass-test");
        properties.getRecovery().setOpenOrderBookRestoreEnabled(false);
        properties.getProtection().setSelfTradePreventionEnabled(true);
        properties.getProtection().setSelfTradePreventionBypassUserIds(List.of(900001L));

        MatchingSymbol matchingSymbol = new MatchingSymbol("BTC-USDT", 301, 11, 12);
        InstrumentSymbol instrument = new InstrumentSymbol("BTC-USDT", "BTC", "USDT", "USDT");
        ExchangeCoreEngine engine = new ExchangeCoreEngine(properties,
                new FakeMatchingSymbolRepository(instrument, matchingSymbol),
                new FakeRecoveryRepository());
        FakeProtectionRepository protectionRepository = new FakeProtectionRepository(true);
        FakeResultRepository resultRepository = new FakeResultRepository();
        MatchingService service = new MatchingService(new ObjectMapper(), properties, engine,
                protectionRepository, new FakeSequenceRepository(), resultRepository, new FakeOutboxRepository());

        try {
            engine.start();

            service.process(new OrderCommandEvent(OrderCommandType.PLACE, 501L, 101L, 900001L,
                    "mm-101", "BTC-USDT", 5L, OrderSide.SELL, OrderType.LIMIT, TimeInForce.GTC,
                    100L, 10L, 2L, 5L, false, false, Instant.parse("2026-07-01T00:00:00Z")));

            assertThat(resultRepository.results).singleElement()
                    .satisfies(result -> assertThat(result.orderStatus()).isEqualTo(OrderStatus.ACCEPTED));
            assertThat(protectionRepository.selfTradeChecks).isZero();
        } finally {
            engine.stop();
        }
    }

    private static final class FakeMatchingSymbolRepository extends MatchingSymbolRepository {
        private final InstrumentSymbol instrument;
        private final MatchingSymbol matchingSymbol;

        private FakeMatchingSymbolRepository(InstrumentSymbol instrument, MatchingSymbol matchingSymbol) {
            super(null, null);
            this.instrument = instrument;
            this.matchingSymbol = matchingSymbol;
        }

        @Override
        public List<InstrumentSymbol> currentTradingSymbols() {
            return List.of(instrument);
        }

        @Override
        public Optional<InstrumentSymbol> currentTradingSymbol(String symbol) {
            return instrument.symbol().equals(symbol) ? Optional.of(instrument) : Optional.empty();
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

    private static final class FakeProtectionRepository extends MatchingProtectionRepository {
        private final boolean wouldSelfTrade;
        private final OptionalLong markPriceTicks;
        private int selfTradeChecks;

        private FakeProtectionRepository() {
            this(false, OptionalLong.of(100L));
        }

        private FakeProtectionRepository(boolean wouldSelfTrade) {
            this(wouldSelfTrade, OptionalLong.of(100L));
        }

        private FakeProtectionRepository(OptionalLong markPriceTicks) {
            this(false, markPriceTicks);
        }

        private FakeProtectionRepository(boolean wouldSelfTrade, OptionalLong markPriceTicks) {
            super(null);
            this.wouldSelfTrade = wouldSelfTrade;
            this.markPriceTicks = markPriceTicks;
        }

        @Override
        public OptionalLong latestMarkPriceTicks(String symbol, long instrumentVersion, Duration maxAge) {
            return markPriceTicks;
        }

        @Override
        public boolean wouldSelfTrade(long userId,
                                      String symbol,
                                      long instrumentVersion,
                                      OrderSide side,
                                      long effectivePriceTicks) {
            selfTradeChecks++;
            return wouldSelfTrade;
        }

        @Override
        public boolean hasOpenOrdersWithDifferentInstrumentVersion(String symbol, long instrumentVersion, long orderId) {
            return false;
        }
    }

    private static final class FakeSequenceRepository extends MatchingSequenceRepository {
        private final Map<String, Long> nextByName = new HashMap<>();

        private FakeSequenceRepository() {
            super(null);
        }

        @Override
        public long nextSequence(String sequenceName) {
            long next = nextByName.getOrDefault(sequenceName, 0L) + 1L;
            nextByName.put(sequenceName, next);
            return next;
        }
    }

    private static final class FakeResultRepository extends MatchingResultRepository {
        private final List<MatchResultEvent> results = new ArrayList<>();
        private final List<MatchTradeEvent> trades = new ArrayList<>();
        private final List<Long> missingOrderIds = new ArrayList<>();
        private final Map<Long, Long> orderVersions = new HashMap<>();
        private final Map<Long, MarginMode> orderMarginModes = new HashMap<>();
        private final Map<Long, PositionSide> orderPositionSides = new HashMap<>();
        private boolean rejectNextSaveResult;
        private boolean rejectNextSaveTrade;
        private int activeStatusUpdates;
        private int makerFillUpdates;

        private FakeResultRepository() {
            super(null, null);
        }

        @Override
        public boolean commandResultExists(long commandId) {
            return results.stream().anyMatch(result -> result.commandId() == commandId);
        }

        @Override
        public boolean orderExists(long orderId) {
            return !missingOrderIds.contains(orderId);
        }

        @Override
        public long orderInstrumentVersion(long orderId) {
            Long version = orderVersions.get(orderId);
            if (version == null) {
                throw new IllegalStateException("missing order version " + orderId);
            }
            return version;
        }

        @Override
        public MarginMode orderMarginMode(long orderId) {
            return orderMarginModes.getOrDefault(orderId, MarginMode.CROSS);
        }

        @Override
        public PositionSide orderPositionSide(long orderId) {
            return orderPositionSides.getOrDefault(orderId, PositionSide.NET);
        }

        @Override
        public MatchedOrderSnapshot orderSnapshot(long orderId) {
            return new MatchedOrderSnapshot(orderInstrumentVersion(orderId), orderMarginMode(orderId),
                    orderPositionSide(orderId), 2L, 5L);
        }

        @Override
        public boolean saveResult(MatchResultEvent event) {
            if (rejectNextSaveResult) {
                rejectNextSaveResult = false;
                return false;
            }
            results.add(event);
            orderVersions.put(event.orderId(), event.instrumentVersion());
            orderMarginModes.put(event.orderId(), MarginMode.CROSS);
            return true;
        }

        @Override
        public boolean saveTrade(MatchTradeEvent trade) {
            if (rejectNextSaveTrade) {
                rejectNextSaveTrade = false;
                return false;
            }
            trades.add(trade);
            return true;
        }

        @Override
        public void applyActiveOrderStatus(MatchResultEvent result) {
            activeStatusUpdates++;
        }

        @Override
        public void applyMakerFill(MatchTradeEvent trade) {
            makerFillUpdates++;
        }
    }

    private static final class FakeOutboxRepository extends MatchingOutboxRepository {
        private final List<OutboxRecord> records = new ArrayList<>();

        private FakeOutboxRepository() {
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
            records.add(new OutboxRecord(aggregateType, aggregateId, topic, eventKey, eventType, payload));
        }
    }

    private static final class FakeDepthPublisher implements OrderBookDepthPublisher {
        private final List<OrderBookDepthEvent> events = new ArrayList<>();

        @Override
        public void offer(OrderBookDepthEvent event) {
            events.add(event);
        }
    }

    private static final class FakePublicTradePublisher implements PublicTradePublisher {
        private final List<PublicTradeEvent> events = new ArrayList<>();
        private final boolean failAfterOffer;

        private FakePublicTradePublisher() {
            this(false);
        }

        private FakePublicTradePublisher(boolean failAfterOffer) {
            this.failAfterOffer = failAfterOffer;
        }

        @Override
        public void offer(PublicTradeEvent event) {
            events.add(event);
            if (failAfterOffer) {
                throw new IllegalStateException("public Kafka queue unavailable");
            }
        }
    }

    private record OutboxRecord(String aggregateType,
                                long aggregateId,
                                String topic,
                                String eventKey,
                                String eventType,
                                String payload) {
    }
}
