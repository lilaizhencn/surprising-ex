package com.surprising.aeron.service.state.account;

import com.surprising.aeron.service.command.AccountLaneOperationType;
import com.surprising.aeron.service.exception.CoreStateRejectedException;
import com.surprising.aeron.service.state.TradingRuntimeState;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.function.IntFunction;

/** 一个控制命令把账户工作交给所属 Lane，Owner 后续轮询轻量完成标记。 */
public final class ControlLaneDispatcher {
    private static final int CACHE_LINE_LONGS = 16;
    private static final VarHandle LONGS = MethodHandles.arrayElementVarHandle(long[].class);
    private final TradingRuntimeState owner;
    private LaneControlTask[] tasks;
    private long[] completedLanes;
    private long requiredLaneMask;
    private long observedCompletedMask;
    private boolean pending;
    private AccountLaneOperationType operationType;

    public ControlLaneDispatcher(TradingRuntimeState owner) {
        this.owner = owner;
    }

    private void initializeTasks() {
        if (tasks != null) return;
        int count = owner.topology().accountLaneCount();
        tasks = new LaneControlTask[count];
        completedLanes = new long[count * CACHE_LINE_LONGS];
        for (int laneId = 0; laneId < count; laneId++) tasks[laneId] = new LaneControlTask(laneId);
    }

    public boolean pending() { return pending; }

    public boolean active() {
        AccountLaneState lane = owner.currentLaneCommandScope();
        return lane != null && tasks != null && tasks[lane.laneId()].executing;
    }

    public void recordRevisionDelta() {
        AccountLaneState lane = owner.currentLaneCommandScope();
        if (lane == null || !tasks[lane.laneId()].executing)
            throw new IllegalStateException("account revision must belong to its control Lane");
        LaneControlTask task = tasks[lane.laneId()];
        task.revisionDelta = Math.incrementExact(task.revisionDelta);
    }

    public String diagnostics() {
        return "pending=" + pending + ",expected=" + Long.toHexString(requiredLaneMask)
                + ",completed=" + Long.toHexString(completedMask()) + ",operation=" + operationType;
    }

    boolean targetsLane(int laneId) { return pending && (requiredLaneMask & (1L << laneId)) != 0; }

    public void dispatch(long laneMask, AccountLaneOperationType type, IntFunction<Object> operation) {
        owner.assertOwner();
        initializeTasks();
        long validMask = tasks.length == 64 ? -1L : (1L << tasks.length) - 1;
        if (pending || laneMask == 0
                || (laneMask & ~validMask) != 0 || operation == null || type == null)
            throw new IllegalStateException("invalid control Lane dispatch");
        if (owner.accountLanesStarted()) {
            owner.ensureLaneWorkerCapacity(laneMask);
            owner.releaseOwnerLaneAccess();
        }
        for (LaneControlTask task : tasks) task.clear();
        requiredLaneMask = laneMask;
        observedCompletedMask = 0;
        operationType = type;
        pending = true;
        long lanes = laneMask;
        while (lanes != 0) {
            int laneId = Long.numberOfTrailingZeros(lanes);
            lanes &= lanes - 1;
            LONGS.setRelease(completedLanes, laneId * CACHE_LINE_LONGS, 0L);
            tasks[laneId].operation = operation;
            if (!owner.accountLanesStarted()) {
                tasks[laneId].execute(owner.accountLane(laneId));
            } else {
                owner.laneWorkers[laneId].submit(tasks[laneId]);
            }
        }
    }

    public boolean poll() {
        owner.assertOwner();
        owner.assertAccountLanesHealthy();
        if (!pending) return true;
        if (completedMask() != requiredLaneMask) return false;
        pending = false;
        long revisionDelta = 0;
        RuntimeException rejection = null;
        long lanes = requiredLaneMask;
        while (lanes != 0) {
            int laneId = Long.numberOfTrailingZeros(lanes);
            lanes &= lanes - 1;
            LaneControlTask task = tasks[laneId];
            revisionDelta = Math.addExact(revisionDelta, task.revisionDelta);
            if (rejection == null) rejection = task.rejection;
        }
        if (rejection != null) throw rejection;
        lanes = requiredLaneMask;
        while (lanes != 0) {
            int laneId = Long.numberOfTrailingZeros(lanes);
            lanes &= lanes - 1;
            owner.flushPublishedChanges(laneId);
        }
        if (revisionDelta != 0)
            owner.setMetadata(owner.productLine(), Math.addExact(owner.revision(), revisionDelta));
        return true;
    }

    public Object result(int laneId) {
        owner.assertOwner();
        if (tasks == null || laneId < 0 || laneId >= tasks.length || (requiredLaneMask & (1L << laneId)) == 0
                || pending || completedMask() != requiredLaneMask)
            throw new IllegalStateException("Lane result is not ready");
        return tasks[laneId].result;
    }

    private long completedMask() {
        long lanes = requiredLaneMask & ~observedCompletedMask;
        while (lanes != 0) {
            int laneId = Long.numberOfTrailingZeros(lanes);
            lanes &= lanes - 1;
            if ((long) LONGS.getAcquire(completedLanes, laneId * CACHE_LINE_LONGS) != 0)
                observedCompletedMask |= 1L << laneId;
        }
        return observedCompletedMask;
    }

    private final class LaneControlTask implements com.surprising.aeron.service.lane.SettlementLaneWorker.Command {
        private final int laneId;
        private IntFunction<Object> operation;
        private Object result;
        private RuntimeException rejection;
        private long revisionDelta;
        private boolean executing;

        LaneControlTask(int laneId) { this.laneId = laneId; }

        void clear() {
            operation = null;
            result = null;
            rejection = null;
            revisionDelta = 0;
        }

        @Override public void execute(AccountLaneState lane) {
            executing = true;
            try { result = owner.inLaneCommandScope(lane, ignored -> operation.apply(laneId)); }
            catch (CoreStateRejectedException | ArithmeticException | IllegalArgumentException failure) {
                rejection = failure;
            } finally {
                owner.laneDelta(laneId).freezeControlValues();
                executing = false;
                operation = null;
            }
            // No mutable task fields are read after release; the Owner may reuse this slot immediately.
            LONGS.setRelease(completedLanes, laneId * CACHE_LINE_LONGS, 1L);
            owner.signalOwnerCompletion();
        }
    }
}
