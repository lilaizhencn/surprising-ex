package com.surprising.account.provider.model;

public record PendingAccountCommand(
        String commandId,
        String partitionKey,
        String serializedEnvelope) {
}
