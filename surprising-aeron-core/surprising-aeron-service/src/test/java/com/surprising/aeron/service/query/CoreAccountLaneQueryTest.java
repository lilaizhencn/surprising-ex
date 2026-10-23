package com.surprising.aeron.service.query;

import com.surprising.aeron.model.*;
import com.surprising.aeron.model.AssetBalance;
import com.surprising.aeron.model.CoreOrderState;
import com.surprising.aeron.model.CoreOrderStatus;
import com.surprising.aeron.model.CoreRiskState;
import com.surprising.aeron.model.CoreRiskStatus;
import com.surprising.aeron.model.TradingCoreState;
import com.surprising.aeron.protocol.*;
import com.surprising.aeron.service.account.AccountLaneState;
import com.surprising.aeron.service.account.CoreUserState;
import com.surprising.aeron.service.core.*;
import com.surprising.aeron.service.core.OwnerAccountReadGuard;
import com.surprising.aeron.service.core.PipelinedExecutionExtension;
import com.surprising.aeron.service.core.TradingCoreRuntime;
import com.surprising.aeron.service.core.TradingAccountRuntime;
import com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.aeron.service.order.CoreTestCompletion;
import com.surprising.aeron.service.position.PositionRuntime;
import com.surprising.aeron.service.query.state.RuntimeStateQueryService;
import com.surprising.aeron.service.retention.TerminalStateRetention;
import com.surprising.aeron.service.snapshot.state.RuntimeStateProjector;
import com.surprising.aeron.service.treasury.CoreTreasuryState;
import com.surprising.product.api.ProductLine;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * 账户查询的 Lane 集成测试：核对用户、订单、哈希、客户端编号及挂单页协议，禁止 Owner 直接读活动账户，并检查慢 Lane、查询超限、只读作用域和关闭清理。
 */
class CoreAccountLaneQueryTest {
    /** 本组账户查询测试独立使用的 U 本位永续产品线。 */
    private static final ProductLine LINE = ProductLine.LINEAR_PERPETUAL;
    /** 固定业务时间，供标记价和命令头共用，不使用墙上时间决定业务。 */
    private static final long TIME = 1_700_000_000_000L;
    /** 本测试实例的消息来源序号，业务命令和查询构造时共同递增。 */
    private long sequence;

    /**
     * 把用户详情及哈希、订单详情及哈希、客户端订单查询的五类响应与同一状态的冷视图逐一比较；Owner 读取账户被守卫禁止，查询前后账户交接计数不变。
     */
    @Test
    void fiveAccountQueriesReadAndEncodeOnLaneAndMatchColdViews() throws Exception {
        try (/* 本用例持有的核心运行时，通过其正常命令或异步查询读取结果，结束时关闭。 */ var core = prepared()) {
            // 冷投影独立使用的身份映射，供用户及订单视图的参照查询使用。
            var identities = new RuntimeIdentityRegistry();
            try (/* 由当前已提交交易状态投影的冷运行时，在合法 Lane 参数下构造预期视图和哈希。 */ var expected = RuntimeStateProjector.project(core.tradingState(), identities)) {
                // 冷运行时中用户 1 的完整查询结果，提供详情编码和状态哈希的参照。
                var user = RuntimeStateQueryService.userState(expected, expected.accountLaneForUser(1), identities, 1);
                // 冷运行时中用户 7 的订单 200 查询结果，详情、哈希和客户端查询均应与它一致。
                var order = RuntimeStateQueryService.orderState(expected,
                        expected.accountLaneForUser(7), identities, 200);
                // 查询开始前账户所有权交接计数，查询完成后必须不变。
                long handoff = core.runtimeState().laneHandoffEpoch();
                try (/* 阻止 Owner 越界读取活动账户的测试守卫，查询数据应从所属 Lane 返回。 */ var guard = new OwnerAccountReadGuard(core.runtimeState())) {
                    assertThat(query(core, CoreMessageType.USER_STATE_QUERY, 1, new byte[0]).data())
                            .isEqualTo(CoreStateQueryCodec.encodeUserState(user.view()));
                    assertThat(CoreStateQueryCodec.decodeStateHash(query(core, CoreMessageType.USER_STATE_HASH_QUERY,
                            1, new byte[0]).data())).isEqualTo(user.stateHash());
                    assertThat(query(core, CoreMessageType.ORDER_STATE_QUERY, 0,
                            TradingCommandCodec.encodeOrderStateQuery(200)).data())
                            .isEqualTo(CoreStateQueryCodec.encodeOrderState(order.view()));
                    assertThat(CoreStateQueryCodec.decodeStateHash(query(core, CoreMessageType.ORDER_STATE_HASH_QUERY,
                            0, TradingCommandCodec.encodeOrderStateQuery(200)).data())).isEqualTo(order.stateHash());
                    assertThat(query(core, CoreMessageType.CLIENT_ORDER_STATE_QUERY, 7,
                            CoreStateQueryCodec.encodeClientOrderStateQuery("account-q-200")).data())
                            .isEqualTo(CoreStateQueryCodec.encodeOrderState(order.view()));
                }
                assertThat(core.runtimeState().laneHandoffEpoch()).isEqualTo(handoff);
                core.assertClusterCallbackComplete();
            }
        }
    }

