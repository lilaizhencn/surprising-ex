package com.surprising.trading.order.controller;

import com.surprising.gateway.provider.auth.model.AuthModels.JwtPrincipal;
import com.surprising.gateway.provider.auth.service.AuthService;
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
    private final AuthService authService;

    public GridStrategyController(GridStrategyService gridStrategyService, AuthService authService) {
        this.gridStrategyService = gridStrategyService;
        this.authService = authService;
    }

    @PostMapping("/create")
    public GridStrategyRecord create(@RequestHeader("Authorization") String authorization,
                                     @Valid @RequestBody CreateGridStrategyRequest request,
                                     @RequestParam(defaultValue = "0") long currentPriceTicks) {
        JwtPrincipal principal = principal(authorization);
        try {
            return gridStrategyService.createAndStartGrid(principal.userId(), request, currentPriceTicks);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @GetMapping("/list")
    public List<GridStrategyRecord> list(@RequestHeader("Authorization") String authorization) {
        JwtPrincipal principal = principal(authorization);
        return gridStrategyService.listStrategies(principal.userId());
    }

    @PostMapping("/{strategyId}/stop")
    public GridStrategyRecord stop(@RequestHeader("Authorization") String authorization,
                                   @PathVariable long strategyId) {
        JwtPrincipal principal = principal(authorization);
        try {
            return gridStrategyService.stopGrid(principal.userId(), strategyId);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        }
    }

    private JwtPrincipal principal(String authorization) {
        try {
            return authService.authenticateBearer(authorization);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage(), ex);
        }
    }
}
