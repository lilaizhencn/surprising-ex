package com.surprising.gateway.provider.local;

import com.surprising.account.api.model.AccountType;
import com.surprising.account.api.model.BalanceAdjustmentRequest;
import com.surprising.account.api.model.PositionMarginAdjustmentRequest;
import com.surprising.account.api.model.PositionModeUpdateRequest;
import com.surprising.account.api.model.ProductBalanceAdjustmentRequest;
import com.surprising.account.api.model.ProductTransferRequest;
import com.surprising.account.provider.controller.AccountRequestHandler;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/** 绑定已鉴权请求的路径、参数和DTO，再交给账户协议handler；不建立内部HTTP连接。 */
public final class AccountLocalRoutes {
    private static final PathPattern ACCOUNT_ADJUST_BALANCE = PathPatternParser.defaultInstance.parse("/api/v1/accounts/admin/balance-adjustments");
    private static final PathPattern ACCOUNT_ADMIN_ADJUST_BALANCE = PathPatternParser.defaultInstance.parse("/api/v1/admin/accounts/balance-adjustments");
    private static final PathPattern ACCOUNT_ADJUST_PRODUCT_BALANCE = PathPatternParser.defaultInstance.parse("/api/v1/accounts/admin/product-balance-adjustments");
    private static final PathPattern ACCOUNT_ADMIN_ADJUST_PRODUCT_BALANCE = PathPatternParser.defaultInstance.parse("/api/v1/admin/accounts/product-balance-adjustments");
    private static final PathPattern ACCOUNT_BALANCE = PathPatternParser.defaultInstance.parse("/api/v1/accounts/balance");
    private static final PathPattern ACCOUNT_BALANCES = PathPatternParser.defaultInstance.parse("/api/v1/accounts/balances");
    private static final PathPattern ACCOUNT_ADMIN_BALANCES = PathPatternParser.defaultInstance.parse("/api/v1/admin/accounts/balances");
    private static final PathPattern ACCOUNT_PRODUCT_BALANCE = PathPatternParser.defaultInstance.parse("/api/v1/accounts/product-balance");
    private static final PathPattern ACCOUNT_PRODUCT_BALANCES = PathPatternParser.defaultInstance.parse("/api/v1/accounts/product-balances");
    private static final PathPattern ACCOUNT_ADMIN_PRODUCT_BALANCES = PathPatternParser.defaultInstance.parse("/api/v1/admin/accounts/product-balances");
    private static final PathPattern ACCOUNT_TRANSFER = PathPatternParser.defaultInstance.parse("/api/v1/accounts/transfers");
    private static final PathPattern ACCOUNT_POSITION_MODE = PathPatternParser.defaultInstance.parse("/api/v1/accounts/position-mode");
    private static final PathPattern ACCOUNT_POSITION = PathPatternParser.defaultInstance.parse("/api/v1/accounts/position");
    private static final PathPattern ACCOUNT_ADMIN_POSITION = PathPatternParser.defaultInstance.parse("/api/v1/admin/accounts/position");
    private static final PathPattern ACCOUNT_POSITION_MARGIN = PathPatternParser.defaultInstance.parse("/api/v1/accounts/position-margin");
    private static final PathPattern ACCOUNT_ADJUST_POSITION_MARGIN = PathPatternParser.defaultInstance.parse("/api/v1/accounts/position-margin-adjustments");
    private static final PathPattern ACCOUNT_POSITIONS = PathPatternParser.defaultInstance.parse("/api/v1/accounts/positions");
    private static final PathPattern ACCOUNT_ADMIN_POSITIONS = PathPatternParser.defaultInstance.parse("/api/v1/admin/accounts/positions");
    private static final PathPattern ACCOUNT_ACCOUNT_LEDGER = PathPatternParser.defaultInstance.parse("/api/v1/admin/accounts/ledger");
    private static final PathPattern ACCOUNT_USER_ACCOUNT_LEDGER = PathPatternParser.defaultInstance.parse("/api/v1/accounts/ledger");
    private static final PathPattern ACCOUNT_PRODUCT_LEDGER = PathPatternParser.defaultInstance.parse("/api/v1/admin/accounts/product-ledger");
    private static final PathPattern ACCOUNT_USER_PRODUCT_LEDGER = PathPatternParser.defaultInstance.parse("/api/v1/accounts/product-ledger");
    private static final PathPattern ACCOUNT_PRODUCT_TRANSFERS = PathPatternParser.defaultInstance.parse("/api/v1/admin/accounts/transfers");
    private static final PathPattern ACCOUNT_ADMIN_BALANCE_ADJUSTMENTS = PathPatternParser.defaultInstance.parse("/api/v1/admin/accounts/adjustments");

