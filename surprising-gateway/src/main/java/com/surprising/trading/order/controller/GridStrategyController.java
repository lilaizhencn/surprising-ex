package com.surprising.trading.order.controller;

import com.surprising.gateway.provider.auth.model.OperatingUser;
import com.surprising.trading.order.model.GridStrategyModels.CreateGridStrategyRequest;
import com.surprising.trading.order.model.GridStrategyModels.GridStrategyRecord;
import com.surprising.trading.order.service.GridStrategyService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/trading/grid")
public class GridStrategyController {

    private final GridStrategyService gridStrategyService;

    public GridStrategyController(GridStrategyService gridStrategyService) {
        this.gridStrategyService = gridStrategyService;
    }

    @PostMapping("/create")
    public GridStrategyRecord create(@OperatingUser long userId,
                                     @Valid @RequestBody CreateGridStrategyRequest request,
                                     @RequestParam(defaultValue = "0") long currentPriceTicks) {
        try {
            return gridStrategyService.createAndStartGrid(userId, request, currentPriceTicks);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @GetMapping("/list")
    public List<GridStrategyRecord> list(@OperatingUser long userId) {
        return gridStrategyService.listStrategies(userId);
    }

    @PostMapping("/{strategyId}/stop")
    public GridStrategyRecord stop(@OperatingUser long userId,
                                   @PathVariable long strategyId) {
        try {
            return gridStrategyService.stopGrid(userId, strategyId);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        }
    }
}
