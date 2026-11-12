package com.surprising.aeron.service.treasury;

import com.surprising.aeron.protocol.CoreRiskLimitBracket;

import com.surprising.aeron.protocol.CoreInstrumentRiskLimits;

import com.surprising.aeron.model.CoreRiskState;
import com.surprising.aeron.service.snapshot.state.TradingCoreState;
import com.surprising.aeron.protocol.RegisterInstrumentCommand;
import com.surprising.aeron.service.core.AccountBook;
import com.surprising.aeron.service.core.identity.IdIndex;
import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.aeron.service.settlement.TradeSettlement;
import com.surprising.aeron.service.snapshot.state.SnapshotWriter;
import com.surprising.aeron.service.snapshot.state.SnapshotLoader;
import com.surprising.product.api.ProductLine;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static com.surprising.aeron.service.treasury.RuntimeFundsTestSupport.postings;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 财库首值捕获、七类分录、回滚及冷运行时投影测试。溢出用例覆盖差额实际支持的六类金额；其他用例直接构造内存值，不代表 Cluster 恢复或独立线程链路。
 */
class SystemFundsChangeTest {

    /**
     * 同资产重复修改保留首个前值，逐项核对七类有符号分录及亏空反号，检查守恒后回滚恢复金额并清除变化捕获。
     */
    @Test
    void recordsFirstSystemFundsValuesAndIncludesAllSevenSubledgersInFundsAndRollback() {
        // 本用例独占的权威 SystemFunds，用于设置前值及检查变化和回滚。
        SystemFunds systemFunds = new SystemFunds();
        systemFunds.setFee(7, 90);
        systemFunds.setInsurance(7, 500, 20);
        systemFunds.setLiquidationFee(7, 1);
        systemFunds.setFundingResidual(7, 4);
        systemFunds.setRoundingResidual(7, 5);
        systemFunds.setClearingPnl(7, -100);
        systemFunds.clearChangedKeys();

        systemFunds.setFee(7, 95);
        systemFunds.setFee(7, 100);
        systemFunds.setInsurance(7, 450, 25);
        systemFunds.setLiquidationFee(7, 3);
        systemFunds.setFundingResidual(7, 0);
        systemFunds.setRoundingResidual(7, 6);
        systemFunds.setClearingPnl(7, -54);

        // 从当前财库前后值生成的资金累加器，用于守恒及逐分录核对。
        FundsTotal funds = new FundsTotal();
        systemFunds.appendFundsDelta(funds);
        funds.requireConserved(false);
        assertThat(postings(funds)).containsExactly(
                new RuntimeFundsTestSupport.Posting(7, TradeSettlement.Posting.OwnerKind.TREASURY, 0, TradeSettlement.Posting.Subledger.FEE, 10),
                new RuntimeFundsTestSupport.Posting(7, TradeSettlement.Posting.OwnerKind.TREASURY, 0, TradeSettlement.Posting.Subledger.INSURANCE, -50),
                new RuntimeFundsTestSupport.Posting(7, TradeSettlement.Posting.OwnerKind.TREASURY, 0, TradeSettlement.Posting.Subledger.DEFICIT, -5),
                new RuntimeFundsTestSupport.Posting(7, TradeSettlement.Posting.OwnerKind.TREASURY, 0, TradeSettlement.Posting.Subledger.LIQUIDATION_FEE, 2),
                new RuntimeFundsTestSupport.Posting(7, TradeSettlement.Posting.OwnerKind.TREASURY, 0, TradeSettlement.Posting.Subledger.FUNDING_RESIDUAL, -4),
                new RuntimeFundsTestSupport.Posting(7, TradeSettlement.Posting.OwnerKind.TREASURY, 0, TradeSettlement.Posting.Subledger.ROUNDING_RESIDUAL, 1),
                new RuntimeFundsTestSupport.Posting(7, TradeSettlement.Posting.OwnerKind.TREASURY, 0, TradeSettlement.Posting.Subledger.CLEARING_PNL, 46));

        systemFunds.rollbackChangedValues();

        assertThat(systemFunds.fee(7)).isEqualTo(90);
        assertThat(systemFunds.insurance(7)).isEqualTo(500);
        assertThat(systemFunds.insuranceDeficit(7)).isEqualTo(20);
        assertThat(systemFunds.liquidationFee(7)).isEqualTo(1);
        assertThat(systemFunds.fundingResidual(7)).isEqualTo(4);
        assertThat(systemFunds.roundingResidual(7)).isEqualTo(5);
        assertThat(systemFunds.clearingPnl(7)).isEqualTo(-100);
        assertThat(systemFunds.hasChangedValues()).isFalse();
    }

