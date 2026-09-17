package com.surprising.aeron.service.state;

import com.surprising.aeron.service.state.model.CoreAlgoOrderState;
import com.surprising.aeron.service.state.model.CoreLiquidationState;
import com.surprising.aeron.service.state.model.CoreTriggerOrderState;

/** Owns runtime order commit metadata and terminal-state cleanup. */
final class RuntimeOrderCommitStateTransitions {

    private RuntimeOrderCommitStateTransitions() {
    }

    static void validateStampInputs(long timestamp, long position, Iterable<Long> orderIds) {
        if (timestamp < 0 || position < 0 || orderIds == null)
            throw new IllegalArgumentException("invalid order commit metadata");
        // Preserve the preparation boundary before any account task can start.
        for (Long ignored : orderIds) { }
    }

    static boolean stampChangedOrdersByLane(
            TradingRuntimeState runtime, long timestamp, long clusterPosition,
            Iterable<Long> changedOrderIds, Iterable<Long> changedUserIds) {
        if (runtime == null || changedOrderIds == null || changedUserIds == null
                || timestamp < 0 || clusterPosition < 0) {
            throw new IllegalArgumentException("invalid lane-batched runtime order commit metadata");
        }
        runtime.assertOwner();
        if (changedOrderIds instanceof java.util.Collection<?> orders && orders.isEmpty()) return false;
        // Callers retain this command-local collection until the synchronous Lane stage completes.
        // Do not copy every order ID into a second boxed list just to iterate it again.
        boolean hasCandidates = false;
        for (Long orderId : changedOrderIds) hasCandidates |= orderId != null;
        if (!hasCandidates) return false;
        Object[] results = runtime.executeOwnerSettlements(changedUserIds, ignored -> {
            boolean changed = false;
            for (Long orderId : changedOrderIds) {
                if (orderId == null) continue;
                OrderRuntime order = runtime.order(orderId);
                if (order == null || order.updatedAtEpochMillis() == timestamp
                        && order.clusterPosition() == clusterPosition) continue;
                runtime.replaceOrder(order.withCommitMetadata(timestamp, clusterPosition));
                changed = true;
            }
            return changed;
        });
        for (Object result : results) {
            if (Boolean.TRUE.equals(result)) return true;
        }
        return false;
    }

    static void pruneTerminalState(TradingRuntimeState runtime, RuntimeIdentityRegistry identities,
                                   TerminalPruneBatch batch) {
        if (runtime == null || identities == null || batch == null || batch.isEmpty()) {
            throw new IllegalArgumentException("invalid runtime terminal prune");
        }
        runtime.assertOwner();
        runtime.pruneTerminalOrders(identities, batch.orderIds());
        for (long algoOrderId : batch.algoOrderIds()) {
            CoreAlgoOrderState algo = runtime.algoOrder(algoOrderId);
            if (algo == null || !algo.terminal()) {
                throw new IllegalStateException("algo order is not terminal: " + algoOrderId);
            }
            runtime.removeAlgoOrder(algoOrderId);
        }
        for (long triggerOrderId : batch.triggerOrderIds()) {
            CoreTriggerOrderState trigger = runtime.triggerOrder(triggerOrderId);
            if (trigger == null || trigger.status().open()) {
                throw new IllegalStateException("trigger order is not terminal: " + triggerOrderId);
            }
            runtime.removeTriggerOrder(triggerOrderId);
        }
        for (long liquidationId : batch.liquidationIds()) {
            LiquidationRuntime liquidation = runtime.liquidation(liquidationId);
            if (liquidation == null || liquidation.status() != CoreLiquidationState.Status.CANCELED
                    && (liquidation.status() != CoreLiquidationState.Status.COMPLETED
                    || liquidation.deficitUnits() != 0)) {
                throw new IllegalStateException("liquidation is not terminal: " + liquidationId);
            }
            runtime.removeLiquidation(liquidationId);
        }
    }
}
