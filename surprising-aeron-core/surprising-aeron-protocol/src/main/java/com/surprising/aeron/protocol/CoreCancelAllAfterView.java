package com.surprising.aeron.protocol;

public record CoreCancelAllAfterView(
        long userId,
        String symbolScope,
        CoreCancelAllAfterStatus status,
        long triggerAtEpochMillis,
        long updatedAtEpochMillis,
        long revision) {

    public CoreCancelAllAfterView {
        if (userId <= 0 || symbolScope == null || symbolScope.isBlank() || status == null
                || triggerAtEpochMillis < 0 || updatedAtEpochMillis <= 0 || revision <= 0) {
            throw new IllegalArgumentException("invalid cancel-all-after view");
        }
    }
}
