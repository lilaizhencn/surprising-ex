package com.surprising.aeron.service.settlement;

import com.surprising.aeron.config.LaneTopology;
import com.surprising.aeron.protocol.*;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreOrderType;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreRiskLimitBracket;
import com.surprising.aeron.protocol.CoreTimeInForce;
import com.surprising.aeron.service.core.TradingAccountRuntime;
import com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.aeron.service.matcher.CoreMatchingResult;
import com.surprising.aeron.service.matcher.MatcherEventFixtures;
import com.surprising.aeron.service.order.state.OrderRuntime;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import exchange.core2.core.common.MatcherResult;
import exchange.core2.core.common.OrderAction;
import exchange.core2.core.common.OrderType;
import exchange.core2.core.common.cmd.CommandResultCode;
import exchange.core2.core.common.cmd.OrderCommandType;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * 撮合结算计划和事件租约的回归用例。覆盖发布预约、就绪与回收、Lane 票据、跨项成交数量、计划数组及视图复用；有的用例使用实际线程，有的直接构造结果，不代表完整 Cluster 或性能采样。
 */
class MatcherSettlementPlanTest {
    /**
     * 检查未预约 Matcher 发布的事件不能开始或保存结果，重复预约也拒绝；合法预约及发布结束后可收集并回收。
     */
    @Test
    void directFactsCannotStartOrPublishWithoutAReservedMatcherSlot() {
        try (/* 本用例独占并在退出时关闭的交易运行时，保存订单、拓扑及结算池。 */ var runtime = new TradingAccountRuntime()) {
            // 当前用例解析币对编号及持仓身份的运行时字典。
            var identities = new RuntimeIdentityRegistry();
            // 固定测试币对一对应的稠密编号。
            int symbol = identities.symbolId("1");
            runtime.setMetadata(ProductLine.LINEAR_PERPETUAL, 0);
            // 拒绝撤单前的权威订单引用，用于核对事件不替换或修改订单归属。
            var original = order(11, 21, symbol, CoreOrderSide.BUY, 3);
            runtime.putOrder(original);
            // 当前需要预约、发布、收集并回收的撮合结算事件。
            var event = runtime.prepareDirectCancellation(1, 21, 11, new java.util.UUID(1, 2),
                    0, identities, 10, 20);
            assertThatThrownBy(event::beginMatcherPublication).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> event.publishDirectNativeResult(nativeResult(
                    CommandResultCode.MATCHING_UNKNOWN_ORDER_ID, symbol, 1, List.of()),
                    1, 1, 2, 11, 1, 0)).isInstanceOf(IllegalStateException.class);
            assertThat(event.resultPrepared()).isFalse();
            assertThat(event.ready()).isFalse();
            event.reserveMatcherPublication();
            assertThatThrownBy(event::reserveMatcherPublication).isInstanceOf(IllegalStateException.class);
            event.beginMatcherPublication();
            event.publishDirectNativeResult(nativeResult(CommandResultCode.MATCHING_UNKNOWN_ORDER_ID,
                    symbol, 1, List.of()), 1, 1, 2, 11, 1, 0);
            event.completeMatcherPublication();
            assertThat(runtime.collectMatcherSettlement(event)).isNotNull();
            runtime.releaseMatcherSettlement(event);
        }
    }

    /** 测试共用的规范 BTC/USDT U 本位永续币对及单档风险限额。 */
    private static final CoreInstrument INSTRUMENT = new CoreInstrument(
            "1", ContractType.LINEAR_PERPETUAL, "BTC", "USDT", "USDT", 1, 1, 1_000_000,
            new CoreInstrumentRiskLimits(
                    100_000, 10_000_000, Long.MAX_VALUE, 0, 1,
                    List.of(new CoreRiskLimitBracket(Long.MAX_VALUE, 10_000_000, 100_000, 50_000))),
            -10, 25, 0, null, 0);

    /**
     * 在发布完成前检查事件未就绪、没有完成位且无法收集，完成发布后拒绝撤单可收集且原订单引用未变。
     */
    @Test
    void matcherPublicationWaitsForReadinessBeforeApplyingAccounts() {
        try (/* 本用例独占并在退出时关闭的交易运行时，保存订单、拓扑及结算池。 */ var runtime = new TradingAccountRuntime()) {
            // 当前用例解析币对编号及持仓身份的运行时字典。
            var identities = new RuntimeIdentityRegistry();
            // 固定测试币对一对应的稠密编号。
            int symbol = identities.symbolId("1");
            runtime.setMetadata(ProductLine.LINEAR_PERPETUAL, 0);
            // 拒绝撤单前的权威订单引用，用于核对事件不替换或修改订单归属。
            var original = order(11, 21, symbol, CoreOrderSide.BUY, 3);
            runtime.putOrder(original);
            // 当前需要预约、发布、收集并回收的撮合结算事件。
            var event = runtime.prepareDirectCancellation(1, original.userId(), original.orderId(), new java.util.UUID(1, 2),
                    0, identities, 10, 20);
            event.reserveMatcherPublication();
            event.beginMatcherPublication();
            assertThat(event.ready()).isFalse();
            assertThat(event.completedLaneMask()).isZero();
            assertThat(runtime.collectMatcherSettlement(event)).isNull();
            event.publishDirectNativeResult(nativeResult(CommandResultCode.MATCHING_UNKNOWN_ORDER_ID,
                    symbol, 1, List.of()), 1, 1, 2, 11, 1, 0);
            event.completeMatcherPublication();
            assertThat(event.complete()).isTrue();
            assertThat(runtime.collectMatcherSettlement(event)).isNotNull();
            assertThat(runtime.order(11)).isSameAs(original);
            runtime.releaseMatcherSettlement(event);
        }
    }

    /**
     * 先发布同账户后缀事件并检查尚无完成位，再发布前缀并按前缀、后缀顺序收集，最后核对拒绝撤单不改原订单；这里没有提前调用后缀收集来单独验证拒绝越序收集。
     */
    @Test
    void matcherPublishedFactsRetireInAccountOrder() {
        try (/* 本用例独占并在退出时关闭的交易运行时，保存订单、拓扑及结算池。 */ var runtime = new TradingAccountRuntime()) {
            // 当前用例解析币对编号及持仓身份的运行时字典。
            var identities = new RuntimeIdentityRegistry();
            // 固定测试币对一对应的稠密编号。
            int symbol = identities.symbolId("1");
            runtime.setMetadata(ProductLine.LINEAR_PERPETUAL, 0);
            // 拒绝撤单前的权威订单引用，用于核对事件不替换或修改订单归属。
            var original = order(11, 21, symbol, CoreOrderSide.BUY, 3);
            runtime.putOrder(original);
            // 同账户先行 Core 序号对应的前缀结算事件。
            var first = runtime.prepareDirectCancellation(1, original.userId(), original.orderId(), new java.util.UUID(1, 2),
                    0, identities, 10, 20);
            first.reserveMatcherPublication();
            first.beginMatcherPublication();
            // 同账户后行 Core 序号对应的后缀事件，不能越过未完成前缀。
            var suffix = runtime.prepareDirectCancellation(2, original.userId(), original.orderId(), new java.util.UUID(3, 4),
                    0, identities, 30, 40);
            suffix.reserveMatcherPublication();
            suffix.beginMatcherPublication();
            suffix.publishDirectNativeResult(nativeResult(CommandResultCode.MATCHING_UNKNOWN_ORDER_ID,
                    symbol, 2, List.of()), 2, 3, 4, 11, 2, 0);
            suffix.completeMatcherPublication();
            assertThat(suffix.completedLaneMask()).isZero();
            first.publishDirectNativeResult(nativeResult(CommandResultCode.MATCHING_UNKNOWN_ORDER_ID,
                    symbol, 1, List.of()), 1, 1, 2, 11, 1, 0);
            first.completeMatcherPublication();
            assertThat(runtime.collectMatcherSettlement(first)).isNotNull();
            runtime.releaseMatcherSettlement(first);
            assertThat(runtime.collectMatcherSettlement(suffix)).isNotNull();
            runtime.releaseMatcherSettlement(suffix);
            assertThat(runtime.order(11)).isSameAs(original);
        }
    }

    /**
     * 用真实 Matcher 测试线程卡住发布后的唤醒回调；Owner 可在就绪后收集并复用事件，旧回调继续返回不能污染新代字段。
     */
    @Test
    @org.junit.jupiter.api.Timeout(15)
    void ownerMayRecycleAfterReadinessWhileMatcherIsStillSignalling() throws Exception {
        // 工作线程进入发布后唤醒回调时通知主线程的门闩。
        var signalling = new java.util.concurrent.CountDownLatch(1);
        // 阻塞旧发布唤醒回调直到主线程完成事件复用的门闩。
        var finishSignal = new java.util.concurrent.CountDownLatch(1);
        // 跨线程记录意外异常的原子引用。
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        // 执行事件发布和完成通知的实际测试线程，退出时有界等待结束。
        Thread matcher = null;
        try (/* 本用例独占并在退出时关闭的交易运行时，保存订单、拓扑及结算池。 */ var runtime = new TradingAccountRuntime()) {
            // 当前用例解析币对编号及持仓身份的运行时字典。
            var identities = new RuntimeIdentityRegistry();
            // 固定测试币对一对应的稠密编号。
            int symbol = identities.symbolId("1");
            runtime.setMetadata(ProductLine.LINEAR_PERPETUAL, 0);
            // 拒绝撤单前的权威订单引用，用于核对事件不替换或修改订单归属。
            var original = order(11, 21, symbol, CoreOrderSide.BUY, 3);
            runtime.putOrder(original);
            // 当前需要预约、发布、收集并回收的撮合结算事件。
            var event = runtime.prepareDirectCancellation(1, original.userId(), original.orderId(), new java.util.UUID(1, 2),
                    0, identities, 10, 20);
            event.reserveMatcherPublication();
            // 传输释放回调应能看到已准备的事件元数据，
            // 此时发布租约仍阻止 Owner 收集或回收事件。
            event.matcherCompletionRoute(/* 释放回调传入的 Matcher 分片编号；这些测试回调不读取编号。 */ shard -> {
                assertThat(event.dispatched()).isTrue();
                assertThat(event.ready()).isFalse();
            }, 0);
            runtime.ownerCompletionSignal(() -> {
                if (runtime.inOwnerThread()) return;
                signalling.countDown();
                awaitPublicationLatch(finishSignal);
            });
            matcher = Thread.ofPlatform().name("test-matcher-publication").start(() -> {
                try {
                    event.beginMatcherPublication();
                    event.publishDirectNativeResult(nativeResult(CommandResultCode.MATCHING_UNKNOWN_ORDER_ID,
                            symbol, 1, List.of()), 1, 1, 2, 11, 1, 0);
                    event.completeMatcherPublication();
                } catch (/* Matcher 测试线程捕获并交给主线程检查的异常。 */ Throwable error) { failure.set(error); signalling.countDown(); }
            });
            assertThat(signalling.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(failure.get()).isNull();
            assertThat(event.ready()).isTrue();
            assertThat(runtime.collectMatcherSettlement(event)).isNotNull();
            runtime.releaseMatcherSettlement(event);
            // 从事件池再次借到的同一对象，用于检查新代字段未被旧回调覆盖。
            var reused = runtime.prepareDirectCancellation(2, original.userId(), original.orderId(), new java.util.UUID(3, 4),
                    0, identities, 30, 40);
            assertThat(reused).isSameAs(event);
            finishSignal.countDown();
            matcher.join(5_000);
            assertThat(matcher.isAlive()).isFalse();
            assertThat(failure.get()).isNull();
            assertThat(reused.ready()).isFalse();
            assertThat(reused.dispatched()).isFalse();
            assertThat(reused.directResultCoreSequence()).isEqualTo(2);
            runtime.ownerCompletionSignal(null);
            reused.reserveMatcherPublication();
            reused.publishDirectNativeResult(nativeResult(CommandResultCode.MATCHING_UNKNOWN_ORDER_ID,
                    symbol, 2, List.of()), 2, 3, 4, 11, 2, 0);
            reused.execute(runtime.accountLane(runtime.topology().accountLaneId(21)));
            assertThat(runtime.collectMatcherSettlement(reused)).isNotNull();
            runtime.releaseMatcherSettlement(reused);
        } finally {
            finishSignal.countDown();
            if (matcher != null) matcher.join(5_000);
        }
    }

    /**
     * 让事件释放路由回调抛异常，检查实际 Matcher 管线把发布故障交给 Owner 查询和后续提交，而不是留下无结果等待。
     */
    @Test
    void settlementPublicationFailureReachesOwnerInsteadOfLeavingDeadMatcher() {
        try (/* 本用例独占并在退出时关闭的交易运行时，保存订单、拓扑及结算池。 */ var runtime = new TradingAccountRuntime();
             /* 用例实际启动并自动关闭的 Matcher 管线，用于检查故障传播。 */ var pipeline = new com.surprising.aeron.service.matcher.pipeline.MatcherCommandPipeline(256)) {
            // 当前需要预约、发布、收集并回收的撮合结算事件。
            var event = new MatcherSettlementEvent();
            event.batchStorage(1);
            event.prepareDirect(1, 1, 0, 0, new java.util.UUID(1, 2), 0,
                    runtime, new RuntimeIdentityRegistry(), 1, List.of(), null);
            event.reserveMatcherPublication();
            event.matcherCompletionRoute(/* 释放回调传入的 Matcher 分片编号；这些测试回调不读取编号。 */ shard -> {
                throw new IllegalStateException("settlement publication failed");
            }, 0);
            pipeline.submit(1, () -> {
                event.failDirect(new IllegalArgumentException("command failed"));
                return new CoreMatchingResult(false, "REJECTED");
            }, event);
            assertThatThrownBy(() -> {
                // 单调时钟计算的有界等待截止点，超时让测试失败。
                long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                while (System.nanoTime() < deadline) {
                    pipeline.completedMatchingSequence();
                    Thread.onSpinWait();
                }
                throw new AssertionError("Owner was not notified of publication failure");
            }).isInstanceOf(IllegalStateException.class).hasMessage("settlement publication failed");
            assertThatThrownBy(() -> pipeline.submit(2, () -> new CoreMatchingResult(true, "SUCCESS")))
                    .isInstanceOf(IllegalStateException.class).hasMessage("settlement publication failed");
        }
    }

    /**
     * 有界等待测试门闩，超时失败；被中断时恢复中断标记并把异常交给测试。
     * @param latch 由测试主线程或工作线程释放的发布门闩
     */
    private static void awaitPublicationLatch(java.util.concurrent.CountDownLatch latch) {
        try {
            if (!latch.await(5, java.util.concurrent.TimeUnit.SECONDS))
                throw new IllegalStateException("test publication signal timed out");
        } catch (/* 有界等待收到的中断异常，恢复中断标记后转换为测试失败。 */ InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("test publication interrupted", failure);
        }
    }

    /**
     * 不注册币对元数据直接构造拒绝撤单，检查完成后没有变化订单身份，原订单仍是同一引用。
     */
    @Test
    void rejectedDirectCancelLeavesOrderUnchangedWithoutInstrumentMetadata() {
        try (/* 本用例独占并在退出时关闭的交易运行时，保存订单、拓扑及结算池。 */ var runtime = new TradingAccountRuntime()) {
            // 当前用例解析币对编号及持仓身份的运行时字典。
            var identities = new RuntimeIdentityRegistry();
            // 当前用例固定测试币对的稠密编号。
            int instrumentId = identities.symbolId("1");
            runtime.setMetadata(ProductLine.LINEAR_PERPETUAL, 0);
            // 拒绝撤单前的权威订单引用，用于核对事件不替换或修改订单归属。
            var original = order(11, 21, instrumentId, CoreOrderSide.BUY, 3);
            runtime.putOrder(original);
            // 本事件命令 UUID，供原生结果证据匹配。
            var id = new java.util.UUID(1, 2);
            // 当前需要预约、发布、收集并回收的撮合结算事件。
            var event = runtime.prepareDirectCancellation(1, original.userId(), original.orderId(), id, 0, identities, 10, 20);
            event.reserveMatcherPublication();
            event.publishDirectNativeResult(nativeResult(CommandResultCode.MATCHING_UNKNOWN_ORDER_ID,
                    instrumentId, 1, List.of()), 1, 1, 2, 11, 1, 0);
            event.execute(runtime.accountLane(runtime.topology().accountLaneId(21)));
            assertThat(event.complete()).isTrue();
            assertThat(event.plan().orderCount()).isZero();
            runtime.collectMatcherSettlement(event);
            assertThat(runtime.order(11)).isSameAs(original);
            runtime.releaseMatcherSettlement(event);
        }
    }

    /**
     * 实际启动四个账户 Lane 并阻塞非成交 Lane，检查成交 Lane 已完成也不能提前回收；全部路由票据消费后才允许事件入池。
     */
    @Test
    @org.junit.jupiter.api.Timeout(15)
    void directEventCannotRecycleBeforeReservedNonTradingLanesConsumeTheirTickets() throws Exception {
        // 用例显式使用的四账户 Lane 路由拓扑。
        var topology = new LaneTopology(1, 4, LaneTopology.DEFAULT_ACCOUNT_LANE_SEED, 16, 16, 16);
        try (/* 本用例独占并在退出时关闭的交易运行时，保存订单、拓扑及结算池。 */ var runtime = new TradingAccountRuntime(topology)) {
            // 当前用例解析币对编号及持仓身份的运行时字典。
            var identities = new RuntimeIdentityRegistry();
            // 当前用例固定测试币对的稠密编号。
            int instrumentId = identities.symbolId("1");
            runtime.setMetadata(ProductLine.LINEAR_PERPETUAL, 0);
            runtime.registerInstrument(instrument());
            // 当前构建或事件输入的主动买单。
            var taker = order(11, 21, instrumentId, CoreOrderSide.BUY, 3);
            runtime.putOrder(taker);
            // 本事件命令 UUID，供原生结果证据匹配。
            var id = new java.util.UUID(1, 2);
            // 当前需要预约、发布、收集并回收的撮合结算事件。
            var event = runtime.prepareDirectMatcherSettlement(1, 15, taker, null, 1, id, 0,
                    identities, 1, 1, List.of(), null);
            // 本次无成交订单实际涉及的账户 Lane 编号。
            int actual = topology.accountLaneId(21);
            // 等待另外三个 Lane 已进入阻塞回调的计数门闩。
            var waiting = new java.util.concurrent.CountDownLatch(3);
            // 释放非成交 Lane 阻塞回调的门闩，finally 保证放行。
            var release = new java.util.concurrent.CountDownLatch(1);
            runtime.startAccountLanes();
            runtime.releaseOwnerLaneAccess();
            try {
                for (/* 当前需阻塞的账户 Lane 下标，实际参与 Lane 被排除。 */ int lane = 0; lane < 4; lane++) if (lane != actual)
                    runtime.submitAccountCommand(lane, /* 阻塞回调收到的账户 Lane，本回调只等待门闩，不读写账户。 */ ignored -> {
                        waiting.countDown();
                        awaitPublicationLatch(release);
                    });
                assertThat(waiting.await(3, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                // 回调记录是否已释放 Matcher 传输路由的单元素标记。
                boolean[] routeReleased = {false};
                event.reserveMatcherPublication();
                event.matcherCompletionRoute(/* 释放回调传入的 Matcher 分片编号；这些测试回调不读取编号。 */ shard -> routeReleased[0] = true, 0);
                event.beginMatcherPublication();
                event.publishDirectNativeResult(nativeResult(CommandResultCode.SUCCESS, instrumentId, 1,
                        List.of()), 1, 1, 2, 11, 1, 0);
                assertThat(event.resultPrepared()).isTrue();
                assertThat(event.ready()).isFalse();
                event.completeMatcherPublication();
                assertThat(routeReleased[0]).isTrue();
                // 单调时钟计算的有界等待截止点，超时让测试失败。
                long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
                while ((event.completedLaneMask() & (1L << actual)) == 0) {
                    runtime.assertAccountLanesHealthy();
                    if (System.nanoTime() >= deadline) throw new AssertionError("active Lane did not settle");
                    Thread.onSpinWait();
                }
                assertThat(event.requiredLaneMask()).isEqualTo(1L << actual);
                assertThat(event.complete()).isFalse();
                assertThatThrownBy(event::clear).isInstanceOf(IllegalStateException.class);
            } finally { release.countDown(); }
            // 单调时钟计算的有界等待截止点，超时让测试失败。
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
            while (!event.complete()) {
                runtime.assertAccountLanesHealthy();
                if (System.nanoTime() >= deadline) throw new AssertionError("reserved Lane tickets did not drain");
                Thread.onSpinWait();
            }
            assertThat(runtime.collectMatcherSettlement(event)).isNotNull();
            runtime.releaseMatcherSettlement(event);
            // 从事件池再次借到的同一对象，用于检查新代字段未被旧回调覆盖。
            var reused = runtime.prepareDirectMatcherSettlement(2, 15, taker, null, 1, id, 0,
                    identities, 2, 2, List.of(), null);
            assertThat(reused).isSameAs(event);
            assertThat(reused.ready()).isFalse();
            assertThat(reused.complete()).isFalse();
        }
    }

    /**
     * 两项共享 maker 时用批次暂存累计剩余量，拒绝合计超量和终态后继续成交；构建失败不扣减权威 maker 数量。
     */
    @Test
    void sharedMakerQuantityIsValidatedAcrossItemsBeforeAnyLaneApplies() {
        try (/* 本用例独占并在退出时关闭的交易运行时，保存订单、拓扑及结算池。 */ var runtime = new TradingAccountRuntime()) {
            // 当前用例解析币对编号及持仓身份的运行时字典。
            var identities = new RuntimeIdentityRegistry();
            // 当前用例固定测试币对的稠密编号。
            int instrumentId = identities.symbolId("1");
            // 用例共用的规范永续币对引用。
            var instrument = instrument();
            runtime.setMetadata(ProductLine.LINEAR_PERPETUAL, 0);
            runtime.registerInstrument(instrument);
            // 批次多次成交共用的卖方 maker，用于检查累计剩余量。
            var maker = order(10, 20, instrumentId, CoreOrderSide.SELL, 5);
            // 当前批次第一笔主动买单，计划消耗 maker 三个单位。
            var first = order(11, 21, instrumentId, CoreOrderSide.BUY, 3);
            // 当前批次第二笔主动买单，用于累计 maker 数量检查。
            var second = order(12, 22, instrumentId, CoreOrderSide.BUY, 3);
            runtime.putOrder(maker); runtime.putOrder(first); runtime.putOrder(second);
            // 当前批次共享的剩余数量、终态及最近 maker 校验暂存。
            var scratch = new MatcherSettlementPlan.BatchValidationScratch();
            assertThat(MatcherSettlementPlan.buildBatchItem(1, first, instrument, fill(3), runtime, identities, scratch, new MatcherSettlementPlan()).tradeCount()).isEqualTo(1);
            assertThatThrownBy(() -> MatcherSettlementPlan.buildBatchItem(1, second, instrument, fill(3), runtime, identities, scratch, new MatcherSettlementPlan()))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("exceeds");
            assertThat(runtime.order(10).remainingQuantitySteps()).isEqualTo(5);
            scratch.clear();
            MatcherSettlementPlan.buildBatchItem(1, first, instrument, fill(3), runtime, identities, scratch, new MatcherSettlementPlan());
            assertThat(MatcherSettlementPlan.buildBatchItem(1, second, instrument, fill(2), runtime, identities, scratch, new MatcherSettlementPlan()).tradeCount()).isEqualTo(1);
            assertThatThrownBy(() -> MatcherSettlementPlan.buildBatchItem(1, first, instrument, fill(1), runtime, identities, scratch, new MatcherSettlementPlan()))
                    .isInstanceOf(IllegalStateException.class);
        }
    }
    /**
     * 清理计划及批次暂存后替换 maker 为更小数量，检查重建会读新数量并拒绝超量；本用例没有附加触发终态，不据名称声称验证触发状态清理。
     */
    @Test
    void reusedPlanAndScratchDoNotRetainPriorMakerOrTriggerState() {
        try (/* 本用例独占并在退出时关闭的交易运行时，保存订单、拓扑及结算池。 */ var runtime = new TradingAccountRuntime()) {
            // 当前用例解析币对编号及持仓身份的运行时字典。
            var identities = new RuntimeIdentityRegistry(); /* 当前用例固定测试币对的稠密编号。 */ int instrumentId = identities.symbolId("1");
            runtime.setMetadata(ProductLine.LINEAR_PERPETUAL, 0); runtime.registerInstrument(instrument());
            runtime.putOrder(order(10,20,instrumentId,CoreOrderSide.SELL,5));
            // 当前构建或事件输入的主动买单。
            var taker = order(11,21,instrumentId,CoreOrderSide.BUY,3); runtime.putOrder(taker);
            // 当前批次共享的剩余数量、终态及最近 maker 校验暂存。
            var scratch = new MatcherSettlementPlan.BatchValidationScratch(); /* 需要在各代间复用的结算计划对象。 */ var slot = new MatcherSettlementPlan();
            assertThat(MatcherSettlementPlan.buildBatchItem(1,taker,instrument(),fill(3),runtime,identities,scratch,slot)).isSameAs(slot);
            slot.clearReferences(); scratch.clear();
            runtime.putOrder(order(10,20,instrumentId,CoreOrderSide.SELL,1));
            assertThatThrownBy(() -> MatcherSettlementPlan.buildBatchItem(1,taker,instrument(),fill(3),runtime,identities,scratch,slot))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("exceeds");
        }
    }
    /**
     * 先为九次成交建立 maker Lane 链，再复用为一次成交，核对新代首项、结束下标及成交数不读取旧链尾。
     */
    @Test
    void pooledPlanSwitchesFromDeepMakerLaneIndexBackToOneFillWithoutStaleLinks() {
        // 用例显式使用的四账户 Lane 路由拓扑。
        var topology = new LaneTopology(1, 4, LaneTopology.DEFAULT_ACCOUNT_LANE_SEED, 16, 16, 16);
        try (/* 本用例独占并在退出时关闭的交易运行时，保存订单、拓扑及结算池。 */ var runtime = new TradingAccountRuntime(topology)) {
            // 当前用例解析币对编号及持仓身份的运行时字典。
            var identities = new RuntimeIdentityRegistry(); /* 当前用例固定测试币对的稠密编号。 */ int instrumentId = identities.symbolId("1");
            runtime.setMetadata(ProductLine.LINEAR_PERPETUAL,0); runtime.registerInstrument(instrument());
            // 调整到与固定 maker 不同账户 Lane 的主动用户编号。
            long takerUser = 21;
            while (topology.accountLaneId(takerUser) == topology.accountLaneId(20)) takerUser++;
            runtime.putOrder(order(10,20,instrumentId,CoreOrderSide.SELL,20));
            // 当前构建或事件输入的主动买单。
            var taker = order(11,takerUser,instrumentId,CoreOrderSide.BUY,20); runtime.putOrder(taker);
            // 构造深度成交所用的九项原始事件列表。
            var events = new java.util.ArrayList<exchange.core2.core.common.MatcherResult.MatcherEvent>();
            for (/* 添加重复 maker 成交事件的当前次数。 */ int i = 0; i < 9; i++) events.add(MatcherEventFixtures.trade(10,20,100,1,false,false));
            // 包含九次小额成交的接受结果，用来触发多 Lane 深度事件链。
            var deep = new CoreMatchingResult(true, "SUCCESS", List.of(),
                    true, 0, 0, 0, 0, 0, -1,
                    null, events).withCoreSequenceInPlace(1);
            // 当前批次共享的剩余数量、终态及最近 maker 校验暂存。
            var scratch = new MatcherSettlementPlan.BatchValidationScratch(); /* 需要在各代间复用的结算计划对象。 */ var slot = new MatcherSettlementPlan();
            MatcherSettlementPlan.buildBatchItem(1,taker,instrument(),deep,runtime,identities,scratch,slot);
            // 固定 maker 所属账户 Lane，用于检查其事件链。
            // 沿 maker Lane 链实际遍历到的事件数。
            int makerLane = topology.accountLaneId(20), count = 0;
            for (/* 沿 maker Lane 链当前访问的原始事件下标，负一表示遍历结束。 */ int event = slot.firstMatcherEvent(makerLane); event >= 0; event = slot.nextMatcherEvent(event,makerLane)) count++;
            assertThat(count).isEqualTo(9);
            slot.clearReferences(); scratch.clear();
            MatcherSettlementPlan.buildBatchItem(1,taker,instrument(),fill(1),runtime,identities,scratch,slot);
            assertThat(slot.firstMatcherEvent(makerLane)).isZero();
            assertThat(slot.nextMatcherEvent(0,makerLane)).isEqualTo(-1);
            assertThat(slot.tradeCount()).isOne();
        }
    }
    /**
     * 借用大批量存储后改为一项，在尾部留异代计划，检查当前有效项只有一项且不能通过 plan 读取尾部。
     */
    @Test
    void smallerBatchReusesCapacityAndIgnoresStaleTailPlans() {
        try (/* 本用例独占并在退出时关闭的交易运行时，保存订单、拓扑及结算池。 */ var runtime = new TradingAccountRuntime()) {
            // 当前用例解析币对编号及持仓身份的运行时字典。
            var identities = new RuntimeIdentityRegistry();
            // 当前用例固定测试币对的稠密编号。
            int instrumentId = identities.symbolId("1");
            // 用例共用的规范永续币对引用。
            var instrument = instrument();
            runtime.setMetadata(ProductLine.LINEAR_PERPETUAL, 0);
            runtime.registerInstrument(instrument);
            // 当前需要预约、发布、收集并回收的撮合结算事件。
            var event = new MatcherSettlementEvent();
            // 大批次借出的事件存储，缩小有效项数后仍保留原容量。
            var storage = event.batchStorage(20);
            // 小批次测试的主动用户编号。
            long userId = 21;
            // 该主动用户所属账户 Lane 位图。
            long laneMask = runtime.topology().accountLaneMask(userId);
            storage.plans[0] = MatcherSettlementPlan.empty(1, userId, new long[]{11}, runtime);
            // 尾部属于上代且序号不同；不能参与本代校验、执行或收集。
            storage.plans[1] = MatcherSettlementPlan.empty(99, userId, new long[]{12}, runtime);
            storage.instruments[0] = instrument;
            assertThat(event.batchStorage(1)).isSameAs(storage);
            storage.admittedOrders[0] = order(11, userId, instrumentId, CoreOrderSide.BUY, 1);
            // 当前构造事实使用的命令 UUID。
            var commandId = new java.util.UUID(1, 2);
            event.prepareDirect(1, laneMask, 1, 1, commandId, 0, runtime, identities, 1, List.of(), null);
            event.reserveMatcherPublication();
            event.publishDirectNativeResult(nativeResult(CommandResultCode.SUCCESS, instrumentId, 1, List.of()),
                    1, 1, 2, 11, 1, 0);
            assertThat(event.planCount()).isOne();
            assertThat(event.plan(0).coreSequence()).isEqualTo(1);
            assertThatThrownBy(() -> event.plan(1)).isInstanceOf(IndexOutOfBoundsException.class);
        }
    }

    /**
     * 交替构建成交和空计划，检查调用方撤单数组被复制、清空后旧事件与撤单不保留，新代编号及成交数正确。
     */
    @Test
    void singleCommandSlotReusesPlanWithoutLeakingPriorOrdersOrCancellations() {
        try (/* 本用例独占并在退出时关闭的交易运行时，保存订单、拓扑及结算池。 */ var runtime = new TradingAccountRuntime()) {
            // 当前用例解析币对编号及持仓身份的运行时字典。
            var identities = new RuntimeIdentityRegistry();
            // 当前用例固定测试币对的稠密编号。
            int instrumentId = identities.symbolId("1");
            runtime.setMetadata(ProductLine.LINEAR_PERPETUAL, 0);
            runtime.registerInstrument(instrument());
            runtime.putOrder(order(10, 20, instrumentId, CoreOrderSide.SELL, 5));
            runtime.putOrder(order(11, 21, instrumentId, CoreOrderSide.BUY, 3));
            // 需要在各代间复用的结算计划对象。
            var slot = new MatcherSettlementPlan();
            assertThat(MatcherSettlementPlan.buildInto(slot, 1, 11, 21, 11, 0,
                    fill(3), runtime, identities)).isSameAs(slot);
            // 调用方撤单数组或混合接受与拒绝的撤单结果列表，具体用例分别核对复制及顺序。
            long[] cancellations = {12};
            assertThat(slot.preCancellations(cancellations)).isSameAs(slot);
            cancellations[0] = 999;
            assertThat(slot.preCancellationOrderId(0)).isEqualTo(12);
            slot.clearReferences();
            assertThat(slot.matcherEventCount()).isZero();
            assertThat(slot.preCancellationCount()).isZero();
            assertThat(MatcherSettlementPlan.emptyInto(slot, 2, 21, 20, 22, runtime)).isSameAs(slot);
            assertThat(slot.orderCount()).isEqualTo(2);
            assertThat(slot.orderId(0)).isEqualTo(20);
            assertThat(slot.orderId(1)).isEqualTo(22);
            assertThat(slot.tradeCount()).isZero();
            assertThat(slot.matcherEventCount()).isZero();
            assertThat(MatcherSettlementPlan.buildInto(slot, 3, 11, 21, 11, 0,
                    fill(1, 3), runtime, identities)).isSameAs(slot);
            assertThat(slot.orderCount()).isEqualTo(2);
            assertThat(slot.orderId(0)).isEqualTo(11);
            assertThat(slot.orderId(1)).isEqualTo(10);
            assertThat(slot.preCancellationCount()).isZero();
        }
    }

    /**
     * 检查单项构建返回原计划槽，并保存预期 taker、maker 和成交数；仅验证槽复用，不代表分配采样。
     */
    @Test
    void singleItemBatchBuilderUsesThePooledTargetForInitialIdentity() {
        try (/* 本用例独占并在退出时关闭的交易运行时，保存订单、拓扑及结算池。 */ var runtime = new TradingAccountRuntime()) {
            // 当前用例解析币对编号及持仓身份的运行时字典。
            var identities = new RuntimeIdentityRegistry();
            // 当前用例固定测试币对的稠密编号。
            int instrumentId = identities.symbolId("1");
            runtime.setMetadata(ProductLine.LINEAR_PERPETUAL, 0);
            runtime.registerInstrument(instrument());
            runtime.putOrder(order(10, 20, instrumentId, CoreOrderSide.SELL, 5));
            runtime.putOrder(order(11, 21, instrumentId, CoreOrderSide.BUY, 3));
            // 需要在各代间复用的结算计划对象。
            var slot = new MatcherSettlementPlan();
            // 本次单项构建使用的接受成交结果。
            var result = fill(3);
            assertThat(MatcherSettlementPlan.buildSingleInto(slot, 1, 11, 21,
                    result, runtime, identities)).isSameAs(slot);
            assertThat(slot.orderCount()).isEqualTo(2);
            assertThat(slot.orderId(0)).isEqualTo(11);
            assertThat(slot.orderId(1)).isEqualTo(10);
            assertThat(slot.tradeCount()).isOne();
        }
    }

    /**
     * 检查重复读取返回同一编号视图，计划清空后旧视图也变空，明确它不是独立快照。
     */
    @Test
    void orderIdViewFollowsPlanLifetimeWithoutCopyingStorage() {
        try (/* 本用例独占并在退出时关闭的交易运行时，保存订单、拓扑及结算池。 */ var runtime = new TradingAccountRuntime()) {
            // 需要在各代间复用的结算计划对象。
            var slot = MatcherSettlementPlan.emptyInto(new MatcherSettlementPlan(), 1, 21, 11, 10, runtime);
            // 绑定计划生命周期的只读订单编号视图。
            var view = slot.orderIdList();

            assertThat(view).containsExactly(11L, 10L);
            assertThat(slot.orderIdList()).isSameAs(view);
            slot.clearReferences();
            assertThat(view).isEmpty();
        }
    }

    /**
     * 让实际结果顺序不同于准入顺序且包含拒绝项，检查只保存成功撤单并按准入期望顺序排列。
     */
    @Test
    void expectedCancellationsAreWrittenInAdmissionOrderIntoReusablePlanStorage() {
        try (/* 本用例独占并在退出时关闭的交易运行时，保存订单、拓扑及结算池。 */ var runtime = new TradingAccountRuntime()) {
            // 当前用例解析币对编号及持仓身份的运行时字典。
            var identities = new RuntimeIdentityRegistry();
            // 当前用例固定测试币对的稠密编号。
            int instrumentId = identities.symbolId("1");
            runtime.setMetadata(ProductLine.LINEAR_PERPETUAL, 0);
            runtime.registerInstrument(instrument());
            runtime.putOrder(order(11, 21, instrumentId, CoreOrderSide.BUY, 3));
            // 需要在各代间复用的结算计划对象。
            var slot = MatcherSettlementPlan.buildInto(new MatcherSettlementPlan(), 1, 11, 21, 11, 0,
                    new CoreMatchingResult(true, "SUCCESS", List.of(),
                            true, 1, 1, 2, 1, 1, 0,
                            null, List.of()), runtime, identities);
            // 调用方撤单数组或混合接受与拒绝的撤单结果列表，具体用例分别核对复制及顺序。
            var cancellations = List.of(new com.surprising.aeron.service.matcher.CoreCancellationResult(13, true),
                    new com.surprising.aeron.service.matcher.CoreCancellationResult(12, false),
                    new com.surprising.aeron.service.matcher.CoreCancellationResult(11, true));
            slot.preCancellationsFromExpected(List.of(12L, 11L, 13L), cancellations);
            assertThat(slot.preCancellationCount()).isEqualTo(2);
            assertThat(slot.preCancellationOrderId(0)).isEqualTo(11);
            assertThat(slot.preCancellationOrderId(1)).isEqualTo(13);
        }
    }

    /**
     * 分别构造单次超量及重复 maker 累计超量，检查失败后合法数量仍可重建，原 taker 和 maker 引用保持不变。
     */
    @Test
    void singleEventAndRepeatedMakerBothRejectExcessBeforeStateMutation() {
        try (/* 本用例独占并在退出时关闭的交易运行时，保存订单、拓扑及结算池。 */ var runtime = new TradingAccountRuntime()) {
            // 当前用例解析币对编号及持仓身份的运行时字典。
            var identities = new RuntimeIdentityRegistry();
            // 当前用例固定测试币对的稠密编号。
            int instrumentId = identities.symbolId("1");
            runtime.setMetadata(ProductLine.LINEAR_PERPETUAL, 0);
            runtime.registerInstrument(instrument());
            // 批次多次成交共用的卖方 maker，用于检查累计剩余量。
            var maker = order(10, 20, instrumentId, CoreOrderSide.SELL, 3);
            // 当前构建或事件输入的主动买单。
            var taker = order(11, 21, instrumentId, CoreOrderSide.BUY, 5);
            runtime.putOrder(maker); runtime.putOrder(taker);
            // 需要在各代间复用的结算计划对象。
            var slot = new MatcherSettlementPlan();
            assertThatThrownBy(() -> MatcherSettlementPlan.buildInto(slot, 1, 11, 21, 11, 0,
                    fill(4), runtime, identities)).isInstanceOf(IllegalStateException.class).hasMessageContaining("exceeds");
            // 两次引用同一 maker 且累计超量的原始成交结果。
            var repeated = new CoreMatchingResult(true, "SUCCESS", List.of(),
                    true, 1, 0, 0, 0, 0, -1,
                    null, List.of(MatcherEventFixtures.trade(10, 20, 100, 2, false, false),
                            MatcherEventFixtures.trade(10, 20, 100, 2, false, false)));
            assertThatThrownBy(() -> MatcherSettlementPlan.buildInto(slot, 1, 11, 21, 11, 0,
                    repeated, runtime, identities)).isInstanceOf(IllegalStateException.class).hasMessageContaining("exceeds");
            assertThat(MatcherSettlementPlan.buildInto(slot, 1, 11, 21, 11, 0,
                    fill(3), runtime, identities).tradeCount()).isEqualTo(1);
            assertThat(runtime.order(10)).isSameAs(maker);
            assertThat(runtime.order(11)).isSameAs(taker);
        }
    }

    /**
     * 构造固定规范币对、价格一百的开放全仓净持仓限价单，用于结算计划输入。
     * @param id 测试订单编号
     * @param user 测试订单所属用户编号
     * @param instrumentId 测试币对的运行时稠密编号
     * @param side 测试订单买卖方向
     * @param qty 测试订单总量及初始剩余量
     */
    private static OrderRuntime order(long id,long user,int instrumentId,CoreOrderSide side,long qty) {
        return new OrderRuntime(id,user,instrumentId,instrument(),side,100,false,CoreMarginMode.CROSS,CorePositionSide.NET,
                CoreOrderType.LIMIT,CoreTimeInForce.GTC,0,0,qty,0,qty,false);
    }
    /**
     * 以 Core 序号一构造固定 maker 的单笔接受成交。
     * @param quantity 固定 maker 本次成交的数量
     */
    private static CoreMatchingResult fill(long quantity) { return fill(quantity, 1); }
    /**
     * 以指定 Core 序号构造固定 maker 的单笔接受成交，数量按参数设置。
     * @param quantity 固定 maker 本次成交的数量
     * @param sequence 关联结果的 Core 序号
     */
    private static CoreMatchingResult fill(long quantity, long sequence) {
        return new CoreMatchingResult(true, "SUCCESS", List.of(),
                true, 0, 0, 0, 0, 0, -1,
                null, List.of(MatcherEventFixtures.trade(10,20,100,quantity,false,false))).withCoreSequenceInPlace(sequence);
    }
    /**
     * 构造测试用原生结果，命令类型固定为 PLACE_ORDER；结果码和事件由用例指定，不实际运行撮合。
     * @param code 测试期望的原生接受或拒绝结果码
     * @param instrumentId 测试币对的运行时稠密编号
     * @param sequence 关联结果的 Core 序号
     * @param events 测试输入的原生撮合事件列表
     */
    private static MatcherResult nativeResult(CommandResultCode code, int instrumentId, long sequence,
                                               List<MatcherResult.MatcherEvent> events) {
        return new MatcherResult(sequence, OrderCommandType.PLACE_ORDER, sequence, instrumentId,
                100, 1, 100, OrderAction.BID, OrderType.GTC, 21, 1_000, 0,
                code, events, new MatcherResult.MarketData(List.of(), List.of(), 0, 0));
    }
    /**
     * 返回所有本文件订单共用的规范 U 本位永续币对对象，便于校验对象身份。
     */
    private static CoreInstrument instrument() {
        return INSTRUMENT;
    }
}
