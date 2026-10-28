package com.surprising.aeron.service.order.state;

import com.surprising.aeron.service.core.CoreStateTestFixtures;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * 预留释放的独立前值、累计消耗的单调性和总额边界。
 */
class ReservationRuntimeTest {
    /**
     * 对一百单位预留释放三十后原位消耗二十，原记录仍为一百，后值剩五十；负释放、消耗回退及超总额应拒绝。
     */
    @Test void releasePreservesTheOldValueAndConsumptionChecksItsCumulativeLimit() {
        var original = CoreStateTestFixtures.reservation(11, 7, 0, 100);
        assertThat(original.release(0)).isSameAs(original);
        var released = original.release(30);
        released.setConsumedInPlace(20);
        assertThat(original.reservedUnits()).isEqualTo(100);
        assertThat(released.reservedUnits()).isEqualTo(50);
        assertThatThrownBy(() -> original.release(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> released.setConsumedInPlace(19)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> released.setConsumedInPlace(71)).isInstanceOf(IllegalArgumentException.class);
    }
}
