package com.surprising.aeron.service.state;

/** owner 到永久 Account Lane 的控制任务派发器；任务槽复用，完成通知不携带共享状态写入。 */
final class ControlLaneDispatcher {
    /** 唯一派发和收集方；账户工作仍由所属 Lane 单写。 */
    private final TradingRuntimeState owner;

    ControlLaneDispatcher(TradingRuntimeState owner) { this.owner = owner; }

    boolean pending() { return controlLaneWorkPending; }

    /** 当前控制阶段唯一在途的 Lane 任务，复用每条 Lane 的任务及结果槽。 */
    private long pendingControlLaneMask;
    /** 当前是否正在收集任务，防止同一批次被覆盖。 */
    private boolean controlLaneWorkPending;
    /** 最近完成批次的参与 Lane，用于拒绝读取上一个批次的残留结果。 */
    private long completedLaneMask;

    /** 本阶段的业务分类，用于区分风险与结算实际任务计数。 */
    private AccountLaneOperationType operationType;

    void dispatch(long laneMask, AccountLaneOperationType type, java.util.function.IntFunction<Object> operation) {
        owner.assertOwner();
        long validMask = owner.accountLanes.length == 64 ? -1L : (1L << owner.accountLanes.length) - 1;
        if (controlLaneWorkPending || laneMask == 0 || (laneMask & ~validMask) != 0 || operation == null || type == null)
            throw new IllegalStateException("invalid control Lane dispatch");
        // 后续账户操作在原有永久 Lane 线程执行；owner 只保留全局状态写入权。
        owner.releaseOwnerLaneAccess();
        operationType = type;
        completedLaneMask = 0;
        pendingControlLaneMask = laneMask;
        controlLaneWorkPending = true;
        for (int id = 0; id < owner.accountLanes.length; id++) {
            if ((laneMask & (1L << id)) == 0) continue;
            TradingRuntimeState.LaneMutationTask task = owner.laneMutationTasks[id];
            if (task == null) owner.laneMutationTasks[id] = task = owner.new LaneMutationTask(id);
            task.prepareIndexed(operation);
            owner.laneMutationStartedNanosScratch[id] = System.nanoTime();
            if (!owner.accountLanesStarted) {
                task.execute(owner.accountLanes[id]);
            } else {
                owner.accountLaneQueueHighWaterMarks[id] = Math.max(owner.accountLaneQueueHighWaterMarks[id],
                        owner.laneWorkers[id].depth() + 1);
                owner.laneWorkers[id].submit(task);
            }
        }
    }

    /** 未就绪立即返回；全部任务完成后再交接，保证失败回滚不会与账户写入并发。 */
    boolean poll() {
        owner.assertOwner();
        if (!controlLaneWorkPending) throw new IllegalStateException("no control Lane work");
        owner.assertAccountLanesHealthy();
        for (int id = 0; id < owner.accountLanes.length; id++) {
            if ((pendingControlLaneMask & (1L << id)) != 0 && !owner.laneMutationTasks[id].completed) return false;
        }
        boolean failed = false;
        for (int id = 0; id < owner.accountLanes.length; id++)
            if ((pendingControlLaneMask & (1L << id)) != 0 && owner.laneMutationTasks[id].failure != null) failed = true;
        if (failed && !owner.tryAcquireOwnerLaneAccess()) return false;
        long mask = pendingControlLaneMask;
        pendingControlLaneMask = 0;
        completedLaneMask = mask;
        controlLaneWorkPending = false;
        Throwable failure = null;
        for (int id = 0; id < owner.accountLanes.length; id++) {
            if ((mask & (1L << id)) == 0) continue;
            TradingRuntimeState.LaneMutationTask task = owner.laneMutationTasks[id];
            task.operation = null;
            task.indexedOperation = null;
            owner.flushPublishedChanges(id);
            owner.recordLaneOperation(id, operationType,
                    System.nanoTime() - owner.laneMutationStartedNanosScratch[id]);
            if (task.failure != null) {
                if (failure == null) failure = task.failure;
                else failure.addSuppressed(task.failure);
            }
        }
        if (failure instanceof RuntimeException exception) throw exception;
        if (failure instanceof Error error) throw error;
        if (failure != null) throw new IllegalStateException("control Lane failed", failure);
        return true;
    }

    Object result(int laneId) {
        owner.assertOwner();
        if (controlLaneWorkPending || laneId < 0 || laneId >= owner.accountLanes.length
                || (completedLaneMask & (1L << laneId)) == 0)
            throw new IllegalStateException("Lane result is not ready");
        return owner.laneMutationTasks[laneId].result;
    }

}
