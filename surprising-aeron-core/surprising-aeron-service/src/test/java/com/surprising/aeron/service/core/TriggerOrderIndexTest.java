package com.surprising.aeron.service.core;

import com.surprising.aeron.config.LaneTopology;
import com.surprising.aeron.model.CoreRiskState;
import com.surprising.aeron.model.CoreTriggerOrderState;
import com.surprising.aeron.model.TradingCoreState;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreOrderType;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreTimeInForce;
import com.surprising.aeron.protocol.CoreTriggerCondition;
import com.surprising.aeron.protocol.CoreTriggerOrderStatus;
import com.surprising.aeron.protocol.CoreTriggerOrderType;
import com.surprising.aeron.service.account.CoreUserState;
import com.surprising.aeron.service.account.UserRuntime;
import com.surprising.aeron.service.core.change.LaneCommitEvent;
import com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.core.index.AdlPositionIndex;
import com.surprising.aeron.service.core.index.OpenInterestIndex;
import com.surprising.aeron.service.core.index.PositionUserIndex;
import com.surprising.aeron.service.core.index.RuntimeFactIndexes;
import com.surprising.aeron.service.core.index.TriggerOrderIndex;
import com.surprising.aeron.service.snapshot.state.RuntimeStateProjector;
import com.surprising.aeron.service.treasury.CoreTreasuryState;
import com.surprising.product.api.ProductLine;
import java.util.LinkedHashSet;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 触发派生索引与账户所有权测试。覆盖倒序查询、价格候选分页、追踪/OCO/到期更新、跨 Lane 提交与变更回执、回滚及替换快照；索引用例直接构造状态，没有经过完整触发单准入或真实 Matcher 成交。
 */
