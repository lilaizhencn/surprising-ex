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
 * <p>算法单和普通订单共用同一个用户分区 WAL、状态快照和单写入 lane。算法单仓储表只作为
 * 异步投影/历史查询，不参与幂等、切片进度或撤单裁决。</p>
 */
@Service
public class AlgoOrderService {

    private static final int MAX_OPEN_CANCEL_LIMIT = 1000;

    private final TradingOrderProperties properties;
    private final OrderService orderService;
    private final OrderUserStateService orderUserStateService;
    private final OrderScheduleIndex scheduleIndex;

    @Autowired
    public AlgoOrderService(TradingOrderProperties properties,
                            OrderService orderService,
                            OrderUserStateService orderUserStateService,
                            OrderScheduleIndex scheduleIndex) {
        this.properties = properties;
        this.orderService = orderService;
        this.orderUserStateService = orderUserStateService;
        this.scheduleIndex = scheduleIndex;
    }

    public AlgoOrderResponse place(PlaceAlgoOrderRequest request) {
        PlaceAlgoOrderRequest normalized = normalize(request);
        ProductLine productLine = currentProductLine();
        requireLocalProductLine(productLine);
        Instant now = Instant.now();
        Instant startAt = normalized.startAt() == null || normalized.startAt().isBefore(now)
                ? now : normalized.startAt();
        AlgoOrderRecord record = new AlgoOrderRecord(
                orderUserStateService.nextOrderId(), productLine, normalized.userId(), normalized.clientAlgoOrderId(),
                normalized.symbol(), normalized.algoType(), normalized.side(), normalized.priceTicks(),
                normalized.quantitySteps(), normalized.childQuantitySteps(), normalized.intervalSeconds(),
                normalized.durationSeconds(), normalized.marginMode(), normalized.positionSide(), normalized.reduceOnly(),
                normalized.postOnly(), normalized.timeInForce(), AlgoOrderStatus.PENDING, null, null,
                TraceContext.currentOrCreate(), startAt, startAt, null, now, now);
        AlgoOrderResponse response = orderUserStateService.placeAlgo(record);
        scheduleIndex.synchronizeAlgo(record);
        return response;
    }

