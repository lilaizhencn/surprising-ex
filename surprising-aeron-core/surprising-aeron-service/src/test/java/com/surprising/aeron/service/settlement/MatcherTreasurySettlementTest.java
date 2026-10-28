package com.surprising.aeron.service.settlement;

import com.surprising.aeron.config.LaneTopology;
import com.surprising.aeron.model.*;
import com.surprising.aeron.model.AssetBalance;
import com.surprising.aeron.model.CoreOrderState;
import com.surprising.aeron.model.CoreOrderStatus;
import com.surprising.aeron.model.CoreRiskState;
import com.surprising.aeron.model.TradingCoreState;
import com.surprising.aeron.protocol.*;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreOrderType;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreRiskLimitBracket;
import com.surprising.aeron.protocol.CoreTimeInForce;
import com.surprising.aeron.protocol.RegisterInstrumentCommand;
import com.surprising.aeron.protocol.ReservationKind;
import com.surprising.aeron.service.account.CoreUserState;
import com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.aeron.service.order.state.OrderReservation;
import com.surprising.aeron.service.order.state.OrderRuntime;
import com.surprising.aeron.service.snapshot.state.RuntimeStateProjector;
import com.surprising.aeron.service.treasury.CoreTreasuryState;
import com.surprising.aeron.service.treasury.RuntimeFundsAccumulator;
import com.surprising.aeron.service.treasury.RuntimeTreasuryDelta;
import com.surprising.aeron.service.treasury.TreasuryRuntime;
import com.surprising.product.api.ProductLine;
import exchange.core2.core.common.MatcherResult;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static com.surprising.aeron.service.matcher.MatcherEventFixtures.trade;
import static org.assertj.core.api.Assertions.*;

/**
 * 结算事件财务槽的回归用例。一项通过反射核对容量保留和活跃槽范围，另一项用真实独立账户 Lane 及原 Treasury 的账本线程验证手续费、满队列、完成与回收交接，不是完整 Cluster 压测。
 */
class MatcherTreasurySettlementTest {
    /**
     * 反射交替准备一 Lane、多 Lane 和无成交路由，检查原财务槽对象保留、每代金额清空且合并只读当前参与范围，包含最高位 Lane。
     */
    @Test
    void shrinkingLaneRoutesRetainTreasurySlotsAndExcludeInactiveContributions() throws Exception {
        // 本次待准备、投递和收集的可复用撮合结算事件。
        var event = new MatcherSettlementEvent();
        // 反射取得的财务槽容量准备方法。
        var prepare = MatcherSettlementEvent.class.getDeclaredMethod("prepareTreasuryDeltas", long.class, boolean.class);
        prepare.setAccessible(true);
        // 反射取得的参与 Lane 财务槽数组字段。
        var storage = MatcherSettlementEvent.class.getDeclaredField("touchedLaneTreasuryDeltas");
        storage.setAccessible(true);
        // 反射取得的实际参与 Lane 位图字段。
        var mask = MatcherSettlementEvent.class.getDeclaredField("requiredLaneMask");
        mask.setAccessible(true);
        // 反射取得的本代是否存在成交标记。
        var trades = MatcherSettlementEvent.class.getDeclaredField("treasuryTrades");
        trades.setAccessible(true);
        // 反射取得的财务差额合并方法。
        var merge = MatcherSettlementEvent.class.getDeclaredMethod("mergeTreasuryDeltas");
        merge.setAccessible(true);

        prepare.invoke(event, 1L, false);
        assertThat(storage.get(event)).isNull();
        prepare.invoke(event, 1L, true);
        // 紧凑参与槽中的第一个财务差额对象，用于检查保留及金额清空。
        RuntimeTreasuryDelta first = ((RuntimeTreasuryDelta[]) storage.get(event))[0];
        first.addFee(2, 17);
        prepare.invoke(event, 5L, true);
        // 已扩为两个槽的财务差额数组，之后缩小路由不应替换它。
        RuntimeTreasuryDelta[] slots = (RuntimeTreasuryDelta[]) storage.get(event);
        assertThat(slots).hasSize(2);
        assertThat(slots[0]).isSameAs(first);
        assertThat(first.size()).isZero();
        // 第二个保留财务槽，当前不参与时故意填入金额以验证不被合并。
        RuntimeTreasuryDelta second = slots[1];

        for (/* 重复扩缩路由及无成交清空的当前轮次。 */ int iteration = 0; iteration < 64; iteration++) {
            prepare.invoke(event, 1L << 63, true);
            assertThat(storage.get(event)).isSameAs(slots);
            assertThat(slots[0]).isSameAs(first);
            assertThat(slots[1]).isSameAs(second);
            assertThat(first.size()).isZero();
            assertThat(second.size()).isZero();
            mask.setLong(event, 1L << 63);
            trades.setBoolean(event, true);
            first.addFee(2, 3);
            // 保留容量不等于本代业务范围，非参与槽即使有数据也不能参与合并。
            second.addFee(2, 100);
            assertThat(((RuntimeTreasuryDelta) merge.invoke(event)).feeUnits(0)).isEqualTo(3);

            prepare.invoke(event, (1L << 63) | 1, true);
            mask.setLong(event, (1L << 63) | 1);
            first.addFee(2, 5);
            second.addFee(2, 7);
            assertThat(((RuntimeTreasuryDelta) merge.invoke(event)).feeUnits(0)).isEqualTo(12);
            prepare.invoke(event, 1L, false);
            assertThat(storage.get(event)).isSameAs(slots);
            assertThat(first.size()).isZero();
            assertThat(second.size()).isZero();
        }
    }

