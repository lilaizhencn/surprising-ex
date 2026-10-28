package com.surprising.aeron.service.core.index;

import com.surprising.aeron.protocol.CoreRiskLimitBracket;

import com.surprising.aeron.protocol.CoreInstrumentRiskLimits;

import com.surprising.aeron.model.TradingCoreState;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.service.account.CoreUserState;
import com.surprising.aeron.service.core.LedgerControlTestSupport;
import com.surprising.aeron.service.core.TradingAccountRuntime;
import com.surprising.aeron.service.core.change.RuntimeIndexedChangeBuffer;
import com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.position.PositionRuntime;
import com.surprising.aeron.service.position.RuntimePositionIndexValue;
import com.surprising.aeron.service.snapshot.state.RuntimeStateProjector;
import com.surprising.aeron.service.treasury.CoreTreasuryState;
import com.surprising.aeron.service.treasury.SystemLedgerCommand;
import com.surprising.aeron.service.treasury.TreasuryRuntime;
import com.surprising.product.api.ProductLine;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

/**
 * 验证持仓变化确实进入 System Ledger 业务索引，以及未发布控制命令如何撤销。使用真实账户和账本工作资源、十五秒用例期限；不从 Owner 直接读取已归账本所有的业务表。
 */
@Timeout(15)
class LedgerPositionPublicationTest {
    /**
     * 分别有、无 Treasury 控制命令地移除唯一持仓，两轮检查即使没有资金补丁也执行账本持仓校验；控制回滚恢复发布计数、用户索引、未平仓量和 ADL，并清掉本次资金费暂存。
     * @param treasuryScope 本轮是否先安排一条未发布的 Treasury 控制命令
     */
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void positionOnlyControlValidationRollsBackWithAndWithoutTreasuryScope(boolean treasuryScope) throws Exception {
        // 本用例隔离的 U 本位永续产品线
        var product = ProductLine.LINEAR_PERPETUAL;
        // 本用例注册的标的一 U 本位永续合约
        var instrument = com.surprising.aeron.service.instrument.CoreInstrument.from(product,
                new com.surprising.aeron.protocol.RegisterInstrumentCommand(
                        "1", "BTC", "USDT", "USDT", 1, 1, 1,
                        new CoreInstrumentRiskLimits(
                                100_000, (1_000_000_000_000L / (100_000)), Long.MAX_VALUE, 0, Long.MAX_VALUE,
                                java.util.List.of(new CoreRiskLimitBracket(Long.MAX_VALUE, (1_000_000_000_000L / (100_000)), 100_000, 50_000))),
                        0, 0, 0, -1, 0));
        // 用户七的初始净仓，数量十、保证金一百
        var position = new com.surprising.aeron.model.CorePositionState("1", "USDT",
                com.surprising.aeron.protocol.CoreMarginMode.CROSS, CorePositionSide.NET, 10, 100, 1000, 0, 100);
        // 带可用九百、冻结一百及唯一净仓的用户七冷状态
        var user = new CoreUserState(product, 7, 1,
                java.util.Map.of("USDT", new com.surprising.aeron.model.AssetBalance("USDT", 900, 100)),
                java.util.Map.of(), java.util.Map.of("1", position));
        // 用于投影运行时的原始产品线冷状态
        var initial = new TradingCoreState(product, 1, java.util.Map.of(7L, user), java.util.Map.of(),
                java.util.Map.of("1", instrument), com.surprising.aeron.model.CoreRiskState.empty(),
                CoreTreasuryState.empty());
        // 本用例资产、标的和持仓的原身份表
        var ids = new RuntimeIdentityRegistry();
        // 本用例真实账本接管的持仓用户索引
        var users = new PositionUserIndex(initial);
        // 本用例真实账本接管的未平仓量索引
        var oi = new OpenInterestIndex(initial);
        // 本用例真实账本接管的 ADL 持仓索引
        var adl = new AdlPositionIndex(initial, ids);
        try (/* 自动关闭的交易运行时，按用例启动所需账户与账本工作资源 */ var accountRuntime = RuntimeStateProjector.project(initial, ids)) {
            var runtime = accountRuntime;
            runtime.bindFactIndexes(indexes(initial, ids, users, oi, adl), ids);
            // 用户七标的一净仓的已准备确定性身份键
            long key = ids.findPositionKeyValue(7, instrument, CorePositionSide.NET);
            // 标的一对应的内部稠密编号，用于 Treasury 暂存
            int symbol = ids.symbolId("1");
            assertThat(runtime.publishedPositionCount()).isOne();
            runtime.startAccountLanes(); runtime.startSystemLedgerLane(); runtime.enterAsynchronousCommandScope();
            try {
                for (/* 本次控制删除、校验与回滚的轮次，共两轮 */ int round = 0; round < 2; round++) {
                    if (treasuryScope) {
                        // 可选的本次资金费暂存账本命令，初始化时绑定原运行时
                        var command = /* 只用于验证控制范围资金费暂存回滚的账本命令，实例初始化时接入当前运行时。 */ new SystemLedgerCommand() {
                            // 实例创建时绑定当前运行时及账本控制范围，再允许派发。
                            { initializeLedgerCommand(accountRuntime); }
                            /**
                             * 把本次未发布控制范围的资金费暂存设为九，供随后验证回滚归零。
                             * @param ledger 本次控制命令可写的权威 Treasury 账本
                             */
                            @Override protected void applyLedger(TreasuryRuntime ledger) { ledger.setFundingSettlement(symbol, 9); }
                        };
                        assertThat(runtime.dispatchLedgerCommand(command)).isTrue();
                        LedgerControlTestSupport.await(runtime, command::complete);
                    }
                    runtime.dispatchControlLanes(runtime.topology().accountLaneMask(7), /* 本次删除持仓的实际账户 Lane，操作只改用户七持仓 */ lane -> {
                        runtime.removePosition(key, 7); return null;
                    });
                    LedgerControlTestSupport.await(runtime, runtime::pollControlLanes);
                    assertThat(runtime.publishedPositionCount()).isZero();
                    // 即使没有资金补丁，仅持仓变化的控制命令仍须完成账本校验。
                    LedgerControlTestSupport.awaitFunds(runtime, false);
                    assertThat(adl.count()).isZero();
                    LedgerControlTestSupport.await(runtime, runtime.beginCommandRollback(1, 2 + round));
                    assertThat(runtime.publishedPositionCount()).isOne();
                    onLedger(runtime, () -> {
                        assertThat(oi.openInterestSteps("1")).isEqualTo(10);
                        assertThat(users.usersAfter("1", 0)).containsExactly(7L);
                        assertThat(adl.value(key).signedQuantitySteps()).isEqualTo(10);
                    });
                    assertThat(runtime.treasury().fundingSettlement(symbol)).isZero();
                }
            } finally { runtime.exitAsynchronousCommandScope(); }
        }
    }

