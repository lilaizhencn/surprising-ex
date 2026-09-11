package com.surprising.aeron.service.state;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class CaptureBufferReuseTest {
    @Test
    void clientOrderSlotsOverwritePresenceAcrossShorterReuse() {
        var captures = new TradingRuntimeState.LaneClientOrderCaptures();
        for (int i = 1; i <= 20; i++) captures.add(7, i, (long) i);
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

    @Test
    void balancesSkipEmptyClearButInvalidateOldIndexAndAfterState() {
        var captures = new TradingRuntimeState.LaneBalancePatches();
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
