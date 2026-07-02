package com.surprising.trading.matching.service;

import static org.assertj.core.api.Assertions.assertThat;

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
    void emitsVersionedTradeEventsFromExchangeCoreMatchesAndSkipsDuplicateCommands() throws Exception {
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
        MatchingService service = new MatchingService(new ObjectMapper(), properties, engine,
                new FakeProtectionRepository(), sequenceRepository, resultRepository, outboxRepository);

        try {
            engine.start();

            OrderCommandEvent maker = new OrderCommandEvent(OrderCommandType.PLACE, 501L, 101L, 1001L,
                    "maker-101", "BTC-USDT", 5L, OrderSide.SELL, OrderType.LIMIT, TimeInForce.GTC,
                    100L, 10L, false, false, Instant.parse("2026-07-01T00:00:00Z"), "trace-maker-501");
            OrderCommandEvent taker = new OrderCommandEvent(OrderCommandType.PLACE, 502L, 202L, 2002L,
                    "taker-202", "BTC-USDT", 7L, OrderSide.BUY, OrderType.LIMIT, TimeInForce.IOC,
                    100L, 3L, false, false, Instant.parse("2026-07-01T00:00:01Z"), "trace-taker-502");

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
            assertThat(trade.makerOrderId()).isEqualTo(101L);
            assertThat(trade.makerInstrumentVersion()).isEqualTo(5L);
            assertThat(trade.makerUserId()).isEqualTo(1001L);
            assertThat(trade.makerMarginMode()).isEqualTo(MarginMode.CROSS);
            assertThat(trade.priceTicks()).isEqualTo(100L);
            assertThat(trade.quantitySteps()).isEqualTo(3L);
            assertThat(trade.traceId()).isEqualTo("trace-taker-502");
            assertThat(outboxRepository.records)
                    .extracting(OutboxRecord::aggregateType)
                    .containsExactly("MATCH_RESULT", "ORDER_BOOK_DEPTH",
                            "MATCH_TRADE", "MATCH_RESULT", "ORDER_BOOK_DEPTH");
            OrderBookDepthEvent depth = new ObjectMapper()
                    .readValue(outboxRepository.records.get(4).payload(), OrderBookDepthEvent.class);
            assertThat(depth.symbol()).isEqualTo("BTC-USDT");
            assertThat(depth.sequence()).isEqualTo(2L);
            assertThat(depth.previousSequence()).isEqualTo(1L);
            assertThat(depth.updateType().name()).isEqualTo("DELTA");
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
    void skipsOrderAndOutboxSideEffectsWhenMatchResultIsDuplicate() {
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
        MatchingService service = new MatchingService(new ObjectMapper(), properties, engine,
                new FakeProtectionRepository(), new FakeSequenceRepository(), resultRepository, outboxRepository);

        try {
            engine.start();

            service.process(new OrderCommandEvent(OrderCommandType.PLACE, 501L, 101L, 1001L,
                    "maker-101", "BTC-USDT", 5L, OrderSide.SELL, OrderType.LIMIT, TimeInForce.GTC,
                    100L, 10L, false, false, Instant.parse("2026-07-01T00:00:00Z")));

            assertThat(resultRepository.results).isEmpty();
            assertThat(resultRepository.activeStatusUpdates).isZero();
            assertThat(outboxRepository.records).isEmpty();
        } finally {
            engine.stop();
        }
    }

    @Test
    void skipsMakerFillAndTradeOutboxWhenMatchTradeIsDuplicate() throws Exception {
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
        MatchingService service = new MatchingService(new ObjectMapper(), properties, engine,
                new FakeProtectionRepository(), new FakeSequenceRepository(), resultRepository, outboxRepository);

        try {
            engine.start();

            service.process(new OrderCommandEvent(OrderCommandType.PLACE, 501L, 101L, 1001L,
                    "maker-101", "BTC-USDT", 5L, OrderSide.SELL, OrderType.LIMIT, TimeInForce.GTC,
                    100L, 10L, false, false, Instant.parse("2026-07-01T00:00:00Z")));
            resultRepository.rejectNextSaveTrade = true;
            service.process(new OrderCommandEvent(OrderCommandType.PLACE, 502L, 202L, 2002L,
                    "taker-202", "BTC-USDT", 7L, OrderSide.BUY, OrderType.LIMIT, TimeInForce.IOC,
                    100L, 3L, false, false, Instant.parse("2026-07-01T00:00:01Z")));

            assertThat(resultRepository.results).hasSize(2);
            assertThat(resultRepository.trades).isEmpty();
            assertThat(resultRepository.makerFillUpdates).isZero();
            assertThat(outboxRepository.records)
                    .extracting(OutboxRecord::aggregateType)
                    .containsExactly("MATCH_RESULT", "ORDER_BOOK_DEPTH", "MATCH_RESULT", "ORDER_BOOK_DEPTH");
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
                    90L, 1L, false, false, Instant.parse("2026-07-01T00:00:00Z")));
            service.process(new OrderCommandEvent(OrderCommandType.CANCEL, 502L, 202L, 2002L,
                    "post-only-cancel-202", "BTC-USDT", 5L, OrderSide.BUY, OrderType.LIMIT, TimeInForce.GTC,
                    100L, 1L, false, true, Instant.parse("2026-07-01T00:00:01Z")));

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
                    100L, 10L, false, false, Instant.parse("2026-07-01T00:00:00Z")));

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
        private int selfTradeChecks;

        private FakeProtectionRepository() {
            this(false);
        }

        private FakeProtectionRepository(boolean wouldSelfTrade) {
            super(null);
            this.wouldSelfTrade = wouldSelfTrade;
        }

        @Override
        public OptionalLong latestMarkPriceTicks(String symbol, long instrumentVersion, Duration maxAge) {
            return OptionalLong.of(100L);
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
        private final Map<Long, Long> orderVersions = new HashMap<>();
        private final Map<Long, MarginMode> orderMarginModes = new HashMap<>();
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

    private record OutboxRecord(String aggregateType,
                                long aggregateId,
                                String topic,
                                String eventKey,
                                String eventType,
                                String payload) {
    }
}
