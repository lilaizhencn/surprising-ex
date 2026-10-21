package com.surprising.gateway.provider.model;

public record ProductTransferWireRequest(
        String sourceAccountType,
        String targetAccountType,
        String asset,
        java.math.BigDecimal amountUnits,
        String referenceId,
        String reason,
        Long recipientUserId) {
}
