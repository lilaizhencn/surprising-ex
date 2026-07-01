package com.surprising.liquidation.provider.service;

import com.surprising.liquidation.api.model.LiquidationOrderQueryResponse;
import com.surprising.liquidation.api.model.LiquidationOrderResponse;
import com.surprising.liquidation.api.model.LiquidationOrderStatus;
import com.surprising.liquidation.provider.config.LiquidationProperties;
import com.surprising.liquidation.provider.model.LiquidationSizingInput;
import com.surprising.liquidation.provider.repository.LiquidationOrderRepository;
import com.surprising.liquidation.provider.repository.LiquidationRepository;
import com.surprising.liquidation.provider.repository.LiquidationSequenceRepository;
import com.surprising.risk.api.model.LiquidationCandidateEvent;
import com.surprising.risk.api.model.RiskStatus;
import com.surprising.trading.api.model.MatchResultEvent;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.OrderStatus;
import java.time.Instant;
import java.util.List;
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

    public LiquidationService(ObjectMapper objectMapper,
                              LiquidationProperties properties,
                              LiquidationRepository liquidationRepository,
                              LiquidationOrderRepository orderRepository,
                              LiquidationSequenceRepository sequenceRepository,
                              LiquidationSizingPolicy sizingPolicy) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.liquidationRepository = liquidationRepository;
        this.orderRepository = orderRepository;
        this.sequenceRepository = sequenceRepository;
        this.sizingPolicy = sizingPolicy;
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
        RiskStatus latestStatus = liquidationRepository.latestRiskStatus(candidate.userId(), candidate.symbol(),
                candidate.marginMode(), candidate.instrumentVersion(), properties.getRisk().getMaxSnapshotAge());
        if (latestStatus != RiskStatus.LIQUIDATION) {
            liquidationRepository.markCandidate(candidate.candidateId(), "CANCELED");
            insertAudit(candidate.candidateId(), 0L, candidate.userId(), candidate.symbol(),
                    candidate.marginMode(), LiquidationSideResolver.closeSide(candidate.signedQuantitySteps()),
                    LiquidationSideResolver.closeQuantity(candidate.signedQuantitySteps()),
                    LiquidationOrderStatus.CANCELED, "RISK_RECOVERED");
            return;
        }

        var closeState = liquidationRepository.lockCloseState(candidate.userId(), candidate.symbol(),
                candidate.marginMode(),
                candidate.instrumentVersion());
        if (closeState.isEmpty() || closeState.get().signedQuantitySteps() == 0) {
            liquidationRepository.markCandidate(candidate.candidateId(), "CANCELED");
            insertAudit(candidate.candidateId(), 0L, candidate.userId(), candidate.symbol(),
                    candidate.marginMode(), LiquidationSideResolver.closeSide(candidate.signedQuantitySteps()),
                    LiquidationSideResolver.closeQuantity(candidate.signedQuantitySteps()),
                    LiquidationOrderStatus.CANCELED, "NO_OPEN_POSITION");
            return;
        }

        OrderSide side = LiquidationSideResolver.closeSide(closeState.get().signedQuantitySteps());
        long positionAbsSteps = LiquidationSideResolver.closeQuantity(closeState.get().signedQuantitySteps());
        orderRepository.cancelOpenReduceOnlyCloseOrders(candidate.userId(), candidate.symbol(),
                candidate.marginMode(), candidate.instrumentVersion(), side, Instant.now(), this::payload);

        LiquidationSizingInput sizingInput = liquidationRepository.sizingInput(candidate.userId(),
                        candidate.symbol(), candidate.marginMode(), candidate.instrumentVersion(), positionAbsSteps)
                .orElse(new LiquidationSizingInput(positionAbsSteps, positionAbsSteps, 0L, 0L, 0L));
        var decision = sizingPolicy.decide(sizingInput, candidate.marginRatioPpm(), properties.getSizing());
        long quantitySteps = decision.quantitySteps();
        if (quantitySteps <= 0) {
            liquidationRepository.markCandidate(candidate.candidateId(), "CANCELED");
            insertAudit(candidate.candidateId(), 0L, candidate.userId(), candidate.symbol(), candidate.marginMode(), side, 0L,
                    LiquidationOrderStatus.CANCELED, decision.reason());
            return;
        }
        var command = orderRepository.createReduceOnlyMarketOrder(candidate.candidateId(), candidate.userId(),
                candidate.symbol(), candidate.marginMode(), candidate.instrumentVersion(), side, quantitySteps,
                Instant.now(), this::payload);
        insertAudit(candidate.candidateId(), command.orderId(), candidate.userId(), candidate.symbol(),
                candidate.marginMode(), side, quantitySteps, LiquidationOrderStatus.SUBMITTED, decision.reason());
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
        List<LiquidationOrderResponse> rows = liquidationRepository.orders(userId, limit);
        return new LiquidationOrderQueryResponse(rows.size(), rows);
    }

    public LiquidationOrderQueryResponse ordersByCandidate(long candidateId) {
        List<LiquidationOrderResponse> rows = liquidationRepository.ordersByCandidate(candidateId);
        return new LiquidationOrderQueryResponse(rows.size(), rows);
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
        boolean inserted = liquidationRepository.insertLiquidationOrder(
                sequenceRepository.nextLiquidationSequence("liquidation-order"),
                candidateId, orderId, userId, symbol, marginMode, side, quantitySteps, status, reason, Instant.now());
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
}
