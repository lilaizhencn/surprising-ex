package com.surprising.trading.order.service;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.AdminBatchCancelOrdersRequest;
import com.surprising.trading.api.model.AdminCancelByInstrumentRequest;
import com.surprising.trading.api.model.AdminCancelOrderResult;
import com.surprising.trading.api.model.AdminCancelOrdersPreviewResponse;
import com.surprising.trading.api.model.AdminCancelOrdersResponse;
import com.surprising.trading.api.model.CancelOrderRequest;
import com.surprising.trading.api.model.OrderBatchItemResponse;
import com.surprising.trading.api.model.OrderResponse;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.OrderStatus;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Administrative order cancellation, preview, and instrument-lifecycle drain operations.
 */
@Service
public class OrderAdminService {

    private final OrderService orderService;
    private final OrderQueryService orderQueryService;

    @Autowired
    public OrderAdminService(OrderService orderService, OrderQueryService orderQueryService) {
        this.orderService = orderService;
        this.orderQueryService = orderQueryService;
    }

    public AdminCancelOrderResult adminCancelOrder(long orderId, String reason) {
        return adminCancelOrder(orderId, reason, null);
    }

    public AdminCancelOrderResult adminCancelOrder(long orderId, String reason, ProductLine productLine) {
        OrderService.requireOrderId(orderId);
        ProductLine resolved = productLine == null ? orderQueryService.currentProductLine() : productLine;
        orderService.requireCurrentProductLine(resolved);
        OrderResponse selected = orderQueryService.findOrderForAdminCancel(resolved, orderId);
        orderService.requireAeron();
        OrderResponse canceled = orderService.cancelById(selected.userId(), selected.orderId());
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
                : OrderService.normalizeInstrumentId(request.instrumentId());
        int limit = request == null || request.limit() == null ? 100 : request.limit();
        if (limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("limit must be in [1, 1000]");
        }
        adminCancelReason(request == null ? null : request.reason());
        ProductLine resolved = productLine == null ? orderQueryService.currentProductLine() : productLine;
        orderService.requireCurrentProductLine(resolved);
        List<OrderResponse> selected = orderQueryService.activeOpenOrders(resolved, userId, instrumentId, limit);
        orderService.requireAeron();
        List<CancelOrderRequest> requests = selected.stream()
                .map(order -> new CancelOrderRequest(order.userId(), order.orderId()))
                .toList();
        List<OrderBatchItemResponse> canceled = orderService.cancelOrderChunks(requests);
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
        String normalizedInstrumentId = instrumentId == null || instrumentId.isBlank() ? null : OrderService.normalizeInstrumentId(instrumentId);
        ProductLine resolvedProductLine = productLine == null ? orderQueryService.currentProductLine() : productLine;
        List<OrderResponse> orders = orderQueryService.activeOpenOrders(resolvedProductLine, userId, normalizedInstrumentId, limit);
        long quantity = orders.stream().mapToLong(OrderResponse::remainingQuantitySteps).sum();
        int buys = (int) orders.stream().filter(order -> order.side() == OrderSide.BUY).count();
        return new AdminCancelOrdersPreviewResponse(userId, normalizedInstrumentId, orders.size(), orders.size(),
                quantity, buys, orders.size() - buys, orders);
    }

    public AdminCancelOrdersResponse adminCancelByInstrument(AdminCancelByInstrumentRequest request) {
        return adminCancelByInstrument(request, null);
    }

    public AdminCancelOrdersResponse adminCancelByInstrument(AdminCancelByInstrumentRequest request, ProductLine productLine) {
        if (request == null) {
            throw new IllegalArgumentException("request is required");
        }
        String instrumentId = OrderService.normalizeInstrumentId(request.instrumentId());
        return adminCancelOrders(new AdminBatchCancelOrdersRequest(null, instrumentId, request.limit(), request.reason()),
                productLine);
    }

    /**
     * 到期生命周期只发起撤单，最终状态仍由撮合结果驱动。
     */
    public int requestLifecycleCancellation(String instrumentId, int limit) {
        String normalizedInstrumentId = OrderService.normalizeInstrumentId(instrumentId);
        List<OrderResponse> selected = orderQueryService.lifecycleOpenOrders(normalizedInstrumentId, limit);
        List<CancelOrderRequest> requests = selected.stream()
                .map(order -> new CancelOrderRequest(order.userId(), order.orderId()))
                .toList();
        return (int) orderService.cancelOrderChunks(requests).stream()
                .filter(item -> item.success() && item.order() != null && cancelSucceeded(item.order().status()))
                .count();
    }

    public boolean hasLifecycleActiveOrders(String instrumentId) {
        return !orderQueryService.lifecycleOpenOrders(OrderService.normalizeInstrumentId(instrumentId), 1).isEmpty();
    }

    private static String adminCancelReason(String reason) {
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
}
