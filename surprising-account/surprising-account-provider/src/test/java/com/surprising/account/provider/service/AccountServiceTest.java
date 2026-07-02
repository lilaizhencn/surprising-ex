package com.surprising.account.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.surprising.account.api.model.AccountType;
import com.surprising.account.api.model.BalanceResponse;
import com.surprising.account.api.model.LiquidationFeeSettledEvent;
import com.surprising.account.api.model.PositionMarginAdjustmentRequest;
import com.surprising.account.api.model.PositionMarginAdjustmentResponse;
import com.surprising.account.api.model.PositionUpdatedEvent;
import com.surprising.account.api.model.PositionResponse;
import com.surprising.account.provider.config.AccountProperties;
import com.surprising.account.provider.model.ContractSpec;
import com.surprising.account.provider.model.LiquidationFeeContext;
import com.surprising.account.provider.model.LiquidationFeeSettlement;
import com.surprising.account.provider.model.OrderFeeSnapshot;
import com.surprising.account.provider.model.PositionState;
import com.surprising.account.provider.model.SpotInstrumentSpec;
import com.surprising.account.provider.repository.AccountOutboxRepository;
import com.surprising.account.provider.repository.AccountRepository;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.instrument.api.model.InstrumentType;
import com.surprising.trading.api.model.MatchTradeEvent;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.PositionSide;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AccountServiceTest {

    private static final Instant EVENT_TIME = Instant.parse("2026-07-01T00:00:00Z");

    @Test
    void processesBothSidesAndSkipsDuplicateTrade() {
        FakeAccountRepository repository = new FakeAccountRepository();
        repository.feeSnapshots.put(9002L, new OrderFeeSnapshot(2L, 5L));
        repository.feeSnapshots.put(9001L, new OrderFeeSnapshot(2L, 5L));
        AccountService service = new AccountService(repository, new PositionCalculator());

        MatchTradeEvent trade = new MatchTradeEvent(
                9201L,
                9101L,
                "BTC-USDT",
                9002L,
                1L,
                2002L,
                OrderSide.BUY,
                9001L,
                1L,
                1001L,
                600_000L,
                3L,
                true,
                false,
                EVENT_TIME);

        service.processTrade(trade);
        service.processTrade(trade);

        assertThat(repository.positionState(2002L, "BTC-USDT"))
                .isEqualTo(new PositionState(3L, 1L, 600_000L, 0L));
        assertThat(repository.positionState(1001L, "BTC-USDT"))
                .isEqualTo(new PositionState(-3L, 1L, 600_000L, 0L));
        assertThat(repository.consumedOrderMargin).containsEntry(9002L, 3L).containsEntry(9001L, 3L);
        assertThat(repository.consumedOrderMarginUnits).containsEntry(9002L, 18_000L)
                .containsEntry(9001L, 18_000L);
        assertThat(repository.feeByUser).containsEntry(2002L, -9L).containsEntry(1001L, -4L);
        assertThat(repository.tradeProcessingAttempts).isEqualTo(2);
        assertThat(repository.positionUpdates).isEqualTo(2);
    }

    @Test
    void tradeFeesUseOrderSnapshotRatesInsteadOfInstrumentDefaults() {
        FakeAccountRepository repository = new FakeAccountRepository();
        repository.feeSnapshots.put(9002L, new OrderFeeSnapshot(2L, 1_000L));
        repository.feeSnapshots.put(9001L, new OrderFeeSnapshot(-100L, 5L));
        AccountService service = new AccountService(repository, new PositionCalculator());

        MatchTradeEvent trade = new MatchTradeEvent(
                9204L,
                9104L,
                "BTC-USDT",
                9002L,
                1L,
                2002L,
                OrderSide.BUY,
                9001L,
                1L,
                1001L,
                600_000L,
                3L,
                true,
                false,
                EVENT_TIME);

        service.processTrade(trade);

        assertThat(repository.feeByUser).containsEntry(2002L, -1_800L).containsEntry(1001L, 180L);
    }

    @Test
    void spotTradeSettlesProductAccountsWithoutPerpetualPositionUpdates() {
        FakeAccountRepository repository = new FakeAccountRepository();
        repository.instrumentTypes.put("BTC-USDT-SPOT:3", InstrumentType.SPOT);
        repository.spotSpecs.put("BTC-USDT-SPOT:3",
                new SpotInstrumentSpec(3L, "BTC", "USDT", 100_000L, 1L));
        repository.feeSnapshots.put(9102L, new OrderFeeSnapshot(-100L, 800L));
        repository.feeSnapshots.put(9101L, new OrderFeeSnapshot(-200L, 900L));
        FakeOutboxRepository outboxRepository = new FakeOutboxRepository();
        AccountService service = new AccountService(repository, new PositionCalculator(), null,
                new AccountProperties(), outboxRepository);

        MatchTradeEvent trade = new MatchTradeEvent(
                9501L,
                9301L,
                "BTC-USDT-SPOT",
                9102L,
                3L,
                2002L,
                OrderSide.BUY,
                9101L,
                3L,
                1001L,
                650_000L,
                4L,
                true,
                false,
                EVENT_TIME.plusSeconds(5),
                "trace-spot");

        service.processTrade(trade);

        assertThat(repository.spotSettlements).hasSize(2);
        assertThat(repository.spotSettlements)
                .extracting(SpotSettlementCall::orderId)
                .containsExactly(9102L, 9101L);
        assertThat(repository.spotSettlements)
                .extracting(SpotSettlementCall::side)
                .containsExactly(OrderSide.BUY, OrderSide.SELL);
        assertThat(repository.spotSettlements)
                .extracting(SpotSettlementCall::feeRatePpm)
                .containsExactly(800L, -200L);
        assertThat(repository.spotSettlements)
                .extracting(SpotSettlementCall::orderCompleted)
                .containsExactly(true, false);
        assertThat(repository.positionUpdates).isZero();
        assertThat(repository.consumedOrderMargin).isEmpty();
        assertThat(repository.releasedOrderMargin).isEmpty();
        assertThat(repository.pnlByUser).isEmpty();
        assertThat(repository.feeByUser).isEmpty();
        assertThat(outboxRepository.calls).isEmpty();
    }

    @Test
    void cachesImmutableContractSpecsAndOrderFeeSnapshotsAcrossTrades() {
        FakeAccountRepository repository = new FakeAccountRepository();
        repository.feeSnapshots.put(9001L, new OrderFeeSnapshot(2L, 5L));
        repository.feeSnapshots.put(9002L, new OrderFeeSnapshot(2L, 5L));
        repository.feeSnapshots.put(9003L, new OrderFeeSnapshot(2L, 5L));
        AccountService service = new AccountService(repository, new PositionCalculator());

        MatchTradeEvent first = new MatchTradeEvent(
                9211L,
                9111L,
                "BTC-USDT",
                9002L,
                1L,
                2002L,
                OrderSide.BUY,
                9001L,
                1L,
                1001L,
                600_000L,
                2L,
                true,
                false,
                EVENT_TIME);
        MatchTradeEvent second = new MatchTradeEvent(
                9212L,
                9112L,
                "BTC-USDT",
                9003L,
                1L,
                2003L,
                OrderSide.BUY,
                9001L,
                1L,
                1001L,
                600_000L,
                2L,
                true,
                false,
                EVENT_TIME.plusMillis(1));

        service.processTrade(first);
        service.processTrade(second);

        assertThat(repository.contractSpecLoads).containsEntry("BTC-USDT:1", 1);
        assertThat(repository.feeSnapshotLoads)
                .containsEntry(9001L, 1)
                .containsEntry(9002L, 1)
                .containsEntry(9003L, 1);
    }

    @Test
    void positionQueryAcceptsNetPositionSideAndRejectsHedgeSides() {
        FakeAccountRepository repository = new FakeAccountRepository();
        repository.positions.put(new PositionKey(1001L, "BTC-USDT", MarginMode.CROSS),
                new PositionState(3L, 1L, 600_000L, 0L));
        AccountService service = new AccountService(repository, new PositionCalculator());

        PositionResponse response = service.position(1001L, "btc-usdt", "cross", "net");

        assertThat(response.positionSide()).isEqualTo(PositionSide.NET);
        assertThat(response.signedQuantitySteps()).isEqualTo(3L);
        assertThatThrownBy(() -> service.position(1001L, "BTC-USDT", "CROSS", "LONG"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("hedge-mode positionSide is not supported; use NET");
    }

    @Test
    void positionsQueryRejectsHedgePositionSide() {
        AccountService service = new AccountService(new FakeAccountRepository(), new PositionCalculator());

        assertThatThrownBy(() -> service.positions(1001L, "SHORT"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("hedge-mode positionSide is not supported; use NET");
    }

    @Test
    void sameTradeIdFromDifferentSymbolsIsProcessedIndependently() {
        FakeAccountRepository repository = new FakeAccountRepository();
        repository.feeSnapshots.put(9002L, new OrderFeeSnapshot(2L, 5L));
        repository.feeSnapshots.put(9001L, new OrderFeeSnapshot(2L, 5L));
        repository.feeSnapshots.put(9003L, new OrderFeeSnapshot(2L, 5L));
        repository.feeSnapshots.put(9004L, new OrderFeeSnapshot(2L, 5L));
        AccountService service = new AccountService(repository, new PositionCalculator());

        MatchTradeEvent btcTrade = new MatchTradeEvent(
                9301L,
                9101L,
                "BTC-USDT",
                9002L,
                1L,
                2002L,
                OrderSide.BUY,
                9001L,
                1L,
                1001L,
                600_000L,
                3L,
                true,
                false,
                EVENT_TIME);
        MatchTradeEvent ethTrade = new MatchTradeEvent(
                9301L,
                9102L,
                "ETH-USDT",
                9003L,
                1L,
                2002L,
                OrderSide.BUY,
                9004L,
                1L,
                1001L,
                30_000L,
                5L,
                true,
                false,
                EVENT_TIME.plusSeconds(1));

        service.processTrade(btcTrade);
        service.processTrade(ethTrade);

        assertThat(repository.positionState(2002L, "BTC-USDT"))
                .isEqualTo(new PositionState(3L, 1L, 600_000L, 0L));
        assertThat(repository.positionState(2002L, "ETH-USDT"))
                .isEqualTo(new PositionState(5L, 1L, 30_000L, 0L));
        assertThat(repository.positionState(1001L, "BTC-USDT"))
                .isEqualTo(new PositionState(-3L, 1L, 600_000L, 0L));
        assertThat(repository.positionState(1001L, "ETH-USDT"))
                .isEqualTo(new PositionState(-5L, 1L, 30_000L, 0L));
        assertThat(repository.tradeProcessingAttempts).isEqualTo(2);
        assertThat(repository.positionUpdates).isEqualTo(4);
    }

    @Test
    void positionMarginAdjustmentOnlySupportsIsolatedMarginMode() {
        AccountService service = new AccountService(new FakeAccountRepository(), new PositionCalculator());

        assertThatThrownBy(() -> service.adjustPositionMargin(new PositionMarginAdjustmentRequest(
                1001L, "BTC-USDT", MarginMode.CROSS, 100L, "cross-margin-ref", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("only supports ISOLATED");
    }

    @Test
    void positionMarginAdjustmentEnqueuesPositionUpdateTrigger() {
        FakeAccountRepository repository = new FakeAccountRepository();
        repository.positions.put(new PositionKey(1001L, "BTC-USDT", MarginMode.ISOLATED),
                new PositionState(3L, 1L, 600_000L, 0L));
        FakeOutboxRepository outboxRepository = new FakeOutboxRepository();
        AccountProperties properties = new AccountProperties();
        properties.getKafka().setPositionEventsTopic("surprising.account.position.events.v1");
        AccountService service = new AccountService(repository, new PositionCalculator(), null,
                properties, outboxRepository);

        PositionMarginAdjustmentResponse response = service.adjustPositionMargin(new PositionMarginAdjustmentRequest(
                1001L, "BTC-USDT", MarginMode.ISOLATED, 500L, "margin-add-1", null));

        assertThat(response.positionMarginUnits()).isEqualTo(1_500L);
        assertThat(repository.positionMarginAdjustmentCalls).isEqualTo(1);
        assertThat(outboxRepository.calls).singleElement().satisfies(call -> {
            assertThat(call.topic()).isEqualTo("surprising.account.position.events.v1");
            assertThat(call.tradeId()).isZero();
            assertThat(call.position().userId()).isEqualTo(1001L);
            assertThat(call.position().symbol()).isEqualTo("BTC-USDT");
            assertThat(call.position().marginMode()).isEqualTo(MarginMode.ISOLATED);
            assertThat(call.position().positionSide()).isEqualTo(PositionSide.NET);
            assertThat(call.traceId()).isNotBlank();
        });
    }

    @Test
    void enqueuesPositionUpdateEventsAfterBothSidesAreSettled() {
        FakeAccountRepository repository = new FakeAccountRepository();
        repository.feeSnapshots.put(9007L, new OrderFeeSnapshot(2L, 5L));
        repository.feeSnapshots.put(9008L, new OrderFeeSnapshot(2L, 5L));
        FakeOutboxRepository outboxRepository = new FakeOutboxRepository();
        AccountProperties properties = new AccountProperties();
        properties.getKafka().setPositionEventsTopic("surprising.account.position.events.v1");
        AccountService service = new AccountService(repository, new PositionCalculator(), null,
                properties, outboxRepository);

        MatchTradeEvent trade = new MatchTradeEvent(
                9401L,
                9104L,
                "BTC-USDT",
                9007L,
                1L,
                2002L,
                OrderSide.BUY,
                9008L,
                1L,
                1001L,
                600_000L,
                3L,
                true,
                true,
                EVENT_TIME.plusSeconds(3),
                "trace-9401");

        service.processTrade(trade);

        assertThat(outboxRepository.calls).hasSize(2);
        assertThat(outboxRepository.calls)
                .extracting(PositionUpdatedCall::topic)
                .containsOnly("surprising.account.position.events.v1");
        assertThat(outboxRepository.calls)
                .extracting(call -> call.position().userId())
                .containsExactly(2002L, 1001L);
        assertThat(outboxRepository.calls)
                .extracting(PositionUpdatedCall::tradeId)
                .containsExactly(9401L, 9401L);
        assertThat(outboxRepository.calls)
                .extracting(PositionUpdatedCall::traceId)
                .containsExactly("trace-9401", "trace-9401");
        assertThat(repository.positionUpdates).isEqualTo(2);
    }

    @Test
    void closingTradeReleasesPositionMarginAndSettlesRealizedPnlForBothSides() {
        FakeAccountRepository repository = new FakeAccountRepository();
        repository.feeSnapshots.put(9003L, new OrderFeeSnapshot(2L, 5L));
        repository.feeSnapshots.put(9004L, new OrderFeeSnapshot(2L, 5L));
        repository.positions.put(new PositionKey(2002L, "BTC-USDT", MarginMode.CROSS),
                new PositionState(3L, 1L, 600_000L, 0L));
        repository.positions.put(new PositionKey(1001L, "BTC-USDT", MarginMode.CROSS),
                new PositionState(-3L, 1L, 600_000L, 0L));
        AccountService service = new AccountService(repository, new PositionCalculator());

        MatchTradeEvent close = new MatchTradeEvent(
                9202L,
                9102L,
                "BTC-USDT",
                9003L,
                1L,
                2002L,
                OrderSide.SELL,
                9004L,
                1L,
                1001L,
                610_000L,
                2L,
                true,
                true,
                EVENT_TIME.plusSeconds(1));

        service.processTrade(close);

        assertThat(repository.positionState(2002L, "BTC-USDT"))
                .isEqualTo(new PositionState(1L, 1L, 600_000L, 20_000L));
        assertThat(repository.positionState(1001L, "BTC-USDT"))
                .isEqualTo(new PositionState(-1L, 1L, 600_000L, -20_000L));
        assertThat(repository.releasedPositionMargin)
                .containsEntry(new PositionKey(2002L, "BTC-USDT", MarginMode.CROSS), 2L)
                .containsEntry(new PositionKey(1001L, "BTC-USDT", MarginMode.CROSS), 2L);
        assertThat(repository.releasedOrderMargin).containsEntry(9003L, 2L).containsEntry(9004L, 2L);
        assertThat(repository.pnlByUser).containsEntry(2002L, 20_000L).containsEntry(1001L, -20_000L);
        assertThat(repository.feeByUser).containsEntry(2002L, -7L).containsEntry(1001L, -3L);
        assertThat(repository.positionUpdates).isEqualTo(2);
    }

    @Test
    void liquidationCloseCollectsActualFeeAndEnqueuesInsuranceEvent() {
        FakeAccountRepository repository = new FakeAccountRepository();
        repository.feeSnapshots.put(9010L, new OrderFeeSnapshot(0L, 0L));
        repository.feeSnapshots.put(9011L, new OrderFeeSnapshot(0L, 0L));
        repository.positions.put(new PositionKey(2002L, "BTC-USDT", MarginMode.CROSS),
                new PositionState(3L, 1L, 600_000L, 0L));
        repository.liquidationFeeContexts.put(9010L, new LiquidationFeeContext(6001L, 9401L, 3_000L));
        FakeOutboxRepository outboxRepository = new FakeOutboxRepository();
        AccountProperties properties = new AccountProperties();
        properties.getKafka().setPositionEventsTopic("surprising.account.position.events.v1");
        properties.getKafka().setLiquidationFeeEventsTopic("surprising.account.liquidation-fee.events.v1");
        AccountService service = new AccountService(repository, new PositionCalculator(), null,
                properties, outboxRepository);

        MatchTradeEvent close = new MatchTradeEvent(
                9205L,
                9105L,
                "BTC-USDT",
                9010L,
                1L,
                2002L,
                OrderSide.SELL,
                9011L,
                1L,
                1001L,
                600_000L,
                2L,
                true,
                false,
                EVENT_TIME.plusSeconds(4),
                "trace-liquidation-fee");

        service.processTrade(close);

        assertThat(repository.liquidationFeeByUser).containsEntry(2002L, 3_600L);
        assertThat(outboxRepository.liquidationFeeCalls).singleElement().satisfies(call -> {
            assertThat(call.topic()).isEqualTo("surprising.account.liquidation-fee.events.v1");
            assertThat(call.tradeId()).isEqualTo(9205L);
            assertThat(call.orderId()).isEqualTo(9010L);
            assertThat(call.liquidationOrderId()).isEqualTo(6001L);
            assertThat(call.candidateId()).isEqualTo(9401L);
            assertThat(call.asset()).isEqualTo("USDT");
            assertThat(call.amountUnits()).isEqualTo(3_600L);
            assertThat(call.traceId()).isEqualTo("trace-liquidation-fee");
        });
    }

    @Test
    void flippingTradeClosesOldExposureBeforeConsumingNewOpeningMargin() {
        FakeAccountRepository repository = new FakeAccountRepository();
        repository.feeSnapshots.put(9005L, new OrderFeeSnapshot(2L, 5L));
        repository.feeSnapshots.put(9006L, new OrderFeeSnapshot(2L, 5L));
        repository.positions.put(new PositionKey(2002L, "BTC-USDT", MarginMode.CROSS),
                new PositionState(5L, 1L, 100L, 0L));
        AccountService service = new AccountService(repository, new PositionCalculator());

        MatchTradeEvent flip = new MatchTradeEvent(
                9203L,
                9103L,
                "BTC-USDT",
                9005L,
                2L,
                2002L,
                OrderSide.SELL,
                9006L,
                2L,
                1001L,
                90L,
                8L,
                true,
                true,
                EVENT_TIME.plusSeconds(2));

        service.processTrade(flip);

        assertThat(repository.positionState(2002L, "BTC-USDT"))
                .isEqualTo(new PositionState(-3L, 2L, 90L, -50L));
        assertThat(repository.positionState(1001L, "BTC-USDT"))
                .isEqualTo(new PositionState(8L, 2L, 90L, 0L));
        assertThat(repository.releasedPositionMargin)
                .containsEntry(new PositionKey(2002L, "BTC-USDT", MarginMode.CROSS), 5L);
        assertThat(repository.releasedOrderMargin).containsEntry(9005L, 5L);
        assertThat(repository.consumedOrderMargin).containsEntry(9005L, 3L).containsEntry(9006L, 8L);
        assertThat(repository.consumedOrderMarginUnits).containsEntry(9005L, 3L)
                .containsEntry(9006L, 8L);
        assertThat(repository.releaseSweepByOrder).containsEntry(9005L, false);
        assertThat(repository.consumeSweepByOrder).containsEntry(9005L, true).containsEntry(9006L, true);
        assertThat(repository.pnlByUser).containsEntry(2002L, -50L);
        assertThat(repository.positionUpdates).isEqualTo(2);
    }

    private static final class FakeAccountRepository extends AccountRepository {

        private final Map<PositionKey, PositionState> positions = new HashMap<>();
        private final Map<Long, OrderFeeSnapshot> feeSnapshots = new HashMap<>();
        private final Map<Long, Long> consumedOrderMargin = new HashMap<>();
        private final Map<Long, Long> consumedOrderMarginUnits = new HashMap<>();
        private final Map<Long, Long> releasedOrderMargin = new HashMap<>();
        private final Map<Long, Boolean> consumeSweepByOrder = new HashMap<>();
        private final Map<Long, Boolean> releaseSweepByOrder = new HashMap<>();
        private final Map<PositionKey, Long> releasedPositionMargin = new HashMap<>();
        private final Map<Long, Long> pnlByUser = new HashMap<>();
        private final Map<Long, Long> feeByUser = new HashMap<>();
        private final Map<Long, LiquidationFeeContext> liquidationFeeContexts = new HashMap<>();
        private final Map<Long, Long> liquidationFeeByUser = new HashMap<>();
        private final Map<String, Integer> contractSpecLoads = new HashMap<>();
        private final Map<Long, Integer> feeSnapshotLoads = new HashMap<>();
        private final Map<String, InstrumentType> instrumentTypes = new HashMap<>();
        private final Map<String, SpotInstrumentSpec> spotSpecs = new HashMap<>();
        private final List<SpotSettlementCall> spotSettlements = new ArrayList<>();
        private final Set<ProcessedTradeKey> processedTradeIds = new HashSet<>();
        private int tradeProcessingAttempts;
        private int positionUpdates;
        private int positionMarginAdjustmentCalls;

        private FakeAccountRepository() {
            super(null, null);
        }

        @Override
        public ContractSpec contractSpec(String symbol, long instrumentVersion) {
            assertThat(symbol).isIn("BTC-USDT", "ETH-USDT");
            contractSpecLoads.merge(symbol + ":" + instrumentVersion, 1, Integer::sum);
            return new ContractSpec(instrumentVersion, ContractType.LINEAR_PERPETUAL,
                    "USDT", 1L, 100_000_000L, 100_000_000L, 10_000L, 2L, 5L);
        }

        @Override
        public InstrumentType instrumentType(String symbol, long instrumentVersion) {
            return instrumentTypes.getOrDefault(symbol + ":" + instrumentVersion, InstrumentType.PERPETUAL);
        }

        @Override
        public SpotInstrumentSpec spotInstrumentSpec(String symbol, long instrumentVersion) {
            return Optional.ofNullable(spotSpecs.get(symbol + ":" + instrumentVersion))
                    .orElseThrow(() -> new IllegalStateException("missing spot spec " + symbol));
        }

        @Override
        public OrderFeeSnapshot orderFeeSnapshot(long orderId, long userId, String symbol) {
            feeSnapshotLoads.merge(orderId, 1, Integer::sum);
            return Optional.ofNullable(feeSnapshots.get(orderId))
                    .orElseThrow(() -> new IllegalStateException("missing fee snapshot " + orderId));
        }

        @Override
        public Optional<LiquidationFeeContext> liquidationFeeContext(long orderId, long userId, String symbol) {
            return Optional.ofNullable(liquidationFeeContexts.get(orderId));
        }

        @Override
        public boolean markTradeProcessing(long tradeId, String symbol) {
            tradeProcessingAttempts++;
            return processedTradeIds.add(new ProcessedTradeKey(symbol, tradeId));
        }

        @Override
        public PositionState lockPosition(long userId, String symbol, MarginMode marginMode) {
            return positions.getOrDefault(new PositionKey(userId, symbol, marginMode),
                    new PositionState(0L, 0L, 0L, 0L));
        }

        @Override
        public Optional<PositionResponse> position(long userId, String symbol, MarginMode marginMode) {
            return Optional.ofNullable(positions.get(new PositionKey(userId, symbol, marginMode)))
                    .map(state -> new PositionResponse(userId, symbol, state.instrumentVersion(), marginMode,
                            state.signedQuantitySteps(), state.entryPriceTicks(), state.realizedPnlUnits(),
                            EVENT_TIME));
        }

        @Override
        public PositionMarginAdjustmentResponse adjustIsolatedPositionMargin(long userId,
                                                                             String symbol,
                                                                             long amountUnits,
                                                                             String referenceId,
                                                                             String reason,
                                                                             java.time.Duration maxRiskSnapshotAge,
                                                                             long removalBufferPpm) {
            positionMarginAdjustmentCalls++;
            assertThat(reason).isEqualTo("ADD_POSITION_MARGIN");
            assertThat(maxRiskSnapshotAge).isEqualTo(java.time.Duration.ofSeconds(10));
            assertThat(removalBufferPpm).isEqualTo(50_000L);
            return new PositionMarginAdjustmentResponse(userId, symbol, "USDT", MarginMode.ISOLATED, amountUnits,
                    1_500L, 10_000L, 1_500L, 11_500L, referenceId, EVENT_TIME);
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
            consumedOrderMargin.merge(orderId, openSteps, Long::sum);
            consumedOrderMarginUnits.merge(orderId, actualMarginUnits, Long::sum);
            consumeSweepByOrder.put(orderId, sweepRemainder);
        }

        @Override
        public void releaseOrderMargin(long orderId,
                                       long userId,
                                       String symbol,
                                       long closeSteps,
                                       boolean sweepRemainder,
                                       Instant now) {
            releasedOrderMargin.merge(orderId, closeSteps, Long::sum);
            releaseSweepByOrder.put(orderId, sweepRemainder);
        }

        @Override
        public void releasePositionMargin(long userId,
                                          String symbol,
                                          MarginMode marginMode,
                                          long closeSteps,
                                          long positionAbsSteps,
                                          Instant now) {
            releasedPositionMargin.merge(new PositionKey(userId, symbol, marginMode), closeSteps, Long::sum);
            assertThat(closeSteps).isLessThanOrEqualTo(positionAbsSteps);
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
            assertThat(asset).isEqualTo("USDT");
            assertThat(symbol).isIn("BTC-USDT", "ETH-USDT");
            assertThat(marginMode).isIn(MarginMode.CROSS, MarginMode.ISOLATED);
            pnlByUser.merge(userId, realizedPnlDeltaUnits, Long::sum);
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
            assertThat(accountType).isEqualTo(AccountType.USDT_PERPETUAL);
            settleRealizedPnl(userId, asset, orderId, tradeId, symbol, marginMode, realizedPnlDeltaUnits, now);
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
            assertThat(asset).isEqualTo("USDT");
            assertThat(symbol).isIn("BTC-USDT", "ETH-USDT");
            assertThat(marginMode).isIn(MarginMode.CROSS, MarginMode.ISOLATED);
            OrderFeeSnapshot snapshot = feeSnapshots.get(orderId);
            assertThat(feeRatePpm).isEqualTo("TAKER_FEE".equals(reason)
                    ? snapshot.takerFeeRatePpm()
                    : snapshot.makerFeeRatePpm());
            feeByUser.merge(userId, feeDeltaUnits, Long::sum);
            if (orderId == 9002L || orderId == 9003L || orderId == 9005L
                    || orderId == 9007L || orderId == 9010L) {
                assertThat(reason).isEqualTo("TAKER_FEE");
            } else {
                assertThat(reason).isEqualTo("MAKER_FEE");
            }
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
            assertThat(accountType).isEqualTo(AccountType.USDT_PERPETUAL);
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
            spotSettlements.add(new SpotSettlementCall(userId, orderId, tradeId, symbol, side, priceTicks,
                    quantitySteps, spec, feeRatePpm, feeReason, orderCompleted, now));
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
            assertThat(asset).isEqualTo("USDT");
            assertThat(symbol).isEqualTo("BTC-USDT");
            assertThat(marginMode).isEqualTo(MarginMode.CROSS);
            liquidationFeeByUser.merge(userId, requestedFeeUnits, Long::sum);
            return Optional.of(new LiquidationFeeSettlement(context.liquidationOrderId(), context.candidateId(),
                    requestedFeeUnits, context.feeRatePpm()));
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
            assertThat(accountType).isEqualTo(AccountType.USDT_PERPETUAL);
            return settleLiquidationFee(userId, asset, orderId, tradeId, symbol, marginMode, requestedFeeUnits,
                    context, now);
        }

        @Override
        public PositionResponse updatePosition(long userId,
                                               String symbol,
                                               MarginMode marginMode,
                                               PositionState state,
                                               Instant now) {
            PositionState previous = positions.getOrDefault(new PositionKey(userId, symbol, marginMode),
                    new PositionState(0L, 0L, 0L, 0L));
            return updatePosition(userId, symbol, marginMode, state, previous.signedQuantitySteps(), now);
        }

        @Override
        public PositionResponse updatePosition(long userId,
                                               String symbol,
                                               MarginMode marginMode,
                                               PositionState state,
                                               long previousSignedQuantitySteps,
                                               Instant now) {
            positionUpdates++;
            positions.put(new PositionKey(userId, symbol, marginMode), state);
            return new PositionResponse(userId, symbol, state.instrumentVersion(), marginMode,
                    state.signedQuantitySteps(), state.entryPriceTicks(), state.realizedPnlUnits(), now);
        }

        @Override
        public Optional<BalanceResponse> balance(long userId, String asset) {
            return Optional.empty();
        }

        private PositionState positionState(long userId, String symbol) {
            return positions.get(new PositionKey(userId, symbol, MarginMode.CROSS));
        }
    }

    private static final class FakeOutboxRepository extends AccountOutboxRepository {

        private final List<PositionUpdatedCall> calls = new ArrayList<>();
        private final List<LiquidationFeeSettledCall> liquidationFeeCalls = new ArrayList<>();

        private FakeOutboxRepository() {
            super(null, null, null);
        }

        @Override
        public PositionUpdatedEvent enqueuePositionUpdated(String topic,
                                                           long tradeId,
                                                           PositionResponse position,
                                                           Instant now,
                                                           String traceId) {
            calls.add(new PositionUpdatedCall(topic, tradeId, position, now, traceId));
            return new PositionUpdatedEvent(calls.size(), tradeId, position.userId(), position.symbol(),
                    position.instrumentVersion(), position.marginMode(), position.signedQuantitySteps(),
                    position.entryPriceTicks(), position.realizedPnlUnits(), now, traceId);
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
                                                                       String asset,
                                                                       long amountUnits,
                                                                       long feeRatePpm,
                                                                       Instant now,
                                                                       String traceId) {
            liquidationFeeCalls.add(new LiquidationFeeSettledCall(topic, tradeId, orderId, liquidationOrderId,
                    candidateId, userId, symbol, marginMode, asset, amountUnits, feeRatePpm, now, traceId));
            return new LiquidationFeeSettledEvent(liquidationFeeCalls.size(), tradeId, orderId,
                    liquidationOrderId, candidateId, userId, symbol, marginMode, asset, amountUnits, feeRatePpm,
                    now, traceId);
        }
    }

    private record PositionUpdatedCall(String topic,
                                       long tradeId,
                                       PositionResponse position,
                                       Instant eventTime,
                                       String traceId) {
    }

    private record LiquidationFeeSettledCall(String topic,
                                             long tradeId,
                                             long orderId,
                                             long liquidationOrderId,
                                             long candidateId,
                                             long userId,
                                             String symbol,
                                             MarginMode marginMode,
                                             String asset,
                                             long amountUnits,
                                             long feeRatePpm,
                                             Instant eventTime,
                                             String traceId) {
    }

    private record SpotSettlementCall(long userId,
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
                                      Instant eventTime) {
    }

    private record PositionKey(long userId, String symbol, MarginMode marginMode) {
    }

    private record ProcessedTradeKey(String symbol, long tradeId) {
    }
}
