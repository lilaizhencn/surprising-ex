package com.surprising.trading.order.service;

import com.surprising.realtime.api.exception.ReadViewUnavailableException;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.OrderCommandReceipt;
import com.surprising.trading.api.model.AmendOrderBatchResponse;
import com.surprising.trading.api.model.AmendOrderRequest;
import com.surprising.trading.api.model.CancellationPlanResponse;
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
import com.surprising.trading.api.model.PositionSide;
import com.surprising.trading.api.model.TestOrderResponse;
import com.surprising.trading.api.model.TimeInForce;
import com.surprising.trading.order.config.TradingOrderProperties;
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
import java.util.concurrent.CompletionStage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class OrderService {
    private final com.surprising.trading.order.service.TradingMetricsService tradingMetrics;

    public tools.jackson.databind.node.ObjectNode tradingMetrics(int window,int limit,ProductLine requestedProduct) {
        var product=currentProductLine();
        if(requestedProduct!=null&&requestedProduct!=product)throw new IllegalArgumentException("product line does not match provider");
        if(tradingMetrics==null)throw new ReadViewUnavailableException("TRADING_METRICS_UNAVAILABLE");
        return tradingMetrics.require(product,window,limit);
    }
    private final com.surprising.trading.order.repository.TradeProjectionRepository tradeProjection;

    public com.surprising.trading.api.model.TradeFillQueryResponse ownFills(long userId, String instrumentId,
            Long orderId, Long startTime, Long endTime, int limit, String cursor) {
        if (tradeProjection == null) throw new ReadViewUnavailableException("TRADE_READ_VIEW_UNAVAILABLE");
        String instrument = instrumentId == null || instrumentId.isBlank() ? null : normalizeSymbol(instrumentId);
        return tradeProjection.ownFills(currentProductLine(), userId, instrument, orderId, startTime, endTime, limit, cursor);
    }

    public com.surprising.trading.api.model.AdminTradeQueryResponse adminTrades(Long userId, String instrumentId,
            Long orderId, int limit, String cursor, String sort, ProductLine requestedProduct) {
        ProductLine product = currentProductLine();
        if (requestedProduct != null && requestedProduct != product)
            throw new IllegalArgumentException("product line does not match provider");
        if (tradeProjection == null) throw new ReadViewUnavailableException("TRADE_READ_VIEW_UNAVAILABLE");
        String instrument = instrumentId == null || instrumentId.isBlank() ? null : normalizeSymbol(instrumentId);
        return tradeProjection.search(product, userId, instrument, orderId, limit, cursor, sort);
    }
    private final com.surprising.realtime.api.ValkeyUserQueries realtimeQueries;

    private static final int MAX_CANCEL_BATCH_SIZE = 50;
    private static final String ACTIVE_ORDER_CURSOR_PREFIX = "core-open:v1:";

    private final TradingOrderProperties properties;
    private final OrderValidator orderValidator;
    private final com.surprising.trading.matching.service.MatchingMarketDataService bboOrderBook;
    private final OrderPlacementStateService placementStateService;
    private final AeronOrderCommandService aeronOrders;
    private final AeronOrderProjectionRepository aeronOrderProjection;

    @Autowired
    public OrderService(TradingOrderProperties properties, OrderValidator orderValidator,
                        OrderPlacementStateService placementStateService, AeronOrderCommandService aeronOrders,
                        AeronOrderProjectionRepository aeronOrderProjection, com.surprising.trading.matching.service.MatchingMarketDataService bboOrderBook,
                        com.surprising.trading.order.service.TradingMetricsService tradingMetrics,
                        com.surprising.trading.order.repository.TradeProjectionRepository tradeProjection,
                        com.surprising.realtime.api.ValkeyUserQueries realtimeQueries) {
        this.tradingMetrics = tradingMetrics;
        this.tradeProjection = tradeProjection;
        this.realtimeQueries = realtimeQueries;
        this.bboOrderBook = bboOrderBook;
        this.properties = properties;
        this.orderValidator = orderValidator;
        this.placementStateService = placementStateService;
        this.aeronOrders = aeronOrders;
        this.aeronOrderProjection = aeronOrderProjection;
    }



    public com.surprising.trading.api.model.OrderPriceProtectionResponse priceProtection() {
        return new com.surprising.trading.api.model.OrderPriceProtectionResponse(
                currentProductLine(), properties.getRisk().getMarketMaxSlippagePpm());
    }

    public OrderResponse place(PlaceOrderRequest request) {
        requireAeron();
        return aeronOrders.place(prepareOrder(request));
    }

    OrderResponse placeAlgoChild(PlaceOrderRequest request) {
        requireAeron();
        return aeronOrders.place(prepareOrder(request, true));
    }

    public OrderCommandReceipt placeCommand(PlaceOrderRequest request) {
        requireAeron();
        PlaceOrderRequest prepared = prepareOrder(request);
        var execution = aeronOrders.placeCommand(prepared);
        return aeronOrders.receipt(execution);
    }

    public CompletionStage<OrderCommandReceipt> placeCommandAsync(PlaceOrderRequest request) {
        requireAeron();
        PlaceOrderRequest prepared = prepareOrder(request);
        return aeronOrders.placeCommandAsync(prepared).thenApply(aeronOrders::receipt);
    }

    private PlaceOrderRequest prepareOrder(PlaceOrderRequest request) {
        return prepareOrder(request, false);
    }

    private PlaceOrderRequest prepareOrder(PlaceOrderRequest request, boolean algoChild) {
        PlaceOrderRequest normalized = normalize(request);
        if (normalized.clientOrderId().startsWith("algo-") != algoChild)
            throw new IllegalArgumentException("clientOrderId uses the reserved algo child namespace");
        InstrumentRule instrument = orderValidator.currentRule(normalized.instrumentId()).orElse(null);
        ValidationResult validation = instrument == null
                ? orderValidator.validate(normalized)
                : orderValidator.validate(normalized, instrument);
        if (!validation.accepted()) throw new IllegalArgumentException(validation.rejectReason());
        return normalized;
    }

    public OrderBatchResponse placeBatch(BatchPlaceOrderRequest request) {
        return terminalBatchResult(placeBatchCommand(request), OrderBatchResponse.class);
    }

    public OrderCommandReceipt placeBatchCommand(BatchPlaceOrderRequest request) {
        List<PlaceOrderRequest> orders = prepareBatchOrders(request);
        requireAeron();
        return aeronOrders.receipt(aeronOrders.placeBatchCommand(request.batchKey(), orders));
    }

    public CompletionStage<OrderCommandReceipt> placeBatchCommandAsync(BatchPlaceOrderRequest request) {
        List<PlaceOrderRequest> orders = prepareBatchOrders(request);
        requireAeron();
        return aeronOrders.placeBatchCommandAsync(request.batchKey(), orders).thenApply(aeronOrders::receipt);
    }

    private List<PlaceOrderRequest> prepareBatchOrders(BatchPlaceOrderRequest request) {
        if (request == null) throw new IllegalArgumentException("place batch request is required");
        requireBatchSize(request.orders().size(), 20, "orders");
        List<PlaceOrderRequest> orders = new ArrayList<>(request.orders().size());
        for (PlaceOrderRequest order : request.orders()) orders.add(prepareOrder(order));
        return orders;
    }

    public TestOrderResponse test(PlaceOrderRequest request) {
        PlaceOrderRequest normalized = normalize(request);
        ValidationResult validation = orderValidator.validate(normalized);
        if (!validation.accepted()) {
            return testRejected(validation, "ORDER_RULES");
        }
        return dryRunOpeningFunds(normalized, validation);
    }

    public AmendOrderResponse amend(AmendOrderRequest request) {
        requireAeron();
        return aeronOrders.replace(normalizeAmend(request));
    }

    public OrderCommandReceipt amendCommand(AmendOrderRequest request) {
        requireAeron();
        return aeronOrders.receipt(aeronOrders.replaceCommand(normalizeAmend(request)));
    }

    public CompletionStage<OrderCommandReceipt> amendCommandAsync(AmendOrderRequest request) {
        requireAeron();
        return aeronOrders.replaceCommandAsync(normalizeAmend(request)).thenApply(aeronOrders::receipt);
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

    public CompletionStage<OrderCommandReceipt> amendBatchCommandAsync(BatchAmendOrdersRequest request) {
        if (request == null) throw new IllegalArgumentException("amend batch request is required");
        requireBatchSize(request.orders().size(), 20, "orders");
        List<AmendOrderRequest> normalized = request.orders().stream().map(this::normalizeAmend).toList();
        requireAeron();
        return aeronOrders.amendBatchCommandAsync(request.batchKey(), normalized).thenApply(aeronOrders::receipt);
    }

    public OrderResponse closePosition(ClosePositionRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("close position request is required");
        }
        if (request.userId() <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
        String clientOrderId = normalizeClientOrderId(request.clientOrderId());
        String instrumentId = normalizeSymbol(request.instrumentId());
        MarginMode marginMode = MarginMode.defaultIfNull(request.marginMode());
        PositionSide positionSide = PositionSide.defaultIfNull(request.positionSide());
        ProductLine productLine = currentProductLine();
        long quantity = placementStateService.requireClosePosition(
                productLine, request.userId(), instrumentId, marginMode, positionSide);
        if (quantity == 0L) {
            throw new IllegalStateException("open position not found");
        }
        OrderSide closeSide = quantity > 0L ? OrderSide.SELL : OrderSide.BUY;
        PlaceOrderRequest closeOrder = new PlaceOrderRequest(
                request.userId(),
                clientOrderId,
                instrumentId,
                closeSide,
                OrderType.MARKET,
                TimeInForce.IOC,
                0L,
                Math.absExact(quantity),
                marginMode,
                positionSide,
                true,
                false);
        return place(closeOrder);
    }

    private TestOrderResponse dryRunOpeningFunds(PlaceOrderRequest request,
                                                 ValidationResult validation) {
        OrderAeronGateway.PreflightResult result = requireAeron().preflight(request);
        if (!result.accepted()) {
            return new TestOrderResponse(false, result.resultCode().name(), validation.instrumentChangeId(),
                    "CORE_PREFLIGHT", currentProductLine().accountTypeCode(), null, 0L);
        }
        return new TestOrderResponse(true, null, validation.instrumentChangeId(), "ACCEPTED",
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

    public CompletionStage<OrderCommandReceipt> cancelCommandAsync(CancelOrderRequest request) {
        if (request == null || request.userId() <= 0 || request.orderId() <= 0) {
            throw new IllegalArgumentException("userId and orderId must be positive");
        }
        requireAeron();
        return aeronOrders.cancelCommandAsync(request.userId(), request.orderId()).thenApply(aeronOrders::receipt);
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

    public CompletionStage<OrderCommandReceipt> cancelBatchCommandAsync(BatchCancelOrdersRequest request) {
        if (request == null) throw new IllegalArgumentException("cancel batch request is required");
        requireBatchSize(request.orders().size(), 50, "orders");
        request.orders().forEach(this::validateCancelRequest);
        requireAeron();
        return aeronOrders.cancelBatchCommandAsync(request.batchKey(), request.orders())
                .thenApply(aeronOrders::receipt);
    }

    public OrderCommandReceipt commandResult(long userId, java.util.UUID commandId) {
        if (commandId == null) {
            throw new IllegalArgumentException("commandId is required");
        }
        return requireProjection().commandResult(currentProductLine(), userId, commandId);
    }

    public com.surprising.trading.api.model.OrderSubmissionResponse submission(long userId, String clientOrderId) {
        if (userId <= 0) throw new IllegalArgumentException("userId must be positive");
        String key = normalizeClientOrderId(clientOrderId);
        var product = currentProductLine();
        return requireProjection().submission(product, userId, key, StableOrderIdentity.commandId(product, userId, key));
    }

    public CancellationPlanResponse cancellationPlan(long userId, String instrumentId) {
        if (userId <= 0) throw new IllegalArgumentException("userId must be positive");
        var product = currentProductLine();
        String instrument = instrumentId == null ? null : normalizeSymbol(instrumentId);
        var view = requireRealtimeQueries().require(product, userId, null);
        var ids = view.openOrders().stream().filter(v -> instrument == null || instrument.equals(v.instrumentId()))
                .map(com.surprising.aeron.protocol.CoreOrderStateView::orderId).distinct().sorted().toList();
        return new CancellationPlanResponse(userId, product, instrument, ids);
    }

    public OrderCommandReceipt cancellationBatch(long userId, String batchKey) {
        if (userId <= 0) throw new IllegalArgumentException("userId must be positive");
        var product = currentProductLine();
        return requireProjection().cancellationBatch(product, userId,
                AeronOrderCommandService.batchCommandId(product, userId, "cancel", normalizeClientOrderId(batchKey)));
    }

    public OrderCommandReceipt amendment(long userId, String clientRequestId) {
        if (userId <= 0) throw new IllegalArgumentException("userId must be positive");
        var product = currentProductLine();
        return requireProjection().amendment(product, userId,
                StableOrderIdentity.replacementCommandId(product, userId, normalizeClientOrderId(clientRequestId)));
    }

    public OrderCommandReceipt cancellation(long userId, long orderId) {
        if (userId <= 0) throw new IllegalArgumentException("userId must be positive");
        requireOrderId(orderId);
        var product = currentProductLine();
        return requireProjection().cancellation(product, userId, orderId,
                StableOrderIdentity.commandId(product, userId, "cancel:" + orderId));
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
        String instrumentId = request.instrumentId() == null || request.instrumentId().isBlank()
                ? null : normalizeSymbol(request.instrumentId());
        List<OrderResponse> open = activeOpenOrders(currentProductLine(), request.userId(), instrumentId, limit);
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
        var order = requireRealtimeQueries().order(currentProductLine(), userId, orderId, minExportSequence);
        OrderResponse current = order == null
                ? terminalOrder(requireProjection().byOrder(currentProductLine(), userId, orderId, minExportSequence))
                : AeronOrderCommandService.toOrder(order);
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
        OrderResponse current = requireRealtimeQueries().require(currentProductLine(),userId,minExportSequence).openOrders().stream()
                .filter(v->normalized.equals(v.clientOrderId())).map(AeronOrderCommandService::toOrder).findFirst().orElse(null);
        if (current == null) current = terminalOrder(requireProjection().byClientOrderId(
                currentProductLine(), userId, normalized, minExportSequence));
        if (current == null) throw new IllegalStateException("order not found: " + normalized);
        return current;
    }

    public OrderQueryResponse openOrders(long userId, String instrumentId, int limit) {
        return openOrders(userId, instrumentId, limit, null);
    }

    public OrderQueryResponse openOrders(long userId, String instrumentId, int limit, String cursor) {
        return openOrders(userId, instrumentId, limit, cursor, null);
    }

    public OrderQueryResponse openOrders(long userId, String instrumentId, int limit, String cursor,
                                         Long minExportSequence) {
        if (userId <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
        if (limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("limit must be in [1, 1000]");
        }
        String normalizedSymbol = instrumentId == null || instrumentId.isBlank() ? null : normalizeSymbol(instrumentId);
        long beforeOrderId = decodeActiveOrderCursor(cursor);
        List<OrderResponse> orders = requireRealtimeQueries().require(currentProductLine(),userId,minExportSequence).openOrders().stream()
                .filter(v->normalizedSymbol==null || normalizedSymbol.equals(v.instrumentId()))
                .filter(v->beforeOrderId==0 || v.orderId()<beforeOrderId)
                .sorted(java.util.Comparator.comparingLong(com.surprising.aeron.protocol.CoreOrderStateView::orderId).reversed())
                .limit(limit+1).map(AeronOrderCommandService::toOrder).toList();
        boolean hasMore = orders.size() > limit;
        if (hasMore) orders = new ArrayList<>(orders.subList(0, limit));
        String nextCursor = hasMore && !orders.isEmpty()
                ? encodeActiveOrderCursor(orders.getLast().orderId()) : null;
        return new OrderQueryResponse(orders.size(), orders, nextCursor, hasMore, "createdAt.desc", limit);
    }

    private List<OrderResponse> activeOpenOrders(ProductLine productLine, Long userId, String instrumentId,
                                                 int limit) {
        if (productLine != currentProductLine()) {
            throw new IllegalArgumentException("product line does not match this order core");
        }
        if (userId != null) return openOrders(userId, instrumentId, limit).orders();
        return readyProjection(requireProjection().openOrders(productLine, null, instrumentId, null, limit, null));
    }

    public OrderQueryResponse historyOrders(long userId,
                                            String instrumentId,
                                            int limit,
                                            Long minimumOrderId,
                                            Long startTimeMillis,
                                            Long endTimeMillis) {
        return historyOrders(userId, instrumentId, limit, minimumOrderId, startTimeMillis, endTimeMillis, null, null, null);
    }

    public OrderQueryResponse historyOrders(long userId,
                                            String instrumentId,
                                            int limit,
                                            Long minimumOrderId,
                                            Long startTimeMillis,
                                            Long endTimeMillis,
                                            String cursor,
                                            Long minExportSequence, String status) {
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
        OrderStatus historyStatus = status == null || status.isBlank() ? null : OrderStatus.valueOf(status.trim().toUpperCase(java.util.Locale.ROOT));
        if (historyStatus != null && !java.util.Set.of(OrderStatus.FILLED, OrderStatus.CANCELED, OrderStatus.REJECTED).contains(historyStatus))
            throw new IllegalArgumentException("history status must be FILLED, CANCELED or REJECTED");
        String normalizedSymbol = instrumentId == null || instrumentId.isBlank() ? null : normalizeSymbol(instrumentId);
        return toQueryResponse(requireProjection().historyOrders(currentProductLine(), userId, normalizedSymbol,
                limit, minimumOrderId, startTimeMillis, endTimeMillis, cursor, minExportSequence, historyStatus),
                "updatedAt.desc", limit);
    }

    public OrderQueryResponse adminOrders(Long userId, String instrumentId, String status, Long orderId, int limit) {
        return adminOrders(userId, instrumentId, status, orderId, limit, null, null, null);
    }

    public OrderQueryResponse adminOrders(Long userId,
                                          String instrumentId,
                                          String status,
                                          Long orderId,
                                          int limit,
                                          String cursor,
                                          String sort) {
        return adminOrders(userId, instrumentId, status, orderId, limit, cursor, sort, null);
    }

    public OrderQueryResponse adminOrders(Long userId,
                                          String instrumentId,
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
        String normalizedSymbol = instrumentId == null || instrumentId.isBlank() ? null : normalizeSymbol(instrumentId);
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
        OrderResponse selected = readyProjection(requireProjection().byOrder(resolved, null, orderId, null))
                .stream().findFirst().orElse(null);
        if (selected == null) throw new IllegalStateException("order not found: " + orderId);
        requireAeron();
        OrderResponse canceled = aeronOrders.cancel(selected.userId(), selected.orderId());
        boolean requested = cancelSucceeded(canceled.status());
        return new AdminCancelOrderResult(canceled.orderId(), canceled.userId(), canceled.instrumentId(),
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
        String instrumentId = request == null || request.instrumentId() == null || request.instrumentId().isBlank()
                ? null
                : normalizeSymbol(request.instrumentId());
        int limit = request == null || request.limit() == null ? 100 : request.limit();
        if (limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("limit must be in [1, 1000]");
        }
        String reason = adminCancelReason(request == null ? null : request.reason());
        ProductLine resolved = productLine == null ? currentProductLine() : productLine;
        requireCurrentProductLine(resolved);
        List<OrderResponse> selected = activeOpenOrders(resolved, userId, instrumentId, limit);
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
                    return new AdminCancelOrderResult(order.orderId(), order.userId(), order.instrumentId(),
                            order.status(), requested, message, order);
                })
                .toList();
        int canceledCount = (int) results.stream().filter(AdminCancelOrderResult::cancelRequested).count();
        return new AdminCancelOrdersResponse(selected.size(), canceledCount, selected.size() - canceledCount, results);
    }

    public AdminCancelOrdersPreviewResponse adminCancelPreview(Long userId, String instrumentId, int limit) {
        return adminCancelPreview(userId, instrumentId, limit, null);
    }

    public AdminCancelOrdersPreviewResponse adminCancelPreview(
            Long userId, String instrumentId, int limit, ProductLine productLine) {
        if (userId != null && userId <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
        if (limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("limit must be in [1, 1000]");
        }
        String normalizedSymbol = instrumentId == null || instrumentId.isBlank() ? null : normalizeSymbol(instrumentId);
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
        String instrumentId = normalizeSymbol(request.instrumentId());
        return adminCancelOrders(new AdminBatchCancelOrdersRequest(null, instrumentId, request.limit(), request.reason()),
                productLine);
    }

    /**
     * 到期生命周期只发起撤单，最终状态仍由撮合结果驱动。
     */
    public int requestLifecycleCancellation(String instrumentId, int limit) {
        String normalizedSymbol = normalizeSymbol(instrumentId);
        List<OrderResponse> selected = requireAeron().lifecycleOpenOrders(normalizedSymbol, limit);
        List<CancelOrderRequest> requests = selected.stream()
                .map(order -> new CancelOrderRequest(order.userId(), order.orderId()))
                .toList();
        return (int) cancelOrderChunks(requests).stream()
                .filter(item -> item.success() && item.order() != null && cancelSucceeded(item.order().status()))
                .count();
    }

    public boolean hasLifecycleActiveOrders(String instrumentId) {
        return !requireAeron().lifecycleOpenOrders(normalizeSymbol(instrumentId), 1).isEmpty();
    }

    private AeronOrderProjectionRepository requireProjection() {
        if (aeronOrderProjection == null) {
            throw new IllegalStateException("order projection repository is required");
        }
        return aeronOrderProjection;
    }

    private com.surprising.realtime.api.ValkeyUserQueries requireRealtimeQueries() {
        if (realtimeQueries == null) throw new ReadViewUnavailableException("READ_VIEW_UNAVAILABLE");
        return realtimeQueries;
    }

    private OrderResponse terminalOrder(ProjectionReadResult result) {
        return readyProjection(result).stream().filter(order -> switch (order.status()) {
            case FILLED, CANCELED, REJECTED -> true;
            default -> false;
        }).findFirst().orElse(null);
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
        return new TestOrderResponse(false, validation.rejectReason(), validation.instrumentChangeId(),
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
        String instrumentId = normalizeSymbol(request.instrumentId());
        long priceTicks = resolveLimitPrice(request, instrumentId);
        PositionSide positionSide = PositionSide.defaultIfNull(request.positionSide());
        if (currentProductLine() == ProductLine.SPOT && positionSide.isHedgeSide()) {
            throw new IllegalArgumentException("现货订单不支持 LONG/SHORT 仓位方向");
        }
        boolean reduceOnly = request.reduceOnly()
                || positionSide.isHedgeSide() && positionSide.isClosingSide(request.side());
        if (request.bboPriceMode() == null && clientOrderId.equals(request.clientOrderId())
                && instrumentId.equals(request.instrumentId()) && priceTicks == request.priceTicks()
                && reduceOnly == request.reduceOnly()) return request;
        return new PlaceOrderRequest(
                request.userId(),
                clientOrderId,
                instrumentId,
                request.side(),
                request.orderType(),
                request.timeInForce(),
                priceTicks,
                request.quantitySteps(),
                MarginMode.defaultIfNull(request.marginMode()),
                positionSide,
                reduceOnly,
                request.postOnly());
    }

    private long resolveLimitPrice(PlaceOrderRequest request, String instrumentId) {
        var mode = request.bboPriceMode();
        if (mode == null) return request.priceTicks();
        if (request.orderType() != OrderType.LIMIT || request.priceTicks() != 0) {
            throw new IllegalArgumentException("BBO requires a LIMIT order with priceTicks=0");
        }
        if (bboOrderBook == null) throw new IllegalStateException("BBO order book is unavailable");
        boolean buyBook = (request.side() == OrderSide.BUY) == mode.sameSide();
        var bookSide = buyBook ? com.surprising.aeron.protocol.CoreOrderSide.BUY
                : com.surprising.aeron.protocol.CoreOrderSide.SELL;
        var book = bboOrderBook.orderBookSnapshot(instrumentId, mode.depth());
        // Rank the realtime reference before validation; Core still decides order acceptance.
        var prices = (buyBook ? book.bids() : book.asks()).stream()
                .filter(level -> level.quantitySteps() > 0 && level.priceTicks() > 0)
                .map(com.surprising.trading.api.model.OrderBookLevel::priceTicks).distinct()
                .sorted(buyBook ? Comparator.reverseOrder() : Comparator.naturalOrder()).toList();
        if (prices.size() < mode.depth()) {
            throw new IllegalArgumentException("BBO_DEPTH_UNAVAILABLE: requested " + mode.depth()
                    + " levels on " + bookSide + ", available " + prices.size());
        }
        return prices.get(mode.depth() - 1);
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

    private String normalizeSymbol(String instrumentId) {
        if (instrumentId == null || instrumentId.isBlank()) {
            throw new IllegalArgumentException("instrumentId is required");
        }
        String normalized = instrumentId.trim().toUpperCase();
        if (!com.surprising.product.api.utils.InstrumentIds.valid(normalized)) {
            throw new IllegalArgumentException("invalid instrumentId: " + instrumentId);
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

}
