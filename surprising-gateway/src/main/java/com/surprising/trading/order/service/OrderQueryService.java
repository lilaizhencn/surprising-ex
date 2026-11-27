package com.surprising.trading.order.service;

import com.surprising.trading.order.model.StableOrderIdentity;

import com.surprising.aeron.client.CoreCommandOutcome;
import com.surprising.aeron.protocol.CoreOrderStateView;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.product.api.ProductLine;
import com.surprising.realtime.api.ValkeyUserQueries;
import com.surprising.realtime.api.exception.ReadViewUnavailableException;
import com.surprising.trading.api.model.AdminTradeQueryResponse;
import com.surprising.trading.api.model.CancellationPlanResponse;
import com.surprising.trading.api.model.OrderCommandReceipt;
import com.surprising.trading.api.model.OrderPriceProtectionResponse;
import com.surprising.trading.api.model.OrderQueryResponse;
import com.surprising.trading.api.model.OrderResponse;
import com.surprising.trading.api.model.OrderStatus;
import com.surprising.trading.api.model.OrderSubmissionResponse;
import com.surprising.trading.api.model.TradeFillQueryResponse;
import com.surprising.trading.order.config.TradingOrderProperties;
import com.surprising.trading.order.model.ActiveOrderPageQuery;
import com.surprising.trading.order.model.TradeReadCursor;
import com.surprising.trading.order.repository.AeronOrderProjectionRepository;
import com.surprising.trading.order.repository.ProjectionReadResult;
import com.surprising.trading.order.repository.TradeProjectionRepository;
import com.surprising.trading.api.utils.AdminCursorPage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.databind.node.ObjectNode;

/**
 * Read/query projection service for orders, fills, metrics, and command receipts.
 */
@Service
public class OrderQueryService {

    private final TradingOrderProperties properties;
    private final OrderAeronGateway aeron;
    private final AeronOrderProjectionRepository aeronOrderProjection;
    private final TradingMetricsService tradingMetrics;
    private final TradeProjectionRepository tradeProjection;
    private final ValkeyUserQueries realtimeQueries;

    @Autowired
    public OrderQueryService(TradingOrderProperties properties,
                             OrderAeronGateway aeron,
                             AeronOrderProjectionRepository aeronOrderProjection,
                             TradingMetricsService tradingMetrics,
                             TradeProjectionRepository tradeProjection,
                             ValkeyUserQueries realtimeQueries) {
        this.properties = properties;
        this.aeron = aeron;
        this.aeronOrderProjection = aeronOrderProjection;
        this.tradingMetrics = tradingMetrics;
        this.tradeProjection = tradeProjection;
        this.realtimeQueries = realtimeQueries;
    }

    public ObjectNode tradingMetrics(int window, int limit, ProductLine requestedProduct) {
        var product = currentProductLine();
        if (requestedProduct != null && requestedProduct != product)
            throw new IllegalArgumentException("product line does not match provider");
        if (tradingMetrics == null) throw new ReadViewUnavailableException("TRADING_METRICS_UNAVAILABLE");
        return tradingMetrics.require(product, window, limit);
    }

    public TradeFillQueryResponse ownFills(long userId, String instrumentId,
                                           Long orderId, Long startTime, Long endTime,
                                           int limit, TradeReadCursor.Fill cursor) {
        if (tradeProjection == null) throw new ReadViewUnavailableException("TRADE_READ_VIEW_UNAVAILABLE");
        String instrument = instrumentId == null || instrumentId.isBlank() ? null : normalizeInstrumentId(instrumentId);
        return tradeProjection.ownFills(currentProductLine(), userId, instrument, orderId, startTime, endTime, limit, cursor);
    }

