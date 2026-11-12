package com.surprising.aeron.service.treasury;

import com.surprising.aeron.service.core.change.StateChangeBuffer;
import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.aeron.service.settlement.TradeSettlement;
import java.util.Arrays;
import org.eclipse.collections.impl.map.mutable.primitive.IntLongHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;
import org.eclipse.collections.impl.set.mutable.primitive.IntHashSet;

/** 系统资金权威运行时。资产余额由当前账本所有者读写，首次变更保存七列前值供提交核对和回滚。币对结算进度以 volatile 单元发布只读记录，准入可以跨线程读取这些记录。待划转路由由外层既有所有权约束，本类路由方法不另做线程检查。 */
public final class SystemFunds {
    /** 当前账本所有者独占的手续费有符号余额表。 */
    private final IntLongHashMap feeBalances = new IntLongHashMap();
    private final IntLongHashMap insuranceBalances = new IntLongHashMap();
    private final IntLongHashMap insuranceDeficits = new IntLongHashMap();
    private final IntLongHashMap liquidationFeeBalances = new IntLongHashMap();
    private final IntLongHashMap fundingResidualBalances = new IntLongHashMap();
    /** 舍入残差余额表。 */
    private final IntLongHashMap roundingResidualBalances = new IntLongHashMap();
    private final IntLongHashMap clearingPnlBalances = new IntLongHashMap();
    /** 按稠密币对编号索引的 volatile 发布单元数组，扩容只复制单元引用，旧读者仍见同一单元。 */
    private volatile SettlementStatusCell[] settlementStates = new SettlementStatusCell[8];
    private final StateChangeBuffer<Void> assetChanges = new StateChangeBuffer<>();
    private long[] feeBefore = new long[8];
    private long[] insuranceBefore = new long[8];
    private long[] deficitBefore = new long[8];
    private long[] liquidationFeeBefore = new long[8];
    private long[] fundingResidualBefore = new long[8];
    /** 对应捕获槽的首个舍入残差前值。 */
    private long[] roundingResidualBefore = new long[8];
    private long[] clearingPnlBefore = new long[8];
    private final IntHashSet changedFundingSymbols = new IntHashSet();
    private final IntHashSet changedLifecycleSymbols = new IntHashSet();
    private final IntObjectHashMap<FundingState> patchFundingBefore =
            new IntObjectHashMap<>();
    private final IntObjectHashMap<LifecycleState> patchLifecycleBefore =
            new IntObjectHashMap<>();
    /** 当前允许访问资金 Map 的所有者线程，volatile 交接不代替调用方停止在途访问。 */
    private volatile Thread owner;
    private boolean orderBatchMutationScope;

    /** 未绑定时认领当前线程，已绑定时要求同一线程。调用方负责交接期间无并发访问，这不是多线程争抢所有权的锁。 */
    void assertOwner() {
        Thread current = Thread.currentThread();
        if (owner == null) owner = current;
        else if (owner != current) throw new IllegalStateException("treasury runtime is bound to another thread");
    }

    /** 当前所有者显式解除绑定，下一执行方可在既有交接边界认领。 */
    public void releaseOwnerForHandoff() {
        assertOwner();
        owner = null;
    }

    /** 当前所有者读取指定资产手续费余额，缺失按原语 Map 返回零。 */
    public long fee(int assetId) { assertOwner(); return feeBalances.get(assetId); }
    /** 当前所有者读取指定资产保险基金余额。 */
    public long insurance(int assetId) { assertOwner(); return insuranceBalances.get(assetId); }
    /** 当前所有者读取指定资产保险亏空余额。 */
    public long insuranceDeficit(int assetId) { assertOwner(); return insuranceDeficits.get(assetId); }
    /** 当前所有者读取指定资产独立强平费余额。 */
    public long liquidationFee(int assetId) { assertOwner(); return liquidationFeeBalances.get(assetId); }
    /** 当前所有者读取指定资产资金费残差余额。 */
    public long fundingResidual(int assetId) { assertOwner(); return fundingResidualBalances.get(assetId); }
    /** 当前所有者读取指定资产舍入残差余额。 */
    public long roundingResidual(int assetId) { assertOwner(); return roundingResidualBalances.get(assetId); }
    /** 当前所有者读取指定资产系统清算盈亏余额。 */
    public long clearingPnl(int assetId) { assertOwner(); return clearingPnlBalances.get(assetId); }
    /** 从已发布币对结算记录读取最近完成资金费编号，未记录时为零，不访问可变资金 Map。 */
    public long fundingSettlement(int symbolId) { return settlementStatus(symbolId).fundingSettlementId(); }
    /** 借用已发布资金费分页进度，空值表示当前无该进度，可供准入读取。 */
    public FundingProgressRuntime fundingProgress(int symbolId) { return settlementStatus(symbolId).fundingProgress(); }
    /** 从已发布记录读取最近完成的交割或行权结算编号，未记录为零。 */
    public long lifecycleSettlement(int symbolId) { return settlementStatus(symbolId).lifecycleSettlementId(); }
    /** 借用已发布生命周期分页进度，不复制或读取账本可变资金表。 */
    public LifecycleProgressRuntime lifecycleProgress(int symbolId) { return settlementStatus(symbolId).lifecycleProgress(); }

