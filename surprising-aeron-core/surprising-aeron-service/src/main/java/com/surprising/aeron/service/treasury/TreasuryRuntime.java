package com.surprising.aeron.service.treasury;

import com.surprising.aeron.service.core.change.RuntimeChangeBuffer;
import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.aeron.service.settlement.FundsPosting;
import java.util.Arrays;
import lombok.Getter;
import lombok.experimental.Accessors;
import org.eclipse.collections.impl.map.mutable.primitive.IntLongHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;
import org.eclipse.collections.impl.set.mutable.primitive.IntHashSet;

/**
 * 系统资金权威运行时。资产余额由当前账本所有者读写，首次变更保存七列前值供提交核对和回滚；金额 setter 保存有符号值，不在此统一限制负余额。币对结算进度以 volatile 单元发布只读记录，准入可以跨线程读取这些记录。待划转路由由外层既有所有权约束，本类路由方法不另做线程检查。
 */
public final class TreasuryRuntime {
    /** 当前账本所有者独占的手续费有符号余额表。 */
    private final IntLongHashMap feeBalances = new IntLongHashMap();
    /** 保险基金有符号余额表。 */
    private final IntLongHashMap insuranceBalances = new IntLongHashMap();
    /** 保险亏空余额表，与 deficit 别名共用。 */
    private final IntLongHashMap insuranceDeficits = new IntLongHashMap();
    /** 独立强平费余额表，不与保险表自动合并。 */
    private final IntLongHashMap liquidationFeeBalances = new IntLongHashMap();
    /** 资金费残差余额表。 */
    private final IntLongHashMap fundingResidualBalances = new IntLongHashMap();
    /** 舍入残差余额表。 */
    private final IntLongHashMap roundingResidualBalances = new IntLongHashMap();
    /** 系统清算盈亏余额表。 */
    private final IntLongHashMap clearingPnlBalances = new IntLongHashMap();
    /** 按稠密币对编号索引的 volatile 发布单元数组，扩容只复制单元引用，旧读者仍见同一单元。 */
    private volatile SettlementStatusCell[] settlementStates = new SettlementStatusCell[8];
    /**  非空资金费进度条目数的 volatile 索引，由账本所有者随发布维护。 直接读取已发布的非空资金费进度数量索引，用于准入栅栏，不扫描所有币对。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private volatile int incompleteFundingCount;
    /** 当前范围首次写入资产的连续原语键索引，值列不用，槽号对应七列前值。 */
    private final RuntimeChangeBuffer<Void> assetChanges = new RuntimeChangeBuffer<>();
    /** 对应捕获槽的首个手续费前值。 */
    private long[] feeBefore = new long[8];
    /** 对应捕获槽的首个保险余额前值。 */
    private long[] insuranceBefore = new long[8];
    /** 对应捕获槽的首个保险亏空前值。 */
    private long[] deficitBefore = new long[8];
    /** 对应捕获槽的首个独立强平费前值。 */
    private long[] liquidationFeeBefore = new long[8];
    /** 对应捕获槽的首个资金费残差前值。 */
    private long[] fundingResidualBefore = new long[8];
    /** 对应捕获槽的首个舍入残差前值。 */
    private long[] roundingResidualBefore = new long[8];
    /** 对应捕获槽的首个清算盈亏前值。 */
    private long[] clearingPnlBefore = new long[8];
    /** 本范围已经修改资金费记录的币对编号集合。 */
    private final IntHashSet changedFundingSymbols = new IntHashSet();
    /** 本范围已经修改生命周期记录的币对编号集合。 */
    private final IntHashSet changedLifecycleSymbols = new IntHashSet();
    /** 有资金费前值的币对到首个只读前值记录的映射，空起点不写记录。 */
    private final IntObjectHashMap<FundingState> patchFundingBefore =
            new IntObjectHashMap<>();
    /** 有生命周期前值的币对到首个前值记录的映射。 */
    private final IntObjectHashMap<LifecycleState> patchLifecycleBefore =
            new IntObjectHashMap<>();
    /** 当前允许访问资金 Map 的所有者线程，volatile 交接不代替调用方停止在途访问。 */
    private volatile Thread owner;
    /** 当前是否处在只允许资产资金变化的订单批次范围。 */
    private boolean orderBatchMutationScope;

    /**
     * 未绑定时认领当前线程，已绑定时要求同一线程；调用方负责交接期间无并发访问，这不是多线程争抢所有权的锁。
     */
    void assertOwner() {
        // 当前线程，或本币对本次修改前已发布的完整结算记录，按所在步骤使用。
        Thread current = Thread.currentThread();
        if (owner == null) owner = current;
        else if (owner != current) throw new IllegalStateException("treasury runtime is bound to another thread");
    }

    /**
     * 由预定账本线程确认自己与当前所有者一致，未绑定时按既有规则认领。
     */
    void bindOwner() { assertOwner(); }

    /**
     * 原所有者在启动工作线程前交出同一财库，目标必须非空且尚未启动。
     * @param nextOwner 按既有静默边界接管原财库的线程，transferOwner 可用空值解除绑定
     */
    void handoffTo(Thread nextOwner) {
        assertOwner();
        if (nextOwner == null || nextOwner.isAlive())
            throw new IllegalArgumentException("ledger handoff requires an unstarted owner thread");
        owner = nextOwner;
    }

    /**
     * 当前所有者显式解除绑定，下一执行方可在既有交接边界认领。
     */
    public void releaseOwnerForHandoff() {
        assertOwner();
        owner = null;
    }

    /**
     * 按 SystemLedgerLane 已确认静默的边界直接迁移所有者，volatile 写入提供可见性；本方法不自行等在途工作。
     * @param nextOwner 按既有静默边界接管原财库的线程，transferOwner 可用空值解除绑定
     */
    void transferOwner(Thread nextOwner) { owner = nextOwner; }