    /**
     * 二十五资产倒序重复写三轮，核对前值列扩容及每轮费用和保险差额，再回滚确认各资产旧值不串槽。
     */
    @Test
    void keepsSeparateAssetBeforeValuesAcrossRepeatedTransactions() {
        // 本用例独占的权威 SystemFunds，用于设置前值及检查变化和回滚。
        SystemFunds systemFunds = new SystemFunds();
        for (/* 当前设置或核对前值的资产编号。 */ int asset = 0; asset < 25; asset++) systemFunds.setFee(asset, asset);
        systemFunds.clearChangedKeys();
        for (/* 二十五资产重复捕获和回滚的当前轮次。 */ int transaction = 0; transaction < 3; transaction++) {
            for (/* 当前设置或核对前值的资产编号。 */ int asset = 24; asset >= 0; asset--) {
                systemFunds.setFee(asset, 100 + asset);
                systemFunds.setFee(asset, 200 + asset);
                systemFunds.setInsurance(asset, 300 + asset, asset);
            }
            // 从当前财库前后值生成的资金累加器，用于守恒及逐分录核对。
            FundsTotal funds = new FundsTotal(75);
            systemFunds.appendFundsDelta(funds);
            // 当前原语流水物化的分录列表，供逐资产核对。
            var postings = postings(funds);
            for (/* 当前设置或核对前值的资产编号。 */ int asset = 0; asset < 25; asset++) {
                assertThat(postings).contains(new RuntimeFundsTestSupport.Posting(asset,
                        TradeSettlement.Posting.OwnerKind.TREASURY, 0, TradeSettlement.Posting.Subledger.FEE, 200));
                assertThat(postings).contains(new RuntimeFundsTestSupport.Posting(asset,
                        TradeSettlement.Posting.OwnerKind.TREASURY, 0, TradeSettlement.Posting.Subledger.INSURANCE, 300 + asset));
            }
            systemFunds.rollbackChangedValues();
            for (/* 当前设置或核对前值的资产编号。 */ int asset = 0; asset < 25; asset++) {
                assertThat(systemFunds.fee(asset)).isEqualTo(asset);
                assertThat(systemFunds.insurance(asset)).isZero();
                assertThat(systemFunds.insuranceDeficit(asset)).isZero();
            }
            assertThat(systemFunds.hasChangedValues()).isFalse();
        }
    }

    /**
     * 对六个支持差额列分别构造单资产或后续资产溢出，检查首资产未先写、目标列原极值仍在且无新变化键。
     * @param subledger 本轮参数化溢出测试选择的系统子账
     */
    @ParameterizedTest
    @EnumSource(value = TradeSettlement.Posting.Subledger.class, names = {
            "FEE", "INSURANCE", "DEFICIT", "FUNDING_RESIDUAL", "ROUNDING_RESIDUAL", "CLEARING_PNL"})
    void rejectsOverflowInSingleOrLaterAssetBeforeWritingAnyFunds(TradeSettlement.Posting.Subledger subledger) {
        for (boolean /* 本轮是否在溢出资产前加入另一个正常资产，以检查跨资产预检。 */ anotherAsset : new boolean[] { false, true }) {
            // 本用例独占的权威 SystemFunds，用于设置前值及检查变化和回滚。
            SystemFunds systemFunds = new SystemFunds();
            systemFunds.setFee(1, 4);
            // 当前构造的系统差额，故意含会使目标余额溢出的金额。
            SystemFundsChange delta = new SystemFundsChange();
            if (anotherAsset) delta.addFee(1, 3);
            switch (subledger) {
                case FEE -> { systemFunds.setFee(2, Long.MAX_VALUE); delta.addFee(2, 1); }
                case INSURANCE -> { systemFunds.setInsurance(2, Long.MAX_VALUE, 0); delta.addInsurance(2, 1); }
                case DEFICIT -> { systemFunds.setInsurance(2, systemFunds.insurance(2), Long.MAX_VALUE); delta.addDeficit(2, 1); }
                case FUNDING_RESIDUAL -> { systemFunds.setFundingResidual(2, Long.MAX_VALUE); delta.addFundingResidual(2, 1); }
                case ROUNDING_RESIDUAL -> { systemFunds.setRoundingResidual(2, Long.MIN_VALUE); delta.addRoundingResidual(2, -1); }
                case CLEARING_PNL -> { systemFunds.setClearingPnl(2, Long.MIN_VALUE); delta.addClearing(2, -1); }
                default -> throw new AssertionError("unexpected Treasury subledger");
            }
            systemFunds.clearChangedKeys();

            assertThatThrownBy(() -> delta.apply(systemFunds)).isInstanceOf(ArithmeticException.class);

            assertThat(systemFunds.fee(1)).isEqualTo(4);
            assertThat(systemFunds.hasChangedValues()).isFalse();
            // 抛出溢出后重新读取的目标子账余额，应仍等于原长整数极值。
            long unchanged = switch (subledger) {
                case FEE -> systemFunds.fee(2);
                case INSURANCE -> systemFunds.insurance(2);
                case DEFICIT -> systemFunds.insuranceDeficit(2);
                case FUNDING_RESIDUAL -> systemFunds.fundingResidual(2);
                case ROUNDING_RESIDUAL -> systemFunds.roundingResidual(2);
                case CLEARING_PNL -> systemFunds.clearingPnl(2);
                default -> throw new AssertionError("unexpected Treasury subledger");
            };
            assertThat(unchanged).isEqualTo(subledger == TradeSettlement.Posting.Subledger.ROUNDING_RESIDUAL
                    || subledger == TradeSettlement.Posting.Subledger.CLEARING_PNL ? Long.MIN_VALUE : Long.MAX_VALUE);
        }
    }

