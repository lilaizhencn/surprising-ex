package com.surprising.account.provider.controller;

import com.surprising.account.provider.service.AccountRequestService;
import com.surprising.account.api.AccountApiPaths;
import com.surprising.account.api.model.AccountLedgerQueryResponse;
import com.surprising.account.api.model.AccountType;
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
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 供独立 maker 和跨产品线账户操作使用的内部 HTTP 契约；内部调用无需凭证。公共用户入口由 GatewayProxyController 提供。
 */
@RestController
public class AccountInternalController {

    @PostMapping(AccountApiPaths.ACCOUNT_ADMIN_BASE_PATH + "/balance-adjustments")
    public BalanceResponse adjustBalance(@Valid @RequestBody BalanceAdjustmentRequest request) {
        return requests.adjustBalance(request);
    }

    @PostMapping(AccountApiPaths.ACCOUNT_ADMIN_BASE_PATH + "/product-balance-adjustments")
    public ProductBalanceResponse adjustProductBalance(@Valid @RequestBody ProductBalanceAdjustmentRequest request) {
        return requests.adjustProductBalance(request);
    }

    @GetMapping(AccountApiPaths.ACCOUNT_BASE_PATH + "/balance")
    public BalanceResponse balance(@RequestParam("userId") long userId, @RequestParam("asset") String asset) {
        return requests.balance(userId, asset);
    }

    @GetMapping(AccountApiPaths.ACCOUNT_BASE_PATH + "/balances")
    public BalanceQueryResponse balances(@RequestParam("userId") long userId) {
        return requests.balances(userId);
    }

    @GetMapping(AccountApiPaths.ACCOUNT_BASE_PATH + "/product-balance")
    public ProductBalanceResponse productBalance(@RequestParam("userId") long userId, @RequestParam("accountType") AccountType accountType, @RequestParam("asset") String asset) {
        return requests.productBalance(userId, accountType, asset);
    }

    @GetMapping(AccountApiPaths.ACCOUNT_BASE_PATH + "/product-balances")
    public ProductBalanceQueryResponse productBalances(@RequestParam("userId") long userId, @RequestParam(value = "accountType", required = false) AccountType accountType) {
        return requests.productBalances(userId, accountType);
    }

    @PostMapping(AccountApiPaths.ACCOUNT_BASE_PATH + "/transfers")
    public ProductTransferResponse transfer(@RequestBody ProductTransferRequest request) {
        return requests.transfer(request);
    }

    @GetMapping(AccountApiPaths.ACCOUNT_BASE_PATH + "/position-mode")
    public PositionModeResponse positionMode(@RequestParam("userId") long userId, @RequestHeader(value = "X-Product-Line", required = false) String productLineHeader, @RequestParam(value = "productLine", required = false) String productLineValue) {
        return requests.positionMode(userId, productLineHeader, productLineValue);
    }

    @PostMapping(AccountApiPaths.ACCOUNT_BASE_PATH + "/position-mode")
    public PositionModeResponse updatePositionMode(@RequestBody PositionModeUpdateRequest request, @RequestHeader(value = "X-Product-Line", required = false) String productLineHeader, @RequestParam(value = "productLine", required = false) String productLineValue) {
        return requests.updatePositionMode(request, productLineHeader, productLineValue);
    }

    @GetMapping(AccountApiPaths.ACCOUNT_BASE_PATH + "/position")
    public PositionResponse position(@RequestParam("userId") long userId, @RequestParam("symbol") String symbol, @RequestParam(value = "marginMode", required = false) String marginMode, @RequestParam(value = "positionSide", required = false) String positionSide) {
        return requests.position(userId, symbol, marginMode, positionSide);
    }

    @GetMapping(AccountApiPaths.ACCOUNT_BASE_PATH + "/position-margin")
    public PositionMarginResponse positionMargin(@RequestParam("userId") long userId, @RequestParam("symbol") String symbol, @RequestParam(value = "marginMode", required = false) String marginMode) {
        return requests.positionMargin(userId, symbol, marginMode);
    }

    @PostMapping(AccountApiPaths.ACCOUNT_BASE_PATH + "/position-margin-adjustments")
    public PositionMarginAdjustmentResponse adjustPositionMargin(@RequestBody PositionMarginAdjustmentRequest request) {
        return requests.adjustPositionMargin(request);
    }

    @GetMapping(AccountApiPaths.ACCOUNT_BASE_PATH + "/positions")
    public PositionQueryResponse positions(@RequestParam("userId") long userId, @RequestParam(value = "positionSide", required = false) String positionSide) {
        return requests.positions(userId, positionSide);
    }

    @GetMapping(AccountApiPaths.ACCOUNT_BASE_PATH + "/ledger")
    public AccountLedgerQueryResponse userAccountLedger(@RequestHeader("X-User-Id") long userId, @RequestParam(value = "asset", required = false) String asset, @RequestParam(value = "referenceType", required = false) String referenceType, @RequestParam(value = "limit", defaultValue = "10") int limit, @RequestParam(value = "cursor", required = false) String cursor, @RequestParam(value = "sort", required = false) String sort) {
        return requests.userAccountLedger(userId, asset, referenceType, limit, cursor, sort);
    }

    @GetMapping(AccountApiPaths.ACCOUNT_BASE_PATH + "/product-ledger")
    public ProductLedgerQueryResponse userProductLedger(@RequestHeader("X-User-Id") long userId, @RequestParam("accountType") AccountType accountType, @RequestParam(value = "asset", required = false) String asset, @RequestParam(value = "referenceType", required = false) String referenceType, @RequestParam(value = "limit", defaultValue = "50") int limit, @RequestParam(value = "cursor", required = false) String cursor, @RequestParam(value = "sort", required = false) String sort) {
        return requests.userProductLedger(userId, accountType, asset, referenceType, limit, cursor, sort);
    }

    @GetMapping(AccountApiPaths.ACCOUNT_BASE_PATH + "/transfers")
    public ProductTransferRecordQueryResponse userProductTransfers(@RequestHeader("X-User-Id") long userId, @RequestParam("accountType") AccountType accountType, @RequestParam(value = "asset", required = false) String asset, @RequestParam(value = "limit", defaultValue = "50") int limit, @RequestParam(value = "cursor", required = false) String cursor, @RequestParam(value = "sort", required = false) String sort) {
        return requests.userProductTransfers(userId, accountType, asset, limit, cursor, sort);
    }

    private final AccountRequestService requests;

    public AccountInternalController(AccountRequestService requests) {
        this.requests = requests;
    }
}
