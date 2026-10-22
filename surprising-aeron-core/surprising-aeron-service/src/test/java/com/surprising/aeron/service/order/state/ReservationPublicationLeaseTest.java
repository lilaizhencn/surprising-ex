package com.surprising.aeron.service.order.state;

import com.surprising.aeron.protocol.ReservationKind;
import com.surprising.aeron.service.core.change.RuntimeReservationChangeBuffer;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * 冻结发布槽的生命周期测试。变更缓冲冻结前可跟随 Lane 活值，冻结后使用独立后值；消费者 clear 后槽才复用，普通权威冻结不能被当作可覆盖发布槽。
 */
class ReservationPublicationLeaseTest {
    /**
     * 先改 Lane 冻结再 freeze 捕获八十单位，之后 Lane 改为七十不影响发布值；clear 后另一订单复用同一发布对象，旧引用只用于验证复用，不再代表旧业务。
     */
    @Test void publicationFollowsLaneUntilFreezeAndReusesItsCellOnlyAfterConsumerClear() {
        // Lane 持有并可继续原位消耗的源冻结值。
        var source = reservation(11, 100);
        // 本测试的原冻结变更缓冲，负责捕获及回收自己的发布槽。
        var buffer = new RuntimeReservationChangeBuffer();
        buffer.putLive(11, source);
        source.consumeInPlace(20);
        buffer.freezeValues();
        // freeze 时捕获的发布值，Lane 后续变化不影响它，但 clear 结束租约后可被下一单覆盖。
        var frozen = buffer.get(11);
        source.consumeInPlace(10);
        assertThat(frozen.reservedUnits()).isEqualTo(80);
        assertThat(source.reservedUnits()).isEqualTo(70);
        assertThat(frozen).isNotSameAs(source);
        buffer.clear();
        assertThat(buffer.get(11)).isNull();
        buffer.putLive(12, reservation(12, 60));
        buffer.freezeValues();
        assertThat(buffer.get(12)).isSameAs(frozen);
        assertThat(buffer.get(12).orderId()).isEqualTo(12);
        assertThat(buffer.get(12).reservedUnits()).isEqualTo(60);
    }

    /**
     * 用普通权威冻结当发布复制目标应被拒绝，目标订单编号和金额不能被改写。
     */
    @Test void copyRejectsAnAuthoritativeReservationAsItsReuseTarget() {
        // Lane 持有并可继续原位消耗的源冻结值。
        var source = reservation(11, 100);
        // 普通可变权威冻结，不能作为发布槽复用目标。
        var authoritative = reservation(12, 60);
        assertThatThrownBy(() -> source.copyIntoPublicationSlot(authoritative))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(authoritative.orderId()).isEqualTo(12);
        assertThat(authoritative.reservedUnits()).isEqualTo(60);
    }

    /**
     * 为指定订单和总额创建用户 7 的保证金冻结，数量基数固定为两步。
     * @param id 本次测试冻结对应的订单编号
     * @param amount 初始总冻结金额，累计释放和消耗均从零开始
     */
    private static ReservationRuntime reservation(long id, long amount) {
        return new ReservationRuntime(id, 7, 0, ReservationKind.DERIVATIVE_MARGIN, 0, amount, 0, 0, 2);
    }
}
