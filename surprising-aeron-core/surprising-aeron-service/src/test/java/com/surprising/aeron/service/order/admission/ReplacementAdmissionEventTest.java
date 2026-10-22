package com.surprising.aeron.service.order.admission;

import com.surprising.aeron.model.*;
import com.surprising.aeron.model.AssetBalance;
import com.surprising.aeron.model.CoreOrderState;
import com.surprising.aeron.model.CoreOrderStatus;
import com.surprising.aeron.model.CoreRiskState;
import com.surprising.aeron.model.TradingCoreState;
import com.surprising.aeron.protocol.*;
import com.surprising.aeron.protocol.AmendOrderCommand;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreOrderType;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreRiskLimitBracket;
import com.surprising.aeron.protocol.CoreTimeInForce;
import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.protocol.RegisterInstrumentCommand;
import com.surprising.aeron.protocol.ReplaceOrderCommand;
import com.surprising.aeron.protocol.ReservationKind;
import com.surprising.aeron.service.account.AccountLaneState;
import com.surprising.aeron.service.account.AccountLaneTestAccess;
import com.surprising.aeron.service.account.CoreUserState;
import com.surprising.aeron.service.core.TradingAccountRuntime;
import com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.fee.FeePolicyBook;
import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.aeron.service.market.MarkPriceRuntime;
import com.surprising.aeron.service.order.state.OrderReservation;
import com.surprising.aeron.service.snapshot.state.RuntimeStateProjector;
import com.surprising.aeron.service.treasury.CoreTreasuryState;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.agrona.collections.Long2ObjectHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * 改单准入的真实账户 Lane 测试，为账户表的 get 加 Owner 读取守卫，验证慢目标 Lane、另一 Lane 可推进、提前回收拒绝及同一事件在成功和拒绝后复用。
 */