    /** 当前所有者进入只允许资产变化的订单批次范围，重复进入拒绝。 */
    public void beginOrderBatchMutationScope() {
        assertOwner();
        if (orderBatchMutationScope) throw new IllegalStateException("Treasury order batch scope is already active");
        orderBatchMutationScope = true;
    }

    /** 当前所有者结束订单批次范围。此处不提交、清空或回滚已捕获变化。 */
    public void endOrderBatchMutationScope() {
        assertOwner();
        orderBatchMutationScope = false;
    }

    private void rejectNonAssetOrderBatchMutation(String domain) {
        if (orderBatchMutationScope) throw new IllegalStateException("order batch cannot mutate " + domain);
    }

    /** 精确加总七类资金 Map 的条目数，同一资产在多类中分别计数，不是不同资产总数。 */
    public int assetLedgerEntryCount() {
        assertOwner();
        return Math.addExact(Math.addExact(Math.addExact(feeBalances.size(), insuranceBalances.size()),
                        Math.addExact(insuranceDeficits.size(), liquidationFeeBalances.size())),
                Math.addExact(Math.addExact(fundingResidualBalances.size(), roundingResidualBalances.size()),
                        clearingPnlBalances.size()));
    }

    /** 当前所有者先捕获该资产本范围首个前值，再设置有符号手续费余额，零值从 Map 移除。 */
    public void setFee(int assetId, long units) {
        assertOwner();
        captureAssetBefore(assetId);
        setSigned(feeBalances, assetId, units);
    }

    /** 先一次捕获该资产全部资金前值，再同时设置有符号保险余额和亏空余额，零值移除。 */
    public void setInsurance(int assetId, long units, long deficit) {
        assertOwner();
        captureAssetBefore(assetId);
        setSigned(insuranceBalances, assetId, units);
        setSigned(insuranceDeficits, assetId, deficit);
    }

    /** 精确计算保险余额加本次变化，再捕获前值并写入，不在此调整其他子账。 */
    public void adjustInsurance(int assetId, long deltaUnits) {
        assertOwner();
        long units = Math.addExact(insurance(assetId), deltaUnits);
        captureAssetBefore(assetId);
        setSigned(insuranceBalances, assetId, units);
    }

    /** 捕获本资产首次前值，再设置独立强平费余额，零值移除。 */
    public void setLiquidationFee(int assetId, long units) {
        assertOwner();
        captureAssetBefore(assetId);
        setSigned(liquidationFeeBalances, assetId, units);
    }

    /** 捕获本资产首次前值，再设置资金费残差余额。 */
    public void setFundingResidual(int assetId, long units) {
        assertOwner();
        captureAssetBefore(assetId);
        setSigned(fundingResidualBalances, assetId, units);
    }

    /** 捕获本资产首次前值，再设置舍入残差余额。 */
    public void setRoundingResidual(int assetId, long units) {
        assertOwner();
        captureAssetBefore(assetId);
        setSigned(roundingResidualBalances, assetId, units);
    }

    /** 捕获本资产首次前值，再设置系统清算盈亏余额。 */
    public void setClearingPnl(int assetId, long units) {
        assertOwner();
        captureAssetBefore(assetId);
        setSigned(clearingPnlBalances, assetId, units);
    }

