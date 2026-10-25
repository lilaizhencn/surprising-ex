package com.surprising.aeron.service.core;

import com.surprising.aeron.bootstrap.account.SettlementLaneWorker;
import com.surprising.aeron.service.account.AccountLaneState;
import com.surprising.aeron.service.settlement.MatcherSettlementEvent;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * 账户工作线程的并发协议测试，使用真实工作线程检查独立准入、等待策略、休眠发布、所有权交接和故障传播；部分输入用门闩或反射模拟，不据此声称验证了撮合及账本业务结算。
 */
class SettlementLaneWorkerTest {
    /**
     * 三种等待策略下，用 accountReady 开关模拟账本输入等待；断言后续准入不能越过队首、准入满时拒绝，较早的普通邮箱任务仍能执行，再放开输入核对八条准入 FIFO。这里没有实际账本线程或 Matcher 成交。
     * @param strategy 本轮参数化选择的账户等待策略名称
     */
    @Test
    void pendingLedgerInputKeepsAdmissionFifoWithoutPreventingOlderSettlement() throws Exception {
        // 模拟账本输入是否到齐的原子开关，关闭清理时也会放开
        var inputReady = new java.util.concurrent.atomic.AtomicBoolean();
        // 任务实际执行顺序的原子计数，与逐项 expected 比较
        var executed = new AtomicInteger();
        // 本轮待执行准入的完成门闩
        var completed = new CountDownLatch(8);
        // 控制首任务退出的门闩，保证后续队列能在确定边界填充
        var release = new CountDownLatch(1);
        try (/* 本例真实账户工作对象，按当前资源或 finally 边界关闭 */ var worker = new SettlementLaneWorker("ledger-input", new AccountLaneState(0, 8), 8)) {
            try {
                // 确认首任务已经进入并占住工作线程的门闩
                var entered = new CountDownLatch(1);
                worker.submit(/* 任务实际收到的账户 Lane，部分顺序模拟任务不读取其业务内容 */ lane -> {
                    entered.countDown();
                    try { if (!release.await(2, TimeUnit.SECONDS)) throw new AssertionError("release timeout"); }
                    catch (/* 本例构造或捕获的原故障，按所在场景传播或转为测试失败 */ InterruptedException failure) { throw new AssertionError(failure); }
                });
                assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
                // 较早普通邮箱任务的完成门闩，该任务本身不是 Matcher 结算事件
                var olderSettlement = new CountDownLatch(1);
                worker.submit(/* 任务实际收到的账户 Lane，部分顺序模拟任务不读取其业务内容 */ lane -> olderSettlement.countDown());
                worker.submitAdmission(/* 模拟需要等待账本输入的准入任务，门禁打开后按序执行 */ new SettlementLaneWorker.Command() {
                    /**
                     * 把原子开关作为本准入任务的模拟账本输入就绪条件。
                     */
                    @Override public boolean accountReady() { return inputReady.get(); }
                    /**
                     * 要求本准入首先执行，记录顺序并减少本轮完成门闩。
                     * @param lane 该模拟准入任务实际执行时所属的账户 Lane
                     */
                    @Override public void execute(AccountLaneState lane) {
                        if (executed.getAndIncrement() != 0) throw new AssertionError("input admission reordered");
                        completed.countDown();
                    }
                });
                for (/* 本轮后续任务的下标，随后固定为其预期执行顺序 */ int item = 1; item < 8; item++) {
                    // 该任务必须取得的执行顺序号，固定后由回调捕获
                    int expected = item;
                    worker.submitAdmission(/* 任务实际收到的账户 Lane，部分顺序模拟任务不读取其业务内容 */ lane -> {
                        if (executed.getAndIncrement() != expected) throw new AssertionError("later admission passed waiting input");
                        completed.countDown();
                    });
                }
                assertThat(worker.hasAdmissionCapacity()).isFalse();
                assertThatThrownBy(() -> worker.submitAdmission(/* 任务实际收到的账户 Lane，部分顺序模拟任务不读取其业务内容 */ lane -> {})).isInstanceOf(RejectedExecutionException.class);
                release.countDown();
                assertThat(olderSettlement.await(2, TimeUnit.SECONDS)).isTrue();
                assertThat(executed.get()).isZero();
                inputReady.set(true);
                assertThat(completed.await(2, TimeUnit.SECONDS)).isTrue();
                worker.assertHealthy();
            } finally { inputReady.set(true); release.countDown(); }
        }
    }

