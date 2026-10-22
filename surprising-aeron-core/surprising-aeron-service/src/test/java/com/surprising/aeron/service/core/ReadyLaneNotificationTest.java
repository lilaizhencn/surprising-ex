package com.surprising.aeron.service.core;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicIntegerArray;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 就绪位逐代确认协议测试，覆盖八个位含最高位63；每位单生产者等确认再发下一代，不把就绪位当任意发布次数计数器。
 */
class ReadyLaneNotificationTest {
    /**
     * 八个生产线程每位各发一万代且逐代等确认，消费方采样清位并确认，最后检查所有代数一致、无故障且提示清空。
     */
    @Test void concurrentLanesKeepEveryAcknowledgedGenerationIncludingBit63() throws Exception {
        // 本例选择的八个 Lane 位下标，包含最高位63
        int[] lanes = {0, 1, 2, 7, 15, 31, 47, 63};
        // 每条 Lane 需要逐代确认的次数，固定一万
        int generations = 10_000;
        // 真实64位提示数组，采样和清除不代替业务结果
        var ready = new TradingRuntimeState.PaddedReadyMask(64);
        // 每条 Lane 已确认的代数，生产线程等到确认再发布下一代
        var acknowledged = new AtomicIntegerArray(64);
        // 让各生产线程开始竞争发布的统一门闩
        var start = new CountDownLatch(1);
        // 各生产线程 finally 退出时减少的完成门闩
        var done = new CountDownLatch(lanes.length);
        // 各生产者首次故障的原子引用
        var failed = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        // 各线程共用的15秒单调纳秒截止时间
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        for (int /* 本次生产、确认或最终核对的 Lane 位下标 */ lane : lanes) Thread.ofPlatform().start(() -> {
            try {
                start.await();
                for (/* 本生产者当前等待确认的代数 */ int generation = 1; generation <= generations; generation++) {
                    ready.setBit(lane);
                    while (acknowledged.get(lane) < generation) {
                        if (System.nanoTime() - deadline >= 0) throw new AssertionError("lost Lane notification: " + lane);
                        Thread.onSpinWait();
                    }
                }
            } catch (/* 生产或等待确认失败的原异常，首次保存供测试检查 */ Throwable failure) { failed.compareAndSet(null, failure); }
            finally { done.countDown(); }
        });
        start.countDown();
        while (done.getCount() != 0 && System.nanoTime() - deadline < 0) {
            // 消费方本轮采样并清除的提示位集合
            long mask = ready.getAndClear();
            while (mask != 0) {
                // 本次生产、确认或最终核对的 Lane 位下标
                int lane = Long.numberOfTrailingZeros(mask);
                mask &= mask - 1;
                acknowledged.incrementAndGet(lane);
            }
        }
        assertThat(done.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(failed.get()).isNull();
        for (int /* 本次生产、确认或最终核对的 Lane 位下标 */ lane : lanes) assertThat(acknowledged.get(lane)).isEqualTo(generations);
        assertThat(ready.get()).isZero();
    }
}
