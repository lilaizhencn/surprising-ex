package com.surprising.trading.api.client;

import com.surprising.trading.api.TradingApiPaths;
import com.surprising.trading.api.model.CancelOrderRequest;
import com.surprising.trading.api.model.OrderQueryResponse;
import com.surprising.trading.api.model.OrderResponse;
import com.surprising.trading.api.model.PlaceOrderRequest;
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

    @PostMapping("/cancel")
    OrderResponse cancel(@Valid @RequestBody CancelOrderRequest request);

    @GetMapping("/{orderId}")
    OrderResponse get(@PathVariable("orderId") @Positive long orderId);

    @GetMapping("/by-client-order-id")
    OrderResponse getByClientOrderId(@RequestParam("userId") @Positive long userId,
                                     @RequestParam("clientOrderId") @NotBlank String clientOrderId);

    @GetMapping("/open")
    OrderQueryResponse openOrders(@RequestParam("userId") @Positive long userId,
                                  @RequestParam(value = "symbol", required = false) String symbol,
                                  @RequestParam(value = "limit", defaultValue = "100") @Min(1) @Max(1000) int limit);
}
