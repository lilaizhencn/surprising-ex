package com.surprising.gateway.provider.controller;

import com.surprising.gateway.provider.auth.AuthModels.JwtPrincipal;
import com.surprising.gateway.provider.auth.AuthService;
import com.surprising.gateway.provider.service.CustodyWithdrawalService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/admin/wallet/withdrawals")
public class AdminCustodyWithdrawalController {

    private final AuthService authService;
    private final CustodyWithdrawalService withdrawalService;

    public AdminCustodyWithdrawalController(AuthService authService,
                                            CustodyWithdrawalService withdrawalService) {
        this.authService = authService;
        this.withdrawalService = withdrawalService;
    }

    @GetMapping
    public List<Map<String, Object>> list(
            @RequestHeader("Authorization") String authorization,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "limit", defaultValue = "50") int limit) {
        authService.requireAdminPermission(authorization, "admin.wallet.read");
        return withdrawalService.adminList(status, limit);
    }

    @PostMapping("/{withdrawalId}/approve")
    public CustodyWithdrawalService.WithdrawalResponse approve(
            @RequestHeader("Authorization") String authorization,
            @PathVariable UUID withdrawalId,
            @RequestBody(required = false) ActionRequest request) {
        return execute(authorization, withdrawalId, request, Action.APPROVE);
    }

    @PostMapping("/{withdrawalId}/reject")
    public CustodyWithdrawalService.WithdrawalResponse reject(
            @RequestHeader("Authorization") String authorization,
            @PathVariable UUID withdrawalId,
            @RequestBody(required = false) ActionRequest request) {
        return execute(authorization, withdrawalId, request, Action.REJECT);
    }

    @PostMapping("/{withdrawalId}/retry")
    public CustodyWithdrawalService.WithdrawalResponse retry(
            @RequestHeader("Authorization") String authorization,
            @PathVariable UUID withdrawalId,
            @RequestBody(required = false) ActionRequest request) {
        JwtPrincipal principal = authService.requireAdminPermission(authorization, "admin.wallet.write");
        String reason = request == null || request.reason() == null ? "" : request.reason().trim();
        if (reason.isBlank() || reason.length() > 500) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "a non-blank reason up to 500 characters is required");
        }
        try {
            return withdrawalService.retry(withdrawalId, principal.userId(), principal.username(), reason);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (CustodyWithdrawalService.WithdrawalUnknownException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage(), ex);
        }
    }

    private CustodyWithdrawalService.WithdrawalResponse execute(String authorization, UUID withdrawalId,
                                                                ActionRequest request, Action action) {
        JwtPrincipal principal = authService.requireAdminPermission(authorization, "admin.wallet.write");
        String reason = request == null || request.reason() == null ? "" : request.reason().trim();
        if (reason.isBlank() || reason.length() > 500) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "a non-blank reason up to 500 characters is required");
        }
        try {
            return action == Action.APPROVE
                    ? withdrawalService.approve(withdrawalId, principal.userId(), principal.username(), reason)
                    : withdrawalService.reject(withdrawalId, principal.userId(), principal.username(), reason);
        } catch (CustodyWithdrawalService.WithdrawalRejectedException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (CustodyWithdrawalService.WithdrawalUnknownException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage(), ex);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    private enum Action {
        APPROVE, REJECT
    }

    public record ActionRequest(String reason) {
    }
}
