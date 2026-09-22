package com.surprising.account.provider.controller;

import com.surprising.account.api.AccountApiPaths;
import com.surprising.account.api.ProductTransferInternalAuth;
import com.surprising.account.api.model.PendingProductTransfersRequest;
import com.surprising.account.api.model.PendingProductTransfersResponse;
import com.surprising.account.api.model.ProductBalanceResponse;
import com.surprising.account.api.model.ProductTransferOperationRequest;
import com.surprising.account.provider.config.AccountProperties;
import com.surprising.account.provider.service.AccountCommandRejectedException;
import com.surprising.account.provider.service.AccountCommandGateway;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public final class ProductTransferInternalController {

    private static final long MAX_CLOCK_SKEW_SECONDS = 300L;
    private final AccountCommandGateway commands;
    private final AccountProperties properties;

    public ProductTransferInternalController(AccountCommandGateway commands, AccountProperties properties) {
        this.commands = commands;
        this.properties = properties;
    }

    @PostMapping(AccountApiPaths.TRANSFER_OUT_PATH)
    public ProductBalanceResponse transferOut(
            @RequestHeader("X-Internal-Service") String service,
            @RequestHeader("X-Internal-Timestamp") String timestamp,
            @RequestHeader("X-Internal-Signature") String signature,
            @RequestHeader("X-Internal-Audience") String audience,
            @Valid @RequestBody ProductTransferOperationRequest request) {
        authenticate(service, timestamp, signature, audience, AccountApiPaths.TRANSFER_OUT_PATH,
                ProductTransferInternalAuth.canonical(
                        AccountApiPaths.TRANSFER_OUT_PATH, parseTimestamp(timestamp), request));
        return commands.transferOut(request);
    }

    @PostMapping(AccountApiPaths.TRANSFER_IN_PATH)
    public ProductBalanceResponse transferIn(
            @RequestHeader("X-Internal-Service") String service,
            @RequestHeader("X-Internal-Timestamp") String timestamp,
            @RequestHeader("X-Internal-Signature") String signature,
            @RequestHeader("X-Internal-Audience") String audience,
            @Valid @RequestBody ProductTransferOperationRequest request) {
        authenticate(service, timestamp, signature, audience, AccountApiPaths.TRANSFER_IN_PATH,
                ProductTransferInternalAuth.canonical(
                        AccountApiPaths.TRANSFER_IN_PATH, parseTimestamp(timestamp), request));
        return commands.transferIn(request);
    }

    @PostMapping(AccountApiPaths.TRANSFER_COMPLETE_PATH)
    public void complete(
            @RequestHeader("X-Internal-Service") String service,
            @RequestHeader("X-Internal-Timestamp") String timestamp,
            @RequestHeader("X-Internal-Signature") String signature,
            @RequestHeader("X-Internal-Audience") String audience,
            @Valid @RequestBody ProductTransferOperationRequest request) {
        authenticate(service, timestamp, signature, audience, AccountApiPaths.TRANSFER_COMPLETE_PATH,
                ProductTransferInternalAuth.canonical(
                        AccountApiPaths.TRANSFER_COMPLETE_PATH, parseTimestamp(timestamp), request));
        commands.completeTransfer(request);
    }

    @PostMapping(AccountApiPaths.TRANSFER_PENDING_PATH)
    public PendingProductTransfersResponse pending(
            @RequestHeader("X-Internal-Service") String service,
            @RequestHeader("X-Internal-Timestamp") String timestamp,
            @RequestHeader("X-Internal-Signature") String signature,
            @RequestHeader("X-Internal-Audience") String audience,
            @Valid @RequestBody PendingProductTransfersRequest request) {
        authenticate(service, timestamp, signature, audience, AccountApiPaths.TRANSFER_PENDING_PATH,
                ProductTransferInternalAuth.canonical(
                        AccountApiPaths.TRANSFER_PENDING_PATH, parseTimestamp(timestamp), request));
        if (request.productLine() != properties.getKafka().getProductLine()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "pending transfer product line mismatch");
        }
        return new PendingProductTransfersResponse(commands.pendingTransfers(request.limit()));
    }

    @ExceptionHandler(AccountCommandRejectedException.class)
    public void rejected(AccountCommandRejectedException exception) {
        throw new ResponseStatusException(HttpStatus.CONFLICT, exception.errorCode(), exception);
    }

    private void authenticate(String service, String timestamp, String signature, String audience,
                              String expectedAudience, String canonical) {
        if (!ProductTransferInternalAuth.SERVICE.equals(service) || !expectedAudience.equals(audience)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "internal transfer identity is invalid");
        }
        long timestampSeconds = parseTimestamp(timestamp);
        if (Math.abs(Instant.now().getEpochSecond() - timestampSeconds) > MAX_CLOCK_SKEW_SECONDS) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "internal transfer timestamp is expired");
        }
        String secret = properties.getInternalServiceSecret();
        if (secret == null || secret.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "internal transfer authentication is not configured");
        }
        String expected = sign(secret, canonical);
        if (signature == null || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "internal transfer signature is invalid");
        }
    }

    private long parseTimestamp(String value) {
        try {
            return Long.parseLong(value);
        } catch (RuntimeException exception) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "internal transfer timestamp is invalid");
        }
    }

    private String sign(String secret, String canonical) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return "v1=" + Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("internal transfer signing failed", exception);
        }
    }
}