class ReplacementAdmissionEventTest {
    /**
     * 阻塞目标 Lane 并禁止测试 Owner 读取五张表的 get，检查未完成事件不可回收而另一 Lane 可工作；依次成功改单、缺失原单拒绝、完整替换成功，复用同一事件，最后核对原单状态和用户版本未改。
     */
    @Test void laneAloneReadsAccountTablesAndReusesItsAdmissionAfterRejection() throws Exception {
        // 测试永续合约 1，设定显式杠杆、持仓限额和风险档位。
        var instrument = CoreInstrument.from(ProductLine.LINEAR_PERPETUAL,
                new RegisterInstrumentCommand("1", ContractType.LINEAR_PERPETUAL.ordinal(),
                        "BTC", "USDT", "USDT", 1, 1, 1, 100_000, 50_000,
                        0, 0, 0, -1, 0, 10_000_000, 10_000, 0, 10_000,
                        List.of(new CoreRiskLimitBracket(1, 0, 10_000, 10_000_000, 100_000, 50_000))));
        // 用户 7 原有的 OPEN 限价买单 11，作为改单和替换对象。
        var order = new CoreOrderState(11, ProductLine.LINEAR_PERPETUAL, 7, "1", CoreOrderSide.BUY,
                100, 1, 0, 1, false, CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT,
                CoreTimeInForce.GTC, false, "original", new UUID(1, 1), 0, 0, CoreOrderStatus.OPEN, 1);
        // 原用户 7 的账户前值，含 800 可用、100 冻结及原订单保证金预留。
        var user = new CoreUserState(ProductLine.LINEAR_PERPETUAL, 7, 1,
                Map.of("USDT", new AssetBalance("USDT", 800, 100)),
                Map.of(11L, OrderReservation.create(11, "1", ReservationKind.DERIVATIVE_MARGIN, "USDT", 100, 1)),
                Map.of());
        // 把原用户、订单和币对组合的冷业务状态，投影后再启动账户 Lane。
        var before = new TradingCoreState(ProductLine.LINEAR_PERPETUAL, 1, Map.of(7L, user),
                Map.of(11L, order), Map.of("1", instrument), CoreRiskState.empty(), CoreTreasuryState.empty());
        // 该测试运行时的身份表，准入事件与决策上下文共用它。
        var identities = new RuntimeIdentityRegistry();
        try (/* 从原业务状态投影并启动账户 worker 的测试运行时，结束时关闭。 */ var runtime = RuntimeStateProjector.project(before, identities)) {
            // laneId 是用户 7 的实际账户分区编号，目标 worker 在此阻塞。
            // symbolId 是合约 1 的稠密编号，价格及准入上下文使用同一身份。
            int laneId = runtime.topology().accountLaneId(7), symbolId = identities.symbolId("1");
            // 用户 7 的实际账户 Lane，启动前在其五张表上安装 get 守卫。
            var lane = runtime.accountLane(laneId);
            // 安装守卫前取得的原用户引用，用来比较准入输出版本及最后保持的版本。
            var originalUser = lane.user(7);
            // 安装守卫前取得的原订单引用，用来比较原单版本及最后仍为 OPEN。
            var originalOrder = lane.order(11);
            // 当前测试线程，守卫禁止它在账户启动后直接执行表的 get。
            Thread owner = Thread.currentThread();
            guard(lane, "orders", agronaGuard(AccountLaneTestAccess.orders(lane), owner));
            guard(lane, "reservations", agronaGuard(AccountLaneTestAccess.reservations(lane), owner));
            guard(lane, "users", eclipseGuard(AccountLaneTestAccess.users(lane), owner));
            guard(lane, "positions", eclipseGuard(AccountLaneTestAccess.positions(lane), owner));
            guard(lane, "balances", eclipseGuard(AccountLaneTestAccess.balances(lane), owner));
            // 当前改单固定的币对、价格、费率表和账本结算事实。
            var decision = new CoreOrderDecisionResolver.Context(instrument, symbolId,
                    new MarkPriceRuntime(symbolId, instrument, 100, 1, 1_000),
                    FeePolicyBook.EMPTY, 1_001, runtime.treasury().settlementStatus(symbolId));
            runtime.startAccountLanes();
            // 确认目标账户 worker 已进入阻塞回调的门闩。
            var workerHeld = new CountDownLatch(1);
            // 放行目标 worker 的门闩，finally 中同样释放避免遗留阻塞。
            var releaseWorker = new CountDownLatch(1);
            runtime.submitAccountCommand(laneId, /* 目标 worker 回调传入的 Lane 参数，本回调只等待门闩，不读实体。 */ value -> {
                workerHeld.countDown();
                try { if (!releaseWorker.await(3, TimeUnit.SECONDS)) throw new AssertionError("Lane gate timed out"); }
                catch (/* 目标 worker 等待门闩期间的中断，包装为保留原因的断言失败。 */ InterruptedException failure) { throw new AssertionError(failure); }
            });
            assertThat(workerHeld.await(3, TimeUnit.SECONDS)).isTrue();
            try {
                // 第一笔改单的池化准入事件，未放行前不得回收，完成后保存新价格和原版本。
                var event = runtime.dispatchReplacementAdmission(2, 7, 11,
                        new AmendOrderCommand(11, 12, "new-client", 101L, 1L, CoreTimeInForce.GTC, false),
                        null, decision, 0, identities);
                assertThat(event.complete()).isFalse();
                assertThatThrownBy(() -> runtime.releaseReplacementAdmission(event))
                        .isInstanceOf(IllegalStateException.class).hasMessageContaining("incomplete");
                // 另一账户 Lane 任务的完成门闩，证明目标 Lane 阻塞不挡住它。
                var independent = new CountDownLatch(1);
                runtime.submitAccountCommand((laneId + 1) % runtime.topology().accountLaneCount(), /* 另一 worker 回调的 Lane 参数，回调只通知该 Lane 已正常运行。 */ value -> independent.countDown());
                assertThat(independent.await(3, TimeUnit.SECONDS)).isTrue();
                releaseWorker.countDown();
                await(runtime, event);
                assertThat(event.rejection()).isNull();
                assertThat(event.admission().originalOrderRevision()).isEqualTo(originalOrder.revision());
                assertThat(event.admission().userRevision()).isEqualTo(originalUser.revision());
                assertThat(event.admission().requiredReservationUnits()).isPositive();
                assertThat(event.admission().command().limitPriceTicks()).isEqualTo(101);
                assertThat(event.closeCapacityCancellations()).isEmpty();
                runtime.releaseReplacementAdmission(event);
                // 同一池化事件复用后查询不存在的原单 404，应产生业务拒绝且无准入结果。
                var retry = runtime.dispatchReplacementAdmission(3, 7, 404,
                        new AmendOrderCommand(404, 405, "missing", 101L, 1L, CoreTimeInForce.GTC, false),
                        null, decision, 0, identities);
                assertThat(retry).isSameAs(event);
                await(runtime, retry);
                assertThat(retry.rejection()).isInstanceOf(com.surprising.aeron.exception.CoreStateRejectedException.class);
                assertThat(retry.admission()).isNull();
                runtime.releaseReplacementAdmission(retry);
                // 再次复用同一事件的完整替换请求，应成功生成新单 13 的输入。
                var accepted = runtime.dispatchReplacementAdmission(4, 7, 11, null,
                        new ReplaceOrderCommand(11, new PlaceOrderCommand(13, "1", CoreOrderSide.BUY, 100, 1,
                                false, CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT,
                                CoreTimeInForce.GTC, false, "new-client")), decision, 0, identities);
                assertThat(accepted).isSameAs(event);
                await(runtime, accepted);
                assertThat(accepted.rejection()).isNull();
                assertThat(originalOrder.status()).isEqualTo(CoreOrderStatus.OPEN);
                assertThat(originalUser.revision()).isEqualTo(1);
                runtime.releaseReplacementAdmission(accepted);
            } finally { releaseWorker.countDown(); }
        }
    }

