package com.surprising.aeron.service.state.ledger;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * 原 Treasury 账本线程的实际并发回归测试。覆盖单写者、FIFO、邮箱容量、槽复用、故障与关闭及停车窗口；账户回执多由原子标记模拟，其中独立账户示例只是普通计数线程，不等同真实 SettlementLaneWorker。
 */
class SystemLedgerLaneTest {
    /**
     * 真实账本线程接管原 Treasury 后，检查创建线程直接读写及释放所有权均拒绝，任务在原对象上更新资金，关闭后原线程能重新读取结果。
     */
    @Test
    void originalTreasuryHasOneWriterUntilWorkerShutdown() throws Exception {
        // 本用例原 Treasury 对象，或任务回调收到的同一财库，不是账本副本。
        TreasuryRuntime ledger = new TreasuryRuntime();
        ledger.setFee(3, 17);
        ledger.setInsurance(3, 100, 0);
        ledger.clearChangedKeys();
        // 跨线程接收账本异常的原子引用，结束时检查。
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try (/* 实际启动的系统账本执行器，按用例选择自动或显式关闭。 */ var lane = new SystemLedgerLane(ledger, 256, failure::set)) {
            assertThatThrownBy(() -> ledger.fee(3)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> ledger.adjustInsurance(3, 1)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(ledger::releaseOwnerForHandoff).isInstanceOf(IllegalStateException.class);
            // 对应资金任务执行到通知点后释放的门闩。
            CountDownLatch done = new CountDownLatch(1);
            assertThat(lane.offer(/* 账本任务回调收到的同一权威 Treasury。 */ actual -> {
                assertThat(actual).isSameAs(ledger);
                assertThat(Thread.currentThread().getName()).isEqualTo("core-system-ledger-lane");
                actual.setFee(3, Math.addExact(actual.fee(3), 5));
                actual.adjustInsurance(3, -10);
                actual.clearChangedKeys();
                done.countDown();
            })).isTrue();
            assertThat(done.await(3, TimeUnit.SECONDS)).isTrue();
            lane.assertHealthy();
        }
        assertThat(failure).hasValue(null);
        assertThat(ledger.fee(3)).isEqualTo(22);
        assertThat(ledger.insurance(3)).isEqualTo(90);
    }

    /**
     * 用原子标记延迟首任务，检查后续扣款不能越过队首；另启只通知门闩的普通线程证明它可运行，放行后最终保险为十，不把此线程当真实账户业务。
     */
    @Test
    void queuedMoneyWaitsForItsAccountReceiptAndKeepsLogOrder() throws Exception {
        // 本用例原 Treasury 对象，或任务回调收到的同一财库，不是账本副本。
        TreasuryRuntime ledger = new TreasuryRuntime();
        // 模拟账户回执是否到达的原子开关，控制队首就绪。
        AtomicBoolean ready = new AtomicBoolean();
        // 对应资金任务执行到通知点后释放的门闩。
        // 账本已经第一次检查队首 readiness 的通知门闩。
        CountDownLatch firstRead = new CountDownLatch(1), done = new CountDownLatch(1);
        // 跨线程接收账本异常的原子引用，结束时检查。
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try (/* 实际启动的系统账本执行器，按用例选择自动或显式关闭。 */ var lane = new SystemLedgerLane(ledger, 256, failure::set)) {
            assertThat(lane.offer(/* 由模拟回执开关或固定 false 控制就绪的测试账本任务，输入和结果只在本用例生命周期使用。 */ new SystemLedgerLane.Entry() {
                /**
                 * 通知主线程账本已经检查过队首，并读取模拟账户回执标记。
                 */
                @Override public boolean ready() { firstRead.countDown(); return ready.get(); }
                /**
                 * 首任务就绪后在原财库设置三十保险，供下一任务按 FIFO 扣减。
                 * @param actual 当前测试任务由账本线程独占持有的原 Treasury
                 */
                @Override public void apply(TreasuryRuntime actual) { actual.setInsurance(1, 30, 0); }
            })).isTrue();
            assertThat(lane.offer(/* 账本任务回调收到的同一权威 Treasury。 */ actual -> {
                assertThat(actual.insurance(1)).isEqualTo(30);
                actual.adjustInsurance(1, -20);
                actual.clearChangedKeys();
                done.countDown();
            })).isTrue();
            try {
                assertThat(firstRead.await(3, TimeUnit.SECONDS)).isTrue();
                assertThat(done.getCount()).isEqualTo(1);
                // 此处独立普通线程只释放门闩，核对它未被账本等待阻止；不运行真实账户 Lane 业务。
                // 独立普通线程用于证明可继续运行的通知门闩，不执行账户资金业务。
                CountDownLatch accountDone = new CountDownLatch(1);
                // 只释放计数门闩的独立普通线程，名称不代表实际账户 Lane。
                Thread account = new Thread(accountDone::countDown, "independent-account-lane");
                account.start();
                assertThat(accountDone.await(3, TimeUnit.SECONDS)).isTrue();
                account.join(3_000L);
            } finally { ready.set(true); }
            assertThat(done.await(3, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(failure).hasValue(null);
        assertThat(ledger.insurance(1)).isEqualTo(10);
    }

    /**
     * 逻辑容量二的邮箱被未就绪队首占满，第三项拒绝；另一个真实生产线程也被拒绝，放行后只执行前两项。
     */
    @Test
    void fullMailboxDoesNotAcceptAnUnownedSlotAndRejectsForeignProducers() throws Exception {
        // 本用例原 Treasury 对象，或任务回调收到的同一财库，不是账本副本。
        TreasuryRuntime ledger = new TreasuryRuntime();
        // 模拟账户回执是否到达的原子开关，控制队首就绪。
        AtomicBoolean ready = new AtomicBoolean();
        // 跨线程接收账本异常的原子引用，结束时检查。
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try (/* 实际启动的系统账本执行器，按用例选择自动或显式关闭。 */ var lane = new SystemLedgerLane(ledger, 2, failure::set)) {
            // 故意保持未就绪以占住邮箱容量的首个任务。
            SystemLedgerLane.Entry held = /* 由模拟回执开关或固定 false 控制就绪的测试账本任务，输入和结果只在本用例生命周期使用。 */ new SystemLedgerLane.Entry() {
                /**
                 * 使用原子标记延迟该测试队首，不提前释放传输容量。
                 */
                @Override public boolean ready() { return ready.get(); }
                /**
                 * 首任务放行后把测试费用设为一，后续第二项再改为二。
                 * @param actual 当前测试任务由账本线程独占持有的原 Treasury
                 */
                @Override public void apply(TreasuryRuntime actual) { actual.setFee(1, 1); }
            };
            try {
                assertThat(lane.offer(held)).isTrue();
                assertThat(lane.offer(/* 账本任务回调收到的同一权威 Treasury。 */ actual -> actual.setFee(1, 2))).isTrue();
                assertThat(lane.offer(/* 账本任务回调收到的同一权威 Treasury。 */ actual -> actual.setFee(1, 3))).isFalse();
                // 保存非原生产线程投递被拒绝异常的原子引用。
                AtomicReference<Throwable> foreign = new AtomicReference<>();
                // 尝试违规投递的另一真实线程，主线程有界等待其退出。
                Thread producer = new Thread(() -> {
                    try { lane.offer(held); } catch (/* 另一个生产线程捕获或账本失败回调收到的原始异常。 */ Throwable problem) { foreign.set(problem); }
                });
                producer.start(); producer.join(3_000L);
                assertThat(producer.isAlive()).isFalse();
                assertThat(foreign.get()).isInstanceOf(IllegalStateException.class);
            } finally { ready.set(true); }
        }
        assertThat(failure).hasValue(null);
        assertThat(ledger.fee(1)).isEqualTo(2);
    }

    /**
     * 复用二百五十六个固定任务一百二十八轮，完成位后核对任务捕获的真实费用和保险，最终累计金额正确；没有测量对象分配或数组容量增长。
     */
    @Test
    void repeatedSlotsPublishActualFundsBeforeCompletionAndRetainCapacity() {
        // 本用例原 Treasury 对象，或任务回调收到的同一财库，不是账本副本。
        TreasuryRuntime ledger = new TreasuryRuntime();
        // 跨线程接收账本异常的原子引用，结束时检查。
        AtomicReference<Throwable> failure = new AtomicReference<>();
        // 本用例重复借用的二百五十六个固定财务任务对象数组。
        TreasurySlot[] slots = new TreasurySlot[256];
        for (/* 初始化固定财务任务数组的当前下标。 */ int index = 0; index < slots.length; index++) slots[index] = new TreasurySlot();
        try (/* 实际启动的系统账本执行器，按用例选择自动或显式关闭。 */ var lane = new SystemLedgerLane(ledger, 256, failure::set)) {
            for (/* 重复入队和等待固定任务组的当前轮次。 */ int round = 1; round <= 128; round++) {
                for (TreasurySlot /* 当前需要重置完成位、投递或读取完成结果的固定财务任务。 */ slot : slots) {
                    slot.completed = false;
                    // 当前任务入队或完成等待的单调时钟截止点。
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                    while (!lane.offer(slot)) {
                        if (System.nanoTime() >= deadline) throw new AssertionError("mailbox offer timeout");
                        Thread.onSpinWait();
                    }
                }
                for (TreasurySlot /* 当前需要重置完成位、投递或读取完成结果的固定财务任务。 */ slot : slots) {
                    // 当前任务入队或完成等待的单调时钟截止点。
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                    while (!slot.completed) {
                        lane.assertHealthy();
                        if (System.nanoTime() >= deadline) throw new AssertionError("ledger completion timeout");
                        Thread.onSpinWait();
                    }
                    assertThat(slot.fee).isPositive();
                    assertThat(slot.insurance).isEqualTo(slot.fee * 2);
                }
            }
        }
        assertThat(failure).hasValue(null);
        assertThat(ledger.fee(1)).isEqualTo(32_768);
        assertThat(ledger.insurance(1)).isEqualTo(65_536);
    }

    /**
     * 任务中保险加法溢出，检查账本故障发布但任务完成位不发布，健康及关闭抛出原算术异常，费用和保险原值未被部分写入。
     */
    @Test
    void overflowIsFatalAndDoesNotPublishCompletionOrPartiallyWriteFunds() throws Exception {
        // 本用例原 Treasury 对象，或任务回调收到的同一财库，不是账本副本。
        TreasuryRuntime ledger = new TreasuryRuntime();
        ledger.setInsurance(1, Long.MAX_VALUE, 0);
        ledger.clearChangedKeys();
        // 故意在已有最大保险上再增加一的系统差额，同时带费用七以检查无部分写入。
        RuntimeTreasuryDelta delta = new RuntimeTreasuryDelta();
        delta.addFee(1, 7); delta.addInsurance(1, 1);
        // 只有资金应用正常返回才设置的原子完成位，溢出场景要求保持 false。
        AtomicBoolean completed = new AtomicBoolean();
        // 跨线程接收账本异常的原子引用，结束时检查。
        AtomicReference<Throwable> failure = new AtomicReference<>();
        // 账本故障回调已经捕获算术异常的通知门闩。
        CountDownLatch failed = new CountDownLatch(1);
        // 实际启动的系统账本执行器，按用例选择自动或显式关闭。
        var lane = new SystemLedgerLane(ledger, 256, /* 另一个生产线程捕获或账本失败回调收到的原始异常。 */ problem -> { failure.set(problem); failed.countDown(); });
        assertThat(lane.offer(/* 账本任务回调收到的同一权威 Treasury。 */ actual -> { delta.apply(actual); completed.set(true); })).isTrue();
        assertThat(failed.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(completed).isFalse();
        assertThatThrownBy(lane::assertHealthy).isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(lane::close).isInstanceOf(ArithmeticException.class);
        assertThat(failure.get()).isInstanceOf(ArithmeticException.class);
        assertThat(ledger.fee(1)).isZero();
        assertThat(ledger.insurance(1)).isEqualTo(Long.MAX_VALUE);
    }

    /**
     * 以始终未就绪任务关闭执行器，检查关闭及故障回调报告错误且任务没有实际应用。
     */
    @Test
    void shutdownRejectsUnreadyFinancialWorkInsteadOfSilentlyDroppingIt() {
        // 本用例原 Treasury 对象，或任务回调收到的同一财库，不是账本副本。
        TreasuryRuntime ledger = new TreasuryRuntime();
        // 记录未就绪关闭任务是否意外执行的原子标记。
        AtomicBoolean applied = new AtomicBoolean();
        // 跨线程接收账本异常的原子引用，结束时检查。
        AtomicReference<Throwable> failure = new AtomicReference<>();
        // 实际启动的系统账本执行器，按用例选择自动或显式关闭。
        var lane = new SystemLedgerLane(ledger, 256, failure::set);
        assertThat(lane.offer(/* 由模拟回执开关或固定 false 控制就绪的测试账本任务，输入和结果只在本用例生命周期使用。 */ new SystemLedgerLane.Entry() {
            /**
             * 始终返回 false，模拟关闭时仍缺账户回执的财务任务。
             */
            @Override public boolean ready() { return false; }
            /**
             * 记录任务若被意外应用的标记，正常用例要求此方法不被调用。
             * @param actual 当前测试任务由账本线程独占持有的原 Treasury
             */
            @Override public void apply(TreasuryRuntime actual) { applied.set(true); }
        })).isTrue();
        assertThatThrownBy(lane::close).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("before settlement was ready");
        assertThat(applied).isFalse();
        assertThat(failure.get()).isInstanceOf(IllegalStateException.class);
    }

    /**
     * 临时使用 BLOCKING 策略，五百一十二轮在停车标记后的复查窗口发布就绪及后续任务，检查任务最终全部执行并恢复原 JVM 属性。
     */
    @Test
    void accountReceiptPublishedInsideParkingWindowCannotLoseWakeup() throws Exception {
        // 本用例临时修改的账本等待策略 JVM 属性名。
        String key = "surprising.aeron.system-ledger-wait-strategy";
        // 用例开始前的等待策略属性值，finally 精确恢复或移除。
        String previous = System.getProperty(key);
        System.setProperty(key, "BLOCKING");
        try {
            // 本用例原 Treasury 对象，或任务回调收到的同一财库，不是账本副本。
            TreasuryRuntime ledger = new TreasuryRuntime();
            // 跨线程接收账本异常的原子引用，结束时检查。
            AtomicReference<Throwable> failure = new AtomicReference<>();
            try (/* 实际启动的系统账本执行器，按用例选择自动或显式关闭。 */ var lane = new SystemLedgerLane(ledger, 256, failure::set)) {
            for (/* 停车窗口竞争场景的当前重复轮次。 */ int iteration = 0; iteration < 512; iteration++) {
                // 模拟账户回执是否到达的原子开关，控制队首就绪。
                AtomicBoolean ready = new AtomicBoolean();
                // 放行被测试门闩阻塞的就绪复查回调，finally 保证释放。
                // 账本已公布停车标记并进入就绪复查窗口的通知门闩。
                CountDownLatch parkedWindow = new CountDownLatch(1), releaseCheck = new CountDownLatch(1);
                // 对应资金任务执行到通知点后释放的门闩。
                CountDownLatch done = new CountDownLatch(1);
                assertThat(lane.offer(/* 由模拟回执开关或固定 false 控制就绪的测试账本任务，输入和结果只在本用例生命周期使用。 */ new SystemLedgerLane.Entry() {
                    /**
                     * 在检测到停车窗口时用门闩延迟复查，让主线程恰在此窗口发布模拟回执，最后读取就绪标记。
                     */
                    @Override public boolean ready() {
                        if (!ready.get() && lane.parked()) {
                            parkedWindow.countDown();
                            try {
                                if (!releaseCheck.await(3, TimeUnit.SECONDS))
                                    throw new AssertionError("parking window not released");
                            } catch (/* 工作线程门闩等待收到的中断异常，当前分支把它转换为断言失败。 */ InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                throw new AssertionError(interrupted);
                            }
                        }
                        return ready.get();
                    }
                    /**
                     * 本轮首个任务在原财库把费用增加一，后续任务再增加一并通知完成。
                     * @param actual 当前测试任务由账本线程独占持有的原 Treasury
                     */
                    @Override public void apply(TreasuryRuntime actual) {
                        actual.setFee(1, Math.incrementExact(actual.fee(1)));
                    }
                })).isTrue();
                try {
                    assertThat(parkedWindow.await(3, TimeUnit.SECONDS)).isTrue();
                    ready.set(true);
                    assertThat(lane.offer(/* 账本任务回调收到的同一权威 Treasury。 */ actual -> {
                        actual.setFee(1, Math.incrementExact(actual.fee(1)));
                        actual.clearChangedKeys();
                        done.countDown();
                    })).isTrue();
                } finally { ready.set(true); releaseCheck.countDown(); }
                assertThat(done.await(3, TimeUnit.SECONDS)).isTrue();
            }
        }
        assertThat(failure).hasValue(null);
        assertThat(ledger.fee(1)).isEqualTo(1_024);
        } finally {
            if (previous == null) System.clearProperty(key);
            else System.setProperty(key, previous);
        }
    }

    /**
     * 关闭线程后投递资金任务应明确拒绝，原费用仍为零且没有额外故障。
     */
    @Test
    void closedMailboxRejectsFurtherMoneyWrites() {
        // 本用例原 Treasury 对象，或任务回调收到的同一财库，不是账本副本。
        TreasuryRuntime ledger = new TreasuryRuntime();
        // 跨线程接收账本异常的原子引用，结束时检查。
        AtomicReference<Throwable> failure = new AtomicReference<>();
        // 实际启动的系统账本执行器，按用例选择自动或显式关闭。
        var lane = new SystemLedgerLane(ledger, 256, failure::set);
        lane.close();
        assertThatThrownBy(() -> lane.offer(/* 账本任务回调收到的同一权威 Treasury。 */ actual -> actual.setFee(1, 1)))
                .isInstanceOf(java.util.concurrent.RejectedExecutionException.class);
        assertThat(failure).hasValue(null);
        assertThat(ledger.fee(1)).isZero();
    }

    /**
     * 容量一的队首进入 apply 后被门闩阻塞，检查传输深度已归零且第二项可入队、第三项仍拒绝，放行后两项资金按顺序完成。
     */
    @Test void executingHeadReleasesMailboxCapacityBeforeCompletion() throws Exception {
        // 容量释放用例使用的原 Treasury 对象。
        var treasury = new TreasuryRuntime();
        // 容量一队首任务已进入 apply 的通知门闩。
        var entered = new CountDownLatch(1);
        // 主线程检查容量后放行队首实际资金写入的门闩。
        var release = new CountDownLatch(1);
        // 对应资金任务执行到通知点后释放的门闩。
        var done = new CountDownLatch(1);
        try (/* 实际启动的系统账本执行器，按用例选择自动或显式关闭。 */ var lane = new SystemLedgerLane(treasury, 1, /* 本测试空故障回调收到的异常参数，这些回调不读取它。 */ ignored -> { })) {
            try {
                assertThat(lane.offer(/* 本用例原 Treasury 对象，或任务回调收到的同一财库，不是账本副本。 */ ledger -> {
                    entered.countDown();
                    try { if (!release.await(3, TimeUnit.SECONDS)) throw new AssertionError("release timeout"); }
                    catch (/* 工作线程门闩等待收到的中断异常，当前分支把它转换为断言失败。 */ InterruptedException interrupted) { throw new AssertionError(interrupted); }
                    ledger.setFee(0, 1); ledger.clearChangedKeys();
                })).isTrue();
                assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
                assertThat(lane.depth()).isZero();
                assertThat(lane.offer(/* 本用例原 Treasury 对象，或任务回调收到的同一财库，不是账本副本。 */ ledger -> {
                    assertThat(ledger.fee(0)).isEqualTo(1);
                    ledger.setFee(0, 2); ledger.clearChangedKeys(); done.countDown();
                })).isTrue();
                assertThat(lane.offer(/* 本用例原 Treasury 对象，或任务回调收到的同一财库，不是账本副本。 */ ledger -> { })).isFalse();
            } finally { release.countDown(); }
            assertThat(done.await(2, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(treasury.fee(0)).isEqualTo(2);
    }

    /**
     * 反复投递的固定财务测试槽，持有只读差额、捕获结果及 volatile 完成位，用于核对数据先于完成发布。
     */
    private static final class TreasurySlot implements SystemLedgerLane.Entry {
        /** 构造后只读、每轮重复应用的一费用二保险差额，归当前测试任务独占。 */
        final RuntimeTreasuryDelta delta = new RuntimeTreasuryDelta();
        /**
         * fee：本任务应用后从原财库读取的累计费用，完成位发布后由主线程检查。
         * insurance：本任务应用后从原财库读取的累计保险余额。
         */
        long fee, insurance;
        /** 本轮资金应用与结果捕获结束的 volatile 标记，主线程每次入队前重置。 */
        volatile boolean completed;

        /**
         * 构造后保留每轮固定增加一费用、二保险的同一差额，不在轮间重建。
         */
        TreasurySlot() { delta.addFee(1, 1); delta.addInsurance(1, 2); }

        /**
         * 在真实账本线程应用固定差额，保存当前费用及保险后清变化键，最后通过 volatile 完成位发布这些结果。
         * @param ledger 复用任务应用时由当前账本线程持有的原 Treasury
         */
        @Override public void apply(TreasuryRuntime ledger) {
            delta.apply(ledger);
            fee = ledger.fee(1); insurance = ledger.insurance(1);
            ledger.clearChangedKeys();
            completed = true;
        }
    }
}
