package com.surprising.aeron.service.core;

import com.surprising.aeron.protocol.CoreRiskLimitBracket;

import com.surprising.aeron.protocol.CoreInstrumentRiskLimits;

import com.surprising.aeron.protocol.ApplyFundingCommand;
import com.surprising.aeron.protocol.ApplyMarkPriceCommand;
import com.surprising.aeron.protocol.BalanceAdjustmentCommand;
import com.surprising.aeron.protocol.CancelOrderCommand;
import com.surprising.aeron.protocol.CommandSource;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageHeader;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreOrderType;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.CoreTimeInForce;
import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.protocol.ProtocolException;
import com.surprising.aeron.protocol.RegisterInstrumentCommand;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.protocol.SettleInstrumentCommand;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.aeron.service.order.CoreTestCompletion;
import com.surprising.aeron.service.snapshot.CoreSnapshotManifest;
import com.surprising.aeron.service.snapshot.SectionedCoreSnapshotCodec;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 六产品线共用的快照恢复契约测试。通过注册、余额调整、挂单再撤单和产品生命周期命令，核对冻结解冻、资金统计、快照恢复、跨产品线拒绝及命令去重。场景没有产生实际成交持仓，资金费/到期分支主要验证标记与产品限制，不等于持仓结算公式覆盖。
 */
class SharedProductLineSnapshotContractTest {

    /** 所有产品线场景使用的固定用户编号。 */
    private static final long USER_ID = 701;
    /** 各产品线在对应结算资产上调整入账的测试金额。 */
    private static final long BALANCE_UNITS = 20_000;
    /** 各自独立产品线运行时使用的同一数字品种字符串。 */
    private static final String SYMBOL = "1";