    /**
     * 当前所有者读取指定资产手续费余额，缺失按原语 Map 返回零。
     * @param assetId 需要读写或捕获的非负资产编号
     */
    public long fee(int assetId) { assertOwner(); return feeBalances.get(assetId); }
    /**
     * 当前所有者读取指定资产保险基金余额。
     * @param assetId 需要读写或捕获的非负资产编号
     */
    public long insurance(int assetId) { assertOwner(); return insuranceBalances.get(assetId); }
    /**
     * 当前所有者读取指定资产保险亏空余额。
     * @param assetId 需要读写或捕获的非负资产编号
     */
    public long insuranceDeficit(int assetId) { assertOwner(); return insuranceDeficits.get(assetId); }
    /**
     * 沿现有别名读取同一份保险亏空余额，不另存亏空状态。
     * @param assetId 需要读写或捕获的非负资产编号
     */
    public long deficit(int assetId) { assertOwner(); return insuranceDeficits.get(assetId); }
    /**
     * 当前所有者读取指定资产独立强平费余额。
     * @param assetId 需要读写或捕获的非负资产编号
     */
    public long liquidationFee(int assetId) { assertOwner(); return liquidationFeeBalances.get(assetId); }
    /**
     * 当前所有者读取指定资产资金费残差余额。
     * @param assetId 需要读写或捕获的非负资产编号
     */
    public long fundingResidual(int assetId) { assertOwner(); return fundingResidualBalances.get(assetId); }
    /**
     * 当前所有者读取指定资产舍入残差余额。
     * @param assetId 需要读写或捕获的非负资产编号
     */
    public long roundingResidual(int assetId) { assertOwner(); return roundingResidualBalances.get(assetId); }
    /**
     * 当前所有者读取指定资产系统清算盈亏余额。
     * @param assetId 需要读写或捕获的非负资产编号
     */
    public long clearingPnl(int assetId) { assertOwner(); return clearingPnlBalances.get(assetId); }
    /**
     * 从已发布币对结算记录读取最近完成资金费编号，未记录时为零，不访问可变资金 Map。
     * @param symbolId 稠密币对编号
     */
    public long fundingSettlement(int symbolId) { return settlementStatus(symbolId).fundingSettlementId(); }
    /**
     * 借用已发布资金费分页进度，空值表示当前无该进度，可供准入读取。
     * @param symbolId 稠密币对编号
     */
    public FundingProgressRuntime fundingProgress(int symbolId) { return settlementStatus(symbolId).fundingProgress(); }
    /**
     * 从已发布记录读取最近完成的交割或行权结算编号，未记录为零。
     * @param symbolId 稠密币对编号
     */
    public long lifecycleSettlement(int symbolId) { return settlementStatus(symbolId).lifecycleSettlementId(); }
    /**
     * 借用已发布生命周期分页进度，不复制或读取账本可变资金表。
     * @param symbolId 稠密币对编号
     */
    public LifecycleProgressRuntime lifecycleProgress(int symbolId) { return settlementStatus(symbolId).lifecycleProgress(); }

    /**
     * 当前所有者进入只允许资产变化的订单批次范围，重复进入拒绝。
     */
    public void beginOrderBatchMutationScope() {
        assertOwner();
        if (orderBatchMutationScope) throw new IllegalStateException("Treasury order batch scope is already active");
        orderBatchMutationScope = true;
    }

    /**
     * 当前所有者结束订单批次范围；此处不提交、清空或回滚已捕获变化。
     */
    public void endOrderBatchMutationScope() {
        assertOwner();
        orderBatchMutationScope = false;
    }

    /**
     * 订单批次范围内拒绝修改资金费或生命周期进度等非资产域，异常说明具体域。
     * @param domain 订单批次内禁止修改的非资产域名称，用于错误说明
     */
    private void rejectNonAssetOrderBatchMutation(String domain) {
        if (orderBatchMutationScope) throw new IllegalStateException("order batch cannot mutate " + domain);
    }

    /**
     * 精确加总七类资金 Map 的条目数，同一资产在多类中分别计数，不是不同资产总数。
     */
    public int assetLedgerEntryCount() {
        assertOwner();
        return Math.addExact(Math.addExact(Math.addExact(feeBalances.size(), insuranceBalances.size()),
                        Math.addExact(insuranceDeficits.size(), liquidationFeeBalances.size())),
                Math.addExact(Math.addExact(fundingResidualBalances.size(), roundingResidualBalances.size()),
                        clearingPnlBalances.size()));
    }

    /**
     * 当前所有者先捕获该资产本范围首个前值，再设置有符号手续费余额，零值从 Map 移除。
     * @param assetId 需要读写或捕获的非负资产编号
     * @param units 要设置的有符号余额，零值移除对应键
     */
    public void setFee(int assetId, long units) {
        assertOwner();
        captureAssetBefore(assetId);
        setSigned(feeBalances, assetId, units);
    }

    /**
     * 先一次捕获该资产全部资金前值，再同时设置有符号保险余额和亏空余额，零值移除。
     * @param assetId 需要读写或捕获的非负资产编号
     * @param units 要设置的有符号余额，零值移除对应键
     * @param deficit 保险亏空余额或前值
     */
    public void setInsurance(int assetId, long units, long deficit) {
        assertOwner();
        captureAssetBefore(assetId);
        setSigned(insuranceBalances, assetId, units);
        setSigned(insuranceDeficits, assetId, deficit);
    }

    /**
     * 精确计算保险余额加本次变化，再捕获前值并写入，不在此调整其他子账。
     * @param assetId 需要读写或捕获的非负资产编号
     * @param deltaUnits 保险基金有符号增减额
     */
    public void adjustInsurance(int assetId, long deltaUnits) {
        assertOwner();
        // 保险余额精确加本次变化后的新金额。
        long units = Math.addExact(insurance(assetId), deltaUnits);
        captureAssetBefore(assetId);
        setSigned(insuranceBalances, assetId, units);
    }

