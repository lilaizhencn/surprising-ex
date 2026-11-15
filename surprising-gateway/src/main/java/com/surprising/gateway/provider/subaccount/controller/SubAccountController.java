package com.surprising.gateway.provider.subaccount.controller;

import com.surprising.gateway.provider.auth.model.CurrentUser;
import com.surprising.gateway.provider.subaccount.model.SubAccountModels.*;
import com.surprising.gateway.provider.subaccount.service.SubAccountService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/sub-account")
public class SubAccountController {

    private final SubAccountService subAccountService;

    public SubAccountController(SubAccountService subAccountService) {
        this.subAccountService = subAccountService;
    }

    @PostMapping("/create")
    public SubAccountRecord create(@CurrentUser long userId,
                                   @Valid @RequestBody CreateSubAccountRequest request) {
        try {
            return subAccountService.createSubAccount(userId, request);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage(), ex);
        }
    }

    @GetMapping("/list")
    public List<SubAccountRecord> list(@CurrentUser long userId) {
        return subAccountService.listSubAccounts(userId);
    }

    @PostMapping("/transfer")
    public SubAccountTransferResponse transfer(@CurrentUser long userId,
                                               @Valid @RequestBody SubAccountTransferRequest request) {
        try {
            return subAccountService.transfer(userId, request);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage(), ex);
        }
    }
}
