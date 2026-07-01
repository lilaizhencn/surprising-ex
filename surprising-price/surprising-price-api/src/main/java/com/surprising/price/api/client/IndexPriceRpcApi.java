package com.surprising.price.api.client;

import com.surprising.price.api.PriceApiPaths;
import com.surprising.price.api.model.IndexPriceQueryResponse;
import com.surprising.price.api.model.IndexPriceResponse;
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
        name = "surprising-index-price-provider",
        contextId = "indexPriceRpcApi",
        path = PriceApiPaths.INDEX_BASE_PATH,
        url = "${surprising.clients.index-price.base-url:http://localhost:9082}")
public interface IndexPriceRpcApi {

    @GetMapping("/latest")
    IndexPriceResponse latestIndexPrice(@RequestParam("symbol") @NotBlank String symbol);

    @GetMapping("/history")
    IndexPriceQueryResponse history(
            @RequestParam("symbol") @NotBlank String symbol,
            @RequestParam("startTime") @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant startTime,
            @RequestParam("endTime") @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant endTime,
            @RequestParam(value = "limit", defaultValue = "500") @Min(1) @Max(5000) int limit);
}
