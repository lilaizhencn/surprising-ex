package com.surprising.gateway.provider.service;

import com.surprising.gateway.provider.model.ProductTransferCommand;
import com.surprising.gateway.provider.model.ProductTransferResult;
import com.surprising.gateway.provider.model.ProductTransferStatus;
import com.surprising.gateway.provider.model.ProductTransferWireRequest;

import com.surprising.account.api.model.AccountType;
import com.surprising.account.api.model.ProductTransferOperationRequest;
import com.surprising.product.api.ProductLine;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public final class ProductTransferCoordinator {

    private static final Logger log = LoggerFactory.getLogger(ProductTransferCoordinator.class);
    // Only the scan order lives here; pending money remains owned by each source Core.
    private final AtomicInteger nextReconciliationLine = new AtomicInteger();

    private final ProductAccountClient accountClient;
    private final com.surprising.gateway.provider.auth.repository.GatewayUserRepository users;

    public ProductTransferCoordinator(ProductAccountClient accountClient) {
        this(accountClient, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ProductTransferCoordinator(ProductAccountClient accountClient,
            com.surprising.gateway.provider.auth.repository.GatewayUserRepository users) {
        this.accountClient = accountClient;
        this.users = users;
    }

    public ProductTransferResult transfer(ProductTransferCommand command) {
        validate(command);
        if (command.recipientUserId() != command.userId()) {
            if (users == null || users.find(command.userId())
                    .filter(user -> "NORMAL".equals(user.status()) || "TRADE_DISABLED".equals(user.status())).isEmpty()) {
                throw new IllegalArgumentException("sender is unavailable or transfers are disabled");
            }
            if (users == null || users.find(command.recipientUserId())
                    .filter(user -> "NORMAL".equals(user.status())).isEmpty()) {
                throw new IllegalArgumentException("recipient is unavailable");
            }
        }
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
        ProductLine[] lines = ProductLine.values();
        int start = nextReconciliationLine.getAndUpdate(value -> (value + 1) % lines.length);
        int processed = 0;
        for (int offset = 0; offset < lines.length && processed < limit; offset++) {
            ProductLine productLine = lines[(start + offset) % lines.length];
            // Reserve a share for later products even when this source has an unavailable target.
            int allowance = Math.min(256, Math.max(1, (limit - processed) / (lines.length - offset)));
            List<ProductTransferOperationRequest> pending;
            try {
                pending = accountClient.pendingTransfers(productLine, allowance);
            } catch (RuntimeException exception) {
                log.warn("Pending transfer scan failed productLine={}", productLine, exception);
                continue;
            }
            for (ProductTransferOperationRequest operation : pending) {
                if (processed == limit) break;
                processed++;
                try {
                    forward(operation, command(operation), Instant.now());
                } catch (RuntimeException exception) {
                    // Unknown outcomes stay pending in Core. Never refund or invent a new identity.
                    log.warn("Pending transfer retry failed productLine={} transferId={}",
                            productLine, operation.transferId(), exception);
                }
            }
        }
        return processed;
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
        AccountType source = command.sourceAccountType();
        AccountType target = command.targetAccountType();
        ProductLine sourceLine = productLine(source);
        ProductLine targetLine = productLine(target);
        if (sourceLine == targetLine && command.userId() == command.recipientUserId()) throw new IllegalArgumentException("source and target are the same account");
        return new ProductTransferOperationRequest(transferId(command.userId(), command.idempotencyKey()),
                command.userId(), sourceLine, targetLine, source, target,
                command.asset().trim().toUpperCase(Locale.ROOT), command.amountUnits(),
                command.referenceId().trim(), command.reason() == null ? "" : command.reason().trim(), command.recipientUserId());
    }

    private ProductTransferCommand command(ProductTransferOperationRequest operation) {
        return new ProductTransferCommand(operation.userId(), Long.toString(operation.transferId()),
                operation.sourceAccountType(), operation.targetAccountType(), operation.asset(),
                operation.amountUnits(), operation.referenceId(), operation.reason(), operation.recipientUserId());
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

    private String providerAccountType(AccountType accountType) {
        return accountType == AccountType.FUNDING ? AccountType.SPOT.name() : accountType.name();
    }

    static ProductLine productLine(AccountType accountType) {
        return accountType == AccountType.FUNDING ? ProductLine.SPOT : accountType.productLine()
                .orElseThrow(() -> new IllegalArgumentException("unsupported account type: " + accountType));
    }

    /** 原生资金命令仍必须满足身份和正金额不变量；HTTP表示格式由Controller负责。 */
    private void validate(ProductTransferCommand command) {
        if (command == null || command.userId() <= 0 || command.recipientUserId() <= 0)
            throw new IllegalArgumentException("userId is required");
        if (command.amountUnits() <= 0) throw new IllegalArgumentException("amount must be positive");
    }
}
