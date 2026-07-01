package com.surprising.price.api.client;

import com.surprising.price.api.PriceApiPaths;
import com.surprising.price.api.model.MarkPriceQueryResponse;
import com.surprising.price.api.model.MarkPriceResponse;
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
        name = "surprising-mark-price-provider",
        contextId = "markPriceRpcApi",
        path = PriceApiPaths.MARK_BASE_PATH,
        url = "${surprising.clients.mark-price.base-url:http://localhost:9083}")
public interface MarkPriceRpcApi {

    @GetMapping("/latest")
    MarkPriceResponse latestMarkPrice(@RequestParam("symbol") @NotBlank String symbol);

    @GetMapping("/history")
    MarkPriceQueryResponse history(
            @RequestParam("symbol") @NotBlank String symbol,
            @RequestParam("startTime") @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant startTime,
            @RequestParam("endTime") @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant endTime,
            @RequestParam(value = "limit", defaultValue = "500") @Min(1) @Max(5000) int limit);
}
