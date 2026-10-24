package com.surprising.aeron.service.treasury;

import com.surprising.aeron.protocol.CoreRiskLimitBracket;

import com.surprising.aeron.protocol.CoreInstrumentRiskLimits;

import com.surprising.aeron.exception.CoreStateRejectedException;
import com.surprising.aeron.model.TradingCoreState;
import com.surprising.aeron.protocol.AdjustInsuranceFundCommand;
import com.surprising.aeron.protocol.RegisterInstrumentCommand;
import com.surprising.aeron.service.core.LedgerControlTestSupport;
import com.surprising.aeron.service.core.TradingAccountRuntime;
import com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.aeron.service.snapshot.state.RuntimeStateProjector;
import com.surprising.product.api.ProductLine;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * 使用真实系统账本线程的控制命令回归测试。核对原 Treasury 单写者、保险调整、邮箱满时重试、阶段拒绝及未关闭财务范围回滚，部分用例同时启动账户 Lane；不运行完整 Aeron Cluster。
 */
class SystemLedgerControlTest {
    /**
     * 检查账本激活后 Owner 不能直接读写原保险余额，调整成功仍须等资金验证及范围关闭，下一笔独立任务扣款一次并核对产品修订。
     */
    @Test
    void insuranceBudgetAndMoneyAreOnlyReadAndWrittenByOriginalLedger() {
        // 本用例共用的资产及币对运行时字典。
        var identities = new RuntimeIdentityRegistry();
        try (/* 由空永续冷状态投影并按需启动真实账户与账本线程的运行时，try 退出时关闭。 */ var runtime = RuntimeStateProjector.project(TradingCoreState.empty(ProductLine.LINEAR_PERPETUAL), identities)) {
            // 用例保险或费用对应的 USDT 稠密资产编号。
            int asset = identities.assetId("USDT");
            // 始终由本用例使用的原 Treasury 引用，账本运行中资产读写仍受线程约束。
            TreasuryRuntime original = runtime.treasury();
            original.setInsurance(asset, 25, 0); runtime.clearChangedKeys();
            runtime.startSystemLedgerLane();
            assertThatThrownBy(() -> original.insurance(asset)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> original.adjustInsurance(asset, 1)).isInstanceOf(IllegalStateException.class);
            // 当前需要等待账本执行及范围关闭的控制任务。
            var work = RuntimeInsuranceFundStateTransitions.prepare(runtime, identities, new AdjustInsuranceFundCommand("USDT", 50));
            LedgerControlTestSupport.await(runtime, work);
            assertThat(work.getAsBoolean()).isTrue();
            assertThat(work.reusable()).isFalse();
            assertThatThrownBy(() -> runtime.treasuryForQuery(identities)).hasMessageContaining("committed control");
            // Owner 当前尝试追加的资金流水，账本独占资金事实不会重复搬给它。
            var funds = new RuntimeFundsAccumulator();
            runtime.appendFundsDelta(funds);
            assertThat(funds.toDelta().postings()).as("Owner consumes completion without money receipts").isEmpty();
            LedgerControlTestSupport.awaitFunds(runtime, true); runtime.clearChangedKeys();
            assertThat(runtime.treasuryForQuery(identities).insuranceBalances()).containsEntry("USDT", 75L);
            assertThat(work.reusable()).isTrue();
            // 工厂为下一笔保险调整创建独立任务，原任务的财务范围此时已经关闭。
            var next = RuntimeInsuranceFundStateTransitions.prepare(runtime, identities, new AdjustInsuranceFundCommand("USDT", -20));
            assertThat(next).isNotSameAs(work);
            LedgerControlTestSupport.await(runtime, next);
            LedgerControlTestSupport.awaitFunds(runtime, true); runtime.clearChangedKeys();
            assertThat(runtime.treasuryForQuery(identities).insuranceBalances()).containsEntry("USDT", 55L);
            assertThat(runtime.revision()).isEqualTo(2);
        }
    }