    /** 禁止订单批次内修改，检查币对及正结算编号，捕获首个资金费前值，再发布完成编号并清除该币对资金费进度，保留生命周期记录。 */
    public void setFundingSettlement(int symbolId, long settlementId) {
        assertOwner();
        rejectNonAssetOrderBatchMutation("Treasury funding state");
        if (symbolId < 0 || settlementId <= 0) {
            throw new IllegalArgumentException("invalid runtime funding settlement");
        }
        captureFundingBefore(symbolId);
        SettlementStatus previous = settlementStatus(symbolId);
        publishSettlementStatus(symbolId, new SettlementStatus(settlementId, null,
                previous.lifecycleSettlementId(), previous.lifecycleProgress()));
        changedFundingSymbols.add(symbolId);
    }

    /** 检查币对与非空资金费进度，捕获首个前值，再发布新进度并保留完成编号和生命周期记录，登记变更币对。 */
    public void setFundingProgress(int symbolId, FundingProgressRuntime progress) {
        assertOwner();
        rejectNonAssetOrderBatchMutation("Treasury funding state");
        if (symbolId < 0 || progress == null) {
            throw new IllegalArgumentException("invalid runtime funding progress");
        }
        captureFundingBefore(symbolId);
        SettlementStatus previous = settlementStatus(symbolId);
        publishSettlementStatus(symbolId, new SettlementStatus(previous.fundingSettlementId(), progress,
                previous.lifecycleSettlementId(), previous.lifecycleProgress()));
        changedFundingSymbols.add(symbolId);
    }

    /** 检查并捕获生命周期前值，发布正完成编号并清除生命周期进度，资金费部分保持原值。 */
    public void setLifecycleSettlement(int symbolId, long settlementId) {
        assertOwner();
        rejectNonAssetOrderBatchMutation("Treasury lifecycle state");
        if (symbolId < 0 || settlementId <= 0) throw new IllegalArgumentException("invalid lifecycle settlement");
        captureLifecycleBefore(symbolId);
        SettlementStatus previous = settlementStatus(symbolId);
        publishSettlementStatus(symbolId, new SettlementStatus(previous.fundingSettlementId(),
                previous.fundingProgress(), settlementId, null));
        changedLifecycleSymbols.add(symbolId);
    }

    /** 检查并捕获生命周期前值，发布新生命周期进度并保留原完成编号及资金费记录。 */
    public void setLifecycleProgress(int symbolId, LifecycleProgressRuntime progress) {
        assertOwner();
        rejectNonAssetOrderBatchMutation("Treasury lifecycle state");
        if (symbolId < 0 || progress == null) throw new IllegalArgumentException("invalid lifecycle progress");
        captureLifecycleBefore(symbolId);
        SettlementStatus previous = settlementStatus(symbolId);
        publishSettlementStatus(symbolId, new SettlementStatus(previous.fundingSettlementId(),
                previous.fundingProgress(), previous.lifecycleSettlementId(), progress));
        changedLifecycleSymbols.add(symbolId);
    }

    /** 检查资产、资金费或生命周期是否有已登记变化键，不逐项比较最终值与前值。 */
    public boolean hasChangedValues() {
        assertOwner();
        return !assetChanges.isEmpty() || !changedFundingSymbols.isEmpty() || !changedLifecycleSymbols.isEmpty();
    }

    /** 清除当前范围变化键及进度前值引用，保留资产原语前值数组容量。 */
    public void clearChangedKeys() {
        assertOwner();
        assetChanges.clear();
        if (!changedFundingSymbols.isEmpty()) changedFundingSymbols.clear();
        if (!changedLifecycleSymbols.isEmpty()) changedLifecycleSymbols.clear();
        if (!patchFundingBefore.isEmpty()) patchFundingBefore.clear();
        if (!patchLifecycleBefore.isEmpty()) patchLifecycleBefore.clear();
    }