    /**
     * 核对跨用户挂单按订单编号降序返回，游标取上一编号之前的订单；用户筛选和空页也保持原协议，查询不触发账户所有权交接。
     */
    @Test
    void openOrderPagesAreEncodedByTheirLanesAndKeepGlobalCursorOrder() throws Exception {
        try (/* 本用例持有的核心运行时，通过其正常命令或异步查询读取结果，结束时关闭。 */ var core = prepared(); /* 阻止 Owner 越界读取活动账户的测试守卫，查询数据应从所属 Lane 返回。 */ var guard = new OwnerAccountReadGuard(core.runtimeState())) {
            // 查询开始前账户所有权交接计数，查询完成后必须不变。
            long handoff = core.runtimeState().laneHandoffEpoch();
            // 不限定用户的币对 1 挂单页，应按订单编号降序包含 202 和 200。
            var all = query(core, CoreMessageType.USER_OPEN_ORDERS_QUERY, 0,
                    CoreStateQueryCodec.encodeOpenOrdersQuery(new CoreOpenOrdersQuery("1", 0, 10)));
            assertThat(CoreStateQueryCodec.decodeOpenOrders(all.data()).orders())
                    .extracting(CoreOrderStateView::orderId).containsExactly(202L, 200L);
            // 以上一条编号 202 为排他游标的一项页，应只返回订单 200。
            var cursor = query(core, CoreMessageType.USER_OPEN_ORDERS_QUERY, 0,
                    CoreStateQueryCodec.encodeOpenOrdersQuery(new CoreOpenOrdersQuery("1", 202, 1)));
            assertThat(CoreStateQueryCodec.decodeOpenOrders(cursor.data()).orders())
                    .extracting(CoreOrderStateView::orderId).containsExactly(200L);
            // 只查询用户 1 的挂单页，应仅包含订单 202。
            var user = query(core, CoreMessageType.USER_OPEN_ORDERS_QUERY, 1,
                    CoreStateQueryCodec.encodeOpenOrdersQuery(new CoreOpenOrdersQuery("1", 0, 10)));
            assertThat(CoreStateQueryCodec.decodeOpenOrders(user.data()).orders())
                    .extracting(CoreOrderStateView::orderId).containsExactly(202L);
            // 查询没有订单的用户 99 的挂单响应，正常返回空列表。
            var empty = query(core, CoreMessageType.USER_OPEN_ORDERS_QUERY, 99,
                    CoreStateQueryCodec.encodeOpenOrdersQuery(new CoreOpenOrdersQuery("1", 0, 10)));
            assertThat(CoreStateQueryCodec.decodeOpenOrders(empty.data()).orders()).isEmpty();
            assertThat(core.runtimeState().laneHandoffEpoch()).isEqualTo(handoff);
            core.assertClusterCallbackComplete();
        }
    }

