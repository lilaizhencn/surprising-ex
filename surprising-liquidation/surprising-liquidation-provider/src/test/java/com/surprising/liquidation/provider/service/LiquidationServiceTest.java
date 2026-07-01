package com.surprising.liquidation.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.surprising.liquidation.api.model.LiquidationOrderResponse;
import com.surprising.liquidation.api.model.LiquidationOrderStatus;
import com.surprising.liquidation.provider.config.LiquidationProperties;
import com.surprising.liquidation.provider.model.ClaimedCandidate;
import com.surprising.liquidation.provider.model.LiquidationCloseState;
import com.surprising.liquidation.provider.model.LiquidationSizingInput;
import com.surprising.liquidation.provider.repository.LiquidationOrderRepository;
import com.surprising.liquidation.provider.repository.LiquidationRepository;
import com.surprising.liquidation.provider.repository.LiquidationSequenceRepository;
import com.surprising.risk.api.model.LiquidationCandidateEvent;
import com.surprising.risk.api.model.RiskStatus;
import com.surprising.trading.api.model.OrderCommandEvent;
import com.surprising.trading.api.model.OrderCommandType;
import com.surprising.trading.api.model.MatchResultEvent;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.OrderStatus;
import com.surprising.trading.api.model.OrderType;
import com.surprising.trading.api.model.TimeInForce;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class LiquidationServiceTest {

    @Test
    void submitsVersionedReduceOnlyCloseOrderAfterPreemptingUserReduceOnlyOrders() {
        FakeLiquidationRepository liquidationRepository = new FakeLiquidationRepository();
        FakeLiquidationOrderRepository orderRepository = new FakeLiquidationOrderRepository();
        orderRepository.openReduceOnlyCloseOrders = 2;
        FakeSequenceRepository sequenceRepository = new FakeSequenceRepository();
        LiquidationService service = new LiquidationService(new ObjectMapper(), new LiquidationProperties(),
                liquidationRepository, orderRepository, sequenceRepository, new LiquidationSizingPolicy());

        service.processCandidate(new LiquidationCandidateEvent(9401L, 9301L, 2002L, "BTC-USDT", 8L,
                "USDT", 10L, 590_000L, -200_000_000L, 88_500_000L, 1_100_000L,
                Instant.parse("2026-07-01T00:00:00Z")));

        assertThat(orderRepository.commands).hasSize(1);
        OrderCommandEvent command = orderRepository.commands.get(0);
        assertThat(command.commandType()).isEqualTo(OrderCommandType.PLACE);
        assertThat(command.orderId()).isEqualTo(7001L);
        assertThat(command.userId()).isEqualTo(2002L);
        assertThat(command.symbol()).isEqualTo("BTC-USDT");
        assertThat(command.instrumentVersion()).isEqualTo(8L);
        assertThat(command.side()).isEqualTo(OrderSide.SELL);
        assertThat(command.orderType()).isEqualTo(OrderType.MARKET);
        assertThat(command.timeInForce()).isEqualTo(TimeInForce.IOC);
        assertThat(command.quantitySteps()).isEqualTo(5L);
        assertThat(command.reduceOnly()).isTrue();
        assertThat(orderRepository.preemptions).containsExactly("2002:BTC-USDT:8:SELL");

        assertThat(liquidationRepository.markedStatuses).isEmpty();
        assertThat(liquidationRepository.orders).hasSize(1);
        LiquidationOrderResponse audit = liquidationRepository.orders.get(0);
        assertThat(audit.orderId()).isEqualTo(7001L);
        assertThat(audit.side()).isEqualTo(OrderSide.SELL);
        assertThat(audit.quantitySteps()).isEqualTo(5L);
        assertThat(audit.status()).isEqualTo(LiquidationOrderStatus.SUBMITTED);
        assertThat(audit.reason()).isEqualTo("PARTIAL_LIQUIDATION");
        assertThat(liquidationRepository.sizingInputs).containsExactly(
                new LiquidationSizingInput(10L, 10L, 40_000L, 400L, 0L));
    }

    @Test
    void disabledExecutionDoesNotClaimCandidateOrCreateOrder() {
        FakeLiquidationRepository liquidationRepository = new FakeLiquidationRepository();
        FakeLiquidationOrderRepository orderRepository = new FakeLiquidationOrderRepository();
        LiquidationProperties properties = new LiquidationProperties();
        properties.getExecution().setEnabled(false);
        LiquidationService service = new LiquidationService(new ObjectMapper(), properties,
                liquidationRepository, orderRepository, new FakeSequenceRepository(), new LiquidationSizingPolicy());

        assertThatThrownBy(() -> service.processCandidate(new LiquidationCandidateEvent(9401L, 9301L,
                2002L, "BTC-USDT", 8L, "USDT", 10L, 590_000L, -200_000_000L,
                88_500_000L, 1_100_000L, Instant.parse("2026-07-01T00:00:00Z"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("liquidation execution is disabled");

        assertThat(liquidationRepository.claimAttempts).isZero();
        assertThat(liquidationRepository.markedStatuses).isEmpty();
        assertThat(liquidationRepository.orders).isEmpty();
        assertThat(orderRepository.commands).isEmpty();
        assertThat(orderRepository.preemptions).isEmpty();
    }

    @Test
    void existingReduceOnlyOrdersDoNotBlockLiquidationSizing() {
        FakeLiquidationRepository liquidationRepository = new FakeLiquidationRepository();
        liquidationRepository.pendingCloseSteps = 10L;
        FakeLiquidationOrderRepository orderRepository = new FakeLiquidationOrderRepository();
        orderRepository.openReduceOnlyCloseOrders = 10;
        LiquidationService service = new LiquidationService(new ObjectMapper(), new LiquidationProperties(),
                liquidationRepository, orderRepository, new FakeSequenceRepository(), new LiquidationSizingPolicy());

        service.processCandidate(new LiquidationCandidateEvent(9401L, 9301L, 2002L, "BTC-USDT", 8L,
                "USDT", 10L, 590_000L, -200_000_000L, 88_500_000L, 1_100_000L,
                Instant.parse("2026-07-01T00:00:00Z")));

        assertThat(orderRepository.preemptions).containsExactly("2002:BTC-USDT:8:SELL");
        assertThat(orderRepository.commands).hasSize(1);
        assertThat(orderRepository.commands.get(0).quantitySteps()).isEqualTo(5L);
        assertThat(liquidationRepository.markedStatuses).isEmpty();
        assertThat(liquidationRepository.orders).hasSize(1);
        assertThat(liquidationRepository.orders.get(0).quantitySteps()).isEqualTo(5L);
        assertThat(liquidationRepository.orders.get(0).reason()).isEqualTo("PARTIAL_LIQUIDATION");
        assertThat(liquidationRepository.sizingInputs).containsExactly(
                new LiquidationSizingInput(10L, 10L, 40_000L, 400L, 0L));
    }

    @Test
    void failsCandidateTransactionWhenLiquidationAuditInsertIsSkipped() {
        FakeLiquidationRepository liquidationRepository = new FakeLiquidationRepository();
        liquidationRepository.insertAudit = false;
        FakeLiquidationOrderRepository orderRepository = new FakeLiquidationOrderRepository();
        LiquidationService service = new LiquidationService(new ObjectMapper(), new LiquidationProperties(),
                liquidationRepository, orderRepository, new FakeSequenceRepository(), new LiquidationSizingPolicy());

        assertThatThrownBy(() -> service.processCandidate(new LiquidationCandidateEvent(9401L, 9301L,
                2002L, "BTC-USDT", 8L, "USDT", 10L, 590_000L, -200_000_000L,
                88_500_000L, 1_100_000L, Instant.parse("2026-07-01T00:00:00Z"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("liquidation order audit");

        assertThat(liquidationRepository.markedStatuses).isEmpty();
    }

    @Test
    void marksLiquidationCompletedOnlyAfterFilledMatchResult() {
        FakeLiquidationRepository liquidationRepository = new FakeLiquidationRepository();
        LiquidationService service = new LiquidationService(new ObjectMapper(), new LiquidationProperties(),
                liquidationRepository, new FakeLiquidationOrderRepository(), new FakeSequenceRepository(),
                new LiquidationSizingPolicy());

        service.processMatchResult(new MatchResultEvent(9101L, 7001L, 2002L, "BTC-USDT", 8L,
                OrderCommandType.PLACE, "SUCCESS", 5L, OrderStatus.FILLED,
                Instant.parse("2026-07-01T00:00:01Z"), List.of(), "trace-liq"));

        assertThat(liquidationRepository.lifecycleUpdates)
                .containsExactly("7001:FILLED:COMPLETED");
    }

    @Test
    void cancelsLiquidationCandidateWhenMatchResultDoesNotFill() {
        FakeLiquidationRepository liquidationRepository = new FakeLiquidationRepository();
        LiquidationService service = new LiquidationService(new ObjectMapper(), new LiquidationProperties(),
                liquidationRepository, new FakeLiquidationOrderRepository(), new FakeSequenceRepository(),
                new LiquidationSizingPolicy());

        service.processMatchResult(new MatchResultEvent(9101L, 7002L, 2002L, "BTC-USDT", 8L,
                OrderCommandType.PLACE, "SUCCESS", 0L, OrderStatus.CANCELED,
                Instant.parse("2026-07-01T00:00:02Z"), List.of(), "trace-liq"));

        assertThat(liquidationRepository.lifecycleUpdates)
                .containsExactly("7002:CANCELED:CANCELED");
    }

    private static final class FakeLiquidationRepository extends LiquidationRepository {
        private long pendingCloseSteps = 4L;
        private boolean insertAudit = true;
        private final List<String> markedStatuses = new ArrayList<>();
        private final List<LiquidationOrderResponse> orders = new ArrayList<>();
        private final List<LiquidationSizingInput> sizingInputs = new ArrayList<>();
        private final List<String> lifecycleUpdates = new ArrayList<>();
        private int claimAttempts;

        private FakeLiquidationRepository() {
            super(null);
        }

        @Override
        public Optional<ClaimedCandidate> claimCandidate(long candidateId) {
            claimAttempts++;
            return Optional.of(new ClaimedCandidate(candidateId, 9301L, 2002L, "BTC-USDT", 8L,
                    "USDT", 10L, 590_000L, 1_100_000L));
        }

        @Override
        public RiskStatus latestRiskStatus(long userId, String settleAsset, Duration maxSnapshotAge) {
            assertThat(maxSnapshotAge).isEqualTo(Duration.ofSeconds(5));
            return RiskStatus.LIQUIDATION;
        }

        @Override
        public Optional<LiquidationCloseState> lockCloseState(long userId, String symbol, long instrumentVersion) {
            assertThat(instrumentVersion).isEqualTo(8L);
            return Optional.of(new LiquidationCloseState(10L));
        }

        @Override
        public long lockOpenReduceOnlySteps(long userId, String symbol, long instrumentVersion, OrderSide closeSide) {
            assertThat(instrumentVersion).isEqualTo(8L);
            assertThat(closeSide).isEqualTo(OrderSide.SELL);
            return pendingCloseSteps;
        }

        @Override
        public Optional<LiquidationSizingInput> sizingInput(long userId,
                                                           String symbol,
                                                           long instrumentVersion,
                                                           long availableCloseSteps) {
            LiquidationSizingInput input = new LiquidationSizingInput(10L, availableCloseSteps,
                    40_000L, 400L, 0L);
            sizingInputs.add(input);
            return Optional.of(input);
        }

        @Override
        public void markCandidate(long candidateId, String status) {
            markedStatuses.add(status);
        }

        @Override
        public Optional<Long> updateOrderLifecycle(long orderId,
                                                   LiquidationOrderStatus orderStatus,
                                                   String candidateStatus) {
            lifecycleUpdates.add(orderId + ":" + orderStatus + ":" + candidateStatus);
            return Optional.of(9401L);
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
            if (!insertAudit) {
                return false;
            }
            orders.add(new LiquidationOrderResponse(liquidationOrderId, candidateId, orderId, userId,
                    symbol, side, quantitySteps, status, reason, now));
            return true;
        }
    }

    private static final class FakeLiquidationOrderRepository extends LiquidationOrderRepository {
        private final List<OrderCommandEvent> commands = new ArrayList<>();
        private final List<String> preemptions = new ArrayList<>();
        private int openReduceOnlyCloseOrders;

        private FakeLiquidationOrderRepository() {
            super(null, null, new LiquidationProperties());
        }

        @Override
        public int cancelOpenReduceOnlyCloseOrders(long userId,
                                                   String symbol,
                                                   long instrumentVersion,
                                                   OrderSide closeSide,
                                                   Instant now,
                                                   Function<Object, String> serializer) {
            preemptions.add(userId + ":" + symbol + ":" + instrumentVersion + ":" + closeSide);
            return openReduceOnlyCloseOrders;
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
            OrderCommandEvent command = new OrderCommandEvent(OrderCommandType.PLACE, 8001L, 7001L, userId,
                    "LIQ-" + candidateId, symbol, instrumentVersion, side, OrderType.MARKET,
                    TimeInForce.IOC, 0L, quantitySteps, true, false, now);
            commands.add(command);
            return command;
        }
    }

    private static final class FakeSequenceRepository extends LiquidationSequenceRepository {
        private long nextLiquidationId = 6001L;

        private FakeSequenceRepository() {
            super(null);
        }

        @Override
        public long nextLiquidationSequence(String sequenceName) {
            return nextLiquidationId++;
        }
    }
}
