package com.surprising.aeron.protocol;

public record CoreFundingProgressView(long settlementId, boolean complete, long nextCursorUserId,
                                      int processedUsers) {
    public CoreFundingProgressView {
        if (settlementId < 0 || nextCursorUserId < 0 || processedUsers < 0
                || processedUsers > 4096 || (complete && nextCursorUserId != 0)) {
            throw new IllegalArgumentException("invalid funding progress");
        }
    }
}
