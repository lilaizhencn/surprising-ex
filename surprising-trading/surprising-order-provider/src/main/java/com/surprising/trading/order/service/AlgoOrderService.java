package com.surprising.trading.order.service;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.TraceContext;
import com.surprising.trading.api.model.AlgoOrderBatchItemResponse;
import com.surprising.trading.api.model.AlgoOrderBatchResponse;
import com.surprising.trading.api.model.AlgoOrderQueryResponse;
import com.surprising.trading.api.model.AlgoOrderResponse;
import com.surprising.trading.api.model.AlgoOrderStatus;
import com.surprising.trading.api.model.AlgoOrderType;
import com.surprising.trading.api.model.CancelAlgoOrderRequest;
import com.surprising.trading.api.model.CancelOpenAlgoOrdersRequest;
import com.surprising.trading.api.model.CancelOrderRequest;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.OrderResponse;
import com.surprising.trading.api.model.OrderStatus;
import com.surprising.trading.api.model.OrderType;
import com.surprising.trading.api.model.PlaceAlgoOrderRequest;
import com.surprising.trading.api.model.PlaceOrderRequest;
import com.surprising.trading.api.model.PositionSide;
import com.surprising.trading.api.model.TimeInForce;
import com.surprising.trading.order.config.TradingOrderProperties;
import com.surprising.trading.order.model.AlgoOrderChild;
import com.surprising.trading.order.model.AlgoOrderProgress;
import com.surprising.trading.order.model.AlgoOrderRecord;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 算法单服务。
 *
 * <p>算法父单状态与普通子订单统一由 Aeron Cluster 顺序裁决；切片任务只提交父单 revision，
 * 子订单仍复用普通订单原子下单路径，不维护外围 WAL 或第二资金状态机。</p>
 */
@Service
public class AlgoOrderService {

    private static final int MAX_OPEN_CANCEL_LIMIT = 1000;

    private final TradingOrderProperties properties;
    private final OrderService orderService;
    private final AeronAlgoOrderStore store;
    private final AeronOrderIdGenerator ids;

    @Autowired
    public AlgoOrderService(TradingOrderProperties properties,
                            OrderService orderService,
                            AeronAlgoOrderStore store,
                            AeronOrderIdGenerator ids) {
        this.properties = properties;
        this.orderService = orderService;
        this.store = store;
        this.ids = ids;
    }

    public AlgoOrderResponse place(PlaceAlgoOrderRequest request) {
        PlaceAlgoOrderRequest normalized = normalize(request);
        ProductLine productLine = currentProductLine();
        requireLocalProductLine(productLine);
        Instant now = Instant.now();
        Instant startAt = normalized.startAt() == null || normalized.startAt().isBefore(now)
                ? now : normalized.startAt();
        AlgoOrderRecord record = new AlgoOrderRecord(
                ids.next(), productLine, normalized.userId(), normalized.clientAlgoOrderId(),
                normalized.symbol(), normalized.algoType(), normalized.side(), normalized.priceTicks(),
                normalized.quantitySteps(), normalized.childQuantitySteps(), normalized.intervalSeconds(),
                normalized.durationSeconds(), normalized.marginMode(), normalized.positionSide(), normalized.reduceOnly(),
                normalized.postOnly(), normalized.timeInForce(), AlgoOrderStatus.PENDING, null, null,
                TraceContext.currentOrCreate(), startAt, startAt, null, now, now);
        var persisted = store.upsert(record, List.of(), 1);
        AlgoOrderResponse response = store.response(store.get(persisted.userId(), persisted.algoOrderId()));
        return response;
    }

    public AlgoOrderResponse cancel(CancelAlgoOrderRequest request) {
        if (request == null || request.userId() <= 0 || request.algoOrderId() <= 0) {
            throw new IllegalArgumentException("userId and algoOrderId must be positive");
        }
        AlgoOrderRecord record = store.record(store.get(request.userId(), request.algoOrderId()));
        requireCurrentProductLine(record);
        if (isTerminal(record.status())) {
            return store.response(store.get(record.userId(), record.algoOrderId()));
        }
        return cancelRecord(record);
    }

