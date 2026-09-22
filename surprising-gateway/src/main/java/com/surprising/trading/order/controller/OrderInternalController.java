package com.surprising.trading.order.controller;

import com.surprising.trading.order.service.OrderRequestService;

import com.surprising.trading.api.TradingApiPaths;
import com.surprising.trading.api.model.AmendOrderRequest;
import com.surprising.trading.api.model.AlgoOrderBatchResponse;
import com.surprising.trading.api.model.AlgoOrderQueryResponse;
import com.surprising.trading.api.model.AlgoOrderResponse;
import com.surprising.trading.api.model.BatchAmendOrdersRequest;
import com.surprising.trading.api.model.BatchCancelOrdersRequest;
import com.surprising.trading.api.model.BatchPlaceOrderRequest;
import com.surprising.trading.api.model.CancelAlgoOrderRequest;
import com.surprising.trading.api.model.CancelAllAfterRequest;
import com.surprising.trading.api.model.CancelAllAfterResponse;
import com.surprising.trading.api.model.CancelOrderRequest;
import com.surprising.trading.api.model.CancelOpenAlgoOrdersRequest;
import com.surprising.trading.api.model.CancelOpenOrdersRequest;
import com.surprising.trading.api.model.ClosePositionRequest;
import com.surprising.trading.api.model.OrderBatchResponse;
import com.surprising.trading.api.model.OrderCommandReceipt;
import com.surprising.trading.api.model.OrderQueryResponse;
import com.surprising.trading.api.model.OrderResponse;
import com.surprising.trading.api.model.PlaceAlgoOrderRequest;
import com.surprising.trading.api.model.PlaceOrderRequest;
import com.surprising.trading.api.model.TestOrderResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/** 独立 maker 使用的内部 HTTP 契约，由 BusinessEndpointConfiguration 校验内部凭证。 */
@RestController
public class OrderInternalController {

    @PostMapping(TradingApiPaths.ORDER_BASE_PATH)
    public CompletionStage<ResponseEntity<OrderCommandReceipt>> place(@Valid @RequestBody PlaceOrderRequest request) {
        return requests.place(request);
    }

    @PostMapping(TradingApiPaths.ORDER_BASE_PATH + "/batch")
    public CompletionStage<ResponseEntity<OrderCommandReceipt>> placeBatch(@Valid @RequestBody BatchPlaceOrderRequest request) {
        return requests.placeBatch(request);
    }

    @PostMapping(TradingApiPaths.ORDER_BASE_PATH + "/test")
    public TestOrderResponse test(@Valid @RequestBody PlaceOrderRequest request) {
        return requests.test(request);
    }

    @PostMapping(TradingApiPaths.ORDER_BASE_PATH + "/amend")
    public CompletionStage<ResponseEntity<OrderCommandReceipt>> amend(@Valid @RequestBody AmendOrderRequest request) {
        return requests.amend(request);
    }

    @PostMapping(TradingApiPaths.ORDER_BASE_PATH + "/batch-amend")
    public CompletionStage<ResponseEntity<OrderCommandReceipt>> amendBatch(@Valid @RequestBody BatchAmendOrdersRequest request) {
        return requests.amendBatch(request);
    }

    @PostMapping(TradingApiPaths.ORDER_BASE_PATH + "/close-position")
    public OrderResponse closePosition(@Valid @RequestBody ClosePositionRequest request) {
        return requests.closePosition(request);
    }

    @PostMapping(TradingApiPaths.ORDER_BASE_PATH + "/cancel")
    public CompletionStage<ResponseEntity<OrderCommandReceipt>> cancel(@RequestBody CancelOrderRequest request) {
        return requests.cancel(request);
    }

    @PostMapping(TradingApiPaths.ORDER_BASE_PATH + "/batch-cancel")
    public CompletionStage<ResponseEntity<OrderCommandReceipt>> cancelBatch(@Valid @RequestBody BatchCancelOrdersRequest request) {
        return requests.cancelBatch(request);
    }

    @PostMapping(TradingApiPaths.ORDER_BASE_PATH + "/cancel-open")
    public OrderBatchResponse cancelOpen(@RequestBody CancelOpenOrdersRequest request) {
        return requests.cancelOpen(request);
    }