    /**
     * 逐产品线建立一个有余额用户，挂单要求有冻结、撤单要求解冻且经济总额不变；执行相应生命周期后捕获，拒绝错误产品线恢复，并比较状态、业务/资金哈希、Lane 清单、来源水位与幂等结果。恢复后重放余额命令必须去重且状态哈希不变。
     * @param productLine 参数化场景当前使用的产品线
     */
    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void currentSnapshotRestoresEveryProductLineWithoutCrossLineOrFinancialDrift(ProductLine productLine) {
        // 当前测试的余额入账命令或资金费响应，所在方法明确对应业务。
        CoreMessage funding = gateway(productLine, 1, USER_ID, CoreMessageType.ADJUST_BALANCE,
                TradingCommandCodec.encodeBalanceAdjustment(
                        new BalanceAdjustmentCommand(settleAsset(productLine), BALANCE_UNITS)));
        try (/* 当前产品线的原始运行时，完成恢复比较后退出关闭。 */ TradingCoreRuntime original = new TradingCoreRuntime(productLine)) {
            assertApplied(applyTerminal(original, operations(productLine, 1, CoreMessageType.REGISTER_INSTRUMENT,
                    TradingCommandCodec.encodeRegisterInstrument(instrument(productLine), productLine))));
            if (productLine.isDerivative()) {
                assertApplied(applyTerminal(original, market(productLine, 1, CoreMessageType.APPLY_MARK_PRICE,
                        TradingCommandCodec.encodeApplyMarkPrice(
                                productLine == ProductLine.OPTION
                                        ? new ApplyMarkPriceCommand(SYMBOL, 100, 100, 100, 1,
                                        1_700_000_000_000L)
                                        : new ApplyMarkPriceCommand(SYMBOL, 100, 1,
                                        1_700_000_000_000L), productLine))));
                assertThat(original.tradingState().riskState().markPrices().get(SYMBOL).markPriceTicks())
                        .isEqualTo(100);
            }

            assertApplied(applyTerminal(original, funding));
            // 下单前所选结算资产的用户与系统账经济合计，用于撤单后比较。
            long economicBeforeOrder = economicAssetUnits(original, settleAsset(productLine));
            // 按产品线枚举偏移生成的本次挂单编号。
            long orderId = 10_000L + productLine.ordinal();
            assertApplied(applyTerminal(original, gateway(productLine, 2, USER_ID, CoreMessageType.PLACE_ORDER,
                    TradingCommandCodec.encodePlaceOrder(place(orderId)))));
            assertThat(original.tradingState().user(USER_ID).balances().get(settleAsset(productLine)).lockedUnits())
                    .as("%s reservation", productLine).isPositive();
            assertApplied(applyTerminal(original, gateway(productLine, 3, USER_ID, CoreMessageType.CANCEL_ORDER,
                    TradingCommandCodec.encodeCancelOrder(new CancelOrderCommand(orderId)))));
            assertThat(original.tradingState().user(USER_ID).balances().get(settleAsset(productLine)).lockedUnits())
                    .as("%s cancellation releases reservation", productLine).isZero();
            assertThat(economicAssetUnits(original, settleAsset(productLine))).isEqualTo(economicBeforeOrder);

            assertProductSpecificLifecycle(productLine, original);
            // 完成挂单撤单及生命周期命令后捕获的当前格式字节。
            byte[] snapshot = original.snapshot(800 + productLine.ordinal());
            // 完整解析快照得到的公开清单。
            CoreSnapshotManifest manifest = TradingCoreRuntime.inspectSnapshot(productLine, snapshot);
            // 保存快照时的已应用 Core 水位，恢复前后取相同 Lane 清单边界。
            long laneFence = original.appliedCommandCount();
            // 保存快照时的运行时状态哈希。
            long stateHash = original.stateHash();
            // 从物化交易状态计算的资金哈希。
            long fundsHash = com.surprising.aeron.service.snapshot.state.FundsStateHash.compute(
                    original.snapshotTradingState());
            // 快照前结算资产的经济合计。
            long economicUnits = economicAssetUnits(original, settleAsset(productLine));

            assertThat(manifest.productLine()).isEqualTo(productLine);
            assertThat(manifest.schemaVersion()).isEqualTo(SectionedCoreSnapshotCodec.VERSION);
            assertThatThrownBy(() -> TradingCoreRuntime.fromSnapshot(other(productLine), snapshot))
                    .isInstanceOf(ProtocolException.class)
                    .hasMessageContaining("product line");

            try (/* 从同产品线字节恢复的独立运行时，资源退出时关闭。 */ TradingCoreRuntime restored = TradingCoreRuntime.fromSnapshot(productLine, snapshot)) {
                assertThat(restored.productLine()).isEqualTo(productLine);
                assertThat(restored.tradingState()).isEqualTo(original.tradingState());
                assertThat(restored.stateHash()).isEqualTo(stateHash);
                assertThat(com.surprising.aeron.service.snapshot.state.FundsStateHash.compute(
                        restored.snapshotTradingState())).isEqualTo(fundsHash);
                assertThat(economicAssetUnits(restored, settleAsset(productLine))).isEqualTo(economicUnits);
                assertThat(restored.accountLaneSnapshots(laneFence, restored.tradingState()))
                        .isEqualTo(original.accountLaneSnapshots(laneFence, original.tradingState()));
                assertThat(restored.commandResults()).isEqualTo(original.commandResults());
                assertThat(restored.lastSourceSequences()).isEqualTo(original.lastSourceSequences());

                // 恢复后再次入账前的状态哈希，重放去重后不得变化。
                long hashBeforeDuplicate = restored.stateHash();
                // 重放相同入账命令得到的响应，要求 DUPLICATE。
                CoreResponse duplicate = restored.apply(funding);
                assertThat(duplicate.status()).isEqualTo(ResponseStatus.DUPLICATE);
                assertThat(restored.stateHash()).isEqualTo(hashBeforeDuplicate);
            }
        }
    }

