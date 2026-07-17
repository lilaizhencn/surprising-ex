package com.surprising.trading.trigger.service;

import com.surprising.account.api.model.PositionUpdatedEvent;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.TraceContext;
import com.surprising.trading.api.client.OrderRpcApi;
import com.surprising.trading.api.model.AdminTriggerOrderTimelineEvent;
import com.surprising.trading.api.model.AdminTriggerOrderTimelineResponse;
import com.surprising.trading.api.model.BatchCancelTriggerOrdersRequest;
import com.surprising.trading.api.model.BatchPlaceTriggerOrderRequest;
import com.surprising.trading.api.model.CancelOpenTriggerOrdersRequest;
import com.surprising.trading.api.model.CancelTriggerOrderRequest;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.OrderResponse;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.OrderStatus;
import com.surprising.trading.api.model.OrderType;
import com.surprising.trading.api.model.PlaceOrderRequest;
import com.surprising.trading.api.model.PlaceTriggerOrderRequest;
import com.surprising.trading.api.model.PositionMode;
import com.surprising.trading.api.model.PositionSide;
import com.surprising.trading.api.model.TimeInForce;
import com.surprising.trading.api.model.TriggerCondition;
import com.surprising.trading.api.model.TriggerOrderBatchItemResponse;
import com.surprising.trading.api.model.TriggerOrderBatchResponse;
import com.surprising.trading.api.model.TriggerOrderQueryResponse;
import com.surprising.trading.api.model.TriggerOrderResponse;
import com.surprising.trading.api.model.TriggerOrderStatus;
import com.surprising.trading.api.model.TriggerOrderType;
import com.surprising.trading.trigger.config.TriggerProperties;
import com.surprising.trading.trigger.config.TriggerTraceContext;
import com.surprising.trading.trigger.model.MarkTrigger;
import com.surprising.trading.trigger.model.TriggerOrderRecord;
import com.surprising.trading.trigger.model.TriggerPosition;
import com.surprising.trading.trigger.repository.TriggerOrderOutboxRepository;
import com.surprising.trading.trigger.repository.TriggerOrderRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Owns the TP/SL trigger-order state machine.
 *
 * <p>Trigger rows are passive until the sampled mark price crosses the configured level. Execution is
 * delegated back to order-provider as a reduce-only close order, so account and position state still
 * changes only through the normal order, matching, and settlement pipeline.</p>
 */
@Service
public class TriggerOrderService {

    private static final Logger log = LoggerFactory.getLogger(TriggerOrderService.class);
    private static final String TRIGGER_ORDER_SEQUENCE = "trigger-order";
    private static final long MIN_TRAILING_CALLBACK_RATE_PPM = 1_000L;
    private static final long MAX_TRAILING_CALLBACK_RATE_PPM = 100_000L;

    private final TriggerOrderRepository triggerOrderRepository;
    private final OrderRpcApi orderRpcApi;
    private final TriggerProperties properties;
    private final TriggerOrderIndex triggerOrderIndex;
    private final TriggerOrderOutboxRepository outboxRepository;
    private final TransactionTemplate transactionTemplate;

    public TriggerOrderService(TriggerOrderRepository triggerOrderRepository,
                               OrderRpcApi orderRpcApi,
                               TriggerProperties properties) {
        this(triggerOrderRepository, orderRpcApi, properties, TriggerOrderIndex.disabled());
    }

    public TriggerOrderService(TriggerOrderRepository triggerOrderRepository,
                               OrderRpcApi orderRpcApi,
                               TriggerProperties properties,
                               TriggerOrderIndex triggerOrderIndex) {
        this(triggerOrderRepository, orderRpcApi, properties, triggerOrderIndex, null, null);
    }

    @Autowired
    public TriggerOrderService(TriggerOrderRepository triggerOrderRepository,
                               OrderRpcApi orderRpcApi,
                               TriggerProperties properties,
                               TriggerOrderIndex triggerOrderIndex,
                               TriggerOrderOutboxRepository outboxRepository,
                               PlatformTransactionManager transactionManager) {
        this.triggerOrderRepository = triggerOrderRepository;
        this.orderRpcApi = orderRpcApi;
        this.properties = properties;
        this.triggerOrderIndex = triggerOrderIndex;
        this.outboxRepository = outboxRepository;
        this.transactionTemplate = transactionManager == null ? null : new TransactionTemplate(transactionManager);
    }