    /**
     * 真实账本接管三个持仓业务索引后，Owner 读取被拒绝；在账本线程应用连续持仓发布，再撤销后续控制变化，验证旧值复制不受可变 ADL 单元改写影响。
     */
    @Test void committedPrefixAndMinimalControlUndoStayOnLedger() throws Exception {
        // 用于投影运行时的原始产品线冷状态
        var initial = TradingCoreState.empty(ProductLine.LINEAR_PERPETUAL);
        // 本用例资产、标的和持仓的原身份表
        var ids = new RuntimeIdentityRegistry();
        // 本用例真实账本接管的持仓用户索引
        var users = new PositionUserIndex(initial);
        // 本用例真实账本接管的未平仓量索引
        var oi = new OpenInterestIndex(initial);
        // 本用例真实账本接管的 ADL 持仓索引
        var adl = new AdlPositionIndex(initial, ids);
        // 本用例各派生索引共用的事实应用入口
        var indexes = indexes(initial, ids, users, oi, adl);
        try (/* 自动关闭的交易运行时，按用例启动所需账户与账本工作资源 */ var runtime = RuntimeStateProjector.project(initial, ids)) {
            runtime.bindFactIndexes(indexes, ids);
            runtime.startSystemLedgerLane();
            assertThat(oi.openInterestSteps("1")).isZero();
            assertThat(users.higherUserId("1", 0)).isZero();
            assertThat(adl.positions("USDT", 2)).isEmpty();
            // 逐轮复用的冻结持仓变化缓冲，prepared 空值用于明确删除
            var changes = new RuntimeIndexedChangeBuffer<PositionRuntime, RuntimePositionIndexValue>();
            onLedger(runtime, () -> {
                changes.putIndexed(101, null, true, position(1, CorePositionSide.LONG, 10));
                changes.putIndexed(102, null, true, position(2, CorePositionSide.SHORT, -10));
                indexes.applyPositions(changes, ids, null);
                assertThat(oi.openInterestSteps("1")).isEqualTo(10);
                changes.clear();
                changes.putIndexed(101, null, true, position(1, CorePositionSide.LONG, 15));
                changes.putIndexed(103, null, true, position(1, CorePositionSide.SHORT, -5));
                changes.putIndexed(102, null, true, position(2, CorePositionSide.SHORT, -20));
                indexes.applyPositions(changes, ids, null);
                assertThat(oi.openInterestSteps("1")).isEqualTo(25);
                assertThat(users.usersAfter("1", 0)).containsExactly(1L, 2L);
                changes.clear();
            });
            assertThat(adl.count()).isEqualTo(3); // 已发布的技术计数不需要跨线程读取业务映射。
            // 后续未发布控制范围的持仓旧字段记录
            var undo = new RuntimeFactIndexes.PositionUndo();
            onLedger(runtime, () -> {
                changes.putIndexed(101, null, true, position(1, CorePositionSide.LONG, -30));
                changes.putIndexed(102, null, true, null);
                indexes.applyPositions(changes, ids, undo);
                assertThat(oi.openInterestSteps("1")).isEqualTo(35);
                assertThat(users.usersAfter("1", 0)).containsExactly(1L);
                // 原 ADL 单元会被原地改写，撤销记录必须保存旧字段值，而非该单元引用。
                undo.rollback(indexes);
                assertThat(oi.totals()).containsEntry("1", new OpenInterestIndex.Totals(15, 25));
                assertThat(adl.value(101).signedQuantitySteps()).isEqualTo(15);
                assertThat(adl.value(102).signedQuantitySteps()).isEqualTo(-20);
                assertThat(users.usersAfter("1", 0)).containsExactly(1L, 2L);
            });
        }
        assertThat(oi.openInterestSteps("1")).isEqualTo(25);
    }

