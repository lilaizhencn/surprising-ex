package com.surprising.trading.order.controller;

import com.surprising.gateway.provider.product.GatewayUserContext;
import com.surprising.trading.api.model.AmendOrderRequest;
import com.surprising.trading.api.model.BatchAmendOrdersRequest;
import com.surprising.trading.api.model.BatchCancelOrdersRequest;
import com.surprising.trading.api.model.BatchCancelTriggerOrdersRequest;
import com.surprising.trading.api.model.BatchPlaceOrderRequest;
import com.surprising.trading.api.model.BatchPlaceTriggerOrderRequest;
import com.surprising.trading.api.model.CancelAlgoOrderRequest;
import com.surprising.trading.api.model.CancelAllAfterRequest;
import com.surprising.trading.api.model.CancelOpenAlgoOrdersRequest;
import com.surprising.trading.api.model.CancelOpenOrdersRequest;
import com.surprising.trading.api.model.CancelOpenTriggerOrdersRequest;
import com.surprising.trading.api.model.CancelOrderRequest;
import com.surprising.trading.api.model.CancelTriggerOrderRequest;
import com.surprising.trading.api.model.ClosePositionRequest;
import com.surprising.trading.api.model.LeverageSettingRequest;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.PlaceAlgoOrderRequest;
import com.surprising.trading.api.model.PlaceOrderRequest;
import com.surprising.trading.api.model.PlaceTriggerOrderRequest;
import com.surprising.trading.matching.service.MatchingMarketDataService;
import com.surprising.trading.trigger.controller.TriggerOrderRequestHandler;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TradingMvcController {
    private final OrderRequestHandler orderRequests;
    private final TriggerOrderRequestHandler triggerOrderRequests;
    private final LeverageRequestHandler leverageRequests;
    private final TradingFeeRequestHandler tradingFeeRequests;
    private final MatchingMarketDataService marketDataService;

    public TradingMvcController(OrderRequestHandler orderRequests,
                                TriggerOrderRequestHandler triggerOrderRequests,
                                LeverageRequestHandler leverageRequests,
                                TradingFeeRequestHandler tradingFeeRequests,
                                MatchingMarketDataService marketDataService) {
        this.orderRequests = orderRequests;
        this.triggerOrderRequests = triggerOrderRequests;
        this.leverageRequests = leverageRequests;
        this.tradingFeeRequests = tradingFeeRequests;
        this.marketDataService = marketDataService;
    }

    @GetMapping("/api/v1/trading/orders/trades")
    public Object ownFills(
            @RequestParam(value = "userId", required = false) Long queryUserId,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestParam(value = "instrumentId", required = false) String instrumentId,
            @RequestParam(value = "orderId", required = false) Long orderId,
            @RequestParam(value = "startTime", required = false) Long startTime,
            @RequestParam(value = "endTime", required = false) Long endTime,
            @RequestParam(value = "limit", defaultValue = "100") int limit,
            @RequestParam(value = "cursor", required = false) String cursor) {
        long userId = GatewayUserContext.requireUserId(queryUserId, headerUserId, adminUserId);
        return orderRequests.ownFills(userId, instrumentId, orderId, startTime, endTime, limit, cursor);
    }

    @GetMapping("/api/v1/trading/orders/cancellation-plan")
    public Object cancellationPlan(
            @RequestParam(value = "userId", required = false) Long queryUserId,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestParam(value = "instrumentId", required = false) String instrumentId) {
        long userId = GatewayUserContext.requireUserId(queryUserId, headerUserId, adminUserId);
        return orderRequests.cancellationPlan(userId, instrumentId);
    }

    @GetMapping("/api/v1/trading/orders/cancellation-batches")
    public Object cancellationBatch(
            @RequestParam(value = "userId", required = false) Long queryUserId,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestParam("batchKey") String batchKey) {
        long userId = GatewayUserContext.requireUserId(queryUserId, headerUserId, adminUserId);
        return orderRequests.cancellationBatch(userId, batchKey);
    }

    @GetMapping("/api/v1/trading/orders/amendments")
    public Object amendment(
            @RequestParam(value = "userId", required = false) Long queryUserId,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestParam("clientRequestId") String clientRequestId) {
        long userId = GatewayUserContext.requireUserId(queryUserId, headerUserId, adminUserId);
        return orderRequests.amendment(userId, clientRequestId);
    }

    @GetMapping("/api/v1/trading/orders/cancellations/{orderId}")
    public Object cancellation(
            @RequestParam(value = "userId", required = false) Long queryUserId,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @PathVariable("orderId") long orderId) {
        long userId = GatewayUserContext.requireUserId(queryUserId, headerUserId, adminUserId);
        return orderRequests.cancellation(userId, orderId);
    }

    @GetMapping("/api/v1/trading/orders/submission")
    public Object submission(
            @RequestParam(value = "userId", required = false) Long queryUserId,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestParam("clientOrderId") String clientOrderId) {
        long userId = GatewayUserContext.requireUserId(queryUserId, headerUserId, adminUserId);
        return orderRequests.submission(userId, clientOrderId);
    }

    @GetMapping("/api/v1/trading/orders/algo/submission")
    public Object algoSubmission(
            @RequestParam(value = "userId", required = false) Long queryUserId,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestParam("clientAlgoOrderId") String clientAlgoOrderId) {
        long userId = GatewayUserContext.requireUserId(queryUserId, headerUserId, adminUserId);
        return orderRequests.algoSubmission(userId, clientAlgoOrderId);
    }

    @GetMapping("/api/v1/trading/orders/price-protection")
    public Object priceProtection() {
        return orderRequests.priceProtection();
    }

    @GetMapping("/api/v1/trading/market/orderbook")
    public Object orderBook(
            @RequestParam("instrumentId") String instrumentId,
            @RequestParam(value = "depth", defaultValue = "30") int depth) {
        return marketDataService.orderBookSnapshot(instrumentId, depth);
    }

    @GetMapping("/api/v1/trading/fees/effective")
    public Object effectiveFee(
            @RequestParam(value = "userId", required = false) Long queryUserId,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestParam("instrumentId") String instrumentId,
            @RequestParam(value = "instrumentChangeId", defaultValue = "0") long instrumentChangeId,
            @RequestHeader(value = "X-Product-Line", required = false) String headerProductLine,
            @RequestParam(value = "productLine", required = false) String queryProductLine) {
        long userId = GatewayUserContext.requireUserId(queryUserId, headerUserId, adminUserId);
        return tradingFeeRequests.effective(userId, instrumentId, instrumentChangeId,
                headerProductLine, queryProductLine);
    }

    @PostMapping("/api/v1/trading/trigger-orders/batch-cancel")
    public Object cancelTriggerBatch(@RequestBody BatchCancelTriggerOrdersRequest request) {
        return triggerOrderRequests.cancelBatch(request);
    }

    @PostMapping("/api/v1/trading/trigger-orders/cancel-open")
    public Object cancelTriggerOpen(@RequestBody CancelOpenTriggerOrdersRequest request) {
        return triggerOrderRequests.cancelOpen(request);
    }

    @GetMapping("/api/v1/trading/orders/by-client-order-id")
    public Object getByClientOrderId(
            @RequestParam(value = "userId", required = false) Long queryUserId,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestParam("clientOrderId") String clientOrderId,
            @RequestParam(value = "minExportSequence", required = false) Long minExportSequence) {
        long userId = GatewayUserContext.requireUserId(queryUserId, headerUserId, adminUserId);
        return orderRequests.getByClientOrderId(userId, clientOrderId, minExportSequence);
    }

    @PostMapping("/api/v1/trading/orders/cancel-all-after")
    public Object cancelAllAfter(@RequestBody CancelAllAfterRequest request) {
        return orderRequests.cancelAllAfter(request);
    }

    @PostMapping("/api/v1/trading/orders/algo/cancel-open")
    public Object cancelOpenAlgo(@RequestBody CancelOpenAlgoOrdersRequest request) {
        return orderRequests.cancelOpenAlgo(request);
    }

    @PostMapping("/api/v1/trading/trigger-orders/cancel")
    public Object cancelTrigger(@RequestBody CancelTriggerOrderRequest request) {
        return triggerOrderRequests.cancel(request);
    }

    @PostMapping("/api/v1/trading/orders/close-position")
    public Object closePosition(@Valid @RequestBody ClosePositionRequest request) {
        return orderRequests.closePosition(request);
    }

    @PostMapping("/api/v1/trading/trigger-orders/batch")
    public Object placeTriggerBatch(@RequestBody BatchPlaceTriggerOrderRequest request) {
        return triggerOrderRequests.placeBatch(request);
    }

    @GetMapping("/api/v1/trading/trigger-orders/open")
    public Object openTriggerOrders(
            @RequestParam(value = "userId", required = false) Long queryUserId,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestParam(value = "instrumentId", required = false) String instrumentId,
            @RequestParam(value = "limit", defaultValue = "100") int limit,
            @RequestParam(value = "cursor", required = false) String cursor) {
        long userId = GatewayUserContext.requireUserId(queryUserId, headerUserId, adminUserId);
        return triggerOrderRequests.openOrders(userId, instrumentId, limit, cursor);
    }

    @GetMapping("/api/v1/trading/trigger-orders/oco-submission")
    public Object triggerOcoSubmission(
            @RequestParam(value = "userId", required = false) Long queryUserId,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestParam("takeProfitClientId") String takeProfitClientId,
            @RequestParam("stopLossClientId") String stopLossClientId) {
        long userId = GatewayUserContext.requireUserId(queryUserId, headerUserId, adminUserId);
        return triggerOrderRequests.ocoSubmission(userId, takeProfitClientId, stopLossClientId);
    }

    @GetMapping("/api/v1/trading/trigger-orders/submission")
    public Object triggerSubmission(
            @RequestParam(value = "userId", required = false) Long queryUserId,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestParam("clientTriggerOrderId") String clientTriggerOrderId) {
        long userId = GatewayUserContext.requireUserId(queryUserId, headerUserId, adminUserId);
        return triggerOrderRequests.submission(userId, clientTriggerOrderId);
    }

    @GetMapping("/api/v1/trading/trigger-orders/cancellations/{triggerOrderId}")
    public Object triggerCancellation(
            @RequestParam(value = "userId", required = false) Long queryUserId,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @PathVariable("triggerOrderId") long triggerOrderId) {
        long userId = GatewayUserContext.requireUserId(queryUserId, headerUserId, adminUserId);
        return triggerOrderRequests.cancellation(userId, triggerOrderId);
    }

    @PostMapping("/api/v1/trading/orders/batch-cancel")
    public Object cancelBatch(@Valid @RequestBody BatchCancelOrdersRequest request) {
        return orderRequests.cancelBatch(request);
    }

    @PostMapping("/api/v1/trading/orders/batch-amend")
    public Object amendBatch(@Valid @RequestBody BatchAmendOrdersRequest request) {
        return orderRequests.amendBatch(request);
    }

    @PostMapping("/api/v1/trading/orders/cancel-open")
    public Object cancelOpen(@RequestBody CancelOpenOrdersRequest request) {
        return orderRequests.cancelOpen(request);
    }

    @PostMapping("/api/v1/trading/orders/algo/cancel")
    public Object cancelAlgo(@RequestBody CancelAlgoOrderRequest request) {
        return orderRequests.cancelAlgo(request);
    }

    @PostMapping("/api/v1/trading/leverage/settings")
    public Object setLeverage(
            @RequestBody LeverageSettingRequest request,
            @RequestHeader(value = "X-Product-Line", required = false) String headerProductLine,
            @RequestParam(value = "productLine", required = false) String queryProductLine) {
        return leverageRequests.set(request, headerProductLine, queryProductLine);
    }

    @GetMapping("/api/v1/trading/leverage/settings")
    public Object getLeverage(
            @RequestParam(value = "userId", required = false) Long queryUserId,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestParam("instrumentId") String instrumentId,
            @RequestParam(value = "marginMode", required = false) MarginMode marginMode,
            @RequestHeader(value = "X-Product-Line", required = false) String headerProductLine,
            @RequestParam(value = "productLine", required = false) String queryProductLine) {
        long userId = GatewayUserContext.requireUserId(queryUserId, headerUserId, adminUserId);
        return leverageRequests.get(userId, instrumentId, marginMode, headerProductLine, queryProductLine);
    }

    @GetMapping("/api/v1/trading/orders/algo/open")
    public Object openAlgoOrders(
            @RequestParam(value = "userId", required = false) Long queryUserId,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestParam(value = "instrumentId", required = false) String instrumentId,
            @RequestParam(value = "limit", defaultValue = "100") int limit) {
        long userId = GatewayUserContext.requireUserId(queryUserId, headerUserId, adminUserId);
        return orderRequests.openAlgoOrders(userId, instrumentId, limit);
    }

    @PostMapping("/api/v1/trading/trigger-orders")
    public Object placeTrigger(@RequestBody PlaceTriggerOrderRequest request) {
        return triggerOrderRequests.place(request);
    }

    @GetMapping("/api/v1/trading/orders/history")
    public Object historyOrders(
            @RequestParam(value = "userId", required = false) Long queryUserId,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestParam(value = "instrumentId", required = false) String instrumentId,
            @RequestParam(value = "limit", defaultValue = "100") int limit,
            @RequestParam(value = "orderId", required = false) Long orderId,
            @RequestParam(value = "startTime", required = false) Long startTime,
            @RequestParam(value = "endTime", required = false) Long endTime,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "minExportSequence", required = false) Long minExportSequence,
            @RequestParam(value = "status", required = false) String status) {
        long userId = GatewayUserContext.requireUserId(queryUserId, headerUserId, adminUserId);
        return orderRequests.historyOrders(userId, instrumentId, limit, orderId, startTime, endTime,
                cursor, minExportSequence, status);
    }

    @PostMapping("/api/v1/trading/orders/cancel")
    public Object cancel(@RequestBody CancelOrderRequest request) {
        return orderRequests.cancel(request);
    }

    @PostMapping("/api/v1/trading/orders/batch")
    public Object placeBatch(@Valid @RequestBody BatchPlaceOrderRequest request) {
        return orderRequests.placeBatch(request);
    }

    @PostMapping("/api/v1/trading/orders/amend")
    public Object amend(@Valid @RequestBody AmendOrderRequest request) {
        return orderRequests.amend(request);
    }

    @PostMapping("/api/v1/trading/orders/test")
    public Object test(@Valid @RequestBody PlaceOrderRequest request) {
        return orderRequests.test(request);
    }

    @PostMapping("/api/v1/trading/orders/algo")
    public Object placeAlgo(@RequestBody PlaceAlgoOrderRequest request) {
        return orderRequests.placeAlgo(request);
    }

    @GetMapping("/api/v1/trading/orders/open")
    public Object openOrders(
            @RequestParam(value = "userId", required = false) Long queryUserId,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestParam(value = "instrumentId", required = false) String instrumentId,
            @RequestParam(value = "limit", defaultValue = "100") int limit,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "minExportSequence", required = false) Long minExportSequence) {
        long userId = GatewayUserContext.requireUserId(queryUserId, headerUserId, adminUserId);
        return orderRequests.openOrders(userId, instrumentId, limit, cursor, minExportSequence);
    }

    @PostMapping("/api/v1/trading/orders")
    public Object place(@Valid @RequestBody PlaceOrderRequest request) {
        return orderRequests.place(request);
    }

    @GetMapping("/api/v1/trading/trigger-orders/{triggerOrderId}")
    public Object getTrigger(
            @RequestParam(value = "userId", required = false) Long queryUserId,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @PathVariable("triggerOrderId") long triggerOrderId) {
        long userId = GatewayUserContext.requireUserId(queryUserId, headerUserId, adminUserId);
        return triggerOrderRequests.get(userId, triggerOrderId);
    }

    @GetMapping("/api/v1/trading/orders/commands/{commandId}")
    public Object commandResult(
            @RequestParam(value = "userId", required = false) Long queryUserId,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @PathVariable("commandId") UUID commandId) {
        long userId = GatewayUserContext.requireUserId(queryUserId, headerUserId, adminUserId);
        return orderRequests.commandResult(userId, commandId);
    }

    @GetMapping("/api/v1/trading/orders/algo/{algoOrderId}")
    public Object getAlgo(
            @RequestParam(value = "userId", required = false) Long queryUserId,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @PathVariable("algoOrderId") long algoOrderId) {
        long userId = GatewayUserContext.requireUserId(queryUserId, headerUserId, adminUserId);
        return orderRequests.getAlgo(userId, algoOrderId);
    }

    @GetMapping("/api/v1/trading/orders/{orderId}")
    public Object get(
            @RequestParam(value = "userId", required = false) Long queryUserId,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @PathVariable("orderId") long orderId,
            @RequestParam(value = "minExportSequence", required = false) Long minExportSequence) {
        long userId = GatewayUserContext.requireUserId(queryUserId, headerUserId, adminUserId);
        return orderRequests.get(userId, orderId, minExportSequence);
    }
}