    private final AccountRequestHandler accountRequests;

    public AccountLocalRoutes(AccountRequestHandler accountRequests) {
        this.accountRequests = accountRequests;
    }

    public Object invoke(LocalApiRequest r) {
        if (r.matches(HttpMethod.POST, ACCOUNT_ADJUST_PRODUCT_BALANCE)) {
            return accountRequests.adjustProductBalance(r.body(ProductBalanceAdjustmentRequest.class, true, true));
        }
        if (r.matches(HttpMethod.POST, ACCOUNT_ADMIN_ADJUST_PRODUCT_BALANCE)) {
            return accountRequests.adminAdjustProductBalance(r.header("X-Admin-User-Id", String.class, null, false),
                    r.header("X-Admin-Username", String.class, null, false),
                    r.body(ProductBalanceAdjustmentRequest.class, true, true));
        }
        if (r.matches(HttpMethod.POST, ACCOUNT_ADJUST_POSITION_MARGIN)) {
            return accountRequests.adjustPositionMargin(r.body(PositionMarginAdjustmentRequest.class, true, false));
        }
        if (r.matches(HttpMethod.POST, ACCOUNT_ADJUST_BALANCE)) {
            return accountRequests.adjustBalance(r.body(BalanceAdjustmentRequest.class, true, true));
        }
        if (r.matches(HttpMethod.POST, ACCOUNT_ADMIN_ADJUST_BALANCE)) {
            return accountRequests.adminAdjustBalance(r.header("X-Admin-User-Id", String.class, null, false),
                    r.header("X-Admin-Username", String.class, null, false),
                    r.body(BalanceAdjustmentRequest.class, true, false));
        }
        if (r.matches(HttpMethod.GET, ACCOUNT_ADMIN_PRODUCT_BALANCES)) {
            return accountRequests.adminProductBalances(r.header("X-Admin-User-Id", String.class, null, false),
                    r.query("userId", long.class, null, true),
                    r.query("accountType", AccountType.class, null, false));
        }
        if (r.matches(HttpMethod.GET, ACCOUNT_PRODUCT_LEDGER)) {
            return accountRequests.productLedger(r.header("X-Admin-User-Id", String.class, null, false),
                    r.query("userId", Long.class, null, false),
                    r.query("accountType", AccountType.class, null, false),
                    r.query("asset", String.class, null, false),
                    r.query("referenceType", String.class, null, false),
                    r.query("limit", int.class, "100", false),
                    r.query("cursor", String.class, null, false),
                    r.query("sort", String.class, null, false));
        }
        if (r.matches(HttpMethod.GET, ACCOUNT_ADMIN_BALANCE_ADJUSTMENTS)) {
            return accountRequests.adminBalanceAdjustments(r.header("X-Admin-User-Id", String.class, null, false),
                    r.query("adminUserId", Long.class, null, false),
                    r.query("userId", Long.class, null, false),
                    r.query("adjustmentKind", String.class, null, false),
                    r.query("accountType", AccountType.class, null, false),
                    r.query("asset", String.class, null, false),
                    r.query("referenceId", String.class, null, false),
                    r.query("limit", int.class, "100", false),
                    r.query("cursor", String.class, null, false),
                    r.query("sort", String.class, null, false));
        }
        if (r.matches(HttpMethod.GET, ACCOUNT_PRODUCT_BALANCES)) {
            return accountRequests.productBalances(r.query("userId", long.class, null, true),
                    r.query("accountType", AccountType.class, null, false));
        }
        if (r.matches(HttpMethod.GET, ACCOUNT_PRODUCT_BALANCE)) {
            return accountRequests.productBalance(r.query("userId", long.class, null, true),
                    r.query("accountType", AccountType.class, null, true),
                    r.query("asset", String.class, null, true));
        }
        if (r.matches(HttpMethod.GET, ACCOUNT_POSITION_MARGIN)) {
            return accountRequests.positionMargin(r.query("userId", long.class, null, true),
                    r.query("instrumentId", String.class, null, true),
                    r.query("marginMode", String.class, null, false));
        }
        if (r.matches(HttpMethod.GET, ACCOUNT_ADMIN_POSITIONS)) {
            return accountRequests.adminPositions(r.header("X-Admin-User-Id", String.class, null, false),
                    r.query("userId", long.class, null, true),
                    r.query("positionSide", String.class, null, false));
        }
        if (r.matches(HttpMethod.GET, ACCOUNT_PRODUCT_TRANSFERS)) {
            return accountRequests.productTransfers(r.header("X-Admin-User-Id", String.class, null, false),
                    r.query("userId", Long.class, null, false),
                    r.query("accountType", AccountType.class, null, false),
                    r.query("asset", String.class, null, false),
                    r.query("limit", int.class, "100", false),
                    r.query("cursor", String.class, null, false),
                    r.query("sort", String.class, null, false));
        }
        if (r.matches(HttpMethod.GET, ACCOUNT_ADMIN_BALANCES)) {
            return accountRequests.adminBalances(r.header("X-Admin-User-Id", String.class, null, false),
                    r.query("userId", long.class, null, true));
        }
        if (r.matches(HttpMethod.GET, ACCOUNT_ADMIN_POSITION)) {
            return accountRequests.adminPosition(r.header("X-Admin-User-Id", String.class, null, false),
                    r.query("userId", long.class, null, true),
                    r.query("instrumentId", String.class, null, true),
                    r.query("marginMode", String.class, null, false),
                    r.query("positionSide", String.class, null, false));
        }
        if (r.matches(HttpMethod.GET, ACCOUNT_USER_PRODUCT_LEDGER)) {
            return accountRequests.userProductLedger(r.header("X-User-Id", long.class, null, true),
                    r.query("accountType", AccountType.class, null, true),
                    r.query("asset", String.class, null, false),
                    r.query("referenceType", String.class, null, false),
                    r.query("limit", int.class, "50", false),
                    r.query("cursor", String.class, null, false),
                    r.query("sort", String.class, null, false));
        }
        if (r.matches(HttpMethod.GET, ACCOUNT_POSITION_MODE)) {
            return accountRequests.positionMode(r.query("userId", long.class, null, true),
                    r.header("X-Product-Line", String.class, null, false),
                    r.query("productLine", String.class, null, false));
        }
        if (r.matches(HttpMethod.POST, ACCOUNT_POSITION_MODE)) {
            return accountRequests.updatePositionMode(r.body(PositionModeUpdateRequest.class, true, false),
                    r.header("X-Product-Line", String.class, null, false),
                    r.query("productLine", String.class, null, false));
        }
        if (r.matches(HttpMethod.GET, ACCOUNT_ACCOUNT_LEDGER)) {
            return accountRequests.accountLedger(r.header("X-Admin-User-Id", String.class, null, false),
                    r.query("userId", Long.class, null, false),
                    r.query("asset", String.class, null, false),
                    r.query("referenceType", String.class, null, false),
                    r.query("limit", int.class, "100", false),
                    r.query("cursor", String.class, null, false),
                    r.query("sort", String.class, null, false));
        }
        if (r.matches(HttpMethod.POST, ACCOUNT_TRANSFER)) {
            return accountRequests.transfer(r.body(ProductTransferRequest.class, true, false));
        }
        if (r.matches(HttpMethod.GET, ACCOUNT_POSITIONS)) {
            return accountRequests.positions(r.query("userId", long.class, null, true),
                    r.query("positionSide", String.class, null, false));
        }
        if (r.matches(HttpMethod.GET, ACCOUNT_TRANSFER)) {
            return accountRequests.userProductTransfers(r.header("X-User-Id", long.class, null, true),
                    r.query("accountType", AccountType.class, null, true),
                    r.query("asset", String.class, null, false),
                    r.query("limit", int.class, "50", false),
                    r.query("cursor", String.class, null, false),
                    r.query("sort", String.class, null, false));
        }
        if (r.matches(HttpMethod.GET, ACCOUNT_BALANCES)) {
            return accountRequests.balances(r.query("userId", long.class, null, true));
        }
        if (r.matches(HttpMethod.GET, ACCOUNT_POSITION)) {
            return accountRequests.position(r.query("userId", long.class, null, true),
                    r.query("instrumentId", String.class, null, true),
                    r.query("marginMode", String.class, null, false),
                    r.query("positionSide", String.class, null, false));
        }
        if (r.matches(HttpMethod.GET, ACCOUNT_BALANCE)) {
            return accountRequests.balance(r.query("userId", long.class, null, true),
                    r.query("asset", String.class, null, true));
        }
        if (r.matches(HttpMethod.GET, ACCOUNT_USER_ACCOUNT_LEDGER)) {
            return accountRequests.userAccountLedger(r.header("X-User-Id", long.class, null, true),
                    r.query("asset", String.class, null, false),
                    r.query("referenceType", String.class, null, false),
                    r.query("limit", int.class, "10", false),
                    r.query("cursor", String.class, null, false),
                    r.query("sort", String.class, null, false));
        }
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown local account endpoint");
    }
}
