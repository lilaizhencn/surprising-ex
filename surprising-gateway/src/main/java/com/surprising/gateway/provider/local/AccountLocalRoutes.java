package com.surprising.gateway.provider.local;

import com.surprising.account.api.model.AccountType;
import com.surprising.account.api.model.BalanceAdjustmentRequest;
import com.surprising.account.api.model.PositionMarginAdjustmentRequest;
import com.surprising.account.api.model.PositionModeUpdateRequest;
import com.surprising.account.api.model.ProductBalanceAdjustmentRequest;
import com.surprising.account.api.model.ProductTransferRequest;
import com.surprising.account.provider.controller.AccountController;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/** 网关协议到账户入口的本地调用；不建立内部 HTTP 连接。 */
@Component
public final class AccountLocalRoutes {
    private static final PathPattern ACCOUNT_CONTROLLER_ADJUSTBALANCE = PathPatternParser.defaultInstance.parse("/api/v1/accounts/admin/balance-adjustments");
    private static final PathPattern ACCOUNT_CONTROLLER_ADMINADJUSTBALANCE = PathPatternParser.defaultInstance.parse("/api/v1/admin/accounts/balance-adjustments");
    private static final PathPattern ACCOUNT_CONTROLLER_ADJUSTPRODUCTBALANCE = PathPatternParser.defaultInstance.parse("/api/v1/accounts/admin/product-balance-adjustments");
    private static final PathPattern ACCOUNT_CONTROLLER_ADMINADJUSTPRODUCTBALANCE = PathPatternParser.defaultInstance.parse("/api/v1/admin/accounts/product-balance-adjustments");
    private static final PathPattern ACCOUNT_CONTROLLER_BALANCE = PathPatternParser.defaultInstance.parse("/api/v1/accounts/balance");
    private static final PathPattern ACCOUNT_CONTROLLER_BALANCES = PathPatternParser.defaultInstance.parse("/api/v1/accounts/balances");
    private static final PathPattern ACCOUNT_CONTROLLER_ADMINBALANCES = PathPatternParser.defaultInstance.parse("/api/v1/admin/accounts/balances");
    private static final PathPattern ACCOUNT_CONTROLLER_PRODUCTBALANCE = PathPatternParser.defaultInstance.parse("/api/v1/accounts/product-balance");
    private static final PathPattern ACCOUNT_CONTROLLER_PRODUCTBALANCES = PathPatternParser.defaultInstance.parse("/api/v1/accounts/product-balances");
    private static final PathPattern ACCOUNT_CONTROLLER_ADMINPRODUCTBALANCES = PathPatternParser.defaultInstance.parse("/api/v1/admin/accounts/product-balances");
    private static final PathPattern ACCOUNT_CONTROLLER_TRANSFER = PathPatternParser.defaultInstance.parse("/api/v1/accounts/transfers");
    private static final PathPattern ACCOUNT_CONTROLLER_POSITIONMODE = PathPatternParser.defaultInstance.parse("/api/v1/accounts/position-mode");
    private static final PathPattern ACCOUNT_CONTROLLER_UPDATEPOSITIONMODE = PathPatternParser.defaultInstance.parse("/api/v1/accounts/position-mode");
    private static final PathPattern ACCOUNT_CONTROLLER_POSITION = PathPatternParser.defaultInstance.parse("/api/v1/accounts/position");
    private static final PathPattern ACCOUNT_CONTROLLER_ADMINPOSITION = PathPatternParser.defaultInstance.parse("/api/v1/admin/accounts/position");
    private static final PathPattern ACCOUNT_CONTROLLER_POSITIONMARGIN = PathPatternParser.defaultInstance.parse("/api/v1/accounts/position-margin");
    private static final PathPattern ACCOUNT_CONTROLLER_ADJUSTPOSITIONMARGIN = PathPatternParser.defaultInstance.parse("/api/v1/accounts/position-margin-adjustments");
    private static final PathPattern ACCOUNT_CONTROLLER_POSITIONS = PathPatternParser.defaultInstance.parse("/api/v1/accounts/positions");
    private static final PathPattern ACCOUNT_CONTROLLER_ADMINPOSITIONS = PathPatternParser.defaultInstance.parse("/api/v1/admin/accounts/positions");
    private static final PathPattern ACCOUNT_CONTROLLER_ACCOUNTLEDGER = PathPatternParser.defaultInstance.parse("/api/v1/admin/accounts/ledger");
    private static final PathPattern ACCOUNT_CONTROLLER_USERACCOUNTLEDGER = PathPatternParser.defaultInstance.parse("/api/v1/accounts/ledger");
    private static final PathPattern ACCOUNT_CONTROLLER_PRODUCTLEDGER = PathPatternParser.defaultInstance.parse("/api/v1/admin/accounts/product-ledger");
    private static final PathPattern ACCOUNT_CONTROLLER_USERPRODUCTLEDGER = PathPatternParser.defaultInstance.parse("/api/v1/accounts/product-ledger");
    private static final PathPattern ACCOUNT_CONTROLLER_PRODUCTTRANSFERS = PathPatternParser.defaultInstance.parse("/api/v1/admin/accounts/transfers");
    private static final PathPattern ACCOUNT_CONTROLLER_USERPRODUCTTRANSFERS = PathPatternParser.defaultInstance.parse("/api/v1/accounts/transfers");
    private static final PathPattern ACCOUNT_CONTROLLER_ADMINBALANCEADJUSTMENTS = PathPatternParser.defaultInstance.parse("/api/v1/admin/accounts/adjustments");