    @Transactional
    public TriggerOrderResponse place(PlaceTriggerOrderRequest request) {
        PlaceTriggerOrderRequest normalized = normalize(request);
        ProductLine productLine = currentProductLine();
        if (hasClientTriggerOrderId(normalized)) {
            var existing = triggerOrderRepository.findByClientTriggerOrderId(
                    productLine, normalized.userId(), normalized.clientTriggerOrderId());
            if (existing.isPresent()) {
                TriggerOrderRecord existingOrder = existing.get();
                requireTriggerOrderCurrentProductLine(existingOrder);
                return toResponse(existingOrder);
            }
        }

        Instant now = Instant.now();
        if (normalized.expiresAt() != null && !normalized.expiresAt().isAfter(now)) {
            throw new IllegalArgumentException("expiresAt must be in the future");
        }
        triggerOrderRepository.lockUserPositionMode(productLine, normalized.userId());
        PositionMode positionMode = triggerOrderRepository.positionMode(productLine, normalized.userId());
        normalized = normalizePositionMode(normalized, positionMode);
        triggerOrderRepository.lockUserSymbolMarginScope(productLine, normalized.userId(), normalized.symbol());
        if (triggerOrderRepository.hasActiveMarginModeConflict(
                productLine, normalized.userId(), normalized.symbol(), normalized.marginMode())) {
            throw new IllegalArgumentException("margin mode switch requires closing positions and open orders first");
        }
        validateCloseCapacity(productLine, normalized);
        long triggerOrderId = triggerOrderRepository.nextSequence(TRIGGER_ORDER_SEQUENCE);
        TriggerOrderRecord order = new TriggerOrderRecord(
                triggerOrderId,
                productLine,
                normalized.userId(),
                emptyToNull(normalized.clientTriggerOrderId()),
                emptyToNull(normalized.ocoGroupId()),
                normalized.symbol(),
                normalized.side(),
                normalized.triggerType(),
                triggerCondition(normalized.side(), normalized.triggerType()),
                normalized.triggerPriceTicks(),
                normalized.activationPriceTicks(),
                normalized.callbackRatePpm(),
                null,
                null,
                null,
                normalized.orderType(),
                normalized.timeInForce(),
                normalized.priceTicks(),
                normalized.quantitySteps(),
                normalized.marginMode(),
                normalized.positionSide(),
                TriggerOrderStatus.PENDING,
                null,
                null,
                null,
                null,
                TraceContext.currentOrCreate(),
                normalized.expiresAt(),
                null,
                now,
                now);
        // Index before insertion: a committed static TP/SL can never exist
        // without its candidate member. A later database rollback only leaves a harmless stale candidate.
        triggerOrderIndex.indexPlaced(order);
        removeIndexOnRollback(order);
        boolean inserted = triggerOrderRepository.insert(order);
        if (!inserted && hasClientTriggerOrderId(normalized)) {
            triggerOrderIndex.remove(order);
            return triggerOrderRepository.findByClientTriggerOrderId(productLine, normalized.userId(),
                            normalized.clientTriggerOrderId())
                    .map(existing -> {
                        requireTriggerOrderCurrentProductLine(existing);
                        return toResponse(existing);
                    })
                    .orElseThrow(() -> new IllegalStateException("duplicate trigger id but order not found"));
        }
        if (!inserted) {
            triggerOrderIndex.remove(order);
            throw new IllegalStateException("failed to insert trigger order " + triggerOrderId);
        }
        enqueueStatusChange(order);
        return toResponse(order);
    }

    public void onPositionClosed(PositionUpdatedEvent event) {
        if (event == null || event.signedQuantitySteps() != 0L || event.eventTime() == null) {
            return;
        }
        triggerOrderRepository.positionClosedCancellations(
                        currentProductLine(), event.userId(), normalizeSymbol(event.symbol()), event.marginMode(),
                        event.positionSide(), event.eventTime())
                .forEach(triggerOrderIndex::synchronize);
    }

    @Transactional
    public TriggerOrderBatchResponse placeBatch(BatchPlaceTriggerOrderRequest request) {
        List<PlaceTriggerOrderRequest> orders = request == null ? List.of() : request.orders();
        requireBatchSize(orders.size(), 20, "orders");
        if (request != null && Boolean.TRUE.equals(request.atomic())) {
            return placeAtomicBatch(orders);
        }
        List<TriggerOrderBatchItemResponse> results = new ArrayList<>();
        for (int i = 0; i < orders.size(); i++) {
            try {
                TriggerOrderResponse order = place(orders.get(i));
                results.add(new TriggerOrderBatchItemResponse(i, true, "completed", order));
            } catch (IllegalArgumentException | IllegalStateException ex) {
                results.add(new TriggerOrderBatchItemResponse(i, false, ex.getMessage(), null));
            }
        }
        return triggerBatchResponse(results);
    }

    private TriggerOrderBatchResponse placeAtomicBatch(List<PlaceTriggerOrderRequest> orders) {
        List<TriggerOrderBatchItemResponse> results = new ArrayList<>();
        try {
            for (int i = 0; i < orders.size(); i++) {
                TriggerOrderResponse order = place(orders.get(i));
                results.add(new TriggerOrderBatchItemResponse(i, true, "completed", order));
            }
            return triggerBatchResponse(results);
        } catch (IllegalArgumentException | IllegalStateException ex) {
            List<TriggerOrderBatchItemResponse> rejected = new ArrayList<>();
            String message = "atomic batch rejected: " + ex.getMessage();
            for (int i = 0; i < orders.size(); i++) {
                rejected.add(new TriggerOrderBatchItemResponse(i, false, message, null));
            }
            throw new AtomicTriggerBatchRejectedException(triggerBatchResponse(rejected), ex);
        }
    }