    public AlgoOrderResponse cancel(CancelAlgoOrderRequest request) {
        if (request == null || request.userId() <= 0 || request.algoOrderId() <= 0) {
            throw new IllegalArgumentException("userId and algoOrderId must be positive");
        }
        AlgoOrderRecord record = orderUserStateService.algo(request.userId(), request.algoOrderId());
        requireCurrentProductLine(record);
        if (isTerminal(record.status())) {
            return orderUserStateService.algoResponse(record);
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
        List<AlgoOrderResponse> canceled = orderUserStateService.openAlgos(request.userId(), symbol, limit).stream()
                .map(value -> cancel(new CancelAlgoOrderRequest(value.userId(), value.algoOrderId())))
                .toList();
        List<AlgoOrderBatchItemResponse> results = new ArrayList<>(canceled.size());
        for (int i = 0; i < canceled.size(); i++) {
            results.add(new AlgoOrderBatchItemResponse(i, true, "cancel requested", canceled.get(i)));
        }
        return batchResponse(results);
    }

    public AlgoOrderResponse get(long algoOrderId) {
        AlgoOrderRecord record = orderUserStateService.algoById(algoOrderId);
        requireCurrentProductLine(record);
        return orderUserStateService.algoResponse(record);
    }

    public int cancelLifecycleOrders(String symbol, int limit) {
        String normalizedSymbol = normalizeSymbol(symbol);
        List<AlgoOrderRecord> orders = orderUserStateService.lifecycleAlgos(
                currentProductLine(), normalizedSymbol, Math.max(1, Math.min(limit, MAX_OPEN_CANCEL_LIMIT)));
        orders.forEach(this::cancelRecord);
        return orders.size();
    }

    public boolean hasLifecycleActiveOrders(String symbol) {
        return orderUserStateService.hasLifecycleActiveAlgos(currentProductLine(), normalizeSymbol(symbol));
    }

    public AlgoOrderQueryResponse openOrders(long userId, String symbol, int limit) {
        if (userId <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
        if (limit < 1 || limit > MAX_OPEN_CANCEL_LIMIT) {
            throw new IllegalArgumentException("limit must be in [1, 1000]");
        }
        List<AlgoOrderResponse> orders = orderUserStateService.openAlgos(userId, symbol, limit);
        return new AlgoOrderQueryResponse(orders.size(), orders);
    }

    public void scanDueAlgoOrders() {
        if (!properties.getAlgo().isEnabled()) {
            return;
        }
        Instant now = Instant.now();
        Duration lease = properties.getRedisIndex().getAlgoClaimLease();
        List<AlgoOrderRecord> due = orderUserStateService.claimDueAlgos(
                currentProductLine(), now, Math.max(1, properties.getAlgo().getClaimBatchSize()), lease);
        for (AlgoOrderRecord record : due) {
            try {
                executeDue(record, now);
            } catch (RuntimeException ex) {
                orderUserStateService.updateAlgo(withStatus(record, AlgoOrderStatus.FAILED, ex.getMessage(),
                        null, now, now, record.currentOrderId()));
            }
        }
    }

    void executeDue(AlgoOrderRecord record, Instant now) {
        AlgoOrderProgress progress = orderUserStateService.algoProgress(record.userId(), record.algoOrderId());
        if (progress.executedQuantitySteps() >= record.quantitySteps()
                && progress.activeChildOrderCount() == 0) {
            orderUserStateService.updateAlgo(withStatus(record, AlgoOrderStatus.COMPLETED, null,
                    null, now, now, record.currentOrderId()));
            scheduleIndex.removeAlgo(record.productLine(), record.algoOrderId());
            return;
        }
        if (progress.activeChildOrderCount() > 0) {
            orderUserStateService.updateAlgo(withStatus(record, AlgoOrderStatus.RUNNING, null,
                    now.plusMillis(properties.getAlgo().getScanDelayMs()), null, now, record.currentOrderId()));
            return;
        }

        long remainingTarget = Math.subtractExact(record.quantitySteps(), progress.executedQuantitySteps());
        if (remainingTarget <= 0L) {
            orderUserStateService.updateAlgo(withStatus(record, AlgoOrderStatus.COMPLETED, null,
                    null, now, now, record.currentOrderId()));
            scheduleIndex.removeAlgo(record.productLine(), record.algoOrderId());
            return;
        }
        long childQuantity = Math.min(record.childQuantitySteps(), remainingTarget);
        PlaceOrderRequest childRequest = childRequest(record, progress.nextSliceIndex(), childQuantity);
        OrderResponse child = orderService.place(childRequest);
        if (child.status() == OrderStatus.REJECTED) {
            orderUserStateService.updateAlgo(withStatus(record, AlgoOrderStatus.FAILED, child.rejectReason(),
                    null, now, now, null));
            return;
        }
        Instant nextSliceAt = nextSliceAt(record, now);
        AlgoOrderRecord updated = withStatus(record, AlgoOrderStatus.RUNNING, null, nextSliceAt,
                null, now, child.orderId());
        orderUserStateService.linkAlgoChild(updated,
                new AlgoOrderChild(record.algoOrderId(), progress.nextSliceIndex(), child.orderId(), childQuantity));
        scheduleIndex.synchronizeAlgo(updated);
    }

    private AlgoOrderResponse cancelRecord(AlgoOrderRecord record) {
        if (isTerminal(record.status())) {
            return orderUserStateService.algoResponse(record);
        }
        Instant now = Instant.now();
        AlgoOrderRecord requested = withStatus(record, AlgoOrderStatus.CANCEL_REQUESTED, null,
                null, null, now, record.currentOrderId());
        orderUserStateService.updateAlgo(requested);
        for (AlgoOrderChild child : orderUserStateService.algoChildren(record.userId(), record.algoOrderId())) {
            try {
                orderService.cancel(new CancelOrderRequest(record.userId(), child.orderId()));
            } catch (RuntimeException ignored) {
                // 子单已终态时继续收敛算法单终态，订单事实流负责最终幂等裁决。
            }
        }
        AlgoOrderRecord canceled = withStatus(requested, AlgoOrderStatus.CANCELED, null,
                null, now, Instant.now(), record.currentOrderId());
        orderUserStateService.updateAlgo(canceled);
        scheduleIndex.removeAlgo(record.productLine(), record.algoOrderId());
        return orderUserStateService.algoResponse(canceled);
    }

    private PlaceOrderRequest childRequest(AlgoOrderRecord record, int sliceIndex, long quantitySteps) {
        OrderType orderType = record.priceTicks() > 0 ? OrderType.LIMIT : OrderType.MARKET;
        TimeInForce timeInForce = orderType == OrderType.MARKET ? TimeInForce.IOC : record.timeInForce();
        return new PlaceOrderRequest(record.userId(), childClientOrderId(record.algoOrderId(), sliceIndex),
                record.symbol(), record.side(), orderType, timeInForce,
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
        if (productLine != ProductLine.LINEAR_PERPETUAL) {
            throw new IllegalStateException("产品线尚未接入本地订单事实流: " + productLine);
        }
    }

    private void requireCurrentProductLine(AlgoOrderRecord order) {
        if (order.productLine() != currentProductLine()) {
            throw new IllegalStateException("算法单不属于当前产品线: " + order.algoOrderId());
        }
    }
}
