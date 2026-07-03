package com.surprising.trading.order.service;

import com.surprising.trading.api.TraceContext;
import com.surprising.trading.api.model.AdminBatchCancelOrdersRequest;
import com.surprising.trading.api.model.AdminCancelBySymbolRequest;
import com.surprising.trading.api.model.AdminCancelOrderResult;
import com.surprising.trading.api.model.AdminCancelOrdersResponse;
import com.surprising.trading.api.model.AdminCancelOrdersPreviewResponse;
import com.surprising.trading.api.model.AdminMatchResultQueryResponse;
import com.surprising.trading.api.model.AdminMatchTradeQueryResponse;
import com.surprising.trading.api.model.AdminOrderEventQueryResponse;
import com.surprising.trading.api.model.AdminOrderTimelineResponse;
import com.surprising.trading.api.model.CancelOrderRequest;
import com.surprising.instrument.api.model.InstrumentType;
import com.surprising.trading.api.model.OrderCommandEvent;
import com.surprising.trading.api.model.OrderCommandType;
import com.surprising.trading.api.model.OrderEvent;
import com.surprising.trading.api.model.OrderEventType;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.OrderQueryResponse;
import com.surprising.trading.api.model.OrderResponse;
import com.surprising.trading.api.model.OrderStatus;
import com.surprising.trading.api.model.PlaceOrderRequest;
import com.surprising.trading.api.model.PositionMode;
import com.surprising.trading.api.model.PositionSide;
import com.surprising.trading.order.config.TradingOrderProperties;
import com.surprising.trading.order.model.OrderFeeSnapshot;
import com.surprising.trading.order.model.OrderRecord;
import com.surprising.trading.order.model.ValidationResult;
import com.surprising.trading.order.repository.OrderFeeRepository;
import com.surprising.trading.order.repository.OrderMarginRepository;
import com.surprising.trading.order.repository.OrderRepository;
import com.surprising.trading.order.repository.OutboxRepository;
import com.surprising.trading.order.repository.SpotOrderReservationRepository;
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
    private final SpotOrderReservationRepository spotOrderReservationRepository;
    private final OutboxRepository outboxRepository;

    public OrderService(ObjectMapper objectMapper,
                        TradingOrderProperties properties,
                        OrderValidator orderValidator,
                        ReduceOnlyValidator reduceOnlyValidator,
                        OrderRepository orderRepository,
                        OrderFeeRepository orderFeeRepository,
                        OrderMarginRepository orderMarginRepository,
                        SpotOrderReservationRepository spotOrderReservationRepository,
                        OutboxRepository outboxRepository) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.orderValidator = orderValidator;
        this.reduceOnlyValidator = reduceOnlyValidator;
        this.orderRepository = orderRepository;
        this.orderFeeRepository = orderFeeRepository;
        this.orderMarginRepository = orderMarginRepository;
        this.spotOrderReservationRepository = spotOrderReservationRepository;
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

        orderRepository.lockUserPositionMode(normalized.userId());
        PositionMode positionMode = orderRepository.positionMode(normalized.userId());
        normalized = normalizePositionMode(normalized, positionMode);
        orderRepository.lockUserSymbolMarginScope(normalized.userId(), normalized.symbol());
        Instant now = Instant.now();
        ValidationResult validation = validateMarginMode(normalized);
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
                normalized.positionSide(),
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
            validation = reserveOpeningFunds(normalized, orderId, validation, feeSnapshot, now);
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

    private ValidationResult reserveOpeningFunds(PlaceOrderRequest request,
                                                 long orderId,
                                                 ValidationResult validation,
                                                 OrderFeeSnapshot feeSnapshot,
                                                 Instant now) {
        if (validation.instrumentType() == InstrumentType.SPOT) {
            return reserveSpotAssets(request, orderId, validation.instrumentVersion(), feeSnapshot, now);
        }
        return reserveInitialMargin(request, orderId, validation.instrumentVersion(), now);
    }

    private ValidationResult reserveSpotAssets(PlaceOrderRequest request,
                                               long orderId,
                                               long instrumentVersion,
                                               OrderFeeSnapshot feeSnapshot,
                                               Instant now) {
        var requirement = spotOrderReservationRepository.requirement(
                request.symbol(), instrumentVersion, request.side(), request.orderType(), request.priceTicks(),
                request.quantitySteps(), properties.getRisk().getMarketMaxSlippagePpm(),
                properties.getRisk().getMarketMaxMarkAgeMs(), feeSnapshot);
        if (requirement.isEmpty()) {
            return ValidationResult.reject("spot reservation requirement unavailable", instrumentVersion,
                    InstrumentType.SPOT);
        }
        if (!requirement.get().accepted()) {
            return ValidationResult.reject(requirement.get().rejectReason(), instrumentVersion, InstrumentType.SPOT);
        }
        boolean reserved = spotOrderReservationRepository.reserve(request.userId(), requirement.get().asset(),
                orderId, request.symbol(), request.side(), requirement.get().reservedUnits(), now);
        return reserved ? ValidationResult.ok(instrumentVersion, InstrumentType.SPOT)
                : ValidationResult.reject("insufficient spot available balance", instrumentVersion,
                InstrumentType.SPOT);
    }

    private ValidationResult reserveInitialMargin(PlaceOrderRequest request,
                                                  long orderId,
                                                  long instrumentVersion,
                                                  Instant now) {
        var requirement = orderMarginRepository.requirement(
                request.symbol(), instrumentVersion, request.userId(), request.marginMode(), request.positionSide(), request.side(),
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
        boolean reserved = orderMarginRepository.reserve(request.userId(), requirement.get().accountType(),
                requirement.get().asset(), orderId, request.symbol(), request.marginMode(),
                request.positionSide(), requirement.get().initialMarginUnits(), now);
        return reserved ? ValidationResult.ok(instrumentVersion)
                : ValidationResult.reject("insufficient available margin", instrumentVersion);
    }

    private ValidationResult validateMarginMode(PlaceOrderRequest request) {
        MarginMode marginMode = MarginMode.defaultIfNull(request.marginMode());
        if (!request.reduceOnly()
                && orderRepository.hasActiveMarginModeConflict(request.userId(), request.symbol(), marginMode)) {
            return ValidationResult.reject("margin mode switch requires closing positions and open orders first");
        }
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

        return requestCancel(order, null).order();
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

    public OrderQueryResponse adminOrders(Long userId, String symbol, String status, Long orderId, int limit) {
        return adminOrders(userId, symbol, status, orderId, limit, null, null);
    }

    public OrderQueryResponse adminOrders(Long userId,
                                          String symbol,
                                          String status,
                                          Long orderId,
                                          int limit,
                                          String cursor,
                                          String sort) {
        if (userId != null && userId <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
        if (orderId != null && orderId <= 0) {
            throw new IllegalArgumentException("orderId must be positive");
        }
        if (limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("limit must be in [1, 1000]");
        }
        String normalizedSymbol = symbol == null || symbol.isBlank() ? null : normalizeSymbol(symbol);
        OrderStatus normalizedStatus = status == null || status.isBlank()
                ? null
                : OrderStatus.valueOf(status.trim().toUpperCase());
        var page = orderRepository.adminOrderPage(userId, normalizedSymbol, normalizedStatus, orderId, limit, cursor, sort);
        List<OrderResponse> rows = page.items()
                .stream()
                .map(this::toResponse)
                .toList();
        return new OrderQueryResponse(rows.size(), rows, page.nextCursor(), page.hasMore(), page.sort(), page.limit());
    }

    public AdminOrderEventQueryResponse adminOrderEvents(long orderId, int limit) {
        requireOrderId(orderId);
        requireTimelineLimit(limit);
        var events = orderRepository.orderEvents(orderId, limit);
        return new AdminOrderEventQueryResponse(events.size(), events);
    }

    public AdminMatchResultQueryResponse adminMatchResults(long orderId, int limit) {
        requireOrderId(orderId);
        requireTimelineLimit(limit);
        var results = orderRepository.matchResults(orderId, limit);
        return new AdminMatchResultQueryResponse(results.size(), results);
    }

    public AdminMatchTradeQueryResponse adminMatchTrades(Long userId, Long orderId, String symbol, int limit) {
        return adminMatchTrades(userId, orderId, symbol, limit, null, null);
    }

    public AdminMatchTradeQueryResponse adminMatchTrades(Long userId,
                                                         Long orderId,
                                                         String symbol,
                                                         int limit,
                                                         String cursor,
                                                         String sort) {
        if (userId != null && userId <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
        if (orderId != null && orderId <= 0) {
            throw new IllegalArgumentException("orderId must be positive");
        }
        if (limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("limit must be in [1, 1000]");
        }
        String normalizedSymbol = symbol == null || symbol.isBlank() ? null : normalizeSymbol(symbol);
        var page = orderRepository.matchTradePage(userId, orderId, normalizedSymbol, limit, cursor, sort);
        return new AdminMatchTradeQueryResponse(page.items().size(), page.items(),
                page.nextCursor(), page.hasMore(), page.sort(), page.limit());
    }

    public AdminOrderTimelineResponse adminOrderTimeline(long orderId) {
        requireOrderId(orderId);
        OrderResponse order = get(orderId);
        return new AdminOrderTimelineResponse(
                order,
                orderRepository.orderEvents(orderId, 1000),
                orderRepository.matchResults(orderId, 1000),
                orderRepository.matchTrades(null, orderId, null, 1000));
    }

    @Transactional
    public AdminCancelOrderResult adminCancelOrder(long orderId, String reason) {
        requireOrderId(orderId);
        OrderRecord order = orderRepository.findByOrderId(orderId)
                .orElseThrow(() -> new IllegalStateException("order not found: " + orderId));
        return requestCancel(order, adminCancelReason(reason));
    }

    @Transactional
    public AdminCancelOrdersResponse adminCancelOrders(AdminBatchCancelOrdersRequest request) {
        Long userId = request == null ? null : request.userId();
        if (userId != null && userId <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
        String symbol = request == null || request.symbol() == null || request.symbol().isBlank()
                ? null
                : normalizeSymbol(request.symbol());
        int limit = request == null || request.limit() == null ? 100 : request.limit();
        if (limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("limit must be in [1, 1000]");
        }
        String reason = adminCancelReason(request == null ? null : request.reason());
        List<AdminCancelOrderResult> results = orderRepository.adminCancelableOrders(userId, symbol, limit)
                .stream()
                .map(order -> requestCancel(order, reason))
                .toList();
        int canceled = (int) results.stream().filter(AdminCancelOrderResult::cancelRequested).count();
        return new AdminCancelOrdersResponse(results.size(), canceled, results.size() - canceled, results);
    }

    public AdminCancelOrdersPreviewResponse adminCancelPreview(Long userId, String symbol, int limit) {
        if (userId != null && userId <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
        if (limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("limit must be in [1, 1000]");
        }
        String normalizedSymbol = symbol == null || symbol.isBlank() ? null : normalizeSymbol(symbol);
        var impact = orderRepository.adminCancelableImpact(userId, normalizedSymbol);
        var sample = orderRepository.adminCancelableOrders(userId, normalizedSymbol, limit)
                .stream()
                .map(this::toResponse)
                .toList();
        return new AdminCancelOrdersPreviewResponse(
                userId,
                normalizedSymbol,
                impact.matched(),
                sample.size(),
                impact.totalRemainingQuantitySteps(),
                impact.buyOrders(),
                impact.sellOrders(),
                sample);
    }

    @Transactional
    public AdminCancelOrdersResponse adminCancelBySymbol(AdminCancelBySymbolRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request is required");
        }
        String symbol = normalizeSymbol(request.symbol());
        return adminCancelOrders(new AdminBatchCancelOrdersRequest(null, symbol, request.limit(), request.reason()));
    }

    private AdminCancelOrderResult requestCancel(OrderRecord order, String reason) {
        if (TERMINAL_STATUSES.contains(order.status()) || order.status() == OrderStatus.CANCEL_REQUESTED) {
            return cancelResult(order, false, "order is already " + order.status().name());
        }
        Instant now = Instant.now();
        String traceId = TraceContext.currentOrCreate();
        boolean cancelRequested = orderRepository.requestCancel(order.orderId(), now);
        OrderRecord updated = orderRepository.findByOrderId(order.orderId())
                .orElseThrow(() -> new IllegalStateException("order disappeared after cancel update"));
        if (!cancelRequested) {
            return cancelResult(updated, false, "order was not cancelable");
        }
        enqueueOrderEvent(updated, OrderEventType.CANCEL_REQUESTED, reason, now, traceId);
        enqueueCommand(updated, OrderCommandType.CANCEL, now, traceId);
        return cancelResult(updated, true, "cancel requested");
    }

    private AdminCancelOrderResult cancelResult(OrderRecord order, boolean cancelRequested, String message) {
        return new AdminCancelOrderResult(
                order.orderId(),
                order.userId(),
                order.symbol(),
                order.status(),
                cancelRequested,
                message,
                toResponse(order));
    }

    private String adminCancelReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "admin cancel";
        }
        String normalized = reason.trim();
        if (normalized.length() > 500) {
            normalized = normalized.substring(0, 500);
        }
        return "admin cancel: " + normalized;
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
                order.positionSide(),
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
                order.positionSide(),
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
        if (request == null) {
            throw new IllegalArgumentException("order request is required");
        }
        PositionSide positionSide = PositionSide.defaultIfNull(request.positionSide());
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
                positionSide,
                request.reduceOnly(),
                request.postOnly());
    }

    private PlaceOrderRequest normalizePositionMode(PlaceOrderRequest request, PositionMode positionMode) {
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
        boolean reduceOnly = request.reduceOnly() || positionSide.isClosingSide(request.side());
        return new PlaceOrderRequest(
                request.userId(),
                request.clientOrderId(),
                request.symbol(),
                request.side(),
                request.orderType(),
                request.timeInForce(),
                request.priceTicks(),
                request.quantitySteps(),
                request.marginMode(),
                positionSide,
                reduceOnly,
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

    private void requireOrderId(long orderId) {
        if (orderId <= 0) {
            throw new IllegalArgumentException("orderId must be positive");
        }
    }

    private void requireTimelineLimit(int limit) {
        if (limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("limit must be in [1, 1000]");
        }
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
                order.positionSide(),
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