    public AdminTradeQueryResponse adminTrades(Long userId, String instrumentId,
                                               Long orderId, int limit, TradeReadCursor.Execution cursor,
                                               boolean ascending, ProductLine requestedProduct) {
        ProductLine product = currentProductLine();
        if (requestedProduct != null && requestedProduct != product)
            throw new IllegalArgumentException("product line does not match provider");
        if (tradeProjection == null) throw new ReadViewUnavailableException("TRADE_READ_VIEW_UNAVAILABLE");
        String instrument = instrumentId == null || instrumentId.isBlank() ? null : normalizeInstrumentId(instrumentId);
        return tradeProjection.search(product, userId, instrument, orderId, limit, cursor, ascending);
    }

    public OrderPriceProtectionResponse priceProtection() {
        return new OrderPriceProtectionResponse(
                currentProductLine(), properties.getRisk().getMarketMaxSlippagePpm());
    }

    public OrderCommandReceipt commandResult(long userId, UUID commandId) {
        if (commandId == null) {
            throw new IllegalArgumentException("commandId is required");
        }
        return requireProjection().commandResult(currentProductLine(), userId, commandId);
    }

    public OrderCommandReceipt commandResultFromCore(UUID commandId) {
        if (commandId == null) throw new IllegalArgumentException("commandId is required");
        ProductLine product = currentProductLine();
        try {
            CoreResponse response = requireAeron().commandResult(commandId);
            if (response.resultCode() == CoreResultCode.RESULT_UNKNOWN_OUTSIDE_RETENTION) {
                return new OrderCommandReceipt(commandId, "OUTSIDE_RETENTION",
                        CoreResultCode.RESULT_UNKNOWN_OUTSIDE_RETENTION.name(),
                        CoreResultCode.RESULT_UNKNOWN_OUTSIDE_RETENTION.name(), null, List.of(),
                        null, null);
            }
            if (response.resultCode() == CoreResultCode.MATCHING_PENDING) {
                return new OrderCommandReceipt(commandId, "MATCHING_PENDING",
                        CoreResultCode.MATCHING_PENDING.name(), "matching pending",
                        OrderService.commandResultUrl(product, commandId), List.of(),
                        null, null);
            }
            return new OrderCommandReceipt(commandId, "TERMINAL", response.resultCode().name(),
                    response.resultCode() == CoreResultCode.NONE ? "completed" : response.resultCode().name(),
                    OrderService.commandResultUrl(product, commandId), List.of(), null, null);
        } catch (com.surprising.aeron.client.ResultUnknownException exception) {
            return new OrderCommandReceipt(commandId, "RESULT_UNKNOWN", "RESULT_UNKNOWN",
                    "command result is unknown", OrderService.commandResultUrl(product, commandId), List.of(),
                    null, null);
        } catch (CoreCommandOutcome.NotAcceptedException exception) {
            CoreCommandOutcome.NotAccepted rejection = exception.rejection();
            return new OrderCommandReceipt(commandId, "NOT_ACCEPTED", rejection.reason().name(),
                    rejection.reason().name(), null, List.of(), null, rejection.rawOfferResult());
        }
    }

    public OrderSubmissionResponse submission(long userId, String clientOrderId) {
        if (userId <= 0) throw new IllegalArgumentException("userId must be positive");
        String key = normalizeClientOrderId(clientOrderId);
        var product = currentProductLine();
        return requireProjection().submission(product, userId, key, StableOrderIdentity.commandId(product, userId, key));
    }

    public CancellationPlanResponse cancellationPlan(long userId, String instrumentId) {
        if (userId <= 0) throw new IllegalArgumentException("userId must be positive");
        var product = currentProductLine();
        String instrument = instrumentId == null ? null : normalizeInstrumentId(instrumentId);
        var view = requireRealtimeQueries().require(product, userId, null);
        var ids = view.openOrders().stream().filter(v -> instrument == null || instrument.equals(v.instrumentId()))
                .map(CoreOrderStateView::orderId).distinct().sorted().toList();
        return new CancellationPlanResponse(userId, product, instrument, ids);
    }

