package com.surprising.aeron.service.core;

import com.surprising.aeron.protocol.*;
import com.surprising.aeron.service.order.CoreTestCompletion;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.assertj.core.api.Assertions.*;

/**
 * 以 PIPELINED 工作线程覆盖六产品线普通入口：无需测试手动开启异步命令作用域，激活后的运行时就应把账户工作交回所属 Lane，并在完成前及时返回等待状态。
 */
@org.junit.jupiter.api.extension.ExtendWith(PipelinedExecutionExtension.class)
class CoreActiveLaneIngressTest {
    /** 本场景固定的复制业务时间。 */
    private static final long TIME = 1_700_000_000_000L;
    /** 每个测试实例递增的来源序号，用来生成唯一命令身份。 */
    private long sequence;

    /**
     * 核对费率配置版本与幂等，然后阻塞真实账户 Lane；普通余额命令必须先返回等待。放行后完成余额、下单成交和余额不足拒绝，核对冻结清空、现货资产或衍生品持仓及账本启动。
     * @param line 当前参数化产品线
     */
    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void ordinaryIngressReturnsBeforeBlockedLaneAndCompletesFundsAndTradesWithoutOwnerAccountReads(ProductLine line) throws Exception {
        // 确保 finally 放行被阻塞账户任务的门闩
        var release = new CountDownLatch(1);
        try (/* 自动关闭的真实交易 Core */ var core = new TradingCoreRuntime(line)) {
            // 当前产品线对应的币对或合约类型
            var type = ContractType.valueOf(line.contractTypeCode());
            applied(core, message(core, CoreMessageType.REGISTER_INSTRUMENT, 0,
                    TradingCommandCodec.encodeRegisterInstrument(new RegisterInstrumentCommand(
                            "1", "BTC", "USDT", type.isInverse() ? "BTC" : "USDT", 1, 1, type.isInverse() ? 1000 : 1,
                            (type == ContractType.SPOT ? null : new CoreInstrumentRiskLimits(
                                    100_000, (1_000_000_000_000L / (100_000)), Long.MAX_VALUE, 0, Long.MAX_VALUE,
                                    java.util.List.of(new CoreRiskLimitBracket(Long.MAX_VALUE, (1_000_000_000_000L / (100_000)), 100_000, 50_000)))),
                            500, 1000, type.isDelivery() || type.isOption() ? TIME + 20_000 : 0,
                            type.isOption() ? 0 : -1, type.isOption() ? 100 : 0), line)));
            if (line != ProductLine.SPOT) applied(core, message(core, CoreMessageType.APPLY_MARK_PRICE, 0,
                    TradingCommandCodec.encodeApplyMarkPrice(line.isOptionProduct()
                            ? new ApplyMarkPriceCommand("1", 100, 100, 100, 1, TIME)
                            : new ApplyMarkPriceCommand("1", 100, 1, TIME), line)));
            // 要通过命令应用的第二版费率配置
            var fee = new UpsertFeePolicyCommand(51, 2, 1, "1", 300, 700, 0, true, TIME, 0);
            assertThatThrownBy(() -> core.runtimeState.upsertFeePolicy(fee))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("configuration Lane 0");
            applied(core, message(core, CoreMessageType.UPSERT_FEE_POLICY, 0,
                    TradingCommandCodec.encodeUpsertFeePolicy(fee)));
            // 成功应用费率后的配置修订号，重复或陈旧请求不应增加它
            long configuredRevision = core.runtimeState.revision();
            applied(core, message(core, CoreMessageType.UPSERT_FEE_POLICY, 0,
                    TradingCommandCodec.encodeUpsertFeePolicy(fee)));
            assertThat(core.runtimeState.revision()).isEqualTo(configuredRevision);
            // 故意提交旧版本的费率配置命令，用来验证版本拒绝与修订不变
            var stale = message(core, CoreMessageType.UPSERT_FEE_POLICY, 0,
                    TradingCommandCodec.encodeUpsertFeePolicy(new UpsertFeePolicyCommand(
                            51, 1, 1, "1", 300, 700, 0, true, TIME, 0)));
            assertThat(complete(core, stale, core.apply(stale)).resultCode()).isEqualTo(CoreResultCode.STALE_FEE_POLICY_VERSION);
            assertThat(core.runtimeState.revision()).isEqualTo(configuredRevision);
            // 本产品用于余额和交易结算的资产代码
            String asset = type.isInverse() ? "BTC" : "USDT";
            // 账户任务已进入阻塞处的门闩
            var entered = new CountDownLatch(1);
            assertThat(core.runtimeState.dispatchAccountQuery(core.runtimeState.topology().accountLaneMask(1), /* 被派发账户查询的任务参数，测试闭包不读取该参数 */ lane -> {
                entered.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("blocked Lane timeout"); }
                catch (/* 阻塞账户任务收到的中断，转成断言失败 */ InterruptedException failure) { throw new AssertionError(failure); }
            })).isTrue();
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            // 命令开始前保存的账户交接代次，跨线程保护期间应保持不变
            long handoff = com.surprising.aeron.service.core.AccountLaneTestAccess.handoffEpoch(core.runtimeState);
            try (/* 禁止 Owner 直接访问账户状态的检查器 */ var guard = new OwnerAccountReadGuard(core.runtimeState)) {
                // 这里不手动开启异步命令作用域，实际激活状态本身就应选择工作线程路径。
                // 在所属 Lane 阻塞时从普通入口交入的余额调整命令
                var adjustment = message(core, CoreMessageType.ADJUST_BALANCE, 1,
                        TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand(asset, 10_000)));
                assertThat(core.apply(adjustment)).isNull();
                assertThat(core.directCommand.poll()).isNull();
                assertThat(core.directCommand.active()).isTrue();
                assertThat(release.getCount()).isOne();
                release.countDown();
                assertThat(complete(core, adjustment, null).commandStatus()).isEqualTo(ResponseStatus.APPLIED);
                applied(core, message(core, CoreMessageType.ADJUST_BALANCE, 7,
                        TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand(asset, 10_000))));
                if (line == ProductLine.SPOT) applied(core, message(core, CoreMessageType.ADJUST_BALANCE, 7,
                        TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("BTC", 10))));
                applied(core, place(core, 7, 101, CoreOrderSide.SELL));
                assertThat(core.runtimeClientIdentityCount()).isOne();
                applied(core, place(core, 1, 102, CoreOrderSide.BUY));
                assertThat(core.runtimeClientIdentityCount()).isZero();
                // 故意超出余额的提款或余额减少请求
                var failed = message(core, CoreMessageType.ADJUST_BALANCE, 1,
                        TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand(asset, -100_000)));
                // 余额不足请求最终得到的拒绝响应
                var rejection = complete(core, failed, core.apply(failed));
                assertThat(rejection.commandStatus()).isEqualTo(ResponseStatus.REJECTED);
                assertThat(com.surprising.aeron.service.core.AccountLaneTestAccess.handoffEpoch(core.runtimeState)).isEqualTo(handoff);
            }
            // 移出跨线程读取保护后导出的挂单用户状态
            var maker = CoreTestCompletion.userState(core, 7);
            // 移出保护后导出的吃单用户状态
            var taker = CoreTestCompletion.userState(core, 1);
            assertThat(maker.reservations()).isEmpty();
            assertThat(taker.reservations()).isEmpty();
            assertThat(core.activeOrderIndex().activeOrderRoute(101)).isNull();
            assertThat(core.activeOrderIndex().activeOrderRoute(102)).isNull();
            if (line == ProductLine.SPOT) {
                assertThat(taker.balances()).anySatisfy(/* 成交后核对的现货目标资产余额 */ balance -> {
                    assertThat(balance.asset()).isEqualTo("BTC");
                    assertThat(balance.availableUnits()).isEqualTo(2);
                });
            } else {
                assertThat(taker.positions()).singleElement().satisfies(/* 衍生品持仓检查中的单个用户持仓 */ position -> assertThat(position.signedQuantitySteps()).isEqualTo(2));
                assertThat(maker.positions()).singleElement().satisfies(/* 衍生品持仓检查中的单个用户持仓 */ position -> assertThat(position.signedQuantitySteps()).isEqualTo(-2));
            }
            core.assertClusterCallbackComplete();
        } finally { release.countDown(); }
    }

    /**
     * 构造指定用户和方向的限价单，使用固定币对、价格、数量和唯一客户端订单号。
     * @param core 本场景真实激活的交易运行时
     * @param user 命令目标用户 ID
     * @param id 限价单业务订单 ID
     * @param side 要构造的买卖方向
     */
    private CoreMessage place(TradingCoreRuntime core, long user, long id, CoreOrderSide side) {
        return message(core, CoreMessageType.PLACE_ORDER, user, TradingCommandCodec.encodePlaceOrder(
                new PlaceOrderCommand(id, "1", side, 100, 2, false, CoreMarginMode.CROSS, CorePositionSide.NET,
                        CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "active-" + id)));
    }

    /**
     * 从普通运行时入口执行命令，等待所有直接控制及撮合完成，再断言最终 APPLIED。
     * @param core 本场景真实激活的交易运行时
     * @param message 需要执行或等待终态的原命令
     */
    private void applied(TradingCoreRuntime core, CoreMessage message) {
        // 普通入口得到并交给完成助手等待的初始响应
        var response = complete(core, message, core.apply(message));
        assertThat(response.commandStatus()).as("%s: %s", message.header().messageType(), response.resultCode()).isEqualTo(ResponseStatus.APPLIED);
    }

    /**
     * 在同一个五秒截止时间内先推进直接控制槽，再排完待撮合队列；只把目标 UUID 对应撮合序号的结果保存为返回终态。
     * @param core 本场景真实激活的交易运行时
     * @param message 需要执行或等待终态的原命令
     * @param response 普通入口已经返回的初始结果，可能尚需异步完成
     */
    private CoreResponse complete(TradingCoreRuntime core, CoreMessage message, CoreResponse response) {
        // 贯穿直接控制和撮合等待的同一个五秒截止时间
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (core.directCommand.active()) {
            response = core.directCommand.poll();
            if (System.nanoTime() >= deadline) throw new AssertionError("direct completion timeout");
            Thread.onSpinWait();
        }
        // 按目标命令 UUID 查询到的撮合 Core 序号
        long requested = core.matchingSequence(message.header().commandId());
        // 固定单槽保存目标命令终态，供完成回调写入
        CoreResponse[] terminal = {response};
        while (core.pendingMatching().firstSequence() != 0) {
            core.commits.commitReadyMatching(16, TIME, message.header().sourceSequence(), false,
                    (/* 已完成撮合的 Core 序号，只有匹配目标序号才保存结果 */ id, /* 当前完成回调交出的撮合终态 */ result) -> { if (id == requested) terminal[0] = result; });
            if (System.nanoTime() >= deadline) throw new AssertionError("matching completion timeout");
            Thread.onSpinWait();
        }
        assertThat(terminal[0]).isNotNull();
        return terminal[0];
    }

    /**
     * 递增本夹具来源序号，构造固定来源、业务时间和唯一 UUID 的普通命令。
     * @param core 本场景真实激活的交易运行时
     * @param type 本次复制命令类型
     * @param user 命令目标用户 ID
     * @param payload 与命令类型对应的业务载荷
     */
    private CoreMessage message(TradingCoreRuntime core, CoreMessageType type, long user, byte[] payload) {
        // 本次新分配的来源序号和 UUID 后半部
        long seq = ++sequence;
        return new CoreMessage(CoreMessageHeader.command(type, new UUID(987, seq), core.productLine,
                CommandSource.OPERATIONS, 987, seq, user, TIME, seq), payload);
    }
}
