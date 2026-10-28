package com.surprising.aeron.service.settlement;

import com.surprising.aeron.protocol.CoreInstrumentRiskLimits;

import com.surprising.aeron.exception.CoreStateRejectedException;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreOrderType;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreRiskLimitBracket;
import com.surprising.aeron.protocol.CoreTimeInForce;
import com.surprising.aeron.protocol.RegisterInstrumentCommand;
import com.surprising.aeron.service.account.BalanceRuntime;
import com.surprising.aeron.service.account.UserRuntime;
import com.surprising.aeron.service.core.CoreStateTestFixtures;
import com.surprising.aeron.service.core.RuntimeSnapshotBuilder;
import com.surprising.aeron.service.core.TradingAccountRuntime;
import com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.aeron.service.order.state.OrderRuntime;
import com.surprising.aeron.service.position.PositionRuntime;
import com.surprising.aeron.service.snapshot.state.*;
import com.surprising.aeron.service.snapshot.state.TradingRuntimeSnapshot;
import com.surprising.aeron.service.treasury.RuntimeTreasuryDelta;
import com.surprising.product.api.ProductLine;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * U 本位永续成交金额与游标复用测试。直接在内存运行时核对开仓、部分平仓、反向、reduce-only、冻结解释及逐笔舍入；快照用于比较当前状态，不代表 Cluster 快照恢复，未发布游标的失败也不等同已发布账户回滚。
 */
class RuntimeDerivativeFillCalculatorTest {

    /**
     * 给单笔计算准备临时系统差额，显式指定用户所属 Lane 应用成交，再在当前测试所有权内应用到原 Treasury；不运行独立账本线程。
     * @param runtime 本用例在内存中操作的交易运行时
     * @param identities 本用例共用的资产、币对和持仓编号字典
     * @param instrument 本笔使用的规范永续币对
     * @param order 需要直接应用成交的运行时订单
     * @param positionKey 用户七的当前币对持仓身份键
     * @param price 本笔实际成交价刻度
     * @param quantity 本笔成交量或辅助方法构造的新订单总量
     * @param taker 是否按主动成交费率计算
     * @param leverage 本笔使用的百万分比杠杆
     * @param assetId 结算资产的运行时编号
     */
    private static void applyFill(TradingAccountRuntime runtime, RuntimeIdentityRegistry identities,
            CoreInstrument instrument, OrderRuntime order, long positionKey, long price, long quantity,
            boolean taker, long leverage, int assetId) {
        // 本笔直接成交记录系统费用和实际盈亏的临时差额，随后应用到测试财库。
        var treasury = new RuntimeTreasuryDelta();
        RuntimeDerivativeFillCalculator.apply(runtime, runtime.accountLaneForUser(order.userId()), identities,
                instrument, order, positionKey, price, quantity, taker, leverage, assetId, treasury, -1, -1);
        treasury.apply(runtime.treasury());
    }

