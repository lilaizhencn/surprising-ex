package com.surprising.account.provider.controller;

import com.surprising.account.api.AccountApiPaths;
import com.surprising.account.api.model.BalanceAdjustmentRequest;
import com.surprising.account.api.model.BalanceQueryResponse;
import com.surprising.account.api.model.BalanceResponse;
import com.surprising.account.api.model.PositionQueryResponse;
import com.surprising.account.api.model.PositionResponse;
import com.surprising.account.provider.service.AccountService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @PostMapping(AccountApiPaths.ACCOUNT_ADMIN_BASE_PATH + "/balance-adjustments")
    public BalanceResponse adjustBalance(@RequestBody BalanceAdjustmentRequest request) {
        try {
            return accountService.adjustBalance(request);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @GetMapping(AccountApiPaths.ACCOUNT_BASE_PATH + "/balance")
    public BalanceResponse balance(@RequestParam("userId") long userId,
                                   @RequestParam("asset") String asset) {
        try {
            return accountService.balance(userId, asset);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @GetMapping(AccountApiPaths.ACCOUNT_BASE_PATH + "/balances")
    public BalanceQueryResponse balances(@RequestParam("userId") long userId) {
        return accountService.balances(userId);
    }

    @GetMapping(AccountApiPaths.ACCOUNT_BASE_PATH + "/position")
    public PositionResponse position(@RequestParam("userId") long userId,
                                     @RequestParam("symbol") String symbol,
                                     @RequestParam(value = "marginMode", required = false) String marginMode) {
        try {
            return accountService.position(userId, symbol, marginMode);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @GetMapping(AccountApiPaths.ACCOUNT_BASE_PATH + "/positions")
    public PositionQueryResponse positions(@RequestParam("userId") long userId) {
        return accountService.positions(userId);
    }
}
