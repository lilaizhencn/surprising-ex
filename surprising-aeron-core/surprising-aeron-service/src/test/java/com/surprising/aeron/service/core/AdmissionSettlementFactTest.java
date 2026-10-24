package com.surprising.aeron.service.core;

import com.surprising.aeron.exception.CoreStateRejectedException;
import com.surprising.aeron.model.*;
import com.surprising.aeron.model.AssetBalance;
import com.surprising.aeron.model.CoreRiskState;
import com.surprising.aeron.model.TradingCoreState;
import com.surprising.aeron.protocol.*;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreOrderType;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreTimeInForce;
import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.protocol.RegisterInstrumentCommand;
import com.surprising.aeron.service.account.CoreUserState;
import com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.aeron.service.market.MarkPriceRuntime;
import com.surprising.aeron.service.order.admission.CoreOrderDecisionResolver;
import com.surprising.aeron.service.order.admission.PlaceAdmissionEvent;
import com.surprising.aeron.service.snapshot.state.RuntimeStateProjector;
import com.surprising.aeron.service.treasury.CoreTreasuryState;
import com.surprising.aeron.service.treasury.SystemLedgerLane;
import com.surprising.aeron.service.treasury.TreasuryRuntime;
import com.surprising.product.api.ProductLine;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

/**
 * 验证延迟执行的账户准入使用派发时借出的结算状态，而不是稍后账本的新状态。分别覆盖清空转资金费中、资金费中转完成、已结算转回滚；检查保留事实、拒绝原因及资金和预留数。
 */
