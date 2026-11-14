package com.surprising.account.provider.controller;

import com.surprising.account.api.model.AccountType;
import com.surprising.account.api.model.BalanceAdjustmentRequest;
import com.surprising.account.api.model.PositionMarginAdjustmentRequest;
import com.surprising.account.api.model.PositionModeUpdateRequest;
import com.surprising.account.api.model.ProductBalanceAdjustmentRequest;
import com.surprising.account.api.model.ProductTransferRequest;
import com.surprising.gateway.provider.product.GatewayUserContext;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AccountMvcController {
    private final AccountRequestHandler accountRequests;

    public AccountMvcController(AccountRequestHandler accountRequests) {
        this.accountRequests = accountRequests;
    }

    @PostMapping("/api/v1/accounts/admin/product-balance-adjustments")
    public Object adjustProductBalance(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @Valid @RequestBody ProductBalanceAdjustmentRequest request) {
        GatewayUserContext.requireAdminUserId(adminUserId);
        return accountRequests.adjustProductBalance(request);
    }

    @PostMapping("/api/v1/admin/accounts/product-balance-adjustments")
    public Object adminAdjustProductBalance(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestHeader(value = "X-Admin-Username", required = false) String adminUsername,
            @Valid @RequestBody ProductBalanceAdjustmentRequest request) {
        GatewayUserContext.requireAdminUserId(adminUserId);
        return accountRequests.adminAdjustProductBalance(adminUserId, adminUsername, request);
    }

    @PostMapping("/api/v1/accounts/position-margin-adjustments")
    public Object adjustPositionMargin(@RequestBody PositionMarginAdjustmentRequest request) {
        return accountRequests.adjustPositionMargin(request);
    }

    @PostMapping("/api/v1/accounts/admin/balance-adjustments")
    public Object adjustBalance(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @Valid @RequestBody BalanceAdjustmentRequest request) {
        GatewayUserContext.requireAdminUserId(adminUserId);
        return accountRequests.adjustBalance(request);
    }

    @PostMapping("/api/v1/admin/accounts/balance-adjustments")
    public Object adminAdjustBalance(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestHeader(value = "X-Admin-Username", required = false) String adminUsername,
            @RequestBody BalanceAdjustmentRequest request) {
        GatewayUserContext.requireAdminUserId(adminUserId);
        return accountRequests.adminAdjustBalance(adminUserId, adminUsername, request);
    }

    @GetMapping("/api/v1/admin/accounts/product-balances")
    public Object adminProductBalances(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestParam("userId") long userId,
            @RequestParam(value = "accountType", required = false) AccountType accountType) {
        GatewayUserContext.requireAdminUserId(adminUserId);
        return accountRequests.adminProductBalances(adminUserId, userId, accountType);
    }

    @GetMapping("/api/v1/admin/accounts/product-ledger")
    public Object productLedger(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestParam(value = "userId", required = false) Long userId,
            @RequestParam(value = "accountType", required = false) AccountType accountType,
            @RequestParam(value = "asset", required = false) String asset,
            @RequestParam(value = "referenceType", required = false) String referenceType,
            @RequestParam(value = "limit", defaultValue = "100") int limit,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "sort", required = false) String sort) {
        GatewayUserContext.requireAdminUserId(adminUserId);
        return accountRequests.productLedger(adminUserId, userId, accountType, asset, referenceType, limit, cursor, sort);
    }

    @GetMapping("/api/v1/admin/accounts/adjustments")
    public Object adminBalanceAdjustments(
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
        return accountRequests.adminBalanceAdjustments(adminUserId, filterAdminUserId, userId, adjustmentKind,
                accountType, asset, referenceId, limit, cursor, sort);
    }

    @GetMapping("/api/v1/accounts/product-balances")
    public Object productBalances(
            @RequestParam(value = "userId", required = false) Long queryUserId,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestParam(value = "accountType", required = false) AccountType accountType) {
        long userId = GatewayUserContext.requireUserId(queryUserId, headerUserId, adminUserId);
        return accountRequests.productBalances(userId, accountType);
    }

    @GetMapping("/api/v1/accounts/product-balance")
    public Object productBalance(
            @RequestParam(value = "userId", required = false) Long queryUserId,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestParam("accountType") AccountType accountType,
            @RequestParam("asset") String asset) {
        long userId = GatewayUserContext.requireUserId(queryUserId, headerUserId, adminUserId);
        return accountRequests.productBalance(userId, accountType, asset);
    }

    @GetMapping("/api/v1/accounts/position-margin")
    public Object positionMargin(
            @RequestParam(value = "userId", required = false) Long queryUserId,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestParam("instrumentId") String instrumentId,
            @RequestParam(value = "marginMode", required = false) String marginMode) {
        long userId = GatewayUserContext.requireUserId(queryUserId, headerUserId, adminUserId);
        return accountRequests.positionMargin(userId, instrumentId, marginMode);
    }

    @GetMapping("/api/v1/admin/accounts/positions")
    public Object adminPositions(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestParam("userId") long userId,
            @RequestParam(value = "positionSide", required = false) String positionSide) {
        GatewayUserContext.requireAdminUserId(adminUserId);
        return accountRequests.adminPositions(adminUserId, userId, positionSide);
    }

    @GetMapping("/api/v1/admin/accounts/transfers")
    public Object productTransfers(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestParam(value = "userId", required = false) Long userId,
            @RequestParam(value = "accountType", required = false) AccountType accountType,
            @RequestParam(value = "asset", required = false) String asset,
            @RequestParam(value = "limit", defaultValue = "100") int limit,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "sort", required = false) String sort) {
        GatewayUserContext.requireAdminUserId(adminUserId);
        return accountRequests.productTransfers(adminUserId, userId, accountType, asset, limit, cursor, sort);
    }

    @GetMapping("/api/v1/admin/accounts/balances")
    public Object adminBalances(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestParam("userId") long userId) {
        GatewayUserContext.requireAdminUserId(adminUserId);
        return accountRequests.adminBalances(adminUserId, userId);
    }

    @GetMapping("/api/v1/admin/accounts/position")
    public Object adminPosition(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestParam("userId") long userId,
            @RequestParam("instrumentId") String instrumentId,
            @RequestParam(value = "marginMode", required = false) String marginMode,
            @RequestParam(value = "positionSide", required = false) String positionSide) {
        GatewayUserContext.requireAdminUserId(adminUserId);
        return accountRequests.adminPosition(adminUserId, userId, instrumentId, marginMode, positionSide);
    }

    @GetMapping("/api/v1/accounts/product-ledger")
    public Object userProductLedger(
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestParam("accountType") AccountType accountType,
            @RequestParam(value = "asset", required = false) String asset,
            @RequestParam(value = "referenceType", required = false) String referenceType,
            @RequestParam(value = "limit", defaultValue = "50") int limit,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "sort", required = false) String sort) {
        long userId = GatewayUserContext.requireHeaderUserId(headerUserId);
        return accountRequests.userProductLedger(userId, accountType, asset, referenceType, limit, cursor, sort);
    }

    @GetMapping("/api/v1/accounts/position-mode")
    public Object positionMode(
            @RequestParam(value = "userId", required = false) Long queryUserId,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestHeader(value = "X-Product-Line", required = false) String headerProductLine,
            @RequestParam(value = "productLine", required = false) String queryProductLine) {
        long userId = GatewayUserContext.requireUserId(queryUserId, headerUserId, adminUserId);
        return accountRequests.positionMode(userId, headerProductLine, queryProductLine);
    }

    @PostMapping("/api/v1/accounts/position-mode")
    public Object updatePositionMode(
            @RequestBody PositionModeUpdateRequest request,
            @RequestHeader(value = "X-Product-Line", required = false) String headerProductLine,
            @RequestParam(value = "productLine", required = false) String queryProductLine) {
        return accountRequests.updatePositionMode(request, headerProductLine, queryProductLine);
    }

    @GetMapping("/api/v1/admin/accounts/ledger")
    public Object accountLedger(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestParam(value = "userId", required = false) Long userId,
            @RequestParam(value = "asset", required = false) String asset,
            @RequestParam(value = "referenceType", required = false) String referenceType,
            @RequestParam(value = "limit", defaultValue = "100") int limit,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "sort", required = false) String sort) {
        GatewayUserContext.requireAdminUserId(adminUserId);
        return accountRequests.accountLedger(adminUserId, userId, asset, referenceType, limit, cursor, sort);
    }

    @PostMapping("/api/v1/accounts/transfers")
    public Object transfer(@RequestBody ProductTransferRequest request) {
        return accountRequests.transfer(request);
    }

    @GetMapping("/api/v1/accounts/positions")
    public Object positions(
            @RequestParam(value = "userId", required = false) Long queryUserId,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestParam(value = "positionSide", required = false) String positionSide) {
        long userId = GatewayUserContext.requireUserId(queryUserId, headerUserId, adminUserId);
        return accountRequests.positions(userId, positionSide);
    }

    @GetMapping("/api/v1/accounts/transfers")
    public Object userProductTransfers(
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestParam("accountType") AccountType accountType,
            @RequestParam(value = "asset", required = false) String asset,
            @RequestParam(value = "limit", defaultValue = "50") int limit,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "sort", required = false) String sort) {
        long userId = GatewayUserContext.requireHeaderUserId(headerUserId);
        return accountRequests.userProductTransfers(userId, accountType, asset, limit, cursor, sort);
    }

    @GetMapping("/api/v1/accounts/balances")
    public Object balances(
            @RequestParam(value = "userId", required = false) Long queryUserId,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId) {
        long userId = GatewayUserContext.requireUserId(queryUserId, headerUserId, adminUserId);
        return accountRequests.balances(userId);
    }

    @GetMapping("/api/v1/accounts/position")
    public Object position(
            @RequestParam(value = "userId", required = false) Long queryUserId,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestParam("instrumentId") String instrumentId,
            @RequestParam(value = "marginMode", required = false) String marginMode,
            @RequestParam(value = "positionSide", required = false) String positionSide) {
        long userId = GatewayUserContext.requireUserId(queryUserId, headerUserId, adminUserId);
        return accountRequests.position(userId, instrumentId, marginMode, positionSide);
    }

    @GetMapping("/api/v1/accounts/balance")
    public Object balance(
            @RequestParam(value = "userId", required = false) Long queryUserId,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestParam("asset") String asset) {
        long userId = GatewayUserContext.requireUserId(queryUserId, headerUserId, adminUserId);
        return accountRequests.balance(userId, asset);
    }

    @GetMapping("/api/v1/accounts/ledger")
    public Object userAccountLedger(
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestParam(value = "asset", required = false) String asset,
            @RequestParam(value = "referenceType", required = false) String referenceType,
            @RequestParam(value = "limit", defaultValue = "10") int limit,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "sort", required = false) String sort) {
        long userId = GatewayUserContext.requireHeaderUserId(headerUserId);
        return accountRequests.userAccountLedger(userId, asset, referenceType, limit, cursor, sort);
    }
}
