package com.surprising.aeron.service.order;

import com.surprising.product.api.ProductLine;

import com.surprising.aeron.service.core.DirectMatcherTestInputs;

import com.surprising.aeron.service.order.admission.ResolvedPlaceOrder;

import com.surprising.aeron.config.TradingCpuAffinity;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreOrderType;
import com.surprising.aeron.protocol.CoreTimeInForce;
import com.surprising.aeron.service.matcher.DeterministicExchangeCoreAdapter;
import com.surprising.aeron.service.matcher.pipeline.MatcherPipelineGroup;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证分片组如何把结果直接交给命令槽、独立推进各分片并安全回收传输槽。普通路径用人工结果，跨品种路径使用真实独立订单簿与直达结算事件；不等于验证账户资金结算或全局 Core 提交。
 */
class MatcherPipelineGroupTest {

    /**
     * 先直接从命令槽收结果并复用同一槽为下一 Core 序号，再回收旧传输完成，要求新槽身份与完成标记不被污染且还能执行下一命令。
     */
    @Test
    void matcherPublishesWithoutOwnerDrainAndTransportRetirementDoesNotTouchReusedSlot() {
        // Owner 命令槽环，用于验证结果直接发布和槽位复用。
        var contexts = new CommandSlotRing(1);
        // 第一条命令占用的上下文，随后由同一对象承载第二条。
        var slot = contexts.claim(1);
        try (/* 本测试使用的 Matcher 分片组。 */ var pipelines = new MatcherPipelineGroup(1, 2, contexts)) {
            pipelines.start(null, TradingCpuAffinity.disabled());
            pipelines.submit(0, 1,
                    () -> new com.surprising.aeron.service.matcher.CoreMatchingResult(true, "FIRST"), null);
            // 当前轮询的 nanoTime 截止点。
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!slot.hasMatchingCompletion() && System.nanoTime() < deadline) Thread.onSpinWait();
            assertThat(slot.hasMatchingCompletion()).isTrue();
            // 从命令槽或组中收取的普通撮合回执。
            var result = slot.takeMatchingCompletion();
            assertThat(result.resultCode()).isEqualTo("FIRST");
            slot.result(result, 0, 1);
            assertThat(slot.submittedMatcherShard()).isEqualTo(-1);
            contexts.release(1);
            // 复用后的命令槽对象，要求不再保留旧命令的路由和结果。
            var reused = contexts.claim(2);
            assertThat(reused).isSameAs(slot);
            pipelines.drainMatchingCompletions();
            assertThat(reused.coreSequence()).isEqualTo(2);
            assertThat(reused.hasMatchingCompletion()).isFalse();
            pipelines.submit(0, 2,
                    () -> new com.surprising.aeron.service.matcher.CoreMatchingResult(true, "SECOND"), null);
            assertThat(await(pipelines, contexts, 2, TimeUnit.SECONDS.toNanos(5)).resultCode()).isEqualTo("SECOND");
        }
    }

    /**
     * 阻塞一分片命令，连续空探查后放行，要求后来发布的普通完成仍被发现并收集。
     */
    @Test
    void emptyProbeDoesNotLoseALaterMatcherPublication() throws Exception {
        // 确认被测试的命令已经进入 worker。
        var entered = new CountDownLatch(1);
        // 放行探查测试中的阻塞命令，finally 防止遗漏释放。
        var release = new CountDownLatch(1);
        var contexts = contexts(8, 42);
        try (/* 本测试使用的 Matcher 分片组。 */ var pipelines = new MatcherPipelineGroup(2, 4, contexts)) {
            pipelines.start(null, TradingCpuAffinity.disabled());
            try {
                pipelines.submit(1, 42, () -> {
                    entered.countDown();
                    await(release);
                    return new com.surprising.aeron.service.matcher.CoreMatchingResult(true, "OK");
                }, null);
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                for (/* 放行前重复进行空探查的次数。 */ int i = 0; i < 4; i++) assertThat(pipelines.hasMatchingCompletions()).isFalse();
                release.countDown();
                // 当前轮询的 nanoTime 截止点。
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (!pipelines.hasMatchingCompletions() && System.nanoTime() < deadline) Thread.yield();
                assertThat(pipelines.hasMatchingCompletions()).isTrue();
                assertThat(await(pipelines, contexts, 42, TimeUnit.SECONDS.toNanos(5))).isNotNull();
                assertThat(pipelines.hasMatchingCompletions()).isFalse();
            } finally {
                release.countDown();
            }
        }
    }

    /**
     * 向两个分片各提交一条普通命令，实际从两个 Core 命令槽收集，再继续 drain 到提交与完成深度都为零；方法名不表示本测试完全未探查待处理序号。
     */
    @Test
    void drainsCompletedShardHeadsWithoutProbingPendingSequences() {
        // 本测试使用的 Matcher 分片组。
        var contexts = contexts(8, 11, 12);
        MatcherPipelineGroup pipelines = new MatcherPipelineGroup(2, 4, contexts);
        try {
            pipelines.start(null, TradingCpuAffinity.disabled());
            pipelines.submit(0, 11, () -> new com.surprising.aeron.service.matcher.CoreMatchingResult(true, "ONE"), null);
            pipelines.submit(1, 12, () -> new com.surprising.aeron.service.matcher.CoreMatchingResult(true, "TWO"), null);
            // 已经收取的两个 Core 序号列表，用于检查结果集合。
            ArrayList<Long> completed = new ArrayList<>();
            // 当前轮询的 nanoTime 截止点。
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (completed.size() < 2 && System.nanoTime() < deadline) {
                pipelines.drainMatchingCompletions();
                for (long /* 当前轮询或预占槽位的 Core 序号。 */ sequence : new long[] {11, 12}) {
                    if (!completed.contains(sequence) && contexts.required(sequence).takeMatchingCompletion() != null) completed.add(sequence);
                }
                Thread.onSpinWait();
            }
            assertThat(completed).containsExactlyInAnyOrder(11L, 12L);
            // 命令槽中的结果可能早于传输完成游标可见。继续回收两个传输槽，
            // 再断言提交深度和完成深度都归零。
            while ((pipelines.submissionDepth() != 0 || pipelines.completionDepth() != 0)
                    && System.nanoTime() < deadline) {
                pipelines.drainMatchingCompletions();
                Thread.onSpinWait();
            }
            assertThat(pipelines.submissionDepth()).isZero();
            assertThat(pipelines.completionDepth()).isZero();
        } finally {
            pipelines.close();
        }
    }

    /**
     * 配置四个 Matcher 并寻找不同分片的两个数字品种，阻塞第一分片，要求第二直达事件先就绪且保留业务时间；放行后第一也完成，最后逐分片关闭订单簿并恢复系统属性。
     */
    @Test
    @ResourceLock(Resources.SYSTEM_PROPERTIES)
    void completesOneSymbolShardWhileAnotherShardIsBlocked() throws Exception {
        // 测试开始前的 Matcher 分片数量系统属性，退出时恢复。
        String previous = System.getProperty("surprising.aeron.matching-engines");
        System.setProperty("surprising.aeron.matching-engines", "4");
        // 延迟启动的真实同步撮合适配器，各分片由自己的 worker 激活。
        DeterministicExchangeCoreAdapter adapter = new DeterministicExchangeCoreAdapter(false);
        // 本测试使用的 Matcher 分片组。
        var contexts = contexts(64, 1, 2);
        MatcherPipelineGroup pipelines = new MatcherPipelineGroup(4, 16, contexts);
        // 放行第一个订单簿分片的门闩，退出时也会释放。
        CountDownLatch releaseFirst = new CountDownLatch(1);
        try {
            pipelines.start(adapter::activateShard, TradingCpuAffinity.disabled());
            // 第一个数字品种字符串。
            String firstSymbol = "39";
            // 第一个品种映射到的全局 Matcher 分片。
            int firstShard = adapter.matcherShardId(firstSymbol);
            // 搜索得到的另一个分片的数字品种，找到前为空。
            String secondSymbol = null;
            // 第二个品种的分片号，找到前为负一。
            int secondShard = -1;
            for (/* 搜索不同分片数字品种时的候选下标。 */ int index = 1; index < 1_000; index++) {
                // 当前尝试的数字品种字符串。
                String candidate = Integer.toString(100 + index);
                // 候选品种对应的全局分片。
                int candidateShard = adapter.matcherShardId(candidate);
                if (candidateShard != firstShard) {
                    secondSymbol = candidate;
                    secondShard = candidateShard;
                    break;
                }
            }
            assertThat(secondSymbol).isNotNull();
            // 确认第一个分片已进入阻塞命令的门闩。
            CountDownLatch firstStarted = new CountDownLatch(1);
            // 第一笔订单输入，路由拒绝测试中则是第一条命令槽。
            ResolvedPlaceOrder first = order(1, firstSymbol);
            // 第二笔订单输入，路由拒绝测试中则是第二条命令槽。
            ResolvedPlaceOrder second = order(2, secondSymbol);

            // 第一笔直接撮合使用的 UUID。
            var firstId = UUID.randomUUID();
            // 第二笔直接撮合使用的 UUID。
            var secondId = UUID.randomUUID();
            // 第一笔订单占用的直达结算事件与原生结果槽。
            var firstTarget = com.surprising.aeron.service.core.DirectMatcherTestInputs.preparePlace(
                    adapter, 1, firstId, 1000, 101, first);
            // 第二笔订单占用的直达结算事件与原生结果槽。
            var secondTarget = com.surprising.aeron.service.core.DirectMatcherTestInputs.preparePlace(
                    adapter, 2, secondId, 1001, 102, second);
            pipelines.submit(firstShard, 1, () -> {
                firstStarted.countDown();
                await(releaseFirst);
                return adapter.placeDirect(firstShard, 1, firstId.getMostSignificantBits(), firstId.getLeastSignificantBits(), 1000, 101, first, firstTarget);
            }, firstTarget);
            assertThat(firstStarted.await(5, TimeUnit.SECONDS)).isTrue();
            // 第二次提交回调固定捕获的分片号。
            int readyShard = secondShard;
            pipelines.submit(secondShard, 2, () -> adapter.placeDirect(
                    readyShard, 2, secondId.getMostSignificantBits(), secondId.getLeastSignificantBits(), 1001, 102, second, secondTarget), secondTarget);

            awaitDirect(secondTarget);
            // 已经就绪的第二笔事件别名，用于检查接受状态及业务时间。
            var secondResult = secondTarget;
            assertThat(secondResult.accepted()).isTrue();
            assertThat(com.surprising.aeron.service.matcher.MatcherEventFixtures.nativeResult(secondResult).timestamp()).isEqualTo(1_001);
            assertThat(firstTarget.ready()).isFalse();
            releaseFirst.countDown();
            awaitDirect(firstTarget);
            // 放行后就绪的第一笔事件别名，用于检查接受状态及业务时间。
            var firstResult = firstTarget;
            assertThat(firstResult.accepted()).isTrue();
            assertThat(com.surprising.aeron.service.matcher.MatcherEventFixtures.nativeResult(firstResult).timestamp()).isEqualTo(1_000);
        } finally {
            releaseFirst.countDown();
            pipelines.closeShards(adapter::closeShard);
            if (previous == null) System.clearProperty("surprising.aeron.matching-engines");
            else System.setProperty("surprising.aeron.matching-engines", previous);
        }
    }

    /**
     * 最多五秒自旋等待直达事件 ready，超时抛 AssertionError，不推进账户 Lane 结算。
     * @param event 等待 Matcher 事实发布就绪的直达结算事件
     */
    private static void awaitDirect(com.surprising.aeron.service.settlement.MatcherSettlementEvent event) {
        // 当前轮询的 nanoTime 截止点。
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!event.ready()) {
            if (System.nanoTime() >= deadline) throw new AssertionError("Direct Matcher result timed out");
            Thread.onSpinWait();
        }
    }

    /**
     * 同一 Core 槽重复路由被拒，另一命令在单槽队列满时提交失败须释放路由；收集后继续提交并复用命令槽，验证路由重置及新 Core 证据身份。
     */
    @Test
    void sequenceSlotRejectsDuplicateRoutesAndRecoversAfterQueueRejection() {
        // Owner 命令槽环，用于验证结果直接发布和槽位复用。
        var contexts = new CommandSlotRing(4);
        // 第一笔订单输入，路由拒绝测试中则是第一条命令槽。
        var first = contexts.claim(1);
        // 第二笔订单输入，路由拒绝测试中则是第二条命令槽。
        var second = contexts.claim(2);
        try (/* 本测试使用的 Matcher 分片组。 */ var pipelines = new MatcherPipelineGroup(2, 1, contexts)) {
            pipelines.start(null, TradingCpuAffinity.disabled());
            pipelines.submit(0, 1, () -> new com.surprising.aeron.service.matcher.CoreMatchingResult(true, "ONE"), null);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> pipelines.submit(1, 1,
                    () -> new com.surprising.aeron.service.matcher.CoreMatchingResult(true, "DUPLICATE"), null))
                    .isInstanceOf(IllegalStateException.class);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> pipelines.submit(0, 2,
                    () -> new com.surprising.aeron.service.matcher.CoreMatchingResult(true, "FULL"), null))
                    .isInstanceOf(java.util.concurrent.RejectedExecutionException.class);
            assertThat(second.submittedMatcherShard()).isEqualTo(-1);
            assertThat(await(pipelines, contexts, 1, TimeUnit.SECONDS.toNanos(5))).isNotNull();
            assertThat(first.submittedMatcherShard()).isEqualTo(-1);
            pipelines.submit(1, 1, () -> new com.surprising.aeron.service.matcher.CoreMatchingResult(true, "CONTINUATION"), null);
            assertThat(await(pipelines, contexts, 1, TimeUnit.SECONDS.toNanos(5)).resultCode()).isEqualTo("CONTINUATION");
            contexts.discard(1);
            // 复用后的命令槽对象，要求不再保留旧命令的路由和结果。
            var reused = contexts.claim(5);
            assertThat(reused.submittedMatcherShard()).isEqualTo(-1);
            pipelines.submit(0, 5, () -> new com.surprising.aeron.service.matcher.CoreMatchingResult(true, "REUSED"), null);
            assertThat(await(pipelines, contexts, 5, TimeUnit.SECONDS.toNanos(5)).nativeCoreSequence()).isEqualTo(5);
        }
    }

    /** 预占测试命令的现有槽，结果仍由真实 Matcher 直接发布。 */
    private static CommandSlotRing contexts(int capacity, long... sequences) {
        var contexts = new CommandSlotRing(capacity);
        for (long sequence : sequences) contexts.claim(sequence);
        return contexts;
    }

    /**
     * 创建买入一单位、价格一百的 GTC 限价撮合输入，不包含账户冻结或业务准入。
     * @param orderId 人工下单的订单号
     * @param instrumentId 数字字符串品种编号
     */
    private static ResolvedPlaceOrder order(long orderId, String instrumentId) {
        return DirectMatcherTestInputs.order(
                ProductLine.SPOT, orderId, instrumentId, CoreOrderSide.BUY, CoreOrderType.LIMIT,
                CoreTimeInForce.GTC, 100, 1);
    }

    /**
     * 最多五秒等待 worker 放行门闩，中断时恢复中断标记并抛异常。
     * @param latch 放行被阻塞 worker 的测试门闩
     */
    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("release timed out");
        } catch (/* 门闩等待中的中断异常，恢复线程中断状态后抛出。 */ InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("matcher wait interrupted", exception);
        }
    }
    /**
     * 在纳秒截止前回收传输槽并从命令槽收取指定 Core 结果，超时返回空。
     * @param pipeline 需要收集结果的分片组
     * @param sequence 需要收集的 Core 序号
     * @param timeout 纳秒自旋等待上限
     */
    private static com.surprising.aeron.service.matcher.CoreMatchingResult await(
            MatcherPipelineGroup pipeline, CommandSlotRing contexts, long sequence, long timeout) {
        // 当前轮询的 nanoTime 截止点。
        long deadline = System.nanoTime() + timeout;
        do {
            // 从命令槽或组中收取的普通撮合回执。
            pipeline.drainMatchingCompletions();
            var result = contexts.required(sequence).takeMatchingCompletion();
            if (result != null) return result;
            Thread.onSpinWait();
        } while (System.nanoTime() < deadline);
        return null;
    }
}
