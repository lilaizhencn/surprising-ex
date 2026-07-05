package com.surprising.trading.api.model;

public record TestOrderResponse(
        boolean accepted,
        String rejectReason,
        long instrumentVersion,
        String validationStage,
        String accountType,
        String asset,
        long estimatedReserveUnits) {
}
