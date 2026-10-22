package com.surprising.aeron.service.core.change;

import com.surprising.aeron.service.core.CoreStateTestFixtures;
import com.surprising.aeron.service.order.state.OrderRuntime;
import java.util.HashMap;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 核对 Owner 接管不同 Lane、合并同 Lane 及直接更新时的最后值语义和源租约释放；指定键数组身份检查证明物理存储交接，不宣称整链路零分配。
 */
class OwnerIndexedChangesTest {
    /**
     * 把已接管 Lane 值借作直接更新输入，要求先复制新值再清源，另一订单也必须保留，随后 null 删除只影响目标键。
     */
    @Test void directUpdateFreezesABorrowedLaneCellBeforeItsSourceIsReleased() {
        // 当前测试的 Owner 有序变化视图，订单场景使用专用冻结直接缓冲。
        var owner = new OwnerIndexedChanges<OrderRuntime, Void>(new RuntimeOrderChangeBuffer());
        // 模拟一个 Lane 输出的变化缓冲。
        var lane = new RuntimeOrderChangeBuffer();
        lane.put(11, CoreStateTestFixtures.order(11, 7, 0, 2));
        lane.put(12, CoreStateTestFixtures.order(12, 8, 0, 3));
        owner.adopt(0, lane);
        // Owner 接管后借出的旧订单命令槽，合并源清理后不得再作为有效新值使用。
        OrderRuntime borrowed = owner.get(11);
        owner.put(11, borrowed);
        assertThat(owner.get(11).orderId()).isEqualTo(11);
        assertThat(owner.get(11).quantitySteps()).isEqualTo(2);
        assertThat(owner.get(12).orderId()).isEqualTo(12);
        assertThat(owner.get(12).quantitySteps()).isEqualTo(3);
        assertThat(owner.get(11)).isNotSameAs(borrowed);
        owner.put(11, null);
        assertThat(owner.get(11)).isNull();
        assertThat(owner.get(12).quantitySteps()).isEqualTo(3);
    }

    /**
     * 反射确认源键数组被 Owner 接管，源空缓冲可立即用于下一命令；旧 Owner 值及预计算事实不被后续源更新覆盖。
     */
    @Test void laneStorageMovesWithoutCopyAndCanBeReusedBeforeOwnerPublication() throws Exception {
        // 当前测试的 Owner 有序变化视图，订单场景使用专用冻结直接缓冲。
        var owner = new OwnerIndexedChanges<String, String>();
        // 第一条模拟 Lane 输出缓冲。
        var first = new RuntimeIndexedChangeBuffer<String, String>();
        // 第二条模拟 Lane 输出缓冲。
        var second = new RuntimeIndexedChangeBuffer<String, String>();
        first.putIndexed(1, "a", true, "index-a");
        second.putIndexed(2, "b", true, "index-b");
        // 读取基础连续键数组的反射字段。
        var keys = RuntimeChangeBuffer.class.getDeclaredField("keys"); keys.setAccessible(true);
        // 交接前第一条 Lane 的实际键数组对象。
        Object originalKeys = keys.get(first);
        owner.adopt(0, first); owner.adopt(3, second);
        assertThat(first.isEmpty()).isTrue(); assertThat(second.isEmpty()).isTrue();
        // 读取 Owner 保留物理缓冲数组的反射字段。
        var lanes = OwnerIndexedChanges.class.getDeclaredField("lanes"); lanes.setAccessible(true);
        assertThat(keys.get(((Object[])lanes.get(owner))[0])).isSameAs(originalKeys);
        first.put(1, "next-command"); second.clear();
        assertThat(owner.get(1)).isEqualTo("a");
        assertThat(owner.toArray()).containsExactly(1, 2);
        // 收集已准备索引事实的独立断言 Map。
        var prepared = new HashMap<Long, String>();
        owner.forEachIndexed((/* 内部遍历回调接收的业务键。 */ id, /* 内部遍历回调接收的业务后值，null 也必须传播。 */ value, /* 当前键是否带有预计算事实的标记。 */ flag, /* 回调收到的预计算事实值，null 可为已准备删除或尚未准备。 */ index) -> { assertThat(flag).isTrue(); prepared.put(id, index); });
        assertThat(prepared).containsEntry(1L,"index-a").containsEntry(2L,"index-b");
        owner.clear(); assertThat(owner.isEmpty()).isTrue();
        assertThat(first.get(1)).isEqualTo("next-command");
    }
    /**
     * 同一 Lane 连续覆盖相同键，再由 Owner 直接删除，要求旧预计算事实失效，后续新 Lane 结果可再次带入新事实。
     */
    @Test void sameLaneAndOwnerControlUpdatesKeepLastValueAndInvalidateOldPreparedIndex() {
        // 当前测试的 Owner 有序变化视图，订单场景使用专用冻结直接缓冲。
        var owner = new OwnerIndexedChanges<String, String>();
        // 模拟一个 Lane 输出的变化缓冲。
        var lane = new RuntimeIndexedChangeBuffer<String, String>();
        lane.putIndexed(9, "old", true, "old-index"); owner.adopt(2, lane);
        lane.putIndexed(9, "new", true, "new-index"); owner.adopt(2, lane);
        assertThat(owner.toArray()).containsExactly(9);
        assertThat(owner.get(9)).isEqualTo("new");
        owner.put(9, null);
        owner.forEachIndexed((/* 内部遍历回调接收的业务键。 */ id, /* 内部遍历回调接收的业务后值，null 也必须传播。 */ value, /* 当前键是否带有预计算事实的标记。 */ flag, /* 回调收到的预计算事实值，null 可为已准备删除或尚未准备。 */ index) -> { assertThat(value).isNull(); assertThat(flag).isFalse(); assertThat(index).isNull(); });
        lane.putIndexed(9, "after-control", true, "last-index"); owner.adopt(2,lane);
        assertThat(owner.get(9)).isEqualTo("after-control");
        owner.forEachIndexed((/* 内部遍历回调接收的业务键。 */ id, /* 内部遍历回调接收的业务后值，null 也必须传播。 */ value, /* 当前键是否带有预计算事实的标记。 */ flag, /* 回调收到的预计算事实值，null 可为已准备删除或尚未准备。 */ index) -> { assertThat(flag).isTrue(); assertThat(index).isEqualTo("last-index"); });
    }

    /**
     * 消费不同 Lane 的保留值并清空 Owner 视图，之后直接缓冲仍可重新写入和读取。
     */
    @Test void drainConsumesDirectAndLaneValuesAndClearsRetainedReferences() {
        // 当前测试的 Owner 有序变化视图，订单场景使用专用冻结直接缓冲。
        var owner = new OwnerIndexedChanges<String, Void>();
        // 模拟一个 Lane 输出的变化缓冲。
        var lane = new RuntimeIndexedChangeBuffer<String, Void>();
        // 另一条模拟 Lane 输出缓冲，用于核对同时保留多个分区。
        var otherLane = new RuntimeIndexedChangeBuffer<String, Void>();
        lane.put(1, "lane");
        otherLane.put(2, "other-lane");
        owner.adopt(2, lane);
        owner.adopt(4, otherLane);
        // 消费者收集到的最终键值 Map。
        var values = new HashMap<Long, String>();

        owner.drainTo(values::put);

        assertThat(values).containsEntry(1L, "lane").containsEntry(2L, "other-lane");
        assertThat(owner.isEmpty()).isTrue();
        owner.put(3, "reused");
        assertThat(owner.get(3)).isEqualTo("reused");
    }
}
