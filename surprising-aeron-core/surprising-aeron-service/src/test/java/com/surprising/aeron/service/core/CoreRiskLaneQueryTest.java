package com.surprising.aeron.service.core;

import com.surprising.aeron.model.*;
import com.surprising.aeron.model.AssetBalance;
import com.surprising.aeron.model.CoreMarkPriceState;
import com.surprising.aeron.model.CorePositionState;
import com.surprising.aeron.model.CoreRiskState;
import com.surprising.aeron.model.TradingCoreState;
import com.surprising.aeron.protocol.*;
import com.surprising.aeron.service.account.CoreUserState;
import com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.core.index.AdlPositionIndex;
import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.aeron.service.market.MarkPriceRuntime;
import com.surprising.aeron.service.order.CoreTestCompletion;
import com.surprising.aeron.service.query.state.RuntimeOperationalQueryService;
import com.surprising.aeron.service.query.state.RuntimeRiskQueryService;
import com.surprising.aeron.service.retention.TerminalStateRetention;
import com.surprising.aeron.service.snapshot.state.RuntimeStateProjector;
import com.surprising.aeron.service.treasury.CoreTreasuryState;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.assertj.core.api.Assertions.*;

/**
 * 风险查询的线程边界与结果一致性测试：用独立参照计算核对各账户 Lane 的返回值，再验证查询固定的市场事实、分页和响应数量限制。
 */
class CoreRiskLaneQueryTest {
    /** 所有测试消息共用的固定 Unix 毫秒时间戳，避免本机时间变化影响结果。 */
    private static final long TIME = 1_700_000_000_000L;
    /** 本测试实例已经分配的查询序号，每次构造消息先递增一次。 */
    private long sequence;

    /**
     * 对线性永续、反向永续和期权验证未平仓量汇总及维护用户分页；安装 Owner 读表哨兵，确保查询遵守账户 Lane 所有权。
     * @param line 本轮使用的产品线，所有合约、状态和查询必须处于同一条产品线
     */
    @ParameterizedTest
    @EnumSource(value = ProductLine.class, names = {"LINEAR_PERPETUAL", "INVERSE_PERPETUAL", "OPTION"})
    void ledgerEncodesOpenInterestAndBoundedMaintenanceUsers(ProductLine line) throws Exception {
        try (/* 从本用例状态初始化的交易运行时，测试结束时自动关闭账户 Lane 等资源。 */ var core = initial(positionedState(line))) {
            core.activate();
            try (/* 临时禁止 Owner 越界读取账户表的哨兵，离开作用域时恢复原表。 */ var guard = new OwnerAccountReadGuard(core.runtimeState)) {
                assertThat(query(core, CoreMessageType.OPEN_INTEREST_QUERY, 0, new byte[0]).data())
                        .isEqualTo(CoreOpenInterestCodec.encode(List.of(new CoreOpenInterestView("1", 25, 0),
                                new CoreOpenInterestView("2", 0, 8), new CoreOpenInterestView("3", 10, 0))));
                // 合约 1 维护用户查询的第一页响应，每页限制为一个用户。
                var first = query(core, CoreMessageType.INSTRUMENT_MAINTENANCE_QUERY, 0,
                        CoreMaintenanceCodec.encodeQuery(new CoreMaintenanceCodec.Query("1", 0, 1)));
                // 解码后的第一页，应该只含用户 1 并提示还有后续用户。
                var page = CoreMaintenanceCodec.decodePage(first.data());
                assertThat(page.userIds()).containsExactly(1L);
                assertThat(page.hasMore()).isTrue();
                // 从用户 1 之后继续查询的下一页，应该只含用户 7 且已经到末尾。
                var next = CoreMaintenanceCodec.decodePage(query(core, CoreMessageType.INSTRUMENT_MAINTENANCE_QUERY, 0,
                        CoreMaintenanceCodec.encodeQuery(new CoreMaintenanceCodec.Query("1", 1, 1))).data());
                assertThat(next.userIds()).containsExactly(7L);
                assertThat(next.hasMore()).isFalse();
            }
            core.assertClusterCallbackComplete();
        }
    }

