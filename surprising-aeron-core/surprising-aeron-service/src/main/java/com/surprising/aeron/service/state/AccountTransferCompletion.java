package com.surprising.aeron.service.state;

import com.surprising.aeron.service.exception.CoreStateRejectedException;

/** Complete the source Lane's pending identity; publish only route removal to Owner. */
public final class AccountTransferCompletion implements java.util.function.IntFunction<Object>, java.util.function.BooleanSupplier {
    private TradingRuntimeState runtime;
    private long userId, transferId;
    private boolean completed, removed;

    public AccountTransferCompletion prepare(TradingRuntimeState runtime, long userId, long transferId) {
        if (runtime == null || userId <= 0 || transferId <= 0)
            throw new IllegalArgumentException("invalid transfer completion");
        runtime.assertOwner();
        long routed = runtime.pendingTransferUser(transferId);
        if (routed != 0 && routed != userId)
            throw new CoreStateRejectedException("IDEMPOTENCY_CONFLICT", "transfer belongs to another user");
        this.runtime = runtime; this.userId = userId; this.transferId = transferId;
        removed = false;
        completed = routed == 0;
        if (!completed) runtime.dispatchControlLanes(runtime.topology().accountLaneMask(userId), this);
        return this;
    }

    @Override public Object apply(int laneId) {
        removed = RuntimeAccountStateTransitions.completeTransfer(runtime, userId, transferId);
        return null;
    }

    @Override public boolean getAsBoolean() {
        runtime.assertOwner();
        if (completed) return true;
        if (!runtime.pollControlLanes()) return false;
        if (!removed) throw new IllegalStateException("pending transfer route has no Lane identity");
        runtime.completePendingTransferRoute(transferId, userId);
        completed = true;
        return true;
    }
}
