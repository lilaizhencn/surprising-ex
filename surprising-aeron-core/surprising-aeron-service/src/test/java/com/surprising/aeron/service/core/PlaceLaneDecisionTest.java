package com.surprising.aeron.service.core;

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
import com.surprising.aeron.protocol.UpsertFeePolicyCommand;
import com.surprising.aeron.service.account.CoreUserState;
import com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.aeron.service.market.MarkPriceRuntime;
import com.surprising.aeron.service.order.admission.CoreOrderDecisionResolver;
import com.surprising.aeron.service.order.admission.PlaceAdmissionEvent;
import com.surprising.aeron.service.snapshot.state.RuntimeStateProjector;
import com.surprising.aeron.service.treasury.CoreTreasuryState;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * 检查价格、费用解析确实在账户 Lane 消费准入时发生，并验证拒绝后事件槽可以清理复用。用真实账户工作线程制造延迟，不把尚未完成的意图载体当作已解析资金决策。
 */
class PlaceLaneDecisionTest {
    /**
     * 阻塞真实账户 Lane 后派发过期价格准入，确认尚无解析结果或撮合价格；放行得到 freshness 拒绝并归还，再复用同一槽以有效时间准入，检查撮合价及 maker、taker 费率。
     */
    @Test void priceAndFeeResolveOnlyAfterLaneConsumesAndRejectedSlotsAreReusable() throws Exception {
        // 标的一的 U 本位永续测试合约
        var instrument = CoreInstrument.from(ProductLine.LINEAR_PERPETUAL,
                new RegisterInstrumentCommand("1", ContractType.LINEAR_PERPETUAL.ordinal(),
                        "BTC", "USDT", "USDT", 1, 1, 1, 100_000, 50_000, 0, 0, 0, -1, 0));
        // 用户七一万 USDT 可用余额、无订单持仓的冷状态
        var user = new CoreUserState(ProductLine.LINEAR_PERPETUAL, 7, 1,
                Map.of("USDT", new AssetBalance("USDT", 10_000, 0)), Map.of(), Map.of());
        // 原用户和合约组成的本产品线冷状态
        var state = new TradingCoreState(ProductLine.LINEAR_PERPETUAL, 1, Map.of(7L, user), Map.of(),
                Map.of("1", instrument), CoreRiskState.empty(), CoreTreasuryState.empty());
        // 原资产、标的和客户端身份表
        var identities = new RuntimeIdentityRegistry();
        try (/* 从冷状态投影的测试运行时，结束自动关闭 */ var runtime = RuntimeStateProjector.project(state, identities)) {
            // USDT 内部资产编号
            // 标的一的内部稠密编号
            int symbol = identities.symbolId("1"), asset = identities.assetId("USDT");
            runtime.putMarkPrice(new MarkPriceRuntime(symbol, instrument, 100, 1, 1_000));
            runtime.upsertFeePolicy(new UpsertFeePolicyCommand(1, 1, 7, "1", -10, 20, 0, true, 1, 0));
            runtime.startAccountLanes();
            // 通知测试线程阻塞工作已进入账户 Lane 的门闩
            var entered = new CountDownLatch(1);
            // 允许账户 Lane 继续处理准入的门闩，所有退出路径放行
            var release = new CountDownLatch(1);
            // 用户七所属的实际账户 Lane 下标
            int laneId = runtime.topology().accountLaneId(7);
            runtime.submitAccountCommand(laneId, /* 当前执行阻塞工作的账户 Lane */ lane -> {
                entered.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Lane gate timed out"); }
                catch (/* 门闩等待中断，转成测试失败 */ InterruptedException failure) { throw new AssertionError(failure); }
            });
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            try {
                // 用户七市价 IOC 买入一份的原意图，两个准入尝试复用
                var command = new PlaceOrderCommand(11, "1", CoreOrderSide.BUY, 0, 1, false,
                        CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.MARKET,
                        CoreTimeInForce.IOC, false, "client");
                // 首次以过期业务时间派发、随后应拒绝的准入槽
                var rejected = runtime.acquirePlaceAdmission();
                CoreOrderDecisionResolver.prepare(rejected, runtime, identities, 7, command, 6_001);
                runtime.dispatchPlaceAdmission(2, 7, rejected, new UUID(1, 2), runtime.treasury().settlementStatus(symbol), asset,
                        identities, 6_001, 2);
                assertThat(rejected.complete()).isFalse();
                assertThat(rejected.matchingOrder().intent()).isSameAs(command);
                assertThat(rejected.resolvedOrderInitialized()).isFalse();
                assertThat(rejected.matchingPriceTicks()).isZero();
                release.countDown();
                await(runtime, rejected);
                assertThat(rejected.rejection()).isInstanceOf(com.surprising.aeron.exception.CoreStateRejectedException.class)
                        .hasMessageContaining("freshness");
                assertThat(rejected.clientKey()).isZero();
                rejected.cancelMatcherWait();
                runtime.releasePlaceAdmission(rejected);
                // 拒绝槽归还后再次借出的同一实例，本轮使用有效价格时间
                var accepted = runtime.acquirePlaceAdmission();
                assertThat(accepted).isSameAs(rejected);
                CoreOrderDecisionResolver.prepare(accepted, runtime, identities, 7, command, 1_001);
                runtime.dispatchPlaceAdmission(3, 7, accepted, new UUID(1, 3), runtime.treasury().settlementStatus(symbol), asset,
                        identities, 1_001, 3);
                await(runtime, accepted);
                assertThat(accepted.rejection()).isNull();
                assertThat(accepted.resolvedOrder().matchingPriceTicks()).isEqualTo(101);
                assertThat(accepted.makerFeeRatePpm()).isEqualTo(-10);
                assertThat(accepted.takerFeeRatePpm()).isEqualTo(20);
                accepted.cancelMatcherWait();
                runtime.releasePlaceAdmission(accepted);
            } finally { release.countDown(); }
        }
    }

    /**
     * 在最多三秒内等准入事件完成，并持续检查账户工作线程健康。
     * @param runtime 拥有真实账户工作资源的原运行时
     * @param event 已派发且仍保留租约的准入事件
     */
    private static void await(TradingAccountRuntime runtime, PlaceAdmissionEvent event) {
        // 等本次准入完成的三秒单调时钟截止时间
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!event.complete()) {
            runtime.assertAccountLanesHealthy();
            if (System.nanoTime() >= deadline) throw new AssertionError("admission timed out");
            Thread.onSpinWait();
        }
    }
}
