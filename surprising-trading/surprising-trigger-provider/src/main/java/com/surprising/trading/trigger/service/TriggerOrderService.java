package com.surprising.trading.trigger.service;

import com.surprising.trading.api.TraceContext;
import com.surprising.trading.api.client.OrderRpcApi;
import com.surprising.trading.api.model.AdminTriggerOrderTimelineEvent;
import com.surprising.trading.api.model.AdminTriggerOrderTimelineResponse;
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
import com.surprising.trading.api.model.TriggerOrderQueryResponse;
import com.surprising.trading.api.model.TriggerOrderResponse;
import com.surprising.trading.api.model.TriggerOrderStatus;
import com.surprising.trading.api.model.TriggerOrderType;
import com.surprising.trading.api.model.TriggerPriceType;
import com.surprising.trading.trigger.config.TriggerProperties;
import com.surprising.trading.trigger.config.TriggerTraceContext;
import com.surprising.trading.trigger.model.MarkTrigger;
import com.surprising.trading.trigger.model.TriggerOrderRecord;
import com.surprising.trading.trigger.repository.TriggerOrderRepository;
import java.time.Instant;
import java.util.List;
import java.util.OptionalLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the TP/SL trigger-order state machine.
 *
 * <p>Trigger rows are passive until a mark-price event crosses the configured level. Execution is
 * delegated back to order-provider as a reduce-only close order, so account and position state still
 * changes only through the normal order, matching, and settlement pipeline.</p>
 */
@Service
public class TriggerOrderService {

    private static final Logger log = LoggerFactory.getLogger(TriggerOrderService.class);
    private static final String TRIGGER_ORDER_SEQUENCE = "trigger-order";

    private final TriggerOrderRepository triggerOrderRepository;
    private final OrderRpcApi orderRpcApi;
    private final TriggerProperties properties;

    public TriggerOrderService(TriggerOrderRepository triggerOrderRepository,
                               OrderRpcApi orderRpcApi,
                               TriggerProperties properties) {
        this.triggerOrderRepository = triggerOrderRepository;
        this.orderRpcApi = orderRpcApi;
        this.properties = properties;
    }