    /**
     * 永续执行资金费并检查结算批次标记；现货跳过该验证；交割和期权要求资金费被产品限制拒绝，再执行到期结算并检查标记及结算后下单拒绝。此前挂单已撤且没有成交持仓，未验证真实持仓资金费或到期金额。
     * @param productLine 参数化场景当前使用的产品线
     * @param state 已应用相关测试命令的交易运行时
     */
    private static void assertProductSpecificLifecycle(ProductLine productLine, TradingCoreRuntime state) {
        if (productLine.isFundingProduct()) {
            // 当前测试的余额入账命令或资金费响应，所在方法明确对应业务。
            CoreResponse funding = applyTerminal(state, market(productLine, 2, CoreMessageType.APPLY_FUNDING,
                    TradingCommandCodec.encodeApplyFunding(new ApplyFundingCommand(
                            90_000L + productLine.ordinal(), SYMBOL, 10))));
            assertApplied(funding);
            assertThat(state.tradingState().treasuryState().fundingSettlements())
                    .containsEntry(SYMBOL, 90_000L + productLine.ordinal());
            return;
        }

        if (productLine == ProductLine.SPOT) return;

        // 当前测试的余额入账命令或资金费响应，所在方法明确对应业务。
        CoreResponse funding = state.apply(market(productLine, 2, CoreMessageType.APPLY_FUNDING,
                TradingCommandCodec.encodeApplyFunding(new ApplyFundingCommand(
                        90_000L + productLine.ordinal(), SYMBOL, 10))));
        assertThat(funding.status()).isEqualTo(ResponseStatus.REJECTED);
        assertThat(funding.resultCode()).isEqualTo(CoreResultCode.PRODUCT_LINE_UNSUPPORTED);
        if (!productLine.isDeliveryProduct()) return;

        // 按产品线偏移生成的到期结算批次标识。
        long settlementId = 91_000L + productLine.ordinal();
        // 到期结算推进到终态后的响应。
        CoreResponse settlement = applyTerminal(state, operations(productLine, 2, CoreMessageType.SETTLE_INSTRUMENT,
                TradingCommandCodec.encodeSettleInstrument(new SettleInstrumentCommand(settlementId, SYMBOL, 100))));
        assertApplied(settlement);
        assertThat(state.tradingState().treasuryState().lifecycleSettlements()).containsEntry(SYMBOL, settlementId);
        // 品种已结算后再次挂单的响应，要求 INSTRUMENT_SETTLED。
        CoreResponse rejectedAfterSettlement = applyTerminal(state, gateway(productLine, 4, USER_ID,
                CoreMessageType.PLACE_ORDER, TradingCommandCodec.encodePlaceOrder(place(
                        20_000L + productLine.ordinal()))));
        assertThat(rejectedAfterSettlement.status()).isEqualTo(ResponseStatus.REJECTED);
        assertThat(rejectedAfterSettlement.resultCode()).isEqualTo(CoreResultCode.INSTRUMENT_SETTLED);
    }

    /**
     * 按产品线选择合约类型和结算资产，交割/期权设置到期时间，反向设置面值，期权设置类型与行权价，生成单位价格/数量步长的测试品种。
     * @param productLine 参数化场景当前使用的产品线
     */
    private static RegisterInstrumentCommand instrument(ProductLine productLine) {
        // 当前产品线对应的合约类型，决定面值、到期与期权参数。
        ContractType type = ContractType.valueOf(productLine.contractTypeCode());
        // 交割/期权的固定到期时间，其余为零。
        long expiry = type.isDelivery() || type.isOption() ? 2_000_000_000_000L : 0;
        return new RegisterInstrumentCommand(
                SYMBOL, "BTC", "USDT", settleAsset(productLine), 1, 1, type.isInverse() ? 1_000 : 1,
                (type == ContractType.SPOT ? null : new CoreInstrumentRiskLimits(
                        100_000, (1_000_000_000_000L / (100_000)), Long.MAX_VALUE, 0, Long.MAX_VALUE,
                        java.util.List.of(new CoreRiskLimitBracket(Long.MAX_VALUE, (1_000_000_000_000L / (100_000)), 100_000, 50_000)))),
                0, 0, expiry, type.isOption() ? 0 : -1, type.isOption() ? 100 : 0);
    }

