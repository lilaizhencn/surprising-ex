package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.ApplyFundingCommand;
import com.surprising.aeron.protocol.ApplyMarkPriceCommand;
import com.surprising.aeron.protocol.BalanceAdjustmentCommand;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.ExecuteLiquidationCommand;
import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.protocol.ResolveLiquidationCommand;
import com.surprising.aeron.protocol.SettleInstrumentCommand;
import com.surprising.aeron.service.exception.CoreStateRejectedException;
import com.surprising.aeron.service.state.account.CoreUserState;
import com.surprising.aeron.service.state.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.state.instrument.CoreInstrument;
import com.surprising.aeron.service.state.ledger.InsuranceAllocationPolicy;
import com.surprising.aeron.service.state.lifecycle.RuntimePerpetualFundingProcessor;
import com.surprising.aeron.service.state.math.*;
import com.surprising.aeron.service.state.math.CoreContractMath;
import com.surprising.aeron.service.state.model.AssetBalance;
import com.surprising.aeron.service.state.model.CoreLiquidationState;
import com.surprising.aeron.service.state.model.CoreMarkPriceState;
import com.surprising.aeron.service.state.model.CoreOrderStatus;
import com.surprising.aeron.service.state.model.CorePositionState;
import com.surprising.aeron.service.state.order.RuntimeTestStateTransitions;
import com.surprising.aeron.service.state.snapshot.*;
import com.surprising.aeron.service.state.snapshot.RuntimeStateProjector;
import com.surprising.aeron.service.state.snapshot.TradingStateSnapshotCodec;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 生命周期业务状态测试，覆盖资金费、到期结算、强平、保险分摊与 ADL 的固定金额和状态边界。部分对照路径复用相同生产计算，状态一致不能单独当作独立公式证明；快照测试使用内存编解码，不启动真实 Cluster，成功场景也不等于跨线程失败回滚验证。
 */
class CoreLifecycleStateTest {

    /** 本组内存业务状态推进辅助器，内部多条路径仍调用生产计算，不代表独立 Cluster。 */
    private final RuntimeTestStateTransitions reducer = new RuntimeTestStateTransitions();

    /**
     * 核对等量反向持仓的 U 本位资金费一付十、一收十，总额不变；已完成编号再次请求必须拒绝，内存快照往返后仍保存完成编号。
     */
    @Test
    void linearFundingIsZeroSumAndSettlementIdSurvivesSnapshot() {
        // 本场景当前交易状态，后续赋值表示应用一条业务命令后的新值。
        TradingCoreState state = stateWithOppositePositions(ProductLine.LINEAR_PERPETUAL,
                ContractType.LINEAR_PERPETUAL, 100, 10, 100);
        state = reducer.applyMarkPrice(state, new ApplyMarkPriceCommand("1", 100, 1,
                1_700_000_000_000L));

        // 本测试资金口径在资金费执行前的总额。
        long totalBefore = total(state, "USDT");
        // 资金费或保险调整后的业务状态。
        TradingCoreState funded = reducer.applyFunding(state,
                new ApplyFundingCommand(91, "1", 10_000));

        assertThat(total(funded, "USDT")).isEqualTo(totalBefore);
        assertThat(funded.user(1).totalUnits("USDT")).isEqualTo(990);
        assertThat(funded.user(2).totalUnits("USDT")).isEqualTo(1_010);
        assertThat(funded.treasuryState().insuranceBalances()).isEmpty();
        assertThatThrownBy(() -> reducer.applyFunding(funded,
                new ApplyFundingCommand(91, "1", 10_000)))
                .isInstanceOfSatisfying(CoreStateRejectedException.class,
                        /* 实际捕获的业务拒绝异常，用于检查准确拒绝码。 */ exception -> assertThat(exception.code()).isEqualTo("STALE_SETTLEMENT_ID"));

        // 当前状态经内存快照编码再解码得到的只读恢复值。
        TradingCoreState restored = TradingStateSnapshotCodec.decode(
                TradingStateSnapshotCodec.encode(funded), ProductLine.LINEAR_PERPETUAL);
        assertThat(restored).isEqualTo(funded);
        assertThat(restored.treasuryState().fundingSettlements()).containsEntry("1", 91L);
    }

    /**
     * 核对两条按用户返回的实际收付明细分别为负十和正十，并与各用户余额变化一致。
     */
    @Test
    void fundingApplicationEmitsActualZeroSumPaymentFacts() {
        // 本场景当前交易状态，后续赋值表示应用一条业务命令后的新值。
        TradingCoreState state = stateWithOppositePositions(ProductLine.LINEAR_PERPETUAL,
                ContractType.LINEAR_PERPETUAL, 100, 10, 100);
        state = reducer.applyMarkPrice(state, new ApplyMarkPriceCommand("1", 100, 1,
                1_700_000_000_000L));

        // 本次资金费状态、实际收付明细及进度的组合结果。
        RuntimeTestStateTransitions.FundingApplication application = reducer.applyFundingWithFacts(state,
                new ApplyFundingCommand(92, "1", 10_000));

        assertThat(application.payments()).hasSize(2);
        assertThat(application.payments()).extracting(/* 当前正在提取用户、持仓方向或有符号金额的资金费明细。 */ payment -> payment.userId())
                .containsExactly(1L, 2L);
        assertThat(application.payments()).extracting(/* 当前正在提取用户、持仓方向或有符号金额的资金费明细。 */ payment -> payment.amountUnits())
                .containsExactly(-10L, 10L);
        assertThat(application.payments().stream().mapToLong(/* 当前正在提取用户、持仓方向或有符号金额的资金费明细。 */ payment -> payment.amountUnits()).sum()).isZero();
        assertThat(application.state().user(1).totalUnits("USDT") - state.user(1).totalUnits("USDT"))
                .isEqualTo(-10);
        assertThat(application.state().user(2).totalUnits("USDT") - state.user(2).totalUnits("USDT"))
                .isEqualTo(10);
    }

    /**
     * 同用户多空腿先净额结算但保留两腿明细，净额为零时用户状态不变；另一用户可用只有五时最多付五，财库记录实际差额且不动保险。
     */
    @Test
    void fundingFactsPreserveHedgedLegsAndCapDebitsAtAvailableCash() {
        // 构造同用户双向持仓之前的基本交易状态。
        TradingCoreState base = stateWithUser(ProductLine.LINEAR_PERPETUAL,
                ContractType.LINEAR_PERPETUAL, 1, 10, 100, 100, 0);
        // 显式覆盖前取得的用户原状态和修订号。
        CoreUserState current = base.user(1);
        // 测试构造的同币对 LONG 和 SHORT 两腿持仓表。
        Map<String, CorePositionState> hedgedPositions = new TreeMap<>();
        hedgedPositions.put("1:LONG", new CorePositionState("1", "USDT",
                com.surprising.aeron.protocol.CoreMarginMode.CROSS,
                com.surprising.aeron.protocol.CorePositionSide.LONG, 10, 100, 1_000, 0, 0));
        hedgedPositions.put("1:SHORT", new CorePositionState("1", "USDT",
                com.surprising.aeron.protocol.CoreMarginMode.CROSS,
                com.surprising.aeron.protocol.CorePositionSide.SHORT, -10, 100, 1_000, 0, 0));
        // 设为双向持仓模式并保留原余额和预留的测试用户。
        CoreUserState hedgedUser = new CoreUserState(base.productLine(), 1, current.revision() + 1,
                current.balances(), current.reservations(), hedgedPositions,
                com.surprising.aeron.protocol.CorePositionMode.HEDGE);
        // 复制旧用户表后替换当前用户的测试集合。
        Map<Long, CoreUserState> users = new TreeMap<>(base.users());
        users.put(1L, hedgedUser);
        // 已经装入同用户多空两腿并应用标记价的前置状态。
        TradingCoreState hedged = new TradingCoreState(base.productLine(), base.revision() + 1, users,
                base.orders(), base.instruments(), base.riskState(), base.treasuryState());
        hedged = reducer.applyMarkPrice(hedged, new ApplyMarkPriceCommand("1", 100, 1,
                1_700_000_000_000L));

        // 双向腿资金费净额为零时的状态与明细结果。
        RuntimeTestStateTransitions.FundingApplication netZero = reducer.applyFundingWithFacts(hedged,
                new ApplyFundingCommand(93, "1", 10_000));

        // 先合并两腿净收付再按可用余额限制扣款；明细的迭代顺序不是本测试依赖的业务约定。
        assertThat(netZero.payments()).extracting(/* 当前正在提取用户、持仓方向或有符号金额的资金费明细。 */ payment -> payment.positionSide(), /* 当前正在提取用户、持仓方向或有符号金额的资金费明细。 */ payment -> payment.amountUnits())
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(com.surprising.aeron.protocol.CorePositionSide.LONG, -10L),
                        org.assertj.core.groups.Tuple.tuple(com.surprising.aeron.protocol.CorePositionSide.SHORT, 10L));
        assertThat(netZero.state().user(1)).isEqualTo(hedged.user(1));

