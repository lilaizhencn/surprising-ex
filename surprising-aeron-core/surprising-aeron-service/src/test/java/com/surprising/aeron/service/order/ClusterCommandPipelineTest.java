package com.surprising.aeron.service.order;

import com.surprising.aeron.config.CoreConfiguration;
import com.surprising.aeron.config.LaneTopology;
import com.surprising.aeron.protocol.*;
import com.surprising.aeron.service.core.AccountLaneQueryTestSupport;
import com.surprising.aeron.service.core.CoreFaults;
import com.surprising.aeron.service.core.OwnerAccountReadGuard;
import com.surprising.aeron.service.core.PipelinedExecutionExtension;
import com.surprising.aeron.service.core.TradingCoreRuntime;
import com.surprising.aeron.service.core.TradingOwnerTestSupport;
import com.surprising.aeron.service.matcher.pipeline.MatcherPipelineGroup;
import com.surprising.aeron.support.egress.ClusterServiceEgress;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import io.aeron.cluster.service.ClientSession;
import io.aeron.cluster.service.Cluster;
import io.aeron.logbuffer.Header;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.agrona.concurrent.NoOpIdleStrategy;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 以代理 Cluster 和会话驱动实际 Core 工作线程，检查在途窗口、依赖前缀、直达结算、触发单及实时边界。JUnit 扩展使用 PIPELINED；各场景按自己的产品枚举源运行。串行、Follower 及恢复比对只覆盖本文件实际构造的数据，不启动真实多成员网络集群。
 */