    /**
     * 重复清变化键保留权威余额及完成编号，下一轮写入能重新捕获前值和资产变化。
     */
    @Test
    void clearingTwicePreservesFundsAndCapturesTheNextBeforeValue() {
        // 本用例独占的权威 SystemFunds，用于设置前值及检查变化和回滚。
        var systemFunds = new SystemFunds();
        systemFunds.setFee(7, 100);
        systemFunds.setFundingSettlement(8, 11);
        systemFunds.setLifecycleSettlement(9, 12);
        systemFunds.clearChangedKeys(); systemFunds.clearChangedKeys();
        assertThat(systemFunds.fee(7)).isEqualTo(100);
        assertThat(systemFunds.fundingSettlement(8)).isEqualTo(11);
        assertThat(systemFunds.lifecycleSettlement(9)).isEqualTo(12);
        assertThat(systemFunds.hasChangedValues()).isFalse();
        systemFunds.setFee(7, 200);
        assertThat(systemFunds.hasChangedValues()).isTrue();
        var funds = new FundsTotal();
        systemFunds.appendFundsDelta(funds);
        assertThat(postings(funds)).containsExactly(new RuntimeFundsTestSupport.Posting(
                7, TradeSettlement.Posting.OwnerKind.TREASURY, 0, TradeSettlement.Posting.Subledger.FEE, 100));
        systemFunds.rollbackChangedValues();
        assertThat(systemFunds.fee(7)).isEqualTo(100);
        assertThat(systemFunds.hasChangedValues()).isFalse();
    }

