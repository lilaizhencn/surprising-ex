package com.surprising.aeron.service.state.admission;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * 待完成预留序号索引的结构测试，覆盖单项 primitive 路径、删除首项后的成员提升、长期滑动窗口、大 long 键、重复成员和清理后复用。
 */
class PendingReservationSequenceIndexTest {
/**
 * 在一个序号加入一百个订单，反复删除首项并核对新首项等于附加集合第一遍历项；测试自身用 toArray 取参照，只验证提升结果，不测量对象分配。
 */
@Test
    void promotionPreservesSetOrderWithoutArrayMaterializationAcrossEveryRemoval() {
        // 本用例直接构造的预留序号索引，初始容量为 4，用小容量触发正常扩容和移除。
        var index = new PendingReservationTracker.PendingReservationSequenceIndex(4);
        for (/* 向同一命令序号加入的订单编号，依次为 1 到 100。 */ long order = 1; order <= 100; order++) index.add(19, order);
        while (index.containsKey(19)) {
            // 删除前首项表保存的订单编号，本轮必须删除它以触发提升。
            long first = index.firstOrderBySequence.get(19);
            // 当前首项之外的 primitive 集合，无附加成员时可为 null。
            var additional = index.additionalOrdersBySequence.get(19);
            // 删除首项后应提升的附加集合第一遍历项，没有附加成员时应变成 0。
            long expected = additional == null || additional.isEmpty() ? 0 : additional.toArray()[0];
            index.remove(19, first);
            assertThat(index.firstOrderBySequence.get(19)).isEqualTo(expected);
        }
        assertThat(index.additionalOrdersBySequence.isEmpty()).isTrue();
    }

/**
 * 只加入一个成员，核对序号、成员和导出数组，再删除并确认完全为空。
 */
@Test
    void pendingReservationSequenceIndexKeepsSingleOrderOnPrimitivePath() {
        // 本用例直接构造的预留序号索引，初始容量为 4，用小容量触发正常扩容和移除。
        PendingReservationTracker.PendingReservationSequenceIndex index =
                new PendingReservationTracker.PendingReservationSequenceIndex(4);

        index.add(101, 1_001);

        assertThat(index.containsKey(101)).isTrue();
        assertThat(index.contains(101, 1_001)).isTrue();
        assertThat(index.orderIds(101)).containsExactly(1_001);

        index.remove(101, 1_001);

        assertThat(index.isEmpty()).isTrue();
        assertThat(index.orderIds(101)).isEmpty();
    }

/**
 * 连续推进两万个序号并保留最近 256 项，检查旧项移除和当前前缀；再用 Long.MAX_VALUE 序号测试多项提升，最后清理并复用索引。
 */
@Test
    void pendingReservationSequenceRolloverPreservesTheActivePrefixAndLongKeys() {
        // 本用例直接构造的预留序号索引，初始容量为 4，用小容量触发正常扩容和移除。
        var index = new PendingReservationTracker.PendingReservationSequenceIndex(4);
        for (/* 滑动窗口当前加入的命令序号，用接近 Long.MAX_VALUE 的订单编号检查长整数身份保留。 */ long sequence = 1; sequence <= 20_000; sequence++) {
            index.add(sequence, Long.MAX_VALUE - sequence);
            if (sequence > 256) index.remove(sequence - 256, Long.MAX_VALUE - (sequence - 256));
            assertThat(index.firstOrderId(sequence)).isEqualTo(Long.MAX_VALUE - sequence);
            assertThat(index.firstOrderId(Math.max(1, sequence - 255)))
                    .isEqualTo(Long.MAX_VALUE - Math.max(1, sequence - 255));
            if (sequence > 256) assertThat(index.containsKey(sequence - 256)).isFalse();
        }
        index.add(Long.MAX_VALUE, 1);
        index.add(Long.MAX_VALUE, 2);
        index.remove(Long.MAX_VALUE, 1);
        assertThat(index.firstOrderId(Long.MAX_VALUE)).isEqualTo(2);
        index.clear();
        assertThat(index.isEmpty()).isTrue();
        assertThat(index.additionalOrdersBySequence.isEmpty()).isTrue();
        index.add(1, 11);
        assertThat(index.orderIds(1)).containsExactly(11);
    }

/**
 * 两个序号分别放多项和单项，重复成员应报错；逐项删除多项序号不影响另一序号，最后清理。
 */
@Test
    void pendingReservationSequenceIndexPromotesBatchOrdersWhenFirstCompletes() {
        // 本用例直接构造的预留序号索引，初始容量为 4，用小容量触发正常扩容和移除。
        PendingReservationTracker.PendingReservationSequenceIndex index =
                new PendingReservationTracker.PendingReservationSequenceIndex(4);
        index.add(101, 1_001);
        index.add(101, 1_002);
        index.add(101, 1_003);
        index.add(102, 2_001);

        assertThat(index.orderIds(101)).containsExactlyInAnyOrder(1_001, 1_002, 1_003);
        assertThatThrownBy(() -> index.add(101, 1_002))
                .isInstanceOf(IllegalStateException.class);

        index.remove(101, 1_001);
        assertThat(index.orderIds(101)).containsExactlyInAnyOrder(1_002, 1_003);
        index.remove(101, 1_002);
        index.remove(101, 1_003);

        assertThat(index.containsKey(101)).isFalse();
        assertThat(index.orderIds(102)).containsExactly(2_001);
        index.clear();
        assertThat(index.isEmpty()).isTrue();
    }
}