    public TriggerOrderResponse get(long triggerOrderId) {
        return get(triggerOrderId, currentProductLineFilter());
    }

    public TriggerOrderResponse get(long triggerOrderId, ProductLine productLine) {
        if (triggerOrderId <= 0) {
            throw new IllegalArgumentException("triggerOrderId must be positive");
        }
        return triggerOrderRepository.findById(triggerOrderId)
                .map(order -> {
                    requireTriggerOrderProductLine(order, productLine);
                    return toResponse(order);
                })
                .orElseThrow(() -> new IllegalStateException("trigger order not found: " + triggerOrderId));
    }

    @Transactional
    public TriggerOrderResponse cancel(CancelTriggerOrderRequest request) {
        if (request.userId() <= 0 || request.triggerOrderId() <= 0) {
            throw new IllegalArgumentException("userId and triggerOrderId must be positive");
        }
        TriggerOrderRecord current = triggerOrderRepository.findById(request.triggerOrderId())
                .orElseThrow(() -> new IllegalStateException("trigger order not found: " + request.triggerOrderId()));
        requireTriggerOrderCurrentProductLine(current);
        if (current.userId() != request.userId()) {
            throw new IllegalArgumentException("trigger order does not belong to user");
        }
        if (current.status() != TriggerOrderStatus.PENDING) {
            return toResponse(current);
        }
        TriggerOrderRecord updated = triggerOrderRepository.cancel(
                        request.userId(), request.triggerOrderId(), Instant.now())
                .orElseThrow(() -> new IllegalStateException("trigger order disappeared after cancel"));
        if (updated.status() == TriggerOrderStatus.CANCELED) {
            enqueueStatusChange(updated);
            afterCommit(() -> triggerOrderIndex.remove(updated));
        }
        return toResponse(updated);
    }

    @Transactional
    public TriggerOrderBatchResponse cancelBatch(BatchCancelTriggerOrdersRequest request) {
        List<CancelTriggerOrderRequest> orders = request == null ? List.of() : request.orders();
        requireBatchSize(orders.size(), 50, "orders");
        List<TriggerOrderBatchItemResponse> results = new ArrayList<>();
        for (int i = 0; i < orders.size(); i++) {
            try {
                TriggerOrderResponse order = cancel(orders.get(i));
                results.add(new TriggerOrderBatchItemResponse(i, true, "completed", order));
            } catch (IllegalArgumentException | IllegalStateException ex) {
                results.add(new TriggerOrderBatchItemResponse(i, false, ex.getMessage(), null));
            }
        }
        return triggerBatchResponse(results);
    }

    @Transactional
    public TriggerOrderBatchResponse cancelOpenOrders(CancelOpenTriggerOrdersRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("cancel open trigger orders request is required");
        }
        if (request.userId() <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
        int limit = request.limit() == null ? 1000 : request.limit();
        if (limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("limit must be in [1, 1000]");
        }
        String symbol = request.symbol() == null || request.symbol().isBlank()
                ? null
                : normalizeSymbol(request.symbol());
        String contractType = currentProductContractType();
        List<TriggerOrderRecord> orders = contractType == null
                ? triggerOrderRepository.pendingCancelableOrders(request.userId(), symbol, limit)
                : triggerOrderRepository.pendingCancelableOrders(request.userId(), symbol, limit, contractType);
        List<TriggerOrderBatchItemResponse> results = new ArrayList<>();
        for (int i = 0; i < orders.size(); i++) {
            try {
                TriggerOrderResponse order = cancel(new CancelTriggerOrderRequest(
                        request.userId(), orders.get(i).triggerOrderId()));
                results.add(new TriggerOrderBatchItemResponse(i, true, "completed", order));
            } catch (IllegalArgumentException | IllegalStateException ex) {
                results.add(new TriggerOrderBatchItemResponse(i, false, ex.getMessage(), null));
            }
        }
        return triggerBatchResponse(results);
    }