    /**
     * 把四笔不同价卖出分别逐笔应用和游标聚合，核对聚合发布前运行时未变、发布后完整状态一致；进一步核对反向持仓、修订次数和累计成交价值四百四十。
     */
    @Test
    void takerCursorPreservesPerFillRoundingReversalAndRevisionCounts() {
        // 本用例共用的币对、资产及持仓身份字典。
        var identities = new RuntimeIdentityRegistry();
        // 本用例使用的 U 本位永续规范币对。
        var instrument = instrument();
        // 当前永续币对的稠密编号。
        // 逐笔与聚合对照用例中的结算资产编号。
        int instrumentId = identities.symbolId(instrument.instrumentId()), asset = identities.assetId(instrument.settleAsset());
        // 逐笔与聚合对照使用的用户七持仓身份键。
        long key = identities.positionKey(7, instrument.instrumentId());
        try (/* 逐笔写回成交的参照运行时，try 退出时关闭。 */ var sequential = runtimeWithPosition(instrumentId, asset, key, CoreOrderSide.SELL, 4, false, 10_000, 120, 100);
             /* 先在游标计算、最后统一写回的对照运行时，try 退出时关闭。 */ var batched = runtimeWithPosition(instrumentId, asset, key, CoreOrderSide.SELL, 4, false, 10_000, 120, 100)) {
            // 逐笔参照路径按原成交顺序记录的系统财务差额。
            var expectedTreasury = new RuntimeTreasuryDelta();
            // 游标路径每笔立即生成的系统财务差额，最后统一应用。
            var actualTreasury = new RuntimeTreasuryDelta();
            // 本用例独占并在 finally 清理的连续成交游标。
            var cursor = new RuntimeDerivativeFillCalculator.FillCursor();
            RuntimeDerivativeFillCalculator.begin(cursor, batched, batched.accountLaneForUser(7), instrument, batched.order(11),
                    key, 10_000_000, asset, 555, 999);
            // 本轮操作前捕获的内存状态快照，用来判断未发布计算是否泄漏到账户。
            var before = RuntimeSnapshotBuilder.capture(batched, 1);
            try {
                for (long /* 当前四笔成交序列中的实际价格刻度。 */ price : new long[]{111, 109, 113, 107}) {
                    RuntimeDerivativeFillCalculator.apply(sequential, sequential.accountLaneForUser(7), identities, instrument, sequential.order(11),
                            key, price, 1, true, 10_000_000, asset, expectedTreasury, 555, 999);
                    cursor.applyNext(price, 1, true, actualTreasury);
                }
                assertThat(RuntimeSnapshotBuilder.capture(batched, 1)).as("intermediate scalar state must not escape").isEqualTo(before);
                cursor.publish(batched, batched.accountLaneForUser(7), key, null);
            } finally { cursor.clear(); }
            expectedTreasury.apply(sequential.treasury());
            actualTreasury.apply(batched.treasury());
            assertThat(RuntimeSnapshotBuilder.capture(batched, 1)).isEqualTo(RuntimeSnapshotBuilder.capture(sequential, 1));
            assertThat(batched.position(key).signedQuantitySteps()).isEqualTo(-2);
            assertThat(batched.order(11).revision()).isEqualTo(5);
            assertThat(batched.order(11).executedValueHigh()).isZero();
            assertThat(batched.order(11).executedValueLow()).isEqualTo(440);
            assertThat(com.surprising.aeron.protocol.OrderExecutionValue.average(
                    batched.order(11).executedValueHigh(), batched.order(11).executedValueLow(), 4)).isEqualTo("110");
            assertThat(batched.user(7).revision()).isEqualTo(4);
        }
    }

    /**
     * 游标先算一笔再遇超量，未 publish 就清理并核对运行时快照不变；随后复用同游标合法成交，检查累计量和用户修订没有旧轮残留。
     */
    @Test
    void abortedTakerCursorDoesNotPublishPartialStateOrLeakIntoNextUse() {
        // 本用例共用的币对、资产及持仓身份字典。
        var identities = new RuntimeIdentityRegistry();
        // 本用例使用的 U 本位永续规范币对。
        var instrument = instrument();
        // 当前永续币对的稠密编号。
        // 逐笔与聚合对照用例中的结算资产编号。
        int instrumentId = identities.symbolId(instrument.instrumentId()), asset = identities.assetId(instrument.settleAsset());
        // 逐笔与聚合对照使用的用户七持仓身份键。
        long key = identities.positionKey(7, instrument.instrumentId());
        try (/* 本用例构造的内存运行时，用于直接调用计算器与读取账户结果。 */ var runtime = runtime(instrumentId, asset, 200)) {
            // 本轮操作前捕获的内存状态快照，用来判断未发布计算是否泄漏到账户。
            var before = RuntimeSnapshotBuilder.capture(runtime, 1);
            // 本用例独占并在 finally 清理的连续成交游标。
            var cursor = new RuntimeDerivativeFillCalculator.FillCursor();
            RuntimeDerivativeFillCalculator.begin(cursor, runtime, runtime.accountLaneForUser(7), instrument, runtime.order(11),
                    key, 10_000_000, asset, 555, 999);
            try {
                cursor.applyNext(100, 1, true, new RuntimeTreasuryDelta());
                assertThatThrownBy(() -> cursor.applyNext(100, 2, true, new RuntimeTreasuryDelta()))
                        .isInstanceOf(IllegalArgumentException.class);
            } finally { cursor.clear(); }
            assertThat(RuntimeSnapshotBuilder.capture(runtime, 1)).isEqualTo(before);
            RuntimeDerivativeFillCalculator.begin(cursor, runtime, runtime.accountLaneForUser(7), instrument, runtime.order(11),
                    key, 10_000_000, asset, 555, 999);
            try {
                cursor.applyNext(100, 2, true, new RuntimeTreasuryDelta());
                cursor.publish(runtime, runtime.accountLaneForUser(7), key, null);
            } finally { cursor.clear(); }
            assertThat(runtime.order(11).executedQuantitySteps()).isEqualTo(2);
            assertThat(runtime.user(7).revision()).isEqualTo(1);
        }
    }

