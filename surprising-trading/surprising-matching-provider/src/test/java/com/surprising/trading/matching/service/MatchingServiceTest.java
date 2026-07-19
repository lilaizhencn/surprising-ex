package com.surprising.trading.matching.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.surprising.account.api.model.AccountUserCommand;
import com.surprising.account.api.model.AccountUserCommandType;
import com.surprising.account.api.model.OrderReleaseAccountCommand;
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
import com.surprising.trading.matching.repository.MatchingOutboxRepository.MatchingOutboxWrite;
import com.surprising.trading.matching.repository.MatchingProtectionRepository;
import com.surprising.trading.matching.repository.MatchingResultRepository;
import com.surprising.trading.matching.repository.MatchingSymbolRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
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
        FakeResultRepository resultRepository = new FakeResultRepository();
        FakeOutboxRepository outboxRepository = new FakeOutboxRepository();
        FakeDepthPublisher depthPublisher = new FakeDepthPublisher();
        FakePublicTradePublisher tradePublisher = new FakePublicTradePublisher(true);
        MatchingService service = new MatchingService(new ObjectMapper(), properties, engine,
                new FakeProtectionRepository(), resultRepository, outboxRepository,
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

            service.processBatch(List.of(maker, taker, taker));

            assertThat(resultRepository.results).hasSize(2);
            assertThat(resultRepository.results.get(0).orderStatus()).isEqualTo(OrderStatus.ACCEPTED);
            assertThat(resultRepository.results.get(0).traceId()).isEqualTo("trace-maker-501");
            assertThat(resultRepository.results.get(1).orderStatus()).isEqualTo(OrderStatus.FILLED);
            assertThat(resultRepository.results.get(1).traceId()).isEqualTo("trace-taker-502");
            assertThat(resultRepository.trades).hasSize(1);

            MatchTradeEvent trade = resultRepository.trades.get(0);
            assertThat(trade.tradeId()).isEqualTo(502_000_001L);
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
            assertThat(depthPublisher.events).hasSize(1);
            OrderBookDepthEvent depth = depthPublisher.events.get(0);
            assertThat(depth.symbol()).isEqualTo("BTC-USDT");
            assertThat(depth.sequence()).isEqualTo(1L);
            assertThat(depth.previousSequence()).isEqualTo(0L);
            assertThat(depth.updateType().name()).isEqualTo("SNAPSHOT");
            assertThat(depth.bids()).isEmpty();
            assertThat(depth.asks()).singleElement().satisfies(level -> {
                assertThat(level.priceTicks()).isEqualTo(100L);
                assertThat(level.quantitySteps()).isEqualTo(7L);
                assertThat(level.orderCount()).isEqualTo(1L);
            });

            OrderBookSnapshotResponse snapshot = service.orderBookSnapshot("BTC-USDT", 10);
            assertThat(snapshot.symbol()).isEqualTo("BTC-USDT");
            assertThat(snapshot.sequence()).isEqualTo(1L);
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
    void internalMarketMakerSelfTradeSkipsFinancialPersistenceButKeepsPublicTrade() throws Exception {
        MatchingProperties properties = new MatchingProperties();
        properties.getEngine().setExchangeId("matching-service-internal-mm-self-trade-test");
        properties.getRecovery().setOpenOrderBookRestoreEnabled(false);
        properties.getProtection().setSelfTradePreventionEnabled(true);
        properties.getProtection().setInternalMarketMakerUserIds(List.of(900001L, 900002L));

        MatchingSymbol matchingSymbol = new MatchingSymbol("BTC-USDT", 301, 11, 12);
        InstrumentSymbol instrument = new InstrumentSymbol("BTC-USDT", "BTC", "USDT", "USDT");
        ExchangeCoreEngine engine = new ExchangeCoreEngine(properties,
                new FakeMatchingSymbolRepository(instrument, matchingSymbol),
                new FakeRecoveryRepository());
        FakeResultRepository resultRepository = new FakeResultRepository();
        FakeOutboxRepository outboxRepository = new FakeOutboxRepository();
        FakePublicTradePublisher tradePublisher = new FakePublicTradePublisher();
        MatchingService service = new MatchingService(new ObjectMapper(), properties, engine,
                new FakeProtectionRepository(), resultRepository, outboxRepository,
                OrderBookDepthPublisher.NOOP, tradePublisher);

        try {
            engine.start();

            service.process(new OrderCommandEvent(OrderCommandType.PLACE, 501L, 101L, 900001L,
                    "mm-maker-101", "BTC-USDT", 5L, OrderSide.SELL, OrderType.LIMIT, TimeInForce.GTC,
                    100L, 3L, 2L, 5L, false, false, Instant.parse("2026-07-01T00:00:00Z")));
            service.process(new OrderCommandEvent(OrderCommandType.PLACE, 502L, 202L, 900002L,
                    "mm-taker-202", "BTC-USDT", 5L, OrderSide.BUY, OrderType.LIMIT, TimeInForce.IOC,
                    100L, 3L, 2L, 5L, false, false, Instant.parse("2026-07-01T00:00:01Z")));

            assertThat(resultRepository.results).hasSize(2);
            assertThat(resultRepository.results.get(1).trades()).singleElement().satisfies(trade -> {
                assertThat(trade.tradeId()).isEqualTo(502_000_001L);
                assertThat(trade.takerUserId()).isEqualTo(900002L);
                assertThat(trade.makerUserId()).isEqualTo(900001L);
            });
            assertThat(resultRepository.trades).isEmpty();
            assertThat(resultRepository.makerFillUpdates).isEqualTo(1);
            assertThat(tradePublisher.events).singleElement()
                    .satisfies(event -> assertThat(event.tradeId()).isEqualTo("502:1"));

            assertThat(outboxRepository.records)
                    .extracting(OutboxRecord::aggregateType)
                    .containsExactly("MATCH_RESULT", "ACCOUNT_COMMAND", "ACCOUNT_COMMAND", "MATCH_RESULT");
            AccountUserCommand makerRelease = accountCommand(outboxRepository.records.get(1));
            AccountUserCommand takerRelease = accountCommand(outboxRepository.records.get(2));
            assertThat(makerRelease.commandType()).isEqualTo(AccountUserCommandType.ORDER_RELEASE);
            assertThat(makerRelease.userId()).isEqualTo(900001L);
            assertThat(makerRelease.dependsOnCommandId()).isNull();
            assertThat(releaseCommand(makerRelease)).satisfies(release -> {
                assertThat(release.orderId()).isEqualTo(101L);
                assertThat(release.releaseAll()).isTrue();
                assertThat(release.reason()).isEqualTo("INTERNAL_MARKET_MAKER_SELF_TRADE");
            });
            assertThat(takerRelease.commandType()).isEqualTo(AccountUserCommandType.ORDER_RELEASE);
            assertThat(takerRelease.userId()).isEqualTo(900002L);
            assertThat(takerRelease.dependsOnCommandId()).isNull();
            assertThat(releaseCommand(takerRelease)).satisfies(release -> {
                assertThat(release.orderId()).isEqualTo(202L);
                assertThat(release.releaseAll()).isTrue();
                assertThat(release.reason()).isEqualTo("ORDER_TERMINAL");
            });
        } finally {
            engine.stop();
        }
    }

    @Test
    void partialInternalMarketMakerSelfTradeReleasesOnlyFilledReservationShare() throws Exception {
        MatchingProperties properties = new MatchingProperties();
        properties.getEngine().setExchangeId("matching-service-internal-mm-partial-self-trade-test");
        properties.getRecovery().setOpenOrderBookRestoreEnabled(false);
        properties.getProtection().setSelfTradePreventionEnabled(true);
        properties.getProtection().setInternalMarketMakerUserIds(List.of(900001L, 900002L));

        MatchingSymbol matchingSymbol = new MatchingSymbol("BTC-USDT", 301, 11, 12);
        InstrumentSymbol instrument = new InstrumentSymbol("BTC-USDT", "BTC", "USDT", "USDT");
        ExchangeCoreEngine engine = new ExchangeCoreEngine(properties,
                new FakeMatchingSymbolRepository(instrument, matchingSymbol),
                new FakeRecoveryRepository());
        FakeResultRepository resultRepository = new FakeResultRepository();
        FakeOutboxRepository outboxRepository = new FakeOutboxRepository();
        MatchingService service = new MatchingService(new ObjectMapper(), properties, engine,
                new FakeProtectionRepository(), resultRepository, outboxRepository);

        try {
            engine.start();

            service.process(new OrderCommandEvent(OrderCommandType.PLACE, 501L, 101L, 900001L,
                    "mm-maker-101", "BTC-USDT", 5L, OrderSide.SELL, OrderType.LIMIT, TimeInForce.GTC,
                    100L, 3L, 2L, 5L, false, false, Instant.parse("2026-07-01T00:00:00Z")));
            service.process(new OrderCommandEvent(OrderCommandType.PLACE, 502L, 202L, 900002L,
                    "mm-taker-202", "BTC-USDT", 5L, OrderSide.BUY, OrderType.LIMIT, TimeInForce.GTC,
                    100L, 5L, 2L, 5L, false, false, Instant.parse("2026-07-01T00:00:01Z")));

            assertThat(resultRepository.results.get(1).orderStatus()).isEqualTo(OrderStatus.PARTIALLY_FILLED);
            assertThat(resultRepository.trades).isEmpty();
            AccountUserCommand activeOrderRelease = accountCommand(outboxRepository.records.get(2));
            assertThat(activeOrderRelease.commandType()).isEqualTo(AccountUserCommandType.ORDER_RELEASE);
            assertThat(releaseCommand(activeOrderRelease)).satisfies(release -> {
                assertThat(release.orderId()).isEqualTo(202L);
                assertThat(release.releaseAll()).isFalse();
                assertThat(release.quantitySteps()).isEqualTo(5L);
                assertThat(release.remainingQuantitySteps()).isEqualTo(2L);
                assertThat(release.reason()).isEqualTo("INTERNAL_MARKET_MAKER_SELF_TRADE");
            });
        } finally {
            engine.stop();
        }
    }

    @Test
    void partialInternalMarketMakerMakerReleaseUsesPreMatchOrderSnapshot() throws Exception {
        MatchingProperties properties = new MatchingProperties();
        properties.getEngine().setExchangeId("matching-service-internal-mm-partial-maker-test");
        properties.getRecovery().setOpenOrderBookRestoreEnabled(false);
        properties.getProtection().setSelfTradePreventionEnabled(true);
        properties.getProtection().setInternalMarketMakerUserIds(List.of(900001L, 900002L));

        MatchingSymbol matchingSymbol = new MatchingSymbol("BTC-USDT", 301, 11, 12);
        InstrumentSymbol instrument = new InstrumentSymbol("BTC-USDT", "BTC", "USDT", "USDT");
        ExchangeCoreEngine engine = new ExchangeCoreEngine(properties,
                new FakeMatchingSymbolRepository(instrument, matchingSymbol),
                new FakeRecoveryRepository());
        FakeResultRepository resultRepository = new FakeResultRepository();
        resultRepository.orderQuantitySteps.put(101L, 5L);
        resultRepository.orderRemainingQuantitySteps.put(101L, 5L);
        FakeOutboxRepository outboxRepository = new FakeOutboxRepository();
        MatchingService service = new MatchingService(new ObjectMapper(), properties, engine,
                new FakeProtectionRepository(), resultRepository, outboxRepository);

        try {
            engine.start();

            service.process(new OrderCommandEvent(OrderCommandType.PLACE, 501L, 101L, 900001L,
                    "mm-maker-101", "BTC-USDT", 5L, OrderSide.SELL, OrderType.LIMIT, TimeInForce.GTC,
                    100L, 5L, 2L, 5L, false, false, Instant.parse("2026-07-01T00:00:00Z")));
            service.process(new OrderCommandEvent(OrderCommandType.PLACE, 502L, 202L, 900002L,
                    "mm-taker-202", "BTC-USDT", 5L, OrderSide.BUY, OrderType.LIMIT, TimeInForce.IOC,
                    100L, 2L, 2L, 5L, false, false, Instant.parse("2026-07-01T00:00:01Z")));

            AccountUserCommand makerRelease = accountCommand(outboxRepository.records.get(1));
            assertThat(releaseCommand(makerRelease)).satisfies(release -> {
                assertThat(release.orderId()).isEqualTo(101L);
                assertThat(release.releaseAll()).isFalse();
                assertThat(release.quantitySteps()).isEqualTo(5L);
                assertThat(release.remainingQuantitySteps()).isEqualTo(3L);
            });
        } finally {
            engine.stop();
        }
    }

    @Test
    void marketMakerTradeWithRealUserKeepsFullFinancialSettlement() throws Exception {
        MatchingProperties properties = new MatchingProperties();
        properties.getEngine().setExchangeId("matching-service-mm-real-user-test");
        properties.getRecovery().setOpenOrderBookRestoreEnabled(false);
        properties.getProtection().setSelfTradePreventionEnabled(true);
        properties.getProtection().setInternalMarketMakerUserIds(List.of(900001L, 900002L));

        MatchingSymbol matchingSymbol = new MatchingSymbol("BTC-USDT", 301, 11, 12);
        InstrumentSymbol instrument = new InstrumentSymbol("BTC-USDT", "BTC", "USDT", "USDT");
        ExchangeCoreEngine engine = new ExchangeCoreEngine(properties,
                new FakeMatchingSymbolRepository(instrument, matchingSymbol),
                new FakeRecoveryRepository());
        FakeResultRepository resultRepository = new FakeResultRepository();
        FakeOutboxRepository outboxRepository = new FakeOutboxRepository();
        MatchingService service = new MatchingService(new ObjectMapper(), properties, engine,
                new FakeProtectionRepository(), resultRepository, outboxRepository);

        try {
            engine.start();

            service.process(new OrderCommandEvent(OrderCommandType.PLACE, 501L, 101L, 900001L,
                    "mm-maker-101", "BTC-USDT", 5L, OrderSide.SELL, OrderType.LIMIT, TimeInForce.GTC,
                    100L, 3L, 2L, 5L, false, false, Instant.parse("2026-07-01T00:00:00Z")));
            service.process(new OrderCommandEvent(OrderCommandType.PLACE, 502L, 202L, 2002L,
                    "real-taker-202", "BTC-USDT", 5L, OrderSide.BUY, OrderType.LIMIT, TimeInForce.IOC,
                    100L, 3L, 2L, 5L, false, false, Instant.parse("2026-07-01T00:00:01Z")));

            assertThat(resultRepository.trades).singleElement().satisfies(trade -> {
                assertThat(trade.takerUserId()).isEqualTo(2002L);
                assertThat(trade.makerUserId()).isEqualTo(900001L);
            });
            assertThat(outboxRepository.records.stream()
                    .map(OutboxRecord::payload)
                    .map(MatchingServiceTest::accountCommandOrNull)
                    .filter(command -> command != null
                            && command.commandType() == AccountUserCommandType.TRADE_SIDE_SETTLE))
                    .hasSize(2);
        } finally {
            engine.stop();
        }
    }

    @Test
    void mixedMarketMakerSweepSettlesRealFillBeforeReleasingInternalSelfTradeMargin() throws Exception {
        MatchingProperties properties = new MatchingProperties();
        properties.getEngine().setExchangeId("matching-service-mm-mixed-sweep-test");
        properties.getRecovery().setOpenOrderBookRestoreEnabled(false);
        properties.getProtection().setSelfTradePreventionEnabled(true);
        properties.getProtection().setInternalMarketMakerUserIds(List.of(900001L, 900002L));

        MatchingSymbol matchingSymbol = new MatchingSymbol("BTC-USDT", 301, 11, 12);
        InstrumentSymbol instrument = new InstrumentSymbol("BTC-USDT", "BTC", "USDT", "USDT");
        ExchangeCoreEngine engine = new ExchangeCoreEngine(properties,
                new FakeMatchingSymbolRepository(instrument, matchingSymbol),
                new FakeRecoveryRepository());
        FakeResultRepository resultRepository = new FakeResultRepository();
        FakeOutboxRepository outboxRepository = new FakeOutboxRepository();
        FakePublicTradePublisher tradePublisher = new FakePublicTradePublisher();
        MatchingService service = new MatchingService(new ObjectMapper(), properties, engine,
                new FakeProtectionRepository(), resultRepository, outboxRepository,
                OrderBookDepthPublisher.NOOP, tradePublisher);

        try {
            engine.start();

            service.process(new OrderCommandEvent(OrderCommandType.PLACE, 501L, 101L, 900002L,
                    "mm-maker-101", "BTC-USDT", 5L, OrderSide.SELL, OrderType.LIMIT, TimeInForce.GTC,
                    100L, 3L, 2L, 5L, false, false, Instant.parse("2026-07-01T00:00:00Z")));
            service.process(new OrderCommandEvent(OrderCommandType.PLACE, 502L, 102L, 1001L,
                    "real-maker-102", "BTC-USDT", 5L, OrderSide.SELL, OrderType.LIMIT, TimeInForce.GTC,
                    101L, 3L, 2L, 5L, false, false, Instant.parse("2026-07-01T00:00:01Z")));
            service.process(new OrderCommandEvent(OrderCommandType.PLACE, 503L, 203L, 900001L,
                    "mm-taker-203", "BTC-USDT", 5L, OrderSide.BUY, OrderType.LIMIT, TimeInForce.IOC,
                    101L, 6L, 2L, 5L, false, false, Instant.parse("2026-07-01T00:00:02Z")));

            assertThat(resultRepository.results.get(2).trades()).hasSize(2);
            assertThat(resultRepository.results.get(2).trades().get(0).tradeId()).isEqualTo(503_000_001L);
            assertThat(resultRepository.trades).singleElement().satisfies(trade -> {
                assertThat(trade.tradeId()).isEqualTo(503_000_002L);
                assertThat(trade.makerUserId()).isEqualTo(1001L);
            });
            assertThat(resultRepository.makerFillUpdates).isEqualTo(2);
            assertThat(tradePublisher.events)
                    .extracting(PublicTradeEvent::tradeId)
                    .containsExactly("503:1", "503:2");

            List<AccountUserCommand> accountCommands = outboxRepository.records.stream()
                    .map(OutboxRecord::payload)
                    .map(MatchingServiceTest::accountCommandOrNull)
                    .filter(command -> command != null)
                    .toList();
            assertThat(accountCommands)
                    .extracting(AccountUserCommand::commandType)
                    .containsExactly(
                            AccountUserCommandType.ORDER_RELEASE,
                            AccountUserCommandType.TRADE_SIDE_SETTLE,
                            AccountUserCommandType.TRADE_SIDE_SETTLE,
                            AccountUserCommandType.ORDER_RELEASE);
            AccountUserCommand activeOrderRelease = accountCommands.get(3);
            AccountUserCommand takerSettlement = accountCommands.get(1);
            assertThat(takerSettlement.userId()).isEqualTo(900001L);
            assertThat(activeOrderRelease.userId()).isEqualTo(900001L);
            assertThat(activeOrderRelease.dependsOnCommandId()).isEqualTo(takerSettlement.commandId());
            assertThat(releaseCommand(activeOrderRelease).releaseAll()).isTrue();
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
                new FakeProtectionRepository(), resultRepository,
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
                new FakeProtectionRepository(), resultRepository,
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
                new FakeProtectionRepository(OptionalLong.empty()), resultRepository,
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
                new FakeProtectionRepository(), resultRepository, outboxRepository,
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
    void failsClosedWhenFinancialTradeBatchIsNotPersisted() throws Exception {
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
                new FakeProtectionRepository(), resultRepository, outboxRepository,
                OrderBookDepthPublisher.NOOP, tradePublisher);

        try {
            engine.start();

            service.process(new OrderCommandEvent(OrderCommandType.PLACE, 501L, 101L, 1001L,
                    "maker-101", "BTC-USDT", 5L, OrderSide.SELL, OrderType.LIMIT, TimeInForce.GTC,
                    100L, 10L, 2L, 5L, false, false, Instant.parse("2026-07-01T00:00:00Z")));
            resultRepository.rejectNextSaveTrade = true;
            assertThatThrownBy(() -> service.process(new OrderCommandEvent(
                    OrderCommandType.PLACE, 502L, 202L, 2002L,
                    "taker-202", "BTC-USDT", 7L, OrderSide.BUY, OrderType.LIMIT, TimeInForce.IOC,
                    100L, 3L, 2L, 5L, false, false, Instant.parse("2026-07-01T00:00:01Z"))))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("match trade batch");

            assertThat(resultRepository.trades).isEmpty();
            assertThat(resultRepository.makerFillUpdates).isZero();
            assertThat(tradePublisher.events).singleElement()
                    .satisfies(event -> assertThat(event.tradeId()).isEqualTo("502:1"));
            assertThat(outboxRepository.records)
                    .extracting(OutboxRecord::aggregateType)
                    .containsExactly("MATCH_RESULT");
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
                new FakeProtectionRepository(), resultRepository,
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
    void internalMarketMakerWhitelistSkipsSelfTradePreventionCheck() {
        MatchingProperties properties = new MatchingProperties();
        properties.getEngine().setExchangeId("matching-service-mm-stp-bypass-test");
        properties.getRecovery().setOpenOrderBookRestoreEnabled(false);
        properties.getProtection().setSelfTradePreventionEnabled(true);
        properties.getProtection().setInternalMarketMakerUserIds(List.of(900001L));

        MatchingSymbol matchingSymbol = new MatchingSymbol("BTC-USDT", 301, 11, 12);
        InstrumentSymbol instrument = new InstrumentSymbol("BTC-USDT", "BTC", "USDT", "USDT");
        ExchangeCoreEngine engine = new ExchangeCoreEngine(properties,
                new FakeMatchingSymbolRepository(instrument, matchingSymbol),
                new FakeRecoveryRepository());
        FakeProtectionRepository protectionRepository = new FakeProtectionRepository(true);
        FakeResultRepository resultRepository = new FakeResultRepository();
        MatchingService service = new MatchingService(new ObjectMapper(), properties, engine,
                protectionRepository, resultRepository, new FakeOutboxRepository());

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

    private static AccountUserCommand accountCommand(OutboxRecord record) throws Exception {
        return new ObjectMapper().readValue(record.payload(), AccountUserCommand.class);
    }

    private static AccountUserCommand accountCommandOrNull(String payload) {
        try {
            return new ObjectMapper().readValue(payload, AccountUserCommand.class);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static OrderReleaseAccountCommand releaseCommand(AccountUserCommand command) throws Exception {
        return new ObjectMapper().readValue(command.payload(), OrderReleaseAccountCommand.class);
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

        @Override
        public Set<Long> commandsThatWouldSelfTrade(List<OrderCommandEvent> commands) {
            if (!wouldSelfTrade) {
                return Set.of();
            }
            return commands.stream().map(OrderCommandEvent::commandId).collect(java.util.stream.Collectors.toSet());
        }

        @Override
        public Set<Long> commandsWithOpenOrdersAtDifferentInstrumentVersion(List<OrderCommandEvent> commands) {
            return Set.of();
        }
    }

    private static final class FakeResultRepository extends MatchingResultRepository {
        private final List<MatchResultEvent> results = new ArrayList<>();
        private final List<MatchTradeEvent> trades = new ArrayList<>();
        private final List<Long> missingOrderIds = new ArrayList<>();
        private final Map<Long, Long> orderVersions = new HashMap<>();
        private final Map<Long, MarginMode> orderMarginModes = new HashMap<>();
        private final Map<Long, PositionSide> orderPositionSides = new HashMap<>();
        private final Map<Long, Long> orderQuantitySteps = new HashMap<>();
        private final Map<Long, Long> orderRemainingQuantitySteps = new HashMap<>();
        private boolean rejectNextSaveResult;
        private boolean rejectNextSaveTrade;
        private int activeStatusUpdates;
        private int makerFillUpdates;

        private FakeResultRepository() {
            super(null, null);
        }

        @Override
        public CommandState commandState(long commandId, long orderId) {
            return new CommandState(
                    results.stream().anyMatch(result -> result.commandId() == commandId),
                    !missingOrderIds.contains(orderId));
        }

        @Override
        public Map<Long, CommandState> commandStates(Map<Long, Long> commandOrderIds) {
            Map<Long, CommandState> states = new HashMap<>();
            commandOrderIds.forEach((commandId, orderId) -> states.put(
                    commandId, commandState(commandId, orderId)));
            return states;
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
                    orderPositionSide(orderId), 2L, 5L,
                    orderQuantitySteps.getOrDefault(orderId, 10L),
                    orderRemainingQuantitySteps.getOrDefault(orderId, 10L),
                    false);
        }

        @Override
        public Map<Long, MatchedOrderSnapshot> orderSnapshots(Collection<Long> orderIds) {
            Map<Long, MatchedOrderSnapshot> snapshots = new HashMap<>();
            for (long orderId : orderIds) {
                snapshots.put(orderId, orderSnapshot(orderId));
            }
            return snapshots;
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
        public void saveTrades(List<MatchTradeEvent> batch) {
            if (rejectNextSaveTrade) {
                rejectNextSaveTrade = false;
                throw new IllegalStateException("rejected fake match trade batch");
            }
            trades.addAll(batch);
        }

        @Override
        public void applyActiveOrderStatus(MatchResultEvent result) {
            activeStatusUpdates++;
        }

        @Override
        public void applyMakerFills(List<MatchTradeEvent> batch) {
            makerFillUpdates += batch.size();
            for (MatchTradeEvent trade : batch) {
                orderRemainingQuantitySteps.computeIfPresent(
                        trade.makerOrderId(),
                        (ignored, remaining) -> Math.subtractExact(remaining, trade.quantitySteps()));
            }
        }
    }

    private static final class FakeOutboxRepository extends MatchingOutboxRepository {
        private final List<OutboxRecord> records = new ArrayList<>();

        private FakeOutboxRepository() {
            super(null);
        }

        @Override
        public void enqueueBatch(List<MatchingOutboxWrite> writes) {
            for (MatchingOutboxWrite write : writes) {
                records.add(new OutboxRecord(
                        write.aggregateType(), write.aggregateId(), write.topic(),
                        write.eventKey(), write.eventType(), write.payload()));
            }
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
