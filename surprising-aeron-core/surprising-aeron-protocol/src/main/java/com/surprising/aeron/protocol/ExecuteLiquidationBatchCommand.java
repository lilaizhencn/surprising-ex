package com.surprising.aeron.protocol;

import java.util.List;

public record ExecuteLiquidationBatchCommand(
        List<ExecuteLiquidationBatchAction> actions,
        int maxCancelOrders,
        long liquidationFeeRatePpm,
        CoreRiskScanContinuation riskScanContinuation,
        int maxRiskScanUsers) {

    public static final int WIRE_VERSION = 1;
    public static final int MAX_ACTIONS = 1_000;
    public static final int MAX_CANCEL_ORDERS = 1_024;
    public static final int MAX_RISK_SCAN_USERS = 4_096;

    /** Preserve the exact queried continuation when combining execution and bounded risk work. */
    public static ExecuteLiquidationBatchCommand fromWork(CoreLiquidationWorkView work,
                                                          long liquidationFeeRatePpm, int maxRiskScanUsers) {
        if (work == null) throw new IllegalArgumentException("liquidation work is required");
        List<ExecuteLiquidationBatchAction> actions = work.actions().stream()
                .map(action -> new ExecuteLiquidationBatchAction(action.liquidationId(), action.userId(), action.symbol(),
                        action.instrumentChangeId(), action.triggerPriceSequence(), action.markPriceTicks(),
                        action.cursorOrderId()))
                .toList();
        boolean continueScan = work.riskScanPending() && maxRiskScanUsers > 0;
        return new ExecuteLiquidationBatchCommand(actions, MAX_CANCEL_ORDERS, liquidationFeeRatePpm,
                continueScan ? work.riskScanContinuation() : null, continueScan ? maxRiskScanUsers : 0);
    }

    public ExecuteLiquidationBatchCommand {
        if (actions == null || actions.size() > MAX_ACTIONS
                || maxCancelOrders < 1 || maxCancelOrders > MAX_CANCEL_ORDERS
                || liquidationFeeRatePpm < 0 || liquidationFeeRatePpm > 1_000_000
                || riskScanContinuation == null && maxRiskScanUsers != 0
                || riskScanContinuation != null
                && (!riskScanContinuation.exact()
                || maxRiskScanUsers < 1 || maxRiskScanUsers > MAX_RISK_SCAN_USERS)
                || actions.isEmpty() && riskScanContinuation == null) {
            throw new IllegalArgumentException("invalid liquidation batch command");
        }
        actions = List.copyOf(actions);
        long previousLiquidationId = 0;
        for (ExecuteLiquidationBatchAction action : actions) {
            if (action == null || action.liquidationId() <= previousLiquidationId) {
                throw new IllegalArgumentException("liquidation batch actions must be sorted and unique");
            }
            previousLiquidationId = action.liquidationId();
        }
    }
}