    /**
     * 两单位买入开仓并释放终态预留，核对持仓保证金、可用冻结、手续费及订单终态；冻结金额由剩余预留加持仓保证金解释。
     */
    @Test
    void opensLinearPositionAndPreservesExplainedLockedFunds() {
        // 本用例共用的币对、资产及持仓身份字典。
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        // 本用例使用的 U 本位永续规范币对。
        CoreInstrument instrument = instrument();
        // 辅助运行时订单使用的币对稠密编号。
        int symbolId = identities.symbolId(instrument.instrumentId());
        // 本用例 USDT 结算资产编号。
        int assetId = identities.assetId(instrument.settleAsset());
        // 本用例用户七的当前合约持仓身份键。
        long positionKey = identities.positionKey(7, instrument.instrumentId());
        // 本用例构造的内存运行时，用于直接调用计算器与读取账户结果。
        TradingAccountRuntime runtime = runtime(symbolId, assetId, 200);
        // 本用例起点的两单位买入订单引用。
        OrderRuntime order = runtime.order(11);

        applyFill(runtime, identities, instrument, order,
                positionKey, 100, 2, true, 10_000_000, assetId);
        com.surprising.aeron.service.core.CoreStateTestFixtures.releaseRemainingReservation(runtime, 11);

        // 成交后读取的权威持仓值，用于核对数量、均价、盈亏和保证金。
        PositionRuntime position = runtime.position(positionKey);
        assertThat(position.signedQuantitySteps()).isEqualTo(2);
        assertThat(position.entryPriceTicks()).isEqualTo(100);
        assertThat(position.positionMarginUnits()).isEqualTo(20);
        assertThat(runtime.reservation(11).reservedUnits()).isZero();
        assertThat(runtime.balance(7, assetId).availableUnits()).isEqualTo(978);
        assertThat(runtime.balance(7, assetId).lockedUnits()).isEqualTo(20);
        assertThat(runtime.reservation(11).reservedUnits() + position.positionMarginUnits())
                .isEqualTo(runtime.balance(7, assetId).lockedUnits());
        assertThat(runtime.treasury().fee(assetId)).isEqualTo(2);
        assertThat(runtime.order(11).executedQuantitySteps()).isEqualTo(2);
        assertThat(runtime.order(11).canceled()).isTrue();
    }

