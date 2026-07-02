package com.surprising.marketmaker.api.client;

import com.surprising.marketmaker.api.MarketMakerApiPaths;
import com.surprising.marketmaker.api.model.MarketMakerRunRequest;
import com.surprising.marketmaker.api.model.MarketMakerStrategyQueryResponse;
import com.surprising.marketmaker.api.model.MarketMakerStrategyResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(
        name = "surprising-market-maker-provider",
        contextId = "marketMakerRpcApi",
        path = MarketMakerApiPaths.BASE_PATH,
        url = "${surprising.clients.market-maker.base-url:http://localhost:9096}")
public interface MarketMakerRpcApi {

    @GetMapping("/strategies")
    MarketMakerStrategyQueryResponse strategies();

    @GetMapping("/strategies/{strategyId}")
    MarketMakerStrategyResponse strategy(@PathVariable("strategyId") @NotBlank String strategyId);

    @PostMapping("/strategies/{strategyId}/pause")
    MarketMakerStrategyResponse pause(@PathVariable("strategyId") @NotBlank String strategyId);

    @PostMapping("/strategies/{strategyId}/resume")
    MarketMakerStrategyResponse resume(@PathVariable("strategyId") @NotBlank String strategyId);

    @PostMapping("/run-once")
    MarketMakerStrategyQueryResponse runOnce(@Valid @RequestBody MarketMakerRunRequest request);
}