class TriggerOrderIndexTest {
    /**
     * 加入一万七千个 ID，确认倒序范围可直接从十开始并包含十到一，且对返回只读视图 clear 会失败；这不是吞吐基准。
     */
    @Test void descendingQueryRangeStartsAtItsCursorBeyondTheScanBound() {
        // 本用例独立的触发派生索引，由空状态或手工候选状态创建
        var index = new TriggerOrderIndex(TradingCoreState.empty(ProductLine.SPOT));
        for (/* 当前逐个加入索引或候选映射的触发 ID */ long id = 1; id <= 17_000; id++) index.apply(id, trigger(id, 7, 100));
        assertThat(index.idsBefore(10)).containsExactly(10L, 9L, 8L, 7L, 6L, 5L, 4L, 3L, 2L, 1L);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> index.idsBefore(10).clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    /**
     * 选择分属不同 Lane 的两个用户，派发关联触发撤销；账户先变取消但全局修订仍十，释放提交事件才增到十二。复用同一事件执行普通空变更后，触发修订和全局修订不能再增加。
     */
    @Test
    void closeTriggerCommitPublishesOnlyAfterLanesAndDoesNotRepeatOnReuse() {
        try (/* 本用例的账户运行时，在资源作用域结束时关闭 */ var runtime = new TradingAccountRuntime(LaneTopology.productionDefault())) {
            // 从用户八开始找出与用户七不同账户 Lane 的用户 ID
            long second = 8;
            while (runtime.topology().accountLaneId(second) == runtime.topology().accountLaneId(7)) second++;
            // 固定给断言闭包捕获的第二用户 ID
            final long secondUser = second;
            runtime.putUser(new UserRuntime(7));
            runtime.putUser(new UserRuntime(second));
            runtime.putTriggerOrder(trigger(901, 7, 100));
            runtime.putTriggerOrder(trigger(902, second, 100));
            // 供各 Owner 派生索引初始化使用的空现货冷状态
            var initial = TradingCoreState.empty(ProductLine.SPOT);
            // 本用例索引、投影及物化共用的整数身份表
            var identities = new RuntimeIdentityRegistry();
            // Owner 触发索引，预先登记两个用户各一条待取消触发
            var triggers = new TriggerOrderIndex(initial);
            triggers.apply(901, trigger(901, 7, 100));
            triggers.apply(902, trigger(902, second, 100));
            runtime.bindFactIndexes(new RuntimeFactIndexes(new PositionUserIndex(initial, identities),
                    new OpenInterestIndex(initial), triggers,
                    new com.surprising.aeron.service.core.index.AlgoOrderIndex(initial),
                    new com.surprising.aeron.service.core.index.LiquidationIndex(initial),
                    new com.surprising.aeron.service.core.index.CancelAllAfterIndex(initial),
                    new com.surprising.aeron.service.core.index.ActiveOrderIndex(initial, identities),
                    new AdlPositionIndex(initial, identities)), identities);
            runtime.setMetadata(ProductLine.SPOT, 10);
            runtime.clearChangedKeys();
            runtime.startAccountLanes();
            runtime.enterAsynchronousCommandScope();
            try {
                // 携带两个跨 Lane 用户关联触发撤销的首个可复用提交事件
                var event = runtime.dispatchLaneMutation(1, java.util.List.of(7L, second),
                        java.util.List.of(), java.util.List.of(901L, 902L), 100, 200);
                awaitCommit(runtime, event);
                assertThat(runtime.revision()).isEqualTo(10);
                assertThat(AccountLaneQueryTestSupport.read(runtime, 7, () -> runtime.triggerOrder(901).status())).isEqualTo(CoreTriggerOrderStatus.CANCELED);
                assertThat(AccountLaneQueryTestSupport.read(runtime, secondUser, () -> runtime.triggerOrder(902).status())).isEqualTo(CoreTriggerOrderStatus.CANCELED);
                assertThat(runtime.ownerLaneAccess()).isFalse();
                runtime.releaseLaneCommit(event);
                assertThat(runtime.revision()).isEqualTo(12);
                assertThat(AccountLaneQueryTestSupport.read(runtime, 7, () -> runtime.triggerOrder(901).status())).isEqualTo(CoreTriggerOrderStatus.CANCELED);
                assertThat(AccountLaneQueryTestSupport.read(runtime, secondUser, () -> runtime.triggerOrder(902).revision())).isEqualTo(2);
                // 再次派发的普通无触发撤销提交事件，必须复用原 event 对象
                var plain = runtime.dispatchLaneMutation(2, java.util.List.of(7L, second));
                assertThat(plain).isSameAs(event);
                awaitCommit(runtime, plain);
                runtime.releaseLaneCommit(plain);
                assertThat(runtime.revision()).isEqualTo(12);
                assertThat(AccountLaneQueryTestSupport.read(runtime, 7, () -> runtime.triggerOrder(901).revision())).isEqualTo(2);
            } finally { runtime.exitAsynchronousCommandScope(); }
        }
    }

    /**
     * 用五秒单调时钟期限自旋等待本次 Lane 提交完成，超时使测试失败，不在这里释放提交事件。
     * @param runtime 本次负责关闭的运行时，等待现有账户工作完成
     * @param event 本次需要等待的 Lane 提交事件，完成后由用例显式释放
     */
    private static void awaitCommit(TradingAccountRuntime runtime, LaneCommitEvent event) {
        // 等待本次 Lane 提交完成的单调时钟五秒期限
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (!runtime.laneCommitComplete(event)) {
            if (System.nanoTime() > deadline) throw new AssertionError("trigger commit timed out");
            Thread.onSpinWait();
        }
    }

    /**
     * 选择末条账户 Lane 的用户，验证 Owner 无权直接读触发正文，所属 Lane 可读；通过控制任务更新再删除，用变更观察器收集新值和删除，再回滚恢复原对象及客户端身份。
     */
    @Test
    void accountThreadMutatesTriggerWhileOwnerUsesOnlyCommitReceipts() {
        try (/* 本用例的账户运行时，在资源作用域结束时关闭 */ var runtime = new TradingAccountRuntime(LaneTopology.productionDefault())) {
            // 按拓扑从一开始搜索目标账户 Lane 的用户 ID
            long user = 1;
            // 生产拓扑中的最后一个账户 Lane 编号
            int targetLane = runtime.topology().accountLaneCount() - 1;
            while (runtime.topology().accountLaneId(user) != targetLane) user++;
            // 搜索后固定捕获的目标 Lane 用户 ID
            final long userId = user;
            // 账户启动前写入的原触发状态，回滚后必须恢复为同一对象
            var initial = trigger(901, userId, 100);
            runtime.putTriggerOrder(initial); runtime.clearChangedKeys(); runtime.startAccountLanes();
            assertThatThrownBy(() -> runtime.triggerOrder(901)).isInstanceOf(IllegalStateException.class);
            assertThat(AccountLaneQueryTestSupport.read(runtime, userId, () -> runtime.triggerOrder(901))).isSameAs(initial);
            // 同触发 ID 的新阈值一百一十状态，供账户更新和回执断言
            var changed = trigger(901, userId, 110);
            runtime.dispatchControlLanes(runtime.topology().accountLaneMask(userId), /* 控制任务携带的 Lane 编号，本测试 lambda 当前不读取 */ ignored -> { runtime.putTriggerOrder(changed); return null; });
            LedgerControlTestSupport.await(runtime, runtime::pollControlLanes);
            assertThat(AccountLaneQueryTestSupport.read(runtime, userId, () -> runtime.triggerOrder(901))).isSameAs(changed);
            // 观察到的触发 ID 到本命令新值表，删除时保存 null
            var observed = new java.util.TreeMap<Long, com.surprising.aeron.model.CoreTriggerOrderState>();
            // 只记录触发变更的新值观察器，Owner 通过它接回账户事实
            var consumer = /* 只收集本命令触发单新值的匿名观察器，供账户更新、删除和回滚断言 */ new TradingAccountRuntime.ChangedIndexConsumer() {
                /**
                 * 收到触发单变更时仅把 ID 到新值记入观察表，before 不参与断言；新值为空表示本命令删除。
                 * @param id 测试触发单或变更回调的业务 ID
                 * @param before 变更前触发状态，此观察器当前不读取
                 * @param after 本次变更后触发状态，空表示删除，存入观察表以断言
                 */
                @Override public void triggerOrder(long id, com.surprising.aeron.model.CoreTriggerOrderState before,
                        com.surprising.aeron.model.CoreTriggerOrderState after) { observed.put(id, after); }
            };
            runtime.visitChangedIndexes(consumer);
            assertThat(observed).containsEntry(901L, changed);
            runtime.dispatchControlLanes(runtime.topology().accountLaneMask(userId), /* 控制任务携带的 Lane 编号，本测试 lambda 当前不读取 */ ignored -> { runtime.removeTriggerOrder(901); return null; });
            LedgerControlTestSupport.await(runtime, runtime::pollControlLanes);
            runtime.visitChangedIndexes(consumer);
            assertThat(AccountLaneQueryTestSupport.read(runtime, userId, () -> runtime.triggerOrder(901))).isNull();
            assertThat(observed).containsEntry(901L, null);
            LedgerControlTestSupport.await(runtime, runtime.beginCommandRollback(runtime.revision(), 1));
            assertThat(AccountLaneQueryTestSupport.read(runtime, userId, () -> runtime.triggerOrder(901))).isSameAs(initial);
            assertThat(AccountLaneQueryTestSupport.read(runtime, userId, () -> runtime.hasTriggerClient(userId, initial.clientTriggerOrderId()))).isTrue();
        }
    }

    /**
     * 账户启动后显式取得 Owner 账户访问权，替换为空辅助状态应删除已发布触发，再替回原状态并释放访问权，所属 Lane 可重新读到原单。获取访问权的自旋循环没有本地期限。
     */
    @Test
    void replacingAuxiliarySnapshotClearsPublishedTriggerIdentities() {
        // 原辅助快照中需要删除再恢复的触发状态
        var initial = trigger(901, 7, 100);
        // 手工装入本用例触发状态的冷状态，供投影或索引重建
        var state = new TradingCoreState(ProductLine.SPOT, 0,
                Map.of(7L, CoreUserState.empty(ProductLine.SPOT, 7)), Map.of(), Map.of(),
                CoreRiskState.empty(), CoreTreasuryState.empty(), Map.of(), Map.of(), Map.of(), Map.of(901L, initial));
        try (/* 本用例的账户运行时，在资源作用域结束时关闭 */ var runtime = RuntimeStateProjector.project(state, new RuntimeIdentityRegistry())) {
            runtime.startAccountLanes();
            assertThat(AccountLaneQueryTestSupport.read(runtime, 7, () -> runtime.triggerOrder(901))).isEqualTo(initial);
            while (!runtime.tryAcquireOwnerLaneAccess()) Thread.onSpinWait();
            runtime.replaceAuxiliaryState(TradingCoreState.empty(ProductLine.SPOT));
            assertThat(runtime.triggerOrder(901)).isNull();
            runtime.replaceAuxiliaryState(state);
            runtime.releaseOwnerLaneAccess();
            assertThat(AccountLaneQueryTestSupport.read(runtime, 7, () -> runtime.triggerOrder(901))).isEqualTo(initial);
        }
    }

    /**
     * 构造标记为现货的待触发买单供索引和发布测试，借用共享正向永续标的一外壳，仅依赖该外壳身份，不代表完整产品准入已经通过。
     * @param id 测试触发单或变更回调的业务 ID
     * @param user 测试触发单所属用户
     * @param price 普通触发阈值或本次候选查询的实际价格 tick
     */
    private static CoreTriggerOrderState trigger(long id, long user, long price) {
        return new CoreTriggerOrderState(id, ProductLine.SPOT, user, "published-" + id, "",
                "1", CoreStateTestFixtures.runtimeInstrument(), CoreOrderSide.BUY,
                CoreTriggerOrderType.STOP_LOSS,
                CoreTriggerCondition.GREATER_OR_EQUAL, price, 0, 0, 0, 0, 0,
                CoreOrderType.LIMIT, CoreTimeInForce.GTC, 90, 1, CoreMarginMode.CROSS,
                CorePositionSide.NET, CoreTriggerOrderStatus.PENDING, 0, 0, 0, "",
                0, 0, 1, 1, 1);
    }

    /**
     * 同价三百个候选每页最多六十四，沿阶段及双游标继续并用集合核对全部 ID 覆盖；还检查客户端身份按用户隔离及删除重加。集合合并不会单独发现跨页重复。
     */
    @Test
    void candidatesPageBoundsAndResumesWithoutOmission() {
        // 本轮三百条同阈值候选的冷状态有序映射
        Map<Long, CoreTriggerOrderState> triggers = new java.util.TreeMap<>();
        for (/* 当前逐个加入索引或候选映射的触发 ID */ long id = 1; id <= 300; id++) {
            triggers.put(id, new CoreTriggerOrderState(id, ProductLine.SPOT, 7, "client-" + id, "",
                    "1", CoreStateTestFixtures.runtimeInstrument(), CoreOrderSide.SELL,
                    CoreTriggerOrderType.TAKE_PROFIT,
                    CoreTriggerCondition.GREATER_OR_EQUAL, 70_000, 0, 0, 0, 0, 0,
                    CoreOrderType.MARKET, CoreTimeInForce.IOC, 0, 1, CoreMarginMode.CROSS,
                    CorePositionSide.NET, CoreTriggerOrderStatus.PENDING, 0, 0, 0, "",
                    0, 0, 1, 1, 1));
        }
        // 手工装入本用例触发状态的冷状态，供投影或索引重建
        TradingCoreState state = new TradingCoreState(ProductLine.SPOT, 0,
                Map.of(7L, CoreUserState.empty(ProductLine.SPOT, 7)), Map.of(), Map.of(),
                CoreRiskState.empty(), CoreTreasuryState.empty(), Map.of(), Map.of(), Map.of(), triggers);
        // 本用例独立的触发派生索引，由空状态或手工候选状态创建
        TriggerOrderIndex index = new TriggerOrderIndex(state);

        // 本用例索引、投影及物化共用的整数身份表
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        try (/* 本用例的账户运行时，在资源作用域结束时关闭 */ TradingAccountRuntime runtime = RuntimeStateProjector.project(state, identities)) {
            assertThat(runtime.hasTriggerClient(7, "client-1")).isTrue();
            assertThat(runtime.hasTriggerClient(8, "client-1")).isFalse();
            runtime.removeTriggerOrder(1);
            assertThat(runtime.hasTriggerClient(7, "client-1")).isFalse();
            runtime.putTriggerOrder(triggers.get(1L));
            assertThat(runtime.hasTriggerClient(7, "client-1")).isTrue();
        }

        // 本轮开始时固定的最大待触发 ID，分页时保持不变
        long upperId = index.maxPendingId("1");
        // 当前单来源候选阶段，按每页返回值接续
        int phase = TriggerOrderIndex.PHASE_GREATER_OR_EQUAL;
        // 上页返回的价格桶位置，初始为 long 最大值
        long priceCursor = Long.MAX_VALUE;
        // 上页最后触发 ID 的排他游标，初始为 long 最大值
        long orderCursor = Long.MAX_VALUE;
        // 按页收集的候选 ID；覆盖用例使用去重集合，顺序辅助使用列表
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        // 三百候选用例已读取的页数，恢复页数必须小于二十
        int pages = 0;
        while (phase < TriggerOrderIndex.PHASE_COMPLETE) {
            // 当前索引返回的候选 ID、下一阶段及双游标
            TriggerOrderIndex.TriggerCandidatePage page = index.candidatesPage("1", 70_000, phase,
                    priceCursor, orderCursor, upperId, 64);
            assertThat(page.ids()).hasSizeLessThanOrEqualTo(64);
            ids.addAll(page.ids());
            pages++;
            if (page.complete()) break;
            phase = page.nextPhase();
            priceCursor = page.nextPriceCursor();
            orderCursor = page.nextOrderCursor();
            assertThat(pages).isLessThan(20);
        }

        assertThat(ids).hasSize(300);
        assertThat(ids).containsExactlyInAnyOrderElementsOf(triggers.keySet());
    }

    /**
     * 建立已激活卖出、待激活卖出、无激活价和已激活买入四种追踪路由，分别在九十五、一百零五和一百一十五查询，核对按阶段出现的候选顺序。
     */
    @Test
    void trailingCandidatesUseActivationAndCallbackThresholds() {
        // 四种激活/回调条件组合的追踪触发状态映射
        Map<Long, CoreTriggerOrderState> triggers = new java.util.TreeMap<>();
        triggers.put(301L, trailing(301, CoreOrderSide.SELL, 100, 100_000, 100, 0, 1_000));
        triggers.put(302L, trailing(302, CoreOrderSide.SELL, 100, 100_000, 0, 0, 0));
        triggers.put(303L, trailing(303, CoreOrderSide.SELL, 0, 100_000, 0, 0, 0));
        triggers.put(304L, trailing(304, CoreOrderSide.BUY, 0, 100_000, 0, 100, 1_000));
        // 手工装入本用例触发状态的冷状态，供投影或索引重建
        TradingCoreState state = new TradingCoreState(ProductLine.SPOT, 0,
                Map.of(7L, CoreUserState.empty(ProductLine.SPOT, 7)), Map.of(), Map.of(),
                CoreRiskState.empty(), CoreTreasuryState.empty(), Map.of(), Map.of(), Map.of(), triggers);
        // 本用例独立的触发派生索引，由空状态或手工候选状态创建
        TriggerOrderIndex index = new TriggerOrderIndex(state);

        assertThat(candidateIds(index, 95)).containsExactly(303L);
        assertThat(candidateIds(index, 105)).containsExactly(302L, 303L);
        assertThat(candidateIds(index, 115)).containsExactly(304L, 302L, 303L);
    }

    /**
     * 把标的一某价格下的六十四条候选页顺序追加为列表，依返回游标直至完成；该辅助循环本身没有额外页数或时间保护。
     * @param index 本次已经按用例布置的触发派生索引
     * @param markPrice 候选查询所用标记价格 tick
     */
    private static java.util.List<Long> candidateIds(TriggerOrderIndex index, long markPrice) {
        // 按页收集的候选 ID；覆盖用例使用去重集合，顺序辅助使用列表
        java.util.ArrayList<Long> ids = new java.util.ArrayList<>();
        // 当前单来源候选阶段，按每页返回值接续
        int phase = TriggerOrderIndex.PHASE_GREATER_OR_EQUAL;
        // 上页返回的价格桶位置，初始为 long 最大值
        long priceCursor = Long.MAX_VALUE;
        // 上页最后触发 ID 的排他游标，初始为 long 最大值
        long orderCursor = Long.MAX_VALUE;
        while (phase < TriggerOrderIndex.PHASE_COMPLETE) {
            // 当前索引返回的候选 ID、下一阶段及双游标
            TriggerOrderIndex.TriggerCandidatePage page = index.candidatesPage("1", markPrice,
                    phase, priceCursor, orderCursor, index.maxPendingId("1"), 64);
            ids.addAll(page.ids());
            if (page.complete()) break;
            phase = page.nextPhase();
            priceCursor = page.nextPriceCursor();
            orderCursor = page.nextOrderCursor();
        }
        return ids;
    }

    /**
     * 使用 long 最大价格验证普通大于等于和小于等于阶段顺序；首段后删除已访问桶并新加 ID 九十九，恢复时固定上界十二应继续旧范围且不包含新单。边界指价格，不是最大 long 触发 ID。
     */
    @Test
    @org.junit.jupiter.api.Timeout(5)
    void nativePricePagesRetainRangeOrderingAtLongBoundaryAndAcrossDeletedBuckets() {
        // 本用例独立的触发派生索引，由空状态或手工候选状态创建
        var index = new TriggerOrderIndex(TradingCoreState.empty(ProductLine.SPOT));
        index.apply(11, indexed(11, "1", CoreTriggerCondition.GREATER_OR_EQUAL, Long.MAX_VALUE, 0, ""));
        index.apply(12, indexed(12, "1", CoreTriggerCondition.GREATER_OR_EQUAL, Long.MAX_VALUE, 0, ""));
        index.apply(10, indexed(10, "1", CoreTriggerCondition.GREATER_OR_EQUAL, 100, 0, ""));
        index.apply(9, indexed(9, "1", CoreTriggerCondition.LESS_OR_EQUAL, Long.MAX_VALUE, 0, ""));
        assertThat(pagedIds(index, Long.MAX_VALUE, 1)).containsExactly(12L, 11L, 10L, 9L);

        // 固定最大 ID 十二、页上限一时得到的首个价格候选页
        var first = index.candidatesPage("1", Long.MAX_VALUE, 0, Long.MAX_VALUE, Long.MAX_VALUE, 12, 1);
        assertThat(first.ids()).containsExactly(12L);
        index.apply(11, null);
        index.apply(12, null);
        index.apply(99, indexed(99, "1", CoreTriggerCondition.GREATER_OR_EQUAL, 100, 0, ""));
        // 删除原价格桶后用保存游标继续得到的候选页
        var resumed = index.candidatesPage("1", Long.MAX_VALUE, first.nextPhase(), first.nextPriceCursor(),
                first.nextOrderCursor(), 12, 4);
        assertThat(resumed.ids()).containsExactly(10L, 9L);
        assertThat(resumed.complete()).isTrue();
    }

    /**
     * 用超过 int 的 long 触发 ID 和 int 最大标的身份，核对标的/用户倒序、持仓升序、OCO 与到期范围；更新换标的及删除后旧桶、客户端身份和最大候选同步移除，空重建无残留。
     */
    @Test
    void nativeMembershipExpiryAndOcoStayConsistentWithUpdatesAndPermanentIntIds() {
        // 本用例独立的触发派生索引，由空状态或手工候选状态创建
        var index = new TriggerOrderIndex(TradingCoreState.empty(ProductLine.SPOT));
        // 超过 int 上限的触发 ID 基数四十亿，用于确认原 long 身份不被截断
        long large = 4_000_000_000L;
        // int 最大标的、到期二十且属于 group 组的第一条大 ID 触发
        var first = indexed(large, "2147483647", CoreTriggerCondition.GREATER_OR_EQUAL, 100, 20, "group");
        // 同 int 最大标的、同组但更早到期的第二条大 ID 触发状态
        var second = indexed(large + 1, "2147483647", CoreTriggerCondition.GREATER_OR_EQUAL, 100, 10, "group");
        // 不同标的一、相同 OCO 文本但不能混组的第三条大 ID 触发状态
        var other = indexed(large + 2, "1", CoreTriggerCondition.GREATER_OR_EQUAL, 100, Long.MAX_VALUE, "group");
        index.apply(large, first); index.apply(large + 1, second); index.apply(large + 2, other);
        assertThat(index.route(large).instrumentId()).isEqualTo(Integer.MAX_VALUE);
        assertThat(index.ids("2147483647")).containsExactly(large + 1, large);
        assertThat(index.ids(7)).containsExactly(large + 2, large + 1, large);
        assertThat(index.ocoSiblingsDescending(first)).containsExactly(large + 1, large);
        assertThat(index.ids(7, "2147483647", CoreMarginMode.CROSS, CorePositionSide.NET))
                .containsExactly(large, large + 1);
        assertThat(index.expired(10, 1)).containsExactly(large + 1);
        assertThat(index.expired(Long.MAX_VALUE, 10)).containsExactly(large, large + 1, large + 2);
        index.apply(large, indexed(large, "1", CoreTriggerCondition.LESS_OR_EQUAL, 110, 0, ""));
        assertThat(index.ids("2147483647")).containsExactly(large + 1);
        assertThat(index.ocoSiblingsDescending(first)).containsExactly(large + 1);
        assertThat(index.expired(20, 10)).containsExactly(large + 1);
        index.apply(large + 1, null);
        assertThat(index.maxPendingId("2147483647")).isZero();
        assertThat(index.containsClient(7, "indexed-" + (large + 1))).isFalse();
        index.rebuild(TradingCoreState.empty(ProductLine.SPOT));
        assertThat(index.ids()).isEmpty();
        assertThat(index.expired(Long.MAX_VALUE, 10)).isEmpty();
    }

    /**
     * 按指定页上限收集标的一候选，至多一百页；完成即返回有序列表，仍未完成则报错。
     * @param index 本次已经按用例布置的触发派生索引
     * @param price 普通触发阈值或本次候选查询的实际价格 tick
     * @param limit 每个候选页允许返回的最大 ID 数
     */
    private static java.util.List<Long> pagedIds(TriggerOrderIndex index, long price, int limit) {
        // 按页收集的候选 ID；覆盖用例使用去重集合，顺序辅助使用列表
        var ids = new java.util.ArrayList<Long>();
        // 当前单来源候选阶段，按每页返回值接续
        int phase = 0;
        // 上页最后触发 ID 的排他游标，初始为 long 最大值
        // 上页返回的价格桶位置，初始为 long 最大值
        long priceCursor = Long.MAX_VALUE, orderCursor = Long.MAX_VALUE;
        for (/* 候选辅助的页数保护计数，最多读取一百页 */ int pageCount = 0; pageCount < 100; pageCount++) {
            // 当前索引返回的候选 ID、下一阶段及双游标
            var page = index.candidatesPage("1", price, phase, priceCursor, orderCursor, index.maxPendingId("1"), limit);
            ids.addAll(page.ids());
            if (page.complete()) return ids;
            phase = page.nextPhase(); priceCursor = page.nextPriceCursor(); orderCursor = page.nextOrderCursor();
        }
        throw new AssertionError("candidate pages did not finish");
    }

    /**
     * 用真正现货配置建立给定标的的普通待触发买单，支持指定阈值方向、到期时间和 OCO 文本组。
     * @param id 测试触发单或变更回调的业务 ID
     * @param instrumentId 本用例需要索引的规范数字标的文本
     * @param condition 普通触发单大于等于或小于等于的价格阈值方向
     * @param price 普通触发阈值或本次候选查询的实际价格 tick
     * @param expiry 测试触发单到期毫秒时间，零表示不进入到期桶
     * @param oco 测试触发单 OCO 组文本，空表示不分组
     */
    private static CoreTriggerOrderState indexed(long id, String instrumentId, CoreTriggerCondition condition,
            long price, long expiry, String oco) {
        // 按本用例数字标的 ID 创建的真正现货配置外壳
        var instrument = com.surprising.aeron.service.instrument.CoreInstrument.from(ProductLine.SPOT,
                new com.surprising.aeron.protocol.RegisterInstrumentCommand(instrumentId, "BTC", "USDT", "USDT",
                        1, 1, 1, 100_000, 50_000, 0, 0, 0, -1, 0));
        return new CoreTriggerOrderState(id, ProductLine.SPOT, 7, "indexed-" + id, oco, instrumentId,
                instrument, CoreOrderSide.BUY, CoreTriggerOrderType.STOP_LOSS, condition, price, 0, 0, 0, 0, 0,
                CoreOrderType.LIMIT, CoreTimeInForce.GTC, 90, 1, CoreMarginMode.CROSS, CorePositionSide.NET,
                CoreTriggerOrderStatus.PENDING, 0, 0, 0, "", expiry, 0, 1, 1, 1);
    }

    /**
     * 构造标记为现货的追踪待触发单，直接指定激活价、回调率及已记高低价和激活时间；同样借用共享标的外壳，只供索引条件测试。
     * @param id 测试触发单或变更回调的业务 ID
     * @param side 追踪子单买卖方向，决定使用高点回落还是低点反弹
     * @param activationPrice 追踪激活价格，零表示无额外激活价
     * @param callbackRate 追踪回调比例，单位为百万分之一
     * @param highest 已记录最高价格 tick
     * @param lowest 已记录最低价格 tick
     * @param activatedAt 已激活毫秒时间，零表示尚未激活
     */
    private static CoreTriggerOrderState trailing(long id, CoreOrderSide side, long activationPrice,
                                                  long callbackRate, long highest, long lowest, long activatedAt) {
        return new CoreTriggerOrderState(id, ProductLine.SPOT, 7, "client-" + id, "", "1",
                CoreStateTestFixtures.runtimeInstrument(), side,
                CoreTriggerOrderType.TRAILING_STOP, CoreTriggerCondition.GREATER_OR_EQUAL, 0,
                activationPrice, callbackRate, highest, lowest, activatedAt, CoreOrderType.MARKET,
                CoreTimeInForce.IOC, 0, 1, CoreMarginMode.CROSS, CorePositionSide.NET,
                CoreTriggerOrderStatus.PENDING, 0, 0, 0, "", 0, 0, 1, 1, 1);
    }
}
