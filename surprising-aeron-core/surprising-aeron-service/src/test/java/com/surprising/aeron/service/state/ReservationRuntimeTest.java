package com.surprising.aeron.service.state;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;

class ReservationRuntimeTest {
    @Test void unchangedAmountsReuseTheImmutableVersionAndChangesPreserveTheOldOne() {
        var original = CoreStateTestFixtures.reservation(11, 7, 0, 100);
        assertThat(original.release(0)).isSameAs(original);
        assertThat(original.consume(0)).isSameAs(original);
        assertThat(original.withRemainingUnits(100)).isSameAs(original);
        var released = original.release(30);
        var consumed = released.consume(20);
        assertThat(original.reservedUnits()).isEqualTo(100);
        assertThat(released.reservedUnits()).isEqualTo(70);
        assertThat(consumed.reservedUnits()).isEqualTo(50);
        assertThat(consumed.release(0)).isSameAs(consumed);
        assertThatThrownBy(() -> original.release(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> original.consume(101)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> consumed.withRemainingUnits(51)).isInstanceOf(IllegalArgumentException.class);
    }
}
