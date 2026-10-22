package com.surprising.aeron.service.core.change;

import com.surprising.aeron.service.core.CoreStateTestFixtures;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * 检验业务值和预计算列对齐、已准备删除与未准备的区别、专用订单值冻结和回收；未使用第二列的测试只核对数组为空，不替代分配采样。
 */
class RuntimeIndexedChangeBufferTest {
    /**
     * 多轮以 Void 预计算类型写入并接管，反射核对未使用 prepared 列始终为空、源已交空，Owner 仍可读取全部值。
     */
    @Test void orderHandoffsDoNotAllocateUnusedPreparedColumns() throws Exception {
        // 模拟 Lane 输出的普通索引变化缓冲，未使用预计算列。
        var lane = new RuntimeIndexedChangeBuffer<String, Void>();
        // 接管模拟 Lane 输出的 Owner 变化视图。
        var owner = new OwnerIndexedChanges<String, Void>();
        // 读取第二列数组的反射字段，用于检查未使用预计算列时长度仍为零。
        var prepared = RuntimeIndexedChangeBuffer.class.getDeclaredField("prepared");
        prepared.setAccessible(true);
        for (/* 当前源缓冲交接和 Owner 清理的轮次。 */ int round = 0; round < 3; round++) {
            for (/* 本次填充或核对的连续业务键下标。 */ int i = 0; i < 100; i++) lane.put(i, "value");
            owner.adopt(0, lane);
            assertThat(lane.isEmpty()).isTrue();
            assertThat((Object[]) prepared.get(lane)).isEmpty();
            for (/* 本次填充或核对的连续业务键下标。 */ int i = 0; i < 100; i++) assertThat(owner.get(i)).isEqualTo("value");
            owner.clear();
        }
    }

    /**
     * 合并有预计算值、明确删除及未准备三种情况，再覆盖一个键，核对标记与 null 的区别没有丢失。
     */
    @Test void coalescesStateAndPreparedIndexWithoutLosingDeletionOrFallback() {
        // 准备交给另一缓冲的业务变化源。
        var source = new RuntimeIndexedChangeBuffer<String, String>();
        // 接收业务变化和预计算事实的独立目标缓冲。
        var target = new RuntimeIndexedChangeBuffer<String, String>();
        source.put(1, "open"); source.putPrepared(1, "active");
        source.put(2, "closed"); source.putPrepared(2, null);
        source.put(3, "control");
        source.drainIndexedTo(target);
        assertThat(source.isEmpty()).isTrue();
        target.putIndexed(1, "filled", true, null);
        // 记录各键值、准备标记及索引结果的断言用 Map。
        var result = new java.util.HashMap<Long, String>();
        target.forEachIndexed((/* 回调接收的业务变化原语键。 */ key, /* 回调接收的当前业务后值。 */ value, /* 本键是否已经准备预计算事实，true 且结果为 null 表示明确空值。 */ present, /* 回调收到的预计算结果，需要结合 present 标记区分尚未准备与明确空值。 */ prepared) ->
                result.put(key, value + ":" + present + ":" + prepared));
        assertThat(result).containsEntry(1L, "filled:true:null").containsEntry(2L, "closed:true:null")
                .containsEntry(3L, "control:false:null");
        assertThat(target.size()).isEqualTo(3);
    }

    /**
     * 拒绝源目标相同的排出，并让两个订单专用缓冲逐项交接；源命令槽清空后，目标应持有自己的独立订单值。
     */
    @Test void directDrainKeepsFrozenOrderValuesAfterReleasingTheirSourceCells() {
        // 准备交给另一缓冲的业务变化源。
        var source = new RuntimeOrderChangeBuffer();
        // 接收业务变化和预计算事实的独立目标缓冲。
        var target = new RuntimeOrderChangeBuffer();
        source.put(11, CoreStateTestFixtures.order(11, 7, 0, 10));
        // 源订单缓冲命令槽的借用引用，源清理后其业务引用应被清空。
        var borrowed = source.get(11);
        assertThatThrownBy(() -> source.drainIndexedTo(source)).isInstanceOf(IllegalArgumentException.class);
        assertThat(source.get(11)).isSameAs(borrowed);
        source.drainIndexedTo(target);
        assertThat(source.isEmpty()).isTrue();
        assertThat(borrowed.instrument()).isNull();
        assertThat(target.get(11).orderId()).isEqualTo(11);
        assertThat(target.get(11).instrument()).isNotNull();
        assertThat(target.get(11)).isNotSameAs(borrowed);
    }

    /**
     * 先扩容并保存多种预计算值，清空后检查引用释放，再填新键确认旧标记不泄漏。
     */
    @Test void reuseAndGrowthDoNotRetainPreparedValues() throws Exception {
        // 检验扩容及跨轮次清理的索引变化缓冲。
        var buffer = new RuntimeIndexedChangeBuffer<String, String>();
        for (/* 本次填充或核对的连续业务键下标。 */ int i = 0; i < 100; i++) { buffer.put(i, "value"); buffer.putPrepared(i, i % 3 == 0 ? null : "index"); }
        buffer.clear();
        // 反射读取预计算列，以断言清空后无保留引用。
        var field = RuntimeIndexedChangeBuffer.class.getDeclaredField("prepared"); field.setAccessible(true);
        assertThat((Object[]) field.get(buffer)).containsOnlyNulls();
        for (/* 本次填充或核对的连续业务键下标。 */ int i = 0; i < 100; i++) buffer.put(1000 + i, "next");
        buffer.forEachIndexed((/* 回调接收的业务变化原语键。 */ key, /* 回调接收的当前业务后值。 */ value, /* 本键是否已经准备预计算事实，true 且结果为 null 表示明确空值。 */ present, /* 回调收到的预计算结果，需要结合 present 标记区分尚未准备与明确空值。 */ prepared) -> {
            assertThat(present).isFalse(); assertThat(prepared).isNull();
        });
        buffer.clear();
        assertThat(buffer.isEmpty()).isTrue();
        assertThat((Object[]) field.get(buffer)).containsOnlyNulls();
    }
}
