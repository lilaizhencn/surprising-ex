package com.surprising.aeron.service.state.change;

import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.service.state.CoreStateTestFixtures;
import com.surprising.aeron.service.state.position.PositionRuntime;
import com.surprising.aeron.service.state.position.RuntimePositionIndexValue;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * 验证持仓独立发布槽、普通快照和 Lane 权威对象的边界；物理交接及扩容后只复用本缓冲的槽，清空使旧租约失效。
 */
class PositionPublicationReuseTest {
    /**
     * 创建带指定用户和方向数量的测试净仓持仓，其他金额使用固定场景参数。
     * @param user 测试持仓所属用户编号
     * @param quantity 带方向的测试数量步数
     */
    private static PositionRuntime position(long user, long quantity) {
        return new PositionRuntime(user, 0, 1, CoreMarginMode.CROSS, CorePositionSide.NET,
                CoreStateTestFixtures.runtimeInstrument(), quantity, 100, Math.abs(quantity) * 100, 0, 40);
    }

    /**
     * 复制持仓后交换 Lane 与 Owner 缓冲，继续修改权威值；较早发布值保持旧数量，消费清空后同物理发布槽可复用为新键，普通快照仍稳定。
     */
    @Test void laneCopiesStayStableAcrossHandoffAndReusableCellsFollowTheirPhysicalBuffer() {
        // 模拟 Lane 的持仓专用变化缓冲。
        var lane = new RuntimePositionChangeBuffer();
        // 模拟 Owner 接管后的持仓变化缓冲。
        var owner = new RuntimePositionChangeBuffer();
        // 测试最初的权威持仓值，后续直接原位更新。
        var first = position(7, 2);
        // 原持仓的独立普通值快照，不参与发布槽复用。
        var stable = first.snapshot();
        lane.put(1, first);
        // 初次复制进缓冲的持仓发布槽，消费结束前保持对应命令边界。
        var publication = lane.get(1);
        assertThat(publication).isNotSameAs(first);
        owner.swapWith(lane);
        first.applyInPlace(first.instrument(), 3, 110, 330, 9, 50);
        lane.put(1, first);
        assertThat(owner.get(1).signedQuantitySteps()).isEqualTo(2);
        assertThat(lane.get(1).signedQuantitySteps()).isEqualTo(3);
        owner.drainTo((/* 消费回调收到的持仓原语键。 */ key, /* 消费回调收到的较早持仓发布值。 */ value) -> assertThat(value.signedQuantitySteps()).isEqualTo(2));
        assertThat(publication.instrument()).isNull();
        owner.put(2, position(9, -4));
        assertThat(owner.get(2)).isSameAs(publication);
        assertThat(owner.get(2).userId()).isEqualTo(9);
        assertThat(owner.get(2).signedQuantitySteps()).isEqualTo(-4);
        assertThat(lane.get(1).realizedPnlUnits()).isEqualTo(9);
        assertThat(stable.signedQuantitySteps()).isEqualTo(2);
        assertThatThrownBy(() -> first.copyIntoPublicationSlot(stable)).isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * 增长到超过初始槽容量后反复更新同一键，要求其他键不变；清空后旧预计算事实不残留。
     */
    @Test void repeatedUpdatesAndGrowthDoNotOverwriteOtherPublishedKeys() {
        // 反复更新并测试增长的持仓发布缓冲。
        var values = new RuntimePositionChangeBuffer();
        for (/* 本轮创建的持仓键及构造用户编号。 */ int i = 1; i <= 33; i++) values.put(i, position(i, i));
        // 键五对应的复用发布槽，同键更新应保留对象身份但更新字段。
        var cell = values.get(5);
        values.put(5, position(5, -8));
        assertThat(values.get(5)).isSameAs(cell);
        assertThat(cell.signedQuantitySteps()).isEqualTo(-8);
        assertThat(values.get(33).signedQuantitySteps()).isEqualTo(33);
        values.putPrepared(5, new RuntimePositionIndexValue(5, "1", "USDT", CorePositionSide.NET, -8));
        values.clear();
        assertThat(cell.instrument()).isNull();
        values.put(1, position(99, 9));
        values.forEachIndexed((/* 消费回调收到的持仓原语键。 */ key, /* 消费回调收到的较早持仓发布值。 */ value, /* 当前遍历项是否带预计算事实，清空复用后应为 false。 */ prepared, /* 当前遍历项的预计算索引值，本断言只检查是否准备。 */ index) -> assertThat(prepared).isFalse());
    }
}
