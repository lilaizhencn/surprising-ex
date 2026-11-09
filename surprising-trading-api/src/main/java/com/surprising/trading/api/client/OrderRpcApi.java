package com.surprising.trading.api.client;

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

    @PostMapping("/api/v1/trading/orders")
    OrderCommandReceipt place(@Valid @RequestBody PlaceOrderRequest request);

    @PostMapping("/api/v1/trading/orders/batch")
    OrderCommandReceipt placeBatch(@Valid @RequestBody BatchPlaceOrderRequest request);

    @PostMapping("/api/v1/trading/orders/test")
    TestOrderResponse test(@Valid @RequestBody PlaceOrderRequest request);

    @PostMapping("/api/v1/trading/orders/amend")
    OrderCommandReceipt amend(@Valid @RequestBody AmendOrderRequest request);

    @PostMapping("/api/v1/trading/orders/batch-amend")
    OrderCommandReceipt amendBatch(@Valid @RequestBody BatchAmendOrdersRequest request);

    @PostMapping("/api/v1/trading/orders/close-position")
    OrderResponse closePosition(@Valid @RequestBody ClosePositionRequest request);

    @PostMapping("/api/v1/trading/orders/cancel")
    OrderCommandReceipt cancel(@Valid @RequestBody CancelOrderRequest request);

    @PostMapping("/api/v1/trading/orders/batch-cancel")
    OrderCommandReceipt cancelBatch(@Valid @RequestBody BatchCancelOrdersRequest request);

    @PostMapping("/api/v1/trading/orders/cancel-open")
    OrderBatchResponse cancelOpen(@Valid @RequestBody CancelOpenOrdersRequest request);

    @PostMapping("/api/v1/trading/orders/cancel-all-after")
    CancelAllAfterResponse cancelAllAfter(@Valid @RequestBody CancelAllAfterRequest request);

    @PostMapping("/api/v1/trading/orders/algo")
    AlgoOrderResponse placeAlgo(@Valid @RequestBody PlaceAlgoOrderRequest request);

    @PostMapping("/api/v1/trading/orders/algo/cancel")
    AlgoOrderResponse cancelAlgo(@Valid @RequestBody CancelAlgoOrderRequest request);

    @PostMapping("/api/v1/trading/orders/algo/cancel-open")
    AlgoOrderBatchResponse cancelOpenAlgo(@Valid @RequestBody CancelOpenAlgoOrdersRequest request);

    @GetMapping("/api/v1/trading/orders/algo/{algoOrderId}")
    AlgoOrderResponse getAlgo(@PathVariable("algoOrderId") @Positive long algoOrderId);

    @GetMapping("/api/v1/trading/orders/algo/open")
    AlgoOrderQueryResponse openAlgoOrders(@RequestParam("userId") @Positive long userId,
                                          @RequestParam(value = "instrumentId", required = false) String instrumentId,
                                          @RequestParam(value = "limit", defaultValue = "100") @Min(1) @Max(1000) int limit);

    @GetMapping("/api/v1/trading/orders/{orderId}")
    OrderResponse get(@PathVariable("orderId") @Positive long orderId);

    @GetMapping("/api/v1/trading/orders/by-client-order-id")
    OrderResponse getByClientOrderId(@RequestParam("userId") @Positive long userId,
                                     @RequestParam("clientOrderId") @NotBlank String clientOrderId);

    @GetMapping("/api/v1/trading/orders/open")
    OrderQueryResponse openOrders(@RequestParam("userId") @Positive long userId,
                                  @RequestParam(value = "instrumentId", required = false) String instrumentId,
                                  @RequestParam(value = "limit", defaultValue = "100") @Min(1) @Max(1000) int limit,
                                  @RequestParam(value = "cursor", required = false) String cursor);
}