    public TriggerOrderQueryResponse openOrders(long userId, String symbol, int limit) {
        if (userId <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
        String normalizedSymbol = symbol == null || symbol.isBlank() ? null : normalizeSymbol(symbol);
        String contractType = currentProductContractType();
        List<TriggerOrderResponse> orders = triggerOrderRepository.openOrders(
                userId, normalizedSymbol, limit, contractType)
                .stream()
                .map(this::toResponse)
                .toList();
        return new TriggerOrderQueryResponse(orders.size(), orders);
    }

    public TriggerOrderQueryResponse adminOrders(Long userId,
                                                 String symbol,
                                                 String status,
                                                 Long triggerOrderId,
                                                 int limit) {
        return adminOrders(userId, symbol, status, triggerOrderId, limit, null, null, null);
    }

    public TriggerOrderQueryResponse adminOrders(Long userId,
                                                 String symbol,
                                                 String status,
                                                 Long triggerOrderId,
                                                 int limit,
                                                 String cursor,
                                                 String sort) {
        return adminOrders(userId, symbol, status, triggerOrderId, limit, cursor, sort, null);
    }

    public TriggerOrderQueryResponse adminOrders(Long userId,
                                                 String symbol,
                                                 String status,
                                                 Long triggerOrderId,
                                                 int limit,
                                                 String cursor,
                                                 String sort,
                                                 ProductLine productLine) {
        if (userId != null && userId <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
        if (triggerOrderId != null && triggerOrderId <= 0) {
            throw new IllegalArgumentException("triggerOrderId must be positive");
        }
        if (limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("limit must be in [1, 1000]");
        }
        String normalizedSymbol = symbol == null || symbol.isBlank() ? null : normalizeSymbol(symbol);
        TriggerOrderStatus normalizedStatus = status == null || status.isBlank()
                ? null
                : TriggerOrderStatus.valueOf(status.trim().toUpperCase());
        String contractType = contractType(productLine);
        var page = contractType == null
                ? triggerOrderRepository.adminOrderPage(
                        userId, normalizedSymbol, normalizedStatus, triggerOrderId, limit, cursor, sort)
                : triggerOrderRepository.adminOrderPage(
                        userId, normalizedSymbol, normalizedStatus, triggerOrderId, limit, contractType, cursor, sort);
        List<TriggerOrderResponse> orders = page.items()
                .stream()
                .map(this::toResponse)
                .toList();
        return new TriggerOrderQueryResponse(orders.size(), orders,
                page.nextCursor(), page.hasMore(), page.sort(), page.limit());
    }

    public AdminTriggerOrderTimelineResponse adminTimeline(long triggerOrderId) {
        return adminTimeline(triggerOrderId, null);
    }

    public AdminTriggerOrderTimelineResponse adminTimeline(long triggerOrderId, ProductLine productLine) {
        if (triggerOrderId <= 0) {
            throw new IllegalArgumentException("triggerOrderId must be positive");
        }
        TriggerOrderRecord order = triggerOrderRepository.findById(triggerOrderId)
                .orElseThrow(() -> new IllegalStateException("trigger order not found: " + triggerOrderId));
        requireTriggerOrderProductLine(order, productLine);
        return new AdminTriggerOrderTimelineResponse(toResponse(order), timelineEvents(order));
    }

    public void onMarkPrice(MarkTrigger markTrigger) {
        OptionalLong markPriceTicks = markPriceTicks(markTrigger);
        if (markPriceTicks.isEmpty()) {
            if (!hasPendingOrders(markTrigger.symbol())) {
                return;
            }
            throw new IllegalStateException("mark price ticks unavailable: " + markTrigger.symbol()
                    + " sequence=" + markTrigger.sequence());
        }
        onTriggerPrice(markTrigger, markPriceTicks.getAsLong());
    }

    private OptionalLong markPriceTicks(MarkTrigger markTrigger) {
        String contractType = currentProductContractType();
        return contractType == null
                ? triggerOrderRepository.markPriceTicks(markTrigger.symbol(), markTrigger.sequence())
                : triggerOrderRepository.markPriceTicks(markTrigger.symbol(), markTrigger.sequence(), contractType);
    }

    private void onTriggerPrice(MarkTrigger priceTrigger, long triggerPriceTicks) {
        Instant now = Instant.now();
        List<TriggerOrderRecord> orders = new ArrayList<>(claimTriggered(priceTrigger.symbol(),
                triggerPriceTicks, priceTrigger.sequence(), priceTrigger.eventTime(),
                properties.getExecution().getTriggerBatchSize(), now));
        orders.addAll(claimTrailingTriggered(priceTrigger.symbol(), triggerPriceTicks,
                priceTrigger.sequence(), priceTrigger.eventTime(),
                properties.getExecution().getTriggerBatchSize(), now));
        for (TriggerOrderRecord order : orders) {
            executeTriggeredOrder(order);
        }
    }

    @Scheduled(fixedDelayString = "${surprising.trading.trigger.execution.maintenance-delay-ms:1000}")
    public void maintenance() {
        Instant now = Instant.now();
        expirePending(now, properties.getExecution().getTriggerBatchSize());
        Instant staleBefore = now.minus(properties.getExecution().getStaleTriggeringAfter());
        resetStaleTriggering(staleBefore, now, properties.getExecution().getTriggerBatchSize());
    }

    private boolean hasPendingOrders(String symbol) {
        String contractType = currentProductContractType();
        return contractType == null
                ? triggerOrderRepository.hasPendingOrders(symbol)
                : triggerOrderRepository.hasPendingOrders(symbol, contractType);
    }

    private List<TriggerOrderRecord> claimTriggered(String symbol,
                                                    long triggerPriceTicks,
                                                    long triggerSequence,
                                                    Instant triggeredAt,
                                                    int limit,
                                                    Instant now) {
        int candidateLimit = Math.max(limit, properties.getRedisIndex().getCandidateBatchSize());
        var candidateIds = triggerOrderIndex.dueCandidates(
                currentProductLine(), symbol, triggerPriceTicks, candidateLimit);
        if (candidateIds.isPresent()) {
            if (candidateIds.get().isEmpty()) {
                return List.of();
            }
            List<TriggerOrderRecord> claimed = inTransaction(() -> {
                List<TriggerOrderRecord> rows = triggerOrderRepository.claimTriggeredCandidates(
                        currentProductLine(), symbol, triggerPriceTicks, triggerSequence,
                        triggeredAt, limit, now, candidateIds.get());
                enqueueClaimChanges(rows);
                return rows;
            });
            try {
                cleanupCandidateIndex(symbol, candidateIds.get());
                cleanupOcoIndex(claimed);
            } catch (RuntimeException ex) {
                // Index cleanup cannot invalidate the DB claim. Stale members are rejected on the next exact claim.
                log.warn("Trigger candidate cleanup failed line={} symbol={}: {}",
                        currentProductLine(), symbol, ex.getMessage());
            }
            return claimed;
        }
        String contractType = currentProductContractType();
        List<TriggerOrderRecord> claimed = inTransaction(() -> {
            List<TriggerOrderRecord> rows = contractType == null
                    ? triggerOrderRepository.claimTriggered(symbol, triggerPriceTicks,
                            triggerSequence, triggeredAt, limit, now)
                    : triggerOrderRepository.claimTriggered(symbol, triggerPriceTicks,
                            triggerSequence, triggeredAt, limit, now, contractType);
            enqueueClaimChanges(rows);
            return rows;
        });
        try {
            cleanupOcoIndex(claimed);
        } catch (RuntimeException ex) {
            log.warn("Trigger OCO index cleanup failed line={} symbol={}: {}",
                    currentProductLine(), symbol, ex.getMessage());
        }
        return claimed;
    }

    private List<TriggerOrderRecord> claimTrailingTriggered(String symbol,
                                                            long triggerPriceTicks,
                                                            long triggerSequence,
                                                            Instant triggeredAt,
                                                            int limit,
                                                            Instant now) {
        String contractType = currentProductContractType();
        List<TriggerOrderRecord> claimed = inTransaction(() -> {
            List<TriggerOrderRecord> rows = contractType == null
                    ? triggerOrderRepository.claimTrailingTriggered(symbol, triggerPriceTicks,
                            triggerSequence, triggeredAt, limit, now)
                    : triggerOrderRepository.claimTrailingTriggered(symbol, triggerPriceTicks,
                            triggerSequence, triggeredAt, limit, now, contractType);
            enqueueClaimChanges(rows);
            return rows;
        });
        try {
            cleanupOcoIndex(claimed);
        } catch (RuntimeException ex) {
            log.warn("Trailing trigger OCO index cleanup failed line={} symbol={}: {}",
                    currentProductLine(), symbol, ex.getMessage());
        }
        return claimed;
    }

    private void expirePending(Instant now, int limit) {
        List<TriggerOrderRecord> expired = inTransaction(() -> {
            List<TriggerOrderRecord> rows = triggerOrderRepository.expirePendingOrders(
                    now, limit, currentProductLine());
            rows.forEach(this::enqueueStatusChange);
            return rows;
        });
        expired.forEach(triggerOrderIndex::remove);
    }

    private void resetStaleTriggering(Instant staleBefore, Instant now, int limit) {
        List<TriggerOrderRecord> reset = inTransaction(() -> {
            List<TriggerOrderRecord> rows = triggerOrderRepository.resetStaleTriggeringOrders(
                    staleBefore, now, limit, currentProductLine());
            rows.forEach(this::enqueueStatusChange);
            return rows;
        });
        reset.forEach(triggerOrderIndex::synchronize);
    }

    private void executeTriggeredOrder(TriggerOrderRecord order) {
        try {
            TriggerTraceContext.set(order.traceId());
            // The generated client id is stable, so retries after process or network failure are safe.
            OrderResponse placed = orderRpcApi.place(new PlaceOrderRequest(
                    order.userId(),
                    triggeredClientOrderId(order.triggerOrderId()),
                    order.symbol(),
                    order.side(),
                    order.orderType(),
                    order.timeInForce(),
                    order.priceTicks(),
                    order.quantitySteps(),
                    order.marginMode(),
                    order.positionSide(),
                    true,
                    false));
            Instant now = Instant.now();
            inTransaction(() -> {
                if (placed.status() == OrderStatus.REJECTED) {
                    triggerOrderRepository.markTriggerFailed(order.triggerOrderId(), placed.orderId(),
                            placed.rejectReason(), now);
                } else {
                    triggerOrderRepository.markTriggered(order.triggerOrderId(), placed.orderId(), now);
                }
                if (outboxRepository != null) {
                    TriggerOrderRecord updated = triggerOrderRepository.findById(order.triggerOrderId())
                            .orElseThrow(() -> new IllegalStateException(
                                    "trigger order disappeared after execution: " + order.triggerOrderId()));
                    enqueueStatusChange(updated);
                }
                return null;
            });
            triggerOrderIndex.remove(order);
        } catch (Exception ex) {
            // Leave the row in TRIGGERING; maintenance will reset stale rows for a later mark event.
            log.error("Failed to execute trigger order id={}: {}", order.triggerOrderId(), ex.getMessage(), ex);
        } finally {
            TriggerTraceContext.clear();
        }
    }

    private PlaceTriggerOrderRequest normalize(PlaceTriggerOrderRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("trigger order request is required");
        }
        PositionSide positionSide = PositionSide.defaultIfNull(request.positionSide());
        if (request.userId() <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
        if (request.side() == null || request.triggerType() == null || request.orderType() == null
                || request.timeInForce() == null) {
            throw new IllegalArgumentException("side, triggerType, orderType and timeInForce are required");
        }
        if (request.quantitySteps() <= 0) {
            throw new IllegalArgumentException("quantitySteps must be positive");
        }
        validateTriggerPriceFields(request);
        validateExecutionOrder(request.orderType(), request.timeInForce(), request.priceTicks());
        String clientTriggerOrderId = emptyToNull(request.clientTriggerOrderId());
        if (clientTriggerOrderId != null && clientTriggerOrderId.length() > 64) {
            throw new IllegalArgumentException("clientTriggerOrderId length must be <= 64");
        }
        String ocoGroupId = emptyToNull(request.ocoGroupId());
        if (ocoGroupId != null && ocoGroupId.length() > 64) {
            throw new IllegalArgumentException("ocoGroupId length must be <= 64");
        }
        return new PlaceTriggerOrderRequest(
                request.userId(),
                clientTriggerOrderId,
                ocoGroupId,
                normalizeSymbol(request.symbol()),
                request.side(),
                request.triggerType(),
                request.triggerPriceTicks(),
                request.activationPriceTicks(),
                request.callbackRatePpm(),
                request.orderType(),
                request.timeInForce(),
                request.priceTicks(),
                request.quantitySteps(),
                MarginMode.defaultIfNull(request.marginMode()),
                positionSide,
                request.expiresAt());
    }

    private PlaceTriggerOrderRequest normalizePositionMode(PlaceTriggerOrderRequest request, PositionMode positionMode) {
        PositionMode normalizedMode = PositionMode.defaultIfNull(positionMode);
        PositionSide positionSide = PositionSide.defaultIfNull(request.positionSide());
        if (normalizedMode == PositionMode.ONE_WAY) {
            if (positionSide.isHedgeSide()) {
                throw new IllegalArgumentException("positionSide LONG/SHORT requires HEDGE position mode");
            }
            return request;
        }
        if (!positionSide.isHedgeSide()) {
            throw new IllegalArgumentException("positionSide LONG or SHORT is required in HEDGE position mode");
        }
        if (!positionSide.isClosingSide(request.side())) {
            throw new IllegalArgumentException("trigger order side must close the selected hedge positionSide");
        }
        return request;
    }

    private void validateCloseCapacity(ProductLine productLine, PlaceTriggerOrderRequest request) {
        TriggerPosition position = triggerOrderRepository.lockedPosition(productLine, request.userId(), request.symbol(),
                request.marginMode(), request.positionSide()).orElse(null);
        long signedQuantity = position == null ? 0L : position.signedQuantitySteps();
        if (signedQuantity == 0) {
            throw new IllegalArgumentException("trigger order requires an open position");
        }
        if (position.instrumentVersion() <= 0) {
            throw new IllegalArgumentException("trigger order position instrument version is missing");
        }
        OrderSide closeSide = signedQuantity > 0 ? OrderSide.SELL : OrderSide.BUY;
        if (request.side() != closeSide) {
            throw new IllegalArgumentException("trigger order side does not reduce current position");
        }
        long openReduceOnlySteps = triggerOrderRepository.openReduceOnlySteps(productLine, request.userId(),
                request.symbol(),
                request.marginMode(), request.positionSide(), position.instrumentVersion(), closeSide);
        long triggerCapacitySteps = triggerOrderRepository.pendingTriggerCloseSteps(productLine, request.userId(),
                request.symbol(),
                request.marginMode(), request.positionSide(), closeSide);
        long sameOcoGroupMax = triggerOrderRepository.pendingTriggerOcoGroupMaxSteps(productLine, request.userId(),
                request.symbol(), request.marginMode(), request.positionSide(), closeSide, request.ocoGroupId());
        long projectedTriggerCapacity = Math.addExact(
                Math.subtractExact(triggerCapacitySteps, sameOcoGroupMax),
                Math.max(sameOcoGroupMax, request.quantitySteps()));
        long projectedCloseSteps = Math.addExact(openReduceOnlySteps, projectedTriggerCapacity);
        if (projectedCloseSteps > Math.absExact(signedQuantity)) {
            throw new IllegalArgumentException("trigger order quantity exceeds available position");
        }
    }

    private void validateExecutionOrder(OrderType orderType, TimeInForce timeInForce, long priceTicks) {
        if (orderType == OrderType.MARKET) {
            if (priceTicks != 0) {
                throw new IllegalArgumentException("market trigger execution priceTicks must be zero");
            }
            if (timeInForce != TimeInForce.IOC && timeInForce != TimeInForce.FOK) {
                throw new IllegalArgumentException("market trigger execution requires IOC or FOK");
            }
            return;
        }
        if (priceTicks <= 0) {
            throw new IllegalArgumentException("limit trigger execution priceTicks must be positive");
        }
        if (timeInForce == TimeInForce.GTX) {
            throw new IllegalArgumentException("trigger execution does not support GTX");
        }
    }

    private void validateTriggerPriceFields(PlaceTriggerOrderRequest request) {
        if (request.triggerType() == TriggerOrderType.TRAILING_STOP) {
            if (request.orderType() != OrderType.MARKET) {
                throw new IllegalArgumentException("trailing stop execution requires MARKET");
            }
            if (request.triggerPriceTicks() < 0) {
                throw new IllegalArgumentException("trailing stop triggerPriceTicks must be zero or positive");
            }
            if (request.activationPriceTicks() != null && request.activationPriceTicks() < 0) {
                throw new IllegalArgumentException("activationPriceTicks must be zero or positive");
            }
            if (request.callbackRatePpm() == null) {
                throw new IllegalArgumentException("callbackRatePpm is required for trailing stop");
            }
            if (request.callbackRatePpm() < MIN_TRAILING_CALLBACK_RATE_PPM
                    || request.callbackRatePpm() > MAX_TRAILING_CALLBACK_RATE_PPM) {
                throw new IllegalArgumentException("callbackRatePpm must be in [1000, 100000]");
            }
            return;
        }
        if (request.triggerPriceTicks() <= 0) {
            throw new IllegalArgumentException("triggerPriceTicks must be positive");
        }
        if (request.activationPriceTicks() != null || request.callbackRatePpm() != null) {
            throw new IllegalArgumentException("activationPriceTicks and callbackRatePpm require TRAILING_STOP");
        }
    }

    private TriggerCondition triggerCondition(OrderSide side, TriggerOrderType triggerType) {
        if (triggerType == TriggerOrderType.TAKE_PROFIT) {
            return side == OrderSide.SELL ? TriggerCondition.GREATER_OR_EQUAL : TriggerCondition.LESS_OR_EQUAL;
        }
        return side == OrderSide.SELL ? TriggerCondition.LESS_OR_EQUAL : TriggerCondition.GREATER_OR_EQUAL;
    }

    private String triggeredClientOrderId(long triggerOrderId) {
        return "trigger-" + triggerOrderId;
    }

    private void requireBatchSize(int size, int max, String field) {
        if (size < 1 || size > max) {
            throw new IllegalArgumentException(field + " size must be in [1, " + max + "]");
        }
    }

    private TriggerOrderBatchResponse triggerBatchResponse(List<TriggerOrderBatchItemResponse> results) {
        int completed = (int) results.stream().filter(TriggerOrderBatchItemResponse::success).count();
        return new TriggerOrderBatchResponse(results.size(), completed, results.size() - completed, results);
    }

    private void requireTriggerOrderProductLine(TriggerOrderRecord order, ProductLine productLine) {
        requireTriggerOrderContractType(order, contractType(productLine));
    }

    private void requireTriggerOrderCurrentProductLine(TriggerOrderRecord order) {
        requireTriggerOrderContractType(order, currentProductContractType());
    }

    private void requireTriggerOrderContractType(TriggerOrderRecord order, String contractType) {
        if (contractType == null) {
            return;
        }
        if (!triggerOrderRepository.triggerOrderMatchesContractType(order.triggerOrderId(), contractType)) {
            throw new IllegalStateException("trigger order not found: " + order.triggerOrderId());
        }
    }

    private String contractType(ProductLine productLine) {
        return productLine == null ? null : productLine.contractTypeCode();
    }

    private String currentProductContractType() {
        return properties.getKafka().isProductTopicsEnabled() ? contractType(currentProductLine()) : null;
    }

    private ProductLine currentProductLineFilter() {
        return properties.getKafka().isProductTopicsEnabled() ? currentProductLine() : null;
    }

    private TriggerOrderResponse toResponse(TriggerOrderRecord order) {
        return new TriggerOrderResponse(
                order.triggerOrderId(),
                order.userId(),
                order.clientTriggerOrderId(),
                order.ocoGroupId(),
                order.symbol(),
                order.side(),
                order.triggerType(),
                order.triggerCondition(),
                order.triggerPriceTicks(),
                order.activationPriceTicks(),
                order.callbackRatePpm(),
                order.highestPriceTicks(),
                order.lowestPriceTicks(),
                order.activatedAt(),
                order.orderType(),
                order.timeInForce(),
                order.priceTicks(),
                order.quantitySteps(),
                order.marginMode(),
                order.positionSide(),
                order.status(),
                order.placedOrderId(),
                order.triggerSequence(),
                order.triggeredPriceTicks(),
                order.rejectReason(),
                order.traceId(),
                order.expiresAt(),
                order.triggeredAt(),
                order.createdAt(),
                order.updatedAt());
    }

    private List<AdminTriggerOrderTimelineEvent> timelineEvents(TriggerOrderRecord order) {
        var events = new java.util.ArrayList<AdminTriggerOrderTimelineEvent>();
        events.add(new AdminTriggerOrderTimelineEvent(
                "CREATED",
                TriggerOrderStatus.PENDING,
                null,
                null,
                null,
                null,
                order.traceId(),
                order.createdAt()));
        if (order.expiresAt() != null && order.status() == TriggerOrderStatus.EXPIRED) {
            events.add(new AdminTriggerOrderTimelineEvent(
                    "EXPIRED",
                    order.status(),
                    null,
                    null,
                    null,
                    "expiresAt reached",
                    order.traceId(),
                    order.updatedAt()));
        }
        if (order.status() == TriggerOrderStatus.CANCELED) {
            events.add(new AdminTriggerOrderTimelineEvent(
                    "CANCELED",
                    order.status(),
                    null,
                    null,
                    null,
                    "trigger order canceled",
                    order.traceId(),
                    order.updatedAt()));
        }
        if (order.triggeredAt() != null) {
            events.add(new AdminTriggerOrderTimelineEvent(
                    "TRIGGERED_MARK",
                    order.status(),
                    order.triggerSequence(),
                    order.triggeredPriceTicks(),
                    null,
                    null,
                    order.traceId(),
                    order.triggeredAt()));
        }
        if (order.placedOrderId() != null) {
            events.add(new AdminTriggerOrderTimelineEvent(
                    order.status() == TriggerOrderStatus.TRIGGER_FAILED ? "EXECUTION_REJECTED" : "EXECUTION_PLACED",
                    order.status(),
                    order.triggerSequence(),
                    order.triggeredPriceTicks(),
                    order.placedOrderId(),
                    order.rejectReason(),
                    order.traceId(),
                    order.updatedAt()));
        }
        return events.stream()
                .sorted(java.util.Comparator.comparing(AdminTriggerOrderTimelineEvent::eventTime))
                .toList();
    }

    private String normalizeSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("symbol is required");
        }
        String normalized = symbol.trim().toUpperCase();
        if (!normalized.matches("[A-Z0-9][A-Z0-9_-]{1,63}")) {
            throw new IllegalArgumentException("invalid symbol: " + symbol);
        }
        return normalized;
    }