    /**
     * 准备一笔已经撤销且没有活跃路由的订单，只带订单编号查询仍应由实际账户 Lane 找到保留详情，返回正确用户和终态。
     */
    @Test
    void orderIdOnlyQueryFindsRetainedTerminalOrderOnItsActualLane() throws Exception {
        // 终态订单所需的永续合约 1 配置。
        var instrument = CoreInstrument.from(LINE, new RegisterInstrumentCommand(
                "1", "BTC", "USDT", "USDT", 1, 1, 1,
                new CoreInstrumentRiskLimits(
                        100_000, (1_000_000_000_000L / (100_000)), Long.MAX_VALUE, 0, Long.MAX_VALUE,
                        java.util.List.of(new CoreRiskLimitBracket(1, 0, Long.MAX_VALUE, (1_000_000_000_000L / (100_000)), 100_000, 50_000))),
                0, 0, 0, -1, 0));
        // 用户 7 保留的已撤卖单 991，没有活跃订单路由但仍应能查询。
        var order = new CoreOrderState(991, LINE, 7, "1", CoreOrderSide.SELL, 100, 1, 0, 1,
                true, CoreOrderStatus.CANCELED, 2);
        // 包含已撤订单、对应用户和币对的最小业务状态。
        var state = new TradingCoreState(LINE, 0, Map.of(7L, CoreUserState.empty(LINE, 7)),
                Map.of(991L, order), Map.of("1", instrument), CoreRiskState.empty(), CoreTreasuryState.empty());
        try (/* 本用例持有的核心运行时，通过其正常命令或异步查询读取结果，结束时关闭。 */ var core = initial(state)) {
            core.activate();
            assertThat(core.activeOrderIndex().activeOrderRoute(991)).isNull();
            try (/* 阻止 Owner 越界读取活动账户的测试守卫，查询数据应从所属 Lane 返回。 */ var guard = new OwnerAccountReadGuard(core.runtimeState())) {
                // 只携带订单编号、不指定用户的终态订单查询响应。
                var response = query(core, CoreMessageType.ORDER_STATE_QUERY, 0, TradingCommandCodec.encodeOrderStateQuery(991));
                // 解码后的保留订单详情，核对编号 991、用户 7 和 CANCELED 终态。
                var view = CoreStateQueryCodec.decodeOrderState(response.data());
                assertThat(view.orderId()).isEqualTo(991);
                assertThat(view.userId()).isEqualTo(7);
                assertThat(view.status()).isEqualTo(CoreOrderStatus.CANCELED.name());
            }
            core.assertClusterCallbackComplete();
        }
    }

    /**
     * 不存在实体的详情返回 ENTITY_NOT_FOUND，缺失哈希返回零，畸形订单载荷返回 INVALID_COMMAND；随后合法用户查询仍成功。
     */
    @Test
    void missingAndMalformedQueriesPreserveTheirCodesAndDoNotPoisonLane() throws Exception {
        try (/* 本用例持有的核心运行时，通过其正常命令或异步查询读取结果，结束时关闭。 */ var core = prepared(); /* 阻止 Owner 越界读取活动账户的测试守卫，查询数据应从所属 Lane 返回。 */ var guard = new OwnerAccountReadGuard(core.runtimeState())) {
            assertThat(query(core, CoreMessageType.USER_STATE_QUERY, 99, new byte[0]).resultCode())
                    .isEqualTo(CoreResultCode.ENTITY_NOT_FOUND);
            assertThat(query(core, CoreMessageType.ORDER_STATE_QUERY, 0,
                    TradingCommandCodec.encodeOrderStateQuery(99)).resultCode()).isEqualTo(CoreResultCode.ENTITY_NOT_FOUND);
            assertThat(query(core, CoreMessageType.CLIENT_ORDER_STATE_QUERY, 1,
                    CoreStateQueryCodec.encodeClientOrderStateQuery("missing")).resultCode()).isEqualTo(CoreResultCode.ENTITY_NOT_FOUND);
            assertThat(CoreStateQueryCodec.decodeStateHash(query(core, CoreMessageType.USER_STATE_HASH_QUERY, 99, new byte[0]).data())).isZero();
            assertThat(CoreStateQueryCodec.decodeStateHash(query(core, CoreMessageType.ORDER_STATE_HASH_QUERY, 0,
                    TradingCommandCodec.encodeOrderStateQuery(99)).data())).isZero();
            assertThat(query(core, CoreMessageType.ORDER_STATE_QUERY, 0, new byte[]{1}).resultCode()).isEqualTo(CoreResultCode.INVALID_COMMAND);
            assertThat(query(core, CoreMessageType.USER_STATE_QUERY, 1, new byte[0]).status()).isEqualTo(ResponseStatus.OK);
            core.assertClusterCallbackComplete();
        }
    }

