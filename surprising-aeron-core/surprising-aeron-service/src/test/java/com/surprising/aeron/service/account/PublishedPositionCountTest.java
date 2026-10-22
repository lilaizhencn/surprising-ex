package com.surprising.aeron.service.account;

import com.surprising.aeron.model.TradingCoreState;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.service.core.CoreStateTestFixtures;
import com.surprising.aeron.service.core.TradingRuntimeState;
import com.surprising.aeron.service.core.change.LaneCommitDelta;
import com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.aeron.service.position.PositionRuntime;
import com.surprising.aeron.service.snapshot.state.RuntimeStateProjector;
import com.surprising.product.api.ProductLine;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.assertj.core.api.Assertions.*;

/**
 * 验证各产品运行时的持仓记录计数由 Lane 完成收据交给 Owner，早期收据不读取较晚活跃表，已清理收据不重复发布；测试直接构造记录，不代表各产品通过 API 新建仓位。
 */
class PublishedPositionCountTest {
    /**
     * 六产品线先核对未启动线程时的计数，再阻塞 Lane 的新增持仓任务，要求 Owner 保持旧计数，收齐回执后才增加，最后删除归零。
     * @param product 当前枚举到的独立产品线
     */
    @ParameterizedTest @EnumSource(ProductLine.class)
    void coldAndWorkerMutationsPublishCountsOnlyWhenOwnerCollectsTheReceipt(ProductLine product) throws Exception {
        try (/* 由当前产品空状态投影得到的独立运行时，结束时关闭账户工作资源。 */ var runtime = RuntimeStateProjector.project(TradingCoreState.empty(product), new RuntimeIdentityRegistry())) {
            runtime.putUser(new UserRuntime(product, 7, 0, com.surprising.aeron.protocol.CorePositionMode.ONE_WAY));
            runtime.putUser(new UserRuntime(product, 8, 0, com.surprising.aeron.protocol.CorePositionMode.ONE_WAY));
            runtime.putPosition(101, position(product, 7));
            runtime.putPosition(102, position(product, 8));
            assertThat(runtime.publishedPositionCount()).isEqualTo(2);
            runtime.removePosition(102, 8);
            assertThat(runtime.publishedPositionCount()).isOne();
            runtime.clearChangedKeys();
            runtime.startAccountLanes();
            // 通知 Lane 已新增持仓、但尚未完成控制任务的门闩。
            var mutated = new CountDownLatch(1);
            // 在 Owner 检查旧发布计数后才放行 Lane 的门闩。
            var release = new CountDownLatch(1);
            try {
                runtime.dispatchControlLanes(runtime.topology().accountLaneMask(7), /* 派发回调提供的 Lane 编号，本场景按固定用户七处理，不读取此参数。 */ ignored -> {
                    runtime.putPosition(103, position(product, 7));
                    mutated.countDown();
                    await(release);
                    return null;
                });
                assertThat(mutated.await(3, TimeUnit.SECONDS)).isTrue();
                assertThat(runtime.publishedPositionCount()).isOne();
                assertThat(runtime.pollControlLanes()).isFalse();
            } finally { release.countDown(); }
            awaitControl(runtime);
            // 活跃 Owner 在此没有 Lane 读取屏障，答案必须来自已交接的收据元数据。
            assertThat(runtime.publishedPositionCount()).isEqualTo(2);
            runtime.dispatchControlLanes(runtime.topology().accountLaneMask(7), /* 派发回调提供的 Lane 编号，本场景按固定用户七处理，不读取此参数。 */ ignored -> {
                runtime.removePosition(101, 7);
                runtime.removePosition(103, 7);
                return null;
            });
            awaitControl(runtime);
            assertThat(runtime.publishedPositionCount()).isZero();
        }
    }

