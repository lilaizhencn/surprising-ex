package com.surprising.aeron.service.orchestration.cluster;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Objects;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

/** 仅通知休眠的 Owner；队列/完成标记仍是工作的唯一来源，不复制业务状态。 */
public final class OwnerIdleStrategy {
    private static final String WAIT_STRATEGY_PROPERTY = "surprising.aeron.owner-wait-strategy";
    private static final VarHandle PARK_REQUESTED;
    static {
        try { PARK_REQUESTED = MethodHandles.lookup().findVarHandle(Wakeup.class, "requested", long.class); }
        catch (ReflectiveOperationException failure) { throw new ExceptionInInitializerError(failure); }
    }
    private final BooleanSupplier workAvailable;
    private final WaitStrategy waitStrategy;
    /** 与 Owner 每轮修改的退避计数分离，完成生产者只读取这个通知单元。 */
    private final Wakeup wakeup = new Wakeup();
    private Thread owner;
    private int idleCount;
    private long parkNanos = 1_000;

    public OwnerIdleStrategy(BooleanSupplier workAvailable) {
        this(workAvailable, configuredWaitStrategy());
    }

    OwnerIdleStrategy(BooleanSupplier workAvailable, WaitStrategy waitStrategy) {
        this.workAvailable = Objects.requireNonNull(workAvailable);
        this.waitStrategy = Objects.requireNonNull(waitStrategy);
    }

    public void bindOwner() { owner = Thread.currentThread(); }

    public void signal() {
        if (wakeup.requested != 0 && PARK_REQUESTED.compareAndSet(wakeup, 1L, 0L)) LockSupport.unpark(owner);
    }

    public void idle(int work, boolean pendingCommands) {
        // 在途数量不是进展。先短暂自旋，再用完成通知唤醒；有界超时同时覆盖
        // 控制阶段和失败检查，不以无限轮询掩盖通知缺失。
        if (work > 0) {
            if (idleCount != 0) { idleCount = 0; parkNanos = 1_000; }
            return;
        }
        if (waitStrategy == WaitStrategy.BUSY_SPIN) {
            Thread.onSpinWait();
            return;
        }
        if (idleCount < 100) { idleCount++; Thread.onSpinWait(); return; }
        if (idleCount < 110) { idleCount++; Thread.yield(); return; }
        wakeup.requested = 1;
        try {
            // 发布先于这次重读时直接看到工作；发布晚于重读时生产者留下 unpark permit。
            if (!workAvailable.getAsBoolean()) LockSupport.parkNanos(this,
                    pendingCommands ? Math.min(parkNanos, 10_000) : parkNanos);
        } finally { wakeup.requested = 0; }
        parkNanos = Math.min(100_000, parkNanos * 2);
    }

    private static WaitStrategy configuredWaitStrategy() {
        String configured = System.getProperty(WAIT_STRATEGY_PROPERTY, WaitStrategy.ADAPTIVE.name());
        try {
            return WaitStrategy.valueOf(configured.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException(
                    WAIT_STRATEGY_PROPERTY + " must be BUSY_SPIN or ADAPTIVE", failure);
        }
    }

    enum WaitStrategy { BUSY_SPIN, ADAPTIVE }

    private static final class Wakeup {
        @SuppressWarnings("unused")
        private long p01, p02, p03, p04, p05, p06, p07;
        private volatile long requested;
        @SuppressWarnings("unused")
        private long p11, p12, p13, p14, p15, p16, p17;
    }
}
