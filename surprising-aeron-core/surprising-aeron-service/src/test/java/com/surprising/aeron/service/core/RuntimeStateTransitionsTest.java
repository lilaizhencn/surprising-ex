package com.surprising.aeron.service.core;

import com.surprising.aeron.service.account.AccountControlTask;
import com.surprising.aeron.protocol.CoreRiskLimitBracket;

import com.surprising.aeron.protocol.CoreInstrumentRiskLimits;

import com.surprising.aeron.model.TradingCoreState;
import com.surprising.aeron.protocol.BalanceAdjustmentCommand;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CorePositionMode;
import com.surprising.aeron.protocol.RegisterInstrumentCommand;
import com.surprising.aeron.protocol.TransferFundsCommand;
import com.surprising.aeron.protocol.UpdateLeverageCommand;
import com.surprising.aeron.protocol.UpdatePositionModeCommand;
import com.surprising.aeron.protocol.UpdateRiskScanControlCommand;
import com.surprising.aeron.service.account.AccountControlTask;
import com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.instrument.command.InstrumentConfigurationCommands;
import com.surprising.aeron.service.order.state.RuntimeTestStateTransitions;
import com.surprising.aeron.service.snapshot.state.RuntimeStateMaterializer;
import com.surprising.aeron.service.snapshot.state.RuntimeStateProjector;
import com.surprising.product.api.ProductLine;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 直接调用运行时转换入口验证划转、余额、风险控制、标的、持仓模式及杠杆，并将物化结果和冷转换辅助比较。辅助本身复用既有业务实现，不是独立金融公式；这些用例主要覆盖未激活的冷路径。
 */
class RuntimeStateTransitionsTest {

    /**
     * 恢复五条未完成划转，按每页两条依次读到一二、三四、五，再回绕到一二，确认查询游标不删除待完成事实。
     */
    @Test
    void pendingQueriesAdvancePastPermanentlyPendingPrefixAndWrap() {
        // 当前用例变更前的完整状态，作为投影起点及未变断言基线
        var before = TradingCoreState.empty(ProductLine.SPOT);
        // 本次投影独用的整数身份表，与对应运行时成对使用
        var identities = new RuntimeIdentityRegistry();
        try (/* 从变更前状态投影的冷运行时，供直接转换和结果物化使用 */ var runtime = RuntimeStateProjector.project(before, identities)) {
            // 只为分页用例构造的五条待转记录，恢复后保留全部事实
            var pending = new java.util.TreeMap<Long, com.surprising.aeron.protocol.TransferFundsCommand>();
            for (/* 当前生成的待转业务 ID，从一到五 */ long id = 1; id <= 5; id++) pending.put(id,
                    new TransferFundsCommand(id, ProductLine.SPOT, ProductLine.LINEAR_PERPETUAL,
                                    "SPOT", "USDT_PERPETUAL", "USDT", 1, "r" + id, "", 7L, 7L));
            runtime.restorePendingTransfers(pending);
            assertThat(runtime.pendingTransfers(2)).extracting(/* 分页中抽取业务 ID 的当前划转 */ t -> t.transferId()).containsExactly(1L, 2L);
            assertThat(runtime.pendingTransfers(2)).extracting(/* 分页中抽取业务 ID 的当前划转 */ t -> t.transferId()).containsExactly(3L, 4L);
            assertThat(runtime.pendingTransfers(2)).extracting(/* 分页中抽取业务 ID 的当前划转 */ t -> t.transferId()).containsExactly(5L);
            assertThat(runtime.pendingTransfers(2)).extracting(/* 分页中抽取业务 ID 的当前划转 */ t -> t.transferId()).containsExactly(1L, 2L);
            assertThat(runtime.pendingTransfersSnapshot()).isEqualTo(pending);
        }
    }