    /**
     * 实际阻塞 maker 与账本队首，让 taker 独立完成；检查满队列保留、提前回收及重复收集拒绝、原财库手续费为二，以及账本处理后续工作时原财务槽可安全复用。
     */
    @Test
    void independentAccountWritersThenOriginalLedgerSettleFeesBeforeOwnerCollects() throws Exception {
        // 生产默认账户 Lane 拓扑，用于挑选跨 Lane 用户。
        var topology = LaneTopology.productionDefault();
        // 固定 maker 用户编号。
        // 调整为与 maker 不同 Lane 的主动用户编号。
        long makerUser = 8, takerUser = 7;
        while (topology.accountLaneId(makerUser) == topology.accountLaneId(takerUser)) takerUser++;
        // 本次真实 Lane 成交使用的规范 U 本位永续币对。
        var instrument = CoreInstrument.from(ProductLine.LINEAR_PERPETUAL,
                new RegisterInstrumentCommand(
                        "1", "BTC", "USDT", "USDT", 1, 1, 1,
                        new CoreInstrumentRiskLimits(
                                100_000, 10_000_000, 10_000, 0, 1,
                                List.of(new CoreRiskLimitBracket(10_000, 10_000_000, 100_000, 50_000))),
                        0, 0, 0, -1, 0));
        // 冷状态中的一单位主动买单。
        var taker = order(11, takerUser, CoreOrderSide.BUY);
        // 冷状态中的一单位卖方 maker。
        var maker = order(12, makerUser, CoreOrderSide.SELL);
        // 包含两账户、两订单、币对和空系统账本的起始冷状态。
        var before = new TradingCoreState(ProductLine.LINEAR_PERPETUAL, 1,
                Map.of(takerUser, user(takerUser, taker), makerUser, user(makerUser, maker)),
                Map.of(11L, taker, 12L, maker), Map.of("1", instrument),
                CoreRiskState.empty(), CoreTreasuryState.empty());
        // 把业务币对、资产及持仓转为运行时编号的字典。
        var identities = new RuntimeIdentityRegistry();
        try (/* 由冷状态投影得到并在用例退出时关闭的交易运行时。 */ var runtime = RuntimeStateProjector.project(before, identities)) {
            // 投影后权威主动订单引用，后续事件复用仍以其身份准备撤单。
            OrderRuntime originalTaker = runtime.order(11);
            // 首代成交事实对应的命令 UUID。
            UUID commandId = new UUID(1, 2);
            // maker 与 taker 两账户 Lane 的联合位图。
            long laneMask = topology.accountLaneMask(takerUser) | topology.accountLaneMask(makerUser);
            // 本次待准备、投递和收集的可复用撮合结算事件。
            var event = runtime.prepareDirectMatcherSettlement(1, laneMask, originalTaker, null, 1,
                    commandId, 0, identities, 10, 20, List.of(), null);
            // 始终由本用例复用的原 Treasury 对象，所有权由系统账本线程交接。
            TreasuryRuntime original = runtime.treasury();
            // 账本工作线程意外故障的原子接收槽。
            var failure = new AtomicReference<Throwable>();
            runtime.startAccountLanes();
            // maker 账户所属 Lane 编号。
            // 主动账户所属 Lane 编号。
            int makerLane = topology.accountLaneId(makerUser), takerLane = topology.accountLaneId(takerUser);
            // maker Lane 已进入阻塞任务的通知门闩。
            var makerParked = new CountDownLatch(1);
            // 放行 maker Lane 继续账户结算的门闩。
            var releaseMaker = new CountDownLatch(1);
            // 由主动账户线程完成通知触发的门闩，用于证明 taker 不必等待 maker。
            // 系统账本进入完成后的唤醒回调时通知主线程的门闩。
            var financialSignalling = new CountDownLatch(1);
            // 延迟旧代账本完成通知返回，供主线程先复用事件的门闩。
            var releaseFinancialSignal = new CountDownLatch(1);
            runtime.submitAccountCommand(makerLane, /* 阻塞任务收到的 maker 账户 Lane，本任务只等门闩而不读账户。 */ lane -> {
                makerParked.countDown();
                await(releaseMaker);
            });
            assertThat(makerParked.await(3, TimeUnit.SECONDS)).isTrue();
            try {
                event.reserveMatcherPublication();
                event.beginMatcherPublication();
                event.publishDirectNativeResult(new MatcherResult(1,
                        exchange.core2.core.common.cmd.OrderCommandType.PLACE_ORDER, 11, identities.symbolId("1"),
                        100, 1, 100, exchange.core2.core.common.OrderAction.BID,
                        exchange.core2.core.common.OrderType.GTC, takerUser, 1_000, 0,
                        exchange.core2.core.common.cmd.CommandResultCode.SUCCESS,
                        List.of(trade(12, makerUser, 100, 1, true, true)),
                        new MatcherResult.MarketData(List.of(), List.of(), 0, 0)),
                        1, 1, 2, 11, 1, 0);
                event.completeMatcherPublication();
                event.submitTreasurySettlement(original);
                try {
                    long takerDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                    while ((event.completedLaneMask() & (1L << takerLane)) == 0) {
                        runtime.assertAccountLanesHealthy();
                        if (System.nanoTime() >= takerDeadline) throw new AssertionError("taker settlement timed out");
                        Thread.onSpinWait();
                    }
                    assertThat(event.complete()).isFalse();
                    assertThatThrownBy(() -> runtime.releaseMatcherSettlement(event))
                            .isInstanceOf(IllegalStateException.class).hasMessageContaining("incomplete");
                } finally { releaseMaker.countDown(); }
                // 等待两个账户 Lane 都完成的有界单调时间截止点。
                long accountDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while (!event.accountLanesCompleteForLedger()) {
                    runtime.assertAccountLanesHealthy();
                    if (System.nanoTime() >= accountDeadline) throw new AssertionError("accounts timed out");
                    Thread.onSpinWait();
                }
                assertThatThrownBy(() -> event.submitTreasurySettlement(original))
                        .isInstanceOf(IllegalStateException.class).hasMessageContaining("already queued");
                // 等待财务槽完成或复用槽完成的有界截止点。
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while (!event.complete()) {
                    runtime.assertAccountLanesHealthy();
                    if (System.nanoTime() >= deadline) throw new AssertionError("financial settlement timed out");
                    Thread.onSpinWait();
                }
                assertThat(runtime.collectMatcherSettlement(event, null, null, null)).isTrue();
                assertThatThrownBy(() -> runtime.collectMatcherSettlement(event, null, null, null))
                        .isInstanceOf(IllegalStateException.class).hasMessageContaining("already collected");
                runtime.releaseMatcherSettlement(event);
                // 复用事件新一代撤单命令的 UUID。
                UUID secondId = new UUID(3, 4);
                // 从池中借到的同一结算事件，用于检查旧财务回调不污染新代。
                var reused = runtime.prepareDirectCancellation(2, originalTaker.userId(), originalTaker.orderId(),
                        secondId, 0, identities, 30, 40);
                assertThat(reused).isSameAs(event);
                reused.submitTreasurySettlement(original);
                reused.reserveMatcherPublication();
                reused.beginMatcherPublication();
                reused.publishDirectNativeResult(new MatcherResult(2,
                        exchange.core2.core.common.cmd.OrderCommandType.CANCEL_ORDER, 2, identities.symbolId("1"),
                        100, 1, 100, exchange.core2.core.common.OrderAction.BID,
                        exchange.core2.core.common.OrderType.GTC, takerUser, 1_000, 0,
                        exchange.core2.core.common.cmd.CommandResultCode.MATCHING_UNKNOWN_ORDER_ID,
                        List.of(), new MatcherResult.MarketData(List.of(), List.of(), 0, 0)),
                        2, 3, 4, 11, 2, 0);
                reused.completeMatcherPublication();
                deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while (!reused.complete()) {
                    runtime.assertAccountLanesHealthy();
                    if (System.nanoTime() >= deadline) throw new AssertionError("reused financial slot timed out");
                    Thread.onSpinWait();
                }
                assertThat(runtime.collectMatcherSettlement(reused, null, null, null)).isTrue();
                runtime.releaseMatcherSettlement(reused);
                assertThat(failure.get()).isNull();
            } finally {
                releaseMaker.countDown();
            }
            assertThat(original.fee(identities.assetId("USDT"))).isEqualTo(2);
        }
    }