    /**
     * 捕获本资产首次前值，再设置独立强平费余额，零值移除。
     * @param assetId 需要读写或捕获的非负资产编号
     * @param units 要设置的有符号余额，零值移除对应键
     */
    public void setLiquidationFee(int assetId, long units) {
        assertOwner();
        captureAssetBefore(assetId);
        setSigned(liquidationFeeBalances, assetId, units);
    }

    /**
     * 捕获本资产首次前值，再设置资金费残差余额。
     * @param assetId 需要读写或捕获的非负资产编号
     * @param units 要设置的有符号余额，零值移除对应键
     */
    public void setFundingResidual(int assetId, long units) {
        assertOwner();
        captureAssetBefore(assetId);
        setSigned(fundingResidualBalances, assetId, units);
    }

    /**
     * 捕获本资产首次前值，再设置舍入残差余额。
     * @param assetId 需要读写或捕获的非负资产编号
     * @param units 要设置的有符号余额，零值移除对应键
     */
    public void setRoundingResidual(int assetId, long units) {
        assertOwner();
        captureAssetBefore(assetId);
        setSigned(roundingResidualBalances, assetId, units);
    }

    /**
     * 捕获本资产首次前值，再设置系统清算盈亏余额。
     * @param assetId 需要读写或捕获的非负资产编号
     * @param units 要设置的有符号余额，零值移除对应键
     */
    public void setClearingPnl(int assetId, long units) {
        assertOwner();
        captureAssetBefore(assetId);
        setSigned(clearingPnlBalances, assetId, units);
    }

    /**
     * 捕获本资产首次前值，再设置同一份保险亏空余额。
     * @param assetId 需要读写或捕获的非负资产编号
     * @param units 要设置的有符号余额，零值移除对应键
     */
    public void setDeficit(int assetId, long units) {
        assertOwner();
        captureAssetBefore(assetId);
        setSigned(insuranceDeficits, assetId, units);
    }

    /**
     * 禁止订单批次内修改，检查币对及正结算编号，捕获首个资金费前值，再发布完成编号并清除该币对资金费进度，保留生命周期记录。
     * @param symbolId 稠密币对编号
     * @param settlementId 当前资金费或生命周期结算编号，完成入口要求正数
     */
    public void setFundingSettlement(int symbolId, long settlementId) {
        assertOwner();
        rejectNonAssetOrderBatchMutation("Treasury funding state");
        if (symbolId < 0 || settlementId <= 0) {
            throw new IllegalArgumentException("invalid runtime funding settlement");
        }
        captureFundingBefore(symbolId);
        // 本次更新前完整结算记录，或当前划转已有的用户归属。
        SettlementStatus previous = settlementStatus(symbolId);
        publishSettlementStatus(symbolId, new SettlementStatus(settlementId, null,
                previous.lifecycleSettlementId(), previous.lifecycleProgress()));
        changedFundingSymbols.add(symbolId);
    }

    /**
     * 检查币对与非空资金费进度，捕获首个前值，再发布新进度并保留完成编号和生命周期记录，登记变更币对。
     * @param symbolId 稠密币对编号
     * @param progress 本币对分页进度值，前值记录中可为空，设置进度入口要求非空
     */
    public void setFundingProgress(int symbolId, FundingProgressRuntime progress) {
        assertOwner();
        rejectNonAssetOrderBatchMutation("Treasury funding state");
        if (symbolId < 0 || progress == null) {
            throw new IllegalArgumentException("invalid runtime funding progress");
        }
        captureFundingBefore(symbolId);
        // 本次更新前完整结算记录，或当前划转已有的用户归属。
        SettlementStatus previous = settlementStatus(symbolId);
        publishSettlementStatus(symbolId, new SettlementStatus(previous.fundingSettlementId(), progress,
                previous.lifecycleSettlementId(), previous.lifecycleProgress()));
        changedFundingSymbols.add(symbolId);
    }

    /**
     * 检查并捕获生命周期前值，发布正完成编号并清除生命周期进度，资金费部分保持原值。
     * @param symbolId 稠密币对编号
     * @param settlementId 当前资金费或生命周期结算编号，完成入口要求正数
     */
    public void setLifecycleSettlement(int symbolId, long settlementId) {
        assertOwner();
        rejectNonAssetOrderBatchMutation("Treasury lifecycle state");
        if (symbolId < 0 || settlementId <= 0) throw new IllegalArgumentException("invalid lifecycle settlement");
        captureLifecycleBefore(symbolId);
        // 本次更新前完整结算记录，或当前划转已有的用户归属。
        SettlementStatus previous = settlementStatus(symbolId);
        publishSettlementStatus(symbolId, new SettlementStatus(previous.fundingSettlementId(),
                previous.fundingProgress(), settlementId, null));
        changedLifecycleSymbols.add(symbolId);
    }

    /**
     * 检查并捕获生命周期前值，发布新生命周期进度并保留原完成编号及资金费记录。
     * @param symbolId 稠密币对编号
     * @param progress 本币对分页进度值，前值记录中可为空，设置进度入口要求非空
     */
    public void setLifecycleProgress(int symbolId, LifecycleProgressRuntime progress) {
        assertOwner();
        rejectNonAssetOrderBatchMutation("Treasury lifecycle state");
        if (symbolId < 0 || progress == null) throw new IllegalArgumentException("invalid lifecycle progress");
        captureLifecycleBefore(symbolId);
        // 本次更新前完整结算记录，或当前划转已有的用户归属。
        SettlementStatus previous = settlementStatus(symbolId);
        publishSettlementStatus(symbolId, new SettlementStatus(previous.fundingSettlementId(),
                previous.fundingProgress(), previous.lifecycleSettlementId(), progress));
        changedLifecycleSymbols.add(symbolId);
    }

    /**
     * 为当前范围已捕获资产编号新建集合并返回，不暴露内部变更索引，也不代表金额最终必然不同。
     */
    public IntHashSet changedAssets() {
        assertOwner();
        // 为导出边界新建的资产集合或结算进度原语 Map，不是内部权威表引用。
        IntHashSet result = new IntHashSet();
        for (/* 当前资产捕获列或返回页的有效原语下标。 */ int index = 0; index < assetChanges.size(); index++) {
            result.add((int) assetChanges.keyAt(index));
        }
        return result;
    }