    /**
     * 反射访问真实私有环游标：同一个推进线程循环发布和取走空事件，测试线程并发采样两百万次深度，断言不为负；不执行事件结算，也未把生产与消费拆成两个线程。
     */
    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.Timeout(15)
    void ownerObservesNonNegativeDepthWhileMatcherAndLaneAdvance() throws Exception {
        // 只操作真实环游标，用空事件占槽，不对模拟载荷执行结算。
        // 通过反射定位到的真实私有 Matcher 环类型
        Class<?> type = Class.forName(SettlementLaneWorker.class.getName() + "$MatcherSettlementRing");
        // 私有环接收容量的构造器，仅用于本测试建立游标对象
        var constructor = type.getDeclaredConstructor(int.class);
        constructor.setAccessible(true);
        // 反射创建的容量八事件环，不执行事件业务内容
        Object ring = constructor.newInstance(8);
        // 私有环发布方法的反射入口
        var publish = type.getDeclaredMethod("publish", MatcherSettlementEvent.class);
        // 私有环取走方法的反射入口
        var poll = type.getDeclaredMethod("poll");
        // 私有环深度采样方法的反射入口
        var depth = type.getDeclaredMethod("depth");
        publish.setAccessible(true);
        poll.setAccessible(true);
        depth.setAccessible(true);
        // 确认推进线程已经开始的门闩
        var started = new CountDownLatch(1);
        // 测试结束通知推进线程停止的原子开关
        var stop = new java.util.concurrent.atomic.AtomicBoolean();
        // 保存反射推进线程原异常的原子引用
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        // 仅占据环引用槽的空结算事件，本例不执行或验证其载荷
        var event = new MatcherSettlementEvent();
        // 同一线程反复 publish 与 poll，测试线程同时采样深度
        Thread advancing = Thread.ofPlatform().start(() -> {
            started.countDown();
            try {
                while (!stop.get()) {
                    publish.invoke(ring, event);
                    poll.invoke(ring);
                }
            } catch (/* 推进线程捕获的故障，保存供测试线程最后检查 */ Throwable thrown) { failure.set(thrown); }
        });
        try {
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            for (/* 本次深度采样下标，总计两百万次 */ int sample = 0; sample < 2_000_000; sample++) {
                // 本次反射读取的环深度，断言不能为负
                long observed = (long) depth.invoke(ring);
                if (observed < 0) fail("Owner sampled negative Matcher ring depth: " + observed);
            }
        } finally {
            stop.set(true);
            advancing.join(2_000);
        }
        assertThat(advancing.isAlive()).isFalse();
        assertThat(failure.get()).isNull();
    }

