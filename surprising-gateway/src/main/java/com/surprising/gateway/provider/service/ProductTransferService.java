package com.surprising.gateway.provider.service;

import com.surprising.account.api.model.AccountType;
import com.surprising.account.api.model.ProductTransferOperationRequest;
import com.surprising.account.provider.exception.AccountCommandRejectedException;
import com.surprising.account.provider.service.AccountCommandGateway;
import com.surprising.gateway.provider.model.ProductTransferCommand;
import com.surprising.gateway.provider.model.ProductTransferResult;
import com.surprising.gateway.provider.model.ProductTransferStatus;
import com.surprising.gateway.provider.model.ProductTransferWireRequest;
import com.surprising.gateway.provider.product.GatewayProductServices;
import com.surprising.product.api.ProductLine;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public final class ProductTransferService {

    private static final Logger log = LoggerFactory.getLogger(ProductTransferService.class);
    // Only the scan order lives here; pending money remains owned by each source Core.
    private final AtomicInteger nextReconciliationLine = new AtomicInteger();

    private final GatewayProductServices products;
    private final com.surprising.gateway.provider.auth.repository.GatewayUserRepository users;

    public ProductTransferService(GatewayProductServices products) {
        this(products, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ProductTransferService(GatewayProductServices products,
            com.surprising.gateway.provider.auth.repository.GatewayUserRepository users) {
        this.products = products;
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
        return executeCommittedTransfer(command);
    }

    /**
     * Resume a durably accepted business transfer. The caller must have recorded and authorized
     * the immutable intent before calling; HTTP requests continue through transfer(). Subsequent
     * user-status changes must not strand a debit whose result was previously unknown.
     */
    public ProductTransferResult executeCommittedTransfer(ProductTransferCommand command) {
        validate(command);
        Instant startedAt = Instant.now();
        ProductTransferOperationRequest operation = operation(command);
        try {
            products.requireEnabled(operation.sourceProductLine());
            products.requireEnabled(operation.targetProductLine());
            products.service(operation.sourceProductLine(), AccountCommandGateway.class).transferOut(operation);
        } catch (AccountCommandRejectedException ex) {
            return result(operation.transferId(), command, ProductTransferStatus.FAILED,
                    "SOURCE_DEBIT_REJECTED", ex.errorCode(), startedAt);
        } catch (IllegalArgumentException ex) {
            return result(operation.transferId(), command, ProductTransferStatus.FAILED,
                    "SOURCE_DEBIT_REJECTED", ex.getMessage(), startedAt);
        } catch (RuntimeException ex) {
            return result(operation.transferId(), command, ProductTransferStatus.PENDING,
                    "SOURCE_DEBIT_UNKNOWN", "account transfer outcome is unknown", startedAt);
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
                pending = products.enabled().contains(productLine)
                        ? products.service(productLine, AccountCommandGateway.class).pendingTransfers(allowance)
                        : List.of();
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
        try {
            products.requireEnabled(operation.sourceProductLine());
            products.requireEnabled(operation.targetProductLine());
            products.service(operation.targetProductLine(), AccountCommandGateway.class).transferIn(operation);
        } catch (AccountCommandRejectedException ex) {
            return result(operation.transferId(), command, ProductTransferStatus.SOURCE_DEBITED,
                    "TARGET_CREDIT_PENDING", ex.errorCode(), startedAt);
        } catch (IllegalArgumentException ex) {
            return result(operation.transferId(), command, ProductTransferStatus.SOURCE_DEBITED,
                    "TARGET_CREDIT_PENDING", ex.getMessage(), startedAt);
        } catch (RuntimeException ex) {
            return result(operation.transferId(), command, ProductTransferStatus.SOURCE_DEBITED,
                    "TARGET_CREDIT_PENDING", "account transfer outcome is unknown", startedAt);
        }
        try {
            products.service(operation.sourceProductLine(), AccountCommandGateway.class).completeTransfer(operation);
        } catch (AccountCommandRejectedException ex) {
            return result(operation.transferId(), command, ProductTransferStatus.SOURCE_DEBITED,
                    "SOURCE_COMPLETION_PENDING", ex.errorCode(), startedAt);
        } catch (IllegalArgumentException ex) {
            return result(operation.transferId(), command, ProductTransferStatus.SOURCE_DEBITED,
                    "SOURCE_COMPLETION_PENDING", ex.getMessage(), startedAt);
        } catch (RuntimeException ex) {
            return result(operation.transferId(), command, ProductTransferStatus.SOURCE_DEBITED,
                    "SOURCE_COMPLETION_PENDING", "account transfer outcome is unknown", startedAt);
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
        byte[] digest = com.surprising.gateway.provider.support.Sha256Digests.bytes(userId + ":" + idempotencyKey.trim());
        long value = ByteBuffer.wrap(digest).order(ByteOrder.BIG_ENDIAN).getLong() & Long.MAX_VALUE;
        return value == 0 ? 1 : value;
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
