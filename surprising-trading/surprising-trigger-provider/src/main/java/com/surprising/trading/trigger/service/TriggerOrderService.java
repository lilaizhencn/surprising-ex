package com.surprising.trading.trigger.service;

import com.surprising.account.api.model.PositionUpdatedEvent;
import com.surprising.product.api.ProductLine;
import com.surprising.price.api.model.MarkPriceEvent;
import com.surprising.trading.api.TraceContext;
import com.surprising.trading.api.client.OrderRpcApi;
import com.surprising.trading.api.client.TradingFeeRpcApi;
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
import com.surprising.trading.trigger.model.TriggerOrderRecord;
import com.surprising.trading.trigger.model.TriggerPosition;
import com.surprising.aeron.protocol.CoreTriggerOrderStateView;
import com.surprising.trading.trigger.repository.TriggerOrderOutboxRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 管理止盈止损触发单状态机。
 *
 * <p>在采样标记价格越过配置阈值之前，触发单保持被动状态。触发执行会委托给订单服务，
 * 生成只减仓平仓单，因此账户和持仓状态仍只通过正常的订单、撮合和结算链路变更。</p>
 */
@Service
    public class TriggerOrderService {

    private static final Logger log = LoggerFactory.getLogger(TriggerOrderService.class);
    private static final String TRIGGER_ORDER_SEQUENCE = "trigger-order";
    private static final long MIN_TRAILING_CALLBACK_RATE_PPM = 1_000L;
    private static final long MAX_TRAILING_CALLBACK_RATE_PPM = 100_000L;

    private final TriggerOrderPersistenceService triggerOrderRepository;
    private final OrderRpcApi orderRpcApi;
    private final TriggerProperties properties;
    private final TriggerOrderIndex triggerOrderIndex;
    private final TriggerOrderOutboxRepository outboxRepository;
    private final TransactionTemplate transactionTemplate;
    private final TriggerInstrumentLifecycleFenceService lifecycleFenceService;
    private final TriggerOrderAeronGateway aeronGateway;
    private final AeronTriggerOrderIdGenerator aeronOrderIds;
    private final TradingFeeRpcApi tradingFeeRpcApi;

    public TriggerOrderService(TriggerOrderPersistenceService triggerOrderRepository,
                               OrderRpcApi orderRpcApi,
                               TriggerProperties properties) {
        this(triggerOrderRepository, orderRpcApi, properties, TriggerOrderIndex.disabled());
    }

    public TriggerOrderService(TriggerOrderPersistenceService triggerOrderRepository,
                               OrderRpcApi orderRpcApi,
                               TriggerProperties properties,
                               TriggerOrderIndex triggerOrderIndex) {
        this(triggerOrderRepository, orderRpcApi, properties, triggerOrderIndex, null, null, null);
    }

    public TriggerOrderService(TriggerOrderPersistenceService triggerOrderRepository,
                               OrderRpcApi orderRpcApi,
                               TriggerProperties properties,
                               TriggerOrderIndex triggerOrderIndex,
                               TriggerOrderOutboxRepository outboxRepository,
                               PlatformTransactionManager transactionManager) {
        this(triggerOrderRepository, orderRpcApi, properties, triggerOrderIndex,
                outboxRepository, transactionManager, null);
    }

    public TriggerOrderService(TriggerOrderPersistenceService triggerOrderRepository,
                               OrderRpcApi orderRpcApi,
                               TriggerProperties properties,
                               TriggerOrderIndex triggerOrderIndex,
                               TriggerOrderOutboxRepository outboxRepository,
                               PlatformTransactionManager transactionManager,
                               TriggerInstrumentLifecycleFenceService lifecycleFenceService) {
        this(triggerOrderRepository, orderRpcApi, properties, triggerOrderIndex, outboxRepository,
                transactionManager, lifecycleFenceService, null, null);
    }

    public TriggerOrderService(TriggerOrderPersistenceService triggerOrderRepository,
                               OrderRpcApi orderRpcApi,
                               TriggerProperties properties,
                               TriggerOrderIndex triggerOrderIndex,
                               TriggerOrderOutboxRepository outboxRepository,
                               PlatformTransactionManager transactionManager,
                               TriggerInstrumentLifecycleFenceService lifecycleFenceService,
                               TriggerOrderAeronGateway aeronGateway,
                               AeronTriggerOrderIdGenerator aeronOrderIds) {
        this(triggerOrderRepository, orderRpcApi, properties, triggerOrderIndex, outboxRepository,
                transactionManager, lifecycleFenceService, aeronGateway, aeronOrderIds, null);
    }

    @Autowired
    public TriggerOrderService(TriggerOrderPersistenceService triggerOrderRepository,
                               OrderRpcApi orderRpcApi,
                               TriggerProperties properties,
                               TriggerOrderIndex triggerOrderIndex,
                               TriggerOrderOutboxRepository outboxRepository,
                               PlatformTransactionManager transactionManager,
                               TriggerInstrumentLifecycleFenceService lifecycleFenceService,
                               TriggerOrderAeronGateway aeronGateway,
                               AeronTriggerOrderIdGenerator aeronOrderIds,
                               TradingFeeRpcApi tradingFeeRpcApi) {
        this.triggerOrderRepository = triggerOrderRepository;
        this.orderRpcApi = orderRpcApi;
        this.properties = properties;
        this.triggerOrderIndex = triggerOrderIndex;
        this.outboxRepository = outboxRepository;
        this.transactionTemplate = transactionManager == null ? null : new TransactionTemplate(transactionManager);
        this.lifecycleFenceService = lifecycleFenceService;
        this.aeronGateway = aeronGateway;
        this.aeronOrderIds = aeronOrderIds;
        this.tradingFeeRpcApi = tradingFeeRpcApi;
    }

    @Transactional
    public TriggerOrderResponse place(PlaceTriggerOrderRequest request) {
        PlaceTriggerOrderRequest normalized = normalize(request);
        ProductLine productLine = currentProductLine();
        if (aeronEnabled()) {
            return placeAeron(normalized, productLine);
        }
        if (hasClientTriggerOrderId(normalized)) {
            var existing = triggerOrderRepository.findByClientTriggerOrderId(
                    productLine, normalized.userId(), normalized.clientTriggerOrderId());
            if (existing.isPresent()) {
                TriggerOrderRecord existingOrder = existing.get();
                requireTriggerOrderCurrentProductLine(existingOrder);
                requireSameClientTriggerIntent(normalized, existingOrder);
                return toResponse(existingOrder);
            }
        }

        if (lifecycleFenceService != null) {
            lifecycleFenceService.requirePlacementAllowed(productLine, normalized.symbol());
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
        // 先写索引再插入数据库，确保已提交的静态止盈止损单一定存在候选索引成员。
        // 如果随后数据库回滚，只会留下一个无害的陈旧候选成员。
        triggerOrderIndex.indexPlaced(order);
        removeIndexOnRollback(order);
        boolean inserted = triggerOrderRepository.insert(order);
        if (!inserted && hasClientTriggerOrderId(normalized)) {
            triggerOrderIndex.remove(order);
            var duplicate = triggerOrderRepository.findByClientTriggerOrderId(productLine, normalized.userId(),
                    normalized.clientTriggerOrderId());
            if (duplicate.isEmpty()) {
                throw new IllegalStateException("duplicate trigger id but order not found");
            }
            TriggerOrderRecord existing = duplicate.get();
            requireTriggerOrderCurrentProductLine(existing);
            requireSameClientTriggerIntent(normalized, existing);
            return toResponse(existing);
        }
        if (!inserted) {
            triggerOrderIndex.remove(order);
            throw new IllegalStateException("failed to insert trigger order " + triggerOrderId);
        }
        enqueueStatusChange(order);
        return toResponse(order);
    }

    private TriggerOrderResponse placeAeron(PlaceTriggerOrderRequest request, ProductLine productLine) {
        Instant now = Instant.now();
        long id = aeronOrderIds.next();
        long expires = request.expiresAt() == null ? 0 : request.expiresAt().toEpochMilli();
        var view = new com.surprising.aeron.protocol.CoreTriggerOrderStateView(id, productLine, request.userId(),
                emptyToNull(request.clientTriggerOrderId()), emptyToNull(request.ocoGroupId()), request.symbol(),
                com.surprising.aeron.protocol.CoreOrderSide.valueOf(request.side().name()),
                com.surprising.aeron.protocol.CoreTriggerOrderType.valueOf(request.triggerType().name()),
                com.surprising.aeron.protocol.CoreTriggerCondition.valueOf(triggerCondition(request.side(), request.triggerType()).name()),
                request.triggerPriceTicks(), request.activationPriceTicks() == null ? 0 : request.activationPriceTicks(),
                request.callbackRatePpm() == null ? 0 : request.callbackRatePpm(), 0, 0, 0,
                com.surprising.aeron.protocol.CoreOrderType.valueOf(request.orderType().name()),
                com.surprising.aeron.protocol.CoreTimeInForce.valueOf(request.timeInForce().name()), request.priceTicks(),
                request.quantitySteps(), com.surprising.aeron.protocol.CoreMarginMode.valueOf(request.marginMode().name()),
                com.surprising.aeron.protocol.CorePositionSide.valueOf(request.positionSide().name()),
                com.surprising.aeron.protocol.CoreTriggerOrderStatus.PENDING, 0, 0, 0, "", TraceContext.currentOrCreate(),
                expires, 0, now.toEpochMilli(), now.toEpochMilli(), 1,
                0, 0, 0);
        UUID commandId = UUID.nameUUIDFromBytes(("TRIGGER_PLACE:" + request.userId() + ':'
                + (request.clientTriggerOrderId() == null ? id : request.clientTriggerOrderId()))
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        aeronGateway.command(com.surprising.aeron.protocol.CoreMessageType.PLACE_TRIGGER_ORDER, commandId,
                request.userId(), com.surprising.aeron.protocol.CoreTriggerOrderCodec.encodeState(view));
        return TriggerOrderAeronGateway.response(view);
    }

    /** 幂等键只能重放同一份触发条件，参数变化必须拒绝并保持原订单不变。 */
    private void requireSameClientTriggerIntent(PlaceTriggerOrderRequest request, TriggerOrderRecord existing) {
        boolean same = existing.userId() == request.userId()
                && existing.productLine() == currentProductLine()
                && Objects.equals(existing.clientTriggerOrderId(), emptyToNull(request.clientTriggerOrderId()))
                && Objects.equals(existing.ocoGroupId(), emptyToNull(request.ocoGroupId()))
                && Objects.equals(existing.symbol(), request.symbol())
                && existing.side() == request.side()
                && existing.triggerType() == request.triggerType()
                && existing.triggerPriceTicks() == request.triggerPriceTicks()
                && Objects.equals(existing.activationPriceTicks(), request.activationPriceTicks())
                && Objects.equals(existing.callbackRatePpm(), request.callbackRatePpm())
                && existing.orderType() == request.orderType()
                && existing.timeInForce() == request.timeInForce()
                && existing.priceTicks() == request.priceTicks()
                && existing.quantitySteps() == request.quantitySteps()
                && existing.marginMode() == request.marginMode()
                && existing.positionSide() == request.positionSide()
                && Objects.equals(existing.expiresAt(), request.expiresAt());
        if (!same) {
            throw new IllegalArgumentException("clientTriggerOrderId already used with different trigger parameters");
        }
    }

    @Transactional
    public void onPositionClosed(PositionUpdatedEvent event) {
        if (event == null || event.signedQuantitySteps() != 0L || event.eventTime() == null) {
            return;
        }
        if (properties.getExecution().isCoreOnly()) {
            aeronEnabled();
            return;
        }
        if (aeronEnabled()) {
            scanAeronOpenOrders(event.userId(), normalizeSymbol(event.symbol()), "position-closed", open -> {
                for (CoreTriggerOrderStateView order : open) {
                    if (order.marginMode().name().equals(event.marginMode().name())
                            && order.positionSide().name().equals(event.positionSide().name())) {
                        aeronGateway.cancel(event.userId(), order.triggerOrderId());
                    }
                }
            });
            return;
        }
        List<TriggerOrderRecord> canceled = triggerOrderRepository.positionClosedCancellations(
                currentProductLine(), event.userId(), normalizeSymbol(event.symbol()), event.marginMode(),
                event.positionSide(), event.eventTime());
        canceled.forEach(this::enqueueStatusChange);
        afterCommit(() -> canceled.forEach(triggerOrderIndex::synchronize));
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

    public TriggerOrderResponse get(long userId, long triggerOrderId) {
        if (userId <= 0 || triggerOrderId <= 0) {
            throw new IllegalArgumentException("userId and triggerOrderId must be positive");
        }
        if (aeronEnabled()) {
            CoreTriggerOrderStateView value = aeronGateway.get(userId, triggerOrderId);
            if (value == null || value.userId() != userId) {
                throw new IllegalStateException("trigger order not found: " + triggerOrderId);
            }
            return TriggerOrderAeronGateway.response(value);
        }
        TriggerOrderRecord order = triggerOrderRepository.findById(triggerOrderId)
                .orElseThrow(() -> new IllegalStateException("trigger order not found: " + triggerOrderId));
        requireTriggerOrderProductLine(order, currentProductLineFilter());
        if (order.userId() != userId) {
            throw new IllegalStateException("trigger order not found: " + triggerOrderId);
        }
        return toResponse(order);
    }

    public TriggerOrderResponse get(long triggerOrderId, ProductLine productLine) {
        if (triggerOrderId <= 0) {
            throw new IllegalArgumentException("triggerOrderId must be positive");
        }
        if (aeronEnabled()) {
            CoreTriggerOrderStateView value = aeronGateway.get(0, triggerOrderId);
            if (value == null || (productLine != null && value.productLine() != productLine)) {
                throw new IllegalStateException("trigger order not found: " + triggerOrderId);
            }
            if (value.userId() != 0 && productLine == null) {
                return TriggerOrderAeronGateway.response(value);
            }
            return TriggerOrderAeronGateway.response(value);
        }
        return triggerOrderRepository.findById(triggerOrderId)
                .map(order -> {
                    requireTriggerOrderProductLine(order, productLine);
                    return toResponse(order);
                })
                .orElseThrow(() -> new IllegalStateException("trigger order not found: " + triggerOrderId));
    }

    /** 到期生命周期直接终止尚未完成的触发状态机。 */
    @Transactional
    public int cancelLifecycleOrders(String symbol, int limit) {
        String normalizedSymbol = normalizeSymbol(symbol);
        if (aeronEnabled()) {
            List<CoreTriggerOrderStateView> open = aeronGateway.openOrders(0, normalizedSymbol, 0,
                    Math.min(1000, Math.max(1, limit)));
            int canceled = 0;
            for (CoreTriggerOrderStateView order : open) {
                if (canceled >= limit) break;
                aeronGateway.cancel(order.userId(), order.triggerOrderId());
                canceled++;
            }
            return canceled;
        }
        List<TriggerOrderRecord> canceled = triggerOrderRepository.cancelForLifecycle(
                currentProductLine(), normalizedSymbol, limit, Instant.now());
        for (TriggerOrderRecord order : canceled) {
            enqueueStatusChange(order);
            afterCommit(() -> triggerOrderIndex.remove(order));
        }
        return canceled.size();
    }

    public boolean hasLifecycleActiveOrders(String symbol) {
        if (aeronEnabled()) {
            return !aeronGateway.openOrders(0, normalizeSymbol(symbol), 0, 1).isEmpty();
        }
        return triggerOrderRepository.hasLifecycleActiveOrders(
                currentProductLine(), normalizeSymbol(symbol));
    }

    @Transactional
    public TriggerOrderResponse cancel(CancelTriggerOrderRequest request) {
        if (request.userId() <= 0 || request.triggerOrderId() <= 0) {
            throw new IllegalArgumentException("userId and triggerOrderId must be positive");
        }
        if (aeronEnabled()) {
            var current = aeronGateway.get(request.userId(), request.triggerOrderId());
            if (current == null) throw new IllegalStateException("trigger order not found: " + request.triggerOrderId());
            if (current.status() == com.surprising.aeron.protocol.CoreTriggerOrderStatus.PENDING) {
                aeronGateway.cancel(request.userId(), request.triggerOrderId());
                current = aeronGateway.get(request.userId(), request.triggerOrderId());
            }
            return TriggerOrderAeronGateway.response(current);
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
        if (aeronEnabled()) {
            List<TriggerOrderResponse> orders = aeronGateway.openOrders(request.userId(), symbol, 0, limit).stream()
                    .map(TriggerOrderAeronGateway::response).toList();
            List<TriggerOrderBatchItemResponse> results = new ArrayList<>();
            for (int i = 0; i < orders.size(); i++) {
                try {
                    results.add(new TriggerOrderBatchItemResponse(i, true, "completed",
                            cancel(new CancelTriggerOrderRequest(request.userId(), orders.get(i).triggerOrderId()))));
                } catch (RuntimeException ex) {
                    results.add(new TriggerOrderBatchItemResponse(i, false, ex.getMessage(), null));
                }
            }
            return triggerBatchResponse(results);
        }
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
        return openOrders(userId, symbol, limit, null);
    }

    public TriggerOrderQueryResponse openOrders(long userId, String symbol, int limit, String cursor) {
        if (userId <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
        if (limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("limit must be in [1, 1000]");
        }
        String normalizedSymbol = symbol == null || symbol.isBlank() ? null : normalizeSymbol(symbol);
        if (aeronEnabled()) {
            long beforeTriggerOrderId = decodeOpenTriggerCursor(cursor);
            List<TriggerOrderResponse> values = aeronGateway.openOrders(userId, normalizedSymbol,
                    beforeTriggerOrderId, limit + 1).stream()
                    .map(TriggerOrderAeronGateway::response).toList();
            boolean hasMore = values.size() > limit;
            List<TriggerOrderResponse> orders = hasMore ? values.subList(0, limit) : values;
            String next = hasMore && !orders.isEmpty()
                    ? encodeOpenTriggerCursor(orders.getLast().triggerOrderId()) : null;
            return new TriggerOrderQueryResponse(orders.size(), List.copyOf(orders), next, hasMore,
                    "createdAt.desc", limit);
        }
        if (cursor != null && !cursor.isBlank()) {
            throw new IllegalArgumentException("cursor is supported only in Aeron Core mode");
        }
        String contractType = currentProductContractType();
        List<TriggerOrderResponse> orders = triggerOrderRepository.openOrders(
                userId, normalizedSymbol, limit, contractType)
                .stream()
                .map(this::toResponse)
                .toList();
        return new TriggerOrderQueryResponse(orders.size(), orders);
    }

    static String encodeOpenTriggerCursor(long triggerOrderId) {
        if (triggerOrderId <= 0) {
            throw new IllegalArgumentException("open-trigger cursor id must be positive");
        }
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("trigger:" + triggerOrderId).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    static long decodeOpenTriggerCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) return Long.MAX_VALUE;
        try {
            String decoded = new String(java.util.Base64.getUrlDecoder().decode(cursor),
                    java.nio.charset.StandardCharsets.UTF_8);
            if (!decoded.startsWith("trigger:")) throw new IllegalArgumentException("invalid open-trigger cursor");
            long id = Long.parseLong(decoded.substring("trigger:".length()));
            if (id <= 0) throw new IllegalArgumentException("invalid open-trigger cursor");
            return id;
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("invalid open-trigger cursor", exception);
        }
    }

    private boolean aeronEnabled() {
        if (properties.getExecution().isCoreOnly()
                && (aeronGateway == null || aeronOrderIds == null)) {
            throw new IllegalStateException("Aeron Core trigger gateway is required in core-only mode");
        }
        return aeronGateway != null && aeronOrderIds != null;
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

    public void onMarkPrice(MarkPriceEvent markPrice) {
        if (markPrice == null || markPrice.markPriceTicks() <= 0 || markPrice.eventTime() == null) {
            throw new IllegalArgumentException("valid fixed-point mark price is required");
        }
        if (properties.getExecution().isCoreOnly()) {
            return;
        }
        if (aeronEnabled()) {
            onAeronMarkPrice(markPrice);
            return;
        }
        onTriggerPrice(markPrice, markPrice.markPriceTicks());
    }

    private void onAeronMarkPrice(MarkPriceEvent markPrice) {
        scanAeronOpenOrders(0, markPrice.symbol(), "mark-price", open -> {
            for (CoreTriggerOrderStateView order : open) {
                if (order.status() != com.surprising.aeron.protocol.CoreTriggerOrderStatus.PENDING) continue;
                if (order.expiresAtEpochMillis() > 0
                        && order.expiresAtEpochMillis() <= markPrice.eventTime().toEpochMilli()) {
                    aeronGateway.expire(order.triggerOrderId(), markPrice.eventTime().toEpochMilli());
                    continue;
                }
                if (order.triggerType() == com.surprising.aeron.protocol.CoreTriggerOrderType.TRAILING_STOP) {
                    processAeronTrailing(markPrice, order);
                } else if (triggered(order.triggerCondition(), markPrice.markPriceTicks(), order.triggerPriceTicks())) {
                    claimAndExecuteAeron(order, markPrice);
                }
            }
        });
    }

    private void processAeronTrailing(MarkPriceEvent markPrice, CoreTriggerOrderStateView order) {
        long price = markPrice.markPriceTicks();
        boolean sell = order.side() == com.surprising.aeron.protocol.CoreOrderSide.SELL;
        long highest = order.highestPriceTicks();
        long lowest = order.lowestPriceTicks();
        long activatedAt = order.activatedAtEpochMillis();
        if (activatedAt == 0 && order.activationPriceTicks() > 0
                && ((sell && price < order.activationPriceTicks()) || (!sell && price > order.activationPriceTicks()))) {
            return;
        }
        if (activatedAt == 0) activatedAt = markPrice.eventTime().toEpochMilli();
        highest = highest == 0 ? price : (sell ? Math.max(highest, price) : highest);
        lowest = lowest == 0 ? price : (sell ? lowest : Math.min(lowest, price));
        aeronGateway.updateTrailing(order.triggerOrderId(), highest, lowest, activatedAt);
        long threshold = sell ? trailingSellThreshold(highest, order.callbackRatePpm())
                : trailingBuyThreshold(lowest, order.callbackRatePpm());
        if ((sell && price <= threshold) || (!sell && price >= threshold)) {
            claimAndExecuteAeron(order, markPrice);
        }
    }

    private void claimAndExecuteAeron(CoreTriggerOrderStateView order, MarkPriceEvent markPrice) {
        try {
            TriggerTraceContext.set(order.traceId());
            aeronGateway.execute(order.triggerOrderId(), markPrice.sequence(), markPrice.markPriceTicks(),
                    markPrice.eventTime().toEpochMilli());
        } catch (RuntimeException ex) {
            log.error("Failed to execute Aeron trigger order id={}: {}", order.triggerOrderId(), ex.getMessage(), ex);
        } finally {
            TriggerTraceContext.clear();
        }
    }

    private static boolean triggered(com.surprising.aeron.protocol.CoreTriggerCondition condition,
                                     long price, long triggerPrice) {
        return condition == com.surprising.aeron.protocol.CoreTriggerCondition.GREATER_OR_EQUAL
                ? price >= triggerPrice : price <= triggerPrice;
    }

    private static long trailingSellThreshold(long highest, long callbackRatePpm) {
        return java.math.BigInteger.valueOf(highest)
                .multiply(java.math.BigInteger.valueOf(1_000_000L - callbackRatePpm))
                .divide(java.math.BigInteger.valueOf(1_000_000L)).longValueExact();
    }

    private static long trailingBuyThreshold(long lowest, long callbackRatePpm) {
        return java.math.BigInteger.valueOf(lowest)
                .multiply(java.math.BigInteger.valueOf(1_000_000L + callbackRatePpm))
                .divide(java.math.BigInteger.valueOf(1_000_000L)).longValueExact();
    }

    private void onTriggerPrice(MarkPriceEvent priceTrigger, long triggerPriceTicks) {
        if (aeronEnabled()) {
            onTriggerPriceAeron(priceTrigger, triggerPriceTicks);
            return;
        }
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

    private void onTriggerPriceAeron(MarkPriceEvent priceTrigger, long triggerPriceTicks) {
        scanAeronOpenOrders(0, priceTrigger.symbol(), "trigger-price", candidates -> {
            for (CoreTriggerOrderStateView order : candidates) {
                if (order.status() != com.surprising.aeron.protocol.CoreTriggerOrderStatus.PENDING) {
                    continue;
                }
                if (order.expiresAtEpochMillis() > 0
                        && order.expiresAtEpochMillis() <= priceTrigger.eventTime().toEpochMilli()) {
                    aeronGateway.expire(order.triggerOrderId(), priceTrigger.eventTime().toEpochMilli());
                    continue;
                }
                boolean matched = order.triggerCondition() == com.surprising.aeron.protocol.CoreTriggerCondition.GREATER_OR_EQUAL
                        ? triggerPriceTicks >= order.triggerPriceTicks()
                        : triggerPriceTicks <= order.triggerPriceTicks();
                if (!matched) continue;
                try {
                    aeronGateway.execute(order.triggerOrderId(), priceTrigger.sequence(), triggerPriceTicks,
                            priceTrigger.eventTime().toEpochMilli());
                } catch (RuntimeException ex) {
                    log.error("Failed to execute Aeron trigger order id={}: {}", order.triggerOrderId(), ex.getMessage(), ex);
                }
            }
        });
    }

    public void maintenance() {
        Instant now = Instant.now();
        if (aeronEnabled()) {
            long nowMillis = now.toEpochMilli();
            List<CoreTriggerOrderStateView> expired = aeronGateway.expiredOrders(
                    nowMillis, Math.min(1000, Math.max(1, properties.getExecution().getTriggerBatchSize())));
            for (CoreTriggerOrderStateView order : expired) {
                if (order.status() == com.surprising.aeron.protocol.CoreTriggerOrderStatus.PENDING
                        && order.expiresAtEpochMillis() > 0
                        && order.expiresAtEpochMillis() <= nowMillis) {
                    aeronGateway.expire(order.triggerOrderId(), nowMillis);
                }
            }
            long staleBefore = now.minus(properties.getExecution().getStaleTriggeringAfter()).toEpochMilli();
            scanAeronOpenOrders(0, null, "maintenance",
                com.surprising.aeron.protocol.CoreTriggerOrderStatus.TRIGGERING, open -> {
                for (CoreTriggerOrderStateView order : open) {
                    if (order.updatedAtEpochMillis() <= staleBefore) {
                        aeronGateway.retry(order.triggerOrderId(), staleBefore, nowMillis);
                    }
                }
            });
            return;
        }
        expirePending(now, properties.getExecution().getTriggerBatchSize());
        Instant staleBefore = now.minus(properties.getExecution().getStaleTriggeringAfter());
        resetStaleTriggering(staleBefore, now, properties.getExecution().getTriggerBatchSize());
    }

    private void scanAeronOpenOrders(long userId, String symbol, String operation,
                                     Consumer<List<CoreTriggerOrderStateView>> pageConsumer) {
        scanAeronOpenOrders(userId, symbol, operation, null, pageConsumer);
    }

    private void scanAeronOpenOrders(long userId, String symbol, String operation,
                                     com.surprising.aeron.protocol.CoreTriggerOrderStatus status,
                                     Consumer<List<CoreTriggerOrderStateView>> pageConsumer) {
        int pageSize = Math.min(1000, Math.max(1, properties.getExecution().getTriggerBatchSize()));
        int maxPages = Math.min(256, Math.max(1, properties.getExecution().getMaxTriggerScanPages()));
        long before = 0;
        for (int pageNumber = 1; pageNumber <= maxPages; pageNumber++) {
            List<CoreTriggerOrderStateView> page = status == null
                    ? aeronGateway.openOrders(userId, symbol, before, pageSize)
                    : aeronGateway.openOrders(userId, symbol, before, pageSize, status);
            if (page.isEmpty()) {
                return;
            }
            pageConsumer.accept(page);
            long nextBefore = page.getLast().triggerOrderId();
            if (page.size() < pageSize) {
                return;
            }
            if (nextBefore <= 0 || (before != 0 && nextBefore >= before)) {
                log.error("Non-monotonic Aeron trigger cursor operation={} symbol={} before={} nextBefore={}",
                        operation, symbol, before, nextBefore);
                return;
            }
            before = nextBefore;
        }
        log.warn("Aeron trigger scan reached page bound operation={} userId={} symbol={} pageSize={} maxPages={}",
                operation, userId, symbol, pageSize, maxPages);
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
                // 索引清理不能使数据库抢占失效；陈旧成员会在下一次精确抢占时被拒绝。
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
            // 生成的客户端编号保持稳定，因此进程或网络故障后的重试是安全的。
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
            // 保持 TRIGGERING 状态；维护任务会重置陈旧记录，等待后续标记价格事件重试。
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
        ProductLine configured = properties.getKafka().getProductLine();
        // 未开启产品线 Topic 的单实例开发模式只允许承载永续默认链路；生产四线部署
        // 必须打开产品线 Topic 并显式配置产品线，不能把该默认值用于跨线共享。
        return configured != null ? configured : ProductLine.LINEAR_PERPETUAL;
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
