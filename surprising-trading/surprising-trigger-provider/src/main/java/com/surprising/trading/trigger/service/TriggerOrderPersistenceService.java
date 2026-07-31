package com.surprising.trading.trigger.service;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.AdminCursorPage;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.PositionMode;
import com.surprising.trading.api.model.PositionSide;
import com.surprising.trading.api.model.TriggerOrderStatus;
import com.surprising.trading.trigger.model.TriggerOrderRecord;
import com.surprising.trading.trigger.model.TriggerPosition;
import com.surprising.trading.trigger.repository.TriggerCoordinationRepository;
import com.surprising.trading.trigger.repository.TriggerOpenOrderRepository;
import com.surprising.trading.trigger.repository.TriggerOrderRepository;
import com.surprising.trading.trigger.repository.TriggerPositionModeRepository;
import com.surprising.trading.trigger.repository.TriggerPositionRepository;
import com.surprising.trading.trigger.repository.TriggerSequenceRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * 聚合触发单持久化操作。
 *
 * <p>事务边界由上层业务服务控制；本服务只组合单表仓储，不执行跨表 SQL。</p>
 */
@Service
public class TriggerOrderPersistenceService {

    private final TriggerOrderRepository triggerOrderRepository;
    private final TriggerSequenceRepository sequenceRepository;
    private final TriggerCoordinationRepository coordinationRepository;
    private final TriggerPositionModeRepository positionModeRepository;
    private final TriggerPositionRepository positionRepository;
    private final TriggerOpenOrderRepository openOrderRepository;

    public TriggerOrderPersistenceService(TriggerOrderRepository triggerOrderRepository,
                                          TriggerSequenceRepository sequenceRepository,
                                          TriggerCoordinationRepository coordinationRepository,
                                          TriggerPositionModeRepository positionModeRepository,
                                          TriggerPositionRepository positionRepository,
                                          TriggerOpenOrderRepository openOrderRepository) {
        this.triggerOrderRepository = triggerOrderRepository;
        this.sequenceRepository = sequenceRepository;
        this.coordinationRepository = coordinationRepository;
        this.positionModeRepository = positionModeRepository;
        this.positionRepository = positionRepository;
        this.openOrderRepository = openOrderRepository;
    }

    public long nextSequence(String sequenceName) {
        return sequenceRepository.nextSequence(sequenceName);
    }

    public boolean insert(TriggerOrderRecord order) {
        return triggerOrderRepository.insert(order);
    }

    public void lockUserSymbolMarginScope(ProductLine productLine, long userId, String symbol) {
        coordinationRepository.lockUserSymbolMarginScope(productLine, userId, symbol);
    }

    public void lockUserPositionMode(ProductLine productLine, long userId) {
        coordinationRepository.lockUserPositionMode(productLine, userId);
    }

    public PositionMode positionMode(ProductLine productLine, long userId) {
        return positionModeRepository.positionMode(productLine, userId);
    }

    public boolean hasActiveMarginModeConflict(ProductLine productLine,
                                               long userId,
                                               String symbol,
                                               MarginMode marginMode) {
        return positionRepository.hasActiveMarginModeConflict(productLine, userId, symbol, marginMode)
                || openOrderRepository.hasActiveMarginModeConflict(productLine, userId, symbol, marginMode)
                || triggerOrderRepository.hasActiveMarginModeConflict(productLine, userId, symbol, marginMode);
    }

    public Optional<TriggerPosition> lockedPosition(ProductLine productLine,
                                                    long userId,
                                                    String symbol,
                                                    MarginMode marginMode,
                                                    PositionSide positionSide) {
        return positionRepository.lockedPosition(productLine, userId, symbol, marginMode, positionSide);
    }

    public long openReduceOnlySteps(ProductLine productLine,
                                    long userId,
                                    String symbol,
                                    MarginMode marginMode,
                                    PositionSide positionSide,
                                    long instrumentVersion,
                                    OrderSide closeSide) {
        return openOrderRepository.openReduceOnlySteps(productLine, userId, symbol, marginMode, positionSide,
                instrumentVersion, closeSide);
    }

    public long pendingTriggerCloseSteps(ProductLine productLine,
                                         long userId,
                                         String symbol,
                                         MarginMode marginMode,
                                         PositionSide positionSide,
                                         OrderSide closeSide) {
        return triggerOrderRepository.pendingTriggerCloseSteps(
                productLine, userId, symbol, marginMode, positionSide, closeSide);
    }

    public long pendingTriggerOcoGroupMaxSteps(ProductLine productLine,
                                               long userId,
                                               String symbol,
                                               MarginMode marginMode,
                                               PositionSide positionSide,
                                               OrderSide closeSide,
                                               String ocoGroupId) {
        return triggerOrderRepository.pendingTriggerOcoGroupMaxSteps(
                productLine, userId, symbol, marginMode, positionSide, closeSide, ocoGroupId);
    }

    public Optional<TriggerOrderRecord> findById(long triggerOrderId) {
        return triggerOrderRepository.findById(triggerOrderId);
    }

    public List<TriggerOrderRecord> positionClosedCancellations(ProductLine productLine,
                                                                 long userId,
                                                                 String symbol,
                                                                 MarginMode marginMode,
                                                                 PositionSide positionSide,
                                                                 Instant closedAt) {
        return triggerOrderRepository.positionClosedCancellations(
                productLine, userId, symbol, marginMode, positionSide, closedAt);
    }

    public List<TriggerOrderRecord> findByIds(List<Long> triggerOrderIds) {
        return triggerOrderRepository.findByIds(triggerOrderIds);
    }

