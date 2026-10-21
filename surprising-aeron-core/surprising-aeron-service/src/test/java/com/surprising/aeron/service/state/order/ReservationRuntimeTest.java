package com.surprising.aeron.service.state.order;

import com.surprising.aeron.service.state.CoreStateTestFixtures;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * 冻结记录非原位更新的结果测试：零变化复用原对象，释放或消耗返回后状态而不改原值，并检查非法数量不能增加或透支剩余额度。
 */
class ReservationRuntimeTest {
    /**
     * 对一百单位冻结依次释放三十、消耗二十，原值仍为一百，各后状态分别剩七十和五十；零操作复用对象，负数、超额和逆向增加剩余额度应拒绝。
     */
    @Test void unchangedAmountsReuseTheImmutableVersionAndChangesPreserveTheOldOne() {
        // 原来一百单位的冻结记录，用作不应被非原位函数修改的前值。
        var original = CoreStateTestFixtures.reservation(11, 7, 0, 100);
        assertThat(original.release(0)).isSameAs(original);
        assertThat(original.consume(0)).isSameAs(original);
        assertThat(original.withRemainingUnits(100)).isSameAs(original);
        // 释放三十后生成的新记录，剩余额度应为七十。
        var released = original.release(30);
        // 在已释放后状态再消耗二十得到的新记录，剩余额度应为五十。
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
