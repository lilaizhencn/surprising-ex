package com.surprising.aeron.service.core;
import com.surprising.aeron.bootstrap.OwnerIdleStrategy;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 Owner 等待器的自旋、自适应退避与唤醒竞态，重点覆盖最后一次空探测期间发布工作时不会丢失通知。测试修改全局 JVM 属性，每次结束恢复原值。
 */
class OwnerIdleStrategyTest {
    /** 测试控制 Owner 等待方式的 JVM 属性名。 */
    private static final String PROPERTY = "surprising.aeron.owner-wait-strategy";
    /** 测试开始前的原属性值，结束时还原。 */
    private String previous;

    /**
     * 保存原等待策略属性，临时设置 ADAPTIVE，保证本次夹具使用确定的退避方式。
     */
    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        previous = System.getProperty(PROPERTY);
        System.setProperty(PROPERTY, "ADAPTIVE");
    }

    /**
     * 还原测试进入前的属性，原来不存在就清除。
     */
    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        if (previous == null) System.clearProperty(PROPERTY);
        else System.setProperty(PROPERTY, previous);
    }

    /**
     * 显式选择 BUSY_SPIN，连续空转一万次仍不能调用自适应的完成探测函数。
     */
    @Test void busySpinStrategyDoesNotEnterCompletionProbe() {
        System.setProperty(PROPERTY, "BUSY_SPIN");
        // 工作可用探测函数被调用的累计次数
        var probes = new java.util.concurrent.atomic.AtomicInteger();
        // 本场景实际使用的 Owner 等待器
        var idle = new OwnerIdleStrategy(() -> { probes.incrementAndGet(); return false; });
        idle.bindOwner();
        for (/* 当前连续空轮次或自旋轮次计数 */ int i = 0; i < 10_000; i++) idle.idle(0, true);
        assertThat(probes.get()).isZero();
    }

    /**
     * 用原子位把发布卡在最后空探测之后，故意把休眠放大到两秒；要求通知使 Owner 一秒内结束，并在 finally 释放、打断及回收测试线程。
     */
    @Test void publicationDuringTheFinalEmptyProbeDoesNotLoseTheWakeup() throws Exception {
        // 通知主测试线程：Owner 已读到最后一次空结果
        var probing = new CountDownLatch(1);
        // 主测试线程已经完成本轮发布和通知的原子标记
        var published = new AtomicBoolean();
        // Owner 验证线程结束的完成门闩
        var finished = new CountDownLatch(1);
        // 模拟既有工作状态的原子位
        var available = new AtomicBoolean();
        // Owner 验证线程捕获的故障，交回主线程断言
        var failure = new AtomicReference<Throwable>();
        // 本场景实际使用的 Owner 等待器
        var idle = new OwnerIdleStrategy(() -> {
            // 发布前读到的空结果，故意保留以制造最后探测竞态
            boolean observed = available.get();
            probing.countDown();
            // 等待测试发布时防止无限自旋的五秒截止时间
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!published.get()) {
                if (System.nanoTime() >= deadline) throw new AssertionError("publication timed out");
                Thread.onSpinWait(); // 此处不能用会消费 unpark permit 的阻塞同步器。
            }
            return observed;
        });
        // 通过反射放大实际休眠时间的字段
        var parkPeriod = OwnerIdleStrategy.class.getDeclaredField("parkNanos");
        parkPeriod.setAccessible(true);
        parkPeriod.setLong(idle, TimeUnit.SECONDS.toNanos(2));
        // 绑定等待器并执行最终探测的测试 Owner 线程
        Thread owner = Thread.ofPlatform().start(() -> {
            try {
                idle.bindOwner();
                for (/* 当前连续空轮次或自旋轮次计数 */ int i = 0; i < 110; i++) idle.idle(0, false);
                idle.idle(0, false);
                // 不能依赖生产环境的100us定时唤醒掩盖丢通知；本次park人为拉长到2秒。
                assertThat(available.get()).isTrue();
                idle.idle(1, false);
            } catch (/* 验证线程发生的任意故障，记录后通知测试结束 */ Throwable e) { failure.set(e); }
            finally { finished.countDown(); }
        });
        try {
            assertThat(probing.await(5, TimeUnit.SECONDS)).isTrue();
            available.set(true);
            idle.signal();
        } finally { published.set(true); }
        try { assertThat(finished.await(1, TimeUnit.SECONDS)).isTrue(); }
        finally { owner.interrupt(); owner.join(5_000); }
        assertThat(failure.get()).isNull();
    }

    /**
     * 多次空转到休眠阈值前再报告实际进展，要求每次重置退避；只有连续空轮次达到阈值才进入探测。
     */
    @Test void realProgressResetsBackoffWithoutProbingOrParking() {
        // 工作可用探测函数被调用的累计次数
        var probes = new java.util.concurrent.atomic.AtomicInteger();
        // 本场景实际使用的 Owner 等待器
        var idle = new OwnerIdleStrategy(() -> { probes.incrementAndGet(); return true; });
        idle.bindOwner();
        for (/* 重复验证实际进展重置退避的轮次 */ int round = 0; round < 20; round++) {
            for (/* 当前连续空轮次或自旋轮次计数 */ int i = 0; i < 110; i++) idle.idle(0, false);
            idle.idle(1, false);
        }
        assertThat(probes.get()).isZero();
        for (/* 当前连续空轮次或自旋轮次计数 */ int i = 0; i < 111; i++) idle.idle(0, false);
        assertThat(probes.get()).isOne();
    }

    /**
     * 在途命令不能反复重置空轮次；连续空转仍须进入完成探测，只有正工作量才重新开始退避。
     */
    @Test void pendingCommandsWithoutProgressReachTheCompletionProbe() {
        // 工作可用探测函数被调用的累计次数
        var probes = new java.util.concurrent.atomic.AtomicInteger();
        // 本场景实际使用的 Owner 等待器
        var idle = new OwnerIdleStrategy(() -> { probes.incrementAndGet(); return true; });
        idle.bindOwner();
        for (/* 当前连续空轮次或自旋轮次计数 */ int i = 0; i < 110; i++) idle.idle(0, true);
        assertThat(probes.get()).isZero();
        idle.idle(0, true);
        assertThat(probes.get()).isOne();
        idle.idle(1, true);
        for (/* 当前连续空轮次或自旋轮次计数 */ int i = 0; i < 110; i++) idle.idle(0, true);
        assertThat(probes.get()).isOne();
    }
}