    public AlgoOrderBatchResponse cancelOpen(CancelOpenAlgoOrdersRequest request) {
        if (request == null || request.userId() <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
        int limit = request.limit() == null ? MAX_OPEN_CANCEL_LIMIT : request.limit();
        if (limit < 1 || limit > MAX_OPEN_CANCEL_LIMIT) {
            throw new IllegalArgumentException("limit must be in [1, 1000]");
        }
        String symbol = request.symbol() == null || request.symbol().isBlank()
                ? null : normalizeSymbol(request.symbol());
        List<AlgoOrderResponse> canceled = store.query(request.userId(), symbol, 0, limit).stream()
                .filter(value -> !isTerminal(AlgoOrderStatus.values()[value.statusCode()]))
                .map(store::response)
                .map(value -> cancel(new CancelAlgoOrderRequest(value.userId(), value.algoOrderId())))
                .toList();
        List<AlgoOrderBatchItemResponse> results = new ArrayList<>(canceled.size());
        for (int i = 0; i < canceled.size(); i++) {
            results.add(new AlgoOrderBatchItemResponse(i, true, "cancel requested", canceled.get(i)));
        }
        return batchResponse(results);
    }

    public AlgoOrderResponse get(long algoOrderId) {
        AlgoOrderRecord record = store.query(0, "", 0, 1000).stream()
                .filter(value -> value.algoOrderId() == algoOrderId).findFirst().map(store::record)
                .orElseThrow(() -> new IllegalStateException("算法单不存在: " + algoOrderId));
        requireCurrentProductLine(record);
        return store.response(store.get(record.userId(), record.algoOrderId()));
    }

    public int cancelLifecycleOrders(String symbol, int limit) {
        String normalizedSymbol = normalizeSymbol(symbol);
        List<AlgoOrderRecord> orders = store.query(0, normalizedSymbol, 0,
                        Math.max(1, Math.min(limit, MAX_OPEN_CANCEL_LIMIT))).stream()
                .filter(value -> !isTerminal(AlgoOrderStatus.values()[value.statusCode()]))
                .map(store::record).toList();
        orders.forEach(this::cancelRecord);
        return orders.size();
    }

    public boolean hasLifecycleActiveOrders(String symbol) {
        return store.query(0, normalizeSymbol(symbol), 0, 1).stream()
                .anyMatch(value -> !isTerminal(AlgoOrderStatus.values()[value.statusCode()]));
    }

    public AlgoOrderQueryResponse openOrders(long userId, String symbol, int limit) {
        if (userId <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
        if (limit < 1 || limit > MAX_OPEN_CANCEL_LIMIT) {
            throw new IllegalArgumentException("limit must be in [1, 1000]");
        }
        List<AlgoOrderResponse> orders = store.query(userId, symbol, 0, limit).stream()
                .filter(value -> !isTerminal(AlgoOrderStatus.values()[value.statusCode()]))
                .map(store::response).toList();
        return new AlgoOrderQueryResponse(orders.size(), orders);
    }

    public void scanDueAlgoOrders() {
        if (!properties.getAlgo().isEnabled()) {
            return;
        }
        Instant now = Instant.now();
        Duration lease = properties.getAlgo().getClaimLease();
        var due = store.query(0, "", now.toEpochMilli(),
                        Math.max(1, properties.getAlgo().getClaimBatchSize())).stream()
                .filter(value -> value.statusCode() == AlgoOrderStatus.PENDING.ordinal()
                        || value.statusCode() == AlgoOrderStatus.RUNNING.ordinal())
                .toList();
        for (var candidate : due) {
            AlgoOrderRecord record = store.record(candidate);
            AlgoOrderRecord claimed = withStatus(record, AlgoOrderStatus.RUNNING, null,
                    now.plus(lease), null, now, record.currentOrderId());
            var claimedView = store.tryClaim(candidate, claimed);
            if (claimedView == null) {
                continue;
            }
            try {
                executeDue(store.record(claimedView), now);
            } catch (RuntimeException ex) {
                update(withStatus(store.record(claimedView), AlgoOrderStatus.FAILED, ex.getMessage(),
                        null, now, now, claimedView.currentOrderId()), claimedView.childOrderIds());
            }
        }
    }

    void executeDue(AlgoOrderRecord record, Instant now) {
        var current = store.get(record.userId(), record.algoOrderId());
        AlgoOrderProgress progress = store.progress(current);
        if (progress.executedQuantitySteps() >= record.quantitySteps()
                && progress.activeChildOrderCount() == 0) {
        update(withStatus(record, AlgoOrderStatus.COMPLETED, null,
                    null, now, now, record.currentOrderId()), current.childOrderIds());
            return;
        }
        if (progress.activeChildOrderCount() > 0) {
        update(withStatus(record, AlgoOrderStatus.RUNNING, null,
                    now.plusMillis(properties.getAlgo().getScanDelayMs()), null, now, record.currentOrderId()), current.childOrderIds());
            return;
        }

        long remainingTarget = Math.subtractExact(record.quantitySteps(), progress.executedQuantitySteps());
        if (remainingTarget <= 0L) {
        update(withStatus(record, AlgoOrderStatus.COMPLETED, null,
                    null, now, now, record.currentOrderId()), current.childOrderIds());
            return;
        }
        long childQuantity = Math.min(record.childQuantitySteps(), remainingTarget);
        PlaceOrderRequest childRequest = childRequest(record, progress.nextSliceIndex(), childQuantity);
        OrderResponse child = orderService.place(childRequest);
        if (child.status() == OrderStatus.REJECTED) {
        update(withStatus(record, AlgoOrderStatus.FAILED, child.rejectReason(),
                    null, now, now, null), current.childOrderIds());
            return;
        }
        Instant nextSliceAt = nextSliceAt(record, now);
        AlgoOrderRecord updated = withStatus(record, AlgoOrderStatus.RUNNING, null, nextSliceAt,
                null, now, child.orderId());
        List<Long> children = new ArrayList<>(current.childOrderIds());
        children.add(child.orderId());
        update(updated, children);
    }

    private AlgoOrderResponse cancelRecord(AlgoOrderRecord record) {
        if (isTerminal(record.status())) {
            return store.response(store.get(record.userId(), record.algoOrderId()));
        }
        Instant now = Instant.now();
        AlgoOrderRecord requested = withStatus(record, AlgoOrderStatus.CANCEL_REQUESTED, null,
                null, null, now, record.currentOrderId());
        var current = store.get(record.userId(), record.algoOrderId());
        update(requested, current.childOrderIds());
        for (AlgoOrderChild child : store.children(current)) {
            try {
                orderService.cancel(new CancelOrderRequest(record.userId(), child.orderId()));
            } catch (RuntimeException ignored) {
                // 子单已终态时继续收敛算法单终态，订单事实流负责最终幂等裁决。
            }
        }
        AlgoOrderRecord canceled = withStatus(requested, AlgoOrderStatus.CANCELED, null,
                null, now, Instant.now(), record.currentOrderId());
        update(canceled, current.childOrderIds());
        return store.response(store.get(canceled.userId(), canceled.algoOrderId()));
    }

    private PlaceOrderRequest childRequest(AlgoOrderRecord record, int sliceIndex, long quantitySteps) {
        OrderType orderType = record.priceTicks() > 0 ? OrderType.LIMIT : OrderType.MARKET;
        TimeInForce timeInForce = orderType == OrderType.MARKET ? TimeInForce.IOC : record.timeInForce();
        return new PlaceOrderRequest(record.userId(), childClientOrderId(record.algoOrderId(), sliceIndex),
                record.symbol(), record.side(), orderType, timeInForce,
                orderType == OrderType.MARKET ? 0L : record.priceTicks(), quantitySteps,
                record.marginMode(), record.positionSide(), record.reduceOnly(), record.postOnly());
    }

    private void update(AlgoOrderRecord record, List<Long> childOrderIds) {
        var current = store.get(record.userId(), record.algoOrderId());
        List<Long> children = childOrderIds.isEmpty() ? current.childOrderIds() : childOrderIds;
        store.upsert(record, children, current.revision() + 1);
    }

    private Instant nextSliceAt(AlgoOrderRecord record, Instant now) {
        return record.algoType() == AlgoOrderType.TWAP
                ? now.plusSeconds(record.intervalSeconds())
                : now.plusMillis(properties.getAlgo().getScanDelayMs());
    }

    private AlgoOrderRecord withStatus(AlgoOrderRecord order,
                                       AlgoOrderStatus status,
                                       String reason,
                                       Instant nextSliceAt,
                                       Instant completedAt,
                                       Instant now,
                                       Long currentOrderId) {
        return new AlgoOrderRecord(order.algoOrderId(), order.productLine(), order.userId(),
                order.clientAlgoOrderId(), order.symbol(), order.algoType(), order.side(), order.priceTicks(),
                order.quantitySteps(), order.childQuantitySteps(), order.intervalSeconds(), order.durationSeconds(),
                order.marginMode(), order.positionSide(), order.reduceOnly(), order.postOnly(), order.timeInForce(),
                status, currentOrderId, reason, order.traceId(), order.startAt(), nextSliceAt, completedAt,
                order.createdAt(), now);
    }

    private PlaceAlgoOrderRequest normalize(PlaceAlgoOrderRequest request) {
        if (request == null || request.userId() <= 0) {
            throw new IllegalArgumentException("algo order request and userId are required");
        }
        String symbol = normalizeSymbol(request.symbol());
        if (request.algoType() == null || request.side() == null) {
            throw new IllegalArgumentException("algoType and side are required");
        }
        if (request.quantitySteps() <= 0 || request.childQuantitySteps() <= 0
                || request.childQuantitySteps() > request.quantitySteps()) {
            throw new IllegalArgumentException("quantitySteps and childQuantitySteps are invalid");
        }
        validateRange(request.intervalSeconds(), properties.getAlgo().getMinIntervalSeconds(),
                properties.getAlgo().getMaxIntervalSeconds(), "intervalSeconds");
        validateRange(request.durationSeconds(), properties.getAlgo().getMinDurationSeconds(),
                properties.getAlgo().getMaxDurationSeconds(), "durationSeconds");
        if (request.durationSeconds() < request.intervalSeconds()) {
            throw new IllegalArgumentException("durationSeconds must be >= intervalSeconds");
        }
        if (request.algoType() == AlgoOrderType.TWAP) {
            long maxSlices = (request.durationSeconds() + request.intervalSeconds() - 1L)
                    / request.intervalSeconds();
            long minChild = (request.quantitySteps() + maxSlices - 1L) / maxSlices;
            if (request.childQuantitySteps() < minChild) {
                throw new IllegalArgumentException("childQuantitySteps is too small to finish TWAP inside durationSeconds");
            }
        }
        MarginMode marginMode = MarginMode.defaultIfNull(request.marginMode());
        PositionSide positionSide = PositionSide.defaultIfNull(request.positionSide());
        TimeInForce tif = normalizeTimeInForce(request);
        boolean postOnly = request.algoType() == AlgoOrderType.ICEBERG
                && (request.postOnly() || tif == TimeInForce.GTX);
        if (request.algoType() == AlgoOrderType.TWAP && postOnly) {
            throw new IllegalArgumentException("TWAP does not support postOnly");
        }
        return new PlaceAlgoOrderRequest(request.userId(), emptyToNull(request.clientAlgoOrderId()), symbol,
                request.algoType(), request.side(), request.priceTicks(), request.quantitySteps(),
                request.childQuantitySteps(), request.intervalSeconds(), request.durationSeconds(), marginMode,
                positionSide, request.reduceOnly(), postOnly, tif, request.startAt());
    }

    private TimeInForce normalizeTimeInForce(PlaceAlgoOrderRequest request) {
        if (request.algoType() == AlgoOrderType.TWAP) {
            if (request.timeInForce() != null && request.timeInForce() != TimeInForce.IOC) {
                throw new IllegalArgumentException("TWAP child orders must use IOC");
            }
            return TimeInForce.IOC;
        }
        if (request.priceTicks() <= 0) {
            throw new IllegalArgumentException("ICEBERG requires limit priceTicks");
        }
        TimeInForce tif = request.timeInForce() == null ? TimeInForce.GTC : request.timeInForce();
        if (tif != TimeInForce.GTC && tif != TimeInForce.GTX) {
            throw new IllegalArgumentException("ICEBERG timeInForce must be GTC or GTX");
        }
        return tif;
    }

    private void validateRange(long value, long min, long max, String name) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(name + " must be in [" + min + ", " + max + "]");
        }
    }

