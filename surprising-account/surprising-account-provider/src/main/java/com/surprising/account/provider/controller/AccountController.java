package com.surprising.account.provider.controller;

import com.surprising.account.api.AccountApiPaths;
import com.surprising.account.api.model.AccountLedgerQueryResponse;
import com.surprising.account.api.model.AccountType;
import com.surprising.account.api.model.AdminBalanceAdjustmentQueryResponse;
import com.surprising.account.api.model.BalanceAdjustmentRequest;
import com.surprising.account.api.model.BalanceQueryResponse;
import com.surprising.account.api.model.BalanceResponse;
import com.surprising.account.api.model.PositionMarginAdjustmentRequest;
import com.surprising.account.api.model.PositionMarginAdjustmentResponse;
import com.surprising.account.api.model.PositionMarginResponse;
import com.surprising.account.api.model.PositionQueryResponse;
import com.surprising.account.api.model.PositionResponse;
import com.surprising.account.api.model.ProductBalanceAdjustmentRequest;
import com.surprising.account.api.model.ProductBalanceQueryResponse;
import com.surprising.account.api.model.ProductBalanceResponse;
import com.surprising.account.api.model.ProductLedgerQueryResponse;
import com.surprising.account.api.model.ProductTransferRecordQueryResponse;
import com.surprising.account.api.model.ProductTransferRequest;
import com.surprising.account.api.model.ProductTransferResponse;
import com.surprising.account.provider.service.AccountService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class AccountController {

    private static final String ADMIN_BASE_PATH = "/api/v1/admin/accounts";

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

    @PostMapping(ADMIN_BASE_PATH + "/balance-adjustments")
    public BalanceResponse adminAdjustBalance(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestHeader(value = "X-Admin-Username", required = false) String adminUsername,
            @RequestBody BalanceAdjustmentRequest request) {
        requireAdmin(adminUserId);
        try {
            return accountService.adminAdjustBalance(adminUserId, adminUsername, request);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage(), ex);
        }
    }

    @PostMapping(AccountApiPaths.ACCOUNT_ADMIN_BASE_PATH + "/product-balance-adjustments")
    public ProductBalanceResponse adjustProductBalance(@RequestBody ProductBalanceAdjustmentRequest request) {
        try {
            return accountService.adjustProductBalance(request);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage(), ex);
        }
    }

    @PostMapping(ADMIN_BASE_PATH + "/product-balance-adjustments")
    public ProductBalanceResponse adminAdjustProductBalance(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestHeader(value = "X-Admin-Username", required = false) String adminUsername,
            @RequestBody ProductBalanceAdjustmentRequest request) {
        requireAdmin(adminUserId);
        try {
            return accountService.adminAdjustProductBalance(adminUserId, adminUsername, request);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage(), ex);
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

    @GetMapping(ADMIN_BASE_PATH + "/balances")
    public BalanceQueryResponse adminBalances(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestParam("userId") long userId) {
        requireAdmin(adminUserId);
        return balances(userId);
    }

    @GetMapping(AccountApiPaths.ACCOUNT_BASE_PATH + "/product-balance")
    public ProductBalanceResponse productBalance(@RequestParam("userId") long userId,
                                                 @RequestParam("accountType") AccountType accountType,
                                                 @RequestParam("asset") String asset) {
        try {
            return accountService.productBalance(userId, accountType, asset);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @GetMapping(AccountApiPaths.ACCOUNT_BASE_PATH + "/product-balances")
    public ProductBalanceQueryResponse productBalances(@RequestParam("userId") long userId,
                                                       @RequestParam(value = "accountType", required = false)
                                                       AccountType accountType) {
        try {
            return accountService.productBalances(userId, accountType);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @GetMapping(ADMIN_BASE_PATH + "/product-balances")
    public ProductBalanceQueryResponse adminProductBalances(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestParam("userId") long userId,
            @RequestParam(value = "accountType", required = false) AccountType accountType) {
        requireAdmin(adminUserId);
        return productBalances(userId, accountType);
    }

    @PostMapping(AccountApiPaths.ACCOUNT_BASE_PATH + "/transfers")
    public ProductTransferResponse transfer(@RequestBody ProductTransferRequest request) {
        try {
            return accountService.transfer(request);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage(), ex);
        }
    }

    @GetMapping(AccountApiPaths.ACCOUNT_BASE_PATH + "/position")
    public PositionResponse position(@RequestParam("userId") long userId,
                                     @RequestParam("symbol") String symbol,
                                     @RequestParam(value = "marginMode", required = false) String marginMode,
                                     @RequestParam(value = "positionSide", required = false) String positionSide) {
        try {
            return accountService.position(userId, symbol, marginMode, positionSide);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @GetMapping(ADMIN_BASE_PATH + "/position")
    public PositionResponse adminPosition(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestParam("userId") long userId,
            @RequestParam("symbol") String symbol,
            @RequestParam(value = "marginMode", required = false) String marginMode,
            @RequestParam(value = "positionSide", required = false) String positionSide) {
        requireAdmin(adminUserId);
        return position(userId, symbol, marginMode, positionSide);
    }

    @GetMapping(AccountApiPaths.ACCOUNT_BASE_PATH + "/position-margin")
    public PositionMarginResponse positionMargin(@RequestParam("userId") long userId,
                                                 @RequestParam("symbol") String symbol,
                                                 @RequestParam(value = "marginMode", required = false) String marginMode) {
        try {
            return accountService.positionMargin(userId, symbol, marginMode);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @PostMapping(AccountApiPaths.ACCOUNT_BASE_PATH + "/position-margin-adjustments")
    public PositionMarginAdjustmentResponse adjustPositionMargin(
            @RequestBody PositionMarginAdjustmentRequest request) {
        try {
            return accountService.adjustPositionMargin(request);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage(), ex);
        }
    }

    @GetMapping(AccountApiPaths.ACCOUNT_BASE_PATH + "/positions")
    public PositionQueryResponse positions(@RequestParam("userId") long userId,
                                           @RequestParam(value = "positionSide", required = false) String positionSide) {
        try {
            return accountService.positions(userId, positionSide);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @GetMapping(ADMIN_BASE_PATH + "/positions")
    public PositionQueryResponse adminPositions(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestParam("userId") long userId,
            @RequestParam(value = "positionSide", required = false) String positionSide) {
        requireAdmin(adminUserId);
        return positions(userId, positionSide);
    }

    @GetMapping(ADMIN_BASE_PATH + "/ledger")
    public AccountLedgerQueryResponse accountLedger(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestParam(value = "userId", required = false) Long userId,
            @RequestParam(value = "asset", required = false) String asset,
            @RequestParam(value = "referenceType", required = false) String referenceType,
            @RequestParam(value = "limit", defaultValue = "100") int limit,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "sort", required = false) String sort) {
        requireAdmin(adminUserId);
        try {
            return accountService.accountLedger(userId, asset, referenceType, limit, cursor, sort);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @GetMapping(ADMIN_BASE_PATH + "/product-ledger")
    public ProductLedgerQueryResponse productLedger(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestParam(value = "userId", required = false) Long userId,
            @RequestParam(value = "accountType", required = false) AccountType accountType,
            @RequestParam(value = "asset", required = false) String asset,
            @RequestParam(value = "referenceType", required = false) String referenceType,
            @RequestParam(value = "limit", defaultValue = "100") int limit,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "sort", required = false) String sort) {
        requireAdmin(adminUserId);
        try {
            return accountService.productLedger(userId, accountType, asset, referenceType, limit, cursor, sort);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @GetMapping(ADMIN_BASE_PATH + "/transfers")
    public ProductTransferRecordQueryResponse productTransfers(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestParam(value = "userId", required = false) Long userId,
            @RequestParam(value = "accountType", required = false) AccountType accountType,
            @RequestParam(value = "asset", required = false) String asset,
            @RequestParam(value = "limit", defaultValue = "100") int limit,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "sort", required = false) String sort) {
        requireAdmin(adminUserId);
        try {
            return accountService.productTransfers(userId, accountType, asset, limit, cursor, sort);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @GetMapping(ADMIN_BASE_PATH + "/adjustments")
    public AdminBalanceAdjustmentQueryResponse adminBalanceAdjustments(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String headerAdminUserId,
            @RequestParam(value = "adminUserId", required = false) Long adminUserId,
            @RequestParam(value = "userId", required = false) Long userId,
            @RequestParam(value = "adjustmentKind", required = false) String adjustmentKind,
            @RequestParam(value = "accountType", required = false) AccountType accountType,
            @RequestParam(value = "asset", required = false) String asset,
            @RequestParam(value = "referenceId", required = false) String referenceId,
            @RequestParam(value = "limit", defaultValue = "100") int limit,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "sort", required = false) String sort) {
        requireAdmin(headerAdminUserId);
        try {
            return accountService.adminBalanceAdjustments(adminUserId, userId, adjustmentKind, accountType, asset,
                    referenceId, limit, cursor, sort);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    private void requireAdmin(String adminUserId) {
        if (adminUserId == null || adminUserId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "admin gateway header is required");
        }
    }
}