    /**
     * 把运行中的全局风险、单用户风险和 ADL 候选编码与独立参照计算比对，检查空结果、候选上限，以及查询不会转移 Lane 所有权或修改业务状态。
     * @param line 本轮使用的产品线，所有合约、状态和查询必须处于同一条产品线
     */
    @ParameterizedTest
    @EnumSource(value = ProductLine.class, names = {"LINEAR_PERPETUAL", "INVERSE_PERPETUAL", "OPTION"})
    void currentRiskAndAdlReadActualLanesAndMatchColdCalculation(ProductLine line) throws Exception {
        // 包含多币对、多空及全仓逐仓持仓的业务参照状态。
        var state = positionedState(line);
        // 参照计算使用的业务编号映射，与投影出来的运行时配套使用。
        var identities = new RuntimeIdentityRegistry();
        try (/* 用于直接调用风险计算的独立参照运行时，测试结束自动关闭。 */ var expected = RuntimeStateProjector.project(state, identities); /* 从本用例状态初始化的交易运行时，测试结束时自动关闭账户 Lane 等资源。 */ var core = initial(state)) {
            // 不限定用户的参照风险行列表，用来核对全局查询编码。
            var globalRisk = RuntimeRiskQueryService.snapshots(expected, identities, 0);
            // 只包含用户 1 的参照风险行列表。
            var userRisk = RuntimeRiskQueryService.snapshots(expected, identities, 1);
            // 按资产建立的 ADL 持仓索引，供参照计算定位候选仓位。
            var adlIndex = new AdlPositionIndex(state, identities);
            // 按本轮结算资产算出的完整 ADL 候选参照列表，再分别核对全量与限一条查询。
            var adl = RuntimeRiskQueryService.adlCandidates(expected, identities, asset(line), adlIndex.positions(asset(line), Integer.MAX_VALUE), 10);
            var beforeQueries = core.tradingState();
            core.activate();
            // 查询前的 Lane 所有权交接代次，查询后不应发生变化。
            long handoff = core.runtimeState.laneHandoffEpoch();
            try (/* 临时禁止 Owner 越界读取账户表的哨兵，离开作用域时恢复原表。 */ var guard = new OwnerAccountReadGuard(core.runtimeState)) {
                assertThat(query(core, CoreMessageType.RISK_STATE_QUERY, 0, new byte[0]).data())
                        .isEqualTo(CoreRiskQueryCodec.encode(globalRisk));
                assertThat(query(core, CoreMessageType.RISK_STATE_QUERY, 1, new byte[0]).data())
                        .isEqualTo(CoreRiskQueryCodec.encode(userRisk));
                assertThat(query(core, CoreMessageType.RISK_STATE_QUERY, 99, new byte[0]).data())
                        .isEqualTo(CoreRiskQueryCodec.encode(List.of()));
                assertThat(query(core, CoreMessageType.ADL_CANDIDATE_QUERY, 0, CoreAdlQueryCodec.encodeQuery(asset(line), 10)).data())
                        .isEqualTo(CoreAdlQueryCodec.encodeCandidates(adl));
                assertThat(query(core, CoreMessageType.ADL_CANDIDATE_QUERY, 0, CoreAdlQueryCodec.encodeQuery(asset(line), 1)).data())
                        .isEqualTo(CoreAdlQueryCodec.encodeCandidates(adl.subList(0, 1)));
                assertThat(query(core, CoreMessageType.ADL_CANDIDATE_QUERY, 0, CoreAdlQueryCodec.encodeQuery("NONE", 10)).data())
                        .isEqualTo(CoreAdlQueryCodec.encodeCandidates(List.of()));
            }
            assertThat(globalRisk).hasSize(5);
            assertThat(adl).hasSize(2);
            assertThat(core.runtimeState.laneHandoffEpoch()).isEqualTo(handoff);
            assertThat(core.tradingState()).isEqualTo(beforeQueries);
            core.assertClusterCallbackComplete();
        }
    }

