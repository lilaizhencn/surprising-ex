package com.surprising.aeron.service.settlement;

import com.surprising.aeron.exception.CoreStateRejectedException;
import com.surprising.aeron.model.AssetBalance;
import com.surprising.aeron.model.CoreOrderState;
import com.surprising.aeron.model.CoreOrderStatus;
import com.surprising.aeron.model.CorePositionState;
import com.surprising.aeron.model.CoreRiskState;
import com.surprising.aeron.model.TradingCoreState;
import com.surprising.aeron.protocol.ApplyMarkPriceCommand;
import com.surprising.aeron.protocol.BalanceAdjustmentCommand;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreOrderType;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreRiskLimitBracket;
import com.surprising.aeron.protocol.CoreTimeInForce;
import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.protocol.RegisterInstrumentCommand;
import com.surprising.aeron.protocol.ReservationKind;
import com.surprising.aeron.service.account.CoreUserState;
import com.surprising.aeron.service.account.UserRuntime;
import com.surprising.aeron.service.core.RuntimeDerivativeMatchFixture;
import com.surprising.aeron.service.core.RuntimeStateParityChecker;
import com.surprising.aeron.service.core.TradingAccountRuntime;
import com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.aeron.service.order.state.OrderReservation;
import com.surprising.aeron.service.order.state.RuntimeTestStateTransitions;
import com.surprising.aeron.service.snapshot.state.RuntimeStateProjector;
import com.surprising.aeron.service.treasury.CoreTreasuryState;
import com.surprising.product.api.ProductLine;
import exchange.core2.core.common.MatcherResult.MatcherEvent;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static com.surprising.aeron.service.matcher.MatcherEventFixtures.trade;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 衍生品通用成交、冷状态投影及修订交接测试。冷状态测试推进器与 RuntimeDerivativeMatchFixture 仍复用生产成交计算，二者一致不能作为独立公式证明；金额硬编码断言另核对指定 U 本位永续场景，不代表六产品线全部资金验证。
 */
class RuntimeDerivativeMatchProcessorTest {

    /**
     * 核对 applyRuntime 返回原运行时对象，成交后值与冷状态测试推进器一致；两侧共用生产计算逻辑，不是独立算法对照。
     */
    @Test
    void persistentApplyMutatesProvidedRuntimeAndMatchesAuthoritativeReducer() {
        // 本用例使用的规范 U 本位永续币对。
        CoreInstrument instrument = instrument();
        // 当前主动买入或平仓卖出冷订单，方向由用例决定。
        CoreOrderState taker = order(11, 7, CoreOrderSide.BUY, 1);
        // 当前成交的相反方向对手冷订单。
        CoreOrderState maker = order(12, 8, CoreOrderSide.SELL, 1);
        // 包含用户、订单、币对及系统账本的操作前冷状态。
        TradingCoreState before = new TradingCoreState(ProductLine.LINEAR_PERPETUAL, 1,
                Map.of(7L, user(7, taker, 100), 8L, user(8, maker, 100)),
                Map.of(11L, taker, 12L, maker), Map.of(instrument.instrumentId(), instrument),
                CoreRiskState.empty(), CoreTreasuryState.empty());
        // 用例直接构造的 Matcher 成交事实列表，不通过真实订单簿。
        List<MatcherEvent> matches = List.of(trade(12, 8, 100, 1, true, true));
        // 冷状态测试推进器给出的期望结果，其资金规则仍调用生产计算器。
        TradingCoreState expected = new RuntimeTestStateTransitions().applyMatches(before, 11, "BTC", "USDT", matches);
        // 当前投影对照使用的资产、合约及持仓身份字典。
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        // 由冷状态投影得到或辅助转换返回的内存交易运行时。
        TradingAccountRuntime runtime = RuntimeStateProjector.project(before, identities);

        // applyRuntime 返回的同一运行时引用，用于核对原位应用契约。
        TradingAccountRuntime applied = RuntimeDerivativeMatchProcessor.applyRuntime(
                11, matches, runtime, identities);

        assertThat(applied).isSameAs(runtime);
        RuntimeStateParityChecker.assertMatches(expected, identities, applied);
    }

