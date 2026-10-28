package com.surprising.aeron.service.query;

import com.surprising.aeron.model.CoreLiquidationState;
import com.surprising.aeron.model.CoreMarkPriceState;
import com.surprising.aeron.model.CoreRiskState;
import com.surprising.aeron.model.TradingCoreState;
import com.surprising.aeron.protocol.*;
import com.surprising.aeron.service.account.CoreUserState;
import com.surprising.aeron.service.core.*;
import com.surprising.aeron.service.core.OwnerAccountReadGuard;
import com.surprising.aeron.service.core.TradingCoreRuntime;
import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.aeron.service.order.CoreTestCompletion;
import com.surprising.aeron.service.retention.TerminalStateRetention;
import com.surprising.aeron.service.treasury.CoreTreasuryState;
import com.surprising.product.api.ProductLine;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * Owner 同步账本查询与账户 Lane 异步强平工作页测试，检查所属线程读取、账户无交接、分页预算、保险建议分配、邮箱满时的拒绝与续投，以及关闭后的查询资源清理。
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
            // 通过正常复制命令给 USDT 保险基金增加 250 的测试消息。
            var adjustment = new CoreMessage(CoreMessageHeader.command(CoreMessageType.ADJUST_INSURANCE_FUND,
                    new UUID(936, 1), LINE, CommandSource.OPERATIONS, 936, 1, 0, 1, 1),
                    TradingCommandCodec.encodeAdjustInsuranceFund(new AdjustInsuranceFundCommand("USDT", 250)));
            assertThat(CoreTestCompletion.applyAsynchronously(core, adjustment).commandStatus()).isEqualTo(ResponseStatus.APPLIED);
            // USDT 的运行时资产编号，账本内联由 Owner 同步持有，可直接读取。
            int assetId = core.identities().assetId("USDT");
            assertThat(core.runtimeState().treasury().insurance(assetId)).isEqualTo(250L);
            // 查询前的账户所有权交接计数，异步读取后应保持不变。
            long handoff = com.surprising.aeron.service.core.AccountLaneTestAccess.handoffEpoch(core.runtimeState());
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
            assertThat(com.surprising.aeron.service.core.AccountLaneTestAccess.handoffEpoch(core.runtimeState())).isEqualTo(handoff);
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
        var instrument = CoreInstrument.from(LINE, new RegisterInstrumentCommand(
                "1", "BTC", "USDT", "USDT", 1, 1, 1,
                new CoreInstrumentRiskLimits(
                        100_000, (1_000_000_000_000L / (100_000)), Long.MAX_VALUE, 0, Long.MAX_VALUE,
                        java.util.List.of(new CoreRiskLimitBracket(Long.MAX_VALUE, (1_000_000_000_000L / (100_000)), 100_000, 50_000))),
                0, 0, 0, -1, 0));
        // 含不同价格序号的执行计划及两笔待 ADL 缺口，用来检查资格筛选和游标推进。
        var liquidations = Map.of(
                1L, new CoreLiquidationState(1, 1001, "1", 1, 1, 0, CoreLiquidationState.Status.PLANNED),
                2L, new CoreLiquidationState(2, 1002, "1", 2, 1, 0, CoreLiquidationState.Status.PLANNED),
                3L, new CoreLiquidationState(3, 1003, "1", 1, 1, 70, CoreLiquidationState.Status.ADL_REQUIRED),
                4L, new CoreLiquidationState(4, 1004, "1", 1, 1, 30, CoreLiquidationState.Status.ADL_REQUIRED),
                5L, new CoreLiquidationState(5, 1005, "1", 1, 1, 0, CoreLiquidationState.Status.PLANNED));
        var cursors = new java.util.ArrayList<>(java.util.Collections.nCopies(
                com.surprising.aeron.config.CoreConfiguration.standaloneLaneTopology().accountLaneCount(),
                new com.surprising.aeron.model.RiskLaneProgress(0, false, 0, 0, "-", 0, 0, 0, 0, 0)));
        cursors.set(0, new com.surprising.aeron.model.RiskLaneProgress(999, false, 0, 0, "-", 0, 0, 0, 0, 0));
        // 含五个用户、当前标记价、强平计划和风险扫描游标的完整参照状态。
        var state = new TradingCoreState(LINE, 0,
                Map.of(1001L, new CoreUserState(LINE, 1001, 0, java.util.Map.of(), java.util.Map.of(), java.util.Map.of(), com.surprising.aeron.protocol.CorePositionMode.ONE_WAY), 1002L, new CoreUserState(LINE, 1002, 0, java.util.Map.of(), java.util.Map.of(), java.util.Map.of(), com.surprising.aeron.protocol.CorePositionMode.ONE_WAY),
                        1003L, new CoreUserState(LINE, 1003, 0, java.util.Map.of(), java.util.Map.of(), java.util.Map.of(), com.surprising.aeron.protocol.CorePositionMode.ONE_WAY), 1004L, new CoreUserState(LINE, 1004, 0, java.util.Map.of(), java.util.Map.of(), java.util.Map.of(), com.surprising.aeron.protocol.CorePositionMode.ONE_WAY),
                        1005L, new CoreUserState(LINE, 1005, 0, java.util.Map.of(), java.util.Map.of(), java.util.Map.of(), com.surprising.aeron.protocol.CorePositionMode.ONE_WAY)),
                Map.of(), Map.of("1", instrument), new CoreRiskState(
                Map.of("1", new CoreMarkPriceState("1", 100, 1, 1)), Map.of(), liquidations, Map.of("1", new CoreRiskState.RiskScan("1", 0, 1, 1, false, true, 0, 0, 0, 0, 0, 0, 0, 0, 0, cursors)), 6, CoreRiskState.defaultScanControl(), 0),
                CoreTreasuryState.empty(), java.util.Map.of(), java.util.Map.of(), java.util.Map.of(), java.util.Map.of());
        try (var core = coreWithInitialState(state)) {
            core.activate();
            // 查询前的账户所有权交接计数，异步读取后应保持不变。
            long handoff = com.surprising.aeron.service.core.AccountLaneTestAccess.handoffEpoch(core.runtimeState());
            try (/* 禁止 Owner 越界读取活动账户的守卫，保证页数据由所属 Lane 查询。 */ var guard = new OwnerAccountReadGuard(core.runtimeState())) {
                for (long /* 本轮页起点，覆盖第一页、中间游标和末尾之后。 */ cursor : new long[]{0, 1, 3, 5}) {
                    for (int /* 本轮允许的工作页编码字节预算，与单项条数限制一起生效。 */ maxBytes : new int[]{256, 1_024}) {
                        // 指定工作类型、游标、一项上限和字节预算的页查询条件。
                        var request = new CoreLiquidationWorkView.Query(LINE, purpose, cursor, 1, maxBytes);
                        // 固定夹具的预期内容与游标，独立于生产筛选及分页方法。
                        var expected = expectedWorkPage(purpose, cursor, maxBytes);
                        var response = query(core, CoreMessageType.LIQUIDATION_WORK_QUERY, CoreLiquidationWorkCodec.encodeQuery(request));
                        assertThat(response.data()).isEqualTo(CoreLiquidationWorkCodec.encodeWork(expected));
                    }
                }
            }
            assertThat(com.surprising.aeron.service.core.AccountLaneTestAccess.handoffEpoch(core.runtimeState())).isEqualTo(handoff);
            core.assertClusterCallbackComplete();
        }
    }

    /** 固定夹具中 EXECUTION 仅 1/5 有效、ADL 仅 3/4 有效；显式列出分页游标。 */
    private static CoreLiquidationWorkView expectedWorkPage(CoreLiquidationWorkView.Purpose purpose,
            long cursor, int maxBytes) {
        boolean execution = purpose == CoreLiquidationWorkView.Purpose.EXECUTION;
        var continuation = execution ? new com.surprising.aeron.protocol.CoreRiskScanContinuation("1", 1, 999) : null;
        long selected = execution ? cursor < 1 ? 1 : cursor < 5 ? 5 : 0
                : cursor < 3 ? 3 : cursor < 4 ? 4 : 0;
        long next = selected == 1 ? 4 : selected == 3 ? 3 : 5;
        var actions = selected != 0 && execution
                ? List.of(new com.surprising.aeron.protocol.CoreLiquidationActionView(selected, 1000 + selected,
                        "1", CoreMarginMode.CROSS, CorePositionSide.NET, 1, 1, 1, 100))
                : List.<com.surprising.aeron.protocol.CoreLiquidationActionView>of();
        var resolutions = selected != 0 && !execution
                ? List.of(new CoreLiquidationWorkView.Resolution(selected, 1000 + selected, "1", "USDT",
                        CoreMarginMode.CROSS, CorePositionSide.NET, 1, 1, selected == 3 ? 70 : 30, 0, purpose))
                : List.<CoreLiquidationWorkView.Resolution>of();
        var expected = new CoreLiquidationWorkView(LINE, next, selected != 1 && selected != 3,
                continuation, actions, resolutions);
        if (CoreLiquidationWorkCodec.encodeWork(expected).length <= maxBytes) return expected;
        long skipped = execution ? selected == 1 ? cursor : 4 : selected == 3 ? 2 : cursor;
        return new CoreLiquidationWorkView(LINE, skipped, false, continuation, List.of(), List.of());
    }

    /**
     * 两笔 70 和 30 的待赔缺口共用 25 的保险余额，即使每页只取一项，也应按全部请求得到 18 和 7 的建议；查询不实际支付保险基金。
     */
    @Test
    void insurancePageAllocatesWholeBudgetIncludingClaimsOutsidePageWithoutAccountReads() throws Exception {
        // 所有本用例强平记录对应的线性永续合约 1。
        var instrument = CoreInstrument.from(LINE, new RegisterInstrumentCommand(
                "1", "BTC", "USDT", "USDT", 1, 1, 1,
                new CoreInstrumentRiskLimits(
                        100_000, (1_000_000_000_000L / (100_000)), Long.MAX_VALUE, 0, Long.MAX_VALUE,
                        java.util.List.of(new CoreRiskLimitBracket(Long.MAX_VALUE, (1_000_000_000_000L / (100_000)), 100_000, 50_000))),
                0, 0, 0, -1, 0));
        // 用户 1001 的首笔待保险赔付计划，缺口为 70。
        var first = new CoreLiquidationState(1, 1001, "1", 1, 1, 70, CoreLiquidationState.Status.INSURANCE_REQUIRED);
        // 用户 1002 的第二笔待保险赔付计划，缺口为 30。
        var second = new CoreLiquidationState(2, 1002, "1", 1, 1, 30, CoreLiquidationState.Status.INSURANCE_REQUIRED);
        // 两笔总缺口 100 的保险请求和 25 单位基金余额，分页不能只按页内请求分配。
        var state = new TradingCoreState(LINE, 0,
                Map.of(1001L, new CoreUserState(LINE, 1001, 0, java.util.Map.of(), java.util.Map.of(), java.util.Map.of(), com.surprising.aeron.protocol.CorePositionMode.ONE_WAY), 1002L, new CoreUserState(LINE, 1002, 0, java.util.Map.of(), java.util.Map.of(), java.util.Map.of(), com.surprising.aeron.protocol.CorePositionMode.ONE_WAY)),
                Map.of(), Map.of("1", instrument), new CoreRiskState(Map.of(), Map.of(), Map.of(1L, first, 2L, second), Map.of(), 3, CoreRiskState.defaultScanControl(), 0),
                new CoreTreasuryState(Map.of(), Map.of("USDT", 25L), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), java.util.Map.of(), java.util.Map.of(), java.util.Map.of(), java.util.Map.of()), java.util.Map.of(), java.util.Map.of(), java.util.Map.of(), java.util.Map.of());
        try (/* 本用例激活的核心运行时，结束时关闭其账本和账户工作资源。 */ var core = coreWithInitialState(state)) {
            core.activate();
            // 查询前的账户所有权交接计数，异步读取后应保持不变。
            long handoff = com.surprising.aeron.service.core.AccountLaneTestAccess.handoffEpoch(core.runtimeState());
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
            assertThat(com.surprising.aeron.service.core.AccountLaneTestAccess.handoffEpoch(core.runtimeState())).isEqualTo(handoff);
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
                new CoreTreasuryState(fees, Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), java.util.Map.of(), java.util.Map.of(), java.util.Map.of(), java.util.Map.of()), java.util.Map.of(), java.util.Map.of(), java.util.Map.of(), java.util.Map.of());
        try (/* 本用例激活的核心运行时，结束时关闭其账本和账户工作资源。 */ var core = coreWithInitialState(state)) {
            core.activate();
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
            assertThatThrownBy(() -> core.runtimeState().queryLedgerAtRest(/* 冷查询回调取得的账本，此处主动抛出异常来检查失败传播和后续查询可用性。 */ ledger -> {
                throw new IllegalArgumentException("invalid cold query");
            })).isInstanceOf(IllegalArgumentException.class).hasMessage("invalid cold query");
            assertThat(CoreStateQueryCodec.decodeTreasuryState(query(core, CoreMessageType.TREASURY_STATE_QUERY, new byte[0]).data()))
                    .isEmpty();
            core.assertClusterCallbackComplete();
        }
    }

    /**
     * 强平执行页查询直接完成，核对结果可消费且不残留待决编号。
     */
    @Test
    void liquidationWorkQueryExecutesAndReturnsResult() {
        try (/* 本用例激活的核心运行时，结束时关闭其账本和账户工作资源。 */ var core = new TradingCoreRuntime(LINE)) {
            core.activate();
            // 固定的一项强平执行页查询，两次请求使用相同载荷但不同命令编号。
            var payload = CoreLiquidationWorkCodec.encodeQuery(new CoreLiquidationWorkView.Query(
                    LINE, CoreLiquidationWorkView.Purpose.EXECUTION, 0, 1, 1_024));
            var first = message(CoreMessageType.LIQUIDATION_WORK_QUERY, payload);
            CoreTestCompletion.applyAsynchronously(core, first);
            var id = first.header().commandId();
            CoreResponse response;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while ((response = core.bookQueries.takeQueryResult(id)) == null) {
                core.assertHealthy();
                if (System.nanoTime() >= deadline) throw new AssertionError("query timed out");
                Thread.onSpinWait();
            }
            assertThat(CoreLiquidationWorkCodec.decodeWork(response.data()).complete()).isTrue();
            assertThat(core.bookQueries.takeQueryResult(id)).isNull();
            assertThat(core.bookQueries.hasPendingQuery(first.header().commandId())).isFalse();
            core.assertHealthy();
        }
    }

    /**
     * 提交一笔尚未消费的查询后关闭核心，核对关闭后账本停止、查询编号与结果表清空；本用例直接断言的是关闭后的状态。
     */
    @Test
    void closeClearsUnconsumedAccountLaneQueryResults() {
        // 本用例激活的核心运行时，结束时关闭其账本和账户工作资源。
        var core = new TradingCoreRuntime(LINE);
        try {
            core.activate();
            // 已经构造的查询消息，用其命令 UUID 核对内部查询关联。
            var message = message(CoreMessageType.LIQUIDATION_WORK_QUERY,
                    CoreLiquidationWorkCodec.encodeQuery(new CoreLiquidationWorkView.Query(
                            LINE, CoreLiquidationWorkView.Purpose.EXECUTION, 0, 1, 1024)));
            assertThat(CoreTestCompletion.applyAsynchronously(core, message).resultCode()).isEqualTo(CoreResultCode.MATCHING_PENDING);
            assertThat(core.bookQueries.hasPendingQuery(message.header().commandId())).isTrue();
        } finally { core.close(); }
        assertThat(core.bookQueries.hasPendingQueries()).isFalse();
    }

    /**
     * 把给定业务状态装入尚未激活的核心运行时，让每个测试自行控制启动和关闭。
     * @param state 预先准备的用户、风险计划和财库业务状态
     */
    private TradingCoreRuntime coreWithInitialState(TradingCoreState state) {
        return new TradingCoreRuntime(LINE, 0, new LinkedHashMap<>(), new LinkedHashMap<>(), state,
                new TerminalStateRetention(), null, 0, Map.of(), Map.of());
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
        if (type != CoreMessageType.LIQUIDATION_WORK_QUERY) {
            assertThat(response.resultCode()).isNotEqualTo(CoreResultCode.MATCHING_PENDING);
            assertThat(core.bookQueries.hasPendingQuery(message.header().commandId())).isFalse();
            assertThat(core.bookQueries.takeQueryResult(message.header().commandId())).isNull();
            return response;
        }
        assertThat(response.resultCode()).isEqualTo(CoreResultCode.MATCHING_PENDING);
        // 本次查询对应的内部异步编号，完成后只能取一次结果。
        var id = message.header().commandId();
        assertThat(core.bookQueries.hasPendingQuery(id)).isTrue();
        // 异步账本查询的五秒单调时钟等待上限，不参与业务定序。
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while ((response = core.bookQueries.takeQueryResult(id)) == null) {
            core.assertHealthy();
            if (System.nanoTime() >= deadline) throw new AssertionError("Ledger query timeout");
            Thread.onSpinWait();
        }
        assertThat(core.bookQueries.takeQueryResult(id)).isNull();
        assertThat(core.bookQueries.hasPendingQuery(message.header().commandId())).isFalse();
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
