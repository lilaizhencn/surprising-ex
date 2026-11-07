package com.surprising.gateway.provider.subaccount.controller;

import com.surprising.gateway.provider.auth.model.AuthModels.JwtPrincipal;
import com.surprising.gateway.provider.auth.service.AuthService;
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
    private final AuthService authService;

    public SubAccountController(SubAccountService subAccountService, AuthService authService) {
        this.subAccountService = subAccountService;
        this.authService = authService;
    }

    @PostMapping("/create")
    public SubAccountRecord create(@RequestHeader("Authorization") String authorization,
                                   @Valid @RequestBody CreateSubAccountRequest request) {
        JwtPrincipal principal = principal(authorization);
        try {
            return subAccountService.createSubAccount(principal.userId(), request);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage(), ex);
        }
    }

    @GetMapping("/list")
    public List<SubAccountRecord> list(@RequestHeader("Authorization") String authorization) {
        JwtPrincipal principal = principal(authorization);
        return subAccountService.listSubAccounts(principal.userId());
    }

    @PostMapping("/transfer")
    public SubAccountTransferResponse transfer(@RequestHeader("Authorization") String authorization,
                                               @Valid @RequestBody SubAccountTransferRequest request) {
        JwtPrincipal principal = principal(authorization);
        try {
            return subAccountService.transfer(principal.userId(), request);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage(), ex);
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