    /**
     * 在有效 maker 后夹带缺失 maker，检查提前校验抛错且完整运行时仍与初始冷状态一致。
     */
    @Test
    void persistentApplyRejectsMissingMakerWithoutMutatingRuntime() {
        // 本用例使用的规范 U 本位永续币对。
        CoreInstrument instrument = instrument();
        // 当前主动买入或平仓卖出冷订单，方向由用例决定。
        CoreOrderState taker = order(11, 7, CoreOrderSide.BUY, 2);
        // 当前成交的相反方向对手冷订单。
        CoreOrderState maker = order(12, 8, CoreOrderSide.SELL, 1);
        // 包含用户、订单、币对及系统账本的操作前冷状态。
        TradingCoreState before = new TradingCoreState(ProductLine.LINEAR_PERPETUAL, 1,
                Map.of(7L, user(7, taker, 200), 8L, user(8, maker, 100)),
                Map.of(11L, taker, 12L, maker),
                Map.of(instrument.instrumentId(), instrument), CoreRiskState.empty(), CoreTreasuryState.empty());
        // 当前投影对照使用的资产、合约及持仓身份字典。
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        // 由冷状态投影得到或辅助转换返回的内存交易运行时。
        TradingAccountRuntime runtime = RuntimeStateProjector.project(before, identities);

        assertThatThrownBy(() -> RuntimeTestStateTransitions.applyDerivativeMatches(before, 11, List.of(trade(12, 8, 100, 1, true, true),
                        trade(99, 9, 100, 1, true, true)), runtime, identities))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("runtime matched order is not open: 99");
        RuntimeStateParityChecker.assertMatches(before, identities, runtime);
    }

    /**
     * 先把无关用户修订人为改成九百九十九，再应用成交及期望修订计划，检查辅助方法只重写变化用户，不覆盖无关用户。
     */
    @Test
    void transitionOnlyRevisesUsersInAuthoritativeDelta() {
        // 本用例使用的规范 U 本位永续币对。
        CoreInstrument instrument = instrument();
        // 当前主动买入或平仓卖出冷订单，方向由用例决定。
        CoreOrderState taker = order(11, 7, CoreOrderSide.BUY, 1);
        // 当前成交的相反方向对手冷订单。
        CoreOrderState maker = order(12, 8, CoreOrderSide.SELL, 1);
        // 不参与本次成交的用户九十九的初始冷状态。
        CoreUserState unrelated = CoreUserState.empty(ProductLine.LINEAR_PERPETUAL, 99);
        // 包含用户、订单、币对及系统账本的操作前冷状态。
        TradingCoreState before = new TradingCoreState(ProductLine.LINEAR_PERPETUAL, 1,
                Map.of(7L, user(7, taker, 100), 8L, user(8, maker, 100), 99L, unrelated),
                Map.of(11L, taker, 12L, maker), Map.of(instrument.instrumentId(), instrument),
                CoreRiskState.empty(), CoreTreasuryState.empty());
        // 用例直接构造的 Matcher 成交事实列表，不通过真实订单簿。
        List<MatcherEvent> matches = List.of(trade(12, 8, 100, 1, true, true));
        // 冷状态测试推进器给出的期望结果，其资金规则仍调用生产计算器。
        TradingCoreState expected = new RuntimeTestStateTransitions().applyMatches(before, 11, "BTC", "USDT", matches);
        // 当前投影对照使用的资产、合约及持仓身份字典。
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        // 由冷状态投影得到或辅助转换返回的内存交易运行时。
        TradingAccountRuntime runtime = RuntimeStateProjector.project(before, identities);
        runtime.putUser(new UserRuntime(ProductLine.LINEAR_PERPETUAL, 99, 999, unrelated.positionMode()));

        RuntimeDerivativeMatchFixture.applyTransition(before, expected, 11, matches, runtime, identities);

        assertThat(runtime.user(99).revision()).isEqualTo(999);
    }

