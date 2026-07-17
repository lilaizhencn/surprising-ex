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
import com.surprising.trading.order.model.AlgoOrderProgress;
import com.surprising.trading.order.model.AlgoOrderRecord;
import com.surprising.trading.order.repository.AlgoOrderRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class AlgoOrderService {

    private static final Logger log = LoggerFactory.getLogger(AlgoOrderService.class);
    private static final int MAX_OPEN_CANCEL_LIMIT = 1000;

    private final TradingOrderProperties properties;
    private final AlgoOrderRepository algoOrderRepository;
    private final OrderService orderService;
    private final OrderScheduleIndex scheduleIndex;

    public AlgoOrderService(TradingOrderProperties properties,
                            AlgoOrderRepository algoOrderRepository,
                            OrderService orderService) {
        this(properties, algoOrderRepository, orderService, OrderScheduleIndex.disabled());
    }

    @Autowired
    public AlgoOrderService(TradingOrderProperties properties,
                            AlgoOrderRepository algoOrderRepository,
                            OrderService orderService,
                            OrderScheduleIndex scheduleIndex) {
        this.properties = properties;
        this.algoOrderRepository = algoOrderRepository;
        this.orderService = orderService;
        this.scheduleIndex = scheduleIndex;
    }

    @Transactional
    public AlgoOrderResponse place(PlaceAlgoOrderRequest request) {
        PlaceAlgoOrderRequest normalized = normalize(request);
        ProductLine productLine = currentProductLine();
        if (normalized.clientAlgoOrderId() != null && !normalized.clientAlgoOrderId().isBlank()) {
            var existing = algoOrderRepository.findByClientAlgoOrderId(
                    productLine, normalized.userId(), normalized.clientAlgoOrderId().trim());
            if (existing.isPresent()) {
                AlgoOrderRecord existingOrder = existing.get();
                requireAlgoOrderCurrentProductLine(existingOrder);
                return toResponse(existingOrder);
            }
        }
        Instant now = Instant.now();
        long algoOrderId = algoOrderRepository.nextAlgoOrderId();
        Instant startAt = normalized.startAt() == null || normalized.startAt().isBefore(now)
                ? now
                : normalized.startAt();
        AlgoOrderRecord record = new AlgoOrderRecord(
                algoOrderId,
                productLine,
                normalized.userId(),
                emptyToNull(normalized.clientAlgoOrderId()),
                normalized.symbol(),
                normalized.algoType(),
                normalized.side(),
                normalized.priceTicks(),
                normalized.quantitySteps(),
                normalized.childQuantitySteps(),
                normalized.intervalSeconds(),
                normalized.durationSeconds(),
                normalized.marginMode(),
                normalized.positionSide(),
                normalized.reduceOnly(),
                normalized.postOnly(),
                normalized.timeInForce(),
                AlgoOrderStatus.PENDING,
                null,
                null,
                TraceContext.currentOrCreate(),
                startAt,
                startAt,
                null,
                now,
                now);
        boolean inserted = algoOrderRepository.insert(record);
        if (!inserted && record.clientAlgoOrderId() != null) {
            return algoOrderRepository.findByClientAlgoOrderId(productLine, record.userId(), record.clientAlgoOrderId())
                    .map(existing -> {
                        requireAlgoOrderCurrentProductLine(existing);
                        return toResponse(existing);
                    })
                    .orElseThrow(() -> new IllegalStateException("duplicate clientAlgoOrderId but algo order not found"));
        }
        if (!inserted) {
            throw new IllegalStateException("failed to insert algo order " + algoOrderId);
        }
        afterCommit(() -> scheduleIndex.synchronizeAlgo(record));
        return toResponse(record);
    }

    @Transactional
    public AlgoOrderResponse cancel(CancelAlgoOrderRequest request) {
        if (request == null || request.userId() <= 0 || request.algoOrderId() <= 0) {
            throw new IllegalArgumentException("userId and algoOrderId must be positive");
        }
        AlgoOrderRecord record = algoOrderRepository.findByAlgoOrderId(request.algoOrderId())
                .orElseThrow(() -> new IllegalStateException("algo order not found: " + request.algoOrderId()));
        requireAlgoOrderCurrentProductLine(record);
        if (record.userId() != request.userId()) {
            throw new IllegalArgumentException("algo order does not belong to user");
        }
        if (isTerminal(record.status())) {
            return toResponse(record);
        }
        cancelRecord(record);
        afterCommit(() -> scheduleIndex.removeAlgo(record.productLine(), record.algoOrderId()));
        return get(request.algoOrderId());
    }

    @Transactional
    public AlgoOrderBatchResponse cancelOpen(CancelOpenAlgoOrdersRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("cancel open algo request is required");
        }
        if (request.userId() <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
        int limit = request.limit() == null ? MAX_OPEN_CANCEL_LIMIT : request.limit();
        if (limit < 1 || limit > MAX_OPEN_CANCEL_LIMIT) {
            throw new IllegalArgumentException("limit must be in [1, 1000]");
        }
        String symbol = request.symbol() == null || request.symbol().isBlank()
                ? null
                : normalizeSymbol(request.symbol());
        String contractType = currentProductContractType();
        List<AlgoOrderRecord> orders = contractType == null
                ? algoOrderRepository.cancelableOpenOrders(request.userId(), symbol, request.algoType(), limit)
                : algoOrderRepository.cancelableOpenOrders(
                        request.userId(), symbol, request.algoType(), limit, contractType);
        List<AlgoOrderBatchItemResponse> results = new ArrayList<>();
        for (int i = 0; i < orders.size(); i++) {
            try {
                cancelRecord(orders.get(i));
                results.add(new AlgoOrderBatchItemResponse(i, true, "cancel requested",
                        toResponse(algoOrderRepository.findByAlgoOrderId(orders.get(i).algoOrderId())
                                .orElse(orders.get(i)))));
            } catch (RuntimeException ex) {
                results.add(new AlgoOrderBatchItemResponse(i, false, ex.getMessage(), null));
            }
        }
        return batchResponse(results);
    }

    public AlgoOrderResponse get(long algoOrderId) {
        return algoOrderRepository.findByAlgoOrderId(algoOrderId)
                .map(order -> {
                    requireAlgoOrderCurrentProductLine(order);
                    return toResponse(order);
                })
                .orElseThrow(() -> new IllegalStateException("algo order not found: " + algoOrderId));
    }

    public AlgoOrderQueryResponse openOrders(long userId, String symbol, int limit) {
        if (userId <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
        if (limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("limit must be in [1, 1000]");
        }
        String normalizedSymbol = symbol == null || symbol.isBlank() ? null : normalizeSymbol(symbol);
        String contractType = currentProductContractType();
        List<AlgoOrderResponse> orders = algoOrderRepository.openOrders(userId, normalizedSymbol, limit, contractType)
                .stream()
                .map(this::toResponse)
                .toList();
        return new AlgoOrderQueryResponse(orders.size(), orders);
    }

    @Scheduled(fixedDelayString = "${surprising.trading.order.algo.scan-delay-ms:250}")
    @Transactional
    public void scanDueAlgoOrders() {
        if (!properties.getAlgo().isEnabled()) {
            return;
        }
        int limit = Math.max(1, properties.getAlgo().getClaimBatchSize());
        Instant now = Instant.now();
        List<AlgoOrderRecord> due = scheduleIndex.dueAlgos(currentProductLine(), now, limit)
                .map(ids -> ids.stream()
                        .map(id -> algoOrderRepository.claimDueOrder(currentProductLine(), id, now,
                                now.plus(properties.getRedisIndex().getAlgoClaimLease())))
                        .flatMap(java.util.Optional::stream)
                        .toList())
                .orElseGet(() -> algoOrderRepository.dueOrders(currentProductLine(), now, limit));
        for (AlgoOrderRecord record : due) {
            try {
                executeDue(record, now);
            } catch (RuntimeException ex) {
                algoOrderRepository.markFailed(record.algoOrderId(), ex.getMessage(), now);
                log.warn("algo order execution failed algoOrderId={}", record.algoOrderId(), ex);
            } finally {
                synchronizeAfterCommit(record.algoOrderId());
            }
        }
    }

    void executeDue(AlgoOrderRecord record, Instant now) {
        algoOrderRepository.refreshChildStatuses(record.algoOrderId(), now);
        AlgoOrderProgress progress = algoOrderRepository.progress(record.algoOrderId());
        if (progress.executedQuantitySteps() >= record.quantitySteps()
                && progress.activeChildOrderCount() == 0) {
            algoOrderRepository.markCompleted(record.algoOrderId(), now);
            return;
        }
        if (progress.activeChildOrderCount() > 0) {
            algoOrderRepository.scheduleNext(record.algoOrderId(), AlgoOrderStatus.RUNNING,
                    now.plusMillis(properties.getAlgo().getScanDelayMs()), now);
            return;
        }

        long remainingTarget = Math.subtractExact(record.quantitySteps(), progress.executedQuantitySteps());
        if (remainingTarget <= 0) {
            algoOrderRepository.markCompleted(record.algoOrderId(), now);
            return;
        }

        long childQuantity = Math.min(record.childQuantitySteps(), remainingTarget);
        OrderResponse child = orderService.place(childRequest(record, progress.nextSliceIndex(), childQuantity));
        if (child.status() == OrderStatus.REJECTED) {
            algoOrderRepository.markFailed(record.algoOrderId(), child.rejectReason(), now);
            return;
        }
        Instant nextSliceAt = nextSliceAt(record, now);
        algoOrderRepository.markChildPlaced(record, progress.nextSliceIndex(), child, now, nextSliceAt);
    }

    private PlaceOrderRequest childRequest(AlgoOrderRecord record, int sliceIndex, long quantitySteps) {
        OrderType orderType = record.priceTicks() > 0 ? OrderType.LIMIT : OrderType.MARKET;
        TimeInForce timeInForce = orderType == OrderType.MARKET ? TimeInForce.IOC : record.timeInForce();
        return new PlaceOrderRequest(
                record.userId(),
                childClientOrderId(record.algoOrderId(), sliceIndex),
                record.symbol(),
                record.side(),
                orderType,
                timeInForce,
                orderType == OrderType.MARKET ? 0L : record.priceTicks(),
                quantitySteps,
                record.marginMode(),
                record.positionSide(),
                record.reduceOnly(),
                record.postOnly());
    }

    private Instant nextSliceAt(AlgoOrderRecord record, Instant now) {
        if (record.algoType() == AlgoOrderType.TWAP) {
            return now.plusSeconds(record.intervalSeconds());
        }
        return now.plusMillis(properties.getAlgo().getScanDelayMs());
    }

    private void cancelRecord(AlgoOrderRecord record) {
        Instant now = Instant.now();
        algoOrderRepository.markCancelRequested(record.algoOrderId(), now);
        for (var child : algoOrderRepository.activeChildOrders(record.algoOrderId())) {
            try {
                orderService.cancel(new CancelOrderRequest(child.userId(), child.orderId()));
            } catch (RuntimeException ex) {
                log.warn("algo child cancel failed algoOrderId={} orderId={}",
                        record.algoOrderId(), child.orderId(), ex);
            }
        }
        algoOrderRepository.markCanceled(record.algoOrderId(), Instant.now());
        afterCommit(() -> scheduleIndex.removeAlgo(record.productLine(), record.algoOrderId()));
    }

    private AlgoOrderResponse toResponse(AlgoOrderRecord record) {
        algoOrderRepository.refreshChildStatuses(record.algoOrderId(), Instant.now());
        AlgoOrderProgress progress = algoOrderRepository.progress(record.algoOrderId());
        return new AlgoOrderResponse(
                record.algoOrderId(),
                record.userId(),
                record.clientAlgoOrderId(),
                record.symbol(),
                record.algoType(),
                record.side(),
                record.priceTicks(),
                record.quantitySteps(),
                record.childQuantitySteps(),
                record.intervalSeconds(),
                record.durationSeconds(),
                record.marginMode(),
                record.positionSide(),
                record.reduceOnly(),
                record.postOnly(),
                record.timeInForce(),
                record.status(),
                progress.executedQuantitySteps(),
                progress.activeQuantitySteps(),
                progress.childOrderCount(),
                record.currentOrderId(),
                record.rejectReason(),
                record.startAt(),
                record.nextSliceAt(),
                record.completedAt(),
                record.createdAt(),
                record.updatedAt());
    }

    private PlaceAlgoOrderRequest normalize(PlaceAlgoOrderRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("algo order request is required");
        }
        if (request.userId() <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
        String symbol = normalizeSymbol(request.symbol());
        if (request.algoType() == null) {
            throw new IllegalArgumentException("algoType is required");
        }
        if (request.side() == null) {
            throw new IllegalArgumentException("side is required");
        }
        if (request.quantitySteps() <= 0 || request.childQuantitySteps() <= 0) {
            throw new IllegalArgumentException("quantitySteps and childQuantitySteps must be positive");
        }
        if (request.childQuantitySteps() > request.quantitySteps()) {
            throw new IllegalArgumentException("childQuantitySteps must be <= quantitySteps");
        }
        long intervalSeconds = request.intervalSeconds();
        long durationSeconds = request.durationSeconds();
        validateRange(intervalSeconds, properties.getAlgo().getMinIntervalSeconds(),
                properties.getAlgo().getMaxIntervalSeconds(), "intervalSeconds");
        validateRange(durationSeconds, properties.getAlgo().getMinDurationSeconds(),
                properties.getAlgo().getMaxDurationSeconds(), "durationSeconds");
        if (durationSeconds < intervalSeconds) {
            throw new IllegalArgumentException("durationSeconds must be >= intervalSeconds");
        }
        if (request.algoType() == AlgoOrderType.TWAP) {
            long maxSlices = (durationSeconds + intervalSeconds - 1L) / intervalSeconds;
            long minChildQuantity = (request.quantitySteps() + maxSlices - 1L) / maxSlices;
            if (request.childQuantitySteps() < minChildQuantity) {
                throw new IllegalArgumentException("childQuantitySteps is too small to finish TWAP inside durationSeconds");
            }
        }
        MarginMode marginMode = MarginMode.defaultIfNull(request.marginMode());
        PositionSide positionSide = PositionSide.defaultIfNull(request.positionSide());
        TimeInForce tif = normalizeTimeInForce(request);
        boolean postOnly = request.algoType() == AlgoOrderType.ICEBERG && (request.postOnly() || tif == TimeInForce.GTX);
        if (request.algoType() == AlgoOrderType.TWAP && postOnly) {
            throw new IllegalArgumentException("TWAP does not support postOnly; use limit IOC child orders");
        }
        return new PlaceAlgoOrderRequest(
                request.userId(),
                emptyToNull(request.clientAlgoOrderId()),
                symbol,
                request.algoType(),
                request.side(),
                request.priceTicks(),
                request.quantitySteps(),
                request.childQuantitySteps(),
                intervalSeconds,
                durationSeconds,
                marginMode,
                positionSide,
                request.reduceOnly(),
                postOnly,
                tif,
                request.startAt());
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

    private void validateRange(long value, long min, long max, String field) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(field + " must be in [" + min + ", " + max + "]");
        }
    }

    private String childClientOrderId(long algoOrderId, int sliceIndex) {
        return "algo-" + algoOrderId + "-" + sliceIndex;
    }

    private boolean isTerminal(AlgoOrderStatus status) {
        return status == AlgoOrderStatus.CANCELED
                || status == AlgoOrderStatus.COMPLETED
                || status == AlgoOrderStatus.FAILED;
    }

    private void synchronizeAfterCommit(long algoOrderId) {
        afterCommit(() -> algoOrderRepository.findByAlgoOrderId(algoOrderId)
                .ifPresent(scheduleIndex::synchronizeAlgo));
    }

    private void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { action.run(); }
        });
    }

    private AlgoOrderBatchResponse batchResponse(List<AlgoOrderBatchItemResponse> results) {
        int completed = (int) results.stream().filter(AlgoOrderBatchItemResponse::success).count();
        return new AlgoOrderBatchResponse(results.size(), completed, results.size() - completed, results);
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

    private String currentProductContractType() {
        return properties.getKafka().isProductTopicsEnabled() ? contractType(currentProductLine()) : null;
    }

    private String contractType(ProductLine productLine) {
        return productLine == null ? null : productLine.contractTypeCode();
    }

    private ProductLine currentProductLine() {
        return properties.getKafka().getProductLine();
    }

    private void requireAlgoOrderCurrentProductLine(AlgoOrderRecord order) {
        String contractType = currentProductContractType();
        if (contractType == null) {
            return;
        }
        if (!algoOrderRepository.algoOrderMatchesContractType(order.algoOrderId(), contractType)) {
            throw new IllegalStateException("algo order not found: " + order.algoOrderId());
        }
    }
}
