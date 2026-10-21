package com.surprising.gateway.provider.controller;

import com.surprising.gateway.provider.service.PortfolioIncomeService;
import com.surprising.gateway.provider.auth.AuthModels.JwtPrincipal;
import com.surprising.gateway.provider.auth.AuthService;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/portfolio")
public class PortfolioIncomeController {
    private final AuthService auth;
    private final PortfolioIncomeService income;
    public PortfolioIncomeController(AuthService auth, PortfolioIncomeService income) { this.auth = auth; this.income = income; }

    @GetMapping("/income")
    public ResponseEntity<PortfolioIncomeService.Summary> income(@RequestHeader(value = "Authorization", required = false) String authorization) {
        JwtPrincipal principal;
        try { principal = auth.authenticateBearer(authorization); }
        catch (IllegalArgumentException unauthenticated) { throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "sign in is required"); }
        catch (IllegalStateException forbidden) { throw new ResponseStatusException(HttpStatus.FORBIDDEN, "account is not available"); }
        try {
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(income.income(principal.userId()));
        } catch (DataAccessException unavailable) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "portfolio income is temporarily unavailable", unavailable);
        }
    }
}
