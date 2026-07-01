package com.surprising.liquidation.provider.controller;

import com.surprising.liquidation.api.LiquidationApiPaths;
import com.surprising.liquidation.api.model.LiquidationOrderQueryResponse;
import com.surprising.liquidation.provider.service.LiquidationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class LiquidationController {

    private final LiquidationService liquidationService;

    public LiquidationController(LiquidationService liquidationService) {
        this.liquidationService = liquidationService;
    }

    @GetMapping(LiquidationApiPaths.BASE_PATH + "/orders")
    public LiquidationOrderQueryResponse orders(@RequestParam(value = "userId", required = false) Long userId,
                                                @RequestParam(value = "limit", defaultValue = "100") int limit) {
        return liquidationService.orders(userId, limit);
    }

    @GetMapping(LiquidationApiPaths.BASE_PATH + "/orders/by-candidate")
    public LiquidationOrderQueryResponse ordersByCandidate(@RequestParam("candidateId") long candidateId) {
        return liquidationService.ordersByCandidate(candidateId);
    }
}