    /**
     * 以二十一单位预留尝试需要更多资金的成交，检查业务拒绝且前后内存状态快照相同，只证明此输入下未写回。
     */
    @Test
    void rejectsInsufficientReservationBeforeAnyRuntimeMutation() {
        // 本用例共用的币对、资产及持仓身份字典。
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        // 本用例使用的 U 本位永续规范币对。
        CoreInstrument instrument = instrument();
        // 辅助运行时订单使用的币对稠密编号。
        int symbolId = identities.symbolId(instrument.instrumentId());
        // 本用例 USDT 结算资产编号。
        int assetId = identities.assetId(instrument.settleAsset());
        // 本用例用户七的当前合约持仓身份键。
        long positionKey = identities.positionKey(7, instrument.instrumentId());
        // 本用例构造的内存运行时，用于直接调用计算器与读取账户结果。
        TradingAccountRuntime runtime = runtime(symbolId, assetId, 21);
        // 本轮操作前捕获的内存状态快照，用来判断未发布计算是否泄漏到账户。
        TradingRuntimeSnapshot before = RuntimeSnapshotBuilder.capture(runtime, 1);

        assertThatThrownBy(() -> applyFill(runtime, identities, instrument,
                runtime.order(11), positionKey, 100, 2, true, 10_000_000, assetId))
                .isInstanceOf(CoreStateRejectedException.class);

        assertThat(RuntimeSnapshotBuilder.capture(runtime, 1)).isEqualTo(before);
    }

    /**
     * 从两单位多仓卖出一单位，核对剩余均价、十单位盈亏和保证金、两单位费用及反向系统清算差额。
     */
    @Test
    void partiallyClosesPositionAndRealizesProfit() {
        // 本用例共用的币对、资产及持仓身份字典。
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        // 本用例使用的 U 本位永续规范币对。
        CoreInstrument instrument = instrument();
        // 辅助运行时订单使用的币对稠密编号。
        int symbolId = identities.symbolId(instrument.instrumentId());
        // 本用例 USDT 结算资产编号。
        int assetId = identities.assetId(instrument.settleAsset());
        // 本用例用户七的当前合约持仓身份键。
        long positionKey = identities.positionKey(7, instrument.instrumentId());
        // 本用例构造的内存运行时，用于直接调用计算器与读取账户结果。
        TradingAccountRuntime runtime = runtimeWithPosition(symbolId, assetId, positionKey,
                CoreOrderSide.SELL, 1, false, 800, 120, 100);

        applyFill(runtime, identities, instrument, runtime.order(11),
                positionKey, 110, 1, true, 10_000_000, assetId);
        com.surprising.aeron.service.core.CoreStateTestFixtures.releaseRemainingReservation(runtime, 11);

        // 成交后读取的权威持仓值，用于核对数量、均价、盈亏和保证金。
        PositionRuntime position = runtime.position(positionKey);
        assertThat(position.signedQuantitySteps()).isEqualTo(1);
        assertThat(position.entryPriceTicks()).isEqualTo(100);
        assertThat(position.realizedPnlUnits()).isEqualTo(10);
        assertThat(position.positionMarginUnits()).isEqualTo(10);
        assertThat(runtime.balance(7, assetId).availableUnits()).isEqualTo(918);
        assertThat(runtime.balance(7, assetId).lockedUnits()).isEqualTo(10);
        assertThat(runtime.treasury().fee(assetId)).isEqualTo(2);
        assertThat(runtime.treasury().insurance(assetId)).isZero();
        assertThat(runtime.treasury().clearingPnl(assetId)).isEqualTo(-10);
        assertThat(runtime.treasury().insuranceDeficit(assetId)).isZero();
    }

    /**
     * 给已有两单位多仓增加一单位买入，检查只为新数量按成交价补保证金，最终保证金三十二且费用、余额正确。
     */
    @Test
    void addsMarginOnlyForTheNewQuantityAtItsFillPrice() {
        // 本用例共用的币对、资产及持仓身份字典。
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        // 本用例使用的 U 本位永续规范币对。
        CoreInstrument instrument = instrument();
        // 辅助运行时订单使用的币对稠密编号。
        int symbolId = identities.symbolId(instrument.instrumentId());
        // 本用例 USDT 结算资产编号。
        int assetId = identities.assetId(instrument.settleAsset());
        // 本用例用户七的当前合约持仓身份键。
        long positionKey = identities.positionKey(7, instrument.instrumentId());
        // 本用例构造的内存运行时，用于直接调用计算器与读取账户结果。
        TradingAccountRuntime runtime = runtimeWithPosition(symbolId, assetId, positionKey,
                CoreOrderSide.BUY, 1, false, 800, 40, 20);

        applyFill(runtime, identities, instrument, runtime.order(11),
                positionKey, 120, 1, true, 10_000_000, assetId);
        com.surprising.aeron.service.core.CoreStateTestFixtures.releaseRemainingReservation(runtime, 11);

        // 成交后读取的权威持仓值，用于核对数量、均价、盈亏和保证金。
        PositionRuntime position = runtime.position(positionKey);
        assertThat(position.signedQuantitySteps()).isEqualTo(3);
        assertThat(position.positionMarginUnits()).isEqualTo(32);
        assertThat(runtime.balance(7, assetId).availableUnits()).isEqualTo(806);
        assertThat(runtime.balance(7, assetId).lockedUnits()).isEqualTo(32);
        assertThat(runtime.treasury().fee(assetId)).isEqualTo(2);
    }