        // 应付十但当前总可用只有五的测试前置状态。
        TradingCoreState lowCash = stateWithUser(ProductLine.LINEAR_PERPETUAL,
                ContractType.LINEAR_PERPETUAL, 1, 10, 100, 5, 0);
        lowCash = reducer.applyMarkPrice(lowCash, new ApplyMarkPriceCommand("1", 100, 1,
                1_700_000_000_000L));
        // 实际应付款被可用余额上限截到五后的资金费结果。
        RuntimeTestStateTransitions.FundingApplication capped = reducer.applyFundingWithFacts(lowCash,
                new ApplyFundingCommand(94, "1", 10_000));

        assertThat(capped.payments()).singleElement().satisfies(/* 当前正在提取用户、持仓方向或有符号金额的资金费明细。 */ payment ->
                assertThat(payment.amountUnits()).isEqualTo(-5));
        assertThat(capped.state().user(1).totalUnits("USDT")).isZero();
        assertThat(capped.state().treasuryState().insuranceBalances()).doesNotContainKey("USDT");
        assertThat(capped.state().treasuryState().fundingResidualBalances()).containsEntry("USDT", 5L);
    }

    /**
     * 首轮只处理用户一并保存游标，经内存快照后再处理用户二，完成后清除进度并登记编号；本用例没有重复重发同一页命令。
     */
    @Test
    void fundingCursorPersistsAcrossSnapshotAndCompletesExactlyOnce() {
        // 本场景当前交易状态，后续赋值表示应用一条业务命令后的新值。
        TradingCoreState state = stateWithOppositePositions(ProductLine.LINEAR_PERPETUAL,
                ContractType.LINEAR_PERPETUAL, 100, 10, 100);
        state = reducer.applyMarkPrice(state, new ApplyMarkPriceCommand("1", 100, 1,
                1_700_000_000_000L));
        // 当前资金费或到期结算首个用户页命令，用户游标为零且每页一人。
        ApplyFundingCommand firstCommand = new ApplyFundingCommand(95, "1", 10_000, 0, 1);

        // 第一页处理后的状态、进度和相应业务事实。
        RuntimeTestStateTransitions.FundingApplication first = reducer.applyFundingWithFacts(state, firstCommand,
                List.of(1L, 2L), UUID.fromString("00000000-0000-0000-0000-000000000095"));

        assertThat(first.progress().complete()).isFalse();
        assertThat(first.progress().nextCursorUserId()).isEqualTo(1);
        assertThat(first.state().treasuryState().fundingProgress("1")).isNotNull();
        assertThat(first.state().treasuryState().fundingSettlements()).doesNotContainKey("1");
        // 当前状态经内存快照编码再解码得到的只读恢复值。
        TradingCoreState restored = TradingStateSnapshotCodec.decode(
                TradingStateSnapshotCodec.encode(first.state()), ProductLine.LINEAR_PERPETUAL);
        assertThat(restored).isEqualTo(first.state());

        // 使用第一页面游标处理后续页的结果。
        RuntimeTestStateTransitions.FundingApplication second = reducer.applyFundingWithFacts(restored,
                new ApplyFundingCommand(95, "1", 10_000, 1, 1), List.of(1L, 2L),
                UUID.fromString("00000000-0000-0000-0000-000000000096"));

        assertThat(second.progress().complete()).isTrue();
        assertThat(second.state().treasuryState().fundingProgress("1")).isNull();
        assertThat(second.state().treasuryState().fundingSettlements()).containsEntry("1", 95L);
        assertThat(second.payments()).extracting(/* 当前正在提取用户、持仓方向或有符号金额的资金费明细。 */ payment -> payment.userId()).containsExactly(2L);
    }

    /**
     * 分别对等额收付、可用不足与两页资金费核对业务状态、明细及进度，续页继续使用原可变运行时；期望辅助器和实际入口共享生产资金费实现，作用是检查状态转换与输入交接一致。
     */
    @Test
    void runtimeFundingMatchesAuthoritativeStateForNetZeroCappedAndChunkedSettlements() {
        // 两用户等量反向持仓并具备标记价的资金费前置状态。
        TradingCoreState netZero = stateWithOppositePositions(ProductLine.LINEAR_PERPETUAL,
                ContractType.LINEAR_PERPETUAL, 100, 10, 100);
        netZero = reducer.applyMarkPrice(netZero, new ApplyMarkPriceCommand("1", 100, 1,
                1_700_000_000_000L));
        // 等额收付对照场景的资金费命令。
        ApplyFundingCommand netZeroCommand = new ApplyFundingCommand(101, "1", 10_000);
        // 辅助状态推进器得到的等额收付期望结果。
        RuntimeTestStateTransitions.FundingApplication netZeroExpected = reducer.applyFundingWithFacts(netZero,
                netZeroCommand);
        // 等额收付运行时投影及状态对照共用的身份表。
        RuntimeIdentityRegistry netZeroIdentities = new RuntimeIdentityRegistry();
        // 从等额收付前置状态投影出的独立运行时。
        TradingRuntimeState netZeroRuntime = RuntimeStateProjector.project(netZero, netZeroIdentities);
        // 实际运行时资金费入口返回的等额收付结果。
        var netZeroActual = RuntimePerpetualFundingProcessor.apply(netZero, netZeroCommand,
                null, null, netZeroRuntime, netZeroIdentities);
        RuntimeStateParityChecker.assertMatches(netZeroExpected.state(), netZeroIdentities, netZeroActual.state());
        assertThat(netZeroActual.payments()).isEqualTo(netZeroExpected.payments());
        assertThat(netZeroActual.progress()).isEqualTo(netZeroExpected.progress());

        // 可用余额只有五的运行时对照前置状态。
        TradingCoreState capped = stateWithUser(ProductLine.LINEAR_PERPETUAL,
                ContractType.LINEAR_PERPETUAL, 1, 10, 100, 5, 0);
        capped = reducer.applyMarkPrice(capped, new ApplyMarkPriceCommand("1", 100, 1,
                1_700_000_000_000L));
        // 可用不足场景的资金费命令。
        ApplyFundingCommand cappedCommand = new ApplyFundingCommand(102, "1", 10_000);
        // 辅助状态推进器得到的扣款封顶期望结果。
        RuntimeTestStateTransitions.FundingApplication cappedExpected = reducer.applyFundingWithFacts(capped, cappedCommand);
        // 扣款封顶运行时模拟及状态对照共用的身份表。
        RuntimeIdentityRegistry cappedIdentities = new RuntimeIdentityRegistry();
        // 独立运行时模拟器返回的扣款封顶结果。
        var cappedActual = RuntimePerpetualFundingFixture.simulate(capped, cappedCommand,
                null, null, cappedIdentities);
        RuntimeStateParityChecker.assertMatches(cappedExpected.state(), cappedIdentities, cappedActual.state());
        assertThat(cappedActual.payments()).isEqualTo(cappedExpected.payments());

        // 沿用等量反向持仓构造的分页资金费前置状态。
        TradingCoreState chunked = netZero;
        // 第一页写进财库进度的固定命令 UUID。
        UUID firstCommandId = UUID.fromString("00000000-0000-0000-0000-000000000101");
        // 当前资金费或到期结算首个用户页命令，用户游标为零且每页一人。
        ApplyFundingCommand firstCommand = new ApplyFundingCommand(103, "1", 10_000, 0, 1);
        // 辅助状态推进器得到的第一页期望状态、明细和游标。
        RuntimeTestStateTransitions.FundingApplication firstExpected = reducer.applyFundingWithFacts(chunked, firstCommand,
                List.of(1L, 2L), firstCommandId);
        // 两页实际运行时共用的身份表。
        RuntimeIdentityRegistry chunkedIdentities = new RuntimeIdentityRegistry();
        // 第一页建立并继续用于第二页的可变权威运行时。
        TradingRuntimeState chunkedRuntime = RuntimeStateProjector.project(chunked, chunkedIdentities);
        // 实际运行时第一页结果，后续继续使用其中原运行时引用。
        var firstActual = RuntimePerpetualFundingProcessor.apply(chunked, firstCommand,
                List.of(1L, 2L), firstCommandId, chunkedRuntime, chunkedIdentities);
        RuntimeStateParityChecker.assertMatches(firstExpected.state(), chunkedIdentities, firstActual.state());
        assertThat(firstActual.payments()).isEqualTo(firstExpected.payments());
        assertThat(firstActual.progress()).isEqualTo(firstExpected.progress());

        // 当前状态经内存快照编码再解码得到的只读恢复值。
        TradingCoreState restored = TradingStateSnapshotCodec.decode(
                TradingStateSnapshotCodec.encode(firstExpected.state()), ProductLine.LINEAR_PERPETUAL);
        // 第二页不同于第一页的命令 UUID。
        UUID secondCommandId = UUID.fromString("00000000-0000-0000-0000-000000000102");
        // 沿用同结算编号及第一页返回用户游标的续页命令。
        ApplyFundingCommand secondCommand = new ApplyFundingCommand(103, "1", 10_000, 1, 1);
        // 辅助状态推进器从恢复状态得到的第二页期望结果。
        RuntimeTestStateTransitions.FundingApplication secondExpected = reducer.applyFundingWithFacts(restored, secondCommand,
                List.of(1L, 2L), secondCommandId);
        // 继续使用原可变运行时执行第二页得到的实际结果。
        var secondActual = RuntimePerpetualFundingProcessor.apply(restored, secondCommand,
                List.of(1L, 2L), secondCommandId, firstActual.state(), chunkedIdentities);
        RuntimeStateParityChecker.assertMatches(secondExpected.state(), chunkedIdentities, secondActual.state());
        assertThat(secondActual.payments()).isEqualTo(secondExpected.payments());
        assertThat(secondActual.progress()).isEqualTo(secondExpected.progress());
    }

    /**
     * 分别断言缺失标记价、已完成结算编号和首轮非零游标的业务拒绝码。
     */
    @Test
    void runtimeFundingRejectsMissingMarkStaleSettlementAndInvalidCursor() {
        // 本场景当前交易状态，后续赋值表示应用一条业务命令后的新值。
        TradingCoreState state = stateWithOppositePositions(ProductLine.LINEAR_PERPETUAL,
                ContractType.LINEAR_PERPETUAL, 100, 10, 100);
        // 在缺价与已完成编号两种场景复用的资金费请求。
        ApplyFundingCommand command = new ApplyFundingCommand(104, "1", 10_000);
        assertThatThrownBy(() -> RuntimePerpetualFundingFixture.simulate(state, command,
                null, null, new RuntimeIdentityRegistry()))
                .isInstanceOfSatisfying(CoreStateRejectedException.class,
                        /* 实际捕获的业务拒绝异常，用于检查准确拒绝码。 */ exception -> assertThat(exception.code()).isEqualTo("MARK_PRICE_NOT_FOUND"));

        // 补入合法标记价后的资金费前置状态。
        TradingCoreState marked = reducer.applyMarkPrice(state,
                new ApplyMarkPriceCommand("1", 100, 1, 1_700_000_000_000L));
        // 资金费或保险调整后的业务状态。
        TradingCoreState funded = reducer.applyFunding(marked, command);
        assertThatThrownBy(() -> RuntimePerpetualFundingFixture.simulate(funded, command,
                null, null, new RuntimeIdentityRegistry()))
                .isInstanceOfSatisfying(CoreStateRejectedException.class,
                        /* 实际捕获的业务拒绝异常，用于检查准确拒绝码。 */ exception -> assertThat(exception.code()).isEqualTo("STALE_SETTLEMENT_ID"));

        // 尚无保存进度却从用户一之后开始的非法首轮请求。
        ApplyFundingCommand invalidCursor = new ApplyFundingCommand(105, "1", 10_000, 1, 1);
        assertThatThrownBy(() -> RuntimePerpetualFundingFixture.simulate(marked, invalidCursor,
                List.of(1L, 2L), UUID.fromString("00000000-0000-0000-0000-000000000105"),
                new RuntimeIdentityRegistry()))
                .isInstanceOfSatisfying(CoreStateRejectedException.class,
                        /* 实际捕获的业务拒绝异常，用于检查准确拒绝码。 */ exception -> assertThat(exception.code()).isEqualTo("INVALID_COMMAND"));
    }

    /**
     * U 本位交割核对用户持仓归零、冻结保证金释放及本测试资金总额不变；期权核对两用户总额为一千零四十和九百六十、持仓归零及总额不变，没有另断言期权冻结额。
     */
    @Test
    void deliveryAndOptionSettlementReleaseMarginFlattenPositionsAndConserveFunds() {
        // 到期前两用户等量反向交割持仓的状态。
        TradingCoreState delivery = stateWithOppositePositions(ProductLine.LINEAR_DELIVERY,
                ContractType.LINEAR_DELIVERY, 100, 10, 100);
        // 交割前按本测试口径计算的资金总量。
        long deliveryBefore = total(delivery, "USDT");
        // 按固定价格交割并释放持仓保证金后的状态。
        TradingCoreState delivered = reducer.settleInstrument(delivery,
                new SettleInstrumentCommand(71, "1", 120, 0));

        assertThat(total(delivered, "USDT")).isEqualTo(deliveryBefore);
        assertThat(delivered.users().values()).allSatisfy(/* 当前断言或合计余额、持仓的用户业务值。 */ user -> {
            assertThat(user.positions().get("1").signedQuantitySteps()).isZero();
            assertThat(user.balances().get("USDT").lockedUnits()).isZero();
        });

        // 期权到期前两用户等量反向持仓的状态。
        TradingCoreState option = stateWithOppositePositions(ProductLine.OPTION,
                ContractType.VANILLA_OPTION, 10, 2, 30);
        // 期权到期前本测试口径的资金总量。
        long optionBefore = total(option, "USDT");
        // 期权到期结算后的用户及财库状态。
        TradingCoreState exercised = reducer.settleInstrument(option,
                new SettleInstrumentCommand(72, "1", 120, 25));

        assertThat(total(exercised, "USDT")).isEqualTo(optionBefore);
        assertThat(exercised.user(1).totalUnits("USDT")).isEqualTo(1_040);
        assertThat(exercised.user(2).totalUnits("USDT")).isEqualTo(960);
        assertThat(exercised.users().values()).allSatisfy(/* 当前断言或合计余额、持仓的用户业务值。 */ user ->
                assertThat(user.positions().get("1").signedQuantitySteps()).isZero());
    }

    /**
     * 核对到期结算后仍复用原注册币对对象，并使所有用户该币对持仓归零。
     */
    @Test
    void settlementUsesTheRegisteredCanonicalInstrument() {
        // 本场景当前交易状态，后续赋值表示应用一条业务命令后的新值。
        TradingCoreState state = stateWithOppositePositions(ProductLine.LINEAR_DELIVERY,
                ContractType.LINEAR_DELIVERY, 100, 10, 100);
        // 当前场景原注册的币对配置。
        var instrument = state.instruments().get("1");

        // 已经完成一轮到期结算的状态。
        TradingCoreState settled = reducer.settleInstrument(state,
                new SettleInstrumentCommand(74, "1", 120, 0));

        assertThat(settled.instruments().get("1")).isSameAs(instrument);
        assertThat(settled.users().values()).allSatisfy(/* 当前断言或合计余额、持仓的用户业务值。 */ user ->
                assertThat(user.positions().get("1").signedQuantitySteps()).isZero());
    }

    /**
     * 完成交割后以同编号但不同价格再次请求，要求完整状态相等、持仓归零且冻结额为零；本场景没有预先建立活动订单，不单凭方法名声称覆盖重复撤单。
     */
    @Test
    void duplicateSettlementIsIdempotentAfterPositionsAndOrdersAreClosed() {
        // 本场景当前交易状态，后续赋值表示应用一条业务命令后的新值。
        TradingCoreState state = stateWithOppositePositions(ProductLine.LINEAR_DELIVERY,
                ContractType.LINEAR_DELIVERY, 100, 10, 100);
        // 已经完成一轮到期结算的状态。
        TradingCoreState settled = reducer.settleInstrument(state,
                new SettleInstrumentCommand(73, "1", 120, 999));

        // 相同结算编号再次请求返回的状态，用来与首次结算结果比较。
        TradingCoreState duplicate = reducer.settleInstrument(settled,
                new SettleInstrumentCommand(73, "1", 1, 1));

        assertThat(duplicate).isEqualTo(settled);
        assertThat(duplicate.treasuryState().lifecycleSettlements()).containsEntry("1", 73L);
        assertThat(duplicate.users().values()).allSatisfy(/* 当前断言或合计余额、持仓的用户业务值。 */ user -> {
            assertThat(user.positions().get("1").signedQuantitySteps()).isZero();
            assertThat(user.balances().get("USDT").lockedUnits()).isZero();
        });
    }

    /**
     * 首用户页保存结算游标，经内存快照后续第二用户页，核对进度清除、完成编号和全部持仓归零；没有重复重发同一页。
     */
    @Test
    void lifecycleSettlementCursorPersistsAcrossSnapshotAndCompletesExactlyOnce() {
        // 本场景当前交易状态，后续赋值表示应用一条业务命令后的新值。
        TradingCoreState state = stateWithOppositePositions(ProductLine.LINEAR_DELIVERY,
                ContractType.LINEAR_DELIVERY, 100, 10, 100);
        // 当前资金费或到期结算首个用户页命令，用户游标为零且每页一人。
        SettleInstrumentCommand firstCommand = new SettleInstrumentCommand(96, "1", 120, 0, 0, 1);

        // 第一页处理后的状态、进度和相应业务事实。
        RuntimeTestStateTransitions.SettlementApplication first = reducer.settleInstrumentWithProgress(state,
                firstCommand, List.of(1L, 2L), UUID.fromString("00000000-0000-0000-0000-000000000096"));

        assertThat(first.progress().complete()).isFalse();
        assertThat(first.progress().nextCursorUserId()).isEqualTo(1);
        assertThat(first.state().treasuryState().lifecycleProgress("1")).isNotNull();
        // 当前状态经内存快照编码再解码得到的只读恢复值。
        TradingCoreState restored = TradingStateSnapshotCodec.decode(
                TradingStateSnapshotCodec.encode(first.state()), ProductLine.LINEAR_DELIVERY);
        assertThat(restored).isEqualTo(first.state());

        // 使用第一页面游标处理后续页的结果。
        RuntimeTestStateTransitions.SettlementApplication second = reducer.settleInstrumentWithProgress(restored,
                new SettleInstrumentCommand(96, "1", 120, 0, 1, 1), List.of(1L, 2L),
                UUID.fromString("00000000-0000-0000-0000-000000000097"));

        assertThat(second.progress().complete()).isTrue();
        assertThat(second.state().treasuryState().lifecycleProgress("1")).isNull();
        assertThat(second.state().treasuryState().lifecycleSettlements()).containsEntry("1", 96L);
        assertThat(second.state().users().values()).allSatisfy(/* 当前断言或合计余额、持仓的用户业务值。 */ user ->
                assertThat(user.positions().get("1").signedQuantitySteps()).isZero());
    }

    /**
     * 两笔活动订单每页最多撤一笔，首轮要求订单一百零二撤销、一百零一仍开着且给出订单游标；续页后核对结算完成及完成编号。
     */
    @Test
    void lifecycleSettlementProcessesOpenOrdersInBoundedOrderChunks() {
        // 本场景当前交易状态，后续赋值表示应用一条业务命令后的新值。
        TradingCoreState state = stateWithOppositePositions(ProductLine.LINEAR_DELIVERY,
                ContractType.LINEAR_DELIVERY, 100, 10, 100);
        state = reducer.applyMarkPrice(state,
                new ApplyMarkPriceCommand("1", 100, 1, 1_700_000_000_000L));
        state = reducer.placeOrder(state, 1, lifecycleOrder(101));
        state = reducer.placeOrder(state, 2, lifecycleOrder(102));

        // 第一页处理后的状态、进度和相应业务事实。
        RuntimeTestStateTransitions.SettlementApplication first = reducer.settleInstrumentWithProgress(state,
                new SettleInstrumentCommand(97, "1", 120, 0, 0, 256, 0, 1),
                List.of(1L, 2L), UUID.fromString("00000000-0000-0000-0000-000000000097"));

        assertThat(first.progress().complete()).isFalse();
        assertThat(first.progress().ordersComplete()).isFalse();
        assertThat(first.progress().nextCursorOrderId()).isEqualTo(102);
        assertThat(first.state().order(102).status()).isEqualTo(CoreOrderStatus.CANCELED);
        assertThat(first.state().order(101).status()).isEqualTo(CoreOrderStatus.OPEN);

        // 使用第一页面游标处理后续页的结果。
        RuntimeTestStateTransitions.SettlementApplication second = reducer.settleInstrumentWithProgress(first.state(),
                new SettleInstrumentCommand(97, "1", 120, 0, 0, 256, 102, 1),
                List.of(1L, 2L), UUID.fromString("00000000-0000-0000-0000-000000000098"));

        assertThat(second.progress().complete()).isTrue();
        assertThat(second.state().order(102).status()).isEqualTo(CoreOrderStatus.CANCELED);
        assertThat(second.state().treasuryState().lifecycleSettlements()).containsEntry("1", 97L);
    }

    /**
     * 极端跌价触发强平后要求持仓归零并留下显式亏空；增加对应保险后按保险回执处理，核对亏空清除、用户归零和强平完成。
     */
    @Test
    void liquidationCreatesExplicitDeficitAndInsuranceReceiptClosesIt() {
        // 本场景当前交易状态，后续赋值表示应用一条业务命令后的新值。
        TradingCoreState state = stateWithUser(ProductLine.LINEAR_PERPETUAL,
                ContractType.LINEAR_PERPETUAL, 1, 10, 100, 100, 100);
        state = reducer.applyMarkPrice(state, new ApplyMarkPriceCommand("1", 1, 1,
                1_700_000_000_000L));
        // 由标记价扫描产生、等待执行的强平计划。
        CoreLiquidationState plan = state.riskState().liquidations().get(1L);
        assertThat(plan.status()).isEqualTo(CoreLiquidationState.Status.PLANNED);

        // 强平执行后持仓已处理且可能留下亏空的状态。
        TradingCoreState liquidated = reducer.executeLiquidation(state,
                new ExecuteLiquidationCommand(1, 1, 1, 0));

        assertThat(liquidated.user(1).positions().get("1").signedQuantitySteps()).isZero();
        assertThat(liquidated.riskState().liquidations().get(1L).status())
                .isEqualTo(CoreLiquidationState.Status.INSURANCE_REQUIRED);
        // 当前强平记录尚需覆盖的亏空金额。
        long deficit = liquidated.riskState().liquidations().get(1L).deficitUnits();
        assertThat(deficit).isPositive();

        // 资金费或保险调整后的业务状态。
        TradingCoreState funded = reducer.adjustInsuranceFund(liquidated,
                new com.surprising.aeron.protocol.AdjustInsuranceFundCommand("USDT", deficit));
        // 保险回执或 ADL 处理完成后的业务状态。
        TradingCoreState resolved = reducer.resolveLiquidation(funded,
                new ResolveLiquidationCommand(1, ResolveLiquidationCommand.Resolution.INSURANCE, deficit));
        assertThat(resolved.treasuryState().insuranceBalances()).containsEntry("USDT", 100L);
        assertThat(resolved.treasuryState().insuranceDeficits().getOrDefault("USDT", 0L)).isZero();
        assertThat(resolved.user(1).totalUnits("USDT")).isZero();
        assertThat(resolved.riskState().liquidations().get(1L).status())
                .isEqualTo(CoreLiquidationState.Status.COMPLETED);
    }

    /**
     * 已有正亏空时直接提交完成回执，状态辅助器和运行时模拟都必须拒绝，原记录仍等待保险。
     */
    @Test
    void liquidationCannotCompleteWhileDeficitRemains() {
        // 本场景当前交易状态，后续赋值表示应用一条业务命令后的新值。
        TradingCoreState state = stateWithUser(ProductLine.LINEAR_PERPETUAL,
                ContractType.LINEAR_PERPETUAL, 1, 10, 100, 100, 100);
        state = reducer.applyMarkPrice(state, new ApplyMarkPriceCommand("1", 1, 1,
                1_700_000_000_000L));
        // 强平执行后持仓已处理且可能留下亏空的状态。
        TradingCoreState liquidated = reducer.executeLiquidation(state,
                new ExecuteLiquidationCommand(1, 1, 1, 0));
        // 在尚有亏空时直接要求强平完成的非法回执命令。
        ResolveLiquidationCommand command = new ResolveLiquidationCommand(
                1, ResolveLiquidationCommand.Resolution.COMPLETED, 0);

        assertThatThrownBy(() -> reducer.resolveLiquidation(liquidated, command))
                .isInstanceOfSatisfying(CoreStateRejectedException.class,
                        /* 实际捕获的业务拒绝异常，用于检查准确拒绝码。 */ exception -> assertThat(exception.code()).isEqualTo("LIQUIDATION_DEFICIT_REMAINS"));
        assertThatThrownBy(() -> RuntimeDerivativeLiquidationFixture.simulateResolution(
                liquidated, command, new RuntimeIdentityRegistry()))
                .isInstanceOfSatisfying(CoreStateRejectedException.class,
                        /* 实际捕获的业务拒绝异常，用于检查准确拒绝码。 */ exception -> assertThat(exception.code()).isEqualTo("LIQUIDATION_DEFICIT_REMAINS"));
        assertThat(liquidated.riskState().liquidations().get(1L).status())
                .isEqualTo(CoreLiquidationState.Status.INSURANCE_REQUIRED);
    }

    /**
     * 提交与计划不符的价格序号，要求拒绝为过期标记价，并核对原业务状态哈希与计划状态不变。
     */
    @Test
    void liquidationRejectsStaleTriggerSequenceWithoutMutation() {
        // 本场景当前交易状态，后续赋值表示应用一条业务命令后的新值。
        TradingCoreState state = stateWithUser(ProductLine.LINEAR_PERPETUAL,
                ContractType.LINEAR_PERPETUAL, 1, 10, 100, 180, 100);
        state = reducer.applyMarkPrice(state, new ApplyMarkPriceCommand("1", 90, 1,
                1_700_000_000_000L));
        // 拒绝执行前保存的原业务状态哈希。
        long hash = state.businessStateHash();
        // 供异常断言捕获的原计划状态引用。
        TradingCoreState planned = state;

        assertThatThrownBy(() -> reducer.executeLiquidation(planned,
                new ExecuteLiquidationCommand(1, 2, 90, 100_000)))
                .isInstanceOfSatisfying(CoreStateRejectedException.class,
                        /* 实际捕获的业务拒绝异常，用于检查准确拒绝码。 */ exception -> assertThat(exception.code()).isEqualTo("STALE_MARK_PRICE"));
        assertThat(planned.businessStateHash()).isEqualTo(hash);
        assertThat(planned.riskState().liquidations().get(1L).status())
                .isEqualTo(CoreLiquidationState.Status.PLANNED);
    }

    /**
     * 标记价恢复后要求取消原强平计划而保留持仓数量，并与运行时风险模拟的业务状态对照。
     */
    @Test
    void riskRecoveryCancelsPlanWithoutClosingPosition() {
        // 本场景当前交易状态，后续赋值表示应用一条业务命令后的新值。
        TradingCoreState state = stateWithUser(ProductLine.LINEAR_PERPETUAL,
                ContractType.LINEAR_PERPETUAL, 1, 10, 100, 180, 100);
        state = reducer.applyMarkPrice(state, new ApplyMarkPriceCommand("1", 90, 1,
                1_700_000_000_000L));

        // 把标记价恢复到开仓价并推进价格序号的命令。
        ApplyMarkPriceCommand command = new ApplyMarkPriceCommand(
                "1", 100, 2, 1_700_000_000_000L);
        // 风险恢复后取消原强平计划但保留持仓的期望状态。
        TradingCoreState recovered = reducer.applyMarkPrice(state, command);
        // 独立运行时投影或业务状态对照使用的身份表。
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        RuntimeStateParityChecker.assertMatches(recovered, identities,
                RuntimeDerivativeRiskFixture.simulateMarkPrice(
                        state, command, state.users().keySet(), identities));

        assertThat(recovered.riskState().liquidations().get(1L).status())
                .isEqualTo(CoreLiquidationState.Status.CANCELED);
        assertThat(recovered.user(1).positions().get("1").signedQuantitySteps()).isEqualTo(10);
    }

    /**
     * 强平费率对应金额超过可用抵押时，实际只收八十并计入保险，用户资金归零、风险快照清除；同时核对本测试资金总额及内存快照往返哈希。
     */
    @Test
    void liquidationFeeIsCappedByCollateralAndCreditedToInsurance() {
        // 本场景当前交易状态，后续赋值表示应用一条业务命令后的新值。
        TradingCoreState state = stateWithUser(ProductLine.LINEAR_PERPETUAL,
                ContractType.LINEAR_PERPETUAL, 1, 10, 100, 180, 100);
        state = reducer.applyMarkPrice(state, new ApplyMarkPriceCommand("1", 90, 1,
                1_700_000_000_000L));
        // 强平前按本测试资金口径保存的总额。
        long before = total(state, "USDT");

        // 强平执行后持仓已处理且可能留下亏空的状态。
        TradingCoreState liquidated = reducer.executeLiquidation(state,
                new ExecuteLiquidationCommand(1, 1, 90, 100_000));

        // 执行强平后保存实际费用、执行价和终态的业务记录。
        CoreLiquidationState result = liquidated.riskState().liquidations().get(1L);
        assertThat(result.status()).isEqualTo(CoreLiquidationState.Status.COMPLETED);
        assertThat(result.executionPriceTicks()).isEqualTo(90);
        assertThat(result.liquidationFeeRatePpm()).isEqualTo(100_000);
        assertThat(result.liquidationFeeUnits()).isEqualTo(80);
        assertThat(liquidated.treasuryState().insuranceBalances()).containsEntry("USDT", 180L);
        assertThat(liquidated.user(1).totalUnits("USDT")).isZero();
        assertThat(total(liquidated, "USDT")).isEqualTo(before);
        assertThat(reducer.riskSnapshots(liquidated, 1)).isEmpty();
        // 当前状态经内存快照编码再解码得到的只读恢复值。
        TradingCoreState restored = TradingStateSnapshotCodec.decode(
                TradingStateSnapshotCodec.encode(liquidated), ProductLine.LINEAR_PERPETUAL);
        assertThat(restored).isEqualTo(liquidated);
        assertThat(restored.businessStateHash()).isEqualTo(liquidated.businessStateHash());
    }

    /**
     * 补入部分保险并按分配策略处理，要求可用保险耗尽、记录只保留未覆盖亏空，并转为等待 ADL。
     */
    @Test
    void partialInsuranceCoverageLeavesOnlyResidualForAdl() {
        // 本场景当前交易状态，后续赋值表示应用一条业务命令后的新值。
        TradingCoreState state = stateWithUser(ProductLine.LINEAR_PERPETUAL,
                ContractType.LINEAR_PERPETUAL, 1, 10, 100, 100, 100);
        state = reducer.applyMarkPrice(state, new ApplyMarkPriceCommand("1", 1, 1,
                1_700_000_000_000L));
        // 强平执行后持仓已处理且可能留下亏空的状态。
        TradingCoreState liquidated = reducer.executeLiquidation(state,
                new ExecuteLiquidationCommand(1, 1, 1, 0));
        // 当前强平记录尚需覆盖的亏空金额。
        long deficit = liquidated.riskState().liquidations().get(1L).deficitUnits();
        // 资金费或保险调整后的业务状态。
        TradingCoreState funded = reducer.adjustInsuranceFund(liquidated,
                new com.surprising.aeron.protocol.AdjustInsuranceFundCommand("USDT", 25));
        // 按当前保险分配规则为本强平记录算出的可覆盖金额。
        long coverage = InsuranceAllocationPolicy.expectedCoverage(funded, 1);

        // 保险回执或 ADL 处理完成后的业务状态。
        TradingCoreState resolved = reducer.resolveLiquidation(funded,
                new ResolveLiquidationCommand(1, ResolveLiquidationCommand.Resolution.INSURANCE, coverage));

        assertThat(resolved.treasuryState().insuranceBalances().getOrDefault("USDT", 0L)).isZero();
        assertThat(resolved.riskState().liquidations().get(1L).deficitUnits()).isEqualTo(deficit - coverage);
        assertThat(resolved.riskState().liquidations().get(1L).status())
                .isEqualTo(CoreLiquidationState.Status.ADL_REQUIRED);
    }

    /**
     * 构造两个亏空记录，核对按比例及固定余数规则分配；反向输入编号仍获得相同结果，越序保险回执拒绝。这里只改变候选列表顺序，没有并发启动两个 Lane 来测执行顺序。
     */
    @Test
    void insuranceShortfallIsProRataAndIndependentOfLaneLiquidationOrder() {
        // 本场景当前交易状态，后续赋值表示应用一条业务命令后的新值。
        TradingCoreState state = stateWithUser(ProductLine.LINEAR_PERPETUAL,
                ContractType.LINEAR_PERPETUAL, 1, 10, 100, 100, 100);
        state = withPositionAndBalance(state, 2, 10, 100, 100, 100);
        state = reducer.applyMarkPrice(state, new ApplyMarkPriceCommand("1", 1, 1,
                1_700_000_000_000L));
        for (CoreLiquidationState /* 由标记价扫描产生、等待执行的强平计划。 */ plan : List.copyOf(state.riskState().liquidations().values())) {
            state = reducer.executeLiquidation(state,
                    new ExecuteLiquidationCommand(plan.liquidationId(), plan.triggerPriceSequence(), 1, 0));
        }
        state = reducer.adjustInsuranceFund(state,
                new com.surprising.aeron.protocol.AdjustInsuranceFundCommand("USDT", 1));

        // 属于用户一的强平亏空记录。
        CoreLiquidationState firstUser = state.riskState().liquidations().values().stream()
                .filter(/* 按所属用户筛选的当前强平记录。 */ value -> value.userId() == 1).findFirst().orElseThrow();
        // 属于用户二的强平亏空记录。
        CoreLiquidationState secondUser = state.riskState().liquidations().values().stream()
                .filter(/* 按所属用户筛选的当前强平记录。 */ value -> value.userId() == 2).findFirst().orElseThrow();
        // 用户一按固定比例与余数规则可分得的保险。
        long firstCoverage = InsuranceAllocationPolicy.expectedCoverage(state, firstUser.liquidationId());
        // 用户二按相同规则可分得的保险。
        long secondCoverage = InsuranceAllocationPolicy.expectedCoverage(state, secondUser.liquidationId());

        // 独立运行时投影或业务状态对照使用的身份表。
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        // 用于核对反向编号列表分配结果的独立运行时。
        TradingRuntimeState runtime = RuntimeStateProjector.project(state, identities);
        // 故意反转的候选强平编号列表，用来检查输入次序不改变分配。
        var reverseIds = new java.util.ArrayList<>(state.riskState().liquidations().keySet());
        java.util.Collections.reverse(reverseIds);
        // 对反向候选列表一次性求出的全部保险分配结果。
        var allAllocations = InsuranceAllocationPolicy.allocations(runtime, identities, reverseIds);
        for (long /* 当前要比较逐项保险覆盖结果的强平编号。 */ id : reverseIds) {
            assertThat(InsuranceAllocationPolicy.expectedCoverage(runtime, identities, reverseIds, id))
                    .isEqualTo(allAllocations.get(id))
                    .isEqualTo(InsuranceAllocationPolicy.expectedCoverage(state, id));
        }
        assertThat(InsuranceAllocationPolicy.isNext(runtime, identities, reverseIds, firstUser.liquidationId()))
                .isTrue();
        assertThat(InsuranceAllocationPolicy.isNext(runtime, identities, reverseIds, secondUser.liquidationId()))
                .isFalse();

        assertThat(firstCoverage).isEqualTo(secondCoverage + 1);
        assertThat(firstCoverage + secondCoverage)
                .isEqualTo(state.treasuryState().insuranceBalances().get("USDT"));
        // 尚未应用第一条保险回执的状态，供越序拒绝断言使用。
        TradingCoreState beforeAllocation = state;
        assertThatThrownBy(() -> reducer.resolveLiquidation(beforeAllocation,
                new ResolveLiquidationCommand(secondUser.liquidationId(),
                        ResolveLiquidationCommand.Resolution.INSURANCE, secondCoverage + 1)))
                .isInstanceOfSatisfying(CoreStateRejectedException.class,
                        /* 实际捕获的业务拒绝异常，用于检查准确拒绝码。 */ exception -> assertThat(exception.code()).isEqualTo("INSURANCE_RESOLUTION_ORDER_MISMATCH"));

        // 已经应用当前保险回执后的状态，后续重新求剩余覆盖额。
        TradingCoreState allocated = reducer.resolveLiquidation(beforeAllocation,
                new ResolveLiquidationCommand(firstUser.liquidationId(),
                        ResolveLiquidationCommand.Resolution.INSURANCE, firstCoverage));
        // 首条分配已应用后，第二条记录当前应获的剩余保险额。
        long remainingCoverage = InsuranceAllocationPolicy.expectedCoverage(
                allocated, secondUser.liquidationId());
        allocated = reducer.resolveLiquidation(allocated,
                new ResolveLiquidationCommand(secondUser.liquidationId(),
                        ResolveLiquidationCommand.Resolution.INSURANCE, remainingCoverage));
        assertThat(allocated.riskState().liquidations().get(secondUser.liquidationId()).status())
                .isEqualTo(CoreLiquidationState.Status.ADL_REQUIRED);
    }

    /**
     * 在保险部分覆盖后用盈利对手方执行一次成功 ADL，核对其空仓减半、亏空归零、强平完成及包含未实现盈亏的测试权益不变，并作运行时状态对照；没有注入中途故障来证明原子回滚。
     */
    @Test
    void adlAtomicallyDeleveragesProfitableCounterpartyAndCoversResidual() {
        // 本场景当前交易状态，后续赋值表示应用一条业务命令后的新值。
        TradingCoreState state = stateWithUser(ProductLine.LINEAR_PERPETUAL,
                ContractType.LINEAR_PERPETUAL, 1, 10, 100, 100, 100);
        state = withPositionAndBalance(state, 2, -10, 200, 1_000, 100);
        state = reducer.applyMarkPrice(state, new ApplyMarkPriceCommand("1", 1, 1,
                1_700_000_000_000L));
        state = reducer.executeLiquidation(state, new ExecuteLiquidationCommand(1, 1, 1, 0));
        // 当前强平记录尚需覆盖的亏空金额。
        long deficit = state.riskState().liquidations().get(1L).deficitUnits();
        state = reducer.adjustInsuranceFund(state,
                new com.surprising.aeron.protocol.AdjustInsuranceFundCommand("USDT", 25));
        // 按当前保险分配规则为本强平记录算出的可覆盖金额。
        long coverage = InsuranceAllocationPolicy.expectedCoverage(state, 1);
        state = reducer.resolveLiquidation(state,
                new ResolveLiquidationCommand(1, ResolveLiquidationCommand.Resolution.INSURANCE, coverage));
        // 成功 ADL 之前按本测试口径加未实现盈亏的经济权益。
        long before = totalEconomicEquity(state, "USDT");
        // 保存的 ADL 前状态引用，当前成功用例后续没有再读取此局部变量。
        TradingCoreState beforeAdl = state;

        // 从盈利用户二的空仓中减去五份并覆盖剩余亏空的 ADL 请求。
        var command = new com.surprising.aeron.protocol.ExecuteAdlCommand(1, 2, "1",
                com.surprising.aeron.protocol.CoreMarginMode.CROSS,
                com.surprising.aeron.protocol.CorePositionSide.NET,
                -10, 200, 1, 5, deficit - coverage);
        // 独立运行时投影或业务状态对照使用的身份表。
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        // 独立运行时 ADL 模拟得到的实际状态。
        TradingRuntimeState runtimeResolved = RuntimeDerivativeLiquidationFixture.simulateAdl(
                state, command, identities);
        // 保险回执或 ADL 处理完成后的业务状态。
        TradingCoreState resolved = reducer.executeAdl(state, command);
        RuntimeStateParityChecker.assertMatches(resolved, identities, runtimeResolved);

        assertThat(resolved.user(2).positions().get("1").signedQuantitySteps()).isEqualTo(-5);
        assertThat(resolved.riskState().liquidations().get(1L).deficitUnits()).isZero();
        assertThat(resolved.riskState().liquidations().get(1L).status())
                .isEqualTo(CoreLiquidationState.Status.COMPLETED);
        assertThat(totalEconomicEquity(resolved, "USDT")).isEqualTo(before);
    }

    /**
     * 分别改变预期持仓数量、价格序号及可供覆盖利润，要求运行时 ADL 返回持仓冲突、过期标记价和利润不足的业务拒绝码。
     */
    @Test
    void runtimeAdlRejectsStaleMarkChangedPositionAndInsufficientProfit() {
        // 本场景当前交易状态，后续赋值表示应用一条业务命令后的新值。
        TradingCoreState state = stateWithUser(ProductLine.LINEAR_PERPETUAL,
                ContractType.LINEAR_PERPETUAL, 1, 10, 100, 100, 100);
        state = withPositionAndBalance(state, 2, -10, 200, 1_000, 100);
        state = reducer.applyMarkPrice(state, new ApplyMarkPriceCommand("1", 1, 1,
                1_700_000_000_000L));
        state = reducer.executeLiquidation(state, new ExecuteLiquidationCommand(1, 1, 1, 0));
        state = reducer.adjustInsuranceFund(state,
                new com.surprising.aeron.protocol.AdjustInsuranceFundCommand("USDT", 25));
        // 按当前保险分配规则为本强平记录算出的可覆盖金额。
        long coverage = InsuranceAllocationPolicy.expectedCoverage(state, 1);
        // 已应用部分保险、仍有 ADL 亏空的拒绝场景前置状态。
        TradingCoreState beforeAdl = reducer.resolveLiquidation(state,
                new ResolveLiquidationCommand(1, ResolveLiquidationCommand.Resolution.INSURANCE, coverage));
        // 部分保险之后剩余、要求 ADL 覆盖的亏空。
        long residual = beforeAdl.riskState().liquidations().get(1L).deficitUnits();

        assertThatThrownBy(() -> RuntimeDerivativeLiquidationFixture.simulateAdl(beforeAdl,
                new com.surprising.aeron.protocol.ExecuteAdlCommand(1, 2, "1",
                        com.surprising.aeron.protocol.CoreMarginMode.CROSS,
                        com.surprising.aeron.protocol.CorePositionSide.NET,
                        -9, 200, 1, 5, residual), new RuntimeIdentityRegistry()))
                .isInstanceOfSatisfying(CoreStateRejectedException.class,
                        /* 实际捕获的业务拒绝异常，用于检查准确拒绝码。 */ exception -> assertThat(exception.code()).isEqualTo("ADL_POSITION_CONFLICT"));

        assertThatThrownBy(() -> RuntimeDerivativeLiquidationFixture.simulateAdl(beforeAdl,
                new com.surprising.aeron.protocol.ExecuteAdlCommand(1, 2, "1",
                        com.surprising.aeron.protocol.CoreMarginMode.CROSS,
                        com.surprising.aeron.protocol.CorePositionSide.NET,
                        -10, 200, 2, 5, residual), new RuntimeIdentityRegistry()))
                .isInstanceOfSatisfying(CoreStateRejectedException.class,
                        /* 实际捕获的业务拒绝异常，用于检查准确拒绝码。 */ exception -> assertThat(exception.code()).isEqualTo("STALE_MARK_PRICE"));

        assertThatThrownBy(() -> RuntimeDerivativeLiquidationFixture.simulateAdl(beforeAdl,
                new com.surprising.aeron.protocol.ExecuteAdlCommand(1, 2, "1",
                        com.surprising.aeron.protocol.CoreMarginMode.CROSS,
                        com.surprising.aeron.protocol.CorePositionSide.NET,
                        -10, 200, 1, 1, residual), new RuntimeIdentityRegistry()))
                .isInstanceOfSatisfying(CoreStateRejectedException.class,
                        /* 实际捕获的业务拒绝异常，用于检查准确拒绝码。 */ exception -> assertThat(exception.code()).isEqualTo("ADL_PROFIT_INSUFFICIENT"));
    }

    /**
     * 构造用户一正向与用户二等量反向持仓，二者初始钱包各一千且保证金相同，供本组 USDT 结算测试使用。
     * @param productLine 本测试前置状态使用的独立产品线
     * @param type 与该产品线匹配的合约类型
     * @param entryPrice 测试持仓的开仓价格
     * @param quantity 持仓有符号数量，负数为空仓
     * @param margin 测试要冻结为持仓保证金的金额
     */
    private TradingCoreState stateWithOppositePositions(
            ProductLine productLine,
            ContractType type,
            long entryPrice,
            long quantity,
            long margin) {
        // 本场景当前交易状态，后续赋值表示应用一条业务命令后的新值。
        TradingCoreState state = stateWithUser(productLine, type, 1, quantity, entryPrice, 1_000, margin);
        return withPositionAndBalance(state, 2, -quantity, entryPrice, 1_000, margin);
    }

    /**
     * 注册本组测试币对并构造一个带持仓的用户；交割与期权填固定到期日，资产配置固定使用 USDT，不是通用六产品资金工厂。
     * @param productLine 本测试前置状态使用的独立产品线
     * @param type 与该产品线匹配的合约类型
     * @param userId 要构造或覆盖持仓余额的用户编号
     * @param quantity 持仓有符号数量，负数为空仓
     * @param entryPrice 测试持仓的开仓价格
     * @param wallet 覆盖后该用户 USDT 的可用与冻结总额
     * @param margin 测试要冻结为持仓保证金的金额
     */
    private TradingCoreState stateWithUser(
            ProductLine productLine,
            ContractType type,
            long userId,
            long quantity,
            long entryPrice,
            long wallet,
            long margin) {
        // 交割或期权测试币对使用的固定未来到期时刻，永续则为零。
        long expiry = type.isDelivery() || type.isOption() ? 2_000_000_000_000L : 0;
        // 本场景当前交易状态，后续赋值表示应用一条业务命令后的新值。
        TradingCoreState state = reducer.registerInstrument(TradingCoreState.empty(productLine),
                new com.surprising.aeron.protocol.RegisterInstrumentCommand("1", type.ordinal(),
                        "BTC", "USDT", "USDT", 1, 1, 1, 100_000, 100_000, 0, 0,
                        expiry, type.isOption() ? 0 : -1, type.isOption() ? 100 : 0));
        return withPositionAndBalance(state, userId, quantity, entryPrice, wallet, margin);
    }

    /**
     * 先通过原辅助器调整余额，再显式覆盖该用户 USDT 可用/冻结额和币对一净持仓，推进用户与全局修订号以构造所需测试前置状态；不通过真实订单撮合开仓。
     * @param state 要构造后续状态或读取测试资金的只读交易状态
     * @param userId 要构造或覆盖持仓余额的用户编号
     * @param quantity 持仓有符号数量，负数为空仓
     * @param entryPrice 测试持仓的开仓价格
     * @param wallet 覆盖后该用户 USDT 的可用与冻结总额
     * @param margin 测试要冻结为持仓保证金的金额
     */
    private TradingCoreState withPositionAndBalance(
            TradingCoreState state,
            long userId,
            long quantity,
            long entryPrice,
            long wallet,
            long margin) {
        // 通过余额调整辅助入口创建用户或增加资金后的前置状态，随后显式覆盖余额和持仓。
        TradingCoreState funded = reducer.adjustBalance(state, userId,
                new BalanceAdjustmentCommand("USDT", wallet));
        // 显式覆盖前取得的用户原状态和修订号。
        CoreUserState current = funded.user(userId);
        // 复制原用户余额后，显式覆盖 USDT 可用和冻结额的测试表。
        Map<String, AssetBalance> balances = new TreeMap<>(current.balances());
        balances.put("USDT", new AssetBalance("USDT", wallet - margin, margin));
        // 复制原用户持仓后，显式覆盖币对一净持仓的测试表。
        Map<String, CorePositionState> positions = new TreeMap<>(current.positions());
        positions.put("1", new CorePositionState("1", "USDT", quantity,
                entryPrice, Math.multiplyExact(Math.absExact(quantity), entryPrice), 0, margin));
        // 用指定钱包和持仓数据新构造的用户业务值。
        CoreUserState user = new CoreUserState(funded.productLine(), userId, current.revision() + 1,
                balances, current.reservations(), positions);
        // 复制旧用户表后替换当前用户的测试集合。
        Map<Long, CoreUserState> users = new TreeMap<>(funded.users());
        users.put(userId, user);
        return new TradingCoreState(funded.productLine(), funded.revision() + 1, users, funded.orders(),
                funded.instruments(), funded.riskState(), funded.treasuryState());
    }

    /**
     * 构造币对一、买价十、数量一的普通限价 GTC 订单，用于到期撤单分页测试。
     * @param orderId 到期撤单场景的订单编号
     */
    private static PlaceOrderCommand lifecycleOrder(long orderId) {
        return new PlaceOrderCommand(orderId, "1", CoreOrderSide.BUY, 10, 1, false, CoreMarginMode.CROSS, CorePositionSide.NET, com.surprising.aeron.protocol.CoreOrderType.LIMIT, com.surprising.aeron.protocol.CoreTimeInForce.GTC, false, "");
    }

    /**
     * 按本组样例口径求用户总额加手续费、保险减亏空；未纳入资金费剩余、舍入、强平费及清算盈亏等其他子账。用户 stream 求和使用普通 long，后续加减用精确算术。
     * @param state 要构造后续状态或读取测试资金的只读交易状态
     * @param asset 本次测试合计的结算资产名称
     */
    private static long total(TradingCoreState state, String asset) {
        // 按资产累计的全部用户可用与冻结总额，stream 内部使用普通 long 求和。
        long users = state.users().values().stream().mapToLong(/* 当前断言或合计余额、持仓的用户业务值。 */ user -> user.totalUnits(asset)).sum();
        // 指定资产的财库手续费余额。
        long fee = state.treasuryState().feeBalances().getOrDefault(asset, 0L);
        // 指定资产的财库保险余额。
        long insurance = state.treasuryState().insuranceBalances().getOrDefault(asset, 0L);
        // 指定资产在财库保险亏空子账中的金额。
        long deficit = state.treasuryState().insuranceDeficits().getOrDefault(asset, 0L);
        return Math.subtractExact(Math.addExact(Math.addExact(users, fee), insurance), deficit);
    }

    /**
     * 在本测试 total 口径上，加上结算资产匹配、持仓非零且存在标记价的未实现盈亏，供成功 ADL 前后权益对照。
     * @param state 要构造后续状态或读取测试资金的只读交易状态
     * @param asset 本次测试合计的结算资产名称
     */
    private static long totalEconomicEquity(TradingCoreState state, String asset) {
        // 匹配当前结算资产的全部非零持仓未实现盈亏累计值。
        long unrealized = 0;
        for (CoreUserState /* 当前断言或合计余额、持仓的用户业务值。 */ user : state.users().values()) {
            for (CorePositionState /* 当前参与未实现盈亏合计的持仓业务值。 */ position : user.positions().values()) {
                // 当前场景原注册的币对配置。
                CoreInstrument instrument = state.instruments().get(position.instrumentId());
                // 当前持仓币对保存的标记价，缺失时不计入本测试权益。
                CoreMarkPriceState mark = state.riskState().markPrices().get(position.instrumentId());
                if (position.signedQuantitySteps() != 0 && mark != null && instrument.settleAsset().equals(asset)) {
                    unrealized = Math.addExact(unrealized, CoreContractMath.pnlUnits(instrument,
                            position.signedQuantitySteps(), position.entryPriceTicks(), mark.markPriceTicks()));
                }
            }
        }
        return Math.addExact(total(state, asset), unrealized);
    }
}
