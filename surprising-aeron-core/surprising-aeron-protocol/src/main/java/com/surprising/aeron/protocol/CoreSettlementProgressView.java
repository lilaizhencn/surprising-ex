package com.surprising.aeron.protocol;

public record CoreSettlementProgressView(long settlementId, boolean complete, boolean ordersComplete,
                                         long nextCursorOrderId, long nextCursorUserId,
                                         int processedOrders, int processedUsers) {
    public CoreSettlementProgressView(long settlementId, boolean complete, long nextCursorUserId,
                                      int processedUsers) {
        this(settlementId, complete, true, 0, nextCursorUserId, 0, processedUsers);
    }

    public CoreSettlementProgressView {
        if (settlementId < 0 || nextCursorOrderId < 0 || nextCursorUserId < 0
                || processedOrders < 0 || processedOrders > 1024 || processedUsers < 0
                || processedUsers > 4096 || (ordersComplete && nextCursorOrderId != 0)
                || (!ordersComplete && nextCursorUserId != 0)
                || (complete && (nextCursorOrderId != 0 || nextCursorUserId != 0))) {
            throw new IllegalArgumentException("invalid settlement progress");
        }
    }
}