    /** 当前所有者直接比较七列权威余额与首次前值，向原语缓冲追加 Treasury 分录。 */
    public void appendFundsDelta(FundsTotal accumulator) {
        assertOwner();
        if (accumulator == null) throw new IllegalArgumentException("funds accumulator is required");
        for (int index = 0; index < assetChanges.size(); index++) {
            int assetId = (int) assetChanges.keyAt(index);
            accumulator.add(assetId, TradeSettlement.Posting.OwnerKind.TREASURY, 0, TradeSettlement.Posting.Subledger.FEE,
                    Math.subtractExact(feeBalances.get(assetId), feeBefore[index]));
            accumulator.add(assetId, TradeSettlement.Posting.OwnerKind.TREASURY, 0, TradeSettlement.Posting.Subledger.INSURANCE,
                    Math.subtractExact(insuranceBalances.get(assetId), insuranceBefore[index]));
            accumulator.add(assetId, TradeSettlement.Posting.OwnerKind.TREASURY, 0, TradeSettlement.Posting.Subledger.DEFICIT,
                    Math.negateExact(Math.subtractExact(insuranceDeficits.get(assetId), deficitBefore[index])));
            accumulator.add(assetId, TradeSettlement.Posting.OwnerKind.TREASURY, 0, TradeSettlement.Posting.Subledger.LIQUIDATION_FEE,
                    Math.subtractExact(liquidationFeeBalances.get(assetId), liquidationFeeBefore[index]));
            accumulator.add(assetId, TradeSettlement.Posting.OwnerKind.TREASURY, 0, TradeSettlement.Posting.Subledger.FUNDING_RESIDUAL,
                    Math.subtractExact(fundingResidualBalances.get(assetId), fundingResidualBefore[index]));
            accumulator.add(assetId, TradeSettlement.Posting.OwnerKind.TREASURY, 0, TradeSettlement.Posting.Subledger.ROUNDING_RESIDUAL,
                    Math.subtractExact(roundingResidualBalances.get(assetId), roundingResidualBefore[index]));
            accumulator.add(assetId, TradeSettlement.Posting.OwnerKind.TREASURY, 0, TradeSettlement.Posting.Subledger.CLEARING_PNL,
                    Math.subtractExact(clearingPnlBalances.get(assetId), clearingPnlBefore[index]));
        }
    }

    /** 借用当前范围捕获的资金费前值记录，原来无编号及进度时可能为空。 */
    FundingState patchFundingBefore(int symbolId) {
        assertOwner();
        return patchFundingBefore.get(symbolId);
    }

    /** 借用当前范围捕获的生命周期前值记录，可能为空。 */
    LifecycleState patchLifecycleBefore(int symbolId) {
        assertOwner();
        return patchLifecycleBefore.get(symbolId);
    }

    /** 恢复捕获资产七列首值及变化币对的资金费和生命周期前值，重新发布进度以同步数量索引，最后清理本范围变化键。 */
    public void rollbackChangedValues() {
        assertOwner();
        for (int index = 0; index < assetChanges.size(); index++) {
            int assetId = (int) assetChanges.keyAt(index);
            restoreSigned(feeBalances, assetId, feeBefore[index]);
            restoreSigned(insuranceBalances, assetId, insuranceBefore[index]);
            restoreSigned(insuranceDeficits, assetId, deficitBefore[index]);
            restoreSigned(liquidationFeeBalances, assetId, liquidationFeeBefore[index]);
            restoreSigned(fundingResidualBalances, assetId, fundingResidualBefore[index]);
            restoreSigned(roundingResidualBalances, assetId, roundingResidualBefore[index]);
            restoreSigned(clearingPnlBalances, assetId, clearingPnlBefore[index]);
        }
        var fundingSymbols = changedFundingSymbols.intIterator();
        while (fundingSymbols.hasNext()) {
            int symbolId = fundingSymbols.next();
            FundingState before = patchFundingBefore.get(symbolId);
            SettlementStatus current = settlementStatus(symbolId);
            publishSettlementStatus(symbolId, new SettlementStatus(before == null ? 0 : before.settlementId(),
                    before == null ? null : before.progress(), current.lifecycleSettlementId(),
                    current.lifecycleProgress()));
        }
        var lifecycleSymbols = changedLifecycleSymbols.intIterator();
        while (lifecycleSymbols.hasNext()) {
            int symbolId = lifecycleSymbols.next();
            LifecycleState before = patchLifecycleBefore.get(symbolId);
            SettlementStatus current = settlementStatus(symbolId);
            publishSettlementStatus(symbolId, new SettlementStatus(current.fundingSettlementId(),
                    current.fundingProgress(), before == null ? 0 : before.settlementId(),
                    before == null ? null : before.progress()));
        }
        clearChangedKeys();
    }

