package com.surprising.trading.order.service;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.AlgoOrderBatchItemResponse;
import com.surprising.trading.api.model.AlgoOrderBatchResponse;
import com.surprising.trading.api.model.AlgoOrderQueryResponse;
import com.surprising.trading.api.model.AlgoOrderResponse;
import com.surprising.trading.api.model.AlgoOrderStatus;
import com.surprising.trading.api.model.AlgoOrderType;
import com.surprising.trading.api.model.CancelAlgoOrderRequest;
import com.surprising.trading.api.model.CancelOpenAlgoOrdersRequest;
import com.surprising.trading.api.model.CancelOrderRequest;
import com.surprising.trading.api.model.OrderResponse;
import com.surprising.trading.api.model.OrderStatus;
import com.surprising.trading.api.model.OrderType;
import com.surprising.trading.api.model.PlaceAlgoOrderRequest;
import com.surprising.trading.api.model.PlaceOrderRequest;
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
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(AlgoOrderService.class);

    private static final int MAX_OPEN_CANCEL_LIMIT = 1000;

    private final TradingOrderProperties properties;
    private final OrderService orderService;
    private final AeronAlgoOrderStore store;
    private final com.surprising.trading.order.repository.AlgoOrderProjectionRepository projection;
    private final com.surprising.trading.order.repository.AeronOrderProjectionRepository receipts;

    @Autowired
    public AlgoOrderService(TradingOrderProperties properties,
                            OrderService orderService,
                            AeronAlgoOrderStore store,
                            com.surprising.trading.order.repository.AlgoOrderProjectionRepository projection,
                            com.surprising.trading.order.repository.AeronOrderProjectionRepository receipts) {
        this.properties = properties;
        this.orderService = orderService;
        this.store = store;
        this.projection = projection;
        this.receipts = receipts;
    }

    public AlgoOrderResponse place(PlaceAlgoOrderRequest request) {
        if (request == null || request.userId() <= 0) {
            throw new IllegalArgumentException("algo order request and userId are required");
        }
        String instrumentId = normalizeSymbol(request.instrumentId());
        if (request.algoType() == null || request.side() == null) {
            throw new IllegalArgumentException("algoType and side are required");
        }
        if (request.quantitySteps() <= 0 || request.childQuantitySteps() <= 0
                || request.childQuantitySteps() > request.quantitySteps()) {
            throw new IllegalArgumentException("quantitySteps and childQuantitySteps are invalid");
        }
        validateRange(request.durationSeconds(), properties.getAlgo().getMinDurationSeconds(),
                properties.getAlgo().getMaxDurationSeconds(), "durationSeconds");
        if (request.algoType() == AlgoOrderType.TWAP) {
            validateRange(request.intervalSeconds(), properties.getAlgo().getMinIntervalSeconds(),
                    properties.getAlgo().getMaxIntervalSeconds(), "intervalSeconds");
            if (request.durationSeconds() < request.intervalSeconds()) {
                throw new IllegalArgumentException("durationSeconds must be >= intervalSeconds");
            }
            long maxSlices = (request.durationSeconds() + request.intervalSeconds() - 1L)
                    / request.intervalSeconds();
            long minChild = request.quantitySteps() / maxSlices
                    + (request.quantitySteps() % maxSlices == 0 ? 0 : 1);
            if (request.childQuantitySteps() < minChild) {
                throw new IllegalArgumentException("childQuantitySteps is too small to finish TWAP inside durationSeconds");
            }
        }
        TimeInForce tif = normalizeTimeInForce(request);
        String clientAlgoOrderId = emptyToNull(request.clientAlgoOrderId());
        if (clientAlgoOrderId == null) {
            throw new IllegalArgumentException("clientAlgoOrderId is required");
        }
        if (clientAlgoOrderId.length() > 64) {
            throw new IllegalArgumentException("clientAlgoOrderId length must be <= 64");
        }
        ProductLine productLine = currentProductLine();
        requireLocalProductLine(productLine);
        long algoOrderId = StableOrderIdentity.algoOrderId(productLine, request.userId(), clientAlgoOrderId);
        AlgoOrderRecord record = new AlgoOrderRecord(
                algoOrderId, productLine, request.userId(), clientAlgoOrderId,
                instrumentId, request.algoType(), request.side(), request.priceTicks(),
                request.quantitySteps(), request.childQuantitySteps(), request.intervalSeconds(),
                request.durationSeconds(), request.marginMode(), request.positionSide(), request.reduceOnly(),
                tif, AlgoOrderStatus.PENDING, null, null,
                request.startAt(), null, null, null, null);
        var persisted = store.create(record);
        return store.response(store.get(persisted.userId(), persisted.algoOrderId()));
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
        return cancelRecord(record, null);
    }

    public AlgoOrderBatchResponse cancelOpen(CancelOpenAlgoOrdersRequest request) {
        if (request == null || request.userId() <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
        int limit = request.limit() == null ? MAX_OPEN_CANCEL_LIMIT : request.limit();
        if (limit < 1 || limit > MAX_OPEN_CANCEL_LIMIT) {
            throw new IllegalArgumentException("limit must be in [1, 1000]");
        }
        String instrumentId = request.instrumentId() == null || request.instrumentId().isBlank()
                ? null : normalizeSymbol(request.instrumentId());
        List<AlgoOrderResponse> canceled = store.query(request.userId(), instrumentId, 0, limit).stream()
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

    public AlgoOrderResponse get(long userId, long algoOrderId) {
        var view = projection.byId(currentProductLine(), userId, algoOrderId);
        if (view == null) throw new java.util.NoSuchElementException( "algo order is not present in the committed read view");
        return store.response(view);
    }

    public com.surprising.trading.api.model.AlgoOrderSubmissionResponse submission(long userId, String clientKey) {
        String key = emptyToNull(clientKey);
        if (key == null || key.length() > 64) throw new IllegalArgumentException("clientAlgoOrderId is required and must be at most 64 characters");
        var product = currentProductLine();
        var command = StableOrderIdentity.algoCommandId(product, userId, key);
        long id = StableOrderIdentity.algoOrderId(product, userId, key);
        var receipt = receipts.algoSubmission(product, userId, command);
        var parent = projection.byClient(product, userId, key);
        boolean terminal = "TERMINAL".equals(receipt.outcome());
        if (parent != null && (parent.algoOrderId() != id || terminal && !"NONE".equals(receipt.code())))
            throw new IllegalStateException("conflicting committed algo creation facts");
        // A retained parent is also definitive evidence of creation, even when a snapshot
        // predates receipt retention. A missing parent/receipt never proves non-admission.
        boolean accepted = parent != null || terminal && "NONE".equals(receipt.code());
        return new com.surprising.trading.api.model.AlgoOrderSubmissionResponse(userId, product, key, id, command,
                accepted || terminal ? "TERMINAL" : "RESULT_UNKNOWN", accepted ? "NONE" : receipt.code(),
                parent == null ? null : store.response(parent));
    }

    public int cancelLifecycleOrders(String instrumentId, int limit) {
        String normalizedSymbol = normalizeSymbol(instrumentId);
        List<AlgoOrderRecord> orders = store.query(0, normalizedSymbol, 0,
                        Math.max(1, Math.min(limit, MAX_OPEN_CANCEL_LIMIT))).stream()
                .filter(value -> !isTerminal(AlgoOrderStatus.values()[value.statusCode()]))
                .map(store::record).toList();
        orders.forEach(order -> cancelRecord(order, null));
        return orders.size();
    }

    public boolean hasLifecycleActiveOrders(String instrumentId) {
        return store.query(0, normalizeSymbol(instrumentId), 0, 1).stream()
                .anyMatch(value -> !isTerminal(AlgoOrderStatus.values()[value.statusCode()]));
    }

    public AlgoOrderQueryResponse openOrders(long userId, String instrumentId, int limit) {
        if (userId <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
        if (limit < 1 || limit > MAX_OPEN_CANCEL_LIMIT) {
            throw new IllegalArgumentException("limit must be in [1, 1000]");
        }
        List<AlgoOrderResponse> orders = projection.openOrders(currentProductLine(), userId, instrumentId, limit).stream()
                .map(store::response).toList();
        return new AlgoOrderQueryResponse(orders.size(), orders);
    }

    private long lastScanNanos;
    public synchronized void scanDueAlgoOrders() {
        long scanNow = System.nanoTime();
        if (scanNow - lastScanNanos < java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(properties.getAlgo().getScanDelayMs())) return;
        lastScanNanos = scanNow;
        if (!properties.getAlgo().isEnabled()) {
            return;
        }
        Instant now = Instant.now();
        Duration lease = properties.getAlgo().getClaimLease();
        var due = store.query(0, "", now.toEpochMilli(),
                        Math.max(1, properties.getAlgo().getClaimBatchSize())).stream()
                .filter(value -> value.statusCode() == AlgoOrderStatus.PENDING.ordinal()
                        || value.statusCode() == AlgoOrderStatus.RUNNING.ordinal()
                        || value.statusCode() == AlgoOrderStatus.CANCEL_REQUESTED.ordinal())
                .toList();
        for (var candidate : due) {
            AlgoOrderRecord record = store.record(candidate);
            AlgoOrderRecord claimed = withStatus(record, record.status() == AlgoOrderStatus.CANCEL_REQUESTED
                    ? AlgoOrderStatus.CANCEL_REQUESTED : AlgoOrderStatus.RUNNING, record.rejectReason(),
                    now.plus(lease), null, now, record.currentOrderId());
            var claimedView = store.tryClaim(candidate, claimed);
            if (claimedView == null) {
                continue;
            }
            try {
                executeDue(store.record(claimedView), now);
            } catch (RuntimeException ex) {
                // A lost placement/cancellation reply cannot prove failure. Keep the claim;
                // the next attempt recovers its immutable child key before doing more work.
                LOG.warn("algo step awaits recovery userId={} algoOrderId={}", claimedView.userId(), claimedView.algoOrderId(), ex);
            }
        }
    }

    void executeDue(AlgoOrderRecord record, Instant now) {
        var current = store.get(record.userId(), record.algoOrderId());
        if (current.statusCode() == AlgoOrderStatus.CANCEL_REQUESTED.ordinal()) {
            cancelRecord(store.record(current), null);
            return;
        }
        if (isTerminal(AlgoOrderStatus.values()[current.statusCode()])) return;
        current = recoverUnlinkedChild(current);
        record = store.record(current);
        AlgoOrderProgress progress = store.progress(current);
        if (progress.executedQuantitySteps() >= record.quantitySteps()
                && progress.activeChildOrderCount() == 0) {
            store.update(withStatus(record, AlgoOrderStatus.COMPLETED, null,
                    null, now, now, record.currentOrderId()), current.revision(), 0);
            return;
        }
        if (!now.isBefore(record.startAt().plusSeconds(record.durationSeconds()))) {
            cancelRecord(record, "执行时长已结束");
            return;
        }
        if (progress.activeChildOrderCount() > 0) {
            store.update(withStatus(record, AlgoOrderStatus.RUNNING, null,
                    now.plusMillis(properties.getAlgo().getScanDelayMs()), null, now, record.currentOrderId()),
                            current.revision(), 0);
            return;
        }

        long remainingTarget = Math.subtractExact(record.quantitySteps(), progress.executedQuantitySteps());
        if (remainingTarget <= 0L) {
            store.update(withStatus(record, AlgoOrderStatus.COMPLETED, null,
                    null, now, now, record.currentOrderId()), current.revision(), 0);
            return;
        }
        long childQuantity = Math.min(record.childQuantitySteps(), remainingTarget);
        PlaceOrderRequest childRequest = childRequest(record, progress.nextSliceIndex(), childQuantity);
        OrderResponse child = orderService.placeAlgoChild(childRequest);
        if (child.status() == OrderStatus.REJECTED) {
            store.update(withStatus(record, AlgoOrderStatus.FAILED, child.rejectReason(),
                    null, now, now, null), current.revision(), 0);
            return;
        }
        Instant nextSliceAt = nextSliceAt(record, now);
        AlgoOrderRecord updated = withStatus(record, AlgoOrderStatus.RUNNING, null, nextSliceAt,
                null, now, child.orderId());
        store.update(updated, current.revision(), child.orderId());
    }

    // Share the existing scheduler monitor: finish linking any locally submitted child
    // before recording cancellation, then refresh the revision and child identities.
    private synchronized AlgoOrderResponse cancelRecord(AlgoOrderRecord record, String requestedReason) {
        var current = store.get(record.userId(), record.algoOrderId());
        record = store.record(current);
        requireCurrentProductLine(record);
        if (isTerminal(record.status())) {
            return store.response(current);
        }
        Instant now = Instant.now();
        String reason = requestedReason == null ? record.rejectReason() : requestedReason;
        AlgoOrderRecord requested = withStatus(record, AlgoOrderStatus.CANCEL_REQUESTED, reason,
                now.plusSeconds(1), null, now, record.currentOrderId());
        store.update(requested, current.revision(), 0);
        current = recoverUnlinkedChild(store.get(record.userId(), record.algoOrderId()));
        RuntimeException cancellationFailure = null;
        for (AlgoOrderChild child : store.children(current)) {
            try {
                orderService.cancel(new CancelOrderRequest(record.userId(), child.orderId()));
            } catch (RuntimeException failure) {
                cancellationFailure = failure;
            }
        }
        current = store.get(record.userId(), record.algoOrderId());
        if (isTerminal(AlgoOrderStatus.values()[current.statusCode()])) return store.response(current);
        if (current.activeChildOrderCount() > 0) {
            if (cancellationFailure != null) throw cancellationFailure;
            return store.response(current);
        }
        AlgoOrderRecord canceled = withStatus(store.record(current), AlgoOrderStatus.CANCELED, reason,
                null, now, Instant.now(), current.currentOrderId() == 0 ? null : current.currentOrderId());
        store.update(canceled, current.revision(), 0);
        return store.response(store.get(canceled.userId(), canceled.algoOrderId()));
    }

    private com.surprising.aeron.protocol.CoreAlgoOrderView recoverUnlinkedChild(com.surprising.aeron.protocol.CoreAlgoOrderView parent) {
        var child = store.unlinkedChild(parent);
        if (child == null) return parent;
        String key = childClientOrderId(parent.algoOrderId(), parent.childOrderIds().size());
        if (child.userId() != parent.userId() || !child.instrumentId().equals(parent.instrumentId())
                || !key.equals(child.clientOrderId())) throw new IllegalStateException("algo child recovery identity mismatch");
        var record = store.record(parent);
        store.update(withStatus(record, record.status(), record.rejectReason(), record.nextSliceAt(),
                record.completedAt(), Instant.now(), child.orderId()), parent.revision(), child.orderId());
        return store.get(parent.userId(), parent.algoOrderId());
    }

    private PlaceOrderRequest childRequest(AlgoOrderRecord record, int sliceIndex, long quantitySteps) {
        OrderType orderType = record.priceTicks() > 0 ? OrderType.LIMIT : OrderType.MARKET;
        TimeInForce timeInForce = orderType == OrderType.MARKET ? TimeInForce.IOC : record.timeInForce();
        return new PlaceOrderRequest(record.userId(), childClientOrderId(record.algoOrderId(), sliceIndex),
                record.instrumentId(), record.side(), orderType, timeInForce,
                orderType == OrderType.MARKET ? 0L : record.priceTicks(), quantitySteps,
                record.marginMode(), record.positionSide(), record.reduceOnly(), record.postOnly());
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
                order.clientAlgoOrderId(), order.instrumentId(), order.algoType(), order.side(), order.priceTicks(),
                order.quantitySteps(), order.childQuantitySteps(), order.intervalSeconds(), order.durationSeconds(),
                order.marginMode(), order.positionSide(), order.reduceOnly(), order.timeInForce(),
                status, currentOrderId, reason, order.startAt(), nextSliceAt, completedAt,
                order.createdAt(), now);
    }


    private TimeInForce normalizeTimeInForce(PlaceAlgoOrderRequest request) {
        if (request.algoType() == AlgoOrderType.TWAP) {
            return TimeInForce.IOC;
        }
        if (request.priceTicks() <= 0) {
            throw new IllegalArgumentException("ICEBERG requires limit priceTicks");
        }
        TimeInForce tif = request.timeInForce() == null ? TimeInForce.GTC : request.timeInForce();
        if (tif != TimeInForce.GTC && tif != TimeInForce.GTX) {
            throw new IllegalArgumentException("ICEBERG timeInForce must be GTC or GTX");
        }
        return request.postOnly() ? TimeInForce.GTX : tif;
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

    private String normalizeSymbol(String instrumentId) {
        if (instrumentId == null || instrumentId.isBlank()) {
            throw new IllegalArgumentException("instrumentId is required");
        }
        String normalized = instrumentId.trim().toUpperCase(Locale.ROOT);
        if (!com.surprising.product.api.utils.InstrumentIds.valid(normalized)) {
            throw new IllegalArgumentException("invalid instrumentId: " + instrumentId);
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
