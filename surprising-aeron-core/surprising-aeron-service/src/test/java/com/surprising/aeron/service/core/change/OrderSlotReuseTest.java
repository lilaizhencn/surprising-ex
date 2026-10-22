package com.surprising.aeron.service.core.change;

import com.surprising.aeron.model.CoreOrderStatus;
import com.surprising.aeron.protocol.*;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreOrderType;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreTimeInForce;
import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.protocol.ReservationKind;
import com.surprising.aeron.service.account.AccountLaneState;
import com.surprising.aeron.service.core.CoreStateTestFixtures;
import com.surprising.aeron.service.order.admission.ResolvedPlaceOrder;
import com.surprising.aeron.service.order.state.ReservationRuntime;
import com.surprising.product.api.ProductLine;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * 核对池化订单及其预留只有跨过最终退休水位才可重置，独立值快照不受复用影响；同时验证订单命令槽随物理缓冲交接。这里不执行 Cluster 快照恢复。
 */
class OrderSlotReuseTest {
    /**
     * 创建指定编号、买入十步的已解析限价意图，带固定测试客户号及保证金预留参数。
     * @param id 测试订单编号，同时用于固定客户号
     */
    private static ResolvedPlaceOrder intent(long id) {
        // 用于构造已解析意图的限价下单业务命令。
        var order = new PlaceOrderCommand(id, "1", CoreOrderSide.BUY, 100, 10, false,
                CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "client-" + id);
        return new ResolvedPlaceOrder(order, CoreStateTestFixtures.runtimeInstrument(), 0, 100, 100, 100,
                ReservationKind.DERIVATIVE_MARGIN, "USDT", 0, 0);
    }
    /**
     * 把终态订单归还后，分别在退休水位前后申请新订单，核对只有达到边界才重用旧权威对象，数量、费用及成交值字段已重置，独立旧值仍稳定。
     */
    @Test void authoritativeOrderWaitsForResponseRetirementBeforeReinitialization() {
        // 当前线程持有的独立账户 Lane，直接控制回收水位。
        var lane = new AccountLaneState(0, 256);
        lane.bindOwner();
        // 最初由 Lane 准备的可回收权威订单对象。
        var first = lane.preparedOrder(ProductLine.LINEAR_PERPETUAL, 7, intent(1), new UUID(1, 1), 0, 100, 200, 0);
        first.applyFillInPlace(10, 0, 3, CoreOrderStatus.FILLED, 2, 110, 210);
        first.executionValue(5, 6);
        // 从原订单生成的独立普通值快照，不是命令复用槽。
        var stable = first.snapshot();
        // 复制原订单字段的独立命令结果槽，在源权威订单重用后仍受自己的租约保护。
        var response = first.copyIntoCommandSlot(null);
        assertThat(first.retireForReuse(4)).isTrue();
        lane.returnRetiredOrder(first);
        // 退休水位尚未达到时为新命令准备的另一订单，不能占用原订单对象。
        var beforeFence = lane.preparedOrder(ProductLine.LINEAR_PERPETUAL, 8, intent(2), new UUID(1, 2), 0, 120, 220, 3);
        assertThat(beforeFence).isNotSameAs(first);
        assertThat(first.orderId()).isEqualTo(1);
        // 达到最终退休水位后重新准备的新订单，应与原对象同身份但字段已重置。
        var reused = lane.preparedOrder(ProductLine.LINEAR_PERPETUAL, 9, intent(3), new UUID(1, 3), 0, 130, 230, 4);
        assertThat(reused).isSameAs(first);
        assertThat(reused.orderId()).isEqualTo(3);
        assertThat(reused.userId()).isEqualTo(9);
        assertThat(reused.status()).isEqualTo(CoreOrderStatus.OPEN);
        assertThat(reused.remainingQuantitySteps()).isEqualTo(10);
        assertThat(reused.executedQuantitySteps()).isZero();
        assertThat(reused.cumulativeFeeUnits()).isZero();
        assertThat(reused.executedValueHigh()).isZero();
        assertThat(reused.executedValueLow()).isZero();
        assertThat(response.orderId()).isEqualTo(1);
        assertThat(stable.executedQuantitySteps()).isEqualTo(10);
        assertThatThrownBy(() -> reused.copyIntoCommandSlot(stable)).isInstanceOf(IllegalArgumentException.class);
    }
    /**
     * 让订单预留先消耗再释放，要求订单退休前不能重备预留；跨边界后可复用同一对象且旧业务字段清空，独立快照仍保存旧值。
     */
    @Test void reservationReuseWaitsForItsOrderFenceAndKeepsIndependentValuesStable() {
        // 当前线程持有的独立账户 Lane，直接控制回收水位。
        var lane = new AccountLaneState(0, 256);
        lane.bindOwner();
        // 最初由 Lane 准备的可回收权威订单对象。
        var first = lane.preparedOrder(ProductLine.LINEAR_PERPETUAL, 7, intent(1), new UUID(1, 1), 0, 100, 200, 0);
        // 与原订单绑定、生命周期跟随订单的预留权威对象。
        var reservation = first.prepareReservation(ReservationKind.DERIVATIVE_MARGIN, 3, 100);
        reservation.consumeInPlace(60);
        reservation.releaseInPlace(40);
        // 独立保存的预留业务值快照，原预留重用后仍保存消耗及释放结果。
        var retained = reservation.snapshot(); // 这是独立业务值副本，本用例没有运行集群快照场景。
        assertThatThrownBy(() -> first.prepareReservation(ReservationKind.DERIVATIVE_MARGIN, 4, 200))
                .isInstanceOf(IllegalStateException.class);
        first.applyFillInPlace(10, 0, 0, CoreOrderStatus.FILLED, 2, 110, 210);
        assertThat(first.retireForReuse(4)).isTrue();
        lane.returnRetiredOrder(first);
        assertThatThrownBy(() -> first.prepareReservation(ReservationKind.DERIVATIVE_MARGIN, 4, 200))
                .isInstanceOf(IllegalStateException.class);

        // 退休水位尚未达到时为新命令准备的另一订单，不能占用原订单对象。
        var beforeFence = lane.preparedOrder(ProductLine.LINEAR_PERPETUAL, 8, intent(2), new UUID(1, 2), 1, 120, 220, 3);
        assertThat(beforeFence.prepareReservation(ReservationKind.DERIVATIVE_MARGIN, 5, 70)).isNotSameAs(reservation);
        assertThat(reservation.orderId()).isEqualTo(1);
        assertThat(reservation.userId()).isEqualTo(7);
        assertThat(reservation.reservedUnits()).isZero();

        // 达到最终退休水位后重新准备的新订单，应与原对象同身份但字段已重置。
        var reused = lane.preparedOrder(ProductLine.LINEAR_PERPETUAL, 9, intent(3), new UUID(1, 3), 2, 130, 230, 4);
        // 跨订单退休边界后重新准备的预留，应复用原预留对象且没有旧字段。
        var next = reused.prepareReservation(ReservationKind.DERIVATIVE_MARGIN, 4, 200);
        assertThat(reused).isSameAs(first);
        assertThat(next).isSameAs(reservation);
        assertThat(next.orderId()).isEqualTo(3);
        assertThat(next.userId()).isEqualTo(9);
        assertThat(next.symbolId()).isEqualTo(2);
        assertThat(next.assetId()).isEqualTo(4);
        assertThat(next.kind()).isEqualTo(ReservationKind.DERIVATIVE_MARGIN);
        assertThat(next.totalReservedUnits()).isEqualTo(200);
        assertThat(next.releasedUnits()).isZero();
        assertThat(next.consumedUnits()).isZero();
        assertThat(next.orderQuantitySteps()).isEqualTo(10);
        assertThat(retained.orderId()).isEqualTo(1);
        assertThat(retained.assetId()).isEqualTo(3);
        assertThat(retained.kind()).isEqualTo(ReservationKind.DERIVATIVE_MARGIN);
        assertThat(retained.releasedUnits()).isEqualTo(40);
        assertThat(retained.consumedUnits()).isEqualTo(60);
        assertThatThrownBy(() -> ReservationRuntime.prepareForOrderSlot(retained, 4, 8, 0,
                ReservationKind.DERIVATIVE_MARGIN, 3, 100, 10)).isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * 先登记活订单引用，在 Lane 完成时冻结并交换物理缓冲；之后活订单与新 Lane 输出继续变化，不覆盖 Owner 较早值，消费者清空后旧命令槽可再次复用。
     */
    @Test void afterImagesFreezeAtLaneCompletionAndMoveWithPhysicalBuffers() {
        // Lane 本次订单输出缓冲，完成时冻结其活引用。
        var laneBuffer = new RuntimeOrderChangeBuffer();
        // Owner 接管物理存储后的独立消费缓冲。
        var ownerBuffer = new RuntimeOrderChangeBuffer();
        // 被 Lane 原位修改的活订单权威值。
        var source = CoreStateTestFixtures.order(1, 7, 0, 10);
        laneBuffer.putLive(1, source);
        source.applyFillInPlace(3, 7, 4, CoreOrderStatus.OPEN, 2, 110, 210);
        laneBuffer.freezeValues();
        ownerBuffer.swapWith(laneBuffer);
        // Owner 持有的较早命令值槽，租约结束清空后可重用同一对象。
        var image = ownerBuffer.get(1);
        source.applyFillInPlace(10, 0, 9, CoreOrderStatus.FILLED, 3, 120, 220);
        laneBuffer.putLive(1, source); laneBuffer.freezeValues();
        assertThat(image.executedQuantitySteps()).isEqualTo(3);
        assertThat(image.cumulativeFeeUnits()).isEqualTo(4);
        assertThat(laneBuffer.get(1).executedQuantitySteps()).isEqualTo(10);
        ownerBuffer.clear();
        assertThat(image.instrument()).isNull();
        assertThat(laneBuffer.get(1).instrument()).isNotNull();
        ownerBuffer.put(1, source);
        assertThat(ownerBuffer.get(1)).isSameAs(image);
    }
}
