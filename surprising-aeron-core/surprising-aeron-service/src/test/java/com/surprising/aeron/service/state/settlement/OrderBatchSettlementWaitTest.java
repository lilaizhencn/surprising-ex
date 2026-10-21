package com.surprising.aeron.service.state.settlement;

import com.surprising.aeron.service.state.TradingRuntimeState;
import com.surprising.aeron.service.state.change.LaneCommitEvent;
import com.surprising.aeron.service.state.ledger.SystemLedgerLane;
import com.surprising.aeron.service.state.ledger.TreasuryRuntime;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 完成回执等待与复用边界测试。用直接构造和反射隔离完成位及 Owner 缓存契约；另启真实账本线程检查就绪读取，不在这些用例中执行真实成交资金计算。
 */
class OrderBatchSettlementWaitTest {
    /**
     * 在批量账户作用域中尝试收集及回收未完成事件，检查两者均拒绝且事件仍未完成。
     */
    @Test
    void incompleteItemCannotBeCollectedOrRecycled() throws Exception {
        try (/* 用例独占并自动关闭的运行时，用于批量作用域或等待入口。 */ var runtime = new TradingRuntimeState()) {
            runtime.beginOrderBatchMutationScope();
            // 尚未完成的结算或 Lane 变更事件，具体用例观察其完成契约。
            var event = incompleteEvent();
            assertThatThrownBy(() -> runtime.collectOrderBatchMatcherSettlement(event, null))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("not ready");
            assertThat(event.complete()).isFalse();
            assertThatThrownBy(() -> runtime.releaseMatcherSettlement(event))
                    .hasMessageContaining("cannot recycle an incomplete");
            runtime.endOrderBatchMutationScope();
        }
    }

    /**
     * 使用已过期等待截止点，检查超时不清除原 Core 序号或伪造完成位，未完成事件不能回收。
     */
    @Test
    void laneCommitTimeoutPreservesUnfinishedSequenceAndCannotRecycle() {
        try (/* 用例独占并自动关闭的运行时，用于批量作用域或等待入口。 */ var runtime = new TradingRuntimeState()) {
            // 尚未完成的结算或 Lane 变更事件，具体用例观察其完成契约。
            var event = incompleteLaneCommit(runtime);
            assertThatThrownBy(() -> runtime.awaitLaneCommit(event, System.nanoTime() - 1))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("timed out");
            assertThat(event.coreSequence()).isEqualTo(1);
            assertThat(event.completedLaneMask()).isZero();
            assertThatThrownBy(() -> runtime.releaseLaneCommit(event))
                    .hasMessageContaining("incomplete Account Lane commit");
        }
    }

    /**
     * 预先设置当前线程中断，检查等待抛出中断故障、保留标记且事件未完成；finally 只为避免影响其他测试而清除标记。
     */
    @Test
    void laneCommitInterruptionDoesNotClearInterruptOrClaimCompletion() {
        try (/* 用例独占并自动关闭的运行时，用于批量作用域或等待入口。 */ var runtime = new TradingRuntimeState()) {
            // 尚未完成的结算或 Lane 变更事件，具体用例观察其完成契约。
            var event = incompleteLaneCommit(runtime);
            Thread.currentThread().interrupt();
            try {
                assertThatThrownBy(() -> runtime.awaitLaneCommit(event, System.nanoTime() + 30_000_000_000L))
                        .isInstanceOf(IllegalStateException.class).hasMessageContaining("interrupted");
                assertThat(Thread.currentThread().isInterrupted()).isTrue();
                assertThat(event.complete()).isFalse();
            } finally {
                Thread.interrupted();
            }
        }
    }