    /**
     * 检查资产、资金费或生命周期是否有已登记变化键，不逐项比较最终值与前值。
     */
    public boolean hasChangedValues() {
        assertOwner();
        return !assetChanges.isEmpty() || !changedFundingSymbols.isEmpty() || !changedLifecycleSymbols.isEmpty();
    }

    /**
     * 复制当前范围资金费变更币对集合，调用方不修改内部集合。
     */
    IntHashSet changedFundingSymbols() {
        assertOwner();
        return new IntHashSet(changedFundingSymbols);
    }

    /**
     * 复制当前范围生命周期变更币对集合。
     */
    IntHashSet changedLifecycleSymbols() {
        assertOwner();
        return new IntHashSet(changedLifecycleSymbols);
    }

    /**
     * 清除当前范围变化键及进度前值引用，保留资产原语前值数组容量；不修改资金权威余额。
     */
    public void clearChangedKeys() {
        assertOwner();
        assetChanges.clear();
        if (!changedFundingSymbols.isEmpty()) changedFundingSymbols.clear();
        if (!changedLifecycleSymbols.isEmpty()) changedLifecycleSymbols.clear();
        if (!patchFundingBefore.isEmpty()) patchFundingBefore.clear();
        if (!patchLifecycleBefore.isEmpty()) patchLifecycleBefore.clear();
    }

    /**
     * 按捕获槽物化该资产首个七列前值；未捕获或七列全零时返回空，使用零与缺失等价语义。
     * @param assetId 需要读写或捕获的非负资产编号
     */
    AssetState patchAssetBefore(int assetId) {
        assertOwner();
        // 当前资产捕获列或返回页的有效原语下标。
        int index = assetChanges.indexOf(assetId);
        if (index < 0 || (feeBefore[index] | insuranceBefore[index] | deficitBefore[index]
                | liquidationFeeBefore[index] | fundingResidualBefore[index]
                | roundingResidualBefore[index] | clearingPnlBefore[index]) == 0) return null;
        return new AssetState(feeBefore[index], insuranceBefore[index], deficitBefore[index],
                liquidationFeeBefore[index], fundingResidualBefore[index], roundingResidualBefore[index],
                clearingPnlBefore[index]);
    }

    /**
     * 当前所有者直接比较七列权威余额与首次前值，向原语缓冲追加 Treasury 分录；亏空变化反号记入资金，不物化资产快照或清空变化键。
     * @param accumulator 接收七类 Treasury 有符号资金变化的原语缓冲
     */
    public void appendFundsDelta(RuntimeFundsAccumulator accumulator) {
        assertOwner();
        if (accumulator == null) throw new IllegalArgumentException("funds accumulator is required");
        for (/* 当前资产捕获列或返回页的有效原语下标。 */ int index = 0; index < assetChanges.size(); index++) {
            // 本次变化槽对应的资产编号。
            int assetId = (int) assetChanges.keyAt(index);
            accumulator.add(assetId, FundsPosting.OwnerKind.TREASURY, 0, FundsPosting.Subledger.FEE,
                    Math.subtractExact(feeBalances.get(assetId), feeBefore[index]));
            accumulator.add(assetId, FundsPosting.OwnerKind.TREASURY, 0, FundsPosting.Subledger.INSURANCE,
                    Math.subtractExact(insuranceBalances.get(assetId), insuranceBefore[index]));
            accumulator.add(assetId, FundsPosting.OwnerKind.TREASURY, 0, FundsPosting.Subledger.DEFICIT,
                    Math.negateExact(Math.subtractExact(insuranceDeficits.get(assetId), deficitBefore[index])));
            accumulator.add(assetId, FundsPosting.OwnerKind.TREASURY, 0, FundsPosting.Subledger.LIQUIDATION_FEE,
                    Math.subtractExact(liquidationFeeBalances.get(assetId), liquidationFeeBefore[index]));
            accumulator.add(assetId, FundsPosting.OwnerKind.TREASURY, 0, FundsPosting.Subledger.FUNDING_RESIDUAL,
                    Math.subtractExact(fundingResidualBalances.get(assetId), fundingResidualBefore[index]));
            accumulator.add(assetId, FundsPosting.OwnerKind.TREASURY, 0, FundsPosting.Subledger.ROUNDING_RESIDUAL,
                    Math.subtractExact(roundingResidualBalances.get(assetId), roundingResidualBefore[index]));
            accumulator.add(assetId, FundsPosting.OwnerKind.TREASURY, 0, FundsPosting.Subledger.CLEARING_PNL,
                    Math.subtractExact(clearingPnlBalances.get(assetId), clearingPnlBefore[index]));
        }
    }

    /**
     * 借用当前范围捕获的资金费前值记录，原来无编号及进度时可能为空。
     * @param symbolId 稠密币对编号
     */
    FundingState patchFundingBefore(int symbolId) {
        assertOwner();
        return patchFundingBefore.get(symbolId);
    }

    /**
     * 借用当前范围捕获的生命周期前值记录，可能为空。
     * @param symbolId 稠密币对编号
     */
    LifecycleState patchLifecycleBefore(int symbolId) {
        assertOwner();
        return patchLifecycleBefore.get(symbolId);
    }