    /**
     * 用未就绪队首填满真实账本邮箱，检查未被接纳的控制任务仍可复用，放行后重试只增加七单位保险和一次产品修订。
     */
    @Test
    void fullMailboxLeavesUnacceptedControlSlotReusableAndRetryAppliesMoneyOnce() throws Exception {
        // 本用例共用的资产及币对运行时字典。
        var identities = new RuntimeIdentityRegistry();
        try (/* 由空永续冷状态投影并按需启动真实账户与账本线程的运行时，try 退出时关闭。 */ var runtime = RuntimeStateProjector.project(TradingCoreState.empty(ProductLine.LINEAR_PERPETUAL), identities)) {
            identities.assetId("USDT"); runtime.startSystemLedgerLane();
            // 反射取得的运行时系统账本执行器字段。
            // 运行时实际持有的系统账本执行器，用于填满其邮箱。
            var ledger = com.surprising.aeron.service.core.AccountLaneTestAccess.ledger(runtime);
            // 控制空队首是否就绪的原子开关，finally 保证放行。
            var ready = new java.util.concurrent.atomic.AtomicBoolean();
            assertThat(ledger.offer(/* 用原子就绪开关卡住账本队首的测试占位任务，不修改系统资金。 */ new SystemLedgerLane.Entry() {
                /**
                 * 由原子开关控制占位队首是否允许执行，便于确定性填满邮箱。
                 */
                @Override public boolean ready() { return ready.get(); }
                /**
                 * 放行后执行空占位任务，只释放队列，不修改财库。
                 * @param ignored 空账本占位任务收到的 Treasury，本任务不读取或修改它
                 */
                @Override public void apply(TreasuryRuntime ignored) { }
            })).isTrue();
            // 当前需要等待账本执行及范围关闭的控制任务。
            var work = RuntimeInsuranceFundStateTransitions.prepare(runtime, identities, new AdjustInsuranceFundCommand("USDT", 7));
            try {
                while (ledger.offer(/* 占位任务或故意拒绝的控制回调收到的入参，本测试不读取其业务内容。 */ ignored -> { })) { }
                assertThat(runtime.dispatchLedgerCommand(work)).isFalse();
                assertThat(work.reusable()).isTrue();
            } finally { ready.set(true); }
            LedgerControlTestSupport.await(runtime, work);
            LedgerControlTestSupport.awaitFunds(runtime, true); runtime.clearChangedKeys();
            assertThat(runtime.treasuryForQuery(identities).insuranceBalances()).containsEntry("USDT", 7L);
            assertThat(runtime.revision()).isEqualTo(1);
        }
    }

    /**
     * 真实账本分别遇金额溢出和保险不足，完成命令回滚后检查余额及修订恢复，再合法入账七单位证明工作线程仍能使用。
     */
    @Test
    void rejectedInsuranceAndOverflowRestoreBeforeImagesAndLeaveWorkerUsable() {
        // 本用例共用的资产及币对运行时字典。
        var identities = new RuntimeIdentityRegistry();
        try (/* 由空永续冷状态投影并按需启动真实账户与账本线程的运行时，try 退出时关闭。 */ var runtime = RuntimeStateProjector.project(TradingCoreState.empty(ProductLine.LINEAR_PERPETUAL), identities)) {
            // 用例保险或费用对应的 USDT 稠密资产编号。
            int asset = identities.assetId("USDT");
            runtime.treasury().setInsurance(asset, Long.MAX_VALUE, 0); runtime.clearChangedKeys();
            runtime.startAccountLanes(); runtime.startSystemLedgerLane(); runtime.enterAsynchronousCommandScope();
            try {
                // 试图在长整数最大保险余额上增加一的溢出控制任务。
                var overflow = RuntimeInsuranceFundStateTransitions.prepare(runtime, identities, new AdjustInsuranceFundCommand("USDT", 1));
                assertThatThrownBy(() -> LedgerControlTestSupport.await(runtime, overflow)).isInstanceOf(ArithmeticException.class);
                LedgerControlTestSupport.await(runtime, runtime.beginCommandRollback(0, 1));
                assertThat(runtime.treasuryForQuery(identities).insuranceBalances()).containsEntry("USDT", Long.MAX_VALUE);
                // 消耗全部剩余保险余额的下一代合法控制任务。
                var spend = RuntimeInsuranceFundStateTransitions.prepare(runtime, identities,
                        new AdjustInsuranceFundCommand("USDT", -Long.MAX_VALUE));
                LedgerControlTestSupport.await(runtime, spend);
                LedgerControlTestSupport.awaitFunds(runtime, true); runtime.clearChangedKeys();
                assertThat(runtime.treasuryForQuery(identities).insuranceBalances()).isEmpty();
                // 零保险余额下尝试再扣一的业务拒绝任务。
                var rejected = RuntimeInsuranceFundStateTransitions.prepare(runtime, identities, new AdjustInsuranceFundCommand("USDT", -1));
                assertThatThrownBy(() -> LedgerControlTestSupport.await(runtime, rejected)).isInstanceOf(CoreStateRejectedException.class);
                LedgerControlTestSupport.await(runtime, runtime.beginCommandRollback(1, 2));
                assertThat(runtime.revision()).isEqualTo(1);
                // 拒绝及回滚后重新合法增加七单位保险的任务。
                var credit = RuntimeInsuranceFundStateTransitions.prepare(runtime, identities, new AdjustInsuranceFundCommand("USDT", 7));
                LedgerControlTestSupport.await(runtime, credit);
                LedgerControlTestSupport.awaitFunds(runtime, true); runtime.clearChangedKeys();
                assertThat(runtime.treasuryForQuery(identities).insuranceBalances()).containsEntry("USDT", 7L);
                runtime.assertAccountLanesHealthy();
            } finally { runtime.exitAsynchronousCommandScope(); }
        }
    }