    /**
     * 向跨产品线待转账扣除二百五十，重复转出不再扣款；完成后移除待转记录，重复完成不再改变状态。
     */
    @Test
    void productTransferUsesOnlyBoundedRuntimeState() {
        // 当前用例变更前的完整状态，作为投影起点及未变断言基线
        TradingCoreState before = new RuntimeTestStateTransitions().adjustBalance(
                TradingCoreState.empty(ProductLine.SPOT), 7,
                new BalanceAdjustmentCommand("USDT", 1_000));
        // 本次投影独用的整数身份表，与对应运行时成对使用
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        // 从变更前状态投影的冷运行时，供直接转换和结果物化使用
        TradingAccountRuntime runtime = RuntimeStateProjector.project(before, identities);
        // 同一用户七从现货向正向永续划转二百五十的请求，目标或源用例分别执行相应一步
        TransferFundsCommand transfer = new TransferFundsCommand(91L, ProductLine.SPOT,
                ProductLine.LINEAR_PERPETUAL, "FUNDING", "USDT_PERPETUAL", "USDT", 250L,
                "transfer-91", "product allocation", 7L, 7L);

        var task = new AccountControlTask();
        LedgerControlTestSupport.await(runtime, task.prepareTransferOut(runtime, identities, 7L, transfer));
        assertThat(task.stateChanged()).isTrue();
        assertThat(runtime.pendingTransfer(91L)).isNotNull();
        assertThat(runtime.balance(7L, identities.assetId("USDT")).availableUnits()).isEqualTo(750L);
        long beforeRetry = runtime.revision();
        LedgerControlTestSupport.await(runtime, task.prepareTransferOut(runtime, identities, 7L, transfer));
        assertThat(runtime.revision()).isEqualTo(beforeRetry);
        assertThat(runtime.balance(7L, identities.assetId("USDT")).availableUnits()).isEqualTo(750L);

        assertThat(AccountControlTask.completeTransfer(runtime, 7L, 91L)).isTrue();
        assertThat(runtime.pendingTransfer(91L)).isNull();
        assertThat(AccountControlTask.completeTransfer(runtime, 7L, 91L)).isFalse();
    }

    /**
     * 在目标永续运行时直接执行一次转入，断言收款用户增加二百五十；此用例没有另行验证重复转入去重。
     */
    @Test
    void transferInCreditsTargetRuntime() {
        var transfer = new TransferFundsCommand(91L, ProductLine.SPOT, ProductLine.LINEAR_PERPETUAL,
                "SPOT", "USDT_PERPETUAL", "USDT", 250, "transfer-91", "product allocation", 7, 7);
        try (var core = new TradingCoreRuntime(ProductLine.LINEAR_PERPETUAL)) {
            var message = new com.surprising.aeron.protocol.CoreMessage(
                    com.surprising.aeron.protocol.CoreMessageHeader.command(
                            com.surprising.aeron.protocol.CoreMessageType.TRANSFER_IN, new java.util.UUID(91, 1),
                            ProductLine.LINEAR_PERPETUAL, com.surprising.aeron.protocol.CommandSource.OPERATIONS,
                            1, 1, 7, 1, 1),
                    com.surprising.aeron.protocol.TradingCommandCodec.encodeTransferFunds(transfer));
            assertThat(com.surprising.aeron.service.order.CoreTestCompletion.applyAsynchronously(core, message).status())
                    .isEqualTo(com.surprising.aeron.protocol.ResponseStatus.APPLIED);
            assertThat(com.surprising.aeron.service.order.CoreTestCompletion.userState(core, 7).balances())
                    .singleElement().satisfies(balance -> assertThat(balance.availableUnits()).isEqualTo(250L));
        }
    }

