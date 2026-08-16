package com.surprising.aeron.protocol;

public record CoreLiquidationBatchResultView(
        int offeredActions,
        int appliedActions,
        int pendingActions,
        int obsoleteActions,
        int processedOrders,
        int riskScanContinuedUsers) {

    public CoreLiquidationBatchResultView {
        if (offeredActions < 0 || offeredActions > ExecuteLiquidationBatchCommand.MAX_ACTIONS
                || appliedActions < 0 || pendingActions < 0 || obsoleteActions < 0
                || (long) appliedActions + pendingActions + obsoleteActions != offeredActions
                || processedOrders < 0
                || processedOrders > ExecuteLiquidationBatchCommand.MAX_CANCEL_ORDERS
                || riskScanContinuedUsers < 0
                || riskScanContinuedUsers > ExecuteLiquidationBatchCommand.MAX_RISK_SCAN_USERS) {
            throw new IllegalArgumentException("invalid liquidation batch result");
        }
    }
}