    private AlgoOrderBatchResponse batchResponse(List<AlgoOrderBatchItemResponse> results) {
        int completed = (int) results.stream().filter(AlgoOrderBatchItemResponse::success).count();
        return new AlgoOrderBatchResponse(results.size(), completed, results.size() - completed, results);
    }

    private boolean isTerminal(AlgoOrderStatus status) {
        return status == AlgoOrderStatus.CANCELED || status == AlgoOrderStatus.COMPLETED
                || status == AlgoOrderStatus.FAILED;
    }

    private String childClientOrderId(long algoOrderId, int sliceIndex) {
        return "algo-" + algoOrderId + "-" + sliceIndex;
    }

    private String normalizeSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("symbol is required");
        }
        String normalized = symbol.trim().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z0-9][A-Z0-9_-]{1,63}")) {
            throw new IllegalArgumentException("invalid symbol: " + symbol);
        }
        return normalized;
    }

    private String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private ProductLine currentProductLine() {
        return properties.getKafka().getProductLine();
    }

    private void requireLocalProductLine(ProductLine productLine) {
        if (productLine == null) throw new IllegalStateException("订单节点产品线未配置");
    }

    private void requireCurrentProductLine(AlgoOrderRecord order) {
        if (order.productLine() != currentProductLine()) {
            throw new IllegalStateException("算法单不属于当前产品线: " + order.algoOrderId());
        }
    }
}