    /**
     * 主动两单位分别吃掉两个 maker，比对辅助投影结果和冷推进结果的完整状态及系统费用、保险和亏空；这些路径共用生产计算器。
     */
    @Test
    void multiMatchSimulationEqualsAuthoritativeReducer() {
        // 本用例使用的规范 U 本位永续币对。
        CoreInstrument instrument = instrument();
        // 当前主动买入或平仓卖出冷订单，方向由用例决定。
        CoreOrderState taker = order(11, 7, CoreOrderSide.BUY, 2);
        // 两笔成交中的第一个一单位卖方 maker。
        CoreOrderState makerOne = order(12, 8, CoreOrderSide.SELL, 1);
        // 两笔成交中的第二个一单位卖方 maker。
        CoreOrderState makerTwo = order(13, 9, CoreOrderSide.SELL, 1);
        // 包含用户、订单、币对及系统账本的操作前冷状态。
        TradingCoreState before = new TradingCoreState(ProductLine.LINEAR_PERPETUAL, 1,
                Map.of(7L, user(7, taker, 200), 8L, user(8, makerOne, 100), 9L, user(9, makerTwo, 100)),
                Map.of(11L, taker, 12L, makerOne, 13L, makerTwo),
                Map.of(instrument.instrumentId(), instrument), CoreRiskState.empty(), CoreTreasuryState.empty());
        // 用例直接构造的 Matcher 成交事实列表，不通过真实订单簿。
        List<MatcherEvent> matches = List.of(
                trade(12, 8, 100, 1, true, false),
                trade(13, 9, 100, 1, true, true));
        // 冷状态测试推进器应用成交后的参照状态。
        TradingCoreState after = new RuntimeTestStateTransitions().applyMatches(before, 11, "BTC", "USDT", matches);

        // 当前投影对照使用的资产、合约及持仓身份字典。
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        // 测试辅助投影并调用生产成交入口得到的运行时结果。
        TradingAccountRuntime simulated = RuntimeDerivativeMatchFixture.simulate(before, 11, matches, identities);

        // 本次读取系统费用、保险及亏空的 USDT 资产编号。
        int assetId = identities.assetId("USDT");
        assertThat(simulated.treasury().fee(assetId))
                .isEqualTo(after.treasuryState().feeBalances().getOrDefault("USDT", 0L));
        assertThat(simulated.treasury().insurance(assetId))
                .isEqualTo(after.treasuryState().insuranceBalances().getOrDefault("USDT", 0L));
        assertThat(simulated.treasury().insuranceDeficit(assetId))
                .isEqualTo(after.treasuryState().insuranceDeficits().getOrDefault("USDT", 0L));

        RuntimeStateParityChecker.assertMatches(after, identities, simulated);
    }

    /**
     * 委托共用对照入口核对已有两单位多仓部分卖出一单位的状态与系统金额。
     */
    @Test
    void partialCloseSimulationEqualsAuthoritativeReducer() {
        assertCloseParity(1, 100);
    }

    /**
     * 委托共用对照入口核对已有两单位多仓卖出三单位后的反向状态与系统金额。
     */
    @Test
    void reversalSimulationEqualsAuthoritativeReducer() {
        assertCloseParity(3, 200);
    }

