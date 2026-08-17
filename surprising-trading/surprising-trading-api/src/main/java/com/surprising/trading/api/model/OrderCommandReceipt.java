package com.surprising.trading.api.model;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record OrderCommandReceipt(
        UUID commandId,
        String outcome,
        String code,
        String message,
        String commandResultUrl,
        List<Long> prospectiveOrderIds,
        Long requiredExportSequence,
        OrderCommandResult result,
        Long rawOfferResult) {

    public OrderCommandReceipt {
        Objects.requireNonNull(commandId, "commandId");
        outcome = requireText(outcome, "outcome");
        code = requireText(code, "code");
        prospectiveOrderIds = prospectiveOrderIds == null ? List.of() : List.copyOf(prospectiveOrderIds);
        if (prospectiveOrderIds.stream().anyMatch(id -> id == null || id <= 0L)) {
            throw new IllegalArgumentException("prospective order ids must be positive");
        }
    }

    public static String commandResultUrl(UUID commandId) {
        return "/api/v1/trading/orders/commands/" + Objects.requireNonNull(commandId, "commandId");
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }
}
