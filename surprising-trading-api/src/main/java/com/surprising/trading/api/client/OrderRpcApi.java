package com.surprising.trading.api.client;

import com.surprising.trading.api.TradingApiPaths;
import com.surprising.trading.api.model.AmendOrderRequest;
import com.surprising.trading.api.model.AlgoOrderBatchResponse;
import com.surprising.trading.api.model.AlgoOrderQueryResponse;
import com.surprising.trading.api.model.AlgoOrderResponse;
import com.surprising.trading.api.model.CancelAlgoOrderRequest;
import com.surprising.trading.api.model.CancelOpenAlgoOrdersRequest;
import com.surprising.trading.api.model.BatchAmendOrdersRequest;
import com.surprising.trading.api.model.BatchCancelOrdersRequest;
import com.surprising.trading.api.model.BatchPlaceOrderRequest;
import com.surprising.trading.api.model.CancelAllAfterRequest;
import com.surprising.trading.api.model.CancelAllAfterResponse;
import com.surprising.trading.api.model.CancelOrderRequest;
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
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(name = "surprising-gateway", contextId = "orderRpcApi")
public interface OrderRpcApi {

    @PostMapping(TradingApiPaths.ORDER_BASE_PATH)
    OrderCommandReceipt place(@Valid @RequestBody PlaceOrderRequest request);

    @PostMapping(TradingApiPaths.ORDER_BASE_PATH + "/batch")
    OrderCommandReceipt placeBatch(@Valid @RequestBody BatchPlaceOrderRequest request);

    @PostMapping(TradingApiPaths.ORDER_BASE_PATH + "/test")
    TestOrderResponse test(@Valid @RequestBody PlaceOrderRequest request);

    @PostMapping(TradingApiPaths.ORDER_BASE_PATH + "/amend")
    OrderCommandReceipt amend(@Valid @RequestBody AmendOrderRequest request);

    @PostMapping(TradingApiPaths.ORDER_BASE_PATH + "/batch-amend")
    OrderCommandReceipt amendBatch(@Valid @RequestBody BatchAmendOrdersRequest request);

    @PostMapping(TradingApiPaths.ORDER_BASE_PATH + "/close-position")
    OrderResponse closePosition(@Valid @RequestBody ClosePositionRequest request);

    @PostMapping(TradingApiPaths.ORDER_BASE_PATH + "/cancel")
    OrderCommandReceipt cancel(@Valid @RequestBody CancelOrderRequest request);

    @PostMapping(TradingApiPaths.ORDER_BASE_PATH + "/batch-cancel")
    OrderCommandReceipt cancelBatch(@Valid @RequestBody BatchCancelOrdersRequest request);

    @PostMapping(TradingApiPaths.ORDER_BASE_PATH + "/cancel-open")
    OrderBatchResponse cancelOpen(@Valid @RequestBody CancelOpenOrdersRequest request);

    @PostMapping(TradingApiPaths.ORDER_BASE_PATH + "/cancel-all-after")
    CancelAllAfterResponse cancelAllAfter(@Valid @RequestBody CancelAllAfterRequest request);

    @PostMapping(TradingApiPaths.ORDER_BASE_PATH + "/algo")
    AlgoOrderResponse placeAlgo(@Valid @RequestBody PlaceAlgoOrderRequest request);

    @PostMapping(TradingApiPaths.ORDER_BASE_PATH + "/algo/cancel")
    AlgoOrderResponse cancelAlgo(@Valid @RequestBody CancelAlgoOrderRequest request);

    @PostMapping(TradingApiPaths.ORDER_BASE_PATH + "/algo/cancel-open")
    AlgoOrderBatchResponse cancelOpenAlgo(@Valid @RequestBody CancelOpenAlgoOrdersRequest request);

    @GetMapping(TradingApiPaths.ORDER_BASE_PATH + "/algo/{algoOrderId}")
    AlgoOrderResponse getAlgo(@PathVariable("algoOrderId") @Positive long algoOrderId);

    @GetMapping(TradingApiPaths.ORDER_BASE_PATH + "/algo/open")
    AlgoOrderQueryResponse openAlgoOrders(@RequestParam("userId") @Positive long userId,
                                          @RequestParam(value = "instrumentId", required = false) String instrumentId,
                                          @RequestParam(value = "limit", defaultValue = "100") @Min(1) @Max(1000) int limit);

    @GetMapping(TradingApiPaths.ORDER_BASE_PATH + "/{orderId}")
    OrderResponse get(@PathVariable("orderId") @Positive long orderId);

    @GetMapping(TradingApiPaths.ORDER_BASE_PATH + "/by-client-order-id")
    OrderResponse getByClientOrderId(@RequestParam("userId") @Positive long userId,
                                     @RequestParam("clientOrderId") @NotBlank String clientOrderId);

    @GetMapping(TradingApiPaths.ORDER_BASE_PATH + "/open")
    OrderQueryResponse openOrders(@RequestParam("userId") @Positive long userId,
                                  @RequestParam(value = "instrumentId", required = false) String instrumentId,
                                  @RequestParam(value = "limit", defaultValue = "100") @Min(1) @Max(1000) int limit,
                                  @RequestParam(value = "cursor", required = false) String cursor);
}