    private final AccountController accountController;

    public AccountLocalRoutes(AccountController accountController) {
        this.accountController = accountController;
    }

    public Object invoke(LocalApiRequest r) {
        if (r.matches(HttpMethod.POST, ACCOUNT_CONTROLLER_ADJUSTPRODUCTBALANCE)) {
            return accountController.adjustProductBalance(r.header("X-Internal-Service", String.class, null, false),
                    r.header("X-Internal-Timestamp", String.class, null, false),
                    r.header("X-Internal-Signature", String.class, null, false),
                    r.header("X-Internal-Audience", String.class, null, false),
                    r.body(ProductBalanceAdjustmentRequest.class, true, true));
        }
        if (r.matches(HttpMethod.POST, ACCOUNT_CONTROLLER_ADMINADJUSTPRODUCTBALANCE)) {
            return accountController.adminAdjustProductBalance(r.header("X-Admin-User-Id", String.class, null, false),
                    r.header("X-Admin-Username", String.class, null, false),
                    r.body(ProductBalanceAdjustmentRequest.class, true, true));
        }
        if (r.matches(HttpMethod.POST, ACCOUNT_CONTROLLER_ADJUSTPOSITIONMARGIN)) {
            return accountController.adjustPositionMargin(r.body(PositionMarginAdjustmentRequest.class, true, false));
        }
        if (r.matches(HttpMethod.POST, ACCOUNT_CONTROLLER_ADJUSTBALANCE)) {
            return accountController.adjustBalance(r.header("X-Internal-Service", String.class, null, false),
                    r.header("X-Internal-Timestamp", String.class, null, false),
                    r.header("X-Internal-Signature", String.class, null, false),
                    r.body(BalanceAdjustmentRequest.class, true, true));
        }
        if (r.matches(HttpMethod.POST, ACCOUNT_CONTROLLER_ADMINADJUSTBALANCE)) {
            return accountController.adminAdjustBalance(r.header("X-Admin-User-Id", String.class, null, false),
                    r.header("X-Admin-Username", String.class, null, false),
                    r.body(BalanceAdjustmentRequest.class, true, false));
        }
        if (r.matches(HttpMethod.GET, ACCOUNT_CONTROLLER_ADMINPRODUCTBALANCES)) {
            return accountController.adminProductBalances(r.header("X-Admin-User-Id", String.class, null, false),
                    r.query("userId", long.class, null, true),
                    r.query("accountType", AccountType.class, null, false));
        }
        if (r.matches(HttpMethod.GET, ACCOUNT_CONTROLLER_PRODUCTLEDGER)) {
            return accountController.productLedger(r.header("X-Admin-User-Id", String.class, null, false),
                    r.query("userId", Long.class, null, false),
                    r.query("accountType", AccountType.class, null, false),
                    r.query("asset", String.class, null, false),
                    r.query("referenceType", String.class, null, false),
                    r.query("limit", int.class, "100", false),
                    r.query("cursor", String.class, null, false),
                    r.query("sort", String.class, null, false));
        }
        if (r.matches(HttpMethod.GET, ACCOUNT_CONTROLLER_ADMINBALANCEADJUSTMENTS)) {
            return accountController.adminBalanceAdjustments(r.header("X-Admin-User-Id", String.class, null, false),
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
        if (r.matches(HttpMethod.GET, ACCOUNT_CONTROLLER_PRODUCTBALANCES)) {
            return accountController.productBalances(r.query("userId", long.class, null, true),
                    r.query("accountType", AccountType.class, null, false));
        }
        if (r.matches(HttpMethod.GET, ACCOUNT_CONTROLLER_PRODUCTBALANCE)) {
            return accountController.productBalance(r.query("userId", long.class, null, true),
                    r.query("accountType", AccountType.class, null, true),
                    r.query("asset", String.class, null, true));
        }
        if (r.matches(HttpMethod.GET, ACCOUNT_CONTROLLER_POSITIONMARGIN)) {
            return accountController.positionMargin(r.query("userId", long.class, null, true),
                    r.query("symbol", String.class, null, true),
                    r.query("marginMode", String.class, null, false));
        }
        if (r.matches(HttpMethod.GET, ACCOUNT_CONTROLLER_ADMINPOSITIONS)) {
            return accountController.adminPositions(r.header("X-Admin-User-Id", String.class, null, false),
                    r.query("userId", long.class, null, true),
                    r.query("positionSide", String.class, null, false));
        }
        if (r.matches(HttpMethod.GET, ACCOUNT_CONTROLLER_PRODUCTTRANSFERS)) {
            return accountController.productTransfers(r.header("X-Admin-User-Id", String.class, null, false),
                    r.query("userId", Long.class, null, false),
                    r.query("accountType", AccountType.class, null, false),
                    r.query("asset", String.class, null, false),
                    r.query("limit", int.class, "100", false),
                    r.query("cursor", String.class, null, false),
                    r.query("sort", String.class, null, false));
        }
        if (r.matches(HttpMethod.GET, ACCOUNT_CONTROLLER_ADMINBALANCES)) {
            return accountController.adminBalances(r.header("X-Admin-User-Id", String.class, null, false),
                    r.query("userId", long.class, null, true));
        }
        if (r.matches(HttpMethod.GET, ACCOUNT_CONTROLLER_ADMINPOSITION)) {
            return accountController.adminPosition(r.header("X-Admin-User-Id", String.class, null, false),
                    r.query("userId", long.class, null, true),
                    r.query("symbol", String.class, null, true),
                    r.query("marginMode", String.class, null, false),
                    r.query("positionSide", String.class, null, false));
        }
        if (r.matches(HttpMethod.GET, ACCOUNT_CONTROLLER_USERPRODUCTLEDGER)) {
            return accountController.userProductLedger(r.header("X-User-Id", long.class, null, true),
                    r.query("accountType", AccountType.class, null, true),
                    r.query("asset", String.class, null, false),
                    r.query("referenceType", String.class, null, false),
                    r.query("limit", int.class, "50", false),
                    r.query("cursor", String.class, null, false),
                    r.query("sort", String.class, null, false));
        }
        if (r.matches(HttpMethod.GET, ACCOUNT_CONTROLLER_POSITIONMODE)) {
            return accountController.positionMode(r.query("userId", long.class, null, true),
                    r.header("X-Product-Line", String.class, null, false),
                    r.query("productLine", String.class, null, false));
        }
        if (r.matches(HttpMethod.POST, ACCOUNT_CONTROLLER_UPDATEPOSITIONMODE)) {
            return accountController.updatePositionMode(r.body(PositionModeUpdateRequest.class, true, false),
                    r.header("X-Product-Line", String.class, null, false),
                    r.query("productLine", String.class, null, false));
        }
        if (r.matches(HttpMethod.GET, ACCOUNT_CONTROLLER_ACCOUNTLEDGER)) {
            return accountController.accountLedger(r.header("X-Admin-User-Id", String.class, null, false),
                    r.query("userId", Long.class, null, false),
                    r.query("asset", String.class, null, false),
                    r.query("referenceType", String.class, null, false),
                    r.query("limit", int.class, "100", false),
                    r.query("cursor", String.class, null, false),
                    r.query("sort", String.class, null, false));
        }
        if (r.matches(HttpMethod.POST, ACCOUNT_CONTROLLER_TRANSFER)) {
            return accountController.transfer(r.body(ProductTransferRequest.class, true, false));
        }
        if (r.matches(HttpMethod.GET, ACCOUNT_CONTROLLER_POSITIONS)) {
            return accountController.positions(r.query("userId", long.class, null, true),
                    r.query("positionSide", String.class, null, false));
        }
        if (r.matches(HttpMethod.GET, ACCOUNT_CONTROLLER_USERPRODUCTTRANSFERS)) {
            return accountController.userProductTransfers(r.header("X-User-Id", long.class, null, true),
                    r.query("accountType", AccountType.class, null, true),
                    r.query("asset", String.class, null, false),
                    r.query("limit", int.class, "50", false),
                    r.query("cursor", String.class, null, false),
                    r.query("sort", String.class, null, false));
        }
        if (r.matches(HttpMethod.GET, ACCOUNT_CONTROLLER_BALANCES)) {
            return accountController.balances(r.query("userId", long.class, null, true));
        }
        if (r.matches(HttpMethod.GET, ACCOUNT_CONTROLLER_POSITION)) {
            return accountController.position(r.query("userId", long.class, null, true),
                    r.query("symbol", String.class, null, true),
                    r.query("marginMode", String.class, null, false),
                    r.query("positionSide", String.class, null, false));
        }
        if (r.matches(HttpMethod.GET, ACCOUNT_CONTROLLER_BALANCE)) {
            return accountController.balance(r.query("userId", long.class, null, true),
                    r.query("asset", String.class, null, true));
        }
        if (r.matches(HttpMethod.GET, ACCOUNT_CONTROLLER_USERACCOUNTLEDGER)) {
            return accountController.userAccountLedger(r.header("X-User-Id", long.class, null, true),
                    r.query("asset", String.class, null, false),
                    r.query("referenceType", String.class, null, false),
                    r.query("limit", int.class, "10", false),
                    r.query("cursor", String.class, null, false),
                    r.query("sort", String.class, null, false));
        }
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown local account endpoint");
    }
}
