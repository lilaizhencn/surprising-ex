package com.surprising.trading.api.client;

import com.surprising.trading.api.model.OrderBookSnapshotResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(name = "surprising-gateway", url = "${surprising.clients.market-data.base-url:${surprising.clients.gateway.url:http://127.0.0.1:9094}}", contextId = "marketDataRpcApi")
public interface MarketDataRpcApi {

    @GetMapping("/api/v1/trading/market/orderbook")
    OrderBookSnapshotResponse orderBook(@RequestParam("instrumentId") @NotBlank String instrumentId,
                                        @RequestParam(value = "depth", defaultValue = "30")
                                        @Min(1) @Max(100) int depth);

}