@org.junit.jupiter.api.extension.ExtendWith(PipelinedExecutionExtension.class)
class ClusterCommandPipelineTest {
    /**
     * 在五种衍生品中先成交形成全局 OI，再提交一项成交、一项挂单的批次；用 Owner 账户读取守卫检查准入看到已完成全局及批内前缀，两项均成功且持仓、预留正确。
     * @param product 当前场景独立产品线，实际范围由各测试枚举源限定
     */
    @ParameterizedTest
    @EnumSource(value = ProductLine.class, names = {"LINEAR_PERPETUAL", "INVERSE_PERPETUAL",
            "LINEAR_DELIVERY", "INVERSE_DELIVERY", "OPTION"})
    void proportionalOiAdmissionUsesTheCompletedGlobalAndSequentialBatchPrefix(ProductLine product) throws Exception {
        try (/* 接收交错或并发在途输入的测试 Fixture，退出资源作用域时关闭 */ Fixture live = new Fixture(product)) {
            // 当前产品对应的合约类型，决定资产、乘数和到期配置
            var type = ContractType.valueOf(product.contractTypeCode());
            // 该产品准入或流动性准备使用的资产名称
            String asset = type.isInverse() ? "BTC" : "USDT";
            // 本场景合约乘数，反向产品用一千，其他用一
            long multiplier = type.isInverse() ? 1000 : 1;
            // OI 比例限制的保底量，按反向与其他产品量纲选择
            long floor = type.isInverse() ? 20 : 200;
            live.apply(live.message(CoreMessageType.REGISTER_INSTRUMENT, 0,
                    TradingCommandCodec.encodeRegisterInstrument(new RegisterInstrumentCommand(
                            "1", "BTC", "USDT", asset, 1, 1, multiplier,
                            (type == ContractType.SPOT ? null : new CoreInstrumentRiskLimits(
                                    100_000, 10_000_000, 1_000_000, 1_000_000, floor,
                                    List.of(new CoreRiskLimitBracket(1_000_000, 10_000_000, 100_000, 50_000)))),
                            0, 0, type.isDelivery() || type.isOption() ? TIME + 100_000 : 0, type.isOption() ? 0 : -1,
                            type.isOption() ? 100 : 0), product)));
            live.apply(live.message(CoreMessageType.APPLY_MARK_PRICE, 0,
                    TradingCommandCodec.encodeApplyMarkPrice(type.isOption()
                            ? new ApplyMarkPriceCommand("1", 100, 100, 100, 1, TIME)
                            : new ApplyMarkPriceCommand("1", 100, 1, TIME))));
            // 第一组主动成交用户，选到与 maker 不同账户 Lane
            // 第二组主动用户，随后发送待测批次
            // 第二组被动成交用户编号
            // 预先提供流动性的用户编号
            long maker = 11, taker = disjointUser(maker), secondMaker = 1001, secondTaker = 1002;
            for (long /* 当前需要加资、下单或查条件单的用户编号 */ user : new long[]{maker, taker, secondMaker, secondTaker})
                live.apply(live.message(CoreMessageType.ADJUST_BALANCE, user,
                        TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand(asset, 20_000))));
            live.responses.clear();
            // 第一组先挂入的被动卖单消息
            var firstMaker = live.place(maker, "1", 101, 100, 2, CoreOrderSide.SELL);
            // 第一组立即吃单的买单消息
            var firstTaker = live.place(taker, "1", 102, 100, 2, CoreOrderSide.BUY);
            // 第二组先挂入的卖单消息
            var nextMaker = live.place(secondMaker, "1", 103, 100, 2, CoreOrderSide.SELL);
            // 本场景待提交的原始批量消息
            var batch = live.message(CoreMessageType.PLACE_ORDER_BATCH, secondTaker,
                    TradingOrderBatchCodec.encodePlaceOrderBatch(new PlaceOrderBatchCommand(List.of(
                            new PlaceOrderCommand(104, "1", CoreOrderSide.BUY, 100, 2, false, CoreMarginMode.CROSS,
                                    CorePositionSide.NET, CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "prefix-104"),
                            new PlaceOrderCommand(105, "1", CoreOrderSide.BUY, 80, 1, false, CoreMarginMode.CROSS,
                                    CorePositionSide.NET, CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "prefix-105")))));
            // 按日志先后保存的原消息列表
            var commands = List.of(firstMaker, firstTaker, nextMaker, batch);
            try (/* 可空 Owner 账户表读取守卫，存在时在作用域结束还原表 */ var guard = new OwnerAccountReadGuard(live.service.state().runtimeState())) {
                for (var /* 当前输入、比较或续扫的原 Core 消息 */ command : commands) live.service.enqueueCommittedCommand(live.session, command,
                        command.header().submittedAtEpochMillis(), 0, null);
                live.tick();
                assertThat(live.responses).hasSize(4).allSatisfy(/* 当前完成回调或响应列表中的 Core 结果 */ response ->
                        assertThat(response.commandStatus()).isEqualTo(ResponseStatus.APPLIED));
                // 批内首项成交把全局 OI 从二增到四，后项按已完成前缀投影该用户三份占用，应使用更新后的市场比例限制。
                assertThat(TradingOrderBatchCodec.decodeResult(live.responses.getLast().data()).items())
                        .hasSize(2).allSatisfy(/* 当前批量响应的一项，读取其状态、订单或明细 */ item -> assertThat(item.status()).isEqualTo(ResponseStatus.APPLIED));
            }
            // 批次全部完成后导出的第二主动用户状态，检查持仓及预留
            var user = CoreTestCompletion.userState(live.service.state(), secondTaker);
            assertThat(user.positions()).singleElement().satisfies(/* 当前用户状态中被断言的持仓视图 */ position -> assertThat(position.signedQuantitySteps()).isEqualTo(2));
            assertThat(user.reservations()).hasSize(1);
            live.service.state().assertClusterCallbackComplete();
        }
    }

    /**
     * 给 maker 增加一百五十张订单，再以六十四用户预算推进风险，检查另一账户止盈仍触发、子单产生、持仓由十减到六，并比较快照恢复哈希。
     * @param product 当前场景独立产品线，实际范围由各测试枚举源限定
     */
    @ParameterizedTest
    @EnumSource(value = ProductLine.class, names = {"LINEAR_PERPETUAL", "INVERSE_PERPETUAL",
            "LINEAR_DELIVERY", "INVERSE_DELIVERY", "OPTION"})
    void eligibleTakeProfitDoesNotWaitForBusyMakerRiskScan(ProductLine product) {
        try (/* 接收交错或并发在途输入的测试 Fixture，退出资源作用域时关闭 */ Fixture live = new Fixture(product)) {
            live.setup();
            // 当前需要加资、下单或查条件单的用户编号
            // 预先提供流动性的用户编号
            long maker = 11, user = disjointUser(maker);
            live.apply(live.place(maker, "1", 701, 100, 10, CoreOrderSide.SELL));
            live.apply(live.place(user, "1", 702, 100, 10, CoreOrderSide.BUY));
            live.apply(live.place(maker, "1", 703, 100, 4, CoreOrderSide.BUY));
            for (/* 当前初始化、查找候选或重复轮询的循环下标 */ int i = 0; i < 150; i++)
                live.apply(live.place(maker, "1", 10000 + i, 80, 1, CoreOrderSide.BUY));
            // 当前被登记或检查的条件单编号
            long id = 5706833411744856774L;
            // 当前待登记条件单视图
            var trigger = new CoreTriggerOrderStateView(id, live.product, user, "busy-risk-tp", "", "1",
                    CoreOrderSide.SELL, CoreTriggerOrderType.TAKE_PROFIT, CoreTriggerCondition.GREATER_OR_EQUAL,
                    100, 0, 0, 0, 0, 0, CoreOrderType.MARKET, CoreTimeInForce.IOC, 0, 4,
                    CoreMarginMode.CROSS, CorePositionSide.NET, CoreTriggerOrderStatus.PENDING,
                    0, 0, 0, "", 0, 0, 0, 0, 1, 0, 0);
            live.apply(live.message(CoreMessageType.PLACE_TRIGGER_ORDER, user,
                    CoreTriggerOrderCodec.encodePlacement(new com.surprising.aeron.protocol.PlaceTriggerOrderCommand(
                        trigger.triggerOrderId(), trigger.clientTriggerOrderId(), trigger.ocoGroupId(),
                                trigger.instrumentId(), trigger.side(), trigger.triggerType(),
                                trigger.triggerCondition(), trigger.triggerPriceTicks(), trigger.activationPriceTicks(),
                                trigger.callbackRatePpm(), trigger.orderType(), trigger.timeInForce(),
                                trigger.priceTicks(), trigger.quantitySteps(), trigger.marginMode(),
                                trigger.positionSide(), trigger.expiresAtEpochMillis(), trigger.priceSource()))));
            live.apply(live.message(CoreMessageType.APPLY_MARK_PRICE, 0,
                    TradingCommandCodec.encodeApplyMarkPrice(new ApplyMarkPriceCommand("1", 100, 100,
                            product == ProductLine.OPTION ? 100 : 0, 2, TIME))));
            live.apply(live.message(CoreMessageType.CONTINUE_RISK_SCAN, 0,
                    TradingCommandCodec.encodeContinueRiskScan(new ContinueRiskScanCommand(64))));
            System.err.println("DEBUG RESPONSES: " + live.responses.stream().map(/* 当前待检查状态或结果码的 Core 响应 */ r -> r.commandStatus() + ":" + r.resultCode()).toList());
            assertThat(live.responses.getLast().commandStatus()).isEqualTo(ResponseStatus.APPLIED);
            // 按所属账户读取的源条件单实际状态
            var triggerOrder = AccountLaneQueryTestSupport.read(
                    live.service.state().runtimeState(), user, () -> live.service.state().runtimeState().triggerOrder(id));
            assertThat(triggerOrder.status()).isEqualTo(CoreTriggerOrderStatus.TRIGGERED);
            // 成功触发后记录的原生子订单编号
            long child = triggerOrder.placedOrderId();
            assertThat(child).as("%s", triggerOrder).isPositive();
            // 触发结束后导出的用户状态，检查剩余持仓及预留
            var userState = CoreTestCompletion.userState(live.service.state(), user);
            assertThat(userState.positions()).singleElement()
                    .satisfies(/* 当前用户状态中被断言的持仓视图 */ position -> assertThat(position.signedQuantitySteps()).isEqualTo(6));
            assertThat(userState.reservations()).isEmpty();
            try (/* 从当前快照恢复出的独立 Core，资源结束时关闭 */ var restored = TradingCoreRuntime.fromSnapshot(product, live.service.captureSnapshot(999))) {
                assertThat(restored.tradingState().businessStateHash()).isEqualTo(live.hash());
            }
        }
    }

    /**
     * 分别让止盈或止损获胜，检查 OCO 对原子写入、兄弟单取消及快照恢复。
     * @param product 当前场景独立产品线，实际范围由各测试枚举源限定
     */
    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void atomicOcoPairPublishesTogetherAndEitherLegCancelsItsSibling(ProductLine product) throws Exception {
        verifyAtomicOcoPair(product, true);
    }

    /**
     * 检查相同两种 OCO 获胜场景，不追加快照恢复。
     * @param product 当前场景独立产品线，实际范围由各测试枚举源限定
     */
    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void atomicOcoPairWithoutSnapshot(ProductLine product) throws Exception {
        verifyAtomicOcoPair(product, false);
    }

    /**
     * 先用空客户号让整对拒绝且两条均不落地，再成功写入并重放同一对；分别执行两个获胜分支，检查另一条取消，按参数决定恢复比较。
     * @param product 当前场景独立产品线，实际范围由各测试枚举源限定
     * @param recovery 是否额外比较快照恢复状态
     */
    private void verifyAtomicOcoPair(ProductLine product, boolean recovery) throws Exception {
        for (boolean /* 本轮是否设置止盈阈值先满足，否则让止损获胜 */ takeProfitWins : new boolean[]{true, false}) {
            try (/* 接收交错或并发在途输入的测试 Fixture，退出资源作用域时关闭 */ Fixture live = new Fixture(product)) {
                live.setup();
                // 当前需要加资、下单或查条件单的用户编号
                // 预先提供流动性的用户编号
                long maker = 11, user = disjointUser(maker);
                if (product == ProductLine.SPOT) live.apply(live.message(CoreMessageType.ADJUST_BALANCE, maker,
                        TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("BTC", 100))));
                live.apply(live.place(maker, "1", 101, 100, 10, CoreOrderSide.SELL));
                live.apply(live.place(user, "1", 102, 100, 10, CoreOrderSide.BUY));
                live.apply(live.place(maker, "1", 103, 100, 1, CoreOrderSide.BUY));
                // 待原子登记的止盈条件单视图
                var tp = pairLeg(product, user, 6174311206148487272L, "pair-tp", CoreTriggerOrderType.TAKE_PROFIT,
                        takeProfitWins ? 100 : 120);
                // 待原子登记的止损条件单视图
                var sl = pairLeg(product, user, 9014874673242113703L, "pair-sl", CoreTriggerOrderType.STOP_LOSS,
                        takeProfitWins ? 80 : 100);
                live.apply(live.message(CoreMessageType.PLACE_TRIGGER_OCO_PAIR, user,
                        CoreTriggerOrderCodec.encodePlacementList(List.of(tp,
                                pairLeg(product, user, 9014874673242113703L, " ", CoreTriggerOrderType.STOP_LOSS,
                                        sl.triggerPriceTicks())).stream().map(placement -> new com.surprising.aeron.protocol.PlaceTriggerOrderCommand(
                        placement.triggerOrderId(), placement.clientTriggerOrderId(), placement.ocoGroupId(),
                                placement.instrumentId(), placement.side(), placement.triggerType(),
                                placement.triggerCondition(), placement.triggerPriceTicks(),
                                placement.activationPriceTicks(), placement.callbackRatePpm(), placement.orderType(),
                                placement.timeInForce(), placement.priceTicks(), placement.quantitySteps(),
                                placement.marginMode(), placement.positionSide(), placement.expiresAtEpochMillis(),
                                placement.priceSource())).toList())));
                assertThat(live.responses.getLast().commandStatus()).isEqualTo(ResponseStatus.REJECTED);
                assertThat(CoreTestCompletion.triggerState(live.service.state(), user, 6174311206148487272L)).isNull();
                assertThat(CoreTestCompletion.triggerState(live.service.state(), user, 9014874673242113703L)).isNull();
                // 同时携带两条 OCO 条件单的原命令
                var pair = live.message(CoreMessageType.PLACE_TRIGGER_OCO_PAIR, user,
                        CoreTriggerOrderCodec.encodePlacementList(List.of(tp,
                                sl).stream().map(placement -> new com.surprising.aeron.protocol.PlaceTriggerOrderCommand(
                        placement.triggerOrderId(), placement.clientTriggerOrderId(), placement.ocoGroupId(),
                                placement.instrumentId(), placement.side(), placement.triggerType(),
                                placement.triggerCondition(), placement.triggerPriceTicks(),
                                placement.activationPriceTicks(), placement.callbackRatePpm(), placement.orderType(),
                                placement.timeInForce(), placement.priceTicks(), placement.quantitySteps(),
                                placement.marginMode(), placement.positionSide(), placement.expiresAtEpochMillis(),
                                placement.priceSource())).toList()));
                live.apply(pair);
                // 首次原子写入 OCO 对得到的 Core 响应
                var placed = live.responses.getLast();
                assertThat(placed.commandStatus()).isEqualTo(ResponseStatus.APPLIED);
                assertThat(CoreTriggerOrderCodec.decodeList(placed.data())).hasSize(2);
                live.apply(pair);
                assertThat(live.responses.getLast().data()).isEqualTo(placed.data());
                // 本轮条件满足并执行的 OCO 腿编号
                long winner = takeProfitWins ? 6174311206148487272L : 9014874673242113703L;
                // 本轮应被获胜腿取消的兄弟条件单编号
                long canceled = takeProfitWins ? 9014874673242113703L : 6174311206148487272L;
                live.apply(live.message(CoreMessageType.EXECUTE_TRIGGER_ORDER, 0,
                        CoreTriggerOrderCodec.encodeExecute(winner, 1, 100, TIME)));
                assertThat(CoreTestCompletion.triggerState(live.service.state(), user, winner).status())
                        .isEqualTo(CoreTriggerOrderStatus.TRIGGERED);
                assertThat(CoreTestCompletion.triggerState(live.service.state(), user, canceled).status())
                        .isEqualTo(CoreTriggerOrderStatus.CANCELED);
                if (recovery) try (/* 从当前快照恢复出的独立 Core，资源结束时关闭 */ var restored = TradingCoreRuntime.fromSnapshot(product, live.service.captureSnapshot(100))) {
                    assertThat(restored.tradingState().businessStateHash()).isEqualTo(live.hash());
                }
            }
        }
    }

    /**
     * 构造共享 atomic-pair 分组的卖出条件单，止盈用大于等于、止损用小于等于，子单固定限价 IOC、价格一百、数量一。
     * @param product 当前场景独立产品线，实际范围由各测试枚举源限定
     * @param user 请求所属用户编号
     * @param id 要派生或登记的测试编号
     * @param client 条件单客户号
     * @param type 止盈或止损类别，决定触发比较方向
     * @param threshold 条件单触发阈值，单位为价格刻度
     */
    private static CoreTriggerOrderStateView pairLeg(ProductLine product, long user, long id,
                                                     String client, CoreTriggerOrderType type, long threshold) {
        return new CoreTriggerOrderStateView(id, product, user, client, "atomic-pair", "1",
                CoreOrderSide.SELL, type, type == CoreTriggerOrderType.TAKE_PROFIT
                ? CoreTriggerCondition.GREATER_OR_EQUAL : CoreTriggerCondition.LESS_OR_EQUAL,
                threshold, 0, 0, 0, 0, 0, CoreOrderType.LIMIT, CoreTimeInForce.IOC, 100, 1,
                CoreMarginMode.CROSS, CorePositionSide.NET, CoreTriggerOrderStatus.PENDING,
                0, 0, 0, "", 0, 0, 0, 0, 1, 0, 0);
    }

    /**
     * 阻塞 Matcher 后排两个独立账户命令，放行并等结算完成及生产者释放槽；检查一次 Owner 轮询收两条有序终态，账户和恢复结果仍与逐条执行一致。
     * @param product 当前场景独立产品线，实际范围由各测试枚举源限定
     */
    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void consecutiveReadyHeadsRetireInOnePollWithoutMergingCommandBoundaries(ProductLine product) throws Exception {
        // 阻塞工作线程后用于放行的门闩或 Future，清理路径负责释放
        var release = new CountDownLatch(1);
        try (/* 接收交错或并发在途输入的测试 Fixture，退出资源作用域时关闭 */ Fixture live = new Fixture(product); /* 接收相同原消息但逐条等完成的串行对照 Fixture */ Fixture serial = new Fixture(product)) {
            serial.applyAll(live.setup());
            // 当前 Fixture 内的实际 Core 运行时
            var state = live.service.state();
            // 确认 Matcher 已执行到阻塞点的门闩或 Future
            var entered = new CountDownLatch(1);
            // 在 Matcher 提交栅栏执行的测试任务 Future，放行后检查其结果
            var gate = state.matcherPipeline().readAtSubmissionFence(
                    state.matchingAdapter().matcherShardId("1"), () -> {
                        entered.countDown();
                        try {
                            if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("matcher timeout");
                        } catch (/* 等待测试门闩被中断的异常，按原测试分支转为断言或运行失败 */ InterruptedException failure) { throw new AssertionError(failure); }
                        return true;
                    });
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            // 当前先登记的原命令消息
            var first = live.place(11, "1", 81000, 80, 1, CoreOrderSide.BUY);
            // 当前后登记的独立原命令消息
            var second = live.place(disjointUser(11), "1", 81001, 80, 1, CoreOrderSide.BUY);
            live.send(first);
            live.send(second);
            // 第一条命令仍占用的原命令槽
            var firstPending = state.pendingMatching().get(state.matchingSequence(first.header().commandId()));
            // 第二条命令仍占用的原命令槽
            var secondPending = state.pendingMatching().get(state.matchingSequence(second.header().commandId()));
            live.progressUntil(() -> firstPending.settlementEvent() != null
                    && firstPending.settlementEvent().dispatched()
                    && secondPending.settlementEvent() != null && secondPending.settlementEvent().dispatched());
            release.countDown();
            // 当前有界等待的 nanoTime 截止点
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            // 账户结算完成与 Matcher 释放提交槽是两件事。
            // 先等生产者释放两槽，再验证一次轮询连续退休。
            while ((!firstPending.settlementEvent().complete() || !secondPending.settlementEvent().complete()
                    || firstPending.submittedMatcherShard() != -1 || secondPending.submittedMatcherShard() != -1)
                    && System.nanoTime() < deadline) Thread.onSpinWait();
            assertThat(firstPending.settlementEvent().complete()).isTrue();
            assertThat(secondPending.settlementEvent().complete()).isTrue();
            assertThat(firstPending.submittedMatcherShard()).isEqualTo(-1);
            assertThat(secondPending.submittedMatcherShard()).isEqualTo(-1);
            assertThat(live.responses).isEmpty();
            live.service.pollCommands();
            assertThat(live.responses).hasSize(2);
            assertThat(live.service.pendingCommandCount()).isZero();
            assertThat(live.responses.get(0).committedCoreSequence())
                    .isLessThan(live.responses.get(1).committedCoreSequence());
            serial.apply(first);
            serial.apply(second);
            assertThat(live.hash()).isEqualTo(serial.hash());
            assertThat(live.service.state().tradingState().users())
                    .isEqualTo(serial.service.state().tradingState().users());
            try (/* 从当前快照恢复出的独立 Core，资源结束时关闭 */ var restored = TradingCoreRuntime.fromSnapshot(product, state.snapshot())) {
                assertThat(restored.tradingState().businessStateHash()).isEqualTo(live.hash());
            }
            assertThat(gate.join()).isTrue();
        } finally { release.countDown(); }
    }

    /**
     * 分别检查普通及批量挂单，撮合前保留准入回执，账户尚无正式订单；完成后核对回执对应的订单身份、品种或价量，并比较串行及恢复状态。
     * @param product 当前场景独立产品线，实际范围由各测试枚举源限定
     */
    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void restingOrdersKeepTheirAdmissionVersionThroughOrderedCommit(ProductLine product) throws Exception {
        for (boolean /* 本轮是否使用二十项批次，否则检查普通下单 */ batch : new boolean[]{false, true}) {
            // 阻塞工作线程后用于放行的门闩或 Future，清理路径负责释放
            var release = new CountDownLatch(1);
            try (/* 接收交错或并发在途输入的测试 Fixture，退出资源作用域时关闭 */ Fixture live = new Fixture(product); /* 接收相同原消息但逐条等完成的串行对照 Fixture */ Fixture serial = new Fixture(product)) {
                serial.applyAll(live.setup());
                // 当前 Fixture 内的实际 Core 运行时
                var state = live.service.state();
                // 确认 Matcher 已执行到阻塞点的门闩或 Future
                var entered = new CountDownLatch(1);
                // 在 Matcher 提交栅栏执行的测试任务 Future，放行后检查其结果
                var gate = state.matcherPipeline().readAtSubmissionFence(
                        state.matchingAdapter().matcherShardId("1"), () -> {
                            entered.countDown();
                            try { if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("matcher timeout"); }
                            catch (/* 等待测试门闩被中断的异常，按原测试分支转为断言或运行失败 */ InterruptedException failure) { throw new AssertionError(failure); }
                            return true;
                        });
                assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
                // 当前输入、比较或续扫的原 Core 消息
                var command = batch ? live.placeBatch(11, "1", 1000)
                        : live.place(11, "1", 1000, 80, 1, CoreOrderSide.BUY);
                try {
                    live.send(command);
                    // 当前原命令登记得到的完整 Core 序号
                    long sequence = state.matchingSequence(command.header().commandId());
                    live.progressUntil(() -> {
                        // 仍在生命周期中的原命令槽
                        var pending = state.pendingMatching().get(sequence);
                        // 普通、批量或当前子项持有的直达结算事件
                        var event = batch ? pending.orderBatch.settlementEvent : pending.settlementEvent();
                        // 撮合前已经可见的准入回执，测试保留它检查身份及稳定输入
                        var receipt = batch
                                ? pending.orderBatch.preparedAdmittedOrders[0]
                                : pending.admittedPlaceOrder();
                        return event != null && event.dispatched() && receipt != null;
                    });
                    // 仍在生命周期中的原命令槽
                    var pending = state.pendingMatching().get(sequence);
                    // 批次所属项保存的可空准入订单副本
                    var admitted = batch ? pending.orderBatch.preparedAdmittedOrders[0] : null;
                    // 普通下单已解析的准入输入，批次分支为空
                    var resolved = batch ? null : pending.admittedPlaceOrder();
                    assertThat(batch ? admitted : resolved).isNotNull();
                    // 普通准入时确定的订单编号，后续不能重新分配
                    long resolvedOrderId = batch ? 0 : resolved.orderId();
                    // 普通准入时选定的品种对象，提交后应仍是同一对象
                    var resolvedInstrument = batch ? null : resolved.instrument();
                    if (batch) assertThat(admitted.createdAtEpochMillis())
                            .isEqualTo(command.header().submittedAtEpochMillis());
                    assertThat(AccountLaneQueryTestSupport.read(state.runtimeState(), 11, () -> state.runtimeState().order(1000)))
                            .as("admission stays Lane-owned until ordered settlement").isNull();
                    release.countDown();
                    live.tick();
                    assertThat(gate.join()).isTrue();
                    if (batch) {
                        // 在所属账户线程读到的正式订单，用于比较准入输入
                        var actual = AccountLaneQueryTestSupport.read(state.runtimeState(), 11, () -> state.runtimeState().order(1000));
                        assertThat(actual.orderId()).isEqualTo(1000);
                        assertThat(actual.priceTicks()).isEqualTo(admitted.priceTicks());
                        assertThat(actual.remainingQuantitySteps()).isEqualTo(admitted.remainingQuantitySteps());
                    } else {
                        // 在所属账户线程读到的正式订单，用于比较准入输入
                        var actual = AccountLaneQueryTestSupport.read(state.runtimeState(), 11, () -> state.runtimeState().order(1000));
                        assertThat(actual.orderId()).isEqualTo(resolvedOrderId);
                        assertThat(actual.instrument()).isSameAs(resolvedInstrument);
                    }
                    serial.apply(command);
                    assertThat(live.responses).hasSize(1);
                    assertThat(live.hash()).isEqualTo(serial.hash());
                    try (/* 从当前快照恢复出的独立 Core，资源结束时关闭 */ var restored = TradingCoreRuntime.fromSnapshot(product, state.snapshot())) {
                        assertThat(restored.tradingState().businessStateHash()).isEqualTo(live.hash());
                    }
                } finally { release.countDown(); }
            }
        }
    }

    /**
     * 分别检查单笔和二十项批次，放行 Matcher 后不再推进 Owner，账户事件仍须自行完成、终态客户身份先释放；随后 Owner 发布保留墓碑，结果与串行和恢复一致。
     * @param product 当前场景独立产品线，实际范围由各测试枚举源限定
     */
    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void matcherPublishesOrdinaryAndBatchFillsToLanesWithoutOwnerDrainingResults(ProductLine product) throws Exception {
        for (boolean /* 本轮是否走二十项直达批次，否则是一笔普通成交 */ batch : new boolean[]{false, true}) {
            // 阻塞工作线程后用于放行的门闩或 Future，清理路径负责释放
            var release = new CountDownLatch(1);
            try (/* 接收交错或并发在途输入的测试 Fixture，退出资源作用域时关闭 */ Fixture live = new Fixture(product); /* 接收相同原消息但逐条等完成的串行对照 Fixture */ Fixture serial = new Fixture(product)) {
                serial.applyAll(live.setup());
                // 预先提供流动性的用户编号
                long maker = disjointUser(11);
                if (product == ProductLine.SPOT) {
                    // 准备对手流动性或测试账户的余额调整消息
                    var deposit = live.message(CoreMessageType.ADJUST_BALANCE, maker,
                            TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("BTC", 100)));
                    live.apply(deposit); serial.apply(deposit);
                }
                // 开始测试前已完成的挂单或批次消息
                var resting = live.place(maker, "1", 900, 80, 20, CoreOrderSide.SELL);
                live.apply(resting); serial.apply(resting);
                live.responses.clear();
                // 当前 Fixture 内的实际 Core 运行时
                var state = live.service.state();
                // 确认 Matcher 已执行到阻塞点的门闩或 Future
                var entered = new CountDownLatch(1);
                // 在 Matcher 提交栅栏执行的测试任务 Future，放行后检查其结果
                var gate = state.matcherPipeline().readAtSubmissionFence(state.matchingAdapter().matcherShardId("1"), () -> {
                    entered.countDown();
                    try {
                        if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("matcher gate timeout");
                    } catch (/* 等待测试门闩被中断的异常，按原测试分支转为断言或运行失败 */ InterruptedException failure) { throw new IllegalStateException(failure); }
                    return true;
                });
                assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
                // 当前输入、比较或续扫的原 Core 消息
                var command = batch ? live.placeBatch(11, "1", 1000)
                        : live.place(11, "1", 1000, 80, 1, CoreOrderSide.BUY);
                live.send(command);
                // 当前原命令登记得到的完整 Core 序号
                long sequence = state.matchingSequence(command.header().commandId());
                live.progressUntil(() -> {
                    // 仍在生命周期中的原命令槽
                    var pending = state.pendingMatching().get(sequence);
                    // 普通、批量或当前子项持有的直达结算事件
                    var event = batch ? pending.orderBatch.settlementEvent : pending.settlementEvent();
                    // 撮合前已经可见的准入回执，测试保留它检查身份及稳定输入
                    var receipt = batch
                            ? pending.orderBatch.preparedAdmittedOrders[0]
                            : pending.admittedPlaceOrder();
                    return event != null && event.dispatched() && receipt != null;
                });
                // 仍在生命周期中的原命令槽
                var pending = state.pendingMatching().get(sequence);
                // 普通、批量或当前子项持有的直达结算事件
                var event = batch ? pending.orderBatch.settlementEvent : pending.settlementEvent();
                assertThat(event.direct()).isTrue();
                assertThat(event.ready()).isFalse();
                // 只保留准入回执供观察，让 Matcher 和账户工作线程独立向前推进。
                // 批量准入保存的订单副本，普通单分支为空
                var admittedOrder = batch ? pending.orderBatch.preparedAdmittedOrders[0] : null;
                // 普通单已解析的准入输入，批次分支为空
                var resolvedOrder = batch ? null : pending.admittedPlaceOrder();
                assertThat(batch ? admittedOrder : resolvedOrder).isNotNull();
                // 撮合前保存的批量准入订单值，用于比较结算没有改写该回执
                var orderBeforeSettlement = batch ? admittedOrder.snapshot() : null;
                assertThat(AccountLaneQueryTestSupport.read(state.runtimeState(), 11, () -> state.runtimeState().order(1000))).isNull();
                assertThat(AccountLaneQueryTestSupport.read(state.runtimeState(), 11, () -> state.runtimeState().reservation(1000))).isNull();
                release.countDown();
                // 此处不调用 Owner 推进或收集；Matcher 和账户必须自己完成后续交接。
                // 当前有界等待的 nanoTime 截止点
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while (!event.complete() && System.nanoTime() < deadline) Thread.onSpinWait();
                assertThat(event.complete()).isTrue();
                if (batch) assertThat(admittedOrder).isEqualTo(orderBeforeSettlement);
                else assertThat(event.admittedOrder())
                        .as("normal admission does not duplicate the Lane-owned runtime order")
                        .isNull();
                assertThat(AccountLaneQueryTestSupport.read(state.runtimeState(), 11, () -> state.runtimeState().order(1000))).isNull();
                assertThat(AccountLaneQueryTestSupport.read(state.runtimeState(), 11, () -> state.runtimeState().reservation(1000))).isNull();
                if (batch) {
                    assertThat(pending.orderBatch.nextIndex).as("submission cursor belongs to Owner").isZero();
                    assertThat(state.batches().orderBatchMatcherShard(pending.orderBatch))
                            .isEqualTo(state.matchingAdapter().matcherShardId("1"));
                }
                assertThat(state.pendingMatching().required(sequence).hasMatchingCompletion()).isFalse();
                assertThat(live.responses).isEmpty();
                assertThat(event.plan().tradeCount()).isPositive();
                for (/* 当前需要核对客户身份已释放的子订单下标 */ int item = 0; item < (batch ? 20 : 1); item++) {
                    // 当前子订单由批次或普通前缀派生的客户号
                    String clientKey = (batch ? "batch-" : "order-") + (1000 + item);
                    assertThat(AccountLaneQueryTestSupport.read(state.runtimeState(), 11, () -> state.identities().findClientKey(11, clientKey)))
                            .as("Lane retires terminal client identity before Owner drains or publishes")
                            .isNull();
                }
                live.tick(); serial.apply(command);
                assertThat(gate.join()).isTrue();
                assertThat(live.responses).hasSize(1);
                assertThat(state.terminalRetention().containsOrder(999_999, 11, (batch ? "batch-" : "order-") + 1000))
                        .as("ordered publication retains the client ID tombstone after Lane freed the identity")
                        .isTrue();
                assertThat(live.hash()).isEqualTo(serial.hash());
                assertThat(state.tradingState().users()).isEqualTo(serial.service.state().tradingState().users());
                try (/* 从当前快照恢复出的独立 Core，资源结束时关闭 */ var restored = TradingCoreRuntime.fromSnapshot(product, live.service.captureSnapshot(101))) {
                    assertThat(restored.tradingState().businessStateHash()).isEqualTo(live.hash());
                }
            } finally { release.countDown(); }
        }
    }

    /**
     * 首条日志之前安装空完成信号并探测，要求无完成可取、轮询返回零，空闲入口不报未激活发布器错误。
     * @param product 当前场景独立产品线，实际范围由各测试枚举源限定
     */
    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void idleProbeBeforeTheFirstLogDoesNotRequireAnActivatedCommitJournal(ProductLine product) {
        try (/* 接收交错或并发在途输入的测试 Fixture，退出资源作用域时关闭 */ Fixture live = new Fixture(product)) {
            live.service.ownerCompletionSignal(() -> {});
            assertThat(live.service.ownerCompletionAvailable()).isFalse();
            assertThat(live.service.pollCommands()).isZero();
            assertThat(live.service.ownerCompletionAvailable()).isFalse();
        }
    }

    /**
     * 把同账户下单批次及撤单批次先入队，确认尚无响应；统一轮询后两终态、账户及恢复哈希与逐条执行一致。
     * @param product 当前场景独立产品线，实际范围由各测试枚举源限定
     */
    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void deferredIngressPreservesDependentBatchOrderAndSnapshot(ProductLine product) {
        try (/* 接收交错或并发在途输入的测试 Fixture，退出资源作用域时关闭 */ Fixture live = new Fixture(product); /* 接收相同原消息但逐条等完成的串行对照 Fixture */ Fixture serial = new Fixture(product)) {
            serial.applyAll(live.setup());
            // 按日志先后保存的原消息列表
            var commands = List.of(live.placeBatch(11, "1", 71000),
                    live.cancelBatch(11, 71000));
            for (var /* 当前输入、比较或续扫的原 Core 消息 */ command : commands) {
                live.service.enqueueCommittedCommand(live.session, command,
                        command.header().submittedAtEpochMillis(), 0, null);
                serial.apply(command);
            }
            assertThat(live.responses).isEmpty();
            live.tick();
            assertThat(live.responses).hasSize(2);
            assertThat(live.hash()).isEqualTo(serial.hash());
            assertThat(live.service.state().tradingState().users()).isEqualTo(serial.service.state().tradingState().users());
            try (/* 从当前快照恢复出的独立 Core，资源结束时关闭 */ var restored = TradingCoreRuntime.fromSnapshot(product, live.service.captureSnapshot(881))) {
                assertThat(restored.tradingState().businessStateHash()).isEqualTo(live.hash());
            }
        }
    }

    /**
     * 临时使用两个 Matcher，先按真实产品准入测出单笔冻结额，再只留够一单资金；阻塞首单时后单不能准入，完成后仅首单成功。撤首单后另分片可复用余额，串行及恢复结果一致。
     * @param product 当前场景独立产品线，实际范围由各测试枚举源限定
     */
    @ParameterizedTest
    @EnumSource(ProductLine.class)
    @org.junit.jupiter.api.parallel.ResourceLock(org.junit.jupiter.api.parallel.Resources.SYSTEM_PROPERTIES)
    void sharedFundsRemainOneAccountAcrossMatcherPartitions(ProductLine product) throws Exception {
        // 本场景临时设置的 Matcher 数量 JVM 属性名
        String key = "surprising.aeron.matching-engines";
        // 进入场景前的全局配置原值，finally 恢复
        String previous = System.getProperty(key);
        System.setProperty(key, "2");
        // 阻塞工作线程后用于放行的门闩或 Future，清理路径负责释放
        var release = new CountDownLatch(1);
        try (/* 接收交错或并发在途输入的测试 Fixture，退出资源作用域时关闭 */ Fixture live = new Fixture(product); /* 接收相同原消息但逐条等完成的串行对照 Fixture */ Fixture serial = new Fixture(product)) {
            // 当前第一个品种名称
            String firstSymbol = "1";
            // 本场景使用的第二个品种名称，是否不同分片由选择方法决定
            String secondSymbol = differentMatcherSymbol(live.service.state(), firstSymbol);
            serial.applyAll(live.setup(secondSymbol));
            // 该产品准入或流动性准备使用的资产名称
            String asset = ContractType.valueOf(product.contractTypeCode()).isInverse() ? "BTC" : "USDT";
            // 由真实产品准入计算一笔订单冻结额；不把现货名义金额套用到衍生品保证金。
            var probe = live.place(11, firstSymbol, 900, 80, 1, CoreOrderSide.BUY);
            live.apply(probe); serial.apply(probe);
            // 真实探测订单被冻结的资产最小单位数量
            long required = live.service.state().tradingState().user(11).balances().get(asset).lockedUnits();
            assertThat(required).isPositive();
            // 撤掉资金探测单以释放其预留的消息
            var cancelProbe = live.cancel(11, 900);
            live.apply(cancelProbe); serial.apply(cancelProbe);
            // 减少账户余额，使后续下单资金不足或只够一单的调整消息
            var withdraw = live.message(CoreMessageType.ADJUST_BALANCE, 11,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand(asset, required - 20_000)));
            live.apply(withdraw); serial.apply(withdraw);
            live.responses.clear();
            // 当前先登记的原命令消息
            var first = live.place(11, firstSymbol, 1000, 80, 1, CoreOrderSide.BUY);
            // 当前后登记的独立原命令消息
            var second = live.place(11, secondSymbol, 2000, 80, 1, CoreOrderSide.BUY);
            // 确认 Matcher 已执行到阻塞点的门闩或 Future
            var entered = new CountDownLatch(1);
            // 在 Matcher 提交栅栏执行的测试任务 Future，放行后检查其结果
            var gate = live.service.state().matcherPipeline().readAtSubmissionFence(
                    live.service.state().matchingAdapter().matcherShardId(firstSymbol), () -> {
                        entered.countDown();
                        try { if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("matcher gate timeout"); }
                        catch (/* 等待测试门闩被中断的异常，按原测试分支转为断言或运行失败 */ InterruptedException failure) { throw new IllegalStateException(failure); }
                        return true;
                    });
            try {
                assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
                live.send(first); live.send(second);
                for (/* 当前初始化、查找候选或重复轮询的循环下标 */ int i = 0; i < 20; i++) live.service.pollCommands();
                assertThat(live.responses).isEmpty();
                assertThat(live.service.state().matchingSequence(second.header().commandId()))
                        .as("shared funds must wait for the earlier account mutation").isZero();
            } finally { release.countDown(); }
            live.tick(); serial.apply(first); serial.apply(second);
            assertThat(gate.join()).isTrue();
            assertThat(live.responses).hasSize(2);
            assertThat(live.responses.getFirst().commandStatus()).isEqualTo(ResponseStatus.APPLIED);
            assertThat(live.responses.getLast().commandStatus()).isEqualTo(ResponseStatus.REJECTED);
            assertThat(live.service.state().tradingState().user(11).balances().get(asset).availableUnits()).isZero();
            assertThat(live.hash()).isEqualTo(serial.hash());
            // 取消 A 的订单后 B 可以使用释放的资金；快照恢复也必须保留这份共享余额。
            var cancel = live.cancel(11, 1000);
            live.apply(cancel); serial.apply(cancel);
            try (/* 从当前快照恢复出的独立 Core，资源结束时关闭 */ var restored = TradingCoreRuntime.fromSnapshot(product, live.service.captureSnapshot(100))) {
                // 已释放资金或完成恢复后再次尝试的新订单消息
                var retry = live.place(11, secondSymbol, 3000, 80, 1, CoreOrderSide.BUY);
                live.apply(retry); serial.apply(retry);
                assertThat(CoreTestCompletion.applyAsynchronously(restored, retry, retry.header().submittedAtEpochMillis(), 0).commandStatus()).isEqualTo(ResponseStatus.APPLIED);
                assertThat(restored.tradingState().businessStateHash()).isEqualTo(live.hash()).isEqualTo(serial.hash());
                assertThat(restored.tradingState().user(11).balances().get(asset).lockedUnits()).isEqualTo(required);
            }
        } finally {
            release.countDown();
            if (previous == null) System.clearProperty(key); else System.setProperty(key, previous);
        }
    }

    /**
     * 在真实 Matcher 注册集合注入不一致引发原生拒绝；检查资金、预留、待槽及 Owner 访问权清理，恢复后可重新下单。
     * @param product 当前场景独立产品线，实际范围由各测试枚举源限定
     */
    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void nativeRejectionReleasesFundsInTheAccountLane(ProductLine product) throws Exception {
        try (/* 接收交错或并发在途输入的测试 Fixture，退出资源作用域时关闭 */ Fixture live = new Fixture(product)) {
            live.setup();
            // 实际 Matcher 适配器，用于在原生边界注入拒绝
            var adapter = live.service.state().matchingAdapter();
            // 待故障注入品种实际路由到的 Matcher 分片号
            int shardId = adapter.matcherShardId("1");
            // 适配器品种名称到编号表的反射字段
            var symbolsField = adapter.getClass().getDeclaredField("symbols");
            symbolsField.setAccessible(true);
            // 实际品种字符串到原生编号的映射
            @SuppressWarnings("unchecked")
            var symbols = (java.util.Map<String, Integer>) symbolsField.get(adapter);
            // 各 Matcher 命令作用域数组的反射字段
            var scopesField = adapter.getClass().getDeclaredField("shardCommandScopes");
            scopesField.setAccessible(true);
            // 目标分片持有的命令作用域
            Object scope = ((Object[]) scopesField.get(adapter))[shardId];
            // 该作用域原生品种注册集合的反射字段
            var registeredField = scope.getClass().getDeclaredField("registeredSymbols");
            registeredField.setAccessible(true);
            // 测试临时改写的原生品种已注册集合，finally 移除注入值
            var registered = (org.eclipse.collections.impl.set.mutable.primitive.IntHashSet)
                    registeredField.get(scope);
            // 故障注入使 native 返回真实的 ORDER_BOOK_ID 拒绝；不伪造成交或资金结果。
            int instrumentId = symbols.get("1");
            live.service.state().matcherPipeline().call(shardId, () -> registered.add(instrumentId),
                    TradingCoreRuntime.MATCHING_AWAIT_TIMEOUT_NANOS);
            // 故障注入前的请求账户全部余额，拒单后应完全恢复
            var before = live.service.state().tradingState().user(11).balances();
            // 需要触发原生拒绝的原始下单消息
            var request = live.place(11, "1", 1000, 80, 1, CoreOrderSide.BUY);
            try { live.apply(request); }
            finally { live.service.state().matcherPipeline().call(shardId, () -> registered.remove(instrumentId),
                    TradingCoreRuntime.MATCHING_AWAIT_TIMEOUT_NANOS); }
            assertThat(live.responses).hasSize(1);
            assertThat(live.responses.getFirst().commandStatus()).isEqualTo(ResponseStatus.REJECTED);
            assertThat(live.responses.getFirst().resultCode()).isEqualTo(CoreResultCode.MATCHING_REJECTED);
            assertThat(live.service.state().tradingState().user(11).balances()).isEqualTo(before);
            assertThat(live.service.state().tradingState().user(11).reservations()).isEmpty();
            assertThat(live.service.state().pendingMatching().size()).isZero();
            // Owner 是否持有账户访问权的反射字段
            assertThat(live.service.state().runtimeState().ownerLaneAccess()).isFalse();
            try (/* 从当前快照恢复出的独立 Core，资源结束时关闭 */ var restored = TradingCoreRuntime.fromSnapshot(product, live.service.captureSnapshot(100))) {
                assertThat(restored.tradingState().businessStateHash()).isEqualTo(live.hash());
                // 已释放资金或完成恢复后再次尝试的新订单消息
                var retry = live.place(11, "1", 2000, 80, 1, CoreOrderSide.BUY);
                live.apply(retry);
                assertThat(CoreTestCompletion.applyAsynchronously(restored, retry,
                        retry.header().submittedAtEpochMillis(), 0).commandStatus()).isEqualTo(ResponseStatus.APPLIED);
                assertThat(restored.tradingState().businessStateHash()).isEqualTo(live.hash());
            }
        }
    }

    /**
     * 在衍生品逐仓持仓中检查保证金增减、极值拒绝、串行及恢复状态。
     * @param product 当前场景独立产品线，实际范围由各测试枚举源限定
     */
    @ParameterizedTest
    @EnumSource(value = ProductLine.class, names = "SPOT", mode = EnumSource.Mode.EXCLUDE)
    void isolatedMarginChangesStayInTheAccountLane(ProductLine product) throws Exception {
        verifyIsolatedMarginChanges(product, true);
    }

    /**
     * 检查同一逐仓保证金场景，不追加恢复比较。
     * @param product 当前场景独立产品线，实际范围由各测试枚举源限定
     */
    @ParameterizedTest
    @EnumSource(value = ProductLine.class, names = "SPOT", mode = EnumSource.Mode.EXCLUDE)
    void isolatedMarginChangesWithoutSnapshot(ProductLine product) throws Exception {
        verifyIsolatedMarginChanges(product, false);
    }

    /**
     * 先成交建立双边逐仓持仓，再依次调整正负十及正负最大值；小额成功并互相抵消，极值拒绝，余额和持仓回到原值，按参数决定恢复比较。
     * @param product 当前场景独立产品线，实际范围由各测试枚举源限定
     * @param recovery 是否额外比较快照恢复状态
     */
    private void verifyIsolatedMarginChanges(ProductLine product, boolean recovery) throws Exception {
        try (/* 接收交错或并发在途输入的测试 Fixture，退出资源作用域时关闭 */ Fixture live = new Fixture(product); /* 接收相同原消息但逐条等完成的串行对照 Fixture */ Fixture serial = new Fixture(product)) {
            serial.applyAll(live.setup());
            for (boolean /* 本轮是否准备主动买单，否则先准备被动卖单 */ buy : new boolean[]{false, true}) {
                // 用来建立双边逐仓持仓的原下单消息
                var order = live.message(CoreMessageType.PLACE_ORDER, buy ? 11 : disjointUser(11),
                        TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(buy ? 1001 : 1000,
                                "1", buy ? CoreOrderSide.BUY : CoreOrderSide.SELL, 100, 10, false,
                                CoreMarginMode.ISOLATED, CorePositionSide.NET, CoreOrderType.LIMIT,
                                CoreTimeInForce.GTC, false, buy ? "margin-buy" : "margin-sell")));
                live.apply(order); serial.apply(order);
                assertThat(live.responses.getLast().commandStatus()).isEqualTo(ResponseStatus.APPLIED);
            }
            // 增减逐仓保证金前导出的用户状态，最后余额及持仓应回到它的值
            var before = live.service.state().tradingState().user(11);
            for (long /* 本轮逐仓保证金调整量，含可恢复小额和拒绝极值 */ units : new long[]{10, -10, Long.MAX_VALUE, -Long.MAX_VALUE}) {
                // 本轮保证金或杠杆设置的原命令
                var change = live.message(CoreMessageType.ADJUST_POSITION_MARGIN, 11,
                        TradingCommandCodec.encodeAdjustPositionMargin(new AdjustPositionMarginCommand("1", CorePositionSide.NET, units)));
                live.apply(change); serial.apply(change);
                assertThat(live.responses.getLast().commandStatus())
                        .isEqualTo(Math.abs(units) == 10 ? ResponseStatus.APPLIED : ResponseStatus.REJECTED);
            }
            assertThat(live.service.state().tradingState().user(11).balances()).isEqualTo(before.balances());
            assertThat(live.service.state().tradingState().user(11).positions()).isEqualTo(before.positions());
            assertThat(live.hash()).isEqualTo(serial.hash());
            if (recovery) {
                try (/* 从当前快照恢复出的独立 Core，资源结束时关闭 */ var restored = TradingCoreRuntime.fromSnapshot(product, live.service.captureSnapshot(100))) {
                    assertThat(restored.tradingState().businessStateHash()).isEqualTo(live.hash());
                }
            }
        }
    }

    /**
     * 重复切换双向和单向模式不改余额；有活动订单时切换被拒且哈希不变，撤单后可再切换。
     * @param product 当前场景独立产品线，实际范围由各测试枚举源限定
     */
    @ParameterizedTest
    @EnumSource(value = ProductLine.class, names = "SPOT", mode = EnumSource.Mode.EXCLUDE)
    void positionModeChangesAndBlockedRejectionKeepAccountFundsWithoutSnapshot(ProductLine product) {
        try (/* 接收交错或并发在途输入的测试 Fixture，退出资源作用域时关闭 */ Fixture live = new Fixture(product)) {
            live.setup();
            // 切换仓位模式前的账户余额，重复设置或拒绝不应改变
            var balances = live.service.state().tradingState().user(11).balances();
            for (var /* 本轮要设置的双向或单向持仓模式 */ mode : new CorePositionMode[]{CorePositionMode.HEDGE, CorePositionMode.HEDGE,
                    CorePositionMode.ONE_WAY}) {
                live.apply(live.message(CoreMessageType.UPDATE_POSITION_MODE, 11,
                        TradingCommandCodec.encodeUpdatePositionMode(new UpdatePositionModeCommand(mode))));
                assertThat(live.responses.getLast().commandStatus()).isEqualTo(ResponseStatus.APPLIED);
                assertThat(live.service.state().tradingState().user(11).positionMode()).isEqualTo(mode);
                assertThat(live.service.state().tradingState().user(11).balances()).isEqualTo(balances);
            }
            live.apply(live.place(11, "1", 19002, 80, 1, CoreOrderSide.BUY));
            // 活动订单阻止模式切换之前的业务哈希
            long before = live.hash();
            live.apply(live.message(CoreMessageType.UPDATE_POSITION_MODE, 11,
                    TradingCommandCodec.encodeUpdatePositionMode(new UpdatePositionModeCommand(CorePositionMode.HEDGE))));
            assertThat(live.responses.getLast().resultCode()).isEqualTo(CoreResultCode.POSITION_MODE_SWITCH_BLOCKED);
            assertThat(live.hash()).isEqualTo(before);
            live.apply(live.cancel(11, 19002));
            live.apply(live.message(CoreMessageType.UPDATE_POSITION_MODE, 11,
                    TradingCommandCodec.encodeUpdatePositionMode(new UpdatePositionModeCommand(CorePositionMode.HEDGE))));
            assertThat(live.responses.getLast().commandStatus()).isEqualTo(ResponseStatus.APPLIED);
            assertThat(live.service.state().tradingState().user(11).positionMode()).isEqualTo(CorePositionMode.HEDGE);
            assertThat(live.service.state().tradingState().user(11).balances()).isEqualTo(balances);
        }
    }

    /**
     * 在四种永续或交割产品检查重复杠杆设置和超品种限额拒绝，最终状态与串行及恢复一致。
     * @param product 当前场景独立产品线，实际范围由各测试枚举源限定
     */
    @ParameterizedTest
    @EnumSource(value = ProductLine.class, names = {"SPOT", "OPTION"}, mode = EnumSource.Mode.EXCLUDE)
    void leverageChangesStayInTheAccountLane(ProductLine product) throws Exception {
        try (/* 接收交错或并发在途输入的测试 Fixture，退出资源作用域时关闭 */ Fixture live = new Fixture(product); /* 接收相同原消息但逐条等完成的串行对照 Fixture */ Fixture serial = new Fixture(product)) {
            serial.applyAll(live.setup());
            for (long /* 本轮传入的杠杆定点值 */ leverage : new long[]{2_000_000, 2_000_000, 1_000_000, 1_000_000_000}) {
                // 本轮保证金或杠杆设置的原命令
                var change = live.message(CoreMessageType.UPDATE_LEVERAGE, 11,
                        TradingCommandCodec.encodeUpdateLeverage(new UpdateLeverageCommand(
                                "1", CoreMarginMode.CROSS, leverage)));
                live.apply(change); serial.apply(change);
                assertThat(live.responses.getLast().commandStatus())
                        .isEqualTo(leverage == 1_000_000_000 ? ResponseStatus.REJECTED : ResponseStatus.APPLIED);
                assertThat(live.responses.getLast().resultCode()).isEqualTo(leverage == 1_000_000_000
                        ? CoreResultCode.LEVERAGE_EXCEEDS_INSTRUMENT_LIMIT : CoreResultCode.NONE);
            }
            assertThat(live.hash()).isEqualTo(serial.hash());
            try (/* 从当前快照恢复出的独立 Core，资源结束时关闭 */ var restored = TradingCoreRuntime.fromSnapshot(product, live.service.captureSnapshot(100))) {
                assertThat(restored.tradingState().businessStateHash()).isEqualTo(live.hash());
            }
        }
    }

    /**
     * 有挂单时改杠杆须被阻止且业务哈希不变，撤单后同一杠杆设置可成功。
     * @param product 当前场景独立产品线，实际范围由各测试枚举源限定
     */
    @ParameterizedTest
    @EnumSource(value = ProductLine.class, names = {"SPOT", "OPTION"}, mode = EnumSource.Mode.EXCLUDE)
    void leverageRejectionPreservesOpenOrderFundsAndCanBeRetriedAfterCancel(ProductLine product) {
        try (/* 接收交错或并发在途输入的测试 Fixture，退出资源作用域时关闭 */ Fixture live = new Fixture(product)) {
            live.setup();
            live.apply(live.place(11, "1", 19001, 80, 1, CoreOrderSide.BUY));
            // 活动订单阻止杠杆变更之前的业务哈希
            long before = live.hash();
            live.apply(live.message(CoreMessageType.UPDATE_LEVERAGE, 11,
                    TradingCommandCodec.encodeUpdateLeverage(new UpdateLeverageCommand("1", CoreMarginMode.CROSS, 2_000_000))));
            assertThat(live.responses.getLast().resultCode()).isEqualTo(CoreResultCode.LEVERAGE_UPDATE_BLOCKED);
            assertThat(live.hash()).isEqualTo(before);
            live.apply(live.cancel(11, 19001));
            live.apply(live.message(CoreMessageType.UPDATE_LEVERAGE, 11,
                    TradingCommandCodec.encodeUpdateLeverage(new UpdateLeverageCommand("1", CoreMarginMode.CROSS, 2_000_000))));
            assertThat(live.responses.getLast().commandStatus()).isEqualTo(ResponseStatus.APPLIED);
        }
    }

    /**
     * 最多尝试二百五十六个测试品种字符串，返回实际路由到不同 Matcher 分片的一个，找不到抛断言。
     * @param state 用于查询实际 Matcher 路由的当前 Core
     * @param firstSymbol 需要避开的原品种路由
     */
    private static String differentMatcherSymbol(TradingCoreRuntime state, String firstSymbol) {
        for (/* 当前初始化、查找候选或重复轮询的循环下标 */ int i = 0; i < 256; i++) {
            // 正在尝试路由的测试品种字符串
            String candidate = Integer.toString(1000+i);
            if (state.matchingAdapter().matcherShardId(candidate) != state.matchingAdapter().matcherShardId(firstSymbol))
                return candidate;
        }
        throw new AssertionError("cannot find an independent order book partition");
    }

    /**
     * 按默认窗口容量准备独立账户命令，满窗口后只轮询到排空，不补定时器回调；检查账户、串行及恢复哈希一致。
     * @param product 当前场景独立产品线，实际范围由各测试枚举源限定
     */
    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void fullIndependentWindowCompletesWithoutAnotherTimer(ProductLine product) {
        try (/* 接收交错或并发在途输入的测试 Fixture，退出资源作用域时关闭 */ Fixture live = new Fixture(product); /* 接收相同原消息但逐条等完成的串行对照 Fixture */ Fixture serial = new Fixture(product)) {
            serial.applyAll(live.setup());
            // 该产品准入或流动性准备使用的资产名称
            String asset = ContractType.valueOf(product.contractTypeCode()).isInverse() ? "BTC" : "USDT";
            for (/* 当前初始化、查找候选或重复轮询的循环下标 */ int i = 0; i < ClusterCommandWindow.DEFAULT_CAPACITY; i++) {
                // 为本轮测试账户或做市账户加资的原消息
                var funds = live.message(CoreMessageType.ADJUST_BALANCE, 10000 + i,
                        TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand(asset, 20000)));
                live.apply(funds);
                serial.apply(funds);
            }
            // 当前普通或批量测试使用的有序订单列表
            var orders = new ArrayList<CoreMessage>();
            for (/* 当前初始化、查找候选或重复轮询的循环下标 */ int i = 0; i < ClusterCommandWindow.DEFAULT_CAPACITY; i++)
                orders.add(live.place(10000 + i, "1", 1000 + i, 80, 1, CoreOrderSide.BUY));
            orders.forEach(live::send);
            // 满窗口本身就是确定性提交边界；后续日志回调只轮询，不再注入额外timer栅栏。
            live.progressUntil(() -> live.service.pendingCommandCount() == 0);
            serial.applyAll(orders);
            assertThat(live.hash()).isEqualTo(serial.hash());
            assertThat(live.service.state().tradingState().users())
                    .isEqualTo(serial.service.state().tradingState().users());
            try (/* 从当前快照恢复出的独立 Core，资源结束时关闭 */ var restored = TradingCoreRuntime.fromSnapshot(product, live.service.state().snapshot())) {
                assertThat(restored.tradingState().businessStateHash()).isEqualTo(live.hash());
            }
        }
    }

    /**
     * 先耗尽一账户资金，必要时模拟批次已开始提交而准入拒绝尚未收取的挂起边界；再入独立订单，检查前批全部项拒绝、后单成功且串行与恢复一致。
     * @param product 当前场景独立产品线，实际范围由各测试枚举源限定
     */
    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void newAdmissionCanResumeRejectedBatchWithSuspendedCommit(ProductLine product) throws Exception {
        try (/* 接收交错或并发在途输入的测试 Fixture，退出资源作用域时关闭 */ Fixture live = new Fixture(product); /* 接收相同原消息但逐条等完成的串行对照 Fixture */ Fixture serial = new Fixture(product)) {
            serial.applyAll(live.setup());
            // 该产品准入或流动性准备使用的资产名称
            String asset = ContractType.valueOf(product.contractTypeCode()).isInverse() ? "BTC" : "USDT";
            // 减少账户余额，使后续下单资金不足或只够一单的调整消息
            var withdraw = live.message(CoreMessageType.ADJUST_BALANCE, 11,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand(asset, -20_000)));
            live.apply(withdraw); serial.apply(withdraw);
            live.responses.clear();
            // 当前先登记的原命令消息
            var first = live.placeBatch(11, "1", 1000);
            // 当前 Fixture 内的实际 Core 运行时
            var state = live.service.state();
            live.send(first);
            live.service.pollCommands();
            // 当前原命令登记得到的完整 Core 序号
            long sequence = state.matchingSequence(first.header().commandId());
            // 当前仍在途的批次上下文，可能已经提前完成而为空
            var batch = state.batches().batch(sequence);
            // 仍在生命周期中的原命令槽
            var pending = state.pendingMatching().get(sequence);
            if (batch != null) {
                // 模拟延迟激活已开始提交、但 Lane 批量准入尚未被 owner 收集的边界。
                if (!batch.commitStarted()) state.batches().beginOrderBatchCommitContext(batch, pending);
                if (!pending.hasCommitContext()) state.suspendMatchingCommitContext(pending);
                // 当前有界等待的 nanoTime 截止点
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (batch.placeBatchAdmissionEvent != null && !batch.placeBatchAdmissionEvent.complete() && System.nanoTime() < deadline) Thread.onSpinWait();
                if (batch.placeBatchAdmissionEvent != null) {
                    assertThat(batch.placeBatchAdmissionEvent.complete()).isTrue();
                    assertThat(batch.placeBatchAdmissionEvent.rejection()).isNotNull();
                }
            }
            // 当前前缀之后继续登记的原命令
            var next = live.place(disjointUser(11), disjointSymbol("1"), 8000, 80, 1, CoreOrderSide.BUY);
            live.send(next);
            live.tick(); serial.apply(first); serial.apply(next);
            assertThat(live.responses).hasSize(2);
            assertThat(TradingOrderBatchCodec.decodeResult(live.responses.getFirst().data()).items())
                    .allSatisfy(/* 当前批量响应的一项，读取其状态、订单或明细 */ item -> assertThat(item.status()).isEqualTo(ResponseStatus.REJECTED));
            assertThat(live.responses.getLast().commandStatus()).isEqualTo(ResponseStatus.APPLIED);
            assertThat(state.tradingState().users()).isEqualTo(serial.service.state().tradingState().users());
            assertThat(live.hash()).isEqualTo(serial.hash());
            try (/* 从当前快照恢复出的独立 Core，资源结束时关闭 */ var restored = TradingCoreRuntime.fromSnapshot(product, state.snapshot())) {
                assertThat(restored.tradingState().businessStateHash()).isEqualTo(live.hash());
            }
        }
    }

    /**
     * 分别用单项批次和带重复号拒绝项的批次，检查等待 Matcher 时发布积累已释放；独立后单仍能完成，整批内拒绝数及账户与串行、恢复一致。
     * @param product 当前场景独立产品线，实际范围由各测试枚举源限定
     */
    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void batchMatcherWaitReleasesOwnerPublicationContext(ProductLine product) {
        for (boolean /* 本轮是否先占用第一项订单号，让批次产生一个已知拒绝项 */ partialRejection : new boolean[]{false, true}) {
            try (/* 接收交错或并发在途输入的测试 Fixture，退出资源作用域时关闭 */ Fixture live = new Fixture(product); /* 接收相同原消息但逐条等完成的串行对照 Fixture */ Fixture serial = new Fixture(product)) {
                serial.applyAll(live.setup());
                if (partialRejection) {
                    // 开始测试前已完成的挂单或批次消息
                    var resting = live.place(11, "1", 101, 80, 1, CoreOrderSide.BUY);
                    live.apply(resting); serial.apply(resting);
                }
                // 从共同构造器解码出的二十项下单输入，用来选择单项或部分拒绝分支
                var batch = TradingOrderBatchCodec.decodePlaceOrderBatch(live.placeBatch(11, "1", 101).payload());
                // 当前先登记的原命令消息
                var first = live.message(CoreMessageType.PLACE_ORDER_BATCH, 11,
                        TradingOrderBatchCodec.encodePlaceOrderBatch(new PlaceOrderBatchCommand(
                                partialRejection ? batch.orders() : batch.orders().subList(0, 1))));
                // 当前前缀之后继续登记的原命令
                var next = live.place(disjointUser(11), disjointSymbol("1"),
                        disjointOrder(8000), 80, 1, CoreOrderSide.BUY);
                // 当前 Fixture 内的实际 Core 运行时
                var state = live.service.state();
                live.responses.clear();
                live.send(first);
                // 当前原命令登记得到的完整 Core 序号
                long sequence = state.matchingSequence(first.header().commandId());
                live.service.pollCommands();
                assertThat(state.commits.publication.deferred()).as("batch waiting for matcher").isFalse();
                live.send(next);
                live.tick();
                assertThat(live.responses).hasSize(2).allSatisfy(/* 当前完成回调或响应列表中的 Core 结果 */ response ->
                        assertThat(response.commandStatus()).isEqualTo(ResponseStatus.APPLIED));
                assertThat(TradingOrderBatchCodec.decodeResult(live.responses.getFirst().data()).items()
                        .stream().filter(/* 当前批量响应的一项，读取其状态、订单或明细 */ item -> item.status() == ResponseStatus.REJECTED).count())
                        .isEqualTo(partialRejection ? 1 : 0);
                serial.apply(first); serial.apply(next);
                assertThat(state.tradingState().users()).isEqualTo(serial.service.state().tradingState().users());
                assertThat(live.hash()).isEqualTo(serial.hash());
                try (/* 从当前快照恢复出的独立 Core，资源结束时关闭 */ var restored = TradingCoreRuntime.fromSnapshot(product, state.snapshot())) {
                    assertThat(restored.tradingState().businessStateHash()).isEqualTo(live.hash());
                }
            }
        }
    }

    /**
     * 依次做普通下单、改单、替换、撤单、批量下撤单及缺失订单拒绝，检查交易期间 Lane 交接代数未增、余额回到原值，再比较恢复哈希。
     * @param product 当前场景独立产品线，实际范围由各测试枚举源限定
     */
    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void ordinaryOrderLifecycleDoesNotAcquireLaneOwnership(ProductLine product) throws Exception {
        try (/* 接收交错或并发在途输入的测试 Fixture，退出资源作用域时关闭 */ Fixture live = new Fixture(product)) {
            live.setup();
            // 当前 Fixture 内的实际 Core 运行时
            var state = live.service.state();
            // 交易动作开始前该账户全部资产余额，用于检查下撤单后恢复
            var beforeBalances = state.tradingState().user(11).balances();
            // 账户所有权交接代数的反射字段
            // 本轮交易动作开始前的账户交接代数
            long before = state.runtimeState().laneHandoffEpoch();
            live.apply(live.place(11, "1", 101, 80, 1, CoreOrderSide.BUY));
            live.apply(live.message(CoreMessageType.AMEND_ORDER, 11, TradingCommandCodec.encodeAmendOrder(
                    new AmendOrderCommand(101, 102, "amend-102", 90L, 1L, CoreTimeInForce.GTC, false))));
            assertThat(live.responses.getLast().commandStatus()).isEqualTo(ResponseStatus.APPLIED);
            live.apply(live.message(CoreMessageType.REPLACE_ORDER, 11, TradingCommandCodec.encodeReplaceOrder(
                    new ReplaceOrderCommand(102, new PlaceOrderCommand(103, "1",
                            CoreOrderSide.BUY, 80, 1, false, CoreMarginMode.CROSS, CorePositionSide.NET,
                            CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "replace-103")))));
            assertThat(live.responses.getLast().commandStatus()).isEqualTo(ResponseStatus.APPLIED);
            live.apply(live.cancel(11, 103));
            assertThat(live.responses.getLast().commandStatus()).isEqualTo(ResponseStatus.APPLIED);
            live.apply(live.placeBatch(11, "1", 201));
            live.apply(live.cancelBatch(11, 201));
            assertThat(TradingOrderBatchCodec.firstNonAppliedItem(live.responses.getLast(), 20)).isEqualTo(-1);
            live.apply(live.cancel(11, 999_999));
            assertThat(live.responses.getLast().commandStatus()).isEqualTo(ResponseStatus.REJECTED);
            assertThat(state.runtimeState().laneHandoffEpoch()).isEqualTo(before);
            assertThat(state.tradingState().user(11).balances()).isEqualTo(beforeBalances);
            try (/* 从当前快照恢复出的独立 Core，资源结束时关闭 */ var restored = TradingCoreRuntime.fromSnapshot(product, live.service.captureSnapshot(100))) {
                assertThat(restored.tradingState().businessStateHash()).isEqualTo(live.hash());
            }
        }
    }

    /**
     * 让改单的新单因只挂单而被拒，检查旧单及已成功的前置撤单仍退休，相关预留释放、交接代数不变，结果包含原单取消视图且恢复一致。
     * @param product 当前场景独立产品线，实际范围由各测试枚举源限定
     */
    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void rejectedAmendCommitsKnownCancellationPrefix(ProductLine product) throws Exception {
        try (/* 接收交错或并发在途输入的测试 Fixture，退出资源作用域时关闭 */ Fixture live = new Fixture(product)) {
            live.setup();
            // 当前需要加资、下单或查条件单的用户编号
            // 预先提供流动性的用户编号
            long user = 11, maker = disjointUser(user);
            if (product == ProductLine.SPOT) for (long /* 当前需要补充基础币的用户编号 */ account : new long[]{user, maker})
                live.apply(live.message(CoreMessageType.ADJUST_BALANCE, account,
                        TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("BTC", 100))));
            live.apply(live.place(user, "1", 101, 100, 1, CoreOrderSide.SELL));
            live.apply(live.place(user, "1", 102, 80, 1, CoreOrderSide.BUY));
            live.apply(live.place(maker, "1", 103, 100, 1, CoreOrderSide.SELL));
            // 账户所有权交接代数的反射字段
            // 本轮交易前记录的账户交接代数，动作完成后要求不增加
            long handoffBefore = live.service.state().runtimeState().laneHandoffEpoch();
            live.responses.clear();
            live.apply(live.message(CoreMessageType.AMEND_ORDER, user, TradingCommandCodec.encodeAmendOrder(
                    new AmendOrderCommand(102, 104, "reject-104", 100L, 1L, CoreTimeInForce.GTX, true))));
            assertThat(live.responses.getLast().commandStatus()).isEqualTo(ResponseStatus.REJECTED);
            assertThat(live.service.state().runtimeState().laneHandoffEpoch()).isEqualTo(handoffBefore);
            // 已拒绝改单响应解码出的订单结果，核对原单取消视图
            var result = CoreCommandResultCodec.decode(live.responses.getLast().data());
            assertThat(result.orders()).filteredOn(/* 从改单结果中筛选并检查取消状态的订单视图 */ order -> order.orderId() == 102)
                    .hasSize(1).allSatisfy(/* 从改单结果中筛选并检查取消状态的订单视图 */ order -> assertThat(order.status()).isEqualTo("CANCELED"));
            // 当前 Core 中所属账户及风险扫描的权威运行时
            var runtime = live.service.state().runtimeState();
            assertThat(AccountLaneQueryTestSupport.read(runtime, user, () -> runtime.order(101))).isNull();
            assertThat(AccountLaneQueryTestSupport.read(runtime, user, () -> runtime.reservation(101))).isNull();
            assertThat(AccountLaneQueryTestSupport.read(runtime, user, () -> runtime.order(102))).isNull();
            assertThat(AccountLaneQueryTestSupport.read(runtime, user, () -> runtime.order(104))).isNull();
            assertThat(AccountLaneQueryTestSupport.read(runtime, user, () -> runtime.reservation(102))).isNull();
            try (/* 从当前快照恢复出的独立 Core，资源结束时关闭 */ var restored = TradingCoreRuntime.fromSnapshot(product, live.service.captureSnapshot(100))) {
                assertThat(restored.tradingState().businessStateHash()).isEqualTo(live.hash());
            }
        }
    }

    /**
     * 先临时取得 Owner 账户访问权，再分别提交普通单和批次，要求准入已归还访问权；随后同步辅助入口完成，账户及恢复状态与串行一致。
     * @param product 当前场景独立产品线，实际范围由各测试枚举源限定
     */
    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void placeAdmissionReturnsBorrowedOwnershipBeforeLaneWork(ProductLine product) throws Exception {
        for (boolean /* 本轮是否用批次检验归还访问权，否则用普通单 */ batch : new boolean[]{false, true}) {
            try (/* 接收交错或并发在途输入的测试 Fixture，退出资源作用域时关闭 */ Fixture live = new Fixture(product); /* 接收相同原消息但逐条等完成的串行对照 Fixture */ Fixture serial = new Fixture(product)) {
                serial.applyAll(live.setup());
                // 当前 Fixture 内的实际 Core 运行时
                var state = live.service.state();
                // 当前有界等待的 nanoTime 截止点
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (!state.runtimeState().tryAcquireOwnerLaneAccess()) {
                    if (System.nanoTime() >= deadline) throw new AssertionError("ownership timeout");
                    Thread.yield();
                }
                // 当前输入、比较或续扫的原 Core 消息
                var command = batch ? live.placeBatch(11, "1", 101)
                        : live.place(11, "1", 101, 80, 1, CoreOrderSide.BUY);
                // 该命令原消息固定的业务时间
                long timestamp = command.header().submittedAtEpochMillis();
                state.applyClusterCommand(command, timestamp, 0);
                // Owner 是否持有账户访问权的反射字段
                assertThat(state.runtimeState().ownerLaneAccess()).as("batch=%s", batch).isFalse();
                // 当前原命令登记得到的完整 Core 序号
                long sequence = state.matchingSequence(command.header().commandId());
                assertThat(CoreTestCompletion.completeMatchingSynchronously(state, sequence, timestamp, 0).commandStatus())
                        .isEqualTo(ResponseStatus.APPLIED);
                serial.apply(command);
                assertThat(state.tradingState().users()).isEqualTo(serial.service.state().tradingState().users());
                assertThat(live.hash()).isEqualTo(serial.hash());
                try (/* 从当前快照恢复出的独立 Core，资源结束时关闭 */ var restored = TradingCoreRuntime.fromSnapshot(product, state.snapshot())) {
                    assertThat(restored.tradingState().businessStateHash()).isEqualTo(live.hash());
                }
            }
        }
    }

    /**
     * 分别对下单、撤单、改单和批量撤单尝试一次完成，保留已返回终态或继续原槽；插入独立后单后两者均成功，账户与串行及恢复一致。
     * @param product 当前场景独立产品线，实际范围由各测试枚举源限定
     */
    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void laneCompletionPollPreservesCommitContextAndDoesNotWaitForUnrelatedWork(ProductLine product) throws Exception {
        for (CoreMessageType /* 本轮测试的下单、撤单、改单或批量撤单消息类别 */ type : List.of(CoreMessageType.PLACE_ORDER, CoreMessageType.CANCEL_ORDER,
                CoreMessageType.AMEND_ORDER, CoreMessageType.CANCEL_ORDER_BATCH)) {
            try (/* 接收交错或并发在途输入的测试 Fixture，退出资源作用域时关闭 */ Fixture live = new Fixture(product); /* 接收相同原消息但逐条等完成的串行对照 Fixture */ Fixture serial = new Fixture(product)) {
                serial.applyAll(live.setup());
                if (type != CoreMessageType.PLACE_ORDER) {
                    // 开始测试前已完成的挂单或批次消息
                    var resting = type == CoreMessageType.CANCEL_ORDER_BATCH
                            ? live.placeBatch(11, "1", 101)
                            : live.place(11, "1", 101, 80, 1, CoreOrderSide.BUY);
                    live.apply(resting); serial.apply(resting);
                }
                // 当前先登记的原命令消息
                CoreMessage first = switch (type) {
                    case CANCEL_ORDER -> live.cancel(11, 101);
                    case CANCEL_ORDER_BATCH -> live.cancelBatch(11, 101);
                    case AMEND_ORDER -> live.message(type, 11, TradingCommandCodec.encodeAmendOrder(
                            new AmendOrderCommand(101, 102, "replace-102", 81L, 1L, CoreTimeInForce.GTC, false)));
                    default -> live.place(11, "1", 101, 80, 1, CoreOrderSide.BUY);
                };
                // 当前 Fixture 内的实际 Core 运行时
                var state = live.service.state();
                // 该命令原消息固定的业务时间
                long timestamp = first.header().submittedAtEpochMillis();
                state.applyClusterCommand(first, timestamp, 0);
                // 当前原命令登记得到的完整 Core 序号
                long sequence = state.matchingSequence(first.header().commandId());
                // 已经取得的原 Matcher 事实，等待账户或财务时继续使用它
                var matching = CoreTestCompletion.awaitMatchingResult(state, sequence);
                assertThat(matching).isNotNull();
                // 当前前缀之后继续登记的原命令
                CoreMessage next = live.place(disjointUser(11), disjointSymbol("1"),
                        disjointOrder(8000), 80, 1, CoreOrderSide.BUY);
                // 第一次尝试完成前命令时直接得到的可空终态，非空时不再向退休槽取结果
                CoreResponse firstResponse = state.completeMatching(sequence, matching, timestamp, 0);
                if (firstResponse != null) {
                    assertThat(firstResponse.commandStatus()).isEqualTo(ResponseStatus.APPLIED);
                    assertThat(state.pendingMatching().get(sequence)).isNull();
                }
                state.applyClusterCommand(next, next.header().submittedAtEpochMillis(), 0);
                if (firstResponse == null)
                    assertThat(CoreTestCompletion.completeMatchingSynchronously(state, sequence, timestamp, 0).commandStatus())
                            .isEqualTo(ResponseStatus.APPLIED);
                // 后续独立命令登记后的完整 Core 序号
                long nextSequence = state.matchingSequence(next.header().commandId());
                assertThat(CoreTestCompletion.completeMatchingSynchronously(state, nextSequence, next.header().submittedAtEpochMillis(), 0).commandStatus())
                        .isEqualTo(ResponseStatus.APPLIED);
                serial.apply(first); serial.apply(next);
                assertThat(serial.responses.getLast().commandStatus()).isEqualTo(ResponseStatus.APPLIED);
                assertThat(state.tradingState().users()).as("%s account parity", type)
                        .isEqualTo(serial.service.state().tradingState().users());
                assertThat(live.hash()).as("%s hash parity", type).isEqualTo(serial.hash());
                try (/* 从当前快照恢复出的独立 Core，资源结束时关闭 */ var restored = TradingCoreRuntime.fromSnapshot(product, state.snapshot())) {
                    assertThat(restored.tradingState().businessStateHash()).isEqualTo(live.hash());
                }
            }
        }
    }
    /**
     * 检查显式或内部扫描触发子单遇编号冲突后使用准入选定身份，随后比较恢复状态。
     * @param product 当前场景独立产品线，实际范围由各测试枚举源限定
     */
    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void triggerChildUsesOwningLaneAndPreservesOrderIdCollision(ProductLine product) throws Exception {
        verifyTriggerChild(product, true);
    }

    /**
     * 相同触发入口安装 Owner 账户读取守卫，并加入异 Lane 终态身份冲突，检查子单继续选择可用编号。
     * @param product 当前场景独立产品线，实际范围由各测试枚举源限定
     */
    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void triggerAdmissionAndMatchingNeverReadOwnerAccountTables(ProductLine product) throws Exception {
        verifyTriggerChild(product, false);
    }

    /**
     * 交叉检查显式触发、衍生品内部扫描及零、部分、全部成交；已有活动号和可选终态号都占子单空间。检查选定子单号、源触发终态、OCO 兄弟取消及不借走 Lane，恢复分支另作哈希比较。
     * @param product 当前场景独立产品线，实际范围由各测试枚举源限定
     * @param recovery 真时做恢复比较；假时增加异 Lane 终态编号冲突并安装 Owner 读取守卫
     */
    private void verifyTriggerChild(ProductLine product, boolean recovery) throws Exception {
        for (boolean /* 是否通过风险续扫内部触发，现货跳过这条分支 */ internalScan : new boolean[]{false, true}) {
        if (internalScan && product == ProductLine.SPOT) continue; // 现货没有这条标记价风险续扫路径。
        for (int /* 给触发子单准备的对手数量，零、部分和完整成交各有场景 */ matchedQuantity : new int[]{0, 1, 10}) {
            // 当前测试是否准备可成交的对手单
            boolean fill = matchedQuantity != 0;
            try (/* 接收交错或并发在途输入的测试 Fixture，退出资源作用域时关闭 */ Fixture live = new Fixture(product)) {
                live.setup();
                // 当前需要加资、下单或查条件单的用户编号
                // 预先提供流动性的用户编号
                long maker = 11, user = disjointUser(maker);
                if (product == ProductLine.SPOT) live.apply(live.message(CoreMessageType.ADJUST_BALANCE, maker,
                        TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("BTC", 100))));
                live.apply(live.place(maker, "1", 101, 100, 10, CoreOrderSide.SELL));
                live.apply(live.place(user, "1", 102, 100, 10, CoreOrderSide.BUY));
                // 子订单编号冲突时必须沿用准入阶段选定的编号，不能重新推导或回查 Lane。
                live.apply(live.place(user, "1", 18003, 80, 1, CoreOrderSide.BUY));
                if (!recovery) {
                    // 其他 Lane 的终态身份也占子订单编号，准入选择时必须避开。
                    live.apply(live.place(maker, "1", 18005, 80, 1, CoreOrderSide.BUY));
                    live.apply(live.cancel(maker, 18005));
                    assertThat(live.service.state().orderIdInUse(18005)).isTrue();
                    assertThat(live.service.state().orderIdInUse(18007)).isFalse();
                }
                if (fill) live.apply(live.place(maker, "1", 103, 100, matchedQuantity, CoreOrderSide.BUY));
                // 当前待登记条件单视图
                var trigger = new CoreTriggerOrderStateView(9001, product, user, "trigger-9001", "oco-9001", "1",
                        CoreOrderSide.SELL, CoreTriggerOrderType.TAKE_PROFIT, CoreTriggerCondition.GREATER_OR_EQUAL,
                        100, 0, 0, 0, 0, 0, CoreOrderType.LIMIT, CoreTimeInForce.IOC, fill ? 100 : 110, Math.max(1, matchedQuantity),
                        CoreMarginMode.CROSS, CorePositionSide.NET, CoreTriggerOrderStatus.PENDING,
                        0, 0, 0, "", 0, 0, 0, 0, 1, 0, 0);
                live.apply(live.message(CoreMessageType.PLACE_TRIGGER_ORDER, user,
                        CoreTriggerOrderCodec.encodePlacement(new com.surprising.aeron.protocol.PlaceTriggerOrderCommand(
                        trigger.triggerOrderId(), trigger.clientTriggerOrderId(), trigger.ocoGroupId(),
                                trigger.instrumentId(), trigger.side(), trigger.triggerType(),
                                trigger.triggerCondition(), trigger.triggerPriceTicks(), trigger.activationPriceTicks(),
                                trigger.callbackRatePpm(), trigger.orderType(), trigger.timeInForce(),
                                trigger.priceTicks(), trigger.quantitySteps(), trigger.marginMode(),
                                trigger.positionSide(), trigger.expiresAtEpochMillis(), trigger.priceSource()))));
                // 源触发单同组、预期会被取消的另一条条件单视图
                var sibling = new CoreTriggerOrderStateView(9002, product, user, "trigger-9002", "oco-9001", "1",
                        CoreOrderSide.SELL, CoreTriggerOrderType.TAKE_PROFIT, CoreTriggerCondition.GREATER_OR_EQUAL,
                        200, 0, 0, 0, 0, 0, CoreOrderType.LIMIT, CoreTimeInForce.IOC, 200, 1,
                        CoreMarginMode.CROSS, CorePositionSide.NET, CoreTriggerOrderStatus.PENDING,
                        0, 0, 0, "", 0, 0, 0, 0, 1, 0, 0);
                live.apply(live.message(CoreMessageType.PLACE_TRIGGER_ORDER, user,
                        CoreTriggerOrderCodec.encodePlacement(new com.surprising.aeron.protocol.PlaceTriggerOrderCommand(
                        sibling.triggerOrderId(), sibling.clientTriggerOrderId(), sibling.ocoGroupId(),
                                sibling.instrumentId(), sibling.side(), sibling.triggerType(),
                                sibling.triggerCondition(), sibling.triggerPriceTicks(), sibling.activationPriceTicks(),
                                sibling.callbackRatePpm(), sibling.orderType(), sibling.timeInForce(),
                                sibling.priceTicks(), sibling.quantitySteps(), sibling.marginMode(),
                                sibling.positionSide(), sibling.expiresAtEpochMillis(), sibling.priceSource()))));
                live.responses.clear();
                // 账户交接代数的反射字段，用来证明交易工作留在固定 Lane
                // 执行触发命令之前记录的账户交接代数
                long beforeHandoff = live.service.state().runtimeState().laneHandoffEpoch();
                try (/* 可空 Owner 账户表读取守卫，存在时在作用域结束还原表 */ var guard = recovery ? null : new OwnerAccountReadGuard(live.service.state().runtimeState())) {
                    if (internalScan) scanTriggers(live, 1);
                    else live.apply(live.message(CoreMessageType.EXECUTE_TRIGGER_ORDER, 0,
                            CoreTriggerOrderCodec.encodeExecute(9001, 1, 100, TIME)));
                }
                assertThat(live.service.state().runtimeState().laneHandoffEpoch())
                        .as("trigger claim/reserve/settle stays on permanent Account Lane")
                        .isEqualTo(beforeHandoff);
                assertThat(live.responses).isNotEmpty().allSatisfy(/* 当前待检查状态或结果码的 Core 响应 */ r ->
                        assertThat(r.commandStatus()).isEqualTo(ResponseStatus.APPLIED));
                // 结束后按所属账户读取的源触发单终态
                var terminal = CoreTestCompletion.triggerState(live.service.state(), user, 9001);
                assertThat(terminal.status()).isEqualTo(CoreTriggerOrderStatus.TRIGGERED);
                assertThat(terminal.placedOrderId()).isEqualTo(recovery ? 18005 : 18007);
                assertThat(CoreTestCompletion.triggerState(live.service.state(), user, 9002).status())
                        .isEqualTo(CoreTriggerOrderStatus.CANCELED);
                if (recovery) try (/* 从当前快照恢复出的独立 Core，资源结束时关闭 */ var restored = TradingCoreRuntime.fromSnapshot(product, live.service.captureSnapshot(100))) {
                    assertThat(restored.tradingState().businessStateHash()).isEqualTo(live.hash());
                }
                live.apply(live.cancel(user, 18003));
                assertThat(live.responses.getLast().commandStatus()).isEqualTo(ResponseStatus.APPLIED);
            }
        }
        }
    }

    /**
     * 构造触发子单只挂单拒绝，检查预留释放、触发失败和兄弟单取消，再比较恢复哈希。
     * @param product 当前场景独立产品线，实际范围由各测试枚举源限定
     */
    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void nativeTriggerRejectionReleasesReservationAndCompletesOnItsLane(ProductLine product) throws Exception {
        verifyTriggerRejection(product, true);
    }

    /**
     * 检查相同原生触发拒绝，不追加恢复比较。
     * @param product 当前场景独立产品线，实际范围由各测试枚举源限定
     */
    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void triggerNativeRejectionWithoutSnapshot(ProductLine product) throws Exception {
        verifyTriggerRejection(product, false);
    }

    /**
     * 当前循环只走显式执行及一个有对手单场景，GTX 子单被拒；检查无子单预留、可查询拒单视图、源触发失败和 OCO 兄弟取消，按参数决定恢复比较。
     * @param product 当前场景独立产品线，实际范围由各测试枚举源限定
     * @param recovery 是否额外比较快照恢复状态
     */
    private void verifyTriggerRejection(ProductLine product, boolean recovery) throws Exception {
        for (boolean /* 是否通过风险续扫内部触发，现货跳过这条分支 */ internalScan : new boolean[]{false}) {
        if (internalScan && product == ProductLine.SPOT) continue; // 现货没有这条标记价风险续扫路径。
        for (int /* 给触发子单准备的对手数量，零、部分和完整成交各有场景 */ matchedQuantity : new int[]{1}) {
            // 当前测试是否准备可成交的对手单
            boolean fill = matchedQuantity != 0;
            try (/* 接收交错或并发在途输入的测试 Fixture，退出资源作用域时关闭 */ Fixture live = new Fixture(product)) {
                live.setup();
                // 当前需要加资、下单或查条件单的用户编号
                // 预先提供流动性的用户编号
                long maker = 11, user = disjointUser(maker);
                if (product == ProductLine.SPOT) live.apply(live.message(CoreMessageType.ADJUST_BALANCE, maker,
                        TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("BTC", 100))));
                live.apply(live.place(maker, "1", 101, 100, 10, CoreOrderSide.SELL));
                live.apply(live.place(user, "1", 102, 100, 10, CoreOrderSide.BUY));
                // 子订单编号冲突时必须沿用准入阶段选定的编号，不能重新推导或回查 Lane。
                live.apply(live.place(user, "1", 18003, 80, 1, CoreOrderSide.BUY));
                if (fill) live.apply(live.place(maker, "1", 103, 100, matchedQuantity, CoreOrderSide.BUY));
                // 当前待登记条件单视图
                var trigger = new CoreTriggerOrderStateView(9001, product, user, "trigger-9001", "oco-9001", "1",
                        CoreOrderSide.SELL, CoreTriggerOrderType.TAKE_PROFIT, CoreTriggerCondition.GREATER_OR_EQUAL,
                        100, 0, 0, 0, 0, 0, CoreOrderType.LIMIT, CoreTimeInForce.GTX, fill ? 100 : 110, Math.max(1, matchedQuantity),
                        CoreMarginMode.CROSS, CorePositionSide.NET, CoreTriggerOrderStatus.PENDING,
                        0, 0, 0, "", 0, 0, 0, 0, 1, 0, 0);
                live.apply(live.message(CoreMessageType.PLACE_TRIGGER_ORDER, user,
                        CoreTriggerOrderCodec.encodePlacement(new com.surprising.aeron.protocol.PlaceTriggerOrderCommand(
                        trigger.triggerOrderId(), trigger.clientTriggerOrderId(), trigger.ocoGroupId(),
                                trigger.instrumentId(), trigger.side(), trigger.triggerType(),
                                trigger.triggerCondition(), trigger.triggerPriceTicks(), trigger.activationPriceTicks(),
                                trigger.callbackRatePpm(), trigger.orderType(), trigger.timeInForce(),
                                trigger.priceTicks(), trigger.quantitySteps(), trigger.marginMode(),
                                trigger.positionSide(), trigger.expiresAtEpochMillis(), trigger.priceSource()))));
                // 源触发单同组、预期会被取消的另一条条件单视图
                var sibling = new CoreTriggerOrderStateView(9002, product, user, "trigger-9002", "oco-9001", "1",
                        CoreOrderSide.SELL, CoreTriggerOrderType.TAKE_PROFIT, CoreTriggerCondition.GREATER_OR_EQUAL,
                        200, 0, 0, 0, 0, 0, CoreOrderType.LIMIT, CoreTimeInForce.IOC, 200, 1,
                        CoreMarginMode.CROSS, CorePositionSide.NET, CoreTriggerOrderStatus.PENDING,
                        0, 0, 0, "", 0, 0, 0, 0, 1, 0, 0);
                live.apply(live.message(CoreMessageType.PLACE_TRIGGER_ORDER, user,
                        CoreTriggerOrderCodec.encodePlacement(new com.surprising.aeron.protocol.PlaceTriggerOrderCommand(
                        sibling.triggerOrderId(), sibling.clientTriggerOrderId(), sibling.ocoGroupId(),
                                sibling.instrumentId(), sibling.side(), sibling.triggerType(),
                                sibling.triggerCondition(), sibling.triggerPriceTicks(), sibling.activationPriceTicks(),
                                sibling.callbackRatePpm(), sibling.orderType(), sibling.timeInForce(),
                                sibling.priceTicks(), sibling.quantitySteps(), sibling.marginMode(),
                                sibling.positionSide(), sibling.expiresAtEpochMillis(), sibling.priceSource()))));
                live.responses.clear();
                // 账户交接代数的反射字段，用来证明交易工作留在固定 Lane
                // 执行触发命令之前记录的账户交接代数
                long beforeHandoff = live.service.state().runtimeState().laneHandoffEpoch();
                if (internalScan) scanTriggers(live, 1);
                else live.apply(live.message(CoreMessageType.EXECUTE_TRIGGER_ORDER, 0,
                        CoreTriggerOrderCodec.encodeExecute(9001, 1, 100, TIME)));
                assertThat(live.service.state().runtimeState().laneHandoffEpoch())
                        .as("trigger claim/reserve/settle stays on permanent Account Lane")
                        .isEqualTo(beforeHandoff);
                assertThat(live.responses).isNotEmpty().allSatisfy(/* 当前待检查状态或结果码的 Core 响应 */ r ->
                        assertThat(r.commandStatus()).isEqualTo(ResponseStatus.APPLIED));
                assertThat(CoreTestCompletion.userState(live.service.state(), user).reservations()).noneMatch(/* 需要确认没有为被拒子单保留冻结的账户预留项 */ r -> r.orderId() == 18005);
                assertThat(CoreTestCompletion.orderState(live.service.state(), user, 18005).status())
                        .isEqualTo("REJECTED");
                // 结束后按所属账户读取的源触发单终态
                var terminal = CoreTestCompletion.triggerState(live.service.state(), user, 9001);
                assertThat(terminal.status()).isEqualTo(CoreTriggerOrderStatus.TRIGGER_FAILED);
                assertThat(terminal.placedOrderId()).isZero();
                assertThat(CoreTestCompletion.triggerState(live.service.state(), user, 9002).status())
                        .isEqualTo(CoreTriggerOrderStatus.CANCELED);
                if (recovery) try (/* 从当前快照恢复出的独立 Core，资源结束时关闭 */ var restored = TradingCoreRuntime.fromSnapshot(product, live.service.captureSnapshot(100))) {
                    assertThat(restored.tradingState().businessStateHash()).isEqualTo(live.hash());
                }
                live.apply(live.cancel(user, 18003));
                assertThat(live.responses.getLast().commandStatus()).isEqualTo(ResponseStatus.APPLIED);
            }
        }
        }
    }

    /**
     * 分别制造现货触发数量溢出及卖出资金不足，检查回滚或失败收尾及恢复。
     * @param overflow 是否用最大数量制造溢出；否则构造卖出缺币失败
     */
    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void triggerChildFailureClosesOrRollsBackOcoOnItsLane(boolean overflow) throws Exception {
        verifyTriggerFailure(overflow, true);
    }

    /**
     * 检查相同两种触发准入失败，不追加快照恢复。
     * @param overflow 是否用最大数量制造溢出；否则构造卖出缺币失败
     */
    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void triggerAdmissionFailureWithoutSnapshot(boolean overflow) throws Exception {
        verifyTriggerFailure(overflow, false);
    }

    /**
     * 数量溢出时原命令拒绝且两条条件单仍待触发；卖出缺币时命令完成但源触发失败、兄弟取消。两种情况均无子单、客户键或账户资金残留，交接代数不变，按参数恢复比较。
     * @param overflow 是否用最大数量制造溢出；否则构造卖出缺币失败
     * @param recovery 是否额外比较快照恢复状态
     */
    private void verifyTriggerFailure(boolean overflow, boolean recovery) throws Exception {
        try (/* 接收交错或并发在途输入的测试 Fixture，退出资源作用域时关闭 */ Fixture live = new Fixture(ProductLine.SPOT)) {
            live.setup();
            // 当前需要加资、下单或查条件单的用户编号
            long user = 11;
            for (long /* 当前被登记或检查的条件单编号 */ id : new long[]{9101, 9102}) {
                // 当前待登记条件单视图
                var trigger = new CoreTriggerOrderStateView(id, ProductLine.SPOT, user, "failure-" + id,
                        "failure-oco", "1", overflow ? CoreOrderSide.BUY : CoreOrderSide.SELL,
                        CoreTriggerOrderType.TAKE_PROFIT, CoreTriggerCondition.GREATER_OR_EQUAL,
                        100, 0, 0, 0, 0, 0, CoreOrderType.LIMIT, CoreTimeInForce.IOC, 100,
                        overflow && id == 9101 ? Long.MAX_VALUE : 1,
                        CoreMarginMode.CROSS, CorePositionSide.NET, CoreTriggerOrderStatus.PENDING,
                        0, 0, 0, "", 0, 0, 0, 0, 1, 0, 0);
                live.apply(live.message(CoreMessageType.PLACE_TRIGGER_ORDER, user,
                        CoreTriggerOrderCodec.encodePlacement(new com.surprising.aeron.protocol.PlaceTriggerOrderCommand(
                        trigger.triggerOrderId(), trigger.clientTriggerOrderId(), trigger.ocoGroupId(),
                                trigger.instrumentId(), trigger.side(), trigger.triggerType(),
                                trigger.triggerCondition(), trigger.triggerPriceTicks(), trigger.activationPriceTicks(),
                                trigger.callbackRatePpm(), trigger.orderType(), trigger.timeInForce(),
                                trigger.priceTicks(), trigger.quantitySteps(), trigger.marginMode(),
                                trigger.positionSide(), trigger.expiresAtEpochMillis(), trigger.priceSource()))));
                assertThat(live.responses.getLast().commandStatus()).isEqualTo(ResponseStatus.APPLIED);
            }
            // 当前 Core 中所属账户及风险扫描的权威运行时
            var runtime = live.service.state().runtimeState();
            // 账户所有权交接代数的反射字段
            // 触发失败前导出的用户账户状态，用来检查资金没有残留变化
            var accountsBefore = live.service.state().tradingState().users();
            // 执行失败触发命令之前的账户交接代数
            long before = runtime.laneHandoffEpoch();
            live.responses.clear();
            live.apply(live.message(CoreMessageType.EXECUTE_TRIGGER_ORDER, user,
                    CoreTriggerOrderCodec.encodeExecute(9101, 1, 100, TIME)));
            assertThat(runtime.laneHandoffEpoch()).isEqualTo(before);
            assertThat(live.responses.getLast().commandStatus())
                    .isEqualTo(overflow ? ResponseStatus.REJECTED : ResponseStatus.APPLIED);
            assertThat(CoreTestCompletion.triggerState(live.service.state(), user, 9101).status())
                    .isEqualTo(overflow ? CoreTriggerOrderStatus.PENDING : CoreTriggerOrderStatus.TRIGGER_FAILED);
            assertThat(CoreTestCompletion.triggerState(live.service.state(), user, 9102).status())
                    .isEqualTo(overflow ? CoreTriggerOrderStatus.PENDING : CoreTriggerOrderStatus.CANCELED);
            assertThat(CoreTestCompletion.orderState(live.service.state(), user, 18203)).isNull();
            // 在所属账户线程查被拒子单客户键后回传的 Future
            var clientIdentity = new java.util.concurrent.CompletableFuture<Long>();
            assertThat(runtime.dispatchAccountQuery(runtime.topology().accountLaneMask(user), /* 账户查询回调接收的所属 Lane，用于读取客户身份键 */ lane ->
                    clientIdentity.complete(live.service.state().identities().findClientKeyValueInLane(
                            lane, user, "TRIGGER:9101")))).isTrue();
            assertThat(clientIdentity.get(3, TimeUnit.SECONDS)).isZero();
            assertThat(live.service.state().tradingState().users()).isEqualTo(accountsBefore);
            if (recovery) try (/* 从当前快照恢复出的独立 Core，资源结束时关闭 */ var restored = TradingCoreRuntime.fromSnapshot(ProductLine.SPOT, live.service.captureSnapshot(101))) {
                assertThat(restored.tradingState().businessStateHash()).isEqualTo(live.hash());
            }
        }
    }

    /**
     * 首单登记后轮询一次再送独立后单，检查两条终态、串行哈希和恢复一致；实际等待长短由工作线程调度决定。
     * @param product 当前场景独立产品线，实际范围由各测试枚举源限定
     */
    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void independentIngressContinuesWhileEarlierCommitPrefixWaitsForLanes(ProductLine product) throws Exception {
        try (/* 接收交错或并发在途输入的测试 Fixture，退出资源作用域时关闭 */ Fixture live = new Fixture(product); /* 接收相同原消息但逐条等完成的串行对照 Fixture */ Fixture serial = new Fixture(product)) {
            serial.applyAll(live.setup());
            // 当前先登记的原命令消息
            var first = live.place(11, "1", 101, 80, 1, CoreOrderSide.BUY);
            // 当前后登记的独立原命令消息
            var second = live.place(disjointUser(11), "1", disjointOrder(101), 81, 1, CoreOrderSide.BUY);
            live.send(first);
            live.service.pollCommands();
            live.send(second);
            live.tick(); serial.apply(first); serial.apply(second);
            assertThat(live.responses).hasSize(2).allSatisfy(/* 当前完成回调或响应列表中的 Core 结果 */ response ->
                    assertThat(response.commandStatus()).isEqualTo(ResponseStatus.APPLIED));
            assertThat(live.hash()).isEqualTo(serial.hash());
            try (/* 从当前快照恢复出的独立 Core，资源结束时关闭 */ var restored = TradingCoreRuntime.fromSnapshot(product, live.service.captureSnapshot(100))) {
                assertThat(restored.tradingState().businessStateHash()).isEqualTo(live.hash());
            }
        }
    }

    /**
     * 单项批次分别走挂单、立即成交及极值拒绝，检查不增加账户交接代数、单项状态和成交数正确，并与串行及恢复比较。
     * @param product 当前场景独立产品线，实际范围由各测试枚举源限定
     */
    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void singleItemPlaceBatchKeepsAccountOwnershipOnLane(ProductLine product) throws Exception {
        for (/* 当前单项批次场景，零为挂单、一为成交、二为极值拒绝 */ int scenario = 0; scenario < 3; scenario++) {
            try (/* 接收交错或并发在途输入的测试 Fixture，退出资源作用域时关闭 */ Fixture live = new Fixture(product); /* 接收相同原消息但逐条等完成的串行对照 Fixture */ Fixture serial = new Fixture(product)) {
                serial.applyAll(live.setup());
                // 预先提供流动性的用户编号
                long maker = disjointUser(11);
                if (scenario == 1) {
                    if (product == ProductLine.SPOT) {
                        // 为本轮测试账户或做市账户加资的原消息
                        var funds = live.message(CoreMessageType.ADJUST_BALANCE, maker,
                                TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("BTC", 20_000)));
                        live.apply(funds); serial.apply(funds);
                    }
                    // 为当前成交场景预先准备的做市加资及挂单消息
                    var liquidity = live.place(maker, "1", 7900, 80, 1, CoreOrderSide.SELL);
                    live.apply(liquidity); serial.apply(liquidity);
                }
                // 本轮单项批次的原始下单输入，极值场景数量为最大长整数
                var item = new PlaceOrderCommand(8000, "1", CoreOrderSide.BUY, 80,
                        scenario == 2 ? Long.MAX_VALUE : 1, false, CoreMarginMode.CROSS,
                        CorePositionSide.NET, CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "single-batch");
                // 本场景待提交的原始批量消息
                var batch = live.message(CoreMessageType.PLACE_ORDER_BATCH, 11,
                        TradingOrderBatchCodec.encodePlaceOrderBatch(new PlaceOrderBatchCommand(List.of(item))));
                // 当前 Core 中所属账户及风险扫描的权威运行时
                var runtime = live.service.state().runtimeState();
                // 账户所有权交接代数的反射字段
                // 单项批次开始前的账户交接代数
                long before = runtime.laneHandoffEpoch();
                live.responses.clear();
                live.apply(batch); serial.apply(batch);
                assertThat(runtime.laneHandoffEpoch()).as("single-item admission/settlement never takes Lane ownership")
                        .isEqualTo(before);
                // 单项批次响应解码得到的一项结果列表
                var result = TradingOrderBatchCodec.decodeResult(live.responses.getFirst().data()).items();
                assertThat(result).hasSize(1);
                assertThat(result.getFirst().status()).isEqualTo(
                        scenario == 2 ? ResponseStatus.REJECTED : ResponseStatus.APPLIED);
                assertThat(result.getFirst().executions()).hasSize(scenario == 1 ? 1 : 0);
                assertThat(live.hash()).isEqualTo(serial.hash());
                try (/* 从当前快照恢复出的独立 Core，资源结束时关闭 */ var restored = TradingCoreRuntime.fromSnapshot(product, live.service.captureSnapshot(100))) {
                    assertThat(restored.tradingState().businessStateHash()).isEqualTo(live.hash());
                }
            }
        }
    }

    /**
     * 先登记到期前交割批次，再送时间已越过到期点的独立消息；首批仍按原日志时间成功，最终哈希与同顺序逐条执行一致。
     */
    @Test
    void deferredSequentialBatchUsesOriginalLogTimeEvenWhenLaterIngressIsAfterExpiry() {
        try (/* 接收交错或并发在途输入的测试 Fixture，退出资源作用域时关闭 */ Fixture live = new Fixture(ProductLine.LINEAR_DELIVERY);
             /* 接收相同原消息但逐条等完成的串行对照 Fixture */ Fixture serial = new Fixture(ProductLine.LINEAR_DELIVERY)) {
            serial.applyAll(live.setup());
            // 提交时间仍在交割到期前的单项批次输入
            var item = new PlaceOrderCommand(1000, "1", CoreOrderSide.BUY, 80, 1,
                    false, CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT,
                    CoreTimeInForce.GTC, false, "before-expiry");
            // 本场景待提交的原始批量消息
            var batch = live.message(CoreMessageType.PLACE_ORDER_BATCH, 11,
                    TradingOrderBatchCodec.encodePlaceOrderBatch(new PlaceOrderBatchCommand(List.of(item))));
            // 用于构造更晚业务时间的原独立订单消息
            var original = live.place(disjointUser(11), disjointSymbol("1"), 2000, 80, 1, CoreOrderSide.BUY);
            // 原独立消息头，复制身份但替换业务时间
            var h = original.header();
            // 业务时间已超过交割到期点的后续独立消息
            var later = new CoreMessage(CoreMessageHeader.command(h.messageType(), h.commandId(),
                    ProductLine.LINEAR_DELIVERY, CommandSource.OPERATIONS, 990, h.sourceSequence(),
                    h.userId(), TIME + 200_000, h.correlationId()), original.payloadUnsafe());
            live.send(batch); live.send(later); live.tick();
            serial.apply(batch); serial.apply(later);
            assertThat(TradingOrderBatchCodec.decodeResult(live.responses.getFirst().data()).items())
                    .singleElement().satisfies(/* 批量响应中需要确认在到期前成功准入的唯一项 */ result -> assertThat(result.status()).isEqualTo(ResponseStatus.APPLIED));
            assertThat(live.hash()).isEqualTo(serial.hash());
        }
    }

    /**
     * 同品种不同账户普通单可同时留在窗口，放行后与逐条执行、Follower 回放及恢复一致。
     * @param product 当前场景独立产品线，实际范围由各测试枚举源限定
     */
    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void sameSymbolIndependentOrdersRemainInFlightAndRecoverLikeSerialExecution(ProductLine product) {
        sameSymbolIndependentOrders(product, false);
    }

    /**
     * 把相同窗口及回放检查用于两批二十项订单。
     * @param product 当前场景独立产品线，实际范围由各测试枚举源限定
     */
    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void sameSymbolIndependentBatchesRemainInFlightAndRecoverLikeSerialExecution(ProductLine product) {
        sameSymbolIndependentOrders(product, true);
    }

    /**
     * 在 Matcher 栅栏上等待放行，确认两条独立账户命令共占窗口且无响应；放行后比较 Leader、串行、Follower 业务哈希及快照恢复。
     * @param product 当前场景独立产品线，实际范围由各测试枚举源限定
     * @param batch 是否使用二十项批次，否则用普通订单
     */
    private void sameSymbolIndependentOrders(ProductLine product, boolean batch) {
        try (/* 接收交错或并发在途输入的测试 Fixture，退出资源作用域时关闭 */ Fixture live = new Fixture(product); /* 接收相同原消息但逐条等完成的串行对照 Fixture */ Fixture serial = new Fixture(product);
             /* 以 Follower 角色接收相同命令的回放对照 Fixture */ Fixture replay = new Fixture(product, Cluster.Role.FOLLOWER)) {
            // 初始化原消息列表，复制给串行或 Follower Fixture
            var setup = live.setup(); serial.applyAll(setup); replay.applyAll(setup);
            // 当前先登记的原命令消息
            var first = batch ? live.placeBatch(11, "1", 100)
                    : live.place(11, "1", 100, 80, 1, CoreOrderSide.BUY);
            // 当前后登记的独立原命令消息
            var second = batch ? live.placeBatch(disjointUser(11), "1", 200)
                    : live.place(disjointUser(11), "1", 200, 81, 1, CoreOrderSide.BUY);
            // 用于放行 Matcher 测试栅栏的门闩或 Future
            var releaseMatcher = new java.util.concurrent.CompletableFuture<Void>();
            // 在 Matcher 队列中先于订单执行的阻塞栅栏 Future
            var matcherFence = live.service.state().matcherPipeline().readAtSubmissionFence(
                    live.service.state().matchingAdapter().matcherShardId("1"), () -> {
                        releaseMatcher.join();
                        return true;
                    });
            try {
                live.send(first); live.send(second);
                assertThat(live.service.commandWindowSize()).isEqualTo(2);
                assertThat(live.responses).isEmpty();
            } finally {
                releaseMatcher.complete(null);
            }
            matcherFence.join();
            live.tick(); serial.apply(first); serial.apply(second);
            replay.send(first); replay.send(second); replay.tick();
            assertThat(live.responses).hasSize(2).allSatisfy(/* 当前待检查状态或结果码的 Core 响应 */ r ->
                    assertThat(r.commandStatus()).isEqualTo(ResponseStatus.APPLIED));
            assertThat(live.hash()).isEqualTo(serial.hash()).isEqualTo(replay.hash());
            try (/* 从当前快照恢复出的独立 Core，资源结束时关闭 */ var restored = TradingCoreRuntime.fromSnapshot(product, live.service.captureSnapshot(100))) {
                assertThat(restored.tradingState().businessStateHash()).isEqualTo(live.hash());
            }
        }
    }

    /**
     * 普通完成入口可能已直接返回终态，应保存该响应再决定是否继续等待；核对批次成功、串行及恢复哈希，避免向已退休槽再索取结果。
     * @param product 当前场景独立产品线，实际范围由各测试枚举源限定
     */
    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void ordinaryCommitAndSpeculativeDispatchCannotSubmitTheSameBatchTwice(ProductLine product) throws Exception {
        try (/* 接收交错或并发在途输入的测试 Fixture，退出资源作用域时关闭 */ Fixture live = new Fixture(product); /* 接收相同原消息但逐条等完成的串行对照 Fixture */ Fixture serial = new Fixture(product)) {
            serial.applyAll(live.setup());
            // 当前 Fixture 内的实际 Core 运行时
            var state = live.service.state();
            // 当前输入、比较或续扫的原 Core 消息
            var command = live.placeBatch(11, "1", 8000);
            // 该命令原消息固定的业务时间
            long timestamp = command.header().submittedAtEpochMillis();
            state.applyClusterCommand(command, timestamp, 0);
            // 当前原命令登记得到的完整 Core 序号
            long sequence = state.matchingSequence(command.header().commandId());
            // 登记后取得的原命令槽局部引用，当前用例随后没有再读取它
            var context = state.pendingMatching().required(sequence);
            // 完成入口已经取得的 Matcher 事实
            var result = CoreTestCompletion.awaitMatchingResult(state, sequence, TimeUnit.SECONDS.toNanos(5));
            assertThat(result).isNotNull();
            // 账户已就绪时这次完成调用可能直接返回终态。
            // 保留该响应，不能再向已经退休的槽要求第二份结果。
            // 完成调用可能直接返回的终态响应，已有值就保留，避免再次等已退休槽
            var receipt = state.completeMatching(sequence, result, timestamp, 0);
            if (receipt == null) receipt = CoreTestCompletion.completeMatchingSynchronously(state, sequence, timestamp, 0);
            assertThat(receipt.commandStatus()).isEqualTo(ResponseStatus.APPLIED);
            serial.apply(command);
            assertThat(live.hash()).isEqualTo(serial.hash());
            try (/* 从当前快照恢复出的独立 Core，资源结束时关闭 */ var restored = TradingCoreRuntime.fromSnapshot(product, state.snapshot())) {
                assertThat(restored.tradingState().businessStateHash()).isEqualTo(live.hash());
            }
        }
    }
    /**
     * 准备六十四个品种和用户，在有限账户 Lane 中交错提交六十四批；全部终态成功，最终哈希与逐批执行一致。
     * @param product 当前场景独立产品线，实际范围由各测试枚举源限定
     */
    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void independentBatchesSharingLanesKeepSettlementSequenceOrdered(ProductLine product) {
        try (/* 接收交错或并发在途输入的测试 Fixture，退出资源作用域时关闭 */ Fixture live = new Fixture(product); /* 接收相同原消息但逐条等完成的串行对照 Fixture */ Fixture serial = new Fixture(product)) {
            serial.applyAll(live.setup());
            // 当前产品对应的合约类型，决定资产、乘数和到期配置
            var type = ContractType.valueOf(product.contractTypeCode());
            // 该产品准入或流动性准备使用的资产名称
            String asset = type.isInverse() ? "BTC" : "USDT";
            // 准备交错送入窗口的六十四条批量消息
            var batches = new ArrayList<CoreMessage>();
            for (/* 当前初始化、查找候选或重复轮询的循环下标 */ int i = 0; i < 64; i++) {
                // 当前注册、路由或故障注入使用的品种标识，具体类型见声明
                String instrumentId = Integer.toString(1000+i);
                // 当前需要加资、下单或查条件单的用户编号
                long user = 10_000 + i;
                // 初始化原消息列表，复制给串行或 Follower Fixture
                var setup = List.of(
                        live.message(CoreMessageType.REGISTER_INSTRUMENT, 0,
                                TradingCommandCodec.encodeRegisterInstrument(new RegisterInstrumentCommand(
                                        instrumentId, "BTC", "USDT", asset, 1, 1, type.isInverse() ? 1_000 : 1,
                                        (type == ContractType.SPOT ? null : new CoreInstrumentRiskLimits(
                                                100_000, (1_000_000_000_000L / (100_000)), Long.MAX_VALUE, 0,
                                                Long.MAX_VALUE,
                                                java.util.List.of(new CoreRiskLimitBracket(Long.MAX_VALUE, (1_000_000_000_000L / (100_000)), 100_000, 50_000)))),
                                        0, 0, type.isDelivery() || type.isOption() ? TIME + 100_000 : 0,
                                        type.isOption() ? 0 : -1, type.isOption() ? 100 : 0), product)),
                        live.message(CoreMessageType.APPLY_MARK_PRICE, 0,
                                TradingCommandCodec.encodeApplyMarkPrice(type.isOption()
                                        ? new ApplyMarkPriceCommand(instrumentId, 100, 100, 100, 1, TIME)
                                        : new ApplyMarkPriceCommand(instrumentId, 100, 1, TIME))),
                        live.message(CoreMessageType.ADJUST_BALANCE, user,
                                TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand(asset, 20_000))));
                live.applyAll(setup); serial.applyAll(setup);
            }
            for (/* 当前初始化、查找候选或重复轮询的循环下标 */ int i = 0; i < 64; i++) batches.add(live.placeBatch(10_000 + i, Integer.toString(1000+i), 10_000 + i * 20));
            live.responses.clear();
            for (var /* 当前输入、比较或续扫的原 Core 消息 */ command : batches) live.send(command);
            live.tick(); serial.applyAll(batches);
            assertThat(live.responses).hasSize(64).allSatisfy(/* 当前待检查状态或结果码的 Core 响应 */ r -> assertThat(r.commandStatus()).isEqualTo(ResponseStatus.APPLIED));
            assertThat(live.hash()).isEqualTo(serial.hash());
        }
    }
    /**
     * 用窗口直接检查撤单依赖：其他账户不同订单无需因同品种互斥，同账户或同订单仍冲突；撮合范围约束归 Matcher。实际撤单和独立单完成后与串行及恢复一致。
     * @param product 当前场景独立产品线，实际范围由各测试枚举源限定
     */
    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void cancellationFencesRemovedLiquidityWithoutBlockingTheWholeSymbol(ProductLine product) {
        try (/* 接收交错或并发在途输入的测试 Fixture，退出资源作用域时关闭 */ Fixture live = new Fixture(product); /* 接收相同原消息但逐条等完成的串行对照 Fixture */ Fixture serial = new Fixture(product)) {
            serial.applyAll(live.setup());
            // 开始测试前已完成的挂单或批次消息
            var resting = live.place(11, "1", 4100, 80, 1, CoreOrderSide.BUY);
            live.apply(resting); serial.apply(resting);
            // 当前要执行的普通或批量撤单消息
            var cancel = live.cancel(11, 4100);
            // 与原请求用户选择不同默认账户 Lane 的另一用户编号
            long other = disjointUser(11);
            // 当前撤单之后可独立登记的另一账户订单消息
            var independent = live.place(other, "1", 4200, 80, 1, CoreOrderSide.BUY);
            // 当前正在检查依赖或缓存解码对象的命令窗口
            var window = new ClusterCommandWindow();
            // 当前 Fixture 内的实际 Core 运行时
            var state = live.service.state();
            assertThat(state.prepareClusterPipelineScope(cancel, window)).isTrue();
            window.add(ClusterServiceEgress.NO_SESSION_ID, cancel, TIME, 1);
            assertThat(state.prepareClusterPipelineScope(independent, window)).isTrue();
            assertThat(window.conflicts()).as("same-side different-account order").isFalse();
            assertThat(state.prepareClusterPipelineScope(
                    live.place(other, "1", 4201, 81, 1, CoreOrderSide.SELL), window)).isTrue();
            assertThat(window.conflicts()).as("sell cannot consume the removed buy").isFalse();
            assertThat(state.prepareClusterPipelineScope(
                    live.place(other, "1", 4202, 80, 1, CoreOrderSide.SELL), window)).isTrue();
            // 撮合价格范围和对手顺序由 Matcher 维护；Owner 入口只检查请求账户 Lane 及精确订单身份。
            assertThat(window.conflicts()).as("crossing sell is not an Owner ingress dependency").isFalse();
            assertThat(state.prepareClusterPipelineScope(
                    live.place(11, "1", 4203, 79, 1, CoreOrderSide.BUY), window)).isTrue();
            assertThat(window.conflicts()).as("same account must observe released funds").isTrue();
            assertThat(state.prepareClusterPipelineScope(cancel, window)).isTrue();
            assertThat(window.conflicts()).as("same order identity remains fenced").isTrue();
            live.responses.clear();
            live.send(cancel); live.send(independent); live.tick();
            serial.apply(cancel); serial.apply(independent);
            assertThat(live.responses).hasSize(2)
                    .allSatisfy(/* 两条实际完成消息中的当前 Core 响应 */ result -> assertThat(result.commandStatus()).isEqualTo(ResponseStatus.APPLIED));
            assertThat(live.hash()).isEqualTo(serial.hash());
            try (/* 从当前快照恢复出的独立 Core，资源结束时关闭 */ var restored = TradingCoreRuntime.fromSnapshot(product, state.snapshot())) {
                assertThat(restored.tradingState().businessStateHash()).isEqualTo(live.hash());
            }
        }
    }

    /**
     * 重复刷新同批窗口范围时保留同一解码对象，准入也接收该对象；完成后二十项结果有效，避免重复解码。
     * @param product 当前场景独立产品线，实际范围由各测试枚举源限定
     */
    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void scopeRefreshAndAdmissionShareOneDecodedBatch(ProductLine product) throws Exception {
        try (/* 当前待初始化、重放或比对的测试 Fixture */ var fixture = new Fixture(product)) {
            fixture.setup();
            // 当前正在检查依赖或缓存解码对象的命令窗口
            var window = new ClusterCommandWindow();
            // 当前输入、比较或续扫的原 Core 消息
            var command = fixture.placeBatch(11, "1", 5000);
            // 当前 Fixture 内的实际 Core 运行时
            var state = fixture.service.state();
            assertThat(state.prepareClusterPipelineScope(command, window)).isTrue();
            // 同一原消息共用的解码对象，依赖等待期间保留其引用
            var decoded = window.decoded(command);
            assertThat(state.prepareClusterPipelineScope(command, window)).isTrue();
            assertThat(window.decoded(command)).isSameAs(decoded);
            state.applyClusterCommand(command, TIME, 1000, decoded);
            // 仍在生命周期中的原命令槽
            var pending = state.pendingMatching().get(state.matchingSequence(command.header().commandId()));
            assertThat(pending.decodedCommand()).isSameAs(decoded);
            // 当前已完成并可核对状态与逐项结果的 Core 响应
            var completed = CoreTestCompletion.completeMatchingSynchronously(state, pending.sequence(), TIME, 1000);
            assertThat(completed.commandStatus()).isEqualTo(ResponseStatus.APPLIED);
            assertThat(TradingOrderBatchCodec.decodeResult(completed.data()).items()).hasSize(20);
        }
    }

    /**
     * 用先后两个栅栏只放行首命令，后缀 Matcher 仍阻塞时要求 Owner 已返回首条终态、另两项仍在窗口；再放行排空全部。
     * @param product 当前场景独立产品线，实际范围由各测试枚举源限定
     */
    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void prefixCommitReturnsWhileIndependentSuffixMatcherIsStillBlocked(ProductLine product) throws Exception {
        try (/* 接收交错或并发在途输入的测试 Fixture，退出资源作用域时关闭 */ Fixture live = new Fixture(product)) {
            live.setup();
            // 需要先退休的前缀原消息
            var a = live.place(11, "1", 1000, 80, 1, CoreOrderSide.BUY);
            // 可以保持在途的独立后缀原消息
            var b = live.place(disjointUser(11), disjointSymbol("1"), 2000, 80, 1, CoreOrderSide.BUY);
            // 依赖前缀账户的第三条原消息
            var c = live.place(11, "1", 3000, 79, 1, CoreOrderSide.BUY);
            // 实际 Matcher 管线组，用于安排精确执行栅栏
            var matcher = live.service.state().matcherPipeline();
            // 确认 Matcher 已执行到阻塞点的门闩或 Future
            var entered = new CountDownLatch(1);
            // 阻塞工作线程后用于放行的门闩或 Future，清理路径负责释放
            var release = new CountDownLatch(1);
            // 先释放首命令的 Future，不同时释放后缀阻塞
            var firstRelease = new java.util.concurrent.CompletableFuture<Void>();
            // 排在首命令之前的初始 Matcher 栅栏 Future
            var firstFence = matcher.readAtSubmissionFence(0, () -> {
                firstRelease.join();
                return 1;
            });
            // 确认后缀仍未放行的 Matcher 栅栏 Future
            java.util.concurrent.CompletableFuture<Integer> blocked;
            try {
                live.send(a);
                // 仍在生命周期中的原命令槽
                var pending = live.service.state().pendingMatching().get(live.service.state().matchingSequence(a.header().commandId()));
                // 当前有界等待的 nanoTime 截止点
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (!pending.isMatchingSubmitted() && System.nanoTime() < deadline)
                    live.service.state().matchingProgress.drainMatchingCompletions();
                assertThat(pending.isMatchingSubmitted()).isTrue();
                blocked = matcher.readAtSubmissionFence(0, () -> {
                    entered.countDown();
                    try { if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("suffix matcher timeout"); }
                    catch (/* 等待测试门闩被中断的异常，按原测试分支转为断言或运行失败 */ InterruptedException failure) { throw new IllegalStateException(failure); }
                    return 1;
                });
            } finally { firstRelease.complete(null); }
            firstFence.join();
            try {
                assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
                live.send(b); live.send(c);
                live.progressUntil(() -> live.responses.size() == 1);
                assertThat(blocked.isDone()).isFalse();
                assertThat(live.responses).hasSize(1);
                assertThat(live.service.commandWindowSize()).isEqualTo(2);
            } finally { release.countDown(); }
            live.tick();
            assertThat(blocked.join()).isOne();
            assertThat(live.responses).hasSize(3);
        }
    }

    /**
     * 两条独立批次各发生真实成交，第三条依赖首批账户；在第一响应边界只取并核对首品种二十条成交，最终响应、实时分组、串行、Follower 及恢复状态一致。
     * @param product 当前场景独立产品线，实际范围由各测试枚举源限定
     */
    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void dependencyDrainsOnlyItsOrderedPrefixAndKeepsIndependentSuffix(ProductLine product) {
        try (/* 接收交错或并发在途输入的测试 Fixture，退出资源作用域时关闭 */ Fixture live = new Fixture(product); /* 接收相同原消息但逐条等完成的串行对照 Fixture */ Fixture serial = new Fixture(product);
             /* 以 Follower 角色接收相同命令的回放对照 Fixture */ Fixture replay = new Fixture(product, Cluster.Role.FOLLOWER)) {
            // 初始化原消息列表，复制给串行或 Follower Fixture
            var setup = live.setup(); serial.applyAll(setup); replay.applyAll(setup);
            // 两个独立批次都产生真实成交，后缀变化不能混入前缀命令的实时分组。
            // 当前用于选择账户 Lane 的拓扑
            LaneTopology topology = live.service.state().runtimeState().topology();
            // 本场景已占用的账户 Lane 位图，后续选择 maker 时避开
            long used = topology.accountLaneMask(11) | topology.accountLaneMask(disjointUser(11));
            // 给两个品种 maker 订单分配不同编号的计数
            int symbolNumber = 0;
            for (String /* 当前注册、路由或故障注入使用的品种标识，具体类型见声明 */ instrumentId : List.of("1", disjointSymbol("1"))) {
                // 正在寻找空闲账户 Lane 的卖方用户编号
                long seller = 100;
                while ((topology.accountLaneMask(seller) & used) != 0) seller++;
                used |= topology.accountLaneMask(seller);
                // 当前产品对应的合约类型，决定资产、乘数和到期配置
                ContractType type = ContractType.valueOf(product.contractTypeCode());
                // 该产品准入或流动性准备使用的资产名称
                String asset = product == ProductLine.SPOT || type.isInverse() ? "BTC" : "USDT";
                // 准备对手流动性或测试账户的余额调整消息
                var deposit = live.message(CoreMessageType.ADJUST_BALANCE, seller,
                        TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand(asset, 20_000)));
                // 当前品种预先挂入的卖单消息
                var ask = live.place(seller, instrumentId, 100 + symbolNumber++, 80, 20, CoreOrderSide.SELL);
                for (Fixture /* 当前待初始化、重放或比对的测试 Fixture */ fixture : List.of(live, serial, replay)) { fixture.apply(deposit); fixture.apply(ask); }
            }
            live.responses.clear();
            // 只在本测试收集实时帧的有界输出队列
            var outbox = new com.surprising.aeron.client.RealtimeOutbox(8192, 8 * 1024 * 1024);
            CoreFaults.attachRealtime(live.service, outbox);
            // 需要先退休的前缀原消息
            var a = live.placeBatch(11, "1", 1000);
            // 可以保持在途的独立后缀原消息
            var b = live.placeBatch(disjointUser(11), disjointSymbol("1"), 2000);
            // 依赖前缀账户的第三条原消息
            var c = live.place(11, "1", 3000, 79, 1, CoreOrderSide.BUY);
            serial.apply(a); serial.apply(b); serial.apply(c);
            // 一次回调可能连续完成多个前缀。
            // 在真实首响应边界观察，不要求依赖调度时机的中间窗口大小。
            // 首响应回调已观察的前缀成交数单元素槽
            int[] prefixTrades = {0};
            live.firstResponse = () -> {
                // 当前从实时队列取出的帧字节或会话代理复制的完整消息字节
                byte[] bytes;
                while (prefixTrades[0] < 20 && (bytes = outbox.poll()) != null) {
                    // 解码后的实时帧，按类别检查成交或快照边界
                    var frame = RealtimeFrameCodec.decode(bytes);
                    if (frame.kind() == RealtimeFrame.Kind.TRADE) {
                        assertThat(frame.instrumentId()).isEqualTo("1");
                        prefixTrades[0]++;
                    }
                }
                assertThat(prefixTrades[0]).isEqualTo(20);
            };
            live.send(a); live.send(b); live.send(c);
            live.tick();
            assertThat(prefixTrades[0]).isEqualTo(20);
            replay.send(a); replay.send(b); replay.send(c); replay.tick();
            assertThat(live.responses).hasSize(3).allSatisfy(/* 当前待检查状态或结果码的 Core 响应 */ r -> assertThat(r.status()).isEqualTo(ResponseStatus.APPLIED));
            assertThat(live.hash()).isEqualTo(serial.hash()).isEqualTo(replay.hash());
            assertThat(live.service.state().tradingState().users()).isEqualTo(serial.service.state().tradingState().users());
            try (/* 从当前快照恢复出的独立 Core，资源结束时关闭 */ TradingCoreRuntime restored = TradingCoreRuntime.fromSnapshot(product, live.service.captureSnapshot(880))) {
                assertThat(restored.tradingState().businessStateHash()).isEqualTo(live.hash());
            }
        }
    }

    /**
     * 两个品种已有同账户低价买单，新来的独立卖单价格不交叉；检查两项可共占窗口、串行哈希一致，旧买单没有成交。
     */
    @Test
    void nonCrossingSharedRestingBuyerDoesNotSerializeIndependentSellers() {
        try (/* 接收交错或并发在途输入的测试 Fixture，退出资源作用域时关闭 */ Fixture live = new Fixture(ProductLine.SPOT); /* 接收相同原消息但逐条等完成的串行对照 Fixture */ Fixture serial = new Fixture(ProductLine.SPOT)) {
            serial.applyAll(live.setup());
            // 两个品种低价买单共用的账户用户编号
            // 按默认拓扑与第一卖方不同 Lane 的第二用户编号
            // 第一条独立卖单所属用户编号
            long a = 11, b = disjointUser(a), shared = b + 1;
            // 当前用于选择账户 Lane 的拓扑
            LaneTopology topology = live.service.state().runtimeState().topology();
            while ((topology.accountLaneMask(shared) & (topology.accountLaneMask(a) | topology.accountLaneMask(b))) != 0) shared++;
            // 准备对手流动性或测试账户的余额调整消息
            var deposit = live.message(CoreMessageType.ADJUST_BALANCE, shared,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 20_000)));
            live.apply(deposit); serial.apply(deposit);
            for (long /* 当前需要加资、下单或查条件单的用户编号 */ user : new long[]{a, b}) {
                // 给两个独立卖方补基础币的余额消息
                var base = live.message(CoreMessageType.ADJUST_BALANCE, user,
                        TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("BTC", 100)));
                live.apply(base); serial.apply(base);
            }
            // 第一品种不会与新卖单交叉的低价买单
            var lowA = live.place(shared, "1", 100, 80, 1, CoreOrderSide.BUY);
            // 第二品种不会与新卖单交叉的低价买单
            var lowB = live.place(shared, disjointSymbol("1"), 200, 80, 1, CoreOrderSide.BUY);
            live.apply(lowA); live.apply(lowB); serial.apply(lowA); serial.apply(lowB);
            live.responses.clear();
            // 第一品种独立卖方新单
            var sellA = live.place(a, "1", 300, 102, 1, CoreOrderSide.SELL);
            // 第二品种独立卖方新单
            var sellB = live.place(b, disjointSymbol("1"), 400, 102, 1, CoreOrderSide.SELL);
            live.send(sellA); live.send(sellB);
            assertThat(live.service.commandWindowSize()).isEqualTo(2);
            assertThat(live.responses).isEmpty();
            live.tick(); serial.apply(sellA); serial.apply(sellB);
            assertThat(live.hash()).isEqualTo(serial.hash());
            assertThat(live.service.state().tradingState().order(100).executedQuantitySteps()).isZero();
            assertThat(live.service.state().tradingState().order(200).executedQuantitySteps()).isZero();
        }
    }
    /** 测试固定业务时间基准，原消息通常使用它加来源序号。 */
    private static final long TIME = 1_700_000_000_000L;

    /**
     * 连续批次中插入实时快照请求，检查请求能在提交边界被处理并有 SNAPSHOT_END；当前用例允许捕获二十或四十张订单及对应预留，随后排空。
     * @param product 当前场景独立产品线，实际范围由各测试枚举源限定
     */
    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void continuousBatchesDispatchSnapshotAtCommittedBoundary(ProductLine product) throws Exception {
        try (/* 当前测试 Fixture 的短引用，资源退出时关闭 */ Fixture f = new Fixture(product)) {
            f.setup();
            // 只在本测试收集实时帧的有界输出队列
            var outbox = new com.surprising.aeron.client.RealtimeOutbox(1024, 1_048_576);
            CoreFaults.attachRealtime(f.service, outbox);
            // 已有实时快照请求队列，等待 Owner 在提交边界消费
            var requests = f.service.snapshotRequests();
            f.send(f.placeBatch(11, "1", 1000));
            requests.add(new RealtimeFrame(product, RealtimeFrame.Kind.SNAPSHOT_REQUEST,
                    11, 0, 0, TIME, 900, "", "", new byte[0]));
            assertThat(f.service.doBackgroundWork(System.nanoTime())).isZero();
            f.send(f.placeBatch(11, "1", 2000));
            f.progressUntil(() -> requests.isEmpty());
            assertThat(f.service.commandWindowSize()).isOne();
            assertThat(requests.isEmpty()).as("continuous input must not starve a read at the preceding commit boundary").isTrue();
            // 运行时保存正在进行实时快照 Future 的反射字段
            var pendingField = TradingCoreRuntime.class.getDeclaredField("pendingRealtimeSnapshot");
            pendingField.setAccessible(true);
            // 运行时已接手的实时快照 Future，等待该次捕获完成
            var snapshot = (java.util.concurrent.CompletableFuture<?>) pendingField.get(f.service.state());
            assertThat(snapshot).isNotNull();
            snapshot.get(2, TimeUnit.SECONDS);
            f.send(f.cancelBatch(11, 1000));
            f.progressUntil(() -> !f.service.state().realtimeSnapshotPending());
            // 从 outbox 中挑出的指定快照编号全部帧
            var frames = new ArrayList<RealtimeFrame>();
            // 当前从实时队列取出的帧字节或会话代理复制的完整消息字节
            byte[] bytes;
            while ((bytes = outbox.poll()) != null) {
                // 解码后的实时帧，按类别检查成交或快照边界
                var frame = RealtimeFrameCodec.decode(bytes);
                if (frame.snapshotId() == 900) frames.add(frame);
            }
            assertThat(frames).anySatisfy(/* 解码后的实时帧，按类别检查成交或快照边界 */ frame -> assertThat(frame.kind()).isEqualTo(RealtimeFrame.Kind.SNAPSHOT_END));
            assertThat(frames.stream().filter(/* 解码后的实时帧，按类别检查成交或快照边界 */ frame -> frame.kind() == RealtimeFrame.Kind.ORDER).count()).isIn(20L, 40L);
            assertThat(frames).anySatisfy(/* 解码后的实时帧，按类别检查成交或快照边界 */ frame -> {
                assertThat(frame.kind()).isEqualTo(RealtimeFrame.Kind.USER);
                assertThat(CoreStateQueryCodec.decodeUserState(frame.payload()).reservations().size()).isIn(20, 40);
            });
            f.tick();
        }
    }

    /**
     * 阻塞 Matcher 时两条独立命令仍入窗口，放行后与串行及 Follower 一致；再交两撤单，用快照边界排空并核对恢复，额外轮询不重复响应。
     * @param product 当前场景独立产品线，实际范围由各测试枚举源限定
     */
    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void independentCommandsEnterBeforeMatcherCompletesAndRecoverExactly(ProductLine product) throws Exception {
        try (/* 接收交错或并发在途输入的测试 Fixture，退出资源作用域时关闭 */ Fixture live = new Fixture(product); /* 接收相同原消息但逐条等完成的串行对照 Fixture */ Fixture serial = new Fixture(product);
             /* 以 Follower 角色接收相同命令的回放对照 Fixture */ Fixture replay = new Fixture(product, Cluster.Role.FOLLOWER)) {
            // 初始化原消息列表，复制给串行或 Follower Fixture
            List<CoreMessage> setup = live.setup();
            serial.applyAll(setup); replay.applyAll(setup);
            // 按默认拓扑选择不同账户 Lane 的后继用户
            // 第一条独立命令用户编号
            long userA = 11, userB = disjointUser(userA);
            // 第二条命令使用的不同品种名称
            String symbolB = disjointSymbol("1");
            // 当前先登记的原命令消息
            CoreMessage first = live.place(userA, "1", 101, 80, 1, CoreOrderSide.BUY);
            // 当前后登记的独立原命令消息
            CoreMessage second = live.place(userB, symbolB, disjointOrder(101), 80, 1, CoreOrderSide.BUY);
            // 确认 Matcher 已执行到阻塞点的门闩或 Future
            // 阻塞工作线程后用于放行的门闩或 Future，清理路径负责释放
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            // 运行时 Matcher 管线字段的反射句柄
            var field = TradingCoreRuntime.class.getDeclaredField("matcherPipeline"); field.setAccessible(true);
            // 实际 Matcher 管线组，用于安排精确执行栅栏
            var matcher = (MatcherPipelineGroup) field.get(live.service.state());
            // 确认后缀仍未放行的 Matcher 栅栏 Future
            var blocked = matcher.readAtSubmissionFence(0, () -> {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("matcher test timeout");
                } catch (/* 等待 Matcher 门闩时的中断异常，按当前分支转换成测试失败 */ InterruptedException e) { throw new IllegalStateException(e); }
                return 1;
            });
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                live.send(first); live.send(second);
                assertThat(live.service.commandWindowSize()).isEqualTo(2);
                assertThat(live.responses).isEmpty();
                assertThat(live.service.doBackgroundWork(Long.MAX_VALUE)).isZero();
            } finally { release.countDown(); }
            live.tick(); assertThat(blocked.join()).isOne();
            serial.send(first); serial.tick(); serial.send(second); serial.tick();
            replay.send(first); replay.send(second); replay.tick();
            assertThat(live.responses).hasSize(2).allSatisfy(/* 当前待检查状态或结果码的 Core 响应 */ r -> assertThat(r.status()).isEqualTo(ResponseStatus.APPLIED));
            assertThat(live.hash()).isEqualTo(serial.hash()).isEqualTo(replay.hash());
            assertThat(live.service.state().tradingState().order(101).createdAtEpochMillis())
                    .isEqualTo(first.header().submittedAtEpochMillis());
            assertThat(live.service.state().tradingState().order(disjointOrder(101)).createdAtEpochMillis())
                    .isEqualTo(second.header().submittedAtEpochMillis());
            assertThat(live.service.commandWindowHighWaterMark()).isEqualTo(2);
            // 第一条活动订单的撤单消息
            var cancelA = live.cancel(userA, 101);
            // 第二条活动订单的撤单消息
            var cancelB = live.cancel(userB, TradingCommandCodec.decodePlaceOrder(second.payloadUnsafe()).orderId());
            // 确认 Matcher 已到达撤单阻塞点的门闩
            var cancelEntered = new CountDownLatch(1);
            // 撤单场景最终放行门闩
            var cancelRelease = new CountDownLatch(1);
            // 撤单阶段 Matcher 栅栏 Future
            var cancelGate = matcher.readAtSubmissionFence(0, () -> {
                cancelEntered.countDown();
                try {
                    if (!cancelRelease.await(5, TimeUnit.SECONDS)) throw new AssertionError("cancel gate timeout");
                } catch (/* 等待 Matcher 门闩时的中断异常，按当前分支转换成测试失败 */ InterruptedException e) { throw new AssertionError(e); }
                return true;
            });
            try {
                assertThat(cancelEntered.await(5, TimeUnit.SECONDS)).isTrue();
                live.send(cancelA); live.send(cancelB);
                assertThat(live.service.commandWindowSize()).isEqualTo(2);
            } finally { cancelRelease.countDown(); }
            assertThat(cancelGate.join()).isTrue();
            // 快照本身建立确定性边界，先把这两条撤单排空再捕获。
            // 快照边界排空两撤单后得到的恢复字节
            byte[] snapshot = live.service.captureSnapshot(100);
            serial.send(cancelA); serial.tick(); serial.send(cancelB); serial.tick();
            assertThat(live.hash()).isEqualTo(serial.hash());
            try (/* 从当前快照恢复出的独立 Core，资源结束时关闭 */ TradingCoreRuntime restored = TradingCoreRuntime.fromSnapshot(product, snapshot)) {
                assertThat(restored.tradingState().businessStateHash()).isEqualTo(live.hash());
            }
            assertThat(live.service.commandWindowSize()).isZero();
            live.tick(); // 额外重复轮询不能二次执行命令或重复完成响应。
            assertThat(live.responses).hasSize(4);
        }
    }

    /**
     * 现货只留一百报价币，两个品种各委托八十，检查首单成功、后单资金拒绝，最后冻结八十、可用二十。
     */
    @Test
    void sameAccountCannotSpendTheSameBalanceTwiceAcrossSymbols() {
        try (/* 当前测试 Fixture 的短引用，资源退出时关闭 */ Fixture f = new Fixture(ProductLine.SPOT)) {
            f.setup();
            f.apply(f.message(CoreMessageType.ADJUST_BALANCE, 11,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", -19_900))));
            f.responses.clear();
            f.send(f.place(11, "1", 101, 80, 1, CoreOrderSide.BUY));
            f.send(f.place(11, disjointSymbol("1"), disjointOrder(101), 80, 1, CoreOrderSide.BUY));
            // 一次 Owner 轮询可能同时发布首单终态和后续资金拒绝，不依赖两者分开到达。
            f.progressUntil(() -> !f.responses.isEmpty());
            f.tick();
            assertThat(f.responses).hasSize(2);
            assertThat(f.responses.get(0).status()).isEqualTo(ResponseStatus.APPLIED);
            assertThat(f.responses.get(1).commandStatus()).isEqualTo(ResponseStatus.REJECTED);
            // 当前请求账户的报价资产可用和冻结余额
            var balance = f.service.state().tradingState().user(11).balances().get("USDT");
            assertThat(balance.lockedUnits()).isEqualTo(80);
            assertThat(balance.availableUnits()).isEqualTo(20);
        }
    }

    /**
     * 等无资金普通单的 Matcher 与账户完成后直接收拒绝，要求发布积累已清；随后独立批次可正常完成并与串行及恢复一致。
     * @param product 当前场景独立产品线，实际范围由各测试枚举源限定
     */
    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void rejectedOrdinaryOrderThenBatchReleasePublicationContext(ProductLine product) {
        try (/* 接收交错或并发在途输入的测试 Fixture，退出资源作用域时关闭 */ Fixture live = new Fixture(product); /* 接收相同原消息但逐条等完成的串行对照 Fixture */ Fixture serial = new Fixture(product)) {
            serial.applyAll(live.setup());
            // 该产品准入或流动性准备使用的资产名称
            String asset = ContractType.valueOf(product.contractTypeCode()).isInverse() ? "BTC" : "USDT";
            // 减少账户余额，使后续下单资金不足或只够一单的调整消息
            var withdraw = live.message(CoreMessageType.ADJUST_BALANCE, 11,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand(asset, -20_000)));
            live.apply(withdraw); serial.apply(withdraw);
            // 当前先登记的原命令消息
            var first = live.place(11, "1", 10000, 80, 1, CoreOrderSide.BUY);
            // 当前 Fixture 内的实际 Core 运行时
            var state = live.service.state();
            // 该命令原消息固定的业务时间
            long timestamp = first.header().submittedAtEpochMillis();
            state.applyClusterCommand(first, timestamp, 0);
            // 当前原命令登记得到的完整 Core 序号
            long sequence = state.matchingSequence(first.header().commandId());
            // 仍在生命周期中的原命令槽
            var pending = state.pendingMatching().get(sequence);
            // 普通、批量或当前子项持有的直达结算事件
            var event = pending.settlementEvent();
            assertThat(event).isNotNull();
            // 当前有界等待的 nanoTime 截止点
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while ((!event.complete() || pending.submittedMatcherShard() != -1)
                    && System.nanoTime() < deadline) Thread.onSpinWait();
            assertThat(event.complete()).isTrue();
            // Matcher/Lane 已完成，但 owner 尚未收取拒单；直接进入有序提交。
            var response = state.completeMatching(sequence, event.directResult(), timestamp, 0);
            assertThat(response.commandStatus()).isEqualTo(ResponseStatus.REJECTED);
            assertThat(state.commits.publication.deferred()).isFalse();
            serial.apply(first);
            // 当前前缀之后继续登记的原命令
            var next = live.placeBatch(disjointUser(11), disjointSymbol("1"), 20000);
            live.apply(next); serial.apply(next);
            assertThat(live.hash()).isEqualTo(serial.hash());
            try (/* 从当前快照恢复出的独立 Core，资源结束时关闭 */ var restored = TradingCoreRuntime.fromSnapshot(product, state.snapshot())) {
                assertThat(restored.tradingState().businessStateHash()).isEqualTo(live.hash());
            }
        }
    }

    /**
     * 同时送无资金批次和独立有效普通单，检查前批各项拒绝、后单成功且可撤，最终哈希始终与串行一致。
     * @param product 当前场景独立产品线，实际范围由各测试枚举源限定
     */
    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void rejectedBatchAndIndependentOrdinaryOrderKeepProgressAndFunds(ProductLine product) {
        try (/* 接收交错或并发在途输入的测试 Fixture，退出资源作用域时关闭 */ Fixture live = new Fixture(product); /* 接收相同原消息但逐条等完成的串行对照 Fixture */ Fixture serial = new Fixture(product)) {
            serial.applyAll(live.setup());
            // 该产品准入或流动性准备使用的资产名称
            String asset = ContractType.valueOf(product.contractTypeCode()).isInverse() ? "BTC" : "USDT";
            // 减少账户余额，使后续下单资金不足或只够一单的调整消息
            var withdraw = live.message(CoreMessageType.ADJUST_BALANCE, 11,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand(asset, -20_000)));
            live.apply(withdraw); serial.apply(withdraw); live.responses.clear();
            // 资金已耗尽、预期各项均拒绝的原批量消息
            var rejected = live.placeBatch(11, "1", 1000);
            // 与被拒批次独立且资金充足的普通订单
            var valid = live.place(disjointUser(11), disjointSymbol("1"), 2000, 80, 1, CoreOrderSide.BUY);
            live.send(rejected); live.send(valid);
            assertThat(live.service.commandWindowSize() + live.responses.size()).isEqualTo(2);
            live.tick(); serial.apply(rejected); serial.apply(valid);
            assertThat(live.hash()).isEqualTo(serial.hash());
            assertThat(live.responses).hasSize(2);
            assertThat(TradingOrderBatchCodec.decodeResult(live.responses.getFirst().data()).items())
                    .allSatisfy(/* 当前批量响应的一项，读取其状态、订单或明细 */ item -> assertThat(item.status()).isEqualTo(ResponseStatus.REJECTED));
            assertThat(live.responses.getLast().status()).isEqualTo(ResponseStatus.APPLIED);
            // 当前要执行的普通或批量撤单消息
            var cancel = live.cancel(disjointUser(11), 2000);
            live.apply(cancel); serial.apply(cancel);
            assertThat(live.hash()).isEqualTo(serial.hash());
        }
    }

    /**
     * 把其他账户原单的撤单排在一条独立下单之后，轮询后两者成功，最终状态与串行及恢复一致。
     * @param product 当前场景独立产品线，实际范围由各测试枚举源限定
     */
    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void cancellationBehindSlowIndependentPlaceAdmissionResumesInSubmissionOrder(ProductLine product) throws Exception {
        try (/* 当前测试 Fixture 的短引用，资源退出时关闭 */ Fixture f = new Fixture(product); /* 接收相同原消息但逐条等完成的串行对照 Fixture */ Fixture serial = new Fixture(product)) {
            serial.applyAll(f.setup());
            // 与原请求用户选择不同默认账户 Lane 的另一用户编号
            long other = disjointUser(11);
            // 开始前挂入另一账户的订单号，后续撤单使用同一值
            long restingId = disjointOrder(101);
            // 开始测试前已完成的挂单或批次消息
            CoreMessage resting = f.place(other, disjointSymbol("1"), restingId, 80, 1, CoreOrderSide.BUY);
            f.apply(resting); serial.apply(resting);
            // 为后续改单或撤单准备的原挂单消息
            CoreMessage place = f.place(11, "1", 101, 80, 1, CoreOrderSide.BUY);
            // 当前要执行的普通或批量撤单消息
            CoreMessage cancel = f.cancel(other, restingId);
            f.send(place); f.send(cancel);
            f.tick(); serial.apply(place); serial.apply(cancel);
            assertThat(f.hash()).isEqualTo(serial.hash());
            assertThat(f.responses).allSatisfy(/* 当前待检查状态或结果码的 Core 响应 */ r -> assertThat(r.status()).isEqualTo(ResponseStatus.APPLIED));
            try (/* 从当前快照恢复出的独立 Core，资源结束时关闭 */ TradingCoreRuntime restored = TradingCoreRuntime.fromSnapshot(product, f.service.captureSnapshot(700))) {
                assertThat(restored.tradingState().businessStateHash()).isEqualTo(f.hash());
            }
        }
    }

    /**
     * 两条独立普通单分别与不同 maker 成交，检查双边结果、实时帧数量及恢复。
     * @param product 当前场景独立产品线，实际范围由各测试枚举源限定
     */
    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void independentFillsSettleBothSidesAndMatchSerialFunds(ProductLine product) {
        independentFills(product, false);
    }

    /**
     * 把双边结算与实时帧数量检查用于两批订单。
     * @param product 当前场景独立产品线，实际范围由各测试枚举源限定
     */
    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void independentBatchFillsSettleBothSidesAndMatchSerialFunds(ProductLine product) {
        independentFills(product, true);
    }

    /**
     * 使用彼此不同账户 Lane 的 maker，委托共同成交场景。
     * @param product 当前场景独立产品线，实际范围由各测试枚举源限定
     * @param batch 是否使用二十项批次，否则用普通订单
     */
    private void independentFills(ProductLine product, boolean batch) {
        independentFills(product, batch, false);
    }

    /**
     * 故意选择不同 maker 用户但同一个账户 Lane，仍检查两批结算及恢复与串行一致。
     * @param product 当前场景独立产品线，实际范围由各测试枚举源限定
     */
    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void collidingMakerMasksStillAllowIndependentBatchFillsAndRecovery(ProductLine product) {
        independentFills(product, true, true);
    }

    /**
     * 按参数选独立或同 Lane maker 并准备两簿流动性，阻塞时确认两个请求共占窗口；放行后核对双边成交、两组完整提交推送、串行业务哈希及恢复。
     * @param product 当前场景独立产品线，实际范围由各测试枚举源限定
     * @param batch 是否使用二十项批次，否则用普通订单
     * @param collidingMakers 是否让两个不同 maker 用户故意落在同一账户 Lane
     */
    private void independentFills(ProductLine product, boolean batch, boolean collidingMakers) {
        try (/* 接收交错或并发在途输入的测试 Fixture，退出资源作用域时关闭 */ Fixture live = new Fixture(product); /* 接收相同原消息但逐条等完成的串行对照 Fixture */ Fixture serial = new Fixture(product)) {
            // 初始化原消息列表，复制给串行或 Follower Fixture
            var setup = live.setup(); serial.applyAll(setup);
            // 当前用于选择账户 Lane 的拓扑
            LaneTopology topology = live.service.state().runtimeState().topology();
            assertThat(topology.accountLaneCount())
                    .as("independent buyers and makers require enough distinct Account Lanes")
                    .isGreaterThanOrEqualTo(collidingMakers ? 3 : 4);
            // 两个主动买方已经占用的账户 Lane 位图
            long buyers = topology.accountLaneMask(11) | topology.accountLaneMask(disjointUser(11));
            // 第一组流动性提供用户编号
            long makerA = 77;
            while ((topology.accountLaneMask(makerA) & buyers) != 0) makerA++;
            // 买方和第一 maker 已占用的账户位图
            long occupied = buyers | topology.accountLaneMask(makerA);
            // 第二组 maker 用户，可按参数与第一 maker 同 Lane 或避开其位图
            long makerB = makerA + 1;
            if (collidingMakers) {
                while (topology.accountLaneMask(makerB) != topology.accountLaneMask(makerA)) makerB++;
            } else {
                while ((topology.accountLaneMask(makerB) & occupied) != 0) makerB++;
            }
            // 该产品准入或流动性准备使用的资产名称
            String asset = product == ProductLine.SPOT || ContractType.valueOf(product.contractTypeCode()).isInverse()
                    ? "BTC" : "USDT";
            // 本场景使用的第二个品种名称，是否不同分片由选择方法决定
            String secondSymbol = disjointSymbol("1");
            // 为当前成交场景预先准备的做市加资及挂单消息
            var liquidity = List.of(
                    live.message(CoreMessageType.ADJUST_BALANCE, makerA,
                            TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand(asset, 20_000))),
                    live.message(CoreMessageType.ADJUST_BALANCE, makerB,
                            TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand(asset, 20_000))),
                    live.place(makerA, "1", 201, batch ? 80 : 100, batch ? 40 : 4, CoreOrderSide.SELL),
                    live.place(makerB, secondSymbol, 202, batch ? 80 : 100, batch ? 40 : 4, CoreOrderSide.SELL));
            live.applyAll(liquidity); serial.applyAll(liquidity);
            live.responses.clear();
            // 只在本测试收集实时帧的有界输出队列
            var outbox = new com.surprising.aeron.client.RealtimeOutbox(1024, 1_048_576);
            CoreFaults.attachRealtime(live.service, outbox);
            // 当前先登记的原命令消息
            var first = batch ? live.placeBatch(11, "1", 1000)
                    : live.place(11, "1", 301, 100, 2, CoreOrderSide.BUY);
            // 当前后登记的独立原命令消息
            var second = batch ? live.placeBatch(disjointUser(11), secondSymbol, 2000)
                    : live.place(disjointUser(11), secondSymbol, disjointOrder(301), 100, 2, CoreOrderSide.BUY);
            // 确认 Matcher 已执行到阻塞点的门闩或 Future
            var entered = new java.util.concurrent.CompletableFuture<Void>();
            // 阻塞工作线程后用于放行的门闩或 Future，清理路径负责释放
            var release = new java.util.concurrent.CompletableFuture<Void>();
            // 在 Matcher 提交栅栏执行的测试任务 Future，放行后检查其结果
            var gate = live.service.state().matcherPipeline().readAtSubmissionFence(
                    live.service.state().matchingAdapter().matcherShardId("1"), () -> {
                        entered.complete(null);
                        release.orTimeout(5, TimeUnit.SECONDS).join();
                        return true;
                    });
            try {
                entered.orTimeout(5, TimeUnit.SECONDS).join();
                live.send(first); live.send(second);
                assertThat(live.service.commandWindowSize()).isEqualTo(2);
            } finally { release.complete(null); }
            gate.join();
            live.tick(); serial.applyAll(List.of(first, second));
            assertThat(live.responses).hasSize(2).allSatisfy(/* 当前待检查状态或结果码的 Core 响应 */ r -> assertThat(r.status()).isEqualTo(ResponseStatus.APPLIED));
            // COMMIT_BEGIN 帧数量，两个原命令各应有一组
            // COMMIT_END 帧数量，应与各命令开始边界配对
            // 已收集公开成交帧数量
            // 已收集双边执行帧数量
            int publicTrades = 0, executions = 0, begin = 0, end = 0;
            // 当前从实时队列取出的完整帧字节
            byte[] frameBytes;
            while ((frameBytes = outbox.poll()) != null) {
                // 解码后的实时帧，按类别检查成交或快照边界
                var frame = RealtimeFrameCodec.decode(frameBytes);
                switch (frame.kind()) {
                    case TRADE -> publicTrades++;
                    case EXECUTION -> executions++;
                    case COMMIT_BEGIN -> begin++;
                    case COMMIT_END -> end++;
                    default -> { }
                }
            }
            assertThat(publicTrades).isEqualTo(batch ? 40 : 2);
            assertThat(executions).isEqualTo(batch ? 80 : 4);
            // 持续Owner按命令建立确定性提交边界，两条命令各有一组完整推送。
            assertThat(begin).isEqualTo(2); assertThat(end).isEqualTo(2);
            assertThat(live.hash()).isEqualTo(serial.hash());
            assertThat(live.service.state().tradingState().order(201).executedQuantitySteps()).isEqualTo(batch ? 20 : 2);
            assertThat(live.service.state().tradingState().order(202).executedQuantitySteps()).isEqualTo(batch ? 20 : 2);
            try (/* 从当前快照恢复出的独立 Core，资源结束时关闭 */ TradingCoreRuntime restored = TradingCoreRuntime.fromSnapshot(product, live.service.captureSnapshot(100))) {
                assertThat(restored.tradingState().businessStateHash()).isEqualTo(live.hash());
            }
        }
    }

    /**
     * 普通及单项批次改单分别走成功、只挂单拒绝；放行 Matcher 后不推进 Owner，账户事件仍须完成，最终内层状态、旧单退休及恢复与串行一致。
     * @param product 当前场景独立产品线，实际范围由各测试枚举源限定
     */
    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void amendSettlesAndHandlesRejectedReplacementWithoutOwnerContinuation(ProductLine product) throws Exception {
        for (boolean /* 本轮是否使用单项改单批次，否则使用普通改单 */ batch : new boolean[] {false, true})
        for (boolean /* 本轮是否把新单改到会成交的只挂单条件 */ rejected : new boolean[] {false, true}) {
            try (/* 接收交错或并发在途输入的测试 Fixture，退出资源作用域时关闭 */ Fixture live = new Fixture(product); /* 接收相同原消息但逐条等完成的串行对照 Fixture */ Fixture serial = new Fixture(product)) {
                serial.applyAll(live.setup());
                // 为后续改单或撤单准备的原挂单消息
                var place = live.place(11, "1", 101, 80, 1, CoreOrderSide.BUY);
                live.apply(place); serial.apply(place);
                if (rejected) {
                    if (product == ProductLine.SPOT) {
                        // 为本轮测试账户或做市账户加资的原消息
                        var funds = live.message(CoreMessageType.ADJUST_BALANCE, disjointUser(11),
                                TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("BTC", 1)));
                        live.apply(funds); serial.apply(funds);
                    }
                    // 会让只挂单新替换单被拒的对手卖单消息
                    var maker = live.place(disjointUser(11), "1", 201, 100, 1, CoreOrderSide.SELL);
                    live.apply(maker); serial.apply(maker);
                    assertThat(live.service.state().tradingState().order(201)).isNotNull();
                }
                // 当前 Fixture 内的实际 Core 运行时
                var state = live.service.state();
                // 确认 Matcher 已执行到阻塞点的门闩或 Future
                var entered = new CountDownLatch(1);
                // 阻塞工作线程后用于放行的门闩或 Future，清理路径负责释放
                var release = new CountDownLatch(1);
                // 在 Matcher 提交栅栏执行的测试任务 Future，放行后检查其结果
                var gate = state.matcherPipeline().readAtSubmissionFence(0, () -> {
                    entered.countDown();
                    try {
                        if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("matcher gate timeout");
                    } catch (/* 测试门闩等待被中断的异常，当前分支恢复中断标记后失败 */ InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(exception);
                    }
                    return 1;
                });
                // 本轮改单的原输入，接受或只挂单拒绝条件由场景决定
                var replacement = new AmendOrderCommand(101, 102, "direct-amend", rejected ? 100L : 90L, 1L,
                        rejected ? CoreTimeInForce.GTX : CoreTimeInForce.GTC, rejected);
                // 普通或单项批次形式的原改单消息
                var amend = batch
                        ? live.message(CoreMessageType.AMEND_ORDER_BATCH, 11,
                                TradingOrderBatchCodec.encodeAmendOrderBatch(new AmendOrderBatchCommand(List.of(replacement))))
                        : live.message(CoreMessageType.AMEND_ORDER, 11, TradingCommandCodec.encodeAmendOrder(replacement));
                try {
                    assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
                    live.send(amend);
                    // 仍在生命周期中的原命令槽
                    var pending = state.pendingMatching().get(state.matchingSequence(amend.header().commandId()));
                    assertThat(pending).isNotNull();
                    // 等待当前单项直达事件已经准备的五秒截止点
                    long prepDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                    while ((batch ? (pending.orderBatch == null ? null : pending.orderBatch.itemSettlementEvent) : pending.settlementEvent()) == null
                            && System.nanoTime() < prepDeadline) {
                        live.service.pollCommands();
                        Thread.onSpinWait();
                    }
                    // 普通、批量或当前子项持有的直达结算事件
                    var event = batch ? pending.orderBatch.itemSettlementEvent : pending.settlementEvent();
                    assertThat(event).isNotNull();
                    assertThat(event.dispatched()).isTrue();
                    assertThat(event.ready()).isFalse();
                    release.countDown();
                    // 当前有界等待的 nanoTime 截止点
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                    while (!event.complete() && System.nanoTime() < deadline) Thread.onSpinWait();
                    assertThat(event.complete()).as("Lane completes without an Owner turn").isTrue();
                    assertThat(state.pendingMatching().get(pending.sequence())).isSameAs(pending);
                } finally { release.countDown(); }
                assertThat(gate.join()).isOne();
                live.tick(); serial.apply(amend);
                // 当前完成回调或响应列表中的 Core 结果
                var response = live.responses.getLast();
                // 普通响应状态或批次内层单项状态，按当前场景读取
                var status = batch ? TradingOrderBatchCodec.decodeResult(response.data()).items().getFirst().status()
                        : response.commandStatus();
                assertThat(status).as("batch=%s rejected=%s", batch, rejected)
                        .isEqualTo(rejected ? ResponseStatus.REJECTED : ResponseStatus.APPLIED);
                assertThat(live.hash()).isEqualTo(serial.hash());
                assertThat(state.tradingState().order(101)).isNull();
                if (rejected) assertThat(state.tradingState().order(102)).isNull();
                try (/* 从当前快照恢复出的独立 Core，资源结束时关闭 */ var restored = TradingCoreRuntime.fromSnapshot(product, live.service.captureSnapshot(100))) {
                    assertThat(restored.tradingState().businessStateHash()).isEqualTo(live.hash());
                }
            }
        }
    }

    /**
     * 放行二十项撤单后不调用 Owner，账户事件自行完成且 Lane 已编码整批响应；命令槽仍保留到 Owner 退休，最后与串行一致。
     * @param product 当前场景独立产品线，实际范围由各测试枚举源限定
     */
    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void cancelBatchSettlesOnLaneWithoutAnOwnerTurnAfterMatcherFinishes(ProductLine product) throws Exception {
        try (/* 接收交错或并发在途输入的测试 Fixture，退出资源作用域时关闭 */ Fixture live = new Fixture(product); /* 接收相同原消息但逐条等完成的串行对照 Fixture */ Fixture serial = new Fixture(product)) {
            serial.applyAll(live.setup());
            // 为后续改单或撤单准备的原挂单消息
            var place = live.placeBatch(11, "1", 101);
            live.apply(place); serial.apply(place);
            // 当前 Fixture 内的实际 Core 运行时
            var state = live.service.state();
            // 确认 Matcher 已执行到阻塞点的门闩或 Future
            var entered = new CountDownLatch(1);
            // 阻塞工作线程后用于放行的门闩或 Future，清理路径负责释放
            var release = new CountDownLatch(1);
            // 在 Matcher 提交栅栏执行的测试任务 Future，放行后检查其结果
            var gate = state.matcherPipeline().readAtSubmissionFence(0, () -> {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("matcher gate timeout");
                } catch (/* 测试门闩等待被中断的异常，当前分支恢复中断标记后失败 */ InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
                return 1;
            });
            // 当前要执行的普通或批量撤单消息
            var cancel = live.cancelBatch(11, 101);
            try {
                assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
                live.send(cancel);
                // 仍在生命周期中的原命令槽
                var pending = state.pendingMatching().get(state.matchingSequence(cancel.header().commandId()));
                assertThat(pending).isNotNull();
                // 普通、批量或当前子项持有的直达结算事件
                var event = pending.orderBatch.itemSettlementEvent;
                assertThat(event).isNotNull();
                assertThat(event.dispatched()).isTrue();
                assertThat(event.ready()).isFalse();
                release.countDown();
                // 当前有界等待的 nanoTime 截止点
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                // 此处不轮询 Owner，Matcher 必须自行唤醒账户并完成结算。
                while (!event.complete() && System.nanoTime() < deadline) Thread.onSpinWait();
                assertThat(event.complete()).isTrue();
                assertThat(pending.orderBatch.preparedResponse)
                        .as("Lane encodes the final batch result without an Owner turn").isNotNull();
                assertThat(TradingOrderBatchCodec.decodeResult(pending.orderBatch.preparedResponse,
                        pending.orderBatch.preparedResponseLength).items())
                        .allSatisfy(/* 当前批量响应的一项，读取其状态、订单或明细 */ item -> assertThat(item.status()).isEqualTo(ResponseStatus.APPLIED));
                assertThat(state.pendingMatching().get(pending.sequence())).isSameAs(pending);
            } finally { release.countDown(); }
            assertThat(gate.join()).isOne();
            live.tick(); serial.apply(cancel);
            assertThat(live.hash()).isEqualTo(serial.hash());
            assertThat(live.service.state().tradingState().order(101)).isNull();
        }
    }

    /**
     * 放行普通撤单后只等账户事件，确认无需 Owner 轮询就完成，原槽仍在；再推进终态并与串行状态比较。
     * @param product 当前场景独立产品线，实际范围由各测试枚举源限定
     */
    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void cancelSettlesOnLaneWithoutAnOwnerTurnAfterMatcherFinishes(ProductLine product) throws Exception {
        try (/* 接收交错或并发在途输入的测试 Fixture，退出资源作用域时关闭 */ Fixture live = new Fixture(product); /* 接收相同原消息但逐条等完成的串行对照 Fixture */ Fixture serial = new Fixture(product)) {
            serial.applyAll(live.setup());
            // 为后续改单或撤单准备的原挂单消息
            var place = live.place(11, "1", 101, 80, 1, CoreOrderSide.BUY);
            live.apply(place); serial.apply(place);
            // 当前 Fixture 内的实际 Core 运行时
            var state = live.service.state();
            // 确认 Matcher 已执行到阻塞点的门闩或 Future
            var entered = new CountDownLatch(1);
            // 阻塞工作线程后用于放行的门闩或 Future，清理路径负责释放
            var release = new CountDownLatch(1);
            // 在 Matcher 提交栅栏执行的测试任务 Future，放行后检查其结果
            var gate = state.matcherPipeline().readAtSubmissionFence(0, () -> {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("matcher gate timeout");
                } catch (/* 测试门闩等待被中断的异常，当前分支恢复中断标记后失败 */ InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
                return 1;
            });
            // 当前要执行的普通或批量撤单消息
            var cancel = live.cancel(11, 101);
            try {
                assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
                live.send(cancel);
                // 仍在生命周期中的原命令槽
                var pending = state.pendingMatching().get(state.matchingSequence(cancel.header().commandId()));
                assertThat(pending).isNotNull();
                // 普通、批量或当前子项持有的直达结算事件
                var event = pending.settlementEvent();
                assertThat(event).isNotNull();
                assertThat(event.dispatched()).isTrue();
                assertThat(event.ready()).isFalse();
                release.countDown();
                // 当前有界等待的 nanoTime 截止点
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                // 此处不轮询 Owner，Matcher 必须自行唤醒账户并完成结算。
                while (!event.complete() && System.nanoTime() < deadline) Thread.onSpinWait();
                assertThat(event.complete()).isTrue();
                assertThat(state.pendingMatching().get(pending.sequence())).isSameAs(pending);
            } finally { release.countDown(); }
            assertThat(gate.join()).isOne();
            live.tick(); serial.apply(cancel);
            assertThat(live.hash()).isEqualTo(serial.hash());
            assertThat(live.service.state().tradingState().order(101)).isNull();
        }
    }

    /**
     * 会话关闭后排空剩余窗口，同命令重试只返回幂等结果；再用撤单及余额命令建立后续边界，检查冻结已释放。本体没有单独发送查询消息。
     */
    @Test
    void queryAndSessionCloseDrainTheFinalPartialWindowAndRetryIsIdempotent() {
        try (/* 当前测试 Fixture 的短引用，资源退出时关闭 */ Fixture f = new Fixture(ProductLine.SPOT)) {
            f.setup();
            // 为后续改单或撤单准备的原挂单消息
            CoreMessage place = f.place(11, "1", 101, 80, 1, CoreOrderSide.BUY);
            f.send(place);
            f.service.onSessionClose(f.session, TIME + 1, io.aeron.cluster.codecs.CloseReason.CLIENT_ACTION);
            f.tick();
            assertThat(f.service.commandWindowSize()).isZero();
            // 第一次命令完成后的业务哈希，重试不能再次改变
            long hash = f.hash();
            f.send(place); f.tick();
            assertThat(f.hash()).isEqualTo(hash);
            assertThat(f.responses).hasSize(1);
            assertThat(f.responses.get(0).status()).isEqualTo(ResponseStatus.DUPLICATE);
            // 当前要执行的普通或批量撤单消息
            CoreMessage cancel = f.cancel(11, 101); f.send(cancel);
            f.apply(f.message(CoreMessageType.ADJUST_BALANCE, 11, com.surprising.aeron.protocol.TradingCommandCodec.encodeBalanceAdjustment(new com.surprising.aeron.protocol.BalanceAdjustmentCommand("USDT", 1))));
            assertThat(f.service.commandWindowSize()).isZero();
            assertThat(f.service.state().tradingState().user(11).balances().get("USDT").lockedUnits()).isZero();
        }
    }

    /**
     * 用标准品种编号把两个命令留在窗口，放行并轮询完成，再撤单；代理记录的定时器调度次数始终为零。
     */
    @Test
    void canonicalInstrumentIdsCompleteWithoutSchedulingTimers() {
        try (/* 当前测试 Fixture 的短引用，资源退出时关闭 */ Fixture f = new Fixture(ProductLine.SPOT)) {
            f.setup();
            // 用于放行 Matcher 测试栅栏的门闩或 Future
            var releaseMatcher = new java.util.concurrent.CompletableFuture<Void>();
            // 标准品种场景中先于订单执行的 Matcher 阻塞任务 Future，放行后检查完成
            var fence = f.service.state().matcherPipeline().readAtSubmissionFence(
                    f.service.state().matchingAdapter().matcherShardId("1"), () -> {
                        releaseMatcher.join();
                        return true;
                    });
            try {
                f.send(f.place(11, "1", 101, 80, 1, CoreOrderSide.BUY));
                f.send(f.place(disjointUser(11), "1", disjointOrder(101), 80, 1, CoreOrderSide.BUY));
                assertThat(f.service.commandWindowSize()).isEqualTo(2);
                assertThat(f.responses).isEmpty();
            } finally {
                releaseMatcher.complete(null);
            }
            fence.join();
            // 放行前记录的代理定时器调度次数
            int scheduled = f.scheduledTimers;
            assertThat(scheduled).isZero();
            f.tick();
            // 轮询后记录的代理定时器次数，当前场景应仍为零
            int afterTick = f.scheduledTimers;
            assertThat(afterTick).isZero();
            f.send(f.cancel(11, 101));
            assertThat(f.scheduledTimers).isEqualTo(afterTick);
            f.tick();
        }
    }

    /**
     * 按独立入口的统一配置找一个账户 Lane 位不同的后继用户。
     * @param user 请求所属用户编号
     */
    private static long disjointUser(long user) {
        // 用于测试选人的独立入口配置拓扑
        var topology = CoreConfiguration.standaloneLaneTopology();
        // 原用户在该默认拓扑中的账户 Lane 单个位图
        long lane = topology.accountLaneMask(user);
        for (/* 从原用户加一开始逐个尝试的候选用户编号 */ long next = user + 1;; next++) {
            if (topology.accountLaneMask(next) != lane) return next;
        }
    }
    /**
     * 返回原测试订单号精确加一的值，溢出直接抛错。
     * @param id 要派生或登记的测试编号
     */
    private static long disjointOrder(long id) {
        return Math.incrementExact(id);
    }
    /**
     * 返回与输入不同的固定测试品种字符串，只保证名称不同，不保证 Matcher 分片不同。
     * @param instrumentId 当前订单或测试输入的品种字符串
     */
    private static String disjointSymbol(String instrumentId) {
        return "32".equals(instrumentId) ? "33" : "32";
    }

    /**
     * 阻塞同账户前单，使后批等待依赖；重复轮询一百次仍须保留同一解码引用，放行提交后再清掉，状态与串行一致。
     * @param product 当前场景独立产品线，实际范围由各测试枚举源限定
     */
    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void blockedBatchRetainsDecodeUntilItsDependencyCommits(ProductLine product) throws Exception {
        try (/* 接收交错或并发在途输入的测试 Fixture，退出资源作用域时关闭 */ Fixture live = new Fixture(product); /* 接收相同原消息但逐条等完成的串行对照 Fixture */ Fixture serial = new Fixture(product)) {
            serial.applyAll(live.setup());
            // 确认 Matcher 已执行到阻塞点的门闩或 Future
            var entered = new CountDownLatch(1);
            // 阻塞工作线程后用于放行的门闩或 Future，清理路径负责释放
            var release = new CountDownLatch(1);
            // 当前 Fixture 内的实际 Core 运行时
            var state = live.service.state();
            // 在 Matcher 提交栅栏执行的测试任务 Future，放行后检查其结果
            var gate = state.matcherPipeline().readAtSubmissionFence(0, () -> {
                entered.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("matcher gate timeout"); }
                catch (/* 等待 Matcher 测试门闩时发生的中断异常，转为运行失败 */ InterruptedException error) { throw new IllegalStateException(error); }
                return 1;
            });
            // 当前先登记的原命令消息
            var first = live.place(11, "1", 50000, 80, 1, CoreOrderSide.BUY);
            // 当前后登记的独立原命令消息
            var second = live.placeBatch(11, "1", 51000);
            // 当前正在检查依赖或缓存解码对象的命令窗口
            var window = live.service.commandWindow();
            // 窗口保留当前解码对象的反射字段
            var decodedField = ClusterCommandWindow.class.getDeclaredField("decoded");
            decodedField.setAccessible(true);
            try {
                assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
                live.send(first); live.send(second);
                // 同一原消息共用的解码对象，依赖等待期间保留其引用
                Object decoded = decodedField.get(window);
                assertThat(decoded).isNotNull();
                for (/* 当前初始化、查找候选或重复轮询的循环下标 */ int i = 0; i < 100; i++) {
                    live.service.pollCommands();
                    assertThat(decodedField.get(window)).isSameAs(decoded);
                }
                assertThat(live.responses).isEmpty();
                assertThat(live.service.commandWindowSize()).isOne();
            } finally { release.countDown(); }
            live.tick(); serial.apply(first); serial.apply(second);
            assertThat(gate.join()).isOne();
            assertThat(decodedField.get(window)).isNull();
            assertThat(live.responses).hasSize(2);
            assertThat(live.hash()).isEqualTo(serial.hash());
        }
    }

    /**
     * 阻塞 Matcher，检查两个独立批次的直达工作已预派发且计入在途，但首序号仍在、没有终态；放行后与串行及恢复一致。当前清理门闩不额外阻塞账户。
     * @param product 当前场景独立产品线，实际范围由各测试枚举源限定
     */
    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void independentSettlementsDispatchAheadWithoutPublishingTheSuffix(ProductLine product) throws Exception {
        try (/* 接收交错或并发在途输入的测试 Fixture，退出资源作用域时关闭 */ Fixture live = new Fixture(product); /* 接收相同原消息但逐条等完成的串行对照 Fixture */ Fixture serial = new Fixture(product)) {
            serial.applyAll(live.setup());
            // 当前 Fixture 内的实际 Core 运行时
            var state = live.service.state();
            // 确认 Matcher 已到达精确阻塞点的门闩
            var matcherEntered = new CountDownLatch(1);
            // 用于放行 Matcher 测试栅栏的门闩或 Future
            var releaseMatcher = new CountDownLatch(1);
            // 清理路径保留的门闩，当前用例未安排线程等待它，不代表实际阻塞了账户
            var releaseLanes = new CountDownLatch(1);
            // 独立结算预派发场景的 Matcher 阻塞 Future
            var matcherGate = state.matcherPipeline().readAtSubmissionFence(0, () -> {
                matcherEntered.countDown();
                try { if (!releaseMatcher.await(5, TimeUnit.SECONDS)) throw new AssertionError("matcher gate timeout"); }
                catch (/* 等待 Matcher 测试门闩时发生的中断异常，转为运行失败 */ InterruptedException error) { throw new IllegalStateException(error); }
                return 1;
            });
            // 当前先登记的原命令消息
            var first = live.placeBatch(11, "1", 60000);
            // 当前后登记的独立原命令消息
            var second = live.placeBatch(disjointUser(11), disjointSymbol("1"), 61000);
            try {
                assertThat(matcherEntered.await(2, TimeUnit.SECONDS)).isTrue();
                live.send(first); live.send(second);
                // 前缀批次的完整 Core 序号
                long firstSequence = state.matchingSequence(first.header().commandId());
                // 后缀批次的完整 Core 序号
                long secondSequence = state.matchingSequence(second.header().commandId());
                // 当前有界等待的 nanoTime 截止点
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while ((!state.pendingMatching().get(firstSequence).isMatchingSubmitted()
                        || !state.pendingMatching().get(secondSequence).isMatchingSubmitted()) && System.nanoTime() < deadline)
                    live.service.pollCommands();
                assertThat(state.pendingMatching().get(secondSequence).isMatchingSubmitted()).isTrue();
                // Matcher 阻塞时，有序直达邮箱已排到账户。
                // 此后再追加的 Lane 阻塞任务会排在这些邮箱后，不能用它抢先阻塞当前交接。
                deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (state.commits.dispatchedSettlementInFlight() < 2 && System.nanoTime() < deadline)
                    live.service.pollCommands();
                assertThat(state.commits.dispatchedSettlementInFlight()).isEqualTo(2);
                assertThat(live.responses).isEmpty();
                assertThat(state.pendingMatching().firstSequence()).isEqualTo(firstSequence);
            } finally { releaseMatcher.countDown(); releaseLanes.countDown(); }
            live.tick(); serial.apply(first); serial.apply(second);
            assertThat(matcherGate.join()).isOne();
            assertThat(live.responses).hasSize(2).allSatisfy(/* 当前待检查状态或结果码的 Core 响应 */ r -> assertThat(r.commandStatus()).isEqualTo(ResponseStatus.APPLIED));
            assertThat(live.hash()).isEqualTo(serial.hash());
            try (/* 从当前快照恢复出的独立 Core，资源结束时关闭 */ var restored = TradingCoreRuntime.fromSnapshot(product, live.service.captureSnapshot(100))) {
                assertThat(restored.tradingState().businessStateHash()).isEqualTo(live.hash());
            }
        }
    }

    /**
     * 在衍生品逐页触发扫描中检查过期、OCO 续页、追踪止损和中途恢复。
     * @param product 当前场景独立产品线，实际范围由各测试枚举源限定
     */
    @ParameterizedTest
    @EnumSource(value = ProductLine.class, names = {"LINEAR_PERPETUAL", "INVERSE_PERPETUAL",
            "LINEAR_DELIVERY", "INVERSE_DELIVERY", "OPTION"})
    void internalTriggerPagesExpireTrailAndRecoverWithoutAccountHandoff(ProductLine product) throws Exception {
        verifyTriggerPages(product, true);
    }

    /**
     * 检查相同逐页触发场景，不追加中途恢复。
     * @param product 当前场景独立产品线，实际范围由各测试枚举源限定
     */
    @ParameterizedTest
    @EnumSource(value = ProductLine.class, names = {"LINEAR_PERPETUAL", "INVERSE_PERPETUAL",
            "LINEAR_DELIVERY", "INVERSE_DELIVERY", "OPTION"})
    void internalTriggerPagesWithoutSnapshot(ProductLine product) throws Exception {
        verifyTriggerPages(product, false);
    }

    /**
     * 建立过期单、跨页 OCO 和追踪单，以一用户预算推进到结束；可在 OCO 续页快照重放，核对终态、最高价、激活时间、账户交接代数及资金哈希不变。
     * @param product 当前场景独立产品线，实际范围由各测试枚举源限定
     * @param recovery 是否额外比较快照恢复状态
     */
    private void verifyTriggerPages(ProductLine product, boolean recovery) throws Exception {
        try (/* 接收交错或并发在途输入的测试 Fixture，退出资源作用域时关闭 */ Fixture live = new Fixture(product)) {
            live.setup();
            // 当前需要加资、下单或查条件单的用户编号
            // 预先提供流动性的用户编号
            long maker = 11, user = disjointUser(maker);
            live.apply(live.place(maker, "1", 501, 100, 10, CoreOrderSide.SELL));
            live.apply(live.place(user, "1", 502, 100, 10, CoreOrderSide.BUY));
            for (long /* 当前被登记或检查的条件单编号 */ id : new long[]{8990, 9001, 9002, 9003, 9004, 9005, 9010}) {
                // 当前条件单是否为追踪止损场景
                // 当前条件单是否为预期在新命令时间过期的那一条
                boolean expired = id == 8990, trailing = id == 9010;
                // 当前待登记条件单视图
                var trigger = new CoreTriggerOrderStateView(id, product, user, "scan-" + id,
                        expired || trailing ? "" : "scan-oco", "1", CoreOrderSide.SELL,
                        trailing ? CoreTriggerOrderType.TRAILING_STOP : CoreTriggerOrderType.TAKE_PROFIT,
                        CoreTriggerCondition.GREATER_OR_EQUAL, expired ? 90 : id == 9001 ? 100 : 200,
                        0, trailing ? 100_000 : 0, trailing ? 120 : 0, 0, 0,
                        CoreOrderType.LIMIT, CoreTimeInForce.IOC, 110, 1,
                        CoreMarginMode.CROSS, CorePositionSide.NET, CoreTriggerOrderStatus.PENDING,
                        0, 0, 0, "", expired ? TIME + 500 : 0, 0, 0, 0, 1, 0, 0);
                live.apply(live.message(CoreMessageType.PLACE_TRIGGER_ORDER, user,
                        CoreTriggerOrderCodec.encodePlacement(new com.surprising.aeron.protocol.PlaceTriggerOrderCommand(
                        trigger.triggerOrderId(), trigger.clientTriggerOrderId(), trigger.ocoGroupId(),
                                trigger.instrumentId(), trigger.side(), trigger.triggerType(),
                                trigger.triggerCondition(), trigger.triggerPriceTicks(), trigger.activationPriceTicks(),
                                trigger.callbackRatePpm(), trigger.orderType(), trigger.timeInForce(),
                                trigger.priceTicks(), trigger.quantitySteps(), trigger.marginMode(),
                                trigger.positionSide(), trigger.expiresAtEpochMillis(), trigger.priceSource()))));
                assertThat(live.responses.getLast().commandStatus()).isEqualTo(ResponseStatus.APPLIED);
            }
            // 当前 Core 中所属账户及风险扫描的权威运行时
            var runtime = live.service.state().runtimeState();
            // 账户所有权交接代数的反射字段
            // 触发扫描前的资金状态哈希，扫描完成后必须相同
            long funds = com.surprising.aeron.service.snapshot.state.FundsStateHash.compute(live.service.state().tradingState());
            // 当前分段开始前的账户交接代数，中途恢复快照后重新取基线
            long before = runtime.laneHandoffEpoch();
            live.sequence += 1000; // 后续命令时间越过条件单有效期及新标记价时间。
            live.apply(live.message(CoreMessageType.APPLY_MARK_PRICE, 0,
                    TradingCommandCodec.encodeApplyMarkPrice(product == ProductLine.OPTION
                            ? new ApplyMarkPriceCommand("1", 100, 100, 100, 2, TIME + 1000)
                            : new ApplyMarkPriceCommand("1", 100, 2, TIME + 1000))));
            // 是否已经实际遇到跨命令 OCO 续页，恢复分支还会在此边界重放
            boolean resumed = false;
            for (/* 单用户预算触发续扫的次数下标，最多一百轮 */ int n = 0; n < 100 && runtime.firstIncompleteRiskScan() != null; n++) {
                // 当前输入、比较或续扫的原 Core 消息
                var command = live.message(CoreMessageType.CONTINUE_RISK_SCAN, 0,
                        TradingCommandCodec.encodeContinueRiskScan(new ContinueRiskScanCommand(1)));
                // 在未结束的 OCO 续页捕获相同快照；恢复后推进同一命令，应与在线账户执行得到相同状态。
                // 当前品种风险及触发扫描游标，用它判断是否在 OCO 续页中
                var scan = runtime.riskScan(live.service.state().identities().findSymbolId("1"));
                if (recovery && !resumed && scan.triggerOcoOrderId() != 0) {
                    try (/* 从当前快照恢复出的独立 Core，资源结束时关闭 */ var restored = TradingCoreRuntime.fromSnapshot(product, live.service.captureSnapshot(501))) {
                        live.apply(command);
                        // 恢复 Core 上同一续扫命令的可空完成响应，控制工作未齐时继续轮询
                        var restoredResponse = restored.apply(command);
                        while (restoredResponse == null && restored.directCommand.active()) {
                            restoredResponse = restored.directCommand.poll();
                            if (restoredResponse == null) Thread.onSpinWait();
                        }
                        assertThat(restoredResponse.commandStatus()).isEqualTo(ResponseStatus.APPLIED);
                        assertThat(restored.tradingState().businessStateHash()).isEqualTo(live.hash());
                    }
                    resumed = true;
                    before = runtime.laneHandoffEpoch();
                } else {
                    resumed |= scan.triggerOcoOrderId() != 0;
                    live.apply(command);
                }
                assertThat(live.responses.getLast().commandStatus()).isEqualTo(ResponseStatus.APPLIED);
                assertThat(runtime.laneHandoffEpoch()).isEqualTo(before);
            }
            assertThat(resumed).as("cross-command OCO continuation exercised").isTrue();
            assertThat(runtime.firstIncompleteRiskScan()).isNull();
            assertThat(CoreTestCompletion.triggerState(live.service.state(), user, 8990).status()).isEqualTo(CoreTriggerOrderStatus.EXPIRED);
            assertThat(CoreTestCompletion.triggerState(live.service.state(), user, 9001).status()).as("%s", CoreTestCompletion.triggerState(live.service.state(), user, 9001)).isEqualTo(CoreTriggerOrderStatus.TRIGGERED);
            for (/* 当前被登记或检查的条件单编号 */ long id = 9002; id <= 9005; id++)
                assertThat(CoreTestCompletion.triggerState(live.service.state(), user, id).status()).isEqualTo(CoreTriggerOrderStatus.CANCELED);
            assertThat(CoreTestCompletion.triggerState(live.service.state(), user, 9010).status()).isEqualTo(CoreTriggerOrderStatus.TRIGGERED);
            assertThat(CoreTestCompletion.triggerState(live.service.state(), user, 9010).highestPriceTicks()).isEqualTo(120);
            assertThat(CoreTestCompletion.triggerState(live.service.state(), user, 9010).activatedAtEpochMillis()).isEqualTo(TIME + 1000);
            assertThat(com.surprising.aeron.service.snapshot.state.FundsStateHash.compute(live.service.state().tradingState()))
                    .isEqualTo(funds);
        }
    }

    /**
     * 先写新标记价，再最多提交一百条指定预算风险续扫命令，要求每条成功且最终没有未完成扫描。
     * @param live 当前接收价格与续扫命令的测试 Fixture
     * @param budget 每条风险续扫命令允许的用户工作预算
     */
    private static void scanTriggers(Fixture live, int budget) {
        // 当前是否期权产品，决定完整价格载荷形式
        boolean option = live.product == ProductLine.OPTION;
        live.apply(live.message(CoreMessageType.APPLY_MARK_PRICE, 0,
                TradingCommandCodec.encodeApplyMarkPrice(option
                        ? new ApplyMarkPriceCommand("1", 100, 100, 100, 2, TIME)
                        : new ApplyMarkPriceCommand("1", 100, 2, TIME))));
        for (/* 当前初始化、查找候选或重复轮询的循环下标 */ int i = 0; i < 100 && live.service.state().runtimeState().firstIncompleteRiskScan() != null; i++) {
            live.apply(live.message(CoreMessageType.CONTINUE_RISK_SCAN, 0,
                    TradingCommandCodec.encodeContinueRiskScan(new ContinueRiskScanCommand(budget))));
            assertThat(live.responses.getLast().commandStatus()).isEqualTo(ResponseStatus.APPLIED);
        }
        assertThat(live.service.state().runtimeState().firstIncompleteRiskScan()).isNull();
    }

    /**
     * 检查条件单按选定价格流触发，并核对恢复后的 LAST 来源。
     * @param product 当前场景独立产品线，实际范围由各测试枚举源限定
     */
    @ParameterizedTest
    @EnumSource(value = ProductLine.class, names = {"LINEAR_PERPETUAL", "INVERSE_PERPETUAL",
            "LINEAR_DELIVERY", "INVERSE_DELIVERY", "OPTION"})
    void evaluatesEachTriggerOnlyAgainstItsSelectedPriceStream(ProductLine product) {
        verifyTriggerPriceStreams(product, true);
    }

    /**
     * 检查相同价格流选择行为，不追加恢复比较。
     * @param product 当前场景独立产品线，实际范围由各测试枚举源限定
     */
    @ParameterizedTest
    @EnumSource(value = ProductLine.class, names = {"LINEAR_PERPETUAL", "INVERSE_PERPETUAL",
            "LINEAR_DELIVERY", "INVERSE_DELIVERY", "OPTION"})
    void selectedTriggerPriceStreamsWithoutSnapshot(ProductLine product) {
        verifyTriggerPriceStreams(product, false);
    }

    /**
     * 按价格来源枚举各建一单，先只让 LAST 对应条件满足，再让另外两张达到条件；检查各自终态，按参数恢复并核对保存的来源。
     * @param product 当前场景独立产品线，实际范围由各测试枚举源限定
     * @param recovery 是否额外比较快照恢复状态
     */
    private void verifyTriggerPriceStreams(ProductLine product, boolean recovery) {
        try (/* 接收交错或并发在途输入的测试 Fixture，退出资源作用域时关闭 */ Fixture live = new Fixture(product)) {
            live.setup();
            // 当前需要加资、下单或查条件单的用户编号
            // 预先提供流动性的用户编号
            long maker = 11, user = disjointUser(maker);
            live.apply(live.place(maker, "1", 701, 100, 10, CoreOrderSide.SELL));
            live.apply(live.place(user, "1", 702, 100, 10, CoreOrderSide.BUY));
            live.apply(live.place(maker, "1", 703, 110, 3, CoreOrderSide.BUY));
            // 条件价格来源枚举数组，为各来源分别构造测试条件单
            CoreTriggerPriceSource[] sources = CoreTriggerPriceSource.values();
            for (/* 当前初始化、查找候选或重复轮询的循环下标 */ int i = 0; i < sources.length; i++) {
                // 当前待登记条件单视图
                var trigger = new CoreTriggerOrderStateView(8100 + i, product,
                        user, "source-" + sources[i], "", "1", CoreOrderSide.SELL,
                        CoreTriggerOrderType.TAKE_PROFIT, CoreTriggerCondition.GREATER_OR_EQUAL,
                        110, 0, 0, 0, 0, 0, CoreOrderType.LIMIT, CoreTimeInForce.IOC,
                        110, 1, CoreMarginMode.CROSS, CorePositionSide.NET,
                        CoreTriggerOrderStatus.PENDING, 0, 0, 0, "", 0, 0, 0, 0,
                        1, 0, 0, sources[i]);
                live.apply(live.message(CoreMessageType.PLACE_TRIGGER_ORDER, user,
                        CoreTriggerOrderCodec.encodePlacement(new com.surprising.aeron.protocol.PlaceTriggerOrderCommand(
                        trigger.triggerOrderId(), trigger.clientTriggerOrderId(), trigger.ocoGroupId(),
                                trigger.instrumentId(), trigger.side(), trigger.triggerType(),
                                trigger.triggerCondition(), trigger.triggerPriceTicks(), trigger.activationPriceTicks(),
                                trigger.callbackRatePpm(), trigger.orderType(), trigger.timeInForce(),
                                trigger.priceTicks(), trigger.quantitySteps(), trigger.marginMode(),
                                trigger.positionSide(), trigger.expiresAtEpochMillis(), trigger.priceSource()))));
                assertThat(live.responses.getLast().commandStatus()).isEqualTo(ResponseStatus.APPLIED);
            }
            applyPriceAndFinishTriggerScan(live, new ApplyMarkPriceCommand("1", 100, 80,
                    product == ProductLine.OPTION ? 80 : 0,
                    2, TIME, 120));
            // 当前 Core 中所属账户及风险扫描的权威运行时
            var runtime = live.service.state().runtimeState();
            assertThat(CoreTestCompletion.triggerState(live.service.state(), user, 8100).status()).isEqualTo(CoreTriggerOrderStatus.PENDING);
            assertThat(CoreTestCompletion.triggerState(live.service.state(), user, 8101).status())
                    .as("%s", CoreTestCompletion.triggerState(live.service.state(), user, 8101).rejectReason()).isEqualTo(CoreTriggerOrderStatus.TRIGGERED);
            assertThat(CoreTestCompletion.triggerState(live.service.state(), user, 8102).status()).isEqualTo(CoreTriggerOrderStatus.PENDING);
            applyPriceAndFinishTriggerScan(live, new ApplyMarkPriceCommand("1", 120, 120,
                    product == ProductLine.OPTION ? 120 : 0,
                    3, TIME, 0));
            assertThat(CoreTestCompletion.triggerState(live.service.state(), user, 8100).status()).isEqualTo(CoreTriggerOrderStatus.TRIGGERED);
            assertThat(CoreTestCompletion.triggerState(live.service.state(), user, 8102).status()).isEqualTo(CoreTriggerOrderStatus.TRIGGERED);
            if (recovery) try (/* 从当前快照恢复出的 Core，用于检查状态及价格来源 */ var recovered = TradingCoreRuntime.fromSnapshot(product,
                    live.service.captureSnapshot(999))) {
                assertThat(recovered.tradingState().businessStateHash()).isEqualTo(live.hash());
                assertThat(recovered.tradingState().triggerOrders().get(8101L).priceSource())
                        .isEqualTo(CoreTriggerPriceSource.LAST);
            }
        }
    }

    /**
     * 应用传入价格命令，再最多用一百条单用户预算续扫推进，逐条要求成功且最终扫描结束。
     * @param live 当前接收价格与续扫命令的测试 Fixture
     * @param command 要更新各价格分量的已解码标记价命令
     */
    private static void applyPriceAndFinishTriggerScan(Fixture live, ApplyMarkPriceCommand command) {
        live.apply(live.message(CoreMessageType.APPLY_MARK_PRICE, 0,
                TradingCommandCodec.encodeApplyMarkPrice(command)));
        for (/* 当前初始化、查找候选或重复轮询的循环下标 */ int i = 0; i < 100 && live.service.state().runtimeState().firstIncompleteRiskScan() != null; i++) {
            live.apply(live.message(CoreMessageType.CONTINUE_RISK_SCAN, 0,
                    TradingCommandCodec.encodeContinueRiskScan(new ContinueRiskScanCommand(1))));
            assertThat(live.responses.getLast().commandStatus())
                    .as("%s", live.responses.getLast().resultCode()).isEqualTo(ResponseStatus.APPLIED);
        }
        assertThat(live.service.state().runtimeState().firstIncompleteRiskScan()).isNull();
    }

    /**
     * 当前产品的可关闭测试节点，真实 Owner/Core 工作配合代理 Cluster、会话及有界轮询；原消息可交给其他 Fixture 逐条执行或 Follower 回放。
     */
    private static final class Fixture implements AutoCloseable {
        /** 当前产品独立 Owner 测试支撑，持有实际 Core 推进与回调边界。 */
        final TradingOwnerTestSupport service;
        /** 此 Fixture 独立服务的产品线。 */
        final ProductLine product;
        /** 会话代理复制并解码后的响应列表，测试可在观察阶段清空。 */
        final List<CoreResponse> responses = new ArrayList<>();
        /** Leader 输入使用的会话代理，offer 时复制原响应字节。 */
        final ClientSession session;
        /** Cluster 代理返回的固定角色，也决定输入是否带会话。 */
        final Cluster.Role role;
        /** 本 Fixture 自行构造消息的来源计数，同时派生 UUID 低位、关联号及业务时间。 */
        long sequence;
        /** 代理收到 scheduleTimer 调用的次数，不是真实定时器队列。 */
        int scheduledTimers;
        /** 响应列表收到首项时执行的可空观察回调，用来截取真实提交边界。 */
        Runnable firstResponse;
        /**
         * 以 Leader 角色创建当前产品测试 Fixture。
         * @param product 当前场景独立产品线，实际范围由各测试枚举源限定
         */
        Fixture(ProductLine product) { this(product, Cluster.Role.LEADER); }
        /**
         * 创建实际 Owner 测试支撑，使用会话代理复制并解码 offer 响应，Cluster 代理提供角色、时间和定时器计数，再调用启动回调；不启动真实多成员集群。
         * @param product 当前场景独立产品线，实际范围由各测试枚举源限定
         * @param role 测试 Cluster 代理返回的角色，Leader 输入带会话，Follower 不带会话
         */
        Fixture(ProductLine product, Cluster.Role role) {
            this.product = product; this.role = role;
            service = new TradingOwnerTestSupport(product);
            session = (ClientSession) Proxy.newProxyInstance(ClientSession.class.getClassLoader(),
                    new Class<?>[]{ClientSession.class}, (/* 动态代理对象自身，当前处理函数不读取它 */ p, /* 当前代理调用的反射方法，以方法名选择模拟返回值 */ method, /* 当前动态代理收到的可空实参数组，按处理分支读取 */ args) -> {
                        if (method.getName().equals("offer")) {
                            // 当前从实时队列取出的帧字节或会话代理复制的完整消息字节
                            byte[] bytes = new byte[(int) args[2]];
                            ((org.agrona.DirectBuffer) args[0]).getBytes((int) args[1], bytes);
                            responses.add(CoreProtocol.decodeResponse(CoreMessageCodec.decode(bytes).payloadUnsafe()));
                            if (responses.size() == 1 && firstResponse != null) firstResponse.run();
                            return 1L;
                        }
                        return zero(method.getReturnType());
                    });
            // 提供角色、时间、日志位置、空闲策略及定时器计数的 Cluster 测试代理
            Cluster cluster = (Cluster) Proxy.newProxyInstance(Cluster.class.getClassLoader(), new Class<?>[]{Cluster.class},
                    (/* 动态代理对象自身，当前处理函数不读取它 */ p, /* 当前代理调用的反射方法，以方法名选择模拟返回值 */ method, /* 当前动态代理收到的可空实参数组，按处理分支读取 */ args) -> switch (method.getName()) {
                        case "role" -> role;
                        case "timeUnit" -> TimeUnit.MILLISECONDS;
                        case "time", "logPosition" -> TIME;
                        case "scheduleTimer" -> { scheduledTimers++; yield true; }
                        case "idleStrategy" -> NoOpIdleStrategy.INSTANCE;
                        default -> zero(method.getReturnType());
                    });
            service.onStart(cluster, null);
        }
        /**
         * 选择另一固定品种名，执行共同初始化并返回原消息供串行或 Follower 重放。
         */
        List<CoreMessage> setup() { return setup(disjointSymbol("1")); }
        /**
         * 为两个品种准备配置及初始价格，给两个测试用户增加结算资产，逐条执行后清响应列表；返回同一批原消息，不修改调用者的业务断言。
         * @param secondSymbol 初始化时除品种一之外的另一个品种名称
         */
        List<CoreMessage> setup(String secondSymbol) {
            // 当前产品对应的合约类型，决定资产、乘数和到期配置
            ContractType type = ContractType.valueOf(product.contractTypeCode());
            // 该产品准入或流动性准备使用的资产名称
            String asset = type.isInverse() ? "BTC" : "USDT";
            // 按日志先后保存的原消息列表
            List<CoreMessage> commands = new ArrayList<>();
            for (String /* 当前注册、路由或故障注入使用的品种标识，具体类型见声明 */ instrumentId : List.of("1", secondSymbol)) {
                commands.add(message(CoreMessageType.REGISTER_INSTRUMENT, 0,
                        TradingCommandCodec.encodeRegisterInstrument(new RegisterInstrumentCommand(
                                instrumentId, "BTC", "USDT", asset, 1, 1, type.isInverse() ? 1_000 : 1,
                                (type == ContractType.SPOT ? null : new CoreInstrumentRiskLimits(
                                        100_000, (1_000_000_000_000L / (100_000)), Long.MAX_VALUE, 0, Long.MAX_VALUE,
                                        java.util.List.of(new CoreRiskLimitBracket(Long.MAX_VALUE, (1_000_000_000_000L / (100_000)), 100_000, 50_000)))),
                                0, 0, type.isDelivery() || type.isOption() ? TIME + 100_000 : 0,
                                type.isOption() ? 0 : -1, type.isOption() ? 100 : 0), type.productLine())));
            }
            for (String /* 当前注册、路由或故障注入使用的品种标识，具体类型见声明 */ instrumentId : List.of("1", secondSymbol)) {
                commands.add(message(CoreMessageType.APPLY_MARK_PRICE, 0,
                        TradingCommandCodec.encodeApplyMarkPrice(type.isOption()
                                ? new ApplyMarkPriceCommand(instrumentId, 100, 100, 100, 1, TIME)
                                : new ApplyMarkPriceCommand(instrumentId, 100, 1, TIME))));
            }
            for (long /* 当前需要加资、下单或查条件单的用户编号 */ user : new long[]{11, disjointUser(11)})
                commands.add(message(CoreMessageType.ADJUST_BALANCE, user,
                        TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand(asset, 20_000))));
            applyAll(commands); responses.clear(); return commands;
        }
        /**
         * 增加本地来源号，用它构造唯一 UUID、来源序号、关联号及 TIME 加偏移的业务时间，来源固定 OPERATIONS 九百九十。
         * @param type 要编码的 Core 消息类别
         * @param user 请求所属用户编号
         * @param payload 已经编码的业务载荷
         */
        CoreMessage message(CoreMessageType type, long user, byte[] payload) {
            // 本条原消息新分配的来源号，同时派生 UUID、关联号和业务时间偏移
            long id = ++sequence;
            return new CoreMessage(CoreMessageHeader.command(type, new UUID(990, id), product,
                    CommandSource.OPERATIONS, 990, id, user, TIME + id, id), payload);
        }
        /**
         * 编码指定用户、品种、方向和价量的普通限价 GTC 订单，客户号由订单编号派生。
         * @param user 请求所属用户编号
         * @param instrumentId 当前订单或测试输入的品种字符串
         * @param order 本次普通订单或撤单的订单编号
         * @param price 测试订单价格刻度
         * @param qty 测试订单数量步数
         * @param side 买卖方向
         */
        CoreMessage place(long user, String instrumentId, long order, long price, long qty, CoreOrderSide side) {
            return message(CoreMessageType.PLACE_ORDER, user, TradingCommandCodec.encodePlaceOrder(
                    new PlaceOrderCommand(order, instrumentId, side, price, qty, false, CoreMarginMode.CROSS,
                            CorePositionSide.NET, CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "order-" + order)));
        }
        /**
         * 为指定用户和原订单号构造普通撤单消息。
         * @param user 请求所属用户编号
         * @param order 本次普通订单或撤单的订单编号
         */
        CoreMessage cancel(long user, long order) {
            return message(CoreMessageType.CANCEL_ORDER, user,
                    TradingCommandCodec.encodeCancelOrder(new CancelOrderCommand(order)));
        }
        /**
         * 从起始编号连续构造二十项价格八十、数量一的买单，编码为一个批量命令。
         * @param user 请求所属用户编号
         * @param instrumentId 当前订单或测试输入的品种字符串
         * @param firstId 连续二十个测试订单的起始编号
         */
        CoreMessage placeBatch(long user, String instrumentId, long firstId) {
            // 当前普通或批量测试使用的有序订单列表
            List<PlaceOrderCommand> orders = new ArrayList<>();
            for (/* 当前初始化、查找候选或重复轮询的循环下标 */ int i = 0; i < 20; i++) orders.add(new PlaceOrderCommand(firstId+i, instrumentId,
                    CoreOrderSide.BUY, 80, 1, false, CoreMarginMode.CROSS, CorePositionSide.NET,
                    CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "batch-"+(firstId+i)));
            return message(CoreMessageType.PLACE_ORDER_BATCH, user,
                    TradingOrderBatchCodec.encodePlaceOrderBatch(new PlaceOrderBatchCommand(orders)));
        }
        /**
         * 从起始编号连续构造二十项撤单，编码为一个批量命令。
         * @param user 请求所属用户编号
         * @param firstId 连续二十个测试订单的起始编号
         */
        CoreMessage cancelBatch(long user, long firstId) {
            // 当前普通或批量测试使用的有序订单列表
            List<CancelOrderCommand> orders = new ArrayList<>();
            for (/* 当前初始化、查找候选或重复轮询的循环下标 */ int i = 0; i < 20; i++) orders.add(new CancelOrderCommand(firstId+i));
            return message(CoreMessageType.CANCEL_ORDER_BATCH, user,
                    TradingOrderBatchCodec.encodeCancelOrderBatch(new CancelOrderBatchCommand(orders)));
        }
        /**
         * 编码原消息并调用会话输入回调，Leader 带会话、Follower 传空；使用测试 Header 交入原业务时间，本方法不等终态。
         * @param command 当前要发送的原 Core 消息
         */
        void send(CoreMessage command) {
            // 当前从实时队列取出的帧字节或会话代理复制的完整消息字节
            byte[] bytes = CoreMessageCodec.encode(command);
            service.onSessionMessage(role == Cluster.Role.LEADER ? session : null, command.header().submittedAtEpochMillis(),
                    new UnsafeBuffer(bytes), 0, bytes.length,
                    new Header(0, 0).buffer(new UnsafeBuffer(new byte[64])).offset(0).initialTermId(0).positionBitsToShift(16));
        }
        /**
         * 最多五秒轮询命令，每轮短暂停等，再检查调用方条件；条件仍未满足就断言失败，不注入定时器日志。
         * @param done 判断当前异步等待条件是否满足的函数
         */
        void progressUntil(java.util.function.BooleanSupplier done) {
            // 当前有界等待的 nanoTime 截止点
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!done.getAsBoolean() && System.nanoTime() < deadline) {
                service.pollCommands();
                java.util.concurrent.locks.LockSupport.parkNanos(100_000);
            }
            assertThat(done.getAsBoolean()).as("bounded asynchronous progress").isTrue();
        }
        /**
         * 至少轮询一次并最多等五秒，待命令计数清零即返回；名称虽然叫 tick，实际不调用定时器回调。
         */
        void tick() {
            // 当前有界等待的 nanoTime 截止点
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            do {
                service.pollCommands();
                if (service.pendingCommandCount() == 0) return;
                java.util.concurrent.locks.LockSupport.parkNanos(100_000);
            } while (System.nanoTime() < deadline);
            throw new AssertionError("asynchronous commands did not complete");
        }
        /**
         * 发送一条原消息后轮询到当前待命令排空，不另行断言它必须业务成功。
         * @param command 当前要发送的原 Core 消息
         */
        void apply(CoreMessage command) { send(command); tick(); }
        /**
         * 按原列表顺序逐条发送并等各次排空，构成串行对照路径。
         * @param commands 需要按原顺序逐条执行的消息列表
         */
        void applyAll(List<CoreMessage> commands) { commands.forEach(this::apply); }
        /**
         * 导出当前已可读取业务状态并计算业务哈希，供不同调度及恢复结果比较。
         */
        long hash() { return service.state().tradingState().businessStateHash(); }
        /**
         * 调用既有终止回调关闭 Owner 测试支撑。
         */
        public void close() { service.onTerminate(null); }
        /**
         * 仅为布尔、long 和 int 返回对应零值，其余返回空；供当前代理未专门处理的方法使用。
         * @param type 代理方法返回类型，只处理布尔、long 和 int 的零值
         */
        private static Object zero(Class<?> type) {
            if (type == boolean.class) return false;
            if (type == long.class) return 0L;
            if (type == int.class) return 0;
            return null;
        }
    }
}
