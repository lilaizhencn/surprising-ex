package com.surprising.trading.order.service;

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
import com.surprising.trading.order.model.OrderFeeSnapshot;
import com.surprising.trading.order.model.OrderRecord;
import com.surprising.trading.order.model.ReduceOnlyPosition;
import com.surprising.trading.order.model.ValidationResult;
import com.surprising.trading.order.repository.AeronOrderProjectionRepository;
import com.surprising.trading.order.repository.ProjectionReadResult;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class OrderService {

    private final TradingOrderProperties properties;
    private final OrderValidator orderValidator;
    private final OrderPlacementStateService placementStateService;
    private final OrderFeeSnapshotLookup feeSnapshotLookup;
    private final AeronOrderCommandService aeronOrders;
    private final AeronOrderProjectionRepository aeronOrderProjection;

    @Autowired
    public OrderService(TradingOrderProperties properties,
                        OrderValidator orderValidator,
                        OrderPlacementStateService placementStateService,
                        OrderFeeSnapshotLookup feeSnapshotLookup,
                        AeronOrderCommandService aeronOrders,
                        AeronOrderProjectionRepository aeronOrderProjection) {
        this.properties = properties;
        this.orderValidator = orderValidator;
        this.placementStateService = placementStateService;
        this.feeSnapshotLookup = feeSnapshotLookup;
        this.aeronOrders = aeronOrders;
        this.aeronOrderProjection = aeronOrderProjection;
    }

    public OrderService(TradingOrderProperties properties,
                        OrderValidator orderValidator,
                        OrderPlacementStateService placementStateService,
                        OrderFeeSnapshotLookup feeSnapshotLookup) {
        this(properties, orderValidator, placementStateService, feeSnapshotLookup, null, null);
    }

    public OrderResponse place(PlaceOrderRequest request) {
        return placeAeron(request);
    }

    private OrderResponse placeAeron(PlaceOrderRequest request) {
        requireAeron();
        PlaceOrderRequest normalized = normalize(request);
        PreparedAeronOrder prepared = prepareAeronOrder(normalized);
        return aeronOrders.place(prepared.request(), prepared.validation(), prepared.fee());
    }

    private PreparedAeronOrder prepareAeronOrder(PlaceOrderRequest normalized) {
        ProductLine productLine = currentProductLine();
        normalized = normalizePositionSemantics(normalized, productLine);
        ValidationResult validation = orderValidator.validate(normalized);
        if (!validation.accepted()) throw new IllegalArgumentException(validation.rejectReason());
        OrderFeeSnapshot fee = feeSnapshotLookup.lookup(productLine, normalized.userId(), normalized.symbol(),
                        validation.instrumentVersion(), Instant.now())
                .orElseThrow(() -> new IllegalStateException("fee schedule unavailable"));
        return new PreparedAeronOrder(normalized, validation, fee);
    }

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

    public TestOrderResponse test(PlaceOrderRequest request) {
        return testLocal(request);
    }

    /** 生产测试下单只读取 JVM 快照，不为校验请求打开数据库事务。 */
    private TestOrderResponse testLocal(PlaceOrderRequest request) {
        PlaceOrderRequest normalized = normalize(request);
        ProductLine productLine = currentProductLine();
        normalized = normalizePositionSemantics(normalized, productLine);
        ValidationResult validation = orderValidator.validate(normalized);
        if (!validation.accepted()) {
            return testRejected(validation, "ORDER_RULES");
        }
        var resolvedFeeSnapshot = feeSnapshotLookup == null
                ? java.util.Optional.<OrderFeeSnapshot>empty()
                : feeSnapshotLookup.lookup(productLine, normalized.userId(), normalized.symbol(),
                validation.instrumentVersion(), Instant.now());
        if (resolvedFeeSnapshot.isEmpty()) {
            return new TestOrderResponse(false, "fee schedule unavailable", validation.instrumentVersion(),
                    "FEE", null, null, 0L);
        }
        return dryRunOpeningFunds(normalized, validation, resolvedFeeSnapshot.get());
    }

    public AmendOrderResponse amend(AmendOrderRequest request) {
        return amendAeron(request);
    }

    private AmendOrderResponse amendAeron(AmendOrderRequest request) {
        requireAeron();
        AmendOrderRequest normalized = normalizeAmend(request);
        return aeronOrders.replace(normalized);
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
        PositionMode positionMode = placementStateService.positionMode(productLine, request.userId());
        if (PositionMode.defaultIfNull(positionMode) == PositionMode.HEDGE && !positionSide.isHedgeSide()) {
            throw new IllegalArgumentException("positionSide LONG or SHORT is required in HEDGE position mode");
        }
        ReduceOnlyPosition position = placementStateService.position(productLine, request.userId(), symbol, marginMode,
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

    private TestOrderResponse dryRunOpeningFunds(PlaceOrderRequest request,
                                                 ValidationResult validation,
                                                 OrderFeeSnapshot feeSnapshot) {
        OrderAeronGateway.PreflightResult result = requireAeron().preflight(request, validation, feeSnapshot);
        if (!result.accepted()) {
            return new TestOrderResponse(false, result.resultCode().name(), validation.instrumentVersion(),
                    "CORE_PREFLIGHT", currentProductLine().accountTypeCode(), null, 0L);
        }
        return new TestOrderResponse(true, null, validation.instrumentVersion(), "ACCEPTED",
                currentProductLine().accountTypeCode(), result.view().reservationAsset(),
                result.view().reservedUnits());
    }

    public OrderResponse cancel(CancelOrderRequest request) {
        if (request.userId() <= 0 || request.orderId() <= 0) {
            throw new IllegalArgumentException("userId and orderId must be positive");
        }
        return requireAeron().cancel(request.userId(), request.orderId());
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
        List<OrderResponse> open = projectionOpenOrders(currentProductLine(), request.userId(), symbol, limit);
        requireAeron();
        List<OrderBatchItemResponse> results = new ArrayList<>();
        for (int index = 0; index < open.size(); index++) {
            try {
                results.add(new OrderBatchItemResponse(index, true, "completed",
                        aeronOrders.cancel(request.userId(), open.get(index).orderId())));
            } catch (IllegalStateException exception) {
                results.add(new OrderBatchItemResponse(index, false, exception.getMessage(), null));
            }
        }
        return orderBatchResponse(results);
    }

    public OrderResponse get(long userId, long orderId) {
        return get(userId, orderId, null);
    }

    public OrderResponse get(long userId, long orderId, Long minExportSequence) {
        if (userId <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
        requireOrderId(orderId);
        return singleProjection(requireProjection().byOrder(currentProductLine(), userId, orderId,
                minExportSequence), "order not found: " + orderId);
    }

    public OrderResponse getByClientOrderId(long userId, String clientOrderId) {
        return getByClientOrderId(userId, clientOrderId, null);
    }

    public OrderResponse getByClientOrderId(long userId, String clientOrderId, Long minExportSequence) {
        if (userId <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
        String normalized = normalizeClientOrderId(clientOrderId);
        return singleProjection(requireProjection().byClientOrderId(currentProductLine(), userId, normalized,
                minExportSequence), "order not found: " + normalized);
    }

    public OrderQueryResponse openOrders(long userId, String symbol, int limit) {
        return openOrders(userId, symbol, limit, null);
    }

    public OrderQueryResponse openOrders(long userId, String symbol, int limit, String cursor) {
        return openOrders(userId, symbol, limit, cursor, null);
    }

    public OrderQueryResponse openOrders(long userId, String symbol, int limit, String cursor,
                                         Long minExportSequence) {
        if (userId <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
        if (limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("limit must be in [1, 1000]");
        }
        String normalizedSymbol = symbol == null || symbol.isBlank() ? null : normalizeSymbol(symbol);
        return toQueryResponse(requireProjection().openOrders(currentProductLine(), userId, normalizedSymbol,
                cursor, limit, minExportSequence), "createdAt.desc", limit);
    }

    private List<OrderResponse> projectionOpenOrders(ProductLine productLine, Long userId, String symbol,
                                                     int limit) {
        if (productLine != currentProductLine()) {
            throw new IllegalArgumentException("product line does not match this order core");
        }
        return readyProjection(requireProjection().openOrders(productLine, userId, symbol, null, limit, null));
    }

    public OrderQueryResponse historyOrders(long userId,
                                            String symbol,
                                            int limit,
                                            Long minimumOrderId,
                                            Long startTimeMillis,
                                            Long endTimeMillis) {
        return historyOrders(userId, symbol, limit, minimumOrderId, startTimeMillis, endTimeMillis, null, null);
    }

    public OrderQueryResponse historyOrders(long userId,
                                            String symbol,
                                            int limit,
                                            Long minimumOrderId,
                                            Long startTimeMillis,
                                            Long endTimeMillis,
                                            String cursor,
                                            Long minExportSequence) {
        if (userId <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
        if (limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("limit must be in [1, 1000]");
        }
        if (minimumOrderId != null && minimumOrderId <= 0L) {
            throw new IllegalArgumentException("orderId must be positive");
        }
        Instant startTime = epochMillis(startTimeMillis, "startTime");
        Instant endTime = epochMillis(endTimeMillis, "endTime");
        if (startTime != null && endTime != null && startTime.isAfter(endTime)) {
            throw new IllegalArgumentException("startTime must not be after endTime");
        }
        String normalizedSymbol = symbol == null || symbol.isBlank() ? null : normalizeSymbol(symbol);
        return toQueryResponse(requireProjection().historyOrders(currentProductLine(), userId, normalizedSymbol,
                limit, minimumOrderId, startTimeMillis, endTimeMillis, cursor, minExportSequence),
                "createdAt.desc", limit);
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
        boolean ascending = "createdAt.asc".equalsIgnoreCase(sort);
        return toQueryResponse(requireProjection().search(resolvedProductLine, userId, normalizedSymbol,
                normalizedStatus, orderId, cursor, ascending, limit, null),
                ascending ? "createdAt.asc" : "createdAt.desc", limit);
    }

    public AdminCancelOrderResult adminCancelOrder(long orderId, String reason) {
        return adminCancelOrder(orderId, reason, null);
    }

    public AdminCancelOrderResult adminCancelOrder(long orderId, String reason, ProductLine productLine) {
        requireOrderId(orderId);
        ProductLine resolved = productLine == null ? currentProductLine() : productLine;
        requireCurrentProductLine(resolved);
        OrderResponse selected = singleProjection(requireProjection().byOrder(resolved, (Long) null, orderId, null),
                "order not found: " + orderId);
        requireAeron();
        OrderResponse canceled = aeronOrders.cancel(selected.userId(), selected.orderId());
        boolean requested = cancelSucceeded(canceled.status());
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
        String reason = adminCancelReason(request == null ? null : request.reason());
        ProductLine resolved = productLine == null ? currentProductLine() : productLine;
        List<OrderResponse> selected = projectionOpenOrders(resolved, userId, symbol, limit);
        requireAeron();
        List<OrderResponse> values = new ArrayList<>();
        for (OrderResponse order : selected) {
            try {
                values.add(aeronOrders.cancel(order.userId(), order.orderId()));
            } catch (IllegalStateException ignored) {
            }
        }
        List<OrderResponse> canceled = List.copyOf(values);
        List<AdminCancelOrderResult> results = canceled.stream()
                .map(order -> new AdminCancelOrderResult(order.orderId(), order.userId(), order.symbol(),
                        order.status(), cancelSucceeded(order.status()),
                        cancelSucceeded(order.status()) ? "cancel completed"
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
        List<OrderResponse> orders = projectionOpenOrders(resolvedProductLine, userId, normalizedSymbol, limit);
        long quantity = orders.stream().mapToLong(OrderResponse::remainingQuantitySteps).sum();
        int buys = (int) orders.stream().filter(order -> order.side() == OrderSide.BUY).count();
        return new AdminCancelOrdersPreviewResponse(userId, normalizedSymbol, orders.size(), orders.size(),
                quantity, buys, orders.size() - buys, orders);
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
        List<OrderResponse> selected = requireAeron().lifecycleOpenOrders(normalizedSymbol, limit);
        int completed = 0;
        for (OrderResponse order : selected) {
            try {
                if (cancelSucceeded(aeronOrders.cancel(order.userId(), order.orderId()).status())) completed++;
            } catch (IllegalStateException ignored) {
            }
        }
        return completed;
    }

    public boolean hasLifecycleActiveOrders(String symbol) {
        return !requireAeron().lifecycleOpenOrders(normalizeSymbol(symbol), 1).isEmpty();
    }

    private AeronOrderProjectionRepository requireProjection() {
        if (aeronOrderProjection == null) {
            throw new IllegalStateException("order projection repository is required");
        }
        return aeronOrderProjection;
    }

    private OrderResponse singleProjection(ProjectionReadResult result, String notFoundMessage) {
        List<OrderResponse> orders = readyProjection(result);
        if (orders.isEmpty()) throw new IllegalStateException(notFoundMessage);
        return orders.getFirst();
    }

    private List<OrderResponse> readyProjection(ProjectionReadResult result) {
        if (result.status() == ProjectionReadResult.Status.PROJECTION_LAG) {
            throw new ProjectionReadResult.ProjectionLagException(result.observedExportSequence(),
                    result.requiredExportSequence());
        }
        if (result.status() == ProjectionReadResult.Status.RESPONSE_TOO_LARGE) {
            throw new ProjectionReadResult.ResponseTooLargeException(result.observedExportSequence(),
                    result.requiredExportSequence(), result.nextCursor());
        }
        return result.orders();
    }

    private OrderQueryResponse toQueryResponse(ProjectionReadResult result, String sort, int limit) {
        List<OrderResponse> orders = readyProjection(result);
        return new OrderQueryResponse(orders.size(), orders, result.nextCursor(), result.hasMore(), sort, limit);
    }

    private AeronOrderCommandService requireAeron() {
        if (aeronOrders == null) throw new IllegalStateException("Aeron order gateway is required");
        return aeronOrders;
    }

    private ProductLine requireCurrentProductLine(ProductLine productLine) {
        if (productLine != currentProductLine()) {
            throw new IllegalArgumentException("product line does not match this order core");
        }
        return productLine;
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

    private static boolean cancelSucceeded(OrderStatus status) {
        return status == OrderStatus.CANCEL_REQUESTED || status == OrderStatus.CANCELED;
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

    private AmendOrderBatchResponse amendBatchResponse(List<AmendOrderBatchItemResponse> results) {
        int completed = (int) results.stream().filter(AmendOrderBatchItemResponse::success).count();
        return new AmendOrderBatchResponse(results.size(), completed, results.size() - completed, results);
    }

    private record PreparedAeronOrder(PlaceOrderRequest request, ValidationResult validation,
                                      OrderFeeSnapshot fee) {
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

    private PlaceOrderRequest normalizePositionSemantics(PlaceOrderRequest request, ProductLine productLine) {
        if (productLine == ProductLine.SPOT) {
            if (PositionSide.defaultIfNull(request.positionSide()).isHedgeSide()) {
                throw new IllegalArgumentException("现货订单不支持 LONG/SHORT 仓位方向");
            }
            return request;
        }
        PositionSide positionSide = PositionSide.defaultIfNull(request.positionSide());
        if (!positionSide.isHedgeSide()) return request;
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

    private void requireOrderId(long orderId) {
        if (orderId <= 0) {
            throw new IllegalArgumentException("orderId must be positive");
        }
    }

    private String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private Instant epochMillis(Long value, String field) {
        if (value == null) {
            return null;
        }
        if (value < 0L) {
            throw new IllegalArgumentException(field + " must be non-negative");
        }
        try {
            return Instant.ofEpochMilli(value);
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException(field + " is invalid", ex);
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