    /**
     * 创建买入一单位、价格一百的全仓净持仓 GTC 限价单；v17 字符串仅作历史命名的客户端键，不决定快照格式版本。
     * @param orderId 本次测试限价单的订单号
     */
    private static PlaceOrderCommand place(long orderId) {
        return new PlaceOrderCommand(orderId, SYMBOL, CoreOrderSide.BUY, 100, 1, false,
                CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT, CoreTimeInForce.GTC,
                false, "v17-" + orderId);
    }

    /**
     * 以固定 Gateway 来源实例七百零一构造带用户身份的业务命令。
     * @param productLine 参数化场景当前使用的产品线
     * @param sequence 该固定来源实例当前命令的单调序号
     * @param userId 命令所属用户编号
     * @param type 协议业务消息类型
     * @param payload 对应类型的已编码协议负载
     */
    private static CoreMessage gateway(ProductLine productLine, long sequence, long userId,
                                       CoreMessageType type, byte[] payload) {
        return message(productLine, CommandSource.GATEWAY, 701, sequence, userId, type, payload);
    }

    /**
     * 以固定 Operations 来源实例七百零二和用户零构造管理命令。
     * @param productLine 参数化场景当前使用的产品线
     * @param sequence 该固定来源实例当前命令的单调序号
     * @param type 协议业务消息类型
     * @param payload 对应类型的已编码协议负载
     */
    private static CoreMessage operations(ProductLine productLine, long sequence, CoreMessageType type,
                                          byte[] payload) {
        return message(productLine, CommandSource.OPERATIONS, 702, sequence, 0, type, payload);
    }

    /**
     * 以固定 Kafka 输入桥来源实例七百零三和用户零构造标记价或资金费命令。
     * @param productLine 参数化场景当前使用的产品线
     * @param sequence 该固定来源实例当前命令的单调序号
     * @param type 协议业务消息类型
     * @param payload 对应类型的已编码协议负载
     */
    private static CoreMessage market(ProductLine productLine, long sequence, CoreMessageType type, byte[] payload) {
        return message(productLine, CommandSource.KAFKA_INPUT_BRIDGE, 703, sequence, 0, type, payload);
    }

    /**
     * 按产品线、来源、源序号和消息类型生成可重现 UUID，结算命令使用到期业务时间，其余使用固定基准加源序号，再包装原协议负载。UUID 命名文本不含来源实例或用户号，本测试由固定来源辅助器约束组合。
     * @param productLine 参数化场景当前使用的产品线
     * @param source 协议命令来源类型
     * @param sourceId 固定来源实例编号
     * @param sourceSequence 当前来源消费序号，也参与生成确定性 UUID
     * @param userId 命令所属用户编号
     * @param type 协议业务消息类型
     * @param payload 对应类型的已编码协议负载
     */
    private static CoreMessage message(ProductLine productLine, CommandSource source, long sourceId,
                                       long sourceSequence, long userId, CoreMessageType type, byte[] payload) {
        // 由本测试固定输入文本生成的确定性命令 UUID。
        UUID commandId = UUID.nameUUIDFromBytes((productLine + ":" + source + ":" + sourceSequence + ":" + type)
                .getBytes(StandardCharsets.UTF_8));
        return new CoreMessage(CoreMessageHeader.command(type, commandId, productLine, source, sourceId,
                sourceSequence, userId, type == CoreMessageType.SETTLE_INSTRUMENT
                        ? 2_000_000_000_000L : 1_700_000_000_000L + sourceSequence, sourceSequence), payload);
    }

