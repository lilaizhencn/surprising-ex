package com.surprising.trading.order.service;

import com.surprising.account.api.model.PositionUpdatedEvent;
import com.surprising.account.api.model.AccountType;
import com.surprising.account.api.model.OrderReservationKind;
import com.surprising.account.api.model.OrderReserveAccountCommand;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.AmendOrderBatchItemResponse;
import com.surprising.trading.api.model.AmendOrderBatchResponse;
import com.surprising.trading.api.model.AmendOrderRequest;
import com.surprising.trading.api.model.AmendOrderResponse;
import com.surprising.trading.api.model.AdminBatchCancelOrdersRequest;
import com.surprising.trading.api.model.AdminCancelBySymbolRequest;
import com.surprising.trading.api.model.AdminCancelOrderResult;
import com.surprising.trading.api.model.AdminCancelOrdersResponse;
import com.surprising.trading.api.model.AdminCancelOrdersPreviewResponse;
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
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class OrderService {

    private static final String REDUCE_ONLY_PRUNE_REASON = "REDUCE_ONLY_POSITION_REDUCED";
    private final TradingOrderProperties properties;
    private final OrderValidator orderValidator;
    private final ReduceOnlyValidator reduceOnlyValidator;
    private final OrderPlacementStateService placementStateService;
    private final OrderMarginCalculator orderMarginCalculator;
    private final SpotReservationCalculator spotReservationCalculator;
    private final OrderFeeSnapshotLookup feeSnapshotLookup;
    private final OrderUserStateService orderUserStateService;
    private final OrderUserCommandGateway orderUserCommandGateway;

    @Autowired
    public OrderService(TradingOrderProperties properties,
                        OrderValidator orderValidator,
                        ReduceOnlyValidator reduceOnlyValidator,
                        OrderPlacementStateService placementStateService,
                        OrderMarginCalculator orderMarginCalculator,
                        SpotReservationCalculator spotReservationCalculator,
                        OrderFeeSnapshotLookup feeSnapshotLookup,
                        OrderUserStateService orderUserStateService,
                        OrderUserCommandGateway orderUserCommandGateway) {
        this.properties = properties;
        this.orderValidator = orderValidator;
        this.reduceOnlyValidator = reduceOnlyValidator;
        this.placementStateService = placementStateService;
        this.orderMarginCalculator = orderMarginCalculator;
        this.spotReservationCalculator = spotReservationCalculator;
        this.feeSnapshotLookup = feeSnapshotLookup;
        this.orderUserStateService = orderUserStateService;
        this.orderUserCommandGateway = orderUserCommandGateway;
    }

    public OrderResponse place(PlaceOrderRequest request) {
        return placeWal(request, null);
    }

    /**
     * 批量下单使用同一用户的账户修订号序列；普通单笔下单仍严格读取当前 JVM 快照。
     */
    private OrderResponse place(PlaceOrderRequest request, BatchReservationSequence sequence) {
        return placeWal(request, sequence);
    }

    /** 生产下单入口：只构造订单事实和账户预占命令，不写订单数据库。 */
    private OrderResponse placeWal(PlaceOrderRequest request, BatchReservationSequence sequence) {
        PlaceOrderRequest normalized = normalize(request);
        ProductLine productLine = currentProductLine();
        requireLocalAccountProductLine(productLine);
        if (hasClientOrderId(normalized)) {
            var existing = orderUserStateService.findByClientOrderId(
                    normalized.userId(), normalized.clientOrderId());
            if (existing.isPresent()) {
                requireSameClientOrderIntent(normalized, existing.get());
                return existing.get();
            }
        }
        PositionMode positionMode = productLine == ProductLine.SPOT
                ? PositionMode.ONE_WAY
                : placementStateService.localPositionMode(productLine, normalized.userId());
        normalized = normalizePositionMode(normalized, positionMode);
        Instant now = Instant.now();
        ValidationResult validation = validateMarginModeForLocalState(productLine, normalized);
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
            var resolved = feeSnapshotLookup == null ? java.util.Optional.<OrderFeeSnapshot>empty()
                    : feeSnapshotLookup.lookup(productLine, normalized.userId(), normalized.symbol(),
                    validation.instrumentVersion(), now);
            if (resolved.isEmpty()) {
                validation = ValidationResult.reject("fee schedule unavailable", validation.instrumentVersion());
            } else {
                feeSnapshot = resolved.get();
            }
        }
        long orderId = orderUserStateService.nextOrderId();
        ReservationPlan reservationPlan = ReservationPlan.none();
        if (validation.accepted() && (!normalized.reduceOnly() || requiresReduceOnlyFunds(normalized, validation))) {
            reservationPlan = planOpeningFunds(normalized, orderId, validation, feeSnapshot, sequence);
            if (!reservationPlan.accepted()) {
                validation = ValidationResult.reject(reservationPlan.rejectReason(), validation.instrumentVersion(),
                        validation.instrumentType(), validation.contractType());
            }
        }
        OrderStatus status = !validation.accepted() ? OrderStatus.REJECTED
                : reservationPlan.command() == null ? OrderStatus.ACCEPTED : OrderStatus.PENDING_RESERVE;
        OrderReserveAccountCommand reservation = reservationPlan.command();
        OrderRecord order = new OrderRecord(orderId, productLine, normalized.userId(),
                emptyToNull(normalized.clientOrderId()), normalized.symbol(), validation.instrumentVersion(),
                normalized.side(), normalized.orderType(), normalized.timeInForce(), normalized.priceTicks(),
                normalized.quantitySteps(), 0L, validation.accepted() ? normalized.quantitySteps() : 0L,
                normalized.marginMode(), normalized.positionSide(), feeSnapshot.makerFeeRatePpm(),
                feeSnapshot.takerFeeRatePpm(), normalized.reduceOnly(), normalized.postOnly(),
                reservation == null ? null : reservation.accountType().name(),
                reservation == null ? null : reservation.asset(), reservation == null ? 0L : reservation.reservedUnits(),
                status, validation.rejectReason(), now, now, 1L);
        return orderUserCommandGateway.place(order);
    }

    /** 订单事实流只开放已接入本地账户 reducer 的产品线，未接入的产品线必须失败关闭。 */
    private void requireLocalAccountProductLine(ProductLine productLine) {
        if (productLine == null || productLine == ProductLine.OPTION) {
            throw new IllegalStateException("产品线尚未接入本地账户事实流: " + productLine);
        }
    }

    /** 本地订单事实流使用账户快照和用户分区状态完成保证金模式校验，不打开数据库事务。 */
    private ValidationResult validateMarginModeForLocalState(ProductLine productLine,
                                                             PlaceOrderRequest request) {
        if (productLine == ProductLine.SPOT) {
            return ValidationResult.ok();
        }
        if (productLine == ProductLine.OPTION) {
            throw new IllegalStateException("产品线尚未接入本地账户事实流: " + productLine);
        }
        if (request.reduceOnly()) {
            // 只减仓不会新增保证金模式状态；仓位模式和仓位快照由只减仓校验统一确认。
            return ValidationResult.ok();
        }
        if (placementStateService.cachedPositionMarginModeConflict(productLine, request.userId(),
                request.symbol(), request.marginMode())) {
            return ValidationResult.reject("margin mode switch requires closing positions and open orders first");
        }
        if (orderUserStateService.hasActiveMarginModeConflict(request.userId(), request.symbol(),
                request.marginMode())) {
            return ValidationResult.reject("margin mode switch requires closing positions and open orders first");
        }
        return ValidationResult.ok();
    }

    public OrderBatchResponse placeBatch(BatchPlaceOrderRequest request) {
        List<PlaceOrderRequest> orders = request == null ? List.of() : request.orders();
        requireBatchSize(orders.size(), 20, "orders");
        List<OrderBatchItemResponse> results = new ArrayList<>();
        BatchReservationSequence sequence = new BatchReservationSequence();
        for (int i = 0; i < orders.size(); i++) {
            try {
                OrderResponse order = place(orders.get(i), sequence);
                results.add(new OrderBatchItemResponse(i, true, "completed", order));
            } catch (IllegalArgumentException | IllegalStateException ex) {
                results.add(new OrderBatchItemResponse(i, false, ex.getMessage(), null));
            }
        }
        return orderBatchResponse(results);
    }

    public TestOrderResponse test(PlaceOrderRequest request) {
        return testLocal(request);
    }

    /** 生产测试下单只读取 JVM 快照，不为校验请求打开数据库事务。 */
    private TestOrderResponse testLocal(PlaceOrderRequest request) {
        PlaceOrderRequest normalized = normalize(request);
        ProductLine productLine = currentProductLine();
        requireLocalAccountProductLine(productLine);
        PositionMode positionMode = productLine == ProductLine.SPOT
                ? PositionMode.ONE_WAY
                : placementStateService.localPositionMode(productLine, normalized.userId());
        normalized = normalizePositionMode(normalized, positionMode);
        ValidationResult validation = validateMarginModeForLocalState(productLine, normalized);
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
        var resolvedFeeSnapshot = feeSnapshotLookup == null
                ? java.util.Optional.<OrderFeeSnapshot>empty()
                : feeSnapshotLookup.lookup(productLine, normalized.userId(), normalized.symbol(),
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

    public AmendOrderResponse amend(AmendOrderRequest request) {
        return amendWal(request);
    }

    /** 生产改单只追加同一用户分区的撤单事实，再提交替代订单事实。 */
    private AmendOrderResponse amendWal(AmendOrderRequest request) {
        AmendOrderRequest normalized = normalizeAmend(request);
        OrderResponse original = orderUserStateService.get(normalized.userId(), normalized.orderId());
        if (original.userId() != normalized.userId()) {
            throw new IllegalArgumentException("order does not belong to user");
        }
        if (original.orderType() != OrderType.LIMIT) {
            throw new IllegalArgumentException("only LIMIT orders can be amended");
        }
        if (original.status() != OrderStatus.ACCEPTED && original.status() != OrderStatus.PARTIALLY_FILLED) {
            throw new IllegalStateException("order is not amendable: " + original.status().name());
        }
        if (original.remainingQuantitySteps() <= 0L) {
            throw new IllegalStateException("order has no open quantity to amend");
        }
        long replacementPriceTicks = normalized.priceTicks() == null ? original.priceTicks() : normalized.priceTicks();
        long replacementQuantitySteps = normalized.quantitySteps() == null
                ? original.remainingQuantitySteps() : normalized.quantitySteps();
        TimeInForce replacementTif = normalized.timeInForce() == null
                ? original.timeInForce() : normalized.timeInForce();
        boolean replacementPostOnly = normalized.postOnly() == null
                ? original.postOnly() : normalized.postOnly();
        PlaceOrderRequest replacement = new PlaceOrderRequest(
                original.userId(), normalized.newClientOrderId(), original.symbol(), original.side(),
                original.orderType(), replacementTif, replacementPriceTicks, replacementQuantitySteps,
                original.marginMode(), original.positionSide(), original.reduceOnly(), replacementPostOnly);
        if (replacement.clientOrderId() != null && !replacement.clientOrderId().isBlank()) {
            var existing = orderUserStateService.findByClientOrderId(normalized.userId(),
                    replacement.clientOrderId());
            if (existing.isPresent()) {
                requireSameClientOrderIntent(replacement, existing.get());
                return new AmendOrderResponse(original, existing.get(), false, "replacement order already exists");
            }
        }
        OrderResponse canceled = orderUserCommandGateway.cancel(currentProductLine(), original.userId(), original.orderId(),
                "order amend replace");
        if (canceled.status() != OrderStatus.CANCEL_REQUESTED && canceled.status() != OrderStatus.CANCELED) {
            throw new IllegalStateException("cancel requested failed for amend: " + canceled.status());
        }
        OrderResponse replacementOrder = place(replacement);
        String message = replacementOrder.status() == OrderStatus.REJECTED
                ? "cancel requested; replacement rejected: " + replacementOrder.rejectReason()
                : "cancel requested; replacement submitted";
        return new AmendOrderResponse(canceled, replacementOrder, true, message);
    }

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

    public OrderResponse closePosition(ClosePositionRequest request) {
        return closePositionWal(request);
    }

    /** 永续平仓从账户 JVM 快照读取仓位，再通过订单用户事实流提交只减仓单。 */
    private OrderResponse closePositionWal(ClosePositionRequest request) {
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
        // 平仓读取账户用户分区快照；不再为一次校验打开数据库锁事务。
        PositionMode positionMode = placementStateService.localPositionMode(productLine, request.userId());
        if (PositionMode.defaultIfNull(positionMode) == PositionMode.HEDGE && !positionSide.isHedgeSide()) {
            throw new IllegalArgumentException("positionSide LONG or SHORT is required in HEDGE position mode");
        }
        ReduceOnlyPosition position = placementStateService.localPosition(productLine, request.userId(), symbol, marginMode,
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
                                             OrderFeeSnapshot feeSnapshot,
                                             BatchReservationSequence sequence) {
        if (validation.instrumentType() == InstrumentType.SPOT) {
            return planSpotReservation(request, orderId, validation.instrumentVersion(), feeSnapshot);
        }
        return planDerivativeReservation(request, orderId, validation.instrumentVersion(), sequence);
    }

    private ReservationPlan planSpotReservation(PlaceOrderRequest request,
                                                long orderId,
                                                long instrumentVersion,
                                                OrderFeeSnapshot feeSnapshot) {
        var requirement = spotReservationCalculator.requirement(
                request.symbol(), instrumentVersion, request.side(), request.orderType(), request.priceTicks(),
                request.quantitySteps(), feeSnapshot);
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
                                                      long instrumentVersion,
                                                      BatchReservationSequence sequence) {
        var requirement = orderMarginCalculator.requirement(
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
        ReservationSequenceSlot slot = sequence == null
                // 单笔订单通过 Kafka 异步到达账户用户分区，期间同一用户可能已经完成
                // 另一笔预占并推进账户修订号。这里不携带下单时读取到的旧修订号，
                // 由账户单写入 reducer 按 WAL 顺序裁决资金；只有批量请求内部显式建立
                // 依赖链时，才用前一条命令约束批内顺序。
                ? new ReservationSequenceSlot(0L, null)
                : sequence.next(currentProductLine(), request.userId(), orderId);
        return ReservationPlan.accept(new OrderReserveAccountCommand(
                orderId, request.symbol(), request.side(), OrderReservationKind.DERIVATIVE_MARGIN, accountType,
                requirement.get().asset(), request.marginMode(), request.positionSide(),
                request.quantitySteps(), request.reduceOnly(),
                requirement.get().initialMarginUnits(), slot.expectedAccountRevision()), slot.dependsOnCommandId());
    }

    private TestOrderResponse dryRunOpeningFunds(PlaceOrderRequest request,
                                                 ValidationResult validation,
                                                 OrderFeeSnapshot feeSnapshot) {
        if (validation.instrumentType() == InstrumentType.SPOT) {
            var requirement = spotReservationCalculator.requirement(
                    request.symbol(), validation.instrumentVersion(), request.side(), request.orderType(),
                    request.priceTicks(), request.quantitySteps(), feeSnapshot);
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
        var requirement = orderMarginCalculator.requirement(
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

    private boolean requiresReduceOnlyFunds(PlaceOrderRequest request, ValidationResult validation) {
        return request.reduceOnly()
                && validation.contractType() == ContractType.VANILLA_OPTION
                && request.side() == OrderSide.BUY;
    }

    private OrderFeeSnapshot rejectedFeeSnapshot() {
        return new OrderFeeSnapshot(properties.getKafka().getProductLine(), 0L, 0L, "REJECTED");
    }

    public OrderResponse cancel(CancelOrderRequest request) {
        if (request.userId() <= 0 || request.orderId() <= 0) {
            throw new IllegalArgumentException("userId and orderId must be positive");
        }
        return orderUserCommandGateway.cancel(currentProductLine(), request.userId(), request.orderId(), null);
    }

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
                ? null : normalizeSymbol(request.symbol());
        return orderUserCommandGateway.cancelOpen(currentProductLine(), request.userId(), symbol, limit);
    }

    public OrderResponse get(long orderId) {
        return orderUserStateService.get(orderId);
    }

    public OrderResponse getByClientOrderId(long userId, String clientOrderId) {
        if (userId <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
        return orderUserStateService.getByClientOrderId(userId, normalizeClientOrderId(clientOrderId));
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
        long beforeOrderId = decodeOpenOrderCursor(cursor);
        return orderUserStateService.openOrders(userId, symbol, limit, beforeOrderId);
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
        ProductLine resolvedProductLine = productLine == null ? currentProductLine() : productLine;
        return orderUserStateService.adminOrders(resolvedProductLine, userId, normalizedSymbol, normalizedStatus,
                orderId, limit, cursor, sort);
    }

    public AdminCancelOrderResult adminCancelOrder(long orderId, String reason) {
        return adminCancelOrder(orderId, reason, null);
    }

    public AdminCancelOrderResult adminCancelOrder(long orderId, String reason, ProductLine productLine) {
        requireOrderId(orderId);
        ProductLine resolved = productLine == null ? currentProductLine() : productLine;
        OrderResponse local = orderUserStateService.findAnyLocal(resolved, orderId)
                .orElseThrow(() -> new IllegalStateException("订单所属用户分区不在当前节点，不能直接管理撤单: " + orderId));
        OrderResponse canceled = orderUserCommandGateway.cancel(resolved, local.userId(), orderId,
                adminCancelReason(reason));
        boolean requested = canceled.status() == OrderStatus.CANCEL_REQUESTED;
        return new AdminCancelOrderResult(canceled.orderId(), canceled.userId(), canceled.symbol(),
                canceled.status(), requested, requested ? "cancel requested" : "order is already "
                + canceled.status().name(), canceled);
    }

    public AdminCancelOrdersResponse adminCancelOrders(AdminBatchCancelOrdersRequest request) {
        return adminCancelOrders(request, null);
    }

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
        if (userId == null) {
            throw new IllegalStateException("管理员跨用户撤单必须先按用户分区路由，已禁止本地全量扫描");
        }
        String reason = adminCancelReason(request == null ? null : request.reason());
        ProductLine resolved = productLine == null ? currentProductLine() : productLine;
        OrderBatchResponse batch = orderUserCommandGateway.cancelOpen(resolved, userId, symbol, limit, reason);
        List<OrderResponse> canceled = batch.results().stream().filter(OrderBatchItemResponse::success)
                .map(OrderBatchItemResponse::order).toList();
        List<AdminCancelOrderResult> results = canceled.stream()
                .map(order -> new AdminCancelOrderResult(order.orderId(), order.userId(), order.symbol(),
                        order.status(), order.status() == OrderStatus.CANCEL_REQUESTED,
                        order.status() == OrderStatus.CANCEL_REQUESTED ? "cancel requested"
                                : "order is already " + order.status().name(), order))
                .toList();
        int canceledCount = (int) results.stream().filter(AdminCancelOrderResult::cancelRequested).count();
        return new AdminCancelOrdersResponse(results.size(), canceledCount, results.size() - canceledCount, results);
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
        ProductLine resolvedProductLine = productLine == null ? currentProductLine() : productLine;
        return orderUserStateService.adminCancelPreview(resolvedProductLine, userId, normalizedSymbol, limit);
    }

    public AdminCancelOrdersResponse adminCancelBySymbol(AdminCancelBySymbolRequest request) {
        return adminCancelBySymbol(request, null);
    }

    public AdminCancelOrdersResponse adminCancelBySymbol(AdminCancelBySymbolRequest request, ProductLine productLine) {
        if (request == null) {
            throw new IllegalArgumentException("request is required");
        }
        String symbol = normalizeSymbol(request.symbol());
        return adminCancelOrders(new AdminBatchCancelOrdersRequest(null, symbol, request.limit(), request.reason()),
                productLine);
    }

    /**
     * 到期生命周期只发起撤单，最终状态仍由撮合结果驱动。
     */
    public int requestLifecycleCancellation(String symbol, int limit) {
        String normalizedSymbol = normalizeSymbol(symbol);
        ProductLine line = currentProductLine();
        int requested = 0;
        for (long userId : orderUserStateService.localUserIds(line)) {
            requested += orderUserCommandGateway.cancelOpen(line, userId, normalizedSymbol, limit,
                    "INSTRUMENT_SETTLING").completed();
        }
        return requested;
    }

    public boolean hasLifecycleActiveOrders(String symbol) {
        return orderUserStateService.hasLifecycleActiveOrders(currentProductLine(), normalizeSymbol(symbol));
    }

    /**
     * 账户单写者发布持久化仓位快照后，由订单侧负责清理只减仓订单。
     *
     * <p>撤单更新带状态条件，并且撮合确认撤单前 {@code CANCEL_REQUESTED} 订单仍参与容量计算，
     * 因此重复消费仓位事件不会重复生成有效撤单。</p>
     */
    public void onPositionUpdated(PositionUpdatedEvent event) {
        if (event == null || event.productLine() != currentProductLine()) {
            throw new IllegalArgumentException("position event product line does not match order provider");
        }
        orderUserCommandGateway.pruneReduceOnly(event, REDUCE_ONLY_PRUNE_REASON);
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

    private String reservationCommandId(ProductLine productLine, long orderId) {
        return "ORDER_RESERVE:" + productLine.name() + ":" + orderId;
    }

    private AmendOrderBatchResponse amendBatchResponse(List<AmendOrderBatchItemResponse> results) {
        int completed = (int) results.stream().filter(AmendOrderBatchItemResponse::success).count();
        return new AmendOrderBatchResponse(results.size(), completed, results.size() - completed, results);
    }

    private record ReservationPlan(OrderReserveAccountCommand command,
                                   String rejectReason,
                                   String dependsOnCommandId) {

        private static ReservationPlan none() {
            return new ReservationPlan(null, null, null);
        }

        private static ReservationPlan accept(OrderReserveAccountCommand command) {
            return new ReservationPlan(command, null, null);
        }

        private static ReservationPlan accept(OrderReserveAccountCommand command, String dependsOnCommandId) {
            return new ReservationPlan(command, null, dependsOnCommandId);
        }

        private static ReservationPlan reject(String reason) {
            return new ReservationPlan(null, reason, null);
        }

        private boolean accepted() {
            return rejectReason == null || rejectReason.isBlank();
        }
    }

    private record ReservationSequenceSlot(long expectedAccountRevision, String dependsOnCommandId) {
    }

    /** 批量请求内按用户建立账户命令依赖链，确保同一批次的预占按顺序执行。 */
    private final class BatchReservationSequence {
        private final Map<Long, String> previousCommands = new HashMap<>();

        private ReservationSequenceSlot next(ProductLine productLine,
                                              long userId,
                                              long orderId) {
            String dependency = previousCommands.get(userId);
            previousCommands.put(userId, reservationCommandId(productLine, orderId));
            // 账户修订号由异步账户命令推进，批次之外可能在消息到达前变化；
            // 不把下单时读取的旧值写入命令，最终资金裁决由账户用户分区 reducer 按 WAL 顺序完成。
            return new ReservationSequenceSlot(0L, dependency);
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
        if (currentProductLine() == ProductLine.SPOT) {
            if (PositionSide.defaultIfNull(request.positionSide()).isHedgeSide()) {
                throw new IllegalArgumentException("现货订单不支持 LONG/SHORT 仓位方向");
            }
            return request;
        }
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

    /**
     * 幂等键只能重放完全相同的业务意图；同一个键携带不同参数必须拒绝，
     * 否则客户端重试拼写错误会被误当成成功并造成资金预期与实际订单不一致。
     */
    private void requireSameClientOrderIntent(PlaceOrderRequest request, OrderRecord existing) {
        boolean same = existing.userId() == request.userId()
                && existing.productLine() == currentProductLine()
                && existing.clientOrderId() != null
                && existing.clientOrderId().equals(request.clientOrderId())
                && existing.symbol().equals(request.symbol())
                && existing.side() == request.side()
                && existing.orderType() == request.orderType()
                && existing.timeInForce() == request.timeInForce()
                && existing.priceTicks() == request.priceTicks()
                && existing.quantitySteps() == request.quantitySteps()
                && existing.marginMode() == request.marginMode()
                && existing.positionSide() == request.positionSide()
                && existing.reduceOnly() == request.reduceOnly()
                && existing.postOnly() == request.postOnly();
        if (!same) {
            throw new IllegalArgumentException("clientOrderId already used with different order parameters");
        }
    }

    private void requireSameClientOrderIntent(PlaceOrderRequest request, OrderResponse existing) {
        boolean same = existing.userId() == request.userId()
                && existing.clientOrderId() != null
                && existing.clientOrderId().equals(request.clientOrderId())
                && existing.symbol().equals(request.symbol())
                && existing.side() == request.side()
                && existing.orderType() == request.orderType()
                && existing.timeInForce() == request.timeInForce()
                && existing.priceTicks() == request.priceTicks()
                && existing.quantitySteps() == request.quantitySteps()
                && existing.marginMode() == request.marginMode()
                && existing.positionSide() == request.positionSide()
                && existing.reduceOnly() == request.reduceOnly()
                && existing.postOnly() == request.postOnly();
        if (!same) {
            throw new IllegalArgumentException("clientOrderId already used with different order parameters");
        }
    }

    private void requireOrderId(long orderId) {
        if (orderId <= 0) {
            throw new IllegalArgumentException("orderId must be positive");
        }
    }

    private String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
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