    /**
     * 无成交市价 IOC 订单仍应终态并释放预留，对照两种投影入口结果；不经过真实 Matcher。
     */
    @Test
    void emptyMarketMatchReleasesTerminalReservationLikeReducer() {
        // 本用例使用的规范 U 本位永续币对。
        CoreInstrument instrument = instrument();
        // 当前主动买入或平仓卖出冷订单，方向由用例决定。
        CoreOrderState taker = order(11, 7, CoreOrderSide.BUY, 2,
                false, CoreOrderType.MARKET, CoreTimeInForce.IOC);
        // 包含用户、订单、币对及系统账本的操作前冷状态。
        TradingCoreState before = new TradingCoreState(ProductLine.LINEAR_PERPETUAL, 1,
                Map.of(7L, user(7, taker, 200)), Map.of(11L, taker),
                Map.of(instrument.instrumentId(), instrument), CoreRiskState.empty(), CoreTreasuryState.empty());
        // 冷状态测试推进器应用成交后的参照状态。
        TradingCoreState after = new RuntimeTestStateTransitions().applyMatches(before, 11, "BTC", "USDT", List.of());
        // 当前投影对照使用的资产、合约及持仓身份字典。
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();

        // 测试辅助投影并调用生产成交入口得到的运行时结果。
        TradingAccountRuntime simulated = RuntimeDerivativeMatchFixture.simulate(before, 11, List.of(), identities);

        RuntimeStateParityChecker.assertMatches(after, identities, simulated);
    }

