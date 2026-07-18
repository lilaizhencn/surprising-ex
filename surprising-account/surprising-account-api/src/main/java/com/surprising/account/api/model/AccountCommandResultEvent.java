package com.surprising.account.api.model;

import com.surprising.product.api.ProductLine;
import java.time.Instant;

public record AccountCommandResultEvent(
        long eventId,
        String commandId,
        ProductLine productLine,
        long userId,
        AccountUserCommandType commandType,
        AccountCommandStatus status,
        String source,
        String sourceReference,
        String resultPayload,
        String errorCode,
        String errorMessage,
        Instant completedAt,
        String traceId) {

    public AccountCommandResultEvent {
        if (eventId <= 0) {
            throw new IllegalArgumentException("eventId must be positive");
        }
        if (commandId == null || commandId.isBlank()) {
            throw new IllegalArgumentException("commandId is required");
        }
        if (productLine == null || userId <= 0 || commandType == null || status == null) {
            throw new IllegalArgumentException("invalid account command result identity");
        }
        if (status == AccountCommandStatus.PROCESSING || status == AccountCommandStatus.WAITING_DEPENDENCY) {
            throw new IllegalArgumentException("only terminal account command results may be published");
        }
        completedAt = completedAt == null ? Instant.now() : completedAt;
    }
}
