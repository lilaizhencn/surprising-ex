package com.surprising.gateway.provider.local;

import com.surprising.account.api.model.AccountType;
import com.surprising.account.api.model.BalanceAdjustmentRequest;
import com.surprising.account.api.model.PositionMarginAdjustmentRequest;
import com.surprising.account.api.model.PositionModeUpdateRequest;
import com.surprising.account.api.model.ProductBalanceAdjustmentRequest;
import com.surprising.account.api.model.ProductTransferRequest;
import com.surprising.account.provider.service.AccountRequestService;
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

    private final AccountRequestService accountRequests;

    public AccountLocalRoutes(AccountRequestService accountRequests) {
        this.accountRequests = accountRequests;
    }

    public Object invoke(LocalApiRequest r) {
        if (r.matches(HttpMethod.POST, ACCOUNT_CONTROLLER_ADJUSTPRODUCTBALANCE)) {
            return accountRequests.adjustProductBalance(r.body(ProductBalanceAdjustmentRequest.class, true, true));
        }
        if (r.matches(HttpMethod.POST, ACCOUNT_CONTROLLER_ADMINADJUSTPRODUCTBALANCE)) {
            return accountRequests.adminAdjustProductBalance(r.header("X-Admin-User-Id", String.class, null, false),
                    r.header("X-Admin-Username", String.class, null, false),
                    r.body(ProductBalanceAdjustmentRequest.class, true, true));
        }
        if (r.matches(HttpMethod.POST, ACCOUNT_CONTROLLER_ADJUSTPOSITIONMARGIN)) {
            return accountRequests.adjustPositionMargin(r.body(PositionMarginAdjustmentRequest.class, true, false));
        }
        if (r.matches(HttpMethod.POST, ACCOUNT_CONTROLLER_ADJUSTBALANCE)) {
            return accountRequests.adjustBalance(r.body(BalanceAdjustmentRequest.class, true, true));
        }
        if (r.matches(HttpMethod.POST, ACCOUNT_CONTROLLER_ADMINADJUSTBALANCE)) {
            return accountRequests.adminAdjustBalance(r.header("X-Admin-User-Id", String.class, null, false),
                    r.header("X-Admin-Username", String.class, null, false),
                    r.body(BalanceAdjustmentRequest.class, true, false));
        }
        if (r.matches(HttpMethod.GET, ACCOUNT_CONTROLLER_ADMINPRODUCTBALANCES)) {
            return accountRequests.adminProductBalances(r.header("X-Admin-User-Id", String.class, null, false),
                    r.query("userId", long.class, null, true),
                    r.query("accountType", AccountType.class, null, false));
        }
        if (r.matches(HttpMethod.GET, ACCOUNT_CONTROLLER_PRODUCTLEDGER)) {
            return accountRequests.productLedger(r.header("X-Admin-User-Id", String.class, null, false),
                    r.query("userId", Long.class, null, false),
                    r.query("accountType", AccountType.class, null, false),
                    r.query("asset", String.class, null, false),
                    r.query("referenceType", String.class, null, false),
                    r.query("limit", int.class, "100", false),
                    r.query("cursor", String.class, null, false),
                    r.query("sort", String.class, null, false));
        }
        if (r.matches(HttpMethod.GET, ACCOUNT_CONTROLLER_ADMINBALANCEADJUSTMENTS)) {
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
        if (r.matches(HttpMethod.GET, ACCOUNT_CONTROLLER_PRODUCTBALANCES)) {
            return accountRequests.productBalances(r.query("userId", long.class, null, true),
                    r.query("accountType", AccountType.class, null, false));
        }
        if (r.matches(HttpMethod.GET, ACCOUNT_CONTROLLER_PRODUCTBALANCE)) {
            return accountRequests.productBalance(r.query("userId", long.class, null, true),
                    r.query("accountType", AccountType.class, null, true),
                    r.query("asset", String.class, null, true));
        }
        if (r.matches(HttpMethod.GET, ACCOUNT_CONTROLLER_POSITIONMARGIN)) {
            return accountRequests.positionMargin(r.query("userId", long.class, null, true),
                    r.query("symbol", String.class, null, true),
                    r.query("marginMode", String.class, null, false));
        }
        if (r.matches(HttpMethod.GET, ACCOUNT_CONTROLLER_ADMINPOSITIONS)) {
            return accountRequests.adminPositions(r.header("X-Admin-User-Id", String.class, null, false),
                    r.query("userId", long.class, null, true),
                    r.query("positionSide", String.class, null, false));
        }
        if (r.matches(HttpMethod.GET, ACCOUNT_CONTROLLER_PRODUCTTRANSFERS)) {
            return accountRequests.productTransfers(r.header("X-Admin-User-Id", String.class, null, false),
                    r.query("userId", Long.class, null, false),
                    r.query("accountType", AccountType.class, null, false),
                    r.query("asset", String.class, null, false),
                    r.query("limit", int.class, "100", false),
                    r.query("cursor", String.class, null, false),
                    r.query("sort", String.class, null, false));
        }
        if (r.matches(HttpMethod.GET, ACCOUNT_CONTROLLER_ADMINBALANCES)) {
            return accountRequests.adminBalances(r.header("X-Admin-User-Id", String.class, null, false),
                    r.query("userId", long.class, null, true));
        }
        if (r.matches(HttpMethod.GET, ACCOUNT_CONTROLLER_ADMINPOSITION)) {
            return accountRequests.adminPosition(r.header("X-Admin-User-Id", String.class, null, false),
                    r.query("userId", long.class, null, true),
                    r.query("symbol", String.class, null, true),
                    r.query("marginMode", String.class, null, false),
                    r.query("positionSide", String.class, null, false));
        }
        if (r.matches(HttpMethod.GET, ACCOUNT_CONTROLLER_USERPRODUCTLEDGER)) {
            return accountRequests.userProductLedger(r.header("X-User-Id", long.class, null, true),
                    r.query("accountType", AccountType.class, null, true),
                    r.query("asset", String.class, null, false),
                    r.query("referenceType", String.class, null, false),
                    r.query("limit", int.class, "50", false),
                    r.query("cursor", String.class, null, false),
                    r.query("sort", String.class, null, false));
        }
        if (r.matches(HttpMethod.GET, ACCOUNT_CONTROLLER_POSITIONMODE)) {
            return accountRequests.positionMode(r.query("userId", long.class, null, true),
                    r.header("X-Product-Line", String.class, null, false),
                    r.query("productLine", String.class, null, false));
        }
        if (r.matches(HttpMethod.POST, ACCOUNT_CONTROLLER_UPDATEPOSITIONMODE)) {
            return accountRequests.updatePositionMode(r.body(PositionModeUpdateRequest.class, true, false),
                    r.header("X-Product-Line", String.class, null, false),
                    r.query("productLine", String.class, null, false));
        }
        if (r.matches(HttpMethod.GET, ACCOUNT_CONTROLLER_ACCOUNTLEDGER)) {
            return accountRequests.accountLedger(r.header("X-Admin-User-Id", String.class, null, false),
                    r.query("userId", Long.class, null, false),
                    r.query("asset", String.class, null, false),
                    r.query("referenceType", String.class, null, false),
                    r.query("limit", int.class, "100", false),
                    r.query("cursor", String.class, null, false),
                    r.query("sort", String.class, null, false));
        }
        if (r.matches(HttpMethod.POST, ACCOUNT_CONTROLLER_TRANSFER)) {
            return accountRequests.transfer(r.body(ProductTransferRequest.class, true, false));
        }
        if (r.matches(HttpMethod.GET, ACCOUNT_CONTROLLER_POSITIONS)) {
            return accountRequests.positions(r.query("userId", long.class, null, true),
                    r.query("positionSide", String.class, null, false));
        }
        if (r.matches(HttpMethod.GET, ACCOUNT_CONTROLLER_USERPRODUCTTRANSFERS)) {
            return accountRequests.userProductTransfers(r.header("X-User-Id", long.class, null, true),
                    r.query("accountType", AccountType.class, null, true),
                    r.query("asset", String.class, null, false),
                    r.query("limit", int.class, "50", false),
                    r.query("cursor", String.class, null, false),
                    r.query("sort", String.class, null, false));
        }
        if (r.matches(HttpMethod.GET, ACCOUNT_CONTROLLER_BALANCES)) {
            return accountRequests.balances(r.query("userId", long.class, null, true));
        }
        if (r.matches(HttpMethod.GET, ACCOUNT_CONTROLLER_POSITION)) {
            return accountRequests.position(r.query("userId", long.class, null, true),
                    r.query("symbol", String.class, null, true),
                    r.query("marginMode", String.class, null, false),
                    r.query("positionSide", String.class, null, false));
        }
        if (r.matches(HttpMethod.GET, ACCOUNT_CONTROLLER_BALANCE)) {
            return accountRequests.balance(r.query("userId", long.class, null, true),
                    r.query("asset", String.class, null, true));
        }
        if (r.matches(HttpMethod.GET, ACCOUNT_CONTROLLER_USERACCOUNTLEDGER)) {
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
