package com.surprising.trading.order.service;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.OrderCommandReceipt;
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
import com.surprising.trading.order.model.OrderRecord;
import com.surprising.trading.order.model.ReduceOnlyPosition;
import com.surprising.trading.order.model.InstrumentRule;
import com.surprising.trading.order.model.ValidationResult;
import com.surprising.trading.order.repository.AeronOrderProjectionRepository;
import com.surprising.trading.order.repository.ProjectionReadResult;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class OrderService {

    private static final int MAX_CANCEL_BATCH_SIZE = 50;
    private static final String ACTIVE_ORDER_CURSOR_PREFIX = "core-open:v1:";

    private final TradingOrderProperties properties;
    private final OrderValidator orderValidator;
    private final OrderPlacementStateService placementStateService;
    private final AeronOrderCommandService aeronOrders;
    private final AeronOrderProjectionRepository aeronOrderProjection;

    @Autowired
    public OrderService(TradingOrderProperties properties,
                        OrderValidator orderValidator,
                        OrderPlacementStateService placementStateService,
                        AeronOrderCommandService aeronOrders,
                        AeronOrderProjectionRepository aeronOrderProjection) {
        this.properties = properties;
        this.orderValidator = orderValidator;
        this.placementStateService = placementStateService;
        this.aeronOrders = aeronOrders;
        this.aeronOrderProjection = aeronOrderProjection;
    }

    public OrderService(TradingOrderProperties properties,
                        OrderValidator orderValidator,
                        OrderPlacementStateService placementStateService) {
        this(properties, orderValidator, placementStateService, null, null);
    }

    public OrderResponse place(PlaceOrderRequest request) {
        return placeAeron(request);
    }

    public OrderCommandReceipt placeCommand(PlaceOrderRequest request) {
        requireAeron();
        PreparedAeronOrder prepared = prepareAeronOrder(normalize(request));
        var execution = aeronOrders.placeCommand(prepared.request(), prepared.validation());
        return aeronOrders.receipt(execution);
    }

    private OrderResponse placeAeron(PlaceOrderRequest request) {
        requireAeron();
        PlaceOrderRequest normalized = normalize(request);
        PreparedAeronOrder prepared = prepareAeronOrder(normalized);
        return aeronOrders.place(prepared.request(), prepared.validation());
    }

    private PreparedAeronOrder prepareAeronOrder(PlaceOrderRequest normalized) {
        ProductLine productLine = currentProductLine();
        normalized = normalizePositionSemantics(normalized, productLine);
        InstrumentRule instrument = orderValidator.currentRule(normalized.symbol()).orElse(null);
        ValidationResult validation = instrument == null
                ? orderValidator.validate(normalized)
                : orderValidator.validate(normalized, instrument);
        if (!validation.accepted()) throw new IllegalArgumentException(validation.rejectReason());
        return new PreparedAeronOrder(normalized, validation);
    }

    public OrderBatchResponse placeBatch(BatchPlaceOrderRequest request) {
        return terminalBatchResult(placeBatchCommand(request), OrderBatchResponse.class);
    }

    public OrderCommandReceipt placeBatchCommand(BatchPlaceOrderRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("place batch request is required");
        }
        List<PlaceOrderRequest> normalized = new ArrayList<>();
        List<ValidationResult> validations = new ArrayList<>();
        requireBatchSize(request.orders().size(), 20, "orders");
        for (PlaceOrderRequest order : request.orders()) {
            PreparedAeronOrder prepared = prepareAeronOrder(normalize(order));
            normalized.add(prepared.request());
            validations.add(prepared.validation());
        }
        requireAeron();
        return aeronOrders.receipt(aeronOrders.placeBatchCommand(request.batchKey(), normalized, validations));
    }

    public TestOrderResponse test(PlaceOrderRequest request) {
        return testLocal(request);
    }

    private TestOrderResponse testLocal(PlaceOrderRequest request) {
        PlaceOrderRequest normalized = normalize(request);
        ProductLine productLine = currentProductLine();
        normalized = normalizePositionSemantics(normalized, productLine);
        ValidationResult validation = orderValidator.validate(normalized);
        if (!validation.accepted()) {
            return testRejected(validation, "ORDER_RULES");
        }
        return dryRunOpeningFunds(normalized, validation);
    }

    public AmendOrderResponse amend(AmendOrderRequest request) {
        return amendAeron(request);
    }

    public OrderCommandReceipt amendCommand(AmendOrderRequest request) {
        requireAeron();
        return aeronOrders.receipt(aeronOrders.replaceCommand(normalizeAmend(request)));
    }

    private AmendOrderResponse amendAeron(AmendOrderRequest request) {
        requireAeron();
        AmendOrderRequest normalized = normalizeAmend(request);
        return aeronOrders.replace(normalized);
    }

    public AmendOrderBatchResponse amendBatch(BatchAmendOrdersRequest request) {
        return terminalBatchResult(amendBatchCommand(request), AmendOrderBatchResponse.class);
    }

    public OrderCommandReceipt amendBatchCommand(BatchAmendOrdersRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("amend batch request is required");
        }
        requireBatchSize(request.orders().size(), 20, "orders");
        List<AmendOrderRequest> normalized = request.orders().stream().map(this::normalizeAmend).toList();
        requireAeron();
        return aeronOrders.receipt(aeronOrders.amendBatchCommand(request.batchKey(), normalized));
    }

    public OrderResponse closePosition(ClosePositionRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("close position request is required");
        }
        if (request.userId() <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
        String clientOrderId = normalizeClientOrderId(request.clientOrderId());
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
                clientOrderId,
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
                                                 ValidationResult validation) {
        OrderAeronGateway.PreflightResult result = requireAeron().preflight(request, validation);
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

    public OrderCommandReceipt cancelCommand(CancelOrderRequest request) {
        if (request == null || request.userId() <= 0 || request.orderId() <= 0) {
            throw new IllegalArgumentException("userId and orderId must be positive");
        }
        requireAeron();
        return aeronOrders.receipt(aeronOrders.cancelCommand(request.userId(), request.orderId()));
    }

    public OrderBatchResponse cancelBatch(BatchCancelOrdersRequest request) {
        return terminalBatchResult(cancelBatchCommand(request), OrderBatchResponse.class);
    }

    public OrderCommandReceipt cancelBatchCommand(BatchCancelOrdersRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("cancel batch request is required");
        }
        requireBatchSize(request.orders().size(), 50, "orders");
        request.orders().forEach(this::validateCancelRequest);
        requireAeron();
        return aeronOrders.receipt(aeronOrders.cancelBatchCommand(request.batchKey(), request.orders()));
    }

    public OrderCommandReceipt commandResult(java.util.UUID commandId) {
        if (commandId == null) {
            throw new IllegalArgumentException("commandId is required");
        }
        requireAeron();
        return aeronOrders.commandResult(commandId);
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
        List<OrderResponse> open = activeOpenOrders(currentProductLine(), request.userId(), symbol, limit);
        requireAeron();
        List<CancelOrderRequest> requests = open.stream()
                .map(order -> new CancelOrderRequest(request.userId(), order.orderId()))
                .toList();
        return orderBatchResponse(cancelOrderChunks(requests));
    }

    public OrderResponse get(long userId, long orderId) {
        return get(userId, orderId, null);
    }

    public OrderResponse get(long userId, long orderId, Long minExportSequence) {
        if (userId <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
        requireOrderId(orderId);
        OrderResponse current = requireAeron().orderState(userId, orderId);
        if (current == null) throw new IllegalStateException("order not found: " + orderId);
        return current;
    }

    public OrderResponse getByClientOrderId(long userId, String clientOrderId) {
        return getByClientOrderId(userId, clientOrderId, null);
    }

    public OrderResponse getByClientOrderId(long userId, String clientOrderId, Long minExportSequence) {
        if (userId <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
        String normalized = normalizeClientOrderId(clientOrderId);
        OrderResponse current = requireAeron().orderStateByClientOrderId(userId, normalized);
        if (current == null) throw new IllegalStateException("order not found: " + normalized);
        return current;
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
        long beforeOrderId = decodeActiveOrderCursor(cursor);
        List<OrderResponse> orders = requireAeron().openOrders(userId, normalizedSymbol, beforeOrderId, limit + 1);
        boolean hasMore = orders.size() > limit;
        if (hasMore) orders = new ArrayList<>(orders.subList(0, limit));
        String nextCursor = hasMore && !orders.isEmpty()
                ? encodeActiveOrderCursor(orders.getLast().orderId()) : null;
        return new OrderQueryResponse(orders.size(), orders, nextCursor, hasMore, "createdAt.desc", limit);
    }

    private List<OrderResponse> activeOpenOrders(ProductLine productLine, Long userId, String symbol,
                                                 int limit) {
        if (productLine != currentProductLine()) {
            throw new IllegalArgumentException("product line does not match this order core");
        }
        return requireAeron().openOrders(userId == null ? 0 : userId, symbol, 0, limit);
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
        OrderResponse selected = requireAeron().orderState(0, orderId);
        if (selected == null) throw new IllegalStateException("order not found: " + orderId);
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
        requireCurrentProductLine(resolved);
        List<OrderResponse> selected = activeOpenOrders(resolved, userId, symbol, limit);
        requireAeron();
        List<CancelOrderRequest> requests = selected.stream()
                .map(order -> new CancelOrderRequest(order.userId(), order.orderId()))
                .toList();
        List<OrderBatchItemResponse> canceled = cancelOrderChunks(requests);
        List<AdminCancelOrderResult> results = canceled.stream()
                .map(item -> {
                    OrderResponse selectedOrder = selected.get(item.index());
                    OrderResponse order = item.order() == null ? selectedOrder : item.order();
                    boolean requested = item.success() && cancelSucceeded(order.status());
                    String message = requested ? "cancel completed"
                            : item.success() ? "order is already " + order.status().name() : item.message();
                    return new AdminCancelOrderResult(order.orderId(), order.userId(), order.symbol(),
                            order.status(), requested, message, order);
                })
                .toList();
        int canceledCount = (int) results.stream().filter(AdminCancelOrderResult::cancelRequested).count();
        return new AdminCancelOrdersResponse(selected.size(), canceledCount, selected.size() - canceledCount, results);
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
        List<OrderResponse> orders = activeOpenOrders(resolvedProductLine, userId, normalizedSymbol, limit);
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
        List<CancelOrderRequest> requests = selected.stream()
                .map(order -> new CancelOrderRequest(order.userId(), order.orderId()))
                .toList();
        return (int) cancelOrderChunks(requests).stream()
                .filter(item -> item.success() && item.order() != null && cancelSucceeded(item.order().status()))
                .count();
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

    private List<OrderBatchItemResponse> cancelOrderChunks(List<CancelOrderRequest> requests) {
        if (requests.isEmpty()) {
            return List.of();
        }
        Map<Long, List<IndexedCancelRequest>> requestsByUser = new LinkedHashMap<>();
        for (int index = 0; index < requests.size(); index++) {
            CancelOrderRequest request = requests.get(index);
            validateCancelRequest(request);
            requestsByUser.computeIfAbsent(request.userId(), ignored -> new ArrayList<>())
                    .add(new IndexedCancelRequest(index, request));
        }

        List<OrderBatchItemResponse> results = new ArrayList<>(requests.size());
        for (List<IndexedCancelRequest> userRequests : requestsByUser.values()) {
            for (int start = 0; start < userRequests.size(); start += MAX_CANCEL_BATCH_SIZE) {
                List<IndexedCancelRequest> chunk = userRequests.subList(start,
                        Math.min(start + MAX_CANCEL_BATCH_SIZE, userRequests.size()));
                List<CancelOrderRequest> chunkRequests = chunk.stream()
                        .map(IndexedCancelRequest::request)
                        .toList();
                OrderBatchResponse response;
                try {
                    response = cancelBatch(new BatchCancelOrdersRequest(chunkRequests));
                } catch (RuntimeException exception) {
                    response = failedCancelBatch(chunkRequests, exception.getMessage());
                }
                for (int localIndex = 0; localIndex < chunk.size(); localIndex++) {
                    int resultIndex = localIndex;
                    IndexedCancelRequest indexed = chunk.get(localIndex);
                    OrderBatchItemResponse item = response.results().stream()
                            .filter(value -> value != null && value.index() == resultIndex)
                            .findFirst()
                            .orElseGet(() -> new OrderBatchItemResponse(resultIndex, false,
                                    "batch cancellation result is missing", null));
                    results.add(new OrderBatchItemResponse(indexed.index(), item.success(), item.message(),
                            item.order()));
                }
            }
        }
        results.sort(Comparator.comparingInt(OrderBatchItemResponse::index));
        return List.copyOf(results);
    }

    private OrderBatchResponse failedCancelBatch(List<CancelOrderRequest> requests, String message) {
        String reason = message == null || message.isBlank() ? "batch cancellation failed" : message;
        List<OrderBatchItemResponse> results = new ArrayList<>(requests.size());
        for (int index = 0; index < requests.size(); index++) {
            results.add(new OrderBatchItemResponse(index, false, reason, null));
        }
        return new OrderBatchResponse(results.size(), 0, results.size(), results);
    }

    private <T> T terminalBatchResult(OrderCommandReceipt receipt, Class<T> type) {
        if (type.isInstance(receipt.result())) {
            return type.cast(receipt.result());
        }
        if ("RESULT_UNKNOWN".equals(receipt.code())) {
            throw new com.surprising.aeron.client.ResultUnknownException(receipt.commandId(), receipt.message());
        }
        if ("NOT_ACCEPTED".equals(receipt.outcome())) {
            throw new com.surprising.aeron.client.CoreCommandOutcome.NotAcceptedException(
                    new com.surprising.aeron.client.CoreCommandOutcome.NotAccepted(
                            com.surprising.aeron.client.CoreCommandOutcome.NotAcceptedReason.valueOf(receipt.code()),
                            receipt.rawOfferResult() == null ? 0L : receipt.rawOfferResult()));
        }
        throw new IllegalStateException(receipt.code() + ": batch result is unavailable");
    }

    private void validateCancelRequest(CancelOrderRequest request) {
        if (request == null || request.userId() <= 0L || request.orderId() <= 0L) {
            throw new IllegalArgumentException("userId and orderId must be positive");
        }
    }

    private record IndexedCancelRequest(int index, CancelOrderRequest request) {
    }

    private record PreparedAeronOrder(PlaceOrderRequest request, ValidationResult validation) {
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
        String clientOrderId = normalizeClientOrderId(request.clientOrderId());
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
                request.priceTicks(), request.quantitySteps(), request.timeInForce(), request.postOnly(),
                request.clientRequestId());
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

    private static String encodeActiveOrderCursor(long orderId) {
        if (orderId <= 0) throw new IllegalArgumentException("invalid active order cursor");
        String value = ACTIVE_ORDER_CURSOR_PREFIX + orderId;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static long decodeActiveOrderCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) return 0;
        try {
            String value = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            if (!value.startsWith(ACTIVE_ORDER_CURSOR_PREFIX)) {
                throw new IllegalArgumentException("invalid active order cursor");
            }
            long orderId = Long.parseLong(value.substring(ACTIVE_ORDER_CURSOR_PREFIX.length()));
            if (orderId <= 0) throw new IllegalArgumentException("invalid active order cursor");
            return orderId;
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("invalid active order cursor", exception);
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