    /**
     * 恢复捕获资产七列首值及变化币对的资金费和生命周期前值，重新发布进度以同步数量索引，最后清理本范围变化键。
     */
    public void rollbackChangedValues() {
        assertOwner();
        for (/* 当前资产捕获列或返回页的有效原语下标。 */ int index = 0; index < assetChanges.size(); index++) {
            // 本次变化槽对应的资产编号。
            int assetId = (int) assetChanges.keyAt(index);
            restoreSigned(feeBalances, assetId, feeBefore[index]);
            restoreSigned(insuranceBalances, assetId, insuranceBefore[index]);
            restoreSigned(insuranceDeficits, assetId, deficitBefore[index]);
            restoreSigned(liquidationFeeBalances, assetId, liquidationFeeBefore[index]);
            restoreSigned(fundingResidualBalances, assetId, fundingResidualBefore[index]);
            restoreSigned(roundingResidualBalances, assetId, roundingResidualBefore[index]);
            restoreSigned(clearingPnlBalances, assetId, clearingPnlBefore[index]);
        }
        for (int /* 当前遍历发布单元或恢复进度的币对编号。 */ symbolId : changedFundingSymbols.toArray()) {
            // 当前币对首个资金费或生命周期前值记录，空表示原来没有事实。
            FundingState before = patchFundingBefore.get(symbolId);
            // 当前线程，或本币对本次修改前已发布的完整结算记录，按所在步骤使用。
            SettlementStatus current = settlementStatus(symbolId);
            publishSettlementStatus(symbolId, new SettlementStatus(before == null ? 0 : before.settlementId(),
                    before == null ? null : before.progress(), current.lifecycleSettlementId(),
                    current.lifecycleProgress()));
        }
        for (int /* 当前遍历发布单元或恢复进度的币对编号。 */ symbolId : changedLifecycleSymbols.toArray()) {
            // 当前币对首个资金费或生命周期前值记录，空表示原来没有事实。
            LifecycleState before = patchLifecycleBefore.get(symbolId);
            // 当前线程，或本币对本次修改前已发布的完整结算记录，按所在步骤使用。
            SettlementStatus current = settlementStatus(symbolId);
            publishSettlementStatus(symbolId, new SettlementStatus(current.fundingSettlementId(),
                    current.fundingProgress(), before == null ? 0 : before.settlementId(),
                    before == null ? null : before.progress()));
        }
        clearChangedKeys();
    }

    /**
     * 清空权威资产资金和已发布结算进度，保留发布单元数组并把未完成资金费数归零；不清当前回滚前值、变化键或待划转路由，重建调用方另行管理这些边界。
     */
    public void clear() {
        assertOwner();
        feeBalances.clear();
        insuranceBalances.clear();
        insuranceDeficits.clear();
        liquidationFeeBalances.clear();
        fundingResidualBalances.clear();
        roundingResidualBalances.clear();
        clearingPnlBalances.clear();
        for (SettlementStatusCell /* 本币对共享的发布单元，扩容数组时单元本身保持同一引用。 */ cell : settlementStates) {
            if (cell != null) cell.value = SettlementStatus.EMPTY;
        }
        incompleteFundingCount = 0;
    }

    /**
     * 当前所有者借用权威手续费原语 Map；直接改 Map 不自动捕获前值，事务变化应走 setter。
     */
    public IntLongHashMap feeBalances() { assertOwner(); return feeBalances; }
    /**
     * 当前所有者借用权威保险余额 Map，不复制内容或自动捕获直接写入。
     */
    public IntLongHashMap insuranceBalances() { assertOwner(); return insuranceBalances; }
    /**
     * 当前所有者借用权威保险亏空 Map。
     */
    public IntLongHashMap insuranceDeficits() { assertOwner(); return insuranceDeficits; }
    /**
     * 以别名返回同一保险亏空 Map，不创建第二份表。
     */
    public IntLongHashMap deficitBalances() { assertOwner(); return insuranceDeficits; }
    /**
     * 当前所有者借用独立强平费余额 Map。
     */
    public IntLongHashMap liquidationFeeBalances() { assertOwner(); return liquidationFeeBalances; }
    /**
     * 当前所有者借用资金费残差余额 Map。
     */
    public IntLongHashMap fundingResidualBalances() { assertOwner(); return fundingResidualBalances; }
    /**
     * 当前所有者借用舍入残差余额 Map。
     */
    public IntLongHashMap roundingResidualBalances() { assertOwner(); return roundingResidualBalances; }
    /**
     * 当前所有者借用系统清算盈亏余额 Map。
     */
    public IntLongHashMap clearingPnlBalances() { assertOwner(); return clearingPnlBalances; }
    /**
     * 当前所有者遍历发布单元，物化非零资金费完成编号的新 Map，作为导出或恢复边界值。
     */
    public IntLongHashMap fundingSettlements() {
        assertOwner();
        // 为导出边界新建的资产集合或结算进度原语 Map，不是内部权威表引用。
        IntLongHashMap result = new IntLongHashMap();
        // 当前借用或扩容后的结算发布单元数组。
        SettlementStatusCell[] states = settlementStates;
        for (/* 当前遍历发布单元或恢复进度的币对编号。 */ int symbolId = 0; symbolId < states.length; symbolId++) {
            // 本币对共享的发布单元，扩容数组时单元本身保持同一引用。
            SettlementStatusCell cell = states[symbolId];
            if (cell != null && cell.value.fundingSettlementId() != 0)
                result.put(symbolId, cell.value.fundingSettlementId());
        }
        return result;
    }
    /**
     * 当前所有者物化有资金费进度币对的新 Map，进度记录本身继续共享只读值。
     */
    public IntObjectHashMap<FundingProgressRuntime> fundingProgresses() {
        assertOwner();
        // 为导出边界新建的资产集合或结算进度原语 Map，不是内部权威表引用。
        IntObjectHashMap<FundingProgressRuntime> result = new IntObjectHashMap<>();
        // 当前借用或扩容后的结算发布单元数组。
        SettlementStatusCell[] states = settlementStates;
        for (/* 当前遍历发布单元或恢复进度的币对编号。 */ int symbolId = 0; symbolId < states.length; symbolId++) {
            // 本币对共享的发布单元，扩容数组时单元本身保持同一引用。
            SettlementStatusCell cell = states[symbolId];
            if (cell != null && cell.value.fundingProgress() != null)
                result.put(symbolId, cell.value.fundingProgress());
        }
        return result;
    }

