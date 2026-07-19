package com.surprising.trading.order.service;

import com.surprising.account.api.model.PositionUpdatedEvent;
import com.surprising.account.api.model.AccountCommandResultEvent;
import com.surprising.account.api.model.AccountCommandStatus;
import com.surprising.account.api.model.AccountType;
import com.surprising.account.api.model.AccountUserCommand;
import com.surprising.account.api.model.AccountUserCommandType;
import com.surprising.account.api.model.OrderReservationKind;
import com.surprising.account.api.model.OrderReserveAccountCommand;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.TraceContext;
import com.surprising.trading.api.model.AmendOrderBatchItemResponse;
import com.surprising.trading.api.model.AmendOrderBatchResponse;
import com.surprising.trading.api.model.AmendOrderRequest;
import com.surprising.trading.api.model.AmendOrderResponse;
import com.surprising.trading.api.model.AdminBatchCancelOrdersRequest;
import com.surprising.trading.api.model.AdminCancelBySymbolRequest;
import com.surprising.trading.api.model.AdminCancelOrderResult;
import com.surprising.trading.api.model.AdminCancelOrdersResponse;
import com.surprising.trading.api.model.AdminCancelOrdersPreviewResponse;
import com.surprising.trading.api.model.AdminMatchResultQueryResponse;
import com.surprising.trading.api.model.AdminMatchTradeQueryResponse;
import com.surprising.trading.api.model.AdminOrderEventQueryResponse;
import com.surprising.trading.api.model.AdminOrderTimelineResponse;
import com.surprising.trading.api.model.BatchCancelOrdersRequest;
import com.surprising.trading.api.model.BatchAmendOrdersRequest;
import com.surprising.trading.api.model.BatchPlaceOrderRequest;
import com.surprising.trading.api.model.CancelOrderRequest;
import com.surprising.trading.api.model.CancelOpenOrdersRequest;
import com.surprising.trading.api.model.ClosePositionRequest;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.instrument.api.model.InstrumentType;
import com.surprising.trading.api.model.OrderBatchItemResponse;
import com.surprising.trading.api.model.OrderBatchResponse;
import com.surprising.trading.api.model.OrderCommandEvent;
import com.surprising.trading.api.model.OrderCommandType;
import com.surprising.trading.api.model.OrderEvent;
import com.surprising.trading.api.model.OrderEventType;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.OrderQueryResponse;
import com.surprising.trading.api.model.OrderResponse;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.OrderStatus;
import com.surprising.trading.api.model.OrderType;
import com.surprising.trading.api.model.PlaceOrderRequest;
import com.surprising.trading.api.model.PositionMode;
import com.surprising.trading.api.model.PositionSide;
import com.surprising.trading.api.model.TestOrderResponse;
import com.surprising.trading.api.model.TimeInForce;
import com.surprising.trading.order.config.TradingOrderProperties;
import com.surprising.trading.order.model.MarginRequirement;
import com.surprising.trading.order.model.OrderFeeSnapshot;
import com.surprising.trading.order.model.OrderRecord;
import com.surprising.trading.order.model.ReduceOnlyPosition;
import com.surprising.trading.order.model.SpotReservationRequirement;
import com.surprising.trading.order.model.ValidationResult;
import com.surprising.trading.order.repository.OrderFeeRepository;
import com.surprising.trading.order.repository.OrderMarginRepository;
import com.surprising.trading.order.repository.OrderRepository;
import com.surprising.trading.order.repository.OutboxRepository;
import com.surprising.trading.order.repository.SpotOrderReservationRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class OrderService {

    private static final String REDUCE_ONLY_PRUNE_REASON = "REDUCE_ONLY_POSITION_REDUCED";
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
    private final RedisOpenOrderView openOrderView;

    public OrderService(ObjectMapper objectMapper,
                        TradingOrderProperties properties,
                        OrderValidator orderValidator,
                        ReduceOnlyValidator reduceOnlyValidator,
                        OrderRepository orderRepository,
                        OrderFeeRepository orderFeeRepository,
                        OrderMarginRepository orderMarginRepository,
                        SpotOrderReservationRepository spotOrderReservationRepository,
                        OutboxRepository outboxRepository) {
        this(objectMapper, properties, orderValidator, reduceOnlyValidator, orderRepository, orderFeeRepository,
                orderMarginRepository, spotOrderReservationRepository, outboxRepository, null);
    }

    @Autowired
    public OrderService(ObjectMapper objectMapper,
                        TradingOrderProperties properties,
                        OrderValidator orderValidator,
                        ReduceOnlyValidator reduceOnlyValidator,
                        OrderRepository orderRepository,
                        OrderFeeRepository orderFeeRepository,
                        OrderMarginRepository orderMarginRepository,
                        SpotOrderReservationRepository spotOrderReservationRepository,
                        OutboxRepository outboxRepository,
                        RedisOpenOrderView openOrderView) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.orderValidator = orderValidator;
        this.reduceOnlyValidator = reduceOnlyValidator;
        this.orderRepository = orderRepository;
        this.orderFeeRepository = orderFeeRepository;
        this.orderMarginRepository = orderMarginRepository;
        this.spotOrderReservationRepository = spotOrderReservationRepository;
        this.outboxRepository = outboxRepository;
        this.openOrderView = openOrderView;
    }

    @Transactional
    public OrderResponse place(PlaceOrderRequest request) {
        PlaceOrderRequest normalized = normalize(request);
        String traceId = TraceContext.currentOrCreate();
        ProductLine productLine = currentProductLine();
        // clientOrderId is the public idempotency key. Replays return the first persisted result.
        if (hasClientOrderId(normalized)) {
            var existing = orderRepository.findByClientOrderId(productLine, normalized.userId(), normalized.clientOrderId());
            if (existing.isPresent()) {
                OrderRecord existingOrder = existing.get();
                requireOrderCurrentProductLine(existingOrder);
                return toResponse(existingOrder);
            }
        }

        orderRepository.lockUserPositionMode(productLine, normalized.userId());
        PositionMode positionMode = orderRepository.positionMode(productLine, normalized.userId());
        normalized = normalizePositionMode(normalized, positionMode);
        orderRepository.lockUserSymbolMarginScope(productLine, normalized.userId(), normalized.symbol());
        Instant now = Instant.now();
        ValidationResult validation = validateMarginMode(productLine, normalized);
        if (validation.accepted()) {
            validation = orderValidator.validate(normalized);
        }
        if (validation.accepted() && normalized.reduceOnly()) {
            ValidationResult reduceOnlyValidation = reduceOnlyValidator.validate(normalized);
            if (!reduceOnlyValidation.accepted()) {
                validation = ValidationResult.reject(reduceOnlyValidation.rejectReason(), validation.instrumentVersion());
            } else {
                validation = ValidationResult.ok(reduceOnlyValidation.instrumentVersion(),
                        validation.instrumentType(), validation.contractType());
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
        ReservationPlan reservationPlan = ReservationPlan.none();
        if (validation.accepted() && (!normalized.reduceOnly() || requiresReduceOnlyFunds(normalized, validation))) {
            reservationPlan = planOpeningFunds(normalized, orderId, validation, feeSnapshot);
            if (!reservationPlan.accepted()) {
                validation = ValidationResult.reject(reservationPlan.rejectReason(),
                        validation.instrumentVersion(), validation.instrumentType(), validation.contractType());
            }
        }
        OrderStatus status = !validation.accepted()
                ? OrderStatus.REJECTED
                : reservationPlan.command() == null ? OrderStatus.ACCEPTED : OrderStatus.PENDING_RESERVE;
        OrderRecord order = new OrderRecord(
                orderId,
                productLine,
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
                now,
                1L);

        boolean inserted = orderRepository.insert(order);
        if (!inserted && hasClientOrderId(normalized)) {
            return orderRepository.findByClientOrderId(productLine, normalized.userId(), normalized.clientOrderId())
                    .map(existing -> {
                        requireOrderCurrentProductLine(existing);
                        return toResponse(existing);
                    })
                    .orElseThrow(() -> new IllegalStateException("duplicate clientOrderId but order not found"));
        }
        if (!inserted) {
            throw new IllegalStateException("failed to insert order " + orderId);
        }

        OrderEventType eventType = !validation.accepted()
                ? OrderEventType.REJECTED
                : status == OrderStatus.PENDING_RESERVE ? OrderEventType.RESERVE_PENDING : OrderEventType.ACCEPTED;
        enqueueOrderEvent(order, eventType, validation.rejectReason(), now, traceId);
        if (status == OrderStatus.PENDING_RESERVE) {
            enqueueAccountReservation(order, reservationPlan.command(), now, traceId);
        } else if (validation.accepted()) {
            enqueueCommand(order, OrderCommandType.PLACE, now, traceId);
        }
        return toResponse(order);
    }

    @Transactional
    public OrderBatchResponse placeBatch(BatchPlaceOrderRequest request) {
        List<PlaceOrderRequest> orders = request == null ? List.of() : request.orders();
        requireBatchSize(orders.size(), 20, "orders");
        List<OrderBatchItemResponse> results = new ArrayList<>();
        for (int i = 0; i < orders.size(); i++) {
            try {
                OrderResponse order = place(orders.get(i));
                results.add(new OrderBatchItemResponse(i, true, "completed", order));
            } catch (IllegalArgumentException | IllegalStateException ex) {
                results.add(new OrderBatchItemResponse(i, false, ex.getMessage(), null));
            }
        }
        return orderBatchResponse(results);
    }

    @Transactional
    public TestOrderResponse test(PlaceOrderRequest request) {
        PlaceOrderRequest normalized = normalize(request);
        ProductLine productLine = currentProductLine();
        orderRepository.lockUserPositionMode(productLine, normalized.userId());
        PositionMode positionMode = orderRepository.positionMode(productLine, normalized.userId());
        normalized = normalizePositionMode(normalized, positionMode);
        orderRepository.lockUserSymbolMarginScope(productLine, normalized.userId(), normalized.symbol());
        ValidationResult validation = validateMarginMode(productLine, normalized);
        if (!validation.accepted()) {
            return testRejected(validation, "MARGIN_MODE");
        }
        validation = orderValidator.validate(normalized);
        if (!validation.accepted()) {
            return testRejected(validation, "ORDER_RULES");
        }
        if (normalized.reduceOnly()) {
            ValidationResult reduceOnlyValidation = reduceOnlyValidator.validate(normalized);
            if (!reduceOnlyValidation.accepted()) {
                return testRejected(reduceOnlyValidation, "REDUCE_ONLY");
            }
            validation = ValidationResult.ok(reduceOnlyValidation.instrumentVersion(),
                    validation.instrumentType(), validation.contractType());
        }
        var resolvedFeeSnapshot = orderFeeRepository.snapshot(normalized.userId(), normalized.symbol(),
                validation.instrumentVersion(), Instant.now());
        if (resolvedFeeSnapshot.isEmpty()) {
            return new TestOrderResponse(false, "fee schedule unavailable", validation.instrumentVersion(),
                    "FEE", null, null, 0L);
        }
        if (normalized.reduceOnly() && !requiresReduceOnlyFunds(normalized, validation)) {
            return new TestOrderResponse(true, null, validation.instrumentVersion(), "ACCEPTED",
                    null, null, 0L);
        }
        return dryRunOpeningFunds(normalized, validation, resolvedFeeSnapshot.get());
    }

    @Transactional
    public AmendOrderResponse amend(AmendOrderRequest request) {
        AmendOrderRequest normalized = normalizeAmend(request);
        ProductLine productLine = currentProductLine();
        var existingReplacement = orderRepository.findByClientOrderId(
                productLine, normalized.userId(), normalized.newClientOrderId());
        OrderRecord original = orderRepository.findByOrderId(normalized.orderId())
                .orElseThrow(() -> new IllegalStateException("order not found: " + normalized.orderId()));
        requireOrderCurrentProductLine(original);
        if (original.userId() != normalized.userId()) {
            throw new IllegalArgumentException("order does not belong to user");
        }
        if (existingReplacement.isPresent()) {
            requireOrderCurrentProductLine(existingReplacement.get());
            return new AmendOrderResponse(toResponse(original), toResponse(existingReplacement.get()),
                    false, "replacement order already exists");
        }
        if (original.orderType() != OrderType.LIMIT) {
            throw new IllegalArgumentException("only LIMIT orders can be amended");
        }
        if (original.status() != OrderStatus.ACCEPTED && original.status() != OrderStatus.PARTIALLY_FILLED) {
            throw new IllegalStateException("order is not amendable: " + original.status().name());
        }
        if (original.remainingQuantitySteps() <= 0) {
            throw new IllegalStateException("order has no open quantity to amend");
        }

        orderRepository.lockUserPositionMode(productLine, original.userId());
        orderRepository.lockUserSymbolMarginScope(productLine, original.userId(), original.symbol());
        long replacementPriceTicks = normalized.priceTicks() == null ? original.priceTicks() : normalized.priceTicks();
        long replacementQuantitySteps = normalized.quantitySteps() == null
                ? original.remainingQuantitySteps()
                : normalized.quantitySteps();
        TimeInForce replacementTif = normalized.timeInForce() == null
                ? original.timeInForce()
                : normalized.timeInForce();
        boolean replacementPostOnly = normalized.postOnly() == null ? original.postOnly() : normalized.postOnly();
        PlaceOrderRequest replacement = new PlaceOrderRequest(
                original.userId(),
                normalized.newClientOrderId(),
                original.symbol(),
                original.side(),
                original.orderType(),
                replacementTif,
                replacementPriceTicks,
                replacementQuantitySteps,
                original.marginMode(),
                original.positionSide(),
                original.reduceOnly(),
                replacementPostOnly);

        AdminCancelOrderResult cancelResult = requestCancel(original, "order amend replace");
        if (!cancelResult.cancelRequested()) {
            throw new IllegalStateException(cancelResult.message());
        }
        OrderResponse replacementOrder = place(replacement);
        String message = replacementOrder.status() == OrderStatus.REJECTED
                ? "cancel requested; replacement rejected: " + replacementOrder.rejectReason()
                : "cancel requested; replacement submitted";
        return new AmendOrderResponse(cancelResult.order(), replacementOrder, true, message);
    }

    @Transactional
    public AmendOrderBatchResponse amendBatch(BatchAmendOrdersRequest request) {
        List<AmendOrderRequest> orders = request == null ? List.of() : request.orders();
        requireBatchSize(orders.size(), 20, "orders");
        List<AmendOrderBatchItemResponse> results = new ArrayList<>();
        for (int i = 0; i < orders.size(); i++) {
            try {
                AmendOrderResponse amend = amend(orders.get(i));
                results.add(new AmendOrderBatchItemResponse(i, true, amend.message(), amend));
            } catch (IllegalArgumentException | IllegalStateException ex) {
                results.add(new AmendOrderBatchItemResponse(i, false, ex.getMessage(), null));
            }
        }
        return amendBatchResponse(results);
    }

    @Transactional
    public OrderResponse closePosition(ClosePositionRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("close position request is required");
        }
        if (request.userId() <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
        String symbol = normalizeSymbol(request.symbol());
        MarginMode marginMode = MarginMode.defaultIfNull(request.marginMode());
        PositionSide positionSide = PositionSide.defaultIfNull(request.positionSide());
        ProductLine productLine = currentProductLine();
        orderRepository.lockUserPositionMode(productLine, request.userId());
        PositionMode positionMode = orderRepository.positionMode(productLine, request.userId());
        if (PositionMode.defaultIfNull(positionMode) == PositionMode.HEDGE && !positionSide.isHedgeSide()) {
            throw new IllegalArgumentException("positionSide LONG or SHORT is required in HEDGE position mode");
        }
        orderRepository.lockUserSymbolMarginScope(productLine, request.userId(), symbol);
        ReduceOnlyPosition position = orderRepository.lockedPosition(productLine, request.userId(), symbol, marginMode,
                        positionSide)
                .orElseThrow(() -> new IllegalStateException("open position not found"));
        if (position.signedQuantitySteps() == 0L) {
            throw new IllegalStateException("open position not found");
        }
        OrderSide closeSide = position.signedQuantitySteps() > 0L ? OrderSide.SELL : OrderSide.BUY;
        PlaceOrderRequest closeOrder = new PlaceOrderRequest(
                request.userId(),
                emptyToNull(request.clientOrderId()),
                symbol,
                closeSide,
                OrderType.MARKET,
                TimeInForce.IOC,
                0L,
                Math.absExact(position.signedQuantitySteps()),
                marginMode,
                positionSide,
                true,
                false);
        return place(closeOrder);
    }

    private ReservationPlan planOpeningFunds(PlaceOrderRequest request,
                                             long orderId,
                                             ValidationResult validation,
                                             OrderFeeSnapshot feeSnapshot) {
        if (validation.instrumentType() == InstrumentType.SPOT) {
            return planSpotReservation(request, orderId, validation.instrumentVersion(), feeSnapshot);
        }
        return planDerivativeReservation(request, orderId, validation.instrumentVersion());
    }

    private ReservationPlan planSpotReservation(PlaceOrderRequest request,
                                                long orderId,
                                                long instrumentVersion,
                                                OrderFeeSnapshot feeSnapshot) {
        var requirement = spotOrderReservationRepository.requirement(
                request.symbol(), instrumentVersion, request.side(), request.orderType(), request.priceTicks(),
                request.quantitySteps(), properties.getRisk().getMarketMaxSlippagePpm(),
                properties.getRisk().getMarketMaxMarkAgeMs(), feeSnapshot);
        if (requirement.isEmpty()) {
            return ReservationPlan.reject("spot reservation requirement unavailable");
        }
        if (!requirement.get().accepted()) {
            return ReservationPlan.reject(requirement.get().rejectReason());
        }
        return ReservationPlan.accept(new OrderReserveAccountCommand(
                orderId, request.symbol(), request.side(), OrderReservationKind.SPOT_ASSET, AccountType.SPOT,
                requirement.get().asset(), request.marginMode(), request.positionSide(),
                request.quantitySteps(), request.reduceOnly(),
                requirement.get().reservedUnits()));
    }

    private ReservationPlan planDerivativeReservation(PlaceOrderRequest request,
                                                      long orderId,
                                                      long instrumentVersion) {
        var requirement = orderMarginRepository.requirement(
                request.symbol(), instrumentVersion, request.userId(), request.marginMode(), request.positionSide(), request.side(),
                request.orderType(), request.priceTicks(), request.quantitySteps(),
                properties.getRisk().getMarketMaxSlippagePpm(),
                properties.getRisk().getMarketMaxMarkAgeMs());
        if (requirement.isEmpty()) {
            return ReservationPlan.reject("margin requirement unavailable");
        }
        if (!requirement.get().accepted()) {
            return ReservationPlan.reject(requirement.get().rejectReason());
        }
        if (requirement.get().initialMarginUnits() <= 0) {
            return ReservationPlan.reject("invalid margin requirement");
        }
        AccountType accountType;
        try {
            accountType = AccountType.valueOf(requirement.get().accountType());
        } catch (IllegalArgumentException ex) {
            return ReservationPlan.reject("unsupported margin account type " + requirement.get().accountType());
        }
        return ReservationPlan.accept(new OrderReserveAccountCommand(
                orderId, request.symbol(), request.side(), OrderReservationKind.DERIVATIVE_MARGIN, accountType,
                requirement.get().asset(), request.marginMode(), request.positionSide(),
                request.quantitySteps(), request.reduceOnly(),
                requirement.get().initialMarginUnits()));
    }

    private TestOrderResponse dryRunOpeningFunds(PlaceOrderRequest request,
                                                 ValidationResult validation,
                                                 OrderFeeSnapshot feeSnapshot) {
        if (validation.instrumentType() == InstrumentType.SPOT) {
            var requirement = spotOrderReservationRepository.requirement(
                    request.symbol(), validation.instrumentVersion(), request.side(), request.orderType(),
                    request.priceTicks(), request.quantitySteps(), properties.getRisk().getMarketMaxSlippagePpm(),
                    properties.getRisk().getMarketMaxMarkAgeMs(), feeSnapshot);
            if (requirement.isEmpty()) {
                return new TestOrderResponse(false, "spot reservation requirement unavailable",
                        validation.instrumentVersion(), "RESERVE_REQUIREMENT", "SPOT", null, 0L);
            }
            SpotReservationRequirement value = requirement.get();
            if (!value.accepted()) {
                return new TestOrderResponse(false, value.rejectReason(), validation.instrumentVersion(),
                        "RESERVE_REQUIREMENT", "SPOT", value.asset(), value.reservedUnits());
            }
            return new TestOrderResponse(true, null, validation.instrumentVersion(), "ACCEPTED",
                    "SPOT", value.asset(), value.reservedUnits());
        }
        var requirement = orderMarginRepository.requirement(
                request.symbol(), validation.instrumentVersion(), request.userId(), request.marginMode(),
                request.positionSide(), request.side(), request.orderType(), request.priceTicks(),
                request.quantitySteps(), properties.getRisk().getMarketMaxSlippagePpm(),
                properties.getRisk().getMarketMaxMarkAgeMs());
        if (requirement.isEmpty()) {
            return new TestOrderResponse(false, "margin requirement unavailable", validation.instrumentVersion(),
                    "RESERVE_REQUIREMENT", null, null, 0L);
        }
        MarginRequirement value = requirement.get();
        if (!value.accepted()) {
            return new TestOrderResponse(false, value.rejectReason(), validation.instrumentVersion(),
                    "RESERVE_REQUIREMENT", value.accountType(), value.asset(), value.initialMarginUnits());
        }
        if (value.initialMarginUnits() <= 0) {
            return new TestOrderResponse(false, "invalid margin requirement", validation.instrumentVersion(),
                    "RESERVE_REQUIREMENT", value.accountType(), value.asset(), value.initialMarginUnits());
        }
        return new TestOrderResponse(true, null, validation.instrumentVersion(), "ACCEPTED",
                value.accountType(), value.asset(), value.initialMarginUnits());
    }

    private ValidationResult validateMarginMode(ProductLine productLine, PlaceOrderRequest request) {
        MarginMode marginMode = MarginMode.defaultIfNull(request.marginMode());
        if (!request.reduceOnly()
                && orderRepository.hasActiveMarginModeConflict(productLine, request.userId(), request.symbol(),
                        marginMode)) {
            return ValidationResult.reject("margin mode switch requires closing positions and open orders first");
        }
        return ValidationResult.ok(0L);
    }

    private boolean requiresReduceOnlyFunds(PlaceOrderRequest request, ValidationResult validation) {
        return request.reduceOnly()
                && validation.contractType() == ContractType.VANILLA_OPTION
                && request.side() == OrderSide.BUY;
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
        requireOrderCurrentProductLine(order);
        if (order.userId() != request.userId()) {
            throw new IllegalArgumentException("order does not belong to user");
        }
        if (TERMINAL_STATUSES.contains(order.status()) || order.status() == OrderStatus.CANCEL_REQUESTED) {
            return toResponse(order);
        }

        return requestCancel(order, null).order();
    }

    @Transactional
    public OrderBatchResponse cancelBatch(BatchCancelOrdersRequest request) {
        List<CancelOrderRequest> orders = request == null ? List.of() : request.orders();
        requireBatchSize(orders.size(), 50, "orders");
        List<OrderBatchItemResponse> results = new ArrayList<>();
        for (int i = 0; i < orders.size(); i++) {
            try {
                OrderResponse order = cancel(orders.get(i));
                results.add(new OrderBatchItemResponse(i, true, "completed", order));
            } catch (IllegalArgumentException | IllegalStateException ex) {
                results.add(new OrderBatchItemResponse(i, false, ex.getMessage(), null));
            }
        }
        return orderBatchResponse(results);
    }

    @Transactional
    public OrderBatchResponse cancelOpenOrders(CancelOpenOrdersRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("cancel open orders request is required");
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
        List<OrderRecord> orders = contractType == null
                ? orderRepository.adminCancelableOrders(request.userId(), symbol, limit)
                : orderRepository.adminCancelableOrders(request.userId(), symbol, contractType, limit);
        List<OrderBatchItemResponse> results = new ArrayList<>();
        for (int i = 0; i < orders.size(); i++) {
            try {
                AdminCancelOrderResult result = requestCancel(orders.get(i), "user cancel open orders");
                results.add(new OrderBatchItemResponse(i, true, result.message(), result.order()));
            } catch (IllegalArgumentException | IllegalStateException ex) {
                results.add(new OrderBatchItemResponse(i, false, ex.getMessage(), null));
            }
        }
        return orderBatchResponse(results);
    }

    public OrderResponse get(long orderId) {
        return orderRepository.findByOrderId(orderId)
                .map(order -> {
                    requireOrderCurrentProductLine(order);
                    return toResponse(order);
                })
                .orElseThrow(() -> new IllegalStateException("order not found: " + orderId));
    }

    public OrderResponse getByClientOrderId(long userId, String clientOrderId) {
        if (userId <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
        return orderRepository.findByClientOrderId(currentProductLine(), userId, normalizeClientOrderId(clientOrderId))
                .map(order -> {
                    requireOrderCurrentProductLine(order);
                    return toResponse(order);
                })
                .orElseThrow(() -> new IllegalStateException("order not found for clientOrderId: " + clientOrderId));
    }

    public OrderQueryResponse openOrders(long userId, String symbol, int limit) {
        return openOrders(userId, symbol, limit, null);
    }

    public OrderQueryResponse openOrders(long userId, String symbol, int limit, String cursor) {
        if (userId <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
        if (limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("limit must be in [1, 1000]");
        }
        String normalizedSymbol = symbol == null || symbol.isBlank() ? null : normalizeSymbol(symbol);
        ProductLine productLine = currentProductLine();
        long beforeOrderId = decodeOpenOrderCursor(cursor);
        RedisOpenOrderView.Page page = openOrderView == null
                ? databaseOpenOrderPage(productLine, userId, normalizedSymbol, beforeOrderId, limit)
                : openOrderView.orders(productLine, userId, normalizedSymbol, beforeOrderId, limit)
                        .orElseGet(() -> databaseOpenOrderPage(productLine, userId, normalizedSymbol,
                                beforeOrderId, limit));
        List<OrderResponse> rows = page.orders()
                .stream()
                .map(this::toResponse)
                .toList();
        String nextCursor = page.hasMore() ? encodeOpenOrderCursor(page.nextOrderId()) : null;
        return new OrderQueryResponse(rows.size(), rows, nextCursor, page.hasMore(), "orderId.desc", limit);
    }

    public OrderQueryResponse adminOrders(Long userId, String symbol, String status, Long orderId, int limit) {
        return adminOrders(userId, symbol, status, orderId, limit, null, null, null);
    }

    public OrderQueryResponse adminOrders(Long userId,
                                          String symbol,
                                          String status,
                                          Long orderId,
                                          int limit,
                                          String cursor,
                                          String sort) {
        return adminOrders(userId, symbol, status, orderId, limit, cursor, sort, null);
    }

    public OrderQueryResponse adminOrders(Long userId,
                                          String symbol,
                                          String status,
                                          Long orderId,
                                          int limit,
                                          String cursor,
                                          String sort,
                                          ProductLine productLine) {
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
        String contractType = contractType(productLine);
        var page = contractType == null
                ? orderRepository.adminOrderPage(userId, normalizedSymbol, normalizedStatus, orderId, limit, cursor, sort)
                : orderRepository.adminOrderPage(
                        userId, normalizedSymbol, normalizedStatus, orderId, limit, contractType, cursor, sort);
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
        return adminMatchTrades(userId, orderId, symbol, limit, null, null, null);
    }

    public AdminMatchTradeQueryResponse adminMatchTrades(Long userId,
                                                         Long orderId,
                                                         String symbol,
                                                         int limit,
                                                         String cursor,
                                                         String sort) {
        return adminMatchTrades(userId, orderId, symbol, limit, cursor, sort, null);
    }

    public AdminMatchTradeQueryResponse adminMatchTrades(Long userId,
                                                         Long orderId,
                                                         String symbol,
                                                         int limit,
                                                         String cursor,
                                                         String sort,
                                                         ProductLine productLine) {
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
        String contractType = contractType(productLine);
        var page = contractType == null
                ? orderRepository.matchTradePage(userId, orderId, normalizedSymbol, limit, cursor, sort)
                : orderRepository.matchTradePage(userId, orderId, normalizedSymbol, limit, contractType, cursor, sort);
        return new AdminMatchTradeQueryResponse(page.items().size(), page.items(),
                page.nextCursor(), page.hasMore(), page.sort(), page.limit());
    }

    public AdminOrderTimelineResponse adminOrderTimeline(long orderId) {
        return adminOrderTimeline(orderId, null);
    }

    public AdminOrderTimelineResponse adminOrderTimeline(long orderId, ProductLine productLine) {
        requireOrderId(orderId);
        OrderRecord order = orderRepository.findByOrderId(orderId)
                .orElseThrow(() -> new IllegalStateException("order not found: " + orderId));
        requireOrderProductLine(order, productLine);
        String contractType = contractType(productLine);
        return new AdminOrderTimelineResponse(
                toResponse(order),
                orderRepository.orderEvents(orderId, 1000),
                orderRepository.matchResults(orderId, 1000),
                contractType == null
                        ? orderRepository.matchTrades(null, orderId, null, 1000)
                        : orderRepository.matchTrades(null, orderId, null, contractType, 1000));
    }

    @Transactional
    public AdminCancelOrderResult adminCancelOrder(long orderId, String reason) {
        return adminCancelOrder(orderId, reason, null);
    }

    @Transactional
    public AdminCancelOrderResult adminCancelOrder(long orderId, String reason, ProductLine productLine) {
        requireOrderId(orderId);
        OrderRecord order = orderRepository.findByOrderId(orderId)
                .orElseThrow(() -> new IllegalStateException("order not found: " + orderId));
        requireOrderProductLine(order, productLine);
        return requestCancel(order, adminCancelReason(reason));
    }

    @Transactional
    public AdminCancelOrdersResponse adminCancelOrders(AdminBatchCancelOrdersRequest request) {
        return adminCancelOrders(request, null);
    }

    @Transactional
    public AdminCancelOrdersResponse adminCancelOrders(AdminBatchCancelOrdersRequest request, ProductLine productLine) {
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
        String contractType = contractType(productLine);
        List<OrderRecord> orders = contractType == null
                ? orderRepository.adminCancelableOrders(userId, symbol, limit)
                : orderRepository.adminCancelableOrders(userId, symbol, contractType, limit);
        List<AdminCancelOrderResult> results = orders
                .stream()
                .map(order -> requestCancel(order, reason))
                .toList();
        int canceled = (int) results.stream().filter(AdminCancelOrderResult::cancelRequested).count();
        return new AdminCancelOrdersResponse(results.size(), canceled, results.size() - canceled, results);
    }

    public AdminCancelOrdersPreviewResponse adminCancelPreview(Long userId, String symbol, int limit) {
        return adminCancelPreview(userId, symbol, limit, null);
    }

    public AdminCancelOrdersPreviewResponse adminCancelPreview(
            Long userId, String symbol, int limit, ProductLine productLine) {
        if (userId != null && userId <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
        if (limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("limit must be in [1, 1000]");
        }
        String normalizedSymbol = symbol == null || symbol.isBlank() ? null : normalizeSymbol(symbol);
        String contractType = contractType(productLine);
        var impact = contractType == null
                ? orderRepository.adminCancelableImpact(userId, normalizedSymbol)
                : orderRepository.adminCancelableImpact(userId, normalizedSymbol, contractType);
        var sample = (contractType == null
                ? orderRepository.adminCancelableOrders(userId, normalizedSymbol, limit)
                : orderRepository.adminCancelableOrders(userId, normalizedSymbol, contractType, limit))
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
        return adminCancelBySymbol(request, null);
    }

    @Transactional
    public AdminCancelOrdersResponse adminCancelBySymbol(AdminCancelBySymbolRequest request, ProductLine productLine) {
        if (request == null) {
            throw new IllegalArgumentException("request is required");
        }
        String symbol = normalizeSymbol(request.symbol());
        return adminCancelOrders(new AdminBatchCancelOrdersRequest(null, symbol, request.limit(), request.reason()),
                productLine);
    }

    private AdminCancelOrderResult requestCancel(OrderRecord order, String reason) {
        return requestCancel(order, reason, Instant.now(), TraceContext.currentOrCreate());
    }

    private AdminCancelOrderResult requestCancel(OrderRecord order,
                                                 String reason,
                                                 Instant now,
                                                 String traceId) {
        if (TERMINAL_STATUSES.contains(order.status()) || order.status() == OrderStatus.CANCEL_REQUESTED) {
            return cancelResult(order, false, "order is already " + order.status().name());
        }
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

    /**
     * Owns order-side cleanup after the account single writer publishes a durable position snapshot.
     * Replays are safe because requestCancel is conditional and CANCEL_REQUESTED orders are retained
     * in the capacity calculation until matching acknowledges their cancellation.
     */
    @Transactional
    public void onPositionUpdated(PositionUpdatedEvent event) {
        if (event == null || event.productLine() != currentProductLine()) {
            throw new IllegalArgumentException("position event product line does not match order provider");
        }
        String symbol = normalizeSymbol(event.symbol());
        orderRepository.lockUserSymbolMarginScope(event.productLine(), event.userId(), symbol);
        List<OrderRecord> orders = orderRepository.lockOpenReduceOnlyOrders(
                event.productLine(), event.userId(), symbol, event.positionSide(), event.eventTime());
        if (orders.isEmpty()) {
            return;
        }
        long capacity = Math.absExact(event.signedQuantitySteps());
        OrderSide closeSide = event.signedQuantitySteps() > 0L
                ? OrderSide.SELL
                : event.signedQuantitySteps() < 0L ? OrderSide.BUY : null;
        long consumedCapacity = 0L;
        for (OrderRecord order : orders) {
            boolean validCloseSide = closeSide != null
                    && order.side() == closeSide
                    && order.instrumentVersion() == event.instrumentVersion();
            boolean excessQuantity = false;
            if (validCloseSide) {
                consumedCapacity = Math.addExact(consumedCapacity, order.remainingQuantitySteps());
                excessQuantity = consumedCapacity > capacity;
            }
            if ((!validCloseSide || excessQuantity) && order.status() != OrderStatus.CANCEL_REQUESTED) {
                requestCancel(order, REDUCE_ONLY_PRUNE_REASON, event.eventTime(), event.traceId());
            }
        }
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

    private void requireOrderProductLine(OrderRecord order, ProductLine productLine) {
        requireOrderContractType(order, contractType(productLine));
    }

    private void requireOrderCurrentProductLine(OrderRecord order) {
        requireOrderContractType(order, currentProductContractType());
    }

    private void requireOrderContractType(OrderRecord order, String contractType) {
        if (contractType == null) {
            return;
        }
        if (!orderRepository.orderMatchesContractType(order.orderId(), contractType)) {
            throw new IllegalStateException("order not found: " + order.orderId());
        }
    }

    private String contractType(ProductLine productLine) {
        return productLine == null ? null : productLine.contractTypeCode();
    }

    private String currentProductContractType() {
        return properties.getKafka().isProductTopicsEnabled() ? contractType(currentProductLine()) : null;
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

    private TestOrderResponse testRejected(ValidationResult validation, String stage) {
        return new TestOrderResponse(false, validation.rejectReason(), validation.instrumentVersion(),
                stage, null, null, 0L);
    }

    private void requireBatchSize(int size, int max, String field) {
        if (size < 1 || size > max) {
            throw new IllegalArgumentException(field + " size must be in [1, " + max + "]");
        }
    }

    private OrderBatchResponse orderBatchResponse(List<OrderBatchItemResponse> results) {
        int completed = (int) results.stream().filter(OrderBatchItemResponse::success).count();
        return new OrderBatchResponse(results.size(), completed, results.size() - completed, results);
    }

    @Transactional
    public void processAccountCommandResults(List<AccountCommandResultEvent> results) {
        if (results == null || results.isEmpty()) {
            return;
        }
        LinkedHashMap<Long, AccountCommandResultEvent> byOrderId = new LinkedHashMap<>();
        for (AccountCommandResultEvent result : results) {
            if (result == null
                    || result.commandType() != AccountUserCommandType.ORDER_RESERVE
                    || !"ORDER".equals(result.source())) {
                continue;
            }
            long orderId;
            try {
                orderId = Long.parseLong(result.sourceReference());
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("invalid order account command source reference", ex);
            }
            AccountCommandResultEvent duplicate = byOrderId.putIfAbsent(orderId, result);
            if (duplicate != null
                    && (!duplicate.commandId().equals(result.commandId())
                    || duplicate.status() != result.status()
                    || duplicate.userId() != result.userId()
                    || duplicate.productLine() != result.productLine())) {
                throw new IllegalArgumentException("conflicting account command results for order " + orderId);
            }
        }
        if (byOrderId.isEmpty()) {
            return;
        }

        Map<Long, OrderRecord> currentOrders = orderRepository.findByOrderIds(byOrderId.keySet());
        List<OrderRepository.ReservationCompletion> completions = new ArrayList<>(byOrderId.size());
        Instant consumedAt = Instant.now();
        for (Map.Entry<Long, AccountCommandResultEvent> entry : byOrderId.entrySet()) {
            long orderId = entry.getKey();
            AccountCommandResultEvent result = entry.getValue();
            OrderRecord current = currentOrders.get(orderId);
            if (current == null) {
                throw new IllegalStateException("order not found for account command result " + orderId);
            }
            requireOrderCurrentProductLine(current);
            if (current.productLine() != result.productLine() || current.userId() != result.userId()) {
                throw new IllegalStateException("account command result does not match order identity " + orderId);
            }
            String expectedCommandId = reservationCommandId(current.productLine(), current.orderId());
            if (!expectedCommandId.equals(result.commandId())) {
                throw new IllegalStateException("account command result id does not match order " + orderId);
            }
            if (current.status() != OrderStatus.PENDING_RESERVE) {
                continue;
            }
            boolean accepted = result.status() == AccountCommandStatus.APPLIED;
            String rejectReason = accepted ? null
                    : result.errorMessage() == null || result.errorMessage().isBlank()
                    ? result.errorCode() : result.errorMessage();
            completions.add(new OrderRepository.ReservationCompletion(
                    orderId, accepted, rejectReason, consumedAt));
        }
        if (completions.isEmpty()) {
            return;
        }

        Map<Long, OrderRecord> updatedOrders = orderRepository.completeReservations(completions);
        if (updatedOrders.size() != completions.size()) {
            List<Long> missingOrderIds = completions.stream()
                    .map(OrderRepository.ReservationCompletion::orderId)
                    .filter(orderId -> !updatedOrders.containsKey(orderId))
                    .toList();
            Map<Long, OrderRecord> concurrent = orderRepository.findByOrderIds(missingOrderIds);
            for (long orderId : missingOrderIds) {
                OrderRecord order = concurrent.get(orderId);
                if (order == null || order.status() == OrderStatus.PENDING_RESERVE) {
                    throw new IllegalStateException("failed to complete order reservation " + orderId);
                }
            }
        }

        List<OrderRepository.ReservationCompletion> appliedCompletions = completions.stream()
                .filter(completion -> updatedOrders.containsKey(completion.orderId()))
                .toList();
        if (appliedCompletions.isEmpty()) {
            return;
        }
        List<Long> eventIds = orderRepository.nextSequenceBatch("event", appliedCompletions.size());
        int acceptedCount = (int) appliedCompletions.stream()
                .filter(OrderRepository.ReservationCompletion::accepted).count();
        List<Long> commandIds = orderRepository.nextSequenceBatch("command", acceptedCount);
        List<OrderEvent> orderEvents = new ArrayList<>(appliedCompletions.size());
        List<OutboxRepository.OrderOutboxWrite> outboxWrites =
                new ArrayList<>(appliedCompletions.size() + acceptedCount);
        int commandIndex = 0;
        for (int index = 0; index < appliedCompletions.size(); index++) {
            OrderRepository.ReservationCompletion completion = appliedCompletions.get(index);
            AccountCommandResultEvent result = byOrderId.get(completion.orderId());
            OrderRecord updated = updatedOrders.get(completion.orderId());
            if (updated == null) {
                throw new IllegalStateException("updated order not found " + completion.orderId());
            }
            OrderEventType eventType = completion.accepted()
                    ? OrderEventType.ACCEPTED : OrderEventType.REJECTED;
            OrderEvent event = orderEvent(updated, eventType, completion.rejectReason(), consumedAt,
                    result.traceId(), eventIds.get(index));
            orderEvents.add(event);
            outboxWrites.add(new OutboxRepository.OrderOutboxWrite(
                    "ORDER", updated.orderId(), properties.getKafka().getOrderEventsTopic(), updated.symbol(),
                    eventType.name(), payload(event), consumedAt));
            if (completion.accepted()) {
                OrderCommandEvent command = orderCommand(updated, OrderCommandType.PLACE, consumedAt,
                        result.traceId(), commandIds.get(commandIndex++));
                outboxWrites.add(new OutboxRepository.OrderOutboxWrite(
                        "ORDER", updated.orderId(), properties.getKafka().getOrderCommandsTopic(), updated.symbol(),
                        OrderCommandType.PLACE.name(), payload(command), consumedAt));
            }
        }
        orderRepository.insertEvents(orderEvents);
        outboxRepository.enqueueBatch(outboxWrites);
    }

    private void enqueueAccountReservation(OrderRecord order,
                                           OrderReserveAccountCommand reservation,
                                           Instant now,
                                           String traceId) {
        AccountUserCommand command = new AccountUserCommand(
                AccountUserCommand.CURRENT_SCHEMA_VERSION,
                reservationCommandId(order.productLine(), order.orderId()),
                order.productLine(),
                order.userId(),
                AccountUserCommandType.ORDER_RESERVE,
                "ORDER",
                String.valueOf(order.orderId()),
                null,
                payload(reservation),
                now,
                traceId);
        outboxRepository.enqueue("ORDER", order.orderId(), properties.getKafka().getAccountUserCommandsTopic(),
                command.partitionKey(), command.commandType().name(), payload(command), now);
    }

    private String reservationCommandId(ProductLine productLine, long orderId) {
        return "ORDER_RESERVE:" + productLine.name() + ":" + orderId;
    }

    private AmendOrderBatchResponse amendBatchResponse(List<AmendOrderBatchItemResponse> results) {
        int completed = (int) results.stream().filter(AmendOrderBatchItemResponse::success).count();
        return new AmendOrderBatchResponse(results.size(), completed, results.size() - completed, results);
    }

    private void enqueueCommand(OrderRecord order, OrderCommandType commandType, Instant now, String traceId) {
        long commandId = orderRepository.nextSequence("command");
        OrderCommandEvent command = orderCommand(order, commandType, now, traceId, commandId);
        outboxRepository.enqueue("ORDER", order.orderId(), properties.getKafka().getOrderCommandsTopic(),
                order.symbol(), commandType.name(), payload(command), now);
    }

    private OrderCommandEvent orderCommand(OrderRecord order,
                                           OrderCommandType commandType,
                                           Instant now,
                                           String traceId,
                                           long commandId) {
        // The future exchange-core matching provider should treat commandId/orderId as its idempotency keys.
        return new OrderCommandEvent(
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
                order.makerFeeRatePpm(),
                order.takerFeeRatePpm(),
                order.reduceOnly(),
                order.postOnly(),
                now,
                traceId);
    }

    private OrderRecord rejectOrder(OrderRecord order, String rejectReason, Instant now) {
        return new OrderRecord(
                order.orderId(),
                order.productLine(),
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
                now,
                order.revision() + 1);
    }

    private void enqueueOrderEvent(OrderRecord order,
                                   OrderEventType eventType,
                                   String reason,
                                   Instant now,
                                   String traceId) {
        long eventId = orderRepository.nextSequence("event");
        OrderEvent event = orderEvent(order, eventType, reason, now, traceId, eventId);
        orderRepository.insertEvent(event);
        outboxRepository.enqueue("ORDER", order.orderId(), properties.getKafka().getOrderEventsTopic(),
                order.symbol(), eventType.name(), payload(event), now);
    }

    private OrderEvent orderEvent(OrderRecord order,
                                  OrderEventType eventType,
                                  String reason,
                                  Instant now,
                                  String traceId,
                                  long eventId) {
        return new OrderEvent(
                eventId,
                order.orderId(),
                order.userId(),
                order.symbol(),
                eventType,
                order.status(),
                reason,
                now,
                traceId);
    }

    private String payload(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException ex) {
            throw new IllegalStateException("failed to serialize order event", ex);
        }
    }

    private record ReservationPlan(OrderReserveAccountCommand command, String rejectReason) {

        private static ReservationPlan none() {
            return new ReservationPlan(null, null);
        }

        private static ReservationPlan accept(OrderReserveAccountCommand command) {
            return new ReservationPlan(command, null);
        }

        private static ReservationPlan reject(String reason) {
            return new ReservationPlan(null, reason);
        }

        private boolean accepted() {
            return rejectReason == null || rejectReason.isBlank();
        }
    }

    private PlaceOrderRequest normalize(PlaceOrderRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("order request is required");
        }
        if (request.userId() <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
        if (request.side() == null || request.orderType() == null || request.timeInForce() == null) {
            throw new IllegalArgumentException("side, orderType and timeInForce are required");
        }
        if (request.priceTicks() < 0 || request.quantitySteps() <= 0) {
            throw new IllegalArgumentException("priceTicks must be non-negative and quantitySteps must be positive");
        }
        String clientOrderId = emptyToNull(request.clientOrderId());
        if (clientOrderId != null && clientOrderId.length() > 64) {
            throw new IllegalArgumentException("clientOrderId length must be <= 64");
        }
        PositionSide positionSide = PositionSide.defaultIfNull(request.positionSide());
        return new PlaceOrderRequest(
                request.userId(),
                clientOrderId,
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

    private AmendOrderRequest normalizeAmend(AmendOrderRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("amend order request is required");
        }
        if (request.userId() <= 0 || request.orderId() <= 0) {
            throw new IllegalArgumentException("userId and orderId must be positive");
        }
        String newClientOrderId = normalizeClientOrderId(request.newClientOrderId());
        if (request.priceTicks() != null && request.priceTicks() <= 0) {
            throw new IllegalArgumentException("priceTicks must be positive for amend");
        }
        if (request.quantitySteps() != null && request.quantitySteps() <= 0) {
            throw new IllegalArgumentException("quantitySteps must be positive for amend");
        }
        if (request.priceTicks() == null && request.quantitySteps() == null
                && request.timeInForce() == null && request.postOnly() == null) {
            throw new IllegalArgumentException("amend request must change price, quantity, timeInForce or postOnly");
        }
        if (request.timeInForce() == TimeInForce.IOC || request.timeInForce() == TimeInForce.FOK) {
            throw new IllegalArgumentException("amended resting order requires GTC or GTX");
        }
        return new AmendOrderRequest(request.userId(), request.orderId(), newClientOrderId,
                request.priceTicks(), request.quantitySteps(), request.timeInForce(), request.postOnly());
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

    private RedisOpenOrderView.Page databaseOpenOrderPage(ProductLine productLine,
                                                           long userId,
                                                           String symbol,
                                                           long beforeOrderId,
                                                           int limit) {
        List<OrderRecord> fetched = orderRepository.openOrdersByOrderId(productLine, userId, symbol,
                beforeOrderId, limit + 1);
        boolean hasMore = fetched.size() > limit;
        List<OrderRecord> page = hasMore ? List.copyOf(fetched.subList(0, limit)) : List.copyOf(fetched);
        Long nextOrderId = hasMore && !page.isEmpty() ? page.get(page.size() - 1).orderId() : null;
        return new RedisOpenOrderView.Page(page, hasMore, nextOrderId);
    }

    static String encodeOpenOrderCursor(long orderId) {
        if (orderId <= 0L) {
            throw new IllegalArgumentException("open-order cursor orderId must be positive");
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("order:" + orderId).getBytes(StandardCharsets.UTF_8));
    }

    static long decodeOpenOrderCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return Long.MAX_VALUE;
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            if (!decoded.startsWith("order:")) {
                throw new IllegalArgumentException("invalid open-order cursor");
            }
            long orderId = Long.parseLong(decoded.substring("order:".length()));
            if (orderId <= 0L) {
                throw new IllegalArgumentException("invalid open-order cursor");
            }
            return orderId;
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("invalid open-order cursor", ex);
        }
    }

    private ProductLine currentProductLine() {
        return properties.getKafka().getProductLine();
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
