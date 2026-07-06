package com.surprising.liquidation.provider.service;

import com.surprising.liquidation.api.model.AdminCursorPage;
import com.surprising.liquidation.api.model.LiquidationOrderQueryResponse;
import com.surprising.liquidation.api.model.LiquidationOrderResponse;
import com.surprising.liquidation.api.model.LiquidationOrderStatus;
import com.surprising.liquidation.provider.config.LiquidationProperties;
import com.surprising.liquidation.provider.model.LiquidationPricingDecision;
import com.surprising.liquidation.provider.model.LiquidationSizingInput;
import com.surprising.liquidation.provider.repository.LiquidationOrderRepository;
import com.surprising.liquidation.provider.repository.LiquidationRepository;
import com.surprising.liquidation.provider.repository.LiquidationRepository.LiquidationAdminAction;
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
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class LiquidationService {

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
    public void processCandidate(LiquidationCandidateEvent event) {
        if (!properties.getExecution().isEnabled()) {
            throw new IllegalStateException("liquidation execution is disabled");
        }
        var claimed = liquidationRepository.claimCandidate(event.candidateId());
        if (claimed.isEmpty()) {
            return;
        }
        var candidate = claimed.get();
        RiskStatus latestStatus = latestRiskStatus(candidate);
        if (latestStatus != RiskStatus.LIQUIDATION) {
            liquidationRepository.markCandidate(candidate.candidateId(), "CANCELED");
            insertAudit(candidate.candidateId(), 0L, candidate.userId(), candidate.symbol(),
                    candidate.marginMode(), candidate.positionSide(),
                    LiquidationSideResolver.closeSide(candidate.signedQuantitySteps()),
                    LiquidationSideResolver.closeQuantity(candidate.signedQuantitySteps()),
                    LiquidationOrderStatus.CANCELED, "RISK_RECOVERED");
            return;
        }

        var closeState = liquidationRepository.lockCloseState(candidate.userId(), candidate.symbol(),
                candidate.marginMode(), candidate.positionSide(),
                candidate.instrumentVersion());
        if (closeState.isEmpty() || closeState.get().signedQuantitySteps() == 0) {
            liquidationRepository.markCandidate(candidate.candidateId(), "CANCELED");
            insertAudit(candidate.candidateId(), 0L, candidate.userId(), candidate.symbol(),
                    candidate.marginMode(), candidate.positionSide(),
                    LiquidationSideResolver.closeSide(candidate.signedQuantitySteps()),
                    LiquidationSideResolver.closeQuantity(candidate.signedQuantitySteps()),
                    LiquidationOrderStatus.CANCELED, "NO_OPEN_POSITION");
            return;
        }
        var pricingInput = liquidationRepository.latestPricingInput(candidate.userId(), candidate.symbol(),
                candidate.marginMode(), candidate.positionSide(), candidate.instrumentVersion(),
                properties.getRisk().getMaxSnapshotAge())
                .orElseThrow(() -> new IllegalStateException("fresh liquidation pricing input not found"));
        if (pricingInput.signedQuantitySteps() != closeState.get().signedQuantitySteps()) {
            liquidationRepository.markCandidate(candidate.candidateId(), "CANCELED");
            insertAudit(candidate.candidateId(), 0L, candidate.userId(), candidate.symbol(),
                    candidate.marginMode(), candidate.positionSide(),
                    LiquidationSideResolver.closeSide(closeState.get().signedQuantitySteps()),
                    LiquidationSideResolver.closeQuantity(closeState.get().signedQuantitySteps()),
                    LiquidationOrderStatus.CANCELED, "RISK_POSITION_CHANGED");
            return;
        }

        OrderSide side = LiquidationSideResolver.closeSide(closeState.get().signedQuantitySteps());
        long positionAbsSteps = LiquidationSideResolver.closeQuantity(closeState.get().signedQuantitySteps());
        orderRepository.cancelOpenReduceOnlyCloseOrders(candidate.userId(), candidate.symbol(),
                candidate.marginMode(), candidate.positionSide(), candidate.instrumentVersion(), side, Instant.now(),
                this::payload);

        LiquidationSizingInput sizingInput = liquidationRepository.sizingInput(candidate.userId(),
                        candidate.symbol(), candidate.marginMode(), candidate.positionSide(), candidate.instrumentVersion(),
                        positionAbsSteps)
                .orElse(new LiquidationSizingInput(positionAbsSteps, positionAbsSteps, 0L, 0L, 0L));
        var decision = sizingPolicy.decide(sizingInput, candidate.marginRatioPpm(), properties.getSizing());
        long quantitySteps = decision.quantitySteps();
        if (quantitySteps <= 0) {
            liquidationRepository.markCandidate(candidate.candidateId(), "CANCELED");
            insertAudit(candidate.candidateId(), 0L, candidate.userId(), candidate.symbol(), candidate.marginMode(),
                    candidate.positionSide(), side, 0L, LiquidationOrderStatus.CANCELED, decision.reason());
            return;
        }
        var command = orderRepository.createReduceOnlyMarketOrder(candidate.candidateId(), candidate.userId(),
                candidate.symbol(), candidate.marginMode(), candidate.positionSide(), candidate.instrumentVersion(), side,
                quantitySteps, Instant.now(), this::payload);
        LiquidationPricingDecision pricing = priceCalculator.decide(pricingInput,
                properties.getExecution().getLiquidationFeeRatePpm());
        insertAudit(candidate.candidateId(), command.orderId(), candidate.userId(), candidate.symbol(),
                candidate.marginMode(), candidate.positionSide(), side, quantitySteps, LiquidationOrderStatus.SUBMITTED,
                decision.reason(), pricing);
    }

    @Transactional
    public void processMatchResult(MatchResultEvent event) {
        if (event == null || event.commandType() == null || event.orderId() <= 0) {
            return;
        }
        LifecycleUpdate update = lifecycleUpdate(event);
        liquidationRepository.updateOrderLifecycle(event.orderId(), update.orderStatus(), update.candidateStatus());
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

    private RiskStatus latestRiskStatus(com.surprising.liquidation.provider.model.ClaimedCandidate candidate) {
        if (candidate.marginMode() == MarginMode.CROSS) {
            return liquidationRepository.latestRiskStatus(candidate.userId(), candidate.accountType(),
                    candidate.settleAsset(), properties.getRisk().getMaxSnapshotAge());
        }
        return liquidationRepository.latestRiskStatus(candidate.userId(), candidate.symbol(),
                candidate.marginMode(), candidate.positionSide(), candidate.instrumentVersion(),
                properties.getRisk().getMaxSnapshotAge());
    }

    private LifecycleUpdate lifecycleUpdate(MatchResultEvent event) {
        boolean success = "SUCCESS".equalsIgnoreCase(event.resultCode());
        OrderStatus orderStatus = event.orderStatus();
        if (success && orderStatus == OrderStatus.FILLED) {
            return new LifecycleUpdate(LiquidationOrderStatus.FILLED, "COMPLETED");
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

    private String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
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
