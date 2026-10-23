package com.surprising.aeron.service.core;

import com.surprising.aeron.exception.FatalMatchingDivergenceException;
import com.surprising.aeron.protocol.AmendOrderBatchCommand;
import com.surprising.aeron.protocol.AmendOrderCommand;
import com.surprising.aeron.protocol.BalanceAdjustmentCommand;
import com.surprising.aeron.protocol.CancelOrderBatchCommand;
import com.surprising.aeron.protocol.CancelOrderCommand;
import com.surprising.aeron.protocol.CommandSource;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageCodec;
import com.surprising.aeron.protocol.CoreMessageHeader;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreOrderType;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreProtocol;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.CoreTimeInForce;
import com.surprising.aeron.protocol.PlaceOrderBatchCommand;
import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.protocol.ProtocolException;
import com.surprising.aeron.protocol.RegisterInstrumentCommand;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.aeron.protocol.TradingOrderBatchCodec;
import com.surprising.aeron.service.matcher.pipeline.MatcherPipelineGroup;
import com.surprising.aeron.service.order.CoreTestCompletion;
import com.surprising.aeron.service.order.PipelineTestAccess;
import com.surprising.aeron.service.snapshot.CoreSnapshotLifecycle;
import com.surprising.aeron.service.snapshot.SectionedCoreSnapshotCodec;
import com.surprising.product.api.ProductLine;
import io.aeron.cluster.service.ClientSession;
import io.aeron.cluster.service.Cluster;
import io.aeron.logbuffer.Header;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.CRC32C;
import org.agrona.concurrent.NoOpIdleStrategy;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Owner 命令推进、账本等待、响应顺序、失败回滚、实时读取及快照边界测试。使用真实业务运行时和按用例启动的工作资源，但 Cluster/会话为进程内代理，许多场景由辅助方法显式 pollCommands；不能据此把底层回调都描述成同步完成或真实网络集群验收。
 */