    /**
     * 给用户 1 准备超过完整查询上限的资产余额，查询应只拒绝该用户的大响应，随后查询空用户 7 仍成功。
     */
    @Test
    void oversizedUserQueryRejectsOnlyItsResult() throws Exception {
        // 用户 1 的超限资产余额集合，每个资产一单位，用来超过完整用户查询实体上限。
        var balances = new LinkedHashMap<String, AssetBalance>();
        for (/* 构造不同资产名称的下标，循环包含上限位置以确保比允许数量多一项。 */ int index = 0; index <= RuntimeStateQueryService.MAX_USER_QUERY_ENTITIES; index++)
            balances.put("ASSET" + index, new AssetBalance("ASSET" + index, 1, 0));
        // 拥有超限余额条数的用户 1，完整详情应被拒绝。
        var user = new CoreUserState(LINE, 1, 0, balances, Map.of(), Map.of());
        // 同时包含超限用户 1 和空用户 7 的状态，用后者确认失败只影响单次查询。
        var state = new TradingCoreState(LINE, 0, Map.of(1L, user, 7L, CoreUserState.empty(LINE, 7)),
                Map.of(), Map.of(), CoreRiskState.empty(), CoreTreasuryState.empty());
        try (/* 本用例持有的核心运行时，通过其正常命令或异步查询读取结果，结束时关闭。 */ var core = initial(state)) {
            core.activate();
            try (/* 阻止 Owner 越界读取活动账户的测试守卫，查询数据应从所属 Lane 返回。 */ var guard = new OwnerAccountReadGuard(core.runtimeState())) {
                assertThat(query(core, CoreMessageType.USER_STATE_QUERY, 1, new byte[0]).resultCode())
                        .isEqualTo(CoreResultCode.QUERY_RESPONSE_TOO_LARGE);
                assertThat(query(core, CoreMessageType.USER_STATE_QUERY, 7, new byte[0]).status()).isEqualTo(ResponseStatus.OK);
            }
            core.assertClusterCallbackComplete();
        }
    }

