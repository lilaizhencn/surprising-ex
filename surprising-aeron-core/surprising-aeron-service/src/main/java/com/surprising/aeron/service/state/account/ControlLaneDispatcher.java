package com.surprising.aeron.service.state.account;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.Getter;
import lombok.experimental.Accessors;
import com.surprising.aeron.service.command.AccountLaneOperationType;
import com.surprising.aeron.service.exception.CoreStateRejectedException;
import com.surprising.aeron.service.state.TradingRuntimeState;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.function.IntFunction;

/**
 * 一个运行时同时推进一项控制派发。复用每 Lane 的任务槽，先保存结果和修订增量再发布完成位，Owner 观察到完成位后才读取结果（release/acquire），收齐后再发布变化。它不自动提供跨 Lane 事务回滚，业务拒绝与工作线程故障沿原边界传播。
 * 构造约定：保存所属运行时，任务槽和完成数组在首次派发时按拓扑创建。
 * 参数 owner：拥有派发周期的交易运行时
 */
@RequiredArgsConstructor
public final class ControlLaneDispatcher {
    /** 完成标记之间间隔十六个 long，减少不同 Lane 频繁写相邻位置。 */
    private static final int CACHE_LINE_LONGS = 16;
    /** long 数组元素的 VarHandle，完成发布用 release、Owner 观察用 acquire。 */
    private static final VarHandle LONGS = MethodHandles.arrayElementVarHandle(long[].class);
    /** 持有控制命令生命周期与发布水位的运行时。 */
    private final TradingRuntimeState owner;
    /** 按实际账户 Lane 编号排列的复用控制任务槽。 */
    private LaneControlTask[] tasks;
    /** 每 Lane 间隔存放的完成标记，零未完成、非零已发布结果。 */
    private long[] completedLanes;
    /** 当前控制派发必须完成的 Lane 位集合。 */
    private long requiredLaneMask;
    /** Owner 已 acquire 观察到完成的 Lane 缓存位图。 */
    private long observedCompletedMask;
    /**  当前是否还需要 Owner 收集控制派发结果。 返回 Owner 是否仍在等待当前控制派发收齐回执。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private boolean pending;
    /** 当前账户控制操作分类，用于工作队列统计和诊断。 */
    private AccountLaneOperationType operationType;


    /**
     * 首次使用时为每个账户 Lane 创建一个可复用任务槽，并为各完成标记留出固定间隔。
     */
    private void initializeTasks() {
        if (tasks != null) return;
        // 当前拓扑中账户 Lane 的实际数量。
        int count = owner.topology().accountLaneCount();
        tasks = new LaneControlTask[count];
        completedLanes = new long[count * CACHE_LINE_LONGS];
        for (/* 当前位图最低有效位对应的 Lane 编号。 */ int laneId = 0; laneId < count; laneId++) tasks[laneId] = new LaneControlTask(laneId);
    }


    /**
     * 检查当前 Lane 命令作用域对应的控制任务是否正在执行，供账户变更归集使用。
     */
    public boolean active() {
        // 当前控制执行作用域所拥有的 Lane；不在 Lane 回调时为空。
        AccountLaneState lane = owner.currentLaneCommandScope();
        return lane != null && tasks != null && tasks[lane.laneId()].executing;
    }

    /**
     * 只允许当前正在执行的控制 Lane 精确累加自己的产品修订增量，避免多线程直接改全局修订号。
     */
    public void recordRevisionDelta() {
        // 当前控制执行作用域所拥有的 Lane；不在 Lane 回调时为空。
        AccountLaneState lane = owner.currentLaneCommandScope();
        if (lane == null || !tasks[lane.laneId()].executing)
            throw new IllegalStateException("account revision must belong to its control Lane");
        // 当前 Lane 对应的复用控制任务槽。
        LaneControlTask task = tasks[lane.laneId()];
        task.revisionDelta = Math.incrementExact(task.revisionDelta);
    }