    /**
     * 最多三秒等待 Lane 发布准入完成，期间检查账户 worker 健康，超时立即使测试失败。
     * @param runtime 持有当前真实账户 worker 的测试运行时
     * @param event 等待 Lane 发布完成的改单准入事件
     */
    private static void await(TradingAccountRuntime runtime, ReplacementAdmissionEvent event) {
        // 等待账户准入回执的三秒单调时钟上限，不改变业务时间。
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!event.complete()) {
            runtime.assertAccountLanesHealthy();
            if (System.nanoTime() >= deadline) throw new AssertionError("replacement admission timed out");
            Thread.onSpinWait();
        }
    }
    /**
     * 在测试启动 Lane 前用反射替换指定账户表字段，安装只检查读取线程的测试 Map。
     * @param lane 准备安装字段守卫的账户 Lane
     * @param name 要替换的具体账户表字段名
     * @param replacement 实现相同表契约的测试 Map 替代对象
     */
    private static void guard(AccountLaneState lane, String name, Object replacement) throws Exception {
        // 反射找到的目标账户表字段，允许访问后替换为测试守卫。
        var field = AccountLaneState.class.getDeclaredField(name); field.setAccessible(true); field.set(lane, replacement);
    }
    /**
     * 创建 Agrona Map 的测试守卫，复制表项但沿用原实体引用，只覆写 get 来禁止指定 Owner 线程读取。
     * @param <T> 守卫 Map 所保存的原账户实体类型，不改变值对象身份
     * @param original 原账户表，用其现有键值初始化守卫，不复制实体本身
     * @param owner 测试中禁止直接读取账户表的 Owner 线程
     */
    private static <T> Long2ObjectHashMap<T> agronaGuard(Long2ObjectHashMap<T> original, Thread owner) {
        // 复制原表键值的临时测试 Map，只改 get 的线程断言，不改变原实体内容。
        var guarded = /* Agrona 账户表读取守卫，只在 get 被指定 Owner 调用时失败。 */ new Long2ObjectHashMap<T>() {
            /**
             * 按键读取前检查当前线程，指定 Owner 线程调用就使测试失败，其他线程交父类正常读取。
             * @param key 当前需要从 primitive Map 读取的账户实体键
             */
            @Override public T get(long key) {
                if (Thread.currentThread() == owner) throw new AssertionError("Owner read Account Lane table");
                return super.get(key);
            }
        };
        guarded.putAll(original); return guarded;
    }
    /**
     * 创建 Eclipse primitive Map 的测试守卫，复制表项并复用原值引用，get 在指定 Owner 线程执行时立即失败。
     * @param <T> 守卫 Map 所保存的原账户实体类型，不改变值对象身份
     * @param original 原账户表，用其现有键值初始化守卫，不复制实体本身
     * @param owner 测试中禁止直接读取账户表的 Owner 线程
     */
    private static <T> LongObjectHashMap<T> eclipseGuard(LongObjectHashMap<T> original, Thread owner) {
        // 复制原表键值的临时测试 Map，只改 get 的线程断言，不改变原实体内容。
        var guarded = /* Eclipse primitive 账户表读取守卫，保持原键值并检查 get 的调用线程。 */ new LongObjectHashMap<T>() {
            /**
             * 检查账户实体的 get 来自允许线程，再按原 Map 语义返回元素。
             * @param key 当前需要从 primitive Map 读取的账户实体键
             */
            @Override public T get(long key) {
                if (Thread.currentThread() == owner) throw new AssertionError("Owner read Account Lane table");
                return super.get(key);
            }
        };
        guarded.putAll(original); return guarded;
    }
}
