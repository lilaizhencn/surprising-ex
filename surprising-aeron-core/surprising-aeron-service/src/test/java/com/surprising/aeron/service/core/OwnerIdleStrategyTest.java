package com.surprising.aeron.service.core;

import com.surprising.aeron.bootstrap.OwnerIdleStrategy;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * 验证 Owner 等待策略在固定 BUSY_SPIN 模式下的忙等行为。
 */
class OwnerIdleStrategyTest {

    @Test
    void busySpinIdleExecutesSafely() {
        var idle = new OwnerIdleStrategy(() -> false);
        assertThatNoException().isThrownBy(() -> {
            idle.bindOwner();
            idle.signal();
            for (int i = 0; i < 1_000; i++) {
                idle.idle(0, true);
                idle.idle(0, false);
            }
            idle.idle(1, false);
        });
    }
}