    /** 当前所有者借用权威手续费原语 Map。 */
    public IntLongHashMap feeBalances() { assertOwner(); return feeBalances; }
    /** 当前所有者借用权威保险余额 Map，不复制内容或自动捕获直接写入。 */
    public IntLongHashMap insuranceBalances() { assertOwner(); return insuranceBalances; }
    /** 当前所有者借用权威保险亏空 Map。 */
    public IntLongHashMap insuranceDeficits() { assertOwner(); return insuranceDeficits; }
    /** 当前所有者借用独立强平费余额 Map。 */
    public IntLongHashMap liquidationFeeBalances() { assertOwner(); return liquidationFeeBalances; }
    /** 当前所有者借用资金费残差余额 Map。 */
    public IntLongHashMap fundingResidualBalances() { assertOwner(); return fundingResidualBalances; }
    /** 当前所有者借用舍入残差余额 Map。 */
    public IntLongHashMap roundingResidualBalances() { assertOwner(); return roundingResidualBalances; }
    /** 当前所有者借用系统清算盈亏余额 Map。 */
    public IntLongHashMap clearingPnlBalances() { assertOwner(); return clearingPnlBalances; }
    /** 当前所有者遍历发布单元，物化非零资金费完成编号的新 Map，作为导出或恢复边界值。 */
    public IntLongHashMap fundingSettlements() {
        assertOwner();
        IntLongHashMap result = new IntLongHashMap();
        SettlementStatusCell[] states = settlementStates;
        for (int symbolId = 0; symbolId < states.length; symbolId++) {
            SettlementStatusCell cell = states[symbolId];
            if (cell != null && cell.value.fundingSettlementId() != 0)
                result.put(symbolId, cell.value.fundingSettlementId());
        }
        return result;
    }
    /** 当前所有者物化有资金费进度币对的新 Map，进度记录本身继续共享只读值。 */
    public IntObjectHashMap<FundingProgressRuntime> fundingProgresses() {
        assertOwner();
        IntObjectHashMap<FundingProgressRuntime> result = new IntObjectHashMap<>();
        SettlementStatusCell[] states = settlementStates;
        for (int symbolId = 0; symbolId < states.length; symbolId++) {
            SettlementStatusCell cell = states[symbolId];
            if (cell != null && cell.value.fundingProgress() != null)
                result.put(symbolId, cell.value.fundingProgress());
        }
        return result;
    }

    /** 当前所有者物化非零生命周期完成编号的新 Map。 */
    public IntLongHashMap lifecycleSettlements() {
        assertOwner();
        IntLongHashMap result = new IntLongHashMap();
        SettlementStatusCell[] states = settlementStates;
        for (int symbolId = 0; symbolId < states.length; symbolId++) {
            SettlementStatusCell cell = states[symbolId];
            if (cell != null && cell.value.lifecycleSettlementId() != 0)
                result.put(symbolId, cell.value.lifecycleSettlementId());
        }
        return result;
    }
    /** 当前所有者物化有生命周期进度币对的新 Map。 */
    public IntObjectHashMap<LifecycleProgressRuntime> lifecycleProgresses() {
        assertOwner();
        IntObjectHashMap<LifecycleProgressRuntime> result = new IntObjectHashMap<>();
        SettlementStatusCell[] states = settlementStates;
        for (int symbolId = 0; symbolId < states.length; symbolId++) {
            SettlementStatusCell cell = states[symbolId];
            if (cell != null && cell.value.lifecycleProgress() != null)
                result.put(symbolId, cell.value.lifecycleProgress());
        }
        return result;
    }

    /** 借用对应单元已发布的只读结算记录，越界、负编号或未建单元返回共用空值，允许准入线程读取。 */
    public SettlementStatus settlementStatus(int symbolId) {
        SettlementStatusCell[] states = settlementStates;
        if (symbolId < 0 || symbolId >= states.length) return SettlementStatus.EMPTY;
        SettlementStatusCell cell = states[symbolId];
        return cell == null ? SettlementStatus.EMPTY : cell.value;
    }

