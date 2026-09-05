package com.surprising.gateway.provider.service;

import com.surprising.account.api.model.AccountType;
import com.surprising.account.api.model.ProductTransferOperationRequest;
import com.surprising.product.api.ProductLine;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Locale;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public final class ProductTransferCoordinator {

    private final ProductAccountClient accountClient;

    public ProductTransferCoordinator(ProductAccountClient accountClient) {
        this.accountClient = accountClient;
    }

    public ProductTransferResult transfer(ProductTransferCommand command) {
        validate(command);
        Instant startedAt = Instant.now();
        ProductTransferOperationRequest operation = operation(command);
        ProductAccountAdjustment debit = accountClient.transferOut(
                providerAccountType(operation.sourceAccountType()), operation);
        if (debit.status() == ProductAccountAdjustment.Status.REJECTED) {
            return result(operation.transferId(), command, ProductTransferStatus.FAILED,
                    "SOURCE_DEBIT_REJECTED", debit.errorMessage(), startedAt);
        }
        if (debit.status() == ProductAccountAdjustment.Status.UNKNOWN) {
            return result(operation.transferId(), command, ProductTransferStatus.PENDING,
                    "SOURCE_DEBIT_UNKNOWN", debit.errorMessage(), startedAt);
        }
        return forward(operation, command, startedAt);
    }

    public int reconcile(int limit) {
        if (limit <= 0) throw new IllegalArgumentException("reconciliation limit must be positive");
        int processed = 0;
        for (ProductLine productLine : ProductLine.values()) {
            if (processed == limit) break;
            for (ProductTransferOperationRequest operation
                    : accountClient.pendingTransfers(productLine, limit - processed)) {
                forward(operation, command(operation), Instant.now());
                processed++;
                if (processed == limit) break;
            }
        }
        return processed;
    }

    public ProductTransferResult transferJson(long userId, byte[] body, String suppliedIdempotencyKey,
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
        } catch (RuntimeException exception) {
            if (exception instanceof IllegalArgumentException) {
                throw exception;
            }
            throw new IllegalArgumentException("transfer request body is invalid", exception);
        }
    }

    private ProductTransferResult forward(ProductTransferOperationRequest operation,
                                          ProductTransferCommand command,
                                          Instant startedAt) {
        ProductAccountAdjustment credit = accountClient.transferIn(
                providerAccountType(operation.targetAccountType()), operation);
        if (credit.status() != ProductAccountAdjustment.Status.APPLIED) {
            return result(operation.transferId(), command, ProductTransferStatus.SOURCE_DEBITED,
                    "TARGET_CREDIT_PENDING", credit.errorMessage(), startedAt);
        }
        ProductAccountAdjustment completion = accountClient.completeTransfer(
                providerAccountType(operation.sourceAccountType()), operation);
        if (completion.status() != ProductAccountAdjustment.Status.APPLIED) {
            return result(operation.transferId(), command, ProductTransferStatus.SOURCE_DEBITED,
                    "SOURCE_COMPLETION_PENDING", completion.errorMessage(), startedAt);
        }
        return result(operation.transferId(), command, ProductTransferStatus.COMPLETED, null, null, startedAt);
    }

    private ProductTransferOperationRequest operation(ProductTransferCommand command) {
        AccountType source = accountType(command.sourceAccountType());
        AccountType target = accountType(command.targetAccountType());
        ProductLine sourceLine = productLine(source);
        ProductLine targetLine = productLine(target);
        if (sourceLine == targetLine) throw new IllegalArgumentException("source and target are the same account");
        return new ProductTransferOperationRequest(transferId(command.userId(), command.idempotencyKey()),
                command.userId(), sourceLine, targetLine, source, target,
                command.asset().trim().toUpperCase(Locale.ROOT), command.amountUnits(),
                command.referenceId().trim(), command.reason() == null ? "" : command.reason().trim());
    }

    private ProductTransferCommand command(ProductTransferOperationRequest operation) {
        return new ProductTransferCommand(operation.userId(), Long.toString(operation.transferId()),
                operation.sourceAccountType().name(), operation.targetAccountType().name(), operation.asset(),
                operation.amountUnits(), operation.referenceId(), operation.reason());
    }

    private ProductTransferResult result(long transferId, ProductTransferCommand command,
                                         ProductTransferStatus status, String errorCode, String errorMessage,
                                         Instant startedAt) {
        return ProductTransferResult.from(transferId, command, status, errorCode, errorMessage, startedAt);
    }

    private long transferId(long userId, String idempotencyKey) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((userId + ":" + idempotencyKey.trim()).getBytes(StandardCharsets.UTF_8));
            long value = ByteBuffer.wrap(digest).order(ByteOrder.BIG_ENDIAN).getLong() & Long.MAX_VALUE;
            return value == 0 ? 1 : value;
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private AccountType accountType(String value) {
        try {
            return AccountType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("unsupported account type: " + value, exception);
        }
    }

    private String providerAccountType(AccountType accountType) {
        return accountType == AccountType.FUNDING ? AccountType.SPOT.name() : accountType.name();
    }

    static ProductLine productLine(AccountType accountType) {
        return accountType == AccountType.FUNDING ? ProductLine.SPOT : accountType.productLine()
                .orElseThrow(() -> new IllegalArgumentException("unsupported account type: " + accountType));
    }

    private void validate(ProductTransferCommand command) {
        if (command == null || command.userId() <= 0) throw new IllegalArgumentException("userId is required");
        if (command.idempotencyKey() == null
                || !command.idempotencyKey().trim().matches("[A-Za-z0-9._:-]{1,128}")) {
            throw new IllegalArgumentException("idempotency key must be 1-128 safe characters");
        }
        if (command.asset() == null || !command.asset().trim().matches("[A-Za-z0-9]{1,20}")) {
            throw new IllegalArgumentException("asset is invalid");
        }
        if (command.amountUnits() <= 0 || command.referenceId() == null
                || command.referenceId().isBlank() || command.referenceId().trim().length() > 128) {
            throw new IllegalArgumentException("amount and referenceId are invalid");
        }
    }
}
