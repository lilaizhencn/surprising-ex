package com.surprising.aeron.service.state;

import com.surprising.aeron.service.state.instrument.CoreInstrument;
import com.surprising.aeron.service.state.settlement.FundsPosting;

import org.eclipse.collections.impl.map.mutable.primitive.IntLongHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;
import org.eclipse.collections.impl.set.mutable.primitive.IntHashSet;

import java.util.UUID;
import java.util.Arrays;

public final class TreasuryRuntime {
    private final IntLongHashMap feeBalances = new IntLongHashMap();
    private final IntLongHashMap insuranceBalances = new IntLongHashMap();
    private final IntLongHashMap insuranceDeficits = new IntLongHashMap();
    private final IntLongHashMap liquidationFeeBalances = new IntLongHashMap();
    private final IntLongHashMap fundingResidualBalances = new IntLongHashMap();
    private final IntLongHashMap roundingResidualBalances = new IntLongHashMap();
    private final IntLongHashMap clearingPnlBalances = new IntLongHashMap();
    private final IntLongHashMap fundingSettlements = new IntLongHashMap();
    private final IntLongHashMap lifecycleSettlements = new IntLongHashMap();
    private final IntObjectHashMap<FundingProgressRuntime> fundingProgress = new IntObjectHashMap<>();
    private final IntObjectHashMap<LifecycleProgressRuntime> lifecycleProgress = new IntObjectHashMap<>();
    /** Treasury 线程独占的事务前值；首次写入捕获，提交或回滚后清空并保留容量。 */
    private final RuntimeChangeBuffer<Void> assetChanges = new RuntimeChangeBuffer<>();
    private long[] feeBefore = new long[8];
    private long[] insuranceBefore = new long[8];
    private long[] deficitBefore = new long[8];
    private long[] liquidationFeeBefore = new long[8];
    private long[] fundingResidualBefore = new long[8];
    private long[] roundingResidualBefore = new long[8];
    private long[] clearingPnlBefore = new long[8];
    private final IntHashSet changedFundingSymbols = new IntHashSet();
    private final IntHashSet changedLifecycleSymbols = new IntHashSet();
    private final IntObjectHashMap<FundingState> patchFundingBefore =
            new IntObjectHashMap<>();
    private final IntObjectHashMap<LifecycleState> patchLifecycleBefore =
            new IntObjectHashMap<>();
    private volatile Thread owner;
    private boolean orderBatchMutationScope;

    void bindOwner() {
        Thread current = Thread.currentThread();
        if (owner == null) owner = current;
        else if (owner != current) throw new IllegalStateException("treasury runtime is bound to another thread");
    }

    void assertOwner() {
        if (owner != Thread.currentThread()) bindOwner();
    }

    void handoffTo(Thread successor) {
        if (successor == null) throw new IllegalArgumentException("treasury successor is required");
        assertOwner();
        owner = successor;
    }

    void releaseOwnerForHandoff() {
        if (owner != null && owner != Thread.currentThread()) {
            throw new IllegalStateException("treasury ownership can only be released by its owner");
        }
        owner = null;
    }

    public long fee(int assetId) { assertOwner(); return feeBalances.get(assetId); }
    public long insurance(int assetId) { assertOwner(); return insuranceBalances.get(assetId); }
    public long insuranceDeficit(int assetId) { assertOwner(); return insuranceDeficits.get(assetId); }
    public long deficit(int assetId) { assertOwner(); return insuranceDeficits.get(assetId); }
    public long liquidationFee(int assetId) { assertOwner(); return liquidationFeeBalances.get(assetId); }
    public long fundingResidual(int assetId) { assertOwner(); return fundingResidualBalances.get(assetId); }
    public long roundingResidual(int assetId) { assertOwner(); return roundingResidualBalances.get(assetId); }
    public long clearingPnl(int assetId) { assertOwner(); return clearingPnlBalances.get(assetId); }
    public long fundingSettlement(int symbolId) { assertOwner(); return fundingSettlements.get(symbolId); }
    public FundingProgressRuntime fundingProgress(int symbolId) { assertOwner(); return fundingProgress.get(symbolId); }
    public long lifecycleSettlement(int symbolId) { assertOwner(); return lifecycleSettlements.get(symbolId); }
    public LifecycleProgressRuntime lifecycleProgress(int symbolId) { assertOwner(); return lifecycleProgress.get(symbolId); }

