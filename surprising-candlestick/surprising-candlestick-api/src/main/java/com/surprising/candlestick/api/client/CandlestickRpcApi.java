package com.surprising.candlestick.api.client;

import com.surprising.candlestick.api.CandlestickApiPaths;
import com.surprising.candlestick.api.model.CandleQueryResponse;
import com.surprising.candlestick.api.model.CandleResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(
        name = "surprising-candlestick-provider",
        contextId = "candlestickRpcApi",
        path = CandlestickApiPaths.BASE_PATH,
        url = "${surprising.clients.candlestick.base-url:http://localhost:9081}")
public interface CandlestickRpcApi {

    @GetMapping("/candles")
    CandleQueryResponse queryCandles(
            @RequestParam("symbol") @NotBlank String symbol,
            @RequestParam("period") @NotBlank String period,
            @RequestParam("startTime") @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant startTime,
            @RequestParam("endTime") @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant endTime,
            @RequestParam(value = "limit", defaultValue = "500") @Min(1) @Max(1500) int limit);

    @GetMapping("/candles/latest")
    CandleResponse latestCandle(
            @RequestParam("symbol") @NotBlank String symbol,
            @RequestParam("period") @NotBlank String period);
}
