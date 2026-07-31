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
import com.surprising.liquidation.provider.repository.LiquidationAdminActionRepository;
import com.surprising.liquidation.provider.repository.LiquidationAdminActionRepository.LiquidationAdminAction;
import com.surprising.liquidation.provider.repository.LiquidationAuditRepository;
import com.surprising.liquidation.provider.repository.LiquidationAuditRepository.LiquidationOrderInsert;
import com.surprising.liquidation.provider.repository.LiquidationCandidateRepository;
import com.surprising.liquidation.provider.repository.LiquidationPositionRepository;
import com.surprising.liquidation.provider.repository.LiquidationRepository;
import com.surprising.liquidation.provider.repository.LiquidationRepository.CandidateInputRequest;
import com.surprising.liquidation.provider.repository.LiquidationRepository.CandidateInputs;
import com.surprising.liquidation.provider.repository.LiquidationRepository.CanceledCandidate;
import com.surprising.liquidation.provider.repository.LiquidationSequenceRepository;
import com.surprising.liquidation.provider.service.LiquidationOrderPersistenceService.LiquidationOrderRequest;
import com.surprising.liquidation.provider.service.LiquidationOrderPersistenceService.LiquidationOrderSubmission;
import com.surprising.risk.api.model.LiquidationCandidateEvent;
import com.surprising.risk.api.model.RiskStatus;
import com.surprising.trading.api.model.OrderCommandEvent;
import com.surprising.trading.api.model.OrderCommandType;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.MatchResultEvent;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.OrderStatus;
import com.surprising.trading.api.model.OrderType;
import com.surprising.trading.api.model.PositionSide;
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
        LiquidationService service = service(new LiquidationProperties(), liquidationRepository,
                orderRepository, sequenceRepository);

        service.processCandidates(List.of(new LiquidationCandidateEvent(9401L, 9301L, 2002L, "BTC-USDT", 8L,
                "USDT", 10L, 590_000L, -200_000_000L, 88_500_000L, 1_100_000L,
                Instant.parse("2026-07-01T00:00:00Z"))));

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
        assertThat(command.positionSide()).isEqualTo(PositionSide.NET);
        assertThat(command.reduceOnly()).isTrue();
        assertThat(orderRepository.preemptions).containsExactly("2002:BTC-USDT:NET:8:SELL");

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
    void crossLiquidationRechecksRiskWithinClaimedAccountType() {
        FakeLiquidationRepository liquidationRepository = new FakeLiquidationRepository();
        liquidationRepository.candidateAccountType = "USDT_DELIVERY";
        FakeLiquidationOrderRepository orderRepository = new FakeLiquidationOrderRepository();
        LiquidationService service = service(new LiquidationProperties(), liquidationRepository,
                orderRepository, new FakeSequenceRepository());

        service.processCandidates(List.of(new LiquidationCandidateEvent(9401L, 9301L, 2002L, "BTC-USDT", 8L,
                "USDT", 10L, 590_000L, -200_000_000L, 88_500_000L, 1_100_000L,
                Instant.parse("2026-07-01T00:00:00Z"))));

        assertThat(liquidationRepository.accountRiskChecks).isEqualTo(1);
        assertThat(orderRepository.commands).hasSize(1);
    }

    @Test
    void hedgeLiquidationUsesClaimedPositionSideForPreemptionSizingOrderAndAudit() {
        FakeLiquidationRepository liquidationRepository = new FakeLiquidationRepository();
        liquidationRepository.candidatePositionSide = PositionSide.SHORT;
        liquidationRepository.candidateSignedQuantitySteps = -10L;
        liquidationRepository.pricingSignedQuantitySteps = -10L;
        FakeLiquidationOrderRepository orderRepository = new FakeLiquidationOrderRepository();
        LiquidationService service = service(new LiquidationProperties(), liquidationRepository,
                orderRepository, new FakeSequenceRepository());

        service.processCandidates(List.of(new LiquidationCandidateEvent(9401L, 9301L, 2002L, "BTC-USDT",
                MarginMode.CROSS, PositionSide.SHORT, 8L, "USDT", -10L, 590_000L,
                -200_000_000L, 88_500_000L, 1_100_000L,
                Instant.parse("2026-07-01T00:00:00Z"))));

        assertThat(orderRepository.preemptions).containsExactly("2002:BTC-USDT:SHORT:8:BUY");
        assertThat(orderRepository.commands).singleElement().satisfies(command -> {
            assertThat(command.side()).isEqualTo(OrderSide.BUY);
            assertThat(command.positionSide()).isEqualTo(PositionSide.SHORT);
            assertThat(command.reduceOnly()).isTrue();
        });
        assertThat(liquidationRepository.sizingInputs).containsExactly(
                new LiquidationSizingInput(10L, 10L, 40_000L, 400L, 0L));
        assertThat(liquidationRepository.orders).singleElement().satisfies(order -> {
            assertThat(order.side()).isEqualTo(OrderSide.BUY);
            assertThat(order.quantitySteps()).isEqualTo(5L);
        });
    }

    @Test
    void disabledExecutionDoesNotClaimCandidateOrCreateOrder() {
        FakeLiquidationRepository liquidationRepository = new FakeLiquidationRepository();
        FakeLiquidationOrderRepository orderRepository = new FakeLiquidationOrderRepository();
        LiquidationProperties properties = new LiquidationProperties();
        properties.getExecution().setEnabled(false);
        LiquidationService service = service(properties, liquidationRepository,
                orderRepository, new FakeSequenceRepository());

        assertThatThrownBy(() -> service.processCandidates(List.of(new LiquidationCandidateEvent(9401L, 9301L,
                2002L, "BTC-USDT", 8L, "USDT", 10L, 590_000L, -200_000_000L,
                88_500_000L, 1_100_000L, Instant.parse("2026-07-01T00:00:00Z")))))
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
        LiquidationService service = service(new LiquidationProperties(), liquidationRepository,
                orderRepository, new FakeSequenceRepository());

        service.processCandidates(List.of(new LiquidationCandidateEvent(9401L, 9301L, 2002L, "BTC-USDT", 8L,
                "USDT", 10L, 590_000L, -200_000_000L, 88_500_000L, 1_100_000L,
                Instant.parse("2026-07-01T00:00:00Z"))));

        assertThat(orderRepository.preemptions).containsExactly("2002:BTC-USDT:NET:8:SELL");
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
        LiquidationService service = service(new LiquidationProperties(), liquidationRepository,
                orderRepository, new FakeSequenceRepository());

        service.processCandidates(List.of(new LiquidationCandidateEvent(9401L, 9301L, 2002L, "BTC-USDT", 8L,
                "USDT", 10L, 590_000L, -200_000_000L, 88_500_000L, 1_100_000L,
                Instant.parse("2026-07-01T00:00:00Z"))));

        assertThat(liquidationRepository.accountRiskChecks).isEqualTo(1);
        assertThat(liquidationRepository.positionRiskChecks).isZero();
        assertThat(orderRepository.commands).hasSize(1);
    }

    @Test
    void cancelsCandidateWhenFreshRiskSnapshotNoLongerMatchesLockedPosition() {
        FakeLiquidationRepository liquidationRepository = new FakeLiquidationRepository();
        liquidationRepository.pricingSignedQuantitySteps = 8L;
        FakeLiquidationOrderRepository orderRepository = new FakeLiquidationOrderRepository();
        LiquidationService service = service(new LiquidationProperties(), liquidationRepository,
                orderRepository, new FakeSequenceRepository());

        service.processCandidates(List.of(new LiquidationCandidateEvent(9401L, 9301L, 2002L, "BTC-USDT", 8L,
                "USDT", 10L, 590_000L, -200_000_000L, 88_500_000L, 1_100_000L,
                Instant.parse("2026-07-01T00:00:00Z"))));

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
        LiquidationService service = service(new LiquidationProperties(), liquidationRepository,
                orderRepository, new FakeSequenceRepository());

        assertThatThrownBy(() -> service.processCandidates(List.of(new LiquidationCandidateEvent(9401L, 9301L,
                2002L, "BTC-USDT", 8L, "USDT", 10L, 590_000L, -200_000_000L,
                88_500_000L, 1_100_000L, Instant.parse("2026-07-01T00:00:00Z")))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("liquidation order audit");

        assertThat(liquidationRepository.markedStatuses).isEmpty();
    }

    @Test
    void marksLiquidationCompletedOnlyAfterFilledMatchResult() {
        FakeLiquidationRepository liquidationRepository = new FakeLiquidationRepository();
        LiquidationService service = service(new LiquidationProperties(), liquidationRepository,
                new FakeLiquidationOrderRepository(), new FakeSequenceRepository());

        service.processMatchResult(new MatchResultEvent(9101L, 7001L, 2002L, "BTC-USDT", 8L,
                OrderCommandType.PLACE, "SUCCESS", 5L, OrderStatus.FILLED,
                Instant.parse("2026-07-01T00:00:01Z"), List.of(), "trace-liq"));

        assertThat(liquidationRepository.lifecycleUpdates)
                .containsExactly("7001:FILLED:PROCESSING");
    }

    @Test
    void cancelsLiquidationCandidateWhenMatchResultDoesNotFill() {
        FakeLiquidationRepository liquidationRepository = new FakeLiquidationRepository();
        LiquidationService service = service(new LiquidationProperties(), liquidationRepository,
                new FakeLiquidationOrderRepository(), new FakeSequenceRepository());

        service.processMatchResult(new MatchResultEvent(9101L, 7002L, 2002L, "BTC-USDT", 8L,
                OrderCommandType.PLACE, "SUCCESS", 0L, OrderStatus.CANCELED,
                Instant.parse("2026-07-01T00:00:02Z"), List.of(), "trace-liq"));

        assertThat(liquidationRepository.lifecycleUpdates)
                .containsExactly("7002:CANCELED:CANCELED");
    }

    @Test
    void cancelCandidateRequiresAdminReasonAndPersistsAuditAction() {
        FakeLiquidationRepository liquidationRepository = new FakeLiquidationRepository();
        LiquidationService service = service(new LiquidationProperties(), liquidationRepository,
                new FakeLiquidationOrderRepository(), new FakeSequenceRepository());

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
        LiquidationService service = service(new LiquidationProperties(), liquidationRepository,
                new FakeLiquidationOrderRepository(), new FakeSequenceRepository());

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

    private LiquidationService service(LiquidationProperties properties,
                                       FakeLiquidationRepository repository,
                                       FakeLiquidationOrderRepository orderRepository,
                                       FakeSequenceRepository sequenceRepository) {
        return new LiquidationService(new ObjectMapper(), properties, repository,
                repository.candidateRepository, repository.positionRepository, repository.auditRepository,
                repository.adminActionRepository, orderRepository, sequenceRepository,
                new LiquidationSizingPolicy(), new LiquidationPriceCalculator());
    }

    private static final class FakeLiquidationRepository extends LiquidationRepository {
        private final FakeLiquidationCandidateRepository candidateRepository =
                new FakeLiquidationCandidateRepository(this);
        private final FakeLiquidationPositionRepository positionRepository =
                new FakeLiquidationPositionRepository(this);
        private final FakeLiquidationAuditRepository auditRepository = new FakeLiquidationAuditRepository(this);
        private final FakeLiquidationAdminActionRepository adminActionRepository =
                new FakeLiquidationAdminActionRepository(this);
        private long pendingCloseSteps = 4L;
        private boolean insertAudit = true;
        private long pricingSignedQuantitySteps = 10L;
        private long candidateSignedQuantitySteps = 10L;
        private PositionSide candidatePositionSide = PositionSide.NET;
        private String candidateAccountType = "USDT_PERPETUAL";
        private RiskStatus accountRiskStatus = RiskStatus.LIQUIDATION;
        private RiskStatus positionRiskStatus = RiskStatus.LIQUIDATION;
        private final List<String> markedStatuses = new ArrayList<>();
        private final List<LiquidationOrderResponse> orders = new ArrayList<>();
        private final List<LiquidationSizingInput> sizingInputs = new ArrayList<>();
        private final List<String> lifecycleUpdates = new ArrayList<>();
        private final List<Long> canceledCandidateIds = new ArrayList<>();
        private final List<LiquidationAdminAction> adminActions = new ArrayList<>();
        private int claimAttempts;
        private int accountRiskChecks;
        private int positionRiskChecks;
        private Long lastOrdersUserId;
        private int lastOrdersLimit;
        private String lastOrdersCursor;
        private String lastOrdersSort;
        private long pendingLifecycleOrderId;
        private LiquidationOrderStatus pendingLifecycleStatus;

        private FakeLiquidationRepository() {
            super(null);
        }

        @Override
        public Optional<CanceledCandidate> cancelCandidateIfSafe(long candidateId, Instant now) {
            canceledCandidateIds.add(candidateId);
            return Optional.of(new CanceledCandidate(candidateId, "CANCELED", now));
        }

        private LiquidationAdminAction recordAdminAction(long candidateId,
                                                         String actionType,
                                                         String adminUserId,
                                                         String reason,
                                                         Instant now) {
            LiquidationAdminAction action = new LiquidationAdminAction(5001L, candidateId, actionType,
                    adminUserId, reason, now);
            adminActions.add(action);
            return action;
        }

        private List<ClaimedCandidate> claimCandidates(List<Long> candidateIds) {
            claimAttempts += candidateIds.size();
            return candidateIds.stream().map(candidateId -> new ClaimedCandidate(candidateId, 9301L, 2002L, "BTC-USDT",
                    MarginMode.CROSS, candidatePositionSide, 8L, candidateAccountType, "USDT", candidateSignedQuantitySteps,
                    590_000L, 1_000L, 500L, 1_100_000L)).toList();
        }

        @Override
        public java.util.OptionalLong freshMarkPriceTicks(String symbol, long instrumentVersion) {
            return java.util.OptionalLong.of(100L);
        }

        private Map<Long, LiquidationCloseState> lockCloseStates(List<ClaimedCandidate> candidates) {
            Map<Long, LiquidationCloseState> states = new LinkedHashMap<>();
            for (ClaimedCandidate candidate : candidates) {
                states.put(candidate.candidateId(), new LiquidationCloseState(candidateSignedQuantitySteps));
            }
            return states;
        }

        @Override
        public Map<Long, CandidateInputs> candidateInputs(List<CandidateInputRequest> requests) {
            Map<Long, CandidateInputs> inputs = new LinkedHashMap<>();
            for (CandidateInputRequest request : requests) {
                ClaimedCandidate candidate = request.candidate();
                RiskStatus status;
                if (candidate.marginMode() == MarginMode.CROSS) {
                    accountRiskChecks++;
                    status = accountRiskStatus;
                } else {
                    positionRiskChecks++;
                    status = positionRiskStatus;
                }
                LiquidationCloseState closeState = new LiquidationCloseState(candidateSignedQuantitySteps);
                LiquidationPricingInput pricing = new LiquidationPricingInput(
                        com.surprising.instrument.api.model.ContractType.LINEAR_PERPETUAL,
                        pricingSignedQuantitySteps, request.markPriceTicks(), 200L, 50L, 1L, 1L, 100_000_000L);
                LiquidationSizingInput sizing = new LiquidationSizingInput(10L,
                        Math.absExact(candidateSignedQuantitySteps), 40_000L, 400L, 0L);
                sizingInputs.add(sizing);
                inputs.put(candidate.candidateId(), new CandidateInputs(status, closeState, pricing, sizing));
            }
            return inputs;
        }

        private void markCandidate(String status) {
            markedStatuses.add(status);
        }

        private List<LiquidationOrderResponse> ordersByCandidate(long candidateId) {
            assertThat(candidateId).isEqualTo(9401L);
            return List.copyOf(orders);
        }

        private AdminCursorPage.CursorPage<LiquidationOrderResponse> ordersPage(Long userId,
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

        private boolean insertLiquidationOrder(long liquidationOrderId,
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
            if (!insertAudit) {
                return false;
            }
            assertThat(marginMode).isEqualTo(MarginMode.CROSS);
            assertThat(positionSide).isEqualTo(candidatePositionSide);
            LiquidationPricingDecision auditPricing = pricing == null ? LiquidationPricingDecision.empty() : pricing;
            orders.add(new LiquidationOrderResponse(liquidationOrderId, candidateId, orderId, userId,
                    symbol, marginMode, side, quantitySteps, auditPricing.bankruptcyPriceTicks(),
                    auditPricing.takeoverPriceTicks(), auditPricing.liquidationFeeRatePpm(),
                    auditPricing.liquidationFeeUnits(), status, reason, now));
            return true;
        }

    }

    private static final class FakeLiquidationCandidateRepository extends LiquidationCandidateRepository {
        private final FakeLiquidationRepository owner;

        private FakeLiquidationCandidateRepository(FakeLiquidationRepository owner) {
            super(null);
            this.owner = owner;
        }

        @Override
        public List<ClaimedCandidate> claimAll(List<Long> candidateIds) {
            return owner.claimCandidates(candidateIds);
        }

        @Override
        public void updateStatus(long candidateId, String status) {
            owner.markCandidate(status);
        }

        @Override
        public void updateProcessingStatus(long candidateId, String status) {
            owner.lifecycleUpdates.add(owner.pendingLifecycleOrderId + ":" + owner.pendingLifecycleStatus
                    + ":" + status);
            owner.pendingLifecycleOrderId = 0L;
            owner.pendingLifecycleStatus = null;
        }
    }

    private static final class FakeLiquidationPositionRepository extends LiquidationPositionRepository {
        private final FakeLiquidationRepository owner;

        private FakeLiquidationPositionRepository(FakeLiquidationRepository owner) {
            super(null, null);
            this.owner = owner;
        }

        @Override
        public Map<Long, LiquidationCloseState> lockAll(List<ClaimedCandidate> candidates) {
            return owner.lockCloseStates(candidates);
        }
    }

    private static final class FakeLiquidationAuditRepository extends LiquidationAuditRepository {
        private final FakeLiquidationRepository owner;

        private FakeLiquidationAuditRepository(FakeLiquidationRepository owner) {
            super(null);
            this.owner = owner;
        }

        @Override
        public boolean insert(LiquidationOrderInsert insert) {
            return owner.insertLiquidationOrder(insert.liquidationOrderId(), insert.candidateId(), insert.orderId(),
                    insert.userId(), insert.symbol(), insert.marginMode(), insert.positionSide(), insert.side(),
                    insert.quantitySteps(), insert.status(), insert.reason(), insert.pricing(), insert.now());
        }

        @Override
        public void insertAll(List<LiquidationOrderInsert> inserts) {
            for (LiquidationOrderInsert insert : inserts) {
                if (!insert(insert)) {
                    throw new IllegalStateException("failed to insert liquidation order audit");
                }
            }
        }

        @Override
        public Optional<Long> updateStatusByOrderId(long orderId, LiquidationOrderStatus status) {
            owner.pendingLifecycleOrderId = orderId;
            owner.pendingLifecycleStatus = status;
            return Optional.of(9401L);
        }

        @Override
        public List<LiquidationOrderResponse> find(Long userId, int limit) {
            return owner.orders.stream().limit(limit).toList();
        }

        @Override
        public AdminCursorPage.CursorPage<LiquidationOrderResponse> page(
                Long userId, int limit, String cursor, String sort) {
            return owner.ordersPage(userId, limit, cursor, sort);
        }

        @Override
        public List<LiquidationOrderResponse> findByCandidate(long candidateId) {
            return owner.ordersByCandidate(candidateId);
        }
    }

    private static final class FakeLiquidationAdminActionRepository extends LiquidationAdminActionRepository {
        private final FakeLiquidationRepository owner;

        private FakeLiquidationAdminActionRepository(FakeLiquidationRepository owner) {
            super(null);
            this.owner = owner;
        }

        @Override
        public LiquidationAdminAction insert(
                long candidateId, String actionType, String adminUserId, String reason, Instant now) {
            return owner.recordAdminAction(candidateId, actionType, adminUserId, reason, now);
        }
    }

    private static final class FakeLiquidationOrderRepository extends LiquidationOrderPersistenceService {
        private final List<OrderCommandEvent> commands = new ArrayList<>();
        private final List<String> preemptions = new ArrayList<>();
        private int openReduceOnlyCloseOrders;

        private FakeLiquidationOrderRepository() {
            super(null, null, null, null, null, null, new LiquidationProperties());
        }

        @Override
        public List<LiquidationOrderSubmission> createReduceOnlyMarketOrders(
                List<LiquidationOrderRequest> requests,
                Function<Object, String> serializer) {
            return requests.stream().map(request -> {
                assertThat(request.marginMode()).isEqualTo(MarginMode.CROSS);
                assertThat(request.positionSide()).isNotNull();
                OrderCommandEvent command = new OrderCommandEvent(
                        OrderCommandType.PLACE, 8001L, 7001L, request.userId(),
                        "LIQ-" + request.candidateId(), request.symbol(), request.instrumentVersion(), request.side(),
                        OrderType.MARKET, TimeInForce.IOC, 0L, request.quantitySteps(), request.marginMode(),
                        request.positionSide(), 0L, 0L, true, false, request.now(), null);
                commands.add(command);
                return new LiquidationOrderSubmission(request.candidateId(), command);
            }).toList();
        }

        @Override
        public int cancelOpenReduceOnlyCloseOrders(
                List<LiquidationOrderRequest> requests,
                Function<Object, String> serializer) {
            int canceled = 0;
            for (LiquidationOrderRequest request : requests) {
                assertThat(request.marginMode()).isEqualTo(MarginMode.CROSS);
                preemptions.add(request.userId() + ":" + request.symbol() + ":" + request.positionSide()
                        + ":" + request.instrumentVersion() + ":" + request.side());
                canceled += openReduceOnlyCloseOrders;
            }
            return canceled;
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
