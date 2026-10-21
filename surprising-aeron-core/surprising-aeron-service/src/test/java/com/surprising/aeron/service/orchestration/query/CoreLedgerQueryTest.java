package com.surprising.aeron.service.orchestration.query;

import com.surprising.aeron.protocol.*;
import com.surprising.aeron.service.orchestration.OwnerAccountReadGuard;
import com.surprising.aeron.service.orchestration.TradingCoreRuntime;
import com.surprising.aeron.service.orchestration.trade.CoreTestCompletion;
import com.surprising.aeron.service.orchestration.retention.TerminalStateRetention;
import com.surprising.aeron.service.state.*;
import com.surprising.aeron.service.state.TradingCoreState;
import com.surprising.aeron.service.state.account.CoreUserState;
import com.surprising.aeron.service.state.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.state.instrument.CoreInstrument;
import com.surprising.aeron.service.state.ledger.CoreTreasuryState;
import com.surprising.aeron.service.state.ledger.SystemLedgerLane;
import com.surprising.aeron.service.state.ledger.TreasuryRuntime;
import com.surprising.aeron.service.state.model.CoreLiquidationState;
import com.surprising.aeron.service.state.model.CoreMarkPriceState;
import com.surprising.aeron.service.state.model.CoreRiskState;
import com.surprising.aeron.service.state.snapshot.RuntimeStateProjector;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * 系统账本及强平工作页的异步查询测试，检查所属线程读取、账户无交接、分页预算、保险建议分配、邮箱满时的拒绝与续投，以及关闭后的查询资源清理。
 */
class CoreLedgerQueryTest {
    /** 本组账本查询测试独立使用的 U 本位永续产品线。 */
    private static final ProductLine LINE = ProductLine.LINEAR_PERPETUAL;
    /** 本测试实例中构造查询消息的递增序号，用于隔离命令 UUID 和来源头。 */
    private long querySequence;

    /**
     * 先通过命令给保险基金增加 250，再确认 Owner 直接读活动账本会失败；经异步查询应读到原账本余额且结果只能消费一次，账户交接计数不变。
     */
    @Test
    void treasuryQueryReadsOriginalLedgerWithoutAccountHandoffAndConsumesResultOnce() throws Exception {
        try (/* 本用例激活的核心运行时，结束时关闭其账本和账户工作资源。 */ var core = new TradingCoreRuntime(LINE)) {
            core.activate();
            assertThat(core.runtimeState().systemLedgerStarted()).isTrue();
            // 通过正常复制命令给 USDT 保险基金增加 250 的测试消息。
            var adjustment = new CoreMessage(CoreMessageHeader.command(CoreMessageType.ADJUST_INSURANCE_FUND,
                    new UUID(936, 1), LINE, CommandSource.OPERATIONS, 936, 1, 0, 1, 1),
                    TradingCommandCodec.encodeAdjustInsuranceFund(new AdjustInsuranceFundCommand("USDT", 250)));
            assertThat(CoreTestCompletion.applyAsynchronously(core, adjustment).commandStatus()).isEqualTo(ResponseStatus.APPLIED);
            // USDT 的运行时资产编号，用于核对 Owner 不能直接读取活动账本。
            int assetId = core.identities().assetId("USDT");
            assertThatThrownBy(() -> core.runtimeState().treasury().insurance(assetId)).isInstanceOf(IllegalStateException.class);
            // 查询前的账户所有权交接计数，异步读取后应保持不变。
            long handoff = core.runtimeState().laneHandoffEpoch();
            try (/* 禁止 Owner 越界读取活动账户的守卫，保证页数据由所属 Lane 查询。 */ var guard = new OwnerAccountReadGuard(core.runtimeState())) {
                // 本次查询消费到的最终响应，按场景核对成功、响应超限或页内容。
                var response = query(core, CoreMessageType.TREASURY_STATE_QUERY, new byte[0]);
                assertThat(response.status()).isEqualTo(ResponseStatus.OK);
                assertThat(CoreStateQueryCodec.decodeTreasuryState(response.data())).singleElement()
                        .satisfies(/* 财库查询中唯一的 USDT 资产项，其保险余额应为 250。 */ asset -> {
                            assertThat(asset.asset()).isEqualTo("USDT");
                            assertThat(asset.insuranceBalanceUnits()).isEqualTo(250);
                        });
            }
            assertThat(core.runtimeState().laneHandoffEpoch()).isEqualTo(handoff);
            core.assertClusterCallbackComplete();
        }
    }

