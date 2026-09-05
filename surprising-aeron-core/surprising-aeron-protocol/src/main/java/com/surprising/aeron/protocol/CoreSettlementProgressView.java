package com.surprising.aeron.protocol;

public record CoreSettlementProgressView(long settlementId, boolean complete, boolean ordersComplete,
                                         long nextCursorOrderId, long nextCursorUserId,
                                         int processedOrders, int processedUsers, long requiredInsuranceUnits) {
    public CoreSettlementProgressView(long settlementId, boolean complete, boolean ordersComplete,
                                      long nextCursorOrderId, long nextCursorUserId, int processedOrders, int processedUsers) {
        this(settlementId, complete, ordersComplete, nextCursorOrderId, nextCursorUserId, processedOrders, processedUsers, 0);
    }
    public CoreSettlementProgressView(long settlementId, boolean complete, long nextCursorUserId,
                                      int processedUsers) {
        this(settlementId, complete, true, 0, nextCursorUserId, 0, processedUsers);
    }

    public CoreSettlementProgressView {
        if (settlementId < 0 || nextCursorOrderId < 0 || nextCursorUserId < 0
                || requiredInsuranceUnits < 0 || requiredInsuranceUnits > 0 && (complete || !ordersComplete || processedUsers != 0)
                || processedOrders < 0 || processedOrders > 1024 || processedUsers < 0
                || processedUsers > 4096 || (ordersComplete && nextCursorOrderId != 0)
                || (!ordersComplete && nextCursorUserId != 0)
                || (complete && (nextCursorOrderId != 0 || nextCursorUserId != 0))) {
            throw new IllegalArgumentException("invalid settlement progress");
        }
    }
}