    /**
     * 先在账本预留二十保险且保持范围未完成，再让真实账户控制阶段业务拒绝，检查原三十保险恢复且原任务重新可复用。
     */
    @Test
    void failedAccountPhaseRestoresInsuranceReservedByAnIncompleteLedgerScope() {
        // 本用例共用的资产及币对运行时字典。
        var identities = new RuntimeIdentityRegistry();
        try (/* 由空永续冷状态投影并按需启动真实账户与账本线程的运行时，try 退出时关闭。 */ var runtime = RuntimeStateProjector.project(TradingCoreState.empty(ProductLine.LINEAR_PERPETUAL), identities)) {
            // 用例保险或费用对应的 USDT 稠密资产编号。
            int asset = identities.assetId("USDT");
            runtime.treasury().setInsurance(asset, 30, 0); runtime.clearChangedKeys();
            runtime.startAccountLanes(); runtime.startSystemLedgerLane(); runtime.enterAsynchronousCommandScope();
            try {
                /**
                 * 测试局部保险预留任务，账本先暂扣保险但不结束范围，等待账户阶段结果。
                 */
                final class InsuranceReservation extends SystemLedgerCommand {
                    /**
                     * 为本测试初始化既有财务控制范围，随后只执行保险预留阶段。
                     */
                    InsuranceReservation(TradingAccountRuntime ownerRuntime) { initializeLedgerCommand(ownerRuntime); }
                    /**
                     * 在原账本内暂扣二十保险，保留前值供后续账户拒绝回滚。
                     * @param ledger 本测试业务阶段由账本线程独占的原 Treasury
                     */
                    @Override protected void applyLedger(TreasuryRuntime ledger) { ledger.adjustInsurance(asset, -20); }
                    /**
                     * 明确当前只是保险预留阶段，返回 false 继续保留同一财务范围。
                     */
                    @Override protected boolean financialScopeComplete() { return false; }
                    /**
                     * 借用父类检查账本当前执行阶段是否完成，不提前关闭财务范围。
                     */
                    boolean poll() { return ledgerCommandCompleted(); }
                }
                // 本用例局部类创建的未结束财务范围保险预留任务。
                var reservation = new InsuranceReservation(runtime);
                assertThat(runtime.dispatchLedgerCommand(reservation)).isTrue();
                LedgerControlTestSupport.await(runtime, reservation::poll);
                runtime.dispatchControlLanes(1, /* 占位任务或故意拒绝的控制回调收到的入参，本测试不读取其业务内容。 */ ignored -> { throw new CoreStateRejectedException("ACCOUNT_REJECTED", "account settlement rejected"); });
                assertThatThrownBy(() -> LedgerControlTestSupport.await(runtime, runtime::pollControlLanes)).isInstanceOf(CoreStateRejectedException.class);
                LedgerControlTestSupport.await(runtime, runtime.beginCommandRollback(0, 1));
                assertThat(runtime.treasuryForQuery(identities).insuranceBalances()).containsEntry("USDT", 30L);
                assertThat(reservation.reusable()).isTrue();
                runtime.assertAccountLanesHealthy();
            } finally { runtime.exitAsynchronousCommandScope(); }
        }
    }

