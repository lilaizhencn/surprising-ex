package com.surprising.trading.trigger.service;

import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreOrderType;
import com.surprising.aeron.protocol.CoreTimeInForce;
import com.surprising.aeron.protocol.CoreTriggerCondition;
import com.surprising.aeron.protocol.CoreTriggerOrderStateView;
import com.surprising.aeron.protocol.CoreTriggerOrderStatus;
import com.surprising.aeron.protocol.CoreTriggerOrderType;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.TraceContext;
import com.surprising.trading.api.model.AdminTriggerOrderTimelineEvent;
import com.surprising.trading.api.model.AdminTriggerOrderTimelineResponse;
import com.surprising.trading.api.model.BatchCancelTriggerOrdersRequest;
import com.surprising.trading.api.model.BatchPlaceTriggerOrderRequest;
import com.surprising.trading.api.model.CancelOpenTriggerOrdersRequest;
import com.surprising.trading.api.model.CancelTriggerOrderRequest;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.OrderType;
import com.surprising.trading.api.model.PlaceTriggerOrderRequest;
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
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class TriggerOrderService {

    private static final Logger log = LoggerFactory.getLogger(TriggerOrderService.class);
    private static final long MIN_TRAILING_CALLBACK_RATE_PPM = 1_000L;
    private static final long MAX_TRAILING_CALLBACK_RATE_PPM = 100_000L;

    private final TriggerProperties properties;
    private final TriggerOrderAeronGateway aeronGateway;
    private final AeronTriggerOrderIdGenerator aeronOrderIds;

    public TriggerOrderService(TriggerProperties properties,
                               TriggerOrderAeronGateway aeronGateway,
                               AeronTriggerOrderIdGenerator aeronOrderIds) {
        this.properties = properties;
        this.aeronGateway = aeronGateway;
        this.aeronOrderIds = aeronOrderIds;
    }

    public TriggerOrderResponse place(PlaceTriggerOrderRequest request) {
        PlaceTriggerOrderRequest normalized = normalize(request);
        long triggerOrderId = aeronOrderIds.next();
        Instant now = Instant.now();
        CoreTriggerOrderStateView view = new CoreTriggerOrderStateView(
                triggerOrderId,
                currentProductLine(),
                normalized.userId(),
                emptyToNull(normalized.clientTriggerOrderId()),
                emptyToNull(normalized.ocoGroupId()),
                normalized.symbol(),
                CoreOrderSide.valueOf(normalized.side().name()),
                CoreTriggerOrderType.valueOf(normalized.triggerType().name()),
                CoreTriggerCondition.valueOf(triggerCondition(normalized.side(), normalized.triggerType()).name()),
                normalized.triggerPriceTicks(),
                normalized.activationPriceTicks() == null ? 0 : normalized.activationPriceTicks(),
                normalized.callbackRatePpm() == null ? 0 : normalized.callbackRatePpm(),
                0,
                0,
                0,
                CoreOrderType.valueOf(normalized.orderType().name()),
                CoreTimeInForce.valueOf(normalized.timeInForce().name()),
                normalized.priceTicks(),
                normalized.quantitySteps(),
                com.surprising.aeron.protocol.CoreMarginMode.valueOf(normalized.marginMode().name()),
                com.surprising.aeron.protocol.CorePositionSide.valueOf(normalized.positionSide().name()),
                CoreTriggerOrderStatus.PENDING,
                0,
                0,
                0,
                "",
                TraceContext.currentOrCreate(),
                normalized.expiresAt() == null ? 0 : normalized.expiresAt().toEpochMilli(),
                0,
                now.toEpochMilli(),
                now.toEpochMilli(),
                1);
        String idempotencyKey = normalized.clientTriggerOrderId() == null
                ? Long.toString(triggerOrderId)
                : normalized.clientTriggerOrderId();
        UUID commandId = UUID.nameUUIDFromBytes(("TRIGGER_PLACE:" + normalized.userId() + ':' + idempotencyKey)
                .getBytes(StandardCharsets.UTF_8));
        aeronGateway.command(CoreMessageType.PLACE_TRIGGER_ORDER, commandId, normalized.userId(),
                com.surprising.aeron.protocol.CoreTriggerOrderCodec.encodeState(view));
        return TriggerOrderAeronGateway.response(view);
    }

    public TriggerOrderBatchResponse placeBatch(BatchPlaceTriggerOrderRequest request) {
        List<PlaceTriggerOrderRequest> orders = request == null ? List.of() : request.orders();
        requireBatchSize(orders.size(), 20, "orders");
        if (request != null && Boolean.TRUE.equals(request.atomic())) {
            return placeAtomicBatch(orders);
        }
        List<TriggerOrderBatchItemResponse> results = new ArrayList<>();
        for (int i = 0; i < orders.size(); i++) {
            try {
                results.add(new TriggerOrderBatchItemResponse(i, true, "completed", place(orders.get(i))));
            } catch (IllegalArgumentException | IllegalStateException ex) {
                results.add(new TriggerOrderBatchItemResponse(i, false, ex.getMessage(), null));
            }
        }
        return triggerBatchResponse(results);
    }

    private TriggerOrderBatchResponse placeAtomicBatch(List<PlaceTriggerOrderRequest> orders) {
        List<TriggerOrderBatchItemResponse> rejected = new ArrayList<>();
        String message = "atomic trigger batches are not supported by the Aeron Core command protocol";
        for (int i = 0; i < orders.size(); i++) {
            rejected.add(new TriggerOrderBatchItemResponse(i, false, message, null));
        }
        return triggerBatchResponse(rejected);
    }

    public TriggerOrderResponse get(long triggerOrderId) {
        return get(triggerOrderId, currentProductLine());
    }

    public TriggerOrderResponse get(long userId, long triggerOrderId) {
        if (userId <= 0 || triggerOrderId <= 0) {
            throw new IllegalArgumentException("userId and triggerOrderId must be positive");
        }
        CoreTriggerOrderStateView value = aeronGateway.get(userId, triggerOrderId);
        if (value == null || value.userId() != userId || value.productLine() != currentProductLine()) {
            throw new IllegalStateException("trigger order not found: " + triggerOrderId);
        }
        return TriggerOrderAeronGateway.response(value);
    }

    public TriggerOrderResponse get(long triggerOrderId, ProductLine productLine) {
        if (triggerOrderId <= 0) {
            throw new IllegalArgumentException("triggerOrderId must be positive");
        }
        ProductLine effective = productLine == null ? currentProductLine() : productLine;
        CoreTriggerOrderStateView value = aeronGateway.get(0, triggerOrderId);
        if (value == null || value.productLine() != effective) {
            throw new IllegalStateException("trigger order not found: " + triggerOrderId);
        }
        return TriggerOrderAeronGateway.response(value);
    }

    public TriggerOrderResponse cancel(CancelTriggerOrderRequest request) {
        if (request == null || request.userId() <= 0 || request.triggerOrderId() <= 0) {
            throw new IllegalArgumentException("userId and triggerOrderId must be positive");
        }
        CoreTriggerOrderStateView current = aeronGateway.get(request.userId(), request.triggerOrderId());
        if (current == null || current.productLine() != currentProductLine()) {
            throw new IllegalStateException("trigger order not found: " + request.triggerOrderId());
        }
        if (current.status() == CoreTriggerOrderStatus.PENDING) {
            aeronGateway.cancel(request.userId(), request.triggerOrderId());
            current = aeronGateway.get(request.userId(), request.triggerOrderId());
        }
        return TriggerOrderAeronGateway.response(current);
    }

    public TriggerOrderBatchResponse cancelBatch(BatchCancelTriggerOrdersRequest request) {
        List<CancelTriggerOrderRequest> orders = request == null ? List.of() : request.orders();
        requireBatchSize(orders.size(), 50, "orders");
        List<TriggerOrderBatchItemResponse> results = new ArrayList<>();
        for (int i = 0; i < orders.size(); i++) {
            try {
                results.add(new TriggerOrderBatchItemResponse(i, true, "completed", cancel(orders.get(i))));
            } catch (IllegalArgumentException | IllegalStateException ex) {
                results.add(new TriggerOrderBatchItemResponse(i, false, ex.getMessage(), null));
            }
        }
        return triggerBatchResponse(results);
    }

    public TriggerOrderBatchResponse cancelOpenOrders(CancelOpenTriggerOrdersRequest request) {
        if (request == null || request.userId() <= 0) {
            throw new IllegalArgumentException("userId is required");
        }
        int limit = request.limit() == null ? 1000 : request.limit();
        if (limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("limit must be in [1, 1000]");
        }
        String symbol = request.symbol() == null || request.symbol().isBlank()
                ? null : normalizeSymbol(request.symbol());
        List<CoreTriggerOrderStateView> orders = aeronGateway.openOrders(request.userId(), symbol, 0, limit);
        List<TriggerOrderBatchItemResponse> results = new ArrayList<>();
        for (int i = 0; i < orders.size(); i++) {
            try {
                results.add(new TriggerOrderBatchItemResponse(i, true, "completed",
                        cancel(new CancelTriggerOrderRequest(request.userId(), orders.get(i).triggerOrderId()))));
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
        long before = decodeOpenTriggerCursor(cursor);
        List<TriggerOrderResponse> values = aeronGateway.openOrders(userId, normalizedSymbol, before, limit + 1)
                .stream().map(TriggerOrderAeronGateway::response).toList();
        boolean hasMore = values.size() > limit;
        List<TriggerOrderResponse> orders = hasMore ? values.subList(0, limit) : values;
        String next = hasMore && !orders.isEmpty()
                ? encodeOpenTriggerCursor(orders.getLast().triggerOrderId()) : null;
        return new TriggerOrderQueryResponse(orders.size(), List.copyOf(orders), next, hasMore,
                "createdAt.desc", limit);
    }

    public TriggerOrderQueryResponse adminOrders(Long userId, String symbol, String status,
                                                 Long triggerOrderId, int limit) {
        return adminOrders(userId, symbol, status, triggerOrderId, limit, null, null, null);
    }

    public TriggerOrderQueryResponse adminOrders(Long userId, String symbol, String status,
                                                 Long triggerOrderId, int limit, String cursor, String sort) {
        return adminOrders(userId, symbol, status, triggerOrderId, limit, cursor, sort, null);
    }

    public TriggerOrderQueryResponse adminOrders(Long userId, String symbol, String status,
                                                 Long triggerOrderId, int limit, String cursor, String sort,
                                                 ProductLine productLine) {
        if (userId != null && userId <= 0) throw new IllegalArgumentException("userId must be positive");
        if (triggerOrderId != null && triggerOrderId <= 0) {
            throw new IllegalArgumentException("triggerOrderId must be positive");
        }
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("limit must be in [1, 1000]");
        ProductLine effective = productLine == null ? currentProductLine() : productLine;
        if (effective != currentProductLine()) throw new IllegalArgumentException("product line does not match provider");
        String normalizedSymbol = symbol == null || symbol.isBlank() ? null : normalizeSymbol(symbol);
        CoreTriggerOrderStatus normalizedStatus = status == null || status.isBlank() ? null
                : CoreTriggerOrderStatus.valueOf(status.trim().toUpperCase());
        long before = decodeOpenTriggerCursor(cursor);
        List<TriggerOrderResponse> values = aeronGateway.query(userId == null ? 0 : userId,
                        triggerOrderId == null ? 0 : triggerOrderId, normalizedSymbol, before, limit + 1,
                        normalizedStatus)
                .stream().filter(value -> value.productLine() == effective)
                .map(TriggerOrderAeronGateway::response).toList();
        boolean hasMore = values.size() > limit;
        List<TriggerOrderResponse> orders = hasMore ? values.subList(0, limit) : values;
        String next = hasMore && !orders.isEmpty()
                ? encodeOpenTriggerCursor(orders.getLast().triggerOrderId()) : null;
        return new TriggerOrderQueryResponse(orders.size(), List.copyOf(orders), next, hasMore,
                "createdAt.desc", limit);
    }

    public AdminTriggerOrderTimelineResponse adminTimeline(long triggerOrderId) {
        return adminTimeline(triggerOrderId, null);
    }

    public AdminTriggerOrderTimelineResponse adminTimeline(long triggerOrderId, ProductLine productLine) {
        TriggerOrderResponse order = get(triggerOrderId, productLine);
        return new AdminTriggerOrderTimelineResponse(order, timelineEvents(order));
    }

    public void maintenance() {
        Instant now = Instant.now();
        long nowMillis = now.toEpochMilli();
        int limit = Math.min(1000, Math.max(1, properties.getExecution().getTriggerBatchSize()));
        for (CoreTriggerOrderStateView order : aeronGateway.expiredOrders(nowMillis, limit)) {
            if (order.status() == CoreTriggerOrderStatus.PENDING
                    && order.expiresAtEpochMillis() > 0
                    && order.expiresAtEpochMillis() <= nowMillis) {
                aeronGateway.expire(order.triggerOrderId(), nowMillis);
            }
        }
        long staleBefore = now.minus(properties.getExecution().getStaleTriggeringAfter()).toEpochMilli();
        scanAeronOpenOrders(0, null, "maintenance", CoreTriggerOrderStatus.TRIGGERING, open -> {
            for (CoreTriggerOrderStateView order : open) {
                if (order.updatedAtEpochMillis() <= staleBefore) {
                    aeronGateway.retry(order.triggerOrderId(), staleBefore, nowMillis);
                }
            }
        });
    }

    private void scanAeronOpenOrders(long userId, String symbol, String operation,
                                     CoreTriggerOrderStatus status,
                                     Consumer<List<CoreTriggerOrderStateView>> pageConsumer) {
        int pageSize = Math.min(1000, Math.max(1, properties.getExecution().getTriggerBatchSize()));
        int maxPages = Math.min(256, Math.max(1, properties.getExecution().getMaxTriggerScanPages()));
        long before = 0;
        for (int pageNumber = 1; pageNumber <= maxPages; pageNumber++) {
            List<CoreTriggerOrderStateView> page = aeronGateway.openOrders(userId, symbol, before, pageSize, status);
            if (page.isEmpty()) return;
            pageConsumer.accept(page);
            long nextBefore = page.getLast().triggerOrderId();
            if (page.size() < pageSize) return;
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

    static String encodeOpenTriggerCursor(long triggerOrderId) {
        if (triggerOrderId <= 0) throw new IllegalArgumentException("open-trigger cursor id must be positive");
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("trigger:" + triggerOrderId).getBytes(StandardCharsets.UTF_8));
    }

    static long decodeOpenTriggerCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) return Long.MAX_VALUE;
        try {
            String decoded = new String(java.util.Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            if (!decoded.startsWith("trigger:")) throw new IllegalArgumentException("invalid open-trigger cursor");
            long id = Long.parseLong(decoded.substring("trigger:".length()));
            if (id <= 0) throw new IllegalArgumentException("invalid open-trigger cursor");
            return id;
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("invalid open-trigger cursor", exception);
        }
    }

    private PlaceTriggerOrderRequest normalize(PlaceTriggerOrderRequest request) {
        if (request == null) throw new IllegalArgumentException("trigger order request is required");
        if (request.userId() <= 0) throw new IllegalArgumentException("userId must be positive");
        if (request.side() == null || request.triggerType() == null || request.orderType() == null
                || request.timeInForce() == null) {
            throw new IllegalArgumentException("side, triggerType, orderType and timeInForce are required");
        }
        if (request.quantitySteps() <= 0) throw new IllegalArgumentException("quantitySteps must be positive");
        validateTriggerPriceFields(request);
        validateExecutionOrder(request.orderType(), request.timeInForce(), request.priceTicks());
        String clientId = emptyToNull(request.clientTriggerOrderId());
        if (clientId != null && clientId.length() > 64) throw new IllegalArgumentException("clientTriggerOrderId length must be <= 64");
        String ocoId = emptyToNull(request.ocoGroupId());
        if (ocoId != null && ocoId.length() > 64) throw new IllegalArgumentException("ocoGroupId length must be <= 64");
        if (request.expiresAt() != null && !request.expiresAt().isAfter(Instant.now())) {
            throw new IllegalArgumentException("expiresAt must be in the future");
        }
        return new PlaceTriggerOrderRequest(request.userId(), clientId, ocoId, normalizeSymbol(request.symbol()),
                request.side(), request.triggerType(), request.triggerPriceTicks(), request.activationPriceTicks(),
                request.callbackRatePpm(), request.orderType(), request.timeInForce(), request.priceTicks(),
                request.quantitySteps(), MarginMode.defaultIfNull(request.marginMode()),
                PositionSide.defaultIfNull(request.positionSide()), request.expiresAt());
    }

    private void validateExecutionOrder(OrderType orderType, TimeInForce timeInForce, long priceTicks) {
        if (orderType == OrderType.MARKET) {
            if (priceTicks != 0) throw new IllegalArgumentException("market trigger execution priceTicks must be zero");
            if (timeInForce != TimeInForce.IOC && timeInForce != TimeInForce.FOK) {
                throw new IllegalArgumentException("market trigger execution requires IOC or FOK");
            }
            return;
        }
        if (priceTicks <= 0) throw new IllegalArgumentException("limit trigger execution priceTicks must be positive");
        if (timeInForce == TimeInForce.GTX) throw new IllegalArgumentException("trigger execution does not support GTX");
    }

    private void validateTriggerPriceFields(PlaceTriggerOrderRequest request) {
        if (request.triggerType() == TriggerOrderType.TRAILING_STOP) {
            if (request.orderType() != OrderType.MARKET) throw new IllegalArgumentException("trailing stop execution requires MARKET");
            if (request.triggerPriceTicks() < 0) throw new IllegalArgumentException("triggerPriceTicks must be zero or positive");
            if (request.activationPriceTicks() != null && request.activationPriceTicks() < 0) {
                throw new IllegalArgumentException("activationPriceTicks must be zero or positive");
            }
            if (request.callbackRatePpm() == null || request.callbackRatePpm() < MIN_TRAILING_CALLBACK_RATE_PPM
                    || request.callbackRatePpm() > MAX_TRAILING_CALLBACK_RATE_PPM) {
                throw new IllegalArgumentException("callbackRatePpm must be in [1000, 100000]");
            }
            return;
        }
        if (request.triggerPriceTicks() <= 0) throw new IllegalArgumentException("triggerPriceTicks must be positive");
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

    private List<AdminTriggerOrderTimelineEvent> timelineEvents(TriggerOrderResponse order) {
        List<AdminTriggerOrderTimelineEvent> events = new ArrayList<>();
        events.add(new AdminTriggerOrderTimelineEvent("CREATED", TriggerOrderStatus.PENDING, null, null, null,
                null, order.traceId(), order.createdAt()));
        if (order.status() == TriggerOrderStatus.EXPIRED) {
            events.add(new AdminTriggerOrderTimelineEvent("EXPIRED", order.status(), null, null, null,
                    "expiresAt reached", order.traceId(), order.updatedAt()));
        }
        if (order.status() == TriggerOrderStatus.CANCELED) {
            events.add(new AdminTriggerOrderTimelineEvent("CANCELED", order.status(), null, null, null,
                    "trigger order canceled", order.traceId(), order.updatedAt()));
        }
        if (order.triggeredAt() != null) {
            events.add(new AdminTriggerOrderTimelineEvent("TRIGGERED_MARK", order.status(), order.triggerSequence(),
                    order.triggeredPriceTicks(), null, null, order.traceId(), order.triggeredAt()));
        }
        if (order.placedOrderId() != null) {
            events.add(new AdminTriggerOrderTimelineEvent(
                    order.status() == TriggerOrderStatus.TRIGGER_FAILED ? "EXECUTION_REJECTED" : "EXECUTION_PLACED",
                    order.status(), order.triggerSequence(), order.triggeredPriceTicks(), order.placedOrderId(),
                    order.rejectReason(), order.traceId(), order.updatedAt()));
        }
        return events.stream().sorted(Comparator.comparing(AdminTriggerOrderTimelineEvent::eventTime)).toList();
    }

    private TriggerOrderBatchResponse triggerBatchResponse(List<TriggerOrderBatchItemResponse> results) {
        int completed = (int) results.stream().filter(TriggerOrderBatchItemResponse::success).count();
        return new TriggerOrderBatchResponse(results.size(), completed, results.size() - completed, results);
    }

    private void requireBatchSize(int size, int max, String field) {
        if (size < 1 || size > max) throw new IllegalArgumentException(field + " size must be in [1, " + max + "]");
    }

    private String normalizeSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) throw new IllegalArgumentException("symbol is required");
        String normalized = symbol.trim().toUpperCase();
        if (!normalized.matches("[A-Z0-9][A-Z0-9_-]{1,63}")) throw new IllegalArgumentException("invalid symbol: " + symbol);
        return normalized;
    }

    private ProductLine currentProductLine() {
        if (properties.getProductLine() == null) throw new IllegalStateException("trigger product line is required");
        return properties.getProductLine();
    }

    private String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
