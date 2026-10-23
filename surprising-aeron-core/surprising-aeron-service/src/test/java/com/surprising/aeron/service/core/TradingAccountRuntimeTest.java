package com.surprising.aeron.service.core;

import com.surprising.aeron.config.LaneTopology;
import com.surprising.aeron.model.CoreLeverageKey;
import com.surprising.aeron.model.CoreLiquidationState;
import com.surprising.aeron.model.CoreOrderStatus;
import com.surprising.aeron.model.TradingCoreState;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.service.account.AccountLaneState;
import com.surprising.aeron.service.account.AccountLaneTestAccess;
import com.surprising.aeron.service.account.BalanceRuntime;
import com.surprising.aeron.service.account.UserRuntime;
import com.surprising.aeron.service.core.change.LaneCommitEvent;
import com.surprising.aeron.service.core.change.RuntimeChangeBuffer;
import com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.order.admission.PendingReservationTracker;
import com.surprising.aeron.service.order.admission.PlaceBatchAdmissionEvent;
import com.surprising.aeron.service.order.admission.PlaceBatchIntentSource;
import com.surprising.aeron.service.order.admission.ResolvedPlaceOrder;
import com.surprising.aeron.service.order.state.LaneCancelEvent;
import com.surprising.aeron.service.order.state.OrderRuntime;
import com.surprising.aeron.service.order.state.ReservationRuntime;
import com.surprising.aeron.service.position.PositionRuntime;
import com.surprising.aeron.service.risk.*;
import com.surprising.aeron.service.risk.LiquidationRuntime;
import com.surprising.aeron.service.risk.RiskScanRuntime;
import com.surprising.aeron.service.settlement.MatcherSettlementChanges;
import com.surprising.aeron.service.snapshot.state.*;
import com.surprising.aeron.service.snapshot.state.AccountLaneSnapshot;
import com.surprising.aeron.service.snapshot.state.RuntimeStateMaterializer;
import com.surprising.aeron.service.snapshot.state.TradingRuntimeSnapshot;
import com.surprising.aeron.service.treasury.SystemLedgerLane;
import com.surprising.aeron.service.treasury.TreasuryRuntime;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 账户运行时内部状态、所有权、预留生命周期、Lane 发布及恢复围栏测试。部分使用独立冷对象，部分真正启动账户或账本工作资源；内部数值夹具不等于完整协议业务准入，循环、反射和源码断言也不等于性能测试。
 */
class TradingAccountRuntimeTest {

    /**
     * 用已完成标记的未来批次回执登记预留，故意不执行其意图来源；确认它没有进入较早命令的订单/余额回滚缓冲，清较早变化后未来预留仍在且已发布可用余额仍一千。
     */
    @Test
    void futureBatchAdmissionDoesNotEnterEarlierCommandsRollbackBuffers() throws Exception {
        try (/* 当前被测原运行时，冷对象或按用例启动实际账户工作资源 */ var state = new TradingAccountRuntime()) {
            state.putUser(new UserRuntime(7));
            state.putBalance(new BalanceRuntime(7, 3, 1000, 0));
            state.clearChangedKeys();
            // 当前用户所属 Lane 下标或回调提供的实际账户 Lane
            int lane = state.topology().accountLaneId(7);
            // 本次模拟未来批次发布的原 Matcher 变化缓冲
            var changes = new MatcherSettlementChanges(state.topology().accountLaneCount());
            changes.ensureActiveLanes(1L << lane);
            changes.balancePatch(lane).add(7, 3, new BalanceRuntime(7, 3, 1000, 0), 0);
            changes.balancePatch(lane).after(7, 3, new BalanceRuntime(7, 3, 800, 200), 200);
            // 仅登记完成回执的未来原批次事件，不执行真实意图源
            var admission = new PlaceBatchAdmissionEvent().prepare(2, 7, java.util.UUID.randomUUID(),
                    new ResolvedPlaceOrder[1], new long[1],
                    new OrderRuntime[]{CoreStateTestFixtures.order(11, 7, 5, 2)},
                    1, lane, 0, state, changes, new RuntimeIdentityRegistry(),
                    /* 所有方法调用都会失败的占位意图源，本测试只登记已完成批次回执，不能意外执行它。 */ new PlaceBatchIntentSource() {
                        /**
                         * 这个占位意图来源不应被执行，若调用立即失败。
                         * @param index 占位意图源的方法下标，本测试不应真的执行该源
                         */
                        public com.surprising.aeron.protocol.PlaceOrderCommand intent(int index) { throw new AssertionError("unexpected execution"); }
                        /**
                         * 这个占位决策不应被求值，若调用立即失败。
                         * @param index 占位意图源的方法下标，本测试不应真的执行该源
                         */
                        public Decision decision(int index) { throw new AssertionError("unexpected execution"); }
                        /**
                         * 本测试只登记完成回执，不应再索取解析槽，若调用立即失败。
                         * @param index 占位意图源的方法下标，本测试不应真的执行该源
                         */
                        public ResolvedPlaceOrder resolvedOrderSlot(int index) { throw new AssertionError("unexpected execution"); }
                        /**
                         * 本测试不执行真实批次准入，不应捕获订单，若调用立即失败。
                         * @param index 占位意图源的方法下标，本测试不应真的执行该源
                         * @param order 占位捕获方法收到的原订单，或测试表 put 要写入的原订单
                         */
                        public OrderRuntime captureAdmittedOrder(int index, OrderRuntime order) { throw new AssertionError("unexpected execution"); }
                    }, 0, 0);
            // 通过反射仅用于本测试标记该原批次已完成的字段
            var complete = PlaceBatchAdmissionEvent.class.getDeclaredField("completed");
            complete.setAccessible(true);
            complete.setBoolean(admission, true);
            state.registerPlaceBatchAdmission(admission);
            assertThat(AccountLaneTestAccess.rollbackOrdersEmpty(state.accountRollback, lane)).isTrue();
            assertThat(state.accountRollback.balancePatch(lane).size()).isZero();
            // 较早命令结束不应读取未来批次的变化后值，两个命令的回滚缓冲分开。
            state.clearChangedKeys();
            assertThat(state.pendingReservations.pendingReservationCount()).isEqualTo(1);
            assertThat(state.publishedAvailableBalance(7, 3)).isEqualTo(1000);
        }
    }

    /**
     * 分别只记余额旧值、或已经写成八百，再回滚当前原命令；两者权威及已发布余额都应恢复一千，不能因失败发生在写之前而漏发布。
     * @param wroteBalance 是否真的先写余额，假则只记录旧值后模拟写入前失败
     */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void rollbackPublishesRestoredBalanceEvenWhenMutationFailedBeforeWriting(boolean wroteBalance) {
        try (/* 当前被测原运行时，冷对象或按用例启动实际账户工作资源 */ var state = new TradingAccountRuntime()) {
            state.putUser(new UserRuntime(7));
            state.putBalance(new BalanceRuntime(7, 3, 1000, 0));
            state.clearChangedKeys();
            if (wroteBalance) state.replaceBalance(7, 3, 800, 0);
            else state.accountRollback.captureBalanceBefore(7, 3);
            state.rollbackActiveCommand(0, 1);
            assertThat(state.balance(7, 3).availableUnits()).isEqualTo(1000);
            assertThat(state.publishedAvailableBalance(7, 3)).isEqualTo(1000);
            state.clearChangedKeys();
        }
    }

    /**
     * 用用户、标的、保证金模式探针读已有跨仓杠杆，逐仓未配置为空，再核对原值键读取一致；不测分配或吞吐。
     */
    @Test
    void leverageProbeReadsConfiguredValueWithoutChangingTheStoredKeyMap() {
        try (/* 当前被测原运行时，冷对象或按用例启动实际账户工作资源 */ var state = new TradingAccountRuntime()) {
            state.putLeverage(new CoreLeverageKey(7, "1", CoreMarginMode.CROSS), 5_000_000L);

            assertThat(state.leverage(7, "1", CoreMarginMode.CROSS)).isEqualTo(5_000_000L);
            assertThat(state.leverage(7, "1", CoreMarginMode.ISOLATED)).isNull();
            assertThat(state.leverage(new CoreLeverageKey(7, "1", CoreMarginMode.CROSS)))
                    .isEqualTo(5_000_000L);
        }
    }

    /**
     * 用两个实际账户 Lane 原订单盖原时间/日志位置，保护表禁止 Owner get/put；收集后复用同事件只改指定订单，再用不带元数据的事件检查旧盖章列表没有串入后续。
     */
    @Test
    void commitEventStampsOnLanesPublishesOnOwnerAndClearsReusedMetadata() throws Exception {
        // 当前夹具固定 Lane 数、容量与用户路由拓扑
        var topology = new LaneTopology(1, 2, LaneTopology.DEFAULT_ACCOUNT_LANE_SEED, 16, 16, 16);
        try (/* 当前被测原运行时，冷对象或按用例启动实际账户工作资源 */ var state = new TradingAccountRuntime(topology)) {
            // 从八开始寻找、确保与用户七不同 Lane 的第二用户
            long second = 8;
            while (topology.accountLaneId(second) == topology.accountLaneId(7)) second++;
            for (long /* 当前为不同 Lane 初始化的原用户 ID */ user : new long[]{7, second}) {
                state.putUser(new UserRuntime(user));
                state.putBalance(new BalanceRuntime(user, 3, 1000, 0));
            }
            CoreStateTestFixtures.reserveOrder(state, 11, 7, 91, 5, 2, 3, 200);
            CoreStateTestFixtures.reserveOrder(state, 12, second, 92, 5, 2, 3, 200);
            // 原订单十一的权威对象，用于检查盖元数据不另拷正文
            var original = state.order(11);
            state.clearChangedKeys();
            // 替换各 Lane 原订单表的测试保护器数组
            var maps = new OwnerForbiddenOrderMap[topology.accountLaneCount()];
            // 仅供本测试替换原账户订单表的反射字段
            var ordersField = AccountLaneState.class.getDeclaredField("orders");
            ordersField.setAccessible(true);
            for (/* 当前 Lane 遍历下标或实际控制回调提供的所属 Lane 下标 */ int id = 0; id < maps.length; id++) {
                maps[id] = new OwnerForbiddenOrderMap(AccountLaneTestAccess.orders(state.accountLanes[id]), Thread.currentThread());
                ordersField.set(state.accountLanes[id], maps[id]);
            }
            state.startAccountLanes();
            state.enterAsynchronousCommandScope();
            try {
                // 第一次给两用户订单盖提交元数据的原事件
                var first = state.dispatchLaneMutation(1, java.util.List.of(7L, second),
                        java.util.List.of(11L, 12L), 100, 200);
                awaitMetadataCommit(state, first);
                assertThat(maps[topology.accountLaneId(7)].inspect(11)).isSameAs(original);
                assertThat(state.ownerLaneAccess()).isFalse();
                state.releaseLaneCommit(first);
                assertThat(maps[topology.accountLaneId(7)].inspect(11).updatedAtEpochMillis()).isEqualTo(100);
                assertThat(maps[topology.accountLaneId(second)].inspect(12).clusterPosition()).isEqualTo(200);
                // 下一次复用原提交事件，预留用例中为候选第二单
                var next = state.dispatchLaneMutation(2, java.util.List.of(7L),
                        java.util.List.of(11L), 101, 201);
                assertThat(next).isSameAs(first);
                awaitMetadataCommit(state, next);
                state.releaseLaneCommit(next);
                assertThat(maps[topology.accountLaneId(7)].inspect(11).clusterPosition()).isEqualTo(201);
                assertThat(maps[topology.accountLaneId(second)].inspect(12).clusterPosition()).isEqualTo(200);
                // 复用池中不带任何订单盖章列表的原提交事件
                var plain = state.dispatchLaneMutation(3, java.util.List.of(7L, second));
                awaitMetadataCommit(state, plain);
                state.releaseLaneCommit(plain);
                assertThat(maps[topology.accountLaneId(7)].inspect(11).clusterPosition()).isEqualTo(201);
                assertThat(maps[topology.accountLaneId(second)].inspect(12).clusterPosition()).isEqualTo(200);
            } finally { state.exitAsynchronousCommandScope(); }
        }
    }

    /**
     * 仅覆盖 get/put 的测试订单表保护器，用来发现这两条路径上 Owner 越界；inspect 供断言有意绕过。
     */
    private static final class OwnerForbiddenOrderMap
            extends org.agrona.collections.Long2ObjectHashMap<OrderRuntime> {
        /** 禁止调用该测试表 get/put 的 Owner 线程。 */
        private final Thread forbidden;
        /**
         * 复制原订单表成员并记录禁止 get/put 的 Owner 线程，订单正文引用仍沿用原对象。
         * @param source 原 Lane 订单表，复制其成员但复用订单正文引用
         * @param owner 要禁止直接 get/put 该测试订单表的原 Owner 线程
         */
        OwnerForbiddenOrderMap(org.agrona.collections.Long2ObjectHashMap<OrderRuntime> source, Thread owner) {
            super(source);
            forbidden = owner;
        }
        /**
         * 若当前为被禁止 Owner 则失败，其他线程沿原表 get；本保护只覆盖此重载。
         * @param id 要读取或写入的原订单号
         */
        @Override public OrderRuntime get(long id) {
            if (Thread.currentThread() == forbidden) throw new AssertionError("Owner read an account order table");
            return super.get(id);
        }
        /**
         * 若当前为被禁止 Owner 则失败，其他线程沿原表 put；本保护不声称拦截所有表方法。
         * @param id 要读取或写入的原订单号
         * @param order 占位捕获方法收到的原订单，或测试表 put 要写入的原订单
         */
        @Override public OrderRuntime put(long id, OrderRuntime order) {
            if (Thread.currentThread() == forbidden) throw new AssertionError("Owner wrote an account order table");
            return super.put(id, order);
        }
        /**
         * 只供测试断言绕过 get 保护，读取已由 Lane 修改的原订单。
         * @param id 要读取或写入的原订单号
         */
        OrderRuntime inspect(long id) { return super.get(id); }
    }