    /**
     * 先挂一笔逐仓订单再查询风险，逐仓冻结应从全仓钱包权益中扣除；查询结果要与参照计算一致且业务状态不变。
     */
    @Test
    void isolatedOrderReservationIsDeductedOnItsLaneWithoutChangingFunds() throws Exception {
        try (/* 从本用例状态初始化的交易运行时，测试结束时自动关闭账户 Lane 等资源。 */ var core = initial(positionedState(ProductLine.LINEAR_PERPETUAL))) {
            core.activate();
            // 用户 1 在合约 3 挂出的逐仓限价单，用它制造真实委托冻结。
            var request = new CoreMessage(CoreMessageHeader.command(CoreMessageType.PLACE_ORDER, new UUID(970, 1), core.productLine,
                    CommandSource.OPERATIONS, 970, 1, 1, TIME, 1), TradingCommandCodec.encodePlaceOrder(
                    new PlaceOrderCommand(400, "3", CoreOrderSide.BUY, 77, 1, false, CoreMarginMode.ISOLATED,
                            CorePositionSide.NET, CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "risk-query-isolated")));
            assertThat(CoreTestCompletion.applyAsynchronously(core, request).commandStatus()).isEqualTo(ResponseStatus.APPLIED);
            // 订单已应用、但风险查询尚未开始的状态，查询后必须保持相同。
            var before = core.tradingState();
            // 该逐仓委托尚未释放的冻结金额，需要从全仓钱包余额中扣除。
            long reserved = before.users().get(1L).reservations().get(400L).remainingUnits();
            // 参照计算使用的业务编号映射，与投影出来的运行时配套使用。
            var identities = new RuntimeIdentityRegistry();
            try (/* 独立投影的参照运行时，用于计算期望结果，离开作用域后自动关闭。 */ var cold = RuntimeStateProjector.project(before, identities)) {
                // 包含逐仓委托冻结扣减后的用户风险参照结果。
                var expected = RuntimeRiskQueryService.snapshots(cold, identities, 1);
                // 查询前的 Lane 所有权交接代次，查询后不应发生变化。
                long handoff = core.runtimeState.laneHandoffEpoch();
                try (/* 临时禁止 Owner 越界读取账户表的哨兵，离开作用域时恢复原表。 */ var guard = new OwnerAccountReadGuard(core.runtimeState)) {
                    // 实际账户 Lane 返回的风险查询响应，用来核对编码及钱包余额。
                    var response = query(core, CoreMessageType.RISK_STATE_QUERY, 1, new byte[0]);
                    assertThat(response.data()).isEqualTo(CoreRiskQueryCodec.encode(expected));
                    assertThat(CoreRiskQueryCodec.decode(response.data())).allSatisfy(/* 逐条检查的钱包风险记录，钱包余额必须扣除逐仓订单预占金额。 */ row ->
                            assertThat(row.walletBalanceUnits()).isEqualTo(9500 - reserved));
                }
                assertThat(core.runtimeState.laneHandoffEpoch()).isEqualTo(handoff);
                assertThat(core.tradingState()).isEqualTo(before);
            }
            core.assertClusterCallbackComplete();
        }
    }