    /**
     * 用反射和 release 写入模拟两个账户完成位，真实账本任务只读 readiness；检查它不更新 Owner 缓存，Owner 自己读取后缓存才变化，复用重置清除旧位。
     */
    @Test
    void ledgerReadinessDoesNotMutateOwnerCompletionCacheOrExecuteAccounts() throws Exception {
        // 尚未完成的结算或 Lane 变更事件，具体用例观察其完成契约。
        var event = incompleteEvent();
        // 反射取得的 requiredLaneMask 字段，用来设置必需业务 Lane。
        var mask = MatcherSettlementEvent.class.getDeclaredField("requiredLaneMask"); mask.setAccessible(true);
        mask.setLong(event, 3);
        // 反射取得的 routedLaneMask 字段，用来设置必须消费票据的实际路由。
        var route = MatcherSettlementEvent.class.getDeclaredField("routedLaneMask"); route.setAccessible(true);
        route.setLong(event, 3);
        // 反射取得的完成数组及 Owner 缓存重置方法。
        var reset = MatcherSettlementEvent.class.getDeclaredMethod("resetCompletions", int.class); reset.setAccessible(true);
        reset.invoke(event, 2);
        // 反射取得的每 Lane 完成标记数组字段。
        var slotsField = MatcherSettlementEvent.class.getDeclaredField("completedLanes"); slotsField.setAccessible(true);
        // 实际完成标记数组，两个 Lane 的有效标记下标分别为零和十六。
        long[] slots = (long[]) slotsField.get(event);
        // 反射取得的 Owner 已观察完成位图字段。
        var cache = MatcherSettlementEvent.class.getDeclaredField("observedCompletedLaneMask"); cache.setAccessible(true);
        // 用于以 release 语义模拟账户完成发布的 long 数组 VarHandle。
        var longs = java.lang.invoke.MethodHandles.arrayElementVarHandle(long[].class);
        // 实际账本线程意外故障的原子接收槽。
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        // 两个账户完成后由账本空任务释放的门闩。
        var done = new java.util.concurrent.CountDownLatch(1);
        try (/* 执行只读 readiness 与空财务任务的真实系统账本线程，退出时关闭。 */ var ledger = new SystemLedgerLane(new TreasuryRuntime(), 256, failure::set)) {
            assertThat(ledger.offer(/* 只读取模拟账户完成位的测试账本任务，就绪后通知门闩，不执行真实账户或财务更新。 */ new SystemLedgerLane.Entry() {
                /**
                 * 账本线程只调用事件的账户完成检查，不运行账户结算，也不写 Owner 完成缓存。
                 */
                @Override public boolean ready() { return event.accountLanesCompleteForLedger(); }
                /**
                 * 两个账户标记都完成后只释放测试门闩，证明账本任务被放行，不修改财库。
                 * @param actual 账本任务收到的实际 Treasury，本任务只通知完成而不读写它
                 */
                @Override public void apply(TreasuryRuntime actual) { done.countDown(); }
            })).isTrue();
            try {
                assertThat(event.accountLanesCompleteForLedger()).isFalse();
                assertThat(cache.getLong(event)).isZero();
                longs.setRelease(slots, 0, 1L);
                assertThat(event.accountLanesCompleteForLedger()).isFalse();
                assertThat(cache.getLong(event)).isZero();
                assertThat(done.getCount()).isOne();
            } finally { longs.setRelease(slots, 16, 1L); }
            assertThat(done.await(3, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(failure.get()).isNull();
            assertThat(event.accountLanesCompleteForLedger()).isTrue();
            assertThat(cache.getLong(event)).isZero();
            assertThat(event.completedLaneMask()).isEqualTo(3);
            assertThat(cache.getLong(event)).isEqualTo(3);
        }
        reset.invoke(event, 2);
        assertThat(event.accountLanesCompleteForLedger()).isFalse();
        assertThat(cache.getLong(event)).isZero();
    }

    /**
     * 按不同先后顺序发布两个完成位，检查全部到齐才完成；重置后旧完成位及观察缓存均不能泄漏到新代。
     */
    @Test
    void completionCacheWaitsForEveryLaneAndResetsBeforeReuse() throws Exception {
        // 尚未完成的结算或 Lane 变更事件，具体用例观察其完成契约。
        var event = incompleteEvent();
        // 反射取得的 requiredLaneMask 字段，用来设置必需业务 Lane。
        var mask = MatcherSettlementEvent.class.getDeclaredField("requiredLaneMask"); mask.setAccessible(true);
        mask.setLong(event, 3);
        // 反射取得的 routedLaneMask 字段，用来设置必须消费票据的实际路由。
        var route = MatcherSettlementEvent.class.getDeclaredField("routedLaneMask"); route.setAccessible(true);
        route.setLong(event, 3);
        // 反射取得的每 Lane 完成标记数组字段。
        var slotsField = MatcherSettlementEvent.class.getDeclaredField("completedLanes"); slotsField.setAccessible(true);
        // 反射取得的完成数组及 Owner 缓存重置方法。
        var reset = MatcherSettlementEvent.class.getDeclaredMethod("resetCompletions", int.class); reset.setAccessible(true);
        reset.invoke(event, 2);
        // 实际完成标记数组，两个 Lane 的有效标记下标分别为零和十六。
        long[] slots = (long[]) slotsField.get(event);
        // 用于以 release 语义模拟账户完成发布的 long 数组 VarHandle。
        var longs = java.lang.invoke.MethodHandles.arrayElementVarHandle(long[].class);
        longs.setRelease(slots, 0, 1L);
        assertThat(event.completedLaneMask()).isEqualTo(1);
        assertThat(event.complete()).isFalse();
        longs.setRelease(slots, 16, 1L);
        assertThat(event.complete()).isTrue();
        reset.invoke(event, 2);
        assertThat(event.completedLaneMask()).isZero();
        assertThat(event.complete()).isFalse();
        longs.setRelease(slots, 16, 1L);
        assertThat(event.complete()).isFalse();
        longs.setRelease(slots, 0, 1L);
        assertThat(event.complete()).isTrue();
    }

    /**
     * 构造只有一个必需 Lane、尚未执行及提交的账户变更事件，供超时和中断等待检查。
     * @param runtime 给账户变更事件提供拓扑及通知边界的测试运行时
     */
    private static LaneCommitEvent incompleteLaneCommit(TradingRuntimeState runtime) {
        return new LaneCommitEvent(1).prepare(1, 1,
                new org.eclipse.collections.impl.list.mutable.primitive.LongArrayList[] {
                    new org.eclipse.collections.impl.list.mutable.primitive.LongArrayList()
                }, runtime);
    }

    /**
     * 通过反射只设置直达事件已发布、一路由及空完成数组，隔离完成标记测试；不准备真实撮合计划、账户变化或财务事实。
     */
    private static MatcherSettlementEvent incompleteEvent() throws Exception {
        // 这里只隔离直达完成位与观察缓存契约；真实成交及账本交接
        // 由 MatcherTreasurySettlementTest 中的原生成交事实用例覆盖。
        // 尚未完成的结算或 Lane 变更事件，具体用例观察其完成契约。
        var event = new MatcherSettlementEvent();
        // 反射取得的 requiredLaneMask 字段，用来设置必需业务 Lane。
        var mask = MatcherSettlementEvent.class.getDeclaredField("requiredLaneMask");
        mask.setAccessible(true);
        mask.setLong(event, 1);
        // 反射取得的 routedLaneMask 字段，用来设置必须消费票据的实际路由。
        var route = MatcherSettlementEvent.class.getDeclaredField("routedLaneMask");
        route.setAccessible(true);
        route.setLong(event, 1);
        // 反射取得的直达结果已发布字段。
        var published = MatcherSettlementEvent.class.getDeclaredField("directPublished");
        published.setAccessible(true);
        published.setBoolean(event, true);
        // 反射取得的账户完成数组字段，用于构造尚无完成位的事件。
        var completed = MatcherSettlementEvent.class.getDeclaredField("completedLanes");
        completed.setAccessible(true);
        completed.set(event, new long[16]);
        return event;
    }
}
