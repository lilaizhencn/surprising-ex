package com.surprising.liquidation.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.surprising.liquidation.api.model.AdminCursorPage;
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
import com.surprising.liquidation.provider.repository.LiquidationRepository.CanceledCandidate;
import com.surprising.liquidation.provider.repository.LiquidationRepository.LiquidationAdminAction;
import com.surprising.liquidation.provider.repository.LiquidationRepository.LiquidationTimelineEvent;
import com.surprising.liquidation.provider.repository.LiquidationSequenceRepository;
import com.surprising.risk.api.model.LiquidationCandidateEvent;
import com.surprising.risk.api.model.RiskStatus;
import com.surprising.trading.api.model.OrderCommandEvent;
import com.surprising.trading.api.model.OrderCommandType;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.MatchResultEvent;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.OrderStatus;
import com.surprising.trading.api.model.OrderType;
import com.surprising.trading.api.model.TimeInForce;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
                liquidationRepository, orderRepository, sequenceRepository, new LiquidationSizingPolicy(), new LiquidationPriceCalculator());

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
        assertThat(audit.bankruptcyPriceTicks()).isEqualTo(80L);
        assertThat(audit.takeoverPriceTicks()).isEqualTo(80L);
        assertThat(audit.liquidationFeeUnits()).isEqualTo(3L);
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
                liquidationRepository, orderRepository, new FakeSequenceRepository(), new LiquidationSizingPolicy(), new LiquidationPriceCalculator());

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
                liquidationRepository, orderRepository, new FakeSequenceRepository(), new LiquidationSizingPolicy(),
                new LiquidationPriceCalculator());

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
    void crossLiquidationRechecksAccountRiskStatusInsteadOfPositionStatus() {
        FakeLiquidationRepository liquidationRepository = new FakeLiquidationRepository();
        liquidationRepository.positionRiskStatus = RiskStatus.NORMAL;
        FakeLiquidationOrderRepository orderRepository = new FakeLiquidationOrderRepository();
        LiquidationService service = new LiquidationService(new ObjectMapper(), new LiquidationProperties(),
                liquidationRepository, orderRepository, new FakeSequenceRepository(), new LiquidationSizingPolicy(),
                new LiquidationPriceCalculator());

        service.processCandidate(new LiquidationCandidateEvent(9401L, 9301L, 2002L, "BTC-USDT", 8L,
                "USDT", 10L, 590_000L, -200_000_000L, 88_500_000L, 1_100_000L,
                Instant.parse("2026-07-01T00:00:00Z")));

        assertThat(liquidationRepository.accountRiskChecks).isEqualTo(1);
        assertThat(liquidationRepository.positionRiskChecks).isZero();
        assertThat(orderRepository.commands).hasSize(1);
    }

    @Test
    void cancelsCandidateWhenFreshRiskSnapshotNoLongerMatchesLockedPosition() {
        FakeLiquidationRepository liquidationRepository = new FakeLiquidationRepository();
        liquidationRepository.pricingSignedQuantitySteps = 8L;
        FakeLiquidationOrderRepository orderRepository = new FakeLiquidationOrderRepository();
        LiquidationService service = new LiquidationService(new ObjectMapper(), new LiquidationProperties(),
                liquidationRepository, orderRepository, new FakeSequenceRepository(), new LiquidationSizingPolicy(),
                new LiquidationPriceCalculator());

        service.processCandidate(new LiquidationCandidateEvent(9401L, 9301L, 2002L, "BTC-USDT", 8L,
                "USDT", 10L, 590_000L, -200_000_000L, 88_500_000L, 1_100_000L,
                Instant.parse("2026-07-01T00:00:00Z")));

        assertThat(liquidationRepository.markedStatuses).containsExactly("CANCELED");
        assertThat(orderRepository.commands).isEmpty();
        assertThat(liquidationRepository.orders).hasSize(1);
        assertThat(liquidationRepository.orders.get(0).reason()).isEqualTo("RISK_POSITION_CHANGED");
    }

    @Test
    void failsCandidateTransactionWhenLiquidationAuditInsertIsSkipped() {
        FakeLiquidationRepository liquidationRepository = new FakeLiquidationRepository();
        liquidationRepository.insertAudit = false;
        FakeLiquidationOrderRepository orderRepository = new FakeLiquidationOrderRepository();
        LiquidationService service = new LiquidationService(new ObjectMapper(), new LiquidationProperties(),
                liquidationRepository, orderRepository, new FakeSequenceRepository(), new LiquidationSizingPolicy(),
                new LiquidationPriceCalculator());

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
                new LiquidationSizingPolicy(), new LiquidationPriceCalculator());

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
                new LiquidationSizingPolicy(), new LiquidationPriceCalculator());

        service.processMatchResult(new MatchResultEvent(9101L, 7002L, 2002L, "BTC-USDT", 8L,
                OrderCommandType.PLACE, "SUCCESS", 0L, OrderStatus.CANCELED,
                Instant.parse("2026-07-01T00:00:02Z"), List.of(), "trace-liq"));

        assertThat(liquidationRepository.lifecycleUpdates)
                .containsExactly("7002:CANCELED:CANCELED");
    }

    @Test
    void returnsAdminTimelineWithCandidateOrdersAndEvents() {
        FakeLiquidationRepository liquidationRepository = new FakeLiquidationRepository();
        LiquidationOrderResponse order = new LiquidationOrderResponse(6001L, 9401L, 7001L, 2002L,
                "BTC-USDT", MarginMode.CROSS, OrderSide.SELL, 5L, 80L, 80L,
                5_000L, 3L, LiquidationOrderStatus.SUBMITTED, "PARTIAL_LIQUIDATION",
                Instant.parse("2026-07-01T00:00:01Z"));
        liquidationRepository.orders.add(order);
        liquidationRepository.timelineEvents.add(new LiquidationTimelineEvent(
                Instant.parse("2026-07-01T00:00:00Z"), "risk", "CANDIDATE_CREATED",
                "9401", "NEW", Map.of("candidate_id", 9401L)));
        liquidationRepository.timelineEvents.add(new LiquidationTimelineEvent(
                Instant.parse("2026-07-01T00:00:01Z"), "liquidation", "LIQUIDATION_AUDIT",
                "6001", "SUBMITTED", Map.of("order_id", 7001L)));
        LiquidationService service = new LiquidationService(new ObjectMapper(), new LiquidationProperties(),
                liquidationRepository, new FakeLiquidationOrderRepository(), new FakeSequenceRepository(),
                new LiquidationSizingPolicy(), new LiquidationPriceCalculator());

        LiquidationService.LiquidationTimelineResponse response = service.timeline(9401L, 100);

        assertThat(response.candidateId()).isEqualTo(9401L);
        assertThat(response.candidate()).containsEntry("candidate_id", 9401L);
        assertThat(response.orders()).containsExactly(order);
        assertThat(response.eventCount()).isEqualTo(2);
        assertThat(response.timeline()).extracting(LiquidationTimelineEvent::eventType)
                .containsExactly("CANDIDATE_CREATED", "LIQUIDATION_AUDIT");
    }

    @Test
    void cancelCandidateRequiresAdminReasonAndPersistsAuditAction() {
        FakeLiquidationRepository liquidationRepository = new FakeLiquidationRepository();
        LiquidationService service = new LiquidationService(new ObjectMapper(), new LiquidationProperties(),
                liquidationRepository, new FakeLiquidationOrderRepository(), new FakeSequenceRepository(),
                new LiquidationSizingPolicy(), new LiquidationPriceCalculator());

        LiquidationService.LiquidationAdminActionResponse response = service.cancelCandidate(9401L,
                " admin-risk ", " margin recovered ");

        assertThat(response.candidateId()).isEqualTo(9401L);
        assertThat(response.status()).isEqualTo("CANCELED");
        assertThat(response.actionType()).isEqualTo("CANCEL_CANDIDATE");
        assertThat(response.adminUserId()).isEqualTo("admin-risk");
        assertThat(response.reason()).isEqualTo("margin recovered");
        assertThat(liquidationRepository.canceledCandidateIds).containsExactly(9401L);
        assertThat(liquidationRepository.adminActions).hasSize(1);
        assertThat(liquidationRepository.adminActions.get(0).adminUserId()).isEqualTo("admin-risk");
        assertThat(liquidationRepository.adminActions.get(0).reason()).isEqualTo("margin recovered");
    }

    @Test
    void adminOrdersExposeCursorMetadata() {
        FakeLiquidationRepository liquidationRepository = new FakeLiquidationRepository();
        LiquidationOrderResponse order = new LiquidationOrderResponse(6001L, 9401L, 7001L, 2002L,
                "BTC-USDT", MarginMode.CROSS, OrderSide.SELL, 5L, 80L, 80L,
                5_000L, 3L, LiquidationOrderStatus.SUBMITTED, "PARTIAL_LIQUIDATION",
                Instant.parse("2026-07-01T00:00:01Z"));
        liquidationRepository.orders.add(order);
        LiquidationService service = new LiquidationService(new ObjectMapper(), new LiquidationProperties(),
                liquidationRepository, new FakeLiquidationOrderRepository(), new FakeSequenceRepository(),
                new LiquidationSizingPolicy(), new LiquidationPriceCalculator());

        var response = service.orders(2002L, 50, "cursor-orders", "createdAt.desc");

        assertThat(liquidationRepository.lastOrdersUserId).isEqualTo(2002L);
        assertThat(liquidationRepository.lastOrdersLimit).isEqualTo(50);
        assertThat(liquidationRepository.lastOrdersCursor).isEqualTo("cursor-orders");
        assertThat(liquidationRepository.lastOrdersSort).isEqualTo("createdAt.desc");
        assertThat(response.orders()).containsExactly(order);
        assertThat(response.nextCursor()).isEqualTo("next-orders");
        assertThat(response.hasMore()).isTrue();
        assertThat(response.limit()).isEqualTo(50);
    }

    private static final class FakeLiquidationRepository extends LiquidationRepository {
        private long pendingCloseSteps = 4L;
        private boolean insertAudit = true;
        private long pricingSignedQuantitySteps = 10L;
        private RiskStatus accountRiskStatus = RiskStatus.LIQUIDATION;
        private RiskStatus positionRiskStatus = RiskStatus.LIQUIDATION;
        private final List<String> markedStatuses = new ArrayList<>();
        private final List<LiquidationOrderResponse> orders = new ArrayList<>();
        private final List<LiquidationSizingInput> sizingInputs = new ArrayList<>();
        private final List<String> lifecycleUpdates = new ArrayList<>();
        private final List<LiquidationTimelineEvent> timelineEvents = new ArrayList<>();
        private final List<Long> canceledCandidateIds = new ArrayList<>();
        private final List<LiquidationAdminAction> adminActions = new ArrayList<>();
        private int claimAttempts;
        private int accountRiskChecks;
        private int positionRiskChecks;
        private Long lastOrdersUserId;
        private int lastOrdersLimit;
        private String lastOrdersCursor;
        private String lastOrdersSort;

        private FakeLiquidationRepository() {
            super(null);
        }

        @Override
        public Optional<Map<String, Object>> candidate(long candidateId) {
            Map<String, Object> candidate = new LinkedHashMap<>();
            candidate.put("candidate_id", candidateId);
            candidate.put("snapshot_id", 9301L);
            candidate.put("user_id", 2002L);
            candidate.put("symbol", "BTC-USDT");
            candidate.put("margin_mode", "CROSS");
            candidate.put("status", "NEW");
            return Optional.of(candidate);
        }

        @Override
        public List<LiquidationTimelineEvent> timeline(long candidateId, int limit) {
            assertThat(candidateId).isEqualTo(9401L);
            return timelineEvents.stream().limit(limit).toList();
        }

        @Override
        public Optional<CanceledCandidate> cancelCandidateIfSafe(long candidateId, Instant now) {
            canceledCandidateIds.add(candidateId);
            return Optional.of(new CanceledCandidate(candidateId, "CANCELED", now));
        }

        @Override
        public LiquidationAdminAction insertAdminAction(long candidateId,
                                                       String actionType,
                                                       String adminUserId,
                                                       String reason,
                                                       Instant now) {
            LiquidationAdminAction action = new LiquidationAdminAction(5001L, candidateId, actionType,
                    adminUserId, reason, now);
            adminActions.add(action);
            return action;
        }

        @Override
        public Optional<ClaimedCandidate> claimCandidate(long candidateId) {
            claimAttempts++;
            return Optional.of(new ClaimedCandidate(candidateId, 9301L, 2002L, "BTC-USDT", 8L,
                    "USDT", 10L, 590_000L, 1_000L, 500L, 1_100_000L));
        }

        @Override
        public RiskStatus latestRiskStatus(long userId, String settleAsset, Duration maxSnapshotAge) {
            accountRiskChecks++;
            assertThat(userId).isEqualTo(2002L);
            assertThat(settleAsset).isEqualTo("USDT");
            assertThat(maxSnapshotAge).isEqualTo(Duration.ofSeconds(5));
            return accountRiskStatus;
        }

        @Override
        public RiskStatus latestRiskStatus(long userId,
                                           String symbol,
                                           MarginMode marginMode,
                                           long instrumentVersion,
                                           Duration maxSnapshotAge) {
            positionRiskChecks++;
            assertThat(maxSnapshotAge).isEqualTo(Duration.ofSeconds(5));
            assertThat(symbol).isEqualTo("BTC-USDT");
            assertThat(marginMode).isEqualTo(MarginMode.CROSS);
            assertThat(instrumentVersion).isEqualTo(8L);
            return positionRiskStatus;
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
            assertThat(instrumentVersion).isEqualTo(8L);
            assertThat(marginMode).isEqualTo(MarginMode.CROSS);
            return Optional.of(new LiquidationCloseState(10L));
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
            assertThat(instrumentVersion).isEqualTo(8L);
            assertThat(marginMode).isEqualTo(MarginMode.CROSS);
            assertThat(closeSide).isEqualTo(OrderSide.SELL);
            return pendingCloseSteps;
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
            assertThat(marginMode).isEqualTo(MarginMode.CROSS);
            LiquidationSizingInput input = new LiquidationSizingInput(10L, availableCloseSteps,
                    40_000L, 400L, 0L);
            sizingInputs.add(input);
            return Optional.of(input);
        }

        @Override
        public Optional<LiquidationPricingInput> latestPricingInput(long userId,
                                                                    String symbol,
                                                                    MarginMode marginMode,
                                                                    long instrumentVersion,
                                                                    Duration maxSnapshotAge) {
            assertThat(userId).isEqualTo(2002L);
            assertThat(symbol).isEqualTo("BTC-USDT");
            assertThat(marginMode).isEqualTo(MarginMode.CROSS);
            assertThat(instrumentVersion).isEqualTo(8L);
            return Optional.of(new LiquidationPricingInput(
                    com.surprising.instrument.api.model.ContractType.LINEAR_PERPETUAL,
                    pricingSignedQuantitySteps, 100L, 200L, 50L, 1L, 1L, 100_000_000L));
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
        public List<LiquidationOrderResponse> ordersByCandidate(long candidateId) {
            assertThat(candidateId).isEqualTo(9401L);
            return List.copyOf(orders);
        }

        @Override
        public AdminCursorPage.CursorPage<LiquidationOrderResponse> ordersPage(Long userId,
                                                                               int limit,
                                                                               String cursor,
                                                                               String sort) {
            lastOrdersUserId = userId;
            lastOrdersLimit = limit;
            lastOrdersCursor = cursor;
            lastOrdersSort = sort;
            return new AdminCursorPage.CursorPage<>(List.copyOf(orders), "next-orders", true,
                    "createdAt.desc", limit);
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
            return insertLiquidationOrder(liquidationOrderId, candidateId, orderId, userId, symbol,
                    MarginMode.CROSS, side, quantitySteps, status, reason, now);
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
                                              Instant now) {
            return insertLiquidationOrder(liquidationOrderId, candidateId, orderId, userId, symbol, marginMode, side,
                    quantitySteps, status, reason, LiquidationPricingDecision.empty(), now);
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
            if (!insertAudit) {
                return false;
            }
            assertThat(marginMode).isEqualTo(MarginMode.CROSS);
            LiquidationPricingDecision auditPricing = pricing == null ? LiquidationPricingDecision.empty() : pricing;
            orders.add(new LiquidationOrderResponse(liquidationOrderId, candidateId, orderId, userId,
                    symbol, marginMode, side, quantitySteps, auditPricing.bankruptcyPriceTicks(),
                    auditPricing.takeoverPriceTicks(), auditPricing.liquidationFeeRatePpm(),
                    auditPricing.liquidationFeeUnits(), status, reason, now));
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
            return cancelOpenReduceOnlyCloseOrders(userId, symbol, MarginMode.CROSS, instrumentVersion,
                    closeSide, now, serializer);
        }

        @Override
        public int cancelOpenReduceOnlyCloseOrders(long userId,
                                                   String symbol,
                                                   MarginMode marginMode,
                                                   long instrumentVersion,
                                                   OrderSide closeSide,
                                                   Instant now,
                                                   Function<Object, String> serializer) {
            assertThat(marginMode).isEqualTo(MarginMode.CROSS);
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
            return createReduceOnlyMarketOrder(candidateId, userId, symbol, MarginMode.CROSS, instrumentVersion,
                    side, quantitySteps, now, serializer);
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
            assertThat(marginMode).isEqualTo(MarginMode.CROSS);
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
