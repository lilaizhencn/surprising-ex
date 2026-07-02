package com.surprising.marketmaker.provider.controller;

import com.surprising.marketmaker.api.MarketMakerApiPaths;
import com.surprising.marketmaker.api.model.MarketMakerRunRequest;
import com.surprising.marketmaker.api.model.MarketMakerStrategyQueryResponse;
import com.surprising.marketmaker.api.model.MarketMakerStrategyResponse;
import com.surprising.marketmaker.provider.service.MarketMakerService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class MarketMakerController {

    private final MarketMakerService marketMakerService;

    public MarketMakerController(MarketMakerService marketMakerService) {
        this.marketMakerService = marketMakerService;
    }

    @GetMapping(MarketMakerApiPaths.BASE_PATH + "/strategies")
    public MarketMakerStrategyQueryResponse strategies() {
        return marketMakerService.strategies();
    }

    @GetMapping(MarketMakerApiPaths.BASE_PATH + "/strategies/{strategyId}")
    public MarketMakerStrategyResponse strategy(@PathVariable("strategyId") String strategyId) {
        try {
            return marketMakerService.strategy(strategyId);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        }
    }

    @PostMapping(MarketMakerApiPaths.BASE_PATH + "/strategies/{strategyId}/pause")
    public MarketMakerStrategyResponse pause(@PathVariable("strategyId") String strategyId) {
        try {
            return marketMakerService.pause(strategyId);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        }
    }

    @PostMapping(MarketMakerApiPaths.BASE_PATH + "/strategies/{strategyId}/resume")
    public MarketMakerStrategyResponse resume(@PathVariable("strategyId") String strategyId) {
        try {
            return marketMakerService.resume(strategyId);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        }
    }

    @PostMapping(MarketMakerApiPaths.BASE_PATH + "/run-once")
    public MarketMakerStrategyQueryResponse runOnce(@RequestBody(required = false) MarketMakerRunRequest request) {
        try {
            return marketMakerService.runOnce(request == null ? new MarketMakerRunRequest(null, null) : request);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }
}
