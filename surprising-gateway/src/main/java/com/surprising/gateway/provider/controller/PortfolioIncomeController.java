package com.surprising.gateway.provider.controller;

import com.surprising.gateway.provider.service.PortfolioIncomeService;
import com.surprising.gateway.provider.auth.service.AuthService;
import com.surprising.gateway.provider.auth.model.CurrentUser;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/portfolio")
public class PortfolioIncomeController {
    private final PortfolioIncomeService income;
    @org.springframework.beans.factory.annotation.Autowired
    public PortfolioIncomeController(PortfolioIncomeService income) { this.income = income; }
    public PortfolioIncomeController(AuthService auth, PortfolioIncomeService income) { this(income); }

    @GetMapping("/income")
    public ResponseEntity<PortfolioIncomeService.Summary> income(@CurrentUser long userId) {
        try {
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(income.income(userId));
        } catch (DataAccessException unavailable) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "portfolio income is temporarily unavailable", unavailable);
        }
    }
}