    /**
     * 当前所有者物化非零生命周期完成编号的新 Map。
     */
    public IntLongHashMap lifecycleSettlements() {
        assertOwner();
        // 为导出边界新建的资产集合或结算进度原语 Map，不是内部权威表引用。
        IntLongHashMap result = new IntLongHashMap();
        // 当前借用或扩容后的结算发布单元数组。
        SettlementStatusCell[] states = settlementStates;
        for (/* 当前遍历发布单元或恢复进度的币对编号。 */ int symbolId = 0; symbolId < states.length; symbolId++) {
            // 本币对共享的发布单元，扩容数组时单元本身保持同一引用。
            SettlementStatusCell cell = states[symbolId];
            if (cell != null && cell.value.lifecycleSettlementId() != 0)
                result.put(symbolId, cell.value.lifecycleSettlementId());
        }
        return result;
    }
    /**
     * 当前所有者物化有生命周期进度币对的新 Map。
     */
    public IntObjectHashMap<LifecycleProgressRuntime> lifecycleProgresses() {
        assertOwner();
        // 为导出边界新建的资产集合或结算进度原语 Map，不是内部权威表引用。
        IntObjectHashMap<LifecycleProgressRuntime> result = new IntObjectHashMap<>();
        // 当前借用或扩容后的结算发布单元数组。
        SettlementStatusCell[] states = settlementStates;
        for (/* 当前遍历发布单元或恢复进度的币对编号。 */ int symbolId = 0; symbolId < states.length; symbolId++) {
            // 本币对共享的发布单元，扩容数组时单元本身保持同一引用。
            SettlementStatusCell cell = states[symbolId];
            if (cell != null && cell.value.lifecycleProgress() != null)
                result.put(symbolId, cell.value.lifecycleProgress());
        }
        return result;
    }

    /**
     * 借用对应单元已发布的只读结算记录，越界、负编号或未建单元返回共用空值，允许准入线程读取。
     * @param symbolId 稠密币对编号
     */
    public SettlementStatus settlementStatus(int symbolId) {
        // 当前借用或扩容后的结算发布单元数组。
        SettlementStatusCell[] states = settlementStates;
        if (symbolId < 0 || symbolId >= states.length) return SettlementStatus.EMPTY;
        // 本币对共享的发布单元，扩容数组时单元本身保持同一引用。
        SettlementStatusCell cell = states[symbolId];
        return cell == null ? SettlementStatus.EMPTY : cell.value;
    }

    /**
     * 账本所有者按需扩容发布单元数组，创建单元时重新 volatile 发布数组，再替换单元只读值；按资金费进度是否为空维护未完成数量，不维护第二份进度 Map。
     * @param symbolId 稠密币对编号
     * @param value 替换该币对发布单元的完整只读结算记录
     */
    private void publishSettlementStatus(int symbolId, SettlementStatus value) {
        // 当前借用或扩容后的结算发布单元数组。
        SettlementStatusCell[] states = settlementStates;
        if (symbolId >= states.length) {
            // 发布单元或资产前值列本次需要的增长容量。
            int capacity = Math.max(Math.addExact(symbolId, 1), Math.multiplyExact(states.length, 2));
            states = Arrays.copyOf(states, capacity);
        }
        // 本币对共享的发布单元，扩容数组时单元本身保持同一引用。
        SettlementStatusCell cell = states[symbolId];
        if (cell == null) {
            cell = new SettlementStatusCell();
            states[symbolId] = cell;
            // 新单元需要通过 volatile 数组引用发布，即使本次没有扩容也一样。
            settlementStates = states;
        }
        // 替换前该币对是否有非空资金费进度。
        boolean wasIncomplete = cell.value.fundingProgress() != null;
        // 替换后该币对是否有非空资金费进度，用来调整未完成计数。
        boolean isIncomplete = value.fundingProgress() != null;
        cell.value = value;
        if (wasIncomplete != isIncomplete) incompleteFundingCount += isIncomplete ? 1 : -1;
    }

    /**
     * 按币对长期保留的结算发布单元，volatile 字段指向只读记录，旧数组读者与新数组读者共享同一单元。
     */
    private static final class SettlementStatusCell {
        /** 本币对最近发布的只读结算记录，volatile 替换供准入观察。 */
        private volatile SettlementStatus value = SettlementStatus.EMPTY;
    }

    /**
     * 一份币对资金费及生命周期已完成编号与进行中进度的只读记录，账本替换发布，准入只读取。
     * @param fundingSettlementId 最近完成的资金费编号，零表示没有记录
     * @param fundingProgress 当前尚未完成的资金费分页进度，可为空
     * @param lifecycleSettlementId 最近完成的交割或行权结算编号，零表示没有记录
     * @param lifecycleProgress 当前生命周期分页进度，可为空
     */
    public record SettlementStatus(long fundingSettlementId, FundingProgressRuntime fundingProgress,
                                   long lifecycleSettlementId, LifecycleProgressRuntime lifecycleProgress) {
        /** 没有资金费或生命周期结算事实的共用空记录。 */
        private static final SettlementStatus EMPTY = new SettlementStatus(0, null, 0, null);
        /**
         * 当前记录生命周期完成编号非零时返回 true，不根据分页进度另行计算。
         */
        public boolean lifecycleSettled() { return lifecycleSettlementId != 0; }
        /**
         * 当前记录存在非空资金费分页进度时返回 true。
         */
        public boolean fundingInProgress() { return fundingProgress != null; }
    }

    /**
     * 检查非负资产编号，零余额删除键，其余有符号金额直接保存，不统一限制金额正负。
     * @param balances 需要规范化写入的资产原语 Map
     * @param assetId 需要读写或捕获的非负资产编号
     * @param units 要设置的有符号余额，零值移除对应键
     */
    private static void setSigned(IntLongHashMap balances, int assetId, long units) {
        if (assetId < 0) throw new IllegalArgumentException("invalid treasury asset");
        if (units == 0) balances.remove(assetId); else balances.put(assetId, units);
    }