    /**
     * 用大整数精度的永续配置核对资金不足准入、买入开仓、卖出平仓、释放保证金补费及两用户加系统费资金总额，并比对运行时状态。市价辅助方法没有使用传入的 matchingPriceTicks，不能据调用字面值认定覆盖了显式保护价输入。
     */
    @Test
    void betterPricedSellActiveCloseSettlesExactFeeAndMatchesRuntime() {
        // 将冷状态投影为运行时再调用生产业务的测试推进器，不是独立资金计算实现。
        RuntimeTestStateTransitions reducer = new RuntimeTestStateTransitions();
        // 大金额开平仓用例当前推进到的冷状态。
        TradingCoreState state = reducer.registerInstrument(TradingCoreState.empty(ProductLine.LINEAR_PERPETUAL),
                liveInstrument());
        state = reducer.applyMarkPrice(state,
                new ApplyMarkPriceCommand("1", 773_022, 1, 1_700_000_000_000L));
        state = reducer.adjustBalance(state, 7, new BalanceAdjustmentCommand("USDT", 10_000_000_000L));
        state = reducer.adjustBalance(state, 8, new BalanceAdjustmentCommand("USDT", 2_000_000_000_000L));
        // 零请求价的一单位买入市价订单，供资金不足和后续开仓场景共用。
        PlaceOrderCommand open = marketOrder(11, CoreOrderSide.BUY, false, 788_640);
        // 可用金额不足时保留的准入前冷状态，供异常断言引用。
        TradingCoreState underfunded = state;
        assertThatThrownBy(() -> reducer.placeOrder(underfunded, 7, open))
                .isInstanceOfSatisfying(CoreStateRejectedException.class,
                        /* 准入拒绝异常，用于核对资金不足业务码。 */ exception -> assertThat(exception.code()).isEqualTo("INSUFFICIENT_AVAILABLE_BALANCE"));

        state = reducer.adjustBalance(state, 7, new BalanceAdjustmentCommand("USDT", 990_000_000_000L));
        // 用例另行构造的一组卖方限价单和主动市价单的成交前状态，变量名不表示此时必然非法。
        TradingCoreState invalidOpen = reducer.placeOrder(state, 8,
                limitOrder(21, CoreOrderSide.SELL, 773_332, false));
        invalidOpen = reducer.placeOrder(invalidOpen, 7,
                marketOrder(22, CoreOrderSide.BUY, false, 773_022));
        // 上述独立场景应用指定成交价后的结果，用例只断言主动订单全部成交。
        TradingCoreState protectedFill = reducer.applyMatches(invalidOpen, 22, "BTC", "USDT",
                List.of(trade(21, 8, 773_332, 1, true, true)));
        assertThat(protectedFill.order(22).status()).isEqualTo(CoreOrderStatus.FILLED);

        state = reducer.placeOrder(state, 8, limitOrder(12, CoreOrderSide.SELL, 773_333, false));
        state = reducer.placeOrder(state, 7, open);
        state = reducer.applyMatches(state, 11, "BTC", "USDT",
                List.of(trade(12, 8, 773_333, 1, true, true)));
        assertThat(state.user(7).balances().get("USDT"))
                .isEqualTo(new AssetBalance("USDT", 918_800_035_000L, 77_333_300_000L));

        state = reducer.placeOrder(state, 8, limitOrder(13, CoreOrderSide.BUY, 773_332, true));
        state = reducer.placeOrder(state, 7, marketOrder(14, CoreOrderSide.SELL, true, 773_022));
        assertThat(state.user(7).reservations().get(14L).remainingUnits()).isEqualTo(3_903_765_000L);

        // 已经开仓并为相反方向订单完成准入的平仓前状态。
        TradingCoreState readyToClose = state;
        // 按指定较低成交价完成平仓的独立结果，用来核对持仓归零。
        TradingCoreState boundaryFill = reducer.applyMatches(readyToClose, 14, "BTC", "USDT",
                List.of(trade(13, 8, 773_022, 1, true, true)));
        assertThat(boundaryFill.user(7).positions().get("1").signedQuantitySteps()).isZero();

        // 平仓卖出取得更高实际成交价的一项成交列表。
        List<MatcherEvent> betterFill = List.of(trade(13, 8, 773_332, 1, true, true));
        // 平仓前用户七可用余额，另一场景把它全部扣除以检查释放保证金补费。
        long availableBeforeClose = readyToClose.user(7).balances().get("USDT").availableUnits();
        // 可用余额先归零后，使用平仓释放保证金完成费用支付的结果状态。
        TradingCoreState marginFundedClose = reducer.adjustBalance(readyToClose, 7,
                new BalanceAdjustmentCommand("USDT", Math.negateExact(availableBeforeClose)));
        marginFundedClose = reducer.applyMatches(marginFundedClose, 14, "BTC", "USDT", betterFill);
        assertThat(marginFundedClose.user(7).balances().get("USDT"))
                .isEqualTo(new AssetBalance("USDT", 77_360_405_000L, 0));

        // 正常可用余额场景按改善成交价完成平仓的结果，包含费用和资金守恒断言。
        TradingCoreState closed = reducer.applyMatches(readyToClose, 14, "BTC", "USDT", betterFill);
        assertThat(closed.user(7).positions().get("1").signedQuantitySteps()).isZero();
        assertThat(closed.user(7).reservations().get(14L).reservedUnits()).isEqualTo(3_903_765_000L);
        assertThat(closed.user(7).reservations().get(14L).remainingUnits()).isZero();
        assertThat(closed.user(7).balances().get("USDT"))
                .isEqualTo(new AssetBalance("USDT", 992_256_675_000L, 0));
        assertThat(closed.treasuryState().feeBalances()).containsEntry("USDT", 7_733_325_000L);
        assertThat(closed.user(7).totalUnits("USDT") + closed.user(8).totalUnits("USDT")
                + closed.treasuryState().feeBalances().get("USDT")).isEqualTo(3_000_000_000_000L);

        // 当前投影对照使用的资产、合约及持仓身份字典。
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        // 由冷状态投影得到或辅助转换返回的内存交易运行时。
        TradingAccountRuntime runtime = RuntimeDerivativeMatchFixture.simulateTransition(
                readyToClose, closed, 14, betterFill, identities);
        RuntimeStateParityChecker.assertMatches(closed, identities, runtime);
    }

