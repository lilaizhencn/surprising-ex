package com.surprising.aeron.service.core;
import com.surprising.aeron.config.TradingCpuAffinity;
import com.surprising.aeron.service.matcher.CoreMatchingResult;
import com.surprising.aeron.service.matcher.pipeline.MatcherCommandPipeline;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证单分片 Matcher 流水线的队列顺序、查询栅栏、容量与异常转交。命令主要返回人工 CoreMatchingResult，不启动真实订单簿；并发用门闩制造明确顺序，等待大多限制五秒。
 */
class MatcherCommandPipelineTest {

    @Test
    void fatalReadCompletesItsFutureBeforeTheWorkerStops() {
        try (var pipeline = new MatcherCommandPipeline(0, 4, null)) {
            pipeline.start(null, TradingCpuAffinity.disabled());
            var failed = pipeline.readAtSubmissionFence(() -> { throw new AssertionError("capture failed"); });
            assertThatThrownBy(() -> failed.get(5, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(AssertionError.class);
            pipeline.close();
            assertThat(pipeline.workerStopped()).isTrue();
            assertThatThrownBy(() -> pipeline.poll(1)).isInstanceOf(AssertionError.class)
                    .hasMessage("capture failed");
        }
    }

    /**
     * 阻塞第一条命令，由同一 Owner 依次投递同栅栏与后栅栏读请求，验证各自看到一或二且第五次读取因信箱满失败；名称中的并发指 worker 与请求交错，不是多个生产者并发提交。
     */
    @Test
    void concurrentBookReadsKeepTheirIndividualFencesAndBoundedCapacity() throws Exception {
        // 确认第一条命令已进入 worker 的门闩。
        var entered = new CountDownLatch(1);
        // 测试线程放行被阻塞命令的门闩，finally 再次放行。
        var release = new CountDownLatch(1);
        // 模拟被命令写入、由查询读取的共享整数。
        var value = new java.util.concurrent.atomic.AtomicInteger();
        try (/* 测试创建的单分片流水线，资源退出时关闭 worker。 */ var pipeline = new MatcherCommandPipeline(0, 4, null)) {
            pipeline.start(null, TradingCpuAffinity.disabled());
            pipeline.submit(1, () -> {
                entered.countDown(); await(release); value.set(1);
                return new CoreMatchingResult(true, "ONE");
            }, null);
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            // 第一提交栅栏的读取 Future，循环测试中则是本轮首个命令序号。
            var first = pipeline.readAtSubmissionFence(value::get);
            // 与第一次查询处于同一提交位置的第二个读取 Future。
            var sameFence = pipeline.readAtSubmissionFence(value::get);
            pipeline.submit(2, () -> { value.set(2); return new CoreMatchingResult(true, "TWO"); }, null);
            // 第二条命令提交后的读取 Future，要求看到值二。
            var later = pipeline.readAtSubmissionFence(value::get);
            // 读信箱容量内最后一个后栅栏读取 Future。
            var last = pipeline.readAtSubmissionFence(value::get);
            // 超出读信箱容量的查询 Future，要求以拒绝执行完成。
            var overflow = pipeline.readAtSubmissionFence(value::get);
            assertThatThrownBy(() -> overflow.get(5, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(RejectedExecutionException.class);
            release.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(sameFence.get(5, TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(later.get(5, TimeUnit.SECONDS)).isEqualTo(2);
            assertThat(last.get(5, TimeUnit.SECONDS)).isEqualTo(2);
            assertThat(await(pipeline, 1, TimeUnit.SECONDS.toNanos(5)).accepted()).isTrue();
            assertThat(await(pipeline, 2, TimeUnit.SECONDS.toNanos(5)).accepted()).isTrue();
        } finally { release.countDown(); }
    }

    /**
     * 在两条写命令之间投递读取，要求只看到第一条写；再让读取函数抛运行异常，验证仅该 Future 失败而下一条普通命令仍完成。
     */
    @Test
    void backgroundReadObservesItsSubmissionFenceAndFailureDoesNotPoisonTrading() throws Exception {
        // 确认第一条命令已进入 worker 的门闩。
        var entered=new CountDownLatch(1);/* 测试线程放行被阻塞命令的门闩，finally 再次放行。 */ var release=new CountDownLatch(1);
        // 模拟被命令写入、由查询读取的共享整数。
        var value=new java.util.concurrent.atomic.AtomicInteger();
        try(/* 测试创建的单分片流水线，资源退出时关闭 worker。 */ var pipeline=new MatcherCommandPipeline(0, 4, null)) {
            pipeline.start(null, TradingCpuAffinity.disabled());
            pipeline.submit(1,()->{entered.countDown();await(release);value.set(1);return new CoreMatchingResult(true,"ONE");}, null);
            assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            // 两条模拟写入之间投递的整数查询 Future，不是真实交易快照。
            var snapshot=pipeline.readAtSubmissionFence(value::get);
            pipeline.submit(2,()->{value.set(2);return new CoreMatchingResult(true,"TWO");}, null);
            release.countDown();
            assertThat(snapshot.get(5,TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(await(pipeline, 1,TimeUnit.SECONDS.toNanos(5)).resultCode()).isEqualTo("ONE");
            assertThat(await(pipeline, 2,TimeUnit.SECONDS.toNanos(5)).resultCode()).isEqualTo("TWO");
            // 读取函数主动抛异常的 Future，验证失败不会阻断后续普通命令。
            var failed=pipeline.readAtSubmissionFence(()->{throw new IllegalArgumentException("read failed");});
            assertThatThrownBy(()->failed.get(5,TimeUnit.SECONDS)).hasCauseInstanceOf(IllegalArgumentException.class);
            pipeline.submit(3,()->new CoreMatchingResult(true,"THREE"), null);
            assertThat(await(pipeline, 3,TimeUnit.SECONDS.toNanos(5)).resultCode()).isEqualTo("THREE");
        } finally {release.countDown();}
    }

    /**
     * 第一条命令被门闩阻塞时填满四槽窗口，要求第五条被队满拒绝；放行后按提交顺序收齐结果并检查窗口清空及观测峰值。
     */
    @Test
    void keepsABoundedWindowAndPublishesCompletionsInCommandOrder() throws Exception {
        // 确认首条命令已进入 worker 的门闩。
        CountDownLatch firstStarted = new CountDownLatch(1);
        // 阻塞首条命令以填满窗口的放行门闩。
        CountDownLatch releaseFirst = new CountDownLatch(1);
        try (/* 测试创建的单分片流水线，资源退出时关闭 worker。 */ MatcherCommandPipeline pipeline = new MatcherCommandPipeline(0, 4, null)) {
            pipeline.start(null, TradingCpuAffinity.disabled());
            pipeline.submit(1, () -> {
                firstStarted.countDown();
                await(releaseFirst);
                return new CoreMatchingResult(true, "ONE");
            }, null);
            assertThat(firstStarted.await(5, TimeUnit.SECONDS)).isTrue();
            pipeline.submit(2, () -> new CoreMatchingResult(true, "TWO"), null);
            pipeline.submit(3, () -> new CoreMatchingResult(true, "THREE"), null);
            pipeline.submit(4, () -> new CoreMatchingResult(true, "FOUR"), null);

            assertThat(pipeline.submissionDepth() + pipeline.completionDepth()).isEqualTo(4);
            assertThat(pipeline.submissionHighWaterMark()).isEqualTo(4);
            assertThatThrownBy(() -> pipeline.submit(5, () -> new CoreMatchingResult(true, "FIVE"), null))
                    .isInstanceOf(RejectedExecutionException.class)
                    .hasMessageContaining("full");

            releaseFirst.countDown();
            assertThat(await(pipeline, 1, TimeUnit.SECONDS.toNanos(5)).resultCode()).isEqualTo("ONE");
            assertThat(await(pipeline, 2, TimeUnit.SECONDS.toNanos(5)).resultCode()).isEqualTo("TWO");
            assertThat(await(pipeline, 3, TimeUnit.SECONDS.toNanos(5)).resultCode()).isEqualTo("THREE");
            assertThat(await(pipeline, 4, TimeUnit.SECONDS.toNanos(5)).resultCode()).isEqualTo("FOUR");
            assertThat(pipeline.submissionDepth() + pipeline.completionDepth()).isZero();
            assertThat(pipeline.completionHighWaterMark()).isPositive();
        }
    }

    /**
     * 使 worker 执行函数抛出 IllegalStateException，要求 Owner 轮询时收到同类带原消息的异常；不证明所有致命发布分支。
     */
    @Test
    void propagatesMatcherFailureToTheOwner() {
        try (/* 测试创建的单分片流水线，资源退出时关闭 worker。 */ MatcherCommandPipeline pipeline = new MatcherCommandPipeline(0, 2, null)) {
            pipeline.start(null, TradingCpuAffinity.disabled());
            pipeline.submit(1, () -> {
                throw new IllegalStateException("matcher failed");
            }, null);

            assertThatThrownBy(() -> await(pipeline, 1, TimeUnit.SECONDS.toNanos(5)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("matcher failed");
        }
    }

    /**
     * 按二十再十的顺序提交，先探查十要求空，再依次取得二十与十，证明同分片按提交顺序消费而非按 Core 序号数值排序。
     */
    @Test
    void probingANonHeadCompletionDoesNotViolateShardOrder() {
        try (/* 测试创建的单分片流水线，资源退出时关闭 worker。 */ MatcherCommandPipeline pipeline = new MatcherCommandPipeline(0, 4, null)) {
            pipeline.start(null, TradingCpuAffinity.disabled());
            pipeline.submit(20, () -> new CoreMatchingResult(true, "FIRST-SUBMITTED"), null);
            pipeline.submit(10, () -> new CoreMatchingResult(true, "SECOND-SUBMITTED"), null);

            assertThat(await(pipeline, 10, TimeUnit.MILLISECONDS.toNanos(1))).isNull();
            assertThat(await(pipeline, 20, TimeUnit.SECONDS.toNanos(5)).resultCode())
                    .isEqualTo("FIRST-SUBMITTED");
            assertThat(await(pipeline, 10, TimeUnit.SECONDS.toNanos(5)).resultCode())
                    .isEqualTo("SECOND-SUBMITTED");
        }
    }

    /**
     * 重复一百轮双命令与短暂空闲，逐轮收齐两个结果并检查无在途槽；没有在本测试强制设置 PARKING，实际等待策略仍取系统配置或默认。
     */
    @Test
    void wakesAfterRepeatedIdleAndBurstCyclesWithoutLosingACompletion() {
        try (/* 测试创建的单分片流水线，资源退出时关闭 worker。 */ MatcherCommandPipeline pipeline = new MatcherCommandPipeline(0, 8, null)) {
            pipeline.start(null, TradingCpuAffinity.disabled());
            for (/* 当前双命令空闲循环轮次，从一到一百。 */ long cycle = 1; cycle <= 100; cycle++) {
                // 第一提交栅栏的读取 Future，循环测试中则是本轮首个命令序号。
                long first = cycle * 2 - 1;
                // 当前循环第二个命令序号。
                long second = cycle * 2;
                pipeline.submit(first, () -> new CoreMatchingResult(true, "FIRST"), null);
                pipeline.submit(second, () -> new CoreMatchingResult(true, "SECOND"), null);

                assertThat(await(pipeline, first, TimeUnit.SECONDS.toNanos(5)).resultCode())
                        .isEqualTo("FIRST");
                assertThat(await(pipeline, second, TimeUnit.SECONDS.toNanos(5)).resultCode())
                        .isEqualTo("SECOND");
                assertThat(pipeline.submissionDepth() + pipeline.completionDepth()).isZero();
                java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MICROSECONDS.toNanos(200));
            }
        }
    }

    /**
     * 最多五秒等待测试门闩，超时抛异常；被中断时恢复中断标记再包装异常。
     * @param latch worker 等待测试线程放行的门闩
     */
    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test matcher release timed out");
            }
        } catch (/* 门闩等待中断异常，恢复中断状态后向外抛出。 */ InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("test matcher interrupted", exception);
        }
    }
    /**
     * 对指定 Core 序号自旋轮询到纳秒截止，成功返回结果，超时返回空；不会取消任务或消费其他 token。
     * @param pipeline 当前待收集的单分片流水线
     * @param sequence 期望收集的正 Core 序号
     * @param timeout 当前自旋等待的纳秒上限
     */
    private static com.surprising.aeron.service.matcher.CoreMatchingResult await(
            MatcherCommandPipeline pipeline, long sequence, long timeout) {
        // 本次轮询的 nanoTime 截止点。
        long deadline = System.nanoTime() + timeout;
        do {
            // 当前序号探查返回的可空撮合回执。
            var result = pipeline.poll(sequence);
            if (result != null) return result;
            Thread.onSpinWait();
        } while (System.nanoTime() < deadline);
        return null;
    }
}
