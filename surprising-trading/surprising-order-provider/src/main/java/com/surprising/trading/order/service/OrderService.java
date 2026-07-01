package com.surprising.trading.order.service;

import com.surprising.trading.api.TraceContext;
import com.surprising.trading.api.model.CancelOrderRequest;
import com.surprising.trading.api.model.OrderCommandEvent;
import com.surprising.trading.api.model.OrderCommandType;
import com.surprising.trading.api.model.OrderEvent;
import com.surprising.trading.api.model.OrderEventType;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.OrderQueryResponse;
import com.surprising.trading.api.model.OrderResponse;
import com.surprising.trading.api.model.OrderStatus;
import com.surprising.trading.api.model.PlaceOrderRequest;
import com.surprising.trading.order.config.TradingOrderProperties;
import com.surprising.trading.order.model.OrderFeeSnapshot;
import com.surprising.trading.order.model.OrderRecord;
import com.surprising.trading.order.model.ValidationResult;
import com.surprising.trading.order.repository.OrderFeeRepository;
import com.surprising.trading.order.repository.OrderMarginRepository;
import com.surprising.trading.order.repository.OrderRepository;
import com.surprising.trading.order.repository.OutboxRepository;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class OrderService {

    private static final Set<OrderStatus> TERMINAL_STATUSES = Set.of(
            OrderStatus.REJECTED,
            OrderStatus.CANCELED,
            OrderStatus.FILLED);

    private final ObjectMapper objectMapper;
    private final TradingOrderProperties properties;
    private final OrderValidator orderValidator;
    private final ReduceOnlyValidator reduceOnlyValidator;
    private final OrderRepository orderRepository;
    private final OrderFeeRepository orderFeeRepository;
    private final OrderMarginRepository orderMarginRepository;
    private final OutboxRepository outboxRepository;

    public OrderService(ObjectMapper objectMapper,
                        TradingOrderProperties properties,
                        OrderValidator orderValidator,
                        ReduceOnlyValidator reduceOnlyValidator,
                        OrderRepository orderRepository,
                        OrderFeeRepository orderFeeRepository,
                        OrderMarginRepository orderMarginRepository,
                        OutboxRepository outboxRepository) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.orderValidator = orderValidator;
        this.reduceOnlyValidator = reduceOnlyValidator;
        this.orderRepository = orderRepository;
        this.orderFeeRepository = orderFeeRepository;
        this.orderMarginRepository = orderMarginRepository;
        this.outboxRepository = outboxRepository;
    }

    @Transactional
    public OrderResponse place(PlaceOrderRequest request) {
        PlaceOrderRequest normalized = normalize(request);
        String traceId = TraceContext.currentOrCreate();
        // clientOrderId is the public idempotency key. Replays return the first persisted result.
        if (hasClientOrderId(normalized)) {
            var existing = orderRepository.findByClientOrderId(normalized.userId(), normalized.clientOrderId());
            if (existing.isPresent()) {
                return toResponse(existing.get());
            }
        }

        Instant now = Instant.now();
        ValidationResult validation = validateMarginMode(normalized.marginMode());
        if (validation.accepted()) {
            validation = orderValidator.validate(normalized);
        }
        if (validation.accepted() && normalized.reduceOnly()) {
            ValidationResult reduceOnlyValidation = reduceOnlyValidator.validate(normalized);
            if (!reduceOnlyValidation.accepted()) {
                validation = ValidationResult.reject(reduceOnlyValidation.rejectReason(), validation.instrumentVersion());
            } else {
                validation = ValidationResult.ok(reduceOnlyValidation.instrumentVersion());
            }
        }
        OrderFeeSnapshot feeSnapshot = rejectedFeeSnapshot();
        if (validation.accepted()) {
            var resolvedFeeSnapshot = orderFeeRepository.snapshot(normalized.userId(), normalized.symbol(),
                    validation.instrumentVersion(), now);
            if (resolvedFeeSnapshot.isEmpty()) {
                validation = ValidationResult.reject("fee schedule unavailable", validation.instrumentVersion());
            } else {
                feeSnapshot = resolvedFeeSnapshot.get();
            }
        }
        long orderId = orderRepository.nextSequence("order");
        OrderStatus status = validation.accepted() ? OrderStatus.ACCEPTED : OrderStatus.REJECTED;
        OrderRecord order = new OrderRecord(
                orderId,
                normalized.userId(),
                emptyToNull(normalized.clientOrderId()),
                normalized.symbol(),
                validation.instrumentVersion(),
                normalized.side(),
                normalized.orderType(),
                normalized.timeInForce(),
                normalized.priceTicks(),
                normalized.quantitySteps(),
                0L,
                validation.accepted() ? normalized.quantitySteps() : 0L,
                normalized.marginMode(),
                feeSnapshot.makerFeeRatePpm(),
                feeSnapshot.takerFeeRatePpm(),
                normalized.reduceOnly(),
                normalized.postOnly(),
                status,
                validation.rejectReason(),
                now,
                now);

        boolean inserted = orderRepository.insert(order);
        if (!inserted && hasClientOrderId(normalized)) {
            return orderRepository.findByClientOrderId(normalized.userId(), normalized.clientOrderId())
                    .map(this::toResponse)
                    .orElseThrow(() -> new IllegalStateException("duplicate clientOrderId but order not found"));
        }
        if (!inserted) {
            throw new IllegalStateException("failed to insert order " + orderId);
        }

        if (validation.accepted() && !normalized.reduceOnly()) {
            validation = reserveInitialMargin(normalized, orderId, validation.instrumentVersion(), now);
            if (!validation.accepted()) {
                orderRepository.reject(orderId, validation.rejectReason(), now);
                order = rejectOrder(order, validation.rejectReason(), now);
            }
        }

        OrderEventType eventType = validation.accepted() ? OrderEventType.ACCEPTED : OrderEventType.REJECTED;
        enqueueOrderEvent(order, eventType, validation.rejectReason(), now, traceId);
        if (validation.accepted()) {
            enqueueCommand(order, OrderCommandType.PLACE, now, traceId);
        }
        return toResponse(order);
    }

    private ValidationResult reserveInitialMargin(PlaceOrderRequest request,
                                                  long orderId,
                                                  long instrumentVersion,
                                                  Instant now) {
        var requirement = orderMarginRepository.requirement(
                request.symbol(), instrumentVersion, request.userId(), request.marginMode(), request.side(),
                request.orderType(), request.priceTicks(), request.quantitySteps(),
                properties.getRisk().getMarketMaxSlippagePpm(),
                properties.getRisk().getMarketMaxMarkAgeMs());
        if (requirement.isEmpty()) {
            return ValidationResult.reject("margin requirement unavailable", instrumentVersion);
        }
        if (!requirement.get().accepted()) {
            return ValidationResult.reject(requirement.get().rejectReason(), instrumentVersion);
        }
        if (requirement.get().initialMarginUnits() <= 0) {
            return ValidationResult.reject("invalid margin requirement", instrumentVersion);
        }
        boolean reserved = orderMarginRepository.reserve(request.userId(), requirement.get().asset(), orderId,
                request.symbol(), request.marginMode(), requirement.get().initialMarginUnits(), now);
        return reserved ? ValidationResult.ok(instrumentVersion)
                : ValidationResult.reject("insufficient available margin", instrumentVersion);
    }

    private ValidationResult validateMarginMode(MarginMode marginMode) {
        return ValidationResult.ok(0L);
    }

    private OrderFeeSnapshot rejectedFeeSnapshot() {
        return new OrderFeeSnapshot(0L, 0L, "REJECTED");
    }

    @Transactional
    public OrderResponse cancel(CancelOrderRequest request) {
        if (request.userId() <= 0 || request.orderId() <= 0) {
            throw new IllegalArgumentException("userId and orderId must be positive");
        }
        OrderRecord order = orderRepository.findByOrderId(request.orderId())
                .orElseThrow(() -> new IllegalStateException("order not found: " + request.orderId()));
        if (order.userId() != request.userId()) {
            throw new IllegalArgumentException("order does not belong to user");
        }
        if (TERMINAL_STATUSES.contains(order.status()) || order.status() == OrderStatus.CANCEL_REQUESTED) {
            return toResponse(order);
        }

        Instant now = Instant.now();
        String traceId = TraceContext.currentOrCreate();
        boolean cancelRequested = orderRepository.requestCancel(order.orderId(), now);
        OrderRecord updated = orderRepository.findByOrderId(order.orderId())
                .orElseThrow(() -> new IllegalStateException("order disappeared after cancel update"));
        if (!cancelRequested) {
            return toResponse(updated);
        }
        enqueueOrderEvent(updated, OrderEventType.CANCEL_REQUESTED, null, now, traceId);
        enqueueCommand(updated, OrderCommandType.CANCEL, now, traceId);
        return toResponse(updated);
    }

    public OrderResponse get(long orderId) {
        return orderRepository.findByOrderId(orderId)
                .map(this::toResponse)
                .orElseThrow(() -> new IllegalStateException("order not found: " + orderId));
    }

    public OrderResponse getByClientOrderId(long userId, String clientOrderId) {
        if (userId <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
        return orderRepository.findByClientOrderId(userId, normalizeClientOrderId(clientOrderId))
                .map(this::toResponse)
                .orElseThrow(() -> new IllegalStateException("order not found for clientOrderId: " + clientOrderId));
    }

    public OrderQueryResponse openOrders(long userId, String symbol, int limit) {
        if (userId <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
        if (limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("limit must be in [1, 1000]");
        }
        String normalizedSymbol = symbol == null || symbol.isBlank() ? null : normalizeSymbol(symbol);
        List<OrderResponse> rows = orderRepository.openOrders(userId, normalizedSymbol, limit)
                .stream()
                .map(this::toResponse)
                .toList();
        return new OrderQueryResponse(rows.size(), rows);
    }

    private void enqueueCommand(OrderRecord order, OrderCommandType commandType, Instant now, String traceId) {
        long commandId = orderRepository.nextSequence("command");
        // The future exchange-core matching provider should treat commandId/orderId as its idempotency keys.
        OrderCommandEvent command = new OrderCommandEvent(
                commandType,
                commandId,
                order.orderId(),
                order.userId(),
                order.clientOrderId(),
                order.symbol(),
                order.instrumentVersion(),
                order.side(),
                order.orderType(),
                order.timeInForce(),
                order.priceTicks(),
                order.quantitySteps(),
                order.marginMode(),
                order.reduceOnly(),
                order.postOnly(),
                now,
                traceId);
        outboxRepository.enqueue("ORDER", order.orderId(), properties.getKafka().getOrderCommandsTopic(),
                order.symbol(), commandType.name(), payload(command), now);
    }

    private OrderRecord rejectOrder(OrderRecord order, String rejectReason, Instant now) {
        return new OrderRecord(
                order.orderId(),
                order.userId(),
                order.clientOrderId(),
                order.symbol(),
                order.instrumentVersion(),
                order.side(),
                order.orderType(),
                order.timeInForce(),
                order.priceTicks(),
                order.quantitySteps(),
                order.executedQuantitySteps(),
                0L,
                order.marginMode(),
                order.makerFeeRatePpm(),
                order.takerFeeRatePpm(),
                order.reduceOnly(),
                order.postOnly(),
                OrderStatus.REJECTED,
                rejectReason,
                order.createdAt(),
                now);
    }

    private void enqueueOrderEvent(OrderRecord order,
                                   OrderEventType eventType,
                                   String reason,
                                   Instant now,
                                   String traceId) {
        long eventId = orderRepository.nextSequence("event");
        OrderEvent event = new OrderEvent(
                eventId,
                order.orderId(),
                order.userId(),
                order.symbol(),
                eventType,
                order.status(),
                reason,
                now,
                traceId);
        orderRepository.insertEvent(event);
        outboxRepository.enqueue("ORDER", order.orderId(), properties.getKafka().getOrderEventsTopic(),
                order.symbol(), eventType.name(), payload(event), now);
    }

    private String payload(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException ex) {
            throw new IllegalStateException("failed to serialize order event", ex);
        }
    }

    private PlaceOrderRequest normalize(PlaceOrderRequest request) {
        return new PlaceOrderRequest(
                request.userId(),
                emptyToNull(request.clientOrderId()),
                normalizeSymbol(request.symbol()),
                request.side(),
                request.orderType(),
                request.timeInForce(),
                request.priceTicks(),
                request.quantitySteps(),
                MarginMode.defaultIfNull(request.marginMode()),
                request.reduceOnly(),
                request.postOnly());
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

    private String normalizeClientOrderId(String clientOrderId) {
        String normalized = emptyToNull(clientOrderId);
        if (normalized == null) {
            throw new IllegalArgumentException("clientOrderId is required");
        }
        if (normalized.length() > 64) {
            throw new IllegalArgumentException("clientOrderId length must be <= 64");
        }
        return normalized;
    }

    private boolean hasClientOrderId(PlaceOrderRequest request) {
        return request.clientOrderId() != null && !request.clientOrderId().isBlank();
    }

    private String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private OrderResponse toResponse(OrderRecord order) {
        return new OrderResponse(
                order.orderId(),
                order.userId(),
                order.clientOrderId(),
                order.symbol(),
                order.instrumentVersion(),
                order.side(),
                order.orderType(),
                order.timeInForce(),
                order.priceTicks(),
                order.quantitySteps(),
                order.executedQuantitySteps(),
                order.remainingQuantitySteps(),
                order.marginMode(),
                order.makerFeeRatePpm(),
                order.takerFeeRatePpm(),
                order.reduceOnly(),
                order.postOnly(),
                order.status(),
                order.rejectReason(),
                order.createdAt(),
                order.updatedAt());
    }
}
