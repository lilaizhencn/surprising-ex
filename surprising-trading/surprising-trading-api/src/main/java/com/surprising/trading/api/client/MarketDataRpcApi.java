package com.surprising.trading.api.client;

import com.surprising.trading.api.TradingApiPaths;
import com.surprising.trading.api.model.OrderBookSnapshotResponse;
import com.surprising.trading.api.model.PublicTradeEvent;
import com.surprising.trading.api.model.MarketTickerSummary;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(
        name = "surprising-market-data-provider",
        contextId = "marketDataRpcApi",
        path = TradingApiPaths.MARKET_BASE_PATH,
        url = "${surprising.clients.matching.base-url:http://localhost:9081}")
public interface MarketDataRpcApi {

    @GetMapping("/orderbook")
    OrderBookSnapshotResponse orderBook(@RequestParam("symbol") @NotBlank String symbol,
                                        @RequestParam(value = "depth", defaultValue = "50")
                                        @Min(1) @Max(200) int depth);

    @GetMapping("/latest-trade")
    PublicTradeEvent latestTrade(@RequestParam("symbol") @NotBlank String symbol);

    @GetMapping("/ticker-24hr")
    MarketTickerSummary ticker24hr(@RequestParam("symbol") @NotBlank String symbol);
}