    /**
     * 为测试订单构造含 USDT 余额及衍生品保证金预留的冷用户状态。
     * @param userId 测试用户编号
     * @param order 需要在冷用户状态中建立保证金预留的订单
     */
    private static CoreUserState user(long userId, CoreOrderState order) {
        return new CoreUserState(ProductLine.LINEAR_PERPETUAL, userId, 1,
                Map.of("USDT", new AssetBalance("USDT", 800, 100)),
                Map.of(order.orderId(), OrderReservation.create(order.orderId(), "1",
                        ReservationKind.DERIVATIVE_MARGIN, "USDT", 100, 1)), Map.of());
    }

    /**
     * 构造一单位数量的开放全仓净持仓限价单，按买卖方向充当 taker 或 maker。
     * @param orderId 测试订单编号
     * @param userId 测试用户编号
     * @param side 订单买卖方向
     */
    private static CoreOrderState order(long orderId, long userId, CoreOrderSide side) {
        return new CoreOrderState(orderId, ProductLine.LINEAR_PERPETUAL, userId, "1", side,
                100, 1, 0, false, CoreMarginMode.CROSS, CorePositionSide.NET,
                CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "", new UUID(0, orderId),
                10_000, 10_000, CoreOrderStatus.OPEN, 1);
    }

    /**
     * 有界等待账户或账本通知门闩，中断时恢复标记并抛出测试断言失败。
     * @param latch 控制工作线程继续执行的门闩
     */
    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(3, TimeUnit.SECONDS)) throw new AssertionError("account gate timed out");
        } catch (/* 门闩等待收到的中断异常，恢复中断标记后交给断言失败。 */ InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw new AssertionError(interrupted);
        }
    }
}
