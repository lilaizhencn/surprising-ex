package com.surprising.aeron.service.core;

import com.surprising.aeron.protocol.*;
import com.surprising.aeron.service.order.CoreTestCompletion;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.assertj.core.api.Assertions.*;

/**
 * 验证两种永续产品的在线资金费分页、命令重复处理、账户及账本完成边界和内存快照续接。测试通过运行时及 Owner 读取守卫观察结果，不启动真实 Aeron Cluster，也不把两实例状态对照当成独立资金费公式验证。
 */
class AsyncFundingCommandTest {
    /** 本测试实例递增的命令源序号，用来构造不同 UUID 与日志位置。 */
    private long sequence;
    /** 本测试所有业务输入使用的固定毫秒时间戳。 */
    private static final long TIME = 1_700_000_000_000L;

    /**
     * 构造八个用户的相向成交，每次只结算一个用户，核对游标、未完成数量、重复命令不再推进修订号以及测试资金总量；完成后再核对下一编号反向费率可继续执行。
     * @param line 参数化测试当前使用的 U 本位或币本位永续产品线
     */
    @ParameterizedTest
    @EnumSource(value = ProductLine.class, names = {"LINEAR_PERPETUAL", "INVERSE_PERPETUAL"})
    void fundingPagesUsePublishedProgressAndCompleteOnceWithoutOwnerLaneHandoff(ProductLine line) throws Exception {
        try (/* 本次场景创建并在结束时关闭的交易运行时。 */ var state = new TradingCoreRuntime(line)) {
            // 当前产品线对应的合约类型，决定资产和名义金额换算参数。
            var type = ContractType.valueOf(line.contractTypeCode());
            // 币本位使用 BTC，U 本位使用 USDT。
            String asset = type.isInverse() ? "BTC" : "USDT";
            applied(state, message(line, CoreMessageType.REGISTER_INSTRUMENT, 0,
                    TradingCommandCodec.encodeRegisterInstrument(new RegisterInstrumentCommand(
                            "1", "BTC", "USDT", asset, 1, 1, type.isInverse() ? 1000 : 1,
                            (type == ContractType.SPOT ? null : new CoreInstrumentRiskLimits(
                                    100_000, (1_000_000_000_000L / (100_000)), Long.MAX_VALUE, 0, Long.MAX_VALUE,
                                    java.util.List.of(new CoreRiskLimitBracket(Long.MAX_VALUE, (1_000_000_000_000L / (100_000)), 100_000, 50_000)))),
                            0, 0, 0, -1, 0), line)));
            // 首轮即使游标也错误，仍先拒绝缺失行情。
            var missingMark = CoreTestCompletion.applyAsynchronously(state, message(line,
                    CoreMessageType.APPLY_FUNDING, 0, TradingCommandCodec.encodeApplyFunding(
                            new ApplyFundingCommand(900, "1", 10_000, 1, 1))));
            assertThat(missingMark.commandStatus()).isEqualTo(ResponseStatus.REJECTED);
            assertThat(missingMark.resultCode().name()).isEqualTo("MARK_PRICE_NOT_FOUND");
            applied(state, message(line, CoreMessageType.APPLY_MARK_PRICE, 0,
                    TradingCommandCodec.encodeApplyMarkPrice(new ApplyMarkPriceCommand("1", 100, 1, TIME), line)));
            for (/* 正在初始化余额并提交相向订单的用户编号。 */ long user = 1; user <= 8; user++) {
                applied(state, message(line, CoreMessageType.ADJUST_BALANCE, user,
                        TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand(asset, 100_000))));
                applied(state, message(line, CoreMessageType.PLACE_ORDER, user,
                        TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(100 + user, "1",
                                user % 2 == 1 ? CoreOrderSide.SELL : CoreOrderSide.BUY, 100, 10, false,
                                CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT,
                                CoreTimeInForce.GTC, false, "funding-" + user))));
            }
            // 资金费执行前按本测试口径计算的经济总量，作为前后参照。
            long funds = economicUnits(state, asset);
            // 当前一轮资金费的全局用户游标。
            long cursor = 0;
            // 测试币对的内部编号。
            int symbolId = state.identities.symbolId("1");
            // 从零开始的分页循环下标，最后一次第八页的下标应为七。
            int pages = 0;
            for (; pages < 8; pages++) {
                // 本页每次最多结算一个用户的原始资金费命令。
                var command = message(line, CoreMessageType.APPLY_FUNDING, 0,
                        TradingCommandCodec.encodeApplyFunding(new ApplyFundingCommand(900, "1", 10_000, cursor, 1)));
                fundingWithPendingBoundaryChecks(state, command);
                assertThat(economicUnits(state, asset)).isEqualTo(funds);
                // 重发相同命令前的修订号，用于检查重复提交没有再写状态。
                long revision = state.runtimeState.revision();
                assertThat(CoreTestCompletion.applyAsynchronously(state, command).status()).isEqualTo(ResponseStatus.DUPLICATE);
                assertThat(state.runtimeState.revision()).isEqualTo(revision);
                // 账本已发布的未完成资金费进度，空表示整轮已经结束。
                var progress = state.runtimeState.treasury().fundingProgress(symbolId);
                if (progress == null) break;
                assertThat(progress.nextCursorUserId()).isGreaterThan(cursor);
                assertThat(progress.settlementId()).isEqualTo(900);
                assertThat(state.runtimeState.treasury().incompleteFundingCount()).isEqualTo(1);
                cursor = progress.nextCursorUserId();
                if (pages == 0) {
                    // 续页只能使用首轮固定的100，不能跟随新行情200。
                    applied(state, message(line, CoreMessageType.APPLY_MARK_PRICE, 0,
                            TradingCommandCodec.encodeApplyMarkPrice(
                                    new ApplyMarkPriceCommand("1", 200, 2, TIME), line)));
                } else if (pages == 1) {
                    // 模拟恢复后行情尚未补齐；进度已保存完整的资金费计算输入。
                    state.runtimeState.markPrices.removeKey(symbolId);
                    for (var invalid : java.util.List.of(
                            new ApplyFundingCommand(901, "1", 10_000, cursor, 1),
                            new ApplyFundingCommand(900, "1", 20_000, cursor, 1),
                            new ApplyFundingCommand(900, "1", 10_000, cursor + 1, 1))) {
                        var rejected = CoreTestCompletion.applyAsynchronously(state, message(line,
                                CoreMessageType.APPLY_FUNDING, 0, TradingCommandCodec.encodeApplyFunding(invalid)));
                        assertThat(rejected.commandStatus()).isEqualTo(ResponseStatus.REJECTED);
                        assertThat(rejected.resultCode().name()).isEqualTo("INVALID_COMMAND");
                    }
                    var original = state.runtimeState.instrument("1");
                    state.runtimeState.instruments.put(original.instrumentIdValue(), original.captureForQuery());
                    try {
                        var validPage = new ApplyFundingCommand(900, "1", 10_000, cursor, 1);
                        assertThatThrownBy(() -> com.surprising.aeron.service.funding.RuntimePerpetualFundingProcessor
                                .prepare(null, validPage, java.util.List.of(), state.runtimeState, state.identities))
                                .isInstanceOf(com.surprising.aeron.exception.CoreStateRejectedException.class)
                                .hasMessage("funding cursor does not match progress");
                    } finally {
                        state.runtimeState.instruments.put(original.instrumentIdValue(), original);
                    }
                    // 拒绝命令会记录幂等结果，但不能改变业务状态。
                    assertThat(state.runtimeState.treasury().fundingProgress(symbolId)).isEqualTo(progress);
                }
            }
            assertThat(pages).isEqualTo(7);
            assertThat(state.runtimeState.treasury().fundingSettlement(symbolId)).isEqualTo(900);
            assertThat(state.runtimeState.treasury().incompleteFundingCount()).isZero();
            long payment = type.isInverse() ? 1 : 10;
            for (long user = 1; user <= 8; user++) {
                assertThat(state.tradingState().users().get(user).totalUnits(asset))
                        .isEqualTo(100_000 + (user % 2 == 1 ? payment : -payment));
            }
            applied(state, message(line, CoreMessageType.APPLY_MARK_PRICE, 0,
                    TradingCommandCodec.encodeApplyMarkPrice(new ApplyMarkPriceCommand("1", 100, 3, TIME), line)));
            // 下一结算编号采用反向费率的命令。
            var next = message(line, CoreMessageType.APPLY_FUNDING, 0,
                    TradingCommandCodec.encodeApplyFunding(new ApplyFundingCommand(901, "1", -10_000)));
            fundingWithPendingBoundaryChecks(state, next);
            assertThat(state.runtimeState.treasury().fundingSettlement(symbolId)).isEqualTo(901);
            assertThat(economicUnits(state, asset)).isEqualTo(funds);
        }
    }

    /**
     * 对原实例和内存快照恢复实例发送同一页命令，核对响应、业务哈希及重复命令不变，完成后再从第二份快照续接下一轮。两边均调用同一异步完成辅助入口；变量名 sync 本身不构成独立同步算法或真实集群恢复证据。
     * @param line 参数化测试当前使用的 U 本位或币本位永续产品线
     */
    @ParameterizedTest
    @EnumSource(value = ProductLine.class, names = {"LINEAR_PERPETUAL", "INVERSE_PERPETUAL"})
    void fundingPagesMatchSynchronousLedgerAndContinueAfterSnapshot(ProductLine line) throws Exception {
        try (/* 本次场景创建并在结束时关闭的交易运行时。 */ var state = new TradingCoreRuntime(line)) {
            // 当前产品线对应的合约类型，决定资产和名义金额换算参数。
            var type = ContractType.valueOf(line.contractTypeCode());
            // 币本位使用 BTC，U 本位使用 USDT。
            String asset = type.isInverse() ? "BTC" : "USDT";
            applied(state, message(line, CoreMessageType.REGISTER_INSTRUMENT, 0,
                    TradingCommandCodec.encodeRegisterInstrument(new RegisterInstrumentCommand(
                            "1", "BTC", "USDT", asset, 1, 1, type.isInverse() ? 1000 : 1,
                            (type == ContractType.SPOT ? null : new CoreInstrumentRiskLimits(
                                    100_000, (1_000_000_000_000L / (100_000)), Long.MAX_VALUE, 0, Long.MAX_VALUE,
                                    java.util.List.of(new CoreRiskLimitBracket(Long.MAX_VALUE, (1_000_000_000_000L / (100_000)), 100_000, 50_000)))),
                            0, 0, 0, -1, 0), line)));
            applied(state, message(line, CoreMessageType.APPLY_MARK_PRICE, 0,
                    TradingCommandCodec.encodeApplyMarkPrice(new ApplyMarkPriceCommand("1", 100, 1, TIME), line)));
            for (/* 正在初始化余额并提交相向订单的用户编号。 */ long user = 1; user <= 8; user++) {
                applied(state, message(line, CoreMessageType.ADJUST_BALANCE, user,
                        TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand(asset, 100_000))));
                applied(state, message(line, CoreMessageType.PLACE_ORDER, user,
                        TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(100 + user, "1",
                                user % 2 == 1 ? CoreOrderSide.SELL : CoreOrderSide.BUY, 100, 10, false,
                                CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT,
                                CoreTimeInForce.GTC, false, "funding-" + user))));
            }
            // 资金费执行前按本测试口径计算的经济总量，作为前后参照。
            long funds = economicUnits(state, asset);
            try (/* 从初始内存快照新建并在作用域结束时关闭的对照运行时。 */ var restored = TradingCoreRuntime.fromSnapshot(line, state.snapshot(100))) {
                // 当前一轮资金费的全局用户游标。
                long cursor = 0;
                for (/* 当前用户页的零起点下标。 */ int page = 0; page < 8; page++) {
                    // 本页每次最多结算一个用户的原始资金费命令。
                    var command = message(line, CoreMessageType.APPLY_FUNDING, 0,
                            TradingCommandCodec.encodeApplyFunding(new ApplyFundingCommand(900, "1", 10_000, cursor, 1)));
                    // 原实例执行本页得到的响应。
                    CoreResponse async = page == 0 ? fundingWithPendingBoundaryChecks(state, command) : applied(state, command);
                    // 快照恢复实例经相同异步完成辅助入口得到的响应，名称不代表独立同步公式。
                    CoreResponse sync = CoreTestCompletion.applyAsynchronously(restored, command);
                    assertThat(async.commandStatus()).isEqualTo(sync.commandStatus());
                    assertThat(async.resultCode()).isEqualTo(sync.resultCode());
                    assertThat(async.data()).isEqualTo(sync.data());
                    assertThat(state.tradingState().businessStateHash()).isEqualTo(restored.tradingState().businessStateHash());
                    assertThat(economicUnits(state, asset)).isEqualTo(funds);
                    // 重复命令之前保存的完整运行时哈希。
                    long hash = state.stateHash();
                    assertThat(CoreTestCompletion.applyAsynchronously(state, command).status()).isEqualTo(ResponseStatus.DUPLICATE);
                    assertThat(state.stateHash()).isEqualTo(hash);
                    // 账本已发布的未完成资金费进度，空表示整轮已经结束。
                    var progress = state.runtimeState.treasury().fundingProgress(state.identities.symbolId("1"));
                    if (progress == null) break;
                    cursor = progress.nextCursorUserId();
                    if (page == 0) {
                        state.runtimeState.markPrices.clear();
                        restored.runtimeState.markPrices.clear();
                        var cold = state.tradingState();
                        var risk = cold.riskState();
                        var noMark = new com.surprising.aeron.model.TradingCoreState(line, cold.revision(),
                                cold.users(), cold.orders(), cold.instruments(), new com.surprising.aeron.model.CoreRiskState(
                                        java.util.Map.of(), risk.snapshots(), risk.liquidations(), risk.scans(),
                                        risk.nextLiquidationId(), risk.scanControl(), risk.marketRevision()),
                                cold.treasuryState(), cold.leverages(), cold.algoOrders(), cold.cancelAllAfterTimers(),
                                cold.clientOrderIndex(), cold.triggerOrders());
                        try (var source = new TradingCoreRuntime(line, 0, new java.util.LinkedHashMap<>(),
                                new java.util.LinkedHashMap<>(), noMark,
                                new com.surprising.aeron.service.retention.TerminalStateRetention(), null, 0,
                                java.util.Map.of(), java.util.Map.of(), null, null);
                                var withoutMark = TradingCoreRuntime.fromSnapshot(line, source.snapshot(101))) {
                            assertThat(withoutMark.runtimeState.markPrice(withoutMark.identities.symbolId("1"))).isNull();
                            applied(withoutMark, message(line, CoreMessageType.APPLY_FUNDING, 0,
                                    TradingCommandCodec.encodeApplyFunding(
                                            new ApplyFundingCommand(900, "1", 10_000, cursor, 1))));
                            assertThat(withoutMark.runtimeState.treasury().fundingProgress(
                                    withoutMark.identities.symbolId("1")).markPriceTicks()).isEqualTo(100);
                            assertThat(economicUnits(withoutMark, asset)).isEqualTo(funds);
                        }
                    }
                }
                assertThat(state.tradingState().treasuryState().fundingSettlements()).containsEntry("1", 900L);
                applied(state, message(line, CoreMessageType.APPLY_MARK_PRICE, 0,
                        TradingCommandCodec.encodeApplyMarkPrice(new ApplyMarkPriceCommand("1", 100, 2, TIME), line)));
                try (/* 整轮结束后从新快照恢复的下一轮对照运行时。 */ var after = TradingCoreRuntime.fromSnapshot(line, state.snapshot(200))) {
                    assertThat(after.stateHash()).isEqualTo(state.stateHash());
                    // 下一结算编号采用反向费率的命令。
                    var next = message(line, CoreMessageType.APPLY_FUNDING, 0,
                            TradingCommandCodec.encodeApplyFunding(new ApplyFundingCommand(901, "1", -10_000)));
                    assertThat(applied(state, next).data()).isEqualTo(applied(after, next).data());
                    assertThat(state.tradingState().businessStateHash()).isEqualTo(after.tradingState().businessStateHash());
                    assertThat(economicUnits(state, asset)).isEqualTo(funds);
                }
            }
        }
    }

    /**
     * 释放 Owner 账户读取权限并进入异步作用域，用守卫检查命令挂起时不能接收下一条或提前结束 Cluster 回调；在五秒截止点内轮询完成，再反射确认没有重新取得账户读取权，最后释放本次作用域。
     * @param state 当前被检查的交易运行时
     * @param message 要等待完成或检查挂起边界的原命令
     */
    private static CoreResponse fundingWithPendingBoundaryChecks(TradingCoreRuntime state, CoreMessage message) throws Exception {
        // 本辅助器轮询完成的五秒纳秒截止点。
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        state.runtimeState.releaseOwnerLaneAccess();

        try (/* 本次异步期间检查 Owner 未直接读取账户的可关闭守卫。 */ var guard = new OwnerAccountReadGuard(state.runtimeState)) {
            assertThat(state.applyDecodedCommand(message, TIME, message.header().sourceSequence(), null, false)).isNull();
            assertThat(state.directCommand.active()).isTrue();
            assertThatThrownBy(() -> state.apply(message)).hasMessageContaining("still active");
            assertThatThrownBy(state::assertClusterCallbackComplete).hasMessageContaining("unfinished business");
            // 当前等待到的完整命令响应；未完成时为 null。
            CoreResponse response;
            while ((response = state.directCommand.poll()) == null) {
                if (System.nanoTime() >= deadline) throw new AssertionError("funding timed out");
                Thread.onSpinWait();
            }
            assertThat(response.commandStatus()).isEqualTo(ResponseStatus.APPLIED);
            assertThat(state.directCommand.active()).isFalse();
            // 反射取得的 ownerLaneAccess 字段，用来核对最终没有重新授权读取。
            assertThat(state.runtimeState.ownerLaneAccess()).isFalse();
            return response;
        } finally {

            state.runtimeState.releaseOwnerLaneAccess();
        }
    }

    /**
     * 按此资金费用例求用户总额，加手续费、保险、强平费、资金费及舍入剩余，减保险亏空。只用于该场景的前后守恒对照，没有汇总清算盈亏子账，不是通用财务对账器。
     * @param runtime 需要导出状态并合计测试资金的交易运行时
     * @param asset 当前产品的结算资产
     */
    private static long economicUnits(TradingCoreRuntime runtime, String asset) {
        // 为测试资金合计取得的只读交易状态视图。
        var state = runtime.tradingState();
        // 按本测试明确列出的用户和财库子账精确累计的总额。
        long total = 0;
        for (var /* 合计其可用与冻结总额的用户状态。 */ user : state.users().values()) total = Math.addExact(total, user.totalUnits(asset));
        // 当前测试状态的财库视图。
        var treasury = state.treasuryState();
        total = Math.addExact(total, treasury.feeBalances().getOrDefault(asset, 0L));
        total = Math.addExact(total, treasury.insuranceBalances().getOrDefault(asset, 0L));
        total = Math.subtractExact(total, treasury.insuranceDeficits().getOrDefault(asset, 0L));
        total = Math.addExact(total, treasury.liquidationFeeBalances().getOrDefault(asset, 0L));
        total = Math.addExact(total, treasury.fundingResidualBalances().getOrDefault(asset, 0L));
        return Math.addExact(total, treasury.roundingResidualBalances().getOrDefault(asset, 0L));
    }

    /**
     * 递增本测试源序号，构造 UUID 和序号稳定对应的本产品线运营命令。
     * @param line 参数化测试当前使用的 U 本位或币本位永续产品线
     * @param type 待构造命令的协议类型
     * @param user 命令用户编号，系统业务使用零
     * @param data 已经编码的命令业务载荷
     */
    private CoreMessage message(ProductLine line, CoreMessageType type, long user, byte[] data) {
        // 本次递增后的源序号，同时写入 UUID 和请求头。
        long seq = ++sequence;
        return new CoreMessage(CoreMessageHeader.command(type, new UUID(91, seq), line,
                CommandSource.OPERATIONS, 871, seq, user, TIME, seq), data);
    }
    /**
     * 通过原测试辅助器等待异步命令完成，并要求业务命令状态为已应用。
     * @param state 当前被检查的交易运行时
     * @param message 要等待完成或检查挂起边界的原命令
     */
    private CoreResponse applied(TradingCoreRuntime state, CoreMessage message) {
        // 当前等待到的完整命令响应；未完成时为 null。
        var response = CoreTestCompletion.applyAsynchronously(state, message);
        assertThat(response.commandStatus()).as("%s %s", message.header().messageType(), response.resultCode())
                .isEqualTo(ResponseStatus.APPLIED);
        return response;
    }
}