    /**
     * 从两单位多仓卖出三单位，核对反向一单位空仓按新价入场，旧仓已实现盈利、费用及系统清算差额正确。
     */
    @Test
    void reversesPositionAtFillPrice() {
        // 本用例共用的币对、资产及持仓身份字典。
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        // 本用例使用的 U 本位永续规范币对。
        CoreInstrument instrument = instrument();
        // 辅助运行时订单使用的币对稠密编号。
        int symbolId = identities.symbolId(instrument.instrumentId());
        // 本用例 USDT 结算资产编号。
        int assetId = identities.assetId(instrument.settleAsset());
        // 本用例用户七的当前合约持仓身份键。
        long positionKey = identities.positionKey(7, instrument.instrumentId());
        // 本用例构造的内存运行时，用于直接调用计算器与读取账户结果。
        TradingAccountRuntime runtime = runtimeWithPosition(symbolId, assetId, positionKey,
                CoreOrderSide.SELL, 3, false, 800, 220, 200);

        applyFill(runtime, identities, instrument, runtime.order(11),
                positionKey, 120, 3, true, 10_000_000, assetId);
        com.surprising.aeron.service.core.CoreStateTestFixtures.releaseRemainingReservation(runtime, 11);

        // 成交后读取的权威持仓值，用于核对数量、均价、盈亏和保证金。
        PositionRuntime position = runtime.position(positionKey);
        assertThat(position.signedQuantitySteps()).isEqualTo(-1);
        assertThat(position.entryPriceTicks()).isEqualTo(120);
        assertThat(position.realizedPnlUnits()).isEqualTo(40);
        assertThat(position.positionMarginUnits()).isEqualTo(12);
        assertThat(runtime.balance(7, assetId).availableUnits()).isEqualTo(1_044);
        assertThat(runtime.balance(7, assetId).lockedUnits()).isEqualTo(12);
        assertThat(runtime.treasury().fee(assetId)).isEqualTo(4);
        assertThat(runtime.treasury().insurance(assetId)).isZero();
        assertThat(runtime.treasury().clearingPnl(assetId)).isEqualTo(-40);
        assertThat(runtime.treasury().insuranceDeficit(assetId)).isZero();
    }

    /**
     * 对两单位多仓提交三单位 reduce-only 卖出成交，检查反向敞口被拒绝且内存状态快照保持不变。
     */
    @Test
    void rejectsReduceOnlyReversalWithoutMutation() {
        // 本用例共用的币对、资产及持仓身份字典。
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        // 本用例使用的 U 本位永续规范币对。
        CoreInstrument instrument = instrument();
        // 辅助运行时订单使用的币对稠密编号。
        int symbolId = identities.symbolId(instrument.instrumentId());
        // 本用例 USDT 结算资产编号。
        int assetId = identities.assetId(instrument.settleAsset());
        // 本用例用户七的当前合约持仓身份键。
        long positionKey = identities.positionKey(7, instrument.instrumentId());
        // 本用例构造的内存运行时，用于直接调用计算器与读取账户结果。
        TradingAccountRuntime runtime = runtimeWithPosition(symbolId, assetId, positionKey,
                CoreOrderSide.SELL, 3, true, 800, 220, 200);
        // 本轮操作前捕获的内存状态快照，用来判断未发布计算是否泄漏到账户。
        TradingRuntimeSnapshot before = RuntimeSnapshotBuilder.capture(runtime, 1);

        assertThatThrownBy(() -> applyFill(runtime, identities, instrument,
                runtime.order(11), positionKey, 120, 3, true, 10_000_000, assetId))
                .isInstanceOf(CoreStateRejectedException.class);
        assertThat(RuntimeSnapshotBuilder.capture(runtime, 1)).isEqualTo(before);
    }

