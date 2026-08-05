package com.surprising.gateway.provider.service;

import com.surprising.product.api.ProductLine;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class ProductTransferCoordinator {

    private final ProductTransferStore store;
    private final ProductAccountClient accountClient;

    public ProductTransferCoordinator(ProductTransferStore store, ProductAccountClient accountClient) {
        this.store = store;
        this.accountClient = accountClient;
    }

    @Autowired
    public ProductTransferCoordinator(ProductTransferRepository repository,
                                      HttpProductAccountClient accountClient) {
        this((ProductTransferStore) repository, (ProductAccountClient) accountClient);
    }

    @Transactional
    public ProductTransferResult transfer(ProductTransferCommand command) {
        validate(command);
        String source = normalizeAccountType(command.sourceAccountType());
        String target = normalizeAccountType(command.targetAccountType());
        if (providerAccountType(source).equals(providerAccountType(target))) {
            throw new IllegalArgumentException("source and target are the same account");
        }
        ProductTransferCreateRequest request = new ProductTransferCreateRequest(command.userId(),
                command.idempotencyKey().trim(), fingerprint(command, source, target), source, target,
                command.asset().trim().toUpperCase(Locale.ROOT), command.amountUnits(), command.referenceId().trim(),
                command.reason() == null ? "" : command.reason().trim());
        ProductTransferState created = store.createOrGet(request);
        if (!created.requestFingerprint().equals(request.requestFingerprint())) {
            throw new ProductTransferConflictException("idempotency key is already used by a different transfer");
        }
        ProductTransferState state = store.lock(created.transferId());
        if (state == null) {
            throw new IllegalStateException("product transfer state disappeared: " + created.transferId());
        }
        return ProductTransferResult.from(drive(state));
    }

    public ProductTransferResult transferJson(long userId,
                                              byte[] body,
                                              String suppliedIdempotencyKey,
                                              ObjectMapper objectMapper) {
        if (body == null || body.length == 0) {
            throw new IllegalArgumentException("transfer request body is required");
        }
        try {
            ProductTransferWireRequest request = objectMapper.readValue(body, ProductTransferWireRequest.class);
            String key = suppliedIdempotencyKey == null || suppliedIdempotencyKey.isBlank()
                    ? request.referenceId() : suppliedIdempotencyKey;
            return transfer(new ProductTransferCommand(userId, key, request.sourceAccountType(),
                    request.targetAccountType(), request.asset(), request.amountUnits(), request.referenceId(),
                    request.reason()));
        } catch (RuntimeException ex) {
            if (ex instanceof ProductTransferConflictException || ex instanceof IllegalArgumentException) {
                throw ex;
            }
            throw new IllegalArgumentException("transfer request body is invalid", ex);
        }
    }

    private ProductTransferState drive(ProductTransferState state) {
        if (state.status().terminal()) {
            return state;
        }
        return switch (state.status()) {
            case PENDING, SOURCE_DEBIT_UNKNOWN -> debitSource(state);
            case SOURCE_DEBITED, TARGET_CREDIT_UNKNOWN -> creditTarget(state);
            case COMPENSATION_REQUIRED -> compensateSource(state);
            case COMPLETED, FAILED -> state;
        };
    }

    private ProductTransferState debitSource(ProductTransferState state) {
        ProductAccountAdjustment adjustment = accountClient.adjust(providerAccountType(state.sourceAccountType()),
                Math.negateExact(state.amountUnits()), state.referenceId() + ":debit", state.reason(), state.userId(),
                state.asset());
        return switch (adjustment.status()) {
            case APPLIED -> drive(store.update(state.status(ProductTransferStatus.SOURCE_DEBITED, null, null)));
            case REJECTED -> store.update(state.status(ProductTransferStatus.FAILED, "SOURCE_DEBIT_REJECTED",
                    adjustment.errorMessage()));
            case UNKNOWN -> store.update(state.status(ProductTransferStatus.SOURCE_DEBIT_UNKNOWN,
                    "SOURCE_DEBIT_UNKNOWN", adjustment.errorMessage()));
        };
    }

    private ProductTransferState creditTarget(ProductTransferState state) {
        ProductAccountAdjustment adjustment = accountClient.adjust(providerAccountType(state.targetAccountType()),
                state.amountUnits(), state.referenceId() + ":credit", state.reason(), state.userId(), state.asset());
        return switch (adjustment.status()) {
            case APPLIED -> store.update(state.status(ProductTransferStatus.COMPLETED, null, null));
            case UNKNOWN -> store.update(state.status(ProductTransferStatus.TARGET_CREDIT_UNKNOWN,
                    "TARGET_CREDIT_UNKNOWN", adjustment.errorMessage()));
            case REJECTED -> compensateSource(store.update(state.status(ProductTransferStatus.COMPENSATION_REQUIRED,
                    "TARGET_CREDIT_REJECTED", adjustment.errorMessage())));
        };
    }

    private ProductTransferState compensateSource(ProductTransferState state) {
        ProductAccountAdjustment adjustment = accountClient.adjust(providerAccountType(state.sourceAccountType()),
                state.amountUnits(), state.referenceId() + ":compensate", state.reason(), state.userId(),
                state.asset());
        return switch (adjustment.status()) {
            case APPLIED -> store.update(state.status(ProductTransferStatus.FAILED, "TARGET_CREDIT_REJECTED",
                    state.errorMessage()));
            case REJECTED, UNKNOWN -> store.update(state.status(ProductTransferStatus.COMPENSATION_REQUIRED,
                    "COMPENSATION_REQUIRED", adjustment.errorMessage()));
        };
    }

    private String fingerprint(ProductTransferCommand command, String source, String target) {
        String canonical = command.userId() + "\n" + source + "\n" + target + "\n"
                + command.asset().trim().toUpperCase(Locale.ROOT) + "\n" + command.amountUnits() + "\n"
                + command.referenceId().trim() + "\n" + (command.reason() == null ? "" : command.reason().trim());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private String normalizeAccountType(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("account type is required");
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        providerAccountType(normalized);
        return normalized;
    }

    private String providerAccountType(String accountType) {
        return switch (accountType) {
            case "FUNDING", "SPOT" -> "SPOT";
            case "USDT_PERPETUAL", "COIN_PERPETUAL", "USDT_DELIVERY", "COIN_DELIVERY", "OPTION" -> accountType;
            default -> throw new IllegalArgumentException("unsupported account type: " + accountType);
        };
    }

    static ProductLine productLine(String accountType) {
        return switch (accountType) {
            case "FUNDING", "SPOT" -> ProductLine.SPOT;
            case "USDT_PERPETUAL" -> ProductLine.LINEAR_PERPETUAL;
            case "COIN_PERPETUAL" -> ProductLine.INVERSE_PERPETUAL;
            case "USDT_DELIVERY" -> ProductLine.LINEAR_DELIVERY;
            case "COIN_DELIVERY" -> ProductLine.INVERSE_DELIVERY;
            case "OPTION" -> ProductLine.OPTION;
            default -> throw new IllegalArgumentException("unsupported account type: " + accountType);
        };
    }

    private void validate(ProductTransferCommand command) {
        if (command == null || command.userId() <= 0L) {
            throw new IllegalArgumentException("userId is required");
        }
        if (command.idempotencyKey() == null || !command.idempotencyKey().trim().matches("[A-Za-z0-9._:-]{8,128}")) {
            throw new IllegalArgumentException("idempotency key must be 8-128 safe characters");
        }
        if (command.asset() == null || !command.asset().trim().matches("[A-Za-z0-9]{1,20}")) {
            throw new IllegalArgumentException("asset is invalid");
        }
        if (command.amountUnits() <= 0L || command.referenceId() == null
                || command.referenceId().isBlank() || command.referenceId().trim().length() > 128) {
            throw new IllegalArgumentException("amount and referenceId are invalid");
        }
    }
}
