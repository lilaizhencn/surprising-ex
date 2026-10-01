package com.surprising.gateway.provider.controller;

import com.surprising.account.api.model.ProductBalanceAdjustmentRequest;
import com.surprising.account.provider.service.AccountCommandGateway;
import com.surprising.gateway.provider.auth.AdminAuditRepository;
import com.surprising.gateway.provider.auth.AdminAuditRepository.AdminOperationRecord;
import com.surprising.gateway.provider.local.LocalBusinessApi;
import com.surprising.instrument.api.model.InstrumentUpsertRequest;
import com.surprising.instrument.provider.service.InstrumentService;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.LeverageSettingRequest;
import com.surprising.trading.order.service.LeverageService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/** Host-local authenticated operations using the same funds, leverage and instrument services as admin. */
@RestController
@RequestMapping("/internal/v1/operations/liquidity")
public class InternalLiquidityOperationsController {
    private final String token;
    private final LocalBusinessApi local;
    private final AccountCommandGateway accounts;
    private final LeverageService leverage;
    private final InstrumentService instruments;
    private final AdminAuditRepository audit;
    private final ObjectMapper mapper;

    public InternalLiquidityOperationsController(
            @Value("${surprising.gateway.operations.token:}") String token,
            LocalBusinessApi local, AccountCommandGateway accounts, LeverageService leverage,
            InstrumentService instruments, AdminAuditRepository audit, ObjectMapper mapper) {
        this.token = token;
        this.local = local;
        this.accounts = accounts;
        this.leverage = leverage;
        this.instruments = instruments;
        this.audit = audit;
        this.mapper = mapper;
    }

    @PostMapping("/balance-adjustments")
    public Object adjust(@Valid @RequestBody ProductBalanceAdjustmentRequest body, HttpServletRequest request) {
        authorize(request);
        if (!local.productLine().accountTypeCode().equals(body.accountType().name()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "product line mismatch");
        return execute(request, body, body.referenceId(), body.reason(),
                () -> accounts.adjustProductBalance(body, null, "SYSTEM:LIQUIDITY_OPERATIONS"));
    }

    @PostMapping("/leverage")
    public Object leverage(@Valid @RequestBody LeverageSettingRequest body, HttpServletRequest request) {
        authorize(request);
        requireProduct(body.productLine());
        return execute(request, body, request.getHeader("X-Operation-Id"), body.reason(), () -> leverage.set(body));
    }

    @GetMapping("/leverage")
    public Object leverage(@RequestParam long userId, @RequestParam String instrumentId,
            @RequestParam ProductLine productLine, @RequestParam com.surprising.trading.api.model.MarginMode marginMode,
            HttpServletRequest request) {
        authorize(request);
        requireProduct(productLine);
        return leverage.get(userId, instrumentId, marginMode, productLine);
    }

    @PostMapping("/instruments")
    public Object instrument(@Valid @RequestBody InstrumentUpsertRequest body,
                             @RequestParam String reason, HttpServletRequest request) {
        authorize(request);
        requireProduct(body.contractType().productLine());
        return execute(request, body, request.getHeader("X-Operation-Id"), reason,
                () -> instruments.upsert(body, "SYSTEM:LIQUIDITY_OPERATIONS", reason));
    }

    private void requireProduct(ProductLine product) {
        if (product == null || product != local.productLine())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "product line mismatch");
    }

    private void authorize(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        boolean loopback = "127.0.0.1".equals(remote) || "::1".equals(remote)
                || "0:0:0:0:0:0:0:1".equals(remote);
        String supplied = request.getHeader("X-Operations-Token");
        if (token.length() < 32 || supplied == null || !loopback
                || request.getHeader("Forwarded") != null || request.getHeader("X-Forwarded-For") != null
                || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8)))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "host-local operations authentication required");
    }

    private Object execute(HttpServletRequest request, Object body, String reference, String reason, Supplier<?> action) {
        if (reference == null || reference.isBlank() || reference.length() > 128
                || reason == null || reason.isBlank() || reason.length() > 128)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "operation reference and reason are required (max 128)");
        String hash;
        try {
            hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(body)));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
        // Durable intent comes first. Funds retries retain the business reference and Core command identity.
        audit.recordRequired(record(request, reference, reason, hash, 202, false, "INTENT"));
        try {
            Object result = action.get();
            audit.recordRequired(record(request, reference, reason, hash, 200, true, null));
            return result;
        } catch (RuntimeException ex) {
            audit.record(record(request, reference, reason, hash, 500, false, ex.getClass().getSimpleName()));
            throw ex;
        }
    }

    private AdminOperationRecord record(HttpServletRequest request, String reference, String reason,
                                        String hash, int status, boolean success, String error) {
        return new AdminOperationRecord(null, "SYSTEM:LIQUIDITY_OPERATIONS", List.of("OPERATIONS"),
                "liquidity-operations", "POST", request.getRequestURI(), reason, local.productLine().name(),
                hash, status, 0L, success, error, reference, "host-local-operations", request.getRemoteAddr(), Instant.now());
    }
}
