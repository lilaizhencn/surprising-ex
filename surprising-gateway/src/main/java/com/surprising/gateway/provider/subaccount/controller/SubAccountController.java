package com.surprising.gateway.provider.subaccount.controller;

import com.surprising.account.api.model.AccountType;
import com.surprising.account.api.model.ProductBalanceQueryResponse;
import com.surprising.gateway.provider.auth.model.AuthResponse;
import com.surprising.gateway.provider.auth.model.JwtPrincipal;
import com.surprising.gateway.provider.auth.model.CurrentUser;
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

    /** 在当前登录会话内切换到自己的子账号或切回主账号。 */
    @PostMapping("/operating-account")
    public AuthResponse switchOperatingAccount(@CurrentUser JwtPrincipal principal,
                                               @RequestBody(required = false) SwitchOperatingAccountRequest request) {
        try {
            Long userId = request == null ? null : request.userId();
            return authService.switchOperatingAccount(principal, userId);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage(), ex);
        }
    }

    @GetMapping("/{subUserId}/assets")
    public ProductBalanceQueryResponse assets(@CurrentUser long userId,
                                              @PathVariable long subUserId,
                                              @RequestParam(required = false) AccountType accountType) {
        try {
            return subAccountService.assets(userId, subUserId, accountType);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage(), ex);
        }
    }

    @PostMapping("/{subUserId}/status")
    public SubAccountRecord updateStatus(@CurrentUser long userId,
                                         @PathVariable long subUserId,
                                         @Valid @RequestBody UpdateSubAccountStatusRequest request) {
        try {
            return subAccountService.updateStatus(userId, subUserId, request.status());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage(), ex);
        }
    }
}