    /**
     * 同一 Lane 连续制造一条、两条、一条记录的不同边界收据，Owner 逐份按各自元数据发布计数，清空旧收据后不得再次改变已发布结果。
     * @param product 当前枚举到的独立产品线
     */
    @ParameterizedTest @EnumSource(ProductLine.class)
    void earlierReceiptCannotObservePositionsFromLaterLaneWorkAndClearedReceiptsCannotRepublish(ProductLine product) throws Exception {
        try (/* 由当前产品空状态投影得到的独立运行时，结束时关闭账户工作资源。 */ var runtime = RuntimeStateProjector.project(TradingCoreState.empty(product), new RuntimeIdentityRegistry())) {
            runtime.startAccountLanes();
            // 分别保存三个不同 Lane 边界位置持仓条数的独立提交收据。
            var receipts = new LaneCommitDelta[]{new LaneCommitDelta(), new LaneCommitDelta(), new LaneCommitDelta()};
            // 通知 Lane 内三次持仓及收据准备全部结束的门闩。
            var completed = new CountDownLatch(1);
            // 把 Lane 查询回调中抛出的断言或异常传回 Owner 测试线程的原子槽。
            var failure = new AtomicReference<Throwable>();
            // 本次用户七对应的实际账户 Lane 编号。
            int laneId = runtime.topology().accountLaneId(7);
            assertThat(runtime.dispatchAccountQuery(1L << laneId, /* 实际执行查询回调的账户 Lane，本用例在其所有权内直接构造记录及收据边界。 */ lane -> {
                try {
                    lane.positions.put(101, position(product, 7));
                    receipts[0].preparePositionRetirement(lane);
                    lane.positions.put(102, position(product, 7));
                    receipts[1].preparePositionRetirement(lane);
                    receipts[1].preparePublication();
                    lane.positions.remove(101);
                    receipts[2].preparePositionRetirement(lane);
                    assertThatThrownBy(runtime::publishedPositionCount).isInstanceOf(IllegalStateException.class);
                } catch (/* Lane 内构造收据或断言时的任何失败，保存后由测试线程核对。 */ Throwable problem) { failure.set(problem); }
                finally { completed.countDown(); }
            })).isTrue();
            assertThat(completed.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(failure.get()).isNull();
            assertThat(runtime.publishedPositionCount()).isZero();
            receipts[0].recordControlChanges(runtime, laneId);
            assertThat(runtime.publishedPositionCount()).isOne();
            receipts[1].commitTerminalToOwner(runtime, laneId, null, 1, null, null);
            assertThat(runtime.publishedPositionCount()).isEqualTo(2);
            receipts[0].clear();
            receipts[0].recordControlChanges(runtime, laneId);
            assertThat(runtime.publishedPositionCount()).isEqualTo(2);
            receipts[2].commitBatchAdmissionToOwner(runtime, laneId);
            assertThat(runtime.publishedPositionCount()).isOne();
            runtime.assertAccountLanesHealthy();
        }
    }

    /**
     * 按当前产品配置创建数量为一的测试净仓记录，只用于记录数交接场景。
     * @param product 当前枚举到的独立产品线
     * @param user 测试持仓所属用户编号
     */
    private static PositionRuntime position(ProductLine product, long user) {
        // 按当前产品构建的测试合约配置，提供 PositionRuntime 必需的规范合约对象。
        var instrument = CoreInstrument.from(product, CoreStateTestFixtures.instrument(product, "1", "BTC", "USDT",
                CoreStateTestFixtures.settleAsset(product)));
        return new PositionRuntime(user, 0, 0, CoreMarginMode.CROSS, CorePositionSide.NET,
                instrument, 1, 100, 100, 0, 10);
    }

    /**
     * 等待控制任务放行门闩，最多三秒；中断时恢复标记并令测试失败。
     * @param latch 需要由测试线程放行的同步门闩
     */
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(3, TimeUnit.SECONDS)) throw new AssertionError("Lane was not released"); }
        catch (/* 等待门闩放行时发生的中断，恢复中断标记并转为断言失败。 */ InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
    }

    /**
     * 轮询 Owner 的控制收集直到完成或三秒超时，不通过直接读取 Lane 表推断完成。
     * @param runtime 要等待控制收据完成的交易运行时
     */
    private static void awaitControl(TradingRuntimeState runtime) {
        // 控制任务收齐完成回执的三秒单调时钟截止时间。
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!runtime.pollControlLanes()) {
            if (System.nanoTime() > deadline) throw new AssertionError("control receipt did not complete");
            Thread.onSpinWait();
        }
    }
}