    private boolean hasClientTriggerOrderId(PlaceTriggerOrderRequest request) {
        return request.clientTriggerOrderId() != null && !request.clientTriggerOrderId().isBlank();
    }

    private String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private ProductLine currentProductLine() {
        return properties.getKafka().getProductLine();
    }

    private void cleanupCandidateIndex(String symbol, List<Long> candidateIds) {
        List<TriggerOrderRecord> persisted = triggerOrderRepository.findByIds(candidateIds);
        Set<Long> foundIds = new HashSet<>();
        for (TriggerOrderRecord order : persisted) {
            foundIds.add(order.triggerOrderId());
            if (order.status() != TriggerOrderStatus.PENDING
                    && order.status() != TriggerOrderStatus.TRIGGERING) {
                triggerOrderIndex.remove(order);
            }
        }
        for (Long candidateId : candidateIds) {
            if (!foundIds.contains(candidateId)) {
                triggerOrderIndex.remove(currentProductLine(), symbol, candidateId);
            }
        }
    }

    private void cleanupOcoIndex(List<TriggerOrderRecord> claimed) {
        Set<String> handledGroups = new HashSet<>();
        for (TriggerOrderRecord order : claimed) {
            if (order.ocoGroupId() == null || order.ocoGroupId().isBlank()) {
                continue;
            }
            String groupKey = order.productLine() + ":" + order.userId() + ":" + order.symbol() + ":"
                    + order.marginMode() + ":" + order.ocoGroupId();
            if (!handledGroups.add(groupKey)) {
                continue;
            }
            triggerOrderRepository.ocoGroupOrders(order).stream()
                    .filter(sibling -> sibling.status() != TriggerOrderStatus.PENDING
                            && sibling.status() != TriggerOrderStatus.TRIGGERING)
                    .forEach(triggerOrderIndex::remove);
        }
    }