    /**
     * 先 apply，直接命令最多轮询五秒；非 MATCHING_PENDING 直接返回，否则按命令 UUID 找到撮合序号并由测试完成器推进到终态。
     * @param state 已应用相关测试命令的交易运行时
     * @param command 需要执行并推进到终态的完整 Core 命令
     */
    private static CoreResponse applyTerminal(TradingCoreRuntime state, CoreMessage command) {
        // 当前命令的 apply 或直接轮询返回值，撮合未完成时继续等待终态。
        CoreResponse response = state.apply(command);
        // 直接命令轮询的五秒 nanoTime 截止点。
        long directDeadline = System.nanoTime() + 5_000_000_000L;
        while (response == null && state.directCommand.active() && System.nanoTime() < directDeadline) {
            response = state.directCommand.poll();
            if (response == null) Thread.onSpinWait();
        }
        if (response == null && state.directCommand.active()) {
            throw new AssertionError("Direct command timed out: " + command.header().messageType());
        }
        if (response.resultCode() != CoreResultCode.MATCHING_PENDING) return response;
        // 命令 UUID 对应的已提交撮合序号。
        long matchingSequence = state.matchingSequence(command.header().commandId());
        return CoreTestCompletion.completeMatchingSynchronously(state, matchingSequence,
                command.header().submittedAtEpochMillis(), command.header().sourceSequence());
    }

    /**
     * 要求响应状态为 APPLIED，失败时展示业务结果码。
     * @param response 需要断言为 APPLIED 的响应
     */
    private static void assertApplied(CoreResponse response) {
        assertThat(response.status()).as("%s", response.resultCode()).isEqualTo(ResponseStatus.APPLIED);
    }

    /**
     * 取枚举数组中的下一个产品线，末尾绕回首项，生成明确不同的恢复预期。
     * @param productLine 参数化场景当前使用的产品线
     */
    private static ProductLine other(ProductLine productLine) {
        return ProductLine.values()[(productLine.ordinal() + 1) % ProductLine.values().length];
    }

    /**
     * 反向永续和反向交割用 BTC，其余产品线用 USDT，供本组余额与资金统计一致使用。
     * @param productLine 参数化场景当前使用的产品线
     */
    private static String settleAsset(ProductLine productLine) {
        return switch (productLine) {
            case INVERSE_PERPETUAL, INVERSE_DELIVERY -> "BTC";
            default -> "USDT";
        };
    }

    /**
     * 按单一资产精确累计所有用户可用加冻结，再加手续费、保险、强平费、资金费残差、舍入残差、清算盈亏并减保险缺口；不是跨资产估值或独立业务结算公式。
     * @param state 已应用相关测试命令的交易运行时
     * @param asset 需要累计经济总额的一种资产名称
     */
    private static long economicAssetUnits(TradingCoreRuntime state, String asset) {
        // 单资产用户与系统子账的精确累计值。
        long total = state.tradingState().users().values().stream()
                .map(/* 当前正在累加余额的用户状态。 */ user -> user.balances().get(asset))
                .filter(java.util.Objects::nonNull)
                .mapToLong(/* 该用户在目标资产上的非空余额，累加可用及冻结。 */ balance -> Math.addExact(balance.availableUnits(), balance.lockedUnits()))
                .reduce(0L, Math::addExact);
        // 包含本场景系统子账的财库只读状态。
        var treasury = state.tradingState().treasuryState();
        total = Math.addExact(total, treasury.feeBalances().getOrDefault(asset, 0L));
        total = Math.addExact(total, treasury.insuranceBalances().getOrDefault(asset, 0L));
        total = Math.subtractExact(total, treasury.insuranceDeficits().getOrDefault(asset, 0L));
        total = Math.addExact(total, treasury.liquidationFeeBalances().getOrDefault(asset, 0L));
        total = Math.addExact(total, treasury.fundingResidualBalances().getOrDefault(asset, 0L));
        total = Math.addExact(total, treasury.roundingResidualBalances().getOrDefault(asset, 0L));
        return Math.addExact(total, treasury.clearingPnlBalances().getOrDefault(asset, 0L));
    }
}