    /**
     * 构造已有两单位多仓与相反方向订单，按给定平仓量和预留对照两种共享生产计算的测试路径，核对完整状态及系统金额。
     * @param quantity 当前对照成交或测试订单的数量
     * @param takerReservation 已有多仓用户为本次主动卖单准备的预留金额
     */
    private static void assertCloseParity(long quantity, long takerReservation) {
        // 本用例使用的规范 U 本位永续币对。
        CoreInstrument instrument = instrument();
        // 当前主动买入或平仓卖出冷订单，方向由用例决定。
        CoreOrderState taker = order(11, 7, CoreOrderSide.SELL, quantity);
        // 当前成交的相反方向对手冷订单。
        CoreOrderState maker = order(12, 8, CoreOrderSide.BUY, quantity);
        // 测试起点已有的两单位多仓、均价一百且保证金二十的冷持仓。
        CorePositionState position = new CorePositionState("1", "USDT", 2, 100, 200, 0, 20);
        // 已有多仓并持有本次主动卖出预留的用户七冷状态。
        CoreUserState takerUser = new CoreUserState(ProductLine.LINEAR_PERPETUAL, 7, 1,
                Map.of("USDT", new AssetBalance("USDT", 800, 20 + takerReservation)),
                Map.of(11L, OrderReservation.create(11, "1",
                        ReservationKind.DERIVATIVE_MARGIN, "USDT", takerReservation, quantity)),
                Map.of(position.key(), position));
        // 包含用户、订单、币对及系统账本的操作前冷状态。
        TradingCoreState before = new TradingCoreState(ProductLine.LINEAR_PERPETUAL, 1,
                Map.of(7L, takerUser, 8L, user(8, maker, 300)), Map.of(11L, taker, 12L, maker),
                Map.of(instrument.instrumentId(), instrument), CoreRiskState.empty(), CoreTreasuryState.empty());
        // 用例直接构造的 Matcher 成交事实列表，不通过真实订单簿。
        List<MatcherEvent> matches = List.of(trade(12, 8, 120, quantity, true, true));
        // 冷状态测试推进器应用成交后的参照状态。
        TradingCoreState after = new RuntimeTestStateTransitions().applyMatches(before, 11, "BTC", "USDT", matches);
        // 当前投影对照使用的资产、合约及持仓身份字典。
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();

        // 测试辅助投影并调用生产成交入口得到的运行时结果。
        TradingAccountRuntime simulated = RuntimeDerivativeMatchFixture.simulate(before, 11, matches, identities);

        // 本次读取系统费用、保险及亏空的 USDT 资产编号。
        int assetId = identities.assetId("USDT");
        assertThat(simulated.treasury().fee(assetId))
                .isEqualTo(after.treasuryState().feeBalances().getOrDefault("USDT", 0L));
        assertThat(simulated.treasury().insurance(assetId))
                .isEqualTo(after.treasuryState().insuranceBalances().getOrDefault("USDT", 0L));
        assertThat(simulated.treasury().insuranceDeficit(assetId))
                .isEqualTo(after.treasuryState().insuranceDeficits().getOrDefault("USDT", 0L));

        RuntimeStateParityChecker.assertMatches(after, identities, simulated);
    }

    /**
     * 为指定测试订单创建含可用八百及指定冻结预留的 U 本位永续用户冷状态。
     * @param userId 测试订单或账户所属用户编号
     * @param order 需要为其建立保证金预留的冷订单
     * @param reservedUnits 新建用户中该订单的冻结预留金额
     */
    private static CoreUserState user(long userId, CoreOrderState order, long reservedUnits) {
        // 给测试订单建立的衍生品保证金预留。
        OrderReservation reservation = OrderReservation.create(order.orderId(), order.instrumentId(),
                ReservationKind.DERIVATIVE_MARGIN, "USDT", reservedUnits,
                order.quantitySteps());
        return new CoreUserState(ProductLine.LINEAR_PERPETUAL, userId, 1,
                Map.of("USDT", new AssetBalance("USDT", 800, reservedUnits)),
                Map.of(order.orderId(), reservation), Map.of());
    }

    /**
     * 构造普通非 reduce-only、GTC 限价测试订单，委托完整辅助入口。
     * @param orderId 测试订单编号
     * @param userId 测试订单或账户所属用户编号
     * @param side 测试订单买卖方向
     * @param quantity 当前对照成交或测试订单的数量
     */
    private static CoreOrderState order(long orderId, long userId, CoreOrderSide side, long quantity) {
        return order(orderId, userId, side, quantity, false, CoreOrderType.LIMIT, CoreTimeInForce.GTC);
    }

