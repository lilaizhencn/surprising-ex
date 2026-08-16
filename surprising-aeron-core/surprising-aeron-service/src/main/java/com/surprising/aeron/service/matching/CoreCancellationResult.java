package com.surprising.aeron.service.matching;

public record CoreCancellationResult(long orderId, boolean accepted, String resultCode) {

    public CoreCancellationResult {
        if (orderId <= 0 || resultCode == null || resultCode.isBlank()) {
            throw new IllegalArgumentException("invalid cancellation result");
        }
    }
}
