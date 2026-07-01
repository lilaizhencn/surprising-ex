package com.surprising.trading.api.client;

import com.surprising.trading.api.TradingApiPaths;
import com.surprising.trading.api.model.CancelTriggerOrderRequest;
import com.surprising.trading.api.model.PlaceTriggerOrderRequest;
import com.surprising.trading.api.model.TriggerOrderQueryResponse;
import com.surprising.trading.api.model.TriggerOrderResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(
        name = "surprising-trigger-provider",
        contextId = "triggerOrderRpcApi",
        path = TradingApiPaths.TRIGGER_ORDER_BASE_PATH,
        url = "${surprising.clients.trigger.base-url:http://localhost:9095}")
public interface TriggerOrderRpcApi {

    @PostMapping
    TriggerOrderResponse place(@Valid @RequestBody PlaceTriggerOrderRequest request);

    @PostMapping("/cancel")
    TriggerOrderResponse cancel(@Valid @RequestBody CancelTriggerOrderRequest request);

    @GetMapping("/{triggerOrderId}")
    TriggerOrderResponse get(@PathVariable("triggerOrderId") @Positive long triggerOrderId);

    @GetMapping("/open")
    TriggerOrderQueryResponse openOrders(@RequestParam("userId") @Positive long userId,
                                         @RequestParam(value = "symbol", required = false) String symbol,
                                         @RequestParam(value = "limit", defaultValue = "100")
                                         @Min(1) @Max(1000) int limit);
}