    /** 账本所有者按需扩容发布单元数组，创建单元时重新 volatile 发布数组，再替换单元只读值。 */
    private void publishSettlementStatus(int symbolId, SettlementStatus value) {
        SettlementStatusCell[] states = settlementStates;
        if (symbolId >= states.length) {
            int capacity = Math.max(Math.addExact(symbolId, 1), Math.multiplyExact(states.length, 2));
            states = Arrays.copyOf(states, capacity);
        }
        SettlementStatusCell cell = states[symbolId];
        if (cell == null) {
            cell = new SettlementStatusCell();
            states[symbolId] = cell;
            // 新单元需要通过 volatile 数组引用发布，即使本次没有扩容也一样。
            settlementStates = states;
        }
        cell.value = value;
    }

    /** 按币对长期保留的结算发布单元，volatile 字段指向只读记录，旧数组读者与新数组读者共享同一单元。 */
    private static final class SettlementStatusCell {
        /** 本币对最近发布的只读结算记录，volatile 替换供准入观察。 */
        private volatile SettlementStatus value = SettlementStatus.EMPTY;
    }

    /** 一份币对资金费及生命周期已完成编号与进行中进度的只读记录，账本替换发布，准入只读取。 */
    public record SettlementStatus(long fundingSettlementId, FundingProgressRuntime fundingProgress,
                                   long lifecycleSettlementId, LifecycleProgressRuntime lifecycleProgress) {
        private static final SettlementStatus EMPTY = new SettlementStatus(0, null, 0, null);
        /** 当前记录生命周期完成编号非零时返回 true，不根据分页进度另行计算。 */
        public boolean lifecycleSettled() { return lifecycleSettlementId != 0; }
        /** 当前记录存在非空资金费分页进度时返回 true。 */
        public boolean fundingInProgress() { return fundingProgress != null; }
    }

    private static void setSigned(IntLongHashMap balances, int assetId, long units) {
        if (assetId < 0) throw new IllegalArgumentException("invalid treasury asset");
        if (units == 0) balances.remove(assetId); else balances.put(assetId, units);
    }

    /** 按已捕获可信键值直接恢复原语 Map，零值删除，不再次执行公开写入捕获。 */
    private static void restoreSigned(IntLongHashMap values, int key, long value) {
        if (value == 0) values.remove(key); else values.put(key, value);
    }

    private void captureAssetBefore(int assetId) {
        if (assetId < 0) throw new IllegalArgumentException("invalid treasury asset");
        if (assetChanges.containsKey(assetId)) return;
        if (assetChanges.size() == feeBefore.length) {
            int capacity = Math.multiplyExact(feeBefore.length, 2);
            feeBefore = Arrays.copyOf(feeBefore, capacity);
            insuranceBefore = Arrays.copyOf(insuranceBefore, capacity);
            deficitBefore = Arrays.copyOf(deficitBefore, capacity);
            liquidationFeeBefore = Arrays.copyOf(liquidationFeeBefore, capacity);
            fundingResidualBefore = Arrays.copyOf(fundingResidualBefore, capacity);
            roundingResidualBefore = Arrays.copyOf(roundingResidualBefore, capacity);
            clearingPnlBefore = Arrays.copyOf(clearingPnlBefore, capacity);
        }
        int index = assetChanges.put(assetId, null);
        feeBefore[index] = feeBalances.get(assetId);
        insuranceBefore[index] = insuranceBalances.get(assetId);
        deficitBefore[index] = insuranceDeficits.get(assetId);
        liquidationFeeBefore[index] = liquidationFeeBalances.get(assetId);
        fundingResidualBefore[index] = fundingResidualBalances.get(assetId);
        roundingResidualBefore[index] = roundingResidualBalances.get(assetId);
        clearingPnlBefore[index] = clearingPnlBalances.get(assetId);
    }

    private void captureFundingBefore(int symbolId) {
        if (changedFundingSymbols.contains(symbolId)) return;
        SettlementStatus current = settlementStatus(symbolId);
        long settlementId = current.fundingSettlementId();
        FundingProgressRuntime progress = current.fundingProgress();
        if (settlementId != 0 || progress != null) {
            patchFundingBefore.put(symbolId,
                    new FundingState(settlementId, progress));
        }
    }