    /**
     * 六产品线逐一直接调整用户余额，物化结果应和冷转换辅助得到的完整状态相同。
     */
    @Test
    void adjustsBalanceDirectlyInRuntimeAcrossEveryProductLine() {
        // 复用原业务转换的冷状态辅助，用来比较入口与物化一致性
        RuntimeTestStateTransitions reference = new RuntimeTestStateTransitions();
        for (ProductLine /* 当前正在验证余额转换的独立产品线 */ productLine : ProductLine.values()) {
            // 当前用例变更前的完整状态，作为投影起点及未变断言基线
            TradingCoreState before = TradingCoreState.empty(productLine);
            // 给用户七注入 USDT 一千的余额调整命令
            BalanceAdjustmentCommand command = new BalanceAdjustmentCommand("USDT", 1_000);
            // 冷转换辅助对同一输入生成的预期完整状态
            TradingCoreState expected = reference.adjustBalance(before, 7, command);
            // 本次投影独用的整数身份表，与对应运行时成对使用
            RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
            // 从变更前状态投影的冷运行时，供直接转换和结果物化使用
            TradingAccountRuntime runtime = RuntimeStateProjector.project(before, identities);

            LedgerControlTestSupport.await(runtime, new AccountControlTask().prepareBalanceAdjustment(runtime, identities, 7, command.asset(), command.deltaUnits()));

            assertThat(RuntimeStateMaterializer.materialize(runtime, identities))
                    .as(productLine.name())
                    .isEqualTo(expected);
        }
    }

