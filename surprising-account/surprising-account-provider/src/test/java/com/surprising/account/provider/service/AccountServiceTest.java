package com.surprising.account.provider.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.account.api.model.PositionUpdatedEvent;
import com.surprising.account.api.model.BalanceResponse;
import com.surprising.account.api.model.PositionResponse;
import com.surprising.account.provider.config.AccountProperties;
import com.surprising.account.provider.model.ContractSpec;
import com.surprising.account.provider.model.PositionState;
import com.surprising.account.provider.repository.AccountOutboxRepository;
import com.surprising.account.provider.repository.AccountRepository;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.trading.api.model.MatchTradeEvent;
import com.surprising.trading.api.model.OrderSide;
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
    void sameTradeIdFromDifferentSymbolsIsProcessedIndependently() {
        FakeAccountRepository repository = new FakeAccountRepository();
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
    void enqueuesPositionUpdateEventsAfterBothSidesAreSettled() {
        FakeAccountRepository repository = new FakeAccountRepository();
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
        repository.positions.put(new PositionKey(2002L, "BTC-USDT"), new PositionState(3L, 1L, 600_000L, 0L));
        repository.positions.put(new PositionKey(1001L, "BTC-USDT"), new PositionState(-3L, 1L, 600_000L, 0L));
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
        assertThat(repository.releasedPositionMargin).containsEntry(new PositionKey(2002L, "BTC-USDT"), 2L)
                .containsEntry(new PositionKey(1001L, "BTC-USDT"), 2L);
        assertThat(repository.releasedOrderMargin).containsEntry(9003L, 2L).containsEntry(9004L, 2L);
        assertThat(repository.pnlByUser).containsEntry(2002L, 20_000L).containsEntry(1001L, -20_000L);
        assertThat(repository.feeByUser).containsEntry(2002L, -7L).containsEntry(1001L, -3L);
        assertThat(repository.positionUpdates).isEqualTo(2);
    }

    @Test
    void flippingTradeClosesOldExposureBeforeConsumingNewOpeningMargin() {
        FakeAccountRepository repository = new FakeAccountRepository();
        repository.positions.put(new PositionKey(2002L, "BTC-USDT"), new PositionState(5L, 1L, 100L, 0L));
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
        assertThat(repository.releasedPositionMargin).containsEntry(new PositionKey(2002L, "BTC-USDT"), 5L);
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
        private final Map<Long, Long> consumedOrderMargin = new HashMap<>();
        private final Map<Long, Long> consumedOrderMarginUnits = new HashMap<>();
        private final Map<Long, Long> releasedOrderMargin = new HashMap<>();
        private final Map<Long, Boolean> consumeSweepByOrder = new HashMap<>();
        private final Map<Long, Boolean> releaseSweepByOrder = new HashMap<>();
        private final Map<PositionKey, Long> releasedPositionMargin = new HashMap<>();
        private final Map<Long, Long> pnlByUser = new HashMap<>();
        private final Map<Long, Long> feeByUser = new HashMap<>();
        private final Set<ProcessedTradeKey> processedTradeIds = new HashSet<>();
        private int tradeProcessingAttempts;
        private int positionUpdates;

        private FakeAccountRepository() {
            super(null, null);
        }

        @Override
        public ContractSpec contractSpec(String symbol, long instrumentVersion) {
            assertThat(symbol).isIn("BTC-USDT", "ETH-USDT");
            return new ContractSpec(instrumentVersion, ContractType.LINEAR_PERPETUAL,
                    "USDT", 1L, 100_000_000L, 100_000_000L, 10_000L, 2L, 5L);
        }

        @Override
        public boolean markTradeProcessing(long tradeId, String symbol) {
            tradeProcessingAttempts++;
            return processedTradeIds.add(new ProcessedTradeKey(symbol, tradeId));
        }

        @Override
        public PositionState lockPosition(long userId, String symbol) {
            return positions.getOrDefault(new PositionKey(userId, symbol), new PositionState(0L, 0L, 0L, 0L));
        }

        @Override
        public void consumeOrderMargin(long orderId,
                                       long userId,
                                       String symbol,
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
                                          long closeSteps,
                                          long positionAbsSteps,
                                          Instant now) {
            releasedPositionMargin.merge(new PositionKey(userId, symbol), closeSteps, Long::sum);
            assertThat(closeSteps).isLessThanOrEqualTo(positionAbsSteps);
        }

        @Override
        public void settleRealizedPnl(long userId,
                                      String asset,
                                      long orderId,
                                      long tradeId,
                                      long realizedPnlDeltaUnits,
                                      Instant now) {
            assertThat(asset).isEqualTo("USDT");
            pnlByUser.merge(userId, realizedPnlDeltaUnits, Long::sum);
        }

        @Override
        public void settleTradeFee(long userId,
                                   String asset,
                                   long orderId,
                                   long tradeId,
                                   long feeDeltaUnits,
                                   String reason,
                                   Instant now) {
            assertThat(asset).isEqualTo("USDT");
            feeByUser.merge(userId, feeDeltaUnits, Long::sum);
            if (orderId == 9002L || orderId == 9003L || orderId == 9005L || orderId == 9007L) {
                assertThat(reason).isEqualTo("TAKER_FEE");
            } else {
                assertThat(reason).isEqualTo("MAKER_FEE");
            }
        }

        @Override
        public PositionResponse updatePosition(long userId, String symbol, PositionState state, Instant now) {
            positionUpdates++;
            positions.put(new PositionKey(userId, symbol), state);
            return new PositionResponse(userId, symbol, state.instrumentVersion(), state.signedQuantitySteps(),
                    state.entryPriceTicks(), state.realizedPnlUnits(), now);
        }

        @Override
        public Optional<BalanceResponse> balance(long userId, String asset) {
            return Optional.empty();
        }

        private PositionState positionState(long userId, String symbol) {
            return positions.get(new PositionKey(userId, symbol));
        }
    }

    private static final class FakeOutboxRepository extends AccountOutboxRepository {

        private final List<PositionUpdatedCall> calls = new ArrayList<>();

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
                    position.instrumentVersion(), position.signedQuantitySteps(), position.entryPriceTicks(),
                    position.realizedPnlUnits(), now, traceId);
        }
    }

    private record PositionUpdatedCall(String topic,
                                       long tradeId,
                                       PositionResponse position,
                                       Instant eventTime,
                                       String traceId) {
    }

    private record PositionKey(long userId, String symbol) {
    }

    private record ProcessedTradeKey(String symbol, long tradeId) {
    }
}