    @PostMapping(TradingApiPaths.ORDER_BASE_PATH + "/cancel-all-after")
    public CancelAllAfterResponse cancelAllAfter(@RequestBody CancelAllAfterRequest request) {
        return requests.cancelAllAfter(request);
    }

    @PostMapping(TradingApiPaths.ORDER_BASE_PATH + "/algo")
    public AlgoOrderResponse placeAlgo(@RequestBody PlaceAlgoOrderRequest request) {
        return requests.placeAlgo(request);
    }

    @PostMapping(TradingApiPaths.ORDER_BASE_PATH + "/algo/cancel")
    public AlgoOrderResponse cancelAlgo(@RequestBody CancelAlgoOrderRequest request) {
        return requests.cancelAlgo(request);
    }

    @PostMapping(TradingApiPaths.ORDER_BASE_PATH + "/algo/cancel-open")
    public AlgoOrderBatchResponse cancelOpenAlgo(@RequestBody CancelOpenAlgoOrdersRequest request) {
        return requests.cancelOpenAlgo(request);
    }

    @GetMapping(TradingApiPaths.ORDER_BASE_PATH + "/algo/{algoOrderId}")
    public AlgoOrderResponse getAlgo(@PathVariable("algoOrderId") long algoOrderId) {
        return requests.getAlgo(algoOrderId);
    }

    @GetMapping(TradingApiPaths.ORDER_BASE_PATH + "/algo/open")
    public AlgoOrderQueryResponse openAlgoOrders(@RequestParam("userId") long userId, @RequestParam(value = "symbol", required = false) String symbol, @RequestParam(value = "limit", defaultValue = "100") int limit) {
        return requests.openAlgoOrders(userId, symbol, limit);
    }

    @GetMapping(TradingApiPaths.ORDER_BASE_PATH + "/{orderId}")
    public OrderResponse get(@RequestParam("userId") long userId, @PathVariable("orderId") long orderId, @RequestParam(value = "minExportSequence", required = false) Long minExportSequence) {
        return requests.get(userId, orderId, minExportSequence);
    }

    @GetMapping(TradingApiPaths.ORDER_BASE_PATH + "/commands/{commandId}")
    public ResponseEntity<OrderCommandReceipt> commandResult(@PathVariable("commandId") UUID commandId) {
        return requests.commandResult(commandId);
    }

    @GetMapping(TradingApiPaths.ORDER_BASE_PATH + "/by-client-order-id")
    public OrderResponse getByClientOrderId(@RequestParam("userId") long userId, @RequestParam("clientOrderId") String clientOrderId, @RequestParam(value = "minExportSequence", required = false) Long minExportSequence) {
        return requests.getByClientOrderId(userId, clientOrderId, minExportSequence);
    }

    @GetMapping(TradingApiPaths.ORDER_BASE_PATH + "/open")
    public OrderQueryResponse openOrders(@RequestParam("userId") long userId, @RequestParam(value = "symbol", required = false) String symbol, @RequestParam(value = "limit", defaultValue = "100") int limit, @RequestParam(value = "cursor", required = false) String cursor, @RequestParam(value = "minExportSequence", required = false) Long minExportSequence) {
        return requests.openOrders(userId, symbol, limit, cursor, minExportSequence);
    }

    @GetMapping(TradingApiPaths.ORDER_BASE_PATH + "/history")
    public OrderQueryResponse historyOrders(@RequestParam("userId") long userId, @RequestParam(value = "symbol", required = false) String symbol, @RequestParam(value = "limit", defaultValue = "100") int limit, @RequestParam(value = "orderId", required = false) Long orderId, @RequestParam(value = "startTime", required = false) Long startTime, @RequestParam(value = "endTime", required = false) Long endTime, @RequestParam(value = "cursor", required = false) String cursor, @RequestParam(value = "minExportSequence", required = false) Long minExportSequence) {
        return requests.historyOrders(userId, symbol, limit, orderId, startTime, endTime, cursor, minExportSequence);
    }

    private final OrderRequestService requests;

    public OrderInternalController(OrderRequestService requests) {
        this.requests = requests;
    }
}