    /**
     * 最多五秒等待指定原 Lane 提交事件完成，不在这里归还或清理事件。
     * @param state 本测试原账户运行时，按其所有权边界操作
     * @param event 尚未归还的原 Lane 元数据提交事件
     */
    private static void awaitMetadataCommit(TradingAccountRuntime state, LaneCommitEvent event) {
        // 当前回执、回滚或控制收集阶段的五秒单调截止值
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (!state.laneCommitComplete(event)) {
            if (System.nanoTime() > deadline) throw new AssertionError("metadata commit timed out");
            Thread.onSpinWait();
        }
    }

    /**
     * 真实 Lane 先预留两单，Owner 以普通或批次回执登记待完成身份；异步回滚仍由 Lane 恢复资金及订单/预留/客户端索引，Owner 不接管账户，所有 pending 数及单位数清零。
     * @param batch 是否按原批次回执登记/撤销预留，撤单用例中选择批撤
     */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void asynchronousRollbackRestoresReservedFundsAndBothReservationIndexes(boolean batch) {
        try (/* 当前被测原运行时，冷对象或按用例启动实际账户工作资源 */ var state = new TradingAccountRuntime()) {
            state.putUser(new UserRuntime(7));
            state.putBalance(new BalanceRuntime(7, 3, 1000, 0));
            state.clearChangedKeys();
            state.startAccountLanes();
            // 当前要派发、反射或读取的原账户 Lane 下标
            int laneId = state.accountLaneId(7);
            state.dispatchControlLanes(1L << laneId, /* 当前 Lane 遍历下标或实际控制回调提供的所属 Lane 下标 */ id -> {
                for (/* 当前数组或订单循环下标 */ int index = 0; index < 2; index++) {
                    CoreStateTestFixtures.reserveOrder(state, 11 + index, 7, 91 + index, 5, 2, 3, 200);
                    state.accountLanes[id].markPendingReservation(11 + index, 4);
                }
                return new OrderRuntime[]{state.order(11), state.order(12)};
            });
            awaitControlLanes(state);
            // 账户实际返回的两份原准入订单数组
            var admitted = (OrderRuntime[]) state.controlLaneResult(laneId);
            if (batch) {
                state.pendingReservations.registerBatch(4, 7, admitted, 2);
            } else {
                for (var /* 当前原准入或测试成员订单 */ order : admitted) state.pendingReservations.collectControlReservation(order, 4);
            }
            state.enterAsynchronousCommandScope();
            try {
                // 本次原命令失败后的异步 Lane 回滚续步
                var rollback = state.beginCommandRollback(0, 4);
                // 当前回执、回滚或控制收集阶段的五秒单调截止值
                long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                while (!rollback.getAsBoolean()) {
                    if (System.nanoTime() > deadline) throw new AssertionError("account rollback timed out");
                    Thread.onSpinWait();
                }
                assertThat(state.ownerLaneAccess()).isFalse();
            } finally { state.exitAsynchronousCommandScope(); }
            assertThat(state.pendingReservations.pendingReservationCount()).isZero();
            state.dispatchControlLanes(1L << laneId, /* 当前 Lane 遍历下标或实际控制回调提供的所属 Lane 下标 */ id -> {
                assertThat(state.balance(7, 3).availableUnits()).isEqualTo(1000);
                assertThat(state.balance(7, 3).lockedUnits()).isZero();
                assertThat(state.order(11)).isNull();
                assertThat(state.order(12)).isNull();
                assertThat(state.reservation(11)).isNull();
                assertThat(state.orderIdByClient(7, 91)).isNull();
                assertThat(state.pendingReservations.pendingReservationCount(7)).isZero();
                assertThat(state.pendingReservations.pendingReservedUnits(7, 3)).isZero();
                return null;
            });
            awaitControlLanes(state);
        }
    }