    void beginOrderBatchMutationScope() {
        assertOwner();
        if (orderBatchMutationScope) throw new IllegalStateException("Treasury order batch scope is already active");
        orderBatchMutationScope = true;
    }

    void endOrderBatchMutationScope() {
        assertOwner();
        orderBatchMutationScope = false;
    }

    private void rejectNonAssetOrderBatchMutation(String domain) {
        if (orderBatchMutationScope) throw new IllegalStateException("order batch cannot mutate " + domain);
    }

    public int assetLedgerEntryCount() {
        assertOwner();
        return Math.addExact(Math.addExact(Math.addExact(feeBalances.size(), insuranceBalances.size()),
                        Math.addExact(insuranceDeficits.size(), liquidationFeeBalances.size())),
                Math.addExact(Math.addExact(fundingResidualBalances.size(), roundingResidualBalances.size()),
                        clearingPnlBalances.size()));
    }

    public void setFee(int assetId, long units) {
        assertOwner();
        captureAssetBefore(assetId);
        setSigned(feeBalances, assetId, units);
    }

    public void setInsurance(int assetId, long units, long deficit) {
        assertOwner();
        captureAssetBefore(assetId);
        setSigned(insuranceBalances, assetId, units);
        setSigned(insuranceDeficits, assetId, deficit);
    }

    public void adjustInsurance(int assetId, long deltaUnits) {
        assertOwner();
        long units = Math.addExact(insurance(assetId), deltaUnits);
        captureAssetBefore(assetId);
        setSigned(insuranceBalances, assetId, units);
    }

    public void setLiquidationFee(int assetId, long units) {
        assertOwner();
        captureAssetBefore(assetId);
        setSigned(liquidationFeeBalances, assetId, units);
    }

    public void setFundingResidual(int assetId, long units) {
        assertOwner();
        captureAssetBefore(assetId);
        setSigned(fundingResidualBalances, assetId, units);
    }

    public void setRoundingResidual(int assetId, long units) {
        assertOwner();
        captureAssetBefore(assetId);
        setSigned(roundingResidualBalances, assetId, units);
    }

    public void setClearingPnl(int assetId, long units) {
        assertOwner();
        captureAssetBefore(assetId);
        setSigned(clearingPnlBalances, assetId, units);
    }

    public void setDeficit(int assetId, long units) {
        assertOwner();
        captureAssetBefore(assetId);
        setSigned(insuranceDeficits, assetId, units);
    }

    public void setFundingSettlement(int symbolId, long settlementId) {
        assertOwner();
        rejectNonAssetOrderBatchMutation("Treasury funding state");
        if (symbolId < 0 || settlementId <= 0) {
            throw new IllegalArgumentException("invalid runtime funding settlement");
        }
        captureFundingBefore(symbolId);
        fundingSettlements.put(symbolId, settlementId);
        fundingProgress.remove(symbolId);
        changedFundingSymbols.add(symbolId);
    }

    public void setFundingProgress(int symbolId, FundingProgressRuntime progress) {
        assertOwner();
        rejectNonAssetOrderBatchMutation("Treasury funding state");
        if (symbolId < 0 || progress == null) {
            throw new IllegalArgumentException("invalid runtime funding progress");
        }
        captureFundingBefore(symbolId);
        fundingProgress.put(symbolId, progress);
        changedFundingSymbols.add(symbolId);
    }

    public void setLifecycleSettlement(int symbolId, long settlementId) {
        assertOwner();
        rejectNonAssetOrderBatchMutation("Treasury lifecycle state");
        if (symbolId < 0 || settlementId <= 0) throw new IllegalArgumentException("invalid lifecycle settlement");
        captureLifecycleBefore(symbolId);
        lifecycleSettlements.put(symbolId, settlementId);
        lifecycleProgress.remove(symbolId);
        changedLifecycleSymbols.add(symbolId);
    }

    public void setLifecycleProgress(int symbolId, LifecycleProgressRuntime progress) {
        assertOwner();
        rejectNonAssetOrderBatchMutation("Treasury lifecycle state");
        if (symbolId < 0 || progress == null) throw new IllegalArgumentException("invalid lifecycle progress");
        captureLifecycleBefore(symbolId);
        lifecycleProgress.put(symbolId, progress);
        changedLifecycleSymbols.add(symbolId);
    }

    IntHashSet changedAssets() {
        assertOwner();
        IntHashSet result = new IntHashSet();
        for (int index = 0; index < assetChanges.size(); index++) {
            result.add((int) assetChanges.keyAt(index));
        }
        return result;
    }