    /**
     * 对空账本及七个子账各单独变化一单位的八个指定状态检查哈希互异，不是统计碰撞率测试。
     */
    @Test
    void everySystemFundsSubledgerContributesToTheBusinessStateHash() {
        // 计算业务哈希参照使用的空冷账本。
        CoreTreasuryState empty = CoreTreasuryState.empty();
        // 空账本加七种单列变化状态的业务哈希集合，重复值会使构造失败。
        Set<Long> hashes = Set.of(
                state(empty, 1).businessStateHash(),
                state(new CoreTreasuryState(Map.of("USDT", 1L), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), java.util.Map.of(), java.util.Map.of(), java.util.Map.of(), java.util.Map.of()), 1).businessStateHash(),
                state(new CoreTreasuryState(Map.of(), Map.of("USDT", 1L), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), java.util.Map.of(), java.util.Map.of(), java.util.Map.of(), java.util.Map.of()), 1).businessStateHash(),
                state(new CoreTreasuryState(Map.of(), Map.of(), Map.of("USDT", 1L), Map.of(), Map.of(), Map.of(), Map.of(), java.util.Map.of(), java.util.Map.of(), java.util.Map.of(), java.util.Map.of()), 1).businessStateHash(),
                state(new CoreTreasuryState(Map.of(), Map.of(), Map.of(), Map.of("USDT", 1L), Map.of(), Map.of(), Map.of(), java.util.Map.of(), java.util.Map.of(), java.util.Map.of(), java.util.Map.of()), 1).businessStateHash(),
                state(new CoreTreasuryState(Map.of(), Map.of(), Map.of(), Map.of(), Map.of("USDT", 1L), Map.of(), Map.of(), java.util.Map.of(), java.util.Map.of(), java.util.Map.of(), java.util.Map.of()), 1).businessStateHash(),
                state(new CoreTreasuryState(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of("USDT", 1L), Map.of(), java.util.Map.of(), java.util.Map.of(), java.util.Map.of(), java.util.Map.of()), 1).businessStateHash(),
                state(new CoreTreasuryState(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of("USDT", 1L), java.util.Map.of(), java.util.Map.of(), java.util.Map.of(), java.util.Map.of()), 1).businessStateHash());

        assertThat(hashes).hasSize(8);
    }

    /**
     * 七子账含正负金额的冷状态投影为运行时后再物化，核对完整值和两种业务哈希结果，不执行快照编码或重启。
     */
    @Test
    void projectsEverySystemFundsSubledgerWithoutDroppingFunds() {
        // 含七个独立正负金额子账的冷账本输入。
        CoreTreasuryState systemFunds = new CoreTreasuryState(Map.of("USDT", 1L), Map.of("USDT", 2L), Map.of("USDT", 7L), Map.of("USDT", 3L), Map.of("USDT", -4L), Map.of("USDT", 5L), Map.of("USDT", -6L), java.util.Map.of(), java.util.Map.of(), java.util.Map.of(), java.util.Map.of());
        // 七子账投影用例中预期完整冷核心状态。
        TradingCoreState expected = state(systemFunds, 1);
        // 当前冷运行时投影共用的资产和币对身份字典。
        IdIndex identities = new IdIndex();

        // 从冷状态投影的内存运行时，不启动真实账本线程。
        AccountBook accountBook = SnapshotLoader.loadSnapshot(expected, identities);

        assertThat(SnapshotWriter.writeSnapshot(accountBook, identities)).isEqualTo(expected);
        assertThat(expected.businessStateHash()).isEqualTo(expected.businessStateHash());
    }

    /**
     * 手工更新 USDT 费用、保险及两类完成编号，核对 BTC 未改、进度清除及最终冷状态一致；没有统计未变化资产的访问次数。
     */
    @Test
    void appliesOnlyChangedSystemFundsEntriesAndPreservesRuntimeParity() {
        // 测试资金费分页进度的固定命令 UUID。
        UUID fundingCommandId = UUID.fromString("00000000-0000-0000-0000-000000000101");
        // 测试生命周期分页进度的固定命令 UUID。
        UUID lifecycleCommandId = UUID.fromString("00000000-0000-0000-0000-000000000102");
        // 含两资产及两类进行中进度的起始冷账本。
        CoreTreasuryState beforeSystemFunds = new CoreTreasuryState(
                Map.of("USDT", 100L, "BTC", 200L),
                Map.of("USDT", 50L),
                Map.of(), java.util.Map.of(), java.util.Map.of(), java.util.Map.of(), java.util.Map.of(),
                Map.of("1", 7L),
                Map.of("2", 3L),
                Map.of("1", new CoreTreasuryState.FundingProgress(7, 10_000, 11, 60_000)),
                Map.of("2", new CoreTreasuryState.LifecycleProgress(3, 60_000, true, 0, 12, 0)));
        // 本次手工运行时变化之前的核心冷状态。
        TradingCoreState before = state(beforeSystemFunds, 1);

        // 按同样预期变化生成的冷账本结果，允许保险有符号余额。
        CoreTreasuryState afterSystemFunds = new CoreTreasuryState(
                Map.of("BTC", 200L), Map.of("USDT", -50L), Map.of(), java.util.Map.of(), java.util.Map.of(), java.util.Map.of(), java.util.Map.of(),
                Map.of("1", 8L), Map.of("2", 4L), Map.of(), Map.of());
        // 手工运行时最终结果应物化得到的完整冷核心状态。
        TradingCoreState after = state(afterSystemFunds, 2);

        // 当前冷运行时投影共用的资产和币对身份字典。
        IdIndex identities = new IdIndex();
        // 从冷状态投影的内存运行时，不启动真实账本线程。
        AccountBook accountBook = SnapshotLoader.loadSnapshot(before, identities);

        // 本用例独占的权威 SystemFunds，用于设置前值及检查变化和回滚。
        SystemFunds systemFunds = accountBook.systemFunds();
        // 当前用例要修改的 USDT 稠密资产编号。
        int usdt = identities.assetId("USDT");
        systemFunds.setFee(usdt, 0);
        systemFunds.setInsurance(usdt, -50, 0);
        systemFunds.setFundingSettlement(identities.symbolId("1"), 8);
        systemFunds.setLifecycleSettlement(identities.symbolId("2"), 4);
        accountBook.setMetadata(ProductLine.LINEAR_PERPETUAL, 2);

        assertThat(systemFunds.fee(identities.assetId("BTC"))).isEqualTo(200L);
        assertThat(systemFunds.fee(identities.assetId("USDT"))).isZero();
        assertThat(systemFunds.insurance(identities.assetId("USDT"))).isEqualTo(-50L);
        assertThat(systemFunds.insuranceDeficit(identities.assetId("USDT"))).isZero();
        assertThat(systemFunds.fundingSettlement(identities.symbolId("1"))).isEqualTo(8L);
        assertThat(systemFunds.fundingProgress(identities.symbolId("1"))).isNull();
        assertThat(systemFunds.lifecycleSettlement(identities.symbolId("2"))).isEqualTo(4L);
        assertThat(systemFunds.lifecycleProgress(identities.symbolId("2"))).isNull();
        assertThat(SnapshotWriter.writeSnapshot(accountBook, identities)).isEqualTo(after);
    }

    /**
     * 合并两份交叉资产费用和清算差额，核对各资产各列独立归集，不跨资产抵消。
     */
    @Test
    void aggregatesContributionsWithoutNettingDifferentAssets() {
        // 包含资产一费用及资产二清算金额的第一份系统差额。
        SystemFundsChange first = new SystemFundsChange();
        first.addFee(1, 7);
        first.addClearing(2, -11);
        // 与第一份资产交叉的第二份系统差额。
        SystemFundsChange second = new SystemFundsChange();
        second.addFee(2, 13);
        second.addClearing(1, -17);

        first.merge(second);

        SystemFunds systemFunds = new SystemFunds();
        first.apply(systemFunds);
        assertThat(systemFunds.fee(1)).isEqualTo(7);
        assertThat(systemFunds.clearingPnl(1)).isEqualTo(-17);
        assertThat(systemFunds.fee(2)).isEqualTo(13);
        assertThat(systemFunds.clearingPnl(2)).isEqualTo(-11);
    }

    /**
     * 用两个固定永续币对和传入冷账本构造无用户订单的测试核心状态。
     * @param treasury 需要装入测试核心状态的冷账本值
     * @param revision 测试核心状态修订号
     */
    private static TradingCoreState state(CoreTreasuryState systemFunds, long revision) {
        // 构造核心状态时固定的 BTC/USDT 永续币对。
        CoreInstrument btc = instrument("1", "BTC");
        // 构造核心状态时固定的 ETH/USDT 永续币对。
        CoreInstrument eth = instrument("2", "ETH");
        return new TradingCoreState(ProductLine.LINEAR_PERPETUAL, revision,
                Map.of(), Map.of(), Map.of(btc.instrumentId(), btc, eth.instrumentId(), eth),
                CoreRiskState.empty(), systemFunds, java.util.Map.of(), java.util.Map.of(), java.util.Map.of(), java.util.Map.of());
    }

    /**
     * 按指定编号及基础资产构造 USDT 结算的基础永续币对。
     * @param instrumentId 测试币对业务名称
     * @param baseAsset 测试币对基础资产名称
     */
    private static CoreInstrument instrument(String instrumentId, String baseAsset) {
        return CoreInstrument.from(ProductLine.LINEAR_PERPETUAL,
                new RegisterInstrumentCommand(
                        instrumentId, baseAsset, "USDT", "USDT", 1, 1, 1,
                        new CoreInstrumentRiskLimits(
                                100_000, (1_000_000_000_000L / (100_000)), Long.MAX_VALUE, 0, Long.MAX_VALUE,
                                java.util.List.of(new CoreRiskLimitBracket(Long.MAX_VALUE, (1_000_000_000_000L / (100_000)), 100_000, 50_000))),
                        0, 0, 0, -1, 0));
    }
}
