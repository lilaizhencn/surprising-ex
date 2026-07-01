package com.surprising.trading.api.client;

import com.surprising.trading.api.TradingApiPaths;
import com.surprising.trading.api.model.OrderBookSnapshotResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(
        name = "surprising-matching-provider",
        contextId = "marketDataRpcApi",
        path = TradingApiPaths.MARKET_BASE_PATH,
        url = "${surprising.clients.matching.base-url:http://localhost:9085}")
public interface MarketDataRpcApi {

    @GetMapping("/orderbook")
    OrderBookSnapshotResponse orderBook(@RequestParam("symbol") @NotBlank String symbol,
                                        @RequestParam(value = "depth", defaultValue = "50")
                                        @Min(1) @Max(200) int depth);
}
