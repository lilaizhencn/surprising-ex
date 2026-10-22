package com.surprising.aeron.bootstrap;

import com.surprising.aeron.config.CoreRuntimeConfiguration;
import com.surprising.aeron.config.CoreRuntimeConfiguration.OwnerWaitStrategy;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Objects;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

/**
 * Owner 线程的等待与唤醒工具：默认持续自旋，可选逐步退避。仅查询已有工作提示并通知等待线程，命令队列和完成标记仍是事实来源，本类不维护第二份业务状态。
 */
public final class OwnerIdleStrategy {
    /** 通知位的字段句柄，用于跨线程原子比较交换。 */
    private static final VarHandle PARK_REQUESTED;
    /** 建立通知位的原子访问句柄；反射失败直接阻止使用等待器。 */
    static {
        try { PARK_REQUESTED = MethodHandles.lookup().findVarHandle(Wakeup.class, "requested", long.class); }
        catch (/* 句柄查找或配置解析失败的异常，保留为启动错误原因 */ ReflectiveOperationException failure) { throw new ExceptionInInitializerError(failure); }
    }
    /** 复查原输入和完成条件的轻量函数，不复制业务状态。 */
    private final BooleanSupplier workAvailable;
    /** 构造时固定的自旋或自适应等待方式。 */
    private final OwnerWaitStrategy waitStrategy;
    /** 完成生产者共用的通知单元，与 Owner 退避计数分离。 */
    private final Wakeup wakeup = new Wakeup();
    /** bindOwner 登记的实际 Owner 线程，作为 unpark 目标。 */
    private Thread owner;
    /** 自上次实际进展以来的退避轮次，只由 Owner 修改。 */
    private int idleCount;
    /** 下一次自适应休眠基准，初始一微秒，逐步上限一百微秒。 */
    private long parkNanos = 1_000;

    /**
     * 要求工作探测函数非空，并按 JVM 属性选择等待策略。
     * @param workAvailable 判断已有输入或完成状态能否产生进展的函数
     */
    public OwnerIdleStrategy(BooleanSupplier workAvailable) {
        this(workAvailable, CoreRuntimeConfiguration.standalone().ownerWaitStrategy());
    }

    /**
     * 显式注入工作探测函数与等待策略，便于测试精确控制退避过程；两者都不能为 null。
     * @param workAvailable 判断已有输入或完成状态能否产生进展的函数
     * @param waitStrategy 显式选择的等待模式
     */
    public OwnerIdleStrategy(BooleanSupplier workAvailable, OwnerWaitStrategy waitStrategy) {
        this.workAvailable = Objects.requireNonNull(workAvailable);
        this.waitStrategy = Objects.requireNonNull(waitStrategy);
    }

    /**
     * 把当前线程登记为唤醒目标，必须在 Owner 开始循环后调用。
     */
    public void bindOwner() { owner = Thread.currentThread(); }

    /**
     * 自适应模式下，只有已请求休眠才尝试原子取走通知位并 unpark；通知仅提示重新检查事实，不证明命令已经完成。
     */
    public void signal() {
        if (waitStrategy != OwnerWaitStrategy.BUSY_SPIN && wakeup.requested != 0 && PARK_REQUESTED.compareAndSet(wakeup, 1L, 0L)) LockSupport.unpark(owner);
    }