    private void enqueueClaimChanges(List<TriggerOrderRecord> claimed) {
        if (outboxRepository == null || claimed.isEmpty()) {
            return;
        }
        claimed.forEach(this::enqueueStatusChange);
        Set<Long> published = new HashSet<>();
        claimed.forEach(order -> published.add(order.triggerOrderId()));
        for (TriggerOrderRecord order : claimed) {
            if (order.ocoGroupId() == null || order.ocoGroupId().isBlank()) {
                continue;
            }
            triggerOrderRepository.ocoGroupOrders(order).stream()
                    .filter(sibling -> sibling.status() == TriggerOrderStatus.CANCELED)
                    .filter(sibling -> published.add(sibling.triggerOrderId()))
                    .forEach(this::enqueueStatusChange);
        }
    }

    private void enqueueStatusChange(TriggerOrderRecord order) {
        if (outboxRepository != null) {
            outboxRepository.enqueue(order, toResponse(order));
        }
    }

    private <T> T inTransaction(Supplier<T> action) {
        if (transactionTemplate == null) {
            return action.get();
        }
        return transactionTemplate.execute(status -> action.get());
    }

    private void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    private void removeIndexOnRollback(TriggerOrderRecord order) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == TransactionSynchronization.STATUS_ROLLED_BACK) {
                    triggerOrderIndex.remove(order);
                }
            }
        });
    }
}