    public OrderCommandReceipt cancellationBatch(long userId, String batchKey) {
        if (userId <= 0) throw new IllegalArgumentException("userId must be positive");
        var product = currentProductLine();
        return requireProjection().cancellationBatch(product, userId,
                OrderService.batchCommandId(product, userId, "cancel", normalizeClientOrderId(batchKey)));
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

    public List<OrderResponse> openOrdersFromCore(long userId, String instrumentId, long beforeOrderId, int limit) {
        return requireAeron().openOrders(userId, instrumentId, beforeOrderId, limit).stream()
                .map(OrderCoreMapper::toOrder)
                .toList();
    }

    public List<OrderResponse> lifecycleOpenOrders(String instrumentId, int limit) {
        if (limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("limit must be in [1, 1000]");
        }
        ProductLine productLine = properties.getProductLine();
        return requireAeron().lifecycleOpenOrders(instrumentId, limit).stream()
                .map(view -> OrderCoreMapper.requireLocalOrder(view, productLine))
                .toList();
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
                : OrderCoreMapper.toOrder(order);
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
        OrderResponse current = requireRealtimeQueries().require(currentProductLine(), userId, minExportSequence).openOrders().stream()
                .filter(v -> normalized.equals(v.clientOrderId())).map(OrderCoreMapper::toOrder).findFirst().orElse(null);
        if (current == null) current = terminalOrder(requireProjection().byClientOrderId(
                currentProductLine(), userId, normalized, minExportSequence));
        if (current == null) throw new IllegalStateException("order not found: " + normalized);
        return current;
    }

    public OrderQueryResponse openOrders(long userId, String instrumentId, ActiveOrderPageQuery query,
                                         Long minExportSequence) {
        int limit = query.limit();
        long beforeOrderId = query.beforeId();
        List<OrderResponse> orders = requireRealtimeQueries().require(currentProductLine(), userId, minExportSequence).openOrders().stream()
                .filter(v -> instrumentId == null || instrumentId.equals(v.instrumentId()))
                .filter(v -> beforeOrderId == 0 || v.orderId() < beforeOrderId)
                .sorted(Comparator.comparingLong(CoreOrderStateView::orderId).reversed())
                .limit(limit + 1).map(OrderCoreMapper::toOrder).toList();
        boolean hasMore = orders.size() > limit;
        if (hasMore) orders = new ArrayList<>(orders.subList(0, limit));
        String nextCursor = hasMore && !orders.isEmpty()
                ? AdminCursorPage.encodeIdCursor("core-open:v1:", orders.getLast().orderId()) : null;
        return new OrderQueryResponse(orders.size(), orders, nextCursor, hasMore, "createdAt.desc", limit);
    }

    List<OrderResponse> activeOpenOrders(ProductLine productLine, Long userId, String instrumentId, int limit) {
        if (productLine != currentProductLine()) {
            throw new IllegalArgumentException("product line does not match this order core");
        }
        if (userId != null) return openOrders(userId, instrumentId, new ActiveOrderPageQuery(limit, 0L), null).orders();
        return readyProjection(requireProjection().openOrders(productLine, null, instrumentId, null, limit, null));
    }

    public OrderQueryResponse historyOrders(long userId, String instrumentId, int limit, Long minimumOrderId,
                                            Long startTimeMillis, Long endTimeMillis) {
        return historyOrders(userId, instrumentId, limit, minimumOrderId, startTimeMillis, endTimeMillis, null, null, null);
    }

    public OrderQueryResponse historyOrders(long userId, String instrumentId, int limit, Long minimumOrderId,
                                            Long startTimeMillis, Long endTimeMillis, String cursor,
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
        OrderStatus historyStatus = status == null || status.isBlank() ? null : OrderStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
        if (historyStatus != null && !Set.of(OrderStatus.FILLED, OrderStatus.CANCELED, OrderStatus.REJECTED).contains(historyStatus))
            throw new IllegalArgumentException("history status must be FILLED, CANCELED or REJECTED");
        String normalizedInstrumentId = instrumentId == null || instrumentId.isBlank() ? null : normalizeInstrumentId(instrumentId);
        return toQueryResponse(requireProjection().historyOrders(currentProductLine(), userId, normalizedInstrumentId,
                limit, minimumOrderId, startTimeMillis, endTimeMillis, cursor, minExportSequence, historyStatus),
                "updatedAt.desc", limit);
    }

    public OrderQueryResponse adminOrders(Long userId, String instrumentId, String status, Long orderId, int limit) {
        return adminOrders(userId, instrumentId, status, orderId, limit, null, null, null);
    }

    public OrderQueryResponse adminOrders(Long userId, String instrumentId, String status, Long orderId,
                                          int limit, String cursor, String sort) {
        return adminOrders(userId, instrumentId, status, orderId, limit, cursor, sort, null);
    }

    public OrderQueryResponse adminOrders(Long userId, String instrumentId, String status, Long orderId,
                                          int limit, String cursor, String sort, ProductLine productLine) {
        if (userId != null && userId <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
        if (orderId != null && orderId <= 0) {
            throw new IllegalArgumentException("orderId must be positive");
        }
        if (limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("limit must be in [1, 1000]");
        }
        String normalizedInstrumentId = instrumentId == null || instrumentId.isBlank() ? null : normalizeInstrumentId(instrumentId);
        OrderStatus normalizedStatus = status == null || status.isBlank()
                ? null
                : OrderStatus.valueOf(status.trim().toUpperCase());
        ProductLine resolvedProductLine = productLine == null ? currentProductLine() : productLine;
        boolean ascending = "createdAt.asc".equalsIgnoreCase(sort);
        return toQueryResponse(requireProjection().search(resolvedProductLine, userId, normalizedInstrumentId,
                normalizedStatus, orderId, cursor, ascending, limit, null),
                ascending ? "createdAt.asc" : "createdAt.desc", limit);
    }

    OrderResponse findOrderForAdminCancel(ProductLine resolved, long orderId) {
        return readyProjection(requireProjection().byOrder(resolved, null, orderId, null))
                .stream().findFirst().orElse(null);
    }

    private AeronOrderProjectionRepository requireProjection() {
        if (aeronOrderProjection == null) {
            throw new ReadViewUnavailableException("ORDER_READ_VIEW_UNAVAILABLE");
        }
        return aeronOrderProjection;
    }

    private ValkeyUserQueries requireRealtimeQueries() {
        if (realtimeQueries == null) throw new ReadViewUnavailableException("ORDER_READ_VIEW_UNAVAILABLE");
        return realtimeQueries;
    }

    private OrderResponse terminalOrder(ProjectionReadResult result) {
        OrderResponse order = readyProjection(result).stream().findFirst().orElse(null);
        return order != null && Set.of(OrderStatus.FILLED, OrderStatus.CANCELED, OrderStatus.REJECTED).contains(order.status())
                ? order : null;
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

    private OrderAeronGateway requireAeron() {
        if (aeron == null) {
            throw new IllegalStateException("aeron order gateway is not configured");
        }
        return aeron;
    }

    public ProductLine currentProductLine() {
        return properties.getProductLine();
    }

    private static String normalizeInstrumentId(String instrumentId) {
        if (instrumentId == null || instrumentId.isBlank()) {
            throw new IllegalArgumentException("instrumentId must not be blank");
        }
        String trimmed = instrumentId.trim();
        for (int index = 0; index < trimmed.length(); index++) {
            if (!Character.isDigit(trimmed.charAt(index))) {
                throw new IllegalArgumentException("instrumentId must be numeric");
            }
        }
        return trimmed;
    }

    private static String normalizeClientOrderId(String clientOrderId) {
        if (clientOrderId == null || clientOrderId.isBlank()) {
            throw new IllegalArgumentException("clientOrderId must not be blank");
        }
        String trimmed = clientOrderId.trim();
        if (trimmed.length() > 64) {
            throw new IllegalArgumentException("clientOrderId must be <= 64 characters");
        }
        return trimmed;
    }

    private static void requireOrderId(long orderId) {
        if (orderId <= 0L) {
            throw new IllegalArgumentException("orderId must be positive");
        }
    }

    private static Instant epochMillis(Long value, String field) {
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
}
