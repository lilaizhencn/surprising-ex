package com.surprising.gateway.provider.model;

import com.surprising.account.api.model.AccountType;

public record ProductTransferCommand(
        long userId,
        String idempotencyKey,
        AccountType sourceAccountType,
        AccountType targetAccountType,
        String asset,
        long amountUnits,
        String referenceId,
        String reason,
        long recipientUserId) {
    public ProductTransferCommand(long userId, String idempotencyKey, AccountType sourceAccountType,
            AccountType targetAccountType, String asset, long amountUnits, String referenceId, String reason) {
        this(userId, idempotencyKey, sourceAccountType, targetAccountType, asset, amountUnits,
                referenceId, reason, userId);
    }
}