    /**
     * 两真实 Lane 各增两次修订，工作已执行但 Owner 未收回执时全局仍十；poll 收齐后才变十四，且仍无 Owner 账户访问权。
     */
    @Test
    void controlLanesPublishRevisionDeltasOnlyWhenOwnerCollects() throws Exception {
        // 当前夹具固定 Lane 数、容量与用户路由拓扑
        var topology = new LaneTopology(1, 2, LaneTopology.DEFAULT_ACCOUNT_LANE_SEED, 16, 16, 16);
        try (/* 当前被测原运行时，冷对象或按用例启动实际账户工作资源 */ var state = new TradingAccountRuntime(topology)) {
            state.setMetadata(com.surprising.product.api.ProductLine.SPOT, 10);
            state.startAccountLanes();
            // 通知两 Lane 已执行的门闩，或检查被拒绝内联回调没有执行的原子计数
            var executed = new java.util.concurrent.CountDownLatch(2);
            state.dispatchControlLanes(3, /* 当前 Lane 遍历下标或实际控制回调提供的所属 Lane 下标 */ id -> {
                state.accountLanes[id].assertOwner();
                state.incrementCommandRevision();
                state.incrementCommandRevision();
                executed.countDown();
                return true;
            });
            assertThat(executed.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(state.revision()).isEqualTo(10);
            // 当前回执、回滚或控制收集阶段的五秒单调截止值
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while (!state.pollControlLanes()) {
                if (System.nanoTime() > deadline) throw new AssertionError("control completion timed out");
                Thread.onSpinWait();
            }
            assertThat(state.revision()).isEqualTo(14);
            assertThat(state.ownerLaneAccess()).isFalse();
            assertThat(state.controlLaneResult(0)).isEqualTo(true);
            assertThat(state.controlLaneResult(1)).isEqualTo(true);
        }
    }

    /**
     * 只为四 Lane 中 2 号 Lane 的二十项准入预备容量，反射检查只有该 Lane 订单和预留缓冲到三十二，其余用户/持仓及其他 Lane 保持八槽。
     */
    @Test
    void admissionOnlyPreallocatesWrittenBuffersInItsOwnerLane() throws Exception {
        // 只供反射建立指定 Lane 数结算变化缓冲的原构造器
        var constructor = MatcherSettlementChanges.class.getDeclaredConstructor(int.class);
        constructor.setAccessible(true);
        // 本次模拟未来批次发布的原 Matcher 变化缓冲
        var changes = constructor.newInstance(4);
        changes.ensureAdmissionCapacity(2, 20);

        for (/* 当前用户所属 Lane 下标或回调提供的实际账户 Lane */ int lane = 0; lane < changes.balancePatchCount(); lane++) {
            for (String /* 当前要核对初始容量的 Lane 变化缓冲字段名 */ name : new String[]{"users", "orders", "reservations", "positions"}) {
                // 当前 Lane 原变化缓冲声明字段
                Field bufferField = changes.laneDelta(lane).getClass().getDeclaredField(name);
                bufferField.setAccessible(true);
                // 反射取得的原用户/订单/预留/持仓变化缓冲对象
                Object buffer = bufferField.get(changes.laneDelta(lane));
                // 原变化缓冲 keys 数组的反射字段
                Field keys = RuntimeChangeBuffer.class.getDeclaredField("keys");
                keys.setAccessible(true);
                assertThat(((long[]) keys.get(buffer)).length).as("lane %s %s", lane, name)
                        .isEqualTo(lane == 2 && (name.equals("orders") || name.equals("reservations")) ? 32 : 8);
            }
        }
    }

    /**
     * 让真实账户工作抛 NoSuchMethodError，检查空完成探测传播同一故障，关闭也传播原错但该例各账户和账本线程均已停止。
     */
    @Test
    void idleCompletionProbeRaisesLaneErrorAndShutdownStopsEveryWorker() throws Exception {
        // 当前夹具固定 Lane 数、容量与用户路由拓扑
        var topology = new LaneTopology(1, 2, LaneTopology.DEFAULT_ACCOUNT_LANE_SEED, 16, 16, 16);
        // 当前被测原运行时，冷对象或按用例启动实际账户工作资源
        var state = new TradingAccountRuntime(topology);
        state.setMetadata(com.surprising.product.api.ProductLine.SPOT, 10);
        // 故障工作发布完成唤醒后通知测试线程的门闩
        var announced = new java.util.concurrent.CountDownLatch(1);
        state.ownerCompletionSignal(announced::countDown);
        state.startAccountLanes();
        state.startSystemLedgerLane();
        // 原运行时 SystemLedgerLane 的反射字段
        // 本用例实际启动的原账本线程，关闭后应不再存活
        Thread ledgerThread = com.surprising.aeron.service.core.AccountLaneTestAccess.ledger(state).thread();
        // 预先构造并由原故障探测传播的异常，跨线程测试中为发布原异常的原子槽
        var failure = new NoSuchMethodError("synthetic settlement linkage failure");
        try {
            state.submitAccountCommand(0, /* 当前用户所属 Lane 下标或回调提供的实际账户 Lane */ lane -> { throw failure; });
            assertThat(announced.await(3, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(state::hasMatchingNotifications).isSameAs(failure);
        } finally {
            assertThatThrownBy(state::close).isSameAs(failure);
        }
        for (int laneId = 0; laneId < state.laneCount(); laneId++) assertThat(state.accountWorkerThread(laneId).isAlive()).isFalse();
        assertThat(ledgerThread.isAlive()).isFalse();
    }

    /**
     * 读取指定源码片段，检查 applied 只见序号/修订而无列出的哈希计算，且无旧检查点文本；运行时修订检查点直接返回 revision。属于有限文本约束，不是执行路径全证明。
     */
    @Test
    void accountLaneApplyOnlyAdvancesSequenceAndRevision() throws Exception {
        // 读到的原账户 Lane Java 源码全文
        String source = accountLaneSource();

        assertThat(methodSource(source, "void applied", "void requireApply"))
                .contains("appliedSequence", "revision")
                .doesNotContain("transitionHash", "computeStateHash", "computeFundsHash");
        assertThat(source).doesNotContain("record Checkpoint", "void rollback(Checkpoint",
                "pendingApplyCheckpoint", "pendingApplySequence");
        // 读到的原 TradingAccountRuntime Java 源码全文
        String runtimeSource = Files.readString(Path.of(
                "src/main/java/com/surprising/aeron/service/core/TradingAccountRuntime.java"));
        assertThat(methodSource(runtimeSource, "public long commandRevisionCheckpoint", "public void beginOrderBatch"))
                .contains("return revision")
                .doesNotContain("onLane(", "new CommandCheckpoint", "AccountLaneState.Checkpoint");
    }

    /**
     * 启动账户工作，捕获 Lane 快照前后比较每 Lane 的原本地状态/资金哈希，检查快照读取不重设这两个观测字段。
     */
    @Test
    void snapshotCaptureDoesNotRebaseLiveAccountLaneHashes() {
        // 当前被测原运行时，冷对象或按用例启动实际账户工作资源
        TradingAccountRuntime state = new TradingAccountRuntime();
        state.startAccountLanes();
        try {
            // 捕获快照前逐 Lane 复制的原数字对照数组
            LaneValues[] before = laneValues(state);

            state.accountLaneSnapshots(1, TradingCoreState.empty(
                    com.surprising.product.api.ProductLine.LINEAR_PERPETUAL));

            // 捕获快照后再次复制的 Lane 数字对照数组
            LaneValues[] after = laneValues(state);
            for (/* 当前要派发、反射或读取的原账户 Lane 下标 */ int laneId = 0; laneId < before.length; laneId++) {
                assertThat(after[laneId].localStateHash())
                        .as("snapshot must not mutate lane %s state hash", laneId)
                        .isEqualTo(before[laneId].localStateHash());
                assertThat(after[laneId].localFundsHash())
                        .as("snapshot must not mutate lane %s funds hash", laneId)
                        .isEqualTo(before[laneId].localFundsHash());
            }
        } finally {
            state.close();
        }
    }

    /**
     * 构造两个用户游标不同的未完扫描，按最早 lastScheduledRevision 选二号；更新其被服务修订后改选一号，不让低用户游标永久抢先。
     */
    @Test
    void riskSchedulerSelectsLeastRecentlyServedWorkRegardlessOfUserProgress() {
        // 当前被测原运行时，冷对象或按用例启动实际账户工作资源
        TradingAccountRuntime state = new TradingAccountRuntime();
        state.putRiskScan(incompleteRiskScan(1, 0).withLastScheduledRevision(20));
        state.putRiskScan(incompleteRiskScan(2, 900).withLastScheduledRevision(10));

        assertThat(state.firstRiskIncompleteScan().symbolId())
                .as("a low user cursor must not repeatedly preempt older work")
                .isEqualTo(2);
        state.putRiskScan(state.riskScan(2).withLastScheduledRevision(30));
        assertThat(state.firstRiskIncompleteScan().symbolId()).isEqualTo(1);
    }

    /**
     * 用内部用户、资产、订单及预留数字键写读，核对余额写入复制、客户端别名及变化 ID/脏标记；不以本例断言证明全系统内存布局或性能。
     */
    @Test
    void keepsHotIndexesFlatAndTracksChangedKeys() {
        // 当前被测原运行时，冷对象或按用例启动实际账户工作资源
        TradingAccountRuntime state = new TradingAccountRuntime();
        state.putUser(new UserRuntime(7));
        // 当前原余额值，独立 BalanceRuntime 用例可直接冻结/释放
        BalanceRuntime balance = new BalanceRuntime(7, 3, 1_000, 0);
        state.putBalance(balance);
        state.putOrder(CoreStateTestFixtures.order(11, 7, 5, 2));
        state.putReservation(CoreStateTestFixtures.reservation(11, 7, 3, 200));
        state.putClientOrder(7, 91, 11);

        assertThat(state.user(7).userId()).isEqualTo(7);
        assertThat(state.balance(7, 3)).isNotSameAs(balance);
        assertThat(state.balance(7, 3).availableUnits()).isEqualTo(1_000);
        assertThat(state.order(11).symbolId()).isEqualTo(5);
        assertThat(state.reservation(11).reservedUnits()).isEqualTo(200);
        assertThat(state.orderIdByClient(7, 91)).isEqualTo(11);
        assertThat(state.changedUsers().contains(7L)).isTrue();
        assertThat(state.snapshotProjectionStateDirty()).isTrue();
        assertThat(state.changedOrders().contains(11L)).isTrue();
        assertThat(state.changedReservations().contains(11L)).isTrue();
    }

    /**
     * 先绑定 Owner，再从另一普通线程 assertOwner，检查明确拒绝；线程 join 没有另设期限。
     */
    @Test
    void protectsSingleWriterBoundary() throws InterruptedException {
        // 当前被测原运行时，冷对象或按用例启动实际账户工作资源
        TradingAccountRuntime state = new TradingAccountRuntime();
        state.bindOwner();
        // 预先构造并由原故障探测传播的异常，跨线程测试中为发布原异常的原子槽
        AtomicReference<Throwable> failure = new AtomicReference<>();
        // 模拟越界调用或修改已脱离副本的另一普通线程
        Thread other = new Thread(() -> {
            try {
                state.assertOwner();
            } catch (/* 另一普通线程捕获的原门禁异常 */ Throwable throwable) {
                failure.set(throwable);
            }
        });
        other.start();
        other.join();
        assertThat(failure.get()).isInstanceOf(IllegalStateException.class);
    }

    /**
     * 冷状态取得的余额是独立值，另一线程修改副本到一千零一不改变权威一千；不是授权跨线程修改 Lane 内部余额。
     */
    @Test
    void returnedBalanceIsDetachedFromTheLaneOwnedAuthoritativeBalance() throws InterruptedException {
        // 当前夹具固定 Lane 数、容量与用户路由拓扑
        LaneTopology topology = LaneTopology.productionDefault();
        // 当前被测原运行时，冷对象或按用例启动实际账户工作资源
        TradingAccountRuntime state = new TradingAccountRuntime(topology);
        // 本次内部用户 ID，部分用例按固定 Lane 路由寻找
        long userId = userForLane(topology, 0);
        state.putUser(new UserRuntime(userId));
        state.putBalance(new BalanceRuntime(userId, 3, 1_000, 0));
        // 从冷运行时取得并可独立修改的原余额副本
        BalanceRuntime retained = state.balance(userId, 3);
        // 模拟越界调用或修改已脱离副本的另一普通线程
        Thread other = new Thread(() -> retained.credit(1));
        other.start();
        other.join();

        assertThat(retained.availableUnits()).isEqualTo(1_001);
        assertThat(state.balance(userId, 3).availableUnits()).isEqualTo(1_000);
    }

    /**
     * 即使早已取得 Treasury 引用，另一线程仍不能写费用；异常后原费用七保持。
     */
    @Test
    void retainedTreasuryReferenceStillEnforcesRuntimeOwner() throws InterruptedException {
        // 当前被测原运行时，冷对象或按用例启动实际账户工作资源
        TradingAccountRuntime state = new TradingAccountRuntime();
        // 已经取得但仍执行原线程所有权校验的 Treasury 引用
        TreasuryRuntime treasury = state.treasury();
        treasury.setFee(3, 7);
        // 预先构造并由原故障探测传播的异常，跨线程测试中为发布原异常的原子槽
        AtomicReference<Throwable> failure = new AtomicReference<>();
        // 模拟越界调用或修改已脱离副本的另一普通线程
        Thread other = new Thread(() -> {
            try {
                treasury.setFee(3, 8);
            } catch (/* 另一普通线程捕获的原门禁异常 */ Throwable throwable) {
                failure.set(throwable);
            }
        });

        other.start();
        other.join();

        assertThat(failure.get()).isInstanceOf(IllegalStateException.class);
        assertThat(treasury.fee(3)).isEqualTo(7);
    }

    /**
     * 原线程首次分配资产绑定身份字典后，另一线程新增资产被拒绝。
     */
    @Test
    void identityRegistryRejectsCrossThreadMutation() throws InterruptedException {
        // 本用例原资产、标的、客户端和持仓身份字典
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        identities.assetId("USDT");
        // 预先构造并由原故障探测传播的异常，跨线程测试中为发布原异常的原子槽
        AtomicReference<Throwable> failure = new AtomicReference<>();
        // 模拟越界调用或修改已脱离副本的另一普通线程
        Thread other = new Thread(() -> {
            try {
                identities.assetId("BTC");
            } catch (/* 另一普通线程捕获的原门禁异常 */ Throwable throwable) {
                failure.set(throwable);
            }
        });

        other.start();
        other.join();

        assertThat(failure.get()).isInstanceOf(IllegalStateException.class);
    }

    /**
     * 检查正常冻结二百五十及释放一百的准确数字，随后超过可用余额的冻结拒绝；算术溢出另由下一用例覆盖。
     */
    @Test
    void reservesAndReleasesWithoutOverflow() {
        // 当前原余额值，独立 BalanceRuntime 用例可直接冻结/释放
        BalanceRuntime balance = new BalanceRuntime(7, 3, 1_000, 0);
        balance.reserve(250);
        assertThat(balance.availableUnits()).isEqualTo(750);
        assertThat(balance.lockedUnits()).isEqualTo(250);
        balance.release(100);
        assertThat(balance.availableUnits()).isEqualTo(850);
        assertThat(balance.lockedUnits()).isEqualTo(150);
        assertThatThrownBy(() -> balance.reserve(851)).isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * 冻结数已到 long 上限再冻结一，精确加法溢出时原可用一百与冻结上限都不被部分改写。
     */
    @Test
    void failedReserveDoesNotPartiallyChangeBalance() {
        // 当前原余额值，独立 BalanceRuntime 用例可直接冻结/释放
        BalanceRuntime balance = new BalanceRuntime(7, 3, 100, Long.MAX_VALUE);

        assertThatThrownBy(() -> balance.reserve(1)).isInstanceOf(ArithmeticException.class);
        assertThat(balance.availableUnits()).isEqualTo(100);
        assertThat(balance.lockedUnits()).isEqualTo(Long.MAX_VALUE);
    }

    /**
     * 原预留辅助同时登记订单、用户客户端身份并冻结二百，检查可用八百、冻结二百和订单数量二。
     */
    @Test
    void reservesOrderAndFundsAsOneRuntimeTransition() {
        // 当前被测原运行时，冷对象或按用例启动实际账户工作资源
        TradingAccountRuntime state = new TradingAccountRuntime();
        state.putUser(new UserRuntime(7));
        state.putBalance(new BalanceRuntime(7, 3, 1_000, 0));

        CoreStateTestFixtures.reserveOrder(state, 11, 7, 91, 5, 2, 3, 200);

        assertThat(state.balance(7, 3).availableUnits()).isEqualTo(800);
        assertThat(state.balance(7, 3).lockedUnits()).isEqualTo(200);
        assertThat(state.order(11).quantitySteps()).isEqualTo(2);
        assertThat(state.orderIdByClient(7, 91)).isEqualTo(11);
    }

    /**
     * 以错误原序号完成预留先拒绝且计数/金额不变，再用正确序号完成后两者归零。
     */
    @Test
    void rejectedReservationCompletionPreservesCountersAndCanCompleteLater() {
        // 当前被测原运行时，冷对象或按用例启动实际账户工作资源
        TradingAccountRuntime state = new TradingAccountRuntime();
        state.putUser(new UserRuntime(7));
        state.putReservation(CoreStateTestFixtures.reservation(11, 7, 3, 200));
        state.markPendingReservation(7, 11, 4);

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> state.completePendingReservation(7, 11, 5));
        assertThat(state.pendingReservations.pendingReservationCount(7)).isEqualTo(1);
        assertThat(state.pendingReservations.pendingReservedUnits(7, 3)).isEqualTo(200);

        state.completePendingReservation(7, 11, 4);
        assertThat(state.pendingReservations.pendingReservationCount(7)).isZero();
        assertThat(state.pendingReservations.pendingReservedUnits(7, 3)).isZero();
    }

    /**
     * 待完成预留结束后，原用户客户端单号仍映射到既有订单，不误删活跃身份。
     */
    @Test
    void reservationCompletionUsesTheOrderToClientReverseIndex() {
        // 当前被测原运行时，冷对象或按用例启动实际账户工作资源
        TradingAccountRuntime state = new TradingAccountRuntime();
        state.putUser(new UserRuntime(7));
        state.putBalance(new BalanceRuntime(7, 3, 1_000, 0));
        CoreStateTestFixtures.reserveOrder(state, 11, 7, 91, 5, 2, 3, 200);
        state.markPendingReservation(7, 11, 4);
        state.clearChangedKeys();

        state.completePendingReservation(7, 11, 4);

        assertThat(state.orderIdByClient(7, 91)).isEqualTo(11);
    }

    /**
     * 先主动移除客户端别名，再完成原预留，不能从旧反向索引复活已删除别名。
     */
    @Test
    void removingAClientOrderAlsoRemovesItsReverseIndexEntry() {
        // 当前被测原运行时，冷对象或按用例启动实际账户工作资源
        TradingAccountRuntime state = new TradingAccountRuntime();
        state.putUser(new UserRuntime(7));
        state.putBalance(new BalanceRuntime(7, 3, 1_000, 0));
        CoreStateTestFixtures.reserveOrder(state, 11, 7, 91, 5, 2, 3, 200);
        state.markPendingReservation(7, 11, 4);
        state.removeClientOrder(7, 91);
        state.clearChangedKeys();

        state.completePendingReservation(7, 11, 4);

        assertThat(state.orderIdByClient(7, 91)).isNull();
    }

    /**
     * 同订单增多个别名，再把一别名改指另一订单并删旧别名；完成首预留后应只保留当前第二单别名，原冻结资金不漂移。
     */
    @Test
    void reassigningAliasesDoesNotLeaveStaleReverseMappings() {
        // 当前被测原运行时，冷对象或按用例启动实际账户工作资源
        TradingAccountRuntime state = new TradingAccountRuntime();
        state.putUser(new UserRuntime(7));
        state.putBalance(new BalanceRuntime(7, 3, 1_000, 0));
        CoreStateTestFixtures.reserveOrder(state, 11, 7, 91, 5, 2, 3, 200);
        CoreStateTestFixtures.reserveOrder(state, 12, 7, 92, 5, 2, 3, 200);
        state.putClientOrder(7, 93, 11);
        state.putClientOrder(7, 94, 11);
        state.putClientOrder(7, 93, 12);
        state.removeClientOrder(7, 91);
        state.removeClientOrder(7, 94);
        state.markPendingReservation(7, 11, 4);
        state.completePendingReservation(7, 11, 4);
        assertThat(state.orderIdByClient(7, 91)).isNull();
        assertThat(state.orderIdByClient(7, 94)).isNull();
        assertThat(state.orderIdByClient(7, 92)).isEqualTo(12);
        assertThat(state.orderIdByClient(7, 93)).isEqualTo(12);
        assertThat(state.balance(7, 3).availableUnits()).isEqualTo(600);
        assertThat(state.balance(7, 3).lockedUnits()).isEqualTo(400);
    }

    /**
     * 构造百用户十资产各十份共一万待完成预留，逐用户及资产核对直接累计数，统一完成后归零；另检查指定读取源码没有列出的遍历形式，不是时延压测。
     */
    @Test
    void pendingReservationCountersTrackUsersAndAssetsInConstantTime() throws Exception {
        // 准备一万待完成预留，分布在一百用户、十种资产，每组十份。
        // 当前被测原运行时，冷对象或按用例启动实际账户工作资源
        TradingAccountRuntime state = new TradingAccountRuntime();
        // 当前创建或核对的原订单业务号
        long orderId = 1;
        for (/* 本次内部用户 ID，部分用例按固定 Lane 路由寻找 */ long userId = 1; userId <= 100; userId++) {
            state.putUser(new UserRuntime(userId));
            for (/* 当前内部资产编号，注册身份场景由字典分配 */ int assetId = 1; assetId <= 10; assetId++) {
                for (/* 当前用户、资产组合内十份预留的构造下标 */ int reservation = 0; reservation < 10; reservation++) {
                    state.putReservation(CoreStateTestFixtures.reservation(orderId, userId, assetId, assetId));
                    state.markPendingReservation(userId, orderId, 1);
                    orderId++;
                }
            }
        }

        // 逐用户及用户/资产读取原累计计数并核对准确。
        for (/* 本次内部用户 ID，部分用例按固定 Lane 路由寻找 */ long userId = 1; userId <= 100; userId++) {
            assertThat(state.pendingReservations.pendingReservationCount(userId)).isEqualTo(100);
            for (/* 当前内部资产编号，注册身份场景由字典分配 */ int assetId = 1; assetId <= 10; assetId++) {
                assertThat(state.pendingReservations.pendingReservedUnits(userId, assetId)).isEqualTo(10L * assetId);
            }
        }

        // 收到对应原序号完成后，所有 Lane 与全局 pending 计数都归零，业务预留正文不在这里删除。
        state.completePendingReservations(1);
        assertThat(state.pendingReservations.pendingReservationCount()).isZero();
        assertThat(state.hasPendingReservations()).isFalse();
        for (/* 本次内部用户 ID，部分用例按固定 Lane 路由寻找 */ long userId = 1; userId <= 100; userId++) {
            assertThat(state.pendingReservations.pendingReservationCount(userId)).isZero();
            for (/* 当前内部资产编号，注册身份场景由字典分配 */ int assetId = 1; assetId <= 10; assetId++) {
                assertThat(state.pendingReservations.pendingReservedUnits(userId, assetId)).isZero();
            }
        }

        // 读到的原账户 Lane Java 源码全文
        String source = accountLaneSource();
        assertThat(methodSource(source, "long pendingReservedUnits", "int pendingReservationCount"))
                .doesNotContain("forEach", "->", "pendingReservationSequences");
        assertThat(methodSource(source, "int pendingReservationCount", "boolean hasPendingReservations"))
                .doesNotContain("forEach", "->", "pendingReservationSequences");
    }

    /**
     * 正确完成一次后重复完成拒绝，已归零计数、原资金及修订保持，不在本例额外断言所有 Lane 哈希。
     */
    @Test
    void duplicateReservationCompletionFailsWithoutCounterDrift() {
        // 准备一份已登记 pending 且冻结二百的原预留。
        // 当前被测原运行时，冷对象或按用例启动实际账户工作资源
        TradingAccountRuntime state = new TradingAccountRuntime();
        state.putUser(new UserRuntime(7));
        state.putBalance(new BalanceRuntime(7, 3, 1_000, 0));
        CoreStateTestFixtures.reserveOrder(state, 11, 7, 91, 5, 2, 3, 200);
        state.markPendingReservation(7, 11, 4);

        // 先正确完成一次，再重复请求同一完成。
        state.completePendingReservation(7, 11, 4);
        // 首次正确完成原预留后的修订，失败重试不能改变它
        long revisionAfterFirstCompletion = state.revision();
        // 首次正确完成原预留后复制的余额对照
        BalanceRuntime balanceAfterFirstCompletion = state.balance(7, 3);
        assertThatThrownBy(() -> state.completePendingReservation(7, 11, 4))
                .isInstanceOf(IllegalStateException.class);

        // 检查失败重试没有改变已归零计数、原余额及修订；本例不额外断言 Lane 哈希。
        assertThat(state.pendingReservations.pendingReservationCount()).isZero();
        assertThat(state.pendingReservations.pendingReservationCount(7)).isZero();
        assertThat(state.pendingReservations.pendingReservedUnits(7, 3)).isZero();
        assertThat(state.balance(7, 3).availableUnits())
                .isEqualTo(balanceAfterFirstCompletion.availableUnits());
        assertThat(state.balance(7, 3).lockedUnits()).isEqualTo(balanceAfterFirstCompletion.lockedUnits());
        assertThat(state.revision()).isEqualTo(revisionAfterFirstCompletion);
    }

    /**
     * 重复登记或用未标记序号完成都拒绝，原 pending 一、金额二百及可用/冻结仍八百/二百。
     */
    @Test
    void duplicateMarkAndMissingCompletionFailWithoutCounterDrift() {
        // 准备一份只标记过一次 pending 的原预留。
        // 当前被测原运行时，冷对象或按用例启动实际账户工作资源
        TradingAccountRuntime state = new TradingAccountRuntime();
        state.putUser(new UserRuntime(7));
        state.putBalance(new BalanceRuntime(7, 3, 1_000, 0));
        CoreStateTestFixtures.reserveOrder(state, 11, 7, 91, 5, 2, 3, 200);
        state.markPendingReservation(7, 11, 4);

        // 分别重复标记，以及用从未标记的原序号请求完成。
        assertThatThrownBy(() -> state.markPendingReservation(7, 11, 4))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> state.completePendingReservation(7, 11, 5))
                .isInstanceOf(IllegalStateException.class);

        // 两种失败都不能改变原 pending 数、金额及已冻结资金。
        assertThat(state.pendingReservations.pendingReservationCount()).isEqualTo(1);
        assertThat(state.pendingReservations.pendingReservationCount(7)).isEqualTo(1);
        assertThat(state.pendingReservations.pendingReservedUnits(7, 3)).isEqualTo(200);
        assertThat(state.balance(7, 3).availableUnits()).isEqualTo(800);
        assertThat(state.balance(7, 3).lockedUnits()).isEqualTo(200);
    }

    /**
     * 在没有在途预留时保存 Lane 围栏，随后新增 pending 并释放五十，检查单位计数一百五十且不能再取新快照；恢复旧 Lane 围栏后临时 pending 清零，当前业务余额仍八百/二百，不把此辅助误作全业务余额回退。
     */
    @Test
    void pendingReservationReplacementAndRestoreKeepTransientCountersExact() {
        // 在没有在途预留的原围栏上保存 Lane 快照。
        // 当前被测原运行时，冷对象或按用例启动实际账户工作资源
        TradingAccountRuntime state = new TradingAccountRuntime();
        // 本用例原资产、标的、客户端和持仓身份字典
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        // 当前内部资产编号，注册身份场景由字典分配
        int assetId = identities.assetId("USDT");
        state.putUser(new UserRuntime(7));
        state.putBalance(new BalanceRuntime(7, assetId, 1_000, 0));
        // 用于 Lane 快照围栏校验的原冷全局状态
        TradingCoreState global = RuntimeStateMaterializer.materialize(state, identities);
        // 同一原围栏捕获的全部账户 Lane 快照列表
        var snapshots = state.accountLaneSnapshots(1, global);
        CoreStateTestFixtures.reserveOrder(state, 11, 7, 91, 5, 2, assetId, 200);
        state.markPendingReservation(7, 11, 2);

        // 新增原 pending 后部分释放，再恢复此前 Lane 快照围栏。
        state.replaceReservation(state.reservation(11).release(50));
        assertThat(state.pendingReservations.pendingReservationCount(7)).isEqualTo(1);
        assertThat(state.pendingReservations.pendingReservedUnits(7, assetId)).isEqualTo(150);
        assertThatThrownBy(() -> state.accountLaneSnapshots(2, global))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pending reservation");
        state.restoreAccountLaneSnapshots(snapshots, 1, global);

        // 旧 Lane 围栏恢复后不保留临时 pending 标志和计数，当前业务余额按本辅助原行为保持。
        assertThat(state.pendingReservations.pendingReservationCount()).isZero();
        assertThat(state.pendingReservations.pendingReservationCount(7)).isZero();
        assertThat(state.pendingReservations.pendingReservedUnits(7, assetId)).isZero();
        assertThat(state.hasPendingReservations()).isFalse();
        assertThat(state.balance(7, assetId).availableUnits()).isEqualTo(800);
        assertThat(state.balance(7, assetId).lockedUnits()).isEqualTo(200);
    }

    /**
     * 先消费预留五十并同步冻结余额，再撤单释放余一百五十；原 pending 身份仍一份、金额已零，可用九百五十，资金释放不等于完成回执已到。
     */
    @Test
    void pendingReservationConsumptionAndCancellationKeepCountersAndFundsExact() {
        // 准备一份仍待完成且资金已冻结的原预留。
        // 当前被测原运行时，冷对象或按用例启动实际账户工作资源
        TradingAccountRuntime state = new TradingAccountRuntime();
        state.putUser(new UserRuntime(7));
        state.putBalance(new BalanceRuntime(7, 3, 1_000, 0));
        CoreStateTestFixtures.reserveOrder(state, 11, 7, 91, 5, 2, 3, 200);
        state.markPendingReservation(7, 11, 4);

        // 先消费原预留一部分并更新冻结，再撤单释放剩余部分。
        state.replaceReservation(state.reservation(11).consume(50));
        state.replaceBalance(new BalanceRuntime(7, 3, 800, 150));
        assertThat(state.pendingReservations.pendingReservedUnits(7, 3)).isEqualTo(150);
        state.cancelOrder(11, 7, 150);

        // 检查待完成金额归零且余额准确释放；pending 条数仍一，等待另行完成回执。
        assertThat(state.pendingReservations.pendingReservationCount(7)).isEqualTo(1);
        assertThat(state.pendingReservations.pendingReservedUnits(7, 3)).isZero();
        assertThat(state.balance(7, 3).availableUnits()).isEqualTo(950);
        assertThat(state.balance(7, 3).lockedUnits()).isZero();
    }

    /**
     * 尚有 pending 身份时直接删预留必须拒绝，正文、全局/用户计数及冻结资金不变。
     */
    @Test
    void rejectsPendingReservationRemovalWithoutCounterOrFundsDrift() {
        // 准备所属 Lane 已标记 pending 并冻结资金的原预留。
        // 当前被测原运行时，冷对象或按用例启动实际账户工作资源
        TradingAccountRuntime state = new TradingAccountRuntime();
        state.putUser(new UserRuntime(7));
        state.putBalance(new BalanceRuntime(7, 3, 1_000, 0));
        CoreStateTestFixtures.reserveOrder(state, 11, 7, 91, 5, 2, 3, 200);
        state.markPendingReservation(7, 11, 4);

        // 尚未收到生命周期完成就尝试直接删除原预留。
        assertThatThrownBy(() -> state.removeReservation(11, 7)).isInstanceOf(IllegalStateException.class);

        // 删除拒绝后原预留正文、pending 计数及冻结都不变。
        assertThat(state.reservation(11)).isNotNull();
        assertThat(state.pendingReservations.pendingReservationCount()).isEqualTo(1);
        assertThat(state.pendingReservations.pendingReservationCount(7)).isEqualTo(1);
        assertThat(state.pendingReservations.pendingReservedUnits(7, 3)).isEqualTo(200);
        assertThat(state.balance(7, 3).availableUnits()).isEqualTo(800);
        assertThat(state.balance(7, 3).lockedUnits()).isEqualTo(200);
    }

    /**
     * 独立冷 Lane 已 pending long 上限单位数，再标记一单位的另一份时溢出，旧 pending 不动且新条目不半登记。
     */
    @Test
    void laneCounterOverflowFailsBeforePendingStateChanges() {
        // 在独立冷账户 Lane 准备一份 long 上限金额的 pending。
        // 当前用户所属 Lane 下标或回调提供的实际账户 Lane
        AccountLaneState lane = new AccountLaneState(0, 8);
        AccountLaneTestAccess.reservations(lane).put(1, CoreStateTestFixtures.reservation(1, 7, 3, Long.MAX_VALUE));
        lane.markPendingReservation(1, 1);
        AccountLaneTestAccess.reservations(lane).put(2, CoreStateTestFixtures.reservation(2, 7, 3, 1));

        // 下一份标记将使 pending 金额精确加法溢出。
        assertThatThrownBy(() -> lane.markPendingReservation(2, 1)).isInstanceOf(ArithmeticException.class);

        // 失败标记不能改旧 pending，也不能把新身份登记一半。
        assertThat(lane.pendingReservation(1)).isTrue();
        assertThat(lane.pendingReservation(2)).isFalse();
        assertThat(lane.pendingReservationCount(7)).isEqualTo(1);
        assertThat(lane.pendingReservedUnits(7, 3)).isEqualTo(Long.MAX_VALUE);
    }

    /**
     * 原全局没有 pending 时尝试完成，必须先拒绝且全局及用户计数仍零。
     */
    @Test
    void globalPendingCounterUnderflowFailsBeforeLaneStateChanges() {
        // 准备没有任何 pending 的原运行时。
        // 当前被测原运行时，冷对象或按用例启动实际账户工作资源
        TradingAccountRuntime state = new TradingAccountRuntime();

        // 全局原 pending 为零时尝试完成一个原订单。
        assertThatThrownBy(() -> state.completePendingReservation(7, 11, 4))
                .isInstanceOf(IllegalStateException.class);

        // 拒绝后全局、用户及用户/资产计数仍为零。
        assertThat(state.pendingReservations.pendingReservationCount()).isZero();
        assertThat(state.pendingReservations.pendingReservationCount(7)).isZero();
        assertThat(state.pendingReservations.pendingReservedUnits(7, 3)).isZero();
    }

    /**
     * 构造两归属 Lane 的冷预留后故意删后 Lane 正文，批次完成预检查应在任何早 Lane 修改前拒绝；比较原预留、资金、两套 Owner 索引、修订及本地哈希不变。
     */
    @Test
    void pendingReservationBatchRejectsLaterLaneBeforeEarlierLaneDrifts() throws Exception {
        // 准备分别归属两个冷账户 Lane 的原 pending 预留。
        // 当前夹具固定 Lane 数、容量与用户路由拓扑
        LaneTopology topology = LaneTopology.productionDefault();
        // 按拓扑固定归属于零号 Lane 的原用户
        long firstUser = userForLane(topology, 0);
        // 按拓扑固定归属于一号 Lane 的原用户
        long laterUser = userForLane(topology, 1);
        // 当前被测原运行时，冷对象或按用例启动实际账户工作资源
        TradingAccountRuntime state = new TradingAccountRuntime(topology);
        state.putUser(new UserRuntime(firstUser));
        state.putUser(new UserRuntime(laterUser));
        state.putBalance(new BalanceRuntime(firstUser, 3, 1_000, 0));
        state.putBalance(new BalanceRuntime(laterUser, 3, 1_000, 0));
        CoreStateTestFixtures.reserveOrder(state, 11, firstUser, 91, 5, 2, 3, 200);
        CoreStateTestFixtures.reserveOrder(state, 12, laterUser, 92, 5, 2, 3, 200);
        state.markPendingReservation(firstUser, 11, 4);
        state.markPendingReservation(laterUser, 12, 4);
        // 故障预检查前首 Lane 原预留正文
        ReservationRuntime firstReservation = state.reservation(11);
        // 故障预检查前首用户原独立余额对照
        BalanceRuntime firstBalance = state.balance(firstUser, 3);
        // 首用户原所属 Lane 下标
        int firstLaneId = topology.accountLaneId(firstUser);
        // 故障预检查前首 Lane 修订、水位及本地哈希对照
        LaneValues firstLane = laneValues(state, firstLaneId);
        // 故障前原 Core 序号关联的 pending 订单清单
        long[] pendingOrderIds = pendingReservationOrderIds(state, 4);
        // 故障前第一订单在原 pending 反向表中的用户
        long firstPendingOwner = pendingReservationOwner(state, 11);
        // 故障前第二订单在原 pending 反向表中的用户
        long laterPendingOwner = pendingReservationOwner(state, 12);
        AccountLaneTestAccess.reservations(accountLaneState(state, topology.accountLaneId(laterUser))).remove(12);

        // 故意删后 Lane 原正文，使批次预检查在该 Lane 失败。
        assertThatThrownBy(() -> state.completePendingReservations(4)).isInstanceOf(IllegalStateException.class);

        // 检查首 Lane 正文、资金与哈希及原 Owner 两套 pending 索引均未漂移。
        assertThat(state.reservation(11)).isEqualTo(firstReservation);
        assertThat(state.pendingReservations.pendingReservationCount()).isEqualTo(2);
        assertThat(state.pendingReservations.pendingReservationCount(firstUser)).isEqualTo(1);
        assertThat(state.pendingReservations.pendingReservationCount(laterUser)).isEqualTo(1);
        assertThat(state.pendingReservations.pendingReservedUnits(firstUser, 3)).isEqualTo(200);
        assertThat(state.pendingReservations.pendingReservation(11, firstUser)).isTrue();
        assertThat(state.pendingReservations.pendingReservation(12, laterUser)).isTrue();
        assertThat(pendingReservationOrderIds(state, 4)).containsExactlyInAnyOrder(pendingOrderIds);
        assertThat(pendingReservationOwner(state, 11)).isEqualTo(firstPendingOwner);
        assertThat(pendingReservationOwner(state, 12)).isEqualTo(laterPendingOwner);
        assertThat(state.balance(firstUser, 3).availableUnits()).isEqualTo(firstBalance.availableUnits());
        assertThat(state.balance(firstUser, 3).lockedUnits()).isEqualTo(firstBalance.lockedUnits());
        // 后 Lane 正文缺失导致拒绝后，首 Lane 再次读取的数字对照
        LaneValues firstLaneAfterFailure = laneValues(state, firstLaneId);
        assertThat(firstLaneAfterFailure.revision()).isEqualTo(firstLane.revision());
        assertThat(firstLaneAfterFailure.localStateHash()).isEqualTo(firstLane.localStateHash());
        assertThat(firstLaneAfterFailure.localFundsHash()).isEqualTo(firstLane.localFundsHash());
    }

    /**
     * 已有原预留后，以重复订单号或重复用户客户端键再建单都拒绝，冻结仍二百。
     */
    @Test
    void rejectsDuplicateOrderAndClientWithoutChangingFunds() {
        // 当前被测原运行时，冷对象或按用例启动实际账户工作资源
        TradingAccountRuntime state = new TradingAccountRuntime();
        state.putUser(new UserRuntime(7));
        state.putBalance(new BalanceRuntime(7, 3, 1_000, 0));
        CoreStateTestFixtures.reserveOrder(state, 11, 7, 91, 5, 2, 3, 200);

        assertThatThrownBy(() -> CoreStateTestFixtures.reserveOrder(state, 11, 7, 92, 5, 2, 3, 100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CoreStateTestFixtures.reserveOrder(state, 12, 7, 91, 5, 2, 3, 100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(state.balance(7, 3).availableUnits()).isEqualTo(800);
        assertThat(state.balance(7, 3).lockedUnits()).isEqualTo(200);
    }

    /**
     * 可用一百却申请冻结一百零一，检查资金不足前就拒绝，不残留订单、预留或客户端映射。
     */
    @Test
    void insufficientFundsRejectsBeforeCreatingRuntimeEntities() {
        // 当前被测原运行时，冷对象或按用例启动实际账户工作资源
        TradingAccountRuntime state = new TradingAccountRuntime();
        state.putUser(new UserRuntime(7));
        state.putBalance(new BalanceRuntime(7, 3, 100, 0));

        assertThatThrownBy(() -> CoreStateTestFixtures.reserveOrder(state, 11, 7, 91, 5, 2, 3, 101))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(state.order(11)).isNull();
        assertThat(state.reservation(11)).isNull();
        assertThat(state.orderIdByClient(7, 91)).isNull();
        assertThat(state.balance(7, 3).availableUnits()).isEqualTo(100);
        assertThat(state.balance(7, 3).lockedUnits()).isZero();
    }

    /**
     * 用同用户两资产分别读已发布余额，检查脏标记随写、清变化及显式 markBalancesChanged 切换；本例不反射全部键类型。
     */
    @Test
    void doesNotUseCompositeBalanceKeys() {
        // 当前被测原运行时，冷对象或按用例启动实际账户工作资源
        TradingAccountRuntime state = new TradingAccountRuntime();
        state.putUser(new UserRuntime(7));
        state.putBalance(new BalanceRuntime(7, 3, 1_000, 0));
        state.putBalance(new BalanceRuntime(7, 4, 2_000, 0));

        assertThat(state.snapshotProjectionStateDirty()).isTrue();
        state.clearChangedKeys();
        assertThat(state.publishedAvailableBalance(7, 3)).isEqualTo(1_000);
        assertThat(state.publishedAvailableBalance(7, 4)).isEqualTo(2_000);
        assertThat(state.snapshotProjectionStateDirty()).isFalse();
        state.markBalancesChanged();
        assertThat(state.snapshotProjectionStateDirty()).isTrue();
        state.clearChangedKeys();
        assertThat(state.snapshotProjectionStateDirty()).isFalse();
        state.close();
    }

    /**
     * 在一次原冻结后捕获指定修订的数字快照，检查可用八百、冻结二百、订单存在且快照订单表拒绝修改。
     */
    @Test
    void capturesDeterministicImmutableSnapshot() {
        // 当前被测原运行时，冷对象或按用例启动实际账户工作资源
        TradingAccountRuntime state = new TradingAccountRuntime();
        state.putUser(new UserRuntime(7));
        state.putBalance(new BalanceRuntime(7, 3, 1_000, 0));
        CoreStateTestFixtures.reserveOrder(state, 11, 7, 91, 5, 2, 3, 200);

        // 本次捕获的独立只读运行时数字快照
        TradingRuntimeSnapshot snapshot = RuntimeSnapshotBuilder.capture(state, 4);

        assertThat(snapshot.revision()).isEqualTo(4);
        assertThat(snapshot.totalAvailableUnits()).isEqualTo(800);
        assertThat(snapshot.totalLockedUnits()).isEqualTo(200);
        assertThat(snapshot.orders()).containsKey(11L);
        assertThatThrownBy(() -> snapshot.orders().put(12L,
                snapshot.orders().get(11L)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    /**
     * 快照包含用户持仓键及数量、费用七和保险十一，持仓映射不能被外部 clear。
     */
    @Test
    void snapshotIncludesPositionsAndTreasury() {
        // 当前被测原运行时，冷对象或按用例启动实际账户工作资源
        TradingAccountRuntime state = new TradingAccountRuntime();
        state.putUser(new UserRuntime(7));
        state.putBalance(new BalanceRuntime(7, 3, 1_000, 0));
        state.putPosition(9, new PositionRuntime(7, 5, 3,
                com.surprising.aeron.protocol.CoreMarginMode.CROSS,
                com.surprising.aeron.protocol.CorePositionSide.NET,
                CoreStateTestFixtures.runtimeInstrument(), 2, 100, 200, 0, 40));
        state.treasury().setFee(3, 7);
        state.treasury().setInsurance(3, 11, 0);

        // 本次捕获的独立只读运行时数字快照
        TradingRuntimeSnapshot snapshot = RuntimeSnapshotBuilder.capture(state, 5);

        assertThat(snapshot.positions()).containsKey(new TradingRuntimeSnapshot.PositionKey(7, 9));
        assertThat(snapshot.positions().get(new TradingRuntimeSnapshot.PositionKey(7, 9)).signedQuantitySteps())
                .isEqualTo(2);
        assertThat(snapshot.treasury().get(3).feeUnits()).isEqualTo(7);
        assertThat(snapshot.treasury().get(3).insuranceUnits()).isEqualTo(11);
        assertThatThrownBy(() -> snapshot.positions().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    /**
     * 按用户、内部标的及持仓方向找到计划强平，原任务替为取消后同范围不再视作活跃。
     */
    @Test
    void indexesActiveLiquidationByExactPositionScope() {
        // 当前被测原运行时，冷对象或按用例启动实际账户工作资源
        TradingAccountRuntime state = new TradingAccountRuntime();
        // 当前原 PLANNED 强平记录，按用户标的方向可定位
        LiquidationRuntime planned = new LiquidationRuntime(1, 7, 5,
                com.surprising.aeron.protocol.CoreMarginMode.CROSS,
                com.surprising.aeron.protocol.CorePositionSide.NET,
                CoreStateTestFixtures.runtimeInstrument(), 9, 2, 2, 0, 0, 0, 0,
                CoreLiquidationState.Status.PLANNED, 0);
        state.putLiquidation(planned);

        assertThat(state.activeLiquidation(7, 5,
                com.surprising.aeron.protocol.CorePositionSide.NET)).isSameAs(planned);

        // 同身份原强平被替换为取消的状态
        LiquidationRuntime canceled = new LiquidationRuntime(1, 7, 5,
                com.surprising.aeron.protocol.CoreMarginMode.CROSS,
                com.surprising.aeron.protocol.CorePositionSide.NET,
                CoreStateTestFixtures.runtimeInstrument(), 9, 2, 2, 0, 0, 0, 0,
                CoreLiquidationState.Status.CANCELED, 0);
        state.replaceLiquidation(canceled);

        assertThat(state.activeLiquidation(7, 5,
                com.surprising.aeron.protocol.CorePositionSide.NET)).isNull();
    }

    /**
     * 使用真实账户工作推进原命令及读取围栏，检查所属 Lane applied/committed 一；只有显式取得 Owner 读取权后才读原余额，最后归还。
     */
    @Test
    void routesStateAndReadFencesThroughThePermanentLaneOwner() {
        // 当前被测原运行时，冷对象或按用例启动实际账户工作资源
        TradingAccountRuntime state = new TradingAccountRuntime(LaneTopology.characterization());
        state.putUser(new UserRuntime(7));
        state.putBalance(new BalanceRuntime(7, 3, 1_000, 0));
        state.startAccountLanes();
        try {
            // 本次原 Lane 阶段调用返回的实际参与位图
            var apply = state.stageLaneMutation(1, java.util.List.of(7L));
            assertThat(apply).isEqualTo(1L << LaneTopology.characterization().accountLaneId(7));
            state.readFence(7, 1);

            // 所属 Lane 围栏内复制出的独立数字对照，含 applied/committed 水位
            LaneValues lane = laneValues(state, state.topology().accountLaneId(7));
            assertThat(lane.appliedSequence()).isEqualTo(1);
            assertThat(lane.committedSequence()).isEqualTo(1);
            state.acquireOwnerLaneAccess();
            try {
                assertThat(state.balance(7, 3).availableUnits()).isEqualTo(1_000);
            } finally {
                state.releaseOwnerLaneAccess();
            }
        } finally {
            state.close();
        }
    }

    /**
     * 在最后 Lane 用户更新后调用全 Lane 围栏，检查零号 Lane 也推进到原序号一；此例没有额外阻塞某 Lane 来观测中间时序。
     */
    @Test
    void crossLaneReadFenceDoesNotPartiallyAdvanceBeforeEveryLaneIsReady() {
        // 当前夹具固定 Lane 数、容量与用户路由拓扑
        LaneTopology topology = LaneTopology.productionDefault();
        // 当前被测原运行时，冷对象或按用例启动实际账户工作资源
        TradingAccountRuntime state = new TradingAccountRuntime(topology);
        // 按拓扑路由到最后 Lane 的原用户
        long userInLastLane = userForLane(topology, topology.accountLaneCount() - 1);
        state.putUser(new UserRuntime(userInLastLane));
        state.startAccountLanes();
        try {
            state.stageLaneMutation(1, java.util.List.of(userInLastLane));
            state.readFenceAll(1);
            assertThat(laneValues(state, 0).committedSequence()).isEqualTo(1);
        } finally {
            state.close();
        }
    }

    /**
     * 两真实 Lane 的原阶段调用完成后，applied 和 committed 均为一，再检查用户读取围栏可满足；不在此单独计数实际任务次数。
     */
    @Test
    void laneApplyPublishesCommittedWatermarkInTheSameOwnerTask() {
        // 当前夹具固定 Lane 数、容量与用户路由拓扑
        LaneTopology topology = LaneTopology.productionDefault();
        // 当前被测原运行时，冷对象或按用例启动实际账户工作资源
        TradingAccountRuntime state = new TradingAccountRuntime(topology);
        // 固定路由到零号 Lane 的原用户
        long laneZeroUser = userForLane(topology, 0);
        // 固定路由到一号 Lane 的原用户
        long laneOneUser = userForLane(topology, 1);
        state.putUser(new UserRuntime(laneZeroUser));
        state.putUser(new UserRuntime(laneOneUser));
        state.startAccountLanes();
        try {
            state.stageLaneMutation(1, java.util.List.of(laneZeroUser, laneOneUser));

            assertThat(laneValues(state, 0).appliedSequence()).isEqualTo(1);
            assertThat(laneValues(state, 1).appliedSequence()).isEqualTo(1);
            assertThat(laneValues(state, 0).committedSequence()).isEqualTo(1);
            assertThat(laneValues(state, 1).committedSequence()).isEqualTo(1);
            state.readFence(laneZeroUser, 1);
        } finally {
            state.close();
        }
    }

    /**
     * 为当前配置每 Lane 选用户，冷推进后检查完成位图覆盖全部及两水位一；无用户的下一次返回零位图，期望掩码显式处理六十四 Lane。
     */
    @Test
    void laneCompletionMaskCoversConfiguredLanesAndTheEmptyApply() {
        // 当前夹具固定 Lane 数、容量与用户路由拓扑
        LaneTopology topology = LaneTopology.productionDefault();
        // 当前被测原运行时，冷对象或按用例启动实际账户工作资源
        TradingAccountRuntime state = new TradingAccountRuntime(topology);
        // 按每 Lane 选出的原用户数组或列表
        java.util.List<Long> users = new java.util.ArrayList<>();
        for (/* 当前要派发、反射或读取的原账户 Lane 下标 */ int laneId = 0; laneId < topology.accountLaneCount(); laneId++) {
            // 本次内部用户 ID，部分用例按固定 Lane 路由寻找
            long userId = userForLane(topology, laneId);
            users.add(userId);
            state.putUser(new UserRuntime(userId));
        }
        state.clearChangedKeys();
        // 本轮原阶段返回的实际 Lane 完成位图
        long committedLaneMask = state.stageLaneMutation(1, users);

        assertThat(committedLaneMask).isEqualTo(topology.accountLaneCount() == 64 ? -1L : (1L << topology.accountLaneCount()) - 1);
        for (/* 当前要派发、反射或读取的原账户 Lane 下标 */ int laneId = 0; laneId < topology.accountLaneCount(); laneId++) {
            assertThat(laneValues(state, laneId).appliedSequence()).isEqualTo(1);
            assertThat(laneValues(state, laneId).committedSequence()).isEqualTo(1);
        }
        state.clearChangedKeys();
        assertThat(state.stageLaneMutation(2, java.util.List.of())).isZero();
    }

    /**
     * 存入逐仓净仓原持仓并清变化，再删除；两次读取本命令 typed before 都是同一原 open 对象，不丢原保证金模式。
     */
    @Test
    void sameCommandPositionRemovalRetainsTypedOpenBeforeAndMarginMode() {
        // 当前被测原运行时，冷对象或按用例启动实际账户工作资源
        TradingAccountRuntime state = new TradingAccountRuntime();
        // 本用例原资产、标的、客户端和持仓身份字典
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        // 由原身份表分配的内部稠密标的编号
        int symbolId = identities.symbolId("1");
        // 当前内部资产编号，注册身份场景由字典分配
        int assetId = identities.assetId("USDT");
        // 用户七原净仓确定性身份键
        long positionKey = identities.positionKey(7, "1:NET");
        // 删除前的逐仓净仓原持仓对象，作为 typed before 保留
        PositionRuntime open = new PositionRuntime(7, symbolId, assetId,
                com.surprising.aeron.protocol.CoreMarginMode.ISOLATED,
                com.surprising.aeron.protocol.CorePositionSide.NET,
                CoreStateTestFixtures.runtimeInstrument(), 2, 100, 200, 0, 40);
        state.putPosition(positionKey, open);
        state.clearChangedKeys();

        state.removePosition(positionKey, 7);

        assertThat(state.currentPatchPositionBefore(positionKey)).isSameAs(open);
        assertThat(state.currentPatchPositionBefore(positionKey)).isSameAs(open);
    }

    /**
     * 各 Lane 已应用不同原序号后，组合重放较早序号必须整批拒绝；检查所有 Lane 修订/水位/哈希及全局修订/变化不动，随后控制任务仍可使用回收资源。
     */
    @Test
    void stagedMutationRejectsAnOlderGlobalSequenceWithoutChangingAnyLane() {
        // 当前夹具固定 Lane 数、容量与用户路由拓扑
        LaneTopology topology = LaneTopology.productionDefault();
        // 当前被测原运行时，冷对象或按用例启动实际账户工作资源
        TradingAccountRuntime state = new TradingAccountRuntime(topology);
        // 固定路由到零号 Lane 的原用户
        long laneZeroUser = userForLane(topology, 0);
        // 固定路由到一号 Lane 的原用户
        long laneOneUser = userForLane(topology, 1);
        state.putUser(new UserRuntime(laneZeroUser));
        state.putUser(new UserRuntime(laneOneUser));
        state.startAccountLanes();
        try {
            state.stageLaneMutation(2, java.util.List.of(laneZeroUser));
            state.clearChangedKeys();
            state.stageLaneMutation(1, java.util.List.of(laneOneUser));
            state.clearChangedKeys();
            // 较早原序号组合应用失败前各 Lane 数字对照
            LaneValues[] beforeFailure = laneValues(state);
            // 较早原序号失败前全局修订
            long revisionBeforeFailure = state.revision();

            assertThatThrownBy(() -> state.stageLaneMutation(
                    1, java.util.List.of(laneZeroUser, laneOneUser)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("out of order");
            // 原无序应用拒绝后再读的 Lane 数字对照，恢复用例中为零号 Lane 对照
            LaneValues[] afterFailure = laneValues(state);
            for (/* 当前要派发、反射或读取的原账户 Lane 下标 */ int laneId = 0; laneId < topology.accountLaneCount(); laneId++) {
                assertThat(afterFailure[laneId].revision()).isEqualTo(beforeFailure[laneId].revision());
                assertThat(afterFailure[laneId].appliedSequence())
                        .isEqualTo(beforeFailure[laneId].appliedSequence());
                assertThat(afterFailure[laneId].committedSequence())
                        .isEqualTo(beforeFailure[laneId].committedSequence());
                assertThat(afterFailure[laneId].localStateHash())
                        .isEqualTo(beforeFailure[laneId].localStateHash());
                assertThat(afterFailure[laneId].localFundsHash())
                        .isEqualTo(beforeFailure[laneId].localFundsHash());
            }
            assertThat(state.revision()).isEqualTo(revisionBeforeFailure);
            assertThat(state.changedUsers().isEmpty()).isTrue();
            state.dispatchControlLanes(topology.accountLaneMask(laneOneUser), /* 当前 Lane 遍历下标或实际控制回调提供的所属 Lane 下标 */ id -> "apply-reclaimed");
            awaitControlLanes(state);
            assertThat(state.controlLaneResult(topology.accountLaneId(laneOneUser))).isEqualTo("apply-reclaimed");
        } finally {
            state.close();
        }
    }

    /**
     * 真实 Lane 连续推进一、二两个原序号，检查最终 applied/committed 同为二；不需要外部再提交一条显式 commit 命令。
     */
    @Test
    void accountLanePublishesEachConsecutiveSequenceWithoutASecondCommitTask() {
        // 当前夹具固定 Lane 数、容量与用户路由拓扑
        LaneTopology topology = LaneTopology.productionDefault();
        // 当前被测原运行时，冷对象或按用例启动实际账户工作资源
        TradingAccountRuntime state = new TradingAccountRuntime(topology);
        // 本次内部用户 ID，部分用例按固定 Lane 路由寻找
        long userId = userForLane(topology, 0);
        state.putUser(new UserRuntime(userId));
        state.startAccountLanes();
        try {
            state.stageLaneMutation(1, java.util.List.of(userId));
            state.stageLaneMutation(2, java.util.List.of(userId));

            // 连续原序号一、二执行后的零号 Lane 数字对照
            LaneValues applied = laneValues(state, 0);
            assertThat(applied.appliedSequence()).isEqualTo(2);
            assertThat(applied.committedSequence()).isEqualTo(2);
        } finally {
            state.close();
        }
    }

    /**
     * 先派发二百五十六个独立序号事件，再逐个等待与归还，检查各事件实际完成位及最终 Lane 水位二百五十六；等待自旋未另设期限，不是吞吐测试。
     */
    @Test
    void keepsTwoHundredFiftySixSequenceLocalLaneCommitsInFlight() {
        // 当前夹具固定 Lane 数、容量与用户路由拓扑
        LaneTopology topology = LaneTopology.productionDefault();
        // 当前被测原运行时，冷对象或按用例启动实际账户工作资源
        TradingAccountRuntime state = new TradingAccountRuntime(topology);
        // 按每 Lane 选出的原用户数组或列表
        long[] users = new long[topology.accountLaneCount()];
        for (/* 当前要派发、反射或读取的原账户 Lane 下标 */ int laneId = 0; laneId < users.length; laneId++) {
            users[laneId] = userForLane(topology, laneId);
            state.putUser(new UserRuntime(users[laneId]));
        }
        state.startAccountLanes();
        try {
            // 先派发、后逐个收集的二百五十六个原 Lane 提交事件
            LaneCommitEvent[] commits = new LaneCommitEvent[256];
            for (/* 当前数组或订单循环下标 */ int index = 0; index < commits.length; index++) {
                commits[index] = state.dispatchLaneMutation(index + 1L, users);
            }
            for (LaneCommitEvent /* 当前待完成、核对实际掩码并归还的原提交事件 */ commit : commits) {
                while (!state.laneCommitComplete(commit)) Thread.onSpinWait();
                assertThat(commit.completedLaneMask()).isEqualTo(commit.requiredLaneMask());
                state.releaseLaneCommit(commit);
            }
            for (/* 当前要派发、反射或读取的原账户 Lane 下标 */ int laneId = 0; laneId < users.length; laneId++) {
                assertThat(laneValues(state, laneId).committedSequence()).isEqualTo(256);
            }
        } finally {
            state.close();
        }
    }

    /**
     * 普通或批撤由真实账户 Lane 执行，收回并归还事件后显式取得 Owner 读取权检查原资金全释放、订单/预留消失，再归还所有权。
     * @param batch 是否按原批次回执登记/撤销预留，撤单用例中选择批撤
     */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void cancellationReturnsBorrowedOwnershipAndRunsOnTheLane(boolean batch) throws Exception {
        // 当前被测原运行时，冷对象或按用例启动实际账户工作资源
        TradingAccountRuntime state = new TradingAccountRuntime(LaneTopology.productionDefault());
        // 本用例原资产、标的、客户端和持仓身份字典
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        identities.bindClientLanes(state);
        // 当前创建或核对的原订单业务号
        // 本次内部用户 ID，部分用例按固定 Lane 路由寻找
        long userId = 1, orderId = 10_000;
        state.putUser(new UserRuntime(userId));
        state.putBalance(new BalanceRuntime(userId, 3, 2, 0));
        CoreStateTestFixtures.reserveOrder(state, orderId, userId, identities.clientKey(userId, "cancel"), 5, 1, 3, 1);
        state.clearChangedKeys();
        state.startAccountLanes();
        try {
            // 当前原普通或批次撤单事件，完成后收回并归还
            LaneCancelEvent event = batch
                    ? state.dispatchCancelBatch(1, userId, new long[]{orderId}, 1, 1, identities)
                    : state.dispatchCancel(1, userId, orderId, 1, 1, identities);
            while (!event.complete()) Thread.onSpinWait();
            assertThat(event.complete()).isTrue();
            state.collectCancel(event, null, null);
            state.releaseCancel(event);
            state.acquireOwnerLaneAccess();
            try {
                assertThat(state.balance(userId, 3).availableUnits()).isEqualTo(2);
                assertThat(state.balance(userId, 3).lockedUnits()).isZero();
                assertThat(state.order(orderId)).isNull();
                assertThat(state.reservation(orderId)).isNull();
            } finally {
                state.releaseOwnerLaneAccess();
            }
        } finally {
            state.close();
        }
    }

    /**
     * 先创建二百五十六用户原冻结单，再先派完全部撤单、后逐一收集归还；取得读取权后检查余额恢复、正文清除及客户端身份表为空，不逐条同步派发等待。
     */
    @Test
    void keepsTwoHundredFiftySixLaneOwnedCancellationsInFlightWithoutOwnerWaits() {
        // 当前夹具固定 Lane 数、容量与用户路由拓扑
        LaneTopology topology = LaneTopology.productionDefault();
        // 当前被测原运行时，冷对象或按用例启动实际账户工作资源
        TradingAccountRuntime state = new TradingAccountRuntime(topology);
        // 本用例原资产、标的、客户端和持仓身份字典
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        identities.bindClientLanes(state);
        // 先派发后收集的二百五十六个原 Lane 撤单事件
        LaneCancelEvent[] cancellations = new LaneCancelEvent[256];
        for (/* 当前数组或订单循环下标 */ int index = 0; index < cancellations.length; index++) {
            // 本次内部用户 ID，部分用例按固定 Lane 路由寻找
            long userId = index + 1L;
            // 当前创建或核对的原订单业务号
            long orderId = 10_000L + index;
            // 当前用户原客户端单号对应的实际正键
            long clientKey = identities.clientKey(userId, "cancel-" + index);
            state.putUser(new UserRuntime(userId));
            state.putBalance(new BalanceRuntime(userId, 3, 2, 0));
            CoreStateTestFixtures.reserveOrder(state, orderId, userId, clientKey, 5, 1, 3, 1);
        }
        state.clearChangedKeys();
        state.startAccountLanes();
        try {
            for (/* 当前数组或订单循环下标 */ int index = 0; index < cancellations.length; index++) {
                cancellations[index] = state.dispatchCancel(
                        index + 1L, index + 1L, 10_000L + index, 1_000L + index, index + 1L,
                        identities);
            }
            for (/* 当前数组或订单循环下标 */ int index = 0; index < cancellations.length; index++) {
                // 当前原普通或批次撤单事件，完成后收回并归还
                LaneCancelEvent event = cancellations[index];
                while (!event.complete()) Thread.onSpinWait();
                state.collectCancel(event, null, null);
                state.releaseCancel(event);
            }
            state.acquireOwnerLaneAccess();
            try {
                for (/* 当前数组或订单循环下标 */ int index = 0; index < cancellations.length; index++) {
                    assertThat(state.balance(index + 1L, 3).availableUnits()).isEqualTo(2);
                    assertThat(state.balance(index + 1L, 3).lockedUnits()).isZero();
                    assertThat(state.order(10_000L + index)).isNull();
                    assertThat(state.reservation(10_000L + index)).isNull();
                }
            } finally {
                state.releaseOwnerLaneAccess();
            }
            assertThat(identities.snapshot().clientKeys()).isEmpty();
        } finally {
            state.close();
        }
    }

    /**
     * 故意把零号 Lane 用户放进一号快照，恢复整组之前必须检查路由拒绝，先前零号 Lane 的水位与本地状态哈希不被部分回退。
     */
    @Test
    void invalidSnapshotSetDoesNotPartiallyRestoreEarlierLanes() {
        // 当前夹具固定 Lane 数、容量与用户路由拓扑
        LaneTopology topology = LaneTopology.productionDefault();
        // 当前被测原运行时，冷对象或按用例启动实际账户工作资源
        TradingAccountRuntime state = new TradingAccountRuntime(topology);
        // 本用例原资产、标的、客户端和持仓身份字典
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        // 用于 Lane 快照围栏校验的原冷全局状态
        TradingCoreState global = RuntimeStateMaterializer.materialize(state, identities);
        // 同一原围栏捕获的全部账户 Lane 快照列表
        java.util.List<AccountLaneSnapshot> snapshots = state.accountLaneSnapshots(1, global);
        // 固定路由到零号 Lane 的原用户
        long laneZeroUser = userForLane(topology, 0);
        state.putUser(new UserRuntime(laneZeroUser));
        state.stageLaneMutation(2, java.util.List.of(laneZeroUser));
        // 坏快照组恢复前零号 Lane 原数字对照
        LaneValues beforeRestore = laneValues(state, 0);

        // 把另一 Lane 用户塞入一号快照的故障候选快照列表
        java.util.List<AccountLaneSnapshot> invalid = new java.util.ArrayList<>(snapshots);
        // 用于替换一号快照的原项，其余字段沿用，只改用户清单
        AccountLaneSnapshot corrupted = snapshots.get(1);
        invalid.set(1, new AccountLaneSnapshot(corrupted.laneId(), corrupted.revision(),
                corrupted.appliedSequence(), corrupted.committedSequence(),
                corrupted.localStateHash(), corrupted.localFundsHash(), java.util.List.of(laneZeroUser)));

        assertThatThrownBy(() -> state.restoreAccountLaneSnapshots(invalid, 1, global))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("incorrectly routed user");
        // 原无序应用拒绝后再读的 Lane 数字对照，恢复用例中为零号 Lane 对照
        LaneValues afterFailure = laneValues(state, 0);
        assertThat(afterFailure.appliedSequence()).isEqualTo(beforeRestore.appliedSequence());
        assertThat(afterFailure.committedSequence()).isEqualTo(beforeRestore.committedSequence());
        assertThat(afterFailure.localStateHash()).isEqualTo(beforeRestore.localStateHash());
    }

    /**
     * 冷状态可内联，但启动实际账户工作后生命周期、用户结算、风控等内联入口都拒绝且回调未执行，Owner 名称不变；真正派发控制回调在另一线程执行。
     */
    @Test
    void activeWorkersRejectInlineSettlementWithoutRunningOrRenamingOwner() {
        try (/* 当前被测原运行时，冷对象或按用例启动实际账户工作资源 */ var state = new TradingAccountRuntime()) {
            // 本测试当前 Owner 线程，用于检查没有改名或冒充工作线程
            Thread owner = Thread.currentThread();
            // 启动账户工作前原 Owner 线程名
            String originalName = owner.getName();
            assertThat(state.executeUserSettlement(8, Thread::currentThread)).isSameAs(owner);
            assertThat(owner.getName()).isEqualTo(originalName);
            state.startAccountLanes();
            assertThat(state.asynchronousCommands()).isTrue();
            assertThat(state.ownerLaneAccess()).isFalse();
            // 通知两 Lane 已执行的门闩，或检查被拒绝内联回调没有执行的原子计数
            var executed = new java.util.concurrent.atomic.AtomicInteger();
            assertThatThrownBy(() -> state.executeLifecycleSettlements(
                    java.util.List.of(203L, 8L), Long::longValue, /* 本不允许内联调用的生命周期回调参数，若执行会错误增加计数 */ laneId -> executed.incrementAndGet()))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("must be dispatched");
            assertThatThrownBy(() -> state.executeUserSettlement(8, executed::incrementAndGet))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("must be dispatched");
            assertThatThrownBy(() -> state.executeUserRisk(8, executed::incrementAndGet))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("must be dispatched");
            assertThatThrownBy(() -> state.executeRiskLane(0, executed::incrementAndGet))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("must be dispatched");
            assertThat(executed.get()).isZero();
            assertThat(owner.getName()).isEqualTo(originalName);
            state.dispatchControlLanes(state.topology().accountLaneMask(8), /* 当前 Lane 遍历下标或实际控制回调提供的所属 Lane 下标 */ id -> Thread.currentThread());
            awaitControlLanes(state);
            assertThat(state.controlLaneResult(state.topology().accountLaneId(8))).isNotSameAs(owner);
        }
    }

    /**
     * 给当前配置每 Lane 一用户，实际 Lane 增用户修订并核对余额；收齐后取得读取权检查回调线程就是固定 worker、不是 Owner。此夹具全掩码表达式依赖配置少于六十四 Lane。
     */
    @Test
    void lifecycleSettlementUsesPermanentIndependentLaneOwners() {
        // 当前夹具固定 Lane 数、容量与用户路由拓扑
        LaneTopology topology = LaneTopology.productionDefault();
        try (/* 当前被测原运行时，冷对象或按用例启动实际账户工作资源 */ TradingAccountRuntime state = new TradingAccountRuntime(topology)) {
            // 按每 Lane 选出的原用户数组或列表
            java.util.List<Long> users = new java.util.ArrayList<>();
            for (/* 当前要派发、反射或读取的原账户 Lane 下标 */ int laneId = 0; laneId < topology.accountLaneCount(); laneId++) {
                // 本次内部用户 ID，部分用例按固定 Lane 路由寻找
                long userId = userForLane(topology, laneId);
                users.add(userId);
                state.putUser(new UserRuntime(userId));
                state.putBalance(new BalanceRuntime(userId, 3, 1_000 + laneId, 0));
            }
            state.startAccountLanes();
            state.dispatchControlLanes((1L << topology.accountLaneCount()) - 1, /* 当前要派发、反射或读取的原账户 Lane 下标 */ laneId -> {
                state.advanceUserRevision(users.get(laneId));
                assertThat(state.balance(users.get(laneId), 3).availableUnits()).isEqualTo(1_000 + laneId);
                return Thread.currentThread();
            });
            awaitControlLanes(state);
            state.acquireOwnerLaneAccess();
            try {
                for (/* 当前要派发、反射或读取的原账户 Lane 下标 */ int laneId = 0; laneId < topology.accountLaneCount(); laneId++) {
                    // 控制回调返回的实际执行线程，应该为固定 worker
                    Thread actual = (Thread) state.controlLaneResult(laneId);
                    assertThat(actual).isSameAs(state.accountWorkerThread(laneId)).isNotSameAs(Thread.currentThread());
                    assertThat(state.user(users.get(laneId)).revision()).isEqualTo(1);
                }
            } finally {
                state.releaseOwnerLaneAccess();
            }
        }
    }

    /**
     * 最多五秒显式收集原控制 Lane 完成，超时使测试失败。
     * @param state 本测试原账户运行时，按其所有权边界操作
     */
    private static void awaitControlLanes(TradingAccountRuntime state) {
        // 当前回执、回滚或控制收集阶段的五秒单调截止值
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (!state.pollControlLanes()) {
            if (System.nanoTime() >= deadline) throw new AssertionError("control Lane timeout");
            Thread.onSpinWait();
        }
    }

    /**
     * 真实 Lane 直接接管已准备订单/预留实例并冻结二百，重复原订单或客户端身份在再次冻结前拒绝；撤原单全释放，Owner 直接读活跃账户仍拒绝。
     */
    @Test
    void preparedReservationInstallsExactValuesAndRejectsBeforeFreezing() {
        try (/* 当前被测原运行时，冷对象或按用例启动实际账户工作资源 */ TradingAccountRuntime state = new TradingAccountRuntime()) {
            state.putUser(new UserRuntime(7));
            state.putBalance(new BalanceRuntime(7, 3, 1_000, 0));
            // 当前原准入或测试成员订单
            OrderRuntime order = CoreStateTestFixtures.order(11, 7, 5, 2);
            // 准备由所属 Lane 接管的两百单位衍生品保证金预留原实例
            ReservationRuntime reservation = new ReservationRuntime(11, 7, 5,
                    com.surprising.aeron.protocol.ReservationKind.DERIVATIVE_MARGIN, 3, 200, 0, 0, 2);
            state.startAccountLanes();
            assertThatThrownBy(() -> state.order(11)).isInstanceOf(IllegalStateException.class);
            state.dispatchControlLanes(state.topology().accountLaneMask(7), /* 当前 Lane 遍历下标或实际控制回调提供的所属 Lane 下标 */ id -> {
                state.reserveOrder(order, reservation, 91);
                assertThat(state.order(11)).isSameAs(order);
                assertThat(state.reservation(11)).isSameAs(reservation);
                assertThat(state.balance(7, 3).availableUnits()).isEqualTo(800);
                assertThat(state.balance(7, 3).lockedUnits()).isEqualTo(200);
                assertThatThrownBy(() -> state.reserveOrder(order, reservation, 92))
                        .isInstanceOf(IllegalArgumentException.class);
                // 下一次复用原提交事件，预留用例中为候选第二单
                var next = CoreStateTestFixtures.order(12, 7, 5, 2);
                // 重复客户端身份时准备的第二单原预留，不能被半冻结安装
                var nextReservation = new ReservationRuntime(12, 7, 5,
                        com.surprising.aeron.protocol.ReservationKind.DERIVATIVE_MARGIN, 3, 200, 0, 0, 2);
                assertThatThrownBy(() -> state.reserveOrder(next, nextReservation, 91))
                        .isInstanceOf(IllegalArgumentException.class);
                assertThat(state.order(12)).isNull();
                assertThat(state.balance(7, 3).lockedUnits()).isEqualTo(200);
                state.cancelOrder(11, 7, 200);
                assertThat(state.balance(7, 3).availableUnits()).isEqualTo(1_000);
                assertThat(state.balance(7, 3).lockedUnits()).isZero();
                return null;
            });
            awaitControlLanes(state);
        }
    }

    /**
     * 真实各 Lane 更新一次用户，直接编码原 Lane 指标再与围栏读到的修订、水位对照；不重新扫描业务表构造指标。此夹具全掩码表达式按少于六十四 Lane 使用。
     */
    @Test
    void directMetricsReadMatchesLaneCountersWithIndependentOwners() {
        // 当前夹具固定 Lane 数、容量与用户路由拓扑
        LaneTopology topology = LaneTopology.productionDefault();
        try (/* 当前被测原运行时，冷对象或按用例启动实际账户工作资源 */ TradingAccountRuntime state = new TradingAccountRuntime(topology)) {
            for (/* 当前用户所属 Lane 下标或回调提供的实际账户 Lane */ int lane = 0; lane < topology.accountLaneCount(); lane++) {
                state.putUser(new UserRuntime(userForLane(topology, lane)));
            }
            state.startAccountLanes();
            state.dispatchControlLanes((1L << topology.accountLaneCount()) - 1, /* 当前用户所属 Lane 下标或回调提供的实际账户 Lane */ lane -> {
                state.advanceUserRevision(userForLane(topology, lane));
                return null;
            });
            awaitControlLanes(state);
            // 当前原 Lane 指标协议编码器
            var encoder = new com.surprising.aeron.protocol.CoreLaneMetricsCodec.Encoder(
                    1, topology.accountLaneCount(), 0, 16, 0, 0, 16, 0, 0, 16, 0, 0);
            for (/* 当前用户所属 Lane 下标或回调提供的实际账户 Lane */ int lane = 0; lane < topology.accountLaneCount(); lane++) state.writeAccountLaneMetrics(lane, encoder);
            // 原指标编码后解出的独立协议视图
            var decoded = com.surprising.aeron.protocol.CoreLaneMetricsCodec.decode(encoder.finish());
            for (/* 当前用户所属 Lane 下标或回调提供的实际账户 Lane */ int lane = 0; lane < topology.accountLaneCount(); lane++) {
                // 在明确围栏内读取的同 Lane 数字对照，用来核对指标
                var view = laneValues(state, lane);
                assertThat(decoded.accountLaneRevisions()[lane]).isEqualTo(view.revision());
                assertThat(decoded.accountLaneAppliedSequences()[lane]).isEqualTo(view.appliedSequence());
                assertThat(decoded.accountLaneCommittedSequences()[lane]).isEqualTo(view.committedSequence());
            }
        }
    }

    /**
     * 独立冷 Lane 一百个活单增长后，部分成交更新仍复用同一用户成员集合；全部终态后该集合仍保留同对象但大小为零，区别于 Owner 路由的空桶回收。
     */
    @Test
    void activeOrderMembershipSurvivesUpdatesGrowthAndTerminalRemoval() {
        // 当前用户所属 Lane 下标或回调提供的实际账户 Lane
        AccountLaneState lane = new AccountLaneState(0, 16);
        for (/* 当前 Lane 遍历下标或实际控制回调提供的所属 Lane 下标 */ long id = 1; id <= 100; id++) lane.putOrder(CoreStateTestFixtures.order(id, 7, 5, 2));
        // 账户 Lane 用户七原活单成员集合，更新及变空仍保留同一对象
        var membership = AccountLaneTestAccess.activeOrderIdsByUser(lane).get(7);
        for (/* 当前 Lane 遍历下标或实际控制回调提供的所属 Lane 下标 */ long id = 1; id <= 100; id++) {
            // 当前原准入或测试成员订单
            OrderRuntime order = lane.order(id);
            lane.putOrder(order.withExecution(1, 1, CoreOrderStatus.OPEN, 2));
            assertThat(AccountLaneTestAccess.activeOrderIdsByUser(lane).get(7)).isSameAs(membership);
        }
        assertThat(membership.size()).isEqualTo(100);
        for (/* 当前 Lane 遍历下标或实际控制回调提供的所属 Lane 下标 */ long id = 1; id <= 100; id++) {
            lane.putOrder(lane.order(id).withStatus(CoreOrderStatus.CANCELED, 3));
        }
        assertThat(AccountLaneTestAccess.activeOrderIdsByUser(lane).get(7)).isSameAs(membership);
        assertThat(membership.size()).isZero();
    }

    /**
     * 按既有批次回执登记两份 pending，检查同用户两原订单命中，未知订单及其他用户不命中；本例不测额外容器分配。
     */
    @Test
    void pendingReservationBatchLookupUsesTheExistingBatchReceipt() {
        try (/* 当前被测原运行时，冷对象或按用例启动实际账户工作资源 */ var state = new TradingAccountRuntime()) {
            state.putUser(new UserRuntime(7));
            state.putBalance(new BalanceRuntime(7, 3, 1_000, 0));
            CoreStateTestFixtures.reserveOrder(state, 11, 7, 91, 5, 2, 3, 200);
            CoreStateTestFixtures.reserveOrder(state, 12, 7, 92, 5, 2, 3, 200);
            state.onLane(7L, /* 当前用户所属 Lane 下标或回调提供的实际账户 Lane */ lane -> {
                lane.markPendingReservation(11, 4);
                lane.markPendingReservation(12, 4);
                return null;
            });
            state.pendingReservations.registerBatch(4, 7,
                    new OrderRuntime[]{state.order(11), state.order(12)}, 2);

            assertThat(state.pendingReservations.pendingReservation(11, 7)).isTrue();
            assertThat(state.pendingReservations.pendingReservation(12, 7)).isTrue();
            assertThat(state.pendingReservations.pendingReservation(13, 7)).isFalse();
            assertThat(state.pendingReservations.pendingReservation(11, 8)).isFalse();
        }
    }

    /**
     * 在一到九千九百九十九间找固定路由到指定 Lane 的用户，没有则明确失败。
     * @param topology 本例固定账户路由拓扑
     * @param laneId 要定位、读取或选用户的原账户 Lane 下标
     */
    private static long userForLane(LaneTopology topology, int laneId) {
        for (/* 本次内部用户 ID，部分用例按固定 Lane 路由寻找 */ long userId = 1; userId < 10_000; userId++) {
            if (topology.accountLaneId(userId) == laneId) return userId;
        }
        throw new IllegalStateException("unable to find user for Account Lane");
    }

    /**
     * 按 Maven 测试类输出位置向上定位模块，再读原 AccountLaneState 源文件，依赖本仓库测试目录布局。
     */
    private static String accountLaneSource() throws Exception {
        // 本测试类实际输出目录，通常为 Maven target/test-classes
        Path testClasses = Path.of(TradingAccountRuntimeTest.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
        // 按原测试输出目录向上定位的 Maven 模块根
        Path module = testClasses.getParent().getParent();
        return Files.readString(module.resolve("src/main/java/com/surprising/aeron/service/account/AccountLaneState.java"));
    }

    /**
     * 只供测试反射取原 accountLanes 数组指定项，不授予生产访问权限。
     * @param state 本测试原账户运行时，按其所有权边界操作
     * @param laneId 要定位、读取或选用户的原账户 Lane 下标
     */
    private static AccountLaneState accountLaneState(TradingAccountRuntime state, int laneId) throws Exception {
        // 仅供测试取原内部状态的反射字段
        Field field = TradingAccountRuntime.class.getDeclaredField("accountLanes");
        field.setAccessible(true);
        return ((AccountLaneState[]) field.get(state))[laneId];
    }

    /**
     * 逐 Lane 读取本地修订、水位及哈希为独立数字对照数组。
     * @param state 本测试原账户运行时，按其所有权边界操作
     */
    private static LaneValues[] laneValues(TradingAccountRuntime state) {
        // 本次逐 Lane 复制的独立数字对照数组
        LaneValues[] values = new LaneValues[state.topology().accountLaneCount()];
        for (/* 当前要派发、反射或读取的原账户 Lane 下标 */ int laneId = 0; laneId < values.length; laneId++) values[laneId] = laneValues(state, laneId);
        return values;
    }

    /**
     * 实际工作已启动且 Owner 尚未持权时先取得原读取围栏，再读取本 Lane 数字，finally 只归还本方法取得的权限；取得权限自旋没有额外期限。
     * @param state 本测试原账户运行时，按其所有权边界操作
     * @param laneId 要定位、读取或选用户的原账户 Lane 下标
     */
    private static LaneValues laneValues(TradingAccountRuntime state, int laneId) {
        // 本 helper 是否临时取得了原 Owner 账户读取权，finally 只归还这一份
        boolean acquired = state.accountLanesStarted() && !state.ownerLaneAccess();
        try {
            if (acquired) while (!state.tryAcquireOwnerLaneAccess()) Thread.onSpinWait();
            return state.onLane(laneId, /* 当前用户所属 Lane 下标或回调提供的实际账户 Lane */ lane -> new LaneValues(lane.revision(), lane.appliedSequence(),
                    lane.committedSequence(), lane.localStateHash(), lane.localFundsHash()));
        } finally {
            if (acquired) state.releaseOwnerLaneAccess();
        }
    }

    /**
     * 本测试从原 Lane 围栏内复制的修订、应用/提交水位及本地哈希数字，不持有可变账户正文。
     * @param revision 围栏内复制的 Lane 本地修订
     * @param appliedSequence 围栏内复制的已应用原命令水位
     * @param committedSequence 围栏内复制的已提交原命令水位
     * @param localStateHash 围栏内复制的 Lane 本地状态哈希字段
     * @param localFundsHash 围栏内复制的 Lane 本地资金哈希字段
     */
    private record LaneValues(long revision, long appliedSequence, long committedSequence,
                              long localStateHash, long localFundsHash) {
    }

    /**
     * 通过原 pending tracker 取指定 Core 序号关联的订单 ID 列表，持有方式沿原接口契约。
     * @param state 本测试原账户运行时，按其所有权边界操作
     * @param coreSequence 需要核对 pending 订单清单的原 Core 序号
     */
    private static long[] pendingReservationOrderIds(TradingAccountRuntime state, long coreSequence) {
        return state.pendingReservations.pendingOrderIds(coreSequence);
    }

    /**
     * 只供测试反射读原 pendingReservationUsers 反向表，取得该订单原用户键。
     * @param state 本测试原账户运行时，按其所有权边界操作
     * @param orderId 要从原 pending 用户反向表读取的订单号
     */
    private static long pendingReservationOwner(TradingAccountRuntime state, long orderId) throws Exception {
        // 仅供测试取原内部状态的反射字段
        Field field = PendingReservationTracker.class.getDeclaredField("pendingReservationUsers");
        field.setAccessible(true);
        // 原 pending 订单到用户的 primitive 反向表，测试只读
        var owners = (org.agrona.collections.Long2LongHashMap) field.get(state.pendingReservations);
        return owners.get(orderId);
    }

    /**
     * 从原源码中按起止字面量截一段文本，任一标记不存在即失败；不解析 Java AST，范围也会包含其间注释。
     * @param source 待截取的原 Java 源码文本；保护表构造中为要复制的原订单表
     * @param startMarker 待检查方法片段的原起始字面量
     * @param endMarker 方法片段不包含的原后继字面量
     */
    private static String methodSource(String source, String startMarker, String endMarker) {
        // 原源码起始标记第一次出现的字符位置
        int start = source.indexOf(startMarker);
        // 原源码后继标记第一次出现的位置，截取不包含它
        int end = source.indexOf(endMarker, start);
        if (start < 0 || end < 0) throw new IllegalArgumentException("account lane lookup method is missing");
        return source.substring(start, end);
    }

    /**
     * 构造标的原价格序号一、风险未完而触发已完的冷扫描值，供公平调度测试指定用户游标。
     * @param symbolId 内部稠密标的编号
     * @param lastUserId 测试风险扫描已到的用户游标
     */
    private static RiskScanRuntime incompleteRiskScan(int symbolId, long lastUserId) {
        return new RiskScanRuntime(symbolId, 1, 1, lastUserId, false,
                0, 0, "-", 0, 0, 0, 0, 0,
                true, 0, 0, 0, 0, 0, 0, 0, 0);
    }
}
