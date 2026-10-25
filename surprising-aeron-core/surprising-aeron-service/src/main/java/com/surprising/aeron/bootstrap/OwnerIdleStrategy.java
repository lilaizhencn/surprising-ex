package com.surprising.aeron.bootstrap;

import java.util.function.BooleanSupplier;

/**
 * Owner 线程的等待策略：固定为持续忙等自旋（BUSY_SPIN）。
 */
public final class OwnerIdleStrategy {

    public OwnerIdleStrategy() {
    }

    public OwnerIdleStrategy(BooleanSupplier workAvailable) {
    }

    public void bindOwner() {
    }

    public void signal() {
    }

    public void idle(int work, boolean pendingCommands) {
        if (work <= 0) {
            Thread.onSpinWait();
        }
    }
}