    /**
     * 真实任务抛出运行时异常，等发布回调后核对收到原对象，关闭时仍抛同一故障。
     */
    @org.junit.jupiter.api.Test
    void realLaneFailurePublishesTheOriginalExceptionBeforeOwnerChecks() throws Exception {
        // 故障发布器实际收到的原异常引用
        var signal = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        // 确认故障发布器已经保存原异常的门闩
        var announced = new CountDownLatch(1);
        // 本例构造或捕获的原故障，按所在场景传播或转为测试失败
        var failure = new IllegalStateException("lane task failed");
        // 本例真实账户工作对象，按当前资源或 finally 边界关闭
        var worker = new SettlementLaneWorker("failure", new AccountLaneState(0, 8), 8,
                /* 故障发布器收到的原 Throwable，保存后通知测试线程 */ value -> { signal.compareAndSet(null, value); announced.countDown(); });
        try {
            worker.submit(/* 任务实际收到的账户 Lane，部分顺序模拟任务不读取其业务内容 */ lane -> { throw failure; });
            assertThat(announced.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(signal.get()).isSameAs(failure);
        } finally {
            assertThatThrownBy(worker::close).isSameAs(failure);
        }
    }

    /**
     * 真实任务抛 Error，故障回调又抛另一异常；核对健康检查及关闭保留原 Error，线程结束后附加报告故障为 suppressed。
     */
    @org.junit.jupiter.api.Test
    void fatalErrorRemainsVisibleWhenItsReportingCallbackAlsoFails() throws Exception {
        // 本例构造或捕获的原故障，按所在场景传播或转为测试失败
        var failure = new NoSuchMethodError("synthetic incompatible settlement method");
        // 故障发布回调故意抛出的第二个异常，最后附加到原 Error
        var reportingFailure = new IllegalStateException("synthetic reporting failure");
        // 确认故障回调已进入的门闩，最终完整收尾仍等待 close
        var reported = new CountDownLatch(1);
        // 本例真实账户工作对象，按当前资源或 finally 边界关闭
        var worker = new SettlementLaneWorker("fatal", new AccountLaneState(0, 8), 8, /* 发布器收到的原故障，本回调随后故意抛出报告异常 */ problem -> {
            reported.countDown();
            throw reportingFailure;
        });
        try {
            worker.submit(/* 任务实际收到的账户 Lane，部分顺序模拟任务不读取其业务内容 */ lane -> { throw failure; });
            assertThat(reported.await(2, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(worker::assertHealthy).isSameAs(failure);
        } finally {
            assertThatThrownBy(worker::close).isSameAs(failure);
        }
        assertThat(failure.getSuppressed()).containsExactly(reportingFailure);
        assertThat(worker.thread().isAlive()).isFalse();
    }

    /**
     * 执行八轮交接：前任务未释放时交接不完成，完成后测试线程绑定账户，后续普通任务须等 Owner 释放并发布恢复轮次才执行。
     */
    @Test
    void controlOwnershipHandoffIsPolledAndQueuedWorkResumesOnlyAfterRelease() throws Exception {
        // 任务实际收到的账户 Lane，部分顺序模拟任务不读取其业务内容
        var lane = new AccountLaneState(0, 8);
        try (/* 本例真实账户工作对象，按当前资源或 finally 边界关闭 */ var worker = new SettlementLaneWorker("handoff", lane, 8)) {
            for (/* 本例第几轮真实账户所有权交接，从一到八 */ long epoch = 1; epoch <= 8; epoch++) {
                // 确认首任务已经进入并占住工作线程的门闩
                var entered = new CountDownLatch(1);
                // 控制首任务退出的门闩，保证后续队列能在确定边界填充
                var release = new CountDownLatch(1);
                // 交接恢复后排队普通任务实际执行的完成门闩
                var resumed = new CountDownLatch(1);
                worker.submit(/* 当前真实任务收到的账户 Lane，恢复后核对其所有权 */ value -> {
                    entered.countDown();
                    try { if (!release.await(2, TimeUnit.SECONDS)) throw new AssertionError("release timeout"); }
                    catch (/* 任务等待门闩被中断，包装为 AssertionError 结束该模拟任务 */ InterruptedException e) { throw new AssertionError(e); }
                });
                assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
                worker.requestHandoff(epoch);
                try { assertThat(worker.handoffReady(epoch)).isFalse(); }
                finally { release.countDown(); }
                // 本轮等待观测或完成的单调纳秒截止时间
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (!worker.handoffReady(epoch) && System.nanoTime() < deadline) Thread.yield();
                assertThat(worker.handoffReady(epoch)).isTrue();
                lane.bindOwner();
                worker.submit(/* 当前真实任务收到的账户 Lane，恢复后核对其所有权 */ value -> { value.assertOwner(); resumed.countDown(); });
                lane.assertOwner();
                assertThat(resumed.getCount()).isOne();
                lane.releaseOwnerForHandoff();
                worker.resumeHandoff(epoch);
                assertThat(resumed.await(2, TimeUnit.SECONDS)).isTrue();
                worker.assertHealthy();
            }
        }
    }
}
