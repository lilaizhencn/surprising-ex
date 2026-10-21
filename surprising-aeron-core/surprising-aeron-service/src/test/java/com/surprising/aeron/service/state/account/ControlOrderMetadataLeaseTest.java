package com.surprising.aeron.service.state.account;

import com.surprising.aeron.protocol.*;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreOrderType;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreTimeInForce;
import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.protocol.ReservationKind;
import com.surprising.aeron.service.state.CoreStateTestFixtures;
import com.surprising.aeron.service.state.TradingRuntimeState;
import com.surprising.aeron.service.state.admission.ResolvedPlaceOrder;
import com.surprising.aeron.service.state.order.OrderRuntime;
import com.surprising.aeron.service.state.order.RuntimeOrderStateTransitions;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.assertj.core.api.Assertions.*;

/**
 * 检验池化订单权威对象、已冻结发布值和命令回滚前值的租约隔离；用实际账户工作线程制造先后更新，避免只用普通副本比较代替交接验证。
 */
@Timeout(15)
class ControlOrderMetadataLeaseTest {
    /**
     * Lane 创建池化订单并完成一次元数据回执，再经 Lane 查询派发故意修改活对象，Owner 收集较早回执时仍应得到原时间和日志位置的独立冻结值。
     */
    @Test void metadataKeepsPooledAuthorityAndPublishesAnIndependentFrozenValue() throws Exception {
        try (/* 用户已入金的独立交易运行时，结束时自动关闭工作线程。 */ var state = fundedRuntime()) {
            state.startAccountLanes();
            // 把 Lane 创建的实际权威订单引用交给测试线程核对的原子槽。
            var authority = new AtomicReference<OrderRuntime>();
            // 用户七实际所属的账户 Lane 编号。
            int laneId = state.accountLaneId(7);
            state.dispatchControlLanes(1L << laneId, /* 控制回调提供的 Lane 编号，本用例按固定用户及订单执行，不读取此参数。 */ ignored -> {
                RuntimeOrderStateTransitions.placePrepared(state, 7, intent(11), new UUID(1, 11), 200, 91, 5, 3);
                authority.set(state.order(11));
                return null;
            });
            await(state, state::pollControlLanes);
            // 保存本次 Lane 提交元数据及冻结发布值的命令回执。
            var event = state.dispatchLaneMutation(1, List.of(7L), List.of(11L), 100, 200);
            await(state, () -> state.laneCommitComplete(event));
            // 确认较晚的 Lane 元数据修改已经执行完的门闩。
            var later = new CountDownLatch(1);
            assertThat(state.dispatchAccountQuery(1L << laneId, /* 查询派发回调实际持有的账户 Lane，本测试故意在其所有权内更新活订单。 */ lane -> {
                // 仍被 Lane 保存的原池化权威订单，较晚修改不应改变较早发布值。
                var live = lane.orders.get(11);
                assertThat(live).isSameAs(authority.get());
                assertThatThrownBy(() -> live.prepareReservation(ReservationKind.DERIVATIVE_MARGIN, 3, 200))
                        .isInstanceOf(IllegalStateException.class).hasMessageContaining("already prepared");
                live.applyCommitMetadataInPlace(101, 201);
                later.countDown();
            })).isTrue();
            assertThat(later.await(3, TimeUnit.SECONDS)).isTrue();
            state.releaseLaneCommit(event);
            assertThat(state.orderChanges().get(11).updatedAtEpochMillis()).isEqualTo(100);
            assertThat(state.orderChanges().get(11).clusterPosition()).isEqualTo(200);
            assertThat(state.orderChanges().get(11)).isNotSameAs(authority.get());
        }
    }