    /**
     * 按参数构造开放全仓净持仓测试订单，市价请求价为零，限价请求价固定一百。
     * @param orderId 测试订单编号
     * @param userId 测试订单或账户所属用户编号
     * @param side 测试订单买卖方向
     * @param quantity 当前对照成交或测试订单的数量
     * @param reduceOnly 是否限制为只减仓
     * @param orderType 市价或限价订单类型
     * @param timeInForce GTC 或 IOC 等订单有效期策略
     */
    private static CoreOrderState order(long orderId, long userId, CoreOrderSide side, long quantity,
                                        boolean reduceOnly, CoreOrderType orderType, CoreTimeInForce timeInForce) {
        return new CoreOrderState(orderId, ProductLine.LINEAR_PERPETUAL, userId, "1",
                side, orderType == CoreOrderType.MARKET ? 0 : 100, quantity, 0, quantity, reduceOnly,
                CoreMarginMode.CROSS, CorePositionSide.NET, orderType, timeInForce, false, "", new UUID(0, orderId),
                10_000, 10_000, CoreOrderStatus.OPEN, 1);
    }

    /**
     * 构造基础 U 本位 BTC/USDT 永续币对及单档风险规则。
     */
    private static CoreInstrument instrument() {
        return CoreInstrument.from(ProductLine.LINEAR_PERPETUAL,
                new RegisterInstrumentCommand("1",
                        "BTC", "USDT", "USDT", 1, 1, 1,
                        100_000, 50_000, 0, 0, 0, -1, 0,
                        10_000_000, 10_000, 0, 1,
                        List.of(new CoreRiskLimitBracket(1, 0, 10_000,
                                10_000_000, 100_000, 50_000))));
    }

    /**
     * 构造高精度、大金额平仓用例的永续注册命令；这是内存测试配置，不启动在线节点。
     */
    private static RegisterInstrumentCommand liveInstrument() {
        return new RegisterInstrumentCommand("1",
                        "BTC", "USDT", "USDT", 10_000_000L, 10_000_000L, 100_000_000L,
                        10_000L, 5_000L, 0, 500L, 0, -1, 0,
                        100_000_000L, 1_000_000_000_000_000L, 1_000_000L, 25_000_000_000_000L,
                        List.of(new CoreRiskLimitBracket(1, 0, 1_000_000_000_000_000L,
                                100_000_000L, 5_000L, 5_000L)));
    }

    /**
     * 构造数量一、请求价零的全仓净持仓市价 IOC 订单；matchingPriceTicks 参数没有写入命令，当前辅助方法不使用它。
     * @param orderId 测试订单编号
     * @param side 测试订单买卖方向
     * @param reduceOnly 是否限制为只减仓
     * @param matchingPriceTicks 调用方传入的预期撮合保护价；本辅助方法实际未使用此参数
     */
    private static PlaceOrderCommand marketOrder(long orderId, CoreOrderSide side,
                                                  boolean reduceOnly, long matchingPriceTicks) {
        return new PlaceOrderCommand(orderId, "1", side, 0, 1, reduceOnly, CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.MARKET, CoreTimeInForce.IOC, false, "order-" + orderId);
    }

    /**
     * 构造数量一、指定价格的全仓净持仓 GTC 限价订单，客户订单号由订单编号生成。
     * @param orderId 测试订单编号
     * @param side 测试订单买卖方向
     * @param priceTicks 限价订单请求价格刻度
     * @param reduceOnly 是否限制为只减仓
     */
    private static PlaceOrderCommand limitOrder(long orderId, CoreOrderSide side,
                                                 long priceTicks, boolean reduceOnly) {
        return new PlaceOrderCommand(orderId, "1", side, priceTicks, 1, reduceOnly, CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "order-" + orderId);
    }
}