    /**
     * 有实际进展就重置退避；否则默认自旋，自适应模式先自旋一百轮、让步十轮，再登记通知并复查工作后有限休眠。在途命令只缩短休眠上限，不算进展。
     * @param work 上一轮实际进展，正数才重置退避
     * @param pendingCommands 是否还有在途条目，只把休眠上限限制到十微秒
     */
    public void idle(int work, boolean pendingCommands) {
        // 在途数量不是进展。先短暂自旋，再用完成通知唤醒；有界超时同时覆盖
        // 控制阶段和失败检查，不以无限轮询掩盖通知缺失。
        if (work > 0) {
            if (idleCount != 0) { idleCount = 0; parkNanos = 1_000; }
            return;
        }
        if (waitStrategy == OwnerWaitStrategy.BUSY_SPIN) {
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

    /**
     * 生产者与 Owner 共用的通知单元；填充字段把通知位与其他可变字段隔开，但不构成硬性缓存行布局保证。
     */
    private static final class Wakeup {
        /**
         * p01：通知位之前的显式填充字段 01，不存放业务数据，代码不读取或修改它。
         * p02：通知位之前的显式填充字段 02，不存放业务数据，代码不读取或修改它。
         * p03：通知位之前的显式填充字段 03，不存放业务数据，代码不读取或修改它。
         * p04：通知位之前的显式填充字段 04，不存放业务数据，代码不读取或修改它。
         * p05：通知位之前的显式填充字段 05，不存放业务数据，代码不读取或修改它。
         * p06：通知位之前的显式填充字段 06，不存放业务数据，代码不读取或修改它。
         * p07：通知位之前的显式填充字段 07，不存放业务数据，代码不读取或修改它。
         * p08：通知位之前的显式填充字段 08，不存放业务数据，代码不读取或修改它。
         * p09：通知位之前的显式填充字段 09，不存放业务数据，代码不读取或修改它。
         * p0a：通知位之前的显式填充字段 0a，不存放业务数据，代码不读取或修改它。
         * p0b：通知位之前的显式填充字段 0b，不存放业务数据，代码不读取或修改它。
         * p0c：通知位之前的显式填充字段 0c，不存放业务数据，代码不读取或修改它。
         * p0d：通知位之前的显式填充字段 0d，不存放业务数据，代码不读取或修改它。
         * p0e：通知位之前的显式填充字段 0e，不存放业务数据，代码不读取或修改它。
         * p0f：通知位之前的显式填充字段 0f，不存放业务数据，代码不读取或修改它。
         */
        @SuppressWarnings("unused")
        private long p01, p02, p03, p04, p05, p06, p07, p08, p09, p0a, p0b, p0c, p0d, p0e, p0f;
        /** 跨线程可见的休眠通知位，1 表示 Owner 请求唤醒，0 表示当前没有请求。 */
        private volatile long requested;
        /**
         * p11：通知位之后的显式填充字段 11，不存放业务数据，代码不读取或修改它。
         * p12：通知位之后的显式填充字段 12，不存放业务数据，代码不读取或修改它。
         * p13：通知位之后的显式填充字段 13，不存放业务数据，代码不读取或修改它。
         * p14：通知位之后的显式填充字段 14，不存放业务数据，代码不读取或修改它。
         * p15：通知位之后的显式填充字段 15，不存放业务数据，代码不读取或修改它。
         * p16：通知位之后的显式填充字段 16，不存放业务数据，代码不读取或修改它。
         * p17：通知位之后的显式填充字段 17，不存放业务数据，代码不读取或修改它。
         * p18：通知位之后的显式填充字段 18，不存放业务数据，代码不读取或修改它。
         * p19：通知位之后的显式填充字段 19，不存放业务数据，代码不读取或修改它。
         * p1a：通知位之后的显式填充字段 1a，不存放业务数据，代码不读取或修改它。
         * p1b：通知位之后的显式填充字段 1b，不存放业务数据，代码不读取或修改它。
         * p1c：通知位之后的显式填充字段 1c，不存放业务数据，代码不读取或修改它。
         * p1d：通知位之后的显式填充字段 1d，不存放业务数据，代码不读取或修改它。
         * p1e：通知位之后的显式填充字段 1e，不存放业务数据，代码不读取或修改它。
         * p1f：通知位之后的显式填充字段 1f，不存放业务数据，代码不读取或修改它。
         */
        @SuppressWarnings("unused")
        private long p11, p12, p13, p14, p15, p16, p17, p18, p19, p1a, p1b, p1c, p1d, p1e, p1f;
    }
}