    /**
     * 构造用户七、固定两单位买单及可用八百冻结二百的内存运行时，订单预留由参数指定。
     * @param symbolId 测试币对的稠密编号
     * @param assetId 结算资产的运行时编号
     * @param reservationUnits 测试订单起点可使用的预留金额
     */
    private static TradingAccountRuntime runtime(int symbolId, int assetId, long reservationUnits) {
        // 本用例构造的内存运行时，用于直接调用计算器与读取账户结果。
        TradingAccountRuntime runtime = new TradingAccountRuntime();
        runtime.putUser(new UserRuntime(7));
        runtime.putBalance(new BalanceRuntime(7, assetId, 800, 200));
        runtime.putOrder(new OrderRuntime(11, 7, symbolId, instrument(), CoreOrderSide.BUY, 100, false,
                CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT, CoreTimeInForce.GTC,
                0, 10_000, 2, 0, 2, false));
        runtime.putReservation(CoreStateTestFixtures.reservation(11, 7, assetId, reservationUnits));
        return runtime;
    }

    /**
     * 构造用户七已有两单位多仓、均价一百且保证金二十的内存运行时，再按参数建立余额和新订单预留。
     * @param symbolId 测试币对的稠密编号
     * @param assetId 结算资产的运行时编号
     * @param positionKey 用户七的当前币对持仓身份键
     * @param side 已有多仓上新订单的买卖方向
     * @param quantity 本笔成交量或辅助方法构造的新订单总量
     * @param reduceOnly 新订单是否只允许减仓
     * @param available 辅助运行时的起点可用余额
     * @param locked 辅助运行时的起点冻结余额
     * @param reservationUnits 测试订单起点可使用的预留金额
     */
    private static TradingAccountRuntime runtimeWithPosition(int symbolId, int assetId, long positionKey,
                                                           CoreOrderSide side, long quantity, boolean reduceOnly,
                                                           long available, long locked, long reservationUnits) {
        // 本用例构造的内存运行时，用于直接调用计算器与读取账户结果。
        TradingAccountRuntime runtime = new TradingAccountRuntime();
        runtime.putUser(new UserRuntime(7));
        runtime.putBalance(new BalanceRuntime(7, assetId, available, locked));
        runtime.putPosition(positionKey, new PositionRuntime(7, symbolId, assetId, CoreMarginMode.CROSS,
                CorePositionSide.NET, instrument(), 2, 100, 200, 0, 20));
        runtime.putOrder(new OrderRuntime(11, 7, symbolId, instrument(), side, 120, reduceOnly,
                CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT, CoreTimeInForce.GTC,
                0, 10_000, quantity, 0, quantity, false));
        runtime.putReservation(CoreStateTestFixtures.reservation(11, 7, assetId, reservationUnits));
        return runtime;
    }

    /**
     * 构造测试共用的 U 本位 BTC/USDT 永续币对、杠杆和风险档位。
     */
    private static CoreInstrument instrument() {
        return CoreInstrument.from(ProductLine.LINEAR_PERPETUAL,
                new RegisterInstrumentCommand(
                        "1", "BTC", "USDT", "USDT", 1, 1, 1,
                        new CoreInstrumentRiskLimits(
                                100_000, 10_000_000, 10_000, 0, 1,
                                List.of(new CoreRiskLimitBracket(10_000,
                                10_000_000, 100_000, 50_000))),
                        0, 0, 0, -1, 0));
    }
}