    boolean hasChangedValues() {
        assertOwner();
        return !assetChanges.isEmpty() || !changedFundingSymbols.isEmpty() || !changedLifecycleSymbols.isEmpty();
    }

    IntHashSet changedFundingSymbols() {
        assertOwner();
        return new IntHashSet(changedFundingSymbols);
    }

    IntHashSet changedLifecycleSymbols() {
        assertOwner();
        return new IntHashSet(changedLifecycleSymbols);
    }

    void clearChangedKeys() {
        assertOwner();
        assetChanges.clear();
        if (!changedFundingSymbols.isEmpty()) changedFundingSymbols.clear();
        if (!changedLifecycleSymbols.isEmpty()) changedLifecycleSymbols.clear();
        if (!patchFundingBefore.isEmpty()) patchFundingBefore.clear();
        if (!patchLifecycleBefore.isEmpty()) patchLifecycleBefore.clear();
    }

    AssetState patchAssetBefore(int assetId) {
        assertOwner();
        int index = assetChanges.indexOf(assetId);
        if (index < 0 || (feeBefore[index] | insuranceBefore[index] | deficitBefore[index]
                | liquidationFeeBefore[index] | fundingResidualBefore[index]
                | roundingResidualBefore[index] | clearingPnlBefore[index]) == 0) return null;
        return new AssetState(feeBefore[index], insuranceBefore[index], deficitBefore[index],
                liquidationFeeBefore[index], fundingResidualBefore[index], roundingResidualBefore[index],
                clearingPnlBefore[index]);
    }

