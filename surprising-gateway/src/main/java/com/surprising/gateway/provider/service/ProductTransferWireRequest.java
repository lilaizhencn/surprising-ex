package com.surprising.gateway.provider.service;

public record ProductTransferWireRequest(
        String sourceAccountType,
        String targetAccountType,
        String asset,
        long amountUnits,
        String referenceId,
        String reason) {
}