    /**
     * 按已捕获可信键值直接恢复原语 Map，零值删除，不再次执行公开写入捕获。
     * @param values 需要恢复前值的资产原语 Map
     * @param key 已经捕获的可信资产键
     * @param value 要恢复的原语金额，或发布入口对应的只读结算记录
     */
    private static void restoreSigned(IntLongHashMap values, int key, long value) {
        if (value == 0) values.remove(key); else values.put(key, value);
    }

    /**
     * 本范围首次触及资产时，按同一变化槽保存七列首值，已有捕获不覆盖；容量不足一起扩容原语前值列。
     * @param assetId 需要读写或捕获的非负资产编号
     */
    private void captureAssetBefore(int assetId) {
        if (assetId < 0) throw new IllegalArgumentException("invalid treasury asset");
        if (assetChanges.containsKey(assetId)) return;
        if (assetChanges.size() == feeBefore.length) {
            // 发布单元或资产前值列本次需要的增长容量。
            int capacity = Math.multiplyExact(feeBefore.length, 2);
            feeBefore = Arrays.copyOf(feeBefore, capacity);
            insuranceBefore = Arrays.copyOf(insuranceBefore, capacity);
            deficitBefore = Arrays.copyOf(deficitBefore, capacity);
            liquidationFeeBefore = Arrays.copyOf(liquidationFeeBefore, capacity);
            fundingResidualBefore = Arrays.copyOf(fundingResidualBefore, capacity);
            roundingResidualBefore = Arrays.copyOf(roundingResidualBefore, capacity);
            clearingPnlBefore = Arrays.copyOf(clearingPnlBefore, capacity);
        }
        // 当前资产捕获列或返回页的有效原语下标。
        int index = assetChanges.put(assetId, null);
        feeBefore[index] = feeBalances.get(assetId);
        insuranceBefore[index] = insuranceBalances.get(assetId);
        deficitBefore[index] = insuranceDeficits.get(assetId);
        liquidationFeeBefore[index] = liquidationFeeBalances.get(assetId);
        fundingResidualBefore[index] = fundingResidualBalances.get(assetId);
        roundingResidualBefore[index] = roundingResidualBalances.get(assetId);
        clearingPnlBefore[index] = clearingPnlBalances.get(assetId);
    }

    /**
     * 本范围首次修改币对资金费时，原编号或进度存在才保存只读前值，空起点不另建记录。
     * @param symbolId 稠密币对编号
     */
    private void captureFundingBefore(int symbolId) {
        if (changedFundingSymbols.contains(symbolId)) return;
        // 当前线程，或本币对本次修改前已发布的完整结算记录，按所在步骤使用。
        SettlementStatus current = settlementStatus(symbolId);
        // 首次捕获时已经发布的完成结算编号。
        long settlementId = current.fundingSettlementId();
        // 首次捕获时已经发布的对应分页进度引用。
        FundingProgressRuntime progress = current.fundingProgress();
        if (settlementId != 0 || progress != null) {
            patchFundingBefore.put(symbolId,
                    new FundingState(settlementId, progress));
        }
    }

    /**
     * 本范围首次修改生命周期时，原编号或进度存在才保存前值，重复修改沿用首值。
     * @param symbolId 稠密币对编号
     */
    private void captureLifecycleBefore(int symbolId) {
        if (changedLifecycleSymbols.contains(symbolId)) return;
        // 当前线程，或本币对本次修改前已发布的完整结算记录，按所在步骤使用。
        SettlementStatus current = settlementStatus(symbolId);
        // 首次捕获时已经发布的完成结算编号。
        long settlementId = current.lifecycleSettlementId();
        // 首次捕获时已经发布的对应分页进度引用。
        LifecycleProgressRuntime progress = current.lifecycleProgress();
        if (settlementId != 0 || progress != null) {
            patchLifecycleBefore.put(symbolId,
                    new LifecycleState(settlementId, progress));
        }
    }

    /**
     * 为回滚或导出物化的单资产七类系统资金前值，不是在线权威余额表。
     * @param fee 该资产手续费前值
     * @param insurance 该资产保险基金前值
     * @param deficit 保险亏空余额或前值
     * @param liquidationFee 该资产独立强平费前值
     * @param fundingResidual 该资产资金费残差前值
     * @param roundingResidual 该资产舍入残差前值
     * @param clearingPnl 该资产系统清算盈亏前值
     */
    record AssetState(long fee, long insurance, long deficit, long liquidationFee,
                      long fundingResidual, long roundingResidual, long clearingPnl) { }

    /**
     * 当前控制范围首次修改前的资金费完成编号及可空分页进度。
     * @param settlementId 当前资金费或生命周期结算编号，完成入口要求正数
     * @param progress 本币对分页进度值，前值记录中可为空，设置进度入口要求非空
     */
    record FundingState(long settlementId, FundingProgressRuntime progress) { }

    /**
     * 当前控制范围首次修改前的生命周期完成编号及可空分页进度。
     * @param settlementId 当前资金费或生命周期结算编号，完成入口要求正数
     * @param progress 本币对分页进度值，前值记录中可为空，设置进度入口要求非空
     */
    record LifecycleState(long settlementId, LifecycleProgressRuntime progress) { }

    /** 资金费续页的固定费率、用户游标和价格证据。 */
    public record FundingProgressRuntime(long settlementId, CoreInstrument instrument, long fundingRatePpm,
            long nextCursorUserId, long markPriceTicks, long priceSequence) {
        public FundingProgressRuntime {
            if (settlementId <= 0 || instrument == null || Math.absExact(fundingRatePpm) > 1_000_000
                    || nextCursorUserId < 0 || markPriceTicks <= 0 || priceSequence <= 0) {
                throw new IllegalArgumentException("invalid funding progress");
            }
        }
    }

