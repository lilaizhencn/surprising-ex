package com.surprising.aeron.service.account;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * 核对捕获缓冲在长批次之后复用短批次时正确重置存在标记、后值捕获标记与代次；只检查这些协议，不测量分配或全链路性能。
 */
class CaptureBufferReuseTest {
    /**
     * 先扩容客户映射捕获缓冲，再清空并使用更短前缀，检查原先存在标记不会泄漏成新命令的前值。
     */
    @Test
    void clientOrderSlotsOverwritePresenceAcrossShorterReuse() {
        // 当前测试独立创建的客户映射或余额捕获缓冲。
        var captures = new LaneClientOrderCaptures();
        for (/* 首次扩容缓冲时逐项新增的正数客户键和订单编号。 */ int i = 1; i <= 20; i++) captures.add(7, i, (long) i);
        captures.clear();
        captures.clear();
        captures.add(8, 100, null);
        assertThat(captures.size()).isEqualTo(1);
        assertThat(captures.beforeOrderId(0)).isNull();
        assertThat(captures.contains(7, 1)).isFalse();
        captures.clear();
        captures.add(9, 101, 99L);
        assertThat(captures.beforeOrderId(0)).isEqualTo(99L);
    }

    /**
     * 检查空清理不换代次，非空清理使旧索引失效，新条目未捕获后值时拒绝读取，并模拟代次从 -1 绕到零后的重置。
     */
    @Test
    void balancesSkipEmptyClearButInvalidateOldIndexAndAfterState() {
        // 当前测试独立创建的客户映射或余额捕获缓冲。
        var captures = new LaneBalancePatches();
        // 空缓冲清理前的索引代次，空清理应保持它。
        int generation = captures.indexGeneration;
        captures.clear();
        assertThat(captures.indexGeneration).isEqualTo(generation);
        captures.add(7, 3, new BalanceRuntime(7, 3, 10, 0), 0);
        captures.after(7, 3, new BalanceRuntime(7, 3, 5, 5), 0);
        captures.clear();
        assertThat(captures.contains(7, 3)).isFalse();
        captures.add(8, 4, null, 0);
        assertThat(captures.before(0)).isNull();
        assertThatThrownBy(() -> captures.after(0)).isInstanceOf(IllegalStateException.class);
        captures.after(8, 4, null, 0);
        assertThat(captures.after(0)).isNull();
        captures.indexGeneration = -1;
        captures.clear();
        assertThat(captures.indexGeneration).isEqualTo(1);
        assertThat(captures.contains(8, 4)).isFalse();
    }
}