    /**
     * 账本阶段改费用和资金费完成记录后由 Owner 中止命令，检查原费用、完成编号、进度引用及产品修订均恢复，关闭后任务才可复用。
     */
    @Test
    void ownerAbortAfterFinancialCompletionRestoresMoneyAndProgressOnLedger() {
        // 本用例共用的资产及币对运行时字典。
        var identities = new RuntimeIdentityRegistry();
        try (/* 由空永续冷状态投影并按需启动真实账户与账本线程的运行时，try 退出时关闭。 */ var runtime = RuntimeStateProjector.project(TradingCoreState.empty(ProductLine.LINEAR_PERPETUAL), identities)) {
            // 用例保险或费用对应的 USDT 稠密资产编号。
            // 用例资金费进度对应的业务币对一的稠密编号。
            int asset = identities.assetId("USDT"), symbol = identities.symbolId("1");
            // 始终由本用例使用的原 Treasury 引用，账本运行中资产读写仍受线程约束。
            TreasuryRuntime original = runtime.treasury();
            // 构造资金费前值进度使用的规范 U 本位永续币对。
            var instrument = CoreInstrument.from(ProductLine.LINEAR_PERPETUAL,
                    new RegisterInstrumentCommand(
                            "1", "BTC", "USDT", "USDT", 1, 1, 1,
                            new CoreInstrumentRiskLimits(
                                    100_000, (1_000_000_000_000L / (100_000)), Long.MAX_VALUE, 0, Long.MAX_VALUE,
                                    java.util.List.of(new CoreRiskLimitBracket(Long.MAX_VALUE, (1_000_000_000_000L / (100_000)), 100_000, 50_000))),
                            0, 0, 0, -1, 0));
            // 操作前保留的资金费分页进度引用，回滚后应恢复同一记录。
            var progress = new TreasuryRuntime.FundingProgressRuntime(3, instrument, 1000, 1, 100);
            original.setFee(asset, 9); original.setFundingSettlement(symbol, 2); original.setFundingProgress(symbol, progress);
            runtime.clearChangedKeys(); runtime.startAccountLanes(); runtime.startSystemLedgerLane(); runtime.enterAsynchronousCommandScope();
            try {
                /**
                 * 测试局部费用及资金费标记更新任务，复用父类首值捕获和回滚协议。
                 */
                final class FinancialWork extends SystemLedgerCommand {
                    /**
                     * 为本测试准备一项使用原财务范围的账本更新任务。
                     */
                    FinancialWork(TradingAccountRuntime ownerRuntime) { initializeLedgerCommand(ownerRuntime); }
                    /**
                     * 把费用改为十八并把资金费编号推进到三，用于检查后续原前值回滚。
                     * @param ledger 本测试业务阶段由账本线程独占的原 Treasury
                     */
                    @Override protected void applyLedger(TreasuryRuntime ledger) { ledger.setFee(asset, 18); ledger.setFundingSettlement(symbol, 3); }
                    /**
                     * 仅检查本账本执行阶段正常完成，不替代范围关闭回执。
                     */
                    boolean poll() { return ledgerCommandCompleted(); }
                }
                // 当前需要等待账本执行及范围关闭的控制任务。
                var work = new FinancialWork(runtime);
                assertThat(runtime.dispatchLedgerCommand(work)).isTrue();
                LedgerControlTestSupport.await(runtime, work::poll);
                assertThat(original.fundingSettlement(symbol)).isEqualTo(3);
                assertThat(work.reusable()).isFalse();
                runtime.setMetadata(runtime.productLine(), 1);
                // Owner 中止命令后用于非阻塞推进账本及账户回滚的续步。
                var rollback = runtime.beginCommandRollback(0, 1);
                LedgerControlTestSupport.await(runtime, rollback);
                assertThat(rollback.getAsBoolean()).isTrue();
                assertThat(work.reusable()).isTrue();
                assertThat(original.fundingSettlement(symbol)).isEqualTo(2);
                assertThat(original.fundingProgress(symbol)).isSameAs(progress);
                assertThat(runtime.treasuryForQuery(identities).feeBalances()).containsEntry("USDT", 9L);
                assertThat(runtime.revision()).isZero();
            } finally { runtime.exitAsynchronousCommandScope(); }
        }
    }
}
