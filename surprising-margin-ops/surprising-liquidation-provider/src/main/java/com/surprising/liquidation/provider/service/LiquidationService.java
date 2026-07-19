package com.surprising.liquidation.provider.service;

import com.surprising.liquidation.api.model.AdminCursorPage;
import com.surprising.liquidation.api.model.LiquidationOrderQueryResponse;
import com.surprising.liquidation.api.model.LiquidationOrderResponse;
import com.surprising.liquidation.api.model.LiquidationOrderStatus;
import com.surprising.liquidation.provider.config.LiquidationProperties;
import com.surprising.liquidation.provider.model.ClaimedCandidate;
import com.surprising.liquidation.provider.model.LiquidationPricingDecision;
import com.surprising.liquidation.provider.model.LiquidationSizingInput;
import com.surprising.liquidation.provider.repository.LiquidationOrderRepository;
import com.surprising.liquidation.provider.repository.LiquidationOrderRepository.LiquidationOrderRequest;
import com.surprising.liquidation.provider.repository.LiquidationOrderRepository.LiquidationOrderSubmission;
import com.surprising.liquidation.provider.repository.LiquidationRepository;
import com.surprising.liquidation.provider.repository.LiquidationRepository.LiquidationAdminAction;
import com.surprising.liquidation.provider.repository.LiquidationRepository.CandidateInputRequest;
import com.surprising.liquidation.provider.repository.LiquidationRepository.CandidateInputs;
import com.surprising.liquidation.provider.repository.LiquidationRepository.LiquidationOrderInsert;
import com.surprising.liquidation.provider.repository.LiquidationRepository.LiquidationTimelineEvent;
import com.surprising.liquidation.provider.repository.LiquidationSequenceRepository;
import com.surprising.risk.api.model.LiquidationCandidateEvent;
import com.surprising.risk.api.model.RiskStatus;
import com.surprising.trading.api.model.MatchResultEvent;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.OrderStatus;
import com.surprising.trading.api.model.PositionSide;
import java.time.Instant;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class LiquidationService {

    private static final Logger log = LoggerFactory.getLogger(LiquidationService.class);

    private final ObjectMapper objectMapper;
    private final LiquidationProperties properties;
    private final LiquidationRepository liquidationRepository;
    private final LiquidationOrderRepository orderRepository;
    private final LiquidationSequenceRepository sequenceRepository;
    private final LiquidationSizingPolicy sizingPolicy;
    private final LiquidationPriceCalculator priceCalculator;

    public LiquidationService(ObjectMapper objectMapper,
                              LiquidationProperties properties,
                              LiquidationRepository liquidationRepository,
                              LiquidationOrderRepository orderRepository,
                              LiquidationSequenceRepository sequenceRepository,
                              LiquidationSizingPolicy sizingPolicy,
                              LiquidationPriceCalculator priceCalculator) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.liquidationRepository = liquidationRepository;
        this.orderRepository = orderRepository;
        this.sequenceRepository = sequenceRepository;
        this.sizingPolicy = sizingPolicy;
        this.priceCalculator = priceCalculator;
    }

    @Transactional
    public CandidateBatchResult processCandidates(List<LiquidationCandidateEvent> events) {
        long batchStarted = System.nanoTime();
        if (!properties.getExecution().isEnabled()) {
            throw new IllegalStateException("liquidation execution is disabled");
        }
        if (events == null || events.isEmpty()) {
            return CandidateBatchResult.empty();
        }
        Map<Long, LiquidationCandidateEvent> uniqueEvents = new LinkedHashMap<>();
        for (LiquidationCandidateEvent event : events) {
            if (event != null) {
                uniqueEvents.putIfAbsent(event.candidateId(), event);
            }
        }
        Map<InstrumentKey, Long> markPrices = new HashMap<>();
        List<Long> retryIds = uniqueEvents.values().stream().filter(event -> {
            InstrumentKey key = new InstrumentKey(event.symbol(), event.instrumentVersion());
            if (markPrices.containsKey(key)) {
                return false;
            }
            var markPrice = liquidationRepository.freshMarkPriceTicks(event.symbol(), event.instrumentVersion());
            if (markPrice.isPresent()) {
                markPrices.put(key, markPrice.getAsLong());
                return false;
            }
            return true;
        }).map(LiquidationCandidateEvent::candidateId).toList();
        HashSet<Long> retrySet = new HashSet<>(retryIds);
        long marksLoaded = System.nanoTime();
        List<Long> readyIds = uniqueEvents.keySet().stream().filter(id -> !retrySet.contains(id)).toList();
        List<ClaimedCandidate> claimed = liquidationRepository.claimCandidates(readyIds);
        long candidatesClaimed = System.nanoTime();
        Map<Long, com.surprising.liquidation.provider.model.LiquidationCloseState> closeStates =
                liquidationRepository.lockCloseStates(claimed);
        long positionsLocked = System.nanoTime();
        List<CandidateInputRequest> inputRequests = claimed.stream()
                .filter(candidate -> closeStates.containsKey(candidate.candidateId()))
                .map(candidate -> new CandidateInputRequest(candidate, markPrices.get(
                        new InstrumentKey(candidate.symbol(), candidate.instrumentVersion()))))
                .toList();
        Map<Long, CandidateInputs> inputs = liquidationRepository.candidateInputs(inputRequests);
        long inputsLoaded = System.nanoTime();
        List<PendingSubmission> pending = new java.util.ArrayList<>(claimed.size());
        for (ClaimedCandidate candidate : claimed) {
            Long markPriceTicks = markPrices.get(new InstrumentKey(candidate.symbol(), candidate.instrumentVersion()));
            if (markPriceTicks == null) {
                throw new IllegalStateException("captured mark price missing for " + candidate.symbol());
            }
            PendingSubmission submission = prepareClaimedCandidate(candidate, closeStates.get(candidate.candidateId()),
                    inputs.get(candidate.candidateId()));
            if (submission != null) {
                pending.add(submission);
            }
        }
        List<LiquidationOrderRequest> requests = pending.stream().map(PendingSubmission::request).toList();
        long candidatesPrepared = System.nanoTime();
        orderRepository.cancelOpenReduceOnlyCloseOrders(requests, this::payload);
        long closeOrdersPreempted = System.nanoTime();
        List<LiquidationOrderSubmission> submissions = orderRepository.createReduceOnlyMarketOrders(
                requests, this::payload);
        long ordersSubmitted = System.nanoTime();
        Map<Long, LiquidationOrderSubmission> submissionByCandidate = submissions.stream()
                .collect(java.util.stream.Collectors.toMap(LiquidationOrderSubmission::candidateId,
                        java.util.function.Function.identity()));
        List<LiquidationOrderInsert> audits = new java.util.ArrayList<>(pending.size());
        for (PendingSubmission item : pending) {
            LiquidationOrderSubmission submission = submissionByCandidate.get(item.candidate().candidateId());
            if (submission == null) {
                throw new IllegalStateException("liquidation order submission missing for candidate "
                        + item.candidate().candidateId());
            }
            audits.add(new LiquidationOrderInsert(sequenceRepository.nextLiquidationSequence("liquidation-order"),
                    item.candidate().candidateId(), submission.command().orderId(), item.candidate().userId(),
                    item.candidate().symbol(), item.candidate().marginMode(), item.candidate().positionSide(),
                    item.request().side(), item.request().quantitySteps(), LiquidationOrderStatus.SUBMITTED,
                    item.reason(), item.pricing(), Instant.now()));
        }
        liquidationRepository.insertLiquidationOrders(audits);
        long auditsInserted = System.nanoTime();
        if (!claimed.isEmpty() || !retryIds.isEmpty()) {
            log.info("liquidation candidate batch events={} claimed={} submitted={} retry={} "
                            + "timingMs[marks={},claim={},positionLock={},inputs={},prepare={},preempt={},submit={},audit={},total={}]",
                    uniqueEvents.size(), claimed.size(), requests.size(), retryIds.size(),
                    elapsedMillis(batchStarted, marksLoaded), elapsedMillis(marksLoaded, candidatesClaimed),
                    elapsedMillis(candidatesClaimed, positionsLocked), elapsedMillis(positionsLocked, inputsLoaded),
                    elapsedMillis(inputsLoaded, candidatesPrepared),
                    elapsedMillis(candidatesPrepared, closeOrdersPreempted),
                    elapsedMillis(closeOrdersPreempted, ordersSubmitted),
                    elapsedMillis(ordersSubmitted, auditsInserted), elapsedMillis(batchStarted, auditsInserted));
        }
        return new CandidateBatchResult(retryIds);
    }

    private PendingSubmission prepareClaimedCandidate(
            ClaimedCandidate candidate,
            com.surprising.liquidation.provider.model.LiquidationCloseState closeState,
            CandidateInputs inputs) {
        if (closeState == null || closeState.signedQuantitySteps() == 0) {
            liquidationRepository.markCandidate(candidate.candidateId(), "CANCELED");
            insertAudit(candidate.candidateId(), 0L, candidate.userId(), candidate.symbol(),
                    candidate.marginMode(), candidate.positionSide(),
                    LiquidationSideResolver.closeSide(candidate.signedQuantitySteps()),
                    LiquidationSideResolver.closeQuantity(candidate.signedQuantitySteps()),
                    LiquidationOrderStatus.CANCELED, "NO_OPEN_POSITION");
            return null;
        }
        if (inputs == null) {
            throw new IllegalStateException("liquidation candidate inputs missing for " + candidate.candidateId());
        }
        if (inputs.latestRiskStatus() != RiskStatus.LIQUIDATION) {
            liquidationRepository.markCandidate(candidate.candidateId(), "CANCELED");
            insertAudit(candidate.candidateId(), 0L, candidate.userId(), candidate.symbol(),
                    candidate.marginMode(), candidate.positionSide(),
                    LiquidationSideResolver.closeSide(candidate.signedQuantitySteps()),
                    LiquidationSideResolver.closeQuantity(candidate.signedQuantitySteps()),
                    LiquidationOrderStatus.CANCELED, "RISK_RECOVERED");
            return null;
        }
        var pricingInput = inputs.pricingInput();
        if (pricingInput.signedQuantitySteps() != closeState.signedQuantitySteps()) {
            liquidationRepository.markCandidate(candidate.candidateId(), "CANCELED");
            insertAudit(candidate.candidateId(), 0L, candidate.userId(), candidate.symbol(),
                    candidate.marginMode(), candidate.positionSide(),
                    LiquidationSideResolver.closeSide(closeState.signedQuantitySteps()),
                    LiquidationSideResolver.closeQuantity(closeState.signedQuantitySteps()),
                    LiquidationOrderStatus.CANCELED, "RISK_POSITION_CHANGED");
            return null;
        }

        OrderSide side = LiquidationSideResolver.closeSide(closeState.signedQuantitySteps());
        LiquidationSizingInput sizingInput = inputs.sizingInput();
        var decision = sizingPolicy.decide(sizingInput, candidate.marginRatioPpm(), properties.getSizing());
        long quantitySteps = decision.quantitySteps();
        if (quantitySteps <= 0) {
            liquidationRepository.markCandidate(candidate.candidateId(), "CANCELED");
            insertAudit(candidate.candidateId(), 0L, candidate.userId(), candidate.symbol(), candidate.marginMode(),
                    candidate.positionSide(), side, 0L, LiquidationOrderStatus.CANCELED, decision.reason());
            return null;
        }
        LiquidationPricingDecision pricing = priceCalculator.decide(pricingInput,
                properties.getExecution().getLiquidationFeeRatePpm());
        LiquidationOrderRequest request = new LiquidationOrderRequest(candidate.candidateId(), candidate.userId(),
                candidate.symbol(), candidate.marginMode(), candidate.positionSide(), candidate.instrumentVersion(),
                side, quantitySteps, Instant.now());
        return new PendingSubmission(candidate, request, decision.reason(), pricing);
    }

    @Transactional
    public void processMatchResult(MatchResultEvent event) {
        if (event == null || event.commandType() == null || event.orderId() <= 0) {
            return;
        }
        LifecycleUpdate update = lifecycleUpdate(event);
        liquidationRepository.updateOrderLifecycle(event.orderId(), update.orderStatus(), update.candidateStatus());
    }

    @Scheduled(fixedDelayString = "${surprising.liquidation.settlement-reconcile-delay-ms:50}")
    public void finalizeSettledCandidates() {
        liquidationRepository.completeSettledCandidates(1_000);
    }

    public LiquidationOrderQueryResponse orders(Long userId, int limit) {
        List<LiquidationOrderResponse> rows = liquidationRepository.orders(userId, normalizeLimit(limit));
        return new LiquidationOrderQueryResponse(rows.size(), rows);
    }

    public LiquidationOrderQueryResponse orders(Long userId, int limit, String cursor, String sort) {
        AdminCursorPage.CursorPage<LiquidationOrderResponse> page = liquidationRepository.ordersPage(userId,
                normalizeLimit(limit), cursor, sort);
        return new LiquidationOrderQueryResponse(page.items().size(), page.items(), page.nextCursor(),
                page.hasMore(), page.sort(), page.limit());
    }

    public LiquidationOrderQueryResponse ordersByCandidate(long candidateId) {
        List<LiquidationOrderResponse> rows = liquidationRepository.ordersByCandidate(candidateId);
        return new LiquidationOrderQueryResponse(rows.size(), rows);
    }

    public LiquidationTimelineResponse timeline(long candidateId, int limit) {
        Map<String, Object> candidate = liquidationRepository.candidate(candidateId)
                .orElseThrow(() -> new IllegalArgumentException("liquidation candidate not found"));
        List<LiquidationOrderResponse> orders = liquidationRepository.ordersByCandidate(candidateId);
        List<LiquidationTimelineEvent> events = liquidationRepository.timeline(candidateId, limit);
        return new LiquidationTimelineResponse(candidateId, candidate, orders, events.size(), events);
    }

    @Transactional
    public LiquidationAdminActionResponse cancelCandidate(long candidateId, String adminUserId, String reason) {
        String normalizedAdminUserId = requireText(adminUserId, "adminUserId");
        String normalizedReason = requireText(reason, "reason");
        if (normalizedReason.length() > 500) {
            throw new IllegalArgumentException("reason must be at most 500 characters");
        }
        Instant now = Instant.now();
        var canceled = liquidationRepository.cancelCandidateIfSafe(candidateId, now)
                .orElseThrow(() -> new IllegalArgumentException("candidate is not cancelable"));
        LiquidationAdminAction action = liquidationRepository.insertAdminAction(candidateId, "CANCEL_CANDIDATE",
                normalizedAdminUserId, normalizedReason, now);
        return new LiquidationAdminActionResponse(canceled.candidateId(), canceled.status(), action.actionType(),
                action.adminUserId(), action.reason(), canceled.updatedAt(), action.createdAt());
    }

    private void insertAudit(long candidateId,
                             long orderId,
                             long userId,
                             String symbol,
                             MarginMode marginMode,
                             PositionSide positionSide,
                             OrderSide side,
                             long quantitySteps,
                             LiquidationOrderStatus status,
                             String reason) {
        insertAudit(candidateId, orderId, userId, symbol, marginMode, positionSide, side, quantitySteps, status, reason,
                LiquidationPricingDecision.empty());
    }

    private void insertAudit(long candidateId,
                             long orderId,
                             long userId,
                             String symbol,
                             MarginMode marginMode,
                             OrderSide side,
                             long quantitySteps,
                             LiquidationOrderStatus status,
                             String reason) {
        insertAudit(candidateId, orderId, userId, symbol, marginMode, PositionSide.NET, side, quantitySteps, status,
                reason);
    }

    private void insertAudit(long candidateId,
                             long orderId,
                             long userId,
                             String symbol,
                             MarginMode marginMode,
                             PositionSide positionSide,
                             OrderSide side,
                             long quantitySteps,
                             LiquidationOrderStatus status,
                             String reason,
                             LiquidationPricingDecision pricing) {
        boolean inserted = liquidationRepository.insertLiquidationOrder(
                sequenceRepository.nextLiquidationSequence("liquidation-order"),
                candidateId, orderId, userId, symbol, marginMode, positionSide, side, quantitySteps, status, reason,
                pricing, Instant.now());
        if (!inserted) {
            throw new IllegalStateException("failed to insert liquidation order audit");
        }
    }

    private String payload(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException ex) {
            throw new IllegalStateException("failed to serialize liquidation payload", ex);
        }
    }

    private LifecycleUpdate lifecycleUpdate(MatchResultEvent event) {
        boolean success = "SUCCESS".equalsIgnoreCase(event.resultCode());
        OrderStatus orderStatus = event.orderStatus();
        if (success && orderStatus == OrderStatus.FILLED) {
            return new LifecycleUpdate(LiquidationOrderStatus.FILLED, "PROCESSING");
        }
        if (success && event.filledQuantitySteps() > 0) {
            return new LifecycleUpdate(LiquidationOrderStatus.PARTIALLY_FILLED, "CANCELED");
        }
        if (success && orderStatus == OrderStatus.CANCELED) {
            return new LifecycleUpdate(LiquidationOrderStatus.CANCELED, "CANCELED");
        }
        if (orderStatus == OrderStatus.REJECTED) {
            return new LifecycleUpdate(LiquidationOrderStatus.REJECTED, "CANCELED");
        }
        return new LifecycleUpdate(success ? LiquidationOrderStatus.CANCELED : LiquidationOrderStatus.REJECTED,
                "CANCELED");
    }

    private record LifecycleUpdate(LiquidationOrderStatus orderStatus, String candidateStatus) {
    }

    private record InstrumentKey(String symbol, long instrumentVersion) {
    }

    private record PendingSubmission(ClaimedCandidate candidate,
                                     LiquidationOrderRequest request,
                                     String reason,
                                     LiquidationPricingDecision pricing) {
    }

    public record CandidateBatchResult(List<Long> retryCandidateIds) {
        public CandidateBatchResult {
            retryCandidateIds = retryCandidateIds == null ? List.of() : List.copyOf(retryCandidateIds);
        }

        public static CandidateBatchResult empty() {
            return new CandidateBatchResult(List.of());
        }
    }

    private String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private static long elapsedMillis(long started, long completed) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(completed - started);
    }

    private int normalizeLimit(int limit) {
        if (limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("limit must be between 1 and 1000");
        }
        return limit;
    }

    public record LiquidationTimelineResponse(long candidateId,
                                              Map<String, Object> candidate,
                                              List<LiquidationOrderResponse> orders,
                                              int eventCount,
                                              List<LiquidationTimelineEvent> timeline) {
    }

    public record LiquidationAdminActionResponse(long candidateId,
                                                 String status,
                                                 String actionType,
                                                 String adminUserId,
                                                 String reason,
                                                 Instant candidateUpdatedAt,
                                                 Instant actionCreatedAt) {
    }
}