    /** 到期结算的固定价格、撤单/用户游标及本页保险需求。 */
    public record LifecycleProgressRuntime(long settlementId, long settlementPriceTicks,
            boolean ordersComplete, long nextCursorOrderId, long nextCursorUserId,
            long requiredInsuranceUnits) {
        public LifecycleProgressRuntime {
            if (settlementId <= 0 || settlementPriceTicks < 0
                    || requiredInsuranceUnits < 0 || requiredInsuranceUnits > 0 && !ordersComplete
                    || nextCursorOrderId < 0 || nextCursorUserId < 0
                    || (!ordersComplete && nextCursorUserId != 0) || (ordersComplete && nextCursorOrderId != 0)) {
                throw new IllegalArgumentException("invalid lifecycle progress");
            }
        }
    }

    /** 正常登记待划转路由时使用的十三万一千零七十二条上限。 */
    public static final int MAX_PENDING_TRANSFERS = 131_072;
    /** 按划转编号有序保存用户归属的既有路由表，不另存资金余额。 */
    private final java.util.NavigableMap<Long, Long> pendingTransferUsers = new java.util.TreeMap<>();
    /** 已经处理页的最后编号，下一次查询取其后尾部。 */
    private long pendingTransferQueryAfterId;

    /**
     * 检查待完成划转路由条目是否低于固定上限，线程访问约束由外层调用边界保证。
     */
    public boolean hasPendingTransferCapacity() {
        return pendingTransferUsers.size() < MAX_PENDING_TRANSFERS;
    }

    /**
     * 返回当前待完成划转路由条目数，不等同资金金额。
     */
    public int pendingTransferCount() {
        return pendingTransferUsers.size();
    }

    /**
     * 按划转编号读取已登记用户，缺失返回零，当前方法不另做所有者线程检查。
     * @param transferId 需要查询归属的划转编号
     */
    public long pendingTransferUser(long transferId) {
        return pendingTransferUsers.getOrDefault(transferId, 0L);
    }

    /**
     * 容量足够且编号未登记时保存用户路由；编号及用户合法性由外层校验，当前只检查容量和重复。
     * @param id 待登记、移除或恢复的划转编号，外层负责业务合法性
     * @param userId 该划转所属用户编号
     */
    public void registerPendingTransferRoute(long id, long userId) {
        if (!hasPendingTransferCapacity() || pendingTransferUsers.putIfAbsent(id, userId) != null)
            throw new IllegalStateException("completed transfer route was not reserved");
    }

    /**
     * 已登记用户必须与输入相同才移除划转路由，缺失则按现有规则直接移除空项，不改变资金。
     * @param id 待登记、移除或恢复的划转编号，外层负责业务合法性
     * @param userId 该划转所属用户编号
     */
    public void completePendingTransferRoute(long id, long userId) {
        // 本次更新前完整结算记录，或当前划转已有的用户归属。
        Long previous = pendingTransferUsers.get(id);
        if (previous != null && previous != userId) throw new IllegalStateException("transfer route owner changed");
        pendingTransferUsers.remove(id);
    }

    /**
     * 一次待划转查询返回的编号、用户数组及 Lane 位图；数组由该页新建，record 不再防御复制，也不推进内部查询游标。
     * @param ids 本次返回页的新建原语划转编号数组
     * @param users 与划转编号同下标的用户数组
     * @param laneMask 本次页涉及用户账户 Lane 的联合位图
     */
    public record PendingTransferRoutes(long[] ids, long[] users, long laneMask) { }

    /**
     * 按上次查询游标后的有序尾部构造一页新数组，尾部为空才从头开始，当前页不足不补头部；可汇总用户 Lane 位图，但本方法不自行推进查询游标。
     * @param limit 本次返回页的最大条目数，须在协议上限内
     * @param laneMaskProvider 把用户编号映射到账户 Lane 位图的回调，可为空
     */
    public PendingTransferRoutes pendingTransferRoutes(int limit, java.util.function.LongUnaryOperator laneMaskProvider) {
        if (limit <= 0 || limit > com.surprising.aeron.protocol.CorePendingTransferCodec.MAX_RESULTS)
            throw new IllegalArgumentException("invalid pending transfer limit");
        // 从已处理划转编号之后开始的有序路由尾部，空时取全表。
        var tail = pendingTransferUsers.tailMap(pendingTransferQueryAfterId, false);
        if (tail.isEmpty()) tail = pendingTransferUsers;
        // 本次按页上限和全表数量预估的返回数组容量，尾部不足时随后缩短。
        int count = Math.min(limit, pendingTransferUsers.size());
        // 本次新建的划转编号返回数组，不引用内部 TreeMap 存储。
        // 本次新建的用户归属返回数组，与编号同下标。
        long[] ids = new long[count], users = new long[count];
        // 本页通过回调逐用户合并的账户 Lane 位图。
        long laneMask = 0;
        // 当前资产捕获列或返回页的有效原语下标。
        int index = 0;
        for (var /* 当前遍历到的划转编号与用户有序路由项。 */ entry : tail.entrySet()) {
            if (index == count) break;
            ids[index] = entry.getKey();
            users[index] = entry.getValue();
            if (laneMaskProvider != null) {
                laneMask |= laneMaskProvider.applyAsLong(entry.getValue());
            }
            index++;
        }
        if (index != count) {
            ids = java.util.Arrays.copyOf(ids, index);
            users = java.util.Arrays.copyOf(users, index);
        }
        return new PendingTransferRoutes(ids, users, laneMask);
    }

    /**
     * 保存已经处理页的最后划转编号，供后续有序查询选择尾部。
     * @param lastId 已经处理完成页的最后划转编号
     */
    public void completePendingTransferPage(long lastId) {
        pendingTransferQueryAfterId = lastId;
    }

    /**
     * 清空全部待划转路由并把查询游标归零，不触及资产资金和结算进度。
     */
    public void clearPendingTransfers() {
        pendingTransferUsers.clear();
        pendingTransferQueryAfterId = 0;
    }

    /**
     * 恢复边界直接写入可信划转路由，不执行正常登记的容量或重复检查。
     * @param id 待登记、移除或恢复的划转编号，外层负责业务合法性
     * @param userId 该划转所属用户编号
     */
    public void restorePendingTransferRoute(long id, long userId) {
        pendingTransferUsers.put(id, userId);
    }
}
