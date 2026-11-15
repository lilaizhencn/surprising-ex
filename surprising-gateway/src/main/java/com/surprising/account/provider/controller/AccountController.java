package com.surprising.account.provider.controller;

import com.surprising.account.api.model.AccountLedgerQueryResponse;
import com.surprising.account.api.model.AccountType;
import com.surprising.account.api.model.AdminBalanceAdjustmentQueryResponse;
import com.surprising.account.api.model.BalanceAdjustmentRequest;
import com.surprising.account.api.model.BalanceQueryResponse;
import com.surprising.account.api.model.BalanceResponse;
import com.surprising.account.api.model.PositionMarginAdjustmentRequest;
import com.surprising.account.api.model.PositionMarginAdjustmentResponse;
import com.surprising.account.api.model.PositionMarginResponse;
import com.surprising.account.api.model.PositionModeResponse;
import com.surprising.account.api.model.PositionModeUpdateRequest;
import com.surprising.account.api.model.PositionQueryResponse;
import com.surprising.account.api.model.PositionResponse;
import com.surprising.account.api.model.ProductBalanceAdjustmentRequest;
import com.surprising.account.api.model.ProductBalanceQueryResponse;
import com.surprising.account.api.model.ProductBalanceResponse;
import com.surprising.account.api.model.ProductLedgerQueryResponse;
import com.surprising.account.api.model.ProductTransferRecordQueryResponse;
import com.surprising.account.api.model.ProductTransferRequest;
import com.surprising.account.api.model.ProductTransferResponse;
import com.surprising.account.provider.exception.AccountCommandTimeoutException;
import com.surprising.account.provider.service.AccountCommandGateway;
import com.surprising.account.provider.service.AccountQueryService;
import com.surprising.account.provider.service.AccountService;
import com.surprising.gateway.provider.product.GatewayUserContext;
import com.surprising.product.api.ProductLine;
import jakarta.validation.Valid;
import java.util.function.Supplier;
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
    private final AccountService accountService;
    private final AccountQueryService history;
    private final AccountCommandGateway commandGateway;

    public AccountController(AccountService accountService,
                             AccountQueryService history,
                             AccountCommandGateway commandGateway) {
        this.accountService = accountService;
        this.history = history;
        this.commandGateway = commandGateway;
    }

    @PostMapping("/api/v1/accounts/admin/product-balance-adjustments")
    public ProductBalanceResponse adjustProductBalance(
            @Valid @RequestBody ProductBalanceAdjustmentRequest request) {
        return executeCommand(() -> commandGateway.adjustProductBalance(AccountInputBinding.productAdjustment(request), null, null));
    }

    @PostMapping("/api/v1/admin/accounts/product-balance-adjustments")
    public ProductBalanceResponse adminAdjustProductBalance(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestHeader(value = "X-Admin-Username", required = false) String adminUsername,
            @Valid @RequestBody ProductBalanceAdjustmentRequest request) {
        GatewayUserContext.requireAdminUserId(adminUserId);
        return executeCommand(() -> commandGateway.adjustProductBalance(AccountInputBinding.productAdjustment(request), adminUserId, adminUsername));
    }

    @PostMapping("/api/v1/accounts/position-margin-adjustments")
    public PositionMarginAdjustmentResponse adjustPositionMargin(@RequestBody PositionMarginAdjustmentRequest request) {
        return executeCommand(() -> commandGateway.adjustPositionMargin(AccountInputBinding.positionMargin(request)));
    }

    @PostMapping("/api/v1/accounts/admin/balance-adjustments")
    public BalanceResponse adjustBalance(
            @Valid @RequestBody BalanceAdjustmentRequest request) {
        return executeCommand(() -> commandGateway.adjustBalance(AccountInputBinding.adjustment(request), null, null));
    }

    @PostMapping("/api/v1/admin/accounts/balance-adjustments")
    public BalanceResponse adminAdjustBalance(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestHeader(value = "X-Admin-Username", required = false) String adminUsername,
            @RequestBody BalanceAdjustmentRequest request) {
        GatewayUserContext.requireAdminUserId(adminUserId);
        return executeCommand(() -> commandGateway.adjustBalance(AccountInputBinding.adjustment(request), adminUserId, adminUsername));
    }

    @GetMapping("/api/v1/admin/accounts/product-balances")
    public ProductBalanceQueryResponse adminProductBalances(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestParam("userId") long userId,
            @RequestParam(value = "accountType", required = false) AccountType accountType) {
        GatewayUserContext.requireAdminUserId(adminUserId);
        return executeQuery(() -> accountService.productBalances(userId, accountType));
    }

    @GetMapping("/api/v1/admin/accounts/product-ledger")
    public ProductLedgerQueryResponse productLedger(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestParam(value = "userId", required = false) Long userId,
            @RequestParam(value = "accountType", required = false) AccountType accountType,
            @RequestParam(value = "asset", required = false) String asset,
            @RequestParam(value = "referenceType", required = false) String referenceType,
            @RequestParam(value = "limit", defaultValue = "100") int limit,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "sort", required = false) String sort) {
        GatewayUserContext.requireAdminUserId(adminUserId);
        return executeQuery(() -> {
            AccountHistoryRequestBinding.userId(userId);
            return history.productLedger(userId, accountType,
                    AccountHistoryRequestBinding.optionalAsset(asset),
                    AccountHistoryRequestBinding.optionalReferenceType(referenceType),
                    AccountHistoryRequestBinding.page(limit, cursor, sort));
        });
    }

    @GetMapping("/api/v1/admin/accounts/adjustments")
    public AdminBalanceAdjustmentQueryResponse adminBalanceAdjustments(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestParam(value = "adminUserId", required = false) Long filterAdminUserId,
            @RequestParam(value = "userId", required = false) Long userId,
            @RequestParam(value = "adjustmentKind", required = false) String adjustmentKind,
            @RequestParam(value = "accountType", required = false) AccountType accountType,
            @RequestParam(value = "asset", required = false) String asset,
            @RequestParam(value = "referenceId", required = false) String referenceId,
            @RequestParam(value = "limit", defaultValue = "100") int limit,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "sort", required = false) String sort) {
        GatewayUserContext.requireAdminUserId(adminUserId);
        return executeQuery(() -> {
            AccountHistoryRequestBinding.userId(userId);
            AccountHistoryRequestBinding.userId(filterAdminUserId);
            return history.adminBalanceAdjustments(filterAdminUserId, userId,
                    AccountHistoryRequestBinding.optionalAdjustmentKind(adjustmentKind), accountType,
                    AccountHistoryRequestBinding.optionalAsset(asset),
                    AccountHistoryRequestBinding.optionalReferenceId(referenceId),
                    AccountHistoryRequestBinding.page(limit, cursor, sort));
        });
    }

    @GetMapping("/api/v1/accounts/product-balances")
    public ProductBalanceQueryResponse productBalances(
            @RequestParam(value = "userId", required = false) Long queryUserId,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestParam(value = "accountType", required = false) AccountType accountType) {
        long userId = GatewayUserContext.requireUserId(queryUserId, headerUserId, adminUserId);
        return executeQuery(() -> accountService.productBalances(userId, accountType));
    }

    @GetMapping("/api/v1/accounts/product-balance")
    public ProductBalanceResponse productBalance(
            @RequestParam(value = "userId", required = false) Long queryUserId,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestParam("accountType") AccountType accountType,
            @RequestParam("asset") String asset) {
        long userId = GatewayUserContext.requireUserId(queryUserId, headerUserId, adminUserId);
        return executeQuery(() -> accountService.productBalance(userId, accountType, AccountInputBinding.asset(asset)));
    }

    @GetMapping("/api/v1/accounts/position-margin")
    public PositionMarginResponse positionMargin(
            @RequestParam(value = "userId", required = false) Long queryUserId,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestParam("instrumentId") String instrumentId,
            @RequestParam(value = "marginMode", required = false) String marginMode) {
        long userId = GatewayUserContext.requireUserId(queryUserId, headerUserId, adminUserId);
        return executeQuery(() -> accountService.positionMargin(userId,
                AccountInputBinding.instrument(instrumentId), AccountInputBinding.marginMode(marginMode)));
    }

    @GetMapping("/api/v1/admin/accounts/positions")
    public PositionQueryResponse adminPositions(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestParam("userId") long userId,
            @RequestParam(value = "positionSide", required = false) String positionSide) {
        GatewayUserContext.requireAdminUserId(adminUserId);
        return executeQuery(() -> accountService.positions(userId, AccountInputBinding.optionalPositionSide(positionSide)));
    }

    @GetMapping("/api/v1/admin/accounts/transfers")
    public ProductTransferRecordQueryResponse productTransfers(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestParam(value = "userId", required = false) Long userId,
            @RequestParam(value = "accountType", required = false) AccountType accountType,
            @RequestParam(value = "asset", required = false) String asset,
            @RequestParam(value = "limit", defaultValue = "100") int limit,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "sort", required = false) String sort) {
        GatewayUserContext.requireAdminUserId(adminUserId);
        return executeQuery(() -> {
            AccountHistoryRequestBinding.userId(userId);
            return history.productTransfers(userId, accountType,
                    AccountHistoryRequestBinding.optionalAsset(asset),
                    AccountHistoryRequestBinding.page(limit, cursor, sort));
        });
    }

    @GetMapping("/api/v1/admin/accounts/balances")
    public BalanceQueryResponse adminBalances(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestParam("userId") long userId) {
        GatewayUserContext.requireAdminUserId(adminUserId);
        return accountService.balances(userId);
    }

    @GetMapping("/api/v1/admin/accounts/position")
    public PositionResponse adminPosition(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestParam("userId") long userId,
            @RequestParam("instrumentId") String instrumentId,
            @RequestParam(value = "marginMode", required = false) String marginMode,
            @RequestParam(value = "positionSide", required = false) String positionSide) {
        GatewayUserContext.requireAdminUserId(adminUserId);
        return executeQuery(() -> accountService.position(userId, AccountInputBinding.instrument(instrumentId),
                AccountInputBinding.marginMode(marginMode), AccountInputBinding.positionSide(positionSide)));
    }

    @GetMapping("/api/v1/accounts/product-ledger")
    public ProductLedgerQueryResponse userProductLedger(
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestParam("accountType") AccountType accountType,
            @RequestParam(value = "asset", required = false) String asset,
            @RequestParam(value = "referenceType", required = false) String referenceType,
            @RequestParam(value = "limit", defaultValue = "50") int limit,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "sort", required = false) String sort) {
        long userId = GatewayUserContext.requireHeaderUserId(headerUserId);
        return executeQuery(() -> {
            AccountHistoryRequestBinding.userId(userId);
            return history.productLedger(userId, accountType,
                    AccountHistoryRequestBinding.optionalAsset(asset),
                    AccountHistoryRequestBinding.optionalReferenceType(referenceType),
                    AccountHistoryRequestBinding.page(limit, cursor, sort));
        });
    }

    @GetMapping("/api/v1/accounts/position-mode")
    public PositionModeResponse positionMode(
            @RequestParam(value = "userId", required = false) Long queryUserId,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestHeader(value = "X-Product-Line", required = false) String headerProductLine,
            @RequestParam(value = "productLine", required = false) String queryProductLine) {
        long userId = GatewayUserContext.requireUserId(queryUserId, headerUserId, adminUserId);
        return executeQuery(() -> accountService.positionMode(productLine(queryProductLine, headerProductLine), userId));
    }

    @PostMapping("/api/v1/accounts/position-mode")
    public PositionModeResponse updatePositionMode(
            @RequestBody PositionModeUpdateRequest request,
            @RequestHeader(value = "X-Product-Line", required = false) String headerProductLine,
            @RequestParam(value = "productLine", required = false) String queryProductLine) {
        return executeCommand(() -> commandGateway.updatePositionMode(
                AccountInputBinding.positionMode(withProductLine(request, productLine(queryProductLine, headerProductLine)))));
    }

    @GetMapping("/api/v1/admin/accounts/ledger")
    public AccountLedgerQueryResponse accountLedger(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestParam(value = "userId", required = false) Long userId,
            @RequestParam(value = "asset", required = false) String asset,
            @RequestParam(value = "referenceType", required = false) String referenceType,
            @RequestParam(value = "limit", defaultValue = "100") int limit,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "sort", required = false) String sort) {
        GatewayUserContext.requireAdminUserId(adminUserId);
        return executeQuery(() -> {
            AccountHistoryRequestBinding.userId(userId);
            return history.accountLedger(userId,
                    AccountHistoryRequestBinding.optionalAsset(asset),
                    AccountHistoryRequestBinding.optionalReferenceType(referenceType),
                    AccountHistoryRequestBinding.page(limit, cursor, sort));
        });
    }

    @PostMapping("/api/v1/accounts/transfers")
    public ProductTransferResponse transfer(@RequestBody ProductTransferRequest request) {
        return executeCommand(() -> commandGateway.transfer(request));
    }

    @GetMapping("/api/v1/accounts/positions")
    public PositionQueryResponse positions(
            @RequestParam(value = "userId", required = false) Long queryUserId,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestParam(value = "positionSide", required = false) String positionSide) {
        long userId = GatewayUserContext.requireUserId(queryUserId, headerUserId, adminUserId);
        return executeQuery(() -> accountService.positions(userId, AccountInputBinding.optionalPositionSide(positionSide)));
    }

    @GetMapping("/api/v1/accounts/transfers")
    public ProductTransferRecordQueryResponse userProductTransfers(
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestParam("accountType") AccountType accountType,
            @RequestParam(value = "asset", required = false) String asset,
            @RequestParam(value = "limit", defaultValue = "50") int limit,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "sort", required = false) String sort) {
        long userId = GatewayUserContext.requireHeaderUserId(headerUserId);
        return executeQuery(() -> {
            AccountHistoryRequestBinding.userId(userId);
            return history.productTransfers(userId, accountType,
                    AccountHistoryRequestBinding.optionalAsset(asset),
                    AccountHistoryRequestBinding.page(limit, cursor, sort));
        });
    }

    @GetMapping("/api/v1/accounts/balances")
    public BalanceQueryResponse balances(
            @RequestParam(value = "userId", required = false) Long queryUserId,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId) {
        long userId = GatewayUserContext.requireUserId(queryUserId, headerUserId, adminUserId);
        return accountService.balances(userId);
    }

    @GetMapping("/api/v1/accounts/position")
    public PositionResponse position(
            @RequestParam(value = "userId", required = false) Long queryUserId,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestParam("instrumentId") String instrumentId,
            @RequestParam(value = "marginMode", required = false) String marginMode,
            @RequestParam(value = "positionSide", required = false) String positionSide) {
        long userId = GatewayUserContext.requireUserId(queryUserId, headerUserId, adminUserId);
        return executeQuery(() -> accountService.position(userId, AccountInputBinding.instrument(instrumentId),
                AccountInputBinding.marginMode(marginMode), AccountInputBinding.positionSide(positionSide)));
    }

    @GetMapping("/api/v1/accounts/balance")
    public BalanceResponse balance(
            @RequestParam(value = "userId", required = false) Long queryUserId,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestParam("asset") String asset) {
        long userId = GatewayUserContext.requireUserId(queryUserId, headerUserId, adminUserId);
        return executeQuery(() -> accountService.balance(userId, AccountInputBinding.asset(asset)));
    }

    @GetMapping("/api/v1/accounts/ledger")
    public AccountLedgerQueryResponse userAccountLedger(
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestParam(value = "asset", required = false) String asset,
            @RequestParam(value = "referenceType", required = false) String referenceType,
            @RequestParam(value = "limit", defaultValue = "10") int limit,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "sort", required = false) String sort) {
        long userId = GatewayUserContext.requireHeaderUserId(headerUserId);
        return executeQuery(() -> {
            AccountHistoryRequestBinding.userId(userId);
            return history.accountLedger(userId,
                    AccountHistoryRequestBinding.optionalAsset(asset),
                    AccountHistoryRequestBinding.optionalReferenceType(referenceType),
                    AccountHistoryRequestBinding.page(limit, cursor, sort));
        });
    }

    private static <T> T executeCommand(Supplier<T> action) {
        try {
            return action.get();
        } catch (AccountCommandTimeoutException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage(), ex);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage(), ex);
        }
    }

    private static <T> T executeQuery(Supplier<T> action) {
        try {
            return action.get();
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    private static PositionModeUpdateRequest withProductLine(PositionModeUpdateRequest request, ProductLine productLine) {
        if (request == null || request.productLine() != null || productLine == null) {
            return request;
        }
        return new PositionModeUpdateRequest(request.userId(), productLine, request.positionMode(), request.referenceId());
    }

    private static ProductLine productLine(String queryValue, String headerValue) {
        String value = queryValue == null || queryValue.isBlank() ? headerValue : queryValue;
        if (value == null || value.isBlank()) {
            return null;
        }
        return ProductLine.requireExternalCode(value);
    }
}