    public List<TriggerOrderRecord> ocoGroupOrders(TriggerOrderRecord order) {
        return triggerOrderRepository.ocoGroupOrders(order);
    }

    public boolean triggerOrderMatchesContractType(long triggerOrderId, String contractType) {
        return triggerOrderRepository.triggerOrderMatchesContractType(triggerOrderId, contractType);
    }

    public Optional<TriggerOrderRecord> findByClientTriggerOrderId(ProductLine productLine,
                                                                   long userId,
                                                                   String clientTriggerOrderId) {
        return triggerOrderRepository.findByClientTriggerOrderId(productLine, userId, clientTriggerOrderId);
    }

    public List<TriggerOrderRecord> openOrders(long userId, String symbol, int limit, String contractType) {
        return triggerOrderRepository.openOrders(userId, symbol, limit, contractType);
    }

    public List<TriggerOrderRecord> pendingCancelableOrders(long userId, String symbol, int limit) {
        return triggerOrderRepository.pendingCancelableOrders(userId, symbol, limit);
    }

    public List<TriggerOrderRecord> pendingCancelableOrders(long userId,
                                                            String symbol,
                                                            int limit,
                                                            String contractType) {
        return triggerOrderRepository.pendingCancelableOrders(userId, symbol, limit, contractType);
    }

    public AdminCursorPage.CursorPage<TriggerOrderRecord> adminOrderPage(Long userId,
                                                                         String symbol,
                                                                         TriggerOrderStatus status,
                                                                         Long triggerOrderId,
                                                                         int limit,
                                                                         String cursor,
                                                                         String sort) {
        return triggerOrderRepository.adminOrderPage(
                userId, symbol, status, triggerOrderId, limit, cursor, sort);
    }

    public AdminCursorPage.CursorPage<TriggerOrderRecord> adminOrderPage(Long userId,
                                                                         String symbol,
                                                                         TriggerOrderStatus status,
                                                                         Long triggerOrderId,
                                                                         int limit,
                                                                         String contractType,
                                                                         String cursor,
                                                                         String sort) {
        return triggerOrderRepository.adminOrderPage(
                userId, symbol, status, triggerOrderId, limit, contractType, cursor, sort);
    }

    public Optional<TriggerOrderRecord> cancel(long userId, long triggerOrderId, Instant now) {
        return triggerOrderRepository.cancel(userId, triggerOrderId, now);
    }

    public List<TriggerOrderRecord> cancelForLifecycle(
            ProductLine productLine, String symbol, int limit, Instant now) {
        return triggerOrderRepository.cancelForLifecycle(productLine, symbol, limit, now);
    }

    public boolean hasLifecycleActiveOrders(ProductLine productLine, String symbol) {
        return triggerOrderRepository.hasLifecycleActiveOrders(productLine, symbol);
    }

    public List<TriggerOrderRecord> claimTriggered(String symbol,
                                                   long triggerPriceTicks,
                                                   long triggerSequence,
                                                   Instant triggeredAt,
                                                   int limit,
                                                   Instant now) {
        return triggerOrderRepository.claimTriggered(
                symbol, triggerPriceTicks, triggerSequence, triggeredAt, limit, now);
    }

    public List<TriggerOrderRecord> claimTriggered(String symbol,
                                                   long triggerPriceTicks,
                                                   long triggerSequence,
                                                   Instant triggeredAt,
                                                   int limit,
                                                   Instant now,
                                                   String contractType) {
        return triggerOrderRepository.claimTriggered(
                symbol, triggerPriceTicks, triggerSequence, triggeredAt, limit, now, contractType);
    }

    public List<TriggerOrderRecord> claimTriggeredCandidates(ProductLine productLine,
                                                             String symbol,
                                                             long triggerPriceTicks,
                                                             long triggerSequence,
                                                             Instant triggeredAt,
                                                             int limit,
                                                             Instant now,
                                                             List<Long> candidateIds) {
        return triggerOrderRepository.claimTriggeredCandidates(
                productLine, symbol, triggerPriceTicks, triggerSequence, triggeredAt, limit, now, candidateIds);
    }

    public List<TriggerOrderRecord> claimTrailingTriggered(String symbol,
                                                           long priceTicks,
                                                           long triggerSequence,
                                                           Instant triggeredAt,
                                                           int limit,
                                                           Instant now) {
        return triggerOrderRepository.claimTrailingTriggered(
                symbol, priceTicks, triggerSequence, triggeredAt, limit, now);
    }

    public List<TriggerOrderRecord> claimTrailingTriggered(String symbol,
                                                           long priceTicks,
                                                           long triggerSequence,
                                                           Instant triggeredAt,
                                                           int limit,
                                                           Instant now,
                                                           String contractType) {
        return triggerOrderRepository.claimTrailingTriggered(
                symbol, priceTicks, triggerSequence, triggeredAt, limit, now, contractType);
    }

    public List<TriggerOrderRecord> expirePendingOrders(Instant now, int limit, ProductLine productLine) {
        return triggerOrderRepository.expirePendingOrders(now, limit, productLine);
    }

    public List<TriggerOrderRecord> resetStaleTriggeringOrders(Instant staleBefore,
                                                               Instant now,
                                                               int limit,
                                                               ProductLine productLine) {
        return triggerOrderRepository.resetStaleTriggeringOrders(staleBefore, now, limit, productLine);
    }

    public void markTriggered(long triggerOrderId, long placedOrderId, Instant now) {
        triggerOrderRepository.markTriggered(triggerOrderId, placedOrderId, now);
    }

    public void markTriggerFailed(long triggerOrderId, long placedOrderId, String reason, Instant now) {
        triggerOrderRepository.markTriggerFailed(triggerOrderId, placedOrderId, reason, now);
    }
}
