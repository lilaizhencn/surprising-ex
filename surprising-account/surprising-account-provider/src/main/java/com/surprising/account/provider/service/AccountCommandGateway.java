package com.surprising.account.provider.service;

import com.surprising.account.api.model.AccountCommandStatus;
import com.surprising.account.api.model.AccountUserCommand;
import com.surprising.account.api.model.AccountUserCommandType;
import com.surprising.account.api.model.BalanceAdjustmentAccountCommand;
import com.surprising.account.api.model.BalanceAdjustmentRequest;
import com.surprising.account.api.model.BalanceResponse;
import com.surprising.account.api.model.PositionMarginAdjustmentRequest;
import com.surprising.account.api.model.PositionMarginAdjustmentResponse;
import com.surprising.account.api.model.PositionModeResponse;
import com.surprising.account.api.model.PositionModeUpdateRequest;
import com.surprising.account.api.model.ProductBalanceAdjustmentAccountCommand;
import com.surprising.account.api.model.ProductBalanceAdjustmentRequest;
import com.surprising.account.api.model.ProductBalanceResponse;
import com.surprising.account.api.model.ProductTransferRequest;
import com.surprising.account.api.model.ProductTransferResponse;
import com.surprising.account.provider.config.AccountProperties;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.TraceContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * Synchronous HTTP facade over the durable per-user account command lane.
 */
@Service
public class AccountCommandGateway {

    private final ObjectMapper objectMapper;
    private final AccountProperties properties;
    private final AccountCommandSubmissionService submissionService;
    private final AccountCommandResultWaiter resultWaiter;

    public AccountCommandGateway(ObjectMapper objectMapper,
                                 AccountProperties properties,
                                 AccountCommandSubmissionService submissionService,
                                 AccountCommandResultWaiter resultWaiter) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.submissionService = submissionService;
        this.resultWaiter = resultWaiter;
    }

    public BalanceResponse adjustBalance(BalanceAdjustmentRequest request,
                                         String adminUserId,
                                         String adminUsername) {
        return execute(AccountUserCommandType.BALANCE_ADJUST, request.userId(), request.referenceId(),
                new BalanceAdjustmentAccountCommand(request, adminUserId, adminUsername), BalanceResponse.class);
    }

    public ProductBalanceResponse adjustProductBalance(ProductBalanceAdjustmentRequest request,
                                                        String adminUserId,
                                                        String adminUsername) {
        return execute(AccountUserCommandType.PRODUCT_BALANCE_ADJUST, request.userId(), request.referenceId(),
                new ProductBalanceAdjustmentAccountCommand(request, adminUserId, adminUsername),
                ProductBalanceResponse.class);
    }

    public ProductTransferResponse transfer(ProductTransferRequest request) {
        return execute(AccountUserCommandType.PRODUCT_TRANSFER, request.userId(), request.referenceId(),
                request, ProductTransferResponse.class);
    }

    public PositionModeResponse updatePositionMode(PositionModeUpdateRequest request) {
        return execute(AccountUserCommandType.POSITION_MODE_UPDATE, request.userId(), request.referenceId(),
                request, PositionModeResponse.class);
    }

    public PositionMarginAdjustmentResponse adjustPositionMargin(PositionMarginAdjustmentRequest request) {
        return execute(AccountUserCommandType.POSITION_MARGIN_ADJUST, request.userId(), request.referenceId(),
                request, PositionMarginAdjustmentResponse.class);
    }

    private <T> T execute(AccountUserCommandType type,
                          long userId,
                          String referenceId,
                          Object payload,
                          Class<T> resultType) {
        ProductLine productLine = properties.getKafka().getProductLine();
        String normalizedReference = requireReference(referenceId);
        AccountUserCommand command = new AccountUserCommand(
                AccountUserCommand.CURRENT_SCHEMA_VERSION,
                commandId(type, productLine, userId, normalizedReference),
                productLine,
                userId,
                type,
                "account-api",
                normalizedReference,
                null,
                objectMapper.writeValueAsString(payload),
                Instant.now(),
                TraceContext.currentOrCreate());
        submissionService.submit(command);
        return await(command.commandId(), resultType);
    }

    private <T> T await(String commandId, Class<T> resultType) {
        var terminal = resultWaiter.await(commandId, properties.getCommandWait().getTimeout());
        if (terminal.status() == AccountCommandStatus.REJECTED) {
            String code = terminal.errorCode() == null ? "ACCOUNT_COMMAND_REJECTED" : terminal.errorCode();
            throw new IllegalStateException(code + ": " + terminal.errorMessage());
        }
        return objectMapper.readValue(terminal.resultPayload(), resultType);
    }

    private String commandId(AccountUserCommandType type,
                             ProductLine productLine,
                             long userId,
                             String referenceId) {
        String identity = productLine.name() + ':' + userId + ':' + type.name() + ':' + referenceId;
        try {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(identity.getBytes(StandardCharsets.UTF_8)));
            return "account-api:" + type.name().toLowerCase() + ':' + hash;
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private String requireReference(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("referenceId is required");
        }
        return value.trim();
    }
}