    @Transactional
    public TriggerOrderResponse place(PlaceTriggerOrderRequest request) {
        PlaceTriggerOrderRequest normalized = normalize(request);
        if (hasClientTriggerOrderId(normalized)) {
            var existing = triggerOrderRepository.findByClientTriggerOrderId(
                    normalized.userId(), normalized.clientTriggerOrderId());
            if (existing.isPresent()) {
                return toResponse(existing.get());
            }
        }

        Instant now = Instant.now();
        if (normalized.expiresAt() != null && !normalized.expiresAt().isAfter(now)) {
            throw new IllegalArgumentException("expiresAt must be in the future");
        }
        triggerOrderRepository.lockUserPositionMode(normalized.userId());
        PositionMode positionMode = triggerOrderRepository.positionMode(normalized.userId());
        normalized = normalizePositionMode(normalized, positionMode);
        triggerOrderRepository.lockUserSymbolMarginScope(normalized.userId(), normalized.symbol());
        if (triggerOrderRepository.hasActiveMarginModeConflict(
                normalized.userId(), normalized.symbol(), normalized.marginMode())) {
            throw new IllegalArgumentException("margin mode switch requires closing positions and open orders first");
        }
        long triggerOrderId = triggerOrderRepository.nextSequence(TRIGGER_ORDER_SEQUENCE);
        TriggerOrderRecord order = new TriggerOrderRecord(
                triggerOrderId,
                normalized.userId(),
                emptyToNull(normalized.clientTriggerOrderId()),
                emptyToNull(normalized.ocoGroupId()),
                normalized.symbol(),
                normalized.side(),
                normalized.triggerType(),
                normalized.triggerPriceType(),
                triggerCondition(normalized.side(), normalized.triggerType()),
                normalized.triggerPriceTicks(),
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
        boolean inserted = triggerOrderRepository.insert(order);
        if (!inserted && hasClientTriggerOrderId(normalized)) {
            return triggerOrderRepository.findByClientTriggerOrderId(normalized.userId(),
                            normalized.clientTriggerOrderId())
                    .map(this::toResponse)
                    .orElseThrow(() -> new IllegalStateException("duplicate trigger id but order not found"));
        }
        if (!inserted) {
            throw new IllegalStateException("failed to insert trigger order " + triggerOrderId);
        }
        return toResponse(order);
    }

    public TriggerOrderResponse get(long triggerOrderId) {
        if (triggerOrderId <= 0) {
            throw new IllegalArgumentException("triggerOrderId must be positive");
        }
        return triggerOrderRepository.findById(triggerOrderId)
                .map(this::toResponse)
                .orElseThrow(() -> new IllegalStateException("trigger order not found: " + triggerOrderId));
    }

    @Transactional
    public TriggerOrderResponse cancel(CancelTriggerOrderRequest request) {
        if (request.userId() <= 0 || request.triggerOrderId() <= 0) {
            throw new IllegalArgumentException("userId and triggerOrderId must be positive");
        }
        TriggerOrderRecord current = triggerOrderRepository.findById(request.triggerOrderId())
                .orElseThrow(() -> new IllegalStateException("trigger order not found: " + request.triggerOrderId()));
        if (current.userId() != request.userId()) {
            throw new IllegalArgumentException("trigger order does not belong to user");
        }
        if (current.status() != TriggerOrderStatus.PENDING) {
            return toResponse(current);
        }
        return triggerOrderRepository.cancel(request.userId(), request.triggerOrderId(), Instant.now())
                .map(this::toResponse)
                .orElseThrow(() -> new IllegalStateException("trigger order disappeared after cancel"));
    }

    public TriggerOrderQueryResponse openOrders(long userId, String symbol, int limit) {
        if (userId <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
        String normalizedSymbol = symbol == null || symbol.isBlank() ? null : normalizeSymbol(symbol);
        List<TriggerOrderResponse> orders = triggerOrderRepository.openOrders(userId, normalizedSymbol, limit)
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
        return adminOrders(userId, symbol, status, triggerOrderId, limit, null, null);
    }

    public TriggerOrderQueryResponse adminOrders(Long userId,
                                                 String symbol,
                                                 String status,
                                                 Long triggerOrderId,
                                                 int limit,
                                                 String cursor,
                                                 String sort) {
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
        var page = triggerOrderRepository.adminOrderPage(
                        userId, normalizedSymbol, normalizedStatus, triggerOrderId, limit, cursor, sort);
        List<TriggerOrderResponse> orders = page.items()
                .stream()
                .map(this::toResponse)
                .toList();
        return new TriggerOrderQueryResponse(orders.size(), orders,
                page.nextCursor(), page.hasMore(), page.sort(), page.limit());
    }

    public AdminTriggerOrderTimelineResponse adminTimeline(long triggerOrderId) {
        if (triggerOrderId <= 0) {
            throw new IllegalArgumentException("triggerOrderId must be positive");
        }
        TriggerOrderRecord order = triggerOrderRepository.findById(triggerOrderId)
                .orElseThrow(() -> new IllegalStateException("trigger order not found: " + triggerOrderId));
        return new AdminTriggerOrderTimelineResponse(toResponse(order), timelineEvents(order));
    }

    public void onMarkPrice(MarkTrigger markTrigger) {
        OptionalLong markPriceTicks = triggerOrderRepository.markPriceTicks(markTrigger.symbol(), markTrigger.sequence());
        if (markPriceTicks.isEmpty()) {
            throw new IllegalStateException("mark price ticks unavailable: " + markTrigger.symbol()
                    + " sequence=" + markTrigger.sequence());
        }
        Instant now = Instant.now();
        List<TriggerOrderRecord> orders = triggerOrderRepository.claimTriggered(markTrigger.symbol(),
                markPriceTicks.getAsLong(), markTrigger.sequence(), markTrigger.eventTime(),
                properties.getExecution().getTriggerBatchSize(), now);
        for (TriggerOrderRecord order : orders) {
            executeTriggeredOrder(order);
        }
    }

    @Scheduled(fixedDelayString = "${surprising.trading.trigger.execution.maintenance-delay-ms:1000}")
    public void maintenance() {
        Instant now = Instant.now();
        triggerOrderRepository.expirePending(now, properties.getExecution().getTriggerBatchSize());
        Instant staleBefore = now.minus(properties.getExecution().getStaleTriggeringAfter());
        triggerOrderRepository.resetStaleTriggering(staleBefore, now, properties.getExecution().getTriggerBatchSize());
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
            if (placed.status() == OrderStatus.REJECTED) {
                triggerOrderRepository.markTriggerFailed(order.triggerOrderId(), placed.orderId(),
                        placed.rejectReason(), now);
            } else {
                triggerOrderRepository.markTriggered(order.triggerOrderId(), placed.orderId(), now);
            }
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
        if (request.triggerPriceType() != TriggerPriceType.MARK_PRICE) {
            throw new IllegalArgumentException("only MARK_PRICE trigger is supported");
        }
        if (request.triggerPriceTicks() <= 0 || request.quantitySteps() <= 0) {
            throw new IllegalArgumentException("triggerPriceTicks and quantitySteps must be positive");
        }
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
                request.triggerPriceType(),
                request.triggerPriceTicks(),
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

    private TriggerCondition triggerCondition(OrderSide side, TriggerOrderType triggerType) {
        if (triggerType == TriggerOrderType.TAKE_PROFIT) {
            return side == OrderSide.SELL ? TriggerCondition.GREATER_OR_EQUAL : TriggerCondition.LESS_OR_EQUAL;
        }
        return side == OrderSide.SELL ? TriggerCondition.LESS_OR_EQUAL : TriggerCondition.GREATER_OR_EQUAL;
    }

    private String triggeredClientOrderId(long triggerOrderId) {
        return "trigger-" + triggerOrderId;
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
                order.triggerPriceType(),
                order.triggerCondition(),
                order.triggerPriceTicks(),
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
}
