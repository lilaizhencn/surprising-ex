package com.surprising.account.provider.service;

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
import com.surprising.account.provider.service.AccountService;
import com.surprising.account.provider.service.AccountCommandGateway;
import com.surprising.account.provider.service.AccountCommandTimeoutException;
import com.surprising.account.provider.config.AccountProperties;
import com.surprising.product.api.ProductLine;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.stereotype.Service;

/**
 * 共享原始 HTTP 与网关入口的请求校验、业务编排和结果转换；不持有 HTTP 路由。
 */
@Service()
public class AccountRequestService {

    private final AccountService accountService;

    private final AccountCommandGateway commandGateway;

    private final AccountProperties properties;

    public AccountRequestService(AccountService accountService, AccountCommandGateway commandGateway, AccountProperties properties) {
        this.accountService = accountService;
        this.commandGateway = commandGateway;
        this.properties = properties;
    }

    public BalanceResponse adjustBalance(BalanceAdjustmentRequest request) {
        try {
            return commandGateway.adjustBalance(request, null, null);
        } catch (AccountCommandTimeoutException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage(), ex);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    public BalanceResponse adminAdjustBalance(String adminUserId, String adminUsername, BalanceAdjustmentRequest request) {
        requireAdmin(adminUserId);
        try {
            return commandGateway.adjustBalance(request, adminUserId, adminUsername);
        } catch (AccountCommandTimeoutException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage(), ex);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage(), ex);
        }
    }

    public ProductBalanceResponse adjustProductBalance(ProductBalanceAdjustmentRequest request) {
        try {
            return commandGateway.adjustProductBalance(request, null, null);
        } catch (AccountCommandTimeoutException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage(), ex);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage(), ex);
        }
    }

    public ProductBalanceResponse adminAdjustProductBalance(String adminUserId, String adminUsername, ProductBalanceAdjustmentRequest request) {
        requireAdmin(adminUserId);
        try {
            return commandGateway.adjustProductBalance(request, adminUserId, adminUsername);
        } catch (AccountCommandTimeoutException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage(), ex);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage(), ex);
        }
    }

    public BalanceResponse balance(long userId, String asset) {
        try {
            return accountService.balance(userId, asset);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    public BalanceQueryResponse balances(long userId) {
        return accountService.balances(userId);
    }

    public BalanceQueryResponse adminBalances(String adminUserId, long userId) {
        requireAdmin(adminUserId);
        return balances(userId);
    }

    public ProductBalanceResponse productBalance(long userId, AccountType accountType, String asset) {
        try {
            return accountService.productBalance(userId, accountType, asset);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    public ProductBalanceQueryResponse productBalances(long userId, AccountType accountType) {
        try {
            return accountService.productBalances(userId, accountType);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    public ProductBalanceQueryResponse adminProductBalances(String adminUserId, long userId, AccountType accountType) {
        requireAdmin(adminUserId);
        return productBalances(userId, accountType);
    }

    public ProductTransferResponse transfer(ProductTransferRequest request) {
        try {
            return commandGateway.transfer(request);
        } catch (AccountCommandTimeoutException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage(), ex);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage(), ex);
        }
    }

    public PositionModeResponse positionMode(long userId, String productLineHeader, String productLineValue) {
        try {
            return accountService.positionMode(productLine(productLineValue, productLineHeader), userId);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    public PositionModeResponse updatePositionMode(PositionModeUpdateRequest request, String productLineHeader, String productLineValue) {
        try {
            return commandGateway.updatePositionMode(withProductLine(request, productLine(productLineValue, productLineHeader)));
        } catch (AccountCommandTimeoutException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage(), ex);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage(), ex);
        }
    }

    private PositionModeUpdateRequest withProductLine(PositionModeUpdateRequest request, ProductLine productLine) {
        if (request == null || request.productLine() != null || productLine == null) {
            return request;
        }
        return new PositionModeUpdateRequest(request.userId(), productLine, request.positionMode(), request.referenceId());
    }

    private ProductLine productLine(String queryValue, String headerValue) {
        String value = queryValue == null || queryValue.isBlank() ? headerValue : queryValue;
        if (value == null || value.isBlank()) {
            return null;
        }
        return ProductLine.requireExternalCode(value);
    }

    public PositionResponse position(long userId, String symbol, String marginMode, String positionSide) {
        try {
            return accountService.position(userId, symbol, marginMode, positionSide);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    public PositionResponse adminPosition(String adminUserId, long userId, String symbol, String marginMode, String positionSide) {
        requireAdmin(adminUserId);
        return accountService.adminPosition(userId, symbol, marginMode, positionSide);
    }

    public PositionMarginResponse positionMargin(long userId, String symbol, String marginMode) {
        try {
            return accountService.positionMargin(userId, symbol, marginMode);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    public PositionMarginAdjustmentResponse adjustPositionMargin(PositionMarginAdjustmentRequest request) {
        try {
            return commandGateway.adjustPositionMargin(request);
        } catch (AccountCommandTimeoutException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage(), ex);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage(), ex);
        }
    }

    public PositionQueryResponse positions(long userId, String positionSide) {
        try {
            return accountService.positions(userId, positionSide);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    public PositionQueryResponse adminPositions(String adminUserId, long userId, String positionSide) {
        requireAdmin(adminUserId);
        return accountService.adminPositions(userId, positionSide);
    }

    public AccountLedgerQueryResponse accountLedger(String adminUserId, Long userId, String asset, String referenceType, int limit, String cursor, String sort) {
        requireAdmin(adminUserId);
        try {
            return accountService.accountLedger(userId, asset, referenceType, limit, cursor, sort);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    public AccountLedgerQueryResponse userAccountLedger(long userId, String asset, String referenceType, int limit, String cursor, String sort) {
        try {
            return accountService.accountLedger(userId, asset, referenceType, limit, cursor, sort);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    public ProductLedgerQueryResponse productLedger(String adminUserId, Long userId, AccountType accountType, String asset, String referenceType, int limit, String cursor, String sort) {
        requireAdmin(adminUserId);
        try {
            return accountService.productLedger(userId, accountType, asset, referenceType, limit, cursor, sort);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    public ProductLedgerQueryResponse userProductLedger(long userId, AccountType accountType, String asset, String referenceType, int limit, String cursor, String sort) {
        try {
            return accountService.productLedger(userId, accountType, asset, referenceType, limit, cursor, sort);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    public ProductTransferRecordQueryResponse productTransfers(String adminUserId, Long userId, AccountType accountType, String asset, int limit, String cursor, String sort) {
        requireAdmin(adminUserId);
        try {
            return accountService.productTransfers(userId, accountType, asset, limit, cursor, sort);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    public ProductTransferRecordQueryResponse userProductTransfers(long userId, AccountType accountType, String asset, int limit, String cursor, String sort) {
        try {
            return accountService.productTransfers(userId, accountType, asset, limit, cursor, sort);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    public AdminBalanceAdjustmentQueryResponse adminBalanceAdjustments(String headerAdminUserId, Long adminUserId, Long userId, String adjustmentKind, AccountType accountType, String asset, String referenceId, int limit, String cursor, String sort) {
        requireAdmin(headerAdminUserId);
        try {
            return accountService.adminBalanceAdjustments(adminUserId, userId, adjustmentKind, accountType, asset, referenceId, limit, cursor, sort);
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
