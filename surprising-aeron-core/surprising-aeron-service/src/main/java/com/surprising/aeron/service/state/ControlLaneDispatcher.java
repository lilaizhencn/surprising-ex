package com.surprising.aeron.service.state;

import com.surprising.aeron.service.command.AccountLaneOperationType;

/** owner 到 Account Lane 的控制任务派发器；单写者模型下同步内联派发与收集。 */
final class ControlLaneDispatcher {
    private final TradingRuntimeState owner;
    private Object[] results;
    private long completedLaneMask;
    private long pendingRevisionDelta;
    private boolean active;
    private AccountLaneOperationType operationType;

    ControlLaneDispatcher(TradingRuntimeState owner) {
        this.owner = owner;
    }

    boolean pending() { return false; }

    boolean active() { return active; }

    void recordRevisionDelta() {
        pendingRevisionDelta = Math.incrementExact(pendingRevisionDelta);
    }

    String diagnostics() {
        return "pending=false,completed=" + Long.toHexString(completedLaneMask) + ",operation=" + operationType;
    }

    boolean targetsLane(int laneId) {
        return false;
    }

    void dispatch(long laneMask, AccountLaneOperationType type, java.util.function.IntFunction<Object> operation) {
        owner.assertOwner();
        if (!owner.accountLanesStarted) throw new IllegalStateException("control work requires running Account Lanes");
        int laneCount = owner.accountLanes == null ? 0 : owner.accountLanes.length;
        long validMask = laneCount == 64 ? -1L : (1L << laneCount) - 1;
        if (laneMask == 0 || (laneMask & ~validMask) != 0 || operation == null || type == null)
            throw new IllegalStateException("invalid control Lane dispatch");
        if (results == null || results.length != laneCount) {
            results = new Object[laneCount];
        }
        owner.releaseOwnerLaneAccess();
        operationType = type;
        completedLaneMask = laneMask;
        active = true;
        try {
            for (int id = 0; id < laneCount; id++) {
                if ((laneMask & (1L << id)) == 0) continue;
                final int laneId = id;
                results[laneId] = owner.inLaneCommandScope(owner.accountLanes[laneId], ignored -> operation.apply(laneId));
            }
        } finally {
            active = false;
        }
    }

    boolean poll() {
        owner.assertOwner();
        if (pendingRevisionDelta != 0) {
            long delta = pendingRevisionDelta;
            pendingRevisionDelta = 0;
            owner.setMetadata(owner.productLine(), Math.addExact(owner.revision(), delta));
        }
        return true;
    }

    Object result(int laneId) {
        owner.assertOwner();
        int laneCount = owner.accountLanes == null ? 0 : owner.accountLanes.length;
        if (laneId < 0 || laneId >= laneCount || (completedLaneMask & (1L << laneId)) == 0 || results == null)
            throw new IllegalStateException("Lane result is not ready");
        return results[laneId];
    }
}