    /**
     * 对强平执行和 ADL 两种工作页，组合多个游标、单项页和字节预算，与冷运行时编码逐一比较，并禁止 Owner 读取账户。
     * @param purpose 本轮测试的强平工作类型，分别为执行页和 ADL 页
     */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = CoreLiquidationWorkView.Purpose.class,
            names = {"EXECUTION", "ADL"})
    void executionAndAdlPagesReadLanesAndKeepExistingBudgets(CoreLiquidationWorkView.Purpose purpose) throws Exception {
        // 所有本用例强平记录对应的线性永续合约 1。
        var instrument = CoreInstrument.from(LINE, new RegisterInstrumentCommand("1", ContractType.LINEAR_PERPETUAL.ordinal(),
                "BTC", "USDT", "USDT", 1, 1, 1, 100_000, 50_000, 0, 0, 0, -1, 0));
        // 含不同价格序号的执行计划及两笔待 ADL 缺口，用来检查资格筛选和游标推进。
        var liquidations = Map.of(
                1L, new CoreLiquidationState(1, 1001, "1", 1, 1, 0, CoreLiquidationState.Status.PLANNED),
                2L, new CoreLiquidationState(2, 1002, "1", 2, 1, 0, CoreLiquidationState.Status.PLANNED),
                3L, new CoreLiquidationState(3, 1003, "1", 1, 1, 70, CoreLiquidationState.Status.ADL_REQUIRED),
                4L, new CoreLiquidationState(4, 1004, "1", 1, 1, 30, CoreLiquidationState.Status.ADL_REQUIRED),
                5L, new CoreLiquidationState(5, 1005, "1", 1, 1, 0, CoreLiquidationState.Status.PLANNED));
        // 含五个用户、当前标记价、强平计划和风险扫描游标的完整参照状态。
        var state = new TradingCoreState(LINE, 0,
                Map.of(1001L, CoreUserState.empty(LINE, 1001), 1002L, CoreUserState.empty(LINE, 1002),
                        1003L, CoreUserState.empty(LINE, 1003), 1004L, CoreUserState.empty(LINE, 1004),
                        1005L, CoreUserState.empty(LINE, 1005)),
                Map.of(), Map.of("1", instrument), new CoreRiskState(
                Map.of("1", new CoreMarkPriceState("1", 100, 1, 1)), Map.of(), liquidations, Map.of("1", new CoreRiskState.RiskScan("1", 1, 999, false)), 6),
                CoreTreasuryState.empty());
        // 冷运行时使用的身份映射，构造同一业务状态的独立查询参照。
        var identities = new RuntimeIdentityRegistry();
        try (/* 从测试业务状态投影的冷运行时，用其工作页编码核对在线异步查询。 */ var cold = RuntimeStateProjector.project(state, identities); /* 本用例激活的核心运行时，结束时关闭其账本和账户工作资源。 */ var core = coreWithInitialState(state)) {
            core.activate();
            // 查询前的账户所有权交接计数，异步读取后应保持不变。
            long handoff = core.runtimeState().laneHandoffEpoch();
            try (/* 禁止 Owner 越界读取活动账户的守卫，保证页数据由所属 Lane 查询。 */ var guard = new OwnerAccountReadGuard(core.runtimeState())) {
                for (long /* 本轮页起点，覆盖第一页、中间游标和末尾之后。 */ cursor : new long[]{0, 1, 3, 5}) {
                    for (int /* 本轮允许的工作页编码字节预算，与单项条数限制一起生效。 */ maxBytes : new int[]{256, 1_024}) {
                        // 指定工作类型、游标、一项上限和字节预算的页查询条件。
                        var request = new CoreLiquidationWorkView.Query(LINE, purpose, cursor, 1, maxBytes);
                        // 冷运行时依照同一计划集合得到的工作页，编码后应与在线响应一致。
                        var expected = com.surprising.aeron.service.state.query.RuntimeLiquidationQueryService.work(
                                cold, identities, LINE, request, new TreeSet<>(liquidations.keySet()), liquidations.keySet());
                        // 本次查询消费到的最终响应，按场景核对成功、响应超限或页内容。
                        var response = query(core, CoreMessageType.LIQUIDATION_WORK_QUERY, CoreLiquidationWorkCodec.encodeQuery(request));
                        assertThat(response.data()).isEqualTo(CoreLiquidationWorkCodec.encodeWork(expected));
                    }
                }
            }
            assertThat(core.runtimeState().laneHandoffEpoch()).isEqualTo(handoff);
            core.assertClusterCallbackComplete();
        }
    }

    /**
     * 两笔 70 和 30 的待赔缺口共用 25 的保险余额，即使每页只取一项，也应按全部请求得到 18 和 7 的建议；查询不实际支付保险基金。
     */
    @Test
    void insurancePageAllocatesWholeBudgetIncludingClaimsOutsidePageWithoutAccountReads() throws Exception {
        // 所有本用例强平记录对应的线性永续合约 1。
        var instrument = CoreInstrument.from(LINE, new RegisterInstrumentCommand("1", ContractType.LINEAR_PERPETUAL.ordinal(),
                "BTC", "USDT", "USDT", 1, 1, 1, 100_000, 50_000, 0, 0, 0, -1, 0));
        // 用户 1001 的首笔待保险赔付计划，缺口为 70。
        var first = new CoreLiquidationState(1, 1001, "1", 1, 1, 70, CoreLiquidationState.Status.INSURANCE_REQUIRED);
        // 用户 1002 的第二笔待保险赔付计划，缺口为 30。
        var second = new CoreLiquidationState(2, 1002, "1", 1, 1, 30, CoreLiquidationState.Status.INSURANCE_REQUIRED);
        // 两笔总缺口 100 的保险请求和 25 单位基金余额，分页不能只按页内请求分配。
        var state = new TradingCoreState(LINE, 0,
                Map.of(1001L, CoreUserState.empty(LINE, 1001), 1002L, CoreUserState.empty(LINE, 1002)),
                Map.of(), Map.of("1", instrument), new CoreRiskState(Map.of(), Map.of(), Map.of(1L, first, 2L, second), Map.of(), 3),
                CoreTreasuryState.empty().adjustInsurance("USDT", 25));
        try (/* 本用例激活的核心运行时，结束时关闭其账本和账户工作资源。 */ var core = coreWithInitialState(state)) {
            core.activate();
            assertThat(core.runtimeState().systemLedgerStarted()).isTrue();
            // 查询前的账户所有权交接计数，异步读取后应保持不变。
            long handoff = core.runtimeState().laneHandoffEpoch();
            try (/* 禁止 Owner 越界读取活动账户的守卫，保证页数据由所属 Lane 查询。 */ var guard = new OwnerAccountReadGuard(core.runtimeState())) {
                // 从游标 0 取得的第一页，应仅包含计划 1、建议赔付 18 且尚未结束。
                var pageOne = insuranceQuery(core, 0);
                assertThat(pageOne.complete()).isFalse();
                assertThat(pageOne.nextCursorLiquidationId()).isEqualTo(1);
                assertThat(pageOne.resolutions()).singleElement().satisfies(/* 当前页唯一的保险建议项，核对计划编号和建议覆盖金额。 */ claim -> {
                    assertThat(claim.liquidationId()).isEqualTo(1);
                    assertThat(claim.recommendedCoveredUnits()).isEqualTo(18);
                });
                // 从游标 1 取得的第二页，应仅包含计划 2、建议赔付 7 且已经结束。
                var pageTwo = insuranceQuery(core, 1);
                assertThat(pageTwo.complete()).isTrue();
                assertThat(pageTwo.resolutions()).singleElement().satisfies(/* 当前页唯一的保险建议项，核对计划编号和建议覆盖金额。 */ claim -> {
                    assertThat(claim.liquidationId()).isEqualTo(2);
                    assertThat(claim.recommendedCoveredUnits()).isEqualTo(7);
                });
            }
            assertThat(core.runtimeState().laneHandoffEpoch()).isEqualTo(handoff);
            assertThat(core.runtimeState().treasuryForQuery(core.identities()).insuranceBalances()).containsEntry("USDT", 25L);
            core.assertClusterCallbackComplete();
        }
    }

    /**
     * 构造一万零一种手续费资产，财库查询应因响应过大被拒绝，随后保险查询仍能正常完成，账本不因单次超限失效。
     */
    @Test
    void oversizedTreasuryResponseRejectsOnlyQueryAndLedgerStillCompletesNextQuery() {
        // 一万零一种不同资产的手续费分账，用来超过财库响应的实体上限。
        var fees = new LinkedHashMap<String, Long>();
        for (/* 生成不同资产名称的下标，每个资产都放一单位手续费。 */ int asset = 0; asset < 10_001; asset++) fees.put("ASSET" + asset, 1L);
        // 只含超大手续费资产集合的财库状态，不需要账户持仓即可触发财库查询上限。
        var state = new TradingCoreState(LINE, 0, Map.of(), Map.of(), Map.of(), CoreRiskState.empty(),
                CoreTreasuryState.ofSubledgers(fees, Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of()));
        try (/* 本用例激活的核心运行时，结束时关闭其账本和账户工作资源。 */ var core = coreWithInitialState(state)) {
            core.activate();
            assertThat(core.runtimeState().systemLedgerStarted()).isTrue();
            // 本次查询消费到的最终响应，按场景核对成功、响应超限或页内容。
            var response = query(core, CoreMessageType.TREASURY_STATE_QUERY, new byte[0]);
            assertThat(response.status()).isEqualTo(ResponseStatus.REJECTED);
            assertThat(response.resultCode()).isEqualTo(CoreResultCode.QUERY_RESPONSE_TOO_LARGE);
            assertThat(insuranceQuery(core, 0).resolutions()).isEmpty();
            core.assertClusterCallbackComplete();
        }
    }

    /**
     * 让冷读取回调抛出指定异常并核对原因，再查询活动财库应成功，原系统账本继续健康。
     */
    @Test
    void coldReadFailureDoesNotPoisonOriginalFinancialLedger() {
        try (/* 本用例激活的核心运行时，结束时关闭其账本和账户工作资源。 */ var core = new TradingCoreRuntime(LINE)) {
            core.activate();
            assertThat(core.runtimeState().systemLedgerStarted()).isTrue();
            assertThatThrownBy(() -> core.runtimeState().queryLedgerAtRest(/* 冷查询回调取得的账本，此处主动抛出异常来检查失败传播和后续查询可用性。 */ ledger -> {
                throw new IllegalArgumentException("invalid cold query");
            })).isInstanceOf(IllegalArgumentException.class).hasMessage("invalid cold query");
            assertThat(CoreStateQueryCodec.decodeTreasuryState(query(core, CoreMessageType.TREASURY_STATE_QUERY, new byte[0]).data()))
                    .isEmpty();
            core.assertClusterCallbackComplete();
        }
    }

    /**
     * 用未就绪任务填满账本邮箱，第一笔查询可保留待投递，第二笔拒绝且不残留编号；放行账本后第一笔仍能完成并只消费一次。
     */
    @Test
    void fullLedgerMailboxKeepsOneDeferredQueryAndRejectsTheNextWithoutPoisoningLedger() {
        // 占位账本任务的放行开关，finally 中同样置为 true，防止失败后遗留阻塞。
        var release = new java.util.concurrent.atomic.AtomicBoolean();
        try (/* 本用例激活的核心运行时，结束时关闭其账本和账户工作资源。 */ var core = new TradingCoreRuntime(LINE)) {
            core.activate();
            // 一直保持未就绪直到测试放行的账本占位任务，可重复投递以填满邮箱。
            var blocked = /* 测试专用账本占位任务，以 ready 控制消费，apply 不改资金。 */ new SystemLedgerLane.Entry() {
                /**
                 * 只在测试放行开关置为 true 后允许账本消费，用未就绪头项制造邮箱积压。
                 */
                @Override public boolean ready() { return release.get(); }
                /**
                 * 测试占位任务不修改账本，只用于在放行后排空先前填入的任务。
                 * @param ledger 账本线程传入的原 TreasuryRuntime，占位任务不需要读取或修改它
                 */
                @Override public void apply(TreasuryRuntime ledger) { }
            };
            try {
                // 已经投进邮箱的占位任务计数，超过保护阈值就让测试失败，避免无限填充。
                int queued = 0;
                while (core.runtimeState().dispatchLedgerQuery(blocked)) {
                    if (++queued > 65_536) throw new AssertionError("Ledger mailbox did not fill");
                }
                // 固定的一项强平执行页查询，两次请求使用相同载荷但不同命令编号。
                var payload = CoreLiquidationWorkCodec.encodeQuery(new CoreLiquidationWorkView.Query(
                        LINE, CoreLiquidationWorkView.Purpose.EXECUTION, 0, 1, 1_024));
                // 邮箱满时可被保留为待投递的第一笔工作页查询。
                var first = message(CoreMessageType.LIQUIDATION_WORK_QUERY, payload);
                assertThat(CoreTestCompletion.applyAsynchronously(core, first).resultCode()).isEqualTo(CoreResultCode.MATCHING_PENDING);
                // 本次查询对应的内部异步编号，完成后只能取一次结果。
                long id = core.bookQueries.querySequence(first.header().commandId());
                // 已有待投递查询时继续提交的第二笔查询，应被拒绝并清除编号关联。
                var second = message(CoreMessageType.LIQUIDATION_WORK_QUERY, payload);
                assertThat(CoreTestCompletion.applyAsynchronously(core, second).resultCode()).isEqualTo(CoreResultCode.MATCHING_REJECTED);
                assertThat(core.bookQueries.querySequence(second.header().commandId())).isZero();
                release.set(true);
                // 本次查询消费到的最终响应，按场景核对成功、响应超限或页内容。
                CoreResponse response;
                // 放行账本后等待第一笔查询完成的三秒单调时钟上限。
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while ((response = core.bookQueries.takeQueryResult(id)) == null) {
                    core.assertHealthy();
                    if (System.nanoTime() >= deadline) throw new AssertionError("deferred query timed out");
                    Thread.onSpinWait();
                }
                assertThat(CoreLiquidationWorkCodec.decodeWork(response.data()).complete()).isTrue();
                assertThat(core.bookQueries.takeQueryResult(id)).isNull();
                assertThat(core.bookQueries.querySequence(first.header().commandId())).isZero();
                core.assertHealthy();
            } finally { release.set(true); }
        }
    }

    /**
     * 提交一笔尚未消费的查询后关闭核心，核对关闭后账本停止、查询编号与结果表清空；本用例直接断言的是关闭后的状态。
     */
    @Test
    void closeJoinsLedgerBeforeClearingUnconsumedQueryResults() {
        // 本用例激活的核心运行时，结束时关闭其账本和账户工作资源。
        var core = new TradingCoreRuntime(LINE);
        try {
            core.activate();
            assertThat(core.runtimeState().systemLedgerStarted()).isTrue();
            // 已经构造的查询消息，用其命令 UUID 核对内部查询关联。
            var message = message(CoreMessageType.TREASURY_STATE_QUERY, new byte[0]);
            assertThat(CoreTestCompletion.applyAsynchronously(core, message).resultCode()).isEqualTo(CoreResultCode.MATCHING_PENDING);
            assertThat(core.bookQueries.querySequence(message.header().commandId())).isNotZero();
        } finally { core.close(); }
        assertThat(core.runtimeState().systemLedgerStarted()).isFalse();
        assertThat(core.bookQueries.queryIds).isEmpty();
        assertThat(core.bookQueries.completedBookQueries).isEmpty();
    }

    /**
     * 把给定业务状态装入尚未激活的核心运行时，让每个测试自行控制启动和关闭。
     * @param state 预先准备的用户、风险计划和财库业务状态
     */
    private TradingCoreRuntime coreWithInitialState(TradingCoreState state) {
        return new TradingCoreRuntime(LINE, 0, new LinkedHashMap<>(), new LinkedHashMap<>(), state,
                new TerminalStateRetention(), null, 0, Map.of(), Map.of(), null, null);
    }

    /**
     * 编码指定游标后的单项保险工作页，发送查询并要求成功，再解码供分页及建议金额断言使用。
     * @param core 已经激活并负责投递与回收异步查询的核心运行时
     * @param cursor 上一页已消费的强平编号，当前页只取它后面的保险计划
     */
    private CoreLiquidationWorkView insuranceQuery(TradingCoreRuntime core, long cursor) {
        // 按传入游标编码的一项保险查询，字节预算固定为 1024。
        var payload = CoreLiquidationWorkCodec.encodeQuery(new CoreLiquidationWorkView.Query(
                LINE, CoreLiquidationWorkView.Purpose.INSURANCE, cursor, 1, 1_024));
        // 本次查询消费到的最终响应，按场景核对成功、响应超限或页内容。
        var response = query(core, CoreMessageType.LIQUIDATION_WORK_QUERY, payload);
        assertThat(response.status()).isEqualTo(ResponseStatus.OK);
        return CoreLiquidationWorkCodec.decodeWork(response.data());
    }

    /**
     * 提交异步查询并确认准备阶段不需要 Owner 读取账户；最多五秒等待结果，消费后核对结果和命令关联都已移除。
     * @param core 已经激活并负责投递与回收异步查询的核心运行时
     * @param type 本次查询的协议消息类型
     * @param payload 对应消息类型的编码查询载荷
     */
    private CoreResponse query(TradingCoreRuntime core, CoreMessageType type, byte[] payload) {
        // 已经构造的查询消息，用其命令 UUID 核对内部查询关联。
        var message = message(type, payload);
        assertThat(core.requiresOwnerLaneAccessForPreparation(message)).isFalse();
        // 本次查询消费到的最终响应，按场景核对成功、响应超限或页内容。
        var response = CoreTestCompletion.applyAsynchronously(core, message);
        assertThat(response.resultCode()).isEqualTo(CoreResultCode.MATCHING_PENDING);
        // 本次查询对应的内部异步编号，完成后只能取一次结果。
        long id = core.bookQueries.querySequence(message.header().commandId());
        assertThat(id).isNotZero();
        // 异步账本查询的五秒单调时钟等待上限，不参与业务定序。
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while ((response = core.bookQueries.takeQueryResult(id)) == null) {
            core.assertHealthy();
            if (System.nanoTime() >= deadline) throw new AssertionError("Ledger query timeout");
            Thread.onSpinWait();
        }
        assertThat(core.bookQueries.takeQueryResult(id)).isNull();
        assertThat(core.bookQueries.querySequence(message.header().commandId())).isZero();
        return response;
    }

    /**
     * 按指定查询类型与载荷构造来源序号递增的消息，各请求使用不同命令 UUID。
     * @param type 本次查询的协议消息类型
     * @param payload 对应消息类型的编码查询载荷
     */
    private CoreMessage message(CoreMessageType type, byte[] payload) {
        // 本次新分配的消息来源序号，也用于查询命令 UUID。
        long seq = ++querySequence;
        return new CoreMessage(CoreMessageHeader.query(type, new UUID(937, seq), LINE, CommandSource.OPERATIONS,
                937, seq, 0, 1, seq), payload);
    }
}