    /**
     * 故意阻塞一个账户 Lane，提交风险和 ADL 查询后更新 Owner 行情与配置；旧查询必须使用提交时的事实，新查询才看到更新，Owner 提交时不应等待 Lane 完成。
     */
    @Test
    @org.junit.jupiter.api.extension.ExtendWith(PipelinedExecutionExtension.class)
    void queryFixesPriceAndConfigurationBeforeOwnerUpdatesThemAndDoesNotWaitForLane() throws Exception {
        // 包含多币对、多空及全仓逐仓持仓的业务参照状态。
        var state = positionedState(ProductLine.LINEAR_PERPETUAL);
        // 参照计算使用的业务编号映射，与投影出来的运行时配套使用。
        var identities = new RuntimeIdentityRegistry();
        // 控制被阻塞 Lane 何时继续的闩锁，finally 中也释放以避免测试遗留阻塞线程。
        var release = new CountDownLatch(1);
        try (/* 独立投影的参照运行时，用于计算期望结果，离开作用域后自动关闭。 */ var cold = RuntimeStateProjector.project(state, identities); /* 从本用例状态初始化的交易运行时，测试结束时自动关闭账户 Lane 等资源。 */ var core = initial(state)) {
            // Owner 更新市场事实前独立算出的旧风险结果，已提交查询必须返回这一版。
            var expectedRisk = RuntimeRiskQueryService.snapshots(cold, identities, 0);
            // Owner 更新市场事实前的 ADL 候选参照，不能受到随后配置原位更新的影响。
            var expectedAdl = RuntimeRiskQueryService.adlCandidates(cold, identities, "USDT",
                    new AdlPositionIndex(state, identities).positions("USDT", Integer.MAX_VALUE), 10);
            core.activate();
            // 查询前的 Lane 所有权交接代次，查询后不应发生变化。
            long handoff = core.runtimeState.laneHandoffEpoch();
            // 确认目标账户 Lane 已进入阻塞回调的闩锁。
            var entered = new CountDownLatch(1);
            // 用户 1 所属账户 Lane 的位掩码，只阻塞需要检查的 Lane。
            long mask = core.runtimeState.topology().accountLaneMask(1);
            assertThat(core.runtimeState.dispatchAccountQuery(mask, /* 被调度到的目标账户 Lane，本用例用该回调等待闩锁以制造延迟。 */ lane -> {
                entered.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("blocked Lane timeout"); }
                catch (/* 阻塞 Lane 等待闩锁时收到的中断，包装为断言失败让测试及时报错。 */ InterruptedException failure) { throw new AssertionError(failure); }
            })).isTrue();
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            try (/* 临时禁止 Owner 越界读取账户表的哨兵，离开作用域时恢复原表。 */ var guard = new OwnerAccountReadGuard(core.runtimeState)) {
                // Lane 尚未释放时提交的全局风险查询，应该已经固定旧行情与配置。
                var riskRequest = message(core, CoreMessageType.RISK_STATE_QUERY, 0, new byte[0]);
                // 同样在 Lane 阻塞期间提交的 ADL 查询，必须保留自己的旧市场事实。
                var adlRequest = message(core, CoreMessageType.ADL_CANDIDATE_QUERY, 0, CoreAdlQueryCodec.encodeQuery("USDT", 10));
                assertThat(core.apply(riskRequest).resultCode()).isEqualTo(CoreResultCode.MATCHING_PENDING);
                assertThat(core.apply(adlRequest).resultCode()).isEqualTo(CoreResultCode.MATCHING_PENDING);
                // 风险查询在异步结果表里的序号，用于后续单次领取结果。
                long riskId = core.bookQueries.querySequence(riskRequest.header().commandId());
                // ADL 查询在异步结果表里的序号，与风险查询分开领取。
                long adlId = core.bookQueries.querySequence(adlRequest.header().commandId());
                assertThat(core.bookQueries.takeQueryResult(riskId)).isNull();
                assertThat(core.bookQueries.takeQueryResult(adlId)).isNull();
                // 修改Owner拥有的市场事实，不进入Lane或触碰账户。配置规范对象本身会原位更新。
                for (String name : state.instruments().keySet()) {
                    // 运行时中由 Owner 持有的币对规范对象，配置更新会直接改写该对象。
                    var instrument = core.runtimeState.instrument(name);
                    instrument.updateConfiguration(instrument(ProductLine.LINEAR_PERPETUAL, name, 60_000));
                    // 当前币对的运行时整数编号，写入新标记价时用于定位。
                    int symbol = core.identities.findSymbolId(name);
                    core.runtimeState.putMarkPrice(new MarkPriceRuntime(symbol, instrument, 120, 100, 100, 2, TIME));
                }
                assertThat(release.getCount()).isOne();
                release.countDown();
                assertThat(await(core, riskRequest, riskId).data()).isEqualTo(CoreRiskQueryCodec.encode(expectedRisk));
                assertThat(await(core, adlRequest, adlId).data()).isEqualTo(CoreAdlQueryCodec.encodeCandidates(expectedAdl));
                // 旧查询都完成之后再发起的新风险查询结果，应反映新价格与维持保证金配置。
                var fresh = CoreRiskQueryCodec.decode(query(core, CoreMessageType.RISK_STATE_QUERY, 0, new byte[0]).data());
                assertThat(fresh).allSatisfy(/* 新查询结果中正在检查或筛选的风险行，应使用更新后的行情事实。 */ row -> {
                    assertThat(row.markPriceTicks()).isEqualTo(120);
                    assertThat(row.priceSequence()).isEqualTo(2);
                });
                assertThat(fresh).isNotEqualTo(expectedRisk);
                assertThat(fresh.stream().filter(/* 新查询结果中正在检查或筛选的风险行，应使用更新后的行情事实。 */ row -> row.userId() == 1 && row.instrumentId().equals("1")).findFirst().orElseThrow()
                        .maintenanceMarginUnits()).isEqualTo(72);
                assertThat(core.runtimeState.laneHandoffEpoch()).isEqualTo(handoff);
            }
            core.assertClusterCallbackComplete();
        } finally { release.countDown(); }
    }

    /**
     * 畸形 ADL 载荷只拒绝本次请求；空运行时的全局风险和合法 ADL 查询随后仍能返回空结果。
     */
    @Test
    void malformedAdlQueryRejectsOnlyThatRequestAndEmptyGlobalRiskIsImmediate() throws Exception {
        try (/* 从本用例状态初始化的交易运行时，测试结束时自动关闭账户 Lane 等资源。 */ var core = new TradingCoreRuntime(ProductLine.LINEAR_PERPETUAL)) {
            core.activate();
            try (/* 临时禁止 Owner 越界读取账户表的哨兵，离开作用域时恢复原表。 */ var guard = new OwnerAccountReadGuard(core.runtimeState)) {
                assertThat(query(core, CoreMessageType.ADL_CANDIDATE_QUERY, 0, new byte[]{1}).resultCode())
                        .isEqualTo(CoreResultCode.INVALID_COMMAND);
                assertThat(query(core, CoreMessageType.RISK_STATE_QUERY, 0, new byte[0]).data())
                        .isEqualTo(CoreRiskQueryCodec.encode(List.of()));
                assertThat(query(core, CoreMessageType.ADL_CANDIDATE_QUERY, 0, CoreAdlQueryCodec.encodeQuery("USDT", 1)).data())
                        .isEqualTo(CoreAdlQueryCodec.encodeCandidates(List.of()));
            }
            core.assertClusterCallbackComplete();
        }
    }

    /**
     * 跨所有 Lane 的全局实体数超限时应返回响应过大，接下来的单用户查询仍需成功且运行时保持健康。
     */
    @Test
    void globalResponseLimitCountsAllLanesAndDoesNotPoisonFollowingQuery() throws Exception {
        // 本次超限压力场景使用的线性永续产品线。
        var line = ProductLine.LINEAR_PERPETUAL;
        // 所有测试用户共同持仓的合约配置。
        var instrument = instrument(line, "1", 50_000);
        // 超过查询实体数量上限一个用户的账户映射，用户会分布在不同 Lane。
        var users = new LinkedHashMap<Long, CoreUserState>();
        for (/* 批量构造持仓账户的用户编号，循环到查询上限加一。 */ long user = 1; user <= RuntimeOperationalQueryService.MAX_QUERY_ENTITIES + 1; user++)
            users.put(user, user(line, user, Map.of("1", position(line, "1", 1, CoreMarginMode.CROSS, 10))));
        // 包含超限用户数的根状态，用来验证限制按所有 Lane 的合计计算。
        var state = new TradingCoreState(line, 0, users, Map.of(), Map.of("1", instrument),
                new CoreRiskState(Map.of("1", new CoreMarkPriceState("1", 80, 1, TIME)), Map.of(), Map.of(), Map.of(), 1),
                CoreTreasuryState.empty());
        try (/* 从本用例状态初始化的交易运行时，测试结束时自动关闭账户 Lane 等资源。 */ var core = initial(state)) {
            core.activate();
            try (/* 临时禁止 Owner 越界读取账户表的哨兵，离开作用域时恢复原表。 */ var guard = new OwnerAccountReadGuard(core.runtimeState)) {
                assertThat(query(core, CoreMessageType.RISK_STATE_QUERY, 0, new byte[0]).resultCode())
                        .isEqualTo(CoreResultCode.QUERY_RESPONSE_TOO_LARGE);
                // 全局查询失败后发出的用户 7 单用户查询，应正常成功。
                var next = query(core, CoreMessageType.RISK_STATE_QUERY, 7, new byte[0]);
                assertThat(next.status()).isEqualTo(ResponseStatus.OK);
                assertThat(CoreRiskQueryCodec.decode(next.data())).singleElement().satisfies(/* 用户 7 唯一一条风险记录，逐项核对持仓数量、钱包及浮亏。 */ row -> {
                    assertThat(row.userId()).isEqualTo(7);
                    assertThat(row.signedQuantitySteps()).isOne();
                    assertThat(row.walletBalanceUnits()).isEqualTo(10_000);
                    assertThat(row.unrealizedPnlUnits()).isEqualTo(-20);
                });
                core.assertHealthy();
            }
            core.assertClusterCallbackComplete();
        }
    }

    /**
     * 创建三个币对、两个用户的测试状态，包含多空及全仓逐仓持仓，供未平仓量、组合风险和 ADL 查询共同使用。
     * @param line 本轮使用的产品线，所有合约、状态和查询必须处于同一条产品线
     */
    private static TradingCoreState positionedState(ProductLine line) {
        // 三个测试币对的配置映射，按插入顺序保存便于预期结果检查。
        var instruments = new LinkedHashMap<String, CoreInstrument>();
        // 三个币对序号为 1 的行情映射，标记价 80，指数和远期价都是 100。
        var prices = new LinkedHashMap<String, CoreMarkPriceState>();
        for (/* 本轮创建合约配置和行情记录的测试币对编号。 */ String name : List.of("1", "2", "3")) {
            instruments.put(name, instrument(line, name, 50_000));
            prices.put(name, new CoreMarkPriceState(name, 80, 100, 100, 1, TIME));
        }
        // 用户 1 的持仓组合，包含两个全仓仓位和一个逐仓仓位。
        var first = user(line, 1, Map.of("1", position(line, "1", 10, CoreMarginMode.CROSS, 100),
                "2", position(line, "2", -5, CoreMarginMode.CROSS, 50),
                "3", position(line, "3", 10, CoreMarginMode.ISOLATED, 500)));
        // 用户 7 的持仓组合，包含一个全仓多头和一个逐仓空头。
        var second = user(line, 7, Map.of("1", position(line, "1", 15, CoreMarginMode.CROSS, 200),
                "2", position(line, "2", -3, CoreMarginMode.ISOLATED, 60)));
        return new TradingCoreState(line, 0, Map.of(1L, first, 7L, second), Map.of(), instruments,
                new CoreRiskState(prices, Map.of(), Map.of(), Map.of(), 1), CoreTreasuryState.empty());
    }

    /**
     * 按产品线构造测试合约，设置结算资产、反向精度和期权行权信息，并允许用不同维持保证金率验证配置变化。
     * @param line 本轮使用的产品线，所有合约、状态和查询必须处于同一条产品线
     * @param name 需要构造的测试币对编号
     * @param maintenance 该合约的维持保证金率，单位为百万分之一
     */
    private static CoreInstrument instrument(ProductLine line, String name, long maintenance) {
        // 由产品线确定的合约类型，用于选择反向精度及到期、行权参数。
        var type = ContractType.valueOf(line.contractTypeCode());
        return CoreInstrument.from(line, new RegisterInstrumentCommand(
                name, "BTC", "USDT", asset(line), 1, 1, type.isInverse() ? 1000 : 1,
                (type == ContractType.SPOT ? null : new CoreInstrumentRiskLimits(
                        100_000, (1_000_000_000_000L / (100_000)), Long.MAX_VALUE, 0, Long.MAX_VALUE,
                        java.util.List.of(new CoreRiskLimitBracket(Long.MAX_VALUE, (1_000_000_000_000L / (100_000)), 100_000, maintenance)))),
                0, 0, type.isDelivery() || type.isOption() ? TIME + 20_000 : 0, type.isOption() ? 0 : -1,
                type.isOption() ? 100 : 0));
    }

    /**
     * 创建入场价为 100 的净持仓，保留调用方指定的方向数量、保证金模式和持仓保证金。
     * @param line 本轮使用的产品线，所有合约、状态和查询必须处于同一条产品线
     * @param name 需要构造的测试币对编号
     * @param quantity 带多空方向的持仓数量步数，正数多头、负数空头
     * @param mode 这份测试持仓使用全仓还是逐仓保证金
     * @param margin 该持仓占用的保证金金额，单位为测试结算资产最小单位
     */
    private static CorePositionState position(ProductLine line, String name, long quantity, CoreMarginMode mode, long margin) {
        return new CorePositionState(name, asset(line), mode, CorePositionSide.NET, quantity, 100,
                Math.abs(quantity) * 100, 0, margin);
    }

    /**
     * 创建总资产为一万单位的测试用户，把所有持仓保证金合计为冻结金额，其余留在可用余额。
     * @param line 本轮使用的产品线，所有合约、状态和查询必须处于同一条产品线
     * @param id 本次创建的测试用户编号
     * @param positions 按持仓业务键保存的测试持仓映射
     */
    private static CoreUserState user(ProductLine line, long id, Map<String, CorePositionState> positions) {
        // 本用户所有持仓保证金之和，作为冻结余额，避免初始资金被重复使用。
        long locked = positions.values().stream().mapToLong(CorePositionState::positionMarginUnits).sum();
        return new CoreUserState(line, id, 0, Map.of(asset(line), new AssetBalance(asset(line), 10_000 - locked, locked)),
                Map.of(), positions);
    }

    /**
     * 返回产品线的测试结算资产：反向永续用 BTC，其余本组测试产品用 USDT。
     * @param line 本轮使用的产品线，所有合约、状态和查询必须处于同一条产品线
     */
    private static String asset(ProductLine line) { return line == ProductLine.INVERSE_PERPETUAL ? "BTC" : "USDT"; }

    /**
     * 使用给定业务状态创建测试交易运行时，保留默认终态保留策略，尚不在这里激活线程。
     * @param state 用于初始化交易运行时的不可变业务状态
     */
    private TradingCoreRuntime initial(TradingCoreState state) {
        return new TradingCoreRuntime(state.productLine(), 0, new LinkedHashMap<>(), new LinkedHashMap<>(), state,
                new TerminalStateRetention(), null, 0, Map.of(), Map.of(), null, null);
    }

    /**
     * 构造并提交查询，先断言准备阶段无需 Owner 读取账户 Lane；异步结果则继续等待，立即结果直接返回。
     * @param core 接收查询并提供异步结果的测试交易运行时
     * @param type 查询消息的协议类型
     * @param user 查询范围的用户编号，0 表示全局范围
     * @param payload 按对应查询协议编码的请求载荷，无载荷查询使用空字节数组
     */
    private CoreResponse query(TradingCoreRuntime core, CoreMessageType type, long user, byte[] payload) {
        // 用统一测试头与载荷构造的查询消息。
        var request = message(core, type, user, payload);
        assertThat(core.requiresOwnerLaneAccessForPreparation(request)).isFalse();
        // 提交后立即返回的响应，可能是最终结果，也可能表示查询还在等待账户 Lane。
        var response = core.apply(request);
        return response.resultCode() == CoreResultCode.MATCHING_PENDING
                ? await(core, request, core.bookQueries.querySequence(request.header().commandId())) : response;
    }

    /**
     * 在五秒内轮询指定查询结果并检查运行时健康，取走结果后再确认结果槽与命令索引已清理，防止重复交付。
     * @param core 接收查询并提供异步结果的测试交易运行时
     * @param request 正在等待结果的原始查询消息，完成后用它核对命令索引已清理
     * @param id 需要领取结果的异步查询序号，必须非零
     */
    private CoreResponse await(TradingCoreRuntime core, CoreMessage request, long id) {
        assertThat(id).isNotZero();
        // 本次等待的单调时钟截止值，最多等待五秒，避免未完成查询让测试无限自旋。
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        // 从异步结果表取到的最终响应，尚未完成时循环继续检查健康状态和超时。
        CoreResponse response;
        while ((response = core.bookQueries.takeQueryResult(id)) == null) {
            core.assertHealthy();
            if (System.nanoTime() >= deadline) throw new AssertionError("risk query timeout");
            Thread.onSpinWait();
        }
        assertThat(core.bookQueries.takeQueryResult(id)).isNull();
        assertThat(core.bookQueries.querySequence(request.header().commandId())).isZero();
        return response;
    }

    /**
     * 为本次查询生成唯一命令编号和递增序号，带上产品线、查询用户与固定测试时间。
     * @param core 接收查询并提供异步结果的测试交易运行时
     * @param type 查询消息的协议类型
     * @param user 查询范围的用户编号，0 表示全局范围
     * @param payload 按对应查询协议编码的请求载荷，无载荷查询使用空字节数组
     */
    private CoreMessage message(TradingCoreRuntime core, CoreMessageType type, long user, byte[] payload) {
        // 为当前查询分配的递增序号，同时用于命令编号和消息头，避免同一测试内冲突。
        long next = ++sequence;
        return new CoreMessage(CoreMessageHeader.query(type, new UUID(971, next), core.productLine,
                CommandSource.OPERATIONS, 971, next, user, TIME, next), payload);
    }
}