    /**
     * 对已有池化订单更新提交元数据后执行真实异步命令回滚，检查权威对象身份、原零时间及日志位置、资金与预留都恢复。
     */
    @Test void metadataRollbackRestoresAnExistingOrdersPreCommandFields() {
        try (/* 用户已入金的独立交易运行时，结束时自动关闭工作线程。 */ var state = fundedRuntime()) {
            state.startAccountLanes();
            // 把 Lane 创建的实际权威订单引用交给测试线程核对的原子槽。
            var authority = new AtomicReference<OrderRuntime>();
            state.dispatchControlLanes(state.topology().accountLaneMask(7), /* 控制回调提供的 Lane 编号，本用例按固定用户及订单执行，不读取此参数。 */ ignored -> {
                RuntimeOrderStateTransitions.placePrepared(state, 7, intent(11), new UUID(1, 11), 200, 91, 5, 3);
                authority.set(state.order(11)); return null;
            });
            await(state, state::pollControlLanes);
            // 元数据命令前的产品修订号，真实回滚以它恢复命令边界。
            long revision = state.revision();
            state.clearChangedKeys();
            state.enterAsynchronousCommandScope();
            try {
                // 保存本次 Lane 提交元数据及冻结发布值的命令回执。
                var event = state.dispatchLaneMutation(1, List.of(7L), List.of(11L), 100, 200);
                await(state, () -> state.laneCommitComplete(event));
                state.releaseLaneCommit(event);
                assertThat(state.orderChanges().get(11).clusterPosition()).isEqualTo(200);
                await(state, state.beginCommandRollback(revision, 1));
                state.dispatchControlLanes(state.topology().accountLaneMask(7), /* 控制回调提供的 Lane 编号，本用例按固定用户及订单执行，不读取此参数。 */ ignored -> {
                    // 回滚完成后在所属 Lane 重新读取的原权威订单。
                    var order = state.order(11);
                    assertThat(order).isSameAs(authority.get());
                    assertThatThrownBy(() -> order.prepareReservation(ReservationKind.DERIVATIVE_MARGIN, 3, 200))
                            .isInstanceOf(IllegalStateException.class);
                    assertThat(order.createdAtEpochMillis()).isZero();
                    assertThat(order.updatedAtEpochMillis()).isZero();
                    assertThat(order.clusterPosition()).isZero();
                    assertThat(state.balance(7, 3).availableUnits()).isEqualTo(800);
                    assertThat(state.balance(7, 3).lockedUnits()).isEqualTo(200);
                    assertThat(state.reservation(11).reservedUnits()).isEqualTo(200);
                    return null;
                });
                await(state, state::pollControlLanes);
            } finally { state.exitAsynchronousCommandScope(); }
        }
    }

    /**
     * 准备用户七及资产三可用一千的独立运行时，清空初始化变化，使测试命令捕获只覆盖本次业务。
     */
    private static TradingRuntimeState fundedRuntime() {
        // 用户已入金的独立交易运行时，结束时自动关闭工作线程。
        var state = new TradingRuntimeState();
        state.putUser(new UserRuntime(7)); state.putBalance(new BalanceRuntime(7, 3, 1000, 0));
        state.clearChangedKeys();
        return state;
    }

    /**
     * 构造合约一、买入两步的已解析限价下单意图，带衍生品保证金预留类型供控制任务建立订单。
     * @param id 测试订单编号，同时用于固定的客户号文本
     */
    private static ResolvedPlaceOrder intent(long id) {
        // 构造已解析意图所用的限价下单业务命令。
        var command = new PlaceOrderCommand(id, "1", CoreOrderSide.BUY, 100, 2, false, CoreMarginMode.CROSS,
                CorePositionSide.NET, CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "control-" + id);
        return new ResolvedPlaceOrder(command, CoreStateTestFixtures.runtimeInstrument(), 5,
                100, 100, 100, ReservationKind.DERIVATIVE_MARGIN, "USDT", 0, 0);
    }

    /**
     * 最多等待三秒，每次检查账户工作线程健康并推进指定完成条件，未满足则自旋直到超时。
     * @param state 用于检查账户工作线程健康的运行时
     * @param done 返回指定事件是否已经完成的推进条件
     */
    private static void await(TradingRuntimeState state, BooleanSupplier done) {
        // 等待指定租约或回执完成的三秒单调时钟截止时间。
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!done.getAsBoolean()) {
            state.assertAccountLanesHealthy();
            if (System.nanoTime() >= deadline) throw new AssertionError("control lease test timed out");
            Thread.onSpinWait();
        }
    }
}