    /**
     * 把未平仓量加到 long 上限，再加一触发精确加法溢出；原汇总单元仍保持原值。
     */
    @Test void ordinaryQuantityOverflowDoesNotPartiallyWriteATotalsCell() {
        // 用于投影运行时的原始产品线冷状态
        var initial = TradingCoreState.empty(ProductLine.LINEAR_PERPETUAL);
        // 本用例真实账本接管的未平仓量索引
        var oi = new OpenInterestIndex(initial);
        oi.apply(null, position(1, CorePositionSide.NET, Long.MAX_VALUE));
        assertThatThrownBy(() -> oi.apply(null, position(2, CorePositionSide.NET, 1)))
                .isInstanceOf(ArithmeticException.class);
        assertThat(oi.openInterestSteps("1")).isEqualTo(Long.MAX_VALUE);
    }

    /**
     * 把本用例的三个持仓业务索引与其余五个 Owner 索引组合为原事实应用入口。
     * @param initial 本用例用于构造和投影的已提交冷状态
     * @param ids 本用例原身份表
     * @param users 本用例持仓用户业务索引
     * @param oi 本用例未平仓量业务索引
     * @param adl 本用例 ADL 持仓业务索引
     */
    private static RuntimeFactIndexes indexes(TradingCoreState initial, RuntimeIdentityRegistry ids,
            PositionUserIndex users, OpenInterestIndex oi, AdlPositionIndex adl) {
        return new RuntimeFactIndexes(users, oi, new TriggerOrderIndex(initial), new AlgoOrderIndex(initial),
                new LiquidationIndex(initial), new CancelAllAfterIndex(initial), new ActiveOrderIndex(initial, ids), adl);
    }

    /**
     * 构造标的一、资产 USDT 的冻结持仓索引值，数量带方向符号。
     * @param user 冻结持仓值所属用户
     * @param side 冻结持仓值的净仓、多头或空头方向
     * @param quantity 带符号的数量步数
     */
    private static RuntimePositionIndexValue position(long user, CorePositionSide side, long quantity) {
        return new RuntimePositionIndexValue(user, "1", "USDT", side, quantity);
    }

    /**
     * 把断言交给真实账本查询线程执行，确认入队成功并最多等待三秒；把工作线程异常传回测试线程验证。
     * @param runtime 已启动账本工作资源的运行时
     * @param operation 必须在账本业务线程执行的操作和断言
     */
    private static void onLedger(TradingAccountRuntime runtime, Runnable operation) throws Exception {
        // 通知测试线程账本查询已经执行完的门闩
        var done = new CountDownLatch(1);
        // 把账本查询操作抛出的异常带回测试线程的原子槽
        var failure = new AtomicReference<Throwable>();
        assertThat(runtime.<Boolean>queryLedgerAtRest(/* 账本查询回调传入的账本对象，本用例操作使用闭包中的业务索引 */ ignored -> {
            try { operation.run(); } catch (/* 账本查询断言或操作抛出的原异常 */ Throwable problem) { failure.set(problem); }
            finally { done.countDown(); }
            return true;
        })).isTrue();
        assertThat(done.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(failure.get()).isNull();
    }
}
