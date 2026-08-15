package com.surprising.aeron.protocol;

public record CoreSettlementProgressView(long settlementId, boolean complete, long nextCursorUserId,
                                         int processedUsers) {
    public CoreSettlementProgressView {
        if (settlementId < 0 || nextCursorUserId < 0 || processedUsers < 0
                || processedUsers > 4096 || (complete && nextCursorUserId != 0)) {
            throw new IllegalArgumentException("invalid settlement progress");
        }
    }
}