    /**
     * 生成当前目标位图、已观察完成位图及操作类型的诊断文本；读取完成位时会更新 Owner 的观察缓存。
     */
    public String diagnostics() {
        return "pending=" + pending + ",expected=" + Long.toHexString(requiredLaneMask)
                + ",completed=" + Long.toHexString(completedMask()) + ",operation=" + operationType;
    }

    /**
     * 判断当前未完成派发是否包含指定 Lane，不表示该 Lane 已经开始执行。
     * @param laneId 当前要检查、执行或读取的账户 Lane 编号
     */
    boolean targetsLane(int laneId) { return pending && (requiredLaneMask & (1L << laneId)) != 0; }

    /**
     * 要求当前没有在途控制命令，检查非空合法 Lane 位图和输入；工作线程模式先保证容量并交出 Lane 访问，再逐目标内联执行或入队。
     * @param laneMask 本次要执行的非空合法 Lane 位图
     * @param type 本次控制工作的操作分类
     * @param operation 在每个目标 Lane 命令作用域中执行、接收 Lane 编号的回调
     */
    public void dispatch(long laneMask, AccountLaneOperationType type, IntFunction<Object> operation) {
        owner.assertOwner();
        initializeTasks();
        // 按实际 Lane 数量计算的允许位集合，六十四条时全部位有效。
        long validMask = tasks.length == 64 ? -1L : (1L << tasks.length) - 1;
        if (pending || laneMask == 0
                || (laneMask & ~validMask) != 0 || operation == null || type == null)
            throw new IllegalStateException("invalid control Lane dispatch");
        if (owner.accountLanesStarted()) {
            owner.ensureLaneWorkerCapacity(laneMask);
            owner.releaseOwnerLaneAccess();
        }
        for (LaneControlTask /* 当前 Lane 对应的复用控制任务槽。 */ task : tasks) task.clear();
        requiredLaneMask = laneMask;
        observedCompletedMask = 0;
        operationType = type;
        pending = true;
        // 还需逐个处理的目标 Lane 位图，每次清除最低有效位。
        long lanes = laneMask;
        while (lanes != 0) {
            // 当前位图最低有效位对应的 Lane 编号。
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

    /**
     * 检查工作线程健康并等待全部目标完成，按 Lane 编号汇总修订及首个业务拒绝；无拒绝才发布 Lane 变化和产品修订号。
     */
    public boolean poll() {
        owner.assertOwner();
        owner.assertAccountLanesHealthy();
        if (!pending) return true;
        if (completedMask() != requiredLaneMask) return false;
        pending = false;
        // Owner 收齐后精确合计的各 Lane 修订增量。
        long revisionDelta = 0;
        // 按 Lane 编号顺序遇到的第一个业务拒绝，所有目标完成后统一抛出。
        RuntimeException rejection = null;
        // 还需逐个处理的目标 Lane 位图，每次清除最低有效位。
        long lanes = requiredLaneMask;
        while (lanes != 0) {
            // 当前位图最低有效位对应的 Lane 编号。
            int laneId = Long.numberOfTrailingZeros(lanes);
            lanes &= lanes - 1;
            // 当前 Lane 对应的复用控制任务槽。
            LaneControlTask task = tasks[laneId];
            revisionDelta = Math.addExact(revisionDelta, task.revisionDelta);
            if (rejection == null) rejection = task.rejection;
        }
        if (rejection != null) throw rejection;
        lanes = requiredLaneMask;
        while (lanes != 0) {
            // 当前位图最低有效位对应的 Lane 编号。
            int laneId = Long.numberOfTrailingZeros(lanes);
            lanes &= lanes - 1;
            owner.flushPublishedChanges(laneId);
        }
        if (revisionDelta != 0)
            owner.setMetadata(owner.productLine(), Math.addExact(owner.revision(), revisionDelta));
        return true;
    }

    /**
     * 在当前派发已收齐完成并结束等待后读取指定目标 Lane 的返回值，不允许提前读取或读取未参与的 Lane。
     * @param laneId 当前要检查、执行或读取的账户 Lane 编号
     */
    public Object result(int laneId) {
        owner.assertOwner();
        if (tasks == null || laneId < 0 || laneId >= tasks.length || (requiredLaneMask & (1L << laneId)) == 0
                || pending || completedMask() != requiredLaneMask)
            throw new IllegalStateException("Lane result is not ready");
        return tasks[laneId].result;
    }

    /**
     * 以 acquire 只读取尚未观察完成的目标标记，把结果缓存到 Owner 位图，避免反复读取已完成 Lane。
     */
    private long completedMask() {
        // 还需逐个处理的目标 Lane 位图，每次清除最低有效位。
        long lanes = requiredLaneMask & ~observedCompletedMask;
        while (lanes != 0) {
            // 当前位图最低有效位对应的 Lane 编号。
            int laneId = Long.numberOfTrailingZeros(lanes);
            lanes &= lanes - 1;
            if ((long) LONGS.getAcquire(completedLanes, laneId * CACHE_LINE_LONGS) != 0)
                observedCompletedMask |= 1L << laneId;
        }
        return observedCompletedMask;
    }

    /** 一个 Lane 固定复用的控制任务槽，操作、结果与拒绝只在对应完成标记建立的交接边界内使用。 构造约定：固定任务所属 Lane 编号，后续派发只复用操作及结果字段。 参数 laneId：当前要检查、执行或读取的账户 Lane 编号 */
    @RequiredArgsConstructor(access = AccessLevel.PACKAGE)
    private final class LaneControlTask implements com.surprising.aeron.service.lane.SettlementLaneWorker.Command {
        /** 复用任务固定所属的 Lane 编号。 */
        private final int laneId;
        /** 这次派发在 Lane 作用域内执行的回调，完成发布前解除引用。 */
        private IntFunction<Object> operation;
        /** Lane 执行回调产生的返回值，Owner 在 acquire 完成后读取。 */
        private Object result;
        /** 可转换为业务拒绝的运行异常，其他故障由工作线程故障边界处理。 */
        private RuntimeException rejection;
        /** 本 Lane 在当前控制命令中累加的产品修订次数。 */
        private long revisionDelta;
        /** 该任务是否正处于 Lane 执行作用域，仅用于所属执行线程判断。 */
        private boolean executing;


        /**
         * 清空上次操作、结果、拒绝及修订增量；仅在上一派发已完成时复用，不在此重新建立线程同步。
         */
        void clear() {
            operation = null;
            result = null;
            rejection = null;
            revisionDelta = 0;
        }

        /**
         * 在所属 Lane 命令作用域运行操作，保存可预期业务拒绝，冻结控制变化并结束执行标记，最后 release 发布完成并通知 Owner。
         * @param lane 工作线程实际拥有的账户 Lane 状态
         */
        @Override public void execute(AccountLaneState lane) {
            executing = true;
            try { result = owner.inLaneCommandScope(lane, /* inLaneCommandScope 提供的 Lane 参数，本回调用固定 laneId 调用操作，不读取此参数。 */ ignored -> operation.apply(laneId)); }
            catch (/* 被识别为业务拒绝的状态拒绝、算术溢出或非法参数异常。 */ CoreStateRejectedException | ArithmeticException | IllegalArgumentException failure) {
                rejection = failure;
            } finally {
                owner.laneDelta(laneId).freezeControlValues();
                executing = false;
                operation = null;
            }
            // release 发布后不再读取可变任务字段，Owner 此后可以立即复用槽位。
            LONGS.setRelease(completedLanes, laneId * CACHE_LINE_LONGS, 1L);
            owner.signalOwnerCompletion();
        }
    }
}