    /**
     * 余额十时尝试扣十一，确认抛异常且完整物化状态保持原值。
     */
    @Test
    void rejectedBalanceAdjustmentLeavesRuntimeUnchanged() {
        // 当前用例变更前的完整状态，作为投影起点及未变断言基线
        TradingCoreState before = new RuntimeTestStateTransitions().adjustBalance(
                TradingCoreState.empty(ProductLine.SPOT), 7, new BalanceAdjustmentCommand("USDT", 10));
        // 本次投影独用的整数身份表，与对应运行时成对使用
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        // 从变更前状态投影的冷运行时，供直接转换和结果物化使用
        TradingAccountRuntime runtime = RuntimeStateProjector.project(before, identities);

        assertThatThrownBy(() -> LedgerControlTestSupport.await(runtime, new AccountControlTask().prepareBalanceAdjustment(runtime, identities, 7, "USDT", -11)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(RuntimeStateMaterializer.materialize(runtime, identities)).isEqualTo(before);
    }

    /**
     * 按原版本和固定业务时间修改风险扫描控制，物化后与冷转换结果相同。
     */
    @Test
    void updatesRiskScanControlDirectlyInRuntime() {
        // 当前用例变更前的完整状态，作为投影起点及未变断言基线
        TradingCoreState before = TradingCoreState.empty(ProductLine.LINEAR_PERPETUAL);
        // 按当前控制版本启用扫描并指定扫描间隔与批量上限的风险控制命令
        UpdateRiskScanControlCommand command = new UpdateRiskScanControlCommand(
                before.riskState().scanControl().version(), "runtime-owner", true,
                25, 64, "qa", "runtime authority");
        // 冷转换辅助对同一输入生成的预期完整状态
        TradingCoreState expected = new RuntimeTestStateTransitions().updateRiskScanControl(before, command, 123);
        // 本次投影独用的整数身份表，与对应运行时成对使用
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        // 从变更前状态投影的冷运行时，供直接转换和结果物化使用
        TradingAccountRuntime runtime = RuntimeStateProjector.project(before, identities);

        runtime.updateRiskScanControl(command, 123);

        assertThat(RuntimeStateMaterializer.materialize(runtime, identities)).isEqualTo(expected);
    }

    /**
     * 直接在空现货运行时注册标的一，并与冷转换的注册结果比较；本用例未执行已有配置更新分支。
     */
    @Test
    void upsertsInstrumentDirectlyInRuntime() {
        // 当前用例变更前的完整状态，作为投影起点及未变断言基线
        TradingCoreState before = TradingCoreState.empty(ProductLine.SPOT);
        // 标的一的现货注册命令，使用 BTC 基础资产和 USDT 报价及结算
        RegisterInstrumentCommand command = new RegisterInstrumentCommand(
                "1", "BTC", "USDT", "USDT", 1, 1, 1, null, 0, 0, 0, -1, 0);
        // 冷转换辅助对同一输入生成的预期完整状态
        TradingCoreState expected = new RuntimeTestStateTransitions().registerInstrument(before, command);
        // 本次投影独用的整数身份表，与对应运行时成对使用
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        // 从变更前状态投影的冷运行时，供直接转换和结果物化使用
        TradingAccountRuntime runtime = RuntimeStateProjector.project(before, identities);

        InstrumentConfigurationCommands.applyConfiguration(runtime, identities, command);

        assertThat(RuntimeStateMaterializer.materialize(runtime, identities)).isEqualTo(expected);
    }

    /**
     * 将用户切到双向持仓，验证首次有变化、物化状态一致，相同模式再次提交无变化。
     */
    @Test
    void updatesPositionModeDirectlyInRuntime() {
        // 当前用例变更前的完整状态，作为投影起点及未变断言基线
        TradingCoreState before = TradingCoreState.empty(ProductLine.LINEAR_PERPETUAL);
        // 把用户七切换为双向持仓模式的命令
        UpdatePositionModeCommand command = new UpdatePositionModeCommand(CorePositionMode.HEDGE);
        // 冷转换辅助对同一输入生成的预期完整状态
        TradingCoreState expected = new RuntimeTestStateTransitions().updatePositionMode(before, 7, command);
        // 本次投影独用的整数身份表，与对应运行时成对使用
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        // 从变更前状态投影的冷运行时，供直接转换和结果物化使用
        TradingAccountRuntime runtime = RuntimeStateProjector.project(before, identities);

        var task = new AccountControlTask();
        LedgerControlTestSupport.await(runtime, task.preparePositionModeChange(runtime, 7, command));
        assertThat(task.stateChanged()).isTrue();

        assertThat(RuntimeStateMaterializer.materialize(runtime, identities)).isEqualTo(expected);
        LedgerControlTestSupport.await(runtime, task.preparePositionModeChange(runtime, 7, command));
        assertThat(task.stateChanged()).isFalse();
    }

    /**
     * 注册正向永续标的后把全仓杠杆改为五倍，验证直接转换有变化且完整物化状态一致。
     */
    @Test
    void updatesLeverageDirectlyInRuntime() {
        // 复用原业务转换的冷状态辅助，用来比较入口与物化一致性
        RuntimeTestStateTransitions reference = new RuntimeTestStateTransitions();
        // 当前用例变更前的完整状态，作为投影起点及未变断言基线
        TradingCoreState before = reference.registerInstrument(
                TradingCoreState.empty(ProductLine.LINEAR_PERPETUAL),
                new RegisterInstrumentCommand(
                        "1", "BTC", "USDT", "USDT", 1, 1, 1,
                        new CoreInstrumentRiskLimits(
                                100_000, (1_000_000_000_000L / (100_000)), Long.MAX_VALUE, 0, Long.MAX_VALUE,
                                java.util.List.of(new CoreRiskLimitBracket(Long.MAX_VALUE, (1_000_000_000_000L / (100_000)), 100_000, 50_000))),
                        0, 0, 0, -1, 0));
        // 把用户七标的一的全仓杠杆改为五倍的命令
        UpdateLeverageCommand command = new UpdateLeverageCommand("1", CoreMarginMode.CROSS, 5_000_000);
        // 冷转换辅助对同一输入生成的预期完整状态
        TradingCoreState expected = reference.updateLeverage(before, 7, command);
        // 本次投影独用的整数身份表，与对应运行时成对使用
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        // 从变更前状态投影的冷运行时，供直接转换和结果物化使用
        TradingAccountRuntime runtime = RuntimeStateProjector.project(before, identities);

        var task = new AccountControlTask();
        LedgerControlTestSupport.await(runtime, task.prepareLeverageChange(runtime, identities, 7, command));
        assertThat(task.stateChanged()).isTrue();

        assertThat(RuntimeStateMaterializer.materialize(runtime, identities)).isEqualTo(expected);
    }
}
