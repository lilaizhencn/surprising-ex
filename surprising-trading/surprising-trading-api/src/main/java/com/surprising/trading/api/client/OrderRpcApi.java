package com.surprising.trading.api.client;

import com.surprising.trading.api.TradingApiPaths;
import com.surprising.trading.api.model.AmendOrderBatchResponse;
import com.surprising.trading.api.model.AmendOrderRequest;
import com.surprising.trading.api.model.AmendOrderResponse;
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

@FeignClient(
        name = "surprising-order-provider",
        contextId = "orderRpcApi",
        path = TradingApiPaths.ORDER_BASE_PATH,
        url = "${surprising.clients.order.base-url:http://localhost:9084}")
public interface OrderRpcApi {

    @PostMapping
    OrderResponse place(@Valid @RequestBody PlaceOrderRequest request);

    @PostMapping("/batch")
    OrderBatchResponse placeBatch(@Valid @RequestBody BatchPlaceOrderRequest request);

    @PostMapping("/test")
    TestOrderResponse test(@Valid @RequestBody PlaceOrderRequest request);

    @PostMapping("/amend")
    AmendOrderResponse amend(@Valid @RequestBody AmendOrderRequest request);

    @PostMapping("/batch-amend")
    AmendOrderBatchResponse amendBatch(@Valid @RequestBody BatchAmendOrdersRequest request);

    @PostMapping("/close-position")
    OrderResponse closePosition(@Valid @RequestBody ClosePositionRequest request);

    @PostMapping("/cancel")
    OrderResponse cancel(@Valid @RequestBody CancelOrderRequest request);

    @PostMapping("/batch-cancel")
    OrderBatchResponse cancelBatch(@Valid @RequestBody BatchCancelOrdersRequest request);

    @PostMapping("/cancel-open")
    OrderBatchResponse cancelOpen(@Valid @RequestBody CancelOpenOrdersRequest request);

    @PostMapping("/cancel-all-after")
    CancelAllAfterResponse cancelAllAfter(@Valid @RequestBody CancelAllAfterRequest request);

    @PostMapping("/algo")
    AlgoOrderResponse placeAlgo(@Valid @RequestBody PlaceAlgoOrderRequest request);

    @PostMapping("/algo/cancel")
    AlgoOrderResponse cancelAlgo(@Valid @RequestBody CancelAlgoOrderRequest request);

    @PostMapping("/algo/cancel-open")
    AlgoOrderBatchResponse cancelOpenAlgo(@Valid @RequestBody CancelOpenAlgoOrdersRequest request);

    @GetMapping("/algo/{algoOrderId}")
    AlgoOrderResponse getAlgo(@PathVariable("algoOrderId") @Positive long algoOrderId);

    @GetMapping("/algo/open")
    AlgoOrderQueryResponse openAlgoOrders(@RequestParam("userId") @Positive long userId,
                                          @RequestParam(value = "symbol", required = false) String symbol,
                                          @RequestParam(value = "limit", defaultValue = "100") @Min(1) @Max(1000) int limit);

    @GetMapping("/{orderId}")
    OrderResponse get(@PathVariable("orderId") @Positive long orderId);

    @GetMapping("/by-client-order-id")
    OrderResponse getByClientOrderId(@RequestParam("userId") @Positive long userId,
                                     @RequestParam("clientOrderId") @NotBlank String clientOrderId);

    @GetMapping("/open")
    OrderQueryResponse openOrders(@RequestParam("userId") @Positive long userId,
                                  @RequestParam(value = "symbol", required = false) String symbol,
                                  @RequestParam(value = "limit", defaultValue = "100") @Min(1) @Max(1000) int limit,
                                  @RequestParam(value = "cursor", required = false) String cursor);
}