    /**
     * 在 PIPELINED 模式实际阻塞用户 1 所属 Lane，核对 Owner 先返回待完成、放行前无结果；放行后成功且结果与编号关联只能消费一次。
     */
    @Test
    @org.junit.jupiter.api.extension.ExtendWith(PipelinedExecutionExtension.class)
    void ownerReturnsWhileTargetLaneIsBlockedAndConsumesResultOnceLater() throws Exception {
        // 阻塞 Lane 的放行门闩，finally 中也释放，避免测试失败后遗留等待。
        var release = new CountDownLatch(1);
        try (/* 本用例持有的核心运行时，通过其正常命令或异步查询读取结果，结束时关闭。 */ var core = prepared()) {
            // 证明阻塞任务已进入目标 Lane 的门闩，随后才发送受阻查询。
            var entered = new CountDownLatch(1);
            assertThat(core.runtimeState().dispatchAccountQuery(core.runtimeState().topology().accountLaneMask(1), /* 用户 1 所属 Lane，在此回调里真实等待放行。 */ lane -> {
                entered.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("blocked Lane timeout"); }
                catch (/* 阻塞 Lane 等待时收到的中断，转换为保留原因的断言失败。 */ InterruptedException failure) { throw new AssertionError(failure); }
            })).isTrue();
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            try (/* 阻止 Owner 越界读取活动账户的测试守卫，查询数据应从所属 Lane 返回。 */ var guard = new OwnerAccountReadGuard(core.runtimeState())) {
                // 在目标 Lane 仍被阻塞时发出的用户 1 查询消息。
                var request = message(CoreMessageType.USER_STATE_QUERY, 1, new byte[0]);
                // Owner 立即返回的待完成响应，此时账户 Lane 尚未放行。
                var response = core.apply(request);
                assertThat(response.resultCode()).isEqualTo(CoreResultCode.MATCHING_PENDING);
                assertThat(release.getCount()).isOne();
                // 当前查询的内部异步编号，用于等待最终结果并核对只能消费一次。
                long id = core.bookQueries.querySequence(request.header().commandId());
                assertThat(core.bookQueries.takeQueryResult(id)).isNull();
                release.countDown();
                assertThat(await(core, request, id).status()).isEqualTo(ResponseStatus.OK);
            }
            core.assertClusterCallbackComplete();
        } finally { release.countDown(); }
    }

    /**
     * 在只读作用域期间监测持仓表读取：Owner 直接借用 Lane 权限应失败，Lane 不能作为查询生产者；带有未收集风险控制变化时，空查询也不应顺手读取持仓来准备发布数据。
     */
    @Test
    @org.junit.jupiter.api.extension.ExtendWith(PipelinedExecutionExtension.class)
    void readScopeDoesNotPrepareOrTouchUncollectedControlChanges() throws Exception {
        try (/* 本用例持有的核心运行时，通过其正常命令或异步查询读取结果，结束时关闭。 */ var core = prepared()) {
            // 用户 1 持仓对应的已注册合约配置，用于取得相同持仓身份。
            var instrument = core.runtimeState().instrument("1");
            // 用户 1 在合约 1 的净持仓键，本用例用它写入后移除风险快照控制变化。
            long key = core.identities().findPositionKeyValue(1, instrument, CorePositionSide.NET);
            // 合约 1 的运行时稠密编号，写入风险快照时与持仓身份一致。
            int symbolId = core.identities().findSymbolId("1");
            // 用户 1 所属账户 Lane 的位集合，控制写入和读取回调都投递到它。
            long mask = core.runtimeState().topology().accountLaneMask(1);
            // 从单 Lane 位集合取出的 Lane 编号，用于找到测试要监测的账户分区。
            int laneId = Long.numberOfTrailingZeros(mask);
            // 反射取得的账户 Lane 数组字段，仅用于测试安装读取监测。
            var lanesField = TradingAccountRuntime.class.getDeclaredField("accountLanes");
            lanesField.setAccessible(true);
            // 用户 1 的真实账户 Lane，反射安装守卫后仍由原线程消费任务。
            var lane = ((AccountLaneState[]) lanesField.get(core.runtimeState()))[laneId];
            // 账户 Lane 的权威持仓表字段，用于暂时替换成带 get 计数的测试 Map。
            var positionsField = AccountLaneState.class.getDeclaredField("positions");
            positionsField.setAccessible(true);
            // 替换前的原持仓表，finally 中恢复，防止测试监测对象逃出用例。
            @SuppressWarnings("unchecked")
            var original = (org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap<PositionRuntime>) positionsField.get(lane);
            // 仅在目标 Lane 的只读作用域测试期间打开的原子开关，区分普通控制处理与查询读取。
            var reading = new AtomicBoolean();
            // 只读作用域期间持仓 Map.get 的次数，空查询应保持为零。
            var proofReads = new AtomicInteger();
            // 以原持仓内容初始化的测试 Map，只覆写 get 计数，不改变持仓查询返回值。
            var guarded = /* 只在本测试使用的持仓 Map 守卫，读取行为不变，仅在只读监测期间累计 get 次数。 */ new org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap<PositionRuntime>(original) {
                /**
                 * 读取持仓键时仅在只读监测开关打开期间计数，再委托原 Map 返回值，用来发现只读范围中的隐式持仓读取。
                 * @param positionKey 本次 Map.get 要读取的持仓身份键，交给父类查询后原样返回
                 */
                @Override public PositionRuntime get(long positionKey) {
                    if (reading.get()) proofReads.incrementAndGet();
                    return super.get(positionKey);
                }
            };
            positionsField.set(lane, guarded);
            try {
                assertThatThrownBy(() -> core.runtimeState().readAccountLane(lane, /* Owner 直接申请 Lane 只读范围的回调参数，所有权检查应在执行回调前拒绝。 */ ignored -> Boolean.TRUE))
                        .isInstanceOf(IllegalStateException.class).hasMessageContaining("owning Lane thread");
                core.runtimeState().dispatchControlLanes(mask, /* 控制任务传入的 Lane 参数，实际通过运行时入口写入再移除风险快照，留下待收集变化。 */ ignored -> {
                    core.runtimeState().putRiskSnapshot(key, new com.surprising.aeron.service.risk.RiskSnapshotRuntime(
                            1, symbolId, CorePositionSide.NET, 1, 100, 0, 1, 1, CoreRiskStatus.NORMAL));
                    core.runtimeState().removeRiskSnapshot(key);
                    return null;
                });
                // 目标 Lane 完成只读回调后发布的原子标记，测试线程据此等待后续断言。
                var complete = new AtomicBoolean();
                assertThat(core.runtimeState().dispatchAccountQuery(mask, /* 真正执行查询回调的 Lane，继续用它进入合法的账户只读范围。 */ actualLane -> {
                    reading.set(true);
                    try {
                        core.runtimeState().readAccountLane(actualLane, /* 合法只读范围传入的 Lane 参数，回调只检查生产者限制，不需要读取实体。 */ ignored -> {
                            assertThatThrownBy(() -> core.runtimeState().dispatchAccountQuery(mask, /* 若非法查询投递意外成功才会收到的 Lane 参数，空回调不承担业务工作。 */ unusedLane -> {}))
                                    .isInstanceOf(IllegalStateException.class).hasMessage("account query producer must be Owner");
                            return Boolean.TRUE;
                        });
                    }
                    finally { reading.set(false); }
                    complete.set(true);
                })).isTrue();
                // 本次异步查询或只读回调的五秒单调时钟等待上限，不用于交易时间计算。
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (!complete.get()) {
                    core.assertHealthy();
                    if (System.nanoTime() >= deadline) throw new AssertionError("read scope timeout");
                    Thread.onSpinWait();
                }
                assertThat(proofReads).hasValue(0);
                assertThat(core.runtimeState().pollControlLanes()).isTrue();
                core.runtimeState().clearCommittedChanges(core.identities());
            } finally { positionsField.set(lane, original); }
        }
    }

    /**
     * 提交一笔未消费的用户查询后关闭运行时，断言关闭后查询编号表和完成结果表都清空；本用例直接检查的是关闭结果。
     */
    @Test
    void closeJoinsAccountReadersBeforeClearingUnconsumedResults() {
        // 本用例持有的核心运行时，通过其正常命令或异步查询读取结果，结束时关闭。
        var core = prepared();
        try {
            // 关闭前已提交但刻意不消费的用户查询，用来检查关闭清理结果表。
            var request = message(CoreMessageType.USER_STATE_QUERY, 1, new byte[0]);
            assertThat(core.apply(request).resultCode()).isEqualTo(CoreResultCode.MATCHING_PENDING);
        } finally { core.close(); }
        assertThat(core.bookQueries.queryIds).isEmpty();
        assertThat(core.bookQueries.completedBookQueries).isEmpty();
    }

    /**
     * 从给定业务快照创建核心运行时，交给用例自行激活及关闭。
     * @param state 预先准备的用户、普通订单、币对、风险和财库业务状态
     */
    private TradingCoreRuntime initial(TradingCoreState state) {
        return new TradingCoreRuntime(LINE, 0, new LinkedHashMap<>(), new LinkedHashMap<>(), state,
                new TerminalStateRetention(), null, 0, Map.of(), Map.of(), null, null);
    }

    /**
     * 通过正式命令注册合约、应用标记价、给两个用户入金并制造一次部分成交和一笔未成交挂单；准备失败时立即关闭已创建的运行时。
     */
    private TradingCoreRuntime prepared() {
        // 本用例持有的核心运行时，通过其正常命令或异步查询读取结果，结束时关闭。
        var core = new TradingCoreRuntime(LINE);
        try {
            command(core, CoreMessageType.REGISTER_INSTRUMENT, 0, TradingCommandCodec.encodeRegisterInstrument(
                    new RegisterInstrumentCommand(
                            "1", "BTC", "USDT", "USDT", 1, 1, 1,
                            new CoreInstrumentRiskLimits(
                                    100_000, (1_000_000_000_000L / (100_000)), Long.MAX_VALUE, 0, Long.MAX_VALUE,
                                    java.util.List.of(new CoreRiskLimitBracket(1, 0, Long.MAX_VALUE, (1_000_000_000_000L / (100_000)), 100_000, 50_000))),
                            0, 0, 0, -1, 0)));
            command(core, CoreMessageType.APPLY_MARK_PRICE, 0,
                    TradingCommandCodec.encodeApplyMarkPrice(new ApplyMarkPriceCommand("1", 100, 1, TIME)));
            for (long /* 本轮需要入金的测试用户，依次为 1 和 7。 */ user : new long[]{1, 7}) command(core, CoreMessageType.ADJUST_BALANCE, user,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 10_000)));
            place(core, 7, 200, CoreOrderSide.SELL, 100, 2);
            place(core, 1, 201, CoreOrderSide.BUY, 100, 1);
            place(core, 1, 202, CoreOrderSide.BUY, 99, 1);
            return core;
        } catch (/* 准备业务状态时的原始失败，先关闭运行时再原样上抛。 */ Throwable failure) { core.close(); throw failure; }
    }

    /**
     * 按指定用户、订单编号、方向、价格和数量提交全仓净持仓的限价 GTC 订单，并设置可查询的客户端订单标识。
     * @param core 本次下单、命令推进或查询所使用的测试核心运行时
     * @param user 下单用户编号
     * @param id 普通订单的唯一 Core 编号
     * @param side 普通订单的买入或卖出方向
     * @param price 限价单价格刻度
     * @param quantity 订单数量步数
     */
    private void place(TradingCoreRuntime core, long user, long id, CoreOrderSide side, long price, long quantity) {
        command(core, CoreMessageType.PLACE_ORDER, user, TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(id,
                "1", side, price, quantity, false, CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT,
                CoreTimeInForce.GTC, false, "account-q-" + id)));
    }

    /**
     * 给业务命令分配来源序号和 UUID，使用测试完成辅助器推进异步工作，并要求最终命令状态为 APPLIED。
     * @param core 本次下单、命令推进或查询所使用的测试核心运行时
     * @param type 要构造的业务命令或查询协议类型
     * @param user 命令头中的用户编号，币对或价格等非用户命令使用 0
     * @param payload 与消息类型对应的编码业务或查询载荷
     */
    private void command(TradingCoreRuntime core, CoreMessageType type, long user, byte[] payload) {
        // 本次消息新分配的来源序号，同时写入 UUID 和协议消息头。
        long next = ++sequence;
        // 本次下发的业务命令，辅助器会等待其最终应用状态。
        var request = new CoreMessage(CoreMessageHeader.command(type, new UUID(956, next), LINE,
                CommandSource.OPERATIONS, 956, next, user, TIME, next), payload);
        assertThat(CoreTestCompletion.applyAsynchronously(core, request).commandStatus()).isEqualTo(ResponseStatus.APPLIED);
    }

    /**
     * 构造账户查询并确认准备阶段不需要 Owner 读取账户；已完成响应直接返回，待完成响应按内部查询编号等待。
     * @param core 本次下单、命令推进或查询所使用的测试核心运行时
     * @param type 要构造的业务命令或查询协议类型
     * @param user 查询头中的用户筛选编号，0 的含义取决于查询类型，例如按订单编号查询或列举全部挂单
     * @param payload 与消息类型对应的编码业务或查询载荷
     */
    private CoreResponse query(TradingCoreRuntime core, CoreMessageType type, long user, byte[] payload) {
        // 按查询类型、用户及载荷创建的请求，用于判断即时或异步返回。
        var request = message(type, user, payload);
        assertThat(core.requiresOwnerLaneAccessForPreparation(request)).isFalse();
        // 提交查询的直接响应，已终态就返回，否则继续消费异步结果。
        var response = core.apply(request);
        if (response.resultCode() != CoreResultCode.MATCHING_PENDING) return response;
        return await(core, request, core.bookQueries.querySequence(request.header().commandId()));
    }

    /**
     * 最多等待五秒消费账户查询的最终响应，期间检查运行时健康；再次读取应为空，外部命令 UUID 到内部查询的关联也应清除。
     * @param core 本次下单、命令推进或查询所使用的测试核心运行时
     * @param request 已经提交的查询消息，用它的命令 UUID 核对查询关联清理
     * @param id 本次账户异步查询的内部编号，要求非零且只消费一次
     */
    private CoreResponse await(TradingCoreRuntime core, CoreMessage request, long id) {
        assertThat(id).isNotZero();
        // 本次异步查询或只读回调的五秒单调时钟等待上限，不用于交易时间计算。
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        // 持续尝试消费的最终账户查询响应，成功取走后不能再次得到同一结果。
        CoreResponse response;
        while ((response = core.bookQueries.takeQueryResult(id)) == null) {
            core.assertHealthy();
            if (System.nanoTime() >= deadline) throw new AssertionError("account query timeout");
            Thread.onSpinWait();
        }
        assertThat(core.bookQueries.takeQueryResult(id)).isNull();
        assertThat(core.bookQueries.querySequence(request.header().commandId())).isZero();
        return response;
    }

    /**
     * 用递增来源序号和独立 UUID 前缀创建指定类型、用户及载荷的查询消息。
     * @param type 要构造的业务命令或查询协议类型
     * @param user 要写入查询头的用户条件，需符合对应查询协议
     * @param payload 与消息类型对应的编码业务或查询载荷
     */
    private CoreMessage message(CoreMessageType type, long user, byte[] payload) {
        // 本次消息新分配的来源序号，同时写入 UUID 和协议消息头。
        long next = ++sequence;
        return new CoreMessage(CoreMessageHeader.query(type, new UUID(957, next), LINE,
                CommandSource.OPERATIONS, 957, next, user, TIME, next), payload);
    }
}