class TradingCoreOwnerTest {
    /**
     * PIPELINED 现货/永续先阻塞 Ledger，提交两个成交单，等账户完成且无 Matcher 通知后确认未回包；仅放行账本，完成通知即可重新唤醒 Owner 并按序交两终态，随后检查数量及资金守恒。
     * @param line 本轮独立运行时的现货或 U 本位永续产品线
     */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = ProductLine.class,
            names = {"SPOT", "LINEAR_PERPETUAL"})
    @org.junit.jupiter.api.extension.ExtendWith(PipelinedExecutionExtension.class)
    void ledgerCompletionResumesOrderedHeadWithoutAnotherMatcherNotification(ProductLine line) throws Exception {
        // 本用例进程内 Owner 测试辅助，结束时按原生命周期终止
        var service = new TradingOwnerTestSupport(line);
        // 模拟会话复制出的原响应字节清单，支持工作线程可见性
        var responses = new CopyOnWriteArrayList<byte[]>();
        // 通知测试线程被阻塞的 Lane、Matcher 或账本工作已经进入的门闩
        var entered = new java.util.concurrent.CountDownLatch(1);
        // 放行阻塞工作的门闩，各退出路径确保放行
        var release = new java.util.concurrent.CountDownLatch(1);
        service.onStart(cluster(), null);
        try {
            // 按当前现货或永续产品类型构造的原合约注册参数
            var register = new RegisterInstrumentCommand("1",
                    "BTC", "USDT", "USDT", 1, 1, 1, 100_000, 50_000, 0, 0, 0, -1, 0);
            onSessionMessage(service, responses, command(line, CoreMessageType.REGISTER_INSTRUMENT, 1, 0,
                    TradingCommandCodec.encodeRegisterInstrument(register)));
            onSessionMessage(service, responses, command(line, CoreMessageType.APPLY_MARK_PRICE, 2, 0,
                    TradingCommandCodec.encodeApplyMarkPrice(
                            new com.surprising.aeron.protocol.ApplyMarkPriceCommand("1", 100, 1, 1_000))));
            for (/* 原用户 ID，或实时用户帧解码/冷状态查得的原账户视图 */ int user = 1; user <= 2; user++) {
                onSessionMessage(service, responses, command(line, CoreMessageType.ADJUST_BALANCE, 2 + user, user,
                        TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 10_000))));
            }
            if (line == ProductLine.SPOT) {
                onSessionMessage(service, responses, command(line, CoreMessageType.ADJUST_BALANCE, 5, 2,
                        TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("BTC", 10))));
            }
            onSessionMessage(service, responses, command(line, CoreMessageType.PLACE_ORDER, 6, 2,
                    TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(9000, "1", CoreOrderSide.SELL,
                            100, 10, false, CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT,
                            CoreTimeInForce.GTC, false, "ledger-maker"))));
            // 当前 Owner 辅助持有的真实业务运行时
            var core = service.state();
            // 当前真实账户与账本工作资源所在的原运行时
            var runtime = core.runtimeState;
            assertThat(core.laneTopology().accountLaneId(1))
                    .isNotEqualTo(core.laneTopology().accountLaneId(2));
            assertThat(runtime.systemLedgerStarted()).isTrue();
            assertThat(runtime.dispatchLedgerQuery(/* 真实账本回调给出的权威 Treasury，只在账本作用域读取 */ ledger -> {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Ledger release timeout");
                } catch (/* 阻塞账本等待中断，转测试失败 */ InterruptedException interrupted) { throw new AssertionError(interrupted); }
            })).isTrue();
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            // 本轮要优先结算的首条原成交请求
            var first = command(line, CoreMessageType.PLACE_ORDER, 7, 1,
                    TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(9001, "1", CoreOrderSide.BUY,
                            100, 3, false, CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT,
                            CoreTimeInForce.IOC, false, "ledger-taker-1")));
            // 本轮排在首条或控制命令之后的原成交请求
            var following = command(line, CoreMessageType.PLACE_ORDER, 8, 1,
                    TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(9002, "1", CoreOrderSide.BUY,
                            100, 3, false, CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT,
                            CoreTimeInForce.IOC, false, "ledger-taker-2")));
            // 本批原消息入队前已收到的响应数量，账本受阻时不能增加
            int before = responses.size();
            service.acceptCommittedCommand(clientSession(responses), first, 2_000, 2_000);
            service.acceptCommittedCommand(clientSession(responses), following, 2_001, 2_001);
            // 当前原命令账户/财务结算事件，分别检查发布、账户完成及最终完成
            com.surprising.aeron.service.settlement.MatcherSettlementEvent event = null;
            // 当前局部等待阶段的单调截止值，按该方法原代码独立或共用
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            do {
                service.pollCommands();
                // 原撮合环当前按序提交头
                var head = core.pendingMatching().head();
                event = head == null ? null : head.settlementEvent();
                if (event != null && event.completedLaneMask() == event.requiredLaneMask()
                        && !core.matchingProgress.hasNotifications() && !service.ownerWorkAvailable()) break;
                if (System.nanoTime() > deadline) throw new AssertionError("Owner did not await Ledger completion");
                java.util.concurrent.locks.LockSupport.parkNanos(100_000);
            } while (true);
            assertThat(event.complete()).isFalse();
            assertThat(responses).hasSize(before);
            release.countDown();
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!event.complete()) {
                if (System.nanoTime() > deadline) throw new AssertionError("Ledger did not finish settlement");
                java.util.concurrent.locks.LockSupport.parkNanos(100_000);
            }
            // 此时只剩账本发布完成及唤醒提示，Matcher/账户通知已被收完。
            assertThat(core.matchingProgress.hasNotifications()).isFalse();
            assertThat(service.ownerCompletionAvailable()).isTrue();
            assertThat(service.ownerWorkAvailable()).isTrue();
            service.pollCommands();
            assertThat(responses.size()).isGreaterThan(before);
            finishCommands(service);
            assertThat(responses).hasSize(before + 2);
            for (/* 遍历原响应或请求清单的当前下标 */ int index = before; index < responses.size(); index++) {
                assertThat(CoreProtocol.decodeResponse(CoreMessageCodec.decode(responses.get(index)).payload())
                        .commandStatus()).isEqualTo(ResponseStatus.APPLIED);
            }
            assertThat(service.pendingCommandCount()).isZero();
            // 相关原命令完成后取得的已提交冷状态，供资金与订单断言
            var state = core.tradingState();
            assertThat(state.order(9000).remainingQuantitySteps()).isEqualTo(4);
            assertThat(state.order(9001)).isNull();
            assertThat(state.order(9002)).isNull();
            assertThat(core.terminalTradeCount).isEqualTo(2);
            // 已提交用户 USDT 总额，和原财库费用一起核对守恒
            long funds = state.users().values().stream().mapToLong(/* 原用户 ID，或实时用户帧解码/冷状态查得的原账户视图 */ user -> user.totalUnits("USDT")).sum();
            assertThat(funds + state.treasuryState().feeBalances().getOrDefault("USDT", 0L)).isEqualTo(20_000);
            if (line == ProductLine.SPOT) {
                assertThat(state.user(1).totalUnits("BTC")).isEqualTo(6);
                assertThat(state.user(2).totalUnits("BTC")).isEqualTo(4);
            } else {
                assertThat(state.user(1).positions().get("1").signedQuantitySteps()).isEqualTo(6);
                assertThat(state.user(2).positions().get("1").signedQuantitySteps()).isEqualTo(-6);
            }
        } finally { release.countDown(); service.onTerminate(null); }
    }

    /**
     * 按指定产品线、运营来源和固定业务时间构造可复现 UUID 的原消息，供账本顺序用例使用。
     * @param line 本轮独立运行时的现货或 U 本位永续产品线
     * @param type 本条原协议消息类型
     * @param sequence 本条原来源顺序号，亦参与 UUID 或关联号
     * @param user 本条原请求所属用户
     * @param payload 本条原消息已编码载荷
     */
    private static CoreMessage command(ProductLine line, CoreMessageType type, long sequence,
                                       long user, byte[] payload) {
        return new CoreMessage(CoreMessageHeader.command(type, new UUID(995, sequence), line,
                CommandSource.OPERATIONS, 995, sequence, user, 1_000, sequence), payload);
    }

    /**
     * PIPELINED 下两个独立标的普通单或双项批次在账本受阻时先完成账户；停止 Owner 轮询再放行账本，用队尾查询证明两命令资金已应用但首响应尚未退休，之后按原顺序收终态及核对资金。
     * @param line 本轮独立运行时的现货或 U 本位永续产品线
     * @param batchCommands 是否把每个原成交命令构成两项批次
     */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("ledgerPipelineCases")
    @org.junit.jupiter.api.extension.ExtendWith(PipelinedExecutionExtension.class)
    void ledgerSettlesIndependentCommandsBeforeOwnerRetiresTheFirstResponse(
            ProductLine line, boolean batchCommands) throws Exception {
        // 本用例进程内 Owner 测试辅助，结束时按原生命周期终止
        var service = new TradingOwnerTestSupport(line);
        // 模拟会话复制出的原响应字节清单，支持工作线程可见性
        var responses = new CopyOnWriteArrayList<byte[]>();
        // 通知测试线程被阻塞的 Lane、Matcher 或账本工作已经进入的门闩
        var entered = new java.util.concurrent.CountDownLatch(1);
        // 放行阻塞工作的门闩，各退出路径确保放行
        var release = new java.util.concurrent.CountDownLatch(1);
        // 账本观察原费用完成后通知测试线程的门闩
        var inspected = new java.util.concurrent.CountDownLatch(1);
        // 仅在真实账本线程读取并发布给测试的费用计数
        var observedFees = new AtomicLong(-1);
        service.onStart(cluster(), null);
        try {
            // 本用例后续可用原来源序号，发送消息时递增
            long sequence = prepareLedgerPipelineMarkets(service, responses, line);
            // 当前 Owner 辅助持有的真实业务运行时
            var core = service.state();
            // 当前真实账户与账本工作资源所在的原运行时
            var runtime = core.runtimeState;
            // 原身份表中 USDT 内部资产编号
            int assetId = core.identities.assetId("USDT");
            // 本轮每原命令子项数量，普通一、批次二
            int itemsPerCommand = batchCommands ? 2 : 1;
            // 本轮原请求集合
            var requests = new java.util.ArrayList<CoreMessage>();
            for (int /* 当前主动买入用户一或三 */ taker : new int[]{1, 3}) {
                // 当前原批次需要的下单意图短期清单
                var orders = new java.util.ArrayList<PlaceOrderCommand>();
                for (/* 当前原批次子项的下标 */ int item = 0; item < itemsPerCommand; item++) {
                    orders.add(new PlaceOrderCommand(9300 + taker * 10 + item, Integer.toString((taker + 1) / 2),
                            CoreOrderSide.BUY, 100, 1, false, CoreMarginMode.CROSS, CorePositionSide.NET,
                            CoreOrderType.LIMIT, CoreTimeInForce.IOC, false, "pipeline-taker-" + taker + "-" + item));
                }
                requests.add(command(line, batchCommands ? CoreMessageType.PLACE_ORDER_BATCH : CoreMessageType.PLACE_ORDER,
                        sequence++, taker, batchCommands
                                ? TradingOrderBatchCodec.encodePlaceOrderBatch(new PlaceOrderBatchCommand(orders))
                                : TradingCommandCodec.encodePlaceOrder(orders.getFirst())));
            }
            assertThat(runtime.dispatchLedgerQuery(/* 真实账本回调给出的权威 Treasury，只在账本作用域读取 */ ledger -> {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Pipeline Ledger release timeout");
                } catch (/* 阻塞账本等待中断，转测试失败 */ InterruptedException interrupted) { throw new AssertionError(interrupted); }
            })).isTrue();
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            // 本批原消息入队前已收到的响应数量，账本受阻时不能增加
            int before = responses.size();
            try (/* 只供测试发现 Owner 越界读取账户正文的原保护器 */ var guard = new OwnerAccountReadGuard(runtime)) {
                for (CoreMessage /* 本轮逐项检查或入队的原协议请求 */ request : requests)
                    service.acceptCommittedCommand(clientSession(responses), request, 2_000, 2_000);
                // 当前局部等待阶段的单调截止值，按该方法原代码独立或共用
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                do {
                    service.pollCommands();
                    // 所列原命令是否都已发布并由实际账户 Lane 应用完成，不包含 Ledger 条件
                    boolean accountsComplete = true;
                    for (CoreMessage /* 本轮逐项检查或入队的原协议请求 */ request : requests) {
                        // 按原 UUID 找到的在途撮合槽
                        var pending = core.pendingMatching().findByCommandId(request.header().commandId());
                        // 当前原命令账户/财务结算事件，分别检查发布、账户完成及最终完成
                        var event = pending == null ? null : pending.orderBatch() == null
                                ? pending.settlementEvent() : pending.orderBatch().settlementEvent();
                        accountsComplete &= event != null && event.ready() && event.completedLaneMask() != 0
                                && (event.completedLaneMask() & event.requiredLaneMask()) == event.requiredLaneMask();
                    }
                    if (accountsComplete && !core.matchingProgress.hasNotifications() && !service.ownerWorkAvailable()) break;
                    if (System.nanoTime() > deadline) throw new AssertionError("Independent Lane facts were not ready");
                    java.util.concurrent.locks.LockSupport.parkNanos(100_000);
                } while (true);
                assertThat(core.pendingMatching().size()).isEqualTo(2);
                assertThat(responses).hasSize(before);
                // 查询排在整个资金前缀之后；放行账本前停止 Owner 轮询，
                // 检查首响应尚未交出时，两条命令的资金仍都能完成。
                assertThat(runtime.dispatchLedgerQuery(/* 真实账本回调给出的权威 Treasury，只在账本作用域读取 */ ledger -> {
                    observedFees.set(ledger.fee(assetId));
                    inspected.countDown();
                })).isTrue();
                release.countDown();
                assertThat(inspected.await(2, TimeUnit.SECONDS)).isTrue();
                assertThat(observedFees).hasValue(4L * itemsPerCommand);
                assertThat(responses).hasSize(before);
                finishCommands(service);
            }
            assertThat(responses).hasSize(before + 2);
            for (/* 遍历原响应或请求清单的当前下标 */ int index = 0; index < requests.size(); index++) {
                // 本轮原业务响应或已编码响应消息，用来核对身份与终态
                var response = CoreMessageCodec.decode(responses.get(before + index));
                assertThat(response.header().commandId()).isEqualTo(requests.get(index).header().commandId());
                // 本轮原业务、Matcher 控制或撮合结果，未就绪可为空
                var result = CoreProtocol.decodeResponse(response.payload());
                assertThat(result.commandStatus()).isEqualTo(ResponseStatus.APPLIED);
                if (batchCommands) assertThat(TradingOrderBatchCodec.firstNonAppliedItem(result, itemsPerCommand)).isEqualTo(-1);
            }
            assertThat(service.pendingCommandCount()).isZero();
            // 相关原命令完成后取得的已提交冷状态，供资金与订单断言
            var state = core.tradingState();
            // 账本原子观测值或冷状态读出的原 USDT 费用总额
            long fees = state.treasuryState().feeBalances().getOrDefault("USDT", 0L);
            // 已提交用户 USDT 总额，和原财库费用一起核对守恒
            long funds = state.users().values().stream().mapToLong(/* 原用户 ID，或实时用户帧解码/冷状态查得的原账户视图 */ user -> user.totalUnits("USDT")).sum();
            assertThat(fees).isEqualTo(4L * itemsPerCommand);
            assertThat(funds + fees).isEqualTo(40_000);
            assertThat(core.terminalTradeCount).isEqualTo(2L * itemsPerCommand);
            for (int /* 当前主动买入用户一或三 */ taker : new int[]{1, 3}) {
                assertThat(state.user(taker).reservations()).isEmpty();
                for (/* 当前原批次子项的下标 */ int item = 0; item < itemsPerCommand; item++) assertThat(state.order(9300 + taker * 10 + item)).isNull();
                assertThat(state.order(9200 + taker + 1).remainingQuantitySteps()).isEqualTo(10 - itemsPerCommand);
                if (line == ProductLine.SPOT) assertThat(state.user(taker).totalUnits("BTC")).isEqualTo(itemsPerCommand);
                else assertThat(state.user(taker).positions().get(Integer.toString((taker + 1) / 2)).signedQuantitySteps())
                        .isEqualTo(itemsPerCommand);
            }
        } finally { release.countDown(); service.onTerminate(null); }
    }

    /**
     * 两成交之间插保险调整控制命令，阻塞账本后确认只能先准备首笔资金，后单不越过控制前缀；放行后账本查询仍只见首笔费用和原保险零，最后推进三响应及准确注资结果。
     * @param line 本轮独立运行时的现货或 U 本位永续产品线
     */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = ProductLine.class,
            names = {"SPOT", "LINEAR_PERPETUAL"})
    void controlCommandFencesLedgerPrefillAndTheFollowingTrade(ProductLine line) throws Exception {
        // 本用例进程内 Owner 测试辅助，结束时按原生命周期终止
        var service = new TradingOwnerTestSupport(line);
        // 模拟会话复制出的原响应字节清单，支持工作线程可见性
        var responses = new CopyOnWriteArrayList<byte[]>();
        // 通知测试线程被阻塞的 Lane、Matcher 或账本工作已经进入的门闩
        var entered = new java.util.concurrent.CountDownLatch(1);
        // 放行阻塞工作的门闩，各退出路径确保放行
        var release = new java.util.concurrent.CountDownLatch(1);
        // 账本观察原费用完成后通知测试线程的门闩
        var inspected = new java.util.concurrent.CountDownLatch(1);
        // 仅在真实账本线程读取并发布给测试的费用计数
        var observedFees = new AtomicLong(-1);
        // 只在账本线程读取并发布的原保险余额
        var observedInsurance = new AtomicLong(-1);
        service.onStart(cluster(), null);
        try {
            // 本用例后续可用原来源序号，发送消息时递增
            long sequence = prepareLedgerPipelineMarkets(service, responses, line);
            // 当前 Owner 辅助持有的真实业务运行时
            var core = service.state();
            // 当前真实账户与账本工作资源所在的原运行时
            var runtime = core.runtimeState;
            // 原身份表中 USDT 内部资产编号
            int assetId = core.identities.assetId("USDT");
            // 本轮要优先结算的首条原成交请求
            var first = pipelineTrade(line, sequence++, 1);
            // 夹在两笔成交之间的原保险加二十五命令
            var control = command(line, CoreMessageType.ADJUST_INSURANCE_FUND, sequence++, 0,
                    TradingCommandCodec.encodeAdjustInsuranceFund(
                            new com.surprising.aeron.protocol.AdjustInsuranceFundCommand("USDT", 25)));
            // 本轮排在首条或控制命令之后的原成交请求
            var following = pipelineTrade(line, sequence, 3);
            assertThat(runtime.dispatchLedgerQuery(/* 真实账本回调给出的权威 Treasury，只在账本作用域读取 */ ledger -> {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Control fence release timeout");
                } catch (/* 阻塞账本等待中断，转测试失败 */ InterruptedException interrupted) { throw new AssertionError(interrupted); }
            })).isTrue();
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            // 本批原消息入队前已收到的响应数量，账本受阻时不能增加
            int before = responses.size();
            try (/* 只供测试发现 Owner 越界读取账户正文的原保护器 */ var guard = new OwnerAccountReadGuard(runtime)) {
                for (var /* 本轮逐项检查或入队的原协议请求 */ request : List.of(first, control, following))
                    service.acceptCommittedCommand(clientSession(responses), request, 2_000, 2_000);
                awaitPipelineAccounts(service, List.of(first));
                assertThat(core.pendingMatching().size()).isEqualTo(1);
                assertThat(core.pendingMatching().findByCommandId(following.header().commandId())).isNull();
                assertThat(responses).hasSize(before);
                assertThat(runtime.dispatchLedgerQuery(/* 真实账本回调给出的权威 Treasury，只在账本作用域读取 */ ledger -> {
                    observedFees.set(ledger.fee(assetId));
                    observedInsurance.set(ledger.insurance(assetId));
                    inspected.countDown();
                })).isTrue();
                release.countDown();
                assertThat(inspected.await(2, TimeUnit.SECONDS)).isTrue();
                assertThat(observedFees).hasValue(2);
                assertThat(observedInsurance).hasValue(0);
                assertThat(responses).hasSize(before);
                finishCommands(service);
            }
            assertThat(responses).hasSize(before + 3);
            // 本轮原请求集合
            var requests = List.of(first, control, following);
            for (/* 遍历原响应或请求清单的当前下标 */ int index = 0; index < requests.size(); index++) {
                // 本轮原业务响应或已编码响应消息，用来核对身份与终态
                var response = CoreMessageCodec.decode(responses.get(before + index));
                assertThat(response.header().commandId()).isEqualTo(requests.get(index).header().commandId());
                assertThat(CoreProtocol.decodeResponse(response.payload()).commandStatus()).isEqualTo(ResponseStatus.APPLIED);
            }
            // 相关原命令完成后取得的已提交冷状态，供资金与订单断言
            var state = core.tradingState();
            assertThat(state.treasuryState().feeBalances()).containsEntry("USDT", 4L);
            assertThat(state.treasuryState().insuranceBalances()).containsEntry("USDT", 25L);
            assertThat(state.users().values().stream().mapToLong(/* 原用户 ID，或实时用户帧解码/冷状态查得的原账户视图 */ user -> user.totalUnits("USDT")).sum() + 4 + 25)
                    .isEqualTo(40_025);
            assertThat(service.pendingCommandCount()).isZero();
        } finally { release.countDown(); service.onTerminate(null); }
    }

    /**
     * 阻塞并填满账本查询邮箱，两个账户结果就绪后检查资金游标仍指首单且存在可重试本地工作；即使取消独立输入标记，放行后也须先投完资金前缀再回包，最后费用及总资产正确。
     */
    @Test
    @org.junit.jupiter.api.extension.ExtendWith(PipelinedExecutionExtension.class)
    void fullLedgerMailboxKeepsItsDispatchCursorAndRetriesWithoutNewMatcherNotifications() throws Exception {
        // 参数组合的当前产品线
        var line = ProductLine.LINEAR_PERPETUAL;
        // 本用例进程内 Owner 测试辅助，结束时按原生命周期终止
        var service = new TradingOwnerTestSupport(line);
        // 模拟会话复制出的原响应字节清单，支持工作线程可见性
        var responses = new CopyOnWriteArrayList<byte[]>();
        // 通知测试线程被阻塞的 Lane、Matcher 或账本工作已经进入的门闩
        var entered = new java.util.concurrent.CountDownLatch(1);
        // 放行阻塞工作的门闩，各退出路径确保放行
        var release = new java.util.concurrent.CountDownLatch(1);
        service.onStart(cluster(), null);
        try {
            // 本用例后续可用原来源序号，发送消息时递增
            long sequence = prepareLedgerPipelineMarkets(service, responses, line);
            // 当前 Owner 辅助持有的真实业务运行时
            var core = service.state();
            // 当前真实账户与账本工作资源所在的原运行时
            var runtime = core.runtimeState;
            assertThat(runtime.dispatchLedgerQuery(/* 真实账本回调给出的权威 Treasury，只在账本作用域读取 */ ledger -> {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Full mailbox release timeout");
                } catch (/* 阻塞账本等待中断，转测试失败 */ InterruptedException interrupted) { throw new AssertionError(interrupted); }
            })).isTrue();
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            // 测试阻塞/填满账本的空查询或原异步只读消息
            com.surprising.aeron.service.treasury.SystemLedgerLane.Entry query = /* 真实账本回调给出的权威 Treasury，只在账本作用域读取 */ ledger -> { };
            // 为填满原账本邮箱成功排入的空查询次数，超过八千一百九十二判测试异常
            int queued = 0;
            while (runtime.dispatchLedgerQuery(query)) {
                if (++queued > 8_192) throw new AssertionError("Ledger mailbox did not backpressure");
            }
            assertThat(queued).isPositive();
            // 本轮要优先结算的首条原成交请求
            var first = pipelineTrade(line, sequence++, 1);
            // 本轮排在首条或控制命令之后的原成交请求
            var following = pipelineTrade(line, sequence, 3);
            // 本批原消息入队前已收到的响应数量，账本受阻时不能增加
            int before = responses.size();
            try (/* 只供测试发现 Owner 越界读取账户正文的原保护器 */ var guard = new OwnerAccountReadGuard(runtime)) {
                for (var /* 本轮逐项检查或入队的原协议请求 */ request : List.of(first, following))
                    service.acceptCommittedCommand(clientSession(responses), request, 2_000, 2_000);
                awaitPipelineAccounts(service, List.of(first, following));
                assertThat(core.pendingMatching().ledgerDispatchHead().command().header().commandId())
                        .isEqualTo(first.header().commandId());
                assertThat(core.matchingProgress.hasNotifications()).isFalse();
                assertThat(core.matchingProgress.hasLocalMatchingWork()).isTrue();
                assertThat(responses).hasSize(before);
                // 原账户事实已就绪，即使不标记为独立输入路径，
                // 首响应交出前也必须把既有资金前缀继续交给账本。
                PipelineTestAccess.clusterIndependent(core.pendingMatching().findByCommandId(first.header().commandId()), false);
                PipelineTestAccess.clusterIndependent(core.pendingMatching().findByCommandId(following.header().commandId()), false);
                release.countDown();
                // 当前局部等待阶段的单调截止值，按该方法原代码独立或共用
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (core.pendingMatching().ledgerDispatchHead() != null) {
                    core.commits.dispatchReadyMatcherFinancialSettlements(Long.MAX_VALUE);
                    if (System.nanoTime() >= deadline) throw new AssertionError("Ledger dispatch still waits for response retirement");
                    Thread.onSpinWait();
                }
                // 账本观察原费用完成后通知测试线程的门闩
                var inspected = new java.util.concurrent.CountDownLatch(1);
                // 账本原子观测值或冷状态读出的原 USDT 费用总额
                var fees = new AtomicLong(-1);
                // 本账本费用读取使用的 USDT 内部资产编号
                int asset = core.identities.assetId("USDT");
                assertThat(runtime.dispatchLedgerQuery(/* 真实账本回调给出的权威 Treasury，只在账本作用域读取 */ ledger -> { fees.set(ledger.fee(asset)); inspected.countDown(); })).isTrue();
                assertThat(inspected.await(2, TimeUnit.SECONDS)).isTrue();
                assertThat(fees).hasValue(4);
                assertThat(core.pendingMatching().size()).isEqualTo(2);
                assertThat(responses).hasSize(before);
                finishCommands(service);
            }
            assertThat(responses).hasSize(before + 2);
            for (/* 遍历原响应或请求清单的当前下标 */ int index = before; index < responses.size(); index++)
                assertThat(CoreProtocol.decodeResponse(CoreMessageCodec.decode(responses.get(index)).payload()).commandStatus())
                        .isEqualTo(ResponseStatus.APPLIED);
            assertThat(core.pendingMatching().ledgerDispatchHead()).isNull();
            // 相关原命令完成后取得的已提交冷状态，供资金与订单断言
            var state = core.tradingState();
            assertThat(state.treasuryState().feeBalances()).containsEntry("USDT", 4L);
            assertThat(state.users().values().stream().mapToLong(/* 原用户 ID，或实时用户帧解码/冷状态查得的原账户视图 */ user -> user.totalUnits("USDT")).sum() + 4).isEqualTo(40_000);
            assertThat(service.pendingCommandCount()).isZero();
        } finally { release.countDown(); service.onTerminate(null); }
    }

    /**
     * 为用户一或三构造对应标的一或二的一手限价 IOC taker，便于独立账本前缀用例。
     * @param line 本轮独立运行时的现货或 U 本位永续产品线
     * @param sequence 本条原来源顺序号，亦参与 UUID 或关联号
     * @param taker 本测试主动买入用户一或三
     */
    private static CoreMessage pipelineTrade(ProductLine line, long sequence, int taker) {
        return command(line, CoreMessageType.PLACE_ORDER, sequence, taker,
                TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(9500 + taker,
                        Integer.toString((taker + 1) / 2), CoreOrderSide.BUY, 100, 1, false,
                        CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT, CoreTimeInForce.IOC,
                        false, "pipeline-boundary-" + taker)));
    }

    /**
     * 最多两秒显式轮询 Owner，逐请求核对结算事件已发布、实际账户 Lane 均完成且无 Matcher 通知；只证明账户事实就绪，不等待 Ledger 或回包。
     * @param service 当前进程内 Owner 测试辅助
     * @param requests 本轮要等待其账户完成的原命令清单
     */
    private static void awaitPipelineAccounts(TradingOwnerTestSupport service, List<CoreMessage> requests) {
        // 当前局部等待阶段的单调截止值，按该方法原代码独立或共用
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        // 当前 Owner 辅助持有的真实业务运行时
        var core = service.state();
        do {
            service.pollCommands();
            // 原请求清单账户是否都已完成，外层仍须另等财务与提交
            boolean ready = true;
            for (var /* 本轮逐项检查或入队的原协议请求 */ request : requests) {
                // 按原 UUID 找到的在途撮合槽
                var pending = core.pendingMatching().findByCommandId(request.header().commandId());
                // 当前原命令账户/财务结算事件，分别检查发布、账户完成及最终完成
                var event = pending == null ? null : pending.settlementEvent();
                ready &= event != null && event.ready() && event.completedLaneMask() != 0
                        && (event.completedLaneMask() & event.requiredLaneMask()) == event.requiredLaneMask();
            }
            if (ready && !core.matchingProgress.hasNotifications()) return;
            if (System.nanoTime() > deadline) throw new AssertionError("Pipeline account completion timeout");
            java.util.concurrent.locks.LockSupport.parkNanos(100_000);
        } while (true);
    }

    /**
     * 建立现货或永续两标的、四用户各一万本金，maker 二/四分别挂十份卖单且有非零费用；返回后续可用原来源序号。
     * @param service 当前进程内 Owner 测试辅助
     * @param responses 接收模拟会话复制响应字节的原清单
     * @param line 本轮独立运行时的现货或 U 本位永续产品线
     */
    private static long prepareLedgerPipelineMarkets(TradingOwnerTestSupport service,
                                                     List<byte[]> responses, ProductLine line) {
        // 本用例后续可用原来源序号，发送消息时递增
        long sequence = 1;
        for (String /* 当前初始化合约的永久数字文本一或二 */ symbol : List.of("1", "2")) {
            onSessionMessage(service, responses, command(line, CoreMessageType.REGISTER_INSTRUMENT, sequence++, 0,
                    TradingCommandCodec.encodeRegisterInstrument(new RegisterInstrumentCommand(symbol, "BTC", "USDT", "USDT",
                            1, 1, 1, 100_000, 50_000, 10_000, 10_000, 0, -1, 0))));
            onSessionMessage(service, responses, command(line, CoreMessageType.APPLY_MARK_PRICE, sequence++, 0,
                    TradingCommandCodec.encodeApplyMarkPrice(
                            new com.surprising.aeron.protocol.ApplyMarkPriceCommand(symbol, 100, 1, 1_000))));
        }
        for (/* 原用户 ID，或实时用户帧解码/冷状态查得的原账户视图 */ int user = 1; user <= 4; user++) {
            onSessionMessage(service, responses, command(line, CoreMessageType.ADJUST_BALANCE, sequence++, user,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 10_000))));
        }
        for (int /* 当前为独立标的挂十份卖单的用户二或四 */ maker : new int[]{2, 4}) {
            if (line == ProductLine.SPOT) {
                onSessionMessage(service, responses, command(line, CoreMessageType.ADJUST_BALANCE, sequence++, maker,
                        TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("BTC", 10))));
            }
            onSessionMessage(service, responses, command(line, CoreMessageType.PLACE_ORDER, sequence++, maker,
                    TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(9200 + maker,
                            Integer.toString(maker / 2), CoreOrderSide.SELL, 100, 10, false,
                            CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT,
                            CoreTimeInForce.GTC, false, "pipeline-maker-" + maker))));
        }
        return sequence;
    }

    /**
     * 组合现货/永续与普通单/双项批次，分别验证账本前缀和终态退休可分开推进。
     */
    private static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> ledgerPipelineCases() {
        return java.util.stream.Stream.of(ProductLine.SPOT, ProductLine.LINEAR_PERPETUAL)
                .flatMap(/* 参数组合的当前产品线 */ line -> java.util.stream.Stream.of(false, true)
                        .map(/* 当前命令槽原批次状态，参数生成回调中为是否批次的布尔值 */ batch -> org.junit.jupiter.params.provider.Arguments.of(line, batch)));
    }

    /**
     * PIPELINED 现货/永续分别构造二十项下单、撤单或改单，阻塞账本直到相关账户完成；放行后通过完成门禁继续 Owner，不需另一 Matcher 提示，批次及后继响应有序并核对费用、余量与资金。
     * @param line 本轮独立运行时的现货或 U 本位永续产品线
     * @param type 本条原协议消息类型
     */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("batchLedgerCompletionCases")
    @org.junit.jupiter.api.extension.ExtendWith(PipelinedExecutionExtension.class)
    void batchLedgerCompletionResumesOrderedHeadWithoutAnotherMatcherNotification(
            ProductLine line, CoreMessageType type) throws Exception {
        // 本用例进程内 Owner 测试辅助，结束时按原生命周期终止
        var service = new TradingOwnerTestSupport(line);
        // 模拟会话复制出的原响应字节清单，支持工作线程可见性
        var responses = new CopyOnWriteArrayList<byte[]>();
        // 通知测试线程被阻塞的 Lane、Matcher 或账本工作已经进入的门闩
        var entered = new java.util.concurrent.CountDownLatch(1);
        // 放行阻塞工作的门闩，各退出路径确保放行
        var release = new java.util.concurrent.CountDownLatch(1);
        service.onStart(cluster(), null);
        try {
            onSessionMessage(service, responses, command(line, CoreMessageType.REGISTER_INSTRUMENT, 1, 0,
                    TradingCommandCodec.encodeRegisterInstrument(new RegisterInstrumentCommand("1", "BTC", "USDT", "USDT",
                            1, 1, 1, 100_000, 50_000, 10_000, 10_000, 0, -1, 0))));
            onSessionMessage(service, responses, command(line, CoreMessageType.APPLY_MARK_PRICE, 2, 0,
                    TradingCommandCodec.encodeApplyMarkPrice(
                            new com.surprising.aeron.protocol.ApplyMarkPriceCommand("1", 100, 1, 1_000))));
            for (/* 原用户 ID，或实时用户帧解码/冷状态查得的原账户视图 */ int user = 1; user <= 2; user++) {
                onSessionMessage(service, responses, command(line, CoreMessageType.ADJUST_BALANCE, 2 + user, user,
                        TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 10_000))));
            }
            if (line == ProductLine.SPOT) {
                onSessionMessage(service, responses, command(line, CoreMessageType.ADJUST_BALANCE, 5, 2,
                        TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("BTC", 20))));
            }
            onSessionMessage(service, responses, command(line, CoreMessageType.PLACE_ORDER, 6, 2,
                    TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(9900, "1", CoreOrderSide.SELL,
                            100, 20, false, CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT,
                            CoreTimeInForce.GTC, false, "batch-ledger-maker"))));
            // 本用例后续可用原来源序号，发送消息时递增
            long sequence = 7;
            // 二十项原下单参数清单
            var placements = new java.util.ArrayList<PlaceOrderCommand>();
            // 二十项原撤单参数清单
            var cancellations = new java.util.ArrayList<CancelOrderCommand>();
            // 二十项原改单参数清单
            var amendments = new java.util.ArrayList<AmendOrderCommand>();
            for (/* 当前原批次子项的下标 */ int item = 0; item < 20; item++) {
                // 本次构造的原子订单号
                long orderId = 10_000 + item;
                placements.add(new PlaceOrderCommand(orderId, "1", CoreOrderSide.BUY, 100, 1,
                        false, CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT,
                        CoreTimeInForce.IOC, false, "batch-ledger-place-" + item));
                cancellations.add(new CancelOrderCommand(orderId));
                amendments.add(new AmendOrderCommand(orderId, 11_000 + item, "batch-ledger-amend-" + item,
                        100L, 1L, CoreTimeInForce.GTC, false));
                if (type != CoreMessageType.PLACE_ORDER_BATCH) {
                    onSessionMessage(service, responses, command(line, CoreMessageType.PLACE_ORDER, sequence++, 1,
                            TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(orderId, "1",
                                    CoreOrderSide.BUY, 99, 1, false, CoreMarginMode.CROSS, CorePositionSide.NET,
                                    CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "batch-ledger-original-" + item))));
                }
            }
            // 按本轮批下、批撤或批改编码的原载荷
            byte[] payload = switch (type) {
                case PLACE_ORDER_BATCH -> TradingOrderBatchCodec.encodePlaceOrderBatch(new PlaceOrderBatchCommand(placements));
                case CANCEL_ORDER_BATCH -> TradingOrderBatchCodec.encodeCancelOrderBatch(new CancelOrderBatchCommand(cancellations));
                case AMEND_ORDER_BATCH -> TradingOrderBatchCodec.encodeAmendOrderBatch(new AmendOrderBatchCommand(amendments));
                default -> throw new AssertionError(type);
            };
            // 当前 Owner 辅助持有的真实业务运行时
            var core = service.state();
            // 当前真实账户与账本工作资源所在的原运行时
            var runtime = core.runtimeState;
            assertThat(runtime.systemLedgerStarted()).isTrue();
            assertThat(core.laneTopology().accountLaneId(1)).isNotEqualTo(core.laneTopology().accountLaneId(2));
            assertThat(runtime.dispatchLedgerQuery(/* 真实账本回调给出的权威 Treasury，只在账本作用域读取 */ ledger -> {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Batch Ledger release timeout");
                } catch (/* 阻塞账本等待中断，转测试失败 */ InterruptedException interrupted) { throw new AssertionError(interrupted); }
            })).isTrue();
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            // 本批原消息入队前已收到的响应数量，账本受阻时不能增加
            int before = responses.size();
            service.acceptCommittedCommand(clientSession(responses), command(line, type, sequence++, 1, payload), 2_000, 2_000);
            service.acceptCommittedCommand(clientSession(responses), command(line, CoreMessageType.PLACE_ORDER, sequence, 1,
                    TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(9800, "1", CoreOrderSide.BUY,
                            98, 1, false, CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT,
                            CoreTimeInForce.IOC, false, "batch-ledger-following"))), 2_001, 2_001);
            // 当前原命令账户/财务结算事件，分别检查发布、账户完成及最终完成
            com.surprising.aeron.service.settlement.MatcherSettlementEvent event;
            // 当前局部等待阶段的单调截止值，按该方法原代码独立或共用
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            do {
                service.pollCommands();
                // 原撮合环当前按序提交头
                var head = core.pendingMatching().head();
                // 当前命令槽原批次状态，参数生成回调中为是否批次的布尔值
                var batch = head == null ? null : head.orderBatch();
                event = batch == null ? null : batch.itemSettlementEvent() != null
                        ? batch.itemSettlementEvent() : batch.settlementEvent();
                if (event != null && event.requiredLaneMask() != 0
                        && (event.completedLaneMask() & event.requiredLaneMask()) == event.requiredLaneMask()
                        && !core.matchingProgress.hasNotifications()
                        && (type != CoreMessageType.CANCEL_ORDER_BATCH || !service.ownerWorkAvailable())) break;
                if (System.nanoTime() > deadline) throw new AssertionError("Batch Owner did not await Ledger completion");
                java.util.concurrent.locks.LockSupport.parkNanos(100_000);
            } while (true);
            assertThat(event.complete()).isFalse();
            assertThat(responses).hasSize(before);
            release.countDown();
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!event.complete()) {
                if (System.nanoTime() > deadline) throw new AssertionError("Ledger did not finish batch settlement");
                java.util.concurrent.locks.LockSupport.parkNanos(100_000);
            }
            assertThat(service.ownerCompletionAvailable()).isTrue();
            assertThat(service.ownerWorkAvailable()).isTrue();
            finishCommands(service);
            assertThat(responses).hasSize(before + 2);
            // 本轮原批次完成后解码的响应
            var batchResponse = CoreProtocol.decodeResponse(CoreMessageCodec.decode(responses.get(before)).payload());
            assertThat(batchResponse.commandStatus()).isEqualTo(ResponseStatus.APPLIED);
            assertThat(TradingOrderBatchCodec.firstNonAppliedItem(batchResponse, 20)).isEqualTo(-1);
            assertThat(CoreProtocol.decodeResponse(CoreMessageCodec.decode(responses.get(before + 1)).payload())
                    .commandStatus()).isEqualTo(ResponseStatus.APPLIED);
            assertThat(service.pendingCommandCount()).isZero();
            // 相关原命令完成后取得的已提交冷状态，供资金与订单断言
            var state = core.tradingState();
            assertThat(state.user(1).reservations()).isEmpty();
            assertThat(state.user(1).balances().get("USDT").lockedUnits()).isEqualTo(
                    line == ProductLine.SPOT || type == CoreMessageType.CANCEL_ORDER_BATCH ? 0 : 200);
            assertThat(state.order(9800)).isNull();
            for (/* 当前原批次子项的下标 */ int item = 0; item < 20; item++) {
                assertThat(state.order(10_000 + item)).isNull();
                assertThat(state.order(11_000 + item)).isNull();
            }
            // 账本原子观测值或冷状态读出的原 USDT 费用总额
            long fees = state.treasuryState().feeBalances().getOrDefault("USDT", 0L);
            // 已提交用户 USDT 总额，和原财库费用一起核对守恒
            long funds = state.users().values().stream().mapToLong(/* 原用户 ID，或实时用户帧解码/冷状态查得的原账户视图 */ user -> user.totalUnits("USDT")).sum();
            assertThat(funds + fees).isEqualTo(20_000);
            if (type == CoreMessageType.CANCEL_ORDER_BATCH) {
                assertThat(fees).isZero();
                assertThat(core.terminalTradeCount).isZero();
                assertThat(state.order(9900).remainingQuantitySteps()).isEqualTo(20);
            } else {
                assertThat(fees).isEqualTo(40);
                assertThat(core.terminalTradeCount).isEqualTo(20);
                assertThat(state.order(9900)).isNull();
                if (line == ProductLine.SPOT) {
                    assertThat(state.user(1).totalUnits("BTC")).isEqualTo(20);
                    assertThat(state.user(2).totalUnits("BTC")).isZero();
                } else {
                    assertThat(state.user(1).positions().get("1").signedQuantitySteps()).isEqualTo(20);
                    assertThat(state.user(2).positions().get("1").signedQuantitySteps()).isEqualTo(-20);
                }
            }
        } finally { release.countDown(); service.onTerminate(null); }
    }

    /**
     * 组合现货/永续及批下、批撤、批改三类原命令。
     */
    private static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> batchLedgerCompletionCases() {
        return java.util.stream.Stream.of(ProductLine.SPOT, ProductLine.LINEAR_PERPETUAL)
                .flatMap(/* 参数组合的当前产品线 */ line -> java.util.stream.Stream.of(CoreMessageType.PLACE_ORDER_BATCH,
                        CoreMessageType.CANCEL_ORDER_BATCH, CoreMessageType.AMEND_ORDER_BATCH)
                        .map(/* 参数组合中当前原批量消息类型：批下、批撤或批改 */ type -> org.junit.jupiter.params.provider.Arguments.of(line, type)));
    }

    /**
     * 先通过资金身份预检查进入真实账户续步，再注入撮合排队容量超限或终态元数据读取异常；检查原余额回滚、无 Owner 交接、队列清空且保留可幂等重试的拒绝结果。
     * @param capacityFailure 是否注入匹配排队容量溢出，否则在终态元数据读取中注入异常
     */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void terminalPreparationFailureRollsBackOnLaneAndRetainsRejection(boolean capacityFailure) throws Exception {
        // 本用例进程内 Owner 测试辅助，结束时按原生命周期终止
        var service = service();
        service.onStart(cluster(), null);
        // 本测试直接操作的原交易运行时
        var owner = service.state();
        // 当前真实账户与账本工作资源所在的原运行时
        var runtime = owner.runtimeState;
        try {
            assertThat(CoreTestCompletion.applyAsynchronously(owner, command(CoreMessageType.ADJUST_BALANCE, 1, 1001,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 10000))))
                    .status()).isEqualTo(ResponseStatus.APPLIED);
            // 只供测试观察账户所有权交接代号的反射字段
            // 本次原业务执行前账户所有权交接代号，完成或拒绝后均应不变
            long before = runtime.laneHandoffEpoch();
            // 本轮逐项检查或入队的原协议请求
            var request = command(CoreMessageType.ADJUST_BALANCE, 2, 1001,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 500)));
            // 本轮原业务、Matcher 控制或撮合结果，未就绪可为空
            CoreResponse result;
            runtime.enterAsynchronousCommandScope();
            try {
                assertThat(owner.applyDecodedCommand(request, 2000, 2000, null, false)).isNull();
                // 保留资金身份的查询先于账户工作，
                // 只在这一步已经接入真实控制续步后注入终态准备故障。
                // 最多五秒等原资金身份预检查接入实际账户续步的截止值
                long preparationDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (!owner.directCommand.hasControlWork()) {
                    assertThat(owner.directCommand.poll()).isNull();
                    if (System.nanoTime() >= preparationDeadline)
                        throw new AssertionError("funds preflight did not reach Account Lane work");
                    Thread.onSpinWait();
                }
                // 替换故障续步前保存的原业务控制工作，先让它完成账户工作
                var business = owner.directCommand.controlWork();
                owner.directCommand.replaceControlWork(() -> {
                    if (!business.getAsBoolean()) return false;
                    if (capacityFailure) {
                        // 这里只制造排队溢出，条目须在实际派发前被拒绝并清空。
                        PipelineTestAccess.overflowQueuedMatching(owner.admissions, owner.pendingMatching().capacity() + 1);
                    } else {
                        owner.resultBuilder.commandChangedOrderIds = /* 只在终态盖元数据调用栈中抛错的测试列表，故障只存在本测试源码。 */ new java.util.AbstractList<Long>() {
                            /**
                             * 故障列表只报告一个原订单 ID，确保终态准备会尝试读取它。
                             */
                            public int size() { return 1; }
                            /**
                             * 仅当调用栈进入终态盖元数据或其校验时抛注入异常，其余返回最大 long 假 ID，定位终态准备的回滚边界。
                             * @param index 测试故障列表要求读取的位置，仅用于触发原终态准备
                             */
                            public Long get(int index) {
                                if (StackWalker.getInstance().walk(/* StackWalker 提供的当前调用栈帧流，只用于定位终态准备方法 */ frames -> frames.anyMatch(
                                        /* 当前调用栈帧，按方法名决定是否注入终态故障 */ frame -> (frame.getMethodName().equals("stampChangedOrdersByLane")
                                                || frame.getMethodName().equals("validateStampInputs")))))
                                    throw new IllegalStateException("injected terminal metadata failure");
                                return Long.MAX_VALUE;
                            }
                        };
                    }
                    return true;
                });
                // 当前局部等待阶段的单调截止值，按该方法原代码独立或共用
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                do {
                    result = owner.directCommand.poll();
                    if (System.nanoTime() > deadline) throw new AssertionError("finalization rollback timed out");
                } while (result == null);
            } finally { runtime.exitAsynchronousCommandScope(); }
            assertThat(result.status()).isEqualTo(ResponseStatus.REJECTED);
            assertThat(result.resultCode()).isEqualTo(capacityFailure
                    ? CoreResultCode.MATCHING_BACKPRESSURE : CoreResultCode.INVALID_COMMAND);
            assertThat(CoreTestCompletion.userState(owner, 1001).balances())
                    .filteredOn(/* 原用户已提交 USDT 余额视图，或余额筛选回调的当前资产项 */ balance -> balance.asset().equals("USDT")).singleElement().satisfies(/* 原用户已提交 USDT 余额视图，或余额筛选回调的当前资产项 */ balance -> {
                        assertThat(balance.availableUnits()).isEqualTo(10000);
                        assertThat(balance.lockedUnits()).isZero();
                    });
            assertThat(runtime.laneHandoffEpoch()).isEqualTo(before);
            assertThat(owner.admissions.queuedMatchingCount()).isZero();
            // 重复原请求取得的已记录拒绝终态
            var duplicate = CoreTestCompletion.applyAsynchronously(owner, request);
            assertThat(duplicate.status()).isEqualTo(ResponseStatus.DUPLICATE);
            assertThat(duplicate.commandStatus()).isEqualTo(ResponseStatus.REJECTED);
            assertThat(duplicate.resultCode()).isEqualTo(result.resultCode());
        } finally { service.onTerminate(null); }
    }

    /**
     * 同算法单连续更新到修订三，不接管账户；把全局修订设为 long 上限制造发布失败，检查算法状态回滚到三，再恢复元数据并以新来源消息成功更新四。
     */
    @Test
    void algoUpdatesStayOnAccountLaneAndPublishRevisions() throws Exception {
        // 本用例进程内 Owner 测试辅助，结束时按原生命周期终止
        var service = service();
        // 模拟会话复制出的原响应字节清单，支持工作线程可见性
        var responses = new CopyOnWriteArrayList<byte[]>();
        service.onStart(cluster(), null);
        try {
            // 当前真实账户与账本工作资源所在的原运行时
            var runtime = service.state().runtimeState;
            // 只供测试观察账户所有权交接代号的反射字段
            // 本次原业务执行前账户所有权交接代号，完成或拒绝后均应不变
            long before = runtime.laneHandoffEpoch();
            for (/* 算法单当前要发布的一到三修订号 */ int revision = 1; revision <= 3; revision++) {
                // 本轮要写入的原算法状态视图
                var algo = new com.surprising.aeron.protocol.CoreAlgoOrderView(501, 1001, "algo-client", "1", 0,
                        CoreOrderSide.BUY, 0, 100, 10, 1, 10, CoreMarginMode.CROSS, CorePositionSide.NET,
                        false, false, CoreTimeInForce.IOC, 0, 0, "",
                        1, 1, 0, 1, revision, revision, List.of(), 0, 0, 0);
                onSessionMessage(service, responses, command(CoreMessageType.UPSERT_ALGO_ORDER,
                        revision, 1001, encodeAlgo(algo, 0)));
                finishCommands(service);
                assertThat(CoreTestCompletion.algoState(service.state(), 1001, 501).revision()).isEqualTo(revision);
                assertThat(CoreTestCompletion.algoState(service.state(), 1001, 501).userId()).isEqualTo(1001);
            }
            assertThat(runtime.laneHandoffEpoch()).isEqualTo(before);
            // 主动设全局元数据为 long 上限之前保存的原修订
            long revisionBeforeFailure = runtime.revision();
            runtime.setMetadata(ProductLine.SPOT, Long.MAX_VALUE);
            // 原算法单下一次修订四的意图
            var next = new com.surprising.aeron.protocol.CoreAlgoOrderView(501, 1001, "algo-client", "1", 0,
                    CoreOrderSide.BUY, 0, 100, 10, 1, 10, CoreMarginMode.CROSS, CorePositionSide.NET,
                    false, false, CoreTimeInForce.IOC, 0, 0, "",
                    1, 1, 0, 1, 4, 4, List.of(), 0, 0, 0);
            // 按本轮批下、批撤或批改编码的原载荷
            byte[] payload = encodeAlgo(next, 0);
            onSessionMessage(service, responses, command(CoreMessageType.UPSERT_ALGO_ORDER, 4, 1001, payload));
            finishCommands(service);
            assertThat(CoreProtocol.decodeResponse(CoreMessageCodec.decode(responses.getLast()).payloadUnsafe()).status())
                    .isEqualTo(ResponseStatus.REJECTED);
            assertThat(CoreTestCompletion.algoState(service.state(), 1001, 501).revision()).isEqualTo(3);
            assertThat(runtime.revision()).isEqualTo(Long.MAX_VALUE);
            runtime.setMetadata(ProductLine.SPOT, revisionBeforeFailure);
            onSessionMessage(service, responses, command(CoreMessageType.UPSERT_ALGO_ORDER, 5, 1001, payload));
            finishCommands(service);
            assertThat(CoreProtocol.decodeResponse(CoreMessageCodec.decode(responses.getLast()).payloadUnsafe()).status())
                    .isEqualTo(ResponseStatus.APPLIED);
            assertThat(CoreTestCompletion.algoState(service.state(), 1001, 501).revision()).isEqualTo(4);
            assertThat(runtime.laneHandoffEpoch()).isEqualTo(before);

        } finally { service.onTerminate(null); }
    }

    /**
     * 连续执行余额、状态校验及延时全撤计时器命令，检查无账户 Owner 交接、余额三、计时器修订一且应用数三。
     */
    @Test
    void ownerMetadataCommandsDoNotHandoffAccountLanes() throws Exception {
        // 本用例进程内 Owner 测试辅助，结束时按原生命周期终止
        var service = service();
        // 模拟会话复制出的原响应字节清单，支持工作线程可见性
        var responses = new CopyOnWriteArrayList<byte[]>();
        service.onStart(cluster(), null);
        try {
            // 当前真实账户与账本工作资源所在的原运行时
            var runtime = service.state().runtimeState;
            // 只供测试观察账户所有权交接代号的反射字段
            // 本次原业务执行前账户所有权交接代号，完成或拒绝后均应不变
            long before = runtime.laneHandoffEpoch();
            onSessionMessage(service, responses,
                    command(CoreMessageType.ADJUST_BALANCE, 1, 1001, com.surprising.aeron.protocol.TradingCommandCodec.encodeBalanceAdjustment(new com.surprising.aeron.protocol.BalanceAdjustmentCommand("USDT", 3))));
            finishCommands(service);
            onSessionMessage(service, responses,
                    command(CoreMessageType.CONTINUE_RISK_SCAN, 2, 1001, com.surprising.aeron.protocol.TradingCommandCodec.encodeContinueRiskScan(new com.surprising.aeron.protocol.ContinueRiskScanCommand(1))));
            finishCommands(service);
            // 原用户标的一延时全撤 SET 命令
            var timer = new com.surprising.aeron.protocol.CoreCancelAllAfterCommand(com.surprising.aeron.protocol.CoreCancelAllAfterAction.SET, "1", 1000, 0, 0, 0, 1000);
            onSessionMessage(service, responses, command(CoreMessageType.UPDATE_CANCEL_ALL_AFTER, 3, 1001,
                    com.surprising.aeron.protocol.CoreCancelAllAfterCodec.encodeCommand(timer)));
            finishCommands(service);
            assertThat(runtime.laneHandoffEpoch()).isEqualTo(before);
            assertThat(testBalance(service.state(), 1001)).isEqualTo(3);
            assertThat(runtime.cancelAllAfterTimer(new com.surprising.aeron.model.CoreCancelAllAfterKey(
                    1001, "1")).revision()).isEqualTo(1);
            assertThat(service.state().appliedCommandCount()).isEqualTo(3);
        } finally {
            service.onTerminate(null);
        }
    }

    /**
     * 阻塞用户所属 Lane，经会话入口入队预检并显式推进到查询挂起；放行前无响应，放行后取得 USDT 预留三百的只读结果，Owner 账户读取保护下交接代号不变。
     */
    @Test
    @org.junit.jupiter.api.extension.ExtendWith(PipelinedExecutionExtension.class)
    void preflightQueryWaitsForLaneOnLoggedCallbacksWithoutOwnerAccountReads() throws Exception {
        // 本用例进程内 Owner 测试辅助，结束时按原生命周期终止
        var service = service();
        // 模拟会话复制出的原响应字节清单，支持工作线程可见性
        var responses = new CopyOnWriteArrayList<byte[]>();
        // 放行阻塞工作的门闩，各退出路径确保放行
        var release = new java.util.concurrent.CountDownLatch(1);
        service.onStart(cluster(), null);
        try {
            assertThat(CoreTestCompletion.applyAsynchronously(service.state(), timerInstrument()).status())
                    .isEqualTo(ResponseStatus.APPLIED);
            // 当前真实账户与账本工作资源所在的原运行时
            var runtime = service.state().runtimeState;
            // 通知测试线程被阻塞的 Lane、Matcher 或账本工作已经进入的门闩
            var entered = new java.util.concurrent.CountDownLatch(1);
            // 原用户一千零一所属账户 Lane 位图
            long mask = runtime.topology().accountLaneMask(1001);
            assertThat(runtime.dispatchAccountQuery(mask, /* 实际执行阻塞或读取工作的用户所属账户 Lane */ lane -> {
                entered.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Lane release timeout"); }
                catch (/* 当前被注入或捕获的原测试故障，按所在异常边界处理 */ InterruptedException failure) { throw new AssertionError(failure); }
            })).isTrue();
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            // 本次异步查询开始前原账户所有权交接代号
            long handoff = runtime.laneHandoffEpoch();
            // 原用户买三份的预检意图
            var intent = new PlaceOrderCommand(910, "1", CoreOrderSide.BUY, 100, 3, false,
                    CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "preflight-910");
            // 本轮逐项检查或入队的原协议请求
            var request = new CoreMessage(CoreMessageHeader.query(CoreMessageType.ORDER_PREFLIGHT_QUERY,
                    UUID.randomUUID(), ProductLine.SPOT, CommandSource.GATEWAY, 77, 0, 1001, 1000, 9),
                    TradingCommandCodec.encodePlaceOrder(intent));
            try (/* 只供测试发现 Owner 越界读取账户正文的原保护器 */ var guard = new OwnerAccountReadGuard(runtime)) {
                // 本次交回调的完整原消息字节
                byte[] encoded = CoreMessageCodec.encode(request);
                service.onSessionMessage(clientSession(responses), 1000, new UnsafeBuffer(encoded), 0,
                        encoded.length, aeronHeader());
                // 当前局部等待阶段的单调截止值，按该方法原代码独立或共用
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (service.state().bookQueries.querySequence(request.header().commandId()) == 0 && System.nanoTime() < deadline)
                    service.pollCommands();
                assertThat(service.state().bookQueries.querySequence(request.header().commandId())).isNotZero();
                assertThat(responses).isEmpty();
                assertThat(release.getCount()).isOne();
                release.countDown();
                finishCommands(service);
                assertThat(responses).hasSize(1);
                // 本轮原业务响应或已编码响应消息，用来核对身份与终态
                var response = CoreProtocol.decodeResponse(CoreMessageCodec.decode(responses.getFirst()).payloadUnsafe());
                assertThat(response.status()).isEqualTo(ResponseStatus.OK);
                assertThat(response.data()).isEqualTo(com.surprising.aeron.protocol.CoreOrderPreflightCodec.encode(
                        new com.surprising.aeron.protocol.CoreOrderPreflightView("USDT", 300)));
                assertThat(runtime.laneHandoffEpoch()).isEqualTo(handoff);
                service.state().assertClusterCallbackComplete();
            }
        } finally { release.countDown(); service.onTerminate(null); }
    }

    /**
     * 阻塞 Matcher 后会话入口可返回并由轮询登记盘口查询，尚无响应；放行控制任务并显式 finishCommands 后得到 OK。当前测试不是通过另一条真实日志回调才完成。
     */
    @Test
    @org.junit.jupiter.api.extension.ExtendWith(PipelinedExecutionExtension.class)
    void bookQueryDoesNotWaitForBlockedMatcherAndCompletesOnLaterLoggedCallback() throws Exception {
        // 本用例进程内 Owner 测试辅助，结束时按原生命周期终止
        var service = service();
        // 模拟会话复制出的原响应字节清单，支持工作线程可见性
        var responses = new CopyOnWriteArrayList<byte[]>();
        // 放行阻塞工作的门闩，各退出路径确保放行
        var release = new java.util.concurrent.CountDownLatch(1);
        service.onStart(cluster(), null);
        try {
            assertThat(CoreTestCompletion.applyAsynchronously(service.state(), timerInstrument()).status())
                    .isEqualTo(ResponseStatus.APPLIED);
            // 通知测试线程被阻塞的 Lane、Matcher 或账本工作已经进入的门闩
            var entered = new java.util.concurrent.CountDownLatch(1);
            // 本次阻塞 Matcher 控制任务的原队列身份
            long token = service.state().matcherPipeline().submitControl(0, () -> {
                entered.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("matcher release timeout"); }
                catch (/* 当前被注入或捕获的原测试故障，按所在异常边界处理 */ InterruptedException failure) { throw new AssertionError(failure); }
                return Boolean.TRUE;
            });
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            // 测试阻塞/填满账本的空查询或原异步只读消息
            var query = new CoreMessage(CoreMessageHeader.query(CoreMessageType.BOOK_STATE_QUERY,
                    UUID.randomUUID(), ProductLine.SPOT, CommandSource.GATEWAY, 77, 0, 0, 1000, 9),
                    com.surprising.aeron.protocol.CoreStateQueryCodec.encodeOrderBookQuery(
                            new com.surprising.aeron.protocol.CoreOrderBookQuery("1", 10)));
            // 本次交回调的完整原消息字节
            byte[] encoded = CoreMessageCodec.encode(query);
            service.onSessionMessage(clientSession(responses), 1000, new UnsafeBuffer(encoded), 0,
                    encoded.length, aeronHeader());
            // 当前局部等待阶段的单调截止值，按该方法原代码独立或共用
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (service.state().bookQueries.querySequence(query.header().commandId()) == 0 && System.nanoTime() < deadline) {
                service.pollCommands();
                java.util.concurrent.locks.LockSupport.parkNanos(100_000);
            }
            assertThat(service.state().bookQueries.querySequence(query.header().commandId())).isNotZero();
            assertThat(responses).isEmpty();
            assertThat(release.getCount()).isOne();
            release.countDown();
            // 本轮原业务、Matcher 控制或撮合结果，未就绪可为空
            Object result;
            do { result = service.state().matcherPipeline().pollControl(0, token); Thread.onSpinWait(); }
            while (result == null && System.nanoTime() < deadline);
            assertThat(result).isEqualTo(Boolean.TRUE);
            finishCommands(service);
            assertThat(responses).hasSize(1);
            assertThat(CoreProtocol.decodeResponse(CoreMessageCodec.decode(responses.getFirst()).payloadUnsafe()).status())
                    .isEqualTo(ResponseStatus.OK);
        } finally { release.countDown(); service.onTerminate(null); }
    }

    /**
     * 用显式纳秒参数检查空轮询健康检测的一毫秒分频：阈值前不检查，原命令执行立即抛已注入审计故障，达到阈值的空探测也抛同一异常；不是实测墙钟延迟上限。
     */
    @Test
    void emptyPollingChecksSilentFailureWithinOneMillisecondAndCommandsNeverSkipHealth() throws Exception {
        // 本用例进程内 Owner 测试辅助，结束时按原生命周期终止
        var service = service();
        service.onStart(cluster(), null);
        // 本辅助当前原交易运行时
        var state = service.state();
        // 仅供测试读取原内部字段的反射对象
        var field = CoreSnapshotLifecycle.class.getDeclaredField("snapshotAuditFailure");
        field.setAccessible(true);
        // 快照审计原异步故障原子槽，测试结束清空
        @SuppressWarnings("unchecked")
        var failureSlot = (AtomicReference<RuntimeException>) field.get(state.snapshots);
        try {
            assertThat(CoreTestCompletion.applyAsynchronously(state, timerInstrument()).status())
                    .isEqualTo(ResponseStatus.APPLIED);
            // 测试人为传入的单调时间基准，用来核对健康探测分频而非测墙钟时延
            long now = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            assertThat(state.matchingProgress.hasNotifications(now)).isFalse();
            // 当前被注入或捕获的原测试故障，按所在异常边界处理
            var failure = new IllegalStateException("silent snapshot audit failure");
            failureSlot.set(failure);
            assertThat(state.matchingProgress.hasNotifications(now + 999_999)).isFalse();
            assertThatThrownBy(() -> state.apply(timerInstrument())).isSameAs(failure);
            assertThatThrownBy(() -> state.matchingProgress.hasNotifications(now + 1_000_000)).isSameAs(failure);
        } finally {
            failureSlot.set(null);
            service.onTerminate(null);
        }
    }

    /**
     * Matcher 受阻时提交订单及排队实时快照，检查回调不调用阻塞等待、背景重入不启动读取；放行后完成业务再完成实时 Lane 查询，冻结与实时用户均见两千冻结，快照恢复业务哈希相同。
     */
    @Test
    @org.junit.jupiter.api.extension.ExtendWith(PipelinedExecutionExtension.class)
    void stageProgressAndReentrantBackgroundDeferSnapshotUntilSettlementCompletes() throws Exception {
        // 本用例进程内 Owner 测试辅助，结束时按原生命周期终止
        var service = service();
        // 模拟会话复制出的原响应字节清单，支持工作线程可见性
        var responses = new CopyOnWriteArrayList<byte[]>();
        // 通知测试线程被阻塞的 Lane、Matcher 或账本工作已经进入的门闩
        var entered = new java.util.concurrent.CountDownLatch(1);
        // 放行阻塞工作的门闩，各退出路径确保放行
        var release = new java.util.concurrent.CountDownLatch(1);
        // 调用即失败的测试等待器，检查命令回调没有靠阻塞 idle 推进
        var idle = /* 任何 idle 都会失败的测试等待器，用来发现回调阻塞等待。 */ new org.agrona.concurrent.IdleStrategy() {
            /**
             * 若回调使用这个等待入口立即让测试失败，禁止靠 idle 阻塞等命令完成。
             * @param work 回调报告的工作量，本测试等待器直接失败不退避
             */
            public void idle(int work) { throw new AssertionError("command callback must not idle"); }
            /**
             * 无参数等待也转到失败入口，避免漏检另一重载。
             */
            public void idle() { idle(0); }
            /**
             * 测试等待器没有可重置状态，保持空操作。
             */
            public void reset() { }
            /**
             * 返回该测试等待器固定名称，供既有等待契约使用。
             */
            public String alias() { return "nonblocking-callback"; }
        };
        // 除等待策略外沿用固定行为的原 Cluster 代理
        Cluster delegate = cluster();
        // 覆盖等待策略的 Cluster 代理，其他调用转原 delegate
        Cluster controlled = (Cluster) Proxy.newProxyInstance(Cluster.class.getClassLoader(),
                new Class<?>[]{Cluster.class}, (/* 当前动态代理对象，测试实现不读取其身份 */ proxy, /* 当前被调用的反射方法，按名称选择原代理行为 */ method, /* 当前动态代理收到的原实参数组 */ arguments) ->
                        method.getName().equals("idleStrategy") ? idle : method.invoke(delegate, arguments));
        service.onStart(controlled, null);
        // 本用例接入的原实时字节缓存，部分场景刻意限制容量
        var outbox = new com.surprising.aeron.client.RealtimeOutbox(1024, 1_048_576);
        CoreFaults.attachRealtime(service, outbox);
        try {
            CoreTestCompletion.applyAsynchronously(service.state(), timerInstrument());
            CoreTestCompletion.applyAsynchronously(service.state(), command(CoreMessageType.ADJUST_BALANCE, 1, 1001,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 10_000))));
            // 仅供测试读取原内部字段的反射对象
            var field = TradingCoreRuntime.class.getDeclaredField("matcherPipeline");
            field.setAccessible(true);
            // 本用例原 Matcher 分组，用提交围栏读取工作构造阻塞
            var pipeline = (MatcherPipelineGroup) field.get(service.state());
            // 被门闩挡住的原 Matcher 读取 Future
            var blocked = pipeline.readAtSubmissionFence(0, () -> {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test matcher timeout");
                } catch (/* 当前被注入或捕获的原测试故障，按所在异常边界处理 */ InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(failure);
                }
                return 1;
            });
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            // Owner 原有的有界实时快照请求队列，本测试只排入一项
            var requests = service.snapshotRequests();
            assertThat(requests.offer(new com.surprising.aeron.protocol.RealtimeFrame(ProductLine.SPOT,
                    com.surprising.aeron.protocol.RealtimeFrame.Kind.SNAPSHOT_REQUEST,
                    1001, 0, 0, 0, 91, "", "", new byte[0]))).isTrue();
            // 本测试刻意待处理或随后已完成的原下单消息
            var place = command(CoreMessageType.PLACE_ORDER, 2, 1001,
                    TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(904, "1",
                            CoreOrderSide.BUY, 1_000, 2, false, CoreMarginMode.CROSS,
                            CorePositionSide.NET, CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "progress")));
            // 本次交回调的完整原消息字节
            byte[] encoded = CoreMessageCodec.encode(place);
            service.onSessionMessage(clientSession(responses), 1_000, new UnsafeBuffer(encoded), 0,
                    encoded.length, aeronHeader());
            for (/* 等待原 Matcher 时前台及重入背景探测轮次，原测试做四轮 */ int tick = 0; tick < 4; tick++) {
                service.pollCommands();
                assertThat(service.doBackgroundWork(System.nanoTime())).isZero();
                assertThat(responses).isEmpty();
                assertThat(service.state().realtimeSnapshotPending()).isFalse();
            }
            assertThat(blocked.isDone()).isFalse();
            release.countDown();
            finishCommands(service);
            assertThat(blocked.join()).isEqualTo(1);
            assertThat(responses).hasSize(1);
            service.state().assertClusterCallbackComplete();
            // 本轮跨实时查询推进收集的完整轨迹帧列表
            var snapshotFrames = drainRealtime(outbox);
            // 命令窗口完成后才可派发实时读取，
            // 前面结算未完时的重入背景探测不能提前派发。
            assertThat(requests.isEmpty()).isTrue();
            // 最多两秒推进已派发用户快照读取的单调截止值
            long snapshotDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (service.state().realtimeSnapshotPending() && System.nanoTime() < snapshotDeadline) {
                service.doBackgroundWork(System.nanoTime());
                Thread.yield();
            }
            assertThat(service.state().realtimeSnapshotPending()).isFalse();
            // 实时读取自身也会占用 Lane 邮箱工作，
            // 要等它完成后再检查完整围栏状态，不能只等交易响应。
            assertThat(service.state().tradingState().user(1001).balances().get("USDT").lockedUnits())
                    .isEqualTo(2_000);
            snapshotFrames.addAll(drainRealtime(outbox));
            assertThat(snapshotFrames).anySatisfy(/* 当前断言的实时协议帧 */ frame -> {
                assertThat(frame.kind()).isEqualTo(com.surprising.aeron.protocol.RealtimeFrame.Kind.USER);
                // 原用户 ID，或实时用户帧解码/冷状态查得的原账户视图
                var user = com.surprising.aeron.protocol.CoreStateQueryCodec.decodeUserState(frame.payload());
                assertThat(user.balances().getFirst().lockedUnits()).isEqualTo(2_000);
            });
            try (/* 从本轮原快照恢复的独立业务运行时 */ var restored = TradingCoreRuntime.fromSnapshot(ProductLine.SPOT, service.state().snapshot())) {
                assertThat(restored.tradingState().businessStateHash())
                        .isEqualTo(service.state().tradingState().businessStateHash());
            }
        } finally {
            release.countDown();
            service.onTerminate(null);
        }
    }

    /**
     * 接入极小实时缓存，原余额、挂单、撤单仍完成且资金全释放，即使 outbox 确实丢批也不能留下未结算业务。
     */
    @Test
    void exhaustedRealtimeOutboxDoesNotRejectOrLeaveUnsettledBusinessCommands() {
        // 本用例进程内 Owner 测试辅助，结束时按原生命周期终止
        var service=service();service.onStart(cluster(),null);
        // 本用例接入的原实时字节缓存，部分场景刻意限制容量
        var outbox=new com.surprising.aeron.client.RealtimeOutbox(2,128);CoreFaults.attachRealtime(service, outbox);
        try {
            replayWithoutSession(service,timerInstrument());
            replayWithoutSession(service,command(CoreMessageType.ADJUST_BALANCE,1,1001,
                TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT",10000))));
            replayWithoutSession(service,command(CoreMessageType.PLACE_ORDER,2,1001,
                TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(20000,"1",
                    CoreOrderSide.BUY,1000,2,false,CoreMarginMode.CROSS,CorePositionSide.NET,
                    CoreOrderType.LIMIT,CoreTimeInForce.GTC,false,"overflow-order"))));
            replayWithoutSession(service,command(CoreMessageType.CANCEL_ORDER,3,1001,
                TradingCommandCodec.encodeCancelOrder(new com.surprising.aeron.protocol.CancelOrderCommand(20000))));
            service.state().assertClusterCallbackComplete();
            // 原用户 ID，或实时用户帧解码/冷状态查得的原账户视图
            var user=service.state().tradingState().users().get(1001L);
            assertThat(user.balances().get("USDT").availableUnits()).isEqualTo(10000);
            assertThat(user.balances().get("USDT").lockedUnits()).isZero();
            assertThat(user.reservations()).isEmpty();
            assertThat(outbox.droppedBatches()).isPositive();
        } finally {service.onTerminate(null);}
    }

    /**
     * 检查提交后余额和订单帧、用户快照开始/结束及连续 ordinal；切到 Follower 后原资金命令仍回放，但不再向实时缓存写新输出。
     */
    @Test
    void realtimePublishesCommittedBalancesAndOrdersAndStopsOnFollower() {
        // 本用例进程内 Owner 测试辅助，结束时按原生命周期终止
        var service=service(); service.onStart(cluster(),null);
        // 本用例接入的原实时字节缓存，部分场景刻意限制容量
        var outbox=new com.surprising.aeron.client.RealtimeOutbox(1024,1_048_576);
        CoreFaults.attachRealtime(service, outbox);
        try {
            replayWithoutSession(service,timerInstrument());
            replayWithoutSession(service,command(CoreMessageType.ADJUST_BALANCE,1,1001,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT",10_000))));
            // 当前收集的实时轨迹帧清单
            var frames=drainRealtime(outbox);
            assertThat(frames).anySatisfy(/* 当前断言的实时协议帧 */ frame -> {
                assertThat(frame.kind()).isEqualTo(com.surprising.aeron.protocol.RealtimeFrame.Kind.BALANCE);
                // 从原实时余额帧载荷解码出的用户协议视图
                var view=com.surprising.aeron.protocol.CoreStateQueryCodec.decodeUserState(frame.payload());
                assertThat(view.balances().getFirst().availableUnits()).isEqualTo(10_000);
            });
            replayWithoutSession(service,command(CoreMessageType.PLACE_ORDER,2,1001,
                    TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(20_000,"1",
                    CoreOrderSide.BUY,1_000,2,false,CoreMarginMode.CROSS,CorePositionSide.NET,
                    CoreOrderType.LIMIT,CoreTimeInForce.GTC,false,"push-order"))));
            assertThat(drainRealtime(outbox)).anySatisfy(/* 当前断言的实时协议帧 */ frame -> {
                assertThat(frame.kind()).isEqualTo(com.surprising.aeron.protocol.RealtimeFrame.Kind.ORDER);
                assertThat(frame.userId()).isEqualTo(1001);
            });
            service.state().captureRealtimeSnapshot(1001,19,7,1234);
            // 当前局部等待阶段的单调截止值，按该方法原代码独立或共用
            long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
            while(service.state().pollRealtimeSnapshot()==0 && System.nanoTime()<deadline)Thread.onSpinWait();
            frames=drainRealtime(outbox);
            assertThat(frames.getFirst().kind()).isEqualTo(com.surprising.aeron.protocol.RealtimeFrame.Kind.SNAPSHOT_BEGIN);
            assertThat(frames.getLast().kind()).isEqualTo(com.surprising.aeron.protocol.RealtimeFrame.Kind.SNAPSHOT_END);
            for(/* 用户快照帧列表下标，检查 ordinal 从零连续 */ int n=0;n<frames.size();n++) assertThat(frames.get(n).ordinal()).isEqualTo(n);
            service.onRoleChange(Cluster.Role.FOLLOWER);
            replayWithoutSession(service,command(CoreMessageType.ADJUST_BALANCE,3,1001,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT",1))));
            assertThat(outbox.poll()).isNull();
        } finally {service.onTerminate(null);}
    }

    /**
     * 取走当前全部 outbox 编码字节并解码成轨迹列表，不等待之后可能到来的帧。
     * @param outbox 当前有界实时字节输出缓存
     */
    private static java.util.List<com.surprising.aeron.protocol.RealtimeFrame> drainRealtime(
            com.surprising.aeron.client.RealtimeOutbox outbox) {
        // 当前收集的实时轨迹帧清单
        var frames=new java.util.ArrayList<com.surprising.aeron.protocol.RealtimeFrame>();
        // 当前从 outbox 取出的原编码实时帧
        byte[] bytes; while((bytes=outbox.poll())!=null) frames.add(com.surprising.aeron.protocol.RealtimeFrameCodec.decode(bytes));
        return frames;
    }

    /**
     * Leader/Follower 各重复二百五十六次挂撤单，每次由前台 finishCommands 辅助推进，不依赖 background；检查提交时间、资金释放、两角色哈希及恢复一致。
     */
    @Test
    void followerReplayAndLeaderCompleteEveryCallbackWithoutBackgroundPumps() {
        // 先记录 Leader 业务哈希，随后用作 Follower 同消息回放对照
        long expectedHash = 0;
        for (Cluster.Role /* 当前原 Cluster 角色，先 Leader 再 Follower */ role : new Cluster.Role[]{Cluster.Role.LEADER, Cluster.Role.FOLLOWER}) {
            // 本用例进程内 Owner 测试辅助，结束时按原生命周期终止
            TradingOwnerTestSupport service = service();
            service.onStart(cluster(role), null);
            try {
                replayWithoutSession(service, timerInstrument());
                replayWithoutSession(service, command(CoreMessageType.ADJUST_BALANCE, 1, 1001,
                        TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 10_000))));
                for (/* 二百五十六轮挂单再撤单的当前轮次 */ int cycle = 0; cycle < 256; cycle++) {
                    // 本次构造的原子订单号
                    long orderId = 20_000 + cycle;
                    replayWithoutSession(service, command(CoreMessageType.PLACE_ORDER, 2 + cycle * 2, 1001,
                            TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(orderId, "1",
                                    CoreOrderSide.BUY, 1_000, 2, false, CoreMarginMode.CROSS,
                                    CorePositionSide.NET, CoreOrderType.LIMIT, CoreTimeInForce.GTC,
                                    false, "replay-" + orderId))));
                    assertThat(service.state().tradingState().order(orderId).updatedAtEpochMillis()).isEqualTo(1234);
                    replayWithoutSession(service, command(CoreMessageType.CANCEL_ORDER, 3 + cycle * 2, 1001,
                            TradingCommandCodec.encodeCancelOrder(new com.surprising.aeron.protocol.CancelOrderCommand(orderId))));
                    if (role == Cluster.Role.FOLLOWER) Thread.yield();
                }
                // 原用户已提交 USDT 余额视图，或余额筛选回调的当前资产项
                var balance = service.state().tradingState().users().get(1001L).balances().get("USDT");
                assertThat(balance.availableUnits()).isEqualTo(10_000);
                assertThat(balance.lockedUnits()).isZero();
                assertThat(service.state().tradingState().orders()).isEmpty();
                // 本轮原冷状态业务哈希，用于角色与快照恢复对照
                long hash = service.state().tradingState().businessStateHash();
                if (role == Cluster.Role.LEADER) expectedHash = hash;
                else assertThat(hash).isEqualTo(expectedHash);
                try (/* 从本轮原快照恢复的独立业务运行时 */ TradingCoreRuntime restored = TradingCoreRuntime.fromSnapshot(ProductLine.SPOT, service.captureSnapshot(1000))) {
                    assertThat(restored.tradingState().businessStateHash()).isEqualTo(hash);
                }
                assertThat(service.doBackgroundWork(Long.MAX_VALUE)).isZero();
            } finally {
                service.onTerminate(null);
            }
        }
    }

    /**
     * 用空会话和固定原 Cluster 时间入队，再显式轮询到命令前缀完成并核对回调干净；辅助返回完成不等于底层 onSessionMessage 必然同步完成。
     * @param service 当前进程内 Owner 测试辅助
     * @param message 本次原消息，用空会话辅助回放
     */
    private static void replayWithoutSession(TradingOwnerTestSupport service, CoreMessage message) {
        // 本次交回调的完整原消息字节
        byte[] encoded = CoreMessageCodec.encode(message);
        service.onSessionMessage(null, 1234, new UnsafeBuffer(encoded), 0, encoded.length, aeronHeader());
        finishCommands(service);
        service.state().assertClusterCallbackComplete();
    }

    /**
     * 经无会话辅助显式推进盘口查询，检查其原查询序号已释放且背景无需再做工作；当前辅助含 finishCommands，不单凭测试名推断底层回调同步等待。
     */
    @Test
    void asynchronousBookQueryIsCollectedBeforeCallbackReturnsEvenWithoutSession() {
        // 本用例进程内 Owner 测试辅助，结束时按原生命周期终止
        TradingOwnerTestSupport service = service();
        service.onStart(cluster(), null);
        try {
            replayWithoutSession(service, timerInstrument());
            // 测试阻塞/填满账本的空查询或原异步只读消息
            var query = new CoreMessage(CoreMessageHeader.query(CoreMessageType.BOOK_STATE_QUERY,
                    UUID.randomUUID(), ProductLine.SPOT, CommandSource.GATEWAY, 77, 0, 0, 1000, 9),
                    com.surprising.aeron.protocol.CoreStateQueryCodec.encodeOrderBookQuery(
                            new com.surprising.aeron.protocol.CoreOrderBookQuery("1", 10)));
            replayWithoutSession(service, query);
            assertThat(service.state().bookQueries.querySequence(query.header().commandId())).isZero();
            assertThat(service.doBackgroundWork(0)).isZero();
        } finally {
            service.onTerminate(null);
        }
    }

    /**
     * 模拟会话前两次 offer 背压，前台轮询最终第三次成功；单独背景工作不增加 offer、也不关会话，余额只应用一次。不建立真实网络。
     */
    @Test
    void egressRetriesOnlyInsideLogCallbackAndBackgroundNeverOffersOrCloses() {
        // 本用例进程内 Owner 测试辅助，结束时按原生命周期终止
        TradingOwnerTestSupport service = service();
        // 原会话代理 offer 调用次数，前两次模拟背压
        AtomicInteger offers = new AtomicInteger();
        // 原会话代理 close 调用次数，预期没有关闭
        AtomicInteger closes = new AtomicInteger();
        // 刻意模拟前两次网络背压的原会话代理
        ClientSession session = (ClientSession) Proxy.newProxyInstance(ClientSession.class.getClassLoader(),
                new Class<?>[]{ClientSession.class}, (/* 当前动态代理对象，测试实现不读取其身份 */ proxy, /* 当前被调用的反射方法，按名称选择原代理行为 */ method, /* 背压会话代理收到的原方法参数，本用例不读取 */ args) -> switch (method.getName()) {
                    case "id" -> 91L;
                    case "isClosing" -> false;
                    case "offer" -> offers.incrementAndGet() < 3 ? io.aeron.Publication.BACK_PRESSURED : 1L;
                    case "close" -> { closes.incrementAndGet(); yield null; }
                    default -> defaultValue(method.getReturnType());
                });
        service.onStart(cluster(), null);
        try {
            // 本轮原余额调整消息
            CoreMessage command = command(CoreMessageType.ADJUST_BALANCE, 1, 1001, com.surprising.aeron.protocol.TradingCommandCodec.encodeBalanceAdjustment(new com.surprising.aeron.protocol.BalanceAdjustmentCommand("USDT", 3)));
            // 本次交回调的完整原消息字节
            byte[] encoded = CoreMessageCodec.encode(command);
            service.onSessionMessage(session, 1234, new UnsafeBuffer(encoded), 0, encoded.length, aeronHeader());
            finishCommands(service);
            assertThat(offers.get()).isBetween(1, 2);
            // 前台首次推进后原 offer 次数，背景调用不得增加它
            int before = offers.get();
            service.doBackgroundWork(0);
            assertThat(offers).hasValue(before);
            while (offers.get() < 3) service.pollCommands();
            assertThat(offers).hasValue(3);
            assertThat(closes).hasValue(0);
            assertThat(testBalance(service.state(), 1001)).isEqualTo(3);
            assertThat(service.state().appliedCommandCount()).isEqualTo(1);
        } finally {
            service.onTerminate(null);
        }
    }

    /**
     * 故意留下未提交撮合，再用无会话辅助尝试后续余额命令；要求 Agent 终止而非保存一份可重试业务拒绝，应用数和结果表未接受后条命令。
     */
    @Test
    void unfinishedCallbackIsAnAgentTerminationNotARecoverableBusinessRejection() {
        // 本用例进程内 Owner 测试辅助，结束时按原生命周期终止
        TradingOwnerTestSupport service = service();
        service.onStart(cluster(), null);
        try {
            preparePendingPlace(service.state(), 30_001);
            // 留下未完成撮合后试图提交的后续原余额命令
            var attempted = command(CoreMessageType.ADJUST_BALANCE, 3, 1001,
                    com.surprising.aeron.protocol.TradingCommandCodec.encodeBalanceAdjustment(
                            new com.surprising.aeron.protocol.BalanceAdjustmentCommand("USDT", 1)));
            // 非法后续回调之前原已应用命令数
            long appliedBefore = service.state().appliedCommandCount();
            assertThatThrownBy(() -> replayWithoutSession(service, attempted))
                    .isInstanceOf(org.agrona.concurrent.AgentTerminationException.class)
                    .hasCauseInstanceOf(IllegalStateException.class);
            // 本场景故意保留未完成预留，不能为状态查询取得完成围栏。
            assertThat(service.state().appliedCommandCount()).isEqualTo(appliedBefore);
            assertThat(service.state().resultLedger.get(attempted.header().commandId())).isNull();
        } finally {
            service.onTerminate(null);
        }
    }

    /**
     * 通过带显式完成轮询的会话辅助依次执行指标查询、下单、用户查询及指标查询，检查无残留撮合且每条有响应，不靠背景泵补完成。
     */
    @Test
    void queriesAndFollowingCommandsObserveCompletedLogCallbacks() throws Exception {
        // 本用例进程内 Owner 测试辅助，结束时按原生命周期终止
        TradingOwnerTestSupport service = service();
        // 模拟会话复制出的原响应字节清单，支持工作线程可见性
        List<byte[]> responses = new CopyOnWriteArrayList<>();
        service.onStart(cluster(), null);
        try {
            // 本辅助当前原交易运行时
            TradingCoreRuntime state = service.state();
            assertThat(CoreTestCompletion.applyAsynchronously(state, timerInstrument()).status())
                    .isEqualTo(ResponseStatus.APPLIED);
            assertThat(CoreTestCompletion.applyAsynchronously(state, command(CoreMessageType.ADJUST_BALANCE, 1, 1001,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 10_000))))
                    .status()).isEqualTo(ResponseStatus.APPLIED);
            // 原 Lane 指标只读请求，独立于账户资金变更
            var metrics = new CoreMessage(CoreMessageHeader.query(CoreMessageType.LANE_METRICS_QUERY,
                    UUID.randomUUID(), ProductLine.SPOT, CommandSource.GATEWAY, 77, 0, 0, 1_000, 8), new byte[0]);
            onSessionMessage(service, responses, metrics);
            assertThat(responses).hasSize(1);
            // 排在原指标查询后的一份普通下单请求
            CoreMessage later = command(CoreMessageType.PLACE_ORDER, 3, 1001,
                    TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(18_002, "1",
                            CoreOrderSide.BUY, 1_000, 2, false, CoreMarginMode.CROSS,
                            CorePositionSide.NET, CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "later")));
            onSessionMessage(service, responses, later);
            assertThat(state.matchingSequence(later.header().commandId())).isZero();
            assertThat(state.pendingMatching().size()).isZero();
            assertThat(responses).hasSize(2);

            // 测试阻塞/填满账本的空查询或原异步只读消息
            var query = new CoreMessage(CoreMessageHeader.query(CoreMessageType.USER_STATE_QUERY,
                    UUID.randomUUID(), ProductLine.SPOT, CommandSource.GATEWAY, 77, 0, 1001, 1_000, 9), new byte[0]);
            onSessionMessage(service, responses, query);
            onSessionMessage(service, responses, metrics);
            assertThat(responses).hasSize(4);
            assertThat(service.doBackgroundWork(0)).isZero();
        } finally {
            service.onTerminate(null);
        }
    }

    /**
     * 在测试线程创建 Owner 辅助，在另一平台线程启动、执行资金命令并终止，检查所有权可交给实际使用线程且应用成功；join 未另设超时。
     */
    @Test
    void handsRuntimeOwnershipFromConstructionThreadToClusterServiceThread() throws Exception {
        // 本用例进程内 Owner 测试辅助，结束时按原生命周期终止
        TradingOwnerTestSupport service = service();
        // 另一启动线程发布原异常的原子槽
        AtomicReference<Throwable> failure = new AtomicReference<>();
        // 另一启动线程发布原资金终态的原子槽
        AtomicReference<CoreResponse> response = new AtomicReference<>();
        // 与辅助构造线程不同、实际启动和使用 Owner 的原平台线程
        Thread serviceThread = new Thread(() -> {
            try {
                service.onStart(cluster(), null);
                response.set(CoreTestCompletion.applyAsynchronously(service.state(), command(CoreMessageType.ADJUST_BALANCE, 1, 1001,
                        com.surprising.aeron.protocol.TradingCommandCodec.encodeBalanceAdjustment(new com.surprising.aeron.protocol.BalanceAdjustmentCommand("USDT", 1)))));
            } catch (/* 另一启动线程捕获的原执行或启动异常 */ Throwable throwable) {
                failure.set(throwable);
            } finally {
                service.onTerminate(null);
            }
        }, "clustered-service-owner-test");

        serviceThread.start();
        serviceThread.join();

        assertThat(failure.get()).isNull();
        assertThat(response.get()).isNotNull();
        assertThat(response.get().status()).isEqualTo(ResponseStatus.APPLIED);
    }

    /**
     * 先由会话辅助完成原下单，再调用已记录计时器，检查无多余响应或残留撮合且订单仍 OPEN；当前场景不证明订单只靠计时器才首次完成。
     */
    @Test
    void loggedTimerCompletesMatchingExactlyOnceWithoutBackgroundWork() {
        // 本用例进程内 Owner 测试辅助，结束时按原生命周期终止
        TradingOwnerTestSupport service = service();
        // 模拟会话复制出的原响应字节清单，支持工作线程可见性
        List<byte[]> responses = new CopyOnWriteArrayList<>();
        service.onStart(cluster(), null);
        try {
            assertThat(CoreTestCompletion.applyAsynchronously(service.state(), timerInstrument()).status())
                    .isEqualTo(ResponseStatus.APPLIED);
            assertThat(CoreTestCompletion.applyAsynchronously(service.state(), command(CoreMessageType.ADJUST_BALANCE, 1, 1001,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 10_000))))
                    .status()).isEqualTo(ResponseStatus.APPLIED);
            // 本测试刻意待处理或随后已完成的原下单消息
            CoreMessage place = command(CoreMessageType.PLACE_ORDER, 2, 1001,
                    TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(904, "1",
                            CoreOrderSide.BUY, 1_000, 2, false, CoreMarginMode.CROSS,
                            CorePositionSide.NET, CoreOrderType.LIMIT, CoreTimeInForce.GTC,
                            false, "same-callback")));

            onSessionMessage(service, responses, place);
            service.onTimerEvent(service.state().appliedCommandCount(), 1_001);
            assertThat(service.doBackgroundWork(0)).isZero();

            assertThat(service.state().pendingMatching().size()).isZero();
            assertThat(responses).hasSize(1);
            assertThat(CoreTestCompletion.orderState(service.state(), 1001, 904).status())
                    .isEqualTo("OPEN");
        } finally {
            service.onTerminate(null);
        }
    }

    /**
     * 用会话辅助完成原下单后再入后续余额命令，检查两响应且无撮合在途；原订单已 OPEN，背景不再提交额外工作。
     */
    @Test
    void logCallbackCommitsMatchingBeforeTheFollowingCommand() throws Exception {
        // 本用例进程内 Owner 测试辅助，结束时按原生命周期终止
        TradingOwnerTestSupport service = service();
        // 模拟会话复制出的原响应字节清单，支持工作线程可见性
        List<byte[]> responses = new CopyOnWriteArrayList<>();
        service.onStart(cluster(), null);
        try {
            // 本辅助当前原交易运行时
            TradingCoreRuntime state = service.state();
            assertThat(CoreTestCompletion.applyAsynchronously(state, timerInstrument()).status()).isEqualTo(ResponseStatus.APPLIED);
            assertThat(CoreTestCompletion.applyAsynchronously(state, command(CoreMessageType.ADJUST_BALANCE, 1, 1001,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 10_000)),
                    UUID.fromString("00000000-0000-0000-0000-000000000012"))).status())
                    .isEqualTo(ResponseStatus.APPLIED);
            // 本测试刻意待处理或随后已完成的原下单消息
            CoreMessage place = command(CoreMessageType.PLACE_ORDER, 2, 1001,
                    TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(906, "1", CoreOrderSide.BUY, 1_000, 2, false, CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "session-fence")),
                    UUID.fromString("00000000-0000-0000-0000-000000000013"));
            onSessionMessage(service, responses, place);
            assertThat(state.pendingMatching().size()).isZero();
            assertThat(responses).hasSize(1);

            onSessionMessage(service, responses, command(CoreMessageType.ADJUST_BALANCE, 3, 1001,
                    com.surprising.aeron.protocol.TradingCommandCodec.encodeBalanceAdjustment(new com.surprising.aeron.protocol.BalanceAdjustmentCommand("USDT", 1)),
                    UUID.fromString("00000000-0000-0000-0000-000000000014")));
            assertThat(service.doBackgroundWork(0)).isZero();

            assertThat(state.pendingMatching().size()).isZero();
            assertThat(CoreTestCompletion.orderState(state, 1001, 906).status())
                    .isEqualTo("OPEN");
            assertThat(responses).hasSize(2);
        } finally {
            service.onTerminate(null);
        }
    }

    /**
     * 把原快照每次只交一字节给恢复缓冲，完整后检查目标余额和业务哈希与源一致，所有字节恰被消费。
     */
    @Test
    void loadsOneByteSnapshotFragmentsThroughBoundedSectionRecovery() {
        // 提供原恢复快照的 Owner 测试辅助
        TradingOwnerTestSupport source = service();
        // 从原片段来源读取快照的目标 Owner 辅助
        TradingOwnerTestSupport target = service();
        try {
            source.onStart(cluster(), null);
            assertThat(CoreTestCompletion.applyAsynchronously(source.state(), command(CoreMessageType.ADJUST_BALANCE, 1, 1001,
                    com.surprising.aeron.protocol.TradingCommandCodec.encodeBalanceAdjustment(new com.surprising.aeron.protocol.BalanceAdjustmentCommand("USDT", 9)))).status()).isEqualTo(ResponseStatus.APPLIED);
            // 本次完整原快照字节
            byte[] snapshot = source.state().snapshot(45);
            // 片段当前发送偏移，代理会话中为响应字节起点
            AtomicInteger offset = new AtomicInteger();
            // 提供原快照片段或会话响应字节的输入缓冲
            UnsafeBuffer buffer = new UnsafeBuffer(snapshot);
            // 每次只交一字节的原快照片段读取器
            TradingOwnerTestSupport.SnapshotFragmentSource fragments = (/* 原恢复缓冲提供的片段接收回调 */ handler, /* 原片段轮询预算，单字节夹具每次只发送一片 */ fragmentLimit) -> {
                if (offset.get() == snapshot.length) return 0;
                handler.onFragment(buffer, offset.getAndIncrement(), 1, null);
                return 1;
            };
            target.onStart(cluster(), null);

            target.loadSnapshot(fragments, () -> offset.get() == snapshot.length);

            assertThat(testBalance(target.state(), 1001)).isEqualTo(9);
            assertThat(target.state().stateHash()).isEqualTo(source.state().stateHash());
            assertThat(offset).hasValue(snapshot.length);
        } finally {
            source.onTerminate(null);
            target.onTerminate(null);
        }
    }

    /**
     * 空输入或截去末字节的片段源都拒绝，且旧运行时引用保持不变。
     */
    @Test
    void emptyAndIncompleteFragmentSourcesFailBeforeStateReplacement() {
        // 本用例进程内 Owner 测试辅助，结束时按原生命周期终止
        TradingOwnerTestSupport service = service();
        try {
            service.onStart(cluster(), null);
            // 本次恢复尝试前的原运行时引用，失败前不得替换
            TradingCoreRuntime before = service.state();
            assertThatThrownBy(() -> service.loadSnapshot((/* 原恢复缓冲提供的片段接收回调 */ handler, /* 空或截断片段源的原轮询预算，本故障夹具不依它继续读取 */ limit) -> 0, () -> true))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("incomplete");
            assertThat(service.state()).isSameAs(before);

            // 本次完整原快照字节
            byte[] snapshot = before.snapshot(46);
            // 原完整快照去掉最后一字节的故障输入
            byte[] truncated = Arrays.copyOf(snapshot, snapshot.length - 1);
            // 截断输入是否已交过一次的原子计数
            AtomicInteger delivered = new AtomicInteger();
            assertThatThrownBy(() -> service.loadSnapshot((/* 原恢复缓冲提供的片段接收回调 */ handler, /* 空或截断片段源的原轮询预算，本故障夹具不依它继续读取 */ limit) -> {
                if (delivered.getAndIncrement() > 0) return 0;
                handler.onFragment(new UnsafeBuffer(truncated), 0, truncated.length, null);
                return 1;
            }, () -> delivered.get() > 0))
                    .isInstanceOf(ProtocolException.class);
            assertThat(service.state()).isSameAs(before);
        } finally {
            service.onTerminate(null);
        }
    }

    /**
     * 允许原快照上限恰好最后一字节，超过一字节时明确拒绝，不尝试真的分配大数组。
     */
    @Test
    void rejectsSnapshotFragmentsBeyondBoundedRecoveryBuffer() {
        TradingOwnerTestSupport.ensureSnapshotCapacity(SectionedCoreSnapshotCodec.HARD_MAX_SNAPSHOT_BYTES - 1, 1);

        assertThatThrownBy(() -> TradingOwnerTestSupport.ensureSnapshotCapacity(
                SectionedCoreSnapshotCodec.HARD_MAX_SNAPSHOT_BYTES - 1, 2))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Aeron core snapshot exceeds maximum size");
    }

    /**
     * 破坏原快照中间一字节，恢复 CRC 拒绝且原运行时引用不变。
     */
    @Test
    void doesNotReplaceStateAfterCorruptSnapshot() {
        // 本用例进程内 Owner 测试辅助，结束时按原生命周期终止
        TradingOwnerTestSupport service = service();
        try {
            service.onStart(cluster(), null);
            // 本次恢复尝试前的原运行时引用，失败前不得替换
            TradingCoreRuntime before = service.state();
            // 本次完整原快照字节
            byte[] snapshot = before.snapshot();
            snapshot[snapshot.length / 2] ^= 1;

            assertThatThrownBy(() -> service.restoreSnapshot(snapshot))
                    .isInstanceOf(ProtocolException.class)
                    .hasMessageContaining("checksum");
            assertThat(service.state()).isSameAs(before);
        } finally {
            service.onTerminate(null);
        }
    }

    /**
     * 按当前快照格式修改 manifest 快照身份并维护外 CRC，让恢复在配对身份校验拒绝，原引用、业务哈希和命令数都不变。
     */
    @Test
    void pairedManifestMismatchFailsBeforeLiveStateReplacement() {
        // 本用例进程内 Owner 测试辅助，结束时按原生命周期终止
        TradingOwnerTestSupport service = service();
        try {
            service.onStart(cluster(), null);
            // 本次恢复尝试前的原运行时引用，失败前不得替换
            TradingCoreRuntime before = service.state();
            assertThat(CoreTestCompletion.applyAsynchronously(before, command(CoreMessageType.ADJUST_BALANCE, 1, 1001,
                    com.surprising.aeron.protocol.TradingCommandCodec.encodeBalanceAdjustment(new com.surprising.aeron.protocol.BalanceAdjustmentCommand("USDT", 9)))).status()).isEqualTo(ResponseStatus.APPLIED);
            // 坏 manifest 恢复尝试前原业务哈希
            long beforeHash = before.stateHash();
            // 坏 manifest 恢复尝试前原应用命令计数
            long beforeSequence = before.appliedCommandCount();
            // 本次完整原快照字节
            byte[] snapshot = before.snapshot(75);
            // 按现有快照结构进行小端故障修改的缓冲
            ByteBuffer manifest = ByteBuffer.wrap(snapshot).order(ByteOrder.LITTLE_ENDIAN);
            // 当前格式 manifest 快照 ID 所在固定绝对偏移
            int snapshotIdOffset = SectionedCoreSnapshotCodec.ENVELOPE_LENGTH
                    + SectionedCoreSnapshotCodec.SECTION_HEADER_LENGTH + 45;
            manifest.putLong(snapshotIdOffset, manifest.getLong(snapshotIdOffset) + 1);
            // 维护故障快照外层 CRC32C，使恢复必须检查内部配对身份
            CRC32C checksum = new CRC32C();
            checksum.update(snapshot, 0, snapshot.length - 16);
            manifest.putLong(snapshot.length - Long.BYTES, checksum.getValue());

            assertThatThrownBy(() -> service.restoreSnapshot(snapshot))
                    .isInstanceOf(ProtocolException.class)
                    .hasMessageContaining("snapshot id");
            assertThat(service.state()).isSameAs(before);
            assertThat(service.state().stateHash()).isEqualTo(beforeHash);
            assertThat(service.state().appliedCommandCount()).isEqualTo(beforeSequence);
        } finally {
            service.onTerminate(null);
        }
    }

    /**
     * 给原在途命令提供明确 Matcher 失败结果以产生致命分歧，再取快照必须继续抛同一故障，不能变成普通业务拒绝。
     */
    @Test
    void propagatesFatalMatcherDivergenceFromSnapshotCallback() {
        // 本用例进程内 Owner 测试辅助，结束时按原生命周期终止
        TradingOwnerTestSupport service = service();
        service.onStart(cluster(), null);
        try {
            // 本辅助当前原交易运行时
            TradingCoreRuntime state = service.state();
            // 本用例后续可用原来源序号，发送消息时递增
            long sequence = preparePendingPlace(state, 901);
            // 为原撮合构造的明确 exchange-core 失败结果
            var matcherFailure = new com.surprising.aeron.service.matcher.CoreMatchingResult(
                    false, "EXCHANGE_CORE_FAILURE");
            // 原提交边界检测出的致命撮合分歧，之后快照继续抛同一故障
            Throwable fatal = catchThrowable(() -> state.completeMatching(sequence, matcherFailure, 2_000, 3));

            assertThat(fatal).isInstanceOf(
                    FatalMatchingDivergenceException.class);
            assertThatThrownBy(() -> service.onTakeSnapshot(null)).isSameAs(fatal);
        } finally {
            service.onTerminate(null);
        }
    }

    /**
     * 代理记录任何计时器调度尝试，留下原在途命令后调用会话打开及背景工作，检查两者都没有擅自安排进度计时器。
     */
    @Test
    void loggedSessionOpenAndBackgroundNeverScheduleProgressTimers() {
        // 本用例进程内 Owner 测试辅助，结束时按原生命周期终止
        TradingOwnerTestSupport service = service();
        // 当前代理记录的原计时器调度次数
        AtomicInteger attempts = new AtomicInteger();
        // 当前代理记录的原计时器请求 ID
        AtomicLong correlationId = new AtomicLong();
        // 模拟会话复制出的原响应字节清单，支持工作线程可见性
        List<byte[]> responses = new CopyOnWriteArrayList<>();
        try {
            service.onStart(clusterWithTimerBackpressure(attempts, correlationId), null);
            preparePendingPlace(service.state(), 902);
            service.onSessionOpen(clientSession(responses), 1_000);

            assertThat(attempts).hasValue(0);
            assertThat(correlationId).hasValue(0);
            service.doBackgroundWork(0);
            assertThat(attempts).hasValue(0);
        } finally {
            service.onTerminate(null);
        }
    }

    /**
     * Follower 已取得原 Matcher 结果但未提交时，收到历史记录计时器也不能自行合成超时终态，原在途槽仍保留。
     */
    @Test
    void followerDoesNotSynthesizeHistoricalMatcherTimeoutDuringReplay() {
        // 本用例进程内 Owner 测试辅助，结束时按原生命周期终止
        TradingOwnerTestSupport service = service();
        service.onStart(cluster(Cluster.Role.FOLLOWER), null);
        try {
            // 本用例后续可用原来源序号，发送消息时递增
            long sequence = preparePendingPlace(service.state(), 905);
            assertThat(CoreTestCompletion.awaitMatchingResult(service.state(), sequence,
                    TimeUnit.SECONDS.toNanos(5))).isNotNull();

            service.onTimerEvent(Long.MAX_VALUE - 1, 31_000);

            assertThat(service.state().pendingMatching().get(sequence)).isNotNull();
        } finally {
            service.onTerminate(null);
        }
    }

    /**
     * 空业务快照捕获成功后仍能继续应用余额命令，检查原快照边界解除后准入可用。
     */
    @Test
    void snapshotWaitsForAsynchronousMatcherCaptureAndReleasesCommandAdmission() {
        // 本用例进程内 Owner 测试辅助，结束时按原生命周期终止
        TradingOwnerTestSupport service = service();
        service.onStart(cluster(), null);
        try {
            assertThat(service.captureSnapshot(7)).isNotEmpty();
            assertThat(CoreTestCompletion.applyAsynchronously(service.state(), command(CoreMessageType.ADJUST_BALANCE, 1, 1001,
                    com.surprising.aeron.protocol.TradingCommandCodec.encodeBalanceAdjustment(new com.surprising.aeron.protocol.BalanceAdjustmentCommand("USDT", 7)))).status()).isEqualTo(ResponseStatus.APPLIED);
        } finally {
            service.onTerminate(null);
        }
    }

    /**
     * 给出立即到期截止值使快照围栏明确超时，再用下一快照正常捕获，检查超时后没有留下永久阻塞准入。
     */
    @Test
    void snapshotCaptureTimeoutIsFailClosed() {
        // 本用例进程内 Owner 测试辅助，结束时按原生命周期终止
        TradingOwnerTestSupport service = service();
        service.onStart(cluster(), null);
        try {
            assertThatThrownBy(() -> service.captureSnapshot(9, System.nanoTime()))
                    .isInstanceOf(TradingCoreRuntime.SnapshotFenceTimeoutException.class)
                    .hasMessage("snapshot fence timed out");
            assertThat(service.captureSnapshot(10)).isNotEmpty();
        } finally {
            service.onTerminate(null);
        }
    }

    /**
     * 故意只完成准入并留下原撮合，取结果后按需放回完成；背景不提交，快照明确拒绝未结束业务，原在途项仍存在。
     */
    @Test
    void backgroundAndSnapshotCannotCommitWorkOutsideALogCallback() {
        // 准备原场景
        // 本用例进程内 Owner 测试辅助，结束时按原生命周期终止
        TradingOwnerTestSupport service = service();
        service.onStart(cluster(), null);
        try {
            // 故意留下未提交撮合的原 Core 序号
            long pendingSequence = preparePendingPlace(service.state(), 903);
            // 已完成但尚未提交的原 Matcher 结果
            var matchingResult = awaitMatching(service.state(), pendingSequence);
            assertThat(matchingResult).isNotNull();
            if (matchingResult instanceof com.surprising.aeron.service.matcher.CoreMatchingResult /* 类型匹配成功的原生 CoreMatchingResult，放回既有完成入口 */ core) {
                service.state().matchingFlow.publishMatchingCompletion(pendingSequence, core);
            }

            // 执行本次边界动作
            assertThat(service.doBackgroundWork(0)).isZero();
            assertThatThrownBy(() -> service.captureSnapshot(8))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageStartingWith("unfinished business work outside cluster log callback");

            // 核对原状态和结果
            assertThat(service.state().pendingMatching().size()).isOne();
            assertThat(service.state().pendingMatching().get(pendingSequence)).isNotNull();
        } finally {
            service.onTerminate(null);
        }
    }

    /**
     * 原余额七的成功快照直接恢复后替换运行时对象，业务余额及应用计数保持；无需另加延时轮询等候恢复结果。
     */
    @Test
    void restoresSuccessfulSnapshotRoundTripWithoutTimingPoll() {
        // 准备原场景
        // 本用例进程内 Owner 测试辅助，结束时按原生命周期终止
        TradingOwnerTestSupport service = service();
        try {
            service.onStart(cluster(), null);
            // 本次恢复尝试前的原运行时引用，失败前不得替换
            TradingCoreRuntime before = service.state();
            assertThat(CoreTestCompletion.applyAsynchronously(before, command(CoreMessageType.ADJUST_BALANCE, 1, 1001,
                    com.surprising.aeron.protocol.TradingCommandCodec.encodeBalanceAdjustment(new com.surprising.aeron.protocol.BalanceAdjustmentCommand("USDT", 7)))).status()).isEqualTo(ResponseStatus.APPLIED);
            // 本次完整原快照字节
            byte[] snapshot = before.snapshot(11);

            // 执行本次边界动作
            service.restoreSnapshot(snapshot);

            // 核对原状态和结果
            assertThat(service.state()).isNotSameAs(before);
            assertThat(testBalance(service.state(), 1001)).isEqualTo(7);
            assertThat(service.state().appliedCommandCount()).isEqualTo(1);
        } finally {
            service.onTerminate(null);
        }
    }

    /**
     * 准备现货合约与一万余额，提交原挂单后最多五秒只完成准入，刻意保留 MATCHING_PENDING 及正 Core 序号供故障边界测试。
     * @param state 当前原交易运行时
     * @param orderId 刻意留下未提交撮合的原订单号
     */
    private static long preparePendingPlace(TradingCoreRuntime state, long orderId) {
        assertThat(CoreTestCompletion.applyAsynchronously(state, instrument()).status()).isEqualTo(ResponseStatus.APPLIED);
        assertThat(CoreTestCompletion.applyAsynchronously(state, command(CoreMessageType.ADJUST_BALANCE, 1, 1001,
                TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 10_000))))
                .status()).isEqualTo(ResponseStatus.APPLIED);
        // 本次待撮合原下单的 UUID
        UUID commandId = UUID.randomUUID();
        // 本测试刻意待处理或随后已完成的原下单消息
        CoreMessage place = command(CoreMessageType.PLACE_ORDER, 2, 1001,
                TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(orderId, "1", CoreOrderSide.BUY, 1_000, 2, false, CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "service-" + orderId)), commandId);
        // 本轮原业务响应或已编码响应消息，用来核对身份与终态
        CoreResponse response = state.apply(place);
        // 当前局部等待阶段的单调截止值，按该方法原代码独立或共用
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        // 这里只完成准入，这些场景故意把原撮合留在尚未提交的状态。
        while (state.directCommand.active()) {
            response = state.directCommand.poll();
            state.assertHealthy();
            if (System.nanoTime() >= deadline) throw new AssertionError("place admission timeout");
            Thread.onSpinWait();
        }
        assertThat(response).isNotNull();
        assertThat(response.resultCode()).isEqualTo(CoreResultCode.MATCHING_PENDING);
        // 本用例后续可用原来源序号，发送消息时递增
        long sequence = state.matchingSequence(commandId);
        assertThat(sequence).isPositive();
        return sequence;
    }

    /**
     * 返回固定会话九十一的代理，只在三参数 offer 中拷贝响应字节到原清单并报告成功，其余方法给默认值。
     * @param responses 接收模拟会话复制响应字节的原清单
     */
    private static ClientSession clientSession(List<byte[]> responses) {
        return (ClientSession) Proxy.newProxyInstance(ClientSession.class.getClassLoader(),
                new Class<?>[]{ClientSession.class}, (/* 当前动态代理对象，测试实现不读取其身份 */ proxy, /* 当前被调用的反射方法，按名称选择原代理行为 */ method, /* 当前动态代理收到的原实参数组 */ arguments) -> switch (method.getName()) {
                    case "id" -> 91L;
                    case "isClosing" -> false;
                    case "offer" -> {
                        if (arguments.length == 3) {
                            // 提供原快照片段或会话响应字节的输入缓冲
                            var buffer = (org.agrona.DirectBuffer) arguments[0];
                            // 片段当前发送偏移，代理会话中为响应字节起点
                            int offset = (int) arguments[1];
                            // 本次会话原响应字节长度
                            int length = (int) arguments[2];
                            // 本轮原业务响应或已编码响应消息，用来核对身份与终态
                            byte[] response = new byte[length];
                            buffer.getBytes(offset, response);
                            responses.add(response);
                        }
                        yield 1L;
                    }
                    default -> defaultValue(method.getReturnType());
                });
    }

    /**
     * 编码原请求经会话回调入队，然后明确调用 finishCommands 等前缀完成；测试调用方读到的是这个辅助的完成边界。
     * @param service 当前进程内 Owner 测试辅助
     * @param responses 接收模拟会话复制响应字节的原清单
     * @param request 待编码交会话回调的原请求
     */
    private static void onSessionMessage(
            TradingOwnerTestSupport service, List<byte[]> responses, CoreMessage request) {
        // 本次交回调的完整原消息字节
        byte[] encoded = CoreMessageCodec.encode(request);
        service.onSessionMessage(clientSession(responses), 1_000, new UnsafeBuffer(encoded), 0,
                encoded.length, aeronHeader());
        finishCommands(service);
    }

    /**
     * 最多五秒显式轮询 Owner，无待命令即返回，未完成短暂 park 后再试，超时附上原停滞诊断。
     * @param service 当前进程内 Owner 测试辅助
     */
    private static void finishCommands(TradingOwnerTestSupport service) {
        // 当前局部等待阶段的单调截止值，按该方法原代码独立或共用
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        do {
            service.pollCommands();
            if (service.pendingCommandCount() == 0) return;
            java.util.concurrent.locks.LockSupport.parkNanos(100_000);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("asynchronous command completion timeout: " + service.stalledCommandDiagnostics());
    }

    /**
     * 构造固定 term、偏移及缓冲的测试 Aeron Header，不连接真实媒体驱动。
     */
    private static Header aeronHeader() {
        return new Header(0, 0).buffer(new UnsafeBuffer(new byte[64])).offset(0)
                .initialTermId(0).positionBitsToShift(16);
    }

    /**
     * 构造固定 UUID 的现货标的一注册消息，供日志/计时器场景复用。
     */
    private static CoreMessage timerInstrument() {
        // 本夹具构造的现货标的一注册配置
        RegisterInstrumentCommand instrument = new RegisterInstrumentCommand("1", "BTC", "USDT", "USDT", 1, 1, 1,
                100_000, 50_000, 0, 0, 0, -1, 0);
        return new CoreMessage(CoreMessageHeader.command(CoreMessageType.REGISTER_INSTRUMENT,
                UUID.fromString("00000000-0000-0000-0000-000000000001"), ProductLine.SPOT,
                CommandSource.OPERATIONS, 88, 1, 0, 1_000, 1),
                TradingCommandCodec.encodeRegisterInstrument(instrument));
    }

    /**
     * 构造随机 UUID 的同类型现货合约注册消息，供待撮合场景独立准备。
     */
    private static CoreMessage instrument() {
        // 本夹具构造的现货标的一注册配置
        RegisterInstrumentCommand instrument = new RegisterInstrumentCommand("1", "BTC", "USDT", "USDT", 1, 1, 1,
                100_000, 50_000, 0, 0, 0, -1, 0);
        return new CoreMessage(CoreMessageHeader.command(CoreMessageType.REGISTER_INSTRUMENT,
                UUID.randomUUID(), ProductLine.SPOT, CommandSource.OPERATIONS, 88, 1, 0, 1_000, 1),
                TradingCommandCodec.encodeRegisterInstrument(instrument));
    }

    /**
     * 用随机 UUID 构造固定现货产品线、网关来源的原命令。
     * @param type 本条原协议消息类型
     * @param sequence 本条原来源顺序号，亦参与 UUID 或关联号
     * @param userId 原业务用户 ID
     * @param payload 本条原消息已编码载荷
     */
    private static CoreMessage command(CoreMessageType type, long sequence, long userId, byte[] payload) {
        return command(type, sequence, userId, payload, UUID.randomUUID());
    }

    /**
     * 使用指定 UUID、来源序号及原用户构造现货网关命令，时间固定一千。
     * @param type 本条原协议消息类型
     * @param sequence 本条原来源顺序号，亦参与 UUID 或关联号
     * @param userId 原业务用户 ID
     * @param payload 本条原消息已编码载荷
     * @param commandId 本条原命令幂等 UUID
     */
    private static CoreMessage command(
            CoreMessageType type,
            long sequence,
            long userId,
            byte[] payload,
            UUID commandId) {
        return new CoreMessage(CoreMessageHeader.command(type, commandId, ProductLine.SPOT,
                CommandSource.GATEWAY, 77, sequence, userId, 1_000, sequence), payload);
    }

    /**
     * 构造 Leader 角色的进程内 Cluster 代理。
     */
    private static Cluster cluster() {
        return cluster(Cluster.Role.LEADER);
    }

    /**
     * 创建现货产品线 Owner 测试辅助，尚未启动。
     */
    private static TradingOwnerTestSupport service() {
        return new TradingOwnerTestSupport(ProductLine.SPOT);
    }

    /**
     * 构造指定角色的 Cluster 代理，提供固定日志位置、毫秒时间和无等待策略，其余方法按返回类型给默认值。
     * @param role 本轮代理的 Cluster 角色
     */
    private static Cluster cluster(Cluster.Role role) {
        return (Cluster) Proxy.newProxyInstance(Cluster.class.getClassLoader(), new Class<?>[]{Cluster.class},
                (/* 当前动态代理对象，测试实现不读取其身份 */ proxy, /* 当前被调用的反射方法，按名称选择原代理行为 */ method, /* 当前动态代理收到的原实参数组 */ arguments) -> switch (method.getName()) {
                    case "role" -> role;
                    case "logPosition" -> 7L;
                    case "idleStrategy" -> NoOpIdleStrategy.INSTANCE;
                    case "timeUnit" -> TimeUnit.MILLISECONDS;
                    case "time" -> 1_000L;
                    case "scheduleTimer" -> true;
                    default -> defaultValue(method.getReturnType());
                });
    }

    /**
     * 最多五秒轮询指定原撮合结果，不自行提交资金或交出终态；超时返回空，由调用方断言。
     * @param state 当前原交易运行时
     * @param sequence 本条原来源顺序号，亦参与 UUID 或关联号
     */
    private static com.surprising.aeron.service.matcher.MatchingResult awaitMatching(
            TradingCoreRuntime state, long sequence) {
        // 本轮原业务、Matcher 控制或撮合结果，未就绪可为空
        com.surprising.aeron.service.matcher.MatchingResult result = null;
        // 当前局部等待阶段的单调截止值，按该方法原代码独立或共用
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (result == null && System.nanoTime() < deadline) {
            result = state.takeMatchingResult(sequence);
            if (result == null) Thread.onSpinWait();
        }
        return result;
    }

    /**
     * 提供可记录计时器 ID 和尝试次数的 Cluster 代理，前两次 scheduleTimer 返回假、第三次起真；测试用它确认相关路径根本没有调度尝试。
     * @param attempts 记录原模拟计时器调度调用次数的原子计数
     * @param correlationId 记录原模拟计时器调度身份的原子槽
     */
    private static Cluster clusterWithTimerBackpressure(AtomicInteger attempts, AtomicLong correlationId) {
        return (Cluster) Proxy.newProxyInstance(Cluster.class.getClassLoader(), new Class<?>[]{Cluster.class},
                (/* 当前动态代理对象，测试实现不读取其身份 */ proxy, /* 当前被调用的反射方法，按名称选择原代理行为 */ method, /* 当前动态代理收到的原实参数组 */ arguments) -> switch (method.getName()) {
                    case "role" -> Cluster.Role.LEADER;
                    case "logPosition" -> 7L;
                    case "idleStrategy" -> NoOpIdleStrategy.INSTANCE;
                    case "timeUnit" -> TimeUnit.MILLISECONDS;
                    case "time" -> 1_000L;
                    case "scheduleTimer" -> {
                        correlationId.set((long) arguments[0]);
                        yield attempts.incrementAndGet() >= 3;
                    }
                    default -> defaultValue(method.getReturnType());
                });
    }

    /**
     * 按代理方法声明返回类型给引用空值或 primitive 零值，不模拟真实 Cluster 行为。
     * @param type 代理方法声明的返回类型
     */
    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        if (type == double.class) return 0D;
        if (type == char.class) return '\0';
        return null;
    }
    /**
     * 从原已提交冷状态查指定用户 USDT 总额，无用户返回零。
     * @param state 当前原交易运行时
     * @param userId 原业务用户 ID
     */
    private static long testBalance(TradingCoreRuntime state, long userId) {
        // 原用户 ID，或实时用户帧解码/冷状态查得的原账户视图
        var user = state.tradingState().user(userId);
        return user == null ? 0 : user.totalUnits("USDT");
    }

    private static byte[] encodeAlgo(com.surprising.aeron.protocol.CoreAlgoOrderView value, long appendedChild) {
        com.surprising.aeron.protocol.CoreAlgoOrderCommand command = value.revision() == 1
                ? new com.surprising.aeron.protocol.CoreAlgoOrderCommand.Create(
                value.algoOrderId(), value.clientAlgoOrderId(), value.instrumentId(), value.algoTypeCode(),
                        value.side(), value.priceTicks(), value.quantitySteps(), value.childQuantitySteps(),
                        value.intervalSeconds(), value.durationSeconds(), value.marginMode(), value.positionSide(),
                        value.reduceOnly(), value.postOnly(), value.timeInForce(), value.startAtEpochMillis())
                : new com.surprising.aeron.protocol.CoreAlgoOrderCommand.Update(
                value.algoOrderId(), value.revision() - 1, value.statusCode(), value.currentOrderId(),
                        value.rejectReason(), value.nextSliceAtEpochMillis(), value.completedAtEpochMillis(),
                        value.updatedAtEpochMillis(), appendedChild);
        return com.surprising.aeron.protocol.CoreAlgoOrderCodec.encodeCommand(command);
    }
}