    /** 由账本所有者读取前后原语值；热路径不物化 AssetState 或变更资产集合。 */
    void appendFundsDelta(RuntimeFundsAccumulator accumulator) {
        assertOwner();
        if (accumulator == null) throw new IllegalArgumentException("funds accumulator is required");
        for (int index = 0; index < assetChanges.size(); index++) {
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

    FundingState patchFundingBefore(int symbolId) {
        assertOwner();
        return patchFundingBefore.get(symbolId);
    }

    LifecycleState patchLifecycleBefore(int symbolId) {
        assertOwner();
        return patchLifecycleBefore.get(symbolId);
    }

    void rollbackChangedValues() {
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
        for (int symbolId : changedFundingSymbols.toArray()) {
            FundingState before = patchFundingBefore.get(symbolId);
            restoreSigned(fundingSettlements, symbolId, before == null ? 0 : before.settlementId());
            if (before == null || before.progress() == null) fundingProgress.remove(symbolId);
            else fundingProgress.put(symbolId, before.progress());
        }
        for (int symbolId : changedLifecycleSymbols.toArray()) {
            LifecycleState before = patchLifecycleBefore.get(symbolId);
            restoreSigned(lifecycleSettlements, symbolId, before == null ? 0 : before.settlementId());
            if (before == null || before.progress() == null) lifecycleProgress.remove(symbolId);
            else lifecycleProgress.put(symbolId, before.progress());
        }
        clearChangedKeys();
    }

    public void clear() {
        assertOwner();
        feeBalances.clear();
        insuranceBalances.clear();
        insuranceDeficits.clear();
        liquidationFeeBalances.clear();
        fundingResidualBalances.clear();
        roundingResidualBalances.clear();
        clearingPnlBalances.clear();
        fundingSettlements.clear();
        lifecycleSettlements.clear();
        fundingProgress.clear();
        lifecycleProgress.clear();
    }

    public IntLongHashMap feeBalances() { assertOwner(); return new IntLongHashMap(feeBalances); }
    public IntLongHashMap insuranceBalances() { assertOwner(); return new IntLongHashMap(insuranceBalances); }
    public IntLongHashMap insuranceDeficits() { assertOwner(); return new IntLongHashMap(insuranceDeficits); }
    public IntLongHashMap deficitBalances() { assertOwner(); return new IntLongHashMap(insuranceDeficits); }
    public IntLongHashMap liquidationFeeBalances() { assertOwner(); return new IntLongHashMap(liquidationFeeBalances); }
    public IntLongHashMap fundingResidualBalances() { assertOwner(); return new IntLongHashMap(fundingResidualBalances); }
    public IntLongHashMap roundingResidualBalances() { assertOwner(); return new IntLongHashMap(roundingResidualBalances); }
    public IntLongHashMap clearingPnlBalances() { assertOwner(); return new IntLongHashMap(clearingPnlBalances); }
    public IntLongHashMap fundingSettlements() { assertOwner(); return new IntLongHashMap(fundingSettlements); }
    public IntObjectHashMap<FundingProgressRuntime> fundingProgresses() {
        assertOwner();
        return new IntObjectHashMap<>(fundingProgress);
    }

    public int incompleteFundingCount() {
        assertOwner();
        return fundingProgress.size();
    }
    public IntLongHashMap lifecycleSettlements() { assertOwner(); return new IntLongHashMap(lifecycleSettlements); }
    public IntObjectHashMap<LifecycleProgressRuntime> lifecycleProgresses() {
        assertOwner();
        return new IntObjectHashMap<>(lifecycleProgress);
    }

    private static void setSigned(IntLongHashMap balances, int assetId, long units) {
        if (assetId < 0) throw new IllegalArgumentException("invalid treasury asset");
        if (units == 0) balances.remove(assetId); else balances.put(assetId, units);
    }

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
        long settlementId = fundingSettlements.get(symbolId);
        FundingProgressRuntime progress = fundingProgress.get(symbolId);
        if (settlementId != 0 || progress != null) {
            patchFundingBefore.put(symbolId,
                    new FundingState(settlementId, progress));
        }
    }

    private void captureLifecycleBefore(int symbolId) {
        if (changedLifecycleSymbols.contains(symbolId)) return;
        long settlementId = lifecycleSettlements.get(symbolId);
        LifecycleProgressRuntime progress = lifecycleProgress.get(symbolId);
        if (settlementId != 0 || progress != null) {
            patchLifecycleBefore.put(symbolId,
                    new LifecycleState(settlementId, progress));
        }
    }

    record AssetState(long fee, long insurance, long deficit, long liquidationFee,
                      long fundingResidual, long roundingResidual, long clearingPnl) { }

    record FundingState(long settlementId, FundingProgressRuntime progress) { }

    record LifecycleState(long settlementId, LifecycleProgressRuntime progress) { }

    public record FundingProgressRuntime(long settlementId, CoreInstrument instrument, long fundingRatePpm,
                                         int accountLaneId, long nextCursorUserId, UUID commandId,
                                         long markPriceTicks, long priceSequence) {
        public FundingProgressRuntime {
            if (settlementId <= 0 || instrument == null || Math.absExact(fundingRatePpm) > 1_000_000
                    || accountLaneId < 0 || accountLaneId >= Long.SIZE
                    || nextCursorUserId < 0 || commandId == null || markPriceTicks <= 0 || priceSequence <= 0) {
                throw new IllegalArgumentException("invalid runtime funding progress");
            }
        }
    }

    public record LifecycleProgressRuntime(long settlementId, CoreInstrument instrument, long settlementPriceTicks,
                                           long optionCashUnitsPerContract, boolean ordersComplete,
                                           int accountLaneId, long nextCursorOrderId,
                                           long nextCursorUserId, UUID commandId, long requiredInsuranceUnits) {
        public LifecycleProgressRuntime(long settlementId, CoreInstrument instrument, long settlementPriceTicks,
                                        long optionCashUnitsPerContract, boolean ordersComplete, int accountLaneId,
                                        long nextCursorOrderId, long nextCursorUserId, UUID commandId) {
            this(settlementId, instrument, settlementPriceTicks, optionCashUnitsPerContract, ordersComplete,
                    accountLaneId, nextCursorOrderId, nextCursorUserId, commandId, 0);
        }
        public LifecycleProgressRuntime {
            if (settlementId <= 0 || instrument == null || settlementPriceTicks < 0
                    || requiredInsuranceUnits < 0 || requiredInsuranceUnits > 0 && !ordersComplete
                    || optionCashUnitsPerContract < 0 || accountLaneId < 0 || accountLaneId >= Long.SIZE
                    || nextCursorOrderId < 0 || nextCursorUserId < 0
                    || (!ordersComplete && nextCursorUserId != 0) || (ordersComplete && nextCursorOrderId != 0)
                    || commandId == null) throw new IllegalArgumentException("invalid lifecycle progress");
        }
        public LifecycleProgressRuntime(long settlementId, CoreInstrument instrument, long settlementPriceTicks,
                                        long optionCashUnitsPerContract, boolean ordersComplete,
                                        long nextCursorOrderId, long nextCursorUserId, UUID commandId) {
            this(settlementId, instrument, settlementPriceTicks, optionCashUnitsPerContract,
                    ordersComplete, 0, nextCursorOrderId, nextCursorUserId, commandId);
        }
    }
}