class AdmissionSettlementFactTest {
    /**
     * 用门闩阻塞真实账户 Lane，派发带原结算状态的准入，再由独立真实账本改变状态；放行后仍按原状态接受或拒绝，并在账户 Lane 核对总资金与预留数。
     * @param transition 本轮账本后续变化类型，准入仍须采用此前捕获的状态
     */
    @ParameterizedTest
    @ValueSource(strings = {"CLEAR_TO_FUNDING", "FUNDING_TO_CLEAR", "SETTLED_TO_ROLLBACK"})
    void delayedLaneUsesTheBorrowedFactInsteadOfLaterLedgerStatus(String transition) throws Exception {
        // 本用例标的一的 U 本位永续合约
        var instrument = CoreInstrument.from(ProductLine.LINEAR_PERPETUAL,
                new RegisterInstrumentCommand(
                        "1", "BTC", "USDT", "USDT", 1, 1, 1,
                        new CoreInstrumentRiskLimits(
                                100_000, (1_000_000_000_000L / (100_000)), Long.MAX_VALUE, 0, Long.MAX_VALUE,
                                java.util.List.of(new CoreRiskLimitBracket(Long.MAX_VALUE, (1_000_000_000_000L / (100_000)), 100_000, 50_000))),
                        0, 0, 0, -1, 0));
        // 用户七初始一万 USDT、无持仓和订单的冷状态
        var user = new CoreUserState(ProductLine.LINEAR_PERPETUAL, 7, 1,
                Map.of("USDT", new AssetBalance("USDT", 10_000, 0)), Map.of(), Map.of());
        // 原资产、标的及持仓身份表
        var ids = new RuntimeIdentityRegistry();
        // 允许被阻塞的账户 Lane 继续消费准入的门闩，所有退出路径都会放行
        var release = new CountDownLatch(1);
        try (/* 由本用例冷状态投影的运行时，启动真实账户工作资源 */ var runtime = RuntimeStateProjector.project(new TradingCoreState(ProductLine.LINEAR_PERPETUAL, 1,
                Map.of(7L, user), Map.of(), Map.of("1", instrument), CoreRiskState.empty(), CoreTreasuryState.empty()), ids)) {
            // USDT 的内部资产编号
            // 永久标的一的内部稠密编号
            int symbol = ids.symbolId("1"), asset = ids.assetId("USDT");
            runtime.putMarkPrice(new MarkPriceRuntime(symbol, instrument, 100, 1, 1_000));
            runtime.clearChangedKeys();
            runtime.startAccountLanes();
            try (/* 直接绑定本运行时 Treasury 的独立账本工作资源，用例结束关闭 */ var ledger = new SystemLedgerLane(runtime.treasury(), 256, /* 账本异步故障通知或门闩中断；前者本测试不处理，由健康检查发现，后者转测试失败 */ failure -> { })) {
                // 用于把标的设为资金费处理中状态的原进度值
                var funding = new TreasuryRuntime.FundingProgressRuntime(1, instrument, 100, 7, 100);
                if (transition.equals("FUNDING_TO_CLEAR")) run(ledger, /* 账本回调给出的权威 Treasury，仅在账本工作中修改 */ actual -> {
                    actual.setFundingProgress(symbol, funding); actual.clearChangedKeys();
                });
                if (transition.equals("SETTLED_TO_ROLLBACK"))
                    run(ledger, /* 账本回调给出的权威 Treasury，仅在账本工作中修改 */ actual -> actual.setLifecycleSettlement(symbol, 2));
                // 派发前捕获的不可变结算状态事实，事件须保留同一实例
                var captured = runtime.treasury().settlementStatus(symbol);
                // 用户七买入一份的限价 GTC 下单意图
                var intent = new PlaceOrderCommand(11, "1", CoreOrderSide.BUY, 100, 1, false,
                        CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "fact");
                // 从池借出的准入事件，账户和 Matcher 借用结束后归还
                var event = runtime.acquirePlaceAdmission();
                CoreOrderDecisionResolver.prepare(event, runtime, ids, 7, intent, 1_001);
                // 用户七所属账户 Lane 下标
                int laneId = runtime.accountLaneId(7);
                // 通知测试线程阻塞工作已经进入账户 Lane 的门闩
                var entered = new CountDownLatch(1);
                runtime.submitAccountCommand(laneId, /* 实际执行阻塞工作的用户所属账户 Lane */ lane -> { entered.countDown(); await(release); });
                assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
                try {
                    runtime.dispatchPlaceAdmission(2, 7, event, new UUID(1, 2), captured, asset, ids, 1_001, 2);
                    // 通过反射读取准入事件所保留结算状态的字段
                    var field = PlaceAdmissionEvent.class.getDeclaredField("settlementStatus");
                    field.setAccessible(true);
                    assertThat(field.get(event)).isSameAs(captured);
                    assertThat(event.complete()).isFalse();
                    run(ledger, /* 账本回调给出的权威 Treasury，仅在账本工作中修改 */ actual -> {
                        switch (transition) {
                            case "CLEAR_TO_FUNDING" -> actual.setFundingProgress(symbol, funding);
                            case "FUNDING_TO_CLEAR" -> actual.setFundingSettlement(symbol, 1);
                            case "SETTLED_TO_ROLLBACK" -> actual.rollbackChangedValues();
                            default -> throw new AssertionError(transition);
                        }
                        actual.clearChangedKeys();
                    });
                    assertThat(runtime.treasury().settlementStatus(symbol)).isNotSameAs(captured);
                    release.countDown();
                    // 放行后准入与后续资金核对共用的三秒单调时钟截止值
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                    while (!event.complete()) {
                        runtime.assertAccountLanesHealthy();
                        if (System.nanoTime() >= deadline) throw new AssertionError("admission timed out");
                        Thread.onSpinWait();
                    }
                    if (transition.equals("CLEAR_TO_FUNDING")) assertThat(event.rejection()).isNull();
                    else assertThat(((CoreStateRejectedException) event.rejection()).code()).isEqualTo(
                            transition.equals("FUNDING_TO_CLEAR") ? "LIFECYCLE_IN_PROGRESS" : "INSTRUMENT_SETTLED");
                    runtime.dispatchControlLanes(1L << laneId, /* 执行资金和预留断言的所属账户 Lane，闭包按原用户读取 */ ignored -> {
                        // 在实际账户 Lane 读到的用户七 USDT 余额，检查可用加冻结仍为一万
                        var balance = runtime.balance(7, asset);
                        assertThat(balance.availableUnits() + balance.lockedUnits()).isEqualTo(10_000);
                        assertThat(runtime.reservationCountForUser(7)).isEqualTo(transition.equals("CLEAR_TO_FUNDING") ? 1 : 0);
                        return null;
                    });
                    while (!runtime.pollControlLanes()) {
                        if (System.nanoTime() >= deadline) throw new AssertionError("funds check timed out");
                        Thread.onSpinWait();
                    }
                    event.cancelMatcherWait();
                    runtime.releasePlaceAdmission(event);
                } finally { release.countDown(); }
            }
        } finally { release.countDown(); }
    }

    /**
     * 要求账本工作成功入队，最多三秒等执行回执，再检查账本健康；工作抛错时由原账本故障机制暴露。
     * @param lane 执行本次结算状态变化的真实独立账本 Lane
     * @param work 要在账本线程执行的原工作
     */
    private static void run(SystemLedgerLane lane, SystemLedgerLane.Entry work) throws Exception {
        // 账本工作执行完后通知测试线程的门闩
        var done = new CountDownLatch(1);
        assertThat(lane.offer(/* 账本回调给出的权威 Treasury，仅在账本工作中修改 */ actual -> { work.apply(actual); done.countDown(); })).isTrue();
        assertThat(done.await(3, TimeUnit.SECONDS)).isTrue();
        lane.assertHealthy();
    }

    /**
     * 账户门闩最多等五秒，超时或中断转成测试失败。
     * @param latch 阻塞真实账户 Lane 并由测试线程放行的门闩
     */
    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("Lane gate timed out");
        } catch (/* 账本异步故障通知或门闩中断；前者本测试不处理，由健康检查发现，后者转测试失败 */ InterruptedException failure) { throw new AssertionError(failure); }
    }
}