    private void captureLifecycleBefore(int symbolId) {
        if (changedLifecycleSymbols.contains(symbolId)) return;
        SettlementStatus current = settlementStatus(symbolId);
        long settlementId = current.lifecycleSettlementId();
        LifecycleProgressRuntime progress = current.lifecycleProgress();
        if (settlementId != 0 || progress != null) {
            patchLifecycleBefore.put(symbolId,
                    new LifecycleState(settlementId, progress));
        }
    }

    /** 当前控制范围首次修改前的资金费完成编号及可空分页进度。 */
    record FundingState(long settlementId, FundingProgressRuntime progress) { }

    /** 当前控制范围首次修改前的生命周期完成编号及可空分页进度。 */
    record LifecycleState(long settlementId, LifecycleProgressRuntime progress) { }

    /** 资金费续页的固定费率、用户游标和计算价格。 */
    public record FundingProgressRuntime(long settlementId, CoreInstrument instrument, long fundingRatePpm,
            long nextCursorUserId, long markPriceTicks) {
        public FundingProgressRuntime {
            if (settlementId <= 0 || instrument == null || Math.absExact(fundingRatePpm) > 1_000_000
                    || nextCursorUserId < 0 || markPriceTicks <= 0) {
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

    public static final int MAX_PENDING_TRANSFERS = 131_072;
    private final java.util.NavigableMap<Long, Long> pendingTransferUsers = new java.util.TreeMap<>();
    private long pendingTransferQueryAfterId;

    /** 检查待完成划转路由条目是否低于固定上限，线程访问约束由外层调用边界保证。 */
    public boolean hasPendingTransferCapacity() {
        return pendingTransferUsers.size() < MAX_PENDING_TRANSFERS;
    }

    /** 按划转编号读取已登记用户，缺失返回零，当前方法不另做所有者线程检查。 */
    public long pendingTransferUser(long transferId) {
        return pendingTransferUsers.getOrDefault(transferId, 0L);
    }

    /** 容量足够且编号未登记时保存用户路由。 */
    public void registerPendingTransferRoute(long id, long userId) {
        if (!hasPendingTransferCapacity() || pendingTransferUsers.putIfAbsent(id, userId) != null)
            throw new IllegalStateException("completed transfer route was not reserved");
    }

    /** 已登记用户必须与输入相同才移除划转路由，缺失则按现有规则直接移除空项，不改变资金。 */
    public void completePendingTransferRoute(long id, long userId) {
        Long previous = pendingTransferUsers.get(id);
        if (previous != null && previous != userId) throw new IllegalStateException("transfer route owner changed");
        pendingTransferUsers.remove(id);
    }

    /** 一次待划转查询返回的编号、用户数组及 Lane 位图。 */
    public record PendingTransferRoutes(long[] ids, long[] users, long laneMask) { }

    /** 按上次查询游标后的有序尾部构造一页新数组，尾部为空才从头开始，当前页不足不补头部。 */
    public PendingTransferRoutes pendingTransferRoutes(int limit, java.util.function.LongUnaryOperator laneMaskProvider) {
        if (limit <= 0 || limit > com.surprising.aeron.protocol.CorePendingTransferCodec.MAX_RESULTS)
            throw new IllegalArgumentException("invalid pending transfer limit");
        var tail = pendingTransferUsers.tailMap(pendingTransferQueryAfterId, false);
        if (tail.isEmpty()) tail = pendingTransferUsers;
        int count = Math.min(limit, pendingTransferUsers.size());
        long[] ids = new long[count], users = new long[count];
        long laneMask = 0;
        int index = 0;
        for (var entry : tail.entrySet()) {
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

    /** 保存已经处理页的最后划转编号，供后续有序查询选择尾部。 */
    public void completePendingTransferPage(long lastId) {
        pendingTransferQueryAfterId = lastId;
    }

    /** 清空全部待划转路由并把查询游标归零，不触及资产资金和结算进度。 */
    public void clearPendingTransfers() {
        pendingTransferUsers.clear();
        pendingTransferQueryAfterId = 0;
    }

    /** 恢复边界直接写入可信划转路由，不执行正常登记的容量或重复检查。 */
    public void restorePendingTransferRoute(long id, long userId) {
        pendingTransferUsers.put(id, userId);
    }
}
