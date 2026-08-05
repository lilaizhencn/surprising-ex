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
import com.surprising.account.provider.service.PositionCacheUnavailableException;
import com.surprising.account.provider.config.AccountProperties;
import com.surprising.product.api.ProductLine;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import jakarta.validation.Valid;
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
    private static final String INTERNAL_SERVICE = "surprising-gateway";
    private static final String PRODUCT_BALANCE_AUDIENCE =
            AccountApiPaths.ACCOUNT_ADMIN_BASE_PATH + "/product-balance-adjustments";
    private static final long MAX_CLOCK_SKEW_SECONDS = 300L;

    private final AccountService accountService;
    private final AccountCommandGateway commandGateway;
    private final AccountProperties properties;

    public AccountController(AccountService accountService, AccountCommandGateway commandGateway,
                             AccountProperties properties) {
        this.accountService = accountService;
        this.commandGateway = commandGateway;
        this.properties = properties;
    }

    @PostMapping(AccountApiPaths.ACCOUNT_ADMIN_BASE_PATH + "/balance-adjustments")
    public BalanceResponse adjustBalance(
            @RequestHeader(value = "X-Internal-Service", required = false) String service,
            @RequestHeader(value = "X-Internal-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "X-Internal-Signature", required = false) String signature,
            @Valid @RequestBody BalanceAdjustmentRequest request) {
        requireInternalService(service, timestamp, signature, request);
        try {
            return commandGateway.adjustBalance(request, null, null);
        } catch (AccountCommandTimeoutException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage(), ex);
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
            return commandGateway.adjustBalance(request, adminUserId, adminUsername);
        } catch (AccountCommandTimeoutException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage(), ex);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage(), ex);
        }
    }

    @PostMapping(AccountApiPaths.ACCOUNT_ADMIN_BASE_PATH + "/product-balance-adjustments")
    public ProductBalanceResponse adjustProductBalance(
            @RequestHeader(value = "X-Internal-Service", required = false) String service,
            @RequestHeader(value = "X-Internal-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "X-Internal-Signature", required = false) String signature,
            @RequestHeader(value = "X-Internal-Audience", required = false) String audience,
            @Valid @RequestBody ProductBalanceAdjustmentRequest request) {
        requireInternalProductService(service, timestamp, signature, audience, request);
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

    @PostMapping(ADMIN_BASE_PATH + "/product-balance-adjustments")
    public ProductBalanceResponse adminAdjustProductBalance(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestHeader(value = "X-Admin-Username", required = false) String adminUsername,
            @RequestBody ProductBalanceAdjustmentRequest request) {
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
            return commandGateway.transfer(request);
        } catch (AccountCommandTimeoutException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage(), ex);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage(), ex);
        }
    }

    @GetMapping(AccountApiPaths.ACCOUNT_BASE_PATH + "/position-mode")
    public PositionModeResponse positionMode(@RequestParam("userId") long userId,
                                             @RequestHeader(value = "X-Product-Line", required = false)
                                             String productLineHeader,
                                             @RequestParam(value = "productLine", required = false)
                                             String productLineValue) {
        try {
            return accountService.positionMode(productLine(productLineValue, productLineHeader), userId);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @PostMapping(AccountApiPaths.ACCOUNT_BASE_PATH + "/position-mode")
    public PositionModeResponse updatePositionMode(@RequestBody PositionModeUpdateRequest request,
                                                   @RequestHeader(value = "X-Product-Line", required = false)
                                                   String productLineHeader,
                                                   @RequestParam(value = "productLine", required = false)
                                                   String productLineValue) {
        try {
            return commandGateway.updatePositionMode(
                    withProductLine(request, productLine(productLineValue, productLineHeader)));
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
        return new PositionModeUpdateRequest(
                request.userId(), productLine, request.positionMode(), request.referenceId());
    }

    private ProductLine productLine(String queryValue, String headerValue) {
        String value = queryValue == null || queryValue.isBlank() ? headerValue : queryValue;
        if (value == null || value.isBlank()) {
            return null;
        }
        return ProductLine.requireExternalCode(value);
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
        } catch (PositionCacheUnavailableException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage(), ex);
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
        return accountService.adminPosition(userId, symbol, marginMode, positionSide);
    }

    @GetMapping(AccountApiPaths.ACCOUNT_BASE_PATH + "/position-margin")
    public PositionMarginResponse positionMargin(@RequestParam("userId") long userId,
                                                 @RequestParam("symbol") String symbol,
                                                 @RequestParam(value = "marginMode", required = false) String marginMode) {
        try {
            return accountService.positionMargin(userId, symbol, marginMode);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (PositionCacheUnavailableException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage(), ex);
        }
    }

    @PostMapping(AccountApiPaths.ACCOUNT_BASE_PATH + "/position-margin-adjustments")
    public PositionMarginAdjustmentResponse adjustPositionMargin(
            @RequestBody PositionMarginAdjustmentRequest request) {
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

    @GetMapping(AccountApiPaths.ACCOUNT_BASE_PATH + "/positions")
    public PositionQueryResponse positions(@RequestParam("userId") long userId,
                                           @RequestParam(value = "positionSide", required = false) String positionSide) {
        try {
            return accountService.positions(userId, positionSide);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (PositionCacheUnavailableException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage(), ex);
        }
    }

    @GetMapping(ADMIN_BASE_PATH + "/positions")
    public PositionQueryResponse adminPositions(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestParam("userId") long userId,
            @RequestParam(value = "positionSide", required = false) String positionSide) {
        requireAdmin(adminUserId);
        return accountService.adminPositions(userId, positionSide);
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

    private void requireInternalService(String service, String timestamp, String signature,
                                         BalanceAdjustmentRequest request) {
        if (!INTERNAL_SERVICE.equals(service)
                || timestamp == null || timestamp.isBlank()
                || signature == null || signature.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "internal service authentication is required");
        }
        String secret = properties.getInternalServiceSecret();
        if (secret == null || secret.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "internal service authentication is not configured");
        }
        long timestampSeconds;
        try {
            timestampSeconds = Long.parseLong(timestamp);
        } catch (NumberFormatException ex) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "internal service timestamp is invalid", ex);
        }
        if (Math.abs(Instant.now().getEpochSecond() - timestampSeconds) > MAX_CLOCK_SKEW_SECONDS) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "internal service timestamp is expired");
        }
        String expected = sign(secret, timestampSeconds, request);
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "internal service signature is invalid");
        }
    }

    private String sign(String secret, long timestamp, BalanceAdjustmentRequest request) {
        String canonical = INTERNAL_SERVICE + "\n" + timestamp + "\n" + request.userId() + "\n"
                + request.asset().trim().toUpperCase(java.util.Locale.ROOT) + "\n"
                + request.amountUnits() + "\n" + request.referenceId() + "\n"
                + (request.reason() == null ? "" : request.reason());
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return "v1=" + Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "internal service signing is unavailable", ex);
        }
    }

    private void requireInternalProductService(String service, String timestamp, String signature,
                                                String audience,
                                                ProductBalanceAdjustmentRequest request) {
        requireInternalHeaders(service, timestamp, signature);
        if (!PRODUCT_BALANCE_AUDIENCE.equals(audience == null ? "" : audience.trim())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "internal service audience is invalid");
        }
        long timestampSeconds = parseInternalTimestamp(timestamp);
        if (Math.abs(Instant.now().getEpochSecond() - timestampSeconds) > MAX_CLOCK_SKEW_SECONDS) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "internal service timestamp is expired");
        }
        String secret = properties.getInternalServiceSecret();
        if (secret == null || secret.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "internal service authentication is not configured");
        }
        String canonical = field(INTERNAL_SERVICE) + field(PRODUCT_BALANCE_AUDIENCE)
                + field(Long.toString(timestampSeconds)) + field(Long.toString(request.userId()))
                + field(request.accountType().name())
                + field(request.asset().trim().toUpperCase(java.util.Locale.ROOT))
                + field(Long.toString(request.amountUnits())) + field(request.referenceId())
                + field(request.reason() == null ? "" : request.reason());
        if (!MessageDigest.isEqual(signCanonical(secret, canonical).getBytes(StandardCharsets.UTF_8),
                signature.trim().getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "internal service signature is invalid");
        }
    }

    private void requireInternalHeaders(String service, String timestamp, String signature) {
        if (!INTERNAL_SERVICE.equals(service == null ? "" : service.trim())
                || timestamp == null || timestamp.isBlank()
                || signature == null || signature.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "internal service authentication is required");
        }
    }

    private long parseInternalTimestamp(String timestamp) {
        try {
            return Long.parseLong(timestamp.trim());
        } catch (NumberFormatException ex) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "internal service timestamp is invalid", ex);
        }
    }

    private String signCanonical(String secret, String canonical) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return "v1=" + Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "internal service signing is unavailable", ex);
        }
    }

    private String field(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        return bytes.length + ":" + value;
    }
}
